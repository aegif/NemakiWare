# NemakiWare v3.1.1-RC6.1 — External Review Packet (re-send)

Single entry point for the **second-round external review** of
RC6. The first round returned 3 P2 findings + 1 P3 finding,
all repo-local. RC6.1 closes all four.

- **Code artifact under review** = the annotated tag
  `v3.1.1-RC6.1` (peeled commit `{{TAG_PEELED}}` — populated by
  the post-tag doc fix immediately following this packet).
- **Review supplementary documentation** = files on
  `release/3.1.1-RC6` **branch HEAD** that may land after the
  tag is cut. As of tag time the divergence is zero — see §3.

Previous historical tags (`v3.1.1-RC6`, `…-RC5.6`, `…-RC5.5`,
`…-RC5.4`, …) remain unchanged for traceability and are NOT
promoted into GA.

---

## 1. Quick reference

| Item | Value |
|---|---|
| **Final candidate tag** | `v3.1.1-RC6.1` |
| Tag annotated object SHA | filled in at tag time |
| Tag peeled commit | filled in at tag time |
| Branch | `release/3.1.1-RC6` |
| Branch HEAD at tag time | filled in at tag time (= tag peeled, zero divergence) |
| Base of RC6.1 cycle | `v3.1.1-RC6` (peeled `9dfd87adb`) |
| RC5 cycle baseline | `v3.1.1-RC4.1` (peeled `572aad18b`) |
| **RC6 → RC6.1 diff cmd** | `git diff v3.1.1-RC6..v3.1.1-RC6.1` |
| Cumulative diff cmd (since RC4.1) | `git diff v3.1.1-RC4.1..v3.1.1-RC6.1` |
| Previous historical candidates | `v3.1.1-RC6` (`9dfd87adb`), `…-RC5.6` (`adf8db3b4`), `…-RC5.5` (`dfb912da9`) |

---

## 2. What changed since the previous external review (RC6 → RC6.1)

The RC6 review surfaced 4 findings:

| ID | Severity | Scope | RC6.1 resolution |
|---|---|---|---|
| **P2-1** | Medium | server response size | `/by-group` per-member `lostIfGroupRemoved` array capped at `MAX_LOST_PER_MEMBER = 50`; new `lostCount` (untruncated) + `lostIfGroupRemovedTruncated` (boolean) fields preserve the signal |
| **P2-2** | Medium | server correctness | `buildMatches` inner-loop direction switch reverted — always iterate `allowed` so `matchedPrincipalIds` order tracks the connector's declared order; restores the byte-identical response invariant |
| **P2-3** | Medium | UI source quality | Literal NUL byte in `ConnectorGovernanceTab.tsx` line 260 (`join('\0')` instead of intended `join(' ')`) replaced with `JSON.stringify(simulateRemove)`; `file` utility now reports the source as text |
| **P3** | Low | UI behavior | `initialFetchDoneRef` shared across modes replaced with `Set<'USER' \| 'GROUP'>` per-kind tracking; `fetchPrincipals` merges options + totals instead of replacing wholesale |

No API contract regression. The P2-1 fix adds two new fields
(`lostCount`, `lostIfGroupRemovedTruncated`) per member entry —
additive, existing consumers reading `lostIfGroupRemoved` are
unaffected except that very-large arrays are now capped at 50
(with the count preserved in `lostCount`).

### Notable design decisions worth review attention

1. **P2-1 cap chosen as 50, not parameterised** — `MAX_LOST_PER_MEMBER`
   is a server-side constant, not a query param. Rationale: the
   primary client (the governance UI) renders one Tag per lost
   connector per member, and 50 tags in a single row is already
   well past the operationally readable threshold. If a future
   workflow needs the full list, the response includes `lostCount`
   so the UI can render "X also lost Y more" and the operator can
   then issue an `/by-principal/{userId}?expand=true` lookup to
   see the full set. Keeping the cap server-side avoids a
   "raise the cap → DoS" parameter handle.

2. **P2-2 revert vs forward-fix** — the M3 "iterate the smaller
   side" optimisation was technically faster for the
   user-with-many-groups shape, but it broke ordering — a
   correctness issue. A forward fix would have required sorting
   matched entries by `allowed.indexOf()`, which is O(matched ×
   allowed) and erases the perf gain. Reverting to the simpler
   shape (iterate `allowed`, contains() on the Set) was the
   cleaner answer; the M3 connector-list cache still delivers
   the headline O(members+1) → O(1) `list()` reduction.

3. **P2-3 NUL byte root cause** — the L1 commit (`8db0eb254`)
   wrote `simulateRemove.join(' ')` via the `Edit` tool. The
   tool's input handling apparently substituted the space with
   `\0`. The file passed TypeScript compilation (NUL inside a
   string literal is valid TS) but failed `file`, grep, and IDE
   tooling. RC6.1 switches to `JSON.stringify` per the reviewer's
   suggestion and adds a permanent comment block warning against
   single-char `join` separators so a future edit doesn't
   regress.

