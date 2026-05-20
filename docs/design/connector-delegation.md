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
  `targetFolderId`. **`targetFolderId` is required for non-admin
  delegated profiles; the `targetFolderPath` input is hidden** — every
  delegated path needs to agree on a single resolved ID for the
  picker, the cache key, and the audit trail. Admin retains the path
  input for legacy / scripted workflows; the server also normalises
  path → ID on POST/PUT.
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

Ownership transfer (`POST /v1/admin/import-profiles/{id}/ownership`,
admin-only) emits `EXTERNAL_PROFILE_UPDATED` with an extra
`transferTo` (`"delegated"` | `"admin"`) and, for delegated
transfers, `newOwnerUserId`. Every failure mode flows through the
private `denyTransfer` / `auditTransferDenial` helpers so the
`denialReason` is recorded even when the transfer is rejected before
`importProfileDefinitionService.update` is called. Reasons used by
this endpoint: `TARGET_FOLDER_UNRESOLVABLE`,
`EMPTY_ALLOWED_CONNECTORS`, `DEFAULT_CONNECTOR_NOT_IN_ALLOWED`,
`BLANK_CONNECTOR_ENTRY`, `UNKNOWN_CONNECTOR`, `CMIS_ALL_REQUIRED`,
`CONNECTOR_NOT_DELEGATED`.

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

## 9.5 Migration safety and pre-existing follow-ups

This section captures the conclusions of the RC3 migration / view
static review so future maintainers don't have to re-derive them.

### 9.5.1 Why RC3 is safe to roll out without a migration step

RC3 makes **no changes to the on-disk shape that the existing
machinery doesn't already know how to handle**:

- No new CouchDB views (no `Patch_*Views` class added).
- No new CMIS type definitions (no `Patch_*Type` class added; the
  RC2 `Patch_IngestRelationshipTypes` stands as-is).
- No new Mango index (none existed before; status quo).
- No new dump file changes (`bedroom_init.dump`,
  `nemaki_conf_init.dump` unchanged).
- No new `DOC_TYPE` discriminator (re-uses existing
  `connector_definition` / `import_profile_definition`).

The added persistence is **JSON-field-only** on existing record
shapes:

- `ConnectorDefinition`: `delegated` (boolean, default false),
  `delegateAllFolders` (boolean, default false), `allowedFolderIds`
  (List, default null), `allowedPrincipalIds` (List, default null).
- `ImportProfileDefinition`: `createdByUserId` (String, default null),
  `delegated` (boolean, default false).

Round-trip behaviour:

1. Pre-RC3 records have none of these fields. Jackson
   `@JsonIgnoreProperties(ignoreUnknown=true)` reads them with Java's
   built-in defaults — booleans are `false`, references are `null`.
   `false` is the safe side for both `delegated` flags.
2. Mango `_find` selectors (`connectorDefinitionService.findBySelector`,
   `importProfileDefinitionService.findBySelector`) query only on
   `type` + the primary key. They do not condition on any new field,
   so pre-RC3 records remain matchable and post-RC3 records remain
   findable.
3. `@JsonInclude(NON_NULL)` strips `null` list fields from the
   serialised JSON, so a post-RC3 record without any scope set
   doesn't grow the document with empty lists. Primitive booleans
   are always emitted (Java can't distinguish "unset false" from
   "explicit false").
4. Corrupted shapes (`delegated=true && allowedConnectorIds=[]`,
   `delegated=true && delegateAllFolders=false && allowedFolderIds=null`,
   etc.) deserialise without exception. They fail at *runtime* via
   `EMPTY_ALLOWED_CONNECTORS` /
   `ConnectorDefinition.hasUsableDelegationScope() == false` /
   `canUseConnectorForDelegatedProfile() == false`, never silently.

### 9.5.2 Pre-existing follow-ups — all closed in RC4

The static review surfaced 4 pre-existing structural items in the
patch / view machinery. They are not RC3 regressions. **All four
shipped in RC4** (see `RELEASE_NOTES.md` 3.1.1-RC4). The detailed
rationale per item is retained below for traceability.

