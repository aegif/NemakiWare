# SOC audit integration — `EXTERNAL_GOVERNANCE_SIMULATE` and related ingest events

Operator reference for wiring NemakiWare's audit log into a SOC /
SIEM stack and writing alerts that catch suspicious patterns
around the connector governance endpoints (RC5.3 W2 + RC6 B3-2).

This document covers:

1. Where the audit log lives + how to ship it (§1)
2. Common audit event schema (§2)
3. `EXTERNAL_GOVERNANCE_SIMULATE`-specific schema and the
   related ingest events worth correlating against (§3)
4. Sample queries — jq / Splunk SPL / Elasticsearch Query DSL /
   Grafana Loki LogQL (§4)
5. Sample alert rules (§5)

Closes **R1** from the RC5.5 → RC6.1 follow-up table. R1 was
classified "repo-external ops work" because the SOC stack
choice (Splunk, Elastic, Loki, …) is operator-specific. This
document gives every operator the templates needed to wire their
own stack — NemakiWare ships the audit event, the operator wires
the dashboard / alert.

---

## 1. Where the audit log lives

Default location (Tomcat install, see
`core/src/main/webapp/WEB-INF/classes/logback.xml`):

```
${catalina.home}/logs/audit.log
```

- **Format**: one JSON document per line (LDJSON / JSONL). The
  encoder is `%msg%n` and the AuditLogger already serializes via
  Jackson — no log4j layout in front of the JSON.
- **Rolling**: daily, gzip'd nightly, 90 days kept, 10 GB total
  cap.
- **Levels**: `INFO` for success, `WARN` for failure. The audit
  logger is wired through an `AsyncAppender` with
  `discardingThreshold=0` and `neverBlock=false` — i.e.
  back-pressure rather than data loss when the queue is full.
- **Reliability**: `H1 safeEmit` (RC5.5) catches audit
  serialization exceptions and emits a WARN line to the regular
  application logger with `op + actor + object + exceptionClass`
  so failures are visible without leaking the `details` map
  contents.

### Ship to a SIEM

Two common patterns:

**Pattern A — file-tail shipper**: Filebeat / Fluent Bit /
Vector tail `audit.log` and forward to the SIEM ingest endpoint.
Each line is already valid JSON, so no parsing logic is needed
beyond `decode_json_fields`.

Filebeat example:

```yaml
filebeat.inputs:
  - type: filestream
    id: nemakiware-audit
    paths:
      - /opt/tomcat/logs/audit.log
    parsers:
      - ndjson:
          target: ""        # flatten to root
          overwrite_keys: true
fields:
  service: nemakiware
fields_under_root: true
output.elasticsearch:
  hosts: ["https://siem.example.internal:9200"]
  index: "nemakiware-audit-%{+yyyy.MM.dd}"
```

**Pattern B — direct syslog appender**: replace the
`AUDIT_FILE` appender in `logback.xml` with a `SyslogAppender`
or a `SocketAppender` to a forwarder. Adds operational moving
parts vs file tailing; only worth it if you can't access the
audit file path.

---

## 2. Common audit event schema

Every audit event ships these fields (see
`core/src/main/java/jp/aegif/nemaki/audit/AuditEvent.java`):

| Field | Type | Notes |
|---|---|---|
| `eventId` | UUID | per-event ID |
| `timestamp` | ISO-8601 | UTC |
| `timestampMs` | long | epoch millis (alternate join key) |
| `traceId` | string | per-request trace correlation key |
| `hostname` | string | host emitting the event |
| `service` | string | always `nemakiware-core` |
| `environment` | string | from `NEMAKI_ENV` (`prod` / `staging` / …) |
| `version` | string | NemakiWare build version |
| `repositoryId` | string | CMIS repo ID (`bedroom` etc.) |
| `userId` | string | acting principal |
| `clientIp` | string | source IP (respects `RemoteIpValve`) |
| `httpMethod` | string | `GET` / `POST` / … |
| `requestPath` | string | full path including query |
| `userAgent` | string | raw User-Agent header |
| `operation` | string | enum from `AuditOperation` |
| `operationDescription` | string | English description |
| `objectId` | string | per-op semantic ID (connector / profile / principal …) |
| `objectName` | string | display name when available |
| `result` | enum | `SUCCESS` / `FAILURE` |
| `errorMessage` | string | failure detail (null on success) |
| `durationMs` | long | endpoint wall-clock time |
| `details` | object | operation-specific structured fields |

The event also carries Elastic Common Schema (ECS) mirror
fields: `@timestamp`, `event.category`, `event.type`,
`event.action`, `event.outcome`, `user.id`, `source.ip`,
`service.name`. SIEMs that auto-detect ECS will index these
without explicit mapping work.