4. **P3 per-kind merge semantics** — `fetchPrincipals` now keeps
   options of kinds it didn't fetch. A consequence: switching
   from group mode to principal mode triggers fetch of USER only
   (GROUP options carry over), preserving the operator's
   previously-loaded data. The totals pane also merges, so the
   "{loaded} of {total}" footer shows the latest counts per kind
   even when only one kind was just refreshed.

---

## 3. What's on the branch HEAD but NOT in the tag

The tag (`v3.1.1-RC6.1`) and the branch HEAD
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

## 4. What's in the v3.1.1-RC6.1 tag (cumulative since RC4.1)

RC5 cycle scope (RC5 → RC5.6) + RC6 scope + RC6.1 corrections:

- **Scheduled delegated profiles** (RC5 §12.1)
- **Connector governance view** (RC5 §12.3) —
  `/by-principal/{id}` admin endpoint
- **simulate-remove endpoint** (RC5.3 W2) — now with M2 body
  size limits and M3 per-request caching
- **Auto-disabled triage UI** (RC5 V1-V4, RC5.1 G1+G3,
  RC5.2 H3)
- **Server-side auto-disabled profile filtering** (RC5.3 W1)
- **R3 explicit Simulate (audit) button** (RC5.4) + H2
  Playwright coverage (RC6)
- **R4 strict 400** (RC5.4) for malformed `autoDisabledSince`
- **C1 fix** (RC5.5) — epoch-overflow → 400
- **H1 safeEmit helper** (RC5.5)
- **R5 denialReason accuracy** (RC5.6)
- **A2 spec CSRF cleanup** (RC5.6)
- **B3-2 group-membership impact view** (RC6) — new
  `/by-group/{id}` endpoint + UI integration
- **V8/G2 picker scale-out** (RC6) — now with P3 per-kind merge
  (RC6.1)
- **M2 simulate-remove body limits** (RC6)
- **M3 buildMatches per-request caching** (RC6) — now with
  ordering revert (RC6.1 P2-2) and per-member response cap
  (RC6.1 P2-1)
- **L1 useEffect dep stability** (RC6) — now via JSON.stringify
  after the NUL byte fix (RC6.1 P2-3)
- **L2 buildMatches null fold** (RC6)
- **Dependabot security pass** (RC6) — Spring 7.0.7, logback
  1.5.25, commons-lang3 3.18.0, npm audit fix

For the full per-RC narrative see `RELEASE_NOTES.md` 9 sections
(`3.1.1-RC5` → `3.1.1-RC6.1`) and
`docs/design/connector-delegation.md` §12.1 - §12.17.

---

## 5. Acceptance status summary (RC6.1)

- **Blocking findings**: 0
- **Unit tests** (precise scope):
  - **Focused set** (governance + scheduler + ingest delegation +
    profile pipeline): the post-RC6.1 focused 14 test classes
    run **180 cases all PASS** at the closure check. Notable
    deltas from RC6:
    - `ConnectorByPrincipalGovernanceTest`: 27 → **30**
      (P2-1 +2, P2-2 +1)
- **Playwright E2E** (RC5+RC6 area):
  - `tests/admin/connector-governance-by-group.spec.ts` — 5/5 PASS
  - `tests/admin/connector-governance-simulate-button.spec.ts`
    — 9/9 PASS
  - 4 existing RC5-area specs — 52/52 PASS
  - **Total: 66/66 PASS** across two consecutive runs (no flake)
- **Live verification**:
  - P2-1: per-member `lostCount` and `lostIfGroupRemovedTruncated`
    confirmed via Mockito-backed unit test for `MAX_LOST_PER_MEMBER`
    boundary (50 → not truncated, 60 → truncated to 50 with
    `lostCount=60`).
  - P2-2: `matchedPrincipalIds` order pinned to declared
    `allowedPrincipalIds` order regardless of `principalsToMatch`
    size; new test covers the previously-buggy "user expanded into
    many groups" branch.
  - P2-3: `file ConnectorGovernanceTab.tsx` reports
    "Java source, Unicode text, UTF-8 text" (was "data" in RC6).
  - P3: per-kind tracking verified by inspection — `Set<Kind>`
    tracks which kinds have run their initial fetch;
    `fetchPrincipals` merges instead of replacing.
- **API contract**: additive only. The new per-member fields
  (`lostCount`, `lostIfGroupRemovedTruncated`) are non-breaking.
- **Patch / view / Mango index / migration / DB bootstrap**:
  unchanged since RC4.1.