#### R1 (High) — Fallback patch listener is asymmetric with the primary

`NemakiPatchInitializationListener.patchBeanNames` (L112-135) is a
hard-coded array of 23 patches. `CMISPostInitializer.cmisPatchList`
(in `patchContext.xml`, L92-272) is an inline list of 28. The
fallback lacks: `patch_IngestRelationshipTypes`,
`patch_BusinessRecordMetadataSecondaryType`,
`patch_ChatContextMetadataSecondaryType`,
`patch_MessageMetadataSecondaryType`,
`patch_NoteMetadataSecondaryType`,
`patch_ExternalIntegrationSourceFields`,
`patch_DefaultCloudDriveConnectorProfile`,
`patch_PurviewStateMigration`. 8 of the 9 have no top-level
`bean id="..."`, so even if they were added to the listener array
`springContext.containsBean()` would skip them.

Failure mode: primary path crashes mid-run → fallback runs only the
23 it knows about → the missing 9 are not retried by the fallback.
The next normal startup re-runs the primary which idempotently
applies the rest, so the window is bounded — but a deployment that
loses the primary repeatedly (config error) could stall on the
missing patches.

**Shipped in RC4**: top-level bean IDs added for all 8 missing
patches; `NemakiPatchInitializationListener` switched to
`getBeansOfType(AbstractNemakiPatch.class)` with a short
`ORDERED_SEED_PATCHES` array preserving dependency-sensitive
ordering. Failing / throwing patches no longer halt the run.

#### R2 (Medium) — Mango `_find` has no registered index

`postIndex` is not called anywhere in the codebase. Cloudant falls
back to `_all_docs` scan for every `_find`. At current scale
(typical 10-50 connectors + 50-200 profiles per repository) this
is fine. At 10k+ records per type it would dominate query latency.

**Shipped in RC4**: `Patch_IngestMangoIndexes` registers 7 compound
indexes on `nemaki_conf` at first boot:
`(type, connectorId)`, `(type, sourceArchetype)`,
`(type, sourceSystem, sourceArchetype, enabled)`,
`(type, profileId)`, `(type, repositoryId)`, `(type, jobId)`,
`(type, dlqEntryId)`. Idempotent on Cloudant (`postIndex` returns
`result="exists"` for unchanged definitions).

#### R3 (Medium) — `REQUIRED_VIEWS_MAIN = 38` drifts from the dump

`StartupProbeService.REQUIRED_VIEWS_MAIN` is hard-coded as 38;
shipped `bedroom_init.dump` has 40 views. `DatabasePreInitializer`
checks `viewCount < required` so both 38 and 40 pass today, but a
release that adds a 41st view via dump + forgets to bump the
constant, or removes a view from the dump without removing the
constant guard, would mis-classify view completeness.

**Shipped in RC4**: `StartupProbeService.expectedMainViewNames()` /
`expectedClosetViewNames()` parse the dump on first call and cache
JVM-wide. `DatabasePreInitializer` does a subset check (existing
DB must contain every expected name; custom views are tolerated as
before). Falls back to the legacy int threshold when the dump is
unreadable so the gate never silently passes everything.

#### R4 (Low) — Duplicate registration of `Patch_StandardCmisViews`

The patch appears both in `cmisPostInitializer.cmisPatchList` and
`patchService.patchList` (`patchContext.xml` L109, L58).
`PatchHistory.isApplied` dedupes execution per repository, so the
duplicate is functionally harmless, but it produces two
"Applying patch: standard-cmis-views" log lines on every startup
and confuses startup diagnostics.

**Shipped in RC4**: duplicate registration removed from
`patchService.patchList`; `cmisPostInitializer.cmisPatchList` is
the canonical home.

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

### 12.1 ~~Scheduled delegated profiles~~ (shipped in RC5)

