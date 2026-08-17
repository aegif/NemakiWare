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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.cmis.service.AclService;
import jp.aegif.nemaki.rest.purview.journal.LeaderElection;

/**
 * The scheduler side of the revocation lane.
 *
 * <h2>What the claim-level tests cannot cover</h2>
 *
 * <p>{@link ReconcilePurgeLaneTest} pins which tasks each lane CLAIMS. That is only half of it: a
 * task is LEASED the moment it is claimed, which puts it out of reach of the other lane, and what
 * happens next is decided by the order the scheduler processes its batch in.
 *
 * <p>The shared lane deliberately claims without filtering on operation, so it can hold a batch of
 * {@code [long ACL_REINDEX, RAG_PURGE]}. Processing that batch in claim order would run a subtree
 * walk — minutes to hours — before the purge, while the dedicated lane can only watch, because the
 * purge is already leased. The lane separation would have bought that purge nothing at all. These
 * tests pin the ordering that closes it, and the leader gate on the new lane.
 */
class ReconcileSchedulerPurgeOrderingTest {

    private SearchIndexReconciliationService svc;
    private AclService acl;
    private SearchIndexReconciliationScheduler scheduler;
    /** Every object the scheduler acted on, in the order it acted. */
    private final List<String> handled = new ArrayList<>();

    @BeforeEach
    void setUp() {
        svc = mock(SearchIndexReconciliationService.class);
        acl = mock(AclService.class);
        scheduler = new SearchIndexReconciliationScheduler();
        scheduler.setReconciliationService(svc);
        scheduler.setAclService(acl);

        when(acl.purgeRagBlockForObject(anyString(), anyString())).thenAnswer(inv -> {
            handled.add("purge:" + inv.getArgument(1));
            return true;
        });
        when(acl.reindexSearchIndexAclForObject(anyString(), anyString(), any())).thenAnswer(inv -> {
            handled.add("redrive:" + inv.getArgument(1));
            return true;
        });
        when(svc.complete(any())).thenReturn(true);
    }

    private static SearchIndexAclReindexTask task(String objectId, String operation) {
        SearchIndexAclReindexTask t = new SearchIndexAclReindexTask();
        t.setTaskId("task-" + objectId);
        t.setCouchId("id-" + objectId);
        t.setRepositoryId("bedroom");
        t.setObjectId(objectId);
        t.setOperation(operation);
        t.setStatus(SearchIndexAclReindexTask.Status.LEASED);
        return t;
    }

    @Test
    @DisplayName("混在バッチでは purge を再駆動より先に処理する")
    void aPurgeInAMixedBatchIsHandledBeforeTheSubtreeWalks() {
        when(svc.claimDue(anyInt(), anyString(), anyLong())).thenReturn(List.of(
                task("big-folder", SearchIndexAclReindexTask.Operation.ACL_REINDEX),
                task("another-folder", SearchIndexAclReindexTask.Operation.ACL_REINDEX),
                task("revoked-doc", SearchIndexAclReindexTask.Operation.RAG_PURGE)));

        scheduler.poll();

        assertEquals("purge:revoked-doc", handled.get(0),
                "the purge was claimed last but must be handled first — it is already LEASED, so"
                        + " the dedicated lane cannot rescue it, and running it after two subtree"
                        + " walks is exactly the wait the lane exists to prevent. Order: "
                        + handled);
        assertEquals(3, handled.size(), "everything in the batch must still be processed");
    }

    @Test
    @DisplayName("purge が無ければ並べ替えは元の順序 (古い順) を壊さない")
    void reorderingIsStableForABatchOfRedrives() {
        when(svc.claimDue(anyInt(), anyString(), anyLong())).thenReturn(List.of(
                task("oldest", SearchIndexAclReindexTask.Operation.ACL_REINDEX),
                task("middle", SearchIndexAclReindexTask.Operation.ACL_REINDEX),
                task("newest", SearchIndexAclReindexTask.Operation.ACL_REINDEX)));

        scheduler.poll();

        assertEquals(List.of("redrive:oldest", "redrive:middle", "redrive:newest"), handled,
                "the claim order IS the ageing order; a sort that is not stable would silently"
                        + " discard the oldest-due-first fairness the queue is built on");
    }

    @Test
    @DisplayName("purge レーンは purge 専用の claim を使う")
    void thePurgeLaneClaimsThroughItsOwnApi() {
        when(svc.claimDuePurges(anyInt(), anyString(), anyLong())).thenReturn(List.of(
                task("revoked-doc", SearchIndexAclReindexTask.Operation.RAG_PURGE)));

        scheduler.pollPurges();

        assertEquals(List.of("purge:revoked-doc"), handled);
        verify(svc, never()).claimDue(anyInt(), anyString(), anyLong());
    }

    @Test
    @DisplayName("purge レーンもリーダーでなければ claim しない")
    void thePurgeLaneRespectsTheLeaderGate() {
        LeaderElection notLeader = mock(LeaderElection.class);
        when(notLeader.isEnabled()).thenReturn(true);
        when(notLeader.isLeader(anyString())).thenReturn(false);
        scheduler.setLeaderElection(notLeader);

        scheduler.pollPurges();

        verify(svc, never()).claimDuePurges(anyInt(), anyString(), anyLong());
        assertTrue(handled.isEmpty(),
                "a follower running the purge lane would process the leader's leased tasks");
    }
}
