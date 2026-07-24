package jp.aegif.nemaki.rag.indexing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.SolrParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.TextExtractionService;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.rag.acl.ACLExpander;
import jp.aegif.nemaki.rag.chunking.ChunkingService;
import jp.aegif.nemaki.rag.config.RAGConfig;
import jp.aegif.nemaki.rag.config.SolrClientProvider;
import jp.aegif.nemaki.rag.embedding.EmbeddingService;
import jp.aegif.nemaki.reconcile.SearchIndexAclReindexTask;
import jp.aegif.nemaki.reconcile.SearchIndexReconciliationService;

/**
 * Pins the PWC exclusion at the RAG single choke point
 * ({@link RAGIndexingServiceImpl#indexDocument}) and — critically — that a FAILED
 * best-effort delete of a stale PWC block is NOT swallowed as success: it becomes a
 * durable {@code RAG_PURGE} reconciliation task (review finding: the previous code
 * WARN-logged the failure and returned normally, leaving the seed-oracle block alive
 * forever with no retry).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class RAGIndexingServiceImplPwcTest {

    private static final String REPO_ID = "test-repo";
    private static final String PWC_ID = "pwc-42";

    @Mock private RAGConfig ragConfig;
    @Mock private EmbeddingService embeddingService;
    @Mock private ChunkingService chunkingService;
    @Mock private TextExtractionService textExtractionService;
    @Mock private ContentService contentService;
    @Mock private ACLExpander aclExpander;
    @Mock private SolrClientProvider solrClientProvider;
    @Mock private SolrClient solrClient;
    @Mock private SearchIndexReconciliationService reconciliationService;

    private RAGIndexingServiceImpl service;

    @BeforeEach
    public void setUp() throws Exception {
        service = new RAGIndexingServiceImpl(ragConfig, embeddingService, chunkingService,
                textExtractionService, contentService, aclExpander, solrClientProvider);
        service.setReconciliationService(reconciliationService);
        when(ragConfig.isEnabled()).thenReturn(true);
        when(embeddingService.isHealthy()).thenReturn(true);
        when(solrClientProvider.getClient()).thenReturn(solrClient);
    }

    private Document pwc() {
        Document d = new Document();
        d.setId(PWC_ID);
        d.setName("draft.docx");
        d.setPrivateWorkingCopy(Boolean.TRUE);
        return d;
    }

    @Test
    public void pwcIsNeverIndexedAndStaleBlockIsDeleted() throws Exception {
        service.indexDocument(REPO_ID, pwc());

        // The stale block (if any) is deleted by _root_ …
        verify(solrClient).deleteByQuery(eq("nemaki"), anyString());
        // … and NOTHING is chunked/embedded/added for a PWC (isEnabled() legitimately
        // touches embeddingService.isHealthy(), so verify the pipeline mocks only).
        verifyNoInteractions(chunkingService, textExtractionService);
        verify(reconciliationService, never()).enqueue(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    public void pwcDeleteFailureEnqueuesDurableRagPurgeInsteadOfSwallowing() throws Exception {
        when(solrClient.deleteByQuery(eq("nemaki"), anyString()))
                .thenThrow(new RuntimeException("Solr down"));

        // Must NOT throw (a single PWC must not abort a full reindex) …
        assertDoesNotThrow(() -> service.indexDocument(REPO_ID, pwc()));

        // … but must NOT be silent success either: a durable RAG_PURGE task is created.
        verify(reconciliationService).enqueue(
                eq(REPO_ID), eq(PWC_ID),
                eq(SearchIndexAclReindexTask.Reason.PWC_PURGE_FAILURE),
                eq(SearchIndexAclReindexTask.Operation.RAG_PURGE));
    }

    @Test
    public void pwcDeleteFailureWithoutQueueDoesNotThrow() throws Exception {
        service.setReconciliationService(null);
        when(solrClient.deleteByQuery(eq("nemaki"), anyString()))
                .thenThrow(new RuntimeException("Solr down"));

        assertDoesNotThrow(() -> service.indexDocument(REPO_ID, pwc()),
                "a minimal context without the queue must degrade to a loud log, not an exception");
    }

    @Test
    public void nonPwcDocumentPassesTheChokePointWithoutDeletion() throws Exception {
        Document normal = new Document();
        normal.setId("doc-1");
        normal.setName("normal.docx");
        normal.setPrivateWorkingCopy(Boolean.FALSE);
        // Force the mime gate right AFTER the PWC check to stop the pipeline —
        // reaching it proves the PWC branch was passed, without mocking the whole
        // embedding pipeline.
        when(contentService.getAttachment(anyString(), any())).thenReturn(null);

        try {
            service.indexDocument(REPO_ID, normal);
        } catch (RAGIndexingException expected) {
            // unsupported mime / no content — fine, we only assert the PWC branch
        }
        verify(solrClient, never()).deleteByQuery(anyString(), anyString());
        verify(reconciliationService, never()).enqueue(anyString(), anyString(), anyString(), anyString());
    }

    // ── isDocumentInRagIndex (the purge verifier) ──────────────────

    @Test
    public void isDocumentInRagIndexTrueWhenAnythingSurvives() throws Exception {
        SolrDocumentList hits = new SolrDocumentList();
        hits.setNumFound(3);
        org.apache.solr.client.solrj.response.QueryResponse resp =
                mock(org.apache.solr.client.solrj.response.QueryResponse.class);
        when(resp.getResults()).thenReturn(hits);
        when(solrClient.query(eq("nemaki"), any(SolrParams.class))).thenReturn(resp);

        assertTrue(service.isDocumentInRagIndex(REPO_ID, PWC_ID),
                "surviving parent or chunks must be reported as present");
    }

    @Test
    public void isDocumentInRagIndexFalseWhenEmpty() throws Exception {
        SolrDocumentList hits = new SolrDocumentList();
        hits.setNumFound(0);
        org.apache.solr.client.solrj.response.QueryResponse resp =
                mock(org.apache.solr.client.solrj.response.QueryResponse.class);
        when(resp.getResults()).thenReturn(hits);
        when(solrClient.query(eq("nemaki"), any(SolrParams.class))).thenReturn(resp);

        assertFalse(service.isDocumentInRagIndex(REPO_ID, PWC_ID));
    }

    @Test
    public void isDocumentInRagIndexThrowsOnQueryFailureNeverReportsAbsent() throws Exception {
        when(solrClient.query(eq("nemaki"), any(SolrParams.class)))
                .thenThrow(new RuntimeException("connection refused"));

        assertThrows(RAGIndexingException.class,
                () -> service.isDocumentInRagIndex(REPO_ID, PWC_ID),
                "an unverifiable state must throw (unknown != absent) so the purge task is retried");
    }
}
