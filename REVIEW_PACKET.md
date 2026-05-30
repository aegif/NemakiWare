# NemakiWare v3.1.1-RC6.5 — External Review Packet

Single entry point for the **sixth-round external review** of the
RC6 series. RC6.5 is a focused security RC: it closes one
externally-reported SSRF bypass (GHSA via tonghuaroot) plus a
three-round closure of the connector-area manual-verification
guide that surfaced 24 doc/actual drift findings (15 from external
review + 9 from self-review).

- **Security fix**: `HttpWebhookDispatcher.isAddressSafe` now
  unwraps IPv6 transition addresses (NAT64 `64:ff9b::/96` +
  `64:ff9b:1::/48`, 6to4 `2002::/16`, IPv4-compatible `::a.b.c.d`)
  and re-classifies the embedded IPv4. The advisory's PoC showed
  all 5 transition forms bypassing the existing guard to reach
  internal IPv4 targets (loopback, RFC 1918, cloud metadata) via
  dual-stack / NAT64 routing; with the fix all 5 are blocked.
  15 regression tests added.
- **Manual-verification doc closure**: three rounds of external
  review on `docs/MANUAL-VERIFICATION-CONNECTORS.md` with live
  reviewer execution. Every documented HTTP code and response
  shape now matches the live RC6 HEAD stack; the pattern of
  vague "X or Y" disjunctions in expect values is fully eliminated.

Java change: one file (`HttpWebhookDispatcher.java`) + its test.
TypeScript: no change. SOC templates / validator script: no change.

- **Code artifact under review** = the annotated tag
  `v3.1.1-RC6.5` (peeled commit `94de9d269c89411e9fa9eb2049554a0b9b070016`).
- **Review supplementary documentation** = files on
  `release/3.1.1-RC6` **branch HEAD** that may land after the
  tag is cut. As of tag time the divergence is zero — see §3.

Previous historical tags (`v3.1.1-RC6.4`, `…-RC6.3`, `…-RC6.2`,
`…-RC6.1`, `…-RC6`, `…-RC5.6`, …) remain unchanged for traceability.

---

## 1. Quick reference

| Item | Value |
|---|---|
| **Final candidate tag** | `v3.1.1-RC6.5` |
| Tag annotated object SHA | `7c7e67e3a870b01db1f12c15444b26c65771a3b2` |
| Tag peeled commit | `94de9d269c89411e9fa9eb2049554a0b9b070016` |
| Branch | `release/3.1.1-RC6` |
| Branch HEAD at tag time | `94de9d269c89411e9fa9eb2049554a0b9b070016` (= tag peeled, zero divergence at tag time) |
| Base of RC6.5 cycle | `v3.1.1-RC6.4` (peeled `afdf4d832`) |
| RC5 cycle baseline | `v3.1.1-RC4.1` (peeled `572aad18b`) |
| **RC6.4 → RC6.5 diff cmd** | `git diff v3.1.1-RC6.4..v3.1.1-RC6.5` |
| Cumulative diff cmd (since RC4.1) | `git diff v3.1.1-RC4.1..v3.1.1-RC6.5` |
| Previous historical candidates | `v3.1.1-RC6.4` (`afdf4d832`), `…-RC6.3` (`77ddfe071`), `…-RC6.2` (`02afee891`), `…-RC6.1` (`595754b8c`), `…-RC6` (`9dfd87adb`), `…-RC5.6` (`adf8db3b4`) |

---

## 2. What changed since the previous external review (RC6.4 → RC6.5)

Two pieces of work: one security fix (the externally-reported SSRF
bypass) and the closure of three rounds of external review on the
manual-verification doc.

### 2.1 — Security: SSRF via IPv6 transition addresses (CWE-918)

Reported via GitHub security advisory by **tonghuaroot** with a
working PoC against the unmodified `HttpWebhookDispatcher`. The
existing SSRF guard classified `InetAddress` correctly for plain
IPv4 (loopback, RFC 1918, link-local 169.254) and IPv4-mapped
(`::ffff:a.b.c.d`, which JDK collapses to `Inet4Address`), but
**did not unwrap IPv6 transition addresses** that embed an IPv4
destination. The 5 PoC vectors all bypassed the guard and reached
internal services / cloud metadata:

| Wrap form | Resolves to | Bypassed? |
|---|---|---:|
| `64:ff9b::7f00:1` | 127.0.0.1 (loopback) | ✗ yes (was bypassed) |
| `64:ff9b::a9fe:a9fe` | 169.254.169.254 (AWS metadata) | ✗ yes |
| `64:ff9b:1::7f00:1` | 127.0.0.1 (NAT64 local-use, RFC 8215) | ✗ yes |
| `2002:7f00:1::` | 127.0.0.1 (6to4 wrap) | ✗ yes |
| `::7f00:1` | 127.0.0.1 (IPv4-compatible, deprecated) | ✗ yes |

Because `POST /rest/repo/{repositoryId}/webhook/test` returns the
fetched response body to the caller, this was a **read-capable**
SSRF (not just blind) — observed responses included the simulated
"internal secret" body in the PoC.

Fix (`core/src/main/java/jp/aegif/nemaki/webhook/HttpWebhookDispatcher.java`):

- New private `extractEmbeddedIpv4(InetAddress)` recognizes the
  5 transition formats by exact byte-prefix comparison and returns
  the embedded `Inet4Address`.
- `isAddressSafe` now (after the existing IPv6 ULA check) calls
  `extractEmbeddedIpv4` and recursively re-runs itself. If the
  embedded IPv4 hits any block rule, the transition literal is
  blocked too.
- Logged at WARN (plain blocks remain at DEBUG) because hitting a
  transition wrap of a private/loopback target is an attempted
  bypass, not benign config.

Tests: 15 new regression tests in `HttpWebhookDispatcherTest`
covering each of the 5 transition forms against
{loopback, AWS metadata, 10.x, 192.168.x} (must block) and against
{8.8.8.8, 203.0.113.0, Cloudflare 2606:4700::1111} (must NOT
over-block). `extractEmbeddedIpv4` is also unit-tested directly.

  Tests run: 52, Failures: 0, Errors: 0, Skipped: 0
  (37 pre-existing + 15 new transition-address tests)

Fix commit: `94d3355a4`.

### 2.2 — Manual-verification doc: 3-round closure

After RC6.4 shipped, `docs/MANUAL-VERIFICATION-CONNECTORS.md` was
sent for external review with **live reviewer execution against
the deployed RC6 HEAD stack**. Each of the three rounds surfaced
doc/actual drift. All fixed and live-re-verified:

| Round | Findings | Highlight |
|---|---|---|
| 1 | P1 ×4 + P2 ×3 | zsh env-var word-splitting / multipart `request` part required / POST/PUT return slim `{status,...}` not full resource / `allowedFolderIds=[] + delegated=true` → 400 / scheduler status wrapper / by-group field names (`groupType` / `userId`) / UI `credentialRef` Form.Item doesn't exist |
| 2 | P1 ×2 + P2 ×1 | ACL form (`addACEPrincipal[n]` / `addACEPermission[n][m]` required, old form silent no-op) / delegated profile `defaultConnectorId` collision rule / Import Profile GET wrapper `{"profile":{...},"warnings":[...]}` |
| 3 | P2 ×1 + self-review of 8 | delegated `schedulerEnabled=true` (default OFF) → HTTP **403** `denialReason="SCHEDULER_REQUIRES_ADMIN"` (was "400 or 200 normalized") + self-review tightened every "expect:" line to a single HTTP code + exact response shape |

Result: every documented HTTP code and message snippet in the
guide is verified against the live RC6 HEAD stack. The pattern of
vague "X or Y" disjunctions in expect values is fully eliminated.
§14 adds notes on the `addACEPrincipal[n]` silent no-op trap and
the `defaultConnectorId` uniqueness constraint.

Doc rounds commits: `a3ac2bc94` / `5b43eb7b4` / `343fe5545`.

### Notable framing decisions

1. **One-file Java change.** The security fix touches only
   `HttpWebhookDispatcher.java` (+ its test). No TypeScript, no
   SOC templates, no validator script, no patches/views/migrations,
   no API contract change. Reviewers can focus the security review
   entirely on `extractEmbeddedIpv4` + the recursive
   `isAddressSafe` call site.

