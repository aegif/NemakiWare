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

import jp.aegif.nemaki.businesslogic.impl.ContentServiceImpl;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Document;

import org.apache.chemistry.opencmis.commons.exceptions.CmisStorageException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A document whose attachment row is gone is not copied into a document with no content.
 *
 * <h2>What was wrong</h2>
 *
 * <p>{@code copyAttachment} answers {@code null} for two different things: the source genuinely
 * has no content, and the attachment document the source NAMES is not in the store.
 * {@code checkOut} and {@code checkIn} wrote that answer straight onto the copy. So a document
 * carrying a dangling reference was checked out into a working copy that reports itself as
 * having no content — and on check-in that content-less version became the LATEST one, with the
 * only record of the loss a {@code WARN} line. {@code createDocumentFromSource}, one method
 * away in the same class, refuses in exactly this situation.
 *
 * <p>The read did not fail — {@code AttachmentDaoDelegate.getAttachment} throws for that and
 * says so in its message. What happened is narrower and still not what was reported: the store
 * answered "that attachment document is not here" about a document that says it has one, and
 * the product turned that into the assertion "this version has no content".
 *
 * <h2>Both directions</h2>
 *
 * <p>Refusing is only right where the source claims content. A content-less document is a real
 * thing and checking one out has to keep working, so that is pinned here too — as is the
 * ordinary case where the row IS there, which a refusal written one line too high would break.
 */
class CopyingContentRefusesAMissingRowTest {

    /** Wires the collaborators {@code checkOut} / {@code checkIn} reach before the copy. */
    private static ContentServiceImpl serviceOn(jp.aegif.nemaki.dao.ContentDaoService dao)
            throws Exception {
        ContentServiceImpl service = new ContentServiceImpl();
        // attachmentDelegate is NOT stubbed: initDelegates() builds the real one over this dao,
        // so the test measures the whole sentence — the row is missing, the delegate answers
        // null, the service refuses — rather than a hand-fed null.
        for (String fieldName : new String[]{"contentDaoService", "helper", "aclDelegate",
                "solrUtil", "nemakiCachePool"}) {
            Field f = ContentServiceImpl.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(service, fieldName.equals("contentDaoService") ? dao
                    : mock(f.getType(), org.mockito.Mockito.RETURNS_DEEP_STUBS));
        }
        jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap infoMap =
                mock(jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap.class);
        jp.aegif.nemaki.cmis.factory.info.RepositoryInfo info =
                mock(jp.aegif.nemaki.cmis.factory.info.RepositoryInfo.class);
        when(info.getRootFolderId()).thenReturn("root-id");
        when(infoMap.get("bedroom")).thenReturn(info);
        service.setRepositoryInfoMap(infoMap);
        return service;
    }

