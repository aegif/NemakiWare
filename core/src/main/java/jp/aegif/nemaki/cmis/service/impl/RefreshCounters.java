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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-traversal tally for a search-index ACL refresh, split by whether the node can recover on
 * its own.
 *
 * <h2>Why the split matters</h2>
 *
 * <p>The reconciliation scheduler gives each task ten attempts and then marks it terminally
 * FAILED. That budget exists for nodes that are genuinely broken. A node blocked by the PENDING
 * GATE is not broken: an ancestor is mid-mutation, and the finalizer advances its marker without
 * anyone's help — the correct response is "come back in a moment", exactly as for a quarantined
 * dependency, which the scheduler already retains without charging an attempt.
 *
 * <p>Before this split, a pending block was flattened into the generic failure branch. A subtree
 * under sustained ACL churn could therefore burn all ten attempts on transient gates and be
 * abandoned — and an abandoned task means those descendants keep stale {@code readers} until a
 * human runs a manual retry. Observed on a live stack: twelve descendants blocked by three
 * mid-mutation ancestors, all twelve queued, all twelve converging on the next poll. That run was
 * harmless because the churn stopped; sustained churn is the case this guards.
 *
 * <p>Note what is NOT claimed: a re-drive whose failures are all pending will be retried forever
 * if the gate never clears. That is why {@link #pendingBlocks()} is exported as a metric — an
 * ever-rising count with nothing converging means a marker is stuck and needs the operator, and
 * the counter is the only way to tell that from healthy churn.
 */
public final class RefreshCounters {

    /** JVM-wide, for the admin metrics endpoint. Not reset. */
    private static final AtomicLong PENDING_BLOCKS_TOTAL = new AtomicLong();

    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicInteger pendingBlocks = new AtomicInteger();

    /** Records a node that failed for a reason retrying may not fix. */
    public void recordFailure() {
        failures.incrementAndGet();
    }

    /** Records a node deferred by the pending gate. Also counts as a failure of this traversal. */
    public void recordPendingBlock() {
        pendingBlocks.incrementAndGet();
        failures.incrementAndGet();
        PENDING_BLOCKS_TOTAL.incrementAndGet();
    }

    public int failures() {
        return failures.get();
    }

    public int pendingBlocks() {
        return pendingBlocks.get();
    }

    /**
     * True when this traversal failed, and every one of its failures was a pending gate. The
     * caller may then retain the task without consuming an attempt.
     *
     * <p>Deliberately conjunctive: one genuine failure alongside ten pending blocks still
     * consumes an attempt, because the genuine failure is the one that needs the attempt budget
     * to eventually give up and surface.
     */
    public boolean blockedOnlyByPendingGates() {
        int f = failures.get();
        return f > 0 && f == pendingBlocks.get();
    }

    public static long pendingBlocksTotal() {
        return PENDING_BLOCKS_TOTAL.get();
    }
}
