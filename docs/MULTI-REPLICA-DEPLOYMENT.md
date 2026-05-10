# Multi-replica deployment requirements (NemakiWare 3.1.1)

> **Default posture**: NemakiWare 3.1.1 is shipped as **single-replica** by
> default. The startup log emits an INFO line confirming that posture so
> a silently-scaled deployment is at least visible in operator logs.
> Multi-replica deployments work, but only if the conditions below are
> met. If any are not met, sessions break, replay protections weaken,
> and scheduled jobs duplicate.

This document lives outside the AWS guide because the requirements apply
to any multi-replica orchestrator (ECS, Kubernetes, Nomad, plain
docker compose --scale). The AWS guide cross-references it from §1.

---

## 1. What's JVM-local in this release

Every item below is held in `ConcurrentHashMap` / process-bound state
inside one core JVM. A request that lands on a different replica than
the one that created the entry will not see it.

| Subsystem | What's in memory | Code |
|-----------|------------------|------|
| **Auth tokens** (`/rest/repo/{repo}/authtoken/...`) | `Map<app, Map<repo, Map<user, Token>>>` | `cmis/factory/auth/impl/TokenServiceImpl.java` |
| **WebAuthn / passkey challenges** | `ConcurrentHashMap<challengeId, ChallengeData>` (auto-purged) | `rest/WebAuthnResource.java:94` |
| **MCP session tokens & cloud login state** | Two `ConcurrentHashMap`s for sessionTokens + pendingCloudLogins | `mcp/McpAuthenticationHandler.java:65-66` |
| **SAML AuthnRequest binding** (strict mode) | `Map<bindingToken, (authnRequestId, expiry)>` | `rest/SamlAuthnRequestRegistry.java` |
| **SAML replay cache** | `Map<id, expiry>` for consumed Response/Assertion IDs | `rest/SamlReplayCache.java` |
| **Setup wizard token** | UUID generated at startup, never re-issued | `init/StartupProbeService.java:59` |
| **SAML `/initiate` rate limit** | Per-IP token bucket | `rest/SamlInitiateServlet.java:69` |
| **Webhook delivery queue / circuit breakers** | `folderQueues`, `rateLimitStates`, `circuitBreakerStates` | `webhook/ChildEventBatchProcessor.java:63-65` |
| **Ingest scheduler circuit breaker** | `consecutiveFailures` map keyed by connectorId | `rest/ingest/IngestSchedulerService.java` |
| **EhCache** (ACL / content / type definition cache) | Local in-memory cache; `cache.clustering.enabled=false` ships disabled | `core/src/main/webapp/WEB-INF/classes/ehcache.yml` + `nemakiware.properties:80` |
| **Cron schedulers** (Cloud Directory Sync / Ingest / Retention / Lineage) | Each replica has its own scheduler — `LeaderElection` gates execution | `rest/purview/journal/LeaderElection.java`, the three scheduler classes |

State that is NOT JVM-local (safe across replicas without coordination):

- All CMIS document/folder/ACL data — CouchDB
- Solr full-text and vector indices — Solr
- RAG embeddings — Solr
- Audit log entries — CouchDB + structured log files
- Ingest job records, dead-letter queue, lineage journal — CouchDB
- Connector / import-profile / integration settings — CouchDB

---

## 2. Conditions for multi-replica support

To run N≥2 core replicas safely, **all** of the following must hold:

### Required

| # | Condition | How to check |
|---|-----------|--------------|
| **R1** | **Cookie-based sticky sessions** are enabled at the load balancer for the entire `/core/*` path tree | LB config inspection. The browser must keep talking to the same replica for the lifetime of an authenticated session. Required by every row in §1 except cron schedulers and EhCache |
| **R2** | `lineage.leader-election.enabled=true` is set in `nemakiware.properties` | Each cron scheduler logs `leaderElection=enabled` at startup |
| **R3** | `nemakiware.deployment.singleReplica=false` AND `nemakiware.deployment.stickySession=true` are set as system properties or env vars on every replica | Suppresses the loud startup WARN from `SamlAuthnRequestRegistry`; absence reverts to the default-single-replica WARN/INFO behaviour |
| **R4** | All replicas read **the same** `nemakiware.properties` (file mount or shared S3/SSM-managed config). Drift between replicas causes intermittent behaviour | Compare the file at `/usr/local/tomcat/conf/nemakiware.properties` on every replica |
| **R5** | All replicas point at **the same CouchDB cluster** (single source of truth). CouchDB itself can be clustered/replicated, but every NemakiWare replica must have one logical view | `db.couchdb.url` is identical |
| **R6** | All replicas point at **the same Solr cluster** for both the `nemaki` and `token` cores | `solr.host` / `solr.port` identical, or all replicas use the same external Solr URL |

