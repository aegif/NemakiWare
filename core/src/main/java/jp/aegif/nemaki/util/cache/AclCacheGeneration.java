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
import java.util.concurrent.atomic.AtomicLong;

/**
 * A per-repository counter that advances whenever an ACL changes, used to date memoised
 * authorization decisions.
 *
 * <h2>The race this closes</h2>
 *
 * <p>{@code applyAcl} evicts the effective-ACL memo for the changed object and every inheriting
 * descendant. It does so while holding only a READ lock, and the eviction walk takes no locks on
 * the descendants at all. So a reader that started computing a descendant's effective ACL before
 * the change can finish afterwards and put its stale answer back — after the eviction has already
 * swept past. Nothing removes it again: the entry is now the newest thing in the cache, and with
 * expiry bounded by time-to-live rather than idleness it survives for the full TTL.
 *
 * <p>Widening the locks does not fix it. Locks here are striped per object, so the descendant's
 * reader holds a different key from the writer, and the ancestor walk it performs takes no locks
 * at all. The set that would have to be excluded is the whole subtree, for the duration of the
 * walk.
 *
 * <p>Dating the answer does fix it, and cheaply: a reader records the generation it began under,
 * and the cache refuses to store an answer computed under a generation older than the current
 * one. A stale computation is then discarded instead of being published, and the next read simply
 * recomputes.
 *
 * <h2>Scope — deliberately not shared with the other generation mechanisms</h2>
 *
 * <p>This is a JVM-local counter used for a compare-and-put inside one process. It is seeded from,
 * and advanced alongside, the durable ACL epoch, but it is NOT the cross-replica invalidation
 * mechanism and NOT the principal-change mechanism:
 *
 * <ul>
 * <li><b>Cross-replica invalidation</b> reads the durable epoch periodically and clears caches.
 *     Different trigger, different cadence, different failure mode.</li>
 * <li><b>Principal changes</b> (password, admin flag, group membership) do not advance the ACL
 *     epoch at all — the only caller of the epoch allocator is ACL finalization. Conflating them
 *     would make this counter silently claim to cover revocations it never sees.</li>
 * </ul>
 *
 * <p>Sharing only the VALUE and keeping the mechanisms separate is what keeps each one's guarantee
 * legible.
 */
public final class AclCacheGeneration {

    private static final Map<String, AtomicLong> GENERATIONS = new ConcurrentHashMap<>();

    private AclCacheGeneration() {
    }

    private static AtomicLong counter(String repositoryId) {
        return GENERATIONS.computeIfAbsent(repositoryId == null ? "" : repositoryId,
                k -> new AtomicLong());
    }

    /**
     * The generation a reader should record before it starts computing an effective ACL.
     */
    public static long current(String repositoryId) {
        return counter(repositoryId).get();
    }

    /**
     * Advances the generation for this repository. Called when an ACL write has been committed
     * and its cache eviction is about to run — anything computed before this point is suspect.
     *
     * @return the new generation
     */
    public static long advance(String repositoryId) {
        return counter(repositoryId).incrementAndGet();
    }

    /**
     * Whether an answer computed under {@code observedGeneration} may still be published.
     *
     * <p>Fails closed on a negative generation (the caller did not record one), because a caller
     * that forgot to date its computation is exactly the caller whose answer cannot be trusted.
     */
    public static boolean isStillCurrent(String repositoryId, long observedGeneration) {
        return observedGeneration >= 0 && observedGeneration >= current(repositoryId);
    }

    /** Test hook: forget all counters. */
    public static void resetForTests() {
        GENERATIONS.clear();
    }
}
