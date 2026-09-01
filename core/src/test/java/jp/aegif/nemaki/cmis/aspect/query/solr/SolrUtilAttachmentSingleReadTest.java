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
package jp.aegif.nemaki.cmis.aspect.query.solr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.TextExtractionService;
import jp.aegif.nemaki.model.AttachmentNode;

/**
 * Indexing one document must read its attachment ONCE.
 *
 * <h2>What was wrong (ledger T3)</h2>
 *
 * <p>{@code createSolrDocument} fetched the attachment twice: {@code extractTextContent} opened it
 * to run Tika, and a separate {@code getContentLength} fetched the same attachment document again
 * purely to read {@code length}. The first fetch already had that number —
 * {@code AttachmentDaoDelegate.getAttachment} calls {@code convertRef()}, which sets the length
 * from the CouchDB {@code _attachments} metadata, before it opens the body.
 *
 * <p>The measured indexing phase is ~90% of a full reindex and attachment reading is ~64% of that,
 * so the duplicate is not free. It was also the LESS consistent shape, not the safer one: two
 * reads can straddle a concurrent update, leaving the indexed body and the indexed length on
 * different revisions.
 *
 * <h2>What these tests pin</h2>
 *
 * <p>Deleting the second read naively loses {@code content_length} in the cases where extraction
 * yields no text — which is precisely what the second read was quietly covering. So the assertions
 * are about the LENGTH surviving, not just about the call count:
 *
 * <ul>
 *   <li>the happy path reads once and never touches the metadata endpoint;
 *   <li>an unsupported MIME type still reports a length;
 *   <li>with no extraction service, the metadata-only endpoint is used — the body is never opened
 *       (the F3 separation), and a length still comes back;
 *   <li>if the body-opening read finds nothing, the metadata endpoint is the fallback, because it
 *       is the one that retries for attachments that are not visible yet.
 * </ul>
 *
 * <p>Streams are checked for closure on every path, since F3 was a connection leak.
 */
class SolrUtilAttachmentSingleReadTest {

	private static final String REPO = "bedroom";
	private static final String ATTACHMENT = "att-1";
	private static final long LENGTH = 4096L;

	private SolrUtil solrUtil;
	private ContentService contentService;
	private TextExtractionService textExtractionService;

	/** An InputStream that remembers whether anyone closed it. */
	private static final class ClosingSpyStream extends ByteArrayInputStream {
		private boolean closed;

		ClosingSpyStream() {
			super("hello".getBytes());
		}

		@Override
		public void close() throws java.io.IOException {
			closed = true;
			super.close();
		}
	}

	@BeforeEach
	void setUp() {
		contentService = mock(ContentService.class);
		textExtractionService = mock(TextExtractionService.class);

		ApplicationContext ctx = mock(ApplicationContext.class);
		when(ctx.getBean("ContentService", ContentService.class)).thenReturn(contentService);

		solrUtil = new SolrUtil();
		solrUtil.setApplicationContext(ctx);
		solrUtil.setTextExtractionService(textExtractionService);
	}

	private AttachmentNode node(InputStream stream, String mimeType) {
		AttachmentNode n = mock(AttachmentNode.class);
		when(n.getLength()).thenReturn(LENGTH);
		when(n.getInputStream()).thenReturn(stream);
		when(n.getMimeType()).thenReturn(mimeType);
		when(n.getName()).thenReturn("doc.txt");
		return n;
	}

	/** The round trip that was removed: on the normal path the metadata read must not happen. */
	@Test
	void theHappyPathReadsTheAttachmentOnce() throws Exception {
		ClosingSpyStream stream = new ClosingSpyStream();
		// Built BEFORE the stubbing below: node() itself stubs, and Mockito rejects a stubbing
		// started inside another one that has not reached thenReturn yet.
		AttachmentNode attachment = node(stream, "text/plain");
		when(contentService.getAttachment(REPO, ATTACHMENT)).thenReturn(attachment);
		when(textExtractionService.isSupported("text/plain")).thenReturn(true);
		when(textExtractionService.extractText(org.mockito.ArgumentMatchers.any(),
				anyString(), anyString())).thenReturn("hello world");

		SolrUtil.AttachmentContent result = solrUtil.readAttachment(REPO, ATTACHMENT);

		assertEquals("hello world", result.text);
		assertEquals(LENGTH, result.length,
				"the length must come from the node just read, not from a second fetch");
		verify(contentService, times(1)).getAttachment(REPO, ATTACHMENT);
		verify(contentService, never()).getAttachmentRef(anyString(), anyString());
		assertTrue(stream.closed, "F3: every opened attachment stream must be closed");
	}

