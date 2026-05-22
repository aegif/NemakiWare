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

Most templates use `${PLACEHOLDER}` for values you must fill in
before deploy. A single
`grep '\${' *.yml *.conf *.toml *.ndjson *.json 2>/dev/null`
from this directory enumerates what needs editing.

Typical placeholders:

| Placeholder | Meaning |
|---|---|
| `${NEMAKIWARE_AUDIT_LOG_PATH}` | path to `audit.log` on the host (default: `/opt/tomcat/logs/audit.log`) |
| `${SIEM_ENDPOINT}` | URL of your SIEM ingest endpoint |
| `${SIEM_USERNAME}` / `${SIEM_PASSWORD}` / `${SIEM_API_KEY}` | SIEM auth |
| `${BUSINESS_HOURS_TZ}` | IANA timezone for the off-hours rule (e.g. `Asia/Tokyo`; default `UTC`) |
| `${BUSINESS_HOURS_START_LOCAL}` / `${BUSINESS_HOURS_END_LOCAL}` | off-hours alert window (Splunk-only; default `06` / `22`) |
| `${OFF_HOURS_REGEX}` | Loki-only alternate form of the off-hours window (default `^(0[0-5]|2[2-3])$`) |
| `${NEW_ACTOR_LOOKBACK}` | new-actor lookback window (default `90d`) |
| `${NOTIFICATION_TARGET}` | PagerDuty service ID / Slack webhook URL / etc. — wired in your SIEM's notification connector, not in these files |

### Kibana NDJSON — threshold values are baked-in defaults, override via sed

`kibana-detection-rules.ndjson` cannot use `${VAR}` markers in
numeric value positions (would break JSON parsing). The two
thresholds — burst count and lost-count outlier — ship with
working defaults of **20** and **50** respectively. To customise
before import:

```bash
# Set burst threshold to 35 and lost-count outlier to 80
sed -i.bak \
    -e 's/"value":20/"value":35/' \
    -e 's/details\.lostCount > 50/details.lostCount > 80/' \
    kibana-detection-rules.ndjson
```

(GNU sed: drop the `.bak`. macOS BSD sed: keep `-i.bak` or use
`-i ''`.) Adjust by editing the description text too so the
in-UI rule reflects your actual numbers — the rule-engine reads
the JSON values, but operators reading the description in
Kibana's Rules tab will get the baked-in `20` / `50` if you
don't update both.

### Off-hours rule timezone / enrichment

The Kibana off-hours rule expects two fields added by the
shipper at ingest:

| Field | Type | Producer |
|---|---|---|
| `hour_of_day_local` | int 0-23 | Vector `format_timestamp!`, Fluent Bit Lua filter, Filebeat JS script |
| `day_of_week_local` | string `Monday..Sunday` | Same three shippers |

All three shipper templates in this directory include the
enrichment as of RC6.2 — verify with
`grep -l hour_of_day_local filebeat-nemakiware.yml fluent-bit-nemakiware.conf vector-nemakiware.toml`.

Operator-local TZ is controlled by:

- **Vector**: `timezone:` param in `format_timestamp!` (default
  `${BUSINESS_HOURS_TZ:-UTC}`).
- **Fluent Bit**: the Lua filter uses `os.date`, which honours
  the Fluent Bit process's `TZ` env var. Set `TZ=Asia/Tokyo`
  (or your IANA zone) in the systemd unit / docker-compose
  environment.
- **Filebeat**: the JS script uses the JS `Date` object, which
  honours the Filebeat process's `TZ` env var. Same systemd /
  docker-compose env approach.

For Loki, the rule defaults to a regex covering 22:00-05:59
UTC. JST (UTC+9) operators wanting "off-hours 22:00-05:59 JST"
must rewrite the regex to UTC-equivalent (`^(1[3-9]|20)$` covers
13:00-20:59 UTC = 22:00-05:59 JST).

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
