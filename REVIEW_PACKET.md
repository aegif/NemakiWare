# NemakiWare v3.1.1-RC5.6 — External Review Packet

Single entry point for the **third-round external review** of the
RC5 cycle. The previous round (`v3.1.1-RC5.5`) shipped with one
low-priority follow-up (R5: scheduler `denialReason` mislabel race)
still open and a repository-wide Playwright spec CSRF gap surfaced
mid-review. RC5.6 closes both items.

- **Code artifact under review** = the annotated tag
  `v3.1.1-RC5.6`.
- **Review supplementary documentation** = files on
  `release/3.1.1-RC5.5` **branch HEAD** that may land after the
  tag is cut (e.g. this very file when re-edited mid-review).
  As of tag time the divergence is zero — see §3.

The previous candidates `v3.1.1-RC5.5` (peeled `dfb912da9`) and
`v3.1.1-RC5.4` (peeled `014939eeb`) **remain as historical tags**
for traceability and are NOT promoted into GA. Nothing has been
force-updated.

---

## 1. Quick reference

| Item | Value |
|---|---|
| **Final candidate tag** | `v3.1.1-RC5.6` |
| Tag annotated object SHA | `f71782ad5b5308728743f5ba13fbc511a5983cd0` |
| Tag peeled commit | `adf8db3b4b2ac6e588b3f93a6b5462d7686ec456` |
| Branch | `release/3.1.1-RC5.5` (RC5.6 lives on the RC5.5 branch — branch name is **not** renamed per release) |
| Branch HEAD at tag time | `adf8db3b4b2ac6e588b3f93a6b5462d7686ec456` (= tag peeled, zero divergence) |
| Base of RC5 cycle | `v3.1.1-RC4.1` (`572aad18b`) |
| **Cumulative diff cmd** | `git diff v3.1.1-RC4.1..v3.1.1-RC5.6` |
| Previous historical candidate | `v3.1.1-RC5.5` (peeled `dfb912da9`) |
| Earlier historical candidate | `v3.1.1-RC5.4` (peeled `014939eeb`) |
| RC5.5 → RC5.6 diff cmd | `git diff v3.1.1-RC5.5..v3.1.1-RC5.6` |

---

## 2. What changed since the previous external review (RC5.5 → RC5.6)

The RC5.5 closure left **R5** (the last cumulative follow-up from
the RC5.4 review) explicitly open as "Low / audit label accuracy /
post-release candidate". Mid-review, an internal review surfaced
that RC5.5's Playwright spec CSRF fix had only been applied to the
3 RC5-area specs and that 2 additional spec files outside the RC5
focus area still failed under CSRF. RC5.6 closes both.

- **R5** (code, low) — `IngestSchedulerService.pollScheduledProfiles`
  extracts the second `resolveFolderId(...)` call into a local
  before the connector delegation re-check. When the resolve
  returns null (folder deleted, ACL revoked, or transient lookup
  failure between scheduler ticks), the audit now emits
  `denialReason=TARGET_FOLDER_UNRESOLVABLE` instead of the prior
  `CONNECTOR_NOT_DELEGATED`. Safety property unchanged (the tick
  is still skipped); the audit label is now accurate. Matches the
  pattern already used in `prepareDelegatedTick` step 5. 2 new
  unit tests pin the fix, 1 of which is a regression guard for
  the legitimate `CONNECTOR_NOT_DELEGATED` path.
- **A2** (test, low) — repo-wide Playwright spec CSRF audit. Of
  the 43 specs issuing state-changing requests, only 2 actually
  needed the fix (Jersey-served `/core/api/v1/cmis/*` and CMIS
  Browser Binding `/core/browser/*` are CSRF-exempt at the servlet
  level). Added `X-Requested-With: XMLHttpRequest` to
  `tests/admin/integration-settings.spec.ts` (12 sites) and
  `tests/admin/purview-atlas-e2e.spec.ts` (7 sites). While in
  `integration-settings.spec.ts`, two pre-existing test drift
  items were resolved as well — stale tab count assertion
  (15 → 17) and a loose `/Connector|コネクタ/i` regex that would
  match both the management and governance tabs (replaced with the
  anchored `/^(コネクタ ベータ|Connectors\s+Beta)$/` pattern
  already used in `connector-profile-management.spec.ts`).
- **RC5.5 post-tag Playwright follow-up** (test, low) — the
  `7f4b268ba` commit (CSRF header + serial mode + tab selector +
  valid sourceSystem fix in the 3 RC5-area spec files) landed
  after the RC5.5 tag was cut. RC5.6 includes it in the
  canonical artifact so reviewers can verify the full E2E spec
  suite against the tag.

There is no public API contract change. There is no DB / patch /
view / Mango index / migration change. There is no new property.

---

## 3. What's on the branch HEAD but NOT in the tag

The tag (`v3.1.1-RC5.6`) and the branch HEAD
(`release/3.1.1-RC5.5`) MAY diverge during the external review
window as supplementary docs land. As of tag time the divergence
is zero — both point at the same commit.

