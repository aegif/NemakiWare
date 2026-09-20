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
package jp.aegif.nemaki.evidence;

import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Observing existing content states a weaker fact, and says so.
 *
 * <p>The whole risk of an observe endpoint is that it looks like a way to give old documents the
 * evidence they never had. It is not: an observation covers "these bytes were stored when
 * someone looked", and the tests here are about the ways that could quietly become "these bytes
 * were what was captured".
 */
class ObservedIsNotCapturedTest {

    private static final class FakeLedger extends EvidenceLedgerService {
        private final List<String> appended = new ArrayList<>();
        private final List<EvidenceLedgerEntry.SubjectKind> kinds = new ArrayList<>();
        private long next = 1;

        @Override
        public AppendResult append(String domain, EvidenceLedgerEntry.SubjectKind kind,
                String subjectId, String payloadDigest, String occurredAt) {
            appended.add(payloadDigest);
            kinds.add(kind);
            return new AppendResult(AppendOutcome.APPENDED, next++, "h", null);
        }
    }

    private static RecordContentObserver observerOver(jp.aegif.nemaki.businesslogic.ContentService
            contentService, FakeLedger ledger) {
        RecordContentStateRecorder recorder = new RecordContentStateRecorder();
        recorder.setLedgerService(ledger);
        RecordContentObserver observer = new RecordContentObserver();
        observer.setContentService(contentService);
        observer.setRecorder(recorder);
        return observer;
    }

