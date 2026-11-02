package jp.aegif.nemaki.businesslogic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
 * A copy that was written and could not be read back to be digested is counted as a write with no statement.
 *
 * <p>{@code digestOfStoredAttachment} answered null both for «there is no copy» and for «the copy could not be read to the
 * end», and the shared recorder returns silently on null: the copy a check-out wrote was left with no statement and no
 * count (closing batch 40 review, subagent — the third arm of the shape closing batch 40 counted). A check-out of a document
 * with no content writes nothing, and is not counted.
 */
class ACopyThatCannotBeDigestedIsCountedTest {

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

    @Test
    @DisplayName("a check-out whose copy cannot be read back to be digested is counted as a write with no statement")
    void aCopyThatCannotBeReadBackIsCounted() throws Exception {
        jp.aegif.nemaki.dao.ContentDaoService dao = dao("att-9");
        AttachmentNode source = new AttachmentNode();
        source.setName("minutes.txt");
        source.setMimeType("text/plain");
        source.setLength(11L);
        source.setInputStream(new ByteArrayInputStream("the minutes".getBytes(StandardCharsets.UTF_8)));
        when(dao.getAttachment("bedroom", "att-9")).thenReturn(source);
        when(dao.createAttachment(eq("bedroom"), any(AttachmentNode.class), any())).thenReturn("att-copy");
        when(dao.getAttachment("bedroom", "att-copy")).thenThrow(new IllegalStateException("the copy could not be read"));
        RecordContentStateRecorder recorder = recorder();
        ContentServiceImpl service = serviceOn(dao, recorder);

        assertDoesNotThrow(() -> service.checkOut(mock(CallContext.class), "bedroom", "doc-1", null),
                "a check-out whose copy could not be digested was refused");
        verify(recorder, never()).recordAndClose(any(), any());
        verify(recorder).leaveUnrecorded(any(), anyString());
    }

    @Test
    @DisplayName("a check-out whose copy reads back shorter than its row says is counted, not recorded")
    void aCopyWhoseLengthDoesNotMatchIsCounted() throws Exception {
        jp.aegif.nemaki.dao.ContentDaoService dao = dao("att-9");
        AttachmentNode source = new AttachmentNode();
        source.setName("minutes.txt");
        source.setMimeType("text/plain");
        source.setLength(11L);
        source.setInputStream(new ByteArrayInputStream("the minutes".getBytes(StandardCharsets.UTF_8)));
        when(dao.getAttachment("bedroom", "att-9")).thenReturn(source);
        when(dao.createAttachment(eq("bedroom"), any(AttachmentNode.class), any())).thenReturn("att-copy");
        AttachmentNode shortCopy = new AttachmentNode();
        shortCopy.setName("minutes.txt");
        shortCopy.setMimeType("text/plain");
        shortCopy.setLength(999L);
        shortCopy.setInputStream(new ByteArrayInputStream("the minutes".getBytes(StandardCharsets.UTF_8)));
        when(dao.getAttachment("bedroom", "att-copy")).thenReturn(shortCopy);
        RecordContentStateRecorder recorder = recorder();
        ContentServiceImpl service = serviceOn(dao, recorder);

        assertDoesNotThrow(() -> service.checkOut(mock(CallContext.class), "bedroom", "doc-1", null),
                "a check-out whose copy could not be digested was refused");
        verify(recorder, never()).recordAndClose(any(), any());
        verify(recorder).leaveUnrecorded(any(), anyString());
    }

    @Test
    @DisplayName("a check-out of a document with no content writes nothing and counts nothing")
    void aContentlessCheckOutCountsNothing() throws Exception {
        RecordContentStateRecorder recorder = recorder();
        ContentServiceImpl service = serviceOn(dao(null), recorder);

        assertDoesNotThrow(() -> service.checkOut(mock(CallContext.class), "bedroom", "doc-1", null),
                "a check-out of a document with no content was refused");
        verify(recorder, never()).leaveUnrecorded(any(), anyString());
        verify(recorder, never()).recordAndClose(any(), any());
    }
}
