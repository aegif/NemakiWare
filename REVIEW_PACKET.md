# NemakiWare v3.1.1-RC6.3 — External Review Packet (re-send)

Single entry point for the **fourth-round external review** of
the RC6 series. RC6.2 ship triggered another round of review
on the SOC templates that landed mid-cycle; that round produced
5 findings (2 P1 + 2 P2 + 1 P3). RC6.3 closes all 5.

- **Code artifact under review** = the annotated tag
  `v3.1.1-RC6.3` (peeled commit `77ddfe071bf0cff3af8457ee37f208605b612699`).
- **Review supplementary documentation** = files on
  `release/3.1.1-RC6` **branch HEAD** that may land after the
  tag is cut. As of tag time the divergence is zero — see §3.

Previous historical tags (`v3.1.1-RC6.2`, `…-RC6.1`, `…-RC6`,
`…-RC5.6`, …) remain unchanged for traceability.

---

## 1. Quick reference

| Item | Value |
|---|---|
| **Final candidate tag** | `v3.1.1-RC6.3` |
| Tag annotated object SHA | `1a55eec67539e31cd7381a707252ca33f4378a26` |
| Tag peeled commit | `77ddfe071bf0cff3af8457ee37f208605b612699` |
| Branch | `release/3.1.1-RC6` |
| Branch HEAD at tag time | `77ddfe071bf0cff3af8457ee37f208605b612699` (= tag peeled, zero divergence) |
| Base of RC6.3 cycle | `v3.1.1-RC6.2` (peeled `02afee891`) |
| RC5 cycle baseline | `v3.1.1-RC4.1` (peeled `572aad18b`) |
| **RC6.2 → RC6.3 diff cmd** | `git diff v3.1.1-RC6.2..v3.1.1-RC6.3` |
| Cumulative diff cmd (since RC4.1) | `git diff v3.1.1-RC4.1..v3.1.1-RC6.3` |
| Previous historical candidates | `v3.1.1-RC6.2` (`02afee891`), `…-RC6.1` (`595754b8c`), `…-RC6` (`9dfd87adb`), `…-RC5.6` (`adf8db3b4`) |

---

## 2. What changed since the previous external review (RC6.2 → RC6.3)

The RC6.2 review surfaced 5 findings on the SOC templates that
landed mid-cycle:

| # | Sev | Finding | Resolution |
|---|---|---|---|
| P1-A | P1 | RC6.2 tag artifact (`02afee891`) didn't include the post-tag 5-fix commit; reviewers checking out tag see buggy state | RC6.3 tag cut against current branch HEAD — tag and shipping artifact realigned |
| P1-B | P1 | REVIEW_PACKET §3 listed `docs/soc-templates/**` as "review-time clarifying additions only" but `bf7c07b3f` was config-body fixes | RC6.3 tag cut + §3 qualifier dropped (no current divergence to qualify) |
| P2-A | P2 | Fluent Bit Lua used `os.time()` (now-fixed) for utc_offset — DST-sensitive for non-real-time or DST-transitioning audit lines | Per-record `utc_offset_at(epoch)` computation + one-pass DST-boundary correction |
| P2-B | P2 | Vector VRL `parse_timestamp(...)` is fallible; missing explicit error handling | Added `?? null` coalesce per VRL strict-mode spec |
| P3 | P3 | REVIEW_PACKET §5 still asserted "none of the 155 attributable" — stronger than §2 note 4 boundary | Reworded to "none show up in the 6 directly-touched specs" to match §2 boundary |

### Notable framing decisions

1. **Cutting RC6.3 is the same pattern that ran in
   RC5.5→RC5.6, RC6→RC6.1, RC6.1→RC6.2.** When post-tag
   commits include substantive content (not just hash-fill
   docs), the tag and shipping artifact diverge — re-cutting
   the tag restores the invariant. Three previous cycles
   established this; RC6.3 applies it again.

2. **Vector VRL `?? null` is a syntax-spec confidence fix, NOT
   live-validated** against a running Vector instance. The
   build host doesn't have the `vector` binary. Operators
   importing the file should run `vector validate
   vector-nemakiware.toml` as the pre-deploy gate (already
   documented in `docs/soc-templates/README.md`'s validation
   commands list).

