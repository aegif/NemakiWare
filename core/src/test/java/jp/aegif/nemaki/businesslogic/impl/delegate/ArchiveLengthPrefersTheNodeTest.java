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
package jp.aegif.nemaki.businesslogic.impl.delegate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.dao.ContentDaoService;

/**
 * The archive record's size must come from the node already fetched.
 *
 * <h2>Why this exists separately (ledger C4)</h2>
 *
 * <p>The two deletion paths in {@code ContentServiceImpl} were corrected first, and the test for
 * them called the helper directly. That left this call site — the archive writer — with the same
 * inverted preference and its own copy of the code, and no test would have noticed: it was found
 * in review, not by the suite.
 *
 * <p>So this one drives {@code createArchive} itself. There is now a single implementation
 * ({@link ArchiveServiceDelegate#lengthForArchive}), and this pins that this call site uses it.
 *
 * <p>The comment that used to sit here read "Use actual content size from CouchDB (not metadata
 * which may be stale/compressed)". Measured on a gzip-stored 1.3 MB PDF, it was backwards: the
 * metadata carried 1,337,959 (correct) and the "actual" derivation started from
 * {@code _attachments.length} = 1,291,901 (compressed) before downloading the whole binary.
 */
class ArchiveLengthPrefersTheNodeTest {

	private static final String REPO = "bedroom";
	private static final String OBJECT = "doc-1";
	private static final String ATTACHMENT = "att-1";
	private static final long CORRECT = 1337959L;
	private static final long WRONG = 1291901L;

	@Test
	void theArchiveRecordsTheNodesLengthWithoutTheExpensiveDerivation() {
		ContentDaoService dao = mock(ContentDaoService.class);
		AttachmentNode node = mock(AttachmentNode.class);
		when(node.getLength()).thenReturn(CORRECT);
		when(node.getMimeType()).thenReturn("application/pdf");
		when(dao.getAttachmentRef(REPO, ATTACHMENT)).thenReturn(node);
		// If the expensive path is consulted at all, it answers with the compressed size — so a
		// regression shows up as the WRONG number, not merely as an extra call.
		when(dao.getAttachmentActualSize(anyString(), anyString())).thenReturn(WRONG);

		Document document = new Document();
		document.setId(OBJECT);
		document.setName("CMIS 1.1 Specification Resources.pdf");
		document.setType("cmis:document");
		document.setObjectType("cmis:document");
		document.setAttachmentNodeId(ATTACHMENT);

		ContentService contentService = mock(ContentService.class);
		when(contentService.getContent(REPO, OBJECT)).thenReturn(document);

		ArchiveServiceDelegate delegate = new ArchiveServiceDelegate(dao, contentService,
				() -> null, (ctx, node2) -> { });

		delegate.createArchive(mock(CallContext.class), REPO, OBJECT, Boolean.FALSE);

		// Assert on what was PERSISTED: createArchive returns the DAO's result, and the DAO is a
		// mock here, so the record itself is the only honest place to look.
		org.mockito.ArgumentCaptor<Archive> persisted =
				org.mockito.ArgumentCaptor.forClass(Archive.class);
		verify(dao).createArchive(eq(REPO), persisted.capture(), org.mockito.ArgumentMatchers.any());
		Archive archive = persisted.getValue();

		assertEquals(CORRECT, archive.getContentStreamLength(),
				"the archive must record the real size; the derivation starts from the compressed "
						+ "one for a gzip-stored attachment");
		verify(dao, never()).getAttachmentActualSize(anyString(), anyString());
	}
}
