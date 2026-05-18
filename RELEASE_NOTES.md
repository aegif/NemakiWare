# NemakiWare Release Notes

User-facing changelog. For per-commit detail see
[`CLAUDE.md`](CLAUDE.md); for design rationale see
[`docs/design/`](docs/design/).

---

## 3.1.1-RC3 — Folder-scoped External Ingestion delegation
_Release branch: `release/3.1.1-RC3` (2026-05-14 → ongoing)_

### What's new

#### Folder owners can now manage their own ingest profiles

Through RC2 only admins could create or edit External Ingestion profiles.
Folder owners had to file a ticket for every change — slow, and admins
rarely have the per-folder domain knowledge to set policies correctly.

RC3 introduces a tightly-scoped delegation model:

- An admin can mark a connector as **delegated** and pin it to one or
  more folders (or a subtree, or — if explicitly necessary —
  repository-wide), optionally restricted to specific users / groups.
- A folder owner with `cmis:all` on a folder can then create, edit, and
  delete **manual-only** import profiles bound to that folder, choosing
  from the connectors the admin delegated to them.
- Admin-owned profiles and scheduled ingestion remain admin-only.
  Non-admin profiles always run synchronously on demand.

The whole flow is server-enforced; the UI is just convenience.

#### New configuration knobs

| Property | Default | Where |
|---|---|---|
| `nemakiware.ingest.ancestorWalk.maxHops` | `128` | `nemakiware.properties`. Tune up if your folder hierarchy is legitimately deeper than the default; the gate logs a WARN when it reaches the cap without resolving so you know when to act. |

#### New endpoints

- `GET /v1/admin/connectors/summary?repositoryId=&targetFolderId=` —
  slim, secret-free connector listing for a single folder, gated by
  `cmis:all`. Used by the delegated profile editor; safe to expose to
  folder owners.

#### New data model fields

| Field | Where | Default | Notes |
|---|---|---|---|
| `delegated` | `ConnectorDefinition` | `false` | Admin-only until set true. |
| `delegateAllFolders` | `ConnectorDefinition` | `false` | Required to grant repo-wide; not implied by an empty `allowedFolderIds`. |
| `allowedFolderIds` | `ConnectorDefinition` | `null` | Folder IDs (and descendants) covered by delegation. Empty + `delegateAllFolders=false` = no delegation, by design. |
| `allowedPrincipalIds` | `ConnectorDefinition` | `null` | User IDs and group IDs (PrincipalService expansion). Empty = no principal restriction. |
| `createdByUserId` | `ImportProfileDefinition` | `null` for legacy | Username of the creator. |
| `delegated` | `ImportProfileDefinition` | `false` | True for profiles created by folder delegation. Non-admins can only edit profiles where this is true. |

#### New audit fields

Every delegation-related operation (profile create / update / delete and
delegated ingest) records a structured `details.denialReason` on
failure. The names are part of the audit contract — see
[`docs/design/connector-delegation.md`](docs/design/connector-delegation.md)
§10 for the full reference table.

### Compatibility

- **Existing connectors**: unchanged behaviour. `delegated` defaults to
  `false` so every pre-RC3 connector is admin-only as before.
- **Existing profiles**: unchanged. They become `delegated=false`
  records and continue to flow through the admin path (scheduler,
  defaults, etc.).
- **Existing audit consumers**: `details` now sometimes carries
  `denialReason` and the new ingest detail keys. Existing fields and
  the wire format are unchanged.
- **Admin API**: unchanged. Adding `delegated` / scope fields to a
  connector POST/PUT is opt-in.

### Migration safety (RC3)

The static review of view registration, patch application, and
record round-trip is summarised here so operators can sign off on
an upgrade without reading the source.

1. **RC3 adds no new CouchDB views, no new patches, and no new type
   definitions.** Existing dump files (`bedroom_init.dump`,
   `nemaki_conf_init.dump`) are unchanged. `DatabasePreInitializer`'s
   `viewCount < requiredViews` heuristic (38) is unaffected.
2. **Added persistence is JSON-field-only**:
   - `ConnectorDefinition` — `delegated`, `delegateAllFolders`,
     `allowedFolderIds`, `allowedPrincipalIds`
   - `ImportProfileDefinition` — `createdByUserId`, `delegated`
3. **Pre-RC3 records read with Java default values that fall on the
   safe side**:
   - `connector.delegated = false` → admin-only (existing behaviour)
   - `profile.delegated = false` → admin-managed (existing behaviour)
   - list fields = `null` → no scope (no implicit grant)
4. **Selector compatibility**. The `_find` mango selectors used for
   `connector_definition` and `import_profile_definition` query only
   on `type` + the primary key (`connectorId` / `profileId` /
   `repositoryId`). They do not filter on any new field, so pre-RC3
   and post-RC3 records remain mutually visible without an index
   rebuild.
