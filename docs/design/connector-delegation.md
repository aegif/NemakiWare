# Connector & Profile Delegation — Design Reference

Status: implemented in 3.1.1-RC3 (commit `2186a40b0`).
Audience: operators configuring External Ingestion, security reviewers,
contributors extending the delegation model.

## 1. Problem

External Ingestion in 3.1.1 lets admins wire a connector (Slack, M365,
Salesforce, Notion, …) to an Import Profile bound to a target folder, then
import content into that folder under a unified `nemaki:externalIntegration`
secondary type. Through RC2, *only* admins could create or edit profiles.
Folder owners who actually understood their folders' content scope had to
file an admin ticket for every change — slow, and admins rarely have the
domain knowledge to set policies correctly.

We want folder owners to manage their own profiles. We do **not** want
folder owners to:

- See or modify another folder's profiles.
- Use a connector that holds powerful external credentials (Slack bot
  token, Graph delegation, Salesforce API key) that the admin hasn't
  expressly authorized for them. Hiding credential bytes is not enough —
  the credential's *capability* leaks if the user can invoke the connector.
- Bypass folder ACL by retargeting an existing profile.
- Trigger scheduled fetches that run with no `CallContext`.

## 2. Boundary (v1)

> A non-admin who effectively holds `cmis:all` on folder F may
> create / edit / delete **manual-only** delegated import profiles bound
> to F, using only connectors an admin has expressly delegated. Admin
> profiles and the scheduler are untouched.

What the boundary *includes*:

- POST/PUT/DELETE/GET on `/v1/admin/import-profiles` for delegated
  profiles bound to a folder where the caller has effective `cmis:all`.
- POST `/v1/repo/{repo}/ingest` with a delegated profile.
- A new GET `/v1/admin/connectors/summary` that returns slim, secret-free
  connector entries for one folder.

What the boundary *excludes* (deliberately):

- Connector CRUD — admin only. Folder owners never see credentials.
- Scheduled ingestion — non-admin's `schedulerEnabled=true` is rejected
  with HTTP 403. The scheduler currently calls `executeFetch(null, …)`
  with no `CallContext` (see `IngestSchedulerService.java:215`); permitting
  delegated cron without redesigning that call site would mean fetches run
  unauthenticated.
- `defaultProfile=true` — affects repository-wide auto-resolution and is
  not per-folder.
- `targetFolderOverride` at execution time — non-admins can't redirect a
  profile mid-call.
- DLQ retry, job history admin API, scheduler status — admin only.
- Cross-repository profile moves — non-admin PUT pins `repositoryId` to
  the existing record.

## 3. Data model

### `ConnectorDefinition` additions

| Field | Default | Meaning |
|---|---|---|
| `delegated` | `false` | False = admin-only (back-compat for every connector that existed before RC3). |
| `delegateAllFolders` | `false` | True = repository-wide delegation; bypasses `allowedFolderIds`. Use sparingly. |
| `allowedFolderIds` | `null` | Folder IDs (and their descendants) that delegated profiles may target. **Empty/null while `delegated=true` and `delegateAllFolders=false` means NO delegation, not "all folders".** This default is critical. |
| `allowedPrincipalIds` | `null` | User IDs and group IDs that may use this connector. Empty = no principal restriction. Group expansion via `PrincipalService.getGroupIdsContainingUser`. |

Validation in `ConnectorDefinitionServiceImpl.validateDelegationFields`:

- `delegated=false` AND any of {`delegateAllFolders`, `allowedFolderIds`,
  `allowedPrincipalIds`} non-empty → reject (stale state).
- `delegated=true` AND `delegateAllFolders=false` AND
  `allowedFolderIds` empty → reject (would otherwise be silently
  un-delegated; we want operators to know).
- `delegateAllFolders=true` AND `allowedFolderIds` non-empty → reject
  (mutually exclusive).
- Any blank entry in `allowedFolderIds` / `allowedPrincipalIds` → reject.

### `ImportProfileDefinition` additions

| Field | Default | Meaning |
|---|---|---|
| `createdByUserId` | `null` | Username of the principal who created this profile. `null` for legacy admin-created profiles. |
| `delegated` | `false` | True for profiles created by a non-admin via folder delegation. Non-admins may only edit/delete profiles where this is true. |

The non-admin POST/PUT path also force-overrides:

- `delegated = true`
- `createdByUserId = ctx.username` on POST; preserved on PUT (or stamped
  if missing in legacy data)
