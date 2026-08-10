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
package jp.aegif.nemaki.rag.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * A purged RAG block must not be resurrected by a concurrent ACL rebuild.
 *
 * <h2>The interleaving</h2>
 *
 * <p>{@code updateDocumentACL} cannot change readers in place: Solr's Block Join means any add
 * against the parent id replaces the whole block, so the only safe way is to read the parent and
 * every chunk, rebuild them with the new readers, and re-add the block. That is a
 * read-rebuild-write, and it is not instantaneous — a large document pages through hundreds of
 * chunks.
 *
 * <p>{@code purgeDocumentBlocks} deletes the block because a reader lost access to it. If that
 * delete lands between the rebuild's read and its write, the write puts the block back — assembled
 * from the snapshot taken before the delete. The purge has already verified the block is gone and
 * reports success, so the resurrection is silent. What comes back is exactly the
 * existence-and-similarity oracle the purge existed to remove.
 *
 * <p>{@code indexToSolr} and {@code updateDocumentACL} already serialize on a per-ragId stripe.
 * The purge did not, which left the one block mutation that matters for authorization outside the
 * serialization. This test drives the interleaving directly: the rebuild is held open after its
 * read, a purge is started on another thread, and the order the two reach Solr is recorded.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagPurgeVsAclRebuildRaceTest {

    private static final String REPO_ID = "bedroom";
    private static final String DOC_ID = "doc-race";
    private static final String RAG_ID = RAGIndexingServiceImpl.toRagId(DOC_ID);

    @Mock private RAGConfig ragConfig;
    @Mock private EmbeddingService embeddingService;
    @Mock private ChunkingService chunkingService;
    @Mock private TextExtractionService textExtractionService;
    @Mock private ContentService contentService;
    @Mock private ACLExpander aclExpander;
    @Mock private SolrClientProvider solrClientProvider;
    @Mock private SolrClient solrClient;

    private RAGIndexingServiceImpl service;

    /** Every Solr mutation in the order it arrived: "add" (block rebuild) or "delete" (purge). */
    private final List<String> solrOps = Collections.synchronizedList(new ArrayList<>());

    /** Opened once the rebuild has read the block and is about to assemble its write. */
    private final CountDownLatch rebuildHasRead = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws Exception {
        service = new RAGIndexingServiceImpl(ragConfig, embeddingService, chunkingService,
                textExtractionService, contentService, aclExpander, solrClientProvider);
        when(ragConfig.isEnabled()).thenReturn(true);
        when(embeddingService.isHealthy()).thenReturn(true); // isEnabled() is both together
        when(ragConfig.getAclChunkUpdateLimit()).thenReturn(10);
        when(solrClientProvider.getClient()).thenReturn(solrClient);

        when(solrClient.request(any(SolrRequest.class), anyString())).thenAnswer(invocation -> {
            SolrRequest<?> req = invocation.getArgument(0);
            if (req instanceof UpdateRequest u) {
                if (u.getDocuments() != null && !u.getDocuments().isEmpty()) {
                    solrOps.add("add");
                } else if (u.getDeleteQuery() != null && !u.getDeleteQuery().isEmpty()) {
                    solrOps.add("delete");
                }
            }
            return new NamedList<>();
        });

        when(solrClient.deleteByQuery(anyString(), anyString())).thenAnswer(invocation -> {
            solrOps.add("delete");
            return null;
        });
        when(solrClient.commit(anyString())).thenAnswer(invocation -> null);

        when(solrClient.query(eq("nemaki"), any(SolrParams.class))).thenAnswer(invocation -> {
            SolrParams params = invocation.getArgument(1);
            String q = params.get("q");
            SolrDocumentList results = new SolrDocumentList();
            if (q != null && q.startsWith("id:")) {
                results.add(parentDoc());
                results.setNumFound(1);
            } else if (q != null && q.startsWith("_root_:")) {
                results.add(chunkDoc());
                results.setNumFound(1);
                // The read is done and the write has not happened yet: this is the window the
                // purge has to slip into. Release it, then give it a generous head start — far
                // more than it needs to delete and commit if nothing is holding it back.
                rebuildHasRead.countDown();
                Thread.sleep(300);
            }
            QueryResponse response = mock(QueryResponse.class);
            when(response.getResults()).thenReturn(results);
            return response;
        });
    }

    private SolrDocument parentDoc() {
        SolrDocument doc = new SolrDocument();
        doc.setField("id", RAG_ID);
        doc.setField("doc_type", "document");
        doc.setField("repository_id", REPO_ID);
        doc.setField("object_id", DOC_ID);
        doc.setField("document_vector", Arrays.asList(0.1f, 0.2f));
        doc.addField("readers", "user:bedroom:alice");
        return doc;
    }

    private SolrDocument chunkDoc() {
        SolrDocument doc = new SolrDocument();
        doc.setField("id", DOC_ID + "_chunk_0");
        doc.setField("doc_type", "chunk");
        doc.setField("repository_id", REPO_ID);
        doc.setField("parent_document_id", DOC_ID);
        doc.setField("chunk_index", 0);
        doc.setField("chunk_text", "text");
        doc.setField("chunk_vector", Arrays.asList(0.4f, 0.5f));
        doc.addField("readers", "user:bedroom:alice");
        return doc;
    }

    @Test
    @DisplayName("ACL 再構築の read と write の間に purge が割り込めない (削除が最後に残る)")
    void aPurgeCannotLandInsideAnAclRebuild() throws Exception {
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        Thread rebuild = new Thread(() -> {
            try {
                service.updateDocumentACL(REPO_ID, DOC_ID, List.of("user:bedroom:bob"));
            } catch (Throwable t) {
                failures.add(t);
            }
        }, "acl-rebuild");

        Thread purge = new Thread(() -> {
            try {
                // Start only once the rebuild is provably past its read, so this test exercises
                // the dangerous interleaving rather than whichever order the scheduler happens
                // to pick.
                assertTrue(rebuildHasRead.await(5, TimeUnit.SECONDS), "rebuild never read");
                service.purgeDocumentBlocks(REPO_ID, DOC_ID);
            } catch (Throwable t) {
                failures.add(t);
            }
        }, "rag-purge");

        rebuild.start();
        purge.start();
        rebuild.join(15_000);
        purge.join(15_000);

        assertEquals(List.of(), failures, "neither operation should have thrown");
        assertTrue(solrOps.contains("add"), "the rebuild should have written a block: " + solrOps);
        assertTrue(solrOps.contains("delete"), "the purge should have deleted: " + solrOps);
        assertEquals("delete", solrOps.get(solrOps.size() - 1),
                "the delete must be the LAST mutation. Seeing add-after-delete means the purge"
                        + " landed inside the rebuild's read-write window and the block it removed"
                        + " was re-created from a pre-purge snapshot — a revoked reader's block"
                        + " back in the index, with the purge reporting success. Order seen: "
                        + solrOps);
    }
}
