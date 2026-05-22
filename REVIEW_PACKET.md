# NemakiWare v3.1.1-RC6 — External Review Packet

Single entry point for the **first external review of RC6**, the
first independent feature RC after the RC5.x correction series.

RC5.x cycle (RC5 → RC5.6) shipped the scheduled-delegated-profile
machinery, the `/by-principal/{id}` governance endpoint, R1-R5
follow-up corrections, and audit-pipeline hardening (C1, H1, H2,
M1, M4, R5, A2). RC6 closes every remaining open item from the
RC5.5 closure follow-up table (B3-2, V8/G2, H2, M2, M3, L1, L2)
plus the 35-alert Dependabot security backlog.

- **Code artifact under review** = the annotated tag
  `v3.1.1-RC6` (peeled commit `9dfd87adb1e90a43885d825ef25039418eec22b2`).
- **Review supplementary documentation** = files on
  `release/3.1.1-RC6` **branch HEAD** that may land after the
  tag is cut. As of tag time the divergence is zero — see §3.

Previous historical tags (`v3.1.1-RC5.6`, `…-RC5.5`, `…-RC5.4`,
…) remain unchanged for traceability and are NOT promoted into GA.

---

## 1. Quick reference

| Item | Value |
|---|---|
| **Final candidate tag** | `v3.1.1-RC6` |
| Tag annotated object SHA | `069e5abaa60925579d0717614d66f621615d8665` |
| Tag peeled commit | `9dfd87adb1e90a43885d825ef25039418eec22b2` |
| Branch | `release/3.1.1-RC6` |
| Branch HEAD at tag time | `9dfd87adb1e90a43885d825ef25039418eec22b2` (= tag peeled, zero divergence) |
| Base of RC6 cycle | `v3.1.1-RC5.6` (peeled `adf8db3b4`) |
| RC5 cycle baseline | `v3.1.1-RC4.1` (peeled `572aad18b`) |
| **RC5.6 → RC6 diff cmd** | `git diff v3.1.1-RC5.6..v3.1.1-RC6` |
| Cumulative diff cmd (since RC4.1) | `git diff v3.1.1-RC4.1..v3.1.1-RC6` |
| Previous historical candidate | `v3.1.1-RC5.6` (peeled `adf8db3b4`) |
| Earlier historical candidates | `v3.1.1-RC5.5` (`dfb912da9`), `…-RC5.4` (`014939eeb`) |

---

## 2. What changed since the previous external review (RC5.6 → RC6)

RC5.6 closure left these items open in the follow-up table:

| ID | RC5.6 status | RC6 resolution |
|---|---|---|
| B3-2 | vNext | **shipped** — `/by-group/{id}` server endpoint + UI integration + 27 governance tests + 5 server-contract Playwright |
| V8/G2 | vNext | **shipped** — picker scale-out for 10k+ directories + offset=0 fix + totalCount surfacing + deferred initial fetch |
| H2 | post-release | **shipped** — `connector-governance-simulate-button.spec.ts` (9 cases — 7 server contract + 1 UI happy path + 1 M2 contract) |
| M2 | post-release | **shipped** — `simulate-remove` body size limits (`MAX_REMOVE_PRINCIPAL_IDS=500` + `MAX_PRINCIPAL_ID_LENGTH=512`) + 4 boundary tests |
| M3 | post-release | **shipped** — `buildMatches` per-request connector caching (O(members+1) → O(1) list() calls) + inner-loop direction opt + 2 regression-guard tests |
| L1 | post-release nit | **shipped** — `simulateLastAuditedAt` reset useEffect now depends on content-stable joined key |
| L2 | post-release nit | **shipped** — `buildMatches` null-folds `connectorDefinitionService.list()` to empty list + 2 defence tests |
| Dependabot 35 | separate scope | **shipped** — 10 Maven all real + 25 npm of which 2 real (rest stale); all 12 real CVEs patched, `npm audit` = 0 |

There are no API-contract regressions. Every existing response
shape is byte-identical for any input that worked in RC5.6. The
new 400 responses on oversized simulate-remove bodies only trigger
for requests outside the documented "dozens of principals" shape.

### Notable design decisions worth review attention

