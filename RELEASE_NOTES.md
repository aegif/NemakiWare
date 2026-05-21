# NemakiWare Release Notes

User-facing changelog. For per-commit detail see
[`CLAUDE.md`](CLAUDE.md); for design rationale see
[`docs/design/`](docs/design/).

---

## 3.1.1-RC5.6 — R5 denialReason accuracy + A2 spec CSRF cleanup
_Release candidate on `release/3.1.1-RC5.5` (2026-05-21), branched
off `v3.1.1-RC5.5` (`dfb912da9`)._

Post-RC5.5 cumulative cleanup. RC5.5 shipped with R5 (the last
remaining cumulative follow-up from RC5.4) still open; RC5.6 closes
it and extends RC5.5's Playwright spec CSRF fix to the rest of the
repository. No public API contract change, no DB / patch / view /
migration change. The RC5.5 tag (`dfb912da9`) is **not force-updated**
and remains as a historical milestone.

### R5: scheduler audit denialReason accuracy

`IngestSchedulerService.pollScheduledProfiles` previously inlined
the second `resolveFolderId(...)` call into the connector delegation
re-check. When that call returned null (folder deleted, ACL revoked,
or transient lookup failure between scheduler ticks), the
authorization check returned `false` and the audit recorded
`denialReason=CONNECTOR_NOT_DELEGATED` — safety preserved, label
wrong. RC5.6 extracts `resolveFolderId(...)` into a local first; a
null result emits `denialReason=TARGET_FOLDER_UNRESOLVABLE` (matches
the shape already used in `prepareDelegatedTick` step 5), then the
non-null result flows into the connector check as before.

2 new unit tests pin the behaviour:
- `targetFolderDisappearsBetweenTicks_emitsTargetFolderUnresolvable_notConnectorNotDelegated`
- `targetFolderResolves_butConnectorNoLongerDelegated_stillEmitsConnectorNotDelegated`
  (regression guard for the legitimate `CONNECTOR_NOT_DELEGATED` path)

### A2: repository-wide spec CSRF cleanup

RC5.5 fixed CSRF headers in 3 RC5-area spec files. RC5.6 audited
all 43 specs that issue state-changing requests and found only 2
additional files actually needed the fix — Jersey-served
`/core/api/v1/cmis/*` paths and CMIS Browser Binding
`/core/browser/*` are CSRF-exempt at the servlet level. Added
`X-Requested-With: XMLHttpRequest` to:

- `tests/admin/integration-settings.spec.ts` (12 PUT/POST sites on
  `/core/api/v1/admin/integration-settings/*`)
- `tests/admin/purview-atlas-e2e.spec.ts` (7 POST/PUT sites on
  `/core/api/v1/admin/{purview,integration-settings,lineage-journal}/*`)

While in `integration-settings.spec.ts`, two pre-existing test drift
items were resolved as well:

- Stale tab count: expected 15 → actual 17 (RC5.1 added
  `connector-governance`, later RC added `mcp`). Test renamed and
  assertion updated.
- Loose `/Connector|コネクタ/i` regex would match both the
  `Connectors` management tab and the `Connector Access` governance
  tab — masking a regression where the management tab disappears
  but the governance tab still satisfies the assertion. Replaced
  with the anchored `/^(コネクタ ベータ|Connectors\s+Beta)$/`
  pattern already used in `connector-profile-management.spec.ts`.

Also folds in `7f4b268ba` (the RC5.5 post-tag Playwright E2E
fix — CSRF header + serial mode + tab selector + valid
sourceSystem in the 3 RC5-area spec files). RC5.5 cut its tag
before that commit landed; RC5.6 includes it in the canonical
artifact.

### Change scope vs RC5.5 (precise)

- **Changed in RC5.6**:
  - `core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerService.java`
    (R5 — extract `resolveFolderId` local, add `TARGET_FOLDER_UNRESOLVABLE`
    early-return branch)
  - `core/src/test/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerDelegatedRunTest.java`
    (+2 R5 unit tests, +1 ArgumentCaptor import, +AuditLogger mock wiring)
  - `core/src/main/webapp/ui/tests/admin/integration-settings.spec.ts`
    (CSRF header on 12 sites + tab count 15→17 + tab regex anchored)
  - `core/src/main/webapp/ui/tests/admin/purview-atlas-e2e.spec.ts`
    (CSRF header on 7 sites)
  - `core/src/main/webapp/ui/tests/api/ingest-pipeline-e2e.spec.ts`
    (RC5.5 follow-up: serial mode + CSRF header + sourceSystem `e2e_test`→`box`/`google_drive`)
  - `core/src/main/webapp/ui/tests/api/external-ingest-api.spec.ts`
    (RC5.5 follow-up: serial mode + CSRF header)
  - `core/src/main/webapp/ui/tests/admin/connector-profile-management.spec.ts`
    (RC5.5 follow-up: CSRF header + anchored tab selector)
  - `docs/design/connector-delegation.md` §12.14 (new) / §12.15 (renumbered vNext)
  - `CLAUDE.md` (RC5.6 section)
  - `REVIEW_PACKET.md` (rewrite as RC5.6 packet)
  - `RELEASE_NOTES.md` (this section)
