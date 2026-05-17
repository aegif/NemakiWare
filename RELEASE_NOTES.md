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

### Upgrade

No migration required. After deploying RC3:

1. Audit any existing admin-owned profiles — they remain admin-managed.
   If a folder owner should take over a profile, currently the admin
   needs to delete + recreate (a 1-click transfer tool is on the v2
   list).
2. To delegate a connector, open Integration Settings → Connectors →
   edit the connector → enable **委譲設定** and set
   `allowedFolderIds`. Optionally restrict by `allowedPrincipalIds`.
3. Notify folder owners — they'll see the import-profile and
   manual-ingest tabs under Integration Settings.

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
  effectively holds `cmis:all` and that every connector in the
  profile's `allowedConnectorIds` is delegated to them, before
  flipping the flag. Refuses with the same `DenialReason` codes
  used elsewhere so audit shape stays uniform.
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
