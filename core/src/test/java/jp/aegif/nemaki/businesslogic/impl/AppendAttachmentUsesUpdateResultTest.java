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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Document;

/**
 * Appending content must not read the document back to learn what it just wrote.
 *
 * <h2>What was wrong (ledger V4)</h2>
 *
 * <p>{@code appendAttachment} fetched the SAME content document three times: once at entry for the
 * attachment node id, once before writing the change token, and once more AFTER
 * {@code contentDaoService.update(...)} to read the token and id back.
 *
 * <p>The third was answerable without asking. The DAO's {@code update} returns the object it just
 * wrote, converted, carrying the new revision ({@code ContentDaoServiceImpl:2161-2166}) — the same
 * return {@code deleteContentStream} already uses for its holder, its Solr index and its change
 * event. This is the CMIS {@code appendContentStream} operation, so a chunked upload pays whatever
 * it costs once per chunk.
 *
 * <p><b>Only the third was removed.</b> The second is a different question — whether the document
 * read at entry is still safely updatable after the attachment write — and is left alone.
 *
 * <h2>What this pins</h2>
 *
 * <p>Both halves. That the read-back is gone (count the reads), and that the value now flowing into
 * the holders comes from the update's return — a change that dropped the read but kept using a
 * stale object would satisfy the count alone.
 */
class AppendAttachmentUsesUpdateResultTest {

	private static final String REPO = "bedroom";
	private static final String DOC = "doc-1";
	private static final String ATTACHMENT = "att-1";
	private static final String TOKEN_FROM_UPDATE = "token-written-by-update";

	@Test
	void theDocumentIsNotReadBackAfterTheUpdate() {
		ContentDaoService dao = mock(ContentDaoService.class);

		Document stored = new Document();
		stored.setId(DOC);
		stored.setAttachmentNodeId(ATTACHMENT);
		when(dao.getDocument(REPO, DOC)).thenReturn(stored);

		AttachmentNode node = mock(AttachmentNode.class);
		when(node.getInputStream()).thenReturn(new ByteArrayInputStream("old".getBytes()));
		when(node.getLength()).thenReturn(3L);
		when(node.getMimeType()).thenReturn("text/plain");
		when(dao.getAttachment(REPO, ATTACHMENT)).thenReturn(node);

		// What update() hands back — deliberately DIFFERENT from `stored`, so a change that kept
		// using the pre-update object would fail the assertion rather than pass by coincidence.
		Document written = new Document();
		written.setId(DOC);
		written.setAttachmentNodeId(ATTACHMENT);
		written.setChangeToken(TOKEN_FROM_UPDATE);
		when(dao.update(anyString(), any(Document.class))).thenReturn(written);

		ContentStream incoming = mock(ContentStream.class);
		when(incoming.getStream()).thenReturn(new ByteArrayInputStream("new".getBytes()));
		when(incoming.getLength()).thenReturn(3L);

		ContentServiceImpl service = new ContentServiceImpl();
		service.setContentDaoService(dao);

		Holder<String> objectId = new Holder<>(DOC);
		Holder<String> changeToken = new Holder<>("token-before");

		try {
			service.appendAttachment(mock(CallContext.class), REPO, objectId, changeToken, incoming,
					true, null);
		} catch (Exception e) {
			// The tail indexes into Solr and writes a change event; those collaborators are not
			// wired. The holders are set before that.
		}

		assertEquals(TOKEN_FROM_UPDATE, changeToken.getValue(),
				"the token has to come from what update() returned; reading it back was the third "
						+ "GET of a document this method had just written");
		// Two reads remain by design: the attachment id at entry, and the fresh copy taken before
		// the token is written. Three would mean the read-back is back.
		verify(dao, times(2)).getDocument(REPO, DOC);
	}
}
