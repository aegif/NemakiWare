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
package jp.aegif.nemaki.dao.impl.couch.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.Attachment;
import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.DocumentResult;
import com.ibm.cloud.cloudant.v1.model.PostDocumentOptions;
import com.ibm.cloud.sdk.core.http.Response;
import com.ibm.cloud.sdk.core.http.ServiceCall;

import jp.aegif.nemaki.config.ObjectMapperFactory;
import jp.aegif.nemaki.model.couch.CouchAttachmentNode;

/**
 * A write must hand back the revision it produced.
 *
 * <h2>What was wrong (ledger V3)</h2>
 *
 * <p>{@code create(Object)} and {@code update(Object)} both write the new revision back onto the
 * object they were given — the wrapper calls it "EKTORP-STYLE". {@code updatePreservingAttachments}
 * did not, for one branch only: when there are attachment stubs to carry forward it goes through
 * the {@code Map} overload, which has nowhere to write back to, and its {@code DocumentResult} was
 * discarded.
 *
 * <p>So every caller issued a {@code GET} immediately afterwards to learn the revision its own
 * write had just produced — three of them in {@code AttachmentDaoDelegate}, on the attachment
 * create/update path. Handing the revision back removes all three.
 *
 * <h2>Why this test and not the call sites</h2>
 *
 * <p>The call sites now read {@code can.getRevision()} straight after the write. If the write-back
 * silently stopped happening they would read the PRE-write revision (or null) and the binary
 * upload in stage 2 would go out with a stale {@code _rev} — a conflict, or worse a retry storm,
 * rather than a visible failure here. So the property to pin is the hand-back itself.
 */
class UpdatePreservingAttachmentsReturnsRevisionTest {

	private static final String DB = "bedroom";
	private static final String ID = "att-1";
	private static final String NEW_REV = "3-9f3b1c2d4e5f60718293a4b5c6d7e8f9";

	@SuppressWarnings("unchecked")
	@Test
	void theNewRevisionIsWrittenBackOntoTheDocument() {
		Cloudant client = mock(Cloudant.class);
		DocumentResult result = mock(DocumentResult.class);
		when(result.getRev()).thenReturn(NEW_REV);
		when(result.getId()).thenReturn(ID);
		when(result.isOk()).thenReturn(Boolean.TRUE);
		Response<DocumentResult> response = mock(Response.class);
		when(response.getResult()).thenReturn(result);
		ServiceCall<DocumentResult> call = mock(ServiceCall.class);
		when(call.execute()).thenReturn(response);
		when(client.postDocument(any(PostDocumentOptions.class))).thenReturn(call);

		// A current document that HAS an attachment, so the stub-preserving branch is the one
		// exercised — the early branch delegates to update(Object) and was never the problem.
		Document current = new Document();
		current.setId(ID);
		current.setRev("2-aaaa");
		Map<String, Attachment> attachments = new LinkedHashMap<>();
		attachments.put("content", new Attachment.Builder().contentType("application/pdf")
				.revpos(2L).build());
		current.setAttachments(attachments);

		CouchAttachmentNode node = new CouchAttachmentNode();
		node.setId(ID);
		node.setRevision("2-aaaa");
		node.setName("doc.pdf");

		CloudantClientWrapper wrapper = new CloudantClientWrapper(client, DB,
				ObjectMapperFactory.createDefaultObjectMapper());
		wrapper.updatePreservingAttachments(node, current);

		assertEquals(NEW_REV, node.getRevision(),
				"the caller reads this straight after the write instead of issuing a GET; if it is "
						+ "not handed back, stage 2 uploads the binary with a stale _rev");
	}
}