5. **Corrupted records fail-closed at runtime**, not at read. A
   hand-edited record like `delegated=true && allowedConnectorIds=[]`
   deserialises cleanly but the runtime gate refuses with
   `denialReason: EMPTY_ALLOWED_CONNECTORS`. Same for an empty scope
   on the connector side (`hasUsableDelegationScope()` returns false).
6. **Backwards-compat for serialisation**: all delegation models are
   annotated `@JsonIgnoreProperties(ignoreUnknown=true)` and
   `@JsonInclude(NON_NULL)`. A round-trip through CouchDB preserves
   both pre-RC3 and post-RC3 records exactly; primitive `false`
   booleans are emitted (cannot be null).

> **Pre-existing items found during this review** that did not
> change in RC3 but are worth knowing about, recorded for follow-up
> in a separate PR — see "Known pre-existing follow-ups" at the end
> of this section.

### Upgrade

No CouchDB migration required — RC3 adds no new views, patches, or
type definitions; only JSON fields with safe defaults. See
[**Migration safety**](#migration-safety-rc3) below for the full
upgrade-time round-trip analysis. After deploying RC3:

1. **Existing admin-owned profiles** stay admin-managed. If a folder
   owner should take over a profile, use
   `POST /v1/admin/import-profiles/{id}/ownership` with
   `{"mode": "delegated", "createdByUserId": "<owner>"}` — admin only.
   Before transferring, verify that:
   - the target connector has `delegated=true` AND the profile's
     target folder is within its `allowedFolderIds` (or it sets
     `delegateAllFolders=true`),
   - the profile's `allowedConnectorIds` is non-empty AND each
     connector in it is delegated to the new owner for the target
     folder,
   - the profile's `defaultConnectorId` (if any) is contained in
     `allowedConnectorIds`,
   - the new owner effectively holds `cmis:all` on the target folder.
   Any of these failing returns a 400/403 with a structured
   `denialReason` and the profile is left untouched (the transfer is
   transactional from the caller's point of view).

   To move a profile back to admin management, POST the same endpoint
   with `{"mode": "admin"}` — clears `delegated`, leaves other fields
   alone.

2. To delegate a connector, open Integration Settings → Connectors →
   edit the connector → enable **委譲設定** and set
   `allowedFolderIds`. Optionally restrict by `allowedPrincipalIds`.

3. Notify folder owners — they'll see the import-profile and
   manual-ingest tabs under Integration Settings, plus a Browse
   folder-picker in the targetFolderId field.

### Security hardening (everything below is automatic)

- Connector credentials never reach non-admin clients. `/summary`
  returns a slim DTO with no `credentialRef` / `webhookSecret` /
  `endpoint` / `tenantId` / scope fields.
- Effective `cmis:all` evaluation uses `PrincipalService` group
  expansion + the repository's Anyone principal. Fail-closed on group
  lookup or ACL calculation failures.
- Folder containment uses ID-based ancestor walks (no path-prefix
  matching), so renames and moves don't false-match.
- TOCTOU defence: profile PUT re-checks `cmis:all` and connector scope
  on BOTH the existing and the new target folder so an attacker can't
  retarget a delegated profile they don't own.
- Runtime ingest re-evaluates the gate on every call — revoking a
  connector's delegation immediately stops in-flight profiles from
  using it.
- Scheduler defensively skips any record whose `delegated=true` even
  if `schedulerEnabled=true` slipped in via direct CouchDB write
  (`logs WARN once per profile per JVM lifetime`, then DEBUG).
- Required Spring dependencies for the gate (`IngestAuthorizationService`,
  `ConnectorDefinitionService`, `ImportProfileDefinitionService`) —
  bean missing means deny, never silent admin fall-through.

### Known limitations (deferred to v2)

- **Scheduled delegated profiles**: not supported.
  `schedulerEnabled=true` on a delegated profile is rejected at both
  the create/update API and at the scheduler poll loop. See
  [`docs/design/connector-delegation.md`](docs/design/connector-delegation.md)
  §12.1 for the full v2 pre-design — CallContext synthesis,
  per-tick ACL re-evaluation, creator deactivation policy, new
  `DenialReason` entries, and the property gates that govern the
  feature.
- ~~**Folder picker tree**~~: **shipped**. See "New in this RC" below.

### New in this RC (closed earlier "limitations")

- **Profile ownership transfer endpoint** (was: "delete + recreate
  only") — `POST /v1/admin/import-profiles/{id}/ownership` with
  `{mode: "delegated", createdByUserId: "alice"}` or
  `{mode: "admin"}`. Admin-only; re-validates that the new owner
  effectively holds `cmis:all`, that every connector in the
  profile's `allowedConnectorIds` is delegated to them, and that
  `defaultConnectorId` (if set) is contained in `allowedConnectorIds`,
  before flipping the flag. Refuses with the same `DenialReason`
  codes used elsewhere. Every denial — including
  `TARGET_FOLDER_UNRESOLVABLE`,
  `EMPTY_ALLOWED_CONNECTORS`, `DEFAULT_CONNECTOR_NOT_IN_ALLOWED`,
  `CMIS_ALL_REQUIRED`, `CONNECTOR_NOT_DELEGATED`, `UNKNOWN_CONNECTOR`,
  `BLANK_CONNECTOR_ENTRY` — flows through `auditTransferDenial`, so
  the audit trail captures `transferTo` + `newOwnerUserId` +
  `denialReason` even when the transfer is rejected before any DB
  write.
- **Connector PUT partial-payload protection** (was: "admin PUT
  omitting allowedFolderIds clears it"). List fields
  (`allowedFolderIds`, `allowedPrincipalIds`) follow
  null=preserve / `[]`=explicit clear semantics. Primitive flags
  still require an explicit value — admin must always send the
  intended boolean.
- **Folder picker tree** (was: "type IDs by hand"). The Browse
  button in the `targetFolderId` field opens a lazy-expanded CMIS
  folder tree. The picker shows folders the user can read (Browser
  Binding default); when a folder is selected the picker probes
  `/v1/admin/connectors/summary` against it — 403 = no `cmis:all`,
  Confirm stays disabled; 200 = green check and Confirm enables.
  Same picker is available to admin as a quality-of-life
  convenience (admin can pick any folder regardless of cmis:all).

### Testing

- 165+ ingest unit tests cover the authorization service, controller
  gates, runtime gates, scheduler defence, and cap-property handling.
- 21 API E2E tests against a live deployment cover admin / delegated
  user / non-delegated user × CRUD + execute + TOCTOU scenarios.
- All tests pass on every RC3 commit including the latest
  hardening rounds.

### Known pre-existing follow-ups (out of scope for RC3)

Surfaced by the RC3 migration / view-registration static review.
**None of these are RC3 regressions** — they exist on the RC2 line as
well. They are recorded here so a follow-up PR can address them
independently of the delegation feature ship.

| ID | Severity | Summary | Suggested follow-up |
|---|---|---|---|
| R1 | High | `NemakiPatchInitializationListener.patchBeanNames` (fallback path) is asymmetric with `CMISPostInitializer.cmisPatchList` (primary path). 9 patches are missing from the fallback list; 8 of those have no top-level `bean id="..."` in `patchContext.xml`, so even if added to the fallback array `springContext.containsBean()` would skip them. Concretely missing: `patch_IngestRelationshipTypes` and 8 others. Impact: if the primary path dies mid-run, the fallback can never finish what's left. | Add top-level bean ids for all patches, or migrate the listener to collect `Map<String, AbstractNemakiPatch>` automatically and drop the hardcoded array. |
| R2 | Medium | Mango `_find` queries against `nemaki_conf` (connector / profile / job records) have no registered Cloudant index — `postIndex` is never called anywhere in the codebase. Falls back to full-table scan. Fine at current scale (<1k records), noticeable at 10k+. | Add a startup patch that registers compound indexes on `(type, connectorId)` / `(type, profileId)` / `(type, repositoryId)`. |
| R3 | Medium | `StartupProbeService.REQUIRED_VIEWS_MAIN = 38` is hard-coded; the shipped `bedroom_init.dump` actually contains 40 views. The check `viewCount < required` passes either way today, but the count drifts over time. A future release that adds or removes views without updating the constant could mis-classify view completeness. | Compute the required-view set from the dump at startup (name-set comparison rather than count threshold). |
| R4 | Low | `Patch_StandardCmisViews` is registered both in `cmisPostInitializer.cmisPatchList` (primary) and in `patchService.patchList`. `PatchHistory` dedupes execution, but the startup log shows the patch entry twice. | Remove the duplicate registration from `patchService.patchList`. |

### References

- Design: [`docs/design/connector-delegation.md`](docs/design/connector-delegation.md)
- Operator runbook: same doc §8 + Help page → 連携設定 — 外部
  インジェスト → 委譲を運用する
- Audit reasons: same doc §10 (`DenialReason` reference table)
- Multi-replica posture: [`docs/MULTI-REPLICA-DEPLOYMENT.md`](docs/MULTI-REPLICA-DEPLOYMENT.md)
  (delegation is stateless — no extra requirements beyond the existing
  single-replica posture)

---

## Prior releases

See the per-RC history block in [`CLAUDE.md`](CLAUDE.md#現在のバージョン)
for RC1 through RC14 (and RC15/RC3 detail).
