package jp.aegif.nemaki.businesslogic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.exceptions.CmisUpdateConflictException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jp.aegif.nemaki.businesslogic.impl.ContentServiceImpl;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.dao.impl.couch.connector.CouchConflicts;
import jp.aegif.nemaki.evidence.ContentWriteJournal;
import jp.aegif.nemaki.evidence.RecordContentStateRecorder;
import jp.aegif.nemaki.evidence.RecordContentStatementV1;
import jp.aegif.nemaki.evidence.RecordStatement;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.cache.NemakiCachePool;

/**
 * Content a version is given when it had none is recorded once the version is stored pointing at it, and as what it is.
 *
 * <p>replacePwc, appendAttachment and updateDocumentWithNewStream's second case created the content and closed the E1 row
 * with an UPDATED statement before the version was stored: a store that failed left a statement for content nothing pointed
 * at, and the content unreferenced (closing batch 37 review, Codex P2 and subagent P3-2); UPDATED, «replaced in place», is
 * untrue of a version that had nothing to replace (P3-1); and an append that found the document given content meanwhile
 * stored its own id over the other's (P3-3).
 *
 * <p>Closing batch 38 took the content back after ANY failed store: a write that landed and whose answer was lost left the
 * stored version pointing at deleted bytes and its row closed as «did not happen» (closing batch 38 review, both reviewers
 * independently). Only a store that was never asked, or that refused the revision, takes it back now. A failure before the
 * store left the content and the id in place (Codex P2), the append reset an instance other than the one it gave the id
 * (subagent P3-1), and the arm that could not tell the kind counted nothing (P3-4).
 */
class FirstContentOfAVersionIsRecordedOnceStoredTest {

    private static final RecordContentStateRecorder.Pending PENDING = new RecordContentStateRecorder.Pending(
            "intent-1", "bedroom", "doc-1", "doc-1", ContentWriteJournal.WriteKind.REPLACE_PWC);