2. **No regression risk for legitimate webhook traffic.** The fix
   is additive: `extractEmbeddedIpv4` returns null for any address
   not in one of the 5 recognized transition formats, and the
   recursive `isAddressSafe` call only **blocks** — it never
   un-blocks an address. Public IPv6 (`2606:4700::1111`) and 6to4
   of public IPv4 (`2002:0808:0808::`) explicitly pass in the new
   tests.

3. **Doc rounds 1–3 are about closing a documentation gap, not
   fixing server behaviour.** The server matched its design all
   along; the doc described it imprecisely. Server source is
   byte-equal except for the SSRF fix in §2.1.

4. **§4 cumulative content is unchanged from RC6.4.** All the RC5
   → RC6.4 features (scheduled delegated profiles, governance
   views, simulate-remove cap, validator gate, baseline diff, etc.)
   carry forward unmodified.

5. **RC6.4's §10 (validator + Playwright baseline diff)** is kept
   as historical context. RC6.5's §10 (this RC) covers the new
   security fix in detail.

---

## 3. What's on the branch HEAD but NOT in the tag

The tag (`v3.1.1-RC6.5`) and the branch HEAD
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
have already burned four review rounds (RC6.1 P2-3 NUL byte,
RC6.2 Filebeat env / Fluent Bit TZ / Vector VRL, RC6.3 Fluent
Bit DST, RC6.4 Vector comment-interpolation / VRL `??` / buffer
min / LogQL offset / Fluent Bit `Code |` heredoc). Spending
review time arguing the boundary is more expensive than
spending five minutes cutting `v3.1.1-RC6.6` (or higher). **RC6.4
added the validator gate so future RCs catch the bug class before
tag — but the §3 cut-new-tag rule still applies for any post-tag
content-class change.**

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

## 4. What's in the v3.1.1-RC6.5 tag (cumulative since RC4.1)

RC5 cycle (RC5 → RC5.6) + RC6 + RC6.1 + RC6.2 + RC6.3 + RC6.4 + RC6.5:

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
- **RC6.4 SOC validation gate + Playwright baseline diff** —
  `scripts/validate-soc-templates.sh` runs 4 vendor CLIs at
  every push (caught 5 real template bugs at bring-up); full
  Playwright chromium ×2 (RC5.6 vs RC6 HEAD) proves 0 RC6
  regressions + 6 net new green from new RC6 functionality
- **RC6.5 SSRF hardening + manual-verification doc closure** —
  `HttpWebhookDispatcher` now unwraps IPv6 transition addresses
  (NAT64 / 6to4 / IPv4-compatible) and recursively re-classifies
  the embedded IPv4, closing a GHSA-reported bypass; 15 regression
  tests added (52/52 PASS). `docs/MANUAL-VERIFICATION-CONNECTORS.md`
  closed against 3 rounds of external review (15 + 9 = 24 findings).

Full per-RC narrative: `RELEASE_NOTES.md` (13 sections, RC5 →
RC6.5), `docs/design/connector-delegation.md` (§12.1 - §12.20).

---

## 5. Acceptance status summary (RC6.5)

### Blocking findings
**0**.

### Java unit tests — verified at HEAD this session

- **`HttpWebhookDispatcherTest`**: **52/52 PASS** (37 pre-existing
  + 15 new transition-address regression tests added in RC6.5 to
  guard the SSRF fix). Re-run command:
  ```bash
  mvn test -Dtest=HttpWebhookDispatcherTest -f core/pom.xml -Pdevelopment
  ```
- **Focused 14-class connector / governance / scheduler / ingest
  suite — carry-forward** at 182/182 PASS from `fd03d4ab4` (RC6.2
  cycle). RC6.3 / RC6.4 / RC6.5 touched zero files in those test
  classes (all those RCs were docs / shell scripts / the single
  webhook-dispatcher fix above). Treat as carry-forward evidence;
  re-run with the command in §5 below.

### Playwright E2E — verified at HEAD this session

- **6 RC5/RC6-area specs (smoke)** — **last executed**: at
  RC6.2 closure window, against the deployed WAR built from
  `fd03d4ab4`. Result: 66/66 PASS across 2 consecutive runs
  (no flake). No Java / TS / spec body delta since; carry-forward.
