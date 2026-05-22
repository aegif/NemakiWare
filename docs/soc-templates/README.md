# SOC ship-and-alert templates for NemakiWare audit log

Ready-to-import config snippets for the most common SIEM stacks.
Complements `docs/SOC-AUDIT-INTEGRATION.md` (which explains the
audit schema and gives ad-hoc query examples) — these files are
the **actual config you drop into your shipper / SIEM** with
only the `${PLACEHOLDER}` values filled in for your environment.

## File catalogue

### Log shippers (tail `audit.log` → SIEM)

| File | Use with | Sink it ships to |
|---|---|---|
| `filebeat-nemakiware.yml` | Elastic Beats / Filebeat 8+ | Elasticsearch (any 8.x cluster) |
| `fluent-bit-nemakiware.conf` | Fluent Bit 3+ | Loki / Splunk HEC / Elasticsearch |
| `vector-nemakiware.toml` | Vector 0.40+ | any (Loki / Splunk / ES / S3 / …) |

Pick **one** shipper; they're alternatives, not stacked.

### Alert rule definitions (drop into your SIEM)

| File | Use with | Rule format |
|---|---|---|
| `kibana-detection-rules.ndjson` | Elastic Stack 8+ | Kibana Detection Engine (NDJSON import) |
| `loki-ruler-rules.yml` | Loki / Grafana Loki Ruler | Prometheus-style ruleGroups |
| `splunk-savedsearches.conf` | Splunk Enterprise 9+ | `savedsearches.conf` |

The five alert rules in each file match the playbook in
`docs/SOC-AUDIT-INTEGRATION.md` §5:

1. **simulate burst** — > 20 simulates by one actor in 5 min
2. **lost-count outlier** — single simulate with `lostCount > 50`
3. **asked-then-acted** — simulate followed by profile update /
   connector PUT by same actor within 30 min
4. **off-hours simulate** — outside business hours
5. **new-actor first-time** — `userId` not seen in last 90 days

## Placeholder convention

Every template uses `${PLACEHOLDER}` for values you must fill in.
A single `grep '\${' *.yml *.conf *.toml *.json` from this
directory enumerates what needs editing before deploy. The
typical placeholders are:

| Placeholder | Meaning |
|---|---|
| `${NEMAKIWARE_AUDIT_LOG_PATH}` | path to `audit.log` on the host (default: `/opt/tomcat/logs/audit.log`) |
| `${SIEM_ENDPOINT}` | URL of your SIEM ingest endpoint |
| `${SIEM_USERNAME}` / `${SIEM_PASSWORD}` / `${SIEM_API_KEY}` | SIEM auth |
| `${BUSINESS_HOURS_START_LOCAL}` / `${BUSINESS_HOURS_END_LOCAL}` | off-hours alert window (e.g. `06:00` / `22:00`) |
| `${BURST_THRESHOLD}` | simulate burst alert N (default 20) |
| `${LOST_COUNT_OUTLIER_THRESHOLD}` | lost-count alert threshold (default 50; baseline first) |
| `${NOTIFICATION_TARGET}` | PagerDuty service ID / Slack webhook URL / etc. — wired in your SIEM's notification connector, not in these files |

## What's NOT in these templates (and why)

These four items are inherently per-deployment and can't be
shipped as a generic template:

1. **Network path** from NemakiWare host to SIEM — firewall
   rules, VPN, TLS certificates, mTLS client auth.
2. **SIEM credentials / API keys** — must come from your secrets
   manager (Vault / KMS / sealed-secret), never committed.
3. **Notification routing** (PagerDuty integration key, Slack
   webhook URL, email distribution list) — wired in the SIEM's
   notification connector layer, not in the alert rule itself.
4. **Threshold tuning** — `${BURST_THRESHOLD}` defaults are
   first-pass estimates. Take a 7-day histogram of simulate
   events per actor from your environment before locking
   numbers in.

The templates flag every such input with `${PLACEHOLDER}` so a
deploy-time linter can refuse the file if anything is unfilled.

## Verifying a template imported cleanly

After dropping a template into your SIEM:

- **Filebeat**: `filebeat test config -c /etc/filebeat/filebeat.yml`
  then `filebeat test output` for connectivity.
- **Fluent Bit**: `fluent-bit -c fluent-bit-nemakiware.conf
  --dry-run` (3.0+) or just start with `--verbose`.
- **Vector**: `vector validate vector-nemakiware.toml`.
- **Kibana Detection Engine**: import the NDJSON via Security
  → Manage rules → Import value lists / rules → Import rules.
  Each line of `kibana-detection-rules.ndjson` is one rule;
  the import surface accepts NDJSON directly. After import,
  Detection rules tab shows "running" with last-execution
  timestamp < interval as the success signal.
- **Loki Ruler**: `cortextool rules check --rule-files
  loki-ruler-rules.yml`.
- **Splunk**: `| rest /servicesNS/-/-/saved/searches | search
  title="NemakiWare *"` should list all five rules.

After a clean import, generate one simulate event manually
(call the `/by-principal/{id}/simulate-remove` endpoint as
admin) and confirm it surfaces in your SIEM within the
shipper's flush interval.
