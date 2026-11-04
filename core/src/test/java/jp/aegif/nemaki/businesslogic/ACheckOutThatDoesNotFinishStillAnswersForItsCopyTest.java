package jp.aegif.nemaki.businesslogic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.impl.ContentServiceImpl;
import jp.aegif.nemaki.evidence.ContentWriteJournal;
import jp.aegif.nemaki.evidence.RecordContentStateRecorder;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Document;

/**
 * A check-out that does not reach the end still answers for the copy it wrote.
 *
 * <p>With the version series gone meanwhile, checkOut returned before the copy's statement; failing after the copy, it
 * threw past it — either way the row stayed open and nothing was counted (closing batch 41 review, subagent P3-1, since
 * 3.4.0). The working copy created with the copy is recorded; a failure after the copy is counted as a write with no
 * statement. A check-out of a document with no content copied nothing, and the JVM failing is not answered.
 */
class ACheckOutThatDoesNotFinishStillAnswersForItsCopyTest {

    private static final RecordContentStateRecorder.Pending PENDING = new RecordContentStateRecorder.Pending(
            "intent-1", "bedroom", "doc-1", null, ContentWriteJournal.WriteKind.CHECK_OUT_PWC);

    private static ContentServiceImpl serviceOn(jp.aegif.nemaki.dao.ContentDaoService dao, RecordContentStateRecorder recorder)
            throws Exception {
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
        jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap infoMap =
                mock(jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap.class);
        jp.aegif.nemaki.cmis.factory.info.RepositoryInfo info = mock(jp.aegif.nemaki.cmis.factory.info.RepositoryInfo.class);
        when(info.getRootFolderId()).thenReturn("root-id");
        when(infoMap.get("bedroom")).thenReturn(info);
        service.setRepositoryInfoMap(infoMap);
        return service;
    }

    private static RecordContentStateRecorder recorder() {
        RecordContentStateRecorder recorder = mock(RecordContentStateRecorder.class);
        when(recorder.openBeforeWriting(anyString(), anyString(), any(), any(), anyString())).thenReturn(PENDING);
        return recorder;
    }