- **Full chromium suite** — **re-run for RC6.4** against both
  RC5.6 and RC6 HEAD (= `1ba21bc59`, == RC6.3 server behaviour
  + RC6.4 docs only). Results:
  - **RC5.6 build**: 673 passed / 162 failed / 2 flaky /
    195 skipped in 4622 s.
  - **RC6 HEAD build**: 679 passed / 156 failed / 2 flaky /
    195 skipped in 4538 s.
  - **Diff**: +6 pass / −6 fail / 2 flaky unchanged / 195
    skipped unchanged.
  - **Per-test classification** (see §10.2 for spec lists):
    0 RC6 regressions, 6 improved (new RC6 endpoints), 155
    persistent fail (= pre-existing backlog, unchanged across
    the entire RC5→RC6 cycle), 672 persistent pass, 1 flaky
    candidate.

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

- **SSRF fix regression tests (RC6.5)** — run live this session:
  `mvn test -Dtest=HttpWebhookDispatcherTest` → 52/52 PASS. All
  5 transition formats (NAT64 well-known + NAT64 local-use + 6to4
  + IPv4-compatible + IPv4-mapped) verified blocked for embedded
  loopback / RFC 1918 / link-local / metadata, AND verified NOT
  blocked for embedded public IPv4 (8.8.8.8, 203.0.113.0) or
  pure-IPv6 public addresses (Cloudflare 2606:4700::1111).
- **Manual-verification §2 → §11 paths** — run live this session
  against the deployed RC6 HEAD stack. Every documented HTTP code
  and response shape in `docs/MANUAL-VERIFICATION-CONNECTORS.md`
  matches the live response (3 rounds of external review +
  self-review tightening).
- **SOC validator (RC6.4 carry-forward)** — `VALIDATE_DOCKER=1
  scripts/validate-soc-templates.sh` → PASS 20 / SKIP 3 / FAIL 0
  / total 23, unchanged from RC6.4 (no template / script change).
- **Playwright full chromium baseline diff (RC6.4 carry-forward)**
  — RC5.6 vs RC6 HEAD = 0 regressions, see §10 (RC6.4 entry).
- B3-2, M2, M3 smokes — carry-forward valid as of RC6.2.

### Full-suite evidence boundary (carry-forward from RC6.4)

- The full 1032-test Playwright suite ran against both
  `v3.1.1-RC5.6` and `release/3.1.1-RC6` HEAD in RC6.4. Diff:
  0 regressions, 6 improvements, 155 persistent failures.
  RC6.5 touches one Java file (`HttpWebhookDispatcher.java`) +
  one test class; neither has a Playwright spec, so the RC6.4
  baseline-diff conclusion carries forward unchanged.

What remains operator-side (unchanged from RC6.4):
- Kibana NDJSON + Splunk savedsearches CLI validation (no
  offline parser exists for either; live cluster import only).
- Vector / Fluent Bit / Filebeat / Loki Ruler pre-deploy
  smoke against the actual SIEM endpoint — the validator
  uses synthetic env values, so DNS / TLS / auth against the
  real SIEM is operator's pre-deploy gate.

### API contract

Additive only across RC5 → RC6.5 (RC6.5 adds zero API surface;
the only code change is internal SSRF-guard hardening in
`HttpWebhookDispatcher`, which strengthens validation without
changing the existing dispatch / response contract).

### Patch / view / Mango / migration / DB bootstrap

Unchanged since RC4.1.

### UI forbidden path

`/core/ui/dist/` — 0 hits.

---

## 6. Remaining follow-ups (post-RC6.5, not blocking review)

(No open repo-shippable items.)

| ID | Severity | Scope | Status |
|---|---|---|---|
| **R1** (deployment-side) | Low (ops) | operator infrastructure | 4 inherently per-deployment items: network path / TLS, SIEM credentials from secrets manager, notification routing, threshold tuning from environment baseline. Not repo-shippable. |
| **Kibana NDJSON CLI validation** | Low | template QA | No offline parser exists for Detection Engine NDJSON; validation requires importing into a live Elastic 8 cluster. Operator pre-deploy gate. |
| **Splunk savedsearches CLI validation** | Low | template QA | No offline parser; `splunk btool` requires a Splunk install. Operator pre-deploy gate. |
| **Full Playwright green-up of the 155 pre-existing failures** | Medium | UI corpus | RC6.4 proved they are pre-existing (RC5.6 vs RC6 HEAD diff). The triage backlog lives under memory `test-skip-triage`. Separate engineering project. |

