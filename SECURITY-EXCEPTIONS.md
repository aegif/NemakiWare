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

## Process

1. A scanner (OSV / Trivy / Dependabot) flags an advisory.
2. Triage: is it reachable in our configuration? Is there a fix?
3. If it must be accepted, add a row here **and** the matching machine-readable
   entry, with a rationale and an expiry no more than ~6 months out.
4. On expiry, CI fails until the row is re-reviewed (renew, or remediate and
   delete).
