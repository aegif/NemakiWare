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
