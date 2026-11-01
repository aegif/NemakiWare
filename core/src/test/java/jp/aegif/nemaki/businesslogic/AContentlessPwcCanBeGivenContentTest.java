package jp.aegif.nemaki.businesslogic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jp.aegif.nemaki.businesslogic.impl.ContentServiceImpl;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.util.PropertyManager;

/**
 * A PWC of a document with no content, and a document with no content, can be given content.
 *
 * <p>replacePwc and appendAttachment read the attachment by its id, null for a document with no content, and the DAO
 * refuses a null id since 3.4's fail-closed reads («could not be read — NOT a finding that the document has no
 * attachment»): setContentStream on such a PWC, and appendContentStream on such a document, answered 500 with that untrue
 * message (closing batch 36 review, subagent P2-1 — the flow R224 opened). The DAO here refuses a null id as the real one
 * does.
 */
class AContentlessPwcCanBeGivenContentTest {

    private static ContentDaoService dao() {
        ContentDaoService dao = mock(ContentDaoService.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        IllegalStateException unreadable = new IllegalStateException(
                "the attachment of a document could not be read; this is NOT a finding that the document has no attachment");
        when(dao.getAttachmentRef(anyString(), isNull())).thenThrow(unreadable);
        when(dao.getAttachment(anyString(), isNull())).thenThrow(unreadable);
        when(dao.createAttachment(anyString(), any(AttachmentNode.class), any(ContentStream.class))).thenReturn("att-new");
        when(dao.update(anyString(), any(Document.class))).thenAnswer(invocation -> invocation.getArgument(1));
        return dao;
    }

    /** The collaborators these writes reach, as TheCheckInCheckSeesTheVersionAsStoredTest wires them; previews off. */
    private static ContentServiceImpl serviceOn(ContentDaoService dao) throws Exception {
        ContentServiceImpl service = new ContentServiceImpl();
        for (String fieldName : new String[]{"contentDaoService", "helper", "aclDelegate", "solrUtil", "nemakiCachePool"}) {
            Field f = ContentServiceImpl.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(service, fieldName.equals("contentDaoService") ? dao
                    : mock(f.getType(), org.mockito.Mockito.RETURNS_DEEP_STUBS));
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

    private static Document contentless(String id, boolean pwc) {
        Document document = new Document();
        document.setId(id);
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

    @Test
    @DisplayName("a PWC of a document with no content can be given content")
    void aContentlessPwcCanBeGivenContent() throws Exception {
        ContentDaoService dao = dao();
        ContentServiceImpl service = serviceOn(dao);
        Document pwc = contentless("pwc-1", true);

        assertDoesNotThrow(() -> service.replacePwc(mock(CallContext.class), "bedroom", pwc, someContent()),
                "setContentStream on a PWC with no content failed — it read the attachment by a null id");
        verify(dao, times(1)).createAttachment(anyString(), any(AttachmentNode.class), any(ContentStream.class));
        ArgumentCaptor<Document> written = ArgumentCaptor.forClass(Document.class);
        verify(dao).update(anyString(), written.capture());
        assertEquals("att-new", written.getValue().getAttachmentNodeId(), "the PWC was stored without the content it was given");
    }

    @Test
    @DisplayName("a document with no content can be appended to, and the appended stream is its content")
    void aContentlessDocumentCanBeAppendedTo() throws Exception {
        ContentDaoService dao = dao();
        when(dao.getDocument("bedroom", "doc-1")).thenAnswer(invocation -> contentless("doc-1", false));
        ContentServiceImpl service = serviceOn(dao);

        assertDoesNotThrow(() -> service.appendAttachment(mock(CallContext.class), "bedroom", new Holder<>("doc-1"),
                new Holder<>(null), someContent(), true, null),
                "appendContentStream on a document with no content failed — it read the attachment by a null id");
        verify(dao, times(1)).createAttachment(anyString(), any(AttachmentNode.class), any(ContentStream.class));
        ArgumentCaptor<Document> written = ArgumentCaptor.forClass(Document.class);
        verify(dao).update(anyString(), written.capture());
        assertEquals("att-new", written.getValue().getAttachmentNodeId(),
                "the document was stored without the content appended to it");
    }
}
