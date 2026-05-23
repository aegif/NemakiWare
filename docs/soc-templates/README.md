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
# Set burst threshold to 35 and lost-count outlier to 80.
# Updates the rule-engine values (threshold.value, KQL filter)
# AND the in-UI description prose in a single sed invocation,
# so an operator reading the Kibana Rules tab sees consistent
# numbers. The prior cookbook updated only the values, leaving
# descriptions claiming "default 20" / "default 50" while the
# rule fired at 35 / 80.
sed -i.bak \
    -e 's/"value":20/"value":35/' \
    -e 's/More than 20 externalGovernanceSimulate/More than 35 externalGovernanceSimulate/' \
    -e 's/details\.lostCount > 50/details.lostCount > 80/g' \
    kibana-detection-rules.ndjson
```

(GNU sed: drop the `.bak`. macOS BSD sed: keep `-i.bak` or use
`-i ''`.)

The `g` flag on the `details.lostCount` replacement is intentional
— that string appears in both the description prose AND the KQL
query body of the same NDJSON line, and both need to update.
The `20` and `50` literals are unique to their respective rule
descriptions / values, so single-pass replacement suffices.

Verify consistency after running:

```bash
grep -oE '("value":[0-9]+|"description":"[^"]*"|"query":"[^"]*")' \
    kibana-detection-rules.ndjson
```

Both the JSON value positions AND the description prose should
reflect the new numbers consistently. If they drift, operators
reading the Kibana UI's Rules tab description will see the old
defaults and misjudge when alerts will fire.

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

## Template validation status

**None of these templates has been live-tested against a
running instance of the corresponding stack on the build host
that produced this RC.** They are syntax-spec confidence
drafts: each follows the public documentation of its engine,
each survives whatever static check the build host can run
(JSON parse, YAML parse, text-grep), but none has been
imported into Elastic / Loki / Splunk and observed firing
against real input.

This matters because every RC in the RC6 series so far (RC6 →
RC6.1 → RC6.2 → RC6.3) has shipped a SOC-template body bug
that an external reviewer caught — Filebeat env syntax,
Vector VRL field path, Fluent Bit DST handling. The
syntax-spec confidence level is what we can ship; the
operator pre-deploy validation step closes the remaining gap.

### Validation matrix

| Template | Build-host check performed | Live validation NOT performed |
|---|---|---|
| `kibana-detection-rules.ndjson` | `python3 -c 'import json; …'` per line — all 5 parse as JSON, all `rule_type` values are valid Detection Engine types | Elastic 8 cluster import via Security → Manage rules → Import rules; EQL sequence join semantics; new_terms history_window evaluation |
| `loki-ruler-rules.yml` | `python3 -c 'import yaml; yaml.safe_load(…)'` | `cortextool rules check`; LogQL `label_format` → next-filter binding; off-hours regex match against label-formatted hour |
| `splunk-savedsearches.conf` | `grep` for known-bad patterns (`startswith=eval`, raw `"details.…" > N`) — none present | `splunk btool savedsearches list --debug`; transaction startswith/endswith eval-form acceptance; `rename` + `tonumber` chain |
| `filebeat-nemakiware.yml` | YAML parse + `${VAR:default}` syntax conformance (per Beats spec) | `filebeat test config`; JS script processor execution on a real ECMAScript 5.1 Goja runtime |
| `fluent-bit-nemakiware.conf` | Hand math-trace of the Lua TZ algorithm against UTC / JST / US/Eastern (summer + winter) / spring-forward boundary | `fluent-bit -c fluent-bit-nemakiware.conf --dry-run`; LuaJIT minor-version variance in `os.date` / `os.time` |
| `vector-nemakiware.toml` | VRL syntax-spec confidence per the published VRL grammar (`."@timestamp"` quoted-path, `parse_timestamp(…) ?? null` coalesce) | `vector validate vector-nemakiware.toml`; whether Vector's TOML reader interprets the multiline VRL `source = '''…'''` block as written |

### Operator pre-deploy validation commands

Run these against your installed stack before relying on the
templates in production:

| Stack | Command | Success signal |
|---|---|---|
| Filebeat | `filebeat test config -c /etc/filebeat/filebeat.yml` + `filebeat test output` | "Config OK" + reachable output |
| Fluent Bit | `fluent-bit -c fluent-bit-nemakiware.conf --dry-run` (3.0+) | Returns 0, no parse error in stderr |
| Vector | `vector validate vector-nemakiware.toml` | "Validated" with 0 errors |
| Kibana Detection Engine | Import NDJSON via Security → Manage rules → Import rules. Detection rules tab shows "running" with last-execution timestamp < interval | All 5 rules import without "Failed" status |
| Loki Ruler | `cortextool rules check --rule-files loki-ruler-rules.yml` | No errors per rule |
| Splunk | `splunk btool savedsearches list --debug` (CLI) or UI Settings → Searches → filter "NemakiWare" | All 5 savedsearches listed, no syntax warnings |

### End-to-end smoke after pre-deploy

Once the shipper + alert rules import cleanly:

1. Generate one simulate event manually as admin:
   ```bash
   curl -s -u admin:admin -X POST \
        -H 'X-Requested-With: XMLHttpRequest' \
        -H 'Content-Type: application/json' \
        -d '{"repositoryId":"bedroom","expand":false,
             "removePrincipalIds":["nemakiware-smoke-test-only"]}' \
        http://localhost:8080/core/api/v1/admin/connectors/by-principal/admin/simulate-remove
   ```
2. Within the shipper's flush interval (default 5-10 sec) the
   event should surface in your SIEM as
   `operation=externalGovernanceSimulate`.
3. If you set `BURST_THRESHOLD=1` and replay the curl twice,
   the burst alert should fire — confirming the rule engine
   is wired to the data.

### Targeted DST gate (Fluent Bit operators only)

Specific to the Fluent Bit DST fix in RC6.3: with
`TZ=America/New_York`, feed a synthetic 2025-03-09T07:00:00Z
audit line and assert `hour_of_day_local = 3` (EDT 03:00 —
2025-03-09 is the second-Sunday DST spring-forward in US).
Without this, a non-UTC operator can't be sure the per-record
offset code path activated.