1. **B3-2 vs the existing `/by-principal/{groupId}`** — when an
   admin queries the existing endpoint with a group ID, they get
   the direct connector grants for that group. B3-2's
   `/by-group/{id}` adds the membership lens (per-member sole-
   route detection). The two endpoints are complementary, not
   redundant. Naming overlap with the pre-existing
   `/by-principal/{groupId}` is unfortunate but renaming the
   older endpoint would be a breaking change.

2. **M3 dual-overload** — `buildMatches(principalId,
   principalsToMatch)` (legacy, single-call endpoints) and
   `buildMatches(principalId, principalsToMatch, connectors)`
   (M3, multi-call). The legacy overload now delegates to the
   new one, ensuring the matching logic stays in one place.
   `listByGroup` is the only multi-call caller today.

3. **L2 null fold = partial-result honesty, not failure
   masking** — when `connectorDefinitionService.list()` returns
   null, the endpoint surfaces empty `matches[]` rather than an
   error. Justified because (a) the current impl never returns
   null and (b) governance is a read-only query — failing
   closed (no matches) is safer than failing loud (500 surfaces
   internal state). If this ever masks a real backend outage,
   the audit pipeline + service-layer logs will still record it.

4. **Dependabot dashboard delta** — 35 Maven+npm alerts on the
   dashboard, but `npm audit` against the actual RC6 lockfile
   reported only 2 npm vulnerabilities. The 23 missing-from-
   audit alerts are stale against the master branch's older
   lockfile (axios 1.6 series, vite 7.0.x, lodash 4.17.x,
   dompurify <3.4.0, follow-redirects, picomatch are all
   already at or above the patched versions in our lockfile).
   The Dashboard counter will catch up once this branch merges
   to master and Dependabot re-scans.

---

## 3. What's on the branch HEAD but NOT in the tag

The tag (`v3.1.1-RC6`) and the branch HEAD
(`release/3.1.1-RC6`) MAY diverge during the external review
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

## 4. What's in the v3.1.1-RC6 tag (cumulative since RC4.1)

RC5 cycle scope (RC5 → RC5.6) plus the RC6 additions above:

- **Scheduled delegated profiles** (RC5 §12.1) — opt-in per-tick
  governance for non-admin profile creators. Default-off.
- **Connector governance view** (RC5 §12.3) — admin-only
  `/by-principal/{id}` endpoint.
- **simulate-remove endpoint** (RC5.3 W2) — admin-only audit
  and what-if tooling, now with M2 body size limits and M3
  per-request caching.
- **Auto-disabled triage UI** (RC5 V1-V4, RC5.1 G1+G3, RC5.2 H3)
  — markers + filter + banner + window control.
- **Server-side auto-disabled profile filtering** (RC5.3 W1) —
  `autoDisabledSince` query param.
- **R3 explicit Simulate (audit) button** (RC5.4) — V7 audit
  fires on a deliberate button click, not a debounce. RC6 H2
  adds the Playwright + server contract coverage.
- **R4 strict 400** (RC5.4) for non-empty malformed
  `autoDisabledSince`.
- **C1 fix** (RC5.5) — epoch-overflow 500 leak → 400.
- **H1 safeEmit helper** (RC5.5) — silent audit catches → WARN
  log without breaking the business path.
- **R5 fix** (RC5.6) — scheduler audit `denialReason` accurate
  for the folder-resolution race.
- **A2 cleanup** (RC5.6) — Playwright spec CSRF / regex / count
  drift resolved.
- **B3-2 group-membership impact view** (RC6) — new
  `/by-group/{id}` endpoint + UI integration.
- **V8/G2 picker scale-out** (RC6) — kinds-aware fetch + offset=0
  fix + totalCount surfacing + deferred initial fetch.
- **H2 / M2 / M3 / L1 / L2** (RC6) — governance medium/low
  cleanup.
- **Dependabot security pass** (RC6) — Spring 7.0.7, logback
  1.5.25, commons-lang3 3.18.0; npm audit fix for brace-expansion
  and ws.

For the full per-RC narrative see `RELEASE_NOTES.md` 8 sections
(`3.1.1-RC5` → `3.1.1-RC6`) and
`docs/design/connector-delegation.md` §12.1 - §12.17.

