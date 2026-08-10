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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
 * <h2>Why the comparison is per remote replica, not against a shared maximum</h2>
 *
 * <p>The first version of this class compared the maximum generation seen across replicas against
 * this replica's OWN counter, so that a replica would not clear its caches in response to its own
 * writes (which have already evicted precisely what they had to). A reviewer showed that breaks:
 * the counters are independent per-process write counts, not a shared clock, so "mine is 101 and
 * the maximum is 101" says nothing about whose 101 it is. A replica that writes frequently keeps
 * its own counter ahead of everyone else's and therefore stops reacting to other replicas
 * ENTIRELY — not an edge case, a systematic failure that grows with how busy the replica is.
 *
 * <p>So each remote replica is tracked separately: its published value is remembered, and any
 * CHANGE to it means that replica did something this one has not accounted for. Own writes never
 * enter the comparison because the store excludes this node from what it returns, which keeps both
 * properties at once — no clearing on our own writes, and no way to miss another replica's.
 *
 * <p>Tracking a change rather than an increase also survives a remote restart, which resets that
 * replica's counter to zero: a decrease is still a change, so it clears (once) instead of being
 * mistaken for "nothing new". The cost of that over-clear is one repopulation; the cost of the
 * alternative is serving revoked permissions.
 *
 * <h2>What gets dropped</h2>
 *
 * <p>The two counters are kept apart on purpose:
 *
 * <ul>
 * <li><b>ACL generation</b> → the effective-ACL, content and object-data caches — the same three
 *     that a local ACL change evicts through {@code removeCmisAndContentCache}. Dropping only two
 *     of them would leave authorization decisions memoised in the third.</li>
 * <li><b>Principal generation</b> → the user, group and joined-group caches. ACL changes do not
 *     advance it and it does not advance the ACL one; a password change and a folder permission
 *     change invalidate genuinely different things.</li>
 * </ul>
 *
 * <p>Bounded staleness after this: one poll interval, plus the time for the writing replica's own
 * publish to land.
 */
public class CrossReplicaCacheInvalidator {

    private static final Logger logger = LoggerFactory.getLogger(CrossReplicaCacheInvalidator.class);

    /** Frequent enough to matter for a revocation, cheap enough to ignore (one small read). */
    static final long DEFAULT_POLL_SECONDS = 5;

    private final NemakiCachePool cachePool;
    private final GenerationStore store;

    /** repository → (remote node id → the value we last accounted for). */
    private final Map<String, Map<String, Long>> seenAcl = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Long>> seenPrincipal = new ConcurrentHashMap<>();

    private volatile ScheduledExecutorService scheduler;
    private volatile boolean enabled = true;
    private long pollSeconds = DEFAULT_POLL_SECONDS;

    /**
     * The shared, durable side. Kept as an interface so the polling logic can be tested without
     * CouchDB, and so a future implementation can change where the counters live.
     */
    public interface GenerationStore {
        /**
         * Publishes this replica's counters and returns what the OTHER replicas have published.
         *
         * <p>The returned maps must NOT include this replica's own entry: excluding it at the
         * source is what makes "do not react to our own writes" hold without any comparison
         * against local state.
         */
        ReplicaGenerations publishAndRead(String repositoryId, long localAcl, long localPrincipal);

        /** Repositories to poll. */
        java.util.Collection<String> repositoryIds();
    }

    /** What the other replicas have published, per node. */
    public static final class ReplicaGenerations {
        public final Map<String, Long> aclByNode;
        public final Map<String, Long> principalByNode;

        public ReplicaGenerations(Map<String, Long> aclByNode, Map<String, Long> principalByNode) {
            this.aclByNode = aclByNode == null ? Map.of() : Collections.unmodifiableMap(aclByNode);
            this.principalByNode = principalByNode == null ? Map.of()
                    : Collections.unmodifiableMap(principalByNode);
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
        // The OUTER try matters as much as the inner one. scheduleWithFixedDelay cancels the task
        // for good if a run throws, so an exception from store.repositoryIds() itself — outside
        // any per-repository handler — would silently end cross-replica invalidation for the
        // lifetime of the process, with no error after the first and nothing to notice it by.
        try {
            for (String repositoryId : store.repositoryIds()) {
                try {
                    pollRepository(repositoryId);
                } catch (Exception e) {
                    // One repository's failure must not stop the others.
                    logger.warn("Cache generation poll failed for {}: {}", repositoryId, e.getMessage());
                }
            }
            consecutiveFailures = 0;
        } catch (Exception | StackOverflowError e) {
            long n = ++consecutiveFailures;
            logger.warn("Cache generation poll cycle failed ({} in a row): {}", n, e.getMessage());
        }
    }