- **UI forbidden path `/core/ui/dist/`**: 0 hit in cumulative diff.

---

## 6. Remaining follow-ups (post-RC6.1, not blocking review)

| ID | Severity | Scope | Description |
|---|---|---|---|
| **R1** | Low (ops) | NemakiWare repo external | SOC tooling integration for `EXTERNAL_GOVERNANCE_SIMULATE` audit event — query / alert template work in the operator monitoring stack, not this repository. |

**Resolved during RC5+RC6+RC6.1 cycle**:

| ID | Resolution venue | Description |
|---|---|---|
| R2 | RC5.3 closure | `docs/MULTI-REPLICA-DEPLOYMENT.md` |
| R3 | RC5.4 commit `6283afc96` | V7 audit explicit button |
| R4 | RC5.4 commit `6283afc96` | `autoDisabledSince` strict 400 |
| C1 | RC5.5 commit `9bb5bcf83` | Epoch overflow → 400 |
| H1 | RC5.5 commit `9bb5bcf83` | safeEmit helper |
| M1, M4 | RC5.5 docs | REVIEW_PACKET + design doc |
| R5 | RC5.6 commit `cee66573e` | denialReason accuracy |
| A2 | RC5.6 commits `dc0ba6dac` + Low fix | Playwright CSRF cleanup |
| B3-2 | RC6 commits `15936c6b3` + `7f31c1d64` + `ca8295b39` | `/by-group/{id}` + UI |
| V8/G2 | RC6 commit `507d65253` | Picker scale-out |
| H2 | RC6 commit `581694272` | Simulate button Playwright |
| M2 | RC6 commit `06ac804cd` | simulate-remove body limits |
| M3 | RC6 commit `82012a221` | buildMatches caching |
| L1, L2 | RC6 commit `8db0eb254` | Dep stability + null fold |
| Dependabot | RC6 commits `9204d3a95` + `9ea197c9a` | Maven + npm |
| **P2-1, P2-2** | **RC6.1 commit `be7160d48`** | **per-member cap + order revert** |
| **P2-3, P3** | **RC6.1 commit `a246ffe81`** | **NUL byte fix + per-kind tracking** |

---

## 7. Promotion path (operational)

`v3.1.1-RC6.1` is and remains a release candidate. The GA path
is unchanged from the RC5.x / RC6 series:

1. External review concludes with approval.
2. Merge `release/3.1.1-RC6` into `master`.
3. Cut a **new** annotated tag `v3.1.1` against the merge commit
   on `master` — never relabel an RC tag.
4. Optionally create a single GitHub Release attached to `v3.1.1`.
5. The RC tags (`v3.1.1-RC5`, `…-RC5.1`, …, `…-RC6`, `…-RC6.1`)
   stay as internal milestones; they keep the cycle's audit trail
   intact.

---

## 8. Re-send delta summary (what reviewers should focus on)

If you reviewed RC6 already, the smallest possible review for
RC6.1 is:

```bash
git diff v3.1.1-RC6..v3.1.1-RC6.1
```

This produces a focused set of changes (~6 files):

- `core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionController.java`
  (P2-1 cap + new fields + JavaDoc, P2-2 inner-loop revert + comment)
- `core/src/test/java/jp/aegif/nemaki/rest/ingest/ConnectorByPrincipalGovernanceTest.java`
  (+3 cases — P2-1 ×2, P2-2 ×1)
- `core/src/main/webapp/ui/src/components/IntegrationSettings/ConnectorGovernanceTab.tsx`
  (P2-3 JSON.stringify, P3 per-kind Set ref + fetchPrincipals
  merge)
- Doc files: `CLAUDE.md`, `RELEASE_NOTES.md`, `REVIEW_PACKET.md`,
  `docs/design/connector-delegation.md`

If you are reviewing RC5+RC6+RC6.1 cold for the first time, use
the cumulative diff (since `v3.1.1-RC4.1`) in §1.

---

## 9. Files external reviewers usually start with

| Purpose | File |
|---|---|
| Re-send overview (this file) | `REVIEW_PACKET.md` |
| What changed and why (per RC) | `RELEASE_NOTES.md` (9 sections RC5 → RC6.1) |
| Design rationale | `docs/design/connector-delegation.md` (§12.1 - §12.17) |
| Multi-replica operational notes | `docs/MULTI-REPLICA-DEPLOYMENT.md` |
| Project-internal navigation (Japanese) | `CLAUDE.md` |
| API entry points | `ConnectorDefinitionController.java`, `ImportProfileDefinitionController.java`, `IngestSchedulerService.java`, `AuditEmitSupport.java` |
| Test coverage proof | `core/src/test/java/jp/aegif/nemaki/rest/ingest/*Test.java` (180 focused tests across 14 classes) |
