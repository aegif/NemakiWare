# NemakiWare Release Notes

User-facing changelog. For per-commit detail see
[`CLAUDE.md`](CLAUDE.md); for design rationale see
[`docs/design/`](docs/design/).

---

## 3.1.1-RC5 — Scheduled delegated profiles + connector governance view
_Release candidate on `release/3.1.1-RC5` (2026-05-19), branched off
`v3.1.1-RC4.1` (`572aad18b`)._

Lands the two v2 items deferred from RC3's connector-delegation work:
scheduling for non-admin delegated profiles, and an admin governance
view answering "which connectors does principal X have access to?".
Both ship behind safe defaults so an upgrade does NOT change runtime
behaviour until the operator explicitly opts in.

### §12.1 Scheduled delegated profiles

A folder owner can now mark their delegated import profile
`schedulerEnabled=true` and have it fire on the scheduler without
needing admin privileges, with the same per-tick `cmis:all` and
connector re-evaluation the manual ingest path runs.

**Why this matters.** Before RC5, scheduled ingest required admin —
non-admins could only trigger their delegated profiles manually. That
forced a workflow where the folder owner kept a browser tab open or
scripted a curl call. RC5 closes the gap while keeping the security
contract: the scheduler tick runs under a synthetic CallContext for
the original creator, never short-circuits to admin even if the
creator happens to be one, and re-checks every gate (folder ACL,
connector delegation scope, creator-still-active) every tick.

**Operator opt-in required.** Off by default. Set:
```properties
nemakiware.ingest.delegated.schedulerEnabled=true
```
to enable. The controller's `SCHEDULER_REQUIRES_ADMIN` gate flips with
the same property — non-admin scheduled profiles can only be created
when the operator has consciously enabled the path.

**Creator deactivation policy.** When a creator's `UserItem` is no
longer findable (LDAP sync hard-delete, manual disable), the next tick
emits a structured `CREATOR_USER_INACTIVE` audit and skips. The
profile stays visible for admin review. Layer on automatic disable
after N consecutive failures:
```properties
nemakiware.ingest.delegated.autoDisableInactiveOwners=true
nemakiware.ingest.delegated.inactiveOwnerFailureThreshold=3
```
A successful active-user resolution resets the streak, so a transient
directory hiccup does not accumulate toward auto-disable.

**New `DenialReason` enum entries** (audit-stable, additive only):
- `CREATOR_USER_INACTIVE`
- `CREATOR_CMIS_ALL_LOST`
- `DELEGATED_SCHEDULING_DISABLED`

**Audit shape**. `EXTERNAL_INGEST_FAILED` records from the scheduled
path now carry `details.scheduled=true`, `details.creatorUserId`,
`details.creatorActive`, plus the existing `details.denialReason` and
`details.targetFolderId` keys. SOC queries that already filter on
`EXTERNAL_INGEST_FAILED` pick up the scheduled denials automatically.

### §12.3 Connector governance view

New admin-only endpoint:
```
GET /v1/admin/connectors/by-principal/{principalId}?repositoryId=...&expand={true|false}
```

Answers "which delegated connectors does this principal have access
to?". `expand=true` includes connectors matched via group expansion
(the same expansion the runtime gate uses, so the view agrees with
what the user would actually experience). Each match records the
principal IDs that triggered it and a `matchType` of `direct`,
`group`, or `direct+group`. The mixed case surfaces redundant grants
that may be cleanup candidates.

**Why this matters.** Removing a user from a group, or deleting a
group entirely, used to require scanning every connector's
`allowedPrincipalIds` by eye. The endpoint makes the question a single
admin API call.

### Migration / upgrade

No migration needed. All new behaviour is property-gated and off by
default. Existing deployments that upgrade and do nothing get
byte-identical scheduler behaviour to RC4.1. Existing tests retained
to pin the legacy path.

### Properties added

```properties
# v2 §12.1 — operator opt-in for delegated scheduled ingest
nemakiware.ingest.delegated.schedulerEnabled=false
nemakiware.ingest.delegated.autoDisableInactiveOwners=false
nemakiware.ingest.delegated.inactiveOwnerFailureThreshold=3
```

### Tests

- New: `DelegatedCallContextFactoryTest` (8),
  `IngestSchedulerDelegatedRunTest` (8),
  `ImportProfileSchedulerGateTest` (7),
  `ConnectorByPrincipalGovernanceTest` (13) — 36 new unit tests.
