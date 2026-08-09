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
| **Scheduled delegated profile state** (RC5+) | `inactiveCreatorStreak` (per-profile auto-disable streak counter) + `warnedDelegatedSchedulerProfiles` (WARN-once memo) | `rest/ingest/IngestSchedulerService.java` |
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
| **R3** | `nemakiware.deployment.singleReplica=false` AND `nemakiware.deployment.stickySession=true` are set **as JVM system properties (`-D...`) or environment variables only** | Suppresses the loud startup WARN from `SamlAuthnRequestRegistry`; absence reverts to the default-single-replica WARN/INFO behaviour. **These two keys are NOT read from `nemakiware.properties`** — `SamlAuthnRequestRegistry` is constructed during static class initialisation, before Spring DI has wired the `PropertyManager` that reads the properties file, so the registry consults `System.getProperty()` and `System.getenv()` directly. Putting them in the properties file is silently ignored |
| **R4** | All replicas read **the same** `nemakiware.properties` (file mount or shared S3/SSM-managed config). Drift between replicas causes intermittent behaviour | Compare the file at `/usr/local/tomcat/conf/nemakiware.properties` on every replica |
| **R5** | All replicas point at **the same CouchDB cluster** (single source of truth). CouchDB itself can be clustered/replicated, but every NemakiWare replica must have one logical view | `db.couchdb.url` is identical |
| **R6** | All replicas point at **the same Solr cluster** for both the `nemaki` and `token` cores | `solr.host` / `solr.port` identical, or all replicas use the same external Solr URL |

### Strongly recommended

| # | Condition | Rationale |
|---|-----------|-----------|
| **S1** | Set `nemakiware.public.scheme=https` whenever the public URL is HTTPS, regardless of replica count | Forces Secure cookie flag even if a single hop loses `X-Forwarded-Proto`; misconfig surfaces as WARN instead of silently downgrading |
| **S2** | Tomcat's `RemoteIpValve` is configured (`docker/core/server.xml`) so `X-Forwarded-Proto` and `X-Forwarded-For` are honoured only from the trusted LB IP range | Already shipped as default; verify if a custom server.xml is in use |
| **S3** | All non-browser clients of `/api/*` and `/rest/*` send `X-Requested-With: XMLHttpRequest` or a non-Basic `Authorization` header (Bearer / `AUTH_TOKEN` / `X-API-Key`) | This satisfies the CSRF policy enforced by `CsrfValidator` for state-changing methods. **It does NOT survive sticky-cookie eviction** — if the LB drops a target, switches stickiness, or the browser clears the sticky cookie, the JVM-local auth token / passkey challenge / MCP session held on the previous replica is gone, and the user must re-authenticate (or the in-flight SAML / passkey flow fails). React UI complies; review any custom REST client to add the header |
| **S4** | `auth.token.expiration` (default 24h) and the LB sticky cookie TTL are aligned (LB ≥ NemakiWare token TTL) | Otherwise sticky drops mid-session and the user is silently re-authenticated as a different identity |

### What multi-replica is NOT supported for in 3.1.1

| # | Limitation | Workaround |
|---|------------|------------|
| **L1** | Setup wizard | The `X-Setup-Token` is generated per-replica at startup and only the issuing replica honours it. Run the initial setup against a **single** replica (scale down to 1, complete setup, then scale up) |
| **L2** | EhCache invalidation across replicas | Cache-clustering knobs (`cache.clustering.terracotta.url`, `cache.clustering.offheap.mb`) exist as placeholders, but even with clustering enabled each cache is built with heap + local offheap tiers only (`CacheService` never adds a clustered resource pool), so nothing is shared or invalidated between replicas. A replica that did not perform a permission change keeps its own answer until the entry expires. **Correction (2026-08-09):** an earlier version of this row said "until per-replica TTL expires" — that was wrong while `ehcache.yml` specified `timeToIdleSeconds`, because `buildExpiry` prefers TTI and ehcache resets an idle deadline on every read, so a frequently-read entry never expired at all. `timeToIdleSeconds` has since been removed from the default block, which makes the bound real: at most `timeToLiveSeconds` (3600). Generation-based cross-replica invalidation is being added — see `docs/design/v3.3-release-blockers.md` P3-1 |
| **L3** | Per-IP rate limits | `/rest/all/saml/initiate` rate limit is per-replica. With N replicas the effective rate is `N × 30/min`. Acceptable for typical workloads; place a coarser global rate limit at the LB if abuse is a concern |
| **L4** | IMAP IDLE | `ImapIdleMonitor` opens an IMAP connection per profile and sits in IDLE. Multiple replicas would each open one connection per profile, duplicating fetches. Gated by `LeaderElection` (R2 above) — only the leader runs IDLE monitors |

---

## 3. Setup recipe

### 3.1 Infrastructure (any orchestrator)