    private static ContentDaoService dao() {
        ContentDaoService dao = mock(ContentDaoService.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        IllegalStateException unreadable = new IllegalStateException("the attachment of a document could not be read");
        when(dao.getAttachmentRef(anyString(), isNull())).thenThrow(unreadable);
        when(dao.getAttachment(anyString(), isNull())).thenThrow(unreadable);
        // The bytes are read as the real store reads them, so the digest the statement carries is taken.
        when(dao.createAttachment(anyString(), any(AttachmentNode.class), any(ContentStream.class))).thenAnswer(invocation -> {
            ((ContentStream) invocation.getArgument(2)).getStream().readAllBytes();
            return "att-new";
        });
        when(dao.update(anyString(), any(Document.class))).thenAnswer(invocation -> invocation.getArgument(1));
        return dao;
    }

    /** As CloudantClientWrapper.update raises a CouchDB 409: the SDK's conflict, wrapped. */
    private static RuntimeException refused() {
        return new RuntimeException("CouchDB revision conflict - update failed", CouchConflicts.conflict());
    }

    /** A failure that says nothing about whether the store took the write — an answer lost after it landed. */
    private static RuntimeException notKnown() {
        return new RuntimeException("CouchDB update failed: timeout", new java.net.SocketTimeoutException("timeout"));
    }

    private static <T> T field(ContentServiceImpl service, String name, Class<T> type) throws Exception {
        Field f = ContentServiceImpl.class.getDeclaredField(name);
        f.setAccessible(true);
        return type.cast(f.get(service));
    }

    private static void verifyForgotten(ContentServiceImpl service) throws Exception {
        verify(field(service, "nemakiCachePool", NemakiCachePool.class).get("bedroom")).removeCmisAndContentCache("doc-1");
    }

    private static RecordContentStateRecorder recorder(Boolean holdsAStatement) {
        RecordContentStateRecorder recorder = mock(RecordContentStateRecorder.class);
        when(recorder.openBeforeWriting(anyString(), anyString(), anyString(), any(), anyString())).thenReturn(PENDING);
        when(recorder.holdsAStatementFor(anyString(), anyString())).thenReturn(holdsAStatement);
        when(recorder.recordAndClose(any(), any())).thenReturn(
                new RecordContentStateRecorder.Result(RecordContentStateRecorder.Outcome.CHAINED, "digest", 1, null));
        return recorder;
    }

    private static ContentServiceImpl serviceOn(ContentDaoService dao, RecordContentStateRecorder recorder) throws Exception {
        ContentServiceImpl service = new ContentServiceImpl();
        for (String fieldName : new String[]{"contentDaoService", "helper", "aclDelegate", "solrUtil", "nemakiCachePool",
                "recordContentState"}) {
            Field f = ContentServiceImpl.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(service, switch (fieldName) {
                case "contentDaoService" -> dao;
                case "recordContentState" -> recorder;
                default -> mock(f.getType(), org.mockito.Mockito.RETURNS_DEEP_STUBS);
            });
        }
        PropertyManager properties = mock(PropertyManager.class);
        when(properties.readValue(anyString())).thenReturn("false");
        service.setPropertyManager(properties);
        jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap infoMap =
                mock(jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap.class);
        jp.aegif.nemaki.cmis.factory.info.RepositoryInfo info = mock(jp.aegif.nemaki.cmis.factory.info.RepositoryInfo.class);
        when(info.getRootFolderId()).thenReturn("root-id");
        when(infoMap.get("bedroom")).thenReturn(info);
        service.setRepositoryInfoMap(infoMap);
        return service;
    }

    private static Document contentless(boolean pwc) {
        Document document = new Document();
        document.setId("doc-1");
        document.setType("cmis:document");
        document.setObjectType("cmis:document");
        document.setName("minutes.txt");
        document.setAttachmentNodeId(null);
        document.setPrivateWorkingCopy(pwc);
        document.setSubTypeProperties(new ArrayList<>());
        document.setAspects(new ArrayList<>());
        document.setSecondaryIds(new ArrayList<>());
        return document;
    }

    private static ContentStream someContent() {
        byte[] bytes = "minutes".getBytes(StandardCharsets.UTF_8);
        return new ContentStreamImpl("minutes.txt", BigInteger.valueOf(bytes.length), "text/plain", new ByteArrayInputStream(bytes));
    }

    private static RecordContentStatementV1.CommitmentKind recordedKind(RecordContentStateRecorder recorder) {
        ArgumentCaptor<RecordStatement> statement = ArgumentCaptor.forClass(RecordStatement.class);
        verify(recorder).recordAndClose(any(), statement.capture());
        return ((RecordContentStatementV1) statement.getValue()).commitmentKind();
    }

    @Test
    @DisplayName("a PWC's first content is CAPTURED when the journal holds nothing for it, UPDATED when it holds a statement")
    void theKindIsWhatTheJournalSays() throws Exception {
        RecordContentStateRecorder never = recorder(Boolean.FALSE);
        serviceOn(dao(), never).replacePwc(mock(CallContext.class), "bedroom", contentless(true), someContent());
        assertEquals(RecordContentStatementV1.CommitmentKind.CAPTURED, recordedKind(never),
                "content a version had none of, and the journal holds nothing for, was recorded as other than captured");

        RecordContentStateRecorder before = recorder(Boolean.TRUE);
        serviceOn(dao(), before).replacePwc(mock(CallContext.class), "bedroom", contentless(true), someContent());
        assertEquals(RecordContentStatementV1.CommitmentKind.UPDATED, recordedKind(before),
                "content a version is given after the journal recorded some for it was recorded as its first");
    }

    @Test
    @DisplayName("when the journal cannot say whether the version had content, no statement is recorded and the row stays open")
    void noKindIsGuessed() throws Exception {
        RecordContentStateRecorder unknown = recorder(null);
        serviceOn(dao(), unknown).replacePwc(mock(CallContext.class), "bedroom", contentless(true), someContent());
        verify(unknown, never()).recordAndClose(any(), any());
        verify(unknown, never()).abandon(any(), anyString());
        verify(unknown).leaveUnrecorded(any(), anyString());
    }

    @Test
    @DisplayName("a PWC whose store refuses the revision has the content taken back and records nothing")
    void aRefusedStoreOfAPwcTakesTheContentBack() throws Exception {
        ContentDaoService dao = dao();
        when(dao.update(anyString(), any(Document.class))).thenThrow(refused());
        RecordContentStateRecorder recorder = recorder(Boolean.FALSE);
        Document pwc = contentless(true);
        ContentServiceImpl service = serviceOn(dao, recorder);

        assertThrows(RuntimeException.class,
                () -> service.replacePwc(mock(CallContext.class), "bedroom", pwc, someContent()));
        verify(recorder, never()).recordAndClose(any(), any());
        verify(dao).delete("bedroom", "att-new");
        verify(recorder).abandon(any(), anyString());
        assertNull(pwc.getAttachmentNodeId(), "the PWC kept the id of content it was not stored with");
        verifyForgotten(service);
    }

    @Test
    @DisplayName("a PWC whose store failed in a way that does not say it was not stored keeps the content, and records nothing")
    void aStoreOfAPwcWhoseOutcomeIsNotKnownKeepsTheContent() throws Exception {
        ContentDaoService dao = dao();
        when(dao.update(anyString(), any(Document.class))).thenThrow(notKnown());
        RecordContentStateRecorder recorder = recorder(Boolean.FALSE);
        ContentServiceImpl service = serviceOn(dao, recorder);

        assertThrows(RuntimeException.class,
                () -> service.replacePwc(mock(CallContext.class), "bedroom", contentless(true), someContent()));
        verify(dao, never()).delete(anyString(), anyString());
        verify(recorder, never()).abandon(any(), anyString());
        verify(recorder, never()).recordAndClose(any(), any());
        verify(recorder).leaveUnrecorded(any(), anyString());
        verifyForgotten(service);
    }

    @Test
    @DisplayName("a PWC that fails before its store is asked has the content taken back")
    void aFailureBeforeTheStoreTakesTheContentBack() throws Exception {
        ContentDaoService dao = dao();
        RecordContentStateRecorder recorder = recorder(Boolean.FALSE);
        Document pwc = contentless(true);
        ContentServiceImpl service = serviceOn(dao, recorder);
        // The PWC's signature, not the new content's: the content is signed as it is created.
        doThrow(new IllegalStateException("the signature could not be set")).when(
                field(service, "helper", jp.aegif.nemaki.businesslogic.impl.delegate.ContentServiceHelper.class))
                .setSignature(any(), any(Document.class));

        assertThrows(IllegalStateException.class,
                () -> service.replacePwc(mock(CallContext.class), "bedroom", pwc, someContent()));
        verify(dao, never()).update(anyString(), any(Document.class));
        verify(dao).delete("bedroom", "att-new");
        verify(recorder).abandon(any(), anyString());
        assertNull(pwc.getAttachmentNodeId(), "the PWC kept the id of content it was not stored with");
    }

    @Test
    @DisplayName("a document with no content whose store refuses the revision has the content taken back")
    void aRefusedStoreOfADocumentTakesTheContentBack() throws Exception {
        ContentDaoService dao = dao();
        when(dao.update(anyString(), any(Document.class))).thenThrow(refused());
        RecordContentStateRecorder recorder = recorder(Boolean.FALSE);
        Document document = contentless(false);
        ContentServiceImpl service = serviceOn(dao, recorder);

        assertThrows(RuntimeException.class, () -> service
                .updateDocumentWithNewStream(mock(CallContext.class), "bedroom", document, someContent()));
        verify(recorder, never()).recordAndClose(any(), any());
        verify(dao).delete("bedroom", "att-new");
        verify(recorder).abandon(any(), anyString());
        assertNull(document.getAttachmentNodeId(), "the document kept the id of content it was not stored with");
        verifyForgotten(service);
    }

    @Test
    @DisplayName("a document whose store failed in a way that does not say it was not stored keeps the content")
    void aStoreOfADocumentWhoseOutcomeIsNotKnownKeepsTheContent() throws Exception {
        ContentDaoService dao = dao();
        when(dao.update(anyString(), any(Document.class))).thenThrow(notKnown());
        RecordContentStateRecorder recorder = recorder(Boolean.FALSE);
        ContentServiceImpl service = serviceOn(dao, recorder);

        assertThrows(RuntimeException.class, () -> service
                .updateDocumentWithNewStream(mock(CallContext.class), "bedroom", contentless(false), someContent()));
        verify(dao, never()).delete(anyString(), anyString());
        verify(recorder, never()).abandon(any(), anyString());
        verify(recorder, never()).recordAndClose(any(), any());
        verify(recorder).leaveUnrecorded(any(), anyString());
        verifyForgotten(service);
    }

    @Test
    @DisplayName("a document with no content that fails before its store is asked has the content taken back")
    void aFailureBeforeTheStoreOfADocumentTakesTheContentBack() throws Exception {
        ContentDaoService dao = dao();
        RecordContentStateRecorder recorder = recorder(Boolean.FALSE);
        Document document = contentless(false);
        ContentServiceImpl service = serviceOn(dao, recorder);
        doThrow(new IllegalStateException("the signature could not be set")).when(
                field(service, "helper", jp.aegif.nemaki.businesslogic.impl.delegate.ContentServiceHelper.class))
                .setSignature(any(), any(Document.class));

        assertThrows(IllegalStateException.class, () -> service
                .updateDocumentWithNewStream(mock(CallContext.class), "bedroom", document, someContent()));
        verify(dao, never()).update(anyString(), any(Document.class));
        verify(dao).delete("bedroom", "att-new");
        verify(recorder).abandon(any(), anyString());
        assertNull(document.getAttachmentNodeId(), "the document kept the id of content it was not stored with");
    }

    @Test
    @DisplayName("a document with no content stored with its new content records it as captured")
    void aDocumentsFirstContentIsCaptured() throws Exception {
        RecordContentStateRecorder recorder = recorder(Boolean.FALSE);
        serviceOn(dao(), recorder).updateDocumentWithNewStream(mock(CallContext.class), "bedroom", contentless(false),
                someContent());
        assertEquals(RecordContentStatementV1.CommitmentKind.CAPTURED, recordedKind(recorder),
                "a document's first content was recorded as other than captured");
    }

    @Test
    @DisplayName("an append that reads its own document back — the cache answers the same instance — is not a conflict")
    void anAppendThatReadsItsOwnDocumentBackIsNotAConflict() throws Exception {
        // The cached DAO answers the re-read with the instance it answered the first read with. The id put on that instance
        // before the re-read was taken for content another request had given the document, and every append to a document
        // with no content answered 409 (measured on nb33 while closing batch 38 was being measured).
        ContentDaoService dao = dao();
        Document cached = contentless(false);
        when(dao.getDocument("bedroom", "doc-1")).thenReturn(cached);
        RecordContentStateRecorder recorder = recorder(Boolean.FALSE);

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> serviceOn(dao, recorder).appendAttachment(
                mock(CallContext.class), "bedroom", new Holder<>("doc-1"), new Holder<>(null), someContent(), true, null),
                "an append to a document with no content answered a conflict with itself");
        ArgumentCaptor<Document> written = ArgumentCaptor.forClass(Document.class);
        verify(dao).update(anyString(), written.capture());
        assertEquals("att-new", written.getValue().getAttachmentNodeId(), "the document was stored without its appended content");
        assertEquals(RecordContentStatementV1.CommitmentKind.CAPTURED, recordedKind(recorder),
                "a document's first appended content was recorded as other than captured");
    }

