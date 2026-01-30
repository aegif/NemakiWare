# Ehcache 3.x + Terracotta Migration Plan (Draft)

## Goals
- Replace Ehcache 2.x with Ehcache 3.x while keeping the current cache semantics and YAML-driven sizing/TTL.
- Defer clustering to a later Terracotta rollout to reduce immediate risk.
- Preserve application behavior for Content/ACL/Type caches and related TCK coverage.

## Phase 1: Ehcache 3.x Local Upgrade
### Dependencies
- Swap `net.sf.ehcache:ehcache` with `org.ehcache:ehcache` 3.x.
- Verify transitive SLF4J compatibility (Ehcache 3 uses SLF4J).

### Configuration
- Keep `ehcache.yml` as the source-of-truth and translate settings into Ehcache 3 programmatic config.
- Map `maxElementsInMemory` to `ResourcePoolsBuilder.heap()`.
- Map `timeToLiveSeconds` and `timeToIdleSeconds` to `ExpiryPolicy`.
  - When both are present, prefer time-to-idle to preserve the more conservative eviction window.

### Code Changes
- Replace Ehcache 2 `CacheManager` creation with `CacheManagerBuilder`.
- Replace `Cache`/`Element` operations with Ehcache 3 `Cache<K,V>` operations.
- Remove Ehcache 2 shutdown property usage; keep a lightweight lifecycle listener for logging only.

## Phase 2: Terracotta Cluster Enablement (Future)
### Cluster Topology
- Determine active/standby Terracotta servers and shared storage.
- Align cache names and repositories to avoid collisions (use `repositoryId_cacheName`).

### Configuration
- Introduce clustered cache manager configuration (server URI, cluster tier sizes).
- Decide which caches should be clustered vs. remain local.
  - Recommended: Type definitions, ACL, Content metadata.
  - Keep large binary content out of clustered cache.

### Consistency & Safety Checks
- Verify cache invalidation paths for type updates and ACL changes.
- Ensure cache entries are treated as immutable or replaced on updates.
- Review any direct object mutation of cached values; consider defensive copies if needed.

## Risk Assessment
- **TTL/TTI semantics**: ensure behavior matches previous Ehcache 2 implementation.
- **Cache sizing**: heap sizing in Ehcache 3 is entry-count-based; validate memory pressure.
- **Serialization**: clustered caches require serializable values—plan for DTOs where needed.

## Test Plan
### Local Upgrade (Phase 1)
- Smoke: CMIS repository info, type list, document read/update.
- TCK focus:
  - TypesTestGroup
  - VersioningTestGroup
  - CrudTestGroup1/CrudTestGroup2
- Verify cache invalidation on type update/delete and ACL changes.

### Cluster Enablement (Phase 2)
- Two-node cache coherence tests for type updates and ACL changes.
- Concurrent update tests for optimistic locking and cache refresh.

## Rollback Plan
- If Ehcache 3 causes regressions, revert dependency and `CacheService` changes; keep YAML as-is.
