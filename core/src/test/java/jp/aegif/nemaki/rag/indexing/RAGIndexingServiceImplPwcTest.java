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
 * ({@link RAGIndexingServiceImpl#indexDocument}) with the round-6 hardening: the
 * IMMEDIATE path deletes AND verifies absence, and any unverified outcome (block
 * still present, or Solr unreachable) becomes a DURABLE {@code RAG_PURGE} task via
 * {@code enqueueOrThrow} — never a swallowed WARN. If the durable obligation itself
 * cannot be recorded (no queue, or the enqueue fails), {@code indexDocument} FAILS
 * (so a batch reindex's per-doc catch surfaces it) rather than reporting success
 * with a live seed-oracle block.
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

    private void stubVerify(long numFound) throws Exception {
        SolrDocumentList hits = new SolrDocumentList();
        hits.setNumFound(numFound);
        org.apache.solr.client.solrj.response.QueryResponse resp =
                mock(org.apache.solr.client.solrj.response.QueryResponse.class);
        when(resp.getResults()).thenReturn(hits);
        when(solrClient.query(eq("nemaki"), any(SolrParams.class))).thenReturn(resp);
    }

    @Test
    public void pwcDeletedAndVerifiedAbsentNoTask() throws Exception {
        stubVerify(0); // absent after delete
        service.indexDocument(REPO_ID, pwc());

        verify(solrClient).deleteByQuery(eq("nemaki"), anyString());
        verifyNoInteractions(chunkingService, textExtractionService);
        verify(reconciliationService, never()).enqueueOrThrow(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    public void pwcStillPresentAfterDeleteEnqueuesDurablePurge() throws Exception {
        stubVerify(2); // verification says the block survived
        service.indexDocument(REPO_ID, pwc());

        verify(reconciliationService).enqueueOrThrow(
                eq(REPO_ID), eq(PWC_ID),
                eq(SearchIndexAclReindexTask.Reason.PWC_PURGE_FAILURE),
                eq(SearchIndexAclReindexTask.Operation.RAG_PURGE));
    }

    @Test
    public void pwcDeleteFailureEnqueuesDurablePurge() throws Exception {
        when(solrClient.deleteByQuery(eq("nemaki"), anyString()))
                .thenThrow(new RuntimeException("Solr down"));

        assertDoesNotThrow(() -> service.indexDocument(REPO_ID, pwc()));
        verify(reconciliationService).enqueueOrThrow(
                eq(REPO_ID), eq(PWC_ID),
                eq(SearchIndexAclReindexTask.Reason.PWC_PURGE_FAILURE),
                eq(SearchIndexAclReindexTask.Operation.RAG_PURGE));
    }

    @Test
    public void pwcDeleteFailureWithoutQueueThrows() throws Exception {
        service.setReconciliationService(null);
        when(solrClient.deleteByQuery(eq("nemaki"), anyString()))
                .thenThrow(new RuntimeException("Solr down"));

        // No queue to make the obligation durable → indexDocument MUST fail (round-6
        // fix: the previous code returned normally, leaving the block alive silently).
        assertThrows(RAGIndexingException.class, () -> service.indexDocument(REPO_ID, pwc()));
    }

    @Test
    public void pwcEnqueueFailureThrows() throws Exception {
        stubVerify(1); // still present
        doThrow(new IllegalStateException("couchdb down"))
                .when(reconciliationService).enqueueOrThrow(anyString(), anyString(), anyString(), anyString());

        // Cannot record the durable obligation → fail (never silent success).
        assertThrows(RuntimeException.class, () -> service.indexDocument(REPO_ID, pwc()));
    }

    @Test
    public void nonPwcDocumentPassesTheChokePointWithoutDeletion() throws Exception {
        Document normal = new Document();
        normal.setId("doc-1");
        normal.setName("normal.docx");
        normal.setPrivateWorkingCopy(Boolean.FALSE);
        when(contentService.getAttachment(anyString(), any())).thenReturn(null);

        try {
            service.indexDocument(REPO_ID, normal);
        } catch (RAGIndexingException expected) {
            // unsupported mime / no content — we only assert the PWC branch was skipped
        }
        verify(solrClient, never()).deleteByQuery(anyString(), anyString());
        verify(reconciliationService, never()).enqueueOrThrow(anyString(), anyString(), anyString(), anyString());
    }

    // ── verify + purge are repository-scoped ──────────────────────

    @Test
    public void isDocumentInRagIndexTrueWhenAnythingSurvives() throws Exception {
        stubVerify(3);
        assertTrue(service.isDocumentInRagIndex(REPO_ID, PWC_ID));
    }

    @Test
    public void isDocumentInRagIndexFalseWhenEmpty() throws Exception {
        stubVerify(0);
        assertFalse(service.isDocumentInRagIndex(REPO_ID, PWC_ID));
    }

    @Test
    public void isDocumentInRagIndexThrowsOnQueryFailureNeverReportsAbsent() throws Exception {
        when(solrClient.query(eq("nemaki"), any(SolrParams.class)))
                .thenThrow(new RuntimeException("connection refused"));
        assertThrows(RAGIndexingException.class, () -> service.isDocumentInRagIndex(REPO_ID, PWC_ID));
    }

    @Test
    public void purgeAndVerifyAreRepositoryScoped() throws Exception {
        stubVerify(0);
        service.purgeDocumentBlocks(REPO_ID, PWC_ID);
        service.isDocumentInRagIndex(REPO_ID, PWC_ID);

        // Both the delete and the verify query must constrain repository_id so a
        // same-id document in another repository is neither purged nor reported.
        org.mockito.ArgumentCaptor<String> del = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(solrClient).deleteByQuery(eq("nemaki"), del.capture());
        assertTrue(del.getValue().contains("repository_id:"), "purge delete must be repository-scoped");

        org.mockito.ArgumentCaptor<SolrParams> q = org.mockito.ArgumentCaptor.forClass(SolrParams.class);
        verify(solrClient).query(eq("nemaki"), q.capture());
        assertTrue(q.getValue().get("q").contains("repository_id:"), "verify query must be repository-scoped");
    }

    @Test
    public void purgeIgnoresRagDisabled() throws Exception {
        // purgeDocumentBlocks must run even when RAG is disabled (a security purge
        // cannot be a silent no-op that lets the block return on re-enablement).
        when(ragConfig.isEnabled()).thenReturn(false);
        service.purgeDocumentBlocks(REPO_ID, PWC_ID);
        verify(solrClient).deleteByQuery(eq("nemaki"), anyString());
    }
}
