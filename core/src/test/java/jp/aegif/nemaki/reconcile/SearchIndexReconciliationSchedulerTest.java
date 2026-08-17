package jp.aegif.nemaki.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.cmis.service.AclService;
import jp.aegif.nemaki.rest.purview.journal.LeaderElection;

/**
 * Pins the reconciliation scheduler's poll outcomes over the claim/ACK (CAS) API:
 * a clean re-drive is {@code complete}d, a failure under the cap is
 * {@code retryLater}ed, a failure at the cap is {@code markFailed}ed, and a
 * non-leader never claims. Also pins the deterministic-id encoding.
 */
public class SearchIndexReconciliationSchedulerTest {

    private SearchIndexReconciliationService svc;
    private AclService acl;
    private SearchIndexReconciliationScheduler scheduler;

    @BeforeEach
    public void setUp() {
        svc = mock(SearchIndexReconciliationService.class);
        acl = mock(AclService.class);
        scheduler = new SearchIndexReconciliationScheduler();
        scheduler.setReconciliationService(svc);
        scheduler.setAclService(acl);
        // leaderElection left null → gating disabled (single-replica default)
    }

    private SearchIndexAclReindexTask task(String id, String objectId, int attempts) {
        SearchIndexAclReindexTask t = new SearchIndexAclReindexTask();
        t.setTaskId(id);
        t.setRepositoryId("bedroom");
        t.setObjectId(objectId);
        t.setAttempts(attempts);
        t.setStatus(SearchIndexAclReindexTask.Status.LEASED);
        return t;
    }