- Regression: `IngestSchedulerDelegationSkipTest` (5) preserved to pin
  the property-off legacy behaviour.
- Ingest delegation suite: 133 tests, all PASS.

### V1-V3 extensions (post-acceptance vNext fold-in)

After acceptance review of §12.1/§12.3, three operator-UX
improvements were folded into the same RC5 cycle. None change runtime
behaviour without a deliberate admin action.

**V1: auto-disable re-enable handshake.** When the scheduler
auto-disables a delegated profile after N consecutive
`CREATOR_USER_INACTIVE` ticks, it now records `lastAutoDisabledAt`
(ISO-8601) and `lastAutoDisabledReason` (e.g.
`CREATOR_USER_INACTIVE: creator 'alice' inactive for 3 consecutive ticks`)
on the profile. The admin UI's profile list shows an orange
"auto-disabled" badge with the reason in a tooltip, so admins can
distinguish a profile they disabled from one the scheduler shut down.
On the next admin re-enable (`enabled: true`), the markers are
cleared and a dedicated audit entry
(`EXTERNAL_PROFILE_UPDATED` with `details.clearedAutoDisableMarker=true`)
fires so the audit trail captures the deliberate reset. Unrelated
edits that don't flip `enabled` preserve the marker.

**V2: `principalType` in governance view.** The governance API now
classifies the queried principal as `USER`, `GROUP`, or `UNKNOWN`
(resolved via `PrincipalService`; `UNKNOWN` is the safe fallback for
pseudo-principals like `Anyone`, typos, or when `PrincipalService` is
not wired). The new `principalType` field appears at the top level of
the response. For `GROUP` principals the `expand=true` flag is now a
no-op (NemakiWare groups don't nest) — avoiding any
PrincipalService-impl-dependent surprises when fed a non-user ID.

**V3: governance dashboard UI.** New admin-only tab
"Connector Access" in Integration Settings. Form: principal ID +
include-group-expansion toggle. The result table shows connector
name, source system, status (enabled/delegated badges), and the match
type with the principal IDs that triggered the match. The header
card surfaces the principal type and the full list of expanded
principal IDs the server actually checked against. Operators no
longer need curl to answer "which connectors does this user/group
have access to?"

### V1-V3 properties

No new properties for V1-V3. Existing
`nemakiware.ingest.delegated.*` properties continue to control the
scheduler's auto-disable behaviour; the markers are written
automatically whenever auto-disable fires.

### F1-V5 hardening + UX (post-V1-V3 review fold-in)

After the V1-V3 acceptance review, three Low-priority hardenings
(F1-F3) and two operator-UX extensions (V4-V5) landed on the same RC5
cycle. None change runtime contracts; the API surface gains nothing
new (V5 is computed client-side from the existing governance response).

**F1: marker spoof prevention.** Non-admin payloads on
`POST /v1/admin/import-profiles` and
`PUT /v1/admin/import-profiles/{id}` can no longer set or modify
`lastAutoDisabledAt` / `lastAutoDisabledReason` via the payload — the
controller strips them before the V1 handshake runs. Admin payloads
can still write the markers for data-repair scenarios. The handshake
itself (re-enable clears, unrelated PUT preserves) is unchanged.

**F2: complete i18n entries.** All UI strings for the
`connectorGovernance` tab and the `importProfileManagement`
auto-disable badge/filter/banner now have explicit ja/en entries
(previously the TSX called `t()` with `defaultValue` only). Adds 17
keys to the `connectorGovernance` top-level section plus 4 keys under
`importProfileManagement` for the V1/V4 UI bits.

**F3: principal AutoComplete.** The governance tab's principal-ID
input is now an Ant Design `AutoComplete`, pre-populated with the
repository's users + groups (limit 500 each, loaded once on mount).
Each option renders as `{id} · {display name} (USER|GROUP)`. Free-text
entry is preserved for pseudo-principals (e.g. `Anyone`) or external
IdP principals not yet cached locally. Lookup failures fall back to
an empty suggestion list — the input still works.

**V4: auto-disable triage UI.** The Import Profiles tab now shows
a "Show only auto-disabled" filter Switch + a count Tag + a warning
Alert banner whenever at least one profile has been auto-disabled by
the scheduler. The filter is hidden when no auto-disabled profiles
exist, so the UI stays clean for healthy deployments.

**V5: simulate principal removal.** The governance tab now offers
a "Simulate removing" dropdown populated from the expansion result
(minus the queried principal itself). Picking a principal filters the
results table to matches where that principal was the **sole**
matching route — i.e. the connectors the user would actually lose if
removed from that group. Computed client-side from the existing
`matchedPrincipalIds` data, so no new API surface and no extra round
trip.

### F1-V5 tests

- `ImportProfileSchedulerGateTest`: +3 cases (F1 non-admin update
  spoof, F1 non-admin create spoof, F1 admin write preserved) — 10
  total cases in this class.
- Ingest delegation suite: **136 tests, all PASS** (was 133).

### Known post-RC5 follow-ups (low priority — RC5.1 candidates)

Surfaced during the F1-V5 acceptance re-review. None are release
blockers; recorded here so they aren't lost.

- **G1** (Low, UX): `ImportProfileManagementTab` — when the
  "auto-disabled only" filter is on AND the admin re-enables the
  last auto-disabled profile, `autoDisabledCount` drops to 0 → the
  filter Switch unmounts but its internal state stays `true`. The
  visible table looks empty until the page is refreshed. Fix: small
  `useEffect` that resets `onlyAutoDisabled` to false when count
  reaches 0.
- **G2** (Low, scale): `ConnectorGovernanceTab` AutoComplete loads
  users + groups with `limit=500`. Adequate for single-tenant
  NemakiWare deployments; needs pagination or server-side filtering
  for 10k+ principal directories.
- **G3** (Low, UX): V5 simulate-removal `Select` includes
  `GROUP_EVERYONE` when expansion brought it in. Simulating its
  removal yields 0 lost (nothing typically lists `GROUP_EVERYONE` in
  `allowedPrincipalIds`), so it's harmless but visually noisy.
  Excluding well-known pseudo-principals from the dropdown would be
  a small polish.

### vNext (separate scope — not RC5.1)

Larger ideas surfaced during RC5 reviews; explicitly out of scope
for any RC5 patch and not started:

- **V6**: "Auto-disabled in last N days" filter in
  `ImportProfileManagementTab`.
- **V7**: Multi-principal removal simulation (e.g. remove from
  group A AND group B simultaneously).
- **V8**: AutoComplete virtual scroll + lazy load for very large
  principal directories.

---

## 3.1.1-RC4.1 — RC4 acceptance findings F1-F3
_Patch release on `release/3.1.1-RC4` (2026-05-19)._
_Release tag: **`v3.1.1-RC4.1`**._

Commit anchors:
- **RC4 baseline** (`cc63d960e`) — R1-R4 patch-machinery cleanup.
- **RC4.1 code hardening** (`7823b60f7`) — F1-F3 fix on top of
  `cc63d960e`. This is the commit that ships the actual behaviour
  changes documented below.
- **Final tag target** — includes the RC4.1 code hardening above
  PLUS one or more doc-only release-review fixes layered on top.
  See `git log v3.1.1-RC4.1 --oneline` for the exact head once the
  tag is created.

Tightens three findings surfaced by the RC4 acceptance review.
All changes are small, idempotent, and re-verifiable. No new
features. No data migration. No change to existing patch history.

### F1 — Fallback patch ordering for ExternalIntegration types

`Patch_ExternalIntegrationSourceFields` depends at runtime on
`Patch_ExternalIntegrationSecondaryType` (it WARN-and-skips if the
target type doesn't yet exist — see
`Patch_ExternalIntegrationSourceFields.java:66`). RC4's fallback
listener happened to preserve this dependency only by coincidence
of alphabetical bean-name ordering ("`Sec`" < "`SourceFields`"
lexicographically). A future patch named, say,
`patch_ExternalIntegrationFoo` would sort BETWEEN them and silently
break the chain.

Fix: both patches added to `ORDERED_SEED_PATCHES`. Seeds run first
in declared order regardless of alphabetical interleaving.
New test `externalIntegrationDependency_isPreservedRegardlessOfAlphabet`
inserts a hostile bean name between them and asserts the
SecondaryType still precedes SourceFields.

### F2 — Mango index targeted a non-existent field

`Patch_IngestMangoIndexes` (RC4) registered `idx_type_dlqEntryId` on
`(type, dlqEntryId)`, but the DLQ record's actual key field — used
in every `_find` selector at `IngestJobService:176/234/278/300` — is
`dlqId`. Cloudant created the index without complaint, but it
matched no real selector, so DLQ lookups silently fell back to
`_all_docs` scan.

Fix: renamed to `idx_type_dlqId` with the correct field. The
existing dead index `idx_type_dlqEntryId` is **not** auto-deleted
(we don't touch state we didn't create with the current patch
instance). Operators on RC4 → RC4.1 upgrades may optionally remove
it. Substitute your own CouchDB host, port, and credentials —
the values shown below are the docker-compose dev defaults and
are **not** appropriate for production:

```bash
# Replace COUCHDB_URL and CREDENTIALS with the values from your
# deployment (e.g. for the docker-compose dev environment:
#   COUCHDB_URL=http://localhost:5984
#   CREDENTIALS=admin:password — DO NOT use these in production)
curl -u "${CREDENTIALS}" -X DELETE \
  "${COUCHDB_URL}/nemaki_conf/_index/ingest-indexes/json/idx_type_dlqEntryId"
```

Leaving it in place is harmless (a few KB of unused index storage).

New test `indexSpecs_targetActualSelectorFields_notGuesses` walks
the patch's `INDEXES` list by reflection and pins:
- `idx_type_dlqId` present with field set `[type, dlqId]`
- `idx_type_dlqEntryId` **absent** (regression guard)
- the other selectors used by ingest services
  (`connectorId`, `profileId`, `jobId`) are still registered

### F3 — Log message simplification

The "created / existing / failed" counter was unreliable: different
Cloudant versions return `result="created"` on idempotent
re-registration too. RC4.1 collapses to a single `processed` counter
(`processed=N, failed=M (out of K)`). The `failed > 0` ->
`RuntimeException` failure detection that drives PatchHistory
non-marking is **unchanged**.

### Not addressed in RC4.1 (documented as accepted)

| ID | Status | Rationale |
|---|---|---|
| F4 | Doc only | `applySystemPatch()` runs on every boot, but Cloudant `postIndex` is idempotent + cheap (~16ms for 7 indexes confirmed on live restart). Matches existing patch pattern. |
| F5 | Doc only | `archive_init.dump` declares 14 views vs the legacy threshold of 8. The RC4 subset check tightens it but the existing `mergeDesignDocument` self-heals on the next boot, so end state is correct and visible via the missing-name WARN log. |

### Verification

- 171 unit tests pass (2 new: F1 ordering + F2 selector field check).
- Live restart on the running container confirms the renamed
  index registers cleanly and the log line reads
  `processed=7, failed=0 (out of 7)`.
- `git status --short`: clean.

---

## 3.1.1-RC4 — Patch machinery cleanup
_Release branch: `release/3.1.1-RC4` (2026-05-18 → ongoing)_

Closes the four pre-existing follow-ups (R1-R4) recorded in RC3's
"Known pre-existing follow-ups" section. No new user-facing
functionality; structural fixes to the patch / view-registration
machinery so the foundation is solid before any future feature
work that touches it.

### What changed

| ID | Fix |
|---|---|
| **R4** (Low) | `Patch_StandardCmisViews` was registered both in `cmisPostInitializer.cmisPatchList` (primary) and in `patchService.patchList`. `PatchHistory` deduped execution, but the startup log emitted "Applying patch: standard-cmis-views" twice and confused diagnostics. Removed the duplicate from `patchService.patchList`; canonical home is `cmisPostInitializer`. |
| **R3** (Medium) | `StartupProbeService.REQUIRED_VIEWS_MAIN = 38` int threshold replaced by a NAME-SET subset comparison against the shipped `bedroom_init.dump` (currently 40 views). `DatabasePreInitializer` now reports specifically *which* view names are missing rather than a count gap. Dump-file unreadability (e.g. classpath-stripped builds) falls back to the legacy int threshold so the check never silently passes everything. The integer constants are kept as a backstop. |
| **R1** (High) | `NemakiPatchInitializationListener.patchBeanNames` hardcoded array of 23 patches replaced with `WebApplicationContext.getBeansOfType(AbstractNemakiPatch.class)` auto-collection. The 8 patches that previously lacked a top-level `bean id` (`Patch_IngestRelationshipTypes`, `Patch_BusinessRecordMetadataSecondaryType`, `Patch_ChatContextMetadataSecondaryType`, `Patch_MessageMetadataSecondaryType`, `Patch_NoteMetadataSecondaryType`, `Patch_ExternalIntegrationSourceFields`, `Patch_DefaultCloudDriveConnectorProfile`, `Patch_PurviewStateMigration`) get one. A short `ORDERED_SEED_PATCHES` array keeps dependency-sensitive patches (`patch_SystemFolderSetup` → `patch_InitialContentSetup` → `patch_StandardCmisViews` → …) in deterministic order; everything else runs in alphabetical bean-name order. A throwing or failing patch no longer halts the run. |
| **R2** (Medium) | New `Patch_IngestMangoIndexes` registers 7 compound Mango indexes on `nemaki_conf` for the ingest record types: `(type, connectorId)`, `(type, sourceArchetype)`, `(type, sourceSystem, sourceArchetype, enabled)`, `(type, profileId)`, `(type, repositoryId)`, `(type, jobId)`, `(type, dlqEntryId)`. Eliminates the `_all_docs` scan fallback that affected query latency at 10k+ records. Idempotent on Cloudant (`postIndex` returns `result="exists"` for unchanged definitions). |

### Compatibility

- **Existing CouchDB views**: untouched. The R3 change is read-only —
  it switches the *completeness check* from a count to a name-set
  subset, but the views themselves are still merged into the design
  document by `DatabasePreInitializer.mergeDesignDocument` as before.
- **Patch execution semantics**: every patch still runs through
  `PatchUtil.isApplied` / `PatchHistory`, so re-runs are no-ops. R1
  may execute patches in a different (alphabetical) order than the
  RC3 hardcoded list for the non-seed entries; `PatchHistory`
  guarantees this doesn't matter for correctness.
- **Mango index creation (R2)**: idempotent. Existing deployments
  get the indexes on first RC4 boot; the operation completes in
  hundreds of milliseconds against a typical `nemaki_conf`.

### Upgrade

No manual steps. Restart the core service; the four patches apply
automatically. Verify with:

```bash
docker logs docker-core-1 2>&1 | grep -E "IngestMangoIndexes|patch.*complete"
curl -u admin:password http://localhost:5984/nemaki_conf/_index | jq '.indexes | length'
```

Expected: 7 newly-created indexes (or 7 "existing" on re-deploy) plus
the CouchDB default `_all_docs` index.

### Testing

- 17 new unit tests:
  - `StartupProbeViewNameSetTest` (5) — dump parsing, caching,
    immutability, fallback when dump missing
  - `NemakiPatchInitializationListenerTest` (6) — auto-collect,
    seed-order preservation, alphabetical remainder, throwing /
    failing patches don't halt the run
  - `Patch_IngestMangoIndexesTest` (6) — patch name stability,
    graceful skip on missing pool / client, failure surfacing
- Live verification:
  - All 7 Mango indexes created on first boot (logs + CouchDB
    `_index` introspection)
  - 21 / 21 RC3 API E2E still pass with RC4 patches deployed

### References

- Design doc with R1-R4 detail moved from "known follow-ups" to
  "shipped": [`docs/design/connector-delegation.md`](docs/design/connector-delegation.md) §9.5
- RC3 history (for context on what these follow-ups closed):
  [section below](#311-rc3--folder-scoped-external-ingestion-delegation)

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

### Known pre-existing follow-ups (closed in RC4)

Surfaced by the RC3 migration / view-registration static review and
**all four shipped in RC4** (see top of this file). The table below
is retained for traceability.

| ID | Severity | Summary | Status |
|---|---|---|---|
| R1 | High | `NemakiPatchInitializationListener.patchBeanNames` (fallback path) is asymmetric with `CMISPostInitializer.cmisPatchList` (primary path). 9 patches are missing from the fallback list; 8 of those have no top-level `bean id="..."` in `patchContext.xml`. | ✅ Fixed in RC4 — auto-collect from Spring context, 8 missing bean ids added |
| R2 | Medium | Mango `_find` queries against `nemaki_conf` have no registered Cloudant index. Fine at current scale, noticeable at 10k+. | ✅ Fixed in RC4 — `Patch_IngestMangoIndexes` registers 7 compound indexes |
| R3 | Medium | `StartupProbeService.REQUIRED_VIEWS_MAIN = 38` is hard-coded; the shipped `bedroom_init.dump` actually contains 40 views. | ✅ Fixed in RC4 — dump-derived name-set subset check |
| R4 | Low | `Patch_StandardCmisViews` is registered both in `cmisPostInitializer.cmisPatchList` and `patchService.patchList`. Startup log shows the patch entry twice. | ✅ Fixed in RC4 — removed from `patchService.patchList` |

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
