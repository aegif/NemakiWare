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

3. **Fluent Bit DST fix is plausibility-checked by math trace,
   NOT live-tested.** This is an honest validation gap, not a
   "verified" claim. What the math trace covers (in the comment
   block of `fluent-bit-nemakiware.conf`):
   - In UTC, `utc_offset_at(epoch) = 0` for any epoch →
     naive_local already correct → no behavior change.
   - In JST (no DST), `utc_offset_at(any) = +32400` →
     true UTC = naive + 32400 → correct.
   - In US/Eastern,
     `utc_offset_at(summer_epoch) = -14400`,
     `utc_offset_at(winter_epoch) = -18000` → per-record
     offset captures the right value for each season.
   - Spring-forward boundary minutes trigger the second
     `if offset2 ~= offset` correction.

   What the math trace does NOT cover:
   - A running Fluent Bit instance ingesting real audit lines.
   - The Lua runtime version differences (Fluent Bit ships
     LuaJIT; some `os.date` flags vary across LuaJIT minor
     versions).
   - Behaviour when `os.date("!*t", epoch)` returns nil for an
     edge-case epoch (e.g., pre-1970 dates if someone
     deliberately injects one — not a realistic audit scenario
     but a theoretical Lua failure mode).

   Recommended pre-deploy: drop the template into a Fluent
   Bit instance with `TZ=America/New_York`, feed a synthetic
   audit line dated 2025-03-09T07:00:00Z (DST spring-forward
   day), and confirm `hour_of_day_local = 3` (EDT 03:00). The
   build host doesn't have Fluent Bit installed so this gate
   is operator-side.

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
allowed to differ — **and only at the indicated rigour level**:

| Path | Allowed post-tag changes |
|---|---|
| `REVIEW_PACKET.md` | Any (this is the review correspondence file by design) |
| `RELEASE_NOTES.md` | Doc-only — narrative additions, typo fixes, framing alignment |
| `CLAUDE.md` | Doc-only |
| `docs/design/connector-delegation.md` | Doc-only — review-time clarifications |
| `docs/SOC-AUDIT-INTEGRATION.md` | Doc-only — playbook clarifications, sample-query syntax corrections (which are themselves doc-only since the file IS the doc) |
| `docs/soc-templates/README.md` | Doc-only |
| `docs/soc-templates/*.yml` / `*.conf` / `*.toml` / `*.ndjson` (shipper + alert rule body files) | **Comment-only** — see rules below |

Any other path diverging is a bug — please flag it.

### What "comment-only" means for the shipper / rule files

**"Comment-only" does NOT mean small edits.** Multi-paragraph
explanation blocks, full-section comment rewrites, header
clarifications, even adding a 50-line block-comment with an
ASCII-art TZ diagram — all of those are allowed. The rule
constrains *effect*, not size.

**A change is comment-only IFF every diff line is either**:

1. A comment line (starts with the language's comment marker —
   `#` for YAML/conf/toml/ini, `--` for Lua block context
   inside a config string), OR
2. Whitespace / blank line additions or removals, OR
3. A rename / restructure of an existing comment block.

**Exception: `docs/soc-templates/*.ndjson` has no comment-only
mode at all.** The NDJSON files are imported as JSON Lines —
the JSON grammar has no comment syntax, and adding any
non-JSON line breaks the import. Comment-only post-tag edits
to `.ndjson` files are therefore impossible: any change to a
line is a content change, which falls under the "NOT
comment-only, requires new RC tag" rule below regardless of
whether it touches the value, the query body, or the
description string. If you want to add an explanation about
an NDJSON rule, put the prose in `docs/soc-templates/README.md`
and leave the `.ndjson` body alone until the next RC tag.

**A change is NOT comment-only and REQUIRES a new RC tag if it
edits any of these — even by one character**:

- A knob value (`Refresh_Interval 5` → `Refresh_Interval 10`,
  `max_lines: 1000` → `2000`)
- A `${VAR:default}` placeholder default value
- A threshold (`"value":20` → `"value":35`,
  `details.lostCount > 50` → `> 80`)
- A query / search-string body (KQL / EQL / SPL / LogQL /
  jq filter)
- A processor / filter / output sink (`[OUTPUT]`, `[FILTER]`,
  `processors: -`, VRL `.field = …`, Lua `function …`)
- A path / glob (`paths: - …`, `Path …`, `include = [...]`)
- A label / tag / metric name that the SIEM indexes
- A rule-evaluation parameter (cron, interval, threshold field,
  rule_type, language, EQL `by …`, new_terms field list)

**If in doubt, treat it as not-comment-only and cut a new RC
tag.** Boundary cases that look like "just a config tweak"
have already burned three review rounds (RC6.1 P2-3 NUL byte,
RC6.2 Filebeat env / Fluent Bit TZ / Vector VRL, RC6.3 Fluent
Bit DST). Spending review time arguing the boundary is more
expensive than spending five minutes cutting `v3.1.1-RC6.4`.

