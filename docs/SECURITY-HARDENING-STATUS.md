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
| 1-6 | Trivy on the core image (pre-push in release-images.yml) + `.trivyignore.yaml` |
| 2-1 | Core image runs non-root + HEALTHCHECK |
| 2-2 | Image SBOM + SLSA provenance attestation; CycloneDX Java SBOM; all Actions SHA-pinned; scanner binaries checksum-verified |
| 2-3 | Deleted the dead `docker/solr/{pom.xml,src,target}` duplicate module (also removes the drifted 2.4.1 twin pom) |
| 2-5 | `BoundedIO` shared bounded-read util applied to CAD/Diagram rendition + OIDC discovery |

## Requires GitHub repo-admin settings (not code) — recommended commands

Phase 0-3 / 0-4 and the "make gates required" steps are repository settings:

- Enable secret scanning + push protection:
  `gh api -X PATCH /repos/aegif/NemakiWare -f security_and_analysis.secret_scanning.status=enabled -f security_and_analysis.secret_scanning_push_protection.status=enabled`
- Protect `master` (require PR review + status checks). Mark these checks
  required once green: `maven-dep-check`, `npm-audit (frontend)`,
  `Config credential drift`, `XML parser hardening gate`, `Security exception
  expiry`, `Trivy scan — Solr runtime image`, `gitleaks secret scan`.
- Promote CodeQL to a required PR check **after** its first baseline is triaged
  (roadmap 1-1) — add `pull_request` to `.github/workflows/codeql.yml` then mark
  the checks required.

## Deferred (with rationale)

- **2-4 Maven parent-POM / BOM consolidation** — the *concrete* drift the review
  cited (logback/jackson/commons-io differing between `solr` and `docker/solr`)
  is already resolved by deleting the dead `docker/solr/pom.xml` (2-3). The
  remaining structural change (a parent `dependencyManagement` so `common`/`solr`
  inherit one version source) is a broad multi-pom refactor; per the review it
  should land as its own small PR(s), not bundled here where it could destabilise
  the build.

- **2-7 Login brute-force protection** — evaluated: SAML has rate limiting
  (`SamlInitiateServlet`), but password / CMIS Basic-auth login has **no**
  failed-attempt throttle or lockout (`AuthenticationServiceImpl`,
  `AuthResource`, `McpAuthenticationHandler` — three separate entry points, the
  same multi-path shape as the historical `allowedAuthMethods` bypass). A
  correct fix needs a shared choke point + per-user/IP counters + a
  single-replica caveat, and touches the highest-blast-radius path, so it is
  scheduled as a dedicated, test-first change rather than a rushed addition.

- **Base-image digest pinning (2-6)** — lower priority than the CI-integrity
  pins already done (Actions SHA-pinned, scanner binaries checksum-verified);
  base tags are already version-pinned (`tomcat:11.0-jre21`, `solr:9.10.1-slim`,
  `couchdb:3.3.3`). Digest-pinning is a follow-up.
