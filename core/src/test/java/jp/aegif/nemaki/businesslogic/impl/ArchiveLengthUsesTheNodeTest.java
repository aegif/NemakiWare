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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.AttachmentNode;

/**
 * Deleting an attachment must not re-derive a size it was just handed.
 *
 * <h2>What was wrong (ledger C4)</h2>
 *
 * <p>Both deletion paths fetched the attachment node and then preferred
 * {@code getAttachmentActualSize} over the length that node already carried. That second call is a
 * separate {@code getDocument} of the same attachment, and for a gzip-stored attachment CouchDB
 * reports the COMPRESSED size there, so it falls through to downloading the whole binary and
 * counting bytes — immediately before deleting that binary.
 *
 * <p>Measured on the live stack against a gzip-stored 1.3 MB PDF: {@code _attachments.length} was
 * 1,291,901 (compressed) while the node's own length, and the real content, were 1,337,959. The
 * cheap answer was also the correct one.
 *
 * <h2>What these pin</h2>
 *
 * <p>Not the number alone — that would pass with the extra call still there. The assertions are
 * that the expensive path is NOT taken when the node knows, and IS taken when it does not, because
 * an attachment written before the length field existed still needs it.
 */
class ArchiveLengthUsesTheNodeTest {

	private static final String REPO = "bedroom";
	private static final String ATTACHMENT = "att-1";
	private static final long REAL_LENGTH = 1337959L;

	private ContentServiceImpl serviceWith(ContentDaoService dao) {
		ContentServiceImpl service = new ContentServiceImpl();
		service.setContentDaoService(dao);
		return service;
	}

	/** The normal case: the node knows, so nothing else is asked. */
	@Test
	void theNodesOwnLengthIsUsedWithoutASecondLookup() {
		ContentDaoService dao = mock(ContentDaoService.class);
		AttachmentNode node = mock(AttachmentNode.class);
		when(node.getLength()).thenReturn(REAL_LENGTH);

		Long length = serviceWith(dao).lengthForArchive(REPO, node, ATTACHMENT);

		assertEquals(REAL_LENGTH, length);
		verify(dao, never()).getAttachmentActualSize(anyString(), anyString());
	}

	/**
	 * Zero is a LENGTH, not a missing value.
	 *
	 * <p>Found in review: the first version returned the derivation's answer unconditionally when
	 * the node was not strictly positive, so an empty attachment whose derivation also failed was
	 * archived with no size at all. The old code recorded 0 there. This is the regression that
	 * introduced, put back.
	 */
	@Test
	void anEmptyAttachmentIsArchivedAsZeroNotAsUnknown() {
		ContentDaoService dao = mock(ContentDaoService.class);
		when(dao.getAttachmentActualSize(REPO, ATTACHMENT)).thenReturn(null);
		AttachmentNode node = mock(AttachmentNode.class);
		when(node.getLength()).thenReturn(0L);

		assertEquals(0L, serviceWith(dao).lengthForArchive(REPO, node, ATTACHMENT),
				"an empty attachment has a length and it is zero — reporting 'unknown' loses it");
	}

	/**
	 * The opposite sentinel. {@code AttachmentServiceDelegate} records -1 when the uploader could
	 * not state a length, and writing that into an archive record as if it were a size would be
	 * worse than admitting the size is unknown.
	 */
	@Test
	void anUnknownLengthIsNotArchivedAsANegativeNumber() {
		ContentDaoService dao = mock(ContentDaoService.class);
		when(dao.getAttachmentActualSize(REPO, ATTACHMENT)).thenReturn(null);
		AttachmentNode node = mock(AttachmentNode.class);
		when(node.getLength()).thenReturn(-1L);

		org.junit.jupiter.api.Assertions.assertNull(
				serviceWith(dao).lengthForArchive(REPO, node, ATTACHMENT),
				"-1 is the uploader's 'I cannot tell you', not a content length");
	}

	/**
	 * The other half. Without it, "never call the expensive path" would pass the test above and
	 * silently record no size at all for attachments written before the length field existed.
	 */
	@Test
	void anAttachmentWithNoRecordedLengthStillFallsBack() {
		ContentDaoService dao = mock(ContentDaoService.class);
		when(dao.getAttachmentActualSize(REPO, ATTACHMENT)).thenReturn(REAL_LENGTH);
		AttachmentNode node = mock(AttachmentNode.class);
		when(node.getLength()).thenReturn(0L);

		Long length = serviceWith(dao).lengthForArchive(REPO, node, ATTACHMENT);

		assertEquals(REAL_LENGTH, length,
				"a legacy attachment has no stored length, and the expensive derivation is the "
						+ "only way left to get a real number");
		verify(dao, times(1)).getAttachmentActualSize(REPO, ATTACHMENT);
	}
}