- `schedulerEnabled = false`
- `defaultProfile = false`

These are forced *after* validation so that a payload that tries to set
them won't slip through.

## 4. Authorization service

`IngestAuthorizationService` has three public predicates:

```java
boolean isAdmin(CallContext)
String  resolveFolderId(repositoryId, folderId, folderPath)
boolean canManageProfileForFolder(ctx, repositoryId, folderId)
boolean canUseConnectorForDelegatedProfile(ctx, repositoryId, connector, targetFolderId)
```

### `canManageProfileForFolder`

```
admin → true
otherwise → load folder, compute Acl via contentService.calculateAcl,
            then check effective cmis:all
```

Effective `cmis:all` means: the user's effective principal set
({username} ∪ `PrincipalService.getGroupIdsContainingUser(repo, user)` ∪
`{repository's Anyone principal}`) intersects an ACE that carries
`cmis:all`. We walk the merged ACL (`Acl.getAllAces()`, which already
unions local + inherited) directly rather than going through
`PermissionServiceImpl.checkPermission`. The reason: `checkPermission`
is action-keyed and resolves the required permission via
`PermissionMapping`. If an operator ever remapped, say,
`CAN_APPLY_ACL_OBJECT` to allow `cmis:write`, a delegation gate built
on that key would silently widen. We want the explicit, mapping-
independent invariant "user effectively holds `cmis:all`".

Group expansion failures are fail-closed: if the lookup throws, we
fall back to direct user ACEs only — group ACEs won't match. Same for
`calculateAcl` exceptions (return false) and folder-load exceptions
(return false). There is no path where an exception turns into "allow".

### `canUseConnectorForDelegatedProfile`

```
connector.enabled && connector.delegated  ── else false
folder check:
  if delegateAllFolders  → pass
  else if allowedFolderIds empty → false
  else walk targetFolderId + ancestors via contentService.getParent;
       must hit one of allowedFolderIds within 128 hops; cycles → false
principal check (only if allowedPrincipalIds non-empty):
  expand user → {username, groups}; require non-empty intersection
```

Folder containment is **ID-based**, not path-prefix. Path prefix
("`/A` matches `/AB`" or path-rename races) was rejected by review —
ID walks can't false-match, and they handle move/rename without
re-resolution.

### Why no admin short-circuit in `canUseConnectorForDelegatedProfile`?

Admins use the admin-only API path (full connector list, no
delegation gate). They never hit this predicate. Returning early for
admin would just mask logic bugs.

## 5. Controller flows

### POST `/v1/admin/import-profiles` (non-admin path)

```
1. Reject if schedulerEnabled=true       → 403
2. Reject if defaultProfile=true         → 403
3. Reject if repositoryId blank          → 400
4. resolveFolderId; reject if null       → 400
5. canManageProfileForFolder(folderId)   → 403 if false
6. Normalise: targetFolderId = resolved, targetFolderPath = null
7. Reject if allowedConnectorIds empty   → 400
   (no implicit "any connector" for non-admin)
8. For each connector in allowedConnectorIds:
     get from CouchDB; reject if unknown → 400
     canUseConnectorForDelegatedProfile  → 403 if false
9. Reject if defaultConnectorId not in allowedConnectorIds → 400
10. Force: delegated=true, createdByUserId=ctx.username,
           schedulerEnabled=false, defaultProfile=false
11. Save
12. Audit: EXTERNAL_PROFILE_CREATED, details={delegated, actorUserId,
                                               targetFolderId, connectorIds}
```

### PUT (non-admin, the dangerous one — TOCTOU coverage)

```
1. Load existing profile
2. Reject if existing.delegated=false        → 403 (admin-owned)
3. Reject if new payload schedulerEnabled    → 403
4. Reject if new payload defaultProfile      → 403
5. Force def.repositoryId = existing.repositoryId (no cross-repo move)
6. Resolve OLD folder; canManageProfileForFolder → 403
7. Resolve NEW folder; canManageProfileForFolder → 403
8. Validate connectors against OLD folder    → 403/400 (defence in
                                                          depth — should
                                                          have been
                                                          ensured at
                                                          create time)
9. Validate connectors against NEW folder    → 403/400
10. Force the safe fields again
11. Save + audit EXTERNAL_PROFILE_UPDATED
```