---

## 3. `EXTERNAL_GOVERNANCE_SIMULATE` and correlation partners

### 3.1 `externalGovernanceSimulate` event shape

Emitted by `ConnectorDefinitionController.simulateRemove` on
every admin POST to `/v1/admin/connectors/by-principal/{id}/simulate-remove`.

| Field | Value |
|---|---|
| `operation` | `"externalGovernanceSimulate"` |
| `result` | always `"SUCCESS"` (failed simulates short-circuit before this point and emit a 4xx instead) |
| `objectId` | `principalId` from the URL |
| `userId` | admin actor invoking the endpoint |
| `repositoryId` | from request body |
| `details.actorUserId` | same as `userId` (duplicated for `details`-only queries) |
| `details.principalId` | queried principal |
| `details.expandedPrincipals` | `string[]` — full expanded principal set used for the simulation |
| `details.removePrincipalIds` | `string[]` — principals admin asked to simulate removing |
| `details.lostCount` | int — connectors the simulation would lose |

Notes:

- RC6.1 P2-1 added per-member caps on the `/by-group/{id}`
  response, but `EXTERNAL_GOVERNANCE_SIMULATE` is the
  `/by-principal/{id}/simulate-remove` audit — single-principal,
  unaffected by the cap.
- `lostCount` is the **net** count after sole-route detection.
  If `removePrincipalIds` is large but `lostCount` is small, the
  admin's expansion has redundant grants; if `lostCount` is
  large relative to `expandedPrincipals.length`, the admin is
  about to make a sweeping change.

### 3.2 Correlation partners

The SOC value of `EXTERNAL_GOVERNANCE_SIMULATE` is twofold:

1. **Pattern detection**: bursts (scripted exploration),
   off-hours, lost-count outliers.
2. **Pre-act signal**: an admin simulating then acting within
   minutes is the "asked, then acted" pattern that strongly
   correlates with deliberate (or coerced) governance changes.

For the "asked, then acted" correlation, the events to join
against are:

| Operation | Description | Emitted by |
|---|---|---|
| `externalProfileUpdated` | profile config changed (could include `allowedConnectorIds`) | `ImportProfileDefinitionController.update` |
| `externalProfileDeleted` | profile removed | `ImportProfileDefinitionController.delete` |
| (connector updates) | connector `allowedPrincipalIds` edits — NOT currently audited as a distinct op; surfaces as `requestPath /v1/admin/connectors/{id}` `PUT` in audit | — |
| `externalIngestFailed` with `denialReason=CONNECTOR_NOT_DELEGATED` | downstream effect of the admin's action — a delegated profile that previously worked now denies | `IngestSchedulerService` + `ExternalIngestController` |

(Future work — the operator playbook below assumes connector
PUTs surface in the `requestPath` field; if your SOC needs a
distinct op name, file a follow-up to add `EXTERNAL_CONNECTOR_UPDATED`.)

---

## 4. Sample queries

All examples assume each audit line is a parsed JSON document.
Adjust field paths per your SIEM's JSON flattener convention
(e.g. `details_lostCount` vs `details.lostCount`).

### 4.1 jq (raw `audit.log` inspection on the host)

```bash
# All simulate events in the last hour
jq -c 'select(.operation == "externalGovernanceSimulate"
              and (.timestampMs > (now * 1000 - 3600000)))' \
   /opt/tomcat/logs/audit.log

# Top actors by simulate count (last 24h)
jq -r 'select(.operation == "externalGovernanceSimulate")
       | .userId' /opt/tomcat/logs/audit.log \
  | sort | uniq -c | sort -rn | head -10

# Simulates with lostCount > 50 (sweeping changes pre-flight)
jq -c 'select(.operation == "externalGovernanceSimulate"
              and .details.lostCount > 50)
       | {ts: .timestamp, actor: .userId,
          principal: .details.principalId,
          remove: .details.removePrincipalIds,
          lost: .details.lostCount}' \
   /opt/tomcat/logs/audit.log

# "Asked then acted": simulate followed by profile update within 5min,
# same actor, same repositoryId
jq -s '
  group_by(.userId)
  | map(
      . as $bucket
      | [
          $bucket[]
          | select(.operation == "externalGovernanceSimulate")
          | . as $sim
          | $bucket[]
          | select(.operation == "externalProfileUpdated"
                   and .repositoryId == $sim.repositoryId
                   and .timestampMs > $sim.timestampMs
                   and .timestampMs - $sim.timestampMs < 300000)
          | {actor: $sim.userId,
             simAt: $sim.timestamp, actAt: .timestamp,
             principal: $sim.details.principalId,
             profile: .objectId,
             gap_s: ((.timestampMs - $sim.timestampMs) / 1000)}
        ]
    )
  | flatten' /opt/tomcat/logs/audit.log
```

