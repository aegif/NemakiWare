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
package jp.aegif.nemaki.cmis.aspect.query.solr;

import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.aegif.nemaki.cmis.service.impl.PropagationProgress;
import jp.aegif.nemaki.reconcile.SearchIndexReconciliationService;

/**
 * "Is this repository's search index still catching up with a permission change?"
 *
 * <h2>What it is for</h2>
 *
 * <p>While descendants' {@code readers} are being rewritten, the index over-reports for a
 * non-admin caller: the tokens of a revoked principal are still on documents the query then
 * counts. The in-memory gate removes those rows from the RESULT, so what the caller reads is
 * correct — but the count used to enforce the ACL scan cap is taken before that gate, so a query
 * that is comfortably within the cap once permissions settle can be rejected with a 400 while the
 * propagation runs. A revocation makes search look broken.
 *
 * <p>The cap itself is not the problem and is not being relaxed. This signal exists so the server
 * can tell "too broad" (reject, as always) from "temporarily over-counting because a permission
 * change is still landing" (degrade to what it can confirm).
 *
 * <h2>Why it is only consulted at the point of rejection</h2>
 *
 * <p>The cap check is a {@code rows=0} probe precisely so an over-broad query is refused without
 * transferring anything. Asking this question on every query would spend a CouchDB round trip to
 * answer "no" for the overwhelming majority of them. It is therefore asked ONLY when the server
 * is about to return the 400 — a request that was going to fail anyway, where one more query is
 * not a regression, and where the answer changes the outcome.
 *
 * <h2>What counts as unconverged</h2>
 *
 * <ul>
 * <li>A subtree refresh is running on THIS replica right now (free to check, in memory).</li>
 * <li>The durable reconciliation queue holds PENDING or LEASED work FOR THIS REPOSITORY — work
 *     that was deferred and has not been re-driven yet. This covers propagation started on
 *     another replica, which the local registry cannot see. Terminal {@code FAILED} entries are
 *     excluded: they are waiting for an operator, not for a propagation to finish, and treating
 *     them as "still landing" would leave the degradation switched on permanently.</li>
 * </ul>
 *
 * <p>Deliberately NOT included: whether this replica might have missed another replica's cache
 * invalidation. That would make the gate depend on the very thing it cannot observe, and would
 * turn "my cache may be stale" into a licence to return partial results for unrelated queries.
 */
public class AclPropagationStaleness {

    private static final Logger logger = LoggerFactory.getLogger(AclPropagationStaleness.class);

    /** How long a queue reading may be reused. Short enough to track a propagation's end. */
    static final long CACHE_MS = 2000;

    private SearchIndexReconciliationService reconciliationService;

    /** Per repository, so one repository's backlog cannot speak for another's. */
    private final java.util.Map<String, long[]> lastProbeAtMs = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Boolean> lastProbeSaidPending =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Times a query was allowed to degrade instead of returning 400. Exported as a metric. */
    private static final AtomicLong DEGRADED_QUERIES = new AtomicLong();

    public void setReconciliationService(SearchIndexReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    public static long degradedQueryCount() {
        return DEGRADED_QUERIES.get();
    }

    static void recordDegradedQuery() {
        DEGRADED_QUERIES.incrementAndGet();
    }

    /**
     * True when a permission change is still propagating and the index may therefore over-count.
     *
     * <p>Fails CLOSED: if the queue cannot be read, the answer is "no", so the query is rejected
     * with the usual 400 rather than silently degraded on an unverified excuse.
     */
    public boolean isPropagationUnconverged(String repositoryId) {
        for (PropagationProgress p : PropagationProgress.active()) {
            if (repositoryId != null && repositoryId.equals(p.toMap().get("repositoryId"))) {
                return true;
            }
        }
        return outstandingWorkFor(repositoryId);
    }

    private boolean outstandingWorkFor(String repositoryId) {
        if (reconciliationService == null || repositoryId == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        long[] last = lastProbeAtMs.computeIfAbsent(repositoryId, k -> new long[] { 0L });
        synchronized (last) {
            if (now - last[0] < CACHE_MS) {
                return Boolean.TRUE.equals(lastProbeSaidPending.get(repositoryId));
            }
        }
        try {
            // Scoped to THIS repository and to the statuses that mean "not done yet". A terminal
            // FAILED task is waiting for a human, not for the propagation to finish — counting it
            // would assert "a permission change is still landing" for ever, and (before this was
            // scoped) one such task in ANY repository degraded queries in every repository.
            boolean pending = reconciliationService.hasOutstandingWork(repositoryId);
            lastProbeSaidPending.put(repositoryId, pending);
            synchronized (last) {
                last[0] = now;
            }
            return pending;
        } catch (Exception e) {
            logger.warn("Could not read the reconciliation queue to decide whether a permission"
                    + " change is still propagating; treating the query as over-broad: {}",
                    e.getMessage());
            return false;
        }
    }
}