	/**
	 * The case the removed read was silently covering.
	 *
	 * <p>Extraction produces nothing for an unsupported type, and the old code still reported a
	 * length because {@code getContentLength} ran regardless. Dropping the second read without
	 * carrying the length forward would have set {@code content_length} to 0 for every image, zip
	 * and binary in the repository.
	 */
	@Test
	void anUnsupportedMimeTypeStillReportsTheLength() {
		ClosingSpyStream stream = new ClosingSpyStream();
		AttachmentNode attachment = node(stream, "application/zip");
		when(contentService.getAttachment(REPO, ATTACHMENT)).thenReturn(attachment);
		when(textExtractionService.isSupported("application/zip")).thenReturn(false);

		SolrUtil.AttachmentContent result = solrUtil.readAttachment(REPO, ATTACHMENT);

		assertNull(result.text, "nothing is extractable from this type");
		assertEquals(LENGTH, result.length, "content_length must not become 0 just because no text "
				+ "could be extracted");
		verify(contentService, never()).getAttachmentRef(anyString(), anyString());
		assertTrue(stream.closed, "F3: the stream was opened, so it must be closed");
	}

	/**
	 * A length that could not be read is UNKNOWN, and unknown is not zero.
	 *
	 * <p>Both reads failing used to produce {@code content_length = 0}, which the indexer wrote
	 * to Solr — so the index stated a size the document does not have, and a range query
	 * answered it confidently. The field is left out instead: an absent field is answered
	 * honestly by Solr, a wrong one is not. Recorded as a known gap for two rounds under
	 * "the indexer degrades deliberately", which is true of the TEXT and was not true of this.
	 */
	@Test
	void aLengthThatCouldNotBeReadIsNotZero() {
		when(contentService.getAttachment(REPO, ATTACHMENT))
				.thenThrow(new IllegalStateException("the attachment could not be read"));
		when(contentService.getAttachmentRef(REPO, ATTACHMENT))
				.thenThrow(new IllegalStateException("nor could its metadata"));

		SolrUtil.AttachmentContent result = solrUtil.readAttachment(REPO, ATTACHMENT);

		assertNull(result.text);
		assertEquals(SolrUtil.AttachmentContent.LENGTH_UNKNOWN, result.length,
				"a length nobody could read was reported as 0, and the index then carried "
						+ "content_length=0 for a document with content");
	}

	/**
	 * An unknown length is left OUT of the index, not written as a sentinel.
	 *
	 * <p>The javadoc of the change claimed "the field is left out"; the test only pinned the
	 * value {@code lengthFromMetadata} returns. An audit named the edit that keeps that green
	 * while the index is still wrong: drop the guard at the write site and Solr receives
	 * {@code content_length = -1} — a different wrong number in the same field.
	 */
	@Test
	void anUnknownLengthIsNotWrittenToTheIndex() throws Exception {
		String source = jp.aegif.nemaki.util.test.JavaSource.withoutComments(
				jp.aegif.nemaki.util.test.JavaSource.read(
						"src/main/java/jp/aegif/nemaki/cmis/aspect/query/solr/SolrUtil.java"));
		int writeAt = source.indexOf("doc.addField(\"content_length\"");
		assertTrue(writeAt > 0, "content_length is no longer indexed at all");
		String before = source.substring(Math.max(0, writeAt - 400), writeAt);
		assertTrue(before.contains("!= AttachmentContent.LENGTH_UNKNOWN"),
				"content_length is written unconditionally again, so a length nobody could "
						+ "read reaches the index as a sentinel value: " + before);
	}