### 4.2 Splunk SPL

```spl
# All simulates last hour
index=nemakiware sourcetype=nemakiware_audit
    operation="externalGovernanceSimulate"
    earliest=-1h
| table _time userId details.principalId details.removePrincipalIds details.lostCount

# Burst alert: > 20 simulates from one actor in 5 min
index=nemakiware sourcetype=nemakiware_audit
    operation="externalGovernanceSimulate"
    earliest=-5m
| stats count BY userId
| where count > 20

# Lost-count outliers
index=nemakiware sourcetype=nemakiware_audit
    operation="externalGovernanceSimulate"
    "details.lostCount" > 50
| table _time userId details.principalId details.lostCount

# "Asked then acted" correlation (5-min window)
index=nemakiware sourcetype=nemakiware_audit
    (operation="externalGovernanceSimulate"
     OR operation="externalProfileUpdated"
     OR operation="externalProfileDeleted")
| transaction userId
    maxspan=5m
    startswith="operation=externalGovernanceSimulate"
    endswith="operation=externalProfileUpdated OR operation=externalProfileDeleted"
| table _time userId duration operation principal_id objectId
```

### 4.3 Elasticsearch Query DSL

```json
// Simulates last hour
{
  "query": {
    "bool": {
      "filter": [
        { "term": { "operation.keyword": "externalGovernanceSimulate" } },
        { "range": { "@timestamp": { "gte": "now-1h" } } }
      ]
    }
  },
  "sort": [{ "@timestamp": "desc" }]
}

// Burst detection (per-actor 5-min count)
{
  "size": 0,
  "query": {
    "bool": {
      "filter": [
        { "term": { "operation.keyword": "externalGovernanceSimulate" } },
        { "range": { "@timestamp": { "gte": "now-5m" } } }
      ]
    }
  },
  "aggs": {
    "by_actor": {
      "terms": { "field": "userId.keyword", "min_doc_count": 20 }
    }
  }
}

// Lost-count outliers
{
  "query": {
    "bool": {
      "filter": [
        { "term":  { "operation.keyword": "externalGovernanceSimulate" } },
        { "range": { "details.lostCount": { "gt": 50 } } }
      ]
    }
  }
}
```

### 4.4 Grafana Loki LogQL

```logql
# Simulate stream
{service="nemakiware-core"}
  | json
  | operation = "externalGovernanceSimulate"

# Burst rate
sum by (userId) (
  rate(
    {service="nemakiware-core"}
      | json
      | operation = "externalGovernanceSimulate"
    [5m]
  )
) > 4   # > 4 events/sec for 5 min ≈ > 1200 events / 5 min

# Lost-count outliers
{service="nemakiware-core"}
  | json
  | operation = "externalGovernanceSimulate"
  | details_lostCount > 50
```

---

### 4.5 Ready-to-import config templates

Pasting the queries above into your shipper / SIEM by hand is
fine for ad-hoc inspection. For production, see the file set in
`docs/soc-templates/` — each shipper has a ready-to-import
config and each SIEM has the 5 alert rules below pre-encoded:

| Shipper template | SIEM rule template |
|---|---|
| `filebeat-nemakiware.yml` | `kibana-detection-rules.ndjson` |
| `fluent-bit-nemakiware.conf` | `loki-ruler-rules.yml` / `splunk-savedsearches.conf` / `kibana-detection-rules.ndjson` |
| `vector-nemakiware.toml` | (any of the above per chosen sink) |

`docs/soc-templates/README.md` lists the placeholders each
template uses and the validation commands per stack.

## 5. Sample alert rules

Each rule below is a starting point — tune the thresholds for
your environment's baseline.

### 5.1 Burst detection (scripted exploration / brute-force)

```
condition: same userId emits > N externalGovernanceSimulate events
           in W minutes
default:   N = 20, W = 5
rationale: a human admin can't realistically click through 20
           what-if simulations in 5 minutes. Bursts above this
           imply a script — either legitimate automation (which
           should be deployed under a service account with its
           own monitoring) or an exploration attack.
severity:  Medium
playbook:
  1. Look up userId — is this a known admin?
  2. Check requestPath + userAgent — are these from the UI
     (`*-RC6.1` build) or curl / a custom client?
  3. If unrecognized, rotate the admin credentials and
     re-examine downstream connector changes.
```

### 5.2 Lost-count outlier (sweeping change pre-flight)

