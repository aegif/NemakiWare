# NemakiWare v3.1.1-RC5.5 — External Review Packet (re-send)

Single entry point for the **second-round external review** of the
RC5 cycle. The previous round (`v3.1.1-RC5.4`) returned one
blocking finding (C1: epoch-overflow HTTP 500 leak); this round
fixes it.

- **Code artifact under review** = the annotated tag
  `v3.1.1-RC5.5` (peeled commit `dfb912da9`).
- **Review supplementary documentation** = files on
  `release/3.1.1-RC5.5` **branch HEAD** that may land after the
  tag is cut (e.g. this very file when re-edited mid-review). The
  current state has zero divergence — see §3.

The previous candidate `v3.1.1-RC5.4` **remains as a historical
tag** for traceability and is NOT promoted into GA. Its peeled
commit is still `014939eeb`; nothing has been force-updated.

---

## 1. Quick reference

| Item | Value |
|---|---|
| **Final candidate tag** | `v3.1.1-RC5.5` |
| Tag annotated object SHA | `bd967193da1f522dc9fed47a24b8c2febfd5fdba` |
| Tag peeled commit | `dfb912da9639f9679aea5b2f0167621397cb67f7` |
| Branch | `release/3.1.1-RC5.5` |
| Branch HEAD | latest commit on branch (see `git log`) |
| Base of RC5 cycle | `v3.1.1-RC4.1` (`572aad18b`) |
| **Cumulative diff cmd** | `git diff v3.1.1-RC4.1..v3.1.1-RC5.5` |
| Previous (now historical) candidate | `v3.1.1-RC5.4` (peeled `014939eeb`) |
| RC5.4 → RC5.5 diff cmd | `git diff v3.1.1-RC5.4..v3.1.1-RC5.5` |

---

## 2. What changed since the previous external review (RC5.4 → RC5.5)

The RC5.4 review surfaced one blocker (C1) and three
recommendations (H1, M1, M4). RC5.5 addresses all four.

- **C1** (blocker, code) — `GET /v1/admin/import-profiles?autoDisabledSince=`
  with a Long-epoch-overflow ISO-8601 instant (e.g.
  `+999999999-12-31T23:59:59Z`) returned HTTP 500 in RC5.4. RC5.5
  now catches `ArithmeticException` alongside
  `DateTimeParseException` on both the cutoff parse and the
  per-profile marker parse. Cutoff overflow → 400. Profile-side
  overflow → defensive exclude (one corrupted record no longer
  500s the entire list). 2 new unit tests pin the behaviour.
- **H1** (recommendation, code) — five `catch (RuntimeException ignored)`
  audit emit sites collapsed to `jp.aegif.nemaki.audit.AuditEmitSupport.safeEmit(...)`.
  Audit failure still cannot break the business path (the
  invariant the original silent catches were defending), but a
  WARN line now records `op + actor + object + exceptionClass + exceptionMessage`.
  The audit `details` map is deliberately NOT logged so that
  secrets / tokens / credentials cannot leak into the general
  application log.
- **M1** (recommendation, doc) — REVIEW_PACKET.md now distinguishes
  the focused 14-test-class / 157-test set from the broader
  pattern that returns 287 tests when re-run.
- **M4** (recommendation, doc) — `docs/design/connector-delegation.md`
  §12.6 and §12.9 (historical post-RC5/RC5.1 follow-up lists)
  now carry the ~~strikethrough~~ + "resolved in RC5.x" treatment
  already used for §12.10 / §12.11 / §12.12.

The RC5.4 review also recorded two non-blocking findings (H2 / M2)
which are **NOT included** in RC5.5 — see §5.

---

## 3. What's on the branch HEAD but NOT in the tag

The tag (`v3.1.1-RC5.5` peeled `dfb912da9`) and the branch HEAD
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

## 4. What's in the v3.1.1-RC5.5 tag (cumulative since RC4.1)

Same RC5 cycle scope as RC5.4, plus the RC5.5 corrections above:

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

For the full per-RC narrative see `RELEASE_NOTES.md` 6 sections
(`3.1.1-RC5` → `3.1.1-RC5.5`) and
`docs/design/connector-delegation.md` §12.1 - §12.14.

---

## 5. Acceptance status summary (RC5.5)

- **Blocking findings**: 0
- **Unit tests** (precise scope):
  - **Focused set** (RC5 cycle delegation / governance / scheduler
    / profile pipeline): **157 tests across 14 test classes PASS**
    — `DelegatedCallContextFactoryTest`,
    `IngestSchedulerDelegatedRunTest`,
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
    `ImportProfileSinceFilterTest` (10 cases — RC5.4 8 + C1 +2),
    `ConnectorSimulateRemoveTest`.
  - **Broader pattern**: `mvn test -Dtest="*Ingest*Test*,*Connector*Test*,*Profile*Test*,Delegated*Test"`
    returned **289 tests** all PASS at the RC5.5 re-send closure
    check (was 287 at RC5.4 closure; +2 from the C1 tests added to
    `ImportProfileSinceFilterTest` which matches the `*Profile*Test*`
    pattern). The 132-test delta beyond the focused 157 set is
    other repository/type/import-export tests that share the
    naming pattern — none are intentionally excluded.
