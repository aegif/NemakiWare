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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.util.PropertyManager;

/**
 * Deleting a document must archive the size the attachment node already knew.
 *
 * <h2>Why this exists (ledger C4)</h2>
 *
 * <p>The C4 correction touched three call sites. Two of them — the deletion paths — were covered
 * only by a test that called the helper DIRECTLY, so the review pointed out that both could
 * regress independently while every archive-length test stayed green. That is not hypothetical:
 * the third call site was found in review precisely because nothing drove it.
 *
 * <p>This drives {@code deleteDocument} end to end and asserts on the {@link Archive} handed to
 * persistence, which is the last point the number can still be wrong.
 *
 * <p><b>The other deletion site is still not covered.</b> {@code deleteDocumentWithVisited} carries
 * its own copy of the same three lines and is private, reachable only through the
 * {@code *WithVisited} cascade — {@code deleteTree} goes to {@code deleteDocument}, not to it. An
 * attempt to reach it through {@code deleteTree} was written and then deleted: it went through
 * {@code deleteDocument} instead, so reverting the cascade site left it green. A test that does not
 * discriminate is worse than none, because it reads as coverage. Whoever next touches that method
 * has to reach it deliberately.
 *
 * <h2>The numbers</h2>
 *
 * <p>Measured on a gzip-stored 1.3 MB PDF: the node carried 1,337,959 (correct) while the
 * expensive derivation starts from {@code _attachments.length} = 1,291,901 (compressed) before
 * downloading the whole binary. So the mock answers the derivation with the WRONG number: a
 * regression then shows up as a wrong archive size, not merely as an extra call.
 */
class DeleteDocumentArchivesTheNodeLengthTest {

	private static final String REPO = "bedroom";
	private static final String DOC = "doc-1";
	private static final String ATTACHMENT = "att-1";
	private static final long CORRECT = 1337959L;
	private static final long COMPRESSED = 1291901L;

	private static Document document() {
		Document document = new Document();
		document.setId(DOC);
		document.setName("CMIS 1.1 Specification Resources.pdf");
		document.setType("cmis:document");
		document.setObjectType("cmis:document");
		document.setAttachmentNodeId(ATTACHMENT);
		document.setVersionSeriesId("vs-1");
		return document;
	}

	/** A DAO wired so the delete can run to the point where the archive is written. */
	private static ContentDaoService daoWithDocument() {
		ContentDaoService dao = mock(ContentDaoService.class);
		when(dao.getContent(REPO, DOC)).thenReturn(document());

		AttachmentNode node = mock(AttachmentNode.class);
		when(node.getLength()).thenReturn(CORRECT);
		when(node.getMimeType()).thenReturn("application/pdf");
		when(dao.getAttachmentRef(REPO, ATTACHMENT)).thenReturn(node);
		when(dao.getAttachmentActualSize(anyString(), anyString())).thenReturn(COMPRESSED);
		// writeChangeEvent runs BEFORE the archive is written and dereferences what create()
		// returns; without this the flow dies before reaching the assertion below.
		when(dao.create(anyString(), any(jp.aegif.nemaki.model.Change.class)))
				.thenAnswer(inv -> inv.getArgument(1));

		return dao;
	}

	private static ContentServiceImpl serviceWith(ContentDaoService dao) {
		PropertyManager properties = mock(PropertyManager.class);
		// Previews off (they have their own round trip and their own test); archiving ON, since
		// the archive record is where the length under test ends up.
		when(properties.readValue(anyString())).thenReturn("false");
		when(properties.readBoolean(jp.aegif.nemaki.util.constant.PropertyKey.ARCHIVE_CREATE_ENABLED))
				.thenReturn(Boolean.TRUE);

		ContentServiceImpl service = new ContentServiceImpl();
		service.setContentDaoService(dao);
		service.setPropertyManager(properties);
		return service;
	}

	private static void assertArchivedLengthIsTheNodes(ContentDaoService dao) {
		ArgumentCaptor<Archive> archived = ArgumentCaptor.forClass(Archive.class);
		verify(dao).createArchive(eq(REPO), archived.capture(), any());
		assertEquals(CORRECT, archived.getValue().getContentStreamLength(),
				"the archive must record the node's length; the derivation answers with the "
						+ "compressed size for a gzip-stored attachment");
		verify(dao, never()).getAttachmentActualSize(anyString(), anyString());
	}

	/** Call site 1: deleting the document directly. */
	@Test
	void theArchivedLengthComesFromTheNodeNotTheDerivation() {
		ContentDaoService dao = daoWithDocument();

		try {
			serviceWith(dao).deleteDocument(mock(CallContext.class), REPO, DOC, Boolean.FALSE,
					Boolean.FALSE);
		} catch (Exception e) {
			// The tail of the delete indexes into Solr; that collaborator is not wired. The
			// archive is written before it, so the assertion below is unaffected.
		}

		assertArchivedLengthIsTheNodes(dao);
	}

}
