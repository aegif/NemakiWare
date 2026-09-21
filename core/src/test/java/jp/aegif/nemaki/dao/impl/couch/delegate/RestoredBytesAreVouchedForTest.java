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

import com.ibm.cloud.cloudant.v1.model.Attachment;
import com.ibm.cloud.cloudant.v1.model.Document;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.Archive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a restore reports about the bytes it wrote back (W11, E1) — three answers, not two.
 *
 * <p>NOTHING (the archive carried no binary), bytes vouched for (the digest taken as they went
 * past, and CouchDB stored exactly that many), and bytes NOT vouched for (they went past, but
 * the confirmation could not say what landed). The first version collapsed the third into the
 * first: a confirmation read that did not answer after a successful PUT reported NOTHING, and
 * the caller abandoned its journal row over bytes that were back (Codex review, P1).
 */
class RestoredBytesAreVouchedForTest {

    private static final byte[] BYTES = "the archived bytes".getBytes(StandardCharsets.UTF_8);

    private static String sha256Hex(byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out = new StringBuilder();
        for (byte b : hash) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    private static Archive attachmentArchive() {
        Archive archive = new Archive();
        archive.setId("arc-att");
        archive.setOriginalId("att-1");
        return archive;
    }

    /** A restore whose archive holds {@code BYTES}, with the confirmation read answering as given. */
    private static final class Fixture {
        final CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        final CloudantClientWrapper archiveClient = mock(CloudantClientWrapper.class);
        final ArchiveDaoDelegate delegate;

        Fixture(Document afterPut, boolean binaryInArchive) {
            CloudantClientPool pool = mock(CloudantClientPool.class);
            RepositoryInfoMap infoMap = mock(RepositoryInfoMap.class);
            when(infoMap.getArchiveId("bedroom")).thenReturn("bedroom_archive");
            when(pool.getClient("bedroom")).thenReturn(client);
            when(pool.getClient("bedroom_archive")).thenReturn(archiveClient);

            Document archived = mock(Document.class);
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("mimeType", "text/plain");
            when(archived.getProperties()).thenReturn(props);
            Map<String, Attachment> stubs = new LinkedHashMap<>();
            if (binaryInArchive) {
                stubs.put("content", mock(Attachment.class));
            }
            when(archived.getAttachments()).thenReturn(stubs);
            when(archiveClient.get("arc-att")).thenReturn(archived);
            if (binaryInArchive) {
                when(archiveClient.getAttachment("arc-att", "content"))
                        .thenReturn(new ByteArrayInputStream(BYTES));
            } else {
                when(archiveClient.getAttachment("arc-att", "content"))
                        .thenThrow(new RuntimeException("404 not found"));
            }

            Document beforePut = mock(Document.class);
            when(beforePut.getRev()).thenReturn("1-abc");
            when(client.get("att-1")).thenReturn(beforePut).thenReturn(afterPut);
            when(client.purgeTombstone("att-1")).thenReturn(true);
            // The PUT consumes the stream, which is what makes the digest a digest of what went
            // past. A mock that did not read would count zero bytes.
            doAnswer(invocation -> {
                ((InputStream) invocation.getArgument(3)).readAllBytes();
                return "2-def";
            }).when(client).createAttachment(anyString(), any(), eq("content"), any(), anyString());
            delegate = new ArchiveDaoDelegate(pool, infoMap, null);
        }
    }

    private static Document confirming(long storedLength) {
        Attachment stored = mock(Attachment.class);
        when(stored.length()).thenReturn(storedLength);
        when(stored.contentType()).thenReturn("text/plain");
        Map<String, Attachment> atts = new LinkedHashMap<>();
        atts.put("content", stored);
        Document doc = mock(Document.class);
        when(doc.getAttachments()).thenReturn(atts);
        when(doc.getRev()).thenReturn("2-def");
        when(doc.getProperties()).thenReturn(new LinkedHashMap<>());
        return doc;
    }

    @Test
    @DisplayName("bytes that went past are never NOTHING, even when the confirmation read does not answer")
    void bytesThatWentPastAreNeverNothing() {
        // The P1: PUT succeeded, the read-back returned null. The old answer was NOTHING, and
        // the caller abandoned the row as "no binary was written" — over bytes that are back.
        ContentDaoService.RestoredBytes restored = new Fixture(null, true).delegate
                .restoreAttachmentRecording("bedroom", attachmentArchive());

        assertTrue(restored.wroteBytes(),
                "the PUT completed and the restore reported NOTHING. 'Could not confirm what "
                        + "landed' was turned into 'nothing was written', which closes the "
                        + "journal row as abandoned over a version whose bytes are back");
        assertNull(restored.contentDigest(),
                "the confirmation did not answer, so the digest must not be vouched for");
        assertEquals("att-1", restored.attachmentId());
    }

    @Test
    @DisplayName("the digest is vouched for when CouchDB stored exactly the bytes that went past")
    void theDigestIsVouchedForWhenTheLengthsAgree() throws Exception {
        ContentDaoService.RestoredBytes restored = new Fixture(confirming(BYTES.length), true)
                .delegate.restoreAttachmentRecording("bedroom", attachmentArchive());

        assertTrue(restored.wroteBytes());
        assertEquals(sha256Hex(BYTES), restored.contentDigest(),
                "the digest is not the SHA-256 of the bytes that went through the PUT");
        assertEquals(BYTES.length, restored.length());
    }

    @Test
    @DisplayName("a stored length that differs from what went past leaves the digest unvouched — but the bytes still went")
    void aLengthMismatchIsNotVouchedFor() {
        ContentDaoService.RestoredBytes restored = new Fixture(confirming(BYTES.length + 1), true)
                .delegate.restoreAttachmentRecording("bedroom", attachmentArchive());

        assertTrue(restored.wroteBytes());
        assertNull(restored.contentDigest(),
                "CouchDB stored a different number of bytes than were counted, and the digest "
                        + "of what went past was vouched for anyway");
    }

    @Test
    @DisplayName("an archive with no binary is NOTHING — a known outcome, not an unreported one")
    void noBinaryIsNothing() {
        ContentDaoService.RestoredBytes restored = new Fixture(null, false).delegate
                .restoreAttachmentRecording("bedroom", attachmentArchive());

        assertFalse(restored.wroteBytes(), "no binary was in the archive and the restore "
                + "reported bytes written");
        assertEquals(ContentDaoService.RestoredBytes.NOTHING, restored);
    }
}
