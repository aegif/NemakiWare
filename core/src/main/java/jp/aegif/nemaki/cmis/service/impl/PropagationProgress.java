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
package jp.aegif.nemaki.cmis.service.impl;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the server is currently doing about a permission change, and roughly how much longer.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Changing the ACL of a folder makes the server rewrite the {@code readers} field of every
 * inheriting descendant, one document at a time, on a single background thread. On a large
 * subtree that runs for a long time — measured on a small stack, about 70 documents a second for
 * a shallow tree with few ACEs, and roughly a tenth of that when each node carries more ACEs and
 * relationships. That is an accepted cost. What was not acceptable is that an administrator had
 * no way to tell it was happening: there was no start log, no count, no queue depth, and no
 * endpoint. "Is the repository busy, and when will search agree with permissions again?" had no
 * answer short of reading Solr by hand.
 *
 * <h2>What the estimate is worth</h2>
 *
 * <p>The refresh executor runs one task at a time in FIFO order, so while it behaves that way the
 * remaining time is simply the outstanding node count divided by the rate observed on this very
 * traversal. Two things break that, and both are reported rather than hidden:
 *
 * <ul>
 * <li><b>CallerRunsPolicy.</b> When the queue is full the submitting thread runs the task inline,
 *     overtaking everything already queued. FIFO no longer holds, so {@code etaReliable} goes
 *     false and the caller-run count is exported separately.</li>
 * <li><b>Cold start.</b> Before enough nodes have been processed to measure a rate, no estimate
 *     is offered at all rather than an invented one.</li>
 * </ul>
 *
 * <p>Progress is per (repository, root). A second change to the same root while the first is
 * still running replaces the entry — the newer traversal supersedes it, and the fenced writer
 * recomputes each node from the authoritative store anyway, so the older one's remaining work is
 * redundant rather than lost.
 */
public final class PropagationProgress {

    /** Minimum nodes processed before a rate is trustworthy enough to extrapolate. */
    private static final int MIN_SAMPLES_FOR_ETA = 20;

    private static final Map<String, PropagationProgress> ACTIVE = new ConcurrentHashMap<>();
    private static final AtomicLong CALLER_RUNS = new AtomicLong();
    private static final AtomicLong DEFERRED_FROM_REQUEST_THREAD = new AtomicLong();

    private final String repositoryId;
    private final String rootId;
    private final int expectedNodes;
    private final long startedAtNanos;
    private final AtomicInteger done = new AtomicInteger();
    private final boolean callerRun;

    private PropagationProgress(String repositoryId, String rootId, int expectedNodes,
            long startedAtNanos, boolean callerRun) {
        this.repositoryId = repositoryId;
        this.rootId = rootId;
        this.expectedNodes = expectedNodes;
        this.startedAtNanos = startedAtNanos;
        this.callerRun = callerRun;
    }

    private static String key(String repositoryId, String rootId) {
        // A separator that cannot occur in either part. NOT a NUL: an embedded NUL makes
        // git treat the whole source file as binary, which silently kills diffs and
        // review on it.
        return repositoryId + "\u0001" + rootId;
    }

    /**
     * Registers a traversal that is starting now.
     *
     * @param expectedNodes the inheriting subtree size counted by the synchronous cache eviction,
     *     or a negative value when this path did not count it (no estimate is then offered)
     * @param callerRun true when the submitting request thread is running the task inline because
     *     the queue was full — the FIFO assumption behind the estimate does not hold
     */
    public static PropagationProgress begin(String repositoryId, String rootId, int expectedNodes,
            boolean callerRun) {
        if (callerRun) {
            CALLER_RUNS.incrementAndGet();
        }
        PropagationProgress p = new PropagationProgress(repositoryId, rootId, expectedNodes,
                System.nanoTime(), callerRun);
        ACTIVE.put(key(repositoryId, rootId), p);
        return p;
    }

    public void nodeDone() {
        done.incrementAndGet();
    }

    /** Removes this traversal, unless a newer one for the same root already replaced it. */
    public void end() {
        ACTIVE.remove(key(repositoryId, rootId), this);
    }

    public static Collection<PropagationProgress> active() {
        return ACTIVE.values();
    }

    public static long callerRunsTotal() {
        return CALLER_RUNS.get();
    }

    /**
     * How many of those inline hand-backs were turned into a queued reconciliation instead of
     * being walked on the request thread.
     *
     * <p>Read together with {@link #callerRunsTotal()}: the two rising in step is the healthy
     * shape (the refresh queue saturated, and every overflow was moved to the durable queue rather
     * than charged to a user's request). Caller-runs rising while this stays flat means the
     * reconciliation service is not wired, and requests really are doing the traversal.
     */
    public static long deferredFromRequestThreadTotal() {
        return DEFERRED_FROM_REQUEST_THREAD.get();
    }

    /** Records that an inline task handed its subtree to the durable reconciliation queue. */
    public static void recordDeferredFromRequestThread() {
        DEFERRED_FROM_REQUEST_THREAD.incrementAndGet();
    }

    public long elapsedMs() {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    /**
     * Estimated milliseconds remaining, or {@code null} when no honest estimate can be made
     * (unknown total, too few samples, or FIFO broken by an inline run).
     */
    public Long estimatedRemainingMs() {
        int completed = done.get();
        if (callerRun || expectedNodes < 0 || completed < MIN_SAMPLES_FOR_ETA) {
            return null;
        }
        int remaining = expectedNodes - completed;
        if (remaining <= 0) {
            return 0L;
        }
        long elapsed = elapsedMs();
        if (elapsed <= 0) {
            return null;
        }
        // Rate measured on THIS traversal, not a constant: per-node cost varies by an order of
        // magnitude with ACE count and depth, so a hard-coded rate would be wrong for most trees.
        return (long) ((double) remaining * elapsed / completed);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("repositoryId", repositoryId);
        m.put("rootObjectId", rootId);
        m.put("expectedNodes", expectedNodes < 0 ? null : expectedNodes);
        m.put("doneNodes", done.get());
        m.put("elapsedMs", elapsedMs());
        Long eta = estimatedRemainingMs();
        m.put("estimatedRemainingMs", eta);
        m.put("etaReliable", eta != null);
        m.put("runningInlineOnRequestThread", callerRun);
        return m;
    }
}