    private static jp.aegif.nemaki.dao.ContentDaoService dao() {
        return mock(jp.aegif.nemaki.dao.ContentDaoService.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
    }

    private static Document document(String id, String attachmentNodeId) {
        Document document = new Document();
        document.setId(id);
        document.setType("cmis:document");
        document.setObjectType("cmis:document");
        document.setName("minutes.txt");
        document.setAttachmentNodeId(attachmentNodeId);
        document.setVersionSeriesId("vs-1");
        document.setSubTypeProperties(new ArrayList<>());
        document.setAspects(new ArrayList<>());
        return document;
    }

    @Test
    @DisplayName("checkOut refuses when the attachment the document names is not in the store")
    void checkOutRefusesADanglingReference() throws Exception {
        jp.aegif.nemaki.dao.ContentDaoService dao = dao();
        when(dao.getDocument("bedroom", "doc-1")).thenReturn(document("doc-1", "att-9"));
        // Genuine absence of the attachment DOCUMENT. The delegate's other arms throw.
        when(dao.getAttachment(anyString(), anyString())).thenReturn(null);
        ContentServiceImpl service = serviceOn(dao);

        CmisStorageException refusal = assertThrows(CmisStorageException.class,
                () -> service.checkOut(mock(CallContext.class), "bedroom", "doc-1", null));

        assertTrue(refusal.getMessage().contains("att-9"),
                "the refusal does not name the attachment that is missing: "
                        + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("NOT a finding that the document has no content"),
                "the refusal does not say what it is not: " + refusal.getMessage());
        // The substantive half: no working copy was written. A refusal that arrives after the
        // PWC exists leaves behind the very object this test says must not exist.
        verify(dao, never()).create(anyString(), any(Document.class));
    }

    @Test
    @DisplayName("checkIn refuses rather than making a content-less version the latest one")
    void checkInRefusesADanglingReference() throws Exception {
        jp.aegif.nemaki.dao.ContentDaoService dao = dao();
        when(dao.getDocument("bedroom", "pwc-1")).thenReturn(document("pwc-1", "att-9"));
        when(dao.getDocumentOfLatestVersion("bedroom", "vs-1"))
                .thenReturn(document("doc-1", "att-8"));
        when(dao.getAttachment(anyString(), anyString())).thenReturn(null);
        ContentServiceImpl service = serviceOn(dao);

        // contentStream == null is the PWC-updated-in-place flow: the working copy's bytes ARE
        // the new version's bytes, so "no bytes" here is the loss, not a shape of the call.
        CmisStorageException refusal = assertThrows(CmisStorageException.class,
                () -> service.checkIn(mock(CallContext.class), "bedroom",
                        new Holder<String>("pwc-1"), Boolean.TRUE, null, null, "comment",
                        null, null, null, null));

        assertTrue(refusal.getMessage().contains("att-9"), refusal.getMessage());
        verify(dao, never()).create(anyString(), any(Document.class));
    }

    @Test
    @DisplayName("checkOut refuses when the attachment row is there and its body is not")
    void checkOutRefusesARowWithNoBody() throws Exception {
        // One level further in than the missing row, and it produced something worse: a SECOND
        // empty row. createAttachment skips its body stage for a null stream and returns the new
        // id as a success, so the copy looked like it worked. Found by review.
        jp.aegif.nemaki.dao.ContentDaoService dao = dao();
        when(dao.getDocument("bedroom", "doc-1")).thenReturn(document("doc-1", "att-9"));
        AttachmentNode bodyless = new AttachmentNode();
        bodyless.setName("minutes.txt");
        bodyless.setMimeType("text/plain");
        bodyless.setLength(11L);
        // No setInputStream: this is what getAttachment returns when the CouchDB document
        // carries no `content` attachment.
        when(dao.getAttachment("bedroom", "att-9")).thenReturn(bodyless);
        ContentServiceImpl service = serviceOn(dao);

        CmisStorageException refusal = assertThrows(CmisStorageException.class,
                () -> service.checkOut(mock(CallContext.class), "bedroom", "doc-1", null));

        assertTrue(refusal.getMessage().contains("no content body"),
                "the refusal does not say what was actually missing: " + refusal.getMessage());
        verify(dao, never()).createAttachment(anyString(), any(AttachmentNode.class), any());
        verify(dao, never()).create(anyString(), any(Document.class));
    }

    @Test
    @DisplayName("a document that genuinely has no content is still checked out")
    void aContentLessDocumentStillChecksOut() throws Exception {
        // The over-throw guard. "The source names nothing" and "the store does not have what the
        // source names" are different, and only the second is a reason to refuse.
        jp.aegif.nemaki.dao.ContentDaoService dao = dao();
        when(dao.getDocument("bedroom", "doc-1")).thenReturn(document("doc-1", null));
        Document[] created = new Document[1];
        when(dao.create(eq("bedroom"), any(Document.class))).thenAnswer(inv -> {
            created[0] = inv.getArgument(1);
            created[0].setId("pwc-1");
            return created[0];
        });
        when(dao.update(eq("bedroom"), any(Document.class)))
                .thenAnswer(inv -> inv.getArgument(1));
        // Null version series: checkOut logs and returns the PWC, which is past the copy and
        // enough for this test. Stubbed explicitly because a deep stub would answer a mock.
        when(dao.getVersionSeries(anyString(), anyString())).thenReturn(null);
        ContentServiceImpl service = serviceOn(dao);

        // assertDoesNotThrow, not a bare call: an over-throw guard whose failure arrives as an
        // uncaught exception proves nothing about the guard — the runner refuses to count it,
        // and it is right to.
        Document pwc = assertDoesNotThrow(
                () -> service.checkOut(mock(CallContext.class), "bedroom", "doc-1", null),
                "checking out a document that genuinely has no content was refused");

        assertNull(pwc.getAttachmentNodeId(),
                "a working copy of a content-less document was given content");
        assertEquals("pwc-1", pwc.getId());
    }

    @Test
    @DisplayName("an attachment that IS in the store is copied onto the working copy")
    void aPresentAttachmentIsStillCopied() throws Exception {
        // The second over-throw guard, and the one a refusal written one line too high breaks:
        // the ordinary check-out of an ordinary document with content.
        jp.aegif.nemaki.dao.ContentDaoService dao = dao();
        when(dao.getDocument("bedroom", "doc-1")).thenReturn(document("doc-1", "att-9"));
        AttachmentNode stored = new AttachmentNode();
        stored.setName("minutes.txt");
        stored.setMimeType("text/plain");
        stored.setLength(11L);
        stored.setInputStream(
                new ByteArrayInputStream("the minutes".getBytes(StandardCharsets.UTF_8)));
        when(dao.getAttachment("bedroom", "att-9")).thenReturn(stored);
        when(dao.createAttachment(eq("bedroom"), any(AttachmentNode.class), any()))
                .thenReturn("att-copy");
        when(dao.create(eq("bedroom"), any(Document.class))).thenAnswer(inv -> {
            Document copy = inv.getArgument(1);
            copy.setId("pwc-1");
            return copy;
        });
        when(dao.update(eq("bedroom"), any(Document.class)))
                .thenAnswer(inv -> inv.getArgument(1));
        when(dao.getVersionSeries(anyString(), anyString())).thenReturn(null);
        ContentServiceImpl service = serviceOn(dao);

        Document pwc = assertDoesNotThrow(
                () -> service.checkOut(mock(CallContext.class), "bedroom", "doc-1", null),
                "an ordinary check-out of a document with content was refused");

        assertEquals("att-copy", pwc.getAttachmentNodeId(),
                "an ordinary check-out lost the copied content");
    }
}