3. **Fluent Bit DST fix is verified by manual math trace**, not
   live test. The math: in UTC, `utc_offset_at(epoch) = 0` for
   any epoch → naive_local already correct → no behavior change.
   In JST, `utc_offset_at(any) = +32400` (no DST in JST) →
   true UTC = naive + 32400 → correct. In US/Eastern,
   `utc_offset_at(summer_epoch) = -14400`,
   `utc_offset_at(winter_epoch) = -18000` → per-record offset
   captures the right value. Spring-forward boundary minutes
   trigger the second `if offset2 ~= offset` correction.

4. **REVIEW_PACKET tone was a real evidence-vs-claim gap.**
   The §2 note 4 fix in RC6.2 already weakened the broad
   "pre-existing" framing; this RC catches the §5 last hold-out.
   Both now say the same thing: 6 directly-touched specs are
   proven green, the 155 elsewhere is unproven beyond
   "clustered in non-touched areas".

---

## 3. What's on the branch HEAD but NOT in the tag

The tag (`v3.1.1-RC6.3`) and the branch HEAD
(`release/3.1.1-RC6`) MAY diverge during the external review
window. As of tag time the divergence is zero — both point at
the same commit.

When divergence happens, only the following files / paths are
allowed to differ:

- `REVIEW_PACKET.md`
- `RELEASE_NOTES.md`
- `CLAUDE.md`
- `docs/design/connector-delegation.md`
- `docs/SOC-AUDIT-INTEGRATION.md`
- `docs/soc-templates/**`

The previous "clarifying additions only" qualifier is **dropped**:
if a real config bug surfaces post-tag (as happened with the
Fluent Bit DST issue), the fix may land as a body edit, and the
honest framing is "config-body fix" not "clarification". The
expectation remains that substantive config-body fixes trigger
a new tag (as RC6.2 → RC6.3 just demonstrated).

Any other path diverging is a bug — please flag it.

External reviewers focused only on the code artifact should
check out the tag and ignore later branch commits. The SOC
templates AND playbook are part of the tag artifact now.

---

## 4. What's in the v3.1.1-RC6.3 tag (cumulative since RC4.1)

RC5 cycle (RC5 → RC5.6) + RC6 + RC6.1 + RC6.2 + RC6.3:

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
- **R1 SOC playbook + templates** (RC6.1 + RC6.2)
- **RC6.2 17-finding closure** (Tier 1+2+3 review fixes,
  perMemberImpact sort, full Playwright run with honest tone)
- **RC6.3 5-finding closure** — Filebeat env syntax, Fluent
  Bit DST, Vector VRL, REVIEW_PACKET tone alignment, plus the
  tag-cut that brings the SOC template body-fixes into the
  audited artifact

Full per-RC narrative: `RELEASE_NOTES.md` (11 sections, RC5 →
RC6.3), `docs/design/connector-delegation.md` (§12.1 - §12.20).

---

## 5. Acceptance status summary (RC6.3)

### Blocking findings
**0**.

### Java unit tests

Focused 14 test classes: **182** total, all pass. Unchanged
from RC6.2 — RC6.3 only touches `docs/soc-templates/**` and
the review docs.

### Playwright E2E

- 6 RC5/RC6-area specs: **66/66 PASS** across 2 consecutive
  runs (smoke).
- Full chromium suite: NOT re-run for RC6.3. RC6.3 changes
  are config / doc-only with zero Java / TS code delta vs
  RC6.2. The RC6.2 baseline (684 passed / 155 failed / 94
  skipped / 97 did-not-run) carries forward.

### Live verification

Carry-forward from RC6.2 closure. No new live verification
done in RC6.3 (the changes don't reach the running NemakiWare
process):

- B3-2, M2, M3 smokes valid as of RC6.2.
- File-level smokes for the RC6.3 changes:
  - `file docs/soc-templates/*` — all report `ASCII text` /
    `JSON data` / `Java source` (no binary leakage).
  - NDJSON / Loki YAML / Splunk SPL syntax: validates.
  - Fluent Bit Lua: math-traced for UTC / JST / US/Eastern
    summer / US/Eastern winter / spring-forward boundary.
  - Vector VRL: syntax-spec confidence fix; live `vector
    validate` not run (binary absent).

### Full-suite evidence boundary (matches §2 note 4 / §5
tone in RC6.2-post-tag, repeated here for clarity)

What is proven:
- The 6 RC5/RC6-area specs RC6 / RC6.1 / RC6.2 / RC6.3 directly
  touched stay at 66/66 PASS through every RC.

