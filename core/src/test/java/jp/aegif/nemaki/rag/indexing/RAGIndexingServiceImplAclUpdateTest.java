package jp.aegif.nemaki.rag.indexing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.TextExtractionService;
import jp.aegif.nemaki.rag.acl.ACLExpander;
import jp.aegif.nemaki.rag.chunking.ChunkingService;
import jp.aegif.nemaki.rag.config.RAGConfig;
import jp.aegif.nemaki.rag.config.SolrClientProvider;
import jp.aegif.nemaki.rag.embedding.EmbeddingService;

/**
 * Regression tests for {@link RAGIndexingServiceImpl#updateDocumentACL}.
 *
 * Background: the previous implementation used Solr atomic updates on the
 * Block Join parent, which replaces the whole block and silently deletes all
 * child chunk documents (observed live: 300 chunks -> 0 after an ACL change).
 * The fix rebuilds the entire block from stored fields (including vectors,
 * no re-embedding) with the new readers list.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class RAGIndexingServiceImplAclUpdateTest {

    private static final String REPO_ID = "test-repo";
    private static final String DOC_ID = "doc-123";
    private static final String RAG_ID = RAGIndexingServiceImpl.toRagId(DOC_ID);
    /** Deliberately different from the version the searcher-read parent carries (1234567L). */
    private static final long REALTIME_VERSION = 999_888_777L;

    @Mock private RAGConfig ragConfig;
    @Mock private EmbeddingService embeddingService;
    @Mock private ChunkingService chunkingService;
    @Mock private TextExtractionService textExtractionService;
    @Mock private ContentService contentService;
    @Mock private ACLExpander aclExpander;
    @Mock private SolrClientProvider solrClientProvider;
    @Mock private SolrClient solrClient;

    private RAGIndexingServiceImpl service;
    private List<UpdateRequest> capturedUpdates;

    @BeforeEach
    public void setUp() throws Exception {
        service = new RAGIndexingServiceImpl(ragConfig, embeddingService, chunkingService,
                textExtractionService, contentService, aclExpander, solrClientProvider);

        when(ragConfig.isEnabled()).thenReturn(true);
        when(embeddingService.isHealthy()).thenReturn(true);
        when(ragConfig.getAclChunkUpdateLimit()).thenReturn(2); // small page size to exercise paging
        when(solrClientProvider.getClient()).thenReturn(solrClient);

        // The block rebuild fences its write on a REALTIME GET of the parent's _version_ (a
        // searcher-read version lags the soft commit and would 409 spuriously). Absent means
        // "deleted while we were rebuilding" and the write is abandoned, so these tests have to
        // say the block is still there.
        SolrDocument version = new SolrDocument();
        version.setField("_version_", REALTIME_VERSION);
        when(solrClient.getById(anyString(), anyString(), any(SolrParams.class)))
                .thenReturn(version);

        // Capture all UpdateRequests flowing through SolrClient.request()
        // (UpdateRequest.process and SolrClient.commit both funnel through request())
        capturedUpdates = new ArrayList<>();
        when(solrClient.request(any(SolrRequest.class), anyString())).thenAnswer(invocation -> {
            SolrRequest<?> req = invocation.getArgument(0);
            if (req instanceof UpdateRequest) {
                capturedUpdates.add((UpdateRequest) req);
            }
            return new NamedList<>();
        });
    }

    private SolrDocument parentDoc(List<String> readers) {
        SolrDocument doc = new SolrDocument();
        doc.setField("id", RAG_ID);
        doc.setField("doc_type", "document");
        doc.setField("repository_id", REPO_ID);
        doc.setField("object_id", DOC_ID);
        doc.setField("name", "sample.docx");
        doc.setField("document_vector", Arrays.asList(0.1f, 0.2f, 0.3f));
        doc.setField("_version_", 1234567L);
        for (String r : readers) {
            doc.addField("readers", r);
        }
        return doc;
    }

    private SolrDocument chunkDoc(int index, List<String> readers) {
        SolrDocument doc = new SolrDocument();
        doc.setField("id", DOC_ID + "_chunk_" + index);
        doc.setField("doc_type", "chunk");
        doc.setField("repository_id", REPO_ID);
        doc.setField("parent_document_id", DOC_ID);
        doc.setField("chunk_index", index);
        doc.setField("chunk_text", "chunk text " + index);
        doc.setField("chunk_vector", Arrays.asList(0.4f, 0.5f, 0.6f));
        doc.setField("_version_", 7654321L);
        for (String r : readers) {
            doc.addField("readers", r);
        }
        return doc;
    }

    /** Stub SolrClient.query to serve the parent lookup and paged chunk queries. */
    private void stubQueries(SolrDocument parent, List<SolrDocument> chunks) throws Exception {
        when(solrClient.query(eq("nemaki"), any(SolrParams.class))).thenAnswer(invocation -> {
            SolrParams params = invocation.getArgument(1);
            String q = params.get("q");
            SolrDocumentList results = new SolrDocumentList();
            if (q != null && q.startsWith("id:")) {
                if (parent != null) {
                    results.add(parent);
                    results.setNumFound(1);
                }
            } else if (q != null && q.startsWith("_root_:")) {
                int start = params.getInt("start", 0);
                int rows = params.getInt("rows", 10);
                results.setNumFound(chunks.size());
                for (int i = start; i < Math.min(start + rows, chunks.size()); i++) {
                    results.add(chunks.get(i));
                }
            }
            QueryResponse response = mock(QueryResponse.class);
            when(response.getResults()).thenReturn(results);
            return response;
        });
    }

    private UpdateRequest findAddRequest() {
        return capturedUpdates.stream()
                .filter(u -> u.getDocuments() != null && !u.getDocuments().isEmpty())
                .findFirst().orElse(null);
    }

    private UpdateRequest findDeleteRequest() {
        return capturedUpdates.stream()
                .filter(u -> u.getDeleteQuery() != null && !u.getDeleteQuery().isEmpty())
                .findFirst().orElse(null);
    }

    @Test
    public void aclUpdateRebuildsBlockAndPreservesAllChunks() throws Exception {
        List<String> oldReaders = Arrays.asList("group:test-repo:old");
        List<String> newReaders = Arrays.asList("user:test-repo:alice", "group:test-repo:sales");
        // 3 chunks with page size 2 -> exercises the paging loop
        List<SolrDocument> chunks = Arrays.asList(
                chunkDoc(0, oldReaders), chunkDoc(1, oldReaders), chunkDoc(2, oldReaders));
        stubQueries(parentDoc(oldReaders), chunks);

        service.updateDocumentACL(REPO_ID, DOC_ID, newReaders);

        // Single-request block replacement: an explicit delete would open a
        // delete-without-add window that loses the document on a mid-operation
        // failure. Re-adding the root id cascades deletion of the old block.
        assertNull(findDeleteRequest(), "no separate delete request (single-add block replacement)");

        UpdateRequest add = findAddRequest();
        assertNotNull(add, "rebuilt block must be re-added");
        List<SolrInputDocument> docs = add.getDocuments();
        assertEquals(1, docs.size(), "exactly one parent document");

        SolrInputDocument parent = docs.get(0);
        assertEquals(RAG_ID, parent.getFieldValue("id"));
        assertEquals("document", parent.getFieldValue("doc_type"));
        assertNotNull(parent.getFieldValue("document_vector"), "document vector must be preserved");
        // The rebuild fences its write on the parent's version so a concurrent purge on another
        // replica cannot be undone. The version has to come from the REALTIME GET: the parent
        // above was read through a searcher, which lags the soft commit, and CASing on that stale
        // value fails writes that should have succeeded (this was tried once and reverted).
        assertEquals(REALTIME_VERSION, parent.getFieldValue("_version_"),
                "the block add must carry the realtime version as its compare-and-swap token,"
                        + " not the stale one copied off the searcher-read parent");
        assertEquals(newReaders, new ArrayList<>(parent.getFieldValues("readers")),
                "parent readers must be replaced");

        List<SolrInputDocument> children = parent.getChildDocuments();
        assertNotNull(children, "chunks must be re-added as child documents");
        assertEquals(3, children.size(), "ALL chunks must survive the ACL update (was 300->0 bug)");
        for (SolrInputDocument child : children) {
            assertEquals("chunk", child.getFieldValue("doc_type"));
            assertNotNull(child.getFieldValue("chunk_text"), "chunk text must be preserved");
            assertNotNull(child.getFieldValue("chunk_vector"), "chunk vector must be preserved (no re-embedding)");
            assertNull(child.getFieldValue("_version_"), "_version_ must not be copied");
            assertEquals(RAG_ID, child.getFieldValue("_root_"), "block linkage must be kept");
            assertEquals(newReaders, new ArrayList<>(child.getFieldValues("readers")),
                    "chunk readers must be replaced");
        }
    }

    @Test
    public void aclUpdateDoesNothingWhenDocumentNotIndexed() throws Exception {
        stubQueries(null, new ArrayList<>());

        service.updateDocumentACL(REPO_ID, DOC_ID, Arrays.asList("user:test-repo:alice"));

        assertNull(findDeleteRequest(), "no delete when document is not in the RAG index");
        assertNull(findAddRequest(), "no add when document is not in the RAG index");
    }

    @Test
    public void aclUpdateSkipsWhenRagDisabled() throws Exception {
        when(ragConfig.isEnabled()).thenReturn(false);

        service.updateDocumentACL(REPO_ID, DOC_ID, Arrays.asList("user:test-repo:alice"));

        verify(solrClientProvider, never()).getClient();
    }
}