**Shipped** in RC5 (3.1.1). Delegated profiles can now fire on a
schedule under the original folder owner's CallContext, with the same
per-tick ACL re-evaluation the manual path enforces. The feature is
**off by default**: operators must explicitly opt in via
`nemakiware.ingest.delegated.schedulerEnabled=true` so they consciously
accept the deactivation policy before non-admin scheduled ticks start
firing.

**Shipped pieces**:

1. **CallContext synthesis** — `DelegatedCallContextFactory.buildOrNull(repositoryId, username)`
   produces a `SyntheticCallContext` that satisfies the four methods
   the delegation gate actually reads (`getUsername`, `getRepositoryId`,
   `get(IS_ADMIN)`, `getBinding`). Hard-coded `IS_ADMIN=false` even when
   the underlying `UserItem.isAdmin()` is true — the gate must run
   regardless of who the creator is. No JVM-scope cache; each call does
   a fresh `UserItem` lookup so mid-day directory changes are picked
   up on the next poll.

2. **Per-tick ACL re-evaluation** — inside `IngestSchedulerService.prepareDelegatedTick`,
   immediately before the fetch is dispatched:
   1. Operator opt-in (`schedulerEnabled` property) → `DELEGATED_SCHEDULING_DISABLED`
   2. Required wiring present → `SERVICES_UNAVAILABLE`
   3. `createdByUserId` set → `CREATOR_USER_INACTIVE`
   4. `UserItem` resolvable (== "active") → `CREATOR_USER_INACTIVE`
   5. Creator still holds `cmis:all` on the target folder → `CREATOR_CMIS_ALL_LOST`

   A second per-tick check fires after `resolveConnectorForProfile` to
   catch connector-side revocations (`CONNECTOR_NOT_DELEGATED`). This is
   needed because the connector list can be edited after the profile
   selection runs.

3. **Creator deactivation policy** — fail-shut as default
   (option **(a)** from the original pre-design): a missing `UserItem`
   triggers `CREATOR_USER_INACTIVE`, the tick is skipped, the profile
   stays visible for admin review. An opt-in
   `nemakiware.ingest.delegated.autoDisableInactiveOwners=true` plus
   `nemakiware.ingest.delegated.inactiveOwnerFailureThreshold=N` (default
   3) layers on option **(b)** — after N consecutive inactive-creator
   ticks, the scheduler flips `enabled=false` and stops re-trying.
   Successful active-user resolution resets the streak counter, so
   transient outages don't accumulate.

4. **`schedulerEnabled` gate relaxation** — the
   `ImportProfileDefinitionController` create/update path now
   property-gates the `SCHEDULER_REQUIRES_ADMIN` rejection. With the
   property off (default) the v1 refusal stands. With the property on,
   non-admins can set `schedulerEnabled=true` on profiles they own; the
   safety properties are re-evaluated per-tick by the scheduler.
   `defaultProfile` remains admin-only — that gate is unrelated.

5. **Audit shape additions** — `EXTERNAL_INGEST_FAILED` records emitted
   from the scheduled path carry `details.scheduled=true`,
   `details.delegated=true`, `details.creatorUserId`,
   `details.creatorActive` (false iff the denial reason is
   `CREATOR_USER_INACTIVE`), and the structured `details.denialReason`
   matching the new `DenialReason` enum entries:
   `CREATOR_USER_INACTIVE`, `CREATOR_CMIS_ALL_LOST`,
   `DELEGATED_SCHEDULING_DISABLED`.

6. **Test coverage** — `DelegatedCallContextFactoryTest` (8),
   `IngestSchedulerDelegatedRunTest` (7),
   `ImportProfileSchedulerGateTest` (5),
   `IngestSchedulerDelegationSkipTest` (5, regression pin for
   property-off legacy behaviour).

**Properties added** (`nemakiware.properties`):

```properties
# Operator opt-in for delegated scheduled ingest (v2 §12.1)
nemakiware.ingest.delegated.schedulerEnabled=false
# Auto-disable a delegated profile after N consecutive CREATOR_USER_INACTIVE ticks
nemakiware.ingest.delegated.autoDisableInactiveOwners=false
nemakiware.ingest.delegated.inactiveOwnerFailureThreshold=3
```

