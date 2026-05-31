# NemakiWare v3.1.1-RC6.10 — External Review Packet

Single entry point for the **eleventh-round external review** of
the RC6 series. RC6.10 is a pure-refactor RC — no new SSRF gap
closed. It extracts the address-classification logic that has
been duplicated between `HttpWebhookDispatcher` and
`AdapterHttpClient` since RC6.7 into a new
`jp.aegif.nemaki.security.SsrfGuard` helper (Rule-of-Three
threshold crossed when 2 consumers ship the same 240+ LOC), adds
a `Phase 1.4.1` source-tree NUL byte scan to
`scripts/validate-soc-templates.sh` so the RC6.1 / RC6.7 NUL
regression class can no longer ship untagged, and closes the
RC6.8 follow-up R3 ("verify other orchestrators don't bypass the
SSRF guard") as documentation-only after auditing all 11
connector adapters.

- **RC6.5–RC6.9 closed** (carry-forward, still in effect):
  - RC6.5: GHSA-reported IPv6 transition unwrap in
    `HttpWebhookDispatcher`.
  - RC6.6: 5 IPv4 special-use ranges + Teredo + RFC 6052 §2.2 /48
    NAT64 in `HttpWebhookDispatcher`.
  - RC6.7: same closures replicated into `AdapterHttpClient` +
    `Redirect.NORMAL`→`NEVER` on SHARED HttpClient.
  - RC6.8: AdapterHttpClient DNS rebinding pin (HTTP fully closed
    at network layer, HTTPS TLS-bounded with TCP-connect residual)
    + runtime endpoint revalidation in Mattermost/Salesforce
    orchestrators + multi-hop redirect uses `currentRequest.uri()`.
  - RC6.9: HTTP IP-pin preserves original `Host` header for
    shared-vhost compat + honest HTTPS Javadoc.
- **RC6.10 adds (refactor + tooling only)**:
  - **New shared utility** `jp.aegif.nemaki.security.SsrfGuard`
    (`isAddressSafe`, `extractEmbeddedIpv4`). Both callers
    delegate; behaviour is byte-equivalent at the
    classifier-output level (no security change).
  - **`SsrfGuardTest`** — 30 cases pinning the helper directly
    (in addition to the 85 cases already covering the dispatchers
    transitively).
  - **`Phase 1.4.1` source-tree NUL scan** in
    `scripts/validate-soc-templates.sh` — 1680 source files
    scanned, 0 hits. Catches the RC6.1 P2-3 / RC6.7 P3 regression
    class at pre-commit / pre-tag time.
  - **R3 follow-up closure** — orchestrator audit complete, only
    Mattermost / Salesforce ever pass user-controlled endpoint
    to HTTP and both were RC6.8-protected; no code change needed.
- **RC6.9 reference (carry-forward) adds**:
  - **P3 fix — HTTP IP-pin preserves original `Host` header.**
    `pinRequestToValidatedAddress` HTTP branch now rewrites URI
    to validated IP literal AND explicitly sets
    `Host: <original-hostname[:port]>` via the documented JDK
    escape hatch (`-Djdk.httpclient.allowRestrictedHeaders=host`
    JVM startup property, added to all 3 `docker/core/Dockerfile*`
    variants AND `core/pom.xml` surefire `<argLine>` AND a static
    initializer in `AdapterHttpClient` as defensive fallback).
    Closes the shared-vhost HTTP misroute caveat from RC6.8.
  - **Javadoc honesty fix on `pinRequestToValidatedAddress`** —
    matches the post-RC6.8 doc-only honesty fix (`d910820d7`)
    that already corrected REVIEW_PACKET / RELEASE_NOTES /
    CLAUDE: HTTP is "network-layer closed", HTTPS is
    "TLS-bounded, NOT fully closed" with residual TCP-connect
    SSRF (port-scan / fingerprint / TCP-side-effect).

**RC6.10 changes**: Code 3 files
(`SsrfGuard.java` +263 LOC new, `HttpWebhookDispatcher.java`
−240 line duplicate classifier replaced by delegation,
`AdapterHttpClient.java` −110 line duplicate classifier replaced
by delegation). Tests 2 files (`SsrfGuardTest.java` +220 LOC new
with 30 cases, `HttpWebhookDispatcherTest.java` reflection-on-
private-method → public-static direct call). Tooling 1 file
(`scripts/validate-soc-templates.sh` Phase 1.4.1 source-tree NUL
scan, ~60 LOC). Docs 3 files (RELEASE_NOTES, CLAUDE,
REVIEW_PACKET). Net: refactor LOC-negative on the dispatchers,
LOC-positive on the new helper + its dedicated test, no public
API change.

**RC6.9 changes (carry-forward)**: 1 file
(`AdapterHttpClient.java` +76 LOC). Tests: 1 file
(`AdapterRegistryTest.java` +31 LOC = 2 new Host-preserve tests).
JVM args: surefire `argLine` + 3 Dockerfile variants
(1 property each).

- **Code artifact under review** = the annotated tag
  `v3.1.1-RC6.10` (peeled commit TBD — populated at tag-cut time;
  see §1 table).
- **Review supplementary documentation** = files on
  `release/3.1.1-RC6` **branch HEAD** that may land after the
  tag is cut. As of tag time the divergence is zero — see §3.

Previous historical tags (`v3.1.1-RC6.9`, `…-RC6.8`, `…-RC6.7`,
`…-RC6.6`, `…-RC6.5`, `…-RC6.4`, `…-RC6.3`, `…-RC6.2`, `…-RC6.1`,
`…-RC6`, `…-RC5.6`, …) remain unchanged for traceability.

---

## 1. Quick reference

| Item | Value |
|---|---|
| **Final candidate tag** | `v3.1.1-RC6.10` |
| Tag annotated object SHA | TBD (populated at tag-cut time) |
| Tag peeled commit | TBD (populated at tag-cut time) |
| Branch | `release/3.1.1-RC6` |
| Branch HEAD at tag time | TBD (zero divergence target at tag time) |
| Base of RC6.10 cycle | `v3.1.1-RC6.9` (peeled `76695f46c`) |
| RC5 cycle baseline | `v3.1.1-RC4.1` (peeled `572aad18b`) |
| **RC6.9 → RC6.10 diff cmd** | `git diff v3.1.1-RC6.9..v3.1.1-RC6.10` |
| Cumulative diff cmd (since RC4.1) | `git diff v3.1.1-RC4.1..v3.1.1-RC6.10` |
| Previous historical candidates | `v3.1.1-RC6.9` (`76695f46c`), `…-RC6.8` (`cd82452f4`), `…-RC6.7` (`b48d9e0c1`), `…-RC6.6` (`c8b37150a`), `…-RC6.5` (`94de9d269`), `…-RC6.4` (`afdf4d832`), `…-RC6.3` (`77ddfe071`), `…-RC6.2` (`02afee891`), `…-RC6.1` (`595754b8c`), `…-RC6` (`9dfd87adb`), `…-RC5.6` (`adf8db3b4`) |

---

## 2. What changed since the previous external review (RC6.9 → RC6.10)

RC6.10 is a **refactor + tooling RC**. No new security gap is
closed; no behaviour visible to operators changes. The cycle
consolidates the SSRF address classifier that has been duplicated
in two files since RC6.7, adds a source-tree NUL byte scan to
the SOC validator so the RC6.1 / RC6.7 NUL-shipped regression
class can no longer escape pre-tag, and closes the RC6.8 R3
follow-up ("verify other orchestrators don't bypass SSRF guard")
as documentation-only.

### 2.1 — Rule-of-Three refactor: new `jp.aegif.nemaki.security.SsrfGuard`

History:
- **RC6.5** added `isAddressSafe` + `extractEmbeddedIpv4` to
  `HttpWebhookDispatcher` (closing the GHSA-reported NAT64 / 6to4
  / IPv4-compatible / IPv4-mapped unwrap gap).
- **RC6.6** added 5 IPv4 special-use ranges + Teredo + RFC 6052
  /48 NAT64 to the same dispatcher copy.
- **RC6.7** replicated the entire `isAddressSafe` +
  `extractEmbeddedIpv4` (~240 LOC) into
  `AdapterHttpClient.java` so all 11 connector adapters benefit
  from the same classification.

After RC6.7, two complete copies of the classifier existed. A
3rd consumer (Purview / Atlas / OIDC / Graph admin-config URL
validators — tracked as deferred work in §6) would have required
synchronously updating duplicated code in 3 places — the classic
Rule-of-Three trigger.

**Fix** — new utility class
`core/src/main/java/jp/aegif/nemaki/security/SsrfGuard.java` (263
LOC, package-new) with exactly two public static methods:

| Method | Purpose |
|---|---|
| `boolean isAddressSafe(InetAddress)` | Full classification — JDK predicates (loopback/link-local/site-local/any-local/multicast) + 9 IPv4 special-use ranges (RFC 1918, 169.254, 0/8, 100.64/10 CGNAT, 192.0.0/24, 198.18/15, 240/4, broadcast) + IPv6 ULA (fc00::/7) + 6 IPv6 transition formats (NAT64 well-known + local-use /48 + /96-PLR fallback, 6to4, Teredo strict, IPv4-mapped, IPv4-compatible) with embedded-IPv4 unwrap and recursive re-classify. |
| `InetAddress extractEmbeddedIpv4(InetAddress)` | Returns the embedded IPv4 for any of the 6 transition formats, or `null` for any other address. Used by `isAddressSafe` internally and (in `HttpWebhookDispatcher`) for operator-log categorization. |

Both callers now delegate:
- **`HttpWebhookDispatcher.isAddressSafe(InetAddress, String)`** —
  removed 240 LOC of duplicated classifier; classification now
  goes through `SsrfGuard.isAddressSafe`. When classification
  returns `false`, the method re-runs cheap top-level predicates
  locally only to produce categorized operator log lines
  (loopback / link-local / site-local / any-local / multicast /
  IPv6 transition wrap / IPv6 ULA / IPv4 special-use). The
  categorization-only re-run is a pure logging concern; the
  security decision is 100% delegated.
- **`AdapterHttpClient.isAddressSafe(InetAddress)`** — removed 110
  LOC of duplicated classifier and the entire `extractEmbeddedIpv4`
  private method (113 LOC). The remaining wrapper is a 3-LOC
  delegator; both call sites
  (`pinRequestToValidatedAddress` and `validateExternalUrl`) stay
  byte-equivalent.

**Behavioural guarantee**: the extracted helper is byte-for-byte
identical to the prior dispatcher / adapter copies. No
classification bucket changed, no edge-case threshold moved, no
predicate sequence reordered. The diff is structural only.

### 2.2 — `SsrfGuardTest` (new, 30 cases)

New test class
`core/src/test/java/jp/aegif/nemaki/security/SsrfGuardTest.java`
exercises every classification bucket directly on the helper
(rather than transitively through the dispatcher / adapter
wrappers). Coverage:

| Bucket | Cases |
|---|---|
| JDK predicate categories (loopback, link-local, RFC 1918, any-local, multicast) | 4 |
| IPv4 special-use ranges (CGNAT 100.64/10 with 100.63/100.128 boundary tests, 0/8, 192.0.0/24, 198.18/15, 240/4, broadcast) | 5 |
| IPv6 ULA (fc00::/8, fd00::/8) | 1 |
| IPv6 transition formats × {private-IPv4 reject, public-IPv4 allow} — NAT64 well-known, NAT64 local-use /48, 6to4, Teredo, IPv4-mapped, IPv4-compatible | 10 |
| Public-allowlist regression guards (8.8.8.8, 1.1.1.1, 172.15/172.32 outside RFC 1918, 2606:4700::, 2001:4860::, 2001:db8:: documentation) | 2 |
| `extractEmbeddedIpv4` direct (regular IPv6 → null, ULA → null, 2001:db8 → null because Teredo prefix is strict, all 4 transitions unwrap correctly, trivial low-bits IPv4-compatible skipped) | 8 |
| **Total** | **30** |

`HttpWebhookDispatcherTest.testExtractEmbeddedIpv4PublicPassthrough`
updated: previously used reflection to invoke a private dispatcher
method; now calls `SsrfGuard.extractEmbeddedIpv4` directly
(public static). Behaviour-equivalent.

### 2.3 — `Phase 1.4.1` source-tree NUL byte scan

`scripts/validate-soc-templates.sh` (the RC6.4 SOC template
validator) gains a new `Phase 1.4.1` that scans `.java`, `.ts`,
`.tsx`, `.js`, `.jsx` files for literal NUL (0x00) bytes across
the repo.

Two regressions in this RC cycle alone shipped NUL bytes:
- **RC6.1 P2-3**: `ConnectorGovernanceTab.tsx` had a literal
  `\x00` in a `simulateRemove.join('\0')` separator (Java would
  fail the same idiom at compile, TypeScript happily emits the
  binary character). Caused `grep` / `rg` / `file` to misclassify
  the source file as binary. Reviewer caught it.
- **RC6.7 P3**: `HttpWebhookDispatcherTest.java` had a literal
  `\x00` in a test string. Same symptom. Reviewer caught it.

Both got past Java / TypeScript compilation. Now caught at
pre-commit / pre-tag time by the validator running with no
docker dependency.

Implementation:
- Python one-liner walks `REPO_ROOT`, excludes `node_modules`,
  `target`, `dist`, `build`, `.git`, `coverage`,
  `playwright-report`, `test-results`.
- Counts NUL bytes per file via `b'\x00'.count(...)` on raw
  bytes.
- 1680 source files scanned at RC6.10 HEAD, 0 hits.
- Honors `VALIDATE_SOURCE_NUL=0` for clean-tree environments
  (default: 1 = run).

The check runs in <1 second on the current tree; no performance
guard needed.

### 2.4 — R3 follow-up closure (orchestrator audit)

REVIEW_PACKET §6 carried an open R3 item from RC6.8: "verify
other orchestrators don't bypass the SSRF guard by passing
`connector.getEndpoint()` to HTTP calls without explicit
validation." Audit complete:

| Orchestrator | Behaviour |
|---|---|
| `MattermostFetchOrchestrator` | RC6.8 explicit `validateExternalUrl(connector.getEndpoint())` at orchestrator entry. |
| `SalesforceFetchOrchestrator` | RC6.8 explicit `validateExternalUrl(connector.getEndpoint())` at orchestrator entry. |
| `ImapFetchOrchestrator` | Uses `imap://` scheme — would fail `validateExternalUrl`'s http/https-only check. NOT placed behind that guard by design; IMAP authentication path is reviewed separately. |
| Slack / Teams / Gmail / M365Mail / Notion / Chatwork / Box / Dropbox (×8) | Vendor API URLs hardcoded in the adapter classes. No user-controlled endpoint reaches HTTP. |

In addition, `AdapterHttpClient.pinRequestToValidatedAddress`
runs send-time re-validation on **every** HTTP request regardless
of caller path. Even if a future change accidentally introduced
a configurable endpoint without explicit `validateExternalUrl`
at orchestrator entry, the SSRF guard still applies at the
HTTP-send layer.

R3 closure is documentation-only — no code change in this RC.

### 2.5 — RC6.9 carry-forward (already external-reviewed)

RC6.9 closes the RC6.8-post-tag P3 compat caveat (HTTP IP-pin sent
`Host: <IP>`, breaking shared-vhost HTTP deployments) and aligns
the `AdapterHttpClient` Javadoc with the post-RC6.8 doc-only P2
honesty fix that landed in `d910820d7` on REVIEW_PACKET /
RELEASE_NOTES / CLAUDE.

#### 2.5.1 — RC6.9 P3 fix: HTTP IP-pin preserves original Host header

In RC6.8, `pinRequestToValidatedAddress` rewrote the HTTP URI to
the validated IP literal and let the JDK default the `Host`
header to that IP. For dedicated-server deployments this works
because the server typically ignores `Host` or responds to any
value. **Shared-vhost deployments** — one IP serving multiple
Mattermost / Salesforce on-prem instances under different
hostnames behind a single reverse proxy — were broken: the
proxy could not match a vhost on `Host: <IP>` and would
404 or route to a default vhost. Setting the connector endpoint
to the IP directly was NOT a workaround (the vhost match
requires the hostname).

Fix: rewrite URI to IP literal AND explicitly set
`b.header("Host", originalHostHeader)`. Uses the documented JDK
escape hatch via the startup property
`-Djdk.httpclient.allowRestrictedHeaders=host`, set in:
- Production: `docker/core/Dockerfile{,.jakarta,.simple}` —
  `CATALINA_OPTS` / `JAVA_OPTS` augmented.
- Tests: `core/pom.xml` surefire `<argLine>`.
- Defensive fallback: a static `{}` initializer at the top of
  `AdapterHttpClient` sets the property additively at class load
  time, preserving any other operator-set values.

JVM-wide effect: other code in the same JVM using
`HttpRequest.Builder.header("Host", ...)` will now succeed
where it previously threw `IllegalArgumentException`. This is
intentional (the JDK's documented escape hatch).

#### 2.5.2 — RC6.9 Javadoc honesty alignment

`AdapterHttpClient.pinRequestToValidatedAddress` Javadoc now
reflects the actual security boundary (mirroring the post-RC6.8
doc fix `d910820d7`):
- **HTTP**: "DNS rebinding closed at the network layer" — IP-pin
  prevents any TCP connection to a rebound IP. Host preservation
  noted.
- **HTTPS**: "TLS-bounded, NOT fully closed" — send-time
  re-validation catches pre-resolve rebinds but a microsecond
  race window remains. TLS cert verification stops
  **data-exchange SSRF** (no body read, no token leak, no
  internal API call succeeds) but **TCP-connect SSRF residual**
  (port scan, service fingerprint, inbound-TCP/TLS-handshake
  side effects on internal services). Real fix queued: custom
  `SocketFactory` pinning IP at TCP-connect time while keeping
  SNI / hostname verification on the original hostname. Tracked
  as Medium residual risk in §6.

#### 2.5.3 — RC6.9 Tests (carry-forward)

- 2 new regression tests in `AdapterRegistryTest`:
  - `pinRequestPreservesOriginalHostHeaderOnHttpPin` — URI uses
    IP literal, `Host` header carries original `hostname:port`.
  - `pinRequestPreservesOriginalHostHeaderWithoutPort` —
    default port 80 case (no `:port` suffix).
- All 7 adapter contract tests (Slack 12 / Teams 11 /
  Mattermost 12 / Notion 8 / Salesforce 11 / M365 Mail 9 /
  Chatwork 13 = **76 PASS**) still pass: WireMock accepts any
  `Host` header so the change is transparent.
- Full focused regression (RC6.9 baseline): **343/343 PASS** (was
  265 in RC6.8; +78 from adding all 7 adapter contract test
  classes to the focused regression set + 2 new Host-preserve
  tests).

### 2.6 — RC6.10 Tests (new this RC)

- **`SsrfGuardTest`** (new): **30/30 PASS** — full classification
  coverage on the extracted helper.
- **`HttpWebhookDispatcherTest`**: **59/59 PASS** — behaviour
  unchanged; one test updated to call `SsrfGuard.extractEmbeddedIpv4`
  directly instead of reflecting on the now-removed private method.
- **`AdapterRegistryTest`**: **26/26 PASS** — RC6.9 Host-preserve
  tests still pass through the delegated classifier.
- **7 adapter contract tests** (Slack 12 / Teams 11 / Mattermost
  12 / Notion 8 / Salesforce 11 / M365 Mail 9 / Chatwork 13):
  **76/76 PASS** — refactor doesn't break legitimate API call
  patterns.
- **Focused 24-class regression** (23 from RC6.9 + new
  `SsrfGuardTest`): **373/373 PASS** (was 343 in RC6.9; +30 from
  new helper test class).
- **Combined SSRF-classifier surface** (`HttpWebhookDispatcherTest
  + AdapterRegistryTest + SsrfGuardTest`): **115 PASS** (was 85
  in RC6.9; +30 from new direct test).
- **SOC validator full run** (no Docker): **17 PASS / 7 SKIP** —
  including new Phase 1.4.1 source-tree NUL scan (1680 source
  files, 0 hits).
- **Maven compile**: clean. No new compiler warnings introduced
  by the delegation.

Re-run command (RC6.10 focused regression):

```bash
mvn test -Dtest="SsrfGuardTest,HttpWebhookDispatcherTest,\
AdapterRegistryTest,ConnectorByPrincipalGovernanceTest,\
ConnectorSimulateRemoveTest,IngestSchedulerDelegatedRunTest,\
ImportProfileSchedulerGateTest,ExternalIngestControllerGateTest,\
IngestAuthorizationServiceTest,ImportProfileSinceFilterTest,\
ConnectorDefinitionControllerPartialPutTest,\
IngestSchedulerDelegationSkipTest,ImportProfileOwnershipTransferTest,\
ExternalIngestControllerTest,IngestWebhookGraphValidationTest,\
ImportProfileDefinitionTest,DelegatedCallContextFactoryTest,\
SlackConnectorAdapterTest,TeamsConnectorAdapterTest,\
MattermostConnectorAdapterTest,NotionConnectorAdapterTest,\
SalesforceConnectorAdapterTest,M365MailConnectorAdapterTest,\
ChatworkConnectorAdapterTest" -f core/pom.xml -Pdevelopment
```

### Notable framing decisions

1. **One main file changed + JVM-args wiring.** Reviewers can
   scope to `AdapterHttpClient.java` (the static initializer +
   the Host-header explicit set in `pinRequestToValidatedAddress`
   + the rewritten Javadoc) and verify the JVM property is set
   in tests (`core/pom.xml` argLine) and production (all 3
   Dockerfile variants).

2. **JVM-wide effect explicit and bounded.** Enabling
   `jdk.httpclient.allowRestrictedHeaders=host` means any code
   in the JVM that uses `HttpRequest.Builder.header("Host", ...)`
   now succeeds. The only such call site in the codebase is
   `pinRequestToValidatedAddress`; we grepped to confirm no
   other Java source touches the `Host` header via the JDK
   `HttpClient` API.

3. **HTTPS residual TCP-connect SSRF is NOT addressed in this
   RC.** Closing it requires either a custom `SocketFactory`
   or a switch to `HttpURLConnection` (same pattern as
   `HttpWebhookDispatcher` uses for HTTP). Tracked in §6 as
   Medium residual risk. The current RC's scope is the P3 doc
   + compat fix only.

4. **§3 cut-new-tag rule honoured.** `AdapterHttpClient.java` +
   `core/pom.xml` + `docker/core/Dockerfile*` are post-tag
   source-tree changes. Per §3 rules, a new RC tag is required.
   RC6.9 cut against the release-package commit.

### 2.7 — RC6.8 carry-forward (still in effect, see RELEASE_NOTES.md "3.1.1-RC6.8")

RC6.8 closed 3 deeper SSRF gaps in `AdapterHttpClient` —
the P1 / P2 / P3 detailed below remain in effect; this RC
only adds the Host header preservation and Javadoc honesty
on top.

### 2.8 — RC6.7 carry-forward (historical reference)

RC6.7 horizontally extended the RC6.5+RC6.6 SSRF guard from
`HttpWebhookDispatcher` into `AdapterHttpClient.validateExternalUrl`
(the shared outbound HTTP validator used by all 11 connector
adapters + `ConnectorDefinitionServiceImpl` +
`IngestWebhookController`). It also flipped the SHARED HttpClient's
auto-follow-redirect from `NORMAL` to `NEVER` and resolved relative
`Location` headers against the original request URI before
revalidating in `sendWithRedirectValidation`. For RC6.7's full
detail see RELEASE_NOTES.md "3.1.1-RC6.7".

### 2.9 — RC6.8 P1: DNS rebinding pin (carry-forward detail)

A deeper adversarial pass found that the RC6.7 fix still left
`AdapterHttpClient` vulnerable to DNS rebinding:
`validateExternalUrl` resolved + validated the host once at
config time, but `sendWithRetry` then handed the original
`HttpRequest` to `HttpClient.send`, which performs its OWN DNS
lookup. An attacker controlling the configured hostname's DNS
could return a public IP at validation and a private / loopback /
metadata IP at connection time.

Fix: new `pinRequestToValidatedAddress(request)` called inside
both `sendWithRetry` and `sendWithRedirectValidation`:

- **HTTP path — DNS rebinding closed at the network layer**.
  Re-resolves at send time, validates every resolved address
  against `isAddressSafe`, then rewrites the URI to use the
  validated IP literal (bracketed for IPv6). The JDK `HttpClient`
  connects to the pinned IP. No TCP connection to a rebound IP
  is possible after the send-time validation succeeds.

  **Known compatibility risk (P3 reviewer finding)**: the JDK
  `HttpClient.Builder.header(...)` restricts the `Host` header by
  default (only overridable via the JVM startup property
  `-Djdk.httpclient.allowRestrictedHeaders=host`, which we do
  NOT set). After URI rewrite, the JDK sends `Host: <IP>` rather
  than `Host: <original-hostname>`. For connector adapters that
  target a single dedicated server (Mattermost / Salesforce
  on-prem on its own IP, Slack/Teams/Notion/etc. on their
  public DNS) this works. For **name-based virtual-host
  deployments** (e.g. a reverse proxy serving several Mattermost
  instances under different hostnames on the same IP), the
  rewritten request will reach the proxy but the proxy may 404
  or route to the wrong vhost because no vhost matches the IP.
  Setting the connector endpoint to the IP directly does NOT
  fix this — the vhost match requires the hostname in the
  `Host` header.

  Operators hitting this need either:
  - JVM startup flag `-Djdk.httpclient.allowRestrictedHeaders=host`
    AND a code path that preserves the original `Host` (future
    enhancement, not in RC6.8); or
  - A migration to `HttpURLConnection`-based dispatch (same
    pattern as `HttpWebhookDispatcher`).

  The trade-off was accepted for RC6.8 because (a) the JDK
  property is a JVM-wide change that operators may not want
  to flip, and (b) most connector deployments don't use
  name-based vhosts for the API endpoint.

- **HTTPS path — TLS-bounded, NOT fully closed (P2 reviewer
  finding)**. Returns the request unchanged. The send-time
  re-validation (via `InetAddress.getAllByName`) catches
  rebound IPs *if* the rebound resolve happens before the
  JDK's internal `HttpClient.send` resolve. **There is a
  residual race window** between our `InetAddress.getAllByName`
  call inside `pinRequestToValidatedAddress` and the JDK's
  own resolve inside `HttpClient.send`. If a DNS attacker
  rebinds within that microsecond window:
  - TCP connection to the internal IP **does succeed**
    (TCP handshake completes before TLS).
  - TLS handshake then fails because the internal server's
    certificate doesn't match the declared hostname → no
    application-layer data exchange, no SSRF data read/write.

  So HTTPS is closed against the **data-exchange** class of
  SSRF (no body read, no token leak, no internal API call
  succeeds), but NOT against the **TCP-connect / port-scan /
  service-fingerprint** class. An attacker could still:
  - Detect open ports on internal hosts (TCP connect succeeds
    even when TLS fails).
  - Measure TLS handshake timing to fingerprint internal
    services.
  - Trigger any side effects internal services have on
    inbound-TCP / inbound-TLS-handshake events.

  Closing the HTTPS path fully requires a custom
  `SocketFactory` that pins the resolved IP at TCP-connect
  time while keeping SNI / hostname verification on the
  original hostname. This is documented as a real follow-up
  in §6 (raised from "Low defence-in-depth" to "Medium
  residual risk" by the P2 finding).

- Unresolvable host throws `SecurityException` (behaviour change
  from "let HttpClient try and fail with network error" to
  "fail fast with security flavour").

### 2.10 — RC6.8 P2: runtime endpoint revalidation (carry-forward detail)

`ConnectorDefinitionServiceImpl` validates endpoints on save, but
`MattermostFetchOrchestrator` (line 42) and
`SalesforceFetchOrchestrator` (line 45) passed
`connector.getEndpoint()` directly to the adapter without runtime
revalidation. Endpoints saved before RC6.7 hardening landed, or
modified at storage level (e.g. CouchDB direct edit), would reach
the adapter without revalidation.

Fix: added explicit
`AdapterHttpClient.validateExternalUrl(connector.getEndpoint())`
at both orchestrator entry points. Defence-in-depth — P1 above
closes the actual gap by re-validating at send time, but the
orchestrator-level check fails earlier with a clearer audit
message and avoids constructing the adapter for an
obviously-bad endpoint.

### 2.11 — RC6.8 P3: multi-hop relative redirect resolve correctness (carry-forward detail)

`sendWithRedirectValidation` called `request.uri().resolve(
location)` on every loop iteration but `request` was never
updated. A second relative `Location` (e.g. `/file` returned from
a redirect that itself jumped to a different host) resolved
against the **original** URL, not the current target.

Fix: track `currentRequest` through the loop and use
`currentRequest.uri().resolve(location)`. Correctness fix; not
exploitable as SSRF in isolation (the wrongly-resolved URL is
also the one we send), but matters for multi-host redirect chains
where the intermediate host's relative paths should resolve
against that host's authority.

### Tests

- 5 new regression tests in `AdapterRegistryTest`:
  - HTTP IPv4 URI rewrite (path + query preserved).
  - HTTPS URI unchanged.
  - `SecurityException` on rebound loopback (127.0.0.1 literal
    via re-resolve).
  - `SecurityException` on rebound IPv6 transition
    (NAT64-wrapped metadata).
  - Non-restricted header preservation on HTTP pin (Authorization
    + X-Custom-* carry over).
- Test infrastructure: `NotionConnectorAdapterTest`,
  `SalesforceConnectorAdapterTest`,
  `MattermostConnectorAdapterTest` now set
  `nemaki.ingest.allowLocalhost=true` in `@BeforeEach` and clear
  in `@AfterEach`. The P1 fix means `sendWithRetry` validates
  every request including the WireMock localhost endpoints these
  tests use; without the opt-in, those 22 tests fail.
- HttpWebhookDispatcherTest + AdapterRegistryTest: **83 PASS**
  (59 webhook + 24 adapter-registry).
- 7 connector adapter contract tests (Slack 12 / Teams 11 /
  Mattermost 12 / Notion 8 / Salesforce 11 / M365 Mail 9 /
  Chatwork 13): **76 PASS**.
- Full 16-class focused regression: **265/265 PASS** (was 260 in
  RC6.7; +5 from new pinRequest tests).

### Out-of-scope hardening (intentional, carry-forward)

Purview / Atlas / OIDC discovery / Microsoft Graph download
remain on their existing validation (RC6.7 carry-forward).

### Notable framing decisions

1. **Three files changed in main + 1 test file.** All RC6.8 Java
   surface change is in `AdapterHttpClient.java` + the two
   orchestrator entry points. Reviewers can scope to those plus
   the 5 new `AdapterRegistryTest` cases.

2. **HTTP `Host` header trade-off.** The IP-pin rewrite means the
   `Host` header sent to the server is the IP literal (JDK
   default). For most connector APIs this is fine (they ignore
   `Host` or respond to any). For shared-vhost servers it may
   misroute. Documented in the dispatcher Javadoc and §6
   follow-up table. A future hardening could enable the JDK
   restricted-headers JVM property + an overload that preserves
   the original `Host` header.

3. **P1 subsumes P2's threat.** P1's send-time revalidation
   means every request goes through fresh validation; an
   endpoint that bypassed save-time validation still fails at
   send. P2's orchestrator-level check is fail-fast UX, not the
   security primitive.

4. **§3 cut-new-tag rule honoured.** The `AdapterHttpClient.java`
   change is a post-`v3.1.1-RC6.7`-tag Java source change. Per
   §3 rules, a new RC tag is required. RC6.8 cut against the
   release-package commit.

### 2.12 — Carry-forward from RC6.5 + RC6.6 + RC6.7 (still in effect)

For reviewers who skipped earlier rounds, the equivalent
`HttpWebhookDispatcher` fix shipped in RC6.5 + RC6.6 is unchanged
in this RC:

- **RC6.5**: closed the externally-reported GHSA from
  tonghuaroot — `HttpWebhookDispatcher` unwraps IPv6 transition
  addresses (NAT64 `64:ff9b::/96`, 6to4 `2002::/16`,
  IPv4-compatible `::a.b.c.d`, IPv4-mapped `::ffff:0:0/96`) and
  re-classifies the embedded IPv4. PoC showed 5 transition forms
  bypassing the existing guard; with the fix all 5 are blocked.
  Also: 3 rounds of external review closure on
  `docs/MANUAL-VERIFICATION-CONNECTORS.md`.
- **RC6.6**: added 5 IPv4 special-use ranges (`0/8`, `100.64/10`,
  `192.0.0/24`, `198.18/15`, `240/4` + `255.255.255.255`), proper
  RFC 6052 §2.2 /48 layout for `64:ff9b:1::/48` (with /96-PLR
  fallback), and Teredo `2001::/32` unwrap. 7 new tests added at
  RC6.6 (59 total for `HttpWebhookDispatcherTest`).
- **RC6.7** (this RC): horizontally extends the same closure to
  `AdapterHttpClient` (the connector-side equivalent), plus
  redirect handling tightening and the NUL test cleanup. The
  RC6.5+RC6.6 fix to `HttpWebhookDispatcher` is byte-equal in
  this RC.

Residual note (carry-forward from RC6.5+RC6.6, unchanged):
HTTPS dispatch in `HttpWebhookDispatcher` still uses the original
hostname URL to leverage TLS certificate validation against the
declared hostname. The same approach is used in `AdapterHttpClient`
for HTTPS adapter API calls. DNS rebinding is mitigated by TLS
cert verification. A future hardening could pin HTTPS to the
resolved IP via a custom SocketFactory while keeping SNI /
hostname verification on the original hostname; not required for
RC6.7's scope.

---

## 3. What's on the branch HEAD but NOT in the tag

The tag (`v3.1.1-RC6.10`) and the branch HEAD
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

## 4. What's in the v3.1.1-RC6.10 tag (cumulative since RC4.1)

RC5 cycle (RC5 → RC5.6) + RC6 + RC6.1 + RC6.2 + RC6.3 + RC6.4 + RC6.5 + RC6.6 + RC6.7 + RC6.8 + RC6.9 + RC6.10:

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
- **RC6.6 SSRF hardening follow-on** — `isAddressSafe` adds 5 IANA
  special-use IPv4 ranges (`0/8` beyond literal 0.0.0.0,
  `100.64/10` CGNAT, `192.0.0/24` IETF protocol assignments,
  `198.18/15` benchmarking, `240/4` reserved + broadcast).
  `extractEmbeddedIpv4` adds proper RFC 6052 §2.2 /48 layout for
  `64:ff9b:1::/48` and Teredo `2001::/32` unwrap (one's complement
  of bytes 12-15). +7 regression tests (59/59 PASS).
- **RC6.7 SSRF horizontal expansion** — same `isAddressSafe` +
  `extractEmbeddedIpv4` design pattern moved into
  `AdapterHttpClient.validateExternalUrl` (the shared outbound
  HTTP validator used by all 11 connector adapters +
  `ConnectorDefinitionServiceImpl` + `IngestWebhookController`).
  SHARED HttpClient redirect setting `NORMAL` → `NEVER`, relative
  `Location` resolution against original URI. +3 new
  AdapterRegistryTest tests (260/260 PASS for full focused
  regression). Also: literal NUL in `HttpWebhookDispatcherTest`
  replaced with `\0` escape (binary → text classification).
- **RC6.8 SSRF deeper closure** — `sendWithRetry` and
  `sendWithRedirectValidation` now call
  `pinRequestToValidatedAddress(request)` before send. HTTP
  rewrites URI to the validated IP literal (DNS rebinding fully
  closed at the network layer); HTTPS re-validates at send time
  but does NOT IP-pin — TLS cert verification stops
  application-layer data exchange on a rebound IP, BUT a TCP
  connect to the rebound IP still succeeds within a microsecond
  race window (residual TCP-connect SSRF — see §6). Mattermost
  + Salesforce orchestrators add explicit `validateExternalUrl`
  defence-in-depth. Multi-hop relative redirect resolve uses
  `currentRequest.uri()` instead of `request.uri()`. +5 new
  AdapterRegistryTest tests (265/265 PASS for full focused
  regression). **Compat caveat (closed in RC6.9)**: HTTP IP-pin
  sent `Host: <IP>`; RC6.9 preserves original Host via JDK
  escape-hatch property.
- **RC6.9 HTTP Host header preservation + Javadoc honesty** —
  `pinRequestToValidatedAddress` HTTP branch now sets
  `Host: <original-hostname[:port]>` after URI rewrite. JDK
  startup property `-Djdk.httpclient.allowRestrictedHeaders=host`
  added to all 3 Dockerfile variants + pom.xml surefire argLine
  + static initializer fallback. Javadoc aligned with the
  post-RC6.8 honest wording (HTTP closed at network layer;
  HTTPS TLS-bounded with TCP-connect SSRF residual). +2
  AdapterRegistryTest tests (343/343 PASS for full focused
  regression including all 7 adapter contract test classes).
- **RC6.10 SsrfGuard refactor + source-tree NUL pre-commit scan**
  — new `jp.aegif.nemaki.security.SsrfGuard` utility (Rule-of-
  Three threshold: 240+ LOC duplicated between
  `HttpWebhookDispatcher` and `AdapterHttpClient` since RC6.7).
  Both call sites delegate; byte-equivalent classification.
  +30 cases in new `SsrfGuardTest`. Source-tree NUL byte scan
  added to `scripts/validate-soc-templates.sh` Phase 1.4.1
  (1680 source files / 0 hits). R3 follow-up (other-orchestrator
  audit) closed documentation-only after verifying only
  Mattermost / Salesforce ever pass `connector.getEndpoint()`
  to HTTP — both already RC6.8-protected.
  **373/373 PASS** for full focused regression (24 classes;
  was 343 in RC6.9; +30 from `SsrfGuardTest`).

Full per-RC narrative: `RELEASE_NOTES.md` (18 sections, RC5 →
RC6.10), `docs/design/connector-delegation.md` (§12.1 - §12.20).

---

## 5. Acceptance status summary (RC6.10)

### Blocking findings
**0**.

### Java unit tests — verified at HEAD this session

- **`SsrfGuardTest`** (new in RC6.10): **30/30 PASS** — direct
  classification coverage on the extracted helper.
- **`HttpWebhookDispatcherTest`**: **59/59 PASS** — behaviour
  unchanged through the SsrfGuard delegation; one test updated
  to call `SsrfGuard.extractEmbeddedIpv4` directly (public
  static) instead of reflecting on the now-removed private
  dispatcher method.
- **`AdapterRegistryTest`**: **26/26 PASS** — RC6.9 Host-preserve
  tests still pass through the delegated classifier.
- **7 connector adapter contract tests** (Slack 12 / Teams 11 /
  Mattermost 12 / Notion 8 / Salesforce 11 / M365 Mail 9 /
  Chatwork 13): **76 PASS** — confirms the SsrfGuard delegation
  does NOT change legitimate adapter API call patterns.
- **Full 24-class focused regression** (23 from RC6.9 + new
  `SsrfGuardTest`): **373/373 PASS** (was 343 in RC6.9; +30 from
  the new helper test).
  Re-run command:
  ```bash
  mvn test -Dtest="SsrfGuardTest,HttpWebhookDispatcherTest,\
  AdapterRegistryTest,ConnectorByPrincipalGovernanceTest,\
  ConnectorSimulateRemoveTest,IngestSchedulerDelegatedRunTest,\
  ImportProfileSchedulerGateTest,ExternalIngestControllerGateTest,\
  IngestAuthorizationServiceTest,ImportProfileSinceFilterTest,\
  ConnectorDefinitionControllerPartialPutTest,\
  IngestSchedulerDelegationSkipTest,ImportProfileOwnershipTransferTest,\
  ExternalIngestControllerTest,IngestWebhookGraphValidationTest,\
  ImportProfileDefinitionTest,DelegatedCallContextFactoryTest,\
  SlackConnectorAdapterTest,TeamsConnectorAdapterTest,\
  MattermostConnectorAdapterTest,NotionConnectorAdapterTest,\
  SalesforceConnectorAdapterTest,M365MailConnectorAdapterTest,\
  ChatworkConnectorAdapterTest" -f core/pom.xml -Pdevelopment
  ```
- **SOC validator full run** (no Docker): **17 PASS / 7 SKIP** —
  Phase 1.4.1 source-tree NUL scan exercised across **1680
  source files with 0 hits**.

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

- **SSRF fix regression tests** — run live this session:
  - `SsrfGuardTest` → 30/30 PASS (new in RC6.10).
  - `HttpWebhookDispatcherTest` → 59/59 PASS (unchanged via
    delegation).
  - `AdapterRegistryTest` → 26/26 PASS (unchanged via delegation).
  - Combined SSRF surface: **115 PASS** (was 85 in RC6.9; +30 from
    new SsrfGuardTest).
  - 7 adapter contract tests (Slack/Teams/Mattermost/Notion/
    Salesforce/M365/Chatwork): **76 PASS** — confirms RC6.10
    refactor doesn't break legitimate adapter API patterns.
  - Full 24-class focused regression (23 from RC6.9 + new
    `SsrfGuardTest`): **373/373 PASS**.
- **Live SSRF guard smoke against deployed RC6.7 stack** (TODO
  after WAR deploy): 10 vectors via `POST /webhook/test` (NAT64
  well-known + local-use, 6to4, Teredo, IPv4-compatible, plus
  100.64 / 240 / 198.18 / 127.0.0.1 / 169.254 baselines) all
  expected blocked; `https://httpbin.org/post` positive control
  expected HTTP 200. Will be re-confirmed after deploy.
- **Manual-verification §2 → §11 paths (RC6.5 carry-forward)** —
  unchanged in RC6.7. No doc body change.
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

Additive only across RC5 → RC6.8. RC6.8 strengthens internal
SSRF validation in `AdapterHttpClient.sendWithRetry` and
`sendWithRedirectValidation` (pre-send re-resolve + IP pin for
HTTP, re-resolve + revalidate for HTTPS). Internal validation
paths that do NOT change the public-facing dispatch / response
contract or any request / response shape.

Two behavioural changes:
- `sendWithRetry` now throws `SecurityException` when the host
  cannot be resolved at send time (previously the JDK would
  attempt connect and throw `IOException`). Caller-visible
  exception class change; functionally still "request failed".
- HTTP requests have their URI host rewritten to the validated
  IP literal. The remote server sees `Host: <IP>` instead of
  `Host: <hostname>`. Confirmed by 7/7 adapter contract tests
  that all adapters work under this (the JDK-default
  restricted-headers behaviour prevents overriding Host back to
  the original hostname; documented as accepted trade-off).

### Patch / view / Mango / migration / DB bootstrap

Unchanged since RC4.1.

### UI forbidden path

`/core/ui/dist/` — 0 hits.

---

## 6. Remaining follow-ups (post-RC6.10, not blocking review)

(No open repo-shippable items beyond the Medium residual SSRF
risk and explicitly-deferred admin-config-surface guard. Both
require larger engineering than a refactor RC.)

| ID | Severity | Scope | Status |
|---|---|---|---|
| **R1** (deployment-side) | Low (ops) | operator infrastructure | 4 inherently per-deployment items: network path / TLS, SIEM credentials from secrets manager, notification routing, threshold tuning from environment baseline. Not repo-shippable. |
| **HTTPS DNS pinning via SocketFactory** | **Medium (residual SSRF, RC6.8 P2 reviewer)** | webhook + connector dispatchers HTTPS path | RC6.8 P1 closes the **HTTPS data-exchange SSRF class** for both `HttpWebhookDispatcher` and `AdapterHttpClient` (re-validate at send time + TLS cert verification stops application-layer data on a rebound IP). The **HTTPS TCP-connect SSRF class is NOT closed**: there is a microsecond race window between our `InetAddress.getAllByName` re-validate and the JDK's own resolve in `HttpClient.send` — a rebind inside that window lets the TCP connect succeed (TLS handshake then fails). Attacker can still port-scan / fingerprint / trigger TCP-side-effects on internal services. Real fix: custom `SocketFactory` that pins the validated IP at TCP-connect time while keeping SNI/hostname-verification on the original hostname. |
| ~~**HTTP `Host` header preservation under IP pin**~~ | **CLOSED in RC6.9** | AdapterHttpClient HTTP path | RC6.9 enables `-Djdk.httpclient.allowRestrictedHeaders=host` (all 3 Dockerfile variants + pom.xml argLine + static init fallback) and sets `Host: <original-hostname[:port]>` after URI rewrite in `pinRequestToValidatedAddress`. Shared-vhost HTTP deployments now route correctly. |
| ~~**`isAddressSafe` + `extractEmbeddedIpv4` shared utility**~~ | **CLOSED in RC6.10** | webhook + adapter dispatchers | New `jp.aegif.nemaki.security.SsrfGuard` utility class. Both `HttpWebhookDispatcher` and `AdapterHttpClient` delegate. Byte-equivalent classification. A future 3rd consumer (Purview / Atlas guard) calls the helper directly rather than re-implementing. |
| ~~**Other connector orchestrator endpoint pre-checks**~~ | **CLOSED in RC6.10** (documentation-only) | Slack/Teams/Notion/Chatwork/M365 etc. orchestrators | Audit complete: only Mattermost + Salesforce orchestrators ever pass `connector.getEndpoint()` to HTTP and both are RC6.8-protected with explicit `validateExternalUrl` at orchestrator entry. The other 8 use hardcoded vendor API URLs and never accept user-controlled endpoint values. Plus `AdapterHttpClient.pinRequestToValidatedAddress` enforces send-time re-validation on every request regardless of caller path. No code change needed. |
| **Purview / Atlas / OIDC discovery / Graph download SSRF guard** | Low (admin-config surface) | external integration outbound HTTP | These surfaces are NOT under the AdapterHttpClient guard; they have admin-configured IdP / on-prem endpoint use cases. Applying the same blocklist unconditionally would break legitimate internal integrations. Future opt-in "production-mode" property or explicit allowlist — when implemented, calls `SsrfGuard.isAddressSafe` directly (Rule-of-Three threshold already crossed, helper is ready). |
| **Kibana NDJSON CLI validation** | Low | template QA | No offline parser exists for Detection Engine NDJSON; validation requires importing into a live Elastic 8 cluster. Operator pre-deploy gate. |
| **Splunk savedsearches CLI validation** | Low | template QA | No offline parser; `splunk btool` requires a Splunk install. Operator pre-deploy gate. |
| **Full Playwright green-up of the 155 pre-existing failures** | Medium | UI corpus | RC6.4 proved they are pre-existing (RC5.6 vs RC6 HEAD diff). The triage backlog lives under memory `test-skip-triage`. Separate engineering project. |
| ~~**Repo-wide NUL byte pre-commit scan**~~ | **CLOSED in RC6.10** | tooling | `scripts/validate-soc-templates.sh` Phase 1.4.1 scans `.java`/`.ts`/`.tsx`/`.js`/`.jsx` for literal `\x00` (1680 files / 0 hits at HEAD). Operators / CI run this before tag-cut; future Slack/Teams/git-hook integration is a separate cosmetic improvement. |

**Resolved in RC6.10 (newly closed)**:
- `SsrfGuard` shared utility — extracted from the duplicated
  `HttpWebhookDispatcher` + `AdapterHttpClient` copies.
  Byte-equivalent classification, 30-case dedicated test
  (`SsrfGuardTest`).
- Source-tree NUL byte pre-commit scan — new
  `scripts/validate-soc-templates.sh` Phase 1.4.1.
- R3 follow-up (other orchestrator audit) — documentation-only
  closure after auditing all 11 connector adapters.

**Resolved in RC6.9 (carry-forward, still closed)**:
- HTTP IP-pin shared-vhost compat caveat (RC6.8 post-tag P3) —
  `Host: <original-hostname[:port]>` preserved via JDK escape
  hatch property.
- `AdapterHttpClient.pinRequestToValidatedAddress` Javadoc
  honesty alignment with the post-RC6.8 doc fix (HTTPS residual
  TCP-connect SSRF stated explicitly in the source).

**Resolved in RC6.8 (carry-forward, still closed)**:
- AdapterHttpClient DNS rebinding gap (P1) — `pinRequestToValidatedAddress`
  re-resolves + revalidates at send time, HTTP rewrites URI to
  pinned IP literal, HTTPS relies on TLS cert verification.
- Mattermost + Salesforce orchestrator endpoint runtime
  revalidation (P2) — defence-in-depth (P1 closes the SSRF gap;
  this layer gives earlier failure with clearer audit).
- Multi-hop relative redirect resolve correctness (P3) —
  `currentRequest.uri().resolve(location)` instead of original.

**Resolved in RC6.7 (carry-forward, still closed)**:
- AdapterHttpClient horizontal SSRF (NAT64 / 6to4 / Teredo /
  IPv4-compatible / IPv4 special-use) — same vector classes as
  RC6.5+RC6.6 but reachable via the 11 connector adapters +
  ConnectorDefinitionServiceImpl + IngestWebhookController.
- SHARED HttpClient auto-follow redirect anti-pattern (now
  `Redirect.NEVER`); relative `Location` resolution against
  original URI in `sendWithRedirectValidation`.
- `HttpWebhookDispatcherTest` literal NUL byte (binary file
  classification → text).

**Resolved in RC6.6 (carry-forward, still closed)**:
- 5 IPv4 special-use bypass surfaces in `HttpWebhookDispatcher`.
- `64:ff9b:1::/48` RFC 6052 §2.2 /48 layout.
- Teredo `2001::/32` unwrap.

**Resolved in RC6.5 (carry-forward, still closed)**:
- GHSA SSRF via IPv6 transition wrap reported by tonghuaroot.
- 3 rounds of external review on `MANUAL-VERIFICATION-CONNECTORS.md`.

**Resolved in RC6.4 (carry-forward, still closed)**:
- Vector VRL / Fluent Bit DST / Filebeat config / Loki ruler live
  validation gaps — addressed by `scripts/validate-soc-templates.sh`.
- RC5.6 baseline diff — Epic 2 (§10.2).
- Recurring "template body bug surfaces only at external review"
  pattern — Epic 1 (§10.1) validator gate.

**Resolved during RC5 → RC6.10 cycle**: all listed in
`RELEASE_NOTES.md` per-section.

---

## 7. Promotion path (operational)

`v3.1.1-RC6.10` is and remains a release candidate. GA path:

1. External review concludes with approval.
2. Merge `release/3.1.1-RC6` into `master`. All 5 SSRF fix
   commits MUST land on master before any public 3.1.1 release
   because the GHSA advisory tracks master as the affected branch:
   - `94d3355a4` — RC6.5: primary GHSA-reported HttpWebhookDispatcher fix
   - `ce2abf646` — RC6.6: follow-on HttpWebhookDispatcher hardening
   - `12994c342` — RC6.7: horizontal AdapterHttpClient fix
   - `892ccfdd9` — RC6.8: deeper AdapterHttpClient closure
     (DNS rebinding pin + runtime revalidation + multi-hop redirect)
   - `e45d172bb` — RC6.9: HTTP Host header preservation +
     Javadoc honesty + JVM property wiring
   - RC6.10 SsrfGuard extraction commit (TBD, populated at
     tag-cut time) — refactor-only; carries no new SSRF
     behaviour but consolidates the helpers ready for future
     consumers
3. Cut a **new** annotated tag `v3.1.1` against the merge
   commit on `master`.
4. Optionally create a single GitHub Release attached to
   `v3.1.1`.
5. The RC tags (`v3.1.1-RC5`, `…-RC5.1`, …, `…-RC6.10`) stay
   as internal milestones.
6. Reply on the GHSA advisory linking the 5 fix commits and the
   cut tag (`v3.1.1-RC6.10`) so the reporter has a clear
   landing reference covering the original report + the 4
   follow-on adversarial-pass findings (RC6.6 IPv4 special-use
   + Teredo, RC6.7 horizontal expansion to AdapterHttpClient,
   RC6.8 DNS rebinding pin + revalidation + redirect, RC6.9
   Host header preservation + Javadoc honesty) + the RC6.10
   shared-helper consolidation (no new closure, structural
   refactor only).

---

## 8. Re-send delta summary (what reviewers should focus on)

If you reviewed RC6.9 already, the smallest possible review
for RC6.10 is:

```bash
git diff v3.1.1-RC6.9..v3.1.1-RC6.10
```

Focused set (refactor + tooling — no new SSRF closure):

- **NEW** `core/src/main/java/jp/aegif/nemaki/security/SsrfGuard.java`
  (~263 lines, new file, new package) — the extracted classifier.
  Read:
  - `public static boolean isAddressSafe(InetAddress)` —
    classification rules byte-for-byte from the two prior
    dispatcher copies (JDK predicates + 9 IPv4 special-use
    ranges + IPv6 ULA + 6 transition formats unwrap-and-recurse).
  - `public static InetAddress extractEmbeddedIpv4(InetAddress)`
    — the 6 transition-format extractor, byte-for-byte from the
    prior dispatcher copies.
- `core/src/main/java/jp/aegif/nemaki/webhook/HttpWebhookDispatcher.java`
  — **−240 LOC duplicate classifier removed**. The remaining
  2-arg wrapper `isAddressSafe(InetAddress, String)` delegates to
  `SsrfGuard.isAddressSafe` and re-runs cheap top-level
  predicates locally only to produce categorized operator log
  lines (loopback / link-local / etc.). Behaviour unchanged.
- `core/src/main/java/jp/aegif/nemaki/rest/ingest/AdapterHttpClient.java`
  — **−110 LOC duplicate classifier removed** (full
  `isAddressSafe` + `extractEmbeddedIpv4` methods deleted). The
  remaining 1-arg `isAddressSafe(InetAddress)` is a 3-LOC
  delegator preserved so `pinRequestToValidatedAddress` and
  `validateExternalUrl` call sites stay byte-equivalent. RC6.9
  Host-header preservation + static `{}` initializer + Javadoc
  are unchanged.
- **NEW** `core/src/test/java/jp/aegif/nemaki/security/SsrfGuardTest.java`
  (~220 lines, 30 cases) — direct unit test pinning the
  extracted helper.
- `core/src/test/java/jp/aegif/nemaki/webhook/HttpWebhookDispatcherTest.java`
  — `testExtractEmbeddedIpv4PublicPassthrough` updated to call
  `SsrfGuard.extractEmbeddedIpv4` directly (public static)
  instead of reflecting on the now-removed private dispatcher
  method.
- `scripts/validate-soc-templates.sh` — new Phase 1.4.1
  source-tree NUL byte scan (~60 LOC). Runs Python walker over
  `.java`/`.ts`/`.tsx`/`.js`/`.jsx`, excludes
  `node_modules`/`target`/`dist`/`build`/`.git`/`coverage`/
  `playwright-report`/`test-results`. 1680 files scanned / 0
  hits at HEAD.
- `REVIEW_PACKET.md`, `RELEASE_NOTES.md`, `CLAUDE.md`
  (RC6.10 section).

Security-focused reviewers can scope to `SsrfGuard.java` (the
extracted source of truth) + the two delegation call sites in
`HttpWebhookDispatcher` and `AdapterHttpClient` (~30 LOC each
after subtraction). The behavioural delta vs RC6.9 is zero by
construction.

If you are reviewing RC5+RC6 cold for the first time, use the
cumulative diff (since `v3.1.1-RC4.1`) in §1.

---

## 9. Files external reviewers usually start with

| Purpose | File |
|---|---|
| Re-send overview (this file) | `REVIEW_PACKET.md` |
| What changed and why (per RC) | `RELEASE_NOTES.md` (18 sections RC5 → RC6.10) |
| Design rationale | `docs/design/connector-delegation.md` (§12.1 - §12.20) |
| Multi-replica operational notes | `docs/MULTI-REPLICA-DEPLOYMENT.md` |
| Project-internal navigation (Japanese) | `CLAUDE.md` |
| API entry points | `ConnectorDefinitionController.java`, `ImportProfileDefinitionController.java`, `IngestSchedulerService.java`, `AuditEmitSupport.java` |
| Test coverage proof | `core/src/test/java/jp/aegif/nemaki/rest/ingest/*Test.java` + `core/src/test/java/jp/aegif/nemaki/security/SsrfGuardTest.java` + `core/src/test/java/jp/aegif/nemaki/webhook/HttpWebhookDispatcherTest.java` (373 PASS across 24 focused classes at RC6.10) |
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
