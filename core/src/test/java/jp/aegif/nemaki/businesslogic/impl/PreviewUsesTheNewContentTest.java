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
package jp.aegif.nemaki.businesslogic.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import jp.aegif.nemaki.businesslogic.rendition.RenditionManager;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.util.PropertyManager;

/**
 * A preview must be made from the content that was just written, not the content it replaced.
 *
 * <h2>What was wrong (ledger B1-b)</h2>
 *
 * <p>{@code updateDocumentWithNewStream} fetched the attachment with {@code getAttachment} — which
 * opens the body — handed it to {@code updateAttachment}, and then built the preview
 * {@code ContentStream} from <b>that same node's stream</b>. The stream had been opened before the
 * new bytes were written, so the preview was rendered from the PREVIOUS content and the document
 * was left showing a thumbnail of what it used to be.
 *
 * <p>{@code replacePwc} re-reads a fresh node for exactly this reason, with a comment saying the
 * already-held stream must not be reused. This path did not, and nothing noticed: a preview of the
 * wrong content is still a valid image.
 *
 * <h2>What this pins</h2>
 *
 * <p>The ORDER. Asserting that {@code getAttachment} is called says nothing — the broken version
 * called it too, just earlier. So the assertion is that the body is opened <b>after</b>
 * {@code updateAttachment}, and that the write itself takes the metadata-only call.
 */
class PreviewUsesTheNewContentTest {

	private static final String REPO = "bedroom";
	private static final String ATTACHMENT = "att-1";

	private static Document documentWithAttachment() {
		Document d = new Document();
		d.setId("doc-1");
		d.setAttachmentNodeId(ATTACHMENT);
		return d;
	}

	private ContentServiceImpl serviceWith(ContentDaoService dao, boolean previewEnabled) {
		PropertyManager properties = mock(PropertyManager.class);
		when(properties.readValue(anyString())).thenReturn(String.valueOf(previewEnabled));
		ContentServiceImpl service = new ContentServiceImpl();
		service.setContentDaoService(dao);
		service.setPropertyManager(properties);
		RenditionManager rendition = mock(RenditionManager.class);
		when(rendition.checkConvertible(anyString())).thenReturn(true);
		service.setRenditionManager(rendition);
		return service;
	}

	/** The defect: the body must be opened AFTER the write, or the preview shows the old content. */
	@Test
	void theBodyIsOpenedAfterTheWrite() {
		ContentDaoService dao = mock(ContentDaoService.class);
		AttachmentNode ref = mock(AttachmentNode.class);
		when(dao.getAttachmentRef(REPO, ATTACHMENT)).thenReturn(ref);
		AttachmentNode fresh = mock(AttachmentNode.class);
		when(fresh.getInputStream()).thenReturn(new ByteArrayInputStream("new".getBytes()));
		when(dao.getAttachment(REPO, ATTACHMENT)).thenReturn(fresh);

		ContentStream incoming = mock(ContentStream.class);
		when(incoming.getMimeType()).thenReturn("application/pdf");

		try {
			serviceWith(dao, true).updateDocumentWithNewStream(
					mock(CallContext.class), REPO, documentWithAttachment(), incoming);
		} catch (Exception e) {
			// The tail of the method writes the document and indexes it; those collaborators are
			// not wired. Everything asserted below has already happened.
		}

		InOrder order = inOrder(dao);
		order.verify(dao).getAttachmentRef(REPO, ATTACHMENT);
		order.verify(dao).updateAttachment(eq(REPO), eq(ref), any());
		order.verify(dao).getAttachment(REPO, ATTACHMENT);
	}

	/**
	 * The other half: with previews off, the binary is not downloaded at all. Without this, a
	 * "fix" that simply moved the same unconditional getAttachment later would pass the test above
	 * while still transferring the whole attachment for nothing.
	 */
	@Test
	void withPreviewsOffTheBinaryIsNeverDownloaded() {
		ContentDaoService dao = mock(ContentDaoService.class);
		when(dao.getAttachmentRef(REPO, ATTACHMENT)).thenReturn(mock(AttachmentNode.class));

		try {
			serviceWith(dao, false).updateDocumentWithNewStream(
					mock(CallContext.class), REPO, documentWithAttachment(), mock(ContentStream.class));
		} catch (Exception e) {
			// as above
		}

		verify(dao).getAttachmentRef(REPO, ATTACHMENT);
		verify(dao, never()).getAttachment(anyString(), anyString());
	}
}