### 12.2 ~~Folder picker UI~~ (shipped)

**Shipped** in RC3 hardening #8. `FolderPickerModal.tsx` renders a
lazy-expanded folder tree fetched via the CMIS Browser Binding's
`getChildren` (so users only see folders they can read). When the user
selects a folder, the modal probes `/v1/admin/connectors/summary`
against that ID — a 200 means the user holds `cmis:all` (the gate the
delegated profile will need anyway) and the Confirm button enables; a
403 keeps the modal open and shows a red "no permission" line.

The probe is intentionally per-selection rather than per-node: bulk
pre-coloring the whole tree would multiply the auth-service load and
slow expansion. The selection probe is fast (single endpoint hit) and
matches exactly the check the server will re-run on form submit.

Wired into `ImportProfileManagementTab` via a Browse button inside
the `targetFolderId` Input's `addonAfter`. Admin gets the same picker
(informational only — admin can pick any folder regardless of cmis:all).

### 12.3 ~~Connector group-membership view~~ (shipped in RC5)

**Shipped** in RC5 (3.1.1) alongside §12.1. The admin-only endpoint

```
GET /v1/admin/connectors/by-principal/{principalId}?repositoryId=...&expand=true|false
```

answers "which connectors does principal X have access to?". The
`expand` flag toggles whether the response includes connectors that
match indirectly via group expansion (`IngestAuthorizationService.expandPrincipals`
— the same expansion the runtime gate uses, so the governance view
agrees with what the user would actually experience). Each match entry
records the principal IDs that triggered it and a `matchType` of
`direct`, `group`, or `direct+group`, so operators can identify
redundant grants (a user listed both directly and via a group they
belong to is a likely candidate for cleanup).

The endpoint returns connectors with an empty/null `allowedPrincipalIds`
as zero matches — a connector that's open to everyone is by definition
not a delegation question.

Tested by `ConnectorByPrincipalGovernanceTest` (13 cases — admin
gate, missing `repositoryId`, direct/group/mixed match-type matrix,
empty-`allowedPrincipalIds` skip, principalType resolution
(USER/GROUP/UNKNOWN paths), and the GROUP-skips-expansion guard).

### 12.4 V1-V3 extensions (RC5, post-acceptance)

Folded into RC5 after the §12.1/§12.3 acceptance review. All
operator-UX; no runtime contract changes.

**V1: auto-disable marker fields.** `ImportProfileDefinition` gained
two strings — `lastAutoDisabledAt` (ISO-8601) and
`lastAutoDisabledReason` — written by the scheduler whenever
`handleInactiveCreator` flips `enabled=false` after the configured
streak threshold. The admin UI shows them as a badge + tooltip on the
profile list so an admin can tell a scheduler-disabled profile from
one they disabled themselves. `ImportProfileDefinitionController.update`
clears both fields when the same PUT also re-enables the profile (and
emits a dedicated `clearedAutoDisableMarker=true` audit detail);
unrelated PUTs preserve the markers. The new fields are JSON-optional
so pre-RC5 records read with `lastAutoDisabledAt=null` and behave
identically.

**V2: `principalType` in governance view.** The governance API now
classifies the queried principal as `USER`, `GROUP`, or `UNKNOWN` via
`PrincipalService.getUserById` → `getGroupById`. `UNKNOWN` is the
safe fallback when neither lookup resolves or `PrincipalService`
isn't wired (the rest of the response is still produced — the lookup
never fails closed). For `GROUP` principals the endpoint now
deliberately skips the `expandPrincipals` call even when
`expand=true`: NemakiWare groups don't nest, and feeding a non-user
ID to a PrincipalService that does is an avoidable surprise.

**V3: governance dashboard tab.** `ConnectorGovernanceTab.tsx` —
admin-only React tab wired into `IntegrationSettings`. Form is
`principalId` + `expand` toggle (default true); submit calls the
governance endpoint and renders a table with a colour-coded matchType
badge (green=direct, blue=via group, orange=direct+group) plus a
header card showing the principal type and the full
`expandedPrincipals` list. Operators answering "what does this user
have access to?" no longer need curl.

