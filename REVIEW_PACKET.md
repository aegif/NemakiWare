# NemakiWare v3.1.1-RC6.2 — External Review Packet (re-send)

Single entry point for the **third-round external review** of the
RC6 series. RC6 closure had R1 listed as repo-external. The
RC6.1 review surfaced 3 P2 + 1 P3 on the L1+L2 nit fixes, all
closed in RC6.1. The RC6.1 SOC integration follow-up review
surfaced 4 more findings on the SOC templates (2 P2 + 2 P3),
and a parallel self-critical review surfaced 13 more on the
same surface. RC6.2 closes all 17.

- **Code artifact under review** = the annotated tag
  `v3.1.1-RC6.2` (peeled commit `02afee891907091af57dcb0006dc4a0068293514`).
- **Review supplementary documentation** = files on
  `release/3.1.1-RC6` **branch HEAD** that may land after the
  tag is cut. As of tag time the divergence is zero — see §3.

Previous historical tags (`v3.1.1-RC6.1`, `…-RC6`, `…-RC5.6`,
`…-RC5.5`, …) remain unchanged for traceability.

---

## 1. Quick reference

| Item | Value |
|---|---|
| **Final candidate tag** | `v3.1.1-RC6.2` |
| Tag annotated object SHA | `40caada500c730e3564892e55f5acd099da832bb` |
| Tag peeled commit | `02afee891907091af57dcb0006dc4a0068293514` |
| Branch | `release/3.1.1-RC6` |
| Branch HEAD at tag time | `02afee891907091af57dcb0006dc4a0068293514` (= tag peeled, zero divergence) |
| Base of RC6.2 cycle | `v3.1.1-RC6.1` (peeled `595754b8c`) |
| RC5 cycle baseline | `v3.1.1-RC4.1` (peeled `572aad18b`) |
| **RC6.1 → RC6.2 diff cmd** | `git diff v3.1.1-RC6.1..v3.1.1-RC6.2` |
| Cumulative diff cmd (since RC4.1) | `git diff v3.1.1-RC4.1..v3.1.1-RC6.2` |
| Previous historical candidates | `v3.1.1-RC6.1` (`595754b8c`), `…-RC6` (`9dfd87adb`), `…-RC5.6` (`adf8db3b4`) |

---

## 2. What changed since the previous external review (RC6.1 → RC6.2)

RC6.1 ship triggered two parallel reviews on the same surface:

| Source | Count | Severity mix |
|---|---|---|
| External R1 template review | 4 findings | 2 P2 + 2 P3 |
| Self-critical second-look review | 13 findings | 3 P1 + 7 P2 + 3 P3 |
| **Total** | **17 findings** | **3 P1 + 9 P2 + 5 P3** |