---

## 5. Acceptance status summary (RC6)

- **Blocking findings**: 0
- **Unit tests** (precise scope):
  - **Focused set** (governance + scheduler + ingest delegation
    + profile pipeline): the post-RC6 focused 14 test classes
    run **177 cases all PASS** at the closure check. Notable
    deltas from RC5.6:
    - `ConnectorByPrincipalGovernanceTest`: 13 → **27**
      (B3-2 +9, review M +1, M3 +2, L2 +2 + the
      ConnectorByGroupGovernance subset which lives in this
      class)
    - `ConnectorSimulateRemoveTest`: 11 → **15** (M2 +4)
    - `IngestSchedulerDelegatedRunTest`: 8 → **10** (RC5.6 R5 +2)
    - `ImportProfileSinceFilterTest`: 8 → **10** (RC5.5 C1 +2,
      no change in RC6)
  - **Broader pattern**
    `mvn test -Dtest="*Ingest*Test*,*Connector*Test*,*Profile*Test*,Delegated*Test"`
    was not re-run for RC6 — RC6 only touches files inside the
    focused set. The RC5.6 closure run returned 289; RC6 adds
    +18 inside the focused set (B3-2 +9, M2 +4, M3 +2, L2 +2,
    + 1 review-M count test), so the expected broader-pattern
    count is 307.
- **Playwright E2E** (RC5+RC6 area):
  - `tests/admin/connector-governance-by-group.spec.ts` (new)
    — 5/5 PASS
  - `tests/admin/connector-governance-simulate-button.spec.ts`
    (new) — 9/9 PASS
  - 4 existing RC5-area specs (integration-settings,
    connector-profile-management, external-ingest-api,
    ingest-pipeline-e2e) — 52/52 PASS
  - **Total: 66/66 PASS** across two consecutive runs (no flake)
- **Live verification**:
  - B3-2 — 39-member `cloud-google:a13@aegif.jp` group with
    `memberLimit=5` returns `memberCount=39`,
    `memberUserIdsTruncated=true`, `perMemberImpact[5]`,
    `perMemberImpactTruncated=true`
  - B3-2 — `memberLimit=1_000_000` clamped to 1000 in echo
  - V8/G2 — `/user/list?offset=0&limit=50` returns 50/112
    (was 112/112)
  - M2 — 501 entries → 400 with named limit;
    513-char entry → 400; 500/512 boundary → 200
  - M3 — `connectorService.list()` count = 1 per request
    (verified via Mockito on a 25-member listByGroup call)
  - `npm audit` against RC6 lockfile = 0 vulnerabilities
- **API contract**: additive only since RC4.1 baseline. No
  breaking change in RC6. The new 400 responses on oversized
  simulate-remove bodies are documented and only trigger
  outside the realistic use shape.
- **Patch / view / Mango index / migration / DB bootstrap**:
  unchanged since RC4.1.
- **UI forbidden path `/core/ui/dist/`**: 0 hit in cumulative diff.

---

## 6. Remaining follow-ups (post-RC6, not blocking review)

| ID | Severity | Scope | Description |
|---|---|---|---|
| **R1** | Low (ops) | NemakiWare repo external | SOC tooling integration for `EXTERNAL_GOVERNANCE_SIMULATE` audit event. Query / alert template work that lives in the operator monitoring stack — not in this repository. |

**Resolved during RC5+RC6 cycle**:

| ID | Resolution venue | Description |
|---|---|---|
| R2 | RC5.3 closure doc commit `01fe84ac5` | `docs/MULTI-REPLICA-DEPLOYMENT.md` |
| R3 | RC5.4 feature commit `6283afc96` | V7 audit explicit button |
| R4 | RC5.4 feature commit `6283afc96` | `autoDisabledSince` strict 400 |
| C1 | RC5.5 feature commit `9bb5bcf83` | Epoch overflow → 400 |
| H1 | RC5.5 feature commit `9bb5bcf83` | safeEmit helper |
| M1 | RC5.5 doc | REVIEW_PACKET test scope precision |
| M4 | RC5.5 doc | design doc §12.6/§12.9 strikethrough |
| R5 | RC5.6 feature commit `cee66573e` | denialReason accuracy |
| A2 | RC5.6 spec commit `dc0ba6dac` + Low fix | Repo-wide Playwright CSRF |
| **B3-2** | **RC6 commits `15936c6b3` + `7f31c1d64` + `ca8295b39`** | **`/by-group/{id}` endpoint + UI** |
| **V8/G2** | **RC6 commit `507d65253`** | **picker scale-out** |
| **H2** | **RC6 commit `581694272`** | **Simulate button Playwright** |
| **M2** | **RC6 commit `06ac804cd`** | **simulate-remove body size limits** |
| **M3** | **RC6 commit `82012a221`** | **buildMatches per-request caching** |
| **L1, L2** | **RC6 commit `8db0eb254`** | **useEffect dep stability + null fold** |
| **Dependabot** | **RC6 commits `9204d3a95` + `9ea197c9a`** | **Maven 10 + npm 2 real** |

---

## 7. Promotion path (operational)

`v3.1.1-RC6` is and remains a release candidate. The GA path
is unchanged from the RC5.x series:

1. External review concludes with approval.
2. Merge `release/3.1.1-RC6` into `master`.
3. Cut a **new** annotated tag `v3.1.1` against the merge commit
   on `master` — never relabel an RC tag.
4. Optionally create a single GitHub Release attached to `v3.1.1`.
5. The RC tags (`v3.1.1-RC5`, `…-RC5.1`, …, `…-RC5.6`,
   `…-RC6`) stay as internal milestones; they keep the cycle's
   audit trail intact.

---

## 8. Re-send delta summary (what reviewers should focus on)

If you reviewed RC5.6 already, the smallest possible review for
RC6 is:

```bash
git diff v3.1.1-RC5.6..v3.1.1-RC6
```

This produces a focused set of changes (~18 files):

- `core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionController.java`
  (B3-2 endpoint + review M cap + L stable shape, M2 size limits,
  M3 caching + overload + inner-loop opt, L2 null fold)
- `core/src/test/java/jp/aegif/nemaki/rest/ingest/ConnectorByPrincipalGovernanceTest.java`
  (+14 cases)
- `core/src/test/java/jp/aegif/nemaki/rest/ingest/ConnectorSimulateRemoveTest.java`
  (+4 M2 cases)
- `core/src/main/webapp/ui/src/services/externalIngest.ts`
  (`getConnectorsByGroup` + types)
- `core/src/main/webapp/ui/src/components/IntegrationSettings/ConnectorGovernanceTab.tsx`
  (group mode toggle + V8/G2 fetch + L1 useEffect stability)
- `core/src/main/webapp/ui/src/i18n/locales/{ja,en}.json` (24
  new keys)
- `core/src/main/webapp/ui/tests/admin/connector-governance-by-group.spec.ts`
  (new)
- `core/src/main/webapp/ui/tests/admin/connector-governance-simulate-button.spec.ts`
  (new)
- `core/pom.xml`, `solr/pom.xml`, `docker/solr/pom.xml` (Maven
  bumps)
- `core/src/main/webapp/ui/package-lock.json` (npm audit fix)
- Doc files: `CLAUDE.md`, `RELEASE_NOTES.md`, `REVIEW_PACKET.md`,
  `docs/design/connector-delegation.md`

If you are reviewing RC5+RC6 cold for the first time, use the
cumulative diff (since `v3.1.1-RC4.1`) in §1.

---

## 9. Files external reviewers usually start with

| Purpose | File |
|---|---|
| Re-send overview (this file) | `REVIEW_PACKET.md` |
| What changed and why (per RC) | `RELEASE_NOTES.md` (8 sections RC5 → RC6) |
| Design rationale | `docs/design/connector-delegation.md` (§12.1 - §12.17) |
| Multi-replica operational notes | `docs/MULTI-REPLICA-DEPLOYMENT.md` |
| Project-internal navigation (Japanese) | `CLAUDE.md` |
| API entry points | `ConnectorDefinitionController.java`, `ImportProfileDefinitionController.java`, `IngestSchedulerService.java`, `AuditEmitSupport.java` |
| Test coverage proof | `core/src/test/java/jp/aegif/nemaki/rest/ingest/*Test.java` (177 focused tests across 14 classes) |