- **Live verification**:
  - C1: malformed string → 400, epoch overflow → 400 (was 500),
    valid ISO → 200, empty → 200
  - C1: corrupted overflow marker on a profile → 200 with that
    profile excluded
  - V3 governance: authenticated non-admin → 403, bad credential → 401
  - W2 simulate-remove: audit entry contains expected fields, no
    secrets in audit details
  - H1: silent audit catch removed from 5 sites; the 2 fall-through
    catches in `resolvePrincipalType` are intentional (not audit)
  - Default-off scheduler still scheduled=0
- **API contract**: additive only, no breaking changes for callers
  using valid input. RC5.4 made `autoDisabledSince` strict 400 on
  non-empty malformed; RC5.5 completes the contract by also
  covering the overflow edge.
- **Patch / view / Mango index / migration / DB bootstrap**:
  unchanged since RC4.1 (cumulative zero diff in those areas).
- **UI forbidden path `/core/ui/dist/`**: 0 hit in cumulative diff.

---

## 6. Remaining follow-ups (post-RC5.5, not blocking review)

These are explicitly NOT blockers. The previous RC5.4 review and
the current RC5.5 review both surfaced low-/medium-priority items
recorded here.

| ID | Severity | Scope | Description |
|---|---|---|---|
| **R1** | Low (ops) | NemakiWare repo external | SOC tooling integration for `EXTERNAL_GOVERNANCE_SIMULATE` audit event |
| **R5** | Low (audit label accuracy) | `IngestSchedulerService` | `denialReason` mislabel in a microsecond-window race during connector re-check |
| **H2** | Medium (test coverage) | UI test | R3 explicit Simulate button has no Playwright / React test. Desirable but not blocker; the underlying audit + API behaviour IS covered by Java tests |
| **M2** | Medium (security hardening) | Server | `simulate-remove` request body has no size limit; admin-gated, so abuse path requires a compromised admin token. Cap value + contract + test would need to land together |
| **M3** | Low (scale) | Server | `buildMatches` full-scan over `connectorDefinitionService.list()` on every governance call |
| **L1** | Nit (UX) | UI | R3 button state reset uses array-reference equality (`useEffect([simulateRemove])`); a JSON-stringified nonce would be marginally more robust |
| **L2** | Nit (defensiveness) | Server | `buildMatches` could null-check `connectorDefinitionService.list()` defensively |

**Resolved during RC5 cycle**:

| ID | Resolution venue | Description |
|---|---|---|
| R2 | RC5.3 closure doc commit `01fe84ac5` | `docs/MULTI-REPLICA-DEPLOYMENT.md` updated |
| R3 | RC5.4 feature commit `6283afc96` | V7 audit explicit button |
| R4 | RC5.4 feature commit `6283afc96` | `autoDisabledSince` malformed → HTTP 400 |
| C1 | **RC5.5 feature commit `9bb5bcf83`** | **Epoch overflow → HTTP 400** |
| H1 | RC5.5 feature commit `9bb5bcf83` | safeEmit helper, audit silent catches eliminated |
| M1 | RC5.5 doc | REVIEW_PACKET test scope precision (this file) |
| M4 | RC5.5 doc | design doc §12.6 / §12.9 strikethrough |

---

## 7. Promotion path (operational)

`v3.1.1-RC5.5` is and remains a release candidate. The GA path
is unchanged from RC5.4:

1. External review (round 2) concludes with approval.
2. Merge `release/3.1.1-RC5.5` into `master`.
3. Cut a **new** annotated tag `v3.1.1` against the merge commit
   on `master` — never relabel an RC tag.
4. Optionally create a single GitHub Release attached to `v3.1.1`.
5. The RC tags (`v3.1.1-RC5`, `.1`, `.2`, `.3`, `.4`, `.5`) stay
   as internal milestones; they keep the cycle's audit trail intact.

---

## 8. Re-send delta summary (what reviewers should focus on)

If you reviewed RC5.4 already, the smallest possible review for
RC5.5 is:

```bash
git diff v3.1.1-RC5.4..v3.1.1-RC5.5
```

This produces a focused set of changes (~10 files, mostly
ImportProfileDefinitionController + ConnectorDefinitionController
+ ExternalIngestController + IngestSchedulerService + new
AuditEmitSupport + ImportProfileSinceFilterTest + doc files). All
listed in §2 above.

If you are reviewing RC5 cold for the first time, use the
cumulative diff in §1.

---

## 9. Files external reviewers usually start with

| Purpose | File |
|---|---|
| Re-send overview (this file) | `REVIEW_PACKET.md` |
| What changed and why (per RC) | `RELEASE_NOTES.md` (6 sections RC5 → RC5.5) |
| Design rationale | `docs/design/connector-delegation.md` (§12.1 - §12.14) |
| Multi-replica operational notes | `docs/MULTI-REPLICA-DEPLOYMENT.md` |
| Project-internal navigation (Japanese) | `CLAUDE.md` |
| API entry points | `ConnectorDefinitionController.java`, `ImportProfileDefinitionController.java`, `IngestSchedulerService.java`, `AuditEmitSupport.java` |
| Test coverage proof | `core/src/test/java/jp/aegif/nemaki/rest/ingest/*Test.java` (157 focused tests, 287 broader pattern) |
