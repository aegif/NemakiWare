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
package jp.aegif.nemaki.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A revocation must not wait behind a bulk permission change.
 *
 * <h2>Why a separate lane rather than a share of the batch</h2>
 *
 * <p>The reconciliation queue holds two kinds of task: {@code ACL_REINDEX}, an eventual-consistency
 * chore that re-walks a subtree, and {@code RAG_PURGE}, which removes a RAG block a reader has just
 * lost access to. Until the purge runs, that block is still usable as an existence-and-similarity
 * oracle by the person whose access was revoked, so it is an outstanding security obligation.
 *
 * <p>Reserving a slice of each claimed batch for purges looks like it solves this and does not.
 * The poller processes its batch SERIALLY, and one re-drive can hold the thread for hours; a purge
 * that arrives a second after the batch was claimed cannot be claimed at all until that walk ends,
 * however much of the batch was reserved. The reservation protects the claim, and the claim is not
 * where the waiting happens. A separate poll thread is what actually bounds it.
 *
 * <p>These tests pin the two halves of that split: the purge lane takes purges and only purges,
 * and the shared lane stays unfiltered so that no class of task is orphaned if the purge lane is
 * unwired or wedged.
 */
class ReconcilePurgeLaneTest {

    /**
     * A service whose two CouchDB touchpoints are replaced: {@code findSortedAsc} answers from a
     * canned backlog chosen by the selector, and {@code putCas} always wins its compare-and-swap.
     * Everything in between — which selectors are issued, in what order, with what limits, and how
     * the results are combined — is the real production code.
     */
    private static final class FakeQueue extends SearchIndexReconciliationService {
        private final List<SearchIndexAclReindexTask> pendingByAge = new ArrayList<>();
        private final List<SearchIndexAclReindexTask> expiredLeases = new ArrayList<>();
        /** Every selector the production code asked for, in order. */
        final List<String> queries = new ArrayList<>();

        @Override
        List<SearchIndexAclReindexTask> findSortedAsc(Map<String, Object> selector, String sortField,
                int limit) {
            boolean purgeOnly = SearchIndexAclReindexTask.Operation.RAG_PURGE
                    .equals(selector.get("operation"));
            boolean leased = SearchIndexAclReindexTask.Status.LEASED.equals(selector.get("status"));
            queries.add((purgeOnly ? "purge-" : "any-") + (leased ? "leased" : "pending")
                    + ":" + limit);

            List<SearchIndexAclReindexTask> source = leased ? expiredLeases : pendingByAge;
            List<SearchIndexAclReindexTask> out = new ArrayList<>();
            for (SearchIndexAclReindexTask t : source) {
                if (out.size() >= limit) break;
                if (purgeOnly && !SearchIndexAclReindexTask.Operation.RAG_PURGE.equals(
                        t.getOperation())) {
                    continue;
                }
                out.add(t);
            }
            return out;
        }

        @Override
        String putCas(SearchIndexAclReindexTask task) {
            return "claimed-rev";
        }

        /** Append in due order, so the list doubles as "oldest first". */
        void duePurge(String id) {
            pendingByAge.add(task(id, SearchIndexAclReindexTask.Operation.RAG_PURGE));
        }

        void dueRedrive(String id) {
            pendingByAge.add(task(id, SearchIndexAclReindexTask.Operation.ACL_REINDEX));
        }

        void expiredLease(String id, String op) {
            expiredLeases.add(task(id, op));
        }

        private static SearchIndexAclReindexTask task(String id, String op) {
            SearchIndexAclReindexTask t = new SearchIndexAclReindexTask();
            t.setCouchId(id);
            t.setObjectId(id);
            t.setRepositoryId("bedroom");
            t.setOperation(op);
            t.setStatus(SearchIndexAclReindexTask.Status.PENDING);
            return t;
        }
    }

    private static List<String> ids(List<SearchIndexAclReindexTask> claimed) {
        List<String> out = new ArrayList<>();
        for (SearchIndexAclReindexTask t : claimed) {
            out.add(t.getCouchId());
        }
        return out;
    }

    @Test
    @DisplayName("purge レーンは再駆動のバックログを一切引き受けない")
    void thePurgeLaneClaimsPurgesAndNothingElse() {
        FakeQueue q = new FakeQueue();
        for (int i = 0; i < 200; i++) {
            q.dueRedrive("redrive-" + i);
        }
        q.duePurge("purge-late-1");
        q.duePurge("purge-late-2");

        List<String> claimed = ids(q.claimDuePurges(10, "node-a", 60_000L));

        assertEquals(List.of("purge-late-1", "purge-late-2"), claimed,
                "the two purges are the newest tasks in the queue and must still be claimed"
                        + " immediately: 200 older re-drives are irrelevant to this lane");
        for (String query : q.queries) {
            assertTrue(query.startsWith("purge-"),
                    "every query this lane issues must filter on operation in the SELECTOR, not"
                            + " after the fact — otherwise CouchDB scans the whole due range to"
                            + " find the rare purges hidden in a large backlog: " + q.queries);
        }
        assertTrue(q.queries.get(0).startsWith("purge-leased:"),
                "expired leases are asked for first: filling from fresh PENDING first would let a"
                        + " steady stream of new purges hide a crashed worker's abandoned one"
                        + " forever. Queries: " + q.queries);
    }

