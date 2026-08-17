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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.util.PropertyManager;

/**
 * Overwriting a PWC's content must not download the content it is about to overwrite.
 *
 * <h2>What was wrong (ledger B1)</h2>
 *
 * <p>{@code replacePwc} fetched the attachment with {@code getAttachment} — the call that OPENS
 * the body — and handed it to {@code updateAttachment}, which then replaced that body. The old
 * binary was transferred in full and discarded, one line before being overwritten.
 *
 * <p>{@code updateAttachment} never reads it: it builds its {@code CouchAttachmentNode} from the
 * node's fields and re-reads the document for the current {@code _rev}
 * ({@code AttachmentDaoDelegate.updateAttachment:587-595}). F3 separated the metadata call from
 * the body call precisely so that callers who do not read the body do not pay for it, and this
 * one was left behind.
 *
 * <h2>What this pins</h2>
 *
 * <p>Which call is made. Asserting on the result would pass either way — the update produced the
 * same document when it downloaded the old body and when it did not.
 */
class ReplacePwcDoesNotDownloadOldBodyTest {

	private static final String REPO = "bedroom";
	private static final String ATTACHMENT = "att-1";

	@Test
	void theOldBodyIsNotFetchedBeforeItIsOverwritten() {
		ContentDaoService dao = mock(ContentDaoService.class);
		AttachmentNode node = mock(AttachmentNode.class);
		when(dao.getAttachmentRef(REPO, ATTACHMENT)).thenReturn(node);

		// Preview generation is the only other consumer in this method and it has its own
		// (separate) round trip; switching it off keeps this test on the one call it is about.
		PropertyManager properties = mock(PropertyManager.class);
		when(properties.readValue(anyString())).thenReturn("false");

		ContentServiceImpl service = new ContentServiceImpl();
		service.setContentDaoService(dao);
		service.setPropertyManager(properties);

		Document pwc = new Document();
		pwc.setId("pwc-1");
		pwc.setAttachmentNodeId(ATTACHMENT);

		try {
			service.replacePwc(mock(CallContext.class), REPO, pwc, mock(ContentStream.class));
		} catch (Exception e) {
			// The method goes on to write the change event and index; those collaborators are not
			// wired here. Everything this test asserts has already happened by then.
		}

		verify(dao, times(1)).getAttachmentRef(REPO, ATTACHMENT);
		verify(dao, never()).getAttachment(anyString(), anyString());
		verify(dao).updateAttachment(eq(REPO), eq(node), any());
	}
}