When divergence happens, only the following files are allowed to
differ:

- `REVIEW_PACKET.md`
- `RELEASE_NOTES.md`
- `CLAUDE.md`
- `docs/design/connector-delegation.md` (review-time clarifying additions only)
- `core/src/main/webapp/ui/src/services/externalIngest.ts` (JSDoc only, no runtime change)

Any other file diverging is a bug — please flag it.

External reviewers focused only on the code artifact should check
out the tag and ignore later branch commits.

---

## 4. What's in the v3.1.1-RC5.6 tag (cumulative since RC4.1)

Same RC5 cycle scope as RC5.5, plus the RC5.6 additions above:

- **Scheduled delegated profiles** (RC5 §12.1) — opt-in per-tick
  governance for non-admin profile creators. Default-off.
- **Connector governance view** (RC5 §12.3) +
  **simulate-remove endpoint** (RC5.3 W2) — admin-only audit and
  what-if tooling.
- **Auto-disabled triage UI** (RC5 V1-V4 / RC5.1 G1+G3 / RC5.2 H3
  Custom-N-days) — markers + filter + banner + window control.
- **Server-side auto-disabled profile filtering** (RC5.3 W1) —
  `autoDisabledSince` query param on `GET /v1/admin/import-profiles`
  for large profile lists.
- **R3 explicit Simulate (audit) button** (RC5.4) — V7 audit fires
  on a deliberate button click, not a debounce.
- **R4 strict 400** (RC5.4) for non-empty malformed `autoDisabledSince`.
- **C1 fix** (RC5.5) — the residual epoch-overflow 500 leak now
  also returns 400.
- **H1 safeEmit** (RC5.5) — audit pipeline failures now visible
  via WARN log without breaking the business path.
- **R5 fix** (RC5.6) — scheduler audit `denialReason` accurate
  for the folder-resolution-race edge case.
- **A2 cleanup** (RC5.6) — Playwright spec CSRF / regex / count
  drift resolved repo-wide.

For the full per-RC narrative see `RELEASE_NOTES.md` 7 sections
(`3.1.1-RC5` → `3.1.1-RC5.6`) and
`docs/design/connector-delegation.md` §12.1 - §12.15.

---

## 5. Acceptance status summary (RC5.6)

- **Blocking findings**: 0
- **Unit tests** (precise scope):
  - **Focused set** (RC5 cycle delegation / governance / scheduler
    / profile pipeline): **159 tests across 14 test classes PASS**
    — `DelegatedCallContextFactoryTest`,
    `IngestSchedulerDelegatedRunTest` (10 cases — RC5.5 8 + R5 +2),
    `ImportProfileSchedulerGateTest`,
    `ConnectorByPrincipalGovernanceTest`,
    `IngestSchedulerDelegationSkipTest`,
    `ImportProfileOwnershipTransferTest`,
    `ExternalIngestControllerGateTest`,
    `ExternalIngestControllerTest`,
    `IngestAuthorizationServiceTest`,
    `ConnectorDefinitionControllerPartialPutTest`,
    `IngestWebhookGraphValidationTest`,
    `ImportProfileDefinitionTest`,
    `ImportProfileSinceFilterTest` (10 cases, unchanged from RC5.5),
    `ConnectorSimulateRemoveTest`.
  - **Broader pattern** `mvn test -Dtest="*Ingest*Test*,*Connector*Test*,*Profile*Test*,Delegated*Test"`
    was not re-run for RC5.6 because RC5.6 only touches files inside
    the focused set (verified: see §2 change scope). The RC5.5
    closure run returned **289 tests** all PASS; RC5.6 adds +2
    inside the focused set, expected RC5.6 broader-pattern count
    is 291.
- **Playwright E2E** (RC5-area + RC5.6 A2 scope):
  - `tests/api/ingest-pipeline-e2e.spec.ts` + `tests/api/external-ingest-api.spec.ts`
    + `tests/admin/connector-profile-management.spec.ts`: **35/35 PASS**
    (unchanged from RC5.5 post-tag fix `7f4b268ba`)
  - `tests/admin/integration-settings.spec.ts`: **17/17 PASS**
    (was 8 failed in RC5.5 — CSRF + stale tab count + loose regex)
  - `tests/admin/purview-atlas-e2e.spec.ts`: **17 PASS / 25 skip**
    (skips are Atlas server not configured in env, intentional;
    CSRF 403s all gone)
- **Live verification** (carried from RC5.5):
  - C1: malformed string → 400, epoch overflow → 400, valid ISO →
    200, empty → 200
  - C1: corrupted overflow marker on a profile → 200 with that
    profile excluded
  - V3 governance: authenticated non-admin → 403, bad credential →
    401
  - W2 simulate-remove: audit entry contains expected fields, no
    secrets in audit details
  - H1: silent audit catch removed from 5 sites; the 2 fall-through
    catches in `resolvePrincipalType` are intentional (not audit)
  - Default-off scheduler still scheduled=0
