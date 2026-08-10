/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.util.cache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes a permission change on one replica reach the caches of the others.
 *
 * <h2>What was missing</h2>
 *
 * <p>Cache eviction in this codebase is entirely JVM-local. EhCache clustering is configured with
 * placeholder properties, but even when enabled every cache is built with heap and local off-heap
 * tiers only — no clustered tier is ever added — so nothing is shared and nothing is invalidated
 * across replicas. There is no CouchDB {@code _changes} listener either. A replica that did not
 * perform an ACL change, a password change or a group removal therefore keeps answering from its
 * own memo. Removing {@code timeToIdleSeconds} bounded that at the time-to-live, but a ceiling of
 * an hour is not propagation.
 *
 * <h2>How this works, and what it deliberately does not do</h2>
 *
 * <p>Each replica publishes its own generation counters into a shared CouchDB document and reads
 * back the maximum seen across replicas. When the maximum exceeds what this replica has already
 * acted on, it drops the affected caches. No new store, no broadcast channel, no leader: a
 * monotonic number per repository is enough, because the only question being asked is "has
 * anything changed that I have not seen".
 *
 * <p>The two counters are kept apart on purpose:
 *
 * <ul>
 * <li><b>ACL generation</b> → drop the effective-ACL and content caches.</li>
 * <li><b>Principal generation</b> → drop the user, group and joined-group caches. ACL changes do
 *     not advance it and it does not advance the ACL one; a password change and a folder
 *     permission change invalidate genuinely different things, and a single counter would either
 *     over-clear on every write or silently miss one of them.</li>
 * </ul>
 *
 * <p>The clearing is coarse — whole caches for the repository, not individual entries. That is the
 * right trade here: ACL and principal writes are rare compared with reads, and an entry-level
 * protocol would need the identities of the changed objects, which is the thing a counter
 * deliberately does not carry.
 *
 * <p>Bounded staleness after this: one poll interval, plus the time for the writing replica's own
 * publish to land. It is a real bound and it is stated, which the previous behaviour was not.
 */
public class CrossReplicaCacheInvalidator {

    private static final Logger logger = LoggerFactory.getLogger(CrossReplicaCacheInvalidator.class);

    /** Frequent enough to matter for a revocation, cheap enough to ignore (one small read). */
    static final long DEFAULT_POLL_SECONDS = 5;

    private final NemakiCachePool cachePool;
    private final GenerationStore store;

    /** Last generation this replica has already reacted to, per repository. */
    private final Map<String, AtomicLong> actedOnAcl = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> actedOnPrincipal = new ConcurrentHashMap<>();

    private volatile ScheduledExecutorService scheduler;
    private volatile boolean enabled = true;
    private long pollSeconds = DEFAULT_POLL_SECONDS;

    /**
     * The shared, durable side. Kept as an interface so the polling logic can be tested without
     * CouchDB, and so a future implementation can change where the counters live.
     */
    public interface GenerationStore {
        /** Publishes this replica's counters and returns the maximum seen across all replicas. */
        Generations publishAndRead(String repositoryId, long localAcl, long localPrincipal);

        /** Repositories to poll. */
        java.util.Collection<String> repositoryIds();
    }

    /** A pair of high-watermarks. */
    public static final class Generations {
        public final long acl;
        public final long principal;

        public Generations(long acl, long principal) {
            this.acl = acl;
            this.principal = principal;
        }
    }

    public CrossReplicaCacheInvalidator(NemakiCachePool cachePool, GenerationStore store) {
        this.cachePool = cachePool;
        this.store = store;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setPollSeconds(long pollSeconds) {
        this.pollSeconds = Math.max(1, pollSeconds);
    }

    public void start() {
        if (!enabled) {
            logger.info("Cross-replica cache invalidation is disabled; caches remain JVM-local and"
                    + " a change made on another replica is visible here only after its entry expires");
            return;
        }
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cache-generation-poll");
            t.setDaemon(true);
            return t;
        });
        s.scheduleWithFixedDelay(this::pollOnce, pollSeconds, pollSeconds, TimeUnit.SECONDS);
        this.scheduler = s;
        logger.info("Cross-replica cache invalidation started (every {}s)", pollSeconds);
    }

    public void stop() {
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            s.shutdownNow();
            scheduler = null;
        }
    }

    /** One poll cycle. Package-visible so a test can drive it deterministically. */
    void pollOnce() {
        for (String repositoryId : store.repositoryIds()) {
            try {
                pollRepository(repositoryId);
            } catch (Exception e) {
                // Never let one repository's failure stop the others, and never let a transient
                // CouchDB blip kill the scheduled task — a dead poller degrades silently back to
                // the unbounded behaviour this exists to remove.
                logger.warn("Cache generation poll failed for {}: {}", repositoryId, e.getMessage());
            }
        }
    }

    private void pollRepository(String repositoryId) {
        long localAcl = AclCacheGeneration.current(repositoryId);
        long localPrincipal = PrincipalGeneration.current(repositoryId);
        Generations seen = store.publishAndRead(repositoryId, localAcl, localPrincipal);
        if (seen == null) {
            return;
        }

        AtomicLong acl = actedOnAcl.computeIfAbsent(repositoryId, k -> new AtomicLong(localAcl));
        if (seen.acl > acl.get() && seen.acl > localAcl) {
            // Strictly greater than our OWN counter too: our own writes already evicted precisely
            // what they had to, so reacting to them here would clear the whole repository's caches
            // for no reason — and would do it on every write, exactly when the cache matters most.
            CacheService cache = cachePool.get(repositoryId);
            cache.getAclCache().removeAll();
            cache.getContentCache().removeAll();
            acl.set(seen.acl);
            logger.info("ACL change detected on another replica (generation {}) — dropped the"
                    + " effective-ACL and content caches for {}", seen.acl, repositoryId);
        }

        AtomicLong principal =
                actedOnPrincipal.computeIfAbsent(repositoryId, k -> new AtomicLong(localPrincipal));
        if (seen.principal > principal.get() && seen.principal > localPrincipal) {
            CacheService cache = cachePool.get(repositoryId);
            cache.getUserItemCache().removeAll();
            cache.getGroupItemCache().removeAll();
            cache.getJoinedGroupCache().removeAll();
            principal.set(seen.principal);
            logger.info("User/group change detected on another replica (generation {}) — dropped"
                    + " the principal caches for {}", seen.principal, repositoryId);
        }
    }
}
