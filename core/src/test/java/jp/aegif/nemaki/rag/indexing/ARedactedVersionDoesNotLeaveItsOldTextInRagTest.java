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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.SolrParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.TextExtractionService;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.rag.acl.ACLExpander;
import jp.aegif.nemaki.rag.chunking.ChunkingService;
import jp.aegif.nemaki.rag.chunking.TextChunk;
import jp.aegif.nemaki.rag.config.RAGConfig;
import jp.aegif.nemaki.rag.config.SolrClientProvider;
import jp.aegif.nemaki.rag.embedding.EmbeddingService;
import jp.aegif.nemaki.reconcile.SearchIndexAclReindexTask;
import jp.aegif.nemaki.reconcile.SearchIndexReconciliationService;

/**
 * Replacing a document's content with a version that has no indexable text takes the OLD version's
 * RAG block out of the index (3.4.1, external review C4).
 *
 * <p>{@code indexDocument} deleted the existing block inside {@code indexToSolr} — AFTER extraction,
 * chunking and embedding had all succeeded. So a replacement that cannot be indexed (a redacted
 * scan with no text layer, a swap to a type we do not extract) returned early and left the previous
 * version's chunks searchable: RAG and MCP kept answering with the text the new version no longer
 * contains, for everyone the {@code readers} filter admits. The redaction had no effect where it
 * matters most.
 *
 * <p>These locks drive {@code indexDocument} — the single entry {@code SolrUtil.triggerRAGIndexing}
 * and the RAG reindex both call — and watch what reaches Solr. The last one is the other direction:
 * when the content could not be READ, nothing is known about this version, so the block must be
 * LEFT alone rather than deleted on a transient store outage.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ARedactedVersionDoesNotLeaveItsOldTextInRagTest {

    private static final String REPO = "bedroom";
    // No Solr-special character in the id on purpose: the purge query sanitises the id (a hyphen
    // comes back as "\\-"), so an id with one would make the substring assertions below vacuous.
    private static final String DOC_ID = "redactedDoc";
    private static final String ATTACHMENT_ID = "att-1";
    private static final String INDEXABLE = "application/pdf";

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
        // isEnabled() is BOTH of these; without the second one every lock here fails with
        // SERVICE_DISABLED before reaching the code it is about (measured).
        when(embeddingService.isHealthy()).thenReturn(true);
        when(ragConfig.isMimeTypeSupported(INDEXABLE)).thenReturn(true);
        when(solrClientProvider.getClient()).thenReturn(solrClient);
        // The block of the PREVIOUS version is in the index, and gone once it is deleted.
        stubIndexHolds(1L, 0L);
    }

    /**
     * What the "is anything there?" query answers, in order. Every response is BUILT before the
     * {@code when(solrClient.query(...))} stubbing starts: stubbing a second mock inside an
     * unfinished stubbing makes Mockito throw {@code UnfinishedStubbingException}, which fails
     * every test in the class and not only this one (measured).
     */
    private void stubIndexHolds(long first, long... rest) throws Exception {
        QueryResponse head = response(first);
        QueryResponse[] later = new QueryResponse[rest.length];
        for (int i = 0; i < rest.length; i++) {
            later[i] = response(rest[i]);
        }
        when(solrClient.query(eq("nemaki"), any(SolrParams.class))).thenReturn(head, later);
    }

    private QueryResponse response(long numFound) {
        SolrDocumentList hits = new SolrDocumentList();
        hits.setNumFound(numFound);
        QueryResponse response = mock(QueryResponse.class);
        when(response.getResults()).thenReturn(hits);
        return response;
    }

    private Document document() {
        Document document = new Document();
        document.setId(DOC_ID);
        document.setName("contract.pdf");
        document.setAttachmentNodeId(ATTACHMENT_ID);
        return document;
    }

    /** The attachment as the store hands it back: {@code mimeType} is what the NEW version is. */
    private void storeHolds(String mimeType, String extracted, RuntimeException extractorFailure)
            throws Exception {
        AttachmentNode ref = mock(AttachmentNode.class);
        when(ref.getMimeType()).thenReturn(mimeType);
        when(contentService.getAttachmentRef(REPO, ATTACHMENT_ID)).thenReturn(ref);

        AttachmentNode body = mock(AttachmentNode.class);
        when(body.getMimeType()).thenReturn(mimeType);
        when(body.getName()).thenReturn("contract.pdf");
        when(body.getInputStream())
                .thenReturn(new ByteArrayInputStream("bytes".getBytes(StandardCharsets.UTF_8)));
        when(contentService.getAttachment(REPO, ATTACHMENT_ID)).thenReturn(body);

        if (extractorFailure != null) {
            when(textExtractionService.extractText(any(), anyString(), anyString()))
                    .thenThrow(extractorFailure);
        } else {
            when(textExtractionService.extractText(any(), anyString(), anyString()))
                    .thenReturn(extracted);
        }
    }

    /** The repository-scoped block selector the purge deletes by. */
    private String deletedQuery() throws Exception {
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(solrClient).deleteByQuery(eq("nemaki"), query.capture());
        return query.getValue();
    }

    @Test
    @DisplayName("a replacement whose type is not indexable takes the old version's chunks out")
    public void aTypeThatIsNotIndexableTakesTheOldChunksOut() throws Exception {
        storeHolds("image/tiff", null, null);

        RAGIndexingException refused = assertThrows(RAGIndexingException.class,
                () -> service.indexDocument(REPO, document()));

        assertEquals(RAGIndexingException.ErrorType.UNSUPPORTED_MIME_TYPE, refused.getErrorType());
        assertTrue(deletedQuery().contains(DOC_ID),
                "the old version's block was left in the index, so RAG and MCP keep answering with"
                        + " the text the current version does not contain");
        assertTrue(deletedQuery().contains(REPO) && deletedQuery().contains("_root_"),
                "the delete did not name this document's block scoped to its repository: "
                        + deletedQuery());
    }

    @Test
    @DisplayName("a replacement with no text at all takes the old version's chunks out")
    public void aVersionWithNoTextTakesTheOldChunksOut() throws Exception {
        storeHolds(INDEXABLE, "   ", null);

        RAGIndexingException refused = assertThrows(RAGIndexingException.class,
                () -> service.indexDocument(REPO, document()));

        assertEquals(RAGIndexingException.ErrorType.NO_CONTENT, refused.getErrorType());
        assertTrue(deletedQuery().contains(DOC_ID),
                "a redacted scan with no text layer left the previous version's chunks in place");
    }

    @Test
    @DisplayName("a replacement the extractor cannot read takes the old version's chunks out — that is deterministic for these bytes")
    public void bytesTheExtractorCannotReadTakeTheOldChunksOut() throws Exception {
        storeHolds(INDEXABLE, null, new RuntimeException("encrypted document"));

        assertThrows(RAGIndexingException.class, () -> service.indexDocument(REPO, document()));

        assertTrue(deletedQuery().contains(DOC_ID),
                "an encrypted or corrupt replacement left the previous version's chunks in place;"
                        + " the same bytes fail the same way every time, so nothing would remove them");
    }

    @Test
    @DisplayName("a replacement that chunks to nothing takes the old version's chunks out")
    public void aVersionThatChunksToNothingTakesTheOldChunksOut() throws Exception {
        storeHolds(INDEXABLE, "text the chunker drops", null);
        when(chunkingService.chunk(anyString())).thenReturn(List.<TextChunk>of());

        RAGIndexingException refused = assertThrows(RAGIndexingException.class,
                () -> service.indexDocument(REPO, document()));

        assertEquals(RAGIndexingException.ErrorType.NO_CONTENT, refused.getErrorType());
        assertTrue(deletedQuery().contains(DOC_ID), "the previous version's chunks were left in place");
    }

    @Test
    @DisplayName("content that could not be READ leaves the block alone — unknown is not \"has no text\"")
    public void contentThatCouldNotBeReadLeavesTheBlockAlone() throws Exception {
        AttachmentNode ref = mock(AttachmentNode.class);
        when(ref.getMimeType()).thenReturn(INDEXABLE);
        when(contentService.getAttachmentRef(REPO, ATTACHMENT_ID)).thenReturn(ref);
        when(contentService.getAttachment(REPO, ATTACHMENT_ID))
                .thenThrow(new RuntimeException("CouchDB is unreachable"));

        RAGIndexingException refused = assertThrows(RAGIndexingException.class,
                () -> service.indexDocument(REPO, document()));

        // The outcome must not be the one that authorises a purge: that is the whole distinction.
        assertEquals(RAGIndexingException.ErrorType.CONTENT_UNREADABLE, refused.getErrorType(),
                "a store outage was reported as \"this version has no text\", which is what"
                        + " authorises deleting the block");
        assertTrue(refused.isRetryable(), "a store outage was reported as a permanent outcome");
        verify(solrClient, never()).deleteByQuery(eq("nemaki"), anyString());
        verify(reconciliationService, never())
                .enqueueOrThrow(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a document that never had a block is not deleted — a full reindex would commit once per image otherwise")
    public void aDocumentWithNoBlockIsNotDeleted() throws Exception {
        stubIndexHolds(0L); // nothing is there
        storeHolds("image/tiff", null, null);

        assertThrows(RAGIndexingException.class, () -> service.indexDocument(REPO, document()));

        verify(solrClient, never()).deleteByQuery(eq("nemaki"), anyString());
        verify(solrClient, never()).commit(anyString());
    }

    @Test
    @DisplayName("a purge that cannot be confirmed becomes a durable task, labelled as stale content")
    public void aPurgeThatCannotBeConfirmedBecomesADurableTask() throws Exception {
        stubIndexHolds(1L, 1L); // present, and still present after the delete
        storeHolds("image/tiff", null, null);

        assertThrows(RAGIndexingException.class, () -> service.indexDocument(REPO, document()));

        verify(reconciliationService).enqueueOrThrow(eq(REPO), eq(DOC_ID),
                eq(SearchIndexAclReindexTask.Reason.STALE_CONTENT_PURGE_FAILURE),
                eq(SearchIndexAclReindexTask.Operation.RAG_PURGE));
    }
}
