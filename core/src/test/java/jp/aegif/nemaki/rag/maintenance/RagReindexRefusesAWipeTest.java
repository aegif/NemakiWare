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
package jp.aegif.nemaki.rag.maintenance;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.RAGIndexMaintenanceService.DocumentReindex;
import jp.aegif.nemaki.businesslogic.RAGIndexMaintenanceService.RAGReindexStatus;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.rag.config.RAGConfig;
import jp.aegif.nemaki.rag.indexing.RAGIndexingException;
import jp.aegif.nemaki.rag.indexing.RAGIndexingException.ErrorType;
import jp.aegif.nemaki.rag.indexing.RAGIndexingService;
import jp.aegif.nemaki.util.test.JavaSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import jp.aegif.nemaki.util.test.HarnessBroken;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The RAG reindex must not clear an index it is about to fail to rebuild.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The CMIS reindex has refused this since the incident it records: a CouchDB view whose map
 * function fails answers <b>HTTP 200 with zero rows</b>, so the walk finds nothing, no exception
 * is thrown, and a full reindex of a 164-object repository left one document in Solr and
 * reported {@code errorCount=0, status=completed}.
 *
 * <p>The RAG path had no guard at all — and {@code CLAUDE.md} tells operators to run BOTH as a
 * required upgrade step before going live. Its walk also caught per-folder failures INSIDE the
 * recursion and carried on, while {@code addError} appended to a capped list without touching
 * {@code errorCount}, so a run that dropped whole sub-trees still summarised as "errors: 0".
 *
 * <p>Refusing an unanswered view (2026-08-28) narrows the window and does not close this one:
 * there is no error to see. The yardstick is what the index ALREADY holds.
 */
class RagReindexRefusesAWipeTest {

    private static final String SOURCE =
            "src/main/java/jp/aegif/nemaki/rag/maintenance/RAGIndexMaintenanceServiceImpl.java";

    @Test
    @DisplayName("the guard is taken BEFORE the index is cleared, not after")
    void theGuardComesBeforeTheClear() throws Exception {
        // Order is the whole protection. A guard evaluated after clearRAGIndex would report the
        // wipe rather than prevent it, and nothing in the type system says which comes first.
        String body = JavaSource.methodBody(JavaSource.read(SOURCE),
                "private void runFullRAGReindex");
        String code = JavaSource.withoutComments(body);

        int guard = code.indexOf("countIndexedRagDocuments");
        int clear = code.indexOf("clearRAGIndex");
        assertTrue(code.contains("if (!clearRAGIndex("),
                "the clear's return value is discarded again: it catches its own failures and "
                        + "answers false, and carrying on rebuilds on top of entries for "
                        + "documents that are gone — then reports the run completed: " + code);

        assertTrue(guard >= 0, "the enumeration guard is gone, so a wipe is unguarded again");
        HarnessBroken.unless(clear >= 0, "fixture check: the clear is no longer in this method");
        assertTrue(guard < clear,
                "the index is cleared before the walk is checked against it, so the guard "
                        + "reports the wipe instead of preventing it");
    }

    @Test
    @DisplayName("a count that could not be taken does not read as an empty index")
    void anUncountableIndexIsNotAnEmptyOne() throws Exception {
        // -1, not 0. Zero is the value that lets the guard pass, and "we could not count" is
        // precisely the case the guard exists for. The CMIS sibling states the same rule.
        String body = JavaSource.methodBody(JavaSource.read(SOURCE),
                "private long countIndexedRagDocuments");
        String code = JavaSource.withoutComments(body);

        assertTrue(code.contains("return -1;"),
                "a failed count no longer answers 'unknown', so it answers 'the index is "
                        + "empty' — and an empty index never triggers the guard: " + code);
        // AND it counts the same unit the walk counts. The first version queried
        // "(doc_type:document OR doc_type:chunk)" while the walk counts DOCUMENTS, so one
        // ordinary document with 20 chunks looked like a 20-fold loss and the guard refused a
        // healthy repository — an outage where a protection was meant. The assertions above
        // are all satisfied by either query, which is why this one names the unit.
        assertFalse(code.contains("doc_type:chunk"),
                "the guard counts chunks while the walk counts documents, so ordinary long "
                        + "documents trip the loss threshold: " + code);
        assertTrue(code.contains("doc_type:document"),
                "the guard no longer counts indexed documents at all: " + code);
        String reindex = JavaSource.withoutComments(JavaSource.methodBody(
                JavaSource.read(SOURCE), "private void runFullRAGReindex"));
        assertTrue(reindex.contains("alreadyIndexed < 0"),
                "the reindex does not act on 'unknown', so the -1 above buys nothing");
    }

