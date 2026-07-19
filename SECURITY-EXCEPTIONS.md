# Security vulnerability exception ledger

Authoritative, human-readable record of vulnerability advisories that scanners
flag but that we have **consciously accepted** (not applicable, no fix
available, or mitigated by configuration). Every entry has an owner and an
**expiry date** — CI is configured so that an expired exception fails the build,
forcing periodic re-review.

Machine-readable counterparts:
- OSV / dependency advisories → [`osv-scanner.toml`](osv-scanner.toml) (`ignoreUntil`)
- Trivy container-image findings → [`.trivyignore.yaml`](.trivyignore.yaml) (`expiredAt`)

Keep the three files in sync. When adding an exception, record *why it does not
apply or cannot be fixed*, not just that it is ignored.

| ID | Component | Applicability / rationale | Mitigation in place | Owner | Expiry |
|----|-----------|---------------------------|---------------------|-------|--------|
| GHSA-qhr7-h655-pw6r | `org.apache.solr:solr-core` (9.10.1) | **Not applicable.** Advisory concerns hardcoded credentials created by Solr's `bin/solr auth enable` Basic-Auth *setup tool*. NemakiWare ships no `security.json`, does not enable `BasicAuthPlugin`, and never runs `bin/solr auth enable` (verified). **No patched version exists** (vulnerable range `>= 9.4.0, <= 9.10.1`). | Solr is bound to `127.0.0.1` in dev (`docker-compose-simple.yml`) and kept off the host network in prod (`docker-compose-prod.yml`); no Basic-Auth surface is configured. | @Ishii-Akinori | 2027-01-31 |
| CVE-2025-66516 | Apache Solr / PDFBox (SolrCell `/update/extract`) | **Mitigated at the source (record kept for traceability).** The live indexing path extracts text application-side with Tika **3.2.3** (which fixes this CVE) and posts plain text to the normal `/update` handler; the Solr runtime image is **9.10.1** (Solr-side fix); the leftover `/update/extract` handler is being removed from `token/solrconfig.xml`. | Triple-covered: patched Tika app-side + patched Solr + handler removal + `127.0.0.1` binding. | @Ishii-Akinori | 2027-01-31 |

## Container-image findings (Trivy) — informational by design

The Solr and core images are third-party distributions (`solr:*-slim`,
`tomcat:*`) plus our config/WAR. Their HIGH/CRITICAL findings are overwhelmingly
in **bundled libraries** (Jetty, ZooKeeper, Netty, Jackson, LibreOffice, the
JDK, …) that we **cannot patch independently of a new upstream image** — e.g.
`solr:9.10.1-slim` currently bundles Jetty 10.0.26 / ZooKeeper 3.9.4 / Netty
4.2.6 / Jackson 2.18.0 with fixes only in versions that ship with a *later Solr
image*. Gating a build/release on these would be non-actionable churn.

Therefore the image Trivy scans (security-scan.yml `trivy-image`,
release-images.yml) are **informational** (report + SARIF artifact, no hard
gate). The two actionable channels remain gates:
- **Base-image lag** → Dependabot (docker ecosystem) opens a PR to bump the
  `FROM` tag when a newer image exists (this is how the Solr 9.10.0→9.10.1
  CVE-2025-66516 lag is caught).
- **Our own Java dependencies** → `maven-dep-check` + OSV hard-fail.

Solr is additionally never exposed to untrusted clients (bound to `127.0.0.1`
in dev, off-host internal network in prod), so its bundled-server CVEs
(request smuggling, etc.) are not reachable from outside the app.

## CodeQL query exclusions

- **java/log-injection (CWE-117)** — excluded via
  `.github/codeql/codeql-config.yml`. The application log appenders use
  `LogstashEncoder` (JSON) for STDOUT + the rolling file; audit / lineage
  appenders log content AuditLogger has already JSON-serialised. JSON string
  encoding escapes CR/LF, so newline-based log forgery is structurally
  prevented — a newline in a username / object id stays inside the `"message"`
  field. CodeQL doesn't model the encoder as a sanitizer and flagged 1000+ call
  sites; per-site sanitization is disproportionate and redundant. Re-enable if
  the log layout ever changes to a plain-text (non-JSON) encoder.

All other CodeQL findings were individually triaged. Fixed in code:
`FilesystemStorageAdapter` invokes `/usr/bin/chattr` by absolute path
(relative-path-command); `ImportExportUtils.isVersionFileFor` now
`Pattern.quote`s the archive-supplied base name (regex-injection). Dismissed
with per-alert reasons: **critical java/ssrf (15)** — all outbound to
admin-configured endpoints (CouchDB URL / Purview / OIDC discovery) set during
authenticated setup, not untrusted input (opt-in SsrfGuard covers
internet-facing); **java/sensitive-log (144)** — verified across every site to
log CMIS change tokens / ids / key-prefixes (metadata), with the only two
credential-touching sites being intentional guarded one-time displays (MCP
auto-generated password with opt-out, setup-token file-write-failure fallback);
**java/user-controlled-bypass** — the authorization checks themselves;
**java/xss** — output escaped via escapeForJavaScript; **polynomial-redos /
client-side sanitization** — authenticated/size-bounded or server-authoritative;
plus vendored JS (Solr admin webapp) and test-only findings.

## Process

1. A scanner (OSV / Trivy / Dependabot) flags an advisory.
2. Triage: is it reachable in our configuration? Is there a fix?
3. If it must be accepted, add a row here **and** the matching machine-readable
   entry, with a rationale and an expiry no more than ~6 months out.
4. On expiry, CI fails until the row is re-reviewed (renew, or remediate and
   delete).