The old-folder check (step 8) catches the "old data was somehow saved
inconsistently" case — better to refuse than to re-bless a pre-existing
inconsistency on update.

### GET list (non-admin)

Profile-by-profile filter. We do NOT enumerate folders the user owns.
For each profile in `listByRepository`/`list`:
- skip unless `profile.delegated`
- resolve target folder; skip if unresolvable
- skip unless `canManageProfileForFolder` returns true

### GET `/v1/admin/connectors/summary?repositoryId=&targetFolderId=`

```
1. canManageProfileForFolder(targetFolderId) → 403 if false
2. For each connector:
     canUseConnectorForDelegatedProfile      → skip if false
     emit {connectorId, displayName, sourceArchetype,
           sourceSystem, adapterKind}
```

The summary DTO contains **no** `credentialRef`, `webhookSecret`,
`endpoint`, `tenantId`, `allowedFolderIds`, or `allowedPrincipalIds`.
A non-admin who reads `/summary` can see *that* a connector exists
and what kind it is, but learns nothing about its credentials or the
admin's scoping decisions for other folders.

### POST `/v1/repo/{repo}/ingest` (runtime gate)

```
1. ingestAuthorizationService is REQUIRED (Spring fail-fast on missing).
   null check returns 503 — never silently downgrade.
2. If admin → existing pipeline, no extra gate.
3. Non-admin:
     a. Reject targetFolderOverride            → 403
     b. Reject if profileId missing            → 403
     c. Load profile; reject if missing/!delegated → 404/403
     d. Reject if profile.repositoryId != path repositoryId → 403
     e. Re-resolve target folder; reject if unresolvable → 404
     f. canManageProfileForFolder              → 403
     g. If connectorId provided:
          must be in profile.allowedConnectorIds → 403
          must still be delegated for user/folder → 403
     h. If no connectorId, profile.defaultConnectorId
        must still be delegated for user/folder → 403
4. Audit every delegated attempt (EXTERNAL_INGEST or
   EXTERNAL_INGEST_FAILED) with details map.
```

## 6. UI contract

The UI is a *convenience* — every gate is enforced server-side, the
UI just makes the right thing easy and the wrong thing visible.

- `IntegrationSettings` page: tab filter by `isAdmin`. Non-admin sees
  only `import-profiles` + `manual-ingest`.
- `ImportProfileManagementTab`: schedulerEnabled / defaultProfile
  switches are disabled for non-admin with tooltip. Connector picker
  is options-driven (`Select`), not free-text tags. Non-admin uses
  `mode="multiple"` (no free tag entry); admin keeps `mode="tags"`.
  Non-admin reactively pulls `/connectors/summary` against the form's
  `targetFolderId`.
- `ManualIngestTab`: non-admin path inverts picker order
  (profile → connector) and uses `listConnectorSummary` scoped to the
  chosen profile's `targetFolderId`, intersected with the profile's
  `allowedConnectorIds`. `listConnectors()` (admin-only) is never
  called from the non-admin path.

## 7. Audit

`AuditLogger.logOperation(op, repo, user, objectId, success, errorMsg, details)`
takes a `details` map that becomes the JSON `details` block. Profile
ops record:

```json
{
  "delegated": true|false,
  "actorUserId": "alice",
  "targetFolderId": "F-…",
  "connectorIds": ["…"]
}
```

Delegated ingest (`EXTERNAL_INGEST` / `EXTERNAL_INGEST_FAILED`) records:

```json
{
  "delegated": true,
  "actorUserId": "alice",
  "profileId": "…",
  "connectorId": "…",
  "targetFolderOverrideAttempted": true,   // only when present
  "denialReason": "CONNECTOR_NOT_DELEGATED"  // only on failure
}
```

Every denial — both profile API and runtime ingest — also carries a
`denialReason` field tagged with a stable {@link DenialReason} enum
value. See §10 for the full list and what each one means. The
`errorMessage` field is human-readable and may be tweaked release to
release; the enum names are part of the audit contract and won't.

Admin ingest continues through the existing AOP audit and is **not**
double-logged here.

## 8. Operator runbook

### Delegating a connector

1. Create the connector as admin (POST `/v1/admin/connectors`).
2. Decide the scope:
   - One folder: `delegated=true`, `allowedFolderIds=["F-…"]`
   - A subtree: `delegated=true`, `allowedFolderIds=["root-of-subtree"]`
     (any descendant of the listed ID becomes eligible)
   - Repository-wide: `delegated=true`, `delegateAllFolders=true`
     (only when truly intended — credential reaches every folder)