    private static jp.aegif.nemaki.dao.ContentDaoService dao(String attachmentNodeId) {
        jp.aegif.nemaki.dao.ContentDaoService dao = mock(jp.aegif.nemaki.dao.ContentDaoService.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
        Document document = new Document();
        document.setId("doc-1");
        document.setType("cmis:document");
        document.setObjectType("cmis:document");
        document.setName("minutes.txt");
        document.setAttachmentNodeId(attachmentNodeId);
        document.setVersionSeriesId("vs-1");
        document.setSubTypeProperties(new ArrayList<>());
        document.setAspects(new ArrayList<>());
        when(dao.getDocument("bedroom", "doc-1")).thenReturn(document);
        when(dao.create(eq("bedroom"), any(Document.class))).thenAnswer(invocation -> {
            Document copy = invocation.getArgument(1);
            copy.setId("pwc-1");
            return copy;
        });
        when(dao.update(eq("bedroom"), any(Document.class))).thenAnswer(invocation -> invocation.getArgument(1));
        // A version series: checkOut returns before it records when there is none.
        jp.aegif.nemaki.model.VersionSeries series = new jp.aegif.nemaki.model.VersionSeries();
        series.setId("vs-1");
        when(dao.getVersionSeries(anyString(), anyString())).thenReturn(series);
        return dao;
    }

    private static jp.aegif.nemaki.dao.ContentDaoService withACopy() {
        jp.aegif.nemaki.dao.ContentDaoService dao = dao("att-9");
        AttachmentNode source = new AttachmentNode();
        source.setName("minutes.txt");
        source.setMimeType("text/plain");
        source.setLength(11L);
        source.setInputStream(new ByteArrayInputStream("the minutes".getBytes(StandardCharsets.UTF_8)));
        when(dao.getAttachment("bedroom", "att-9")).thenReturn(source);
        when(dao.createAttachment(eq("bedroom"), any(AttachmentNode.class), any())).thenReturn("att-copy");
        AttachmentNode copy = new AttachmentNode();
        copy.setName("minutes.txt");
        copy.setMimeType("text/plain");
        copy.setLength(11L);
        copy.setInputStream(new ByteArrayInputStream("the minutes".getBytes(StandardCharsets.UTF_8)));
        // The copy's row as read back, with its revision — the place of the copy among the row's writes (R229).
        copy.setRevision("2-copy");
        when(dao.getAttachment("bedroom", "att-copy")).thenReturn(copy);
        return dao;
    }

    @Test
    @DisplayName("a check-out whose version series is gone meanwhile still records the copy its working copy was made with")
    void aCheckOutWhoseVersionSeriesIsGoneRecordsItsCopy() throws Exception {
        jp.aegif.nemaki.dao.ContentDaoService dao = withACopy();
        when(dao.getVersionSeries(anyString(), anyString())).thenReturn(null);
        RecordContentStateRecorder recorder = recorder();
        ContentServiceImpl service = serviceOn(dao, recorder);

        assertDoesNotThrow(() -> service.checkOut(mock(CallContext.class), "bedroom", "doc-1", null),
                "a check-out whose version series was gone was refused");
        verify(recorder).recordAndClose(any(), any());
    }

    @Test
    @DisplayName("a check-out that fails after its copy is written counts the copy as a write with no statement")
    void aCheckOutThatFailsAfterItsCopyCountsIt() throws Exception {
        jp.aegif.nemaki.dao.ContentDaoService dao = withACopy();
        when(dao.create(eq("bedroom"), any(Document.class))).thenThrow(new IllegalStateException("the store refused the PWC"));
        RecordContentStateRecorder recorder = recorder();
        ContentServiceImpl service = serviceOn(dao, recorder);

        assertThrows(IllegalStateException.class, () -> service.checkOut(mock(CallContext.class), "bedroom", "doc-1", null));
        verify(recorder).leaveUnrecorded(any(), anyString());
        verify(recorder, never()).recordAndClose(any(), any());
    }

    @Test
    @DisplayName("a check-out that fails with an Error after its copy counts the copy too")
    void aCheckOutThatFailsWithAnErrorAfterItsCopyCountsIt() throws Exception {
        jp.aegif.nemaki.dao.ContentDaoService dao = withACopy();
        when(dao.create(eq("bedroom"), any(Document.class))).thenThrow(new NoClassDefFoundError("a model class"));
        RecordContentStateRecorder recorder = recorder();
        ContentServiceImpl service = serviceOn(dao, recorder);

        assertThrows(NoClassDefFoundError.class, () -> service.checkOut(mock(CallContext.class), "bedroom", "doc-1", null));
        verify(recorder).leaveUnrecorded(any(), anyString());
    }

    @Test
    @DisplayName("a failed check-out of a document with no content copied nothing and counts nothing")
    void aFailedContentlessCheckOutCountsNothing() throws Exception {
        jp.aegif.nemaki.dao.ContentDaoService dao = dao(null);
        when(dao.create(eq("bedroom"), any(Document.class))).thenThrow(new IllegalStateException("the store refused the PWC"));
        RecordContentStateRecorder recorder = recorder();
        ContentServiceImpl service = serviceOn(dao, recorder);

        assertThrows(IllegalStateException.class, () -> service.checkOut(mock(CallContext.class), "bedroom", "doc-1", null));
        verify(recorder, never()).leaveUnrecorded(any(), anyString());
    }

    @Test
    @DisplayName("the JVM failing after the copy is not answered")
    void theJvmFailingAfterTheCopyIsNotAnswered() throws Exception {
        jp.aegif.nemaki.dao.ContentDaoService dao = withACopy();
        when(dao.create(eq("bedroom"), any(Document.class))).thenThrow(new StackOverflowError("deep"));
        RecordContentStateRecorder recorder = recorder();
        ContentServiceImpl service = serviceOn(dao, recorder);

        assertThrows(StackOverflowError.class, () -> service.checkOut(mock(CallContext.class), "bedroom", "doc-1", null));
        verify(recorder, never()).leaveUnrecorded(any(), anyString());
    }

    @Test
    @DisplayName("a copy whose source cannot be taken out of the cache writes nothing — no copy is left unanswered")
    @SuppressWarnings("unchecked")
    void aCopyWhoseCacheCannotBeClearedWritesNothing() throws Exception {
        // The eviction ran after the copy was written: when it failed, the copy was written and its id never returned, so
        // nothing could record or count it (closing batch 42 review, Codex P3 and subagent P3-4).
        jp.aegif.nemaki.dao.ContentDaoService dao = withACopy();
        RecordContentStateRecorder recorder = recorder();
        ContentServiceImpl service = serviceOn(dao, recorder);
        Field poolField = ContentServiceImpl.class.getDeclaredField("nemakiCachePool");
        poolField.setAccessible(true);
        jp.aegif.nemaki.util.cache.NemakiCachePool pool = (jp.aegif.nemaki.util.cache.NemakiCachePool) poolField.get(service);
        jp.aegif.nemaki.util.cache.model.NemakiCache<AttachmentNode> attachments = mock(jp.aegif.nemaki.util.cache.model.NemakiCache.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("the cache could not be cleared")).when(attachments).remove("att-9");
        when(pool.get("bedroom").getAttachmentCache()).thenReturn(attachments);

        assertThrows(IllegalStateException.class, () -> service.checkOut(mock(CallContext.class), "bedroom", "doc-1", null));
        verify(dao, never()).createAttachment(anyString(), any(AttachmentNode.class), any());
    }

    @Test
    @DisplayName("a check-out whose copy's digest fails with an Error counts the copy")
    void aCheckOutWhoseCopyDigestFailsWithAnErrorCountsIt() throws Exception {
        jp.aegif.nemaki.dao.ContentDaoService dao = withACopy();
        when(dao.getAttachment("bedroom", "att-copy")).thenThrow(new NoClassDefFoundError("a stream class"));
        RecordContentStateRecorder recorder = recorder();
        ContentServiceImpl service = serviceOn(dao, recorder);

        assertThrows(NoClassDefFoundError.class, () -> service.checkOut(mock(CallContext.class), "bedroom", "doc-1", null));
        verify(recorder).leaveUnrecorded(any(), anyString());
    }

    @Test
    @DisplayName("a statement that cannot be built because a class will not load is counted as a write with no statement")
    void aStatementThatCannotBeBuiltWithAnErrorIsCounted() throws Exception {
        // No arm produces it, so the arm is driven directly: the version's id is read to build the statement.
        RecordContentStateRecorder recorder = recorder();
        ContentServiceImpl service = serviceOn(dao(null), recorder);
        Document version = mock(Document.class);
        when(version.getId()).thenThrow(new NoClassDefFoundError("a model class")).thenReturn("pwc-1");
        java.lang.reflect.Method record = ContentServiceImpl.class.getDeclaredMethod("recordContentState", String.class,
                Document.class, jp.aegif.nemaki.businesslogic.impl.delegate.AttachmentServiceDelegate.Written.class,
                RecordContentStateRecorder.Pending.class, jp.aegif.nemaki.evidence.RecordContentStatementV1.CommitmentKind.class);
        record.setAccessible(true);

        assertDoesNotThrow(() -> record.invoke(service, "bedroom", version,
                new jp.aegif.nemaki.businesslogic.impl.delegate.AttachmentServiceDelegate.Written("att-copy",
                        "0000000000000000000000000000000000000000000000000000000000000000", 11L, "2-copy"),
                PENDING, jp.aegif.nemaki.evidence.RecordContentStatementV1.CommitmentKind.CAPTURED),
                "a statement that could not be built for want of a class failed the write instead of being counted");
        verify(recorder).leaveUnrecorded(any(), anyString());
        verify(recorder, never()).recordAndClose(any(), any());
    }

    @Test
    @DisplayName("the JVM failing while a statement is built goes up, uncounted")
    void theJvmFailingWhileBuildingAStatementGoesUp() throws Exception {
        RecordContentStateRecorder recorder = recorder();
        ContentServiceImpl service = serviceOn(dao(null), recorder);
        Document version = mock(Document.class);
        when(version.getId()).thenThrow(new StackOverflowError("deep")).thenReturn("pwc-1");
        java.lang.reflect.Method record = ContentServiceImpl.class.getDeclaredMethod("recordContentState", String.class,
                Document.class, jp.aegif.nemaki.businesslogic.impl.delegate.AttachmentServiceDelegate.Written.class,
                RecordContentStateRecorder.Pending.class, jp.aegif.nemaki.evidence.RecordContentStatementV1.CommitmentKind.class);
        record.setAccessible(true);

        java.lang.reflect.InvocationTargetException thrown = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> record.invoke(service, "bedroom", version,
                        new jp.aegif.nemaki.businesslogic.impl.delegate.AttachmentServiceDelegate.Written("att-copy",
                                "0000000000000000000000000000000000000000000000000000000000000000", 11L, "2-copy"),
                        PENDING, jp.aegif.nemaki.evidence.RecordContentStatementV1.CommitmentKind.CAPTURED),
                "the JVM failing while a statement was built was swallowed");
        org.junit.jupiter.api.Assertions.assertInstanceOf(StackOverflowError.class, thrown.getCause(),
                "the JVM failing while a statement was built did not go up as it was");
        verify(recorder, never()).leaveUnrecorded(any(), anyString());
    }
}