- **Unchanged from RC5.5** (byte-equal):
  - All other product code (controllers, services, factories, audit
    pipeline, scheduler-other paths, governance V3 endpoint, W1/W2
    endpoints, RC5 §12.1 scheduled-delegated machinery)
  - `AuditOperation` / `DenialReason` enums (no new entries)
  - `nemakiware.properties`, `serviceContext.xml`
  - Patch / view dumps / Mango index / DB bootstrap
  - UI shipped components (only test files changed)

### Commit + tag relationship

- **R5 feature commit**: `cee66573e`
- **A2 spec CSRF cleanup commit**: `dc0ba6dac`
- **RC5.5 post-tag Playwright E2E follow-up**: `7f4b268ba`
- **Tab regex tightening (Low)**: in the RC5.6 doc commit
- **`v3.1.1-RC5.6` annotated tag target**: see `git rev-parse v3.1.1-RC5.6^{}`

The previous candidate `v3.1.1-RC5.5` is **not force-updated** and
remains at peeled commit `dfb912da9` as a historical milestone.

### Tests + verification

- 96/96 ingest-related Java tests pass
  (10 `IngestSchedulerDelegatedRunTest`, was 8 — R5 +2)
- 17/17 `integration-settings.spec.ts` (was 8 failed in RC5.5)
- 17 pass / 25 skip `purview-atlas-e2e.spec.ts` (skips are Atlas
  not configured in env, intentional)
- 35/35 RC5-area specs from RC5.5 still pass (no regression)

### Follow-up status (cumulative across RC5 cycle)

**Resolved in this RC**: R5, A2 (Playwright spec CSRF cleanup).

**Remaining** (post-release / RC5.7+ candidates, not blocking):

- **R1** (Low, ops) — SOC tooling integration for the
  `EXTERNAL_GOVERNANCE_SIMULATE` audit event.
- **H2** (Medium, test coverage) — R3 Simulate button has no
  Playwright / React test.
- **M2** (Medium, security hardening) — `simulate-remove` body
  size limit.
- **M3** (Low, scale) — `buildMatches` full-scan per call.
- **L1 / L2** — nit findings (UI ref-equality reset, server-side
  null check defensiveness).

---

## 3.1.1-RC5.5 — External-review C1 blocker fix + H1/M1/M4 cleanups
_Release candidate on `release/3.1.1-RC5.5` (2026-05-20), branched
off `release/3.1.1-RC5.4` HEAD `8629782bb` (the post-RC5.4-closure
branch state, with all RC5.4 doc commits included)._

Correction cycle from the **first external-review round** that
targeted `v3.1.1-RC5.4`. The reviewer surfaced one blocker (C1)
plus three recommendations (H1, M1, M4); RC5.5 addresses all four.

### C1: epoch overflow → HTTP 400 (was 500)

`GET /v1/admin/import-profiles?autoDisabledSince=` with an
ISO-8601 instant that parses successfully but overflows Long
range on `toEpochMilli()` (e.g. `+999999999-12-31T23:59:59Z`) used
to return HTTP 500. RC5.4 only caught `DateTimeParseException`;
RC5.5 also catches `ArithmeticException` for both the cutoff
parse and the per-profile marker parse:

- Cutoff overflow → controller returns **HTTP 400** (R4 strictness
  contract now holds for both DateTimeParseException AND overflow).
- Profile-side overflow on `lastAutoDisabledAt` → defensive exclude
  (one corrupted record no longer 500s the whole list response).

2 new unit tests pin the behaviour:
- `epochOverflowCutoff_returns400_C1_RC5_5`
- `profileWithEpochOverflowMarker_isExcluded_listStillReturns200_C1_RC5_5`

`ImportProfileSinceFilterTest` total: 8 → 10 cases.

### H1: silent audit catch → safeEmit helper

The five `catch (RuntimeException ignored)` audit emit sites in
the RC5 cycle (3 in `ImportProfileDefinitionController`, 1 in
`ConnectorDefinitionController`, 1 in
`ExternalIngestController`, 1 in `IngestSchedulerService`) are
collapsed to `jp.aegif.nemaki.audit.AuditEmitSupport.safeEmit(...)`
which:

- Preserves the original invariant: audit failure must never
  break the business path.