### 12.5 F1-V5 follow-up (RC5, post-V1-V3 review)

Five follow-ups from the V1-V3 acceptance review.

**F1 (Low): marker spoof prevention.** The V1 marker fields are
scheduler-controlled. A non-admin folder owner had API surface to set
fake markers on their own delegated profile via POST/PUT payload —
no security impact (they own the profile) but it could pollute the
"auto-disabled" badge and audit story. F1 strips
`lastAutoDisabledAt` + `lastAutoDisabledReason` from non-admin
payloads in both `enforceDelegationOnCreate` (for create, end of
method) and at the top of `update()` (before the V1 handshake runs,
so the handshake operates on clean data). Admin payloads are
unchanged so admins can manually annotate the marker for data
repair.

**F2 (Low): complete i18n.** The V1 badge / V4 banner / V3 + V5 tab
content previously used `t(key, { defaultValue: ... })` exclusively.
F2 adds explicit ja/en entries under a new top-level
`connectorGovernance` i18n section (17 keys) and 4 keys under
`importProfileManagement` for auto-disable bits. Future translation
updates will surface as missing-key warnings instead of silently
diverging.

**F3 (Low): principal AutoComplete.** Replaces the plain `Input`
with an Ant Design `AutoComplete` populated from
`CMISService.getUsers(repo, {limit:500})` +
`getGroups(repo, {limit:500})` on mount. Options are
`{id} · {display name} (USER|GROUP)`; `filterOption` matches against
the rendered label so typing a partial display name surfaces
suggestions. Free-text submission is still allowed, which keeps
pseudo-principals (e.g. `Anyone`) and external-IdP principals not
yet cached working. Lookup failures soft-fail to an empty
suggestion list.

**V4: auto-disable triage UI.** `ImportProfileManagementTab` now
computes `autoDisabledCount = profiles.filter(p => !p.enabled && p.lastAutoDisabledAt).length`
and surfaces:
- A `Switch` "Show only auto-disabled" + count `Tag` (hidden when
  count is 0)
