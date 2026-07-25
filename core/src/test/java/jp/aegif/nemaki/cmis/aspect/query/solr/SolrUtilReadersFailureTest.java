package jp.aegif.nemaki.cmis.aspect.query.solr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;

import jp.aegif.nemaki.acl.PrincipalUnavailableException;
import jp.aegif.nemaki.reconcile.SearchIndexReconciliationService;

/**
 * Increment 5T, part 2: what the index path does when readers CANNOT be computed.
 *
 * <p>This binds the decision itself. Before 5T the ordinary path ended at a bare {@code log.warn}:
 * because the catch sits INSIDE {@code createSolrDocument}, execution continued and the document
 * was indexed with an empty {@code readers} set as a SUCCESS — on the most frequent path there is
 * — with nothing queued, so the stale-deny survived until the next ACL change or a full reindex.
 *
 * <p>What this test does NOT claim: it binds the strict/ordinary decision and the enqueue, not the
 * continuation inside {@code createSolrDocument} that leaves {@code readers} unset. That
 * continuation is structural — the method returns without adding the field — and the fail-closed
 * query-side consequence is covered by the existing reader-token tests.
 */
public class SolrUtilReadersFailureTest {

    private static final String REPO = "bedroom";
    private static final String DOC = "doc-1";

    private SolrUtil solrUtil;
    private SearchIndexReconciliationService queue;

    @BeforeEach
    public void setUp() {
        solrUtil = new SolrUtil();
        queue = mock(SearchIndexReconciliationService.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(SearchIndexReconciliationService.class)).thenReturn(queue);
        solrUtil.setApplicationContext(ctx);
    }

    @Test
    public void strictThrowsSoTheReconciliationTaskIsRetriedNotCompleted() {
        Exception cause = new PrincipalUnavailableException("principal lookup could not be served");

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> solrUtil.onReadersComputationFailed(REPO, DOC, cause, true));

        assertTrue(e.getMessage().contains(DOC), "the failing object must be named");
        assertEquals(cause, e.getCause(), "the original failure must not be swallowed");
        // The point of throwing: the re-drive must NOT quietly hand the object to the queue and
        // report success. It reports failure to its caller, which keeps the task.
        verify(queue, never()).enqueue(anyString(), anyString(), anyString());
    }

    @Test
    public void theOrdinaryPathEnqueuesInsteadOfOnlyWarning() {
        Exception cause = new PrincipalUnavailableException("principal lookup could not be served");

        // Must NOT throw: the ordinary index write continues and the document is indexed with an
        // empty readers set (fail-closed for non-admin queries).
        solrUtil.onReadersComputationFailed(REPO, DOC, cause, false);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(queue).enqueue(eq(REPO), eq(DOC), reason.capture());
        assertTrue(reason.getValue().startsWith("READERS_COMPUTATION_FAILURE"),
                "the queued reason must identify the failure class, was: " + reason.getValue());
    }

    @Test
    public void anUnwiredQueueDoesNotFailTheOrdinaryIndexWrite() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(SearchIndexReconciliationService.class))
                .thenThrow(new IllegalStateException("no such bean"));
        solrUtil.setApplicationContext(ctx);

        // Best-effort by design: a queue problem must not fail an ordinary create/update. The
        // failure is visible through the reconciliation enqueueFailureCount metric and the log.
        solrUtil.onReadersComputationFailed(REPO, DOC, new RuntimeException("boom"), false);
    }

    @Test
    public void aNullCauseIsStillReportedRatherThanNPEing() {
        solrUtil.onReadersComputationFailed(REPO, DOC, null, false);
        verify(queue).enqueue(eq(REPO), eq(DOC), anyString());

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> solrUtil.onReadersComputationFailed(REPO, DOC, null, true));
        assertTrue(e.getMessage().contains(DOC));
    }
}