- Logs a WARN line on failure with `op + actor + object +
  exceptionClass + exceptionMessage`.
- Deliberately does NOT log the audit `details` map. The details
  map can contain principal lists, internal IDs, and other
  audit-only fields; keeping it out of the general application
  log preserves audit's segregation contract and avoids
  secret / token / credential leakage into less-protected
  logging sinks.

The 2 `catch (RuntimeException ignored)` in
`resolvePrincipalType` are unchanged — they are deliberate
fall-through logic (USER → GROUP → UNKNOWN), not audit-related.

Net audit silent catches in RC5 area: 5 → 0.

### M1: REVIEW_PACKET.md test evidence precision

Replaces bare "155 / 155 ingest delegation tests PASS" with two
explicit scopes:

- **Focused 14 test classes / 157 tests** (RC5.5 includes the C1 +2).
  Explicit class list documented.
- **Broader pattern** `*Ingest*Test*,*Connector*Test*,*Profile*Test*,Delegated*Test`
  returned **287 tests** all PASS at the RC5.4 closure review;
  not re-run for RC5.5 because RC5.5 only touches files inside
  the focused set.

### M4: design doc historical section strikethrough

`docs/design/connector-delegation.md` §12.6 and §12.9 (the
historical post-RC5 / post-RC5.1 follow-up lists, whose items
all shipped in RC5.1 / RC5.2 respectively) now carry the
`~~strikethrough~~ (resolved in RC5.x)` treatment to match
§12.10 / §12.11 / §12.12 already-shipped sections.

### Change scope vs RC5.4 (precise)

- **Changed in RC5.5**:
  - `ImportProfileDefinitionController.applyAutoDisabledSinceFilter`
    (C1 — adds `ArithmeticException` to the catch alongside
    `DateTimeParseException` on both cutoff + profile paths)
  - 5 audit method bodies in 4 files → `safeEmit` helper calls (H1)
  - **NEW** `core/src/main/java/jp/aegif/nemaki/audit/AuditEmitSupport.java` (H1)
  - `ImportProfileSinceFilterTest` (+2 C1 tests)
  - `REVIEW_PACKET.md` (M1)
  - `docs/design/connector-delegation.md` (M4 + RC5.5 §12.13)
  - `CLAUDE.md` (RC5.5 section)
  - `RELEASE_NOTES.md` (this section)
- **Unchanged from RC5.4** (byte-equal, accumulated zero diff):
  - Scheduler core, governance V3 endpoint, W2 simulate-remove
    endpoint, W1 query param, V1 marker handshake, V4-V8 UI logic,
    R3 audit button.
  - `AuditOperation` enum (no new entries since RC5.3).
  - `DenialReason` enum (no new entries since RC5).
  - `nemakiware.properties`, `serviceContext.xml`.
  - Patch / view dumps / Mango index / DB bootstrap.

### Commit + tag relationship

- **C1 + H1 feature commit**: `9bb5bcf83`
- **Pre-tag doc closure commit** (status flip 進行中 → shipped): `dfb912da9`
- **`v3.1.1-RC5.5` annotated tag target**: `dfb912da9`
- **Annotated tag object SHA**: `bd967193da1f522dc9fed47a24b8c2febfd5fdba`

The previous candidate `v3.1.1-RC5.4` is **not force-updated**
and remains at peeled commit `014939eeb` as a historical
milestone.

### Tests + verification

- 157/157 focused ingest tests pass (was 155 — C1 +2)
- TypeScript check + UI build pass
- `/core/ui/dist/` forbidden path: 0 hit cumulative
- Live C1: 4-case curl confirms 400 / 400 / 200 / 200
- Live C1: corrupted profile marker → that profile excluded, list 200

### Follow-up status (cumulative across RC5 cycle)

**Remaining** (post-release / RC5.6+ candidates, not blocking
external re-review):

- **R1** (Low, ops) — SOC tooling integration for the
  `EXTERNAL_GOVERNANCE_SIMULATE` audit event.
- ~~**R5** (Low, audit label) — denialReason mislabel race in
  `IngestSchedulerService` connector re-check.~~ **Resolved in RC5.6
  (`cee66573e`).**
- **H2** (Medium, test coverage) — R3 Simulate button has no
  Playwright / React test.
- **M2** (Medium, security hardening) — `simulate-remove` body
  size limit.
- **M3** (Low, scale) — `buildMatches` full-scan per call.
- **L1 / L2** — nit findings (UI ref-equality reset, server-side
  null check defensiveness).

**Resolved in this RC**: C1, H1, M1, M4.

---

## 3.1.1-RC5.4 — R3 + R4 closure review code corrections
_Release candidate on `release/3.1.1-RC5.4` (2026-05-20), branched
off `release/3.1.1-RC5.3` HEAD `01fe84ac5` (RC5.3 closure
correction doc commit included)._

