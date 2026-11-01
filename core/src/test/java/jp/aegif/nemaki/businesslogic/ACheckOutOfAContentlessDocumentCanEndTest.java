package jp.aegif.nemaki.businesslogic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;

import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.impl.ContentServiceImpl;
import jp.aegif.nemaki.model.Document;

/**
 * R224: a check-out of a document with no content can be ended — checked in or cancelled.
 *
 * <p>cancelCheckOut deleted the PWC's attachment by its id, null for a document with no content, and the DAO refuses a null
 * id since 3.4's fail-closed reads («Failed to read document 'null' … this is NOT a finding that it does not exist»). So the
 * PWC could not be cancelled, and a check-in — which cancels the check-out after it has made the new version — answered 500
 * with the version made, the PWC left and the series still checked out; each retry made one more version (measured on
 * bedroom, closing batch 28; a 3.4.0 regression). The DAO here refuses a null id as the real one does.
 */
class ACheckOutOfAContentlessDocumentCanEndTest {

    /** The collaborators checkIn and cancelCheckOut reach, as CopyingContentRefusesAMissingRowTest wires them. */
    private static ContentServiceImpl serviceOn(jp.aegif.nemaki.dao.ContentDaoService dao) throws Exception {
        ContentServiceImpl service = new ContentServiceImpl();
        for (String fieldName : new String[]{"contentDaoService", "helper", "aclDelegate", "solrUtil", "nemakiCachePool"}) {
            Field f = ContentServiceImpl.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(service, fieldName.equals("contentDaoService") ? dao
                    : mock(f.getType(), org.mockito.Mockito.RETURNS_DEEP_STUBS));
        }
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
        document.setVersionSeriesId("vs-1");
        document.setPrivateWorkingCopy(pwc);
        document.setSubTypeProperties(new ArrayList<>());
        document.setAspects(new ArrayList<>());
        document.setSecondaryIds(new ArrayList<>());
        return document;
    }

    /** A DAO that refuses a null id, as CloudantClientWrapper does since 3.4 (258b13bc3). */
    private static jp.aegif.nemaki.dao.ContentDaoService dao() {
        jp.aegif.nemaki.dao.ContentDaoService dao = mock(jp.aegif.nemaki.dao.ContentDaoService.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
        doThrow(new CmisRuntimeException("Failed to read document 'null' from database 'bedroom'; this is NOT a finding "
                + "that it does not exist")).when(dao).delete(anyString(), isNull());
        when(dao.getDocument("bedroom", "pwc-1")).thenReturn(contentless("pwc-1", true));
        when(dao.getDocumentOfLatestVersion("bedroom", "vs-1")).thenReturn(contentless("doc-1", false));
        return dao;
    }

    @Test
    @DisplayName("a check-out of a document with no content can be cancelled (R224)")
    void aContentlessCheckOutCanBeCancelled() throws Exception {
        jp.aegif.nemaki.dao.ContentDaoService dao = dao();
        ContentServiceImpl service = serviceOn(dao);
        assertDoesNotThrow(() -> service.cancelCheckOut(mock(CallContext.class), "bedroom", "pwc-1", null),
                "cancelCheckOut of a PWC with no content failed — it deleted the attachment by a null id");
        verify(dao).delete("bedroom", "pwc-1");
        verify(dao, never()).delete(anyString(), isNull());
    }

    @Test
    @DisplayName("a check-out of a document with no content can be checked in, with one version made (R224)")
    void aContentlessCheckOutCanBeCheckedIn() throws Exception {
        jp.aegif.nemaki.dao.ContentDaoService dao = dao();
        ContentServiceImpl service = serviceOn(dao);
        assertDoesNotThrow(() -> service.checkIn(mock(CallContext.class), "bedroom", new Holder<>("pwc-1"), Boolean.TRUE,
                null, null, "comment", null, null, null, null),
                "checkIn of a PWC with no content failed after it had made the new version");
        verify(dao, times(1)).create(anyString(), any(Document.class));
        verify(dao).delete("bedroom", "pwc-1");
        verify(dao, never()).delete(anyString(), isNull());
    }
}