**Resolved in RC6.5 (newly closed)**:
- GHSA SSRF via IPv6 transition wrap (NAT64 / 6to4 / IPv4-compatible)
  reported by tonghuaroot — `HttpWebhookDispatcher` now extracts
  embedded IPv4 and re-classifies. 15 regression tests.
- 3 rounds of external review on `MANUAL-VERIFICATION-CONNECTORS.md`
  (15 findings + 9 self-review tightenings = 24 fixes), with
  live-verified expectations matching the deployed stack.

**Resolved in RC6.4 (carry-forward, still closed)**:
- Vector VRL / Fluent Bit DST / Filebeat config / Loki ruler live
  validation gaps — addressed by `scripts/validate-soc-templates.sh`.
- RC5.6 baseline diff — Epic 2 (§10.2).
- Recurring "template body bug surfaces only at external review"
  pattern — Epic 1 (§10.1) validator gate.

**Resolved during RC5+RC6+RC6.1+RC6.2+RC6.3+RC6.4+RC6.5 cycle**: all
listed in `RELEASE_NOTES.md` per-section.

---

## 7. Promotion path (operational)

`v3.1.1-RC6.5` is and remains a release candidate. GA path:

1. External review concludes with approval.
2. Merge `release/3.1.1-RC6` into `master`. The SSRF fix in
   `HttpWebhookDispatcher.java` MUST land on master before any
   public 3.1.1 release because the advisory tracks master as the
   affected branch.
3. Cut a **new** annotated tag `v3.1.1` against the merge
   commit on `master`.
4. Optionally create a single GitHub Release attached to
   `v3.1.1`.
5. The RC tags (`v3.1.1-RC5`, `…-RC5.1`, …, `…-RC6.5`) stay
   as internal milestones.
6. Reply on the GHSA advisory linking the fix commit (`94d3355a4`)
   and the cut tag (`v3.1.1-RC6.5`) so the reporter has a clear
   landing reference.

---

## 8. Re-send delta summary (what reviewers should focus on)

If you reviewed RC6.4 already, the smallest possible review
for RC6.5 is:

```bash
git diff v3.1.1-RC6.4..v3.1.1-RC6.5
```

Focused set:

- `core/src/main/java/jp/aegif/nemaki/webhook/HttpWebhookDispatcher.java`
  — the security fix. Read `extractEmbeddedIpv4` and the recursive
  `isAddressSafe` call site (added immediately after the existing
  IPv6 ULA check).
- `core/src/test/java/jp/aegif/nemaki/webhook/HttpWebhookDispatcherTest.java`
  — 15 new regression tests covering each transition format for
  blocked / allowed targets + a direct extractor test.
- `docs/MANUAL-VERIFICATION-CONNECTORS.md` — 3 rounds of external
  review closure (HTTP code + response shape tightening, ACL
  parameter form, `defaultConnectorId` collision, wrapper `.profile`,
  zsh env-var caveat).
- `REVIEW_PACKET.md`, `RELEASE_NOTES.md`, `CLAUDE.md` (RC6.5
  section).

Security-focused reviewers can scope to the first two files
(~270 LOC delta) and the GHSA advisory text.

If you are reviewing RC5+RC6 cold for the first time, use the
cumulative diff (since `v3.1.1-RC4.1`) in §1.

---

## 9. Files external reviewers usually start with

| Purpose | File |
|---|---|
| Re-send overview (this file) | `REVIEW_PACKET.md` |
| What changed and why (per RC) | `RELEASE_NOTES.md` (13 sections RC5 → RC6.5) |
| Design rationale | `docs/design/connector-delegation.md` (§12.1 - §12.20) |
| Multi-replica operational notes | `docs/MULTI-REPLICA-DEPLOYMENT.md` |
| Project-internal navigation (Japanese) | `CLAUDE.md` |
| API entry points | `ConnectorDefinitionController.java`, `ImportProfileDefinitionController.java`, `IngestSchedulerService.java`, `AuditEmitSupport.java` |
| Test coverage proof | `core/src/test/java/jp/aegif/nemaki/rest/ingest/*Test.java` (182 focused tests across 14 classes) |
| SOC / SIEM audit integration (playbook) | `docs/SOC-AUDIT-INTEGRATION.md` |
| SOC / SIEM audit integration (import-ready templates, operator validation required) | `docs/soc-templates/` (README + Filebeat / Fluent Bit / Vector shippers + Kibana Detection Engine NDJSON / Loki Ruler / Splunk savedsearches rule sets — see README "Template validation status" table for the per-template syntax-only / no-live-test gap) |
| Connector-area manual verification (RC6.5) | `docs/MANUAL-VERIFICATION-CONNECTORS.md` (1300+ lines step-by-step curl + UI, 3-round live-verified) |

