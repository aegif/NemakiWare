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

    @Test
    public void cleanReDriveCompletesTask() {
        SearchIndexAclReindexTask t = task("sir-1", "obj-1", 0);
        when(svc.claimDue(anyInt(), anyString(), anyLong())).thenReturn(List.of(t));
        when(acl.reindexSearchIndexAclForObject("bedroom", "obj-1")).thenReturn(true);
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
        when(acl.reindexSearchIndexAclForObject("bedroom", "obj-2")).thenReturn(false);

        scheduler.poll();

        verify(svc).retryLater(eq(t), anyLong());
        verify(svc, never()).complete(any());
        verify(svc, never()).markFailed(any(), any());
    }

    @Test
    public void failingAtCapIsMarkedFailed() {
        SearchIndexAclReindexTask t = task("sir-3", "obj-3", 10); // == default maxAttempts
        when(svc.claimDue(anyInt(), anyString(), anyLong())).thenReturn(List.of(t));
        when(acl.reindexSearchIndexAclForObject("bedroom", "obj-3")).thenReturn(false);

        scheduler.poll();

        verify(svc).markFailed(eq(t), any());
        verify(svc, never()).complete(any());
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
}