Code correction follow-up cycle for the two RC5.3 closure-review
findings the user opted to fix before external review. Both items
were post-release candidates in RC5.3 but accepted with caveat
documentation; RC5.4 elevates them to shipped fixes.

### R3: V7 audit fires on explicit button instead of 800ms debounce

`ConnectorGovernanceTab` removes the RC5.3 `useEffect`-driven
debounce that fired `simulate-remove` 800 ms after the multi-select
settled. Replaced with an explicit `Simulate (audit)` button next
to the simulate `Clear` button. Click → exactly one audit entry
recorded, 1:1 mapping to "an admin deliberately asked this
question". The button disables after firing and re-enables when
the selection changes (so repeated audit of the same query is
gated behind a state change). Client-computed display is unchanged —
the table filter is still instant, only the audit-trail trigger
moved from automatic to deliberate.

SOC tooling consumers can now treat each `EXTERNAL_GOVERNANCE_SIMULATE`
audit entry as a high-signal event (intentional operator query)
rather than a low-signal event (UI side-effect of multi-select
traversal).

### R4: `autoDisabledSince` malformed → 400 BAD_REQUEST

`applyAutoDisabledSinceFilter` now throws `IllegalArgumentException`
on unparseable ISO-8601 input; the controller catches and returns
HTTP 400. Empty / null params still pass through (treat as "no
filter requested") — only non-empty malformed values 400.

The closure review flagged the RC5.3 fail-safe pass-through as
risky: a typo in an admin diagnostic query returned the full list,
which an operator might briefly misread as "no recent shutdowns" =
"system healthy". The RC5.3 UI only ever ships `Date.toISOString()`
output, so the strictness doesn't affect the shipped flow; CLI /
scripting callers with malformed input now get an immediate 400
instead of a silently-full response.

### API contract impact

- **Backwards-compatible for callers shipping valid ISO-8601 or
  empty/missing `autoDisabledSince`** (the entire shipped UI flow).
- **Breaking only for non-empty malformed `autoDisabledSince`**:
  RC5.3 returned 200 + unfiltered list (with WARN log); RC5.4
  returns 400. This is the deliberate R4 strictness improvement.

### Tests

- `ImportProfileSinceFilterTest`: 8 cases (was 7 — `malformedCutoff`
  case rewritten as `malformedCutoff_returns400_R4Strictness` +
  new `emptyStringCutoff_stillTreatedAsAbsent_passThrough_evenWithR4`).
- Ingest delegation suite: **155 tests, all PASS** (was 154).
- TS check + UI build pass.

### Live verification

- R4: malformed query → HTTP 400 (was 200 in RC5.3) ✅
- R4: valid ISO → 200 ✅
- R4: empty param → 200 pass-through (unchanged) ✅
- R3: simulateAudit / simulateAudited / simulateAuditHint / simulateAuditFailed
  i18n keys all present in deployed bundle (3x each = ja + en + t() call) ✅
- W2 endpoint regression check: still returns lost/kept correctly ✅

### Change scope vs RC5.3 (precise)

Where exactly the RC5.4 cycle touched code, so external reviewers
can scope their review accurately:

- **Changed in RC5.4** (deliberate):
  - `ImportProfileDefinitionController` (Java) — R4 strictness:
    `applyAutoDisabledSinceFilter` throws `IllegalArgumentException`
    on malformed cutoff; `list()` catches → 400.
  - `ConnectorGovernanceTab.tsx` (UI) — R3 explicit audit button
    replaces the 800ms debounce useEffect; new state +
    `triggerSimulateAudit` callback.
  - `ImportProfileSinceFilterTest` — `malformedCutoff` test rewritten
    + new `emptyStringCutoff` test (155/155 PASS, was 154).
  - 4 new i18n keys (ja + en) for the R3 audit button states.
- **Unchanged from RC5.3** (verified zero diff):
  - `IngestSchedulerService` and `DelegatedCallContextFactory`
    (the scheduled delegated profile core)
  - `ConnectorDefinitionController` (governance V3 / W2 endpoints)
  - `AuditOperation` enum (no new entries since RC5.3)
  - `DenialReason` enum (no new entries since RC5.3)
  - `nemakiware.properties` (all 3 RC5 opt-in properties unchanged)
  - `serviceContext.xml` (no DI changes)
  - Patch / CouchDB view dumps / Mango index registration /
    DB bootstrap (`Patch_*`, `*_init.dump`, `Patch_IngestMangoIndexes`,
    `DatabasePreInitializer`, etc.) — accumulated zero diff
    since v3.1.1-RC4.1.

Phrased operationally: **RC5.4 changes one Java controller method,
one TSX component, one test class, and four i18n keys. Everything
else listed above is byte-equal to RC5.3.**

### Commit + tag relationship

- **R3 + R4 feature commit**: `6283afc96`
  (`feat(rc5.4): R3 + R4 closure review code corrections`)
- **Pre-tag doc closure commit** (status flip 進行中 → shipped):
  `014939eeb`
- **`v3.1.1-RC5.4` annotated tag target**: `014939eeb`
- **Annotated tag object SHA**: `d0a4a4f3d0f40482b0ca45cae47f75305235588b`

The annotated tag points at the pre-tag doc closure commit, not
the feature commit, by the project convention established in RC5
closure (doc closure included in the reviewed tag).

#### Post-tag supplemental docs (NOT in the tag)

After the tag was cut, additional documentation-only commits
landed on `release/3.1.1-RC5.4` branch HEAD. These do not modify
the shipped code artifact captured by `v3.1.1-RC5.4`. The current
list of files allowed to differ between tag and branch HEAD lives
in `REVIEW_PACKET.md` §3. External reviewers should treat:

- **Tag `v3.1.1-RC5.4` (peeled `014939eeb`)** as the code artifact
  under review.
- **Branch HEAD** as the review-time supplementary documentation,
  including this section and `REVIEW_PACKET.md`.

`REVIEW_PACKET.md` is the single-page entry point for external
reviewers and explicitly tracks this tag-vs-branch divergence.

### Follow-up status (cumulative across RC5 cycle)

**Remaining** (post-release / RC5.5+ candidates):

- **R1** (Low, ops) — SOC tooling integration for the
  `EXTERNAL_GOVERNANCE_SIMULATE` audit event. A query / alert
  template would let operators get notified on high-frequency
  simulate bursts. Not a release blocker; recorded so it isn't
  lost in the external-review handoff.
- ~~**R5** (Low, audit accuracy) —
  `IngestSchedulerService.pollScheduledProfiles` re-runs
  `IngestAuthorizationService.resolveFolderId(...)` when re-checking
  connector delegation for a delegated profile. If the second
  resolve returns `null` (microsecond-window race where the target
  folder was resolvable at `prepareDelegatedTick` time but
  unresolvable a few statements later), the audit's
  `denialReason` is `CONNECTOR_NOT_DELEGATED` when
  `TARGET_FOLDER_UNRESOLVABLE` would be more accurate.~~ **Resolved
  in RC5.6 (`cee66573e`)** — the second `resolveFolderId` result is
  now extracted into a local and null-checked before reaching
  `canUseConnectorForDelegatedProfileAsUser`; a null result emits
  `TARGET_FOLDER_UNRESOLVABLE` instead, matching `prepareDelegatedTick`
  step 5's existing shape.

**Resolved during RC5 cycle** (for completeness):

| ID | Resolution venue | Description |
|---|---|---|
| R2 | RC5.3 closure doc commit `01fe84ac5` | `docs/MULTI-REPLICA-DEPLOYMENT.md` updated with the new `nemakiware.ingest.delegated.*` properties + leader-failover streak-reset caveat |
| R3 | RC5.4 feature commit `6283afc96` | V7 audit fires on explicit "Simulate (audit)" button (was 800ms debounce) |
| R4 | RC5.4 feature commit `6283afc96` | `autoDisabledSince` malformed → HTTP 400 (was 200 pass-through with WARN) |

At RC5.4 closure: **outstanding follow-ups were R1 and R5; R5 is now resolved in RC5.6 (`cee66573e`).**
- R1 is infra/ops integration that lives outside this repository,
  not NemakiWare code work.
- R5 was a small denialReason-label refactor inside
  `IngestSchedulerService`; safety property was already preserved,
  only the emitted audit label was mislabeled in a microsecond race
  window. Not a release blocker at RC5.4. **Shipped in RC5.6
  (`cee66573e`).**

---

## 3.1.1-RC5.3 — W1 + W2 server-side governance scalability
_Release candidate on `release/3.1.1-RC5.3` (2026-05-20), branched
off `v3.1.1-RC5.2` (`e18e020f6`)._

First RC5.x cycle to **add Java code paths** since the RC5 base.
Resolves the W1 / W2 vNext items by pushing two pieces of work
server-side: V6's "auto-disabled within N days" filter and V7's
multi-principal removal simulation. Both are purely additive — RC5's
existing API response shapes and the existing endpoints are unchanged.

### W1: import-profiles `autoDisabledSince` filter

`GET /v1/admin/import-profiles?autoDisabledSince=ISO-8601` returns
only profiles whose `lastAutoDisabledAt` is `>= cutoff`. Profiles
without a marker are excluded.

**Malformed cutoff behaviour (documented trade-off)**: empty /
missing / unparseable ISO-8601 cutoff is **silently ignored** — the
filter is dropped and the unfiltered list is returned, with a WARN
in the server log. This preserves the no-param call shape (no
breaking change for old clients) and keeps the admin tab usable if
the UI ships a malformed value. The trade-off: a typo in an
admin-only diagnostic query returns the full list rather than 0,
which an operator might briefly misread as "no recent shutdowns" =
"everything looks fine". Because the endpoint is admin-only and the
intended UI driver only ever ships a `Date.toISOString()` value,
the misread risk is low. Operators who prefer strict 400 semantics
can request that change in a follow-up RC; the current behaviour is
deliberately permissive for backward compat.

The V6
UI window now ships the cutoff when both the "only auto-disabled"
filter and a non-zero window are active, so large-profile deployments
fetch only the relevant slice rather than filtering client-side.

### W2: `POST /by-principal/{principalId}/simulate-remove`

Admin-only endpoint that performs V5/V7's sole-route detection
server-side. Body shape:

```json
{
  "repositoryId": "bedroom",
  "expand": true,
  "removePrincipalIds": ["group-a", "group-b"]
}
```

Response partitions matches into `lost` (every matched principal lies
in the removal set — no alternate route) and `kept` (everything
else). Same logic the V7 UI computes client-side; the value is
**CLI / scripting access** + **audit trail**: each invocation logs an
`EXTERNAL_GOVERNANCE_SIMULATE` audit entry with the queried principal,
the removal set, and the lost count. SOC tooling can now correlate
"what-if" questions with subsequent group / ACL changes.

The V7 UI keeps its instant client-side computation for display
responsiveness, but fires the W2 endpoint debounced at 800 ms after
the multi-select settles. Audit captures the operator intent without
adding a round-trip to every keystroke.

**Audit noise trade-off (documented)**: a 5-principal selection
session in the UI typically produces 1 audit entry (the final
settled state after the user stops adjusting for 800 ms). A
power-user who toggles selection repeatedly within shorter windows
can produce more — each 800 ms quiet period after a change yields
one audit. Rough upper bound: one entry per second of active
multi-select tweaking. For SOC tooling consumers this is small
compared to legitimate ingest audit volume, but operators should be
aware that "intent to investigate" produces audit volume, not just
"acted on". A future RC could replace the debounce with an explicit
"Simulate (audit)" button so audit entries map 1:1 to deliberate
operator decisions; tracked as a post-release follow-up.

CLI / scripting access bypasses this entirely — the endpoint is
called explicitly, audit fires once per call, no debounce.

### Audit additions

- New `AuditOperation.EXTERNAL_GOVERNANCE_SIMULATE` enum entry (audit
  contract: additive only, never renamed).
- W2 audit details include:
  `actorUserId`, `principalId`, `expandedPrincipals`, `removePrincipalIds`,
  `lostCount`.

### API contract — additive only, no breaking changes

This section uses precise terms because external reviewers will read
it:

- **New endpoint** (additive): `POST /v1/admin/connectors/by-principal/{id}/simulate-remove`
  — admin-only. Pre-RC5.3 clients are unaffected (they never call it).
- **New optional query param** (additive): `GET /v1/admin/import-profiles?autoDisabledSince=ISO-8601`.
  Pre-RC5.3 clients that omit the param see the same response set
  they did at RC5.2. The param is opt-in per request.
- **New response field** (additive): `ImportProfileDefinition`
  gained `lastAutoDisabledAt` + `lastAutoDisabledReason`. Marshalled
  with `@JsonInclude(NON_NULL)`, so profiles without a marker emit
  the same JSON they did at RC5.2. Pre-RC5.3 clients tolerate the
  fields via `@JsonIgnoreProperties(ignoreUnknown=true)`.
- **No fields removed or renamed.** No endpoint paths changed.
- **Audit enum** (`AuditOperation`, `DenialReason`) gained entries
  only — additive per the existing audit-stability contract.

In short: RC5.3 is **backward-compatible** with RC5.2 — no breaking
changes — but it is NOT "byte-identical" because additive surface
necessarily changes byte-level output for clients that opt in. Old
clients see the same bytes; new clients see strictly more.

### Migration / upgrade

No migration. Both features are additive at the API surface; W1's
default behaviour with no param is identical to RC5.2.

### Tests

- New `ImportProfileSinceFilterTest`: 7 cases (no param /
  empty string / valid cutoff / malformed cutoff fail-safe /
  malformed marker defensive-exclude / future cutoff / repo-scoped
  composition).
- New `ConnectorSimulateRemoveTest`: 11 cases (admin gate /
  required-body validation / sole-route detection / multi-principal
  cascade / response-shape alignment with V3 / GROUP-skip-expand /
  empty-allowedPrincipalIds skip / blank-entry filter).
- Ingest delegation suite: **154 tests, all PASS** (was 136).
- TS check + UI build pass.

### Known post-RC5.3 follow-ups (low priority)

Surfaced by the cumulative closure review. Not release blockers;
recorded so they aren't lost when external review concludes.

- **R1** (doc, ops): SOC tooling integration — add a query / alert
  template for the new `EXTERNAL_GOVERNANCE_SIMULATE` audit event so
  operators get notified when a high-frequency simulate burst
  happens (could indicate either reasonable investigation or a UI
  bug spamming the endpoint).
- **R2** (doc, deployment): `docs/MULTI-REPLICA-DEPLOYMENT.md` lists
  the JVM-local state subsystems that need sticky sessions / leader
  election for multi-replica. The RC5 inactiveCreatorStreak counter
  inside `IngestSchedulerService` is one such state (per-JVM
  HashMap). `docs/MULTI-REPLICA-DEPLOYMENT.md` should be updated to
  list the new properties + the leader-election requirement that
  scheduled delegated profiles already inherit from the existing
  ingest scheduler.
- **R3** (UX, RC5.4 optional): replace V7 UI's 800 ms debounce audit
  fire with an explicit "Simulate (audit)" button so audit entries
  map 1:1 to deliberate operator decisions. Trade-off noted above.
- **R4** (API, RC5.4 optional): `GET import-profiles?autoDisabledSince=`
  malformed cutoff currently pass-through with WARN. Switching to
  400 would be stricter but breaks the "forgiving admin tab" path.
  Operator choice.

### Release / GA operational note

`v3.1.1-RC5.3` is and remains a **release candidate** tag — RC
suffix tags must NOT be promoted to GA by removing a "pre-release"
flag. The promotion path on completion of external review is:

1. Merge `release/3.1.1-RC5.3` into `master` (or whichever GA branch
   the project uses).
2. Cut a **new** annotated tag `v3.1.1` against the merge commit on
   `master`. This is the GA tag.
3. Optionally create a GitHub Release attached to `v3.1.1` (the GA
   tag), separate from any "Pre-release" labels on the RC tags.
4. Existing `v3.1.1-RC5{,.1,.2,.3}` tags stay as internal
   milestones; they're never relabelled to GA. This keeps the
   audit trail of which commit was reviewed and approved at which
   point in the RC cycle.

---

## 3.1.1-RC5.2 — H1-H3 UI polish
_Release candidate on `release/3.1.1-RC5.2` (2026-05-20), branched
off `v3.1.1-RC5.1` (`cc1ac2b54`)._

UI-only polish cycle. **No Java / property / patch / migration / API
contract changes.** Resolves the H1-H3 follow-ups recorded in RC5.1
closure.

### H1: governance picker debounce unmount cleanup

`ConnectorGovernanceTab` adds a `useEffect` return-cleanup that
clears the V8 search debounce `setTimeout` when the tab unmounts.
Eliminates a "setState on unmounted component" warning class. No
behaviour change for live tabs.

### H2: V7 multi-removal selection cap

The "Simulate removing" Select now caps at `SIMULATE_REMOVE_MAX = 10`
principals via Ant Design's `maxCount`. A Tooltip on the label
explains the rationale (picking everything gives the trivial "lose
everything" answer with low operator value). An orange "Reached limit"
Tag appears when the cap is hit so the cap isn't silent.

### H3: V6 window custom days input

The auto-disabled "last N days" Select gains a "Custom..." option.
Selecting it swaps the Select for an `InputNumber` (min=1, max=9999,
addonAfter="d"). A "Done" button snaps back to the preset Select.
G1's count-reset effect now also resets the custom-mode flag so the
filter row stays consistent.

### i18n additions

- `connectorGovernance.simulateRemoveHint` ({{max}}) +
  `connectorGovernance.simulateMaxReached` ({{max}}) — H2
- `importProfileManagement.autoDisabledWindowCustom` +
  `importProfileManagement.autoDisabledWindowDone` — H3
- Parity: 30 `connectorGovernance` keys + 11
  `importProfileManagement.autoDisabled*` keys aligned ja/en.

### Tests + verification

- 136/136 ingest unit tests pass (unchanged — no Java touched)
- TypeScript check + UI build + i18n parity pass
- Live deployment verified all 3 H-keys present in bundle

### Known post-RC5.2 follow-ups

None at this time. RC5.1 W1/W2 (vNext, server-side scalability)
remain as separate scope, not RC5.2 work.

---

## 3.1.1-RC5.1 — Governance dashboard polish + scalability
_Release candidate on `release/3.1.1-RC5.1` (2026-05-20), branched
off `v3.1.1-RC5` (`f47d3273d`)._

UI-only follow-up cycle on top of RC5. **No Java / property / patch /
migration changes** — RC5's scheduled delegated profile contract and
governance API contract are unchanged. RC4.1 → RC5.1 upgrade
behaviour is identical to RC4.1 → RC5 (default-safe).

### G1: auto-disable filter state reset

`ImportProfileManagementTab` — when the "Show only auto-disabled"
filter is on and the admin re-enables the last auto-disabled profile,
`autoDisabledCount` drops to 0, the filter Switch unmounts, but
React state stayed `true`. The table silently rendered empty until
refresh. A `useEffect` now resets the state when the count reaches 0
so the table reverts to the full list automatically.

### G3: pseudo-principal removal-simulation filter

The governance tab's "Simulate removing" dropdown no longer offers
well-known pseudo-principals — `GROUP_EVERYONE`, `anyone`, `Anyone`,
`GROUP_ANYONE`, `authenticated`, `Authenticated`. These are ACL
targets, not group memberships an admin can edit, so simulating
their removal isn't a meaningful operator question. Reduces visual
noise without changing match logic.

### V6: "auto-disabled in last N days" filter

V4's auto-disabled filter gains a window selector (All / 24h / 7d /
30d, default All). When a window is active:
- The count Tag shows `recent/total` (e.g. `2/3`)
- The banner switches to a recent-count phrasing
- Malformed `lastAutoDisabledAt` timestamps fail-shut (excluded)

Lets ops teams investigating an incident surface fresh scheduler
shutdowns without legacy auto-disables creating noise.

### V7: multi-principal removal simulation

The V5 simulate-removal Select goes multi-select. Filter logic
extends from `every === simulateRemove` to
`every ∈ removalSet`, so removing multiple principals together
correctly cascades — e.g. removing the user from both group A and
group B reveals connectors that survive each removal individually
but fall when both are removed (matched via either group only).

### V8: server-side principal search

The governance tab's principal picker no longer issues a single
`limit=500` fetch on mount. Instead:
- An empty `query` fetches the first 50 users + 50 groups
- Every keystroke triggers `onSearch` with a 300 ms debounce
- The fetch passes the typed query to `/user/list?query=` and
  `/group/list?query=` (existing endpoints, existing admin gate)
- Scales to 10k+ principal directories without an upfront fetch
  cost

### B1 fix (acceptance-review regression)

A pre-fix V8 implementation switched the picker from Ant Design
`AutoComplete` to `Select` to gain virtual scrolling. Acceptance
review surfaced that `Select` only accepts values from its options
array — typed pseudo-principals (`anyone`, external-IdP IDs) couldn't
be submitted. The fix reverts the picker to `AutoComplete` while
keeping V8's server-side `onSearch` + 300 ms debounce + 50-per-call
fetch. Virtual scrolling is given up; 50 items is small enough that
DOM cost is negligible.

### i18n

- `importProfileManagement` gains 5 keys (`autoDisabledBannerRecent`,
  `autoDisabledWindow{All,1d,7d,30d}`).
- `connectorGovernance.simulationNote` takes a `{{principals}}`
  placeholder for V7's multi-principal phrasing.
- Parity: 28 `connectorGovernance` keys + 9
  `importProfileManagement.autoDisabled*` keys aligned ja/en.

### Migration / upgrade

No migration. UI-only changes; pre-RC5.1 records read unchanged.

### Tests

- 136/136 ingest unit tests pass (unchanged from RC5 — UI-only).
- Live verification of G1 transition, G3 filter, V6 window logic
  (3 timestamps × 4 windows), V7 cascade simulation, V8 server-side
  search, and B1 free-text round-trip.

### Known post-RC5.1 follow-ups (low priority — RC5.2 candidates)

- **H1**: V8 debounce timer lacks unmount cleanup. Single-tab admin
  UI rarely unmounts, so impact is low; a `useEffect` return-cleanup
  closes the loop.
- **H2**: V7 multi-removal Select has no max-selection cap. Power
  users could pick the whole expansion set and see a noisy "lose
  everything" result. UX guard, not security.
- **H3**: V6 window is a fixed list (All / 24h / 7d / 30d). A custom
  N-days input would handle incident windows that don't fit the
  preset.

### vNext (separate scope — not RC5.2)

- **W1**: V6 server-side filter for very large profile lists (current
  V6 filters client-side).
- **W2**: V7 server-side simulate endpoint — `POST /by-principal/{id}/simulate-remove`
  with a principal-set body and a `lost` array response. Useful for
  CLI / scripting access to the same logic the UI offers.

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
RC4.1-equivalent scheduler behaviour (no-op observable to old
clients). Existing tests retained
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
