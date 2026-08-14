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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;

import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.AttachmentNode;

/**
 * Reading an attachment must not write to the database.
 *
 * <h2>What was wrong (ledger B2)</h2>
 *
 * <p>{@code ContentServiceImpl.getAttachment} called {@code setStream} whenever the DAO returned a
 * node without a body, with the comment "calling setStream to populate it".
 *
 * <p>{@code setStream} cannot populate anything — it never calls {@code setInputStream}, and its
 * STAGE 2 only uploads a stream the node already carries, which by definition it does not here.
 * What it does do is {@code exists()} + {@code get()} + {@code updatePreservingAttachments()} +
 * {@code get()}: three requests and a metadata WRITE, bumping a revision, on the READ path, once
 * per attachment whose body failed to open.
 *
 * <p>So the call could not achieve its stated purpose and was not free. Every caller of
 * {@code getAttachment} already copes with a null stream, and the DAO is the layer that opens the
 * body — once, where the repository is known, which is what F3 established.
 *
 * <h2>What this pins</h2>
 *
 * <p>That no write is attempted, and that the node still comes back. Asserting only "the node is
 * returned" would pass with the write still there.
 */
class GetAttachmentDoesNotWriteTest {

	private static final String REPO = "bedroom";
	private static final String ATTACHMENT = "att-1";

	private ContentServiceImpl serviceWith(AttachmentNode node, ContentDaoService dao) {
		when(dao.getAttachment(REPO, ATTACHMENT)).thenReturn(node);
		ContentServiceImpl service = new ContentServiceImpl();
		service.setContentDaoService(dao);
		return service;
	}

	/** The case that used to trigger the write. */
	@Test
	void anAttachmentWithoutABodyIsReturnedWithoutWriting() {
		ContentDaoService dao = mock(ContentDaoService.class);
		AttachmentNode node = mock(AttachmentNode.class);
		when(node.getInputStream()).thenReturn(null);

		AttachmentNode returned = serviceWith(node, dao).getAttachment(REPO, ATTACHMENT);

		assertSame(node, returned, "the node itself is still the answer");
		assertNull(returned.getInputStream(),
				"the body could not be opened; saying so is the honest answer, and callers "
						+ "already handle it");
		verify(dao, never()).setStream(anyString(), any(AttachmentNode.class));
	}

	/** The normal case must be untouched — one DAO read, no write. */
	@Test
	void anAttachmentWithABodyIsUnaffected() {
		ContentDaoService dao = mock(ContentDaoService.class);
		AttachmentNode node = mock(AttachmentNode.class);
		when(node.getInputStream()).thenReturn(new ByteArrayInputStream("body".getBytes()));

		AttachmentNode returned = serviceWith(node, dao).getAttachment(REPO, ATTACHMENT);

		assertNotNull(returned.getInputStream());
		verify(dao).getAttachment(REPO, ATTACHMENT);
		verify(dao, never()).setStream(anyString(), any(AttachmentNode.class));
	}

	/** A missing attachment stays missing, and still writes nothing. */
	@Test
	void aMissingAttachmentWritesNothing() {
		ContentDaoService dao = mock(ContentDaoService.class);

		assertNull(serviceWith(null, dao).getAttachment(REPO, ATTACHMENT));

		verify(dao, never()).setStream(anyString(), any(AttachmentNode.class));
	}
}