    @Test
    @DisplayName("an append that finds the document given content by another request answers a conflict and takes its own back")
    void anAppendThatFindsContentGivenMeanwhileIsAConflict() throws Exception {
        ContentDaoService dao = dao();
        Document givenMeanwhile = contentless(false);
        givenMeanwhile.setAttachmentNodeId("att-other");
        when(dao.getDocument("bedroom", "doc-1")).thenReturn(contentless(false), givenMeanwhile);
        RecordContentStateRecorder recorder = recorder(Boolean.FALSE);

        assertThrows(CmisUpdateConflictException.class, () -> serviceOn(dao, recorder).appendAttachment(mock(CallContext.class),
                "bedroom", new Holder<>("doc-1"), new Holder<>(null), someContent(), true, null),
                "an append stored its content's id over the content another request gave the document meanwhile");
        verify(dao, never()).update(anyString(), any(Document.class));
        verify(dao).delete("bedroom", "att-new");
        verify(recorder).abandon(any(), anyString());
        verify(recorder, never()).recordAndClose(any(), any());
    }

    @Test
    @DisplayName("an append whose store refuses the revision takes the id back from the instance it put it on")
    void anAppendTakesTheIdBackFromTheInstanceItGaveIt() throws Exception {
        // The re-read answers another instance than the first read — a cache emptied meanwhile, or no cache.
        ContentDaoService dao = dao();
        Document reRead = contentless(false);
        when(dao.getDocument("bedroom", "doc-1")).thenReturn(contentless(false), reRead);
        when(dao.update(anyString(), any(Document.class))).thenThrow(refused());
        RecordContentStateRecorder recorder = recorder(Boolean.FALSE);
        ContentServiceImpl service = serviceOn(dao, recorder);

        assertThrows(RuntimeException.class, () -> service.appendAttachment(mock(CallContext.class), "bedroom",
                new Holder<>("doc-1"), new Holder<>(null), someContent(), true, null));
        verify(dao).delete("bedroom", "att-new");
        assertNull(reRead.getAttachmentNodeId(), "the instance the append gave the id kept the id of content it deleted");
        verifyForgotten(service);
    }

