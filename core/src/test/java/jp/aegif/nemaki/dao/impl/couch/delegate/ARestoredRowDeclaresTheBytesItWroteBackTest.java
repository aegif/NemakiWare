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
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A restored attachment row declares the bytes the restore wrote back — R241.
 *
 * <p>The row is kept with {@code encoding: gzip}, and its stub {@code length} is then the size of the encoded bytes. The
 * restore copied that into the row's {@code length}, which is what a CMIS read declares, and the read ended short of it:
 * measured on nb33, a document of 3 bytes restored with {@code length} 23 and a Browser-binding read stopped after 3
 * (for application/octet-stream as for text/plain).
 *
 * <p>The example discriminates both wrong answers. The stub's length is the encoded size, and so is the archived row's own
 * {@code length} — which is what a row restored by an earlier build carries, deleted again and archived. A restore that
 * copied the stub, and one that left the archived length in place, both declare that size; only the bytes counted going
 * past are right.
 */
class ARestoredRowDeclaresTheBytesItWroteBackTest {

    private static final byte[] BYTES = "the archived bytes".getBytes(StandardCharsets.UTF_8);

    /** What the encoded row reports — not the bytes, and not equal to them. */
    private static final long ENCODED_LENGTH = BYTES.length + 20L;

    @Test
    @DisplayName("the restored row's length is the bytes written back, not the gzip stub's length or the archived one")
    void theRestoredRowDeclaresTheBytesWrittenBack() {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        CloudantClientWrapper archiveClient = mock(CloudantClientWrapper.class);
        CloudantClientPool pool = mock(CloudantClientPool.class);
        RepositoryInfoMap infoMap = mock(RepositoryInfoMap.class);
        when(infoMap.getArchiveId("bedroom")).thenReturn("bedroom_archive");
        when(pool.getClient("bedroom")).thenReturn(client);
        when(pool.getClient("bedroom_archive")).thenReturn(archiveClient);

        // The archived row: the encoded size in its own length, as an earlier restore wrote it.
        Map<String, Object> archivedProps = new LinkedHashMap<>();
        archivedProps.put("mimeType", "application/octet-stream");
        archivedProps.put("length", ENCODED_LENGTH);
        archivedProps.put("actualLength", ENCODED_LENGTH);
        Document archived = mock(Document.class);
        when(archived.getProperties()).thenReturn(archivedProps);
        Map<String, Attachment> archivedStubs = new LinkedHashMap<>();
        archivedStubs.put("content", encodedStub());
        when(archived.getAttachments()).thenReturn(archivedStubs);
        when(archiveClient.get("arc-att")).thenReturn(archived);
        when(archiveClient.getAttachment("arc-att", "content")).thenReturn(new ByteArrayInputStream(BYTES));

        // The restored row as CouchDB answers after the PUT: the archived fields copied, the stub encoded.
        Document beforePut = mock(Document.class);
        when(beforePut.getRev()).thenReturn("1-abc");
        Document afterPut = mock(Document.class);
        when(afterPut.getRev()).thenReturn("2-def");
        when(afterPut.getProperties()).thenReturn(new LinkedHashMap<>(archivedProps));
        Map<String, Attachment> storedStubs = new LinkedHashMap<>();
        storedStubs.put("content", encodedStub());
        when(afterPut.getAttachments()).thenReturn(storedStubs);
        when(client.get("att-1")).thenReturn(beforePut).thenReturn(afterPut);
        when(client.purgeTombstone("att-1")).thenReturn(true);
        // The PUT consumes the stream, which is what makes the count a count of what went past.
        doAnswer(invocation -> {
            ((InputStream) invocation.getArgument(3)).readAllBytes();
            return "2-def";
        }).when(client).createAttachment(anyString(), any(), eq("content"), any(), anyString());

        Archive archive = new Archive();
        archive.setId("arc-att");
        archive.setOriginalId("att-1");
        ArchiveDaoDelegate delegate = new ArchiveDaoDelegate(pool, infoMap, null);

        ContentDaoService.RestoredBytes restored = assertDoesNotThrow(
                () -> delegate.restoreAttachmentRecording("bedroom", archive),
                "the restore threw on an encoded row, so what it declares was never measured");
        assertTrue(restored.wroteBytes(), "the bytes went past and the restore reported none written");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> updates = ArgumentCaptor.forClass(Map.class);
        verify(client, atLeastOnce()).update(updates.capture());
        List<Map<String, Object>> written = updates.getAllValues();
        Map<String, Object> last = written.get(written.size() - 1);

        assertInstanceOf(Number.class, last.get("length"),
                "the restored row was written without a numeric length: " + last.get("length"));
        assertEquals(BYTES.length, ((Number) last.get("length")).longValue(),
                "the restored row declares " + last.get("length") + " bytes and the restore wrote back "
                        + BYTES.length + ". A CMIS read declares the row's length and ends after the bytes, so it "
                        + "ends short of what it declared (" + ENCODED_LENGTH + " is the gzip stub's length)");
        assertInstanceOf(Number.class, last.get("actualLength"),
                "the restored row was written without a numeric actualLength: " + last.get("actualLength"));
        assertEquals(BYTES.length, ((Number) last.get("actualLength")).longValue(),
                "the restored row's actualLength is " + last.get("actualLength") + ", not the "
                        + BYTES.length + " bytes written back");
    }

    private static Attachment encodedStub() {
        Attachment stub = mock(Attachment.class);
        when(stub.length()).thenReturn(ENCODED_LENGTH);
        when(stub.encodedLength()).thenReturn(ENCODED_LENGTH);
        when(stub.encoding()).thenReturn("gzip");
        when(stub.contentType()).thenReturn("application/octet-stream");
        return stub;
    }
}