    /** Consecutive whole-cycle failures; surfaced so a wedged poller is visible. */
    private volatile long consecutiveFailures = 0;

    public long getConsecutiveFailures() {
        return consecutiveFailures;
    }

    private void pollRepository(String repositoryId) {
        ReplicaGenerations remote = store.publishAndRead(repositoryId,
                AclCacheGeneration.current(repositoryId), PrincipalGeneration.current(repositoryId));
        if (remote == null) {
            return;
        }

        // CLEAR FIRST, RECORD AFTER — in that order, and only on success.
        //
        // Recording before clearing looks harmless because the clear "cannot fail", but if it
        // does (or fails part-way) the next poll sees no movement and the eviction is never
        // retried: the replica keeps serving the stale authorization decisions for ever, and the
        // only trace is one warning. Acknowledging work that did not happen is the one mistake a
        // convergence mechanism must not make.
        if (movedFrom(seenAcl, repositoryId, remote.aclByNode)) {
            CacheService cache = cachePool.get(repositoryId);
            cache.getAclCache().removeAll();
            cache.getContentCache().removeAll();
            // Same three caches a local ACL change evicts (removeCmisAndContentCache). Leaving
            // objectDataCache behind would keep compiled CMIS objects — including their ACLs —
            // memoised after the other two were dropped.
            cache.getObjectDataCache().removeAll();
            record(seenAcl, repositoryId, remote.aclByNode);
            logger.info("ACL change detected on another replica — dropped the effective-ACL,"
                    + " content and object-data caches for {}", repositoryId);
        }

        if (movedFrom(seenPrincipal, repositoryId, remote.principalByNode)) {
            CacheService cache = cachePool.get(repositoryId);
            cache.getUserItemCache().removeAll();
            cache.getGroupItemCache().removeAll();
            cache.getJoinedGroupCache().removeAll();
            // A UserItem / GroupItem is also a Content: the local update path refreshes it in
            // contentCache and removes it from objectDataCache, and deletion removes it from both.
            // Without these two a remote principal change leaves the OLD principal object — or a
            // deleted one — reachable by object id here. Principal writes are rare, so paying a
            // repository-wide content clear for them is cheap.
            cache.getContentCache().removeAll();
            cache.getObjectDataCache().removeAll();
            record(seenPrincipal, repositoryId, remote.principalByNode);
            logger.info("User/group change detected on another replica — dropped the principal,"
                    + " content and object-data caches for {}", repositoryId);
        }
    }

    /**
     * Records the values just read and reports whether any of them moved.
     *
     * <p>A node seen for the first time counts as movement: this replica cannot know what that
     * node did before it started looking, so the safe reading of "unknown" is "something changed".
     * That costs one clear per newly-observed replica.
     */
    private static boolean movedFrom(Map<String, Map<String, Long>> state, String repositoryId,
            Map<String, Long> current) {
        Map<String, Long> known = state.computeIfAbsent(repositoryId,
                k -> new ConcurrentHashMap<>());
        for (Map.Entry<String, Long> e : current.entrySet()) {
            Long previous = known.get(e.getKey());
            if (previous == null || !previous.equals(e.getValue())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Marks the values as accounted for. Called ONLY after the corresponding caches were actually
     * cleared, so a failed clear leaves the movement outstanding and the next poll retries it.
     *
     * <p>Nodes absent from {@code current} are forgotten rather than kept: a decommissioned
     * replica's last value has already been acted on, and forgetting it means that if the same id
     * ever reappears it is treated as new — which errs towards an extra clear, not a missed one.
     */
    private static void record(Map<String, Map<String, Long>> state, String repositoryId,
            Map<String, Long> current) {
        Map<String, Long> known = state.computeIfAbsent(repositoryId,
                k -> new ConcurrentHashMap<>());
        known.putAll(current);
        known.keySet().retainAll(new LinkedHashMap<>(current).keySet());
    }
}