1. **Provision shared CouchDB and Solr** that all replicas can reach. CouchDB can itself be a cluster; Solr can be SolrCloud or a single replicated node.
2. **Provision a cookie-based sticky load balancer** in front of the core replicas. AWS ALB: enable `stickiness` with `lb_cookie` duration ≥ `auth.token.expiration` (default 86 400 seconds = 24h).
3. **Mount or distribute identical `nemakiware.properties`** to every replica.

### 3.2 Per-replica configuration

Two distinct configuration channels — they are **not** interchangeable.

#### (a) Spring-loaded properties (read by `PropertyManager`)

These work in `nemakiware.properties` (or as `-D` system properties /
env vars — `PropertyManager.readValue` checks system → env → CouchDB
dynamic config → properties file in that order):

```properties
# nemakiware.properties — read via PropertyManager
lineage.leader-election.enabled=true
nemakiware.public.scheme=https
saml.require.inResponseTo=true        # Enable strict mode after migrating
                                      # all SAML clients to /saml/initiate

# RC5+ scheduled delegated profiles (default-off; enable only after
# reading the multi-replica caveats below).
# - schedulerEnabled: lets non-admin folder owners run their delegated
#   profiles on the scheduler. The scheduler tick re-evaluates
#   cmis:all + connector delegation per tick under a synthesised
#   CallContext for the original creator.
# - autoDisableInactiveOwners + inactiveOwnerFailureThreshold: after
#   N consecutive CREATOR_USER_INACTIVE ticks, auto-disable the
#   profile. The streak counter is JVM-LOCAL — see the multi-replica
#   note below.
nemakiware.ingest.delegated.schedulerEnabled=false
nemakiware.ingest.delegated.autoDisableInactiveOwners=false
nemakiware.ingest.delegated.inactiveOwnerFailureThreshold=3
```

**Multi-replica caveat for `nemakiware.ingest.delegated.*`** (RC5+):
The scheduled delegated path is **gated by the existing
`lineage.leader-election.enabled` (R2)** — only the leader replica
runs the ingest scheduler, so JVM-local state (
`inactiveCreatorStreak`, `warnedDelegatedSchedulerProfiles`) lives
on exactly one replica per cluster. Leader failover hands the
scheduler thread to the new leader; streak state is **reset on
failover** (new JVM starts from streak=0). Net effect with R2
enabled: a profile whose creator is inactive will need another
`inactiveOwnerFailureThreshold` consecutive failures on the new
leader before auto-disable fires. Operators should size the
threshold accordingly if they expect leader churn to be frequent.

Without R2, every replica's scheduler would race, each with its own
streak counter — single-replica posture is required.

#### (b) Process-bound deployment knobs (env or `-D` only)

These are consumed by `SamlAuthnRequestRegistry` during static class
initialisation, before the Spring `PropertyManager` is wired. They
**must** be set as JVM system properties or env vars on every replica;
writing them in `nemakiware.properties` has no effect:

```bash
# As env vars (uppercased, dots → underscores)
export NEMAKIWARE_DEPLOYMENT_SINGLEREPLICA=false
export NEMAKIWARE_DEPLOYMENT_STICKYSESSION=true

# Or as JVM system properties via CATALINA_OPTS
export CATALINA_OPTS="$CATALINA_OPTS \
  -Dnemakiware.deployment.singleReplica=false \
  -Dnemakiware.deployment.stickySession=true"
```

Verification: each replica must log `Deployment posture: multi-replica
+ sticky session declared.` (rather than the default
`single-replica (default).`) shortly after Tomcat startup. If you see
the default INFO line — or worse, the WARN block — the env knobs were
not picked up.

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

(Not the default `single-replica (default)` line.) If the default line
appears even after you set the two env knobs in §3.2 (b), the most
common cause is putting them in `nemakiware.properties` instead of
exporting them as env vars or passing them via `-D` flags —
`SamlAuthnRequestRegistry` does **not** read the properties file for
these keys.

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
| Stale ACL behaviour between replicas after permission change | EhCache local invalidation (L2). Bounded by `timeToLiveSeconds` (3600s) since the TTI removal; before that it was unbounded for hot entries. Roll the replicas to clear it immediately |
| Loud startup WARN about "multi-replica WITHOUT sticky session" | R3 not configured. Set both as env vars or `-D` system properties after enabling LB sticky — these two keys are NOT honoured from `nemakiware.properties` |
| Startup INFO line still says `single-replica (default)` even though you set `nemakiware.deployment.singleReplica=false` in `nemakiware.properties` | R3 mis-applied. The two `nemakiware.deployment.*` keys are env / system-property only (see §3.2 b); rewrite as `-D` flags or env vars |
| Sticky session is enabled at the LB but users still occasionally have to re-login mid-session | Either S4 (sticky cookie TTL < `auth.token.expiration`) or LB target removed. Note that S3 (`X-Requested-With`) does NOT prevent this — it only satisfies the CSRF policy |
