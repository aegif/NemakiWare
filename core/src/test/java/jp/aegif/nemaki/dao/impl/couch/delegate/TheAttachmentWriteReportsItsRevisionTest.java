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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.DocumentResult;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.AttachmentNode;

/**
 * R229: an attachment write puts on the node the revision its bytes were stored at — the place of the write among the writes
 * of the row, which E1 records and the journal orders a row's statements by.
 *
 * <p>Both writes save the row more than once (the metadata, then the bytes), so the revision the node came with, and the one
 * the metadata save left, are not the write's place: a later write of the row reads past them. The bytes' revision is.
 */
class TheAttachmentWriteReportsItsRevisionTest {

    private static AttachmentDaoDelegate delegateOver(CloudantClientWrapper client) {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        when(pool.getClient(anyString())).thenReturn(client);
        return new AttachmentDaoDelegate(pool, new DaoHelper());
    }

    private static Document at(String revision) {
        Document doc = mock(Document.class);
        when(doc.getRev()).thenReturn(revision);
        return doc;
    }

    private static ContentStreamImpl someContent() {
        byte[] bytes = "minutes".getBytes(StandardCharsets.UTF_8);
        return new ContentStreamImpl("minutes.txt", BigInteger.valueOf(bytes.length), "text/plain",
                new ByteArrayInputStream(bytes));
    }

    @Test
    @DisplayName("a rewrite of a row reports the revision its new bytes were stored at, not the one the node was read with")
    void aRewriteReportsTheRevisionOfItsBytes() {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        // Read before the metadata save, read again after it, then the bytes.
        // Made before the stubbing: a mock stubbed inside another's thenReturn is an unfinished stubbing.
        Document read = at("3-read");
        Document afterMetadata = at("4-metadata");
        when(client.get("att-1")).thenReturn(read, afterMetadata);
        when(client.createAttachment(eq("att-1"), eq("4-metadata"), eq("content"), any(), anyString())).thenReturn("5-bytes");
        AttachmentNode node = new AttachmentNode();
        node.setId("att-1");
        node.setRevision("3-read");

        delegateOver(client).updateAttachment("bedroom", node, someContent());

        assertEquals("5-bytes", node.getRevision(),
                "the rewrite did not report the revision its bytes were stored at — E1 would place it among the row's "
                        + "writes by another one");
    }

    @Test
    @DisplayName("a row read back carries the revision it was read at — a copy is placed by it")
    void aRowReadBackCarriesItsRevision() {
        // A check-out's copy is digested from the copy as read back, and placed among its row's writes by that read's revision.
        // The node a read made had none (closing batch 45 — the copy's E1 row stayed open on nb33).
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        jp.aegif.nemaki.model.couch.CouchAttachmentNode row = new jp.aegif.nemaki.model.couch.CouchAttachmentNode();
        row.setId("att-copy");
        row.setRevision("3-read");
        when(client.get(jp.aegif.nemaki.model.couch.CouchAttachmentNode.class, "att-copy")).thenReturn(row);

        assertEquals("3-read", delegateOver(client).getAttachmentRef("bedroom", "att-copy").getRevision(),
                "a row read back did not carry the revision it was read at");
    }

    @Test
    @DisplayName("a new row reports the revision its bytes were stored at")
    void aNewRowReportsTheRevisionOfItsBytes() {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        DocumentResult created = mock(DocumentResult.class);
        when(created.getId()).thenReturn("att-new");
        when(created.getRev()).thenReturn("1-metadata");
        when(client.create(anyMap())).thenReturn(created);
        when(client.createAttachment(eq("att-new"), eq("1-metadata"), eq("content"), any(), anyString()))
                .thenReturn("2-bytes");
        AttachmentNode node = new AttachmentNode();

        delegateOver(client).createAttachment("bedroom", node, someContent());

        assertEquals("2-bytes", node.getRevision(),
                "the new row did not report the revision its bytes were stored at — E1 would place it among the row's "
                        + "writes by another one");
    }
}