    @Test
    @DisplayName("期限切れ purge が新着 purge に押し出されない (フルバッチの新着があっても)")
    void anAbandonedPurgeIsNotBuriedByAStreamOfFreshOnes() {
        FakeQueue q = new FakeQueue();
        q.expiredLease("abandoned-purge", SearchIndexAclReindexTask.Operation.RAG_PURGE);
        for (int i = 0; i < 50; i++) {
            q.duePurge("fresh-" + i);
        }

        List<String> claimed = ids(q.claimDuePurges(5, "node-a", 60_000L));

        assertEquals("abandoned-purge", claimed.get(0),
                "with a full batch of fresh purges due on every poll, filling from PENDING first"
                        + " means the abandoned one is never even looked at: " + claimed);
    }

    @Test
    @DisplayName("purge レーンは期限切れリースの purge も回収する")
    void thePurgeLaneAlsoReclaimsAbandonedPurges() {
        FakeQueue q = new FakeQueue();
        q.expiredLease("abandoned-purge", SearchIndexAclReindexTask.Operation.RAG_PURGE);
        q.expiredLease("abandoned-redrive", SearchIndexAclReindexTask.Operation.ACL_REINDEX);

        List<String> claimed = ids(q.claimDuePurges(10, "node-a", 60_000L));

        assertEquals(List.of("abandoned-purge"), claimed,
                "a purge whose worker died must be recovered by this lane; leaving it to the"
                        + " shared lane puts it back behind the subtree walks");
    }

    @Test
    @DisplayName("共有レーンは operation で絞らない (purge レーンが死んでも孤児にしない)")
    void theSharedLaneStaysUnfilteredSoNothingIsOrphaned() {
        FakeQueue q = new FakeQueue();
        q.duePurge("purge-1");
        q.dueRedrive("redrive-0");
        q.dueRedrive("redrive-1");

        List<String> claimed = ids(q.claimDue(10, "node-a", 60_000L));

        assertEquals(List.of("purge-1", "redrive-0", "redrive-1"), claimed,
                "the shared lane must still be a complete drain: the purge lane exists to stop a"
                        + " purge WAITING, not because this lane cannot run one");
        for (String query : q.queries) {
            assertTrue(query.startsWith("any-"),
                    "the shared lane must not filter by operation: " + q.queries);
        }
    }

    @Test
    @DisplayName("共有レーンは期限切れリースを先に取り、残りを古い順で埋める")
    void theSharedLaneRecoversAbandonedWorkBeforeFreshPending() {
        FakeQueue q = new FakeQueue();
        q.expiredLease("abandoned-1", SearchIndexAclReindexTask.Operation.ACL_REINDEX);
        for (int i = 0; i < 10; i++) {
            q.dueRedrive("redrive-" + i);
        }

        List<String> claimed = ids(q.claimDue(3, "node-a", 60_000L));

        assertEquals(List.of("abandoned-1", "redrive-0", "redrive-1"), claimed,
                "a full batch of fresh PENDING would otherwise never leave room to reclaim a"
                        + " crashed worker's lease");
    }

    @Test
    @DisplayName("2 つの充填が重複してもバッチが埋まり切る (不足数ではなくフルバッチを要求)")
    void anOverlapBetweenTheTwoFillsDoesNotUnderFillTheBatch() {
        FakeQueue q = new FakeQueue();
        // The same document seen by both fills: in production this happens when a task changes
        // status between the two queries. Asking the second fill for only the shortfall would
        // lose a slot per duplicate, which is worst exactly when a backlog makes it matter.
        q.expiredLease("both-1", SearchIndexAclReindexTask.Operation.ACL_REINDEX);
        q.pendingByAge.add(q.expiredLeases.get(0));
        for (int i = 0; i < 10; i++) {
            q.dueRedrive("redrive-" + i);
        }

        List<String> claimed = ids(q.claimDue(4, "node-a", 60_000L));

        assertEquals(4, claimed.size(), "the batch must be full: " + claimed);
        assertEquals(List.of("both-1", "redrive-0", "redrive-1", "redrive-2"), claimed,
                "the duplicate is collapsed and the freed slot is refilled, not dropped");
    }
}