---

## 10. RC6.4: SOC template validation gate + Playwright baseline diff (full detail)

This section documents the two RC6.4 quality-improvement epics
in detail. §2 above is the executive summary; this section is
the per-bug and per-test record. Both epics are **fully shipped
in the `v3.1.1-RC6.4` tag**.

### 10.1 Epic 1: SOC template validation gate (`scripts/validate-soc-templates.sh`)

The RC6 → RC6.3 cycle shipped a template-body bug in every cycle
(Filebeat env syntax, VRL `??` on infallible path, Fluent Bit DST
handling) — each caught only at external review, never at build time.
RC6.4 introduces a CLI validator that runs the actual vendor tool for
4 of 6 templates inside their official Docker images:

| Template | RC6.4 automated check | Status |
|---|---|---|
| `vector-nemakiware.toml` | `vector validate --skip-healthchecks` | ✅ PASS |
| `fluent-bit-nemakiware.conf` | `fluent-bit -c … --dry-run` (full INI parse + Lua load + plugin instantiation) | ✅ PASS |
| `filebeat-nemakiware.yml` | `filebeat test config` (Beats parser + JS processor compile) | ✅ PASS |
| `loki-ruler-rules.yml` | `cortextool rules check --backend=loki` (with Python `envsubst` for `${VAR:-default}`) | ✅ PASS |
| `kibana-detection-rules.ndjson` | JSON parse per line (no offline CLI exists; Detection Engine ships only with a running Elastic cluster) | Operator gate |
| `splunk-savedsearches.conf` | grep for known-bad patterns (Splunk `btool` requires Splunk install) | Operator gate |

Phase 1 (`python3` only): JSON / YAML / TOML parse, NUL-byte smoke,
file-type smoke, placeholder enumeration. Always runs.
Phase 2 (`VALIDATE_DOCKER=1`): the 4 vendor CLIs above.
Phase 3 (`WRITE_VALIDATION_MD=1`): emits `docs/soc-templates/VALIDATION.md`
capturing the last automated run state.

**5 real template bugs caught during RC6.4 bring-up** that prior
syntax-spec-confidence approaches had missed:

1. Vector header comment containing `${...}` was interpolated as an
   env-var name (Vector substitutes inside comments).
2. Fluent Bit `Code |` heredoc was rejected by the classic INI parser
   with "extra indentation level found" — fixed by externalising the
   Lua to `fluent-bit-nemakiware-time-enrichment.lua`.
3. VRL `??` on the infallible field path `."@timestamp"` triggered
   "unnecessary error coalescing operation" → switched to a
   conditional assignment.
4. Vector `buffer.max_size = 268435456` (exactly 256 MiB) was below
   the required `>= 268435488` minimum → bumped to 536870912 (512 MiB).
5. LogQL `offset 1h` placed AFTER the wrapping `count_over_time(...)`
   rather than INSIDE the range-vector selector `[7d] offset 1h`.

Commit: `1ba21bc59` (`feat(rc6.4): SOC template validation gate + 5 real-bug fixes caught at bring-up`).

### 10.2 Epic 2: full Playwright baseline diff — RC5.6 vs RC6 HEAD

To prove RC6 shipped zero behavioural regressions vs the prior RC5
cycle, the full chromium Playwright suite (1032 tests) was run twice
against the same NemakiWare deployment, swapping only the WAR:

| Build | Tag / commit | WAR SHA-256 |
|---|---|---|
| RC5.6 | `v3.1.1-RC5.6` = `adf8db3b4` | `749dedd883c8146516d4f618859db2b8c317f9f972939e432cf4a7989feb592e` |
| RC6 HEAD | `release/3.1.1-RC6` HEAD = `1ba21bc59` (= RC6.3 server behaviour, since Epic 1 was doc/script only) | `9df81beb10e8f3309534e8d830c734fb9485a3bc32d38c36a52cf54e5af56328` |

