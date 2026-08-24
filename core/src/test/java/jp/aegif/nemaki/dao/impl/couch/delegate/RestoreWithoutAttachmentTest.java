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
package jp.aegif.nemaki.dao.impl.couch.delegate;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Restoring a document whose archive names no attachment must succeed.
 *
 * <h2>What went wrong</h2>
 *
 * <p>{@code getAttachmentArchive} returns {@code null} by design when the document archive has
 * no {@code attachmentNodeId} — it logs a WARN and returns — and
 * {@code restoreDocumentWithArchive} passed that straight into {@code restoreAttachment}, which
 * dereferenced it. The NPE was caught by the method's own catch and rethrown as "Failed to
 * restore attachment from archive", which propagated out of {@code restoreArchive}: the REST
 * caller was told the restore had failed while the document was, in fact, already back.
 *
 * <p>Found by the first restore drill (2026-08-24), not by a unit test — the drill's whole
 * purpose. This pins it.
 */
class RestoreWithoutAttachmentTest {

    @Test
    @DisplayName("a document that names no attachment: nothing to restore")
    void namesNoAttachmentIsNone() {
        jp.aegif.nemaki.model.Archive doc = new jp.aegif.nemaki.model.Archive();
        doc.setId("arc-1");
        doc.setAttachmentNodeId(null);
        ArchiveDaoDelegate delegate = new ArchiveDaoDelegate(mock(CloudantClientPool.class),
                mock(RepositoryInfoMap.class), null);

        assertInstanceOf(ArchiveDaoDelegate.AttachmentArchiveLookup.None.class,
                delegate.lookupAttachmentArchive("bedroom", doc),
                "the case the restore drill hit: a normal document with no attachment");
    }

    @Test
    @DisplayName("a document that NAMES an attachment the archive does not hold is UNAVAILABLE")
    void namedButMissingAttachmentIsUnavailable() {
        // The hole the first fix opened (external review): guarding the dereference made this
        // case — and an unreadable archive database — restore "successfully" without content.
        // Absent and could-not-read must not be the same answer (roadmap §2-1, at the restore
        // door).
        jp.aegif.nemaki.model.Archive doc = new jp.aegif.nemaki.model.Archive();
        doc.setId("arc-1");
        doc.setAttachmentNodeId("att-node-1");
        CloudantClientPool pool = mock(CloudantClientPool.class);
        RepositoryInfoMap infoMap = mock(RepositoryInfoMap.class);
        when(infoMap.getArchiveId("bedroom")).thenReturn("bedroom_archive");
        jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper client =
                mock(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper.class);
        when(pool.getClient("bedroom_archive")).thenReturn(client);
        when(client.queryView(any(), any(), any(), any())).thenReturn(java.util.List.of());
        ArchiveDaoDelegate delegate = new ArchiveDaoDelegate(pool, infoMap, null);

        assertInstanceOf(ArchiveDaoDelegate.AttachmentArchiveLookup.Unavailable.class,
                delegate.lookupAttachmentArchive("bedroom", doc),
                "a document whose content is missing from the archive restored as a success");
    }

    @Test
    @DisplayName("a lookup that THREW is UNAVAILABLE, not 'there is none'")
    void aFailedLookupIsUnavailable() {
        jp.aegif.nemaki.model.Archive doc = new jp.aegif.nemaki.model.Archive();
        doc.setId("arc-1");
        doc.setAttachmentNodeId("att-node-1");
        CloudantClientPool pool = mock(CloudantClientPool.class);
        RepositoryInfoMap infoMap = mock(RepositoryInfoMap.class);
        when(infoMap.getArchiveId("bedroom")).thenReturn("bedroom_archive");
        when(pool.getClient("bedroom_archive")).thenThrow(new RuntimeException("db down"));
        ArchiveDaoDelegate delegate = new ArchiveDaoDelegate(pool, infoMap, null);

        assertInstanceOf(ArchiveDaoDelegate.AttachmentArchiveLookup.Unavailable.class,
                delegate.lookupAttachmentArchive("bedroom", doc),
                "an unreadable archive database read as 'this document has no attachment'");
    }

    @Test
    @DisplayName("NOTHING is restored when the attachment cannot be got")
    void nothingIsRestoredWhenTheAttachmentIsUnavailable() {
        // The ordering, not just the verdict. Restoring the document FIRST and then throwing
        // reproduces exactly the defect this work started from: the document is back and the
        // caller is told the restore failed (external review of the first fix). The earlier
        // tests here checked only the lookup, so they passed with the wrong order.
        jp.aegif.nemaki.model.Archive doc = new jp.aegif.nemaki.model.Archive();
        doc.setId("arc-1");
        doc.setAttachmentNodeId("att-node-1");
        CloudantClientPool pool = mock(CloudantClientPool.class);
        RepositoryInfoMap infoMap = mock(RepositoryInfoMap.class);
        when(infoMap.getArchiveId("bedroom")).thenReturn("bedroom_archive");
        when(pool.getClient("bedroom_archive")).thenThrow(new RuntimeException("db down"));
        ArchiveDaoDelegate delegate =
                org.mockito.Mockito.spy(new ArchiveDaoDelegate(pool, infoMap, null));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> delegate.restoreDocumentWithArchive("bedroom", doc));

        org.mockito.Mockito.verify(delegate, org.mockito.Mockito.never())
                .restoreContent(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("a null attachment archive is nothing to restore, not a failure")
    void nullAttachmentArchiveIsNotAFailure() {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        RepositoryInfoMap infoMap = mock(RepositoryInfoMap.class);
        ArchiveDaoDelegate delegate = new ArchiveDaoDelegate(pool, infoMap, null);

        assertDoesNotThrow(() -> delegate.restoreAttachment("bedroom", null),
                "restoring a document with no archived attachment reported a failure; the "
                        + "document is back and the caller is told it is not");

        // And it must not have gone looking: reaching the pool at all means the guard was
        // placed after the first dereference rather than before it.
        verifyNoInteractions(pool);
        verifyNoInteractions(infoMap);
    }
}