    private static jp.aegif.nemaki.businesslogic.ContentService serviceWith(Document document,
            AttachmentNode attachment) {
        jp.aegif.nemaki.businesslogic.ContentService service =
                Mockito.mock(jp.aegif.nemaki.businesslogic.ContentService.class);
        Mockito.when(service.getDocument(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(document);
        Mockito.when(service.getAttachment(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(attachment);
        return service;
    }

    private static AttachmentNode attachmentOf(String content, long declaredLength) {
        AttachmentNode node = new AttachmentNode();
        node.setId("att-1");
        node.setLength(declaredLength);
        node.setInputStream(new java.io.ByteArrayInputStream(
                content.getBytes(StandardCharsets.UTF_8)));
        return node;
    }

    private static Document documentWith(String attachmentId) {
        Document document = new Document();
        document.setId("doc-1");
        document.setAttachmentNodeId(attachmentId);
        return document;
    }

    @Test
    @DisplayName("an observation records OBSERVED, never CAPTURED")
    void anObservationIsRecordedAsObserved() {
        FakeLedger ledger = new FakeLedger();
        RecordContentObserver observer = observerOver(
                serviceWith(documentWith("att-1"), attachmentOf("hello", 5)), ledger);

        RecordContentObserver.Observation observation = observer.observe("bedroom", "doc-1");

        assertNull(observation.refusal(), "nothing here should have refused");
        assertTrue(observation.recorded());
        assertEquals(List.of(EvidenceLedgerEntry.SubjectKind.RECORD_CONTENT_STATE), ledger.kinds);

        // The KIND, from the statement that was actually built. Comparing against a
        // reconstructed digest could never match — recordedAt is generated inside the observer —
        // so the first version of this assertion protected nothing (control HV3 did not fire).
        assertNotNull(observation.statement());
        assertEquals(RecordContentStatementV1.CommitmentKind.OBSERVED,
                observation.statement().commitmentKind(),
                "the chain must not hold a statement that reads as a capture for bytes that "
                        + "were only ever looked at. Backfilling history as CAPTURED would say "
                        + "the repository committed to these bytes at capture time, which it "
                        + "did not");
        assertEquals(sha256Of("hello"), observation.statement().contentDigest());
        assertTrue(ledger.appended.contains(observation.statement().digest()),
                "and the digest that went into the chain is this statement's");
    }

    @Test
    @DisplayName("a document with no content is refused, not stated as empty")
    void aDocumentWithoutContentIsNotStatedAsEmpty() {
        FakeLedger ledger = new FakeLedger();
        RecordContentObserver observer = observerOver(
                serviceWith(documentWith(null), null), ledger);

        RecordContentObserver.Observation observation = observer.observe("bedroom", "doc-1");

        assertEquals(RecordContentObserver.Refusal.NO_CONTENT, observation.refusal());
        assertTrue(ledger.appended.isEmpty(),
                "the SHA-256 of no input is a real-looking digest, and nothing about that value "
                        + "says it came from an absence. A statement holding it would assert "
                        + "that this document's content IS the empty string");
    }

    @Test
    @DisplayName("bytes that could not be opened are not an empty document")
    void anUnreadableBodyIsNotAnEmptyOne() {
        FakeLedger ledger = new FakeLedger();
        AttachmentNode noBody = new AttachmentNode();
        noBody.setId("att-1");
        noBody.setLength(5);
        RecordContentObserver observer = observerOver(
                serviceWith(documentWith("att-1"), noBody), ledger);

        RecordContentObserver.Observation observation = observer.observe("bedroom", "doc-1");

        assertEquals(RecordContentObserver.Refusal.UNREADABLE, observation.refusal());
        assertTrue(ledger.appended.isEmpty());
        assertNotNull(observation.detail());
    }

    @Test
    @DisplayName("a short read against the recorded length produces no statement")
    void aShortReadIsNotObserved() {
        FakeLedger ledger = new FakeLedger();
        // The row says 99 bytes; three are there.
        RecordContentObserver observer = observerOver(
                serviceWith(documentWith("att-1"), attachmentOf("hel", 99)), ledger);

        RecordContentObserver.Observation observation = observer.observe("bedroom", "doc-1");

        assertEquals(RecordContentObserver.Refusal.NOT_DIGESTIBLE, observation.refusal());
        assertTrue(ledger.appended.isEmpty(),
                "committing the chain to the digest of part of a file would make every later "
                        + "check of that version fail against bytes that were never wrong");
    }

    @Test
    @DisplayName("a missing document is refused rather than observed as nothing")
    void aMissingDocumentIsRefused() {
        FakeLedger ledger = new FakeLedger();
        RecordContentObserver observer = observerOver(serviceWith(null, null), ledger);

        RecordContentObserver.Observation observation = observer.observe("bedroom", "nope");

        assertEquals(RecordContentObserver.Refusal.NO_SUCH_DOCUMENT, observation.refusal());
        assertTrue(ledger.appended.isEmpty());
    }

    @Test
    @DisplayName("the unresolved-writes endpoint refuses rather than answering 'no gaps'")
    void anUnaskableJournalAnswers503RatherThanAnEmptyList() {
        jp.aegif.nemaki.rest.controller.RecordContentStateController controller =
                new jp.aegif.nemaki.rest.controller.RecordContentStateController();
        // Admin, so the guard is not what is being measured here.
        jakarta.servlet.http.HttpServletRequest request =
                Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
        org.apache.chemistry.opencmis.commons.server.CallContext context =
                Mockito.mock(org.apache.chemistry.opencmis.commons.server.CallContext.class);
        Mockito.when(context.get(jp.aegif.nemaki.util.constant.CallContextKey.IS_ADMIN))
                .thenReturn(Boolean.TRUE);
        Mockito.when(request.getAttribute("CallContext")).thenReturn(context);
        controller.setHttpRequest(request);

        // A journal that IS wired and reports itself inactive — an unreachable evidence
        // database. Not "no journal at all": with no journal the guard's removal throws a
        // NullPointerException and the control fires on a harness break, which proves nothing
        // about the protection (control HX3, first attempt).
        controller.setJournal(new ContentWriteJournal() {
            @Override
            public String open(String repositoryId, String objectId, String versionObjectId,
                    WriteKind kind, String openedAt) {
                throw new ContentWriteJournalUnavailable("down");
            }

            @Override
            public CloseOutcome close(String intentId, String versionObjectId,
                    String statementDigest, java.util.Map<String, Object> statementDocument,
                    long entrySequence) {
                return CloseOutcome.UNAVAILABLE;
            }

            @Override
            public List<Unresolved> unresolved(int limit) {
                // What an unreachable store returns beside unreadableCount() > 0. If the guard
                // above is removed, THIS is what the endpoint would answer 200 with.
                return List.of();
            }

            @Override
            public boolean isActive() {
                return false;
            }
        });
        org.springframework.http.ResponseEntity<java.util.Map<String, Object>> answer =
                controller.unresolved(100);

        assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                answer.getStatusCode(),
                "200 with an empty array would let an operator read 'could not ask' as 'there "
                        + "are no unresolved writes' — the one reading this whole journal exists "
                        + "to prevent");
        assertEquals("unavailable", answer.getBody().get("status"));
        assertFalse(answer.getBody().containsKey("unresolved"),
                "an empty list must not be in the body at all: a client that reads the field "
                        + "without checking the status would see zero gaps");
    }

    private static String sha256Of(String input) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : hash) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