```
condition: any externalGovernanceSimulate with
           details.lostCount > N
default:   N = 50 (per-deployment; baseline by querying
           histogram of lostCount over 30 days)
rationale: admins planning to remove access from a small group
           of principals see single-digit lostCount. Triple-digit
           lostCount means the simulation found a removal that
           cuts access to a lot of connectors — worth a human
           review before the corresponding act fires.
severity:  Medium
playbook:
  1. Capture the event's details.principalId and
     details.removePrincipalIds.
  2. Page the admin to confirm intent before any subsequent
     externalProfileUpdated or connector PUT.
```

### 5.3 "Asked, then acted" (premeditated change)

```
condition: externalGovernanceSimulate by actor A on principal P
           in repository R, followed within T minutes by
           externalProfileUpdated OR externalProfileDeleted OR
           any PUT/DELETE on /v1/admin/connectors/{id}
           by the same actor A in the same repository R
default:   T = 30 minutes
rationale: simulate-then-act is the deliberate change pattern.
           Not inherently bad (it's exactly what governance is
           for), but elevated visibility helps with after-action
           review if the change turns out to be hostile.
severity:  Low (informational unless paired with other signals)
playbook:
  1. Capture both the simulate and the follow-up event.
  2. Cross-reference against change management ticketing —
     was the change authorised?
  3. If no ticket, escalate to the change owner.
```

### 5.4 Off-hours simulate

```
condition: externalGovernanceSimulate emitted outside business
           hours (per your environment's calendar)
default:   00:00-06:00 local, weekends
rationale: emergency change management is rare. Off-hours
           governance work paired with subsequent acts is one of
           the strongest "credential compromised" signals you'll
           get from this event.
severity:  Medium (paired with §5.3 = High)
```

### 5.5 New actor (first-time invoker)

```
condition: externalGovernanceSimulate.userId not seen in last 90d
rationale: a brand-new admin account discovering the governance
           endpoint should be expected (rotation, new hire), but
           it's worth one human-look confirmation that the actor
           is intended to have admin.
severity:  Low
```

---

### 5.6 Per-member identity beyond the 50-cap (RC6.1 P2-1 follow-up)

The `/by-group/{id}` endpoint caps `lostIfGroupRemoved` per
member at 50 (`MAX_LOST_PER_MEMBER`). The `lostCount` field
preserves the true count and `lostIfGroupRemovedTruncated`
signals truncation, so SOC sees the size signal. To recover
the specific connector identities past the 50th for a given
member, fall back to the per-principal endpoint:

```
GET /v1/admin/connectors/by-principal/{userId}?repositoryId={r}&expand=true
```

The returned `matches[]` lists every connector that user has
access to (no cap). Intersect with the group's
`directGrants[]` (from the original `/by-group` response) plus
the user's other group memberships to derive the
"sole-route-via-this-group" subset for that one member, in
full. This is the documented escape hatch for the small
fraction of operators whose groups produce > 50 sole-route
connectors per member.

## 6. Operational notes

- **Simulate is read-only**: the endpoint never mutates state. A
  burst of simulates does NOT cause governance drift. The alert
  value is in *who's asking* and *what they're asking about*,
  not in any direct change.
- **Default-on, no opt-out**: every simulate-remove call emits
  exactly one audit event. The H1 `safeEmit` helper (RC5.5)
  catches and logs (WARN) audit serialization failures, but
  the audit event itself is not skippable by callers.
- **No PII in `details`**: the `details` map carries principal
  IDs (which may include email addresses depending on your
  directory backend) but no passwords, tokens, or
  content-bearing fields. Standard care for principal IDs as
  "internal" PII applies — same handling as any other audit
  field carrying user identifiers.
- **`lostCount` is the post-RC6.1 untruncated total**: RC6.1
  P2-1 added per-member response caps for `/by-group/{id}`,
  but `EXTERNAL_GOVERNANCE_SIMULATE` is the `/by-principal/{id}`
  audit and unaffected — `lostCount` is always the true count.

---

## 7. Cross-references

- `core/src/main/java/jp/aegif/nemaki/audit/AuditEvent.java` —
  full schema source of truth
- `core/src/main/java/jp/aegif/nemaki/audit/AuditOperation.java` —
  every audited operation enum and its `code` string
- `core/src/main/java/jp/aegif/nemaki/rest/ingest/ConnectorDefinitionController.java`
  (`auditSimulate` method) — the emitter for
  `EXTERNAL_GOVERNANCE_SIMULATE`
- `core/src/main/webapp/WEB-INF/classes/logback.xml` (AUDIT
  appender) — default file path, rolling policy, async queue
  sizing
- `docs/design/connector-delegation.md` §10 `DenialReason` table
  + §11 troubleshooting — companion enum for failure-path audit
  entries
- `docs/MULTI-REPLICA-DEPLOYMENT.md` — audit file location notes
  in multi-replica setups (each replica writes its own
  `audit.log`; the SIEM shipper layer is responsible for
  merging)