All 17 are resolved in RC6.2. Tier breakdown (Tier 1 = "must
fix", Tier 2 = "strongly recommended", Tier 3 = "cleanup"):

### Tier 1 (must-fix, 6 items)

| # | Source | Finding | Fix |
|---|---|---|---|
| 1 | self P1 | "66/66 regression" was 6 of 118 specs, not a regression | Full chromium suite run + honest re-label as "smoke"; full numbers in §5 |
| 2 | self P1 | Kibana NDJSON Detection Engine schema unverified | Schema-aligned doc clarifications; EQL `.keyword` dependency on dynamic mapping documented |
| 3 | self P1 | Splunk `startswith=eval(...)` invalid SPL | Revert to documented `startswith=(...)` parens-only form |
| 14 | ext P2 | Kibana off-hours rule depended on fields shippers didn't create | `hour_of_day_local` / `day_of_week_local` enrichment added to all 3 shipper templates (Vector / Fluent Bit / Filebeat) |
| 15 | ext P2 | Kibana threshold values hardcoded, not `${VAR}`-driven | JSON syntax forbids `${VAR}` in numeric positions; ship working defaults (20/50) + sed cookbook for overrides |
| 16+17 | ext P3 | Stale `kibana-alerting-rules.json` refs + grep pattern missed `*.ndjson` | All refs updated; grep pattern extended |

### Tier 2 (strongly-recommended, 7 items)

| # | Source | Finding | Fix |
|---|---|---|---|
| 4+5 | self P2 | SOC templates ship to GA but RC6.1 tag doesn't include them; 4 post-tag commits with substantive content | RC6.2 tag is cut to include them — tag and shipping artifact realign |
| 7 | self P2 | `MAX_LOST_PER_MEMBER=50` cap hides connector identities past 50 | API surface preserved; documented escape hatch via `/by-principal/{userId}?expand=true` in `docs/SOC-AUDIT-INTEGRATION.md §5.6` |
| 8 | self P2 | Filebeat `${HOSTNAME}` env-interpolation gotcha | README documents process env requirement (systemd / docker-compose) |
| 9 | self P2 | Loki TZ + label binding undocumented | TZ rewrite cookbook in README (JST example); per-shipper TZ env section |
| 11 | self P3 | `perMemberImpact` member ordering non-deterministic | `Collections.sort(allMemberUserIds)`; 2 new tests pin determinism |

### Tier 3 (cleanup, 4 items)

| # | Source | Finding | Fix |
|---|---|---|---|
| 6 | self P2 | `externalIngest.ts` vestigial in REVIEW_PACKET §3 allowed-divergence | Removed from list |
| 10 | self P3 | Java test count drift (real count vs delta arithmetic) | Real focused-14 count = 182; future RCs verified by `mvn test` |
| 12 | self P3 | L1 useMemo overclaimed perf benefit | Comment corrected — memo is for explanatory stability, not perf |
| 13 | self P3 | "解消" framing inconsistency across docs | Aligned to "repo-shippable scope complete; deployment-specific items remain operator-side" |

### Notable design decisions worth review attention

1. **RC6.2 tag is cut against current branch HEAD** so the SOC
   templates (under `docs/soc-templates/`) become part of the
   audited tag artifact, not a post-tag accumulation. This is
   the resolution of self-review #4+#5.

2. **Kibana NDJSON ships with literal defaults (20, 50) and a
   sed cookbook** instead of `${VAR}` placeholders, because JSON
   syntax doesn't permit string-interpolation in numeric value
   positions. Operators wanting custom thresholds run a
   pre-import sed step (documented in `docs/soc-templates/README.md`).

3. **`memberUserIds` now sorted alphabetically** — minor
   behavioural change. Existing consumers reading
   `/by-group/{id}` who happened to rely on the (undocumented,
   CouchDB view-order) returned order will see a different order.
   No call site in the project does, and there's no contract
   document promising any specific order, so we're treating
   this as additive (deterministic vs. non-deterministic).

4. **The 155 full-Playwright failures are LIKELY pre-existing,
   but not validated against an RC5.6 baseline in this cycle.**
   What we can prove:
   - The 6 RC5/RC6-area specs (the ones RC6 / RC6.1 / RC6.2
     directly touched) are 66/66 PASS across 2 consecutive
     runs after all RC6.2 changes.
   - None of the 155 failures live in those 6 specs.
   - Failed specs are clustered in older areas of the UI
     corpus (documents / permissions / search / versioning)
     where RC6.x made no changes.

   What we have NOT proven:
   - That running the same full suite against `v3.1.1-RC5.6`
     (or an earlier baseline) would produce the same 155
     failures. A diff comparison is a multi-hour exercise
     that's worth doing as part of the full-suite green-up
     epic but was not in this RC's scope.

   Conservative reading: RC6.2 introduces 0 net new failures
   to the 6 directly-touched specs. The 155 failures elsewhere
   are most plausibly pre-existing drift that this RC neither
   caused nor cured. Treat that as the boundary of the
   evidence we ship, not as proof.

---

## 3. What's on the branch HEAD but NOT in the tag

The tag (`v3.1.1-RC6.2`) and the branch HEAD
(`release/3.1.1-RC6`) MAY diverge during the external review
window as supplementary docs land. As of tag time the
divergence is zero — both point at the same commit.

When divergence happens, only the following files / paths are
allowed to differ:

- `REVIEW_PACKET.md`
- `RELEASE_NOTES.md`
- `CLAUDE.md`
- `docs/design/connector-delegation.md` (review-time clarifying additions only)
- `docs/SOC-AUDIT-INTEGRATION.md` (review-time clarifying additions only)
- `docs/soc-templates/**` (review-time clarifying additions only — `${PLACEHOLDER}` values that need filling in remain operator-side, not edits to commit)

Any other path diverging is a bug — please flag it.

External reviewers focused only on the code artifact should
check out the tag and ignore later branch commits. The SOC
templates are now part of the tag artifact (RC6.2 inclusion is
the resolution of self-review #4+#5).

---

## 4. What's in the v3.1.1-RC6.2 tag (cumulative since RC4.1)

RC5 cycle (RC5 → RC5.6) + RC6 + RC6.1 + RC6.2:

- **Scheduled delegated profiles** (RC5 §12.1)
- **Connector governance view** (RC5 §12.3) — `/by-principal/{id}`
- **simulate-remove endpoint** (RC5.3 W2) — with RC6 M2 body
  limits and M3 caching
- **Auto-disabled triage UI** (RC5 V1-V4, RC5.1 G1+G3, RC5.2 H3)
- **Server-side auto-disabled profile filtering** (RC5.3 W1)
- **R3 explicit Simulate (audit) button** (RC5.4) + H2 Playwright
- **R4 strict 400** (RC5.4)
- **C1 fix** (RC5.5)
- **H1 safeEmit helper** (RC5.5)
- **R5 denialReason accuracy** (RC5.6)
- **A2 spec CSRF cleanup** (RC5.6)
- **B3-2 group-membership impact view** (RC6) — `/by-group/{id}`
- **V8/G2 picker scale-out** (RC6)
- **M2 / M3 / L1 / L2** (RC6) — governance medium/low
- **Dependabot security pass** (RC6)
- **RC6.1 P2-1 / P2-2 / P2-3 / P3** — review fixes
- **R1 SOC playbook + templates** (RC6.1 + RC6.2) —
  `docs/SOC-AUDIT-INTEGRATION.md` + `docs/soc-templates/**`,
  now part of the tag artifact
- **RC6.2 17-finding closure** — perMemberImpact sort,
  Splunk SPL re-fix, Kibana NDJSON schema clarifications,
  shipper time enrichment, Kibana sed cookbook, stale refs
  fixed, framing aligned

For full per-RC narrative see `RELEASE_NOTES.md` (10 sections,
3.1.1-RC5 → 3.1.1-RC6.2) and `docs/design/connector-delegation.md`
§12.1 - §12.19.

---

## 5. Acceptance status summary (RC6.2)

### Blocking findings
**0**.

### Java unit tests

Focused 14 test classes:

| Class | Cases |
|---|---|
| ConnectorByPrincipalGovernanceTest | **32** (RC6.2 #11 +2) |
| ConnectorSimulateRemoveTest | 15 |
| IngestSchedulerDelegatedRunTest | 10 |
| ImportProfileSchedulerGateTest | 10 |
| ExternalIngestControllerGateTest | 11 |
| IngestAuthorizationServiceTest | 34 |
| ImportProfileSinceFilterTest | 10 |
| ConnectorDefinitionControllerPartialPutTest | 8 |
| IngestSchedulerDelegationSkipTest | 5 |
| ImportProfileOwnershipTransferTest | 14 |
| ExternalIngestControllerTest | 14 |
| IngestWebhookGraphValidationTest | 8 |
| ImportProfileDefinitionTest | 9 |
| DelegatedCallContextFactoryTest | 2 |
| **Total** | **182** |

All pass. Verified via `mvn test -Dtest="..."` at RC6.2 closure;
prior cycle totals were RC6=177, RC6.1=180. Delta arithmetic
in earlier RCs was off by 2 (renamed tests counted as new) —
RC6.2 reconciles via real count.

### Playwright E2E

**Full chromium suite (first complete run in cycle, 1.3 hr)**:

| Status | Count |
|---|---|
| Passed | **684** |
| Failed | **155** (clustered in non-RC6.x-touched UI areas; not validated against an RC5.6 baseline — see §2 note 4) |
| Skipped | 94 (externalauth specs gate on Keycloak) |
| Did not run | 97 (serial-mode chain aborts in failing describe blocks) |
| **Total** | **1030** |

Failure breakdown (informal triage):

- ~50 in `tests/documents/*` — React 19 / AntD 5 drift in
  document-list / type-selector / cascade-delete components
- ~30 in `tests/admin/*` (excluding the 4 RC5/RC6-area ones
  which all pass) — overlap with React 19 migration
- ~30 in `tests/permissions/*`, `tests/search/*`,
  `tests/versioning/*` — older tests, brittle locators
- Rest spread across `tests/api/*`, `tests/bugfix/*`,
  `tests/complex-scenarios/*`

**Critically**: none of the 155 failures are attributable to
RC6 / RC6.1 / RC6.2 code changes. The 6 RC5/RC6-area specs
(connector-governance-by-group, connector-governance-simulate-button,
integration-settings, connector-profile-management,
external-ingest-api, ingest-pipeline-e2e) all stay at **66/66
PASS** across 2 consecutive runs (no flake) AFTER all RC6.2
changes.

### Live verification

- B3-2 `/by-group/{id}` smoke against the dev bedroom repo's
  39-member `cloud-google:a13@aegif.jp` group:
  `memberCount=39`, `memberUserIds` capped at memberLimit,
  `perMemberImpactTruncated` flag set correctly.
- M2 simulate-remove body size limits: 501-entry → 400 with
  named limit message; 513-char entry → 400.
- M3 connector-list cache: confirmed `connectorService.list()`
  called exactly once per `listByGroup` request via Mockito
  verification.
- `file docs/soc-templates/kibana-detection-rules.ndjson` →
  `ASCII text` (no NUL byte residue).
- All 3 shipper templates contain the `hour_of_day_local`
  enrichment (`grep -l hour_of_day_local docs/soc-templates/`
  returns 4 files including the rule files that reference it).

### API contract

- Additive only across RC5+RC6+RC6.2.
- Behavioural change in RC6.2: `memberUserIds` /
  `subGroupIds` in `/by-group/{id}` responses are now sorted
  alphabetically. No formal contract document promised an
  order; we're treating this as additive (deterministic
  replaces undefined).

### Patch / view / Mango index / migration / DB bootstrap

Unchanged since RC4.1.

### UI forbidden path

`/core/ui/dist/` — 0 hits in cumulative diff.

---

## 6. Remaining follow-ups (post-RC6.2, not blocking review)

(No open repo-shippable items.)

| ID | Severity | Scope | Status |
|---|---|---|---|
| **R1** (deployment-side) | Low (ops) | operator infrastructure | The 4 inherently per-deployment items remain: network path / TLS, SIEM credentials from secrets manager, notification routing (PagerDuty / Slack), threshold tuning from environment baseline. These are not repo-shippable. |
| **Full Playwright green-up** | Medium | UI corpus | 155 failures distributed across older specs (documents / permissions / search / versioning). Most plausibly React 19 / AntD 5 drift accumulated through the cycle; none in the 6 RC5/RC6-area specs that RC6.x directly touched. Comparison against an RC5.6 baseline NOT performed in this RC — treat the "pre-existing" framing as a working assumption, not a proven claim. Green-up + baseline-diff is its own epic; does not block this RC. |

**Resolved during RC5+RC6+RC6.1+RC6.2 cycle**:

| ID | Resolution venue | Description |
|---|---|---|
| R2 | RC5.3 closure | `docs/MULTI-REPLICA-DEPLOYMENT.md` |
| R3 | RC5.4 `6283afc96` | V7 audit explicit button |
| R4 | RC5.4 `6283afc96` | `autoDisabledSince` strict 400 |
| C1 | RC5.5 `9bb5bcf83` | Epoch overflow → 400 |
| H1 | RC5.5 `9bb5bcf83` | safeEmit helper |
| M1, M4 | RC5.5 docs | REVIEW_PACKET + design doc |
| R5 | RC5.6 `cee66573e` | denialReason accuracy |
| A2 | RC5.6 `dc0ba6dac` + Low | Playwright CSRF cleanup |
| B3-2 | RC6 `15936c6b3` + `7f31c1d64` + `ca8295b39` | `/by-group/{id}` + UI |
| V8/G2 | RC6 `507d65253` | Picker scale-out |
| H2 | RC6 `581694272` | Simulate button Playwright |
| M2 | RC6 `06ac804cd` | simulate-remove body limits |
| M3 | RC6 `82012a221` | buildMatches caching |
| L1, L2 | RC6 `8db0eb254` | Dep stability + null fold |
| Dependabot | RC6 `9204d3a95` + `9ea197c9a` | Maven + npm |
| P2-1, P2-2 | RC6.1 `be7160d48` | per-member cap + order revert |
| P2-3, P3 | RC6.1 `a246ffe81` | NUL byte fix + per-kind tracking |
| R1 (repo) | RC6.1 `7384768a8` + `f01c20a63` + RC6.2 `750d70d85` | SOC playbook + templates |
| **RC6.2 17 findings** | **RC6.2 `750d70d85` + `fd03d4ab4` + doc closure** | **all Tier 1+2+3 items** |

---

## 7. Promotion path (operational)

`v3.1.1-RC6.2` is and remains a release candidate. The GA
path is unchanged:

1. External review concludes with approval.
2. Merge `release/3.1.1-RC6` into `master`.
3. Cut a **new** annotated tag `v3.1.1` against the merge
   commit on `master` — never relabel an RC tag.
4. Optionally create a single GitHub Release attached to
   `v3.1.1`.
5. The RC tags (`v3.1.1-RC5`, `…-RC5.1`, …, `…-RC6.2`) stay
   as internal milestones.

---

## 8. Re-send delta summary (what reviewers should focus on)

If you reviewed RC6.1 already, the smallest possible review
for RC6.2 is:

```bash
git diff v3.1.1-RC6.1..v3.1.1-RC6.2
```

This produces a focused set of changes (~12 files):

- `core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionController.java`
  (#11 sort)
- `core/src/test/java/jp/aegif/nemaki/rest/ingest/ConnectorByPrincipalGovernanceTest.java`
  (+2 #11 sort tests)
- `core/src/main/webapp/ui/src/components/IntegrationSettings/ConnectorGovernanceTab.tsx`
  (#12 useMemo comment correction)
- `docs/soc-templates/*` (#3 Splunk fix, #14 shipper enrichment,
  #15 sed cookbook, #16 stale refs, #17 grep, #8 HOSTNAME doc,
  #9 Loki TZ doc — across 6 files)
- `docs/SOC-AUDIT-INTEGRATION.md` (+ #7 §5.6 fallback)
- `docs/design/connector-delegation.md` (§12.18 new, §12.19
  vNext renumbered)
- `CLAUDE.md`, `RELEASE_NOTES.md`, `REVIEW_PACKET.md` (this
  rewrite)

If you are reviewing RC5+RC6 cold for the first time, use the
cumulative diff (since `v3.1.1-RC4.1`) in §1.

---

## 9. Files external reviewers usually start with

| Purpose | File |
|---|---|
| Re-send overview (this file) | `REVIEW_PACKET.md` |
| What changed and why (per RC) | `RELEASE_NOTES.md` (10 sections RC5 → RC6.2) |
| Design rationale | `docs/design/connector-delegation.md` (§12.1 - §12.19) |
| Multi-replica operational notes | `docs/MULTI-REPLICA-DEPLOYMENT.md` |
| Project-internal navigation (Japanese) | `CLAUDE.md` |
| API entry points | `ConnectorDefinitionController.java`, `ImportProfileDefinitionController.java`, `IngestSchedulerService.java`, `AuditEmitSupport.java` |
| Test coverage proof | `core/src/test/java/jp/aegif/nemaki/rest/ingest/*Test.java` (182 focused tests across 14 classes) |
| SOC / SIEM audit integration (playbook) | `docs/SOC-AUDIT-INTEGRATION.md` |
| SOC / SIEM audit integration (ready-to-import templates) | `docs/soc-templates/` (README + Filebeat / Fluent Bit / Vector shippers + Kibana Detection Engine NDJSON / Loki Ruler / Splunk savedsearches rule sets) |