3. Optionally restrict by principal:
   `allowedPrincipalIds=["user-id", "group:editors"]`. Group IDs
   match what `PrincipalService` returns for membership lookup.
4. PUT the connector. The validation rules in §3 will reject
   inconsistent combinations.

### Auditing delegated activity

Every delegation-related decision (success and denial) emits an audit
event with a structured `details` map. The `denialReason` field uses
the stable enum values defined in `DenialReason.java` — see §10 for the
full list. Free-form English in `errorMessage` may be tweaked between
releases; the enum names will not.

```bash
# Find profiles created by delegation
grep '"operation":"externalProfileCreated"' audit.log \
  | jq 'select(.details.delegated == true)'

# Runtime executions by a specific user (success + failure)
grep -E '"operation":"external(Ingest|IngestFailed)"' audit.log \
  | jq 'select(.details.actorUserId == "alice")'

# Every denied attempt against a profile (gate refusal trail)
jq 'select(.details.denialReason)' audit.log

# Bucketise the most common denial reasons in the last 24h
jq -r 'select(.details.denialReason)
       | "\(.timestamp) \(.details.denialReason) \(.details.actorUserId)"' \
   audit.log \
  | awk '$1 >= "'"$(date -u -d '24 hours ago' +%Y-%m-%dT%H)"'"' \
  | awk '{print $2}' | sort | uniq -c | sort -nr

# Override attempts (always denied for non-admin, but visible)
jq 'select(.details.targetFolderOverrideAttempted == true)' audit.log

# Folder-swap escalation attempts (CMIS_ALL_REQUIRED_NEW)
jq 'select(.details.denialReason == "CMIS_ALL_REQUIRED_NEW")' audit.log

# Connector swap attempts (CONNECTOR_NOT_DELEGATED on PUT)
jq 'select(.operation == "externalProfileUpdated"
           and .details.denialReason == "CONNECTOR_NOT_DELEGATED")' audit.log
```

### Revoking delegation

- Set `delegated=false` on the connector → existing delegated profiles
  using it will fail at execute time (re-evaluated each call) and
  immediately on next PUT (TOCTOU re-check both ways).
- Or remove the user/group from `allowedPrincipalIds`.
- Or remove the folder from `allowedFolderIds`.
- Profile `delegated=true` records are **not auto-cleaned** when their
  connector becomes undelegated — they just stop working. That's a
  deliberate fail-shut: you want the profile to stay visible to admin
  for inspection.

## 9. Multi-replica considerations

`IngestAuthorizationService` is stateless — no per-replica caching, no
sticky-session dependency. The folder + ACL it consults are CouchDB-
backed and shared. Multi-replica deployments inherit the existing
constraints from `docs/MULTI-REPLICA-DEPLOYMENT.md`; delegation adds
nothing new.

## 10. `DenialReason` reference

Stable identifiers used in audit `details.denialReason` and in the JSON
response body's `denialReason` key. Names are part of the audit
contract — do not rename or remove. Adding new entries is fine.