- A warning `Alert` banner ("N profiles were auto-disabled — review
  before re-enabling")
- The existing "auto-disabled" badge per row (already shipped in V1)
The filter passes a derived `visibleProfiles` to the Table.

**V5: simulate-removal drill-down.** The governance tab adds a
`Select` populated from `result.expandedPrincipals - {result.principalId}`
(the queried principal itself isn't a meaningful "remove" target).
When the admin picks a principal, the matches table re-filters
client-side to:

```
matches.filter(m =>
    m.matchedPrincipalIds.length > 0
    && m.matchedPrincipalIds.every(p => p === simulateRemove))
```

i.e. matches where the simulated principal is the **sole** route to
access. These are exactly the connectors the queried principal would
lose if removed from that group. A warning Alert with the lost count
appears above the filtered table. No new server endpoint — the data
needed is already in the existing `matchedPrincipalIds` field, so V5
is purely UI.

Tested by `ImportProfileSchedulerGateTest` (+3 F1 cases). V4 / V5 /
F2 / F3 are UI-only; coverage relies on TS type checking, UI build,
and live deployment verification.

### 12.6 Post-RC5 follow-up (low priority — RC5.1 candidates)

Surfaced in the F1-V5 acceptance re-review. Not release blockers;
recorded here so the RC5.1 cycle can pick them up cleanly.

- **G1** (Low, UX): `ImportProfileManagementTab` — when the
  "auto-disabled only" filter Switch is on AND the admin re-enables
  the last auto-disabled profile, `autoDisabledCount` drops to 0,
  the Switch unmounts, and its `useState` value stays `true`. The
  table renders empty until refresh. Fix: a 3-line `useEffect` that
  resets the state when the count hits 0.
- **G2** (Low, scale): F3's AutoComplete loads users + groups in a
  single `limit=500` call on tab mount. Adequate for single-tenant
  NemakiWare; 10k+ principal directories need pagination or
  server-side filtering. Defer until a deployment actually needs it.
- **G3** (Low, UX): V5's `simulateRemove` `Select` includes
  `GROUP_EVERYONE` when expansion brought it in. Removing it from
  the simulation set typically yields 0 lost (no connector should
  list `GROUP_EVERYONE` in `allowedPrincipalIds` — defeats the
  point of delegation). Filtering well-known pseudo-principals out
  of the dropdown is a small polish.

### 12.7 vNext (separate scope, not RC5.1)

Larger ideas that came up during reviews but are explicitly NOT
RC5.1 work. Recorded for future planning only.

- **V6**: "Auto-disabled in last N days" filter in
  `ImportProfileManagementTab` so admins can separate fresh
  scheduler shutdowns from legacy ones.
- **V7**: Multi-principal removal simulation in V5 — e.g. "what
  does Alice lose if removed from BOTH group A and group B?"
- **V8**: F3 AutoComplete virtual scroll + lazy load to make the
  governance tab usable at 10k+ users/groups without large initial
  fetches.

### 12.8 RC5.1 polish (G1 + G3 + V6 + V7 + V8 + B1 fix)

Folded into RC5.1 — all UI / i18n only, no Java / contract / DB
changes. RC5.1's tag (`v3.1.1-RC5.1`) closes this cycle.

**G1**: `ImportProfileManagementTab` gains a `useEffect` that resets
`onlyAutoDisabled` to false whenever `autoDisabledCount` drops to 0.
Previously the filter Switch would unmount but its state would stay
`true`, leaving the admin staring at an empty table until refresh.

**G3**: `ConnectorGovernanceTab` filters
`PSEUDO_PRINCIPALS_FOR_SIMULATE = {GROUP_EVERYONE, anyone, Anyone, GROUP_ANYONE, authenticated, Authenticated}`
out of the simulate-removal Select. These are ACL targets, not group
memberships an admin can edit; simulating their removal isn't a
meaningful operator question. The match-list itself is unaffected
(connectors that allow `GROUP_EVERYONE` still surface in `matches`).

**V6**: V4's filter Switch now sits next to a window Select
(All / 24h / 7d / 30d). The `isWithinDays(p, days)` helper fails
shut on malformed timestamps. The count Tag shows `recent/total`
when a window is active and the banner switches to the
`autoDisabledBannerRecent` i18n key.

**V7**: V5's `simulateRemove` type changes from `string | null` to
`string[]`. The Select goes `mode="multiple"`. Filter logic stays
sole-route-detection but generalises to "every matched principal is
in the removal set": `m.matchedPrincipalIds.every(p => removalSet.has(p))`.
Single-element selection degenerates exactly to V5 behaviour. The
key UX win is cascade detection — a connector that allows both group
A and group B doesn't lose access when either is removed
individually, but does when both are.

**V8**: server-side principal search. The picker no longer fetches
500 users + 500 groups on mount. An empty `query` fetches 50 of
each; `onSearch` triggers a fresh fetch with the typed query against
the existing `/user/list?query=` and `/group/list?query=` admin
endpoints (300 ms debounce). Lookup failures soft-fail to an empty
options list. Scales for 10k+ principal directories.

**B1 fix** (RC5.1 acceptance-review regression): V8 originally
switched the picker from `AutoComplete` to Ant Design `Select` to
get virtual scrolling. Review caught that `Select` only accepts
options-list values — typed pseudo-principals couldn't be submitted.
Fix: revert the picker to `AutoComplete` while preserving V8's
server-side debounced search. Virtual scroll is explicitly traded
off — 50 items per response is small enough that virtual scrolling
adds no measurable benefit for this use case.

### 12.9 Post-RC5.1 follow-up (low priority — RC5.2 candidates)

Surfaced during the RC5.1 acceptance review. Not blockers.

- **H1**: V8 debounce `setTimeout` lacks an unmount cleanup. The
  governance tab rarely unmounts in admin sessions so the impact
  is essentially zero, but adding a `useEffect` return-cleanup
  closes the loop.
- **H2**: V7's `mode="multiple"` Select accepts unbounded selection.
  Picking the whole expansion set yields a noisy "lose everything"
  result that isn't a useful operator question. A max-count cap
  (5? 10?) would steer the UI toward meaningful comparisons.
- **H3**: V6's window selector is fixed (All / 24h / 7d / 30d). A
  custom-N-days input would handle incident windows that don't fit
  the preset list.

### 12.10 ~~RC5.2 polish~~ (shipped) — H1 + H2 + H3

Folded into RC5.2 — all UI / i18n only. RC5.1 W1/W2 (server-side
scalability) remain as separate scope.

**H1**: `ConnectorGovernanceTab` `useEffect` return-cleanup clears
the V8 search debounce timer on unmount. Eliminates a
"setState on unmounted" warning class. No live-tab behaviour change.

**H2**: V7 multi-removal `Select` gains `maxCount={SIMULATE_REMOVE_MAX}`
(= 10). A Tooltip on the label explains the cap; an orange Tag
appears once the cap is hit. Picking the whole expansion set
(the noisy "lose everything" answer) is now prevented in the UI
without restricting the underlying API.

**H3**: V6's window selector gains a "Custom..." option. Selecting
it flips a `customDaysMode` flag → `InputNumber` (min=1, max=9999,
addonAfter="d") renders inline. "Done" button snaps back to the
preset Select. G1's count-reset `useEffect` was extended to also
reset `customDaysMode` so the filter row stays consistent when
the underlying count drops to 0.

### 12.11 ~~RC5.3: W1 + W2 server-side scalability~~ (shipped)

Folded into RC5.3 — first RC5.x cycle since the base that adds Java
code. All previous RC5.x cycles were UI-only.

**W1: `autoDisabledSince` server-side filter.**
`GET /v1/admin/import-profiles` gains an optional `autoDisabledSince`
query param (ISO-8601 instant). When set, only profiles whose
`lastAutoDisabledAt >= cutoff` are returned; profiles without a
marker are excluded by definition. Malformed cutoff → filter dropped
+ WARN, preserving backward compat for shaky callers. Profile-side
malformed markers fail-shut (exclude rather than misclassify a stale
shutdown as fresh during triage). The V6 UI window pushes the
cutoff to the server when its "only auto-disabled" filter is also
active, eliminating client-side filtering at scale.

**W2: `POST /by-principal/{id}/simulate-remove`.**
Admin-only endpoint that runs V5/V7's sole-route detection
server-side and returns a `{lost, kept}` partition.
`buildMatches(principalId, principalsToMatch)` is extracted as a
shared helper used by both this endpoint and V3's listByPrincipal
so the two stay byte-identical for the same principal set. The
audit log adds `EXTERNAL_GOVERNANCE_SIMULATE` entries with
`actorUserId`, `principalId`, `expandedPrincipals`,
`removePrincipalIds`, and `lostCount` — SOC tooling can correlate
"what-if" questions with subsequent group / ACL changes.

The V7 UI keeps client-side computation for instant filter feedback
but fires W2 debounced at 800 ms after the multi-select settles, so
the audit fires without slowing the UI.

`AuditOperation.EXTERNAL_GOVERNANCE_SIMULATE` is the only audit-enum
addition. Per the existing audit-stability contract, the enum name
is now part of the audit-trail and may never be renamed or removed.

### 12.12 vNext (separate scope, not RC5.x)

Bigger ideas that came up during RC5.1 review but are explicitly
NOT planned for any RC5 patch.

- **W1**: V6 currently filters client-side. Very large profile
  lists would benefit from a server-side `since` query param so
  the client fetches only profiles disabled in the window.
- **W2**: V7 currently computes the lost-connector set client-side
  from the existing `matchedPrincipalIds` data. A dedicated server
  endpoint (`POST /by-principal/{id}/simulate-remove` with a
  principal-set body and a `lost` array response) would let CLI
  / scripting access the same logic without re-implementing it.