    /**
     * §5.1 items 1 + 3 (wiring gate 4). A quarantined dependency — usually an ANCESTOR — is not this
     * task's fault and is not fixed by retrying: it needs a human. So the task must be RETAINED and
     * its attempt count must NOT advance towards the cap, or a subtree blocked for a day would burn
     * through maxAttempts and be abandoned exactly when the repair finally lands.
     */
    @Test
    public void aQuarantineBlockRETAINSTheTaskAndDoesNotCountAsAFailure() {
        SearchIndexAclReindexTask t = task("sir-q", "obj-q", 9); // ONE below the default cap of 10
        when(svc.claimDue(anyInt(), anyString(), anyLong())).thenReturn(List.of(t));
        when(acl.reindexSearchIndexAclForObject(eq("bedroom"), eq("obj-q"),
                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new jp.aegif.nemaki.epoch.AclEpochQuarantineBlockedException(
                        "document is quarantined on anc-1", "anc-1"));

        scheduler.poll();

        verify(svc, never()).markFailed(any(), any());   // never terminal, even at the cap
        verify(svc, never()).complete(any());            // and never silently completed
        verify(svc).retryLaterWithoutCountingAnAttempt(eq(t), anyLong()); // RETAINED, under backoff
        verify(svc, never()).retryLater(any(), anyLong()); // which COUNTS an attempt — see the IT
    }

    /** A quarantine block retries at the CAPPED delay — a repair happens on human timescales. */
    @Test
    public void aQuarantineBlockBacksOffAtTheCAP_notTheNormalPollInterval() {
        SearchIndexAclReindexTask blocked = task("sir-q2", "obj-q2", 0); // attempts=0
        when(svc.claimDue(anyInt(), anyString(), anyLong())).thenReturn(List.of(blocked));
        when(acl.reindexSearchIndexAclForObject(eq("bedroom"), eq("obj-q2"),
                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new jp.aegif.nemaki.epoch.AclEpochQuarantineBlockedException("q", "anc-2"));

        scheduler.poll();

        org.mockito.ArgumentCaptor<Long> backoff = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(svc).retryLaterWithoutCountingAnAttempt(eq(blocked), backoff.capture());
        assertTrue(backoff.getValue() >= 3600_000L,
                "a first-attempt ordinary failure would back off ~60s; a quarantine block must go "
                        + "straight to the cap, got " + backoff.getValue() + "ms");
    }

    @Test
    public void cleanReDriveCompletesTask() {
        SearchIndexAclReindexTask t = task("sir-1", "obj-1", 0);
        when(svc.claimDue(anyInt(), anyString(), anyLong())).thenReturn(List.of(t));
        when(acl.reindexSearchIndexAclForObject(eq("bedroom"), eq("obj-1"), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(svc.complete(t)).thenReturn(true);

        scheduler.poll();

        verify(svc).complete(t);
        verify(svc, never()).markFailed(any(), any());
        verify(svc, never()).retryLater(any(), anyLong());
    }

    @Test
    public void failingUnderCapIsRetried() {
        SearchIndexAclReindexTask t = task("sir-2", "obj-2", 1); // under default max (10)
        when(svc.claimDue(anyInt(), anyString(), anyLong())).thenReturn(List.of(t));
        when(acl.reindexSearchIndexAclForObject(eq("bedroom"), eq("obj-2"), org.mockito.ArgumentMatchers.any())).thenReturn(false);

        scheduler.poll();

        verify(svc).retryLater(eq(t), anyLong());
        verify(svc, never()).complete(any());
        verify(svc, never()).markFailed(any(), any());
    }

    @Test
    public void failingAtCapIsMarkedFailed() {
        SearchIndexAclReindexTask t = task("sir-3", "obj-3", 10); // == default maxAttempts
        when(svc.claimDue(anyInt(), anyString(), anyLong())).thenReturn(List.of(t));
        when(acl.reindexSearchIndexAclForObject(eq("bedroom"), eq("obj-3"), org.mockito.ArgumentMatchers.any())).thenReturn(false);

        scheduler.poll();

        verify(svc).markFailed(eq(t), any());
        verify(svc, never()).complete(any());
        verify(svc, never()).retryLater(any(), anyLong());
    }

    @Test
    public void boundaryTheNthDriveFailsAtExactlyMaxAttempts() {
        // attempts counts PRIOR failed drives; the 10th drive (attempts=9) fails ->
        // (9+1)>=10 -> markFailed. So exactly maxAttempts(=10) re-drives, no off-by-one.
        SearchIndexAclReindexTask ninth = task("sir-9", "obj-9", 8); // 9th drive
        when(svc.claimDue(anyInt(), anyString(), anyLong())).thenReturn(List.of(ninth));
        when(acl.reindexSearchIndexAclForObject(eq("bedroom"), eq("obj-9"), org.mockito.ArgumentMatchers.any())).thenReturn(false);
        scheduler.poll();
        verify(svc).retryLater(eq(ninth), anyLong());   // 9th drive -> still retried
        verify(svc, never()).markFailed(any(), any());
    }

    @Test
    public void boundaryTenthDriveIsMarkedFailed() {
        SearchIndexAclReindexTask tenth = task("sir-10", "obj-10", 9); // 10th drive
        when(svc.claimDue(anyInt(), anyString(), anyLong())).thenReturn(List.of(tenth));
        when(acl.reindexSearchIndexAclForObject(eq("bedroom"), eq("obj-10"), org.mockito.ArgumentMatchers.any())).thenReturn(false);
        scheduler.poll();
        verify(svc).markFailed(eq(tenth), any());       // 10th drive -> FAILED (exactly maxAttempts)
        verify(svc, never()).retryLater(any(), anyLong());
    }

    @Test
    public void nonLeaderDoesNotClaim() {
        LeaderElection leader = mock(LeaderElection.class);
        when(leader.isEnabled()).thenReturn(true);
        when(leader.isLeader("search-index-reconciliation")).thenReturn(false);
        scheduler.setLeaderElection(leader);

        scheduler.poll();

        verify(svc, never()).claimDue(anyInt(), anyString(), anyLong());
    }

    @Test
    public void deterministicIdIsStableAndEncodesSeparators() {
        String a = SearchIndexAclReindexTask.deterministicId("bedroom", "abc123");
        String b = SearchIndexAclReindexTask.deterministicId("bedroom", "abc123");
        assertEquals(a, b, "same (repo,object) must map to the same _id");
        assertTrue(a.startsWith(SearchIndexAclReindexTask.ID_PREFIX + "bedroom::"));
        // a colon in the objectId is encoded so the '::' separator stays unambiguous
        String withColon = SearchIndexAclReindexTask.deterministicId("bedroom", "a:b");
        assertTrue(withColon.endsWith("::a%3Ab"), "colon in objectId must be percent-encoded");
    }

    // ── operation dispatch (RAG_PURGE must never be handled as an ACL reindex) ──

    @Test
    public void ragPurgeTaskIsPurgedNotAclReindexed() {
        SearchIndexAclReindexTask t = task("sir-p1", "pwc-1", 0);
        t.setOperation(SearchIndexAclReindexTask.Operation.RAG_PURGE);
        when(svc.claimDue(anyInt(), anyString(), anyLong())).thenReturn(List.of(t));
        when(acl.purgeRagBlockForObject("bedroom", "pwc-1")).thenReturn(true);
        when(svc.complete(t)).thenReturn(true);

        scheduler.poll();

        verify(acl).purgeRagBlockForObject("bedroom", "pwc-1");
        // The scheduler must NOT run the ACL reindex for a purge task — that would
        // leave (or even refresh) the very block the purge exists to remove.
        verify(acl, never()).reindexSearchIndexAclForObject(anyString(), anyString(), any());
        verify(svc).complete(t);
    }

    @Test
    public void failedRagPurgeIsRetriedNotCompleted() {
        SearchIndexAclReindexTask t = task("sir-p2", "pwc-2", 0);
        t.setOperation(SearchIndexAclReindexTask.Operation.RAG_PURGE);
        when(svc.claimDue(anyInt(), anyString(), anyLong())).thenReturn(List.of(t));
        when(acl.purgeRagBlockForObject("bedroom", "pwc-2")).thenReturn(false);

        scheduler.poll();

        verify(svc, never()).complete(t);
        verify(svc).retryLater(eq(t), anyLong());
    }

    @Test
    public void ragPurgeNeverBecomesTerminalFailed() {
        // A purge at/over the attempt cap must NOT be markFailed — a terminal FAILED
        // purge would let a stale PWC block silently return when RAG is re-enabled.
        SearchIndexAclReindexTask t = task("sir-p9", "pwc-9", 99); // way over any cap
        t.setOperation(SearchIndexAclReindexTask.Operation.RAG_PURGE);
        when(svc.claimDue(anyInt(), anyString(), anyLong())).thenReturn(List.of(t));
        when(acl.purgeRagBlockForObject("bedroom", "pwc-9")).thenReturn(false);

        scheduler.poll();

        verify(svc, never()).markFailed(any(), anyString());
        verify(svc).retryLater(eq(t), anyLong()); // stays PENDING under capped backoff
    }

    @Test
    public void absentOperationDefaultsToAclReindexBackwardCompatible() {
        // Pre-existing queue documents have no operation field at all.
        SearchIndexAclReindexTask t = task("sir-p3", "obj-old", 0);
        assertEquals(SearchIndexAclReindexTask.Operation.ACL_REINDEX, t.getEffectiveOperation(),
                "absent operation must mean ACL_REINDEX (backward compatibility)");
        when(svc.claimDue(anyInt(), anyString(), anyLong())).thenReturn(List.of(t));
        when(acl.reindexSearchIndexAclForObject(eq("bedroom"), eq("obj-old"), any())).thenReturn(true);
        when(svc.complete(t)).thenReturn(true);

        scheduler.poll();

        verify(acl).reindexSearchIndexAclForObject(eq("bedroom"), eq("obj-old"), any());
        verify(acl, never()).purgeRagBlockForObject(anyString(), anyString());
    }
}