What is NOT proven:
- That the 155 full-suite failures elsewhere are all
  pre-existing. We did not compare against
  `v3.1.1-RC5.6` (or earlier) baselines in this RC. They
  cluster in non-RC6.x-touched UI areas (documents /
  permissions / search / versioning) which is suggestive but
  not proof. Full-suite green-up + baseline diff is its own
  epic.

### API contract

Additive only across RC5 → RC6.3.

### Patch / view / Mango / migration / DB bootstrap

Unchanged since RC4.1.

### UI forbidden path

`/core/ui/dist/` — 0 hits.

---

## 6. Remaining follow-ups (post-RC6.3, not blocking review)

(No open repo-shippable items.)

| ID | Severity | Scope | Status |
|---|---|---|---|
| **R1** (deployment-side) | Low (ops) | operator infrastructure | 4 inherently per-deployment items: network path / TLS, SIEM credentials from secrets manager, notification routing, threshold tuning from environment baseline. Not repo-shippable. |
| **Vector VRL live validation** | Low | template QA | Run `vector validate vector-nemakiware.toml` against an installed vector CLI as a pre-deploy check. Build host absence is the gap. |
| **Full Playwright green-up + RC5.6 baseline-diff** | Medium | UI corpus | 155 failures distributed across older specs (most plausibly React 19 / AntD 5 drift). Separate engineering project. |

**Resolved during RC5+RC6+RC6.1+RC6.2+RC6.3 cycle**: all
listed in `RELEASE_NOTES.md` per-section.

---

## 7. Promotion path (operational)

`v3.1.1-RC6.3` is and remains a release candidate. GA path:

1. External review concludes with approval.
2. Merge `release/3.1.1-RC6` into `master`.
3. Cut a **new** annotated tag `v3.1.1` against the merge
   commit on `master`.
4. Optionally create a single GitHub Release attached to
   `v3.1.1`.
5. The RC tags (`v3.1.1-RC5`, `…-RC5.1`, …, `…-RC6.3`) stay
   as internal milestones.

---

## 8. Re-send delta summary (what reviewers should focus on)

If you reviewed RC6.2 already, the smallest possible review
for RC6.3 is:

```bash
git diff v3.1.1-RC6.2..v3.1.1-RC6.3
```

Focused set (~6 files):

- `docs/soc-templates/filebeat-nemakiware.yml` (post-RC6.2
  bf7c07b3f: `${VAR:default}`, now in tag)
- `docs/soc-templates/fluent-bit-nemakiware.conf` (P2-A
  per-record DST offset)
- `docs/soc-templates/vector-nemakiware.toml` (post-RC6.2
  bf7c07b3f: VRL field path + 256 MiB; RC6.3 P2-B `?? null`)
- `REVIEW_PACKET.md` (P3 tone + §3 qualifier drop, this
  rewrite)
- `CLAUDE.md`, `RELEASE_NOTES.md`, `docs/design/connector-delegation.md`
  (RC6.3 section)

If you are reviewing RC5+RC6 cold for the first time, use the
cumulative diff (since `v3.1.1-RC4.1`) in §1.

---

## 9. Files external reviewers usually start with

| Purpose | File |
|---|---|
| Re-send overview (this file) | `REVIEW_PACKET.md` |
| What changed and why (per RC) | `RELEASE_NOTES.md` (11 sections RC5 → RC6.3) |
| Design rationale | `docs/design/connector-delegation.md` (§12.1 - §12.20) |
| Multi-replica operational notes | `docs/MULTI-REPLICA-DEPLOYMENT.md` |
| Project-internal navigation (Japanese) | `CLAUDE.md` |
| API entry points | `ConnectorDefinitionController.java`, `ImportProfileDefinitionController.java`, `IngestSchedulerService.java`, `AuditEmitSupport.java` |
| Test coverage proof | `core/src/test/java/jp/aegif/nemaki/rest/ingest/*Test.java` (182 focused tests across 14 classes) |
| SOC / SIEM audit integration (playbook) | `docs/SOC-AUDIT-INTEGRATION.md` |
| SOC / SIEM audit integration (ready-to-import templates) | `docs/soc-templates/` (README + Filebeat / Fluent Bit / Vector shippers + Kibana Detection Engine NDJSON / Loki Ruler / Splunk savedsearches rule sets) |