### Strongly recommended

| # | Condition | Rationale |
|---|-----------|-----------|
| **S1** | Set `nemakiware.public.scheme=https` whenever the public URL is HTTPS, regardless of replica count | Forces Secure cookie flag even if a single hop loses `X-Forwarded-Proto`; misconfig surfaces as WARN instead of silently downgrading |
| **S2** | Tomcat's `RemoteIpValve` is configured (`docker/core/server.xml`) so `X-Forwarded-Proto` and `X-Forwarded-For` are honoured only from the trusted LB IP range | Already shipped as default; verify if a custom server.xml is in use |
| **S3** | All Java clients of `/api/*` and `/rest/*` send `X-Requested-With: XMLHttpRequest` or a non-Basic `Authorization` header, so they survive the LB sticky cookie eviction | CSRF policy expects this; React UI complies |
| **S4** | `auth.token.expiration` (default 24h) and the LB sticky cookie TTL are aligned (LB ≥ NemakiWare token TTL) | Otherwise sticky drops mid-session and the user is silently re-authenticated as a different identity |

### What multi-replica is NOT supported for in 3.1.1

| # | Limitation | Workaround |
|---|------------|------------|
| **L1** | Setup wizard | The `X-Setup-Token` is generated per-replica at startup and only the issuing replica honours it. Run the initial setup against a **single** replica (scale down to 1, complete setup, then scale up) |
| **L2** | EhCache invalidation across replicas | Cache-clustering knobs (`cache.clustering.terracotta.url`, `cache.clustering.offheap.mb`) exist in properties as commented-out placeholders but Terracotta is not actively wired. Stale ACL / content cache between replicas is possible until per-replica TTL expires. Mitigation: keep `core/src/main/webapp/WEB-INF/classes/ehcache.yml` TTLs short (defaults are conservative) |
| **L3** | Per-IP rate limits | `/rest/all/saml/initiate` rate limit is per-replica. With N replicas the effective rate is `N × 30/min`. Acceptable for typical workloads; place a coarser global rate limit at the LB if abuse is a concern |
| **L4** | IMAP IDLE | `ImapIdleMonitor` opens an IMAP connection per profile and sits in IDLE. Multiple replicas would each open one connection per profile, duplicating fetches. Gated by `LeaderElection` (R2 above) — only the leader runs IDLE monitors |

---

## 3. Setup recipe

### 3.1 Infrastructure (any orchestrator)

1. **Provision shared CouchDB and Solr** that all replicas can reach. CouchDB can itself be a cluster; Solr can be SolrCloud or a single replicated node.
2. **Provision a cookie-based sticky load balancer** in front of the core replicas. AWS ALB: enable `stickiness` with `lb_cookie` duration ≥ `auth.token.expiration` (default 86 400 seconds = 24h).
3. **Mount or distribute identical `nemakiware.properties`** to every replica.

### 3.2 Per-replica configuration

Add to the JVM args / `CATALINA_OPTS` of every replica:

```properties
# In nemakiware.properties (or as -D system properties):
lineage.leader-election.enabled=true
nemakiware.public.scheme=https
saml.require.inResponseTo=true        # Enable strict mode after migrating
                                      # all SAML clients to /saml/initiate

# Suppress the multi-replica startup WARN
nemakiware.deployment.singleReplica=false
nemakiware.deployment.stickySession=true
```

Or equivalent env vars (uppercased, dots → underscores):