| Reason | Where it fires | What the operator should check |
|---|---|---|
| `SCHEDULER_REQUIRES_ADMIN` | non-admin POST/PUT with `schedulerEnabled=true` | UI client trying to enable scheduler — explain v1 limit, or upgrade caller to admin |
| `DEFAULT_PROFILE_REQUIRES_ADMIN` | non-admin POST/PUT with `defaultProfile=true` | Same as above; affects repo-wide auto-resolution |
| `ADMIN_OWNED_PROFILE` | non-admin PUT/DELETE/GET on a profile with `delegated=false` | UI bug listing admin profiles to non-admin, or stale link |
| `REPOSITORY_REQUIRED` | POST without `repositoryId` | Client payload incomplete |
| `TARGET_FOLDER_UNRESOLVABLE` | targetFolderId/Path doesn't resolve to an existing folder | Folder deleted between profile open and save, or wrong path |
| `CMIS_ALL_REQUIRED` | runtime execute / single GET / current-folder check | Caller's ACE was revoked, or they were never granted |
| `CMIS_ALL_REQUIRED_OLD` | PUT — caller has lost cmis:all on the EXISTING target folder since profile was created | ACL revoke; ask admin to restore or delete the stale profile |
| `CMIS_ALL_REQUIRED_NEW` | PUT — caller does not have cmis:all on the NEW target folder they're trying to swap to | **Possible escalation attempt** — investigate caller |
| `EMPTY_ALLOWED_CONNECTORS` | profile create/update with empty allowedConnectorIds, OR runtime gate seeing a corrupted record | UI client bug — must offer at least one delegated connector |
| `BLANK_CONNECTOR_ENTRY` | allowedConnectorIds list contains a blank string | Client serialisation bug |
| `UNKNOWN_CONNECTOR` | referenced connector doesn't exist in CouchDB | Connector deleted out from under a profile |
| `CONNECTOR_NOT_DELEGATED` | connector is not `delegated=true`, scope mismatch, or principal restriction excludes the user | If unexpected, check connector's `allowedFolderIds` / `allowedPrincipalIds` |
| `DEFAULT_CONNECTOR_NOT_IN_ALLOWED` | profile's `defaultConnectorId` is not listed in `allowedConnectorIds` | Client validation bug — keep them in sync |
| `PROFILE_NOT_FOUND` | runtime execute references a profileId that doesn't exist | Stale link or recently-deleted profile |
| `PROFILE_REPO_MISMATCH` | runtime execute targets a profile bound to a different repository | URL routing bug |
| `PROFILE_ID_REQUIRED` | non-admin runtime execute with no profileId | Auto-resolution path is admin-only by design |
| `TARGET_FOLDER_OVERRIDE_FORBIDDEN` | non-admin runtime execute with `targetFolderOverride` set | Override is admin-only in v1 |
| `CONNECTOR_NOT_IN_PROFILE` | runtime execute with a connectorId not in profile's allowedConnectorIds | Client bug; verify pickers match the saved profile |
| `DEFAULT_CONNECTOR_NOT_DELEGATED` | runtime execute fell through to profile's defaultConnectorId, but admin has since revoked its delegation | Restore delegation or update the profile |
| `SERVICES_UNAVAILABLE` | required Spring beans missing (should be impossible in production) | Custom application context misconfiguration |

## 11. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Folder owner sees "Admin-managed profile" when trying to edit a profile they expected to own | The profile was created by an admin (delegated=false). Non-admins can only edit `delegated=true` profiles | Delete and re-create the profile under the folder owner's account, or have admin migrate it (set delegated=true and createdByUserId). v2 will offer a migration tool |
| Folder owner can't see any connectors in the picker | Either no connector is `delegated=true`, or none has `allowedFolderIds` covering this folder, or the user is excluded by `allowedPrincipalIds` | Check ConnectorManagementTab — the new "委譲" column shows admin-only / scoped / all-folders / misconfigured at a glance |
| Runtime ingest fails immediately for a non-admin with `CONNECTOR_NOT_DELEGATED` even though the profile saved successfully | An admin revoked the connector's delegation between profile create and execute | Either restore delegation (`delegated=true` + scope) or have the user delete the profile and pick a still-delegated connector |
| `denialReason: SERVICES_UNAVAILABLE` in audit | Custom Spring context strips one of the required ingest beans | Restore default `serviceContext.xml` wiring; the controller fail-closes rather than fall through to admin path |
| Connector picker reactively refreshes per keystroke | UI uses `Form.useWatch('targetFolderId')`; React's render cycle debounces. Single-target endpoint per change is intentional | No action — confirmed safe |
| Non-admin scheduled ingest doesn't fire | By design — non-admin profiles have `schedulerEnabled=false` enforced; the scheduler also defensively skips any record with `delegated=true` regardless | Use manual ingest, or have admin take over the profile (`delegated=false` + admin sets schedulerEnabled) |

## 12. Future work (not in v1)

- **Scheduled delegated profiles.** Requires `IngestSchedulerService`
  to construct a `CallContext` from `createdByUserId` and re-evaluate
  `cmis:all` at each tick (currently calls `executeFetch(null, …)`).
  Also needs a story for what happens when the creator is deactivated
  (auto-disable the profile, or leave it for admin review).
- **Folder picker UI.** Today, non-admins enter folder IDs by hand.
  A tree picker showing only folders where they have `cmis:all` would
  be friendlier — the API is already there (per-folder
  `canManageProfileForFolder`); it's a UI cost.
- **Connector group memberships.** `allowedPrincipalIds` accepts group
  IDs and expands them at evaluation. A view "what connectors does
  group X have access to?" would help operators answer governance
  questions; not modelled today.