    @Test
    @DisplayName("a SHORT listing is a reindex failure here too, not a smaller folder")
    void aShortListingIsCountedByTheRagWalk() throws Exception {
        // The CMIS walk got this rule and the RAG walk is the sibling that keeps missing the
        // same corrections one round later — this time both were changed the same day, and
        // this pins the RAG side so reverting one arm alone goes red.
        String walk = JavaSource.withoutComments(JavaSource.methodBody(
                JavaSource.read(SOURCE), "private void reindexFolderRecursive"));
        assertTrue(walk.contains("lastUnreadableChildCount"),
                "the RAG walk treats a decode-shortened listing as a complete folder again, so "
                        + "documents the repository could not decode vanish from the index "
                        + "with the run still 'completed': " + walk.substring(0, 200));
        // The EFFECT, not just the wiring: keeping the read while deleting the two lines that
        // count it stays green under the assertion above. Round 18's lesson, applied to the
        // assertion that was written the same day as the lesson.
        int guard = walk.indexOf("lastUnreadableChildCount");
        String afterGuard = walk.substring(guard, Math.min(walk.length(), guard + 400));
        assertTrue(afterGuard.contains("setErrorCount"),
                "the count is read and never added to errorCount, so the run still ends "
                        + "'completed': " + afterGuard);
        assertTrue(afterGuard.contains("addError"),
                "the count raises the number but leaves no message saying which folder: "
                        + afterGuard);
    }

    @Test
    @DisplayName("a walk that skipped folders is not reported as a completed reindex")
    void aWalkThatSkippedFoldersIsNotCompleted() throws Exception {
        // errorCount, not the capped errors list: the summary line and the final status both
        // read errorCount, and the per-folder catch never touched it.
        String walk = JavaSource.withoutComments(JavaSource.methodBody(
                JavaSource.read(SOURCE), "private void reindexFolderRecursive"));
        assertTrue(walk.contains("setErrorCount"),
                "a folder the walk could not enumerate is still invisible to the summary: "
                        + walk);

        String settle = JavaSource.withoutComments(JavaSource.methodBody(
                JavaSource.read(SOURCE), "private void settleStatus"));
        assertTrue(settle.contains("completed_with_errors"),
                "a run that dropped folders is still called completed: " + settle);
        assertFalse(JavaSource.withoutComments(JavaSource.methodBody(
                        JavaSource.read(SOURCE), "private void runFullRAGReindex"))
                .contains("setStatus(\"completed\")"),
                "the reindex sets 'completed' directly again, bypassing the check that a walk "
                        + "which dropped folders is not a completed reindex");
    }