```bash
export LINEAGE_LEADER_ELECTION_ENABLED=true
export NEMAKIWARE_PUBLIC_SCHEME=https
export SAML_REQUIRE_INRESPONSETO=true
export NEMAKIWARE_DEPLOYMENT_SINGLEREPLICA=false
export NEMAKIWARE_DEPLOYMENT_STICKYSESSION=true
```

### 3.3 Initial bootstrap

The Setup Wizard runs only on the issuing replica (L1). Bootstrap order:

```bash
# 1. Bring up exactly ONE replica.
docker compose -f docker-compose-simple.yml up -d --force-recreate core
# (or: kubectl scale deployment/core --replicas=1)

# 2. Complete the Setup Wizard via the UI (or POST /api/v1/setup/apply).

# 3. Once bootstrap completes, scale out.
docker compose -f docker-compose-simple.yml up -d --force-recreate --scale core=3 core
# (or: kubectl scale deployment/core --replicas=3)
```

### 3.4 Verification

Each replica should log at startup:

```
Deployment posture: multi-replica + sticky session declared.
SAML strict mode will rely on the LB to keep IdP callbacks on the issuing replica.
```

(Not the default `single-replica (default)` line.)

Each scheduler should log:

```
Cloud directory sync scheduler initialized (..., leaderElection=enabled)
Ingest scheduler started (interval=...s, leaderElection=enabled)
Retention scheduler initialized (..., leaderElection=enabled)
```

The LineagePurge / LineageProjection schedulers also log a heartbeat
acquisition message via `LeaderElection`.

### 3.5 Smoke check

```bash
# All replicas serve the public-anonymous endpoint
curl -sf "${LB_URL}/core/rest/all/repositories"

# SAML strict mode is reachable
curl -sf -X POST -H "X-Requested-With: XMLHttpRequest" "${LB_URL}/core/rest/all/saml/initiate"
# Expect: 200 + Set-Cookie: NEMAKI_SAML_BIND=...; Secure; HttpOnly; SameSite=Lax
```

Then exercise login, document upload, and search through the UI to
confirm the LB sticky cookie keeps the session on a single replica.

---

## 4. What 3.1.1 does NOT promise for multi-replica

This release does NOT ship:

- A shared SAML binding registry / replay cache. If a client somehow
  loses the LB sticky cookie mid-flow (cookie cleared, browser switch,
  network blip pinning to a different LB target), strict-mode validation
  will fail — and gracefully — rather than silently accept.
- An automatic cross-replica peer discovery to detect "you scaled out
  but forgot to set `singleReplica=false`". The startup INFO line for
  the default posture is the only signal, and it's per-replica.
- Cluster-aware EhCache (Terracotta config is placeholder-only).
- Hot-restart / graceful-shutdown of in-flight SAML flows during a
  rolling deploy. Time the deployment so SAML sessions complete first,
  or expect a small fraction of users to need to re-authenticate.

A future version may add a shared (CouchDB-backed) binding store to
remove the sticky-session requirement, plus EhCache clustering. Until
then, follow the recipe above.

---

## 5. Quick reference: failure modes if requirements aren't met

| Symptom | Likely root cause |
|---------|------------------|
| Login succeeds intermittently with a different identity each time | Sticky session not enabled (auth tokens) |
| SAML login redirects from IdP land on `403 SAML binding cookie missing` | Sticky session not enabled OR strict mode on without the React UI's `/saml/initiate` flow |
| Passkey enrolment / authentication fails with "challenge not found" | Sticky session not enabled (WebAuthn challenge state) |
| MCP `tools/call` returns auth error after a successful `nemakiware_login` | Sticky session not enabled (session token) |
| Cloud Directory Sync runs N×expected times | `lineage.leader-election.enabled` not set |
| External Ingest fetches duplicated across replicas | Same — leader election off |
| Setup Wizard returns 401 or "invalid token" | You're hitting a replica other than the one that issued the setup token (L1) |
| Stale ACL behaviour between replicas after permission change | EhCache local invalidation (L2). Wait out TTL or roll the replicas |
| Loud startup WARN about "multi-replica WITHOUT sticky session" | R3 not configured. Set both env vars after enabling LB sticky |
