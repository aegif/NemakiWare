# Security hardening roadmap — status

Tracks the reviewed hardening roadmap (Phase 0/1/2). Most items landed on the
`security/hardening-roadmap` branch; the rest are either GitHub-settings changes
(repo admin) or deliberately deferred with rationale.

## Done (this branch)

| Item | What |
|------|------|
| 0-1 | Opt-in guard: refuse to leave Setup Mode while `admin` has the default password (`nemakiware.security.requireAdminPasswordChange`, on in prod compose) |
| 0-2 | Removed the unused SolrCell `/update/extract` handler + CI guard against reintroduction |
| 0-5 | Vulnerability exception ledger (`SECURITY-EXCEPTIONS.md` + `osv-scanner.toml` + `.trivyignore.yaml`) with expiry enforced in CI |
| 1-1 | CodeQL (java + javascript-typescript), scheduled + master (not yet a required PR check) |
| 1-2 | `SecureXml` single hardened-parser factory; all 5 XXE sinks migrated; CI grep gate |
| 1-3 | gitleaks secret scan (PR diff blocking, full history scheduled) + allowlist |
| 1-4 | `.github/dependabot.yml` grouped weekly version updates (maven/npm/docker/actions) |
| 1-5 | OSV `--config` + documented `ignoreUntil`; blocking exception-expiry job |
| 1-6 | Trivy on the Solr + core images (security-scan.yml + release-images.yml), **informational** — third-party bundled-lib CVEs aren't independently patchable, so base bumps come from Dependabot(docker) and our own deps stay hard-gated by maven-dep-check + OSV (see SECURITY-EXCEPTIONS.md) |
| 2-1 | Core image runs non-root + HEALTHCHECK |
| 2-2 | Image SBOM + SLSA provenance attestation; CycloneDX Java SBOM; all Actions SHA-pinned; scanner binaries checksum-verified |
| 2-3 | Deleted the dead `docker/solr/{pom.xml,src,target}` duplicate module (also removes the drifted 2.4.1 twin pom) |
| 2-5 | `BoundedIO` shared bounded-read util applied to CAD/Diagram rendition + OIDC discovery |
| 2-7 | Brute-force login throttle (`LoginThrottle`) on the CMIS Basic-auth choke point — per repo+user+IP, failures-only, default-on/tunable; TCK 38/38 + Playwright 935/1-flaky/0-fail verified |

## Requires GitHub repo-admin settings (not code) — recommended commands

Phase 0-3 / 0-4 and the "make gates required" steps are repository settings:

- Enable secret scanning + push protection — **DONE** (repo settings).
- Protect `master` — **DONE** (PR review required, force-push/deletion blocked,
  enforce_admins=false). Mark these gating checks *required* once confirmed
  green on a PR: `maven-dep-check`, `npm-audit (frontend)`,
  `Config credential drift`, `XML parser hardening gate`, `Security exception
  expiry`, `Bundled jar staleness scan`, `gitleaks secret scan`. (The Trivy
  image scan and OSV job are informational by design — do NOT mark required.)
- Promote CodeQL to a required PR check **after** its first baseline is triaged
  (roadmap 1-1) — add `pull_request` to `.github/workflows/codeql.yml` then mark
  the checks required.

## Post-merge follow-ups (done)

- **Branch-protection required checks** — set on `master`: `gitleaks secret
  scan`, `Maven dependency drift check`, `npm audit (frontend)`, `Config
  credential drift`, `XML parser hardening gate`, `Security exception expiry`,
  `Bundled jar staleness scan`. security-scan.yml now runs on every PR (paths
  filter removed on `pull_request`) so these never hang "pending". The
  informational Trivy/OSV jobs are intentionally NOT required.
- **CodeQL high-severity baseline triaged — 0 open high.** All 18 high alerts
  reviewed and dismissed with reasons: 10 tainted-arithmetic (bounded
  pagination — Math.min + loop/subList guards, overflow yields an empty page
  not an OOB), 2 uncontrolled-arithmetic (fixed-length salt/iv + internal
  config plaintext), 4 comparison-with-wider-type (small bounded counts),
  1 weak-crypto (MD5 is verify-only for pre-BCrypt accounts), 1 TOCTOU (IMAP
  IDLE loop with exception handling + backoff).
- **CodeQL full baseline triaged → 0 open, and PR-required promotion DONE.**
  The full security-extended scan surfaced 16 critical + 265 high + 1048
  log-injection medium. Handled: log-injection excluded via codeql-config
  (JSON log encoder escapes CR/LF → forgery structurally prevented, auto-closed
  1048); real fixes for relative-path-command (`/usr/bin/chattr`) and
  regex-injection (`Pattern.quote`); all other high/critical/medium dismissed
  with per-alert reasons (admin-config SSRF, metadata sensitive-log, authz-check
  bypass FPs, escaped XSS, bounded ReDoS, vendored/test). CodeQL now runs on PRs
  and `Analyze (java-kotlin)` + `Analyze (javascript-typescript)` are required
  status checks (9 required total).

## Dependabot version-update backlog (triage)

`.github/dependabot.yml` opened ~31 PRs on first run. Note: their "failing" CI
is the old **hard-gate** Trivy job (now informational + not required) — the
required checks actually pass. Triaged:

- **Merged (trivially safe):** jakarta.xml.bind-api 4.0.4→4.0.5 (#450, #458),
  axios 1.16→1.18 (#454, security-relevant).
- **Safe minor/patch, rebased (merge one-at-a-time — npm lockfile conflicts):**
  oidc-client-ts (#448), monaco-editor (#456), picomatch (#428),
  @playwright/test (#446).
- **Breaking majors — need individual A+C-style verification (build + TCK + E2E
  each):** solr 9.10.1→**10.0.0** (#432, Lucene 10 / Jetty 12 / config changes —
  the report advised against this major with no CVE driver), antd 5→**6** (#444),
  react-router-dom 6→**7** (#447), i18next 25→**26** (#451), jsdom 27→**29**
  (#455), jakarta.annotation 2→**3** (#452/#457, Jakarta EE 11 alignment), the
  GitHub-Actions majors (checkout 4→7 #436, upload-artifact 4→7 #431,
  setup-java 4→5 #438, codeql-action 3→4 #434, build-push 6→7 #433).
- **Maven security groups — verify individually (they move the pinned security
  deps + must stay above the drift-check floors):** jackson (#435), netty
  (#437), logback-slf4j (#440), aws-sdk (#441), solr-lucene (#443),
  apache-commons (#445), plus react (#439) / vite-build (#442) / testing (#449)
  / maven (#427) groups.

## Deferred (with rationale)

- **2-4 Maven parent-POM / BOM consolidation** — the *concrete* drift the review
  cited (logback/jackson/commons-io differing between `solr` and `docker/solr`)
  is already resolved by deleting the dead `docker/solr/pom.xml` (2-3). The
  remaining structural change (a parent `dependencyManagement` so `common`/`solr`
  inherit one version source) is a broad multi-pom refactor; per the review it
  should land as its own small PR(s), not bundled here where it could destabilise
  the build.

- **Base-image digest pinning (2-6)** — lower priority than the CI-integrity
  pins already done (Actions SHA-pinned, scanner binaries checksum-verified);
  base tags are already version-pinned (`tomcat:11.0-jre21`, `solr:9.10.1-slim`,
  `couchdb:3.3.3`). Digest-pinning is a follow-up.