    @Test
    @DisplayName("an append whose store failed in a way that does not say it was not stored keeps the content")
    void anAppendWhoseOutcomeIsNotKnownKeepsTheContent() throws Exception {
        ContentDaoService dao = dao();
        Document reRead = contentless(false);
        when(dao.getDocument("bedroom", "doc-1")).thenReturn(contentless(false), reRead);
        when(dao.update(anyString(), any(Document.class))).thenThrow(notKnown());
        RecordContentStateRecorder recorder = recorder(Boolean.FALSE);
        ContentServiceImpl service = serviceOn(dao, recorder);

        assertThrows(RuntimeException.class, () -> service.appendAttachment(mock(CallContext.class), "bedroom",
                new Holder<>("doc-1"), new Holder<>(null), someContent(), true, null));
        verify(dao, never()).delete(anyString(), anyString());
        verify(recorder, never()).abandon(any(), anyString());
        verify(recorder).leaveUnrecorded(any(), anyString());
        verifyForgotten(service);
    }

    @Test
    @DisplayName("a write left without a statement is counted, and says whether its row lists it")
    void aWriteLeftUnrecordedIsCountedAndSaysWhetherItIsListed() {
        RecordContentStateRecorder recorder = new RecordContentStateRecorder();
        assertEquals(RecordContentStateRecorder.Outcome.UNRECORDED_GAP_OPEN, recorder.leaveUnrecorded(PENDING, "a test"),
                "a write whose row is open was not reported as listed by it");
        RecordContentStateRecorder.Pending noRow = new RecordContentStateRecorder.Pending(null, "bedroom", "doc-1", "doc-1",
                ContentWriteJournal.WriteKind.APPEND);
        assertEquals(RecordContentStateRecorder.Outcome.UNRECORDED_GAP_UNLISTABLE, recorder.leaveUnrecorded(noRow, "a test"),
                "a write the journal opened no row for was reported as listed");
        assertEquals(2, recorder.unrecordedWritesSinceStartup(), "a write left without a statement was not counted");
    }
}
