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
package jp.aegif.nemaki.businesslogic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
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
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jp.aegif.nemaki.businesslogic.impl.ContentServiceImpl;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.evidence.ContentWriteJournal;
import jp.aegif.nemaki.evidence.RecordContentStateRecorder;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.util.PropertyManager;

/**
 * R229, at the entry points: a content write is recorded with the revision the write left its content's row at — the revision
 * the store reported for the new bytes, not the one the node was read with — and not at all when the store did not say.
 *
 * <p>The journal takes the newest statement of a row from that revision's generation (ALaterWriteOfTheSameRowIsTheNewestTest).
 * The revision the node came with is the row's revision BEFORE the write: recorded as the write's place, the write stands one
 * place early, level with the write before it, and the ledger's order decides between the two again — the defect.
 */
class AContentWriteIsPlacedByTheRevisionItLeftTest {

    private static final RecordContentStateRecorder.Pending PENDING = new RecordContentStateRecorder.Pending(
            "intent-1", "bedroom", "doc-1", "doc-1", ContentWriteJournal.WriteKind.REPLACE_PWC);

    /** A store whose rewrite of att-1 reports {@code reported} on the node (or nothing, when null). */
    private static ContentDaoService dao(String reported) {
        ContentDaoService dao = mock(ContentDaoService.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(dao.getAttachmentRef("bedroom", "att-1")).thenAnswer(invocation -> {
            AttachmentNode node = new AttachmentNode();
            node.setId("att-1");
            node.setRevision("3-before");
            return node;
        });
        doAnswer(invocation -> {
            ((ContentStream) invocation.getArgument(2)).getStream().readAllBytes();
            if (reported != null) {
                ((AttachmentNode) invocation.getArgument(1)).setRevision(reported);
            }
            return null;
        }).when(dao).updateAttachment(anyString(), any(AttachmentNode.class), any(ContentStream.class));
        when(dao.getAttachment(anyString(), anyString())).thenAnswer(invocation -> {
            AttachmentNode body = new AttachmentNode();
            body.setId(invocation.getArgument(1));
            body.setInputStream(new ByteArrayInputStream("minutes".getBytes(StandardCharsets.UTF_8)));
            return body;
        });
        when(dao.createAttachment(anyString(), any(AttachmentNode.class), any(ContentStream.class))).thenAnswer(invocation -> {
            ((ContentStream) invocation.getArgument(2)).getStream().readAllBytes();
            ((AttachmentNode) invocation.getArgument(1)).setRevision("2-att-new");
            return "att-new";
        });
        when(dao.update(anyString(), any(Document.class))).thenAnswer(invocation -> invocation.getArgument(1));
        return dao;
    }

    private static RecordContentStateRecorder recorder() {
        RecordContentStateRecorder recorder = mock(RecordContentStateRecorder.class);
        when(recorder.openBeforeWriting(anyString(), anyString(), anyString(), any(), anyString())).thenReturn(PENDING);
        when(recorder.holdsAStatementFor(anyString(), anyString())).thenReturn(Boolean.FALSE);
        when(recorder.recordAndClose(any(), any())).thenReturn(
                new RecordContentStateRecorder.Result(RecordContentStateRecorder.Outcome.CHAINED, "digest", 1, null));
        return recorder;
    }

    private static ContentServiceImpl serviceOn(ContentDaoService dao, RecordContentStateRecorder recorder) throws Exception {
        ContentServiceImpl service = new ContentServiceImpl();
        for (String fieldName : new String[]{"contentDaoService", "helper", "aclDelegate", "solrUtil", "nemakiCachePool",
                "recordContentState", "renditionManager"}) {
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

    private static Document version(boolean pwc, String attachment) {
        Document document = new Document();
        document.setId("doc-1");
        document.setRevision("6-doc");
        document.setType("cmis:document");
        document.setObjectType("cmis:document");
        document.setName("minutes.txt");
        document.setAttachmentNodeId(attachment);
        document.setPrivateWorkingCopy(pwc);
        document.setSubTypeProperties(new ArrayList<>());
        document.setAspects(new ArrayList<>());
        document.setSecondaryIds(new ArrayList<>());
        return document;
    }

    private static ContentStream someContent() {
        byte[] bytes = "minutes".getBytes(StandardCharsets.UTF_8);
        return new ContentStreamImpl("minutes.txt", BigInteger.valueOf(bytes.length), "text/plain",
                new ByteArrayInputStream(bytes));
    }

    private static String recordedRevision(RecordContentStateRecorder recorder) {
        ArgumentCaptor<RecordContentStateRecorder.Pending> pending = ArgumentCaptor.forClass(RecordContentStateRecorder.Pending.class);
        verify(recorder).recordAndClose(pending.capture(), any());
        return pending.getValue().writtenRevision();
    }

    @Test
    @DisplayName("a PWC's content rewritten in place is recorded at the revision the store reported for the new bytes (W9)")
    void aRewrittenPwcIsRecordedAtTheRevisionTheStoreReported() throws Exception {
        RecordContentStateRecorder recorder = recorder();
        serviceOn(dao("4-after"), recorder).replacePwc(mock(CallContext.class), "bedroom", version(true, "att-1"),
                someContent());
        assertEquals("4-after", recordedRevision(recorder),
                "the rewrite was recorded at a revision other than the one the store reported for its bytes");
    }

    @Test
    @DisplayName("a document's content rewritten in place is recorded at the revision the store reported (W3)")
    void aRewrittenDocumentIsRecordedAtTheRevisionTheStoreReported() throws Exception {
        RecordContentStateRecorder recorder = recorder();
        serviceOn(dao("4-after"), recorder).updateDocumentWithNewStream(mock(CallContext.class), "bedroom",
                version(false, "att-1"), someContent());
        assertEquals("4-after", recordedRevision(recorder),
                "the rewrite was recorded at a revision other than the one the store reported for its bytes");
    }

    @Test
    @DisplayName("a rewrite the store reported no revision for is left unrecorded, not recorded at the revision the node was read with")
    void aRewriteWithNoReportedRevisionIsLeftUnrecorded() throws Exception {
        RecordContentStateRecorder recorder = recorder();
        serviceOn(dao(null), recorder).replacePwc(mock(CallContext.class), "bedroom", version(true, "att-1"), someContent());
        verify(recorder, never()).recordAndClose(any(), any());
        verify(recorder).leaveUnrecorded(any(), anyString());
    }

    @Test
    @DisplayName("content made for a version that had none is recorded at the revision its new row was stored at")
    void contentMadeForAVersionIsRecordedAtItsNewRowsRevision() throws Exception {
        RecordContentStateRecorder recorder = recorder();
        serviceOn(dao("4-after"), recorder).replacePwc(mock(CallContext.class), "bedroom", version(true, null),
                someContent());
        assertEquals("2-att-new", recordedRevision(recorder),
                "the content made was recorded at a revision other than the one its row was stored at");
    }
}
