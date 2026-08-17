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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.reconcile.SearchIndexAclReindexTask;
import jp.aegif.nemaki.reconcile.SearchIndexReconciliationService;

/**
 * A permission change must not be paid for by whoever happens to be making a request.
 *
 * <h2>The mechanism this closes</h2>
 *
 * <p>Descendant {@code readers} are refreshed on a bounded executor whose rejection policy is
 * {@code CallerRunsPolicy}. That policy is deliberate — it is the backpressure that stops an
 * unbounded queue from growing — but it has a consequence that only shows up under load: when the
 * queue is full, the task is handed back to the thread that submitted it, which on this path is a
 * user's request thread. That request then walks somebody else's subtree, one Solr document at a
 * time, before it answers. It is the single clearest way for "a permission change is propagating"
 * to become "the application is slow", which is the one outcome the owner ruled out.
 *
 * <p>Cooperative yielding does not fix it. Splitting the walk into segments and re-submitting the
 * remainder only works if the queue has room; when it is full, the continuation is handed straight
 * back to the same thread and nothing has changed. Handing the whole subtree to the durable
 * reconciliation queue does fix it, at the cost of re-walking from the root later.
 *
 * <p>The two properties below are what make that trade safe: the work is recorded before the
 * request is released, and when there is nowhere to record it the code refuses to drop it.
 */
class InlineRefreshDeferralTest {

    @Test
    @DisplayName("キューが溢れて要求スレッドに落ちたら、走査せず耐久キューへ委ねる")
    void anInlineRefreshIsHandedToTheDurableQueue() {
        SearchIndexReconciliationService reconcile = mock(SearchIndexReconciliationService.class);
        long before = PropagationProgress.deferredFromRequestThreadTotal();

        boolean deferred = AclServiceImpl.deferInlineRefreshToReconciliation(
                "bedroom", "folder-1", 42L, reconcile);

        assertTrue(deferred, "the caller must be told to return instead of walking the subtree");
        // enqueueOrThrow, not the fail-soft enqueue: returning true is a claim that the work is
        // now the queue's, and the fail-soft variant can swallow a CouchDB outage into a metric.
        verify(reconcile).enqueueOrThrow("bedroom", "folder-1",
                SearchIndexAclReindexTask.Reason.TRAVERSAL_FAILURE,
                SearchIndexAclReindexTask.Operation.ACL_REINDEX, 42L);
        assertEquals(before + 1, PropagationProgress.deferredFromRequestThreadTotal(),
                "the deferral must be counted, otherwise an operator sees caller-runs climbing"
                        + " with no way to tell whether requests are walking subtrees");
    }

    @Test
    @DisplayName("epoch 未設定 (null) でも NPE にならず 0 として積まれる")
    void anAbsentEpochIsRecordedAsNoObligation() {
        SearchIndexReconciliationService reconcile = mock(SearchIndexReconciliationService.class);

        // The durable overload takes a primitive long. An unguarded unbox of a null epoch would
        // throw inside the task, and the throw happens before the traversal's try/finally — so the
        // refresh would be lost AND the progress entry would leak.
        boolean deferred = AclServiceImpl.deferInlineRefreshToReconciliation(
                "bedroom", "folder-5", null, reconcile);

        assertTrue(deferred);
        verify(reconcile).enqueueOrThrow(eq("bedroom"), eq("folder-5"), any(), any(), eq(0L));
    }

    @Test
    @DisplayName("キューへの書き込みが失敗したら「委譲した」と言わない")
    void aFailedHandoffIsNotReportedAsASuccess() {
        SearchIndexReconciliationService reconcile = mock(SearchIndexReconciliationService.class);
        doThrow(new IllegalStateException("couchdb unavailable"))
                .when(reconcile).enqueueOrThrow(any(), any(), any(), any(), anyLong());
        long before = PropagationProgress.deferredFromRequestThreadTotal();

        boolean deferred = AclServiceImpl.deferInlineRefreshToReconciliation(
                "bedroom", "folder-6", 3L, reconcile);

        assertFalse(deferred,
                "if the queue write did not land, returning true would turn a slow request into"
                        + " a lost refresh — nobody would own the subtree");
        assertEquals(before, PropagationProgress.deferredFromRequestThreadTotal(),
                "a failed handoff must not be counted as a deferral, or the metric would report"
                        + " protection that is not there");
    }

    @Test
    @DisplayName("ACL epoch は再駆動に引き継がれる (fence を落とさない)")
    void theEpochObligationTravelsWithTheDeferredWork() {
        SearchIndexReconciliationService reconcile = mock(SearchIndexReconciliationService.class);

        AclServiceImpl.deferInlineRefreshToReconciliation("bedroom", "folder-2", 7L, reconcile);

        // The epoch is what fences the re-drive against a newer ACL change. Dropping it here would
        // turn a deferral into a silent downgrade: the work would still run, but unfenced.
        verify(reconcile).enqueueOrThrow(eq("bedroom"), eq("folder-2"), any(), any(), eq(7L));
    }

    @Test
    @DisplayName("耐久キューが無い構成では委譲せず、その場で走らせる (捨てない)")
    void withoutADurableQueueTheWorkIsNotDropped() {
        long before = PropagationProgress.deferredFromRequestThreadTotal();

        boolean deferred = AclServiceImpl.deferInlineRefreshToReconciliation(
                "bedroom", "folder-3", 1L, null);

        assertFalse(deferred,
                "with no queue to hand it to, returning early would lose the refresh entirely —"
                        + " a slow request is the lesser evil and the caller must walk it");
        assertEquals(before, PropagationProgress.deferredFromRequestThreadTotal(),
                "nothing was deferred, so nothing should be counted as deferred");
    }

    @Test
    @DisplayName("委譲の判断そのものは settle しない (完了扱いにしない)")
    void deferringNeverSettlesTheEpochObligation() {
        SearchIndexReconciliationService reconcile = mock(SearchIndexReconciliationService.class);

        AclServiceImpl.deferInlineRefreshToReconciliation("bedroom", "folder-4", 9L, reconcile);

        // The epoch terminus (settleIfCovered) CONSUMES the queue task. Calling it here would
        // delete the obligation this method just created, and the subtree would never be
        // refreshed by anyone — the failure would be silent and permanent.
        //
        // Pinned as "enqueue and nothing else" rather than naming a settle method: the point is
        // that deferring touches the queue in exactly one way, so any future call added here —
        // a completion, an acknowledgement, a status change — has to be justified against this
        // test rather than slipping in.
        verify(reconcile).enqueueOrThrow(eq("bedroom"), eq("folder-4"), any(), any(), eq(9L));
        verifyNoMoreInteractions(reconcile);
    }
}