    @Test
    @DisplayName("a single-document reindex keeps WHY it did not index, and both doors report it (R206)")
    void aSingleDocumentReindexKeepsWhy() throws Exception {
        // 「could not read」 must not be reported with the same value as 「this version has no
        // indexable text」. The published way to clear a redacted version's RAG blocks is this
        // endpoint, so an operator who reads 「may be a folder or unsupported type」 for a CouchDB
        // outage stops — and the text the redaction was meant to remove stays searchable.
        String impl = JavaSource.withoutLiteralText(JavaSource.read(SOURCE));
        String outcome = JavaSource.methodBody(impl, "public DocumentReindex reindexDocumentOutcome");
        assertTrue(outcome.contains("DocumentReindex.FAILED") && outcome.contains("answerFor("),
                "the single-document reindex does not classify the refusal it caught: " + outcome);

        // BY TYPE, as an allow-list. isRetryable() is not a classification: the one- and two-argument
        // constructors of RAGIndexingException are UNKNOWN with retryable=false, and the purge paths
        // throw exactly those — so 「could not purge the old block」 came out as 「this version has no
        // indexable text。Retrying will not change that」, which is this batch's own harm statement
        // (batch-22..24 review: both reviewers, P1).
        // The classification itself is measured by RUNNING it, in
        // everyErrorTypeGetsItsOwnAnswer below — every source-level form of this check was
        // bypassable, and two reviewers found two different bypasses each round: a branch BEFORE
        // the switch (Codex), and one arm with TWO answers —
        // 「default: return refused.isRetryable() ? FAILED : NOT_INDEXABLE;」 — which restores the
        // very harm this lock exists for while reading as a one-answer arm (the subagent, measured).
        int read = outcome.indexOf("getContent(");
        int notADocument = outcome.indexOf("NOT_A_DOCUMENT");
        assertTrue(read > 0 && notADocument > read,
                "the object is called 「not a document」 before it has been read, so a read that "
                        + "could not be made is reported as a folder: " + outcome);

        // BOTH DOORS read the outcome, not the boolean: the collapse was in the service, and the
        // message that misled the operator was in the resources.
        for (String door : new String[] {
                "src/main/java/jp/aegif/nemaki/api/v1/resource/SearchEngineResource.java",
                "src/main/java/jp/aegif/nemaki/rest/SolrResource.java" }) {
            String body = JavaSource.withoutLiteralText(JavaSource.read(door));
            assertTrue(body.contains("reindexDocumentOutcome("), door + " still asks the boolean, so "
                    + "whatever the service distinguishes is thrown away at the door");
        }
        // And the sentence that told the operator to stop is gone from the api/v1 ANSWER. Comments
        // out, literals KEPT: the message is a string literal, and this file's own note about the
        // history mentions the sentence (measured — it failed on that comment first).
        assertFalse(JavaSource.withoutComments(JavaSource.read(
                        "src/main/java/jp/aegif/nemaki/api/v1/resource/SearchEngineResource.java"))
                        .contains("may be a folder or unsupported type"),
                "the door still answers 「may be a folder or unsupported type」 for every failure");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(RAGIndexingException.ErrorType.class)
    @DisplayName("every error type gets its own answer — RUN, not read (R206)")
    void everyErrorTypeGetsItsOwnAnswer(RAGIndexingException.ErrorType type) throws Exception {
        // THROUGH THE PRODUCT'S OWN FACTORY, so the (type, retryable) pair is one production can
        // produce. The first version built `new RAGIndexingException(msg, type, type != UNKNOWN)`,
        // which is the OPPOSITE of the factory for five of the nine types — and under that fixture
        // 「case SERVICE_DISABLED: return refused.isRetryable() ? DISABLED : NOT_INDEXABLE;」 passed
        // every parameter while production answered 「this version has no indexable text」 for a
        // repository with RAG switched off (batch-26 review, subagent P2-2, measured).
        RAGIndexingException refused = asProductionThrowsIt(type);
        assertEquals(type, refused.getErrorType(),
                "the factory for " + type + " does not carry that type, so this case measures another");
        // RUN the classification. Every source-level form of this check was bypassable, and the two
        // reviewers found a different bypass each: a branch BEFORE the switch, and one arm with TWO
        // answers — 「default: return refused.isRetryable() ? FAILED : NOT_INDEXABLE;」 — which
        // restores the harm in one line while reading as a one-answer arm (batch-25 review: both
        // reviewers, P2, each measured). Driving it answers the question the source could not:
        // what does this type actually come out as.
        ContentService contentService = mock(ContentService.class);
        RAGIndexingService indexing = mock(RAGIndexingService.class);
        RAGConfig config = mock(RAGConfig.class);
        when(config.isEnabled()).thenReturn(true);
        when(indexing.isEnabled()).thenReturn(true);
        when(contentService.getContent("bedroom", "doc-1")).thenReturn(new Document());
        doThrow(refused).when(indexing).indexDocument(eq("bedroom"), any(Document.class));

        RAGIndexMaintenanceServiceImpl service = new RAGIndexMaintenanceServiceImpl(
                contentService, indexing, config, mock(RepositoryInfoMap.class),
                mock(jp.aegif.nemaki.rag.config.SolrClientProvider.class));

        DocumentReindex answer = service.reindexDocumentOutcome("bedroom", "doc-1");

        // 「This version has no indexable text。Retrying will not change that」 belongs to the two
        // types that SAY something about the text. CONTENT_UNREADABLE is 「the bytes could not be
        // obtained」 and UNKNOWN is what the purge paths throw: answering 「not indexable」 for
        // either tells the operator clearing a redacted version's blocks to stop, and the
        // pre-redaction text stays searchable with no obligation recorded.
        DocumentReindex expected;
        switch (type) {
            case UNSUPPORTED_MIME_TYPE:
            case NO_CONTENT:
                expected = DocumentReindex.NOT_INDEXABLE;
                break;
            case SERVICE_DISABLED:
                expected = DocumentReindex.DISABLED;
                break;
            default:
                expected = DocumentReindex.FAILED;
                break;
        }
        assertEquals(expected, answer, "a refusal carrying " + type + " is answered with "
                + answer + ", not " + expected);
    }

    /**
     * The exception production throws for this type, built by the product's own factory.
     *
     * <p>{@code UNKNOWN} has no factory: it is what the one- and two-argument constructors set, and
     * the purge paths use those. Every other type is reached through the factory so its
     * {@code retryable} flag is the product's, not the test's.
     */
    private static RAGIndexingException asProductionThrowsIt(RAGIndexingException.ErrorType type) {
        Throwable cause = new java.io.IOException("the store did not answer");
        switch (type) {
            case SERVICE_DISABLED:
                return RAGIndexingException.serviceDisabled("RAG is off for this repository");
            case UNSUPPORTED_MIME_TYPE:
                return RAGIndexingException.unsupportedMimeType("application/octet-stream");
            case TEXT_EXTRACTION_FAILED:
                return RAGIndexingException.textExtractionFailed("doc-1", cause);
            case NO_CONTENT:
                return RAGIndexingException.noContent("no text could be extracted");
            case CONTENT_UNREADABLE:
                return RAGIndexingException.contentUnreadable("doc-1", cause);
            case EMBEDDING_FAILED:
                return RAGIndexingException.embeddingFailed("doc-1", cause);
            case SOLR_ERROR:
                return RAGIndexingException.solrError("doc-1", cause);
            case ACL_ERROR:
                return RAGIndexingException.aclError("doc-1", cause);
            case UNKNOWN:
                return new RAGIndexingException("the RAG block could not be purged");
            default:
                // Not an assertion: a type with no case here means this sweep is not measuring it.
                throw new jp.aegif.nemaki.util.test.HarnessBroken("no production shape for " + type
                        + ", so the sweep over ErrorType is not covering every value");
        }
    }

    @Test
    @DisplayName("a read that could not be made is FAILED, not 「not indexable」 — the other two producers (R206)")
    void theOtherProducersOfFailedAreAlsoDriven() throws Exception {
        // reindexDocumentOutcome answers FAILED in three places: the getContent catch, answerFor, and
        // the generic catch around indexDocument. The sweep above drives only answerFor, so changing
        // EITHER catch to NOT_INDEXABLE left all nine parameters green — and the operator clearing a
        // redacted version's blocks is told 「retrying will not change that」 when CouchDB is simply
        // unreachable (batch-26 review, subagent P2-3, measured).
        ContentService couldNotRead = mock(ContentService.class);
        when(couldNotRead.getContent("bedroom", "doc-1"))
                .thenThrow(new IllegalStateException("CouchDB did not answer"));
        assertEquals(DocumentReindex.FAILED, enabledServiceWith(couldNotRead, mock(RAGIndexingService.class))
                        .reindexDocumentOutcome("bedroom", "doc-1"),
                "the object could not be READ and the answer says something about its content");

        ContentService readable = mock(ContentService.class);
        when(readable.getContent("bedroom", "doc-1")).thenReturn(new Document());
        RAGIndexingService threwSomethingElse = mock(RAGIndexingService.class);
        when(threwSomethingElse.isEnabled()).thenReturn(true);
        doThrow(new RuntimeException("the embedding service closed the connection"))
                .when(threwSomethingElse).indexDocument(eq("bedroom"), any(Document.class));
        assertEquals(DocumentReindex.FAILED, enabledServiceWith(readable, threwSomethingElse)
                        .reindexDocumentOutcome("bedroom", "doc-1"),
                "an exception that is not a RAGIndexingException is answered as a statement about "
                        + "this version's text");
    }

    /** The maintenance service with RAG switched on and these two collaborators. */
    private static RAGIndexMaintenanceServiceImpl enabledServiceWith(
            ContentService contentService, RAGIndexingService indexing) {
        RAGConfig config = mock(RAGConfig.class);
        when(config.isEnabled()).thenReturn(true);
        when(indexing.isEnabled()).thenReturn(true);
        return new RAGIndexMaintenanceServiceImpl(contentService, indexing, config,
                mock(RepositoryInfoMap.class), mock(jp.aegif.nemaki.rag.config.SolrClientProvider.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(DocumentReindex.class)
    @DisplayName("the api/v1 door answers 503 for an attempt that may succeed later — RUN (R206)")
    void theDoorAnswers503ForAnAttemptThatMaySucceedLater(DocumentReindex outcome) throws Exception {
        // RELEASE_NOTES 3.4.1 tells automated callers to treat 503 on this endpoint as 「retry」, and
        // the source form of this lock was bypassable two ways (a branch before the switch; the
        // last arm's text running to the end of the METHOD, so a catch that throws 503 satisfied
        // it). Driving the door answers what the caller actually receives.
        jp.aegif.nemaki.businesslogic.RAGIndexMaintenanceService service =
                mock(jp.aegif.nemaki.businesslogic.RAGIndexMaintenanceService.class);
        when(service.isRAGEnabled()).thenReturn(true);
        when(service.reindexDocumentOutcome("bedroom", "doc-1")).thenReturn(outcome);

        jp.aegif.nemaki.api.v1.resource.SearchEngineResource door =
                new jp.aegif.nemaki.api.v1.resource.SearchEngineResource();
        org.apache.chemistry.opencmis.commons.server.CallContext callContext =
                mock(org.apache.chemistry.opencmis.commons.server.CallContext.class);
        when(callContext.get(jp.aegif.nemaki.util.constant.CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        jakarta.servlet.http.HttpServletRequest request =
                mock(jakarta.servlet.http.HttpServletRequest.class);
        when(request.getAttribute("CallContext")).thenReturn(callContext);
        inject(door, "ragIndexMaintenanceService", service);
        inject(door, "httpRequest", request);

        if (outcome == DocumentReindex.FAILED || outcome == DocumentReindex.DISABLED) {
            jp.aegif.nemaki.api.v1.exception.ApiException refused =
                    assertThrows(jp.aegif.nemaki.api.v1.exception.ApiException.class,
                            () -> door.reindexDocumentRAG("bedroom", "doc-1"),
                            "the door answers " + outcome + " without a status an automated caller "
                                    + "retries, so the attempt that MAY succeed later is reported as "
                                    + "a settled one");
            assertEquals(503, refused.getStatus(), "the door answers " + outcome + " with "
                    + refused.getStatus() + ", and RELEASE_NOTES 3.4.1 tells automated callers to "
                    + "retry on 503: " + refused.getMessage());
        } else {
            jakarta.ws.rs.core.Response answered = door.reindexDocumentRAG("bedroom", "doc-1");
            assertEquals(200, answered.getStatus(), "a settled answer is not a 200: " + outcome);
            Object body = answered.getEntity();
            boolean success = (Boolean) body.getClass().getMethod("isSuccess").invoke(body);
            assertEquals(outcome == DocumentReindex.INDEXED, success,
                    "the door reports " + outcome + " with success=" + success);
        }

        // AND THE MACHINE-READABLE CONTRACT, which a run cannot see. Literals kept: the code is one.
        String declared = JavaSource.read(
                "src/main/java/jp/aegif/nemaki/api/v1/resource/SearchEngineResource.java");
        int method = declared.indexOf("public Response reindexDocumentRAG");
        // The method not found is this lock not looking; the method found with no @ApiResponses before it is the
        // contract missing (closing batch 8 review, subagent P3-3: one condition said both).
        HarnessBroken.unless(method > 0, "SearchEngineResource no longer declares reindexDocumentRAG, which this lock "
                + "reads the contract of");
        int declaration = declared.lastIndexOf("@ApiResponses", method);
        assertTrue(declaration > 0, "the endpoint declares no responses at all");
        assertTrue(declared.substring(declaration, method).contains("\"503\""),
                "the endpoint's declared responses do not include 503, so the generated client knows "
                        + "only about the 200 — the answer and the contract disagree");
    }

    /** Field injection, because the door takes its collaborators from Spring and JAX-RS. */
    private static void inject(Object into, String field, Object value) throws Exception {
        java.lang.reflect.Field f = into.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(into, value);
    }

    @Test
    @DisplayName("the status object really carries the count the checks read — the control")
    void theStatusCarriesTheCount() {
        // Without this, the assertions above could pin a field that does not exist or is never
        // read back, and the whole file would be checking its own spelling.
        RAGReindexStatus status = new RAGReindexStatus();
        status.setErrorCount(3);

        assertEquals(3, status.getErrorCount());
        status.setStatus("completed_with_errors");
        assertEquals("completed_with_errors", status.getStatus());
    }

    @Test
    @DisplayName("the RAG error list says when it was cut off — the CMIS sibling's fix, here too")
    void theRagErrorListSaysWhenItWasCutOff() throws Exception {
        // The CMIS reindex was corrected for this and this one was not. The UI renders both
        // numbers side by side through the SAME component for both reindexes, so a run with
        // 5,000 failures showed "5000" beside "error details (100)" with the difference
        // explained on one tab and not the other — and this is the reindex CLAUDE.md names as
        // a required upgrade step.
        String settle = JavaSource.withoutComments(JavaSource.methodBody(
                JavaSource.read(SOURCE), "private void settleStatus"));
        assertTrue(settle.contains("noteTruncation"),
                "every ending passes through settleStatus and none of them notes the cap, so a "
                        + "shortened list is handed over as the whole of it: " + settle);

        // And the helper's EFFECT, not its spelling. The first version only checked that
        // settleStatus names noteTruncation and that the helper early-returns on the count —
        // so deleting the add(...) inside it left everything green while the note silently
        // stopped appearing. Same hole the round-18 finding named: the wiring was pinned and
        // the work was not.
        RAGReindexStatus cut = new RAGReindexStatus();
        cut.setErrorCount(5000);
        java.util.List<String> hundred = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            hundred.add("failure " + i);
        }
        cut.setErrors(new java.util.ArrayList<>(hundred));
        java.lang.reflect.Method noteTruncation = Class
                .forName("jp.aegif.nemaki.rag.maintenance.RAGIndexMaintenanceServiceImpl")
                .getDeclaredMethod("noteTruncation", RAGReindexStatus.class);
        noteTruncation.setAccessible(true);
        noteTruncation.invoke(newServiceForReflection(), cut);
        assertEquals(101, cut.getErrors().size(),
                "a run that lost 4,900 messages hands over the shortened list with no note: "
                        + cut.getErrors().size() + " message(s)");
        assertTrue(cut.getErrors().get(100).contains("4900"),
                "the note does not say how many are counted but not described: "
                        + cut.getErrors().get(100));

        RAGReindexStatus exact = new RAGReindexStatus();
        exact.setErrorCount(100);
        exact.setErrors(new java.util.ArrayList<>(hundred));
        noteTruncation.invoke(newServiceForReflection(), exact);
        assertEquals(100, exact.getErrors().size(),
                "a run with exactly 100 failures and nothing dropped was told it was cut off");
    }

    /**
     * An instance for reflective calls only. The constructor wires nothing this test touches —
     * {@code noteTruncation} reads and writes the status argument alone.
     */
    private static Object newServiceForReflection() throws Exception {
        java.lang.reflect.Constructor<?> c = Class
                .forName("jp.aegif.nemaki.rag.maintenance.RAGIndexMaintenanceServiceImpl")
                .getDeclaredConstructors()[0];
        c.setAccessible(true);
        Object[] args = new Object[c.getParameterCount()];
        return c.newInstance(args);
    }
}
