package jp.aegif.nemaki.reconcile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.cmis.service.AclService;
import jp.aegif.nemaki.rest.purview.journal.LeaderElection;

/**
 * Pins the reconciliation scheduler's poll outcomes: a clean re-drive deletes the
 * task, a failing one under the attempt cap is retried (reservation only), a
 * failing one at the cap is marked FAILED, a lost reservation (concurrent replica)
 * skips the re-drive, and a non-leader does not touch the queue.
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
        t.setStatus(SearchIndexAclReindexTask.Status.PENDING);
        return t;
    }

    @Test
    public void cleanReDriveDeletesTask() {
        SearchIndexAclReindexTask t = task("sir-1", "obj-1", 0);
        when(svc.listDue(anyInt())).thenReturn(List.of(t));
        when(svc.reserveForRetry(eq(t), anyLong())).thenReturn(true);
        when(acl.reindexSearchIndexAclForObject("bedroom", "obj-1")).thenReturn(true);

        scheduler.poll();

        verify(svc).delete("sir-1");
        verify(svc, never()).markFailed(any(), any());
    }

    @Test
    public void failingUnderCapIsRetriedNotFailed() {
        SearchIndexAclReindexTask t = task("sir-2", "obj-2", 1); // well under default max (10)
        when(svc.listDue(anyInt())).thenReturn(List.of(t));
        when(svc.reserveForRetry(eq(t), anyLong())).thenReturn(true);
        when(acl.reindexSearchIndexAclForObject("bedroom", "obj-2")).thenReturn(false);

        scheduler.poll();

        // reservation pushes the backoff; the task is neither deleted nor failed.
        verify(svc).reserveForRetry(eq(t), anyLong());
        verify(svc, never()).delete(any());
        verify(svc, never()).markFailed(any(), any());
    }

    @Test
    public void failingAtCapIsMarkedFailed() {
        SearchIndexAclReindexTask t = task("sir-3", "obj-3", 10); // == default maxAttempts
        when(svc.listDue(anyInt())).thenReturn(List.of(t));
        when(svc.reserveForRetry(eq(t), anyLong())).thenReturn(true);
        when(acl.reindexSearchIndexAclForObject("bedroom", "obj-3")).thenReturn(false);

        scheduler.poll();

        verify(svc).markFailed(eq(t), any());
        verify(svc, never()).delete(any());
    }

    @Test
    public void lostReservationSkipsReDrive() {
        SearchIndexAclReindexTask t = task("sir-4", "obj-4", 0);
        when(svc.listDue(anyInt())).thenReturn(List.of(t));
        when(svc.reserveForRetry(eq(t), anyLong())).thenReturn(false); // another replica won

        scheduler.poll();

        verify(acl, never()).reindexSearchIndexAclForObject(any(), any());
        verify(svc, never()).delete(any());
        verify(svc, never()).markFailed(any(), any());
    }

    @Test
    public void nonLeaderDoesNotDrainQueue() {
        LeaderElection leader = mock(LeaderElection.class);
        when(leader.isEnabled()).thenReturn(true);
        when(leader.isLeader("search-index-reconciliation")).thenReturn(false);
        scheduler.setLeaderElection(leader);

        scheduler.poll();

        verify(svc, never()).listDue(anyInt());
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }
}