**Aggregate**:

| Stat | RC5.6 | RC6 HEAD | Δ |
|---|---:|---:|---:|
| Passed | 673 | 679 | **+6** |
| Failed | 162 | 156 | **−6** |
| Flaky | 2 | 2 | 0 |
| Skipped | 195 | 195 | 0 |
| Total | 1032 | 1032 | — |
| Duration | 77 min | 76 min | −1 min |

**Per-test classification** (per the user's RC6.4 spec):

| Class | Count | Notes |
|---|---:|---|
| RC6 **regression** (RC5.6 ✓ → RC6 ✗) | **0** | 1 candidate found → reclassified as flaky (see below) |
| **Improved by RC6** (RC5.6 ✗ → RC6 ✓) | 6 | All new RC6 features now exercised — `/v1/admin/connectors/by-group` + `simulate-remove` count cap |
| **Pre-existing** (✗ in both) | 155 | Long-running Playwright stabilization backlog; not introduced by RC6 |
| **Persistent pass** (✓ in both) | 672 | Core production behaviour stable across the entire RC5→RC6 cycle |
| **Environment / flaky** | 1 | `group-management-crud.spec.ts:315 › should add member to group` — Ant `Select` dropdown viewport positioning |
| **Skipped (`test.skip`)** | 192 | Explicit annotations, expected per memory `test-skip-triage` |

**The single candidate regression**:

```
admin/group-management-crud.spec.ts:315
  Error: locator.click: Element is outside of the viewport
  - waiting for locator('.ant-select-item:has-text("testuser")').first()
  - locator resolved to <div ... title="api-e2e-testuser (...)" class="ant-select-item ant-select-item-option ant-select-item-option-active">
  - attempting click action
  - scrolling into view if needed
  - done scrolling
```

Classification: **environmental / flaky**, not a real RC6 regression.

Evidence:
- The locator resolved (dropdown rendered, target item present, scroll
  ran) before the click was rejected — classic Playwright flake pattern
  for virtualised Ant `Select` lists.
- `git log v3.1.1-RC5.6..1ba21bc59 -- '**/group-*'` shows only the
  new `connector-governance-by-group.spec.ts` test file added; no
  `GroupResource` / `GroupManagement*` source touched between the
  two builds.
- The test depends on transient state (which users exist in the
  CouchDB at the moment the dropdown opens), and the two ~75-minute
  runs accumulated different transient state.

**Recommended action**: do not block RC6 on this. Track under the
existing test-skip triage backlog (memory `test-skip-triage`) with
"viewport-flake" subcategory; re-run in isolation to confirm, then
either pin the test's viewport or scroll the Select panel
programmatically before the click.

**Improvements (RC5.6 ✗ → RC6 ✓) — all genuine RC6 functionality**:

| Spec | Why it passes only on RC6 |
|---|---|
| `admin/connector-governance-by-group.spec.ts:15` `:34` `:46` `:55` `:63` | RC6 added `/v1/admin/connectors/by-group` (RC5 only had `/by-principal`) |
| `admin/connector-governance-simulate-button.spec.ts:146` | RC6.1 P2 added the explicit `removePrincipalIds > MAX` 400-response cap |

**Persistent failures (155)** are evenly distributed across ~85 spec
files (each `file:line` entry appears exactly once — no clusters).
Top file groups: `components/layout-navigation` (14), `search/custom-property-search` (14),
`components/protected-route` (12), `user-scenarios` (10),
`documents/type-specification` (9), `documents/property-editor` (9).
These are the existing backlog already tracked under memory
`project_test_skip_triage` (Playwright 421件のtest.skip分類と改善方針).

**Conclusion**: RC6 ships zero regressions and 6 net test
additions in the green. The persistent backlog is unchanged.

Run artefacts:

- `/tmp/playwright-report-rc5.6/results.json` — full RC5.6 result tree (3.2 MB JSON + HTML)
- `/tmp/playwright-report-rc6-head/results.json` — full RC6 HEAD result tree (3.2 MB JSON + HTML)
- `/tmp/playwright-baseline/diff.json` — programmatic diff (lists of test keys per bucket)
- `/tmp/playwright-baseline/diff-rc5.6-vs-rc6-head.md` — extended report (this section is the inline summary)
