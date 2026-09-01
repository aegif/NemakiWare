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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;

import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.couch.CouchAttachmentNode;

/**
 * An attachment that could not be READ is never reported as content that is not there.
 *
 * <h2>The three doors and who walks through them</h2>
 *
 * <p>{@code getAttachmentRef} answering null, {@code getAttachment} handing back a node whose
 * stream is null, and {@code getAttachmentActualSize} answering null all say the same thing to
 * their callers: <em>this document has no bytes</em>. The fixity scan then reports a record
 * with nothing to check; the exporters wrote a metadata sidecar with no file beside it; and the
 * size door is worse than the others, because a null size is read as "use the length the
 * document claims" — which is the very number the fixity check exists to corroborate, so the
 * check degenerates into comparing a number with itself.
 *
 * <p>Genuine absence keeps its answer, and that boundary is measured here too: the wrapper
 * raises {@link CmisObjectNotFoundException} when the document really carries no {@code
 * content} attachment, and only that case still produces a node with no stream.
 */
class AttachmentReadFailuresAreNotAbsenceTest {

    private static final String REPO = "bedroom";

    private CloudantClientWrapper client;
    private AttachmentDaoDelegate delegate;

    private void wire() {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        client = mock(CloudantClientWrapper.class);
        when(pool.getClient(REPO)).thenReturn(client);
        delegate = new AttachmentDaoDelegate(pool, mock(DaoHelper.class));
    }

    private static CouchAttachmentNode storedNode() {
        CouchAttachmentNode node = new CouchAttachmentNode();
        node.setId("att-1");
        node.setName("report.pdf");
        node.setMimeType("application/pdf");
        node.setLength(1234L);
        return node;
    }

    @Test
    @DisplayName("a failed metadata read refuses; a document that is not there is still null")
    void aFailedRefReadRefuses() {
        wire();
        when(client.get(eq(CouchAttachmentNode.class), eq("att-1")))
                .thenThrow(new RuntimeException("connection reset"));
        assertThrows(IllegalStateException.class,
                () -> delegate.getAttachmentRef(REPO, "att-1"),
                "a failed metadata read answered 'this document has no attachment', which "
                        + "the fixity scan records as a record with nothing to check");

        wire();
        when(client.get(eq(CouchAttachmentNode.class), eq("att-2"))).thenReturn(null);
        assertNull(delegate.getAttachmentRef(REPO, "att-2"),
                "an attachment that genuinely does not exist must still read as absent");
    }

    @Test
    @DisplayName("a body that could not be read refuses instead of a node with no stream")
    void aFailedBodyReadRefuses() {
        wire();
        when(client.get(eq(CouchAttachmentNode.class), eq("att-1"))).thenReturn(storedNode());
        when(client.getAttachment(eq("att-1"), eq("content")))
                .thenThrow(new org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException(
                        "the body could not be fetched"));

        assertThrows(RuntimeException.class,
                () -> delegate.getAttachment(REPO, "att-1"),
                "the node came back with a null stream, and every exporter wrote its "
                        + "metadata with no bytes beside it");
    }

    @Test
    @DisplayName("a document that carries no content attachment still yields a stream-less node")
    void aGenuinelyBodilessDocumentIsStillAnswered() {
        wire();
        when(client.get(eq(CouchAttachmentNode.class), eq("att-1"))).thenReturn(storedNode());
        when(client.getAttachment(eq("att-1"), eq("content")))
                .thenThrow(new CmisObjectNotFoundException("no such attachment"));

        AttachmentNode node = delegate.getAttachment(REPO, "att-1");
        assertNotNull(node, "genuine absence of the body was turned into a refusal");
        assertNull(node.getInputStream());
    }

    @Test
    @DisplayName("a readable attachment still comes back with its stream — the control")
    void aReadableAttachmentStillWorks() {
        wire();
        when(client.get(eq(CouchAttachmentNode.class), eq("att-1"))).thenReturn(storedNode());
        when(client.getAttachment(eq("att-1"), eq("content")))
                .thenReturn(new ByteArrayInputStream("hello".getBytes()));

        AttachmentNode node = delegate.getAttachment(REPO, "att-1");
        assertNotNull(node);
        assertNotNull(node.getInputStream(), "the refusal arms broke the ordinary read");
    }

    @Test
    @DisplayName("a size that could not be measured refuses — it is not the recorded length")
    void aFailedSizeReadRefuses() {
        wire();
        when(client.getAttachmentSize(anyString(), anyString()))
                .thenThrow(new RuntimeException("connection reset"));

        assertThrows(IllegalStateException.class,
                () -> delegate.getAttachmentActualSize(REPO, "att-1"),
                "the stored size could not be read and the caller fell back to the length "
                        + "the document itself claims — the check compared a number with itself");
    }

    @Test
    @DisplayName("a measured size is returned; a document with no body is still null")
    void aMeasuredSizeIsStillReturned() {
        wire();
        when(client.getAttachmentSize(eq("att-1"), eq("content"))).thenReturn(1234L);
        assertEquals(1234L, delegate.getAttachmentActualSize(REPO, "att-1"));

        when(client.getAttachmentSize(eq("att-2"), eq("content"))).thenReturn(null);
        assertNull(delegate.getAttachmentActualSize(REPO, "att-2"),
                "a document that carries no content attachment must still read as having "
                        + "no measurable size");
    }

    @Test
    @DisplayName("no client for the repository refuses rather than answering 'no size'")
    void anAbsentClientRefusesTheSize() {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        when(pool.getClient(REPO)).thenReturn(null);
        AttachmentDaoDelegate d = new AttachmentDaoDelegate(pool, mock(DaoHelper.class));

        assertThrows(IllegalStateException.class,
                () -> d.getAttachmentActualSize(REPO, "att-1"));
    }
}