### New file additions — also require a new RC tag

The §3 table enumerates exactly the paths whose post-tag edits
are allowed today. **Adding a new file under any of these
paths (or anywhere else under the repo) is NOT a permitted
post-tag change**, even if the new file is "obviously docs"
or "obviously a SOC template". The reasoning:

- A new template / dashboard / rule file ships to GA via the
  branch HEAD merge to master; reviewers looking at the
  tag don't see it. Same tag/branch mismatch class the §3
  rule exists to prevent.
- A new README or design-doc page is genuinely additive but
  changes what the reviewer is supposed to read. Surface it
  in the next RC tag so the review packet stays complete.

So: **any new file under `release/3.1.1-RC6` post-tag → cut
a new RC tag**. The table is a permission, not a wildcard.

### Rationale recap

This rule set is the response to the RC6.2 review's P1-B
finding (the "clarifying additions only" qualifier was too
vague — "config-body fix" silently masqueraded as
"clarification"). Going forward: if a SOC template has a bug,
fix it on the branch and cut a new RC tag before the next
review send; don't ship the fix as a post-tag commit and call
it a clarification.

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

### Java unit tests — carry-forward evidence

**Last executed**: at commit `fd03d4ab4` (RC6.2 cycle,
`perMemberImpact` sort + useMemo comment commit).
**Result at that run**: 182/182 across the focused 14 test
classes, all pass.
**Java / test code delta since `fd03d4ab4`**: zero. RC6.2
closure docs (`02afee891`, `568f4ebb2`), all RC6.3 commits
(`bf7c07b3f`, `3afd284f5`, `77ddfe071`, `0028a01cd`,
`e0f00c7fb`), and this post-tag self-review follow-up touch
only `docs/**`, `REVIEW_PACKET.md`, `RELEASE_NOTES.md`,
`CLAUDE.md`. The 182/182 number is therefore the **current
expected result if you re-run** — but treat it as
carry-forward evidence, NOT "verified at branch HEAD this
session".

### Playwright E2E — carry-forward evidence

- **6 RC5/RC6-area specs (smoke)** — **last executed**: at
  RC6.2 closure window, against the deployed WAR built from
  `fd03d4ab4`. Result: 66/66 PASS across 2 consecutive runs
  (no flake). No Java / TS / spec body delta since; same
  carry-forward caveat as the Java tests above.
- **Full chromium suite** — **last executed**: at RC6.2
  closure (`02afee891` window). Result: 684 passed / 155
  failed / 94 skipped / 97 did-not-run. NOT re-run for RC6.3.
  RC6.3 and this post-tag follow-up are config / doc-only
  with zero Java / TS / spec code delta vs RC6.2 closure
  baseline, so re-execution would surface the same numbers
  modulo Playwright flake noise.

If a reviewer wants live-at-HEAD verification, the commands
are:

```bash
mvn test -Dtest="ConnectorByPrincipalGovernanceTest,\
ConnectorSimulateRemoveTest,IngestSchedulerDelegatedRunTest,\
ImportProfileSchedulerGateTest,ExternalIngestControllerGateTest,\
IngestAuthorizationServiceTest,ImportProfileSinceFilterTest,\
ConnectorDefinitionControllerPartialPutTest,IngestSchedulerDelegationSkipTest,\
ImportProfileOwnershipTransferTest,ExternalIngestControllerTest,\
IngestWebhookGraphValidationTest,ImportProfileDefinitionTest,\
DelegatedCallContextFactoryTest" -f core/pom.xml -Pdevelopment
```

```bash
cd core/src/main/webapp/ui
npx playwright test --project=chromium \
  tests/admin/connector-governance-by-group.spec.ts \
  tests/admin/connector-governance-simulate-button.spec.ts \
  tests/admin/integration-settings.spec.ts \
  tests/admin/connector-profile-management.spec.ts \
  tests/api/external-ingest-api.spec.ts \
  tests/api/ingest-pipeline-e2e.spec.ts
```

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
    summer / US/Eastern winter / spring-forward boundary —
    plausibility check only, NOT live-tested against a running
    Fluent Bit (binary absent on build host; operator gate via
    DST-day synthetic input documented in §2 note 3).
  - Vector VRL: syntax-spec confidence fix only; live `vector
    validate` not run (binary absent; operator gate documented
    in §2 note 2 and `docs/soc-templates/README.md`).

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
| SOC / SIEM audit integration (import-ready templates, operator validation required) | `docs/soc-templates/` (README + Filebeat / Fluent Bit / Vector shippers + Kibana Detection Engine NDJSON / Loki Ruler / Splunk savedsearches rule sets — see README "Template validation status" table for the per-template syntax-only / no-live-test gap) |