- **R5 verification** (RC5.6): unit tests cover the audit-level
  behaviour with `ArgumentCaptor` on `AuditLogger.logOperation`.
  No live test exists because reproducing the race requires
  injecting a folder deletion between two scheduler ticks; the
  refactor is mechanical and the unit test pins the audit emit
  precisely.
- **API contract**: additive only since RC4.1 baseline. No
  breaking change in RC5.6.
- **Patch / view / Mango index / migration / DB bootstrap**:
  unchanged since RC4.1 (cumulative zero diff in those areas).
- **UI forbidden path `/core/ui/dist/`**: 0 hit in cumulative diff.

---

## 6. Remaining follow-ups (post-RC5.6, not blocking review)

| ID | Severity | Scope | Description |
|---|---|---|---|
| **R1** | Low (ops) | NemakiWare repo external | SOC tooling integration for `EXTERNAL_GOVERNANCE_SIMULATE` audit event |
| **H2** | Medium (test coverage) | UI test | R3 explicit Simulate button has no Playwright / React test |
| **M2** | Medium (security hardening) | Server | `simulate-remove` request body has no size limit |
| **M3** | Low (scale) | Server | `buildMatches` full-scan over `connectorDefinitionService.list()` on every governance call |
| **L1** | Nit (UX) | UI | R3 button state reset uses array-reference equality |
| **L2** | Nit (defensiveness) | Server | `buildMatches` could null-check `connectorDefinitionService.list()` defensively |

**Resolved during RC5 cycle**:

| ID | Resolution venue | Description |
|---|---|---|
| R2 | RC5.3 closure doc commit `01fe84ac5` | `docs/MULTI-REPLICA-DEPLOYMENT.md` updated |
| R3 | RC5.4 feature commit `6283afc96` | V7 audit explicit button |
| R4 | RC5.4 feature commit `6283afc96` | `autoDisabledSince` malformed → HTTP 400 |
| C1 | RC5.5 feature commit `9bb5bcf83` | Epoch overflow → HTTP 400 |
| H1 | RC5.5 feature commit `9bb5bcf83` | safeEmit helper, audit silent catches eliminated |
| M1 | RC5.5 doc | REVIEW_PACKET test scope precision |
| M4 | RC5.5 doc | design doc §12.6 / §12.9 strikethrough |
| **R5** | **RC5.6 feature commit `cee66573e`** | **denialReason accuracy via local extract + early-return** |
| **A2** | **RC5.6 spec commit `dc0ba6dac` + Low fix in doc commit** | **Repo-wide Playwright spec CSRF / tab count / regex hygiene** |

---

## 7. Promotion path (operational)

`v3.1.1-RC5.6` is and remains a release candidate. The GA path
is unchanged from RC5.5:

1. External review (round 3) concludes with approval.
2. Merge `release/3.1.1-RC5.5` into `master`.
3. Cut a **new** annotated tag `v3.1.1` against the merge commit
   on `master` — never relabel an RC tag.
4. Optionally create a single GitHub Release attached to `v3.1.1`.
5. The RC tags (`v3.1.1-RC5`, `.1`, `.2`, `.3`, `.4`, `.5`, `.6`)
   stay as internal milestones; they keep the cycle's audit trail
   intact.

---

## 8. Re-send delta summary (what reviewers should focus on)

If you reviewed RC5.5 already, the smallest possible review for
RC5.6 is:

```bash
git diff v3.1.1-RC5.5..v3.1.1-RC5.6
```

This produces a focused set of changes (~8 files):

- `core/src/main/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerService.java` (R5)
- `core/src/test/java/jp/aegif/nemaki/rest/ingest/IngestSchedulerDelegatedRunTest.java` (R5 +2 tests)
- 5 Playwright spec files (A2 + RC5.5 post-tag follow-up + Low)
- Doc files: `CLAUDE.md`, `RELEASE_NOTES.md`, `REVIEW_PACKET.md`,
  `docs/design/connector-delegation.md`

If you are reviewing RC5 cold for the first time, use the
cumulative diff in §1.

---

## 9. Files external reviewers usually start with

| Purpose | File |
|---|---|
| Re-send overview (this file) | `REVIEW_PACKET.md` |
| What changed and why (per RC) | `RELEASE_NOTES.md` (7 sections RC5 → RC5.6) |
| Design rationale | `docs/design/connector-delegation.md` (§12.1 - §12.15) |
| Multi-replica operational notes | `docs/MULTI-REPLICA-DEPLOYMENT.md` |
| Project-internal navigation (Japanese) | `CLAUDE.md` |
| API entry points | `ConnectorDefinitionController.java`, `ImportProfileDefinitionController.java`, `IngestSchedulerService.java`, `AuditEmitSupport.java` |
| Test coverage proof | `core/src/test/java/jp/aegif/nemaki/rest/ingest/*Test.java` (159 focused tests) |
