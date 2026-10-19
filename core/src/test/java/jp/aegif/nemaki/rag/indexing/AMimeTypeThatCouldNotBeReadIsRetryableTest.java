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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.SolrParams;
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
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.rag.acl.ACLExpander;
import jp.aegif.nemaki.rag.chunking.ChunkingService;
import jp.aegif.nemaki.rag.config.RAGConfig;
import jp.aegif.nemaki.rag.config.SolrClientProvider;
import jp.aegif.nemaki.rag.embedding.EmbeddingService;
import jp.aegif.nemaki.util.test.JavaSource;

/**
 * A MIME type that could not be READ is a retryable failure, not "this type is not supported"
 * (R185, 3.4.1).
 *
 * <p>{@code getMimeType} answered {@code null} for three different things — the document has no
 * attachment, the store threw, the store handed back nothing — and the gate turned every
 * {@code null} into {@link RAGIndexingException#unsupportedMimeType}: NOT retryable, and the RAG
 * reindex classifies that by message as SKIPPED. A full reindex CLEARS THE INDEX FIRST, so a
 * CouchDB outage during one made every document "skipped", the run reported {@code completed},
 * and the index stayed empty with nothing queued to try again.
 *
 * <p>These locks drive {@code indexDocument} — the single entry both
 * {@code SolrUtil.triggerRAGIndexing} and the RAG reindex call. Three of them are the other
 * direction (an unindexable type, an attachment that records no type, a document with no
 * attachment at all are all still skipped and not retried for ever), because over-refusal here
 * would turn every image in a repository into a permanent reindex error.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class AMimeTypeThatCouldNotBeReadIsRetryableTest {

    private static final String REPO = "bedroom";
    private static final String DOC_ID = "doc-1";
    private static final String ATTACHMENT_ID = "attachment-1";
    private static final String INDEXABLE = "application/pdf";

    private static final String MAINTENANCE_SOURCE =
            "src/main/java/jp/aegif/nemaki/rag/maintenance/RAGIndexMaintenanceServiceImpl.java";

    @Mock private RAGConfig ragConfig;
    @Mock private EmbeddingService embeddingService;
    @Mock private ChunkingService chunkingService;
    @Mock private TextExtractionService textExtractionService;
    @Mock private ContentService contentService;
    @Mock private ACLExpander aclExpander;
    @Mock private SolrClientProvider solrClientProvider;
    @Mock private SolrClient solrClient;
    @Mock private jp.aegif.nemaki.reconcile.SearchIndexReconciliationService reconciliationService;

    private RAGIndexingServiceImpl service;

    @BeforeEach
    public void setUp() throws Exception {
        service = new RAGIndexingServiceImpl(ragConfig, embeddingService, chunkingService,
                textExtractionService, contentService, aclExpander, solrClientProvider);
        when(ragConfig.isEnabled()).thenReturn(true);
        // isEnabled() is both of these; without the second every lock here would fail with
        // SERVICE_DISABLED before reaching the code it is about.
        when(embeddingService.isHealthy()).thenReturn(true);
        when(ragConfig.isMimeTypeSupported(INDEXABLE)).thenReturn(true);
        when(solrClientProvider.getClient()).thenReturn(solrClient);
        service.setReconciliationService(reconciliationService);
        // The block of an earlier version is in the index, so a purge would have something to
        // take out: "nothing was deleted" below is then about the decision, not about an empty
        // index. The SECOND answer is 0, which is how the purge confirms absence — without it
        // the purge records a durable task instead, and the exception this class is about is
        // replaced by whatever that path answers (measured: ErrorType.UNKNOWN).
        QueryResponse holdsOne = response(1L);
        QueryResponse holdsNone = response(0L);
        when(solrClient.query(eq("nemaki"), any(SolrParams.class))).thenReturn(holdsOne, holdsNone);
    }

    /** Built BEFORE the stubbing that returns it: a mock stubbed inside an unfinished stubbing
     * makes Mockito throw UnfinishedStubbing, which fails every test in the class. */
    private QueryResponse response(long numFound) {
        SolrDocumentList hits = new SolrDocumentList();
        hits.setNumFound(numFound);
        QueryResponse response = mock(QueryResponse.class);
        when(response.getResults()).thenReturn(hits);
        return response;
    }

    private Document document(String attachmentId) {
        Document document = new Document();
        document.setId(DOC_ID);
        document.setName("contract.pdf");
        document.setAttachmentNodeId(attachmentId);
        return document;
    }

    /** The attachment ref as the store hands it back, with the type it records. */
    private void storeHoldsTypeOf(String mimeType) {
        AttachmentNode ref = mock(AttachmentNode.class);
        when(ref.getMimeType()).thenReturn(mimeType);
        when(contentService.getAttachmentRef(REPO, ATTACHMENT_ID)).thenReturn(ref);
    }

    @Test
    @DisplayName("the store throwing while the type is read is a RETRYABLE failure")
    public void aStoreThatThrowsIsRetryable() {
        when(contentService.getAttachmentRef(REPO, ATTACHMENT_ID))
                .thenThrow(new RuntimeException("couchdb is not answering"));

        RAGIndexingException refused = assertThrows(RAGIndexingException.class,
                () -> service.indexDocument(REPO, document(ATTACHMENT_ID)),
                "the indexing did not fail at all for a type it could not read");

        assertEquals(RAGIndexingException.ErrorType.CONTENT_UNREADABLE, refused.getErrorType(),
                "a store outage is reported as a fact about the document's type. The reindex "
                        + "classifies that as SKIPPED, and a full reindex has already cleared the "
                        + "index, so it reports completion over an empty one");
        assertTrue(refused.isRetryable(),
                "the failure is not retryable, so nothing will ever try this document again");
    }

    @Test
    @DisplayName("an attachment the store ANSWERS is not there is skipped, not retried for ever")
    public void anAttachmentTheStoreSaysIsNotThereIsSkipped() {
        // The DAO returns null only for not-found and throws for anything else, and the read has
        // already retried twice for async visibility — so this is a PERMANENT state, and calling
        // it retryable would leave every full reindex of this repository in
        // completed_with_errors with no way to accept it (batch-21 review, subagent P3-3). The
        // first version of this lock asserted the opposite.
        when(contentService.getAttachmentRef(REPO, ATTACHMENT_ID)).thenReturn(null);

        RAGIndexingException refused = assertThrows(RAGIndexingException.class,
                () -> service.indexDocument(REPO, document(ATTACHMENT_ID)));

        assertEquals(RAGIndexingException.ErrorType.UNSUPPORTED_MIME_TYPE, refused.getErrorType(),
                "an attachment row the store says is not there is reported as a failure to read, "
                        + "so the reindex can never finish cleanly");
        assertFalse(refused.isRetryable(), "a missing attachment row is reported as transient");
    }

    @Test
    @DisplayName("a BLANK attachment id is «no attachment», not a failed read")
    public void aBlankAttachmentIdIsNoAttachment() {
        // getAttachmentRef answers null for a blank id WITHOUT asking the store, so reading its
        // answer as 「the store handed back nothing」 reports 「not asked」 as 「could not read」
        // (batch-21 review, Codex P2).
        RAGIndexingException refused = assertThrows(RAGIndexingException.class,
                () -> service.indexDocument(REPO, document("   ")));

        assertEquals(RAGIndexingException.ErrorType.UNSUPPORTED_MIME_TYPE, refused.getErrorType(),
                "a blank attachment id became a retryable read failure, so such a document is a "
                        + "permanent error in every reindex");
        assertFalse(refused.isRetryable(), "there is nothing to read, so there is nothing to retry");
        // THE STORE IS NOT ASKED. Without this the lock protects nothing: the store's own guard
        // answers null for a blank id, and this reader now treats a null answer as ABSENT too, so
        // dropping the guard gives the same outcome by a different route (measured — control LM5
        // DID NOT FIRE until this line). «Not asked» and «asked, and there is nothing» are
        // different states, and this is where the difference is visible.
        verify(contentService, never()).getAttachmentRef(anyString(), anyString());
    }

    /**
     * What this does NOT prove: that the UNREADABLE arm is what keeps the block. With the read
     * collapsed to «absent» the type is null and the purge is held by {@code mimeType != null}
     * (C4's own arm), so nothing is deleted either way — measured, control LF5 DID NOT FIRE on
     * this lock. It falls when the read ANSWERS with a guessed type (control LL5), which is the
     * shape that would authorise a delete on a store outage.
     */
    @Test
    @DisplayName("and a type that could not be read does not take the old block out")
    public void aTypeThatCouldNotBeReadLeavesTheBlockAlone() throws Exception {
        when(contentService.getAttachmentRef(REPO, ATTACHMENT_ID))
                .thenThrow(new RuntimeException("couchdb is not answering"));

        assertThrows(RAGIndexingException.class,
                () -> service.indexDocument(REPO, document(ATTACHMENT_ID)));

        verify(solrClient, never()).deleteByQuery(anyString(), anyString());
    }

    @Test
    @DisplayName("a type we do not extract is still skipped, and still takes the old block out")
    public void aTypeWeDoNotExtractIsStillSkipped() throws Exception {
        storeHoldsTypeOf("image/tiff");

        RAGIndexingException refused = assertThrows(RAGIndexingException.class,
                () -> service.indexDocument(REPO, document(ATTACHMENT_ID)));

        assertEquals(RAGIndexingException.ErrorType.UNSUPPORTED_MIME_TYPE, refused.getErrorType(),
                "a type the extractor does not handle became a retryable failure, so every image "
                        + "in the repository is now a permanent reindex error");
        assertFalse(refused.isRetryable(), "re-reading an image's type cannot change the answer");
        // C4's protection, unchanged: the version that is there NOW has no indexable text, so
        // whatever an earlier version left in the index is wrong.
        verify(solrClient).deleteByQuery(eq("nemaki"), anyString());
    }

    @Test
    @DisplayName("an attachment that records no type at all is skipped, not retried for ever")
    public void anAttachmentWithNoRecordedTypeIsStillSkipped() throws Exception {
        storeHoldsTypeOf(null);

        RAGIndexingException refused = assertThrows(RAGIndexingException.class,
                () -> service.indexDocument(REPO, document(ATTACHMENT_ID)));

        assertEquals(RAGIndexingException.ErrorType.UNSUPPORTED_MIME_TYPE, refused.getErrorType(),
                "the store answered and the attachment records no type — re-reading cannot change "
                        + "that, so it is not a retryable failure");
        assertFalse(refused.isRetryable(), "a missing type field is reported as transient");
        // Deliberately unchanged by R185: nothing is purged for this input, as before. Widening
        // what authorises a delete is a different question from ending the collapse.
        verify(solrClient, never()).deleteByQuery(anyString(), anyString());
    }

    @Test
    @DisplayName("a document with no attachment at all is skipped")
    public void aDocumentWithNoAttachmentIsStillSkipped() {
        RAGIndexingException refused = assertThrows(RAGIndexingException.class,
                () -> service.indexDocument(REPO, document(null)));

        assertEquals(RAGIndexingException.ErrorType.UNSUPPORTED_MIME_TYPE, refused.getErrorType(),
                "a document with no content stream became a retryable failure");
        assertFalse(refused.isRetryable(), "there is nothing to read, so there is nothing to retry");
    }

    @Test
    @DisplayName("the reindex's skip test cannot match any RETRYABLE failure's message")
    public void theReindexSkipTestCannotMatchARetryableFailure() throws Exception {
        // The seam. The reindex decides skipped-vs-error by looking for substrings in the
        // exception's MESSAGE, so the whole protection above is only worth what those substrings
        // are: a retryable failure whose message happened to contain one of them would be counted
        // as a skip again, and the run would report completion. Read the substrings from the
        // production source rather than restating them here, and build the exceptions for real.
        String batch = JavaSource.withoutComments(
                JavaSource.methodBody(JavaSource.read(MAINTENANCE_SOURCE), "private void processBatch"));
        Matcher tested = Pattern.compile("contains\\(\"([^\"]+)\"\\)").matcher(batch);
        List<String> skipSubstrings = new ArrayList<>();
        while (tested.find()) {
            skipSubstrings.add(tested.group(1));
        }
        assertTrue(skipSubstrings.size() >= 2,
                "processBatch no longer tests the exception's message for the skip classification, "
                        + "so this lock is not reading the decision it is about: " + skipSubstrings);

        List<String> retryable = new ArrayList<>();
        for (Method factory : RAGIndexingException.class.getDeclaredMethods()) {
            if (!Modifier.isStatic(factory.getModifiers())
                    || factory.getReturnType() != RAGIndexingException.class) {
                continue;
            }
            Object[] arguments = new Object[factory.getParameterCount()];
            for (int i = 0; i < arguments.length; i++) {
                arguments[i] = factory.getParameterTypes()[i] == String.class ? DOC_ID : null;
            }
            if (!java.lang.reflect.Modifier.isPublic(factory.getModifiers())) {
                // Not swept, and said so: invoking it would fail with IllegalAccessException —
                // an exception, not this lock's own assertion, which the control runner reads as
                // 「the harness broke」 (batch-21 review, subagent P3-5).
                throw new jp.aegif.nemaki.util.test.HarnessBroken(
                        "RAGIndexingException." + factory.getName() + " is a non-public factory, "
                                + "so this lock cannot build it and cannot say whether its message "
                                + "is one the reindex reads as «skipped»");
            }
            RAGIndexingException made = (RAGIndexingException) factory.invoke(null, arguments);
            if (!made.isRetryable()) {
                continue;
            }
            retryable.add(factory.getName());
            for (String skip : skipSubstrings) {
                assertFalse(made.getMessage().contains(skip),
                        "RAGIndexingException." + factory.getName() + " is retryable and its "
                                + "message contains 「" + skip + "」, which the reindex reads as "
                                + "«skipped». The run will report completion over the documents "
                                + "it could not index: " + made.getMessage());
            }
        }
        assertTrue(retryable.contains("contentUnreadable"),
                "contentUnreadable is not among the retryable factories, so the type that R185 "
                        + "routes an unreadable MIME read to is itself not retried: " + retryable);
    }
}