	/**
	 * With nothing to extract, the body must not be opened at all — that is the F3 separation
	 * between the metadata path and the body path.
	 */
	@Test
	void withNoExtractionServiceOnlyMetadataIsRead() {
		solrUtil.setTextExtractionService(null);
		AttachmentNode ref = mock(AttachmentNode.class);
		when(ref.getLength()).thenReturn(LENGTH);
		when(contentService.getAttachmentRef(REPO, ATTACHMENT)).thenReturn(ref);

		SolrUtil.AttachmentContent result = solrUtil.readAttachment(REPO, ATTACHMENT);

		assertNull(result.text);
		assertEquals(LENGTH, result.length);
		verify(contentService, never()).getAttachment(anyString(), anyString());
		verify(contentService, times(1)).getAttachmentRef(REPO, ATTACHMENT);
	}

	/**
	 * The fallback. {@code getAttachmentRef} retries briefly for attachments that are not visible
	 * yet and {@code getAttachment} does not, so losing that retry would set {@code content_length}
	 * to 0 for documents indexed immediately after upload. It costs a second read only when the
	 * first one already failed.
	 */
	@Test
	void aMissingAttachmentFallsBackToTheRetryingMetadataRead() {
		when(contentService.getAttachment(REPO, ATTACHMENT)).thenReturn(null);
		AttachmentNode ref = mock(AttachmentNode.class);
		when(ref.getLength()).thenReturn(LENGTH);
		when(contentService.getAttachmentRef(REPO, ATTACHMENT)).thenReturn(ref);

		SolrUtil.AttachmentContent result = solrUtil.readAttachment(REPO, ATTACHMENT);

		assertNull(result.text);
		assertEquals(LENGTH, result.length,
				"the retrying metadata read is the reason this fallback exists");
		verify(contentService, times(1)).getAttachmentRef(REPO, ATTACHMENT);
	}

	/**
	 * If the attachment read itself throws, the length must still be sought.
	 *
	 * <p>Found in review. The two methods this replaced were independent: {@code extractTextContent}
	 * swallowed the exception and {@code getContentLength} then made its own metadata call. Merging
	 * them put both inside one {@code try}, so the catch returned a length of 0 that had never been
	 * read — silently indexing {@code content_length=0} where the old shape indexed the real value.
	 */
	@Test
	void anAttachmentReadThatThrowsStillLooksUpTheLength() {
		when(contentService.getAttachment(REPO, ATTACHMENT))
				.thenThrow(new RuntimeException("CouchDB is unreachable"));
		AttachmentNode ref = mock(AttachmentNode.class);
		when(ref.getLength()).thenReturn(LENGTH);
		when(contentService.getAttachmentRef(REPO, ATTACHMENT)).thenReturn(ref);

		SolrUtil.AttachmentContent result = solrUtil.readAttachment(REPO, ATTACHMENT);

		assertNull(result.text);
		assertEquals(LENGTH, result.length,
				"the node was never read, so the length is unknown — the metadata path is the "
						+ "independent lookup the removed second read used to provide");
	}

	/** A failed extraction must not take the length down with it, and must still close. */
	@Test
	void aFailedExtractionKeepsTheLength() throws Exception {
		ClosingSpyStream stream = new ClosingSpyStream();
		AttachmentNode attachment = node(stream, "text/plain");
		when(contentService.getAttachment(REPO, ATTACHMENT)).thenReturn(attachment);
		when(textExtractionService.isSupported("text/plain")).thenReturn(true);
		when(textExtractionService.extractText(org.mockito.ArgumentMatchers.any(),
				anyString(), anyString())).thenThrow(new RuntimeException("Tika blew up"));

		SolrUtil.AttachmentContent result = solrUtil.readAttachment(REPO, ATTACHMENT);

		assertNull(result.text);
		assertEquals(LENGTH, result.length,
				"the node was read before the failure, so its length is known");
		assertTrue(stream.closed, "F3: the finally block must still close it");
	}
}
