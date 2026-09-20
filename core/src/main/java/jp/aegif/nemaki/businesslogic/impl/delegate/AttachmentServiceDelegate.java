/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.businesslogic.impl.delegate;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Rendition;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Delegate for attachment and rendition operations extracted from ContentServiceImpl.
 */
public class AttachmentServiceDelegate {

	private static final Logger log = LoggerFactory.getLogger(AttachmentServiceDelegate.class);

	private final ContentDaoService contentDaoService;
	private final ContentServiceHelper helper;
	private final NemakiCachePool nemakiCachePool;

	public AttachmentServiceDelegate(ContentDaoService contentDaoService, ContentServiceHelper helper,
			NemakiCachePool nemakiCachePool) {
		this.contentDaoService = contentDaoService;
		this.helper = helper;
		this.nemakiCachePool = nemakiCachePool;
	}

	/**
	 * The existing signature, for callers that only need the id.
	 *
	 * <p>Delegates rather than duplicating: a second body would be a second definition of what
	 * an attachment write does, and the two would drift.
	 */
	public String createAttachment(CallContext callContext, String repositoryId, ContentStream contentStream) {
		return createAttachmentRecording(callContext, repositoryId, contentStream).attachmentId();
	}

	public Written createAttachmentRecording(CallContext callContext, String repositoryId, ContentStream contentStream) {
		AttachmentNode a = new AttachmentNode();

		String mimeType = contentStream.getMimeType();
		if (mimeType == null || mimeType.isEmpty()) {
			mimeType = "application/octet-stream";
		}
		a.setMimeType(mimeType);

		// CRITICAL FIX: Calculate actual stream size when length is unknown (-1) or invalid (0 or negative)
		// BUT avoid consuming the stream if it doesn't support mark/reset
		long streamLength = contentStream.getLength();
		if (streamLength <= 0) {
			log.warn("ContentStream length is " + streamLength + " (unknown/invalid) for: " + contentStream.getFileName());

			// TCK COMPATIBILITY FIX: Only calculate size if stream supports mark/reset
			// This prevents consuming the stream content during size calculation
			InputStream stream = contentStream.getStream();
			if (stream != null && stream.markSupported()) {
				log.debug("Stream supports mark/reset, calculating actual size for: " + contentStream.getFileName());
				streamLength = calculateStreamSize(stream);

				if (streamLength >= 0) {
					if (log.isDebugEnabled()) {
						log.debug("Calculated stream size: " + streamLength + " bytes for: " + contentStream.getFileName());
					}
				} else {
					log.error("Failed to calculate stream size, using -1 (unknown) for: " + contentStream.getFileName());
					streamLength = -1L; // Use -1 to indicate unknown size to DAO layer
				}
			} else {
				log.debug("Stream does not support mark/reset, preserving content and using -1 (unknown size) for: " + contentStream.getFileName());
				streamLength = -1L; // Let DAO layer handle unknown size without consuming content
			}
		}

		a.setLength(streamLength);

		String fileName = contentStream.getFileName();
		if (fileName == null || fileName.isEmpty()) {
			fileName = "-";
		}
		a.setName(fileName);

		helper.setSignature(callContext, a);

		// E1: the digest of the bytes as they go past, on the one pass that carries them
		// (docs/design/adr-e1-durable-commitment.md, decision 2). Wrapping happens HERE and not
		// earlier because the length logic above may itself read and rewind the stream.
		jp.aegif.nemaki.evidence.DigestingInputStream digesting =
				jp.aegif.nemaki.evidence.DigestingInputStream.over(contentStream.getStream());
		ContentStream toWrite = contentStream;
		if (digesting != null) {
			// The ORIGINAL declared length is carried through, not the computed one: the DAO
			// reads it, and substituting a value it did not have before would change what gets
			// stored for a stream whose length was unknown.
			org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl wrapped =
					new org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl(
							contentStream.getFileName(), contentStream.getBigLength(),
							contentStream.getMimeType(), digesting);
			toWrite = wrapped;
		}
		String attachmentId = contentDaoService.createAttachment(repositoryId, a, toWrite);
		// Null when the stream could not be vouched for — a short read, a rewind whose digest
		// could not be snapshotted. The caller records NO statement in that case rather than a
		// digest that may cover the wrong bytes.
		String digest = digesting == null ? null : digesting.digestIfTrustworthy(streamLength);
		return new Written(attachmentId, digest, streamLength);
	}

	/**
	 * What one attachment write produced.
	 *
	 * @param contentDigest null when the write could not be digested; NOT an empty string and
	 *        not the digest of nothing, both of which look exactly like an answer
	 */
	public record Written(String attachmentId, String contentDigest, long length) {
	}

	public String copyAttachment(CallContext callContext, String repositoryId, String attachmentId) {
		// CRITICAL FIX (2025-12-16): Handle null attachmentId (document without content)
		if (attachmentId == null || attachmentId.isEmpty()) {
			log.debug("copyAttachment: attachmentId is null or empty, returning null (document has no content)");
			return null;
		}

		AttachmentNode original = contentDaoService.getAttachment(repositoryId, attachmentId);

		// Read once more before answering "there is nothing here", for either shape of nothing.
		//
		// Restoring from the archive puts the DOCUMENT back first (ArchiveDaoDelegate:853) and
		// only then the attachment — its row at :772, its body at :792. A check-out landing in
		// that window reads no row at all for a moment, and a row with no body for another.
		// Both are a legitimate operation in progress. One retry narrows the window; it does
		// not close it (the body PUT takes as long as the attachment is big), which is why the
		// refusal says to retry rather than pretending this cannot happen. Same shape as
		// ContentServiceImpl.getAttachmentRef's "minimal retry for async scenarios".
		if (original == null || !hasBody(original)) {
			try {
				Thread.sleep(25);
			} catch (InterruptedException interrupted) {
				// Restore the flag and answer from what the FIRST read already told us: a
				// blocking read on an interrupted thread can fail with a different sentence
				// than the one this method means to say.
				Thread.currentThread().interrupt();
			}
			// Checked again here, not only in the catch: the interrupt can arrive AFTER the
			// sleep returns and before the read starts, and a blocking read on an interrupted
			// thread fails with a different sentence than the one this method means to say
			// (review, 2026-09-20).
			if (Thread.currentThread().isInterrupted()) {
				// Its OWN sentence. Answering with the two-read message would say "asked twice,
				// twice there was nothing" about a read that was never made — the branch's
				// subject, in miniature (review, 2026-09-20).
				throw new org.apache.chemistry.opencmis.commons.exceptions.CmisStorageException(
						"the attachment '" + attachmentId + "' in '" + repositoryId + "' was "
								+ (original == null ? "not found" : "found without a content body")
								+ " on the first read, and this thread was interrupted before it "
								+ "could be read again. This is NOT a finding that the document "
								+ "has no content, and NOT a finding that the attachment is "
								+ "gone — the second read did not happen.");
			}
			original = contentDaoService.getAttachment(repositoryId, attachmentId);
		}

		// CRITICAL FIX (2025-12-16): Handle null attachment (corrupted or deleted)
		if (original == null) {
			log.warn("copyAttachment: Could not retrieve attachment with ID '{}', returning null", attachmentId);
			return null;
		}

		// The row is there and its body is not. getAttachment leaves the stream null for exactly
		// one thing — the CouchDB document carries no `content` attachment — and createAttachment
		// SKIPS stage 2 for a null stream and returns the new id as a success
		// (AttachmentDaoDelegate: "STAGE 2 SKIPPED: No binary content to attach"). So copying it
		// produced a second empty row, and the caller, holding a non-null id, recorded a
		// successful copy of content that was never there. Found by review, 2026-09-19; same
		// class as R54, one level further in. A zero-byte upload is NOT this case: CouchDB
		// answers it with an empty stream, not with none.
		//
		// It is read ONCE MORE before refusing, because this state also occurs in the middle of
		// a legitimate operation: restoring from the archive creates the attachment row
		// (ArchiveDaoDelegate:772) and PUTs the body in a SEPARATE write (:792), with the
		// restored document already reachable. A check-out landing between the two reads a row
		// with no body, and a moment later the same read succeeds. The same shape as
		// ContentServiceImpl.getAttachmentRef's "minimal retry for async scenarios". One retry
		// narrows that window; it does not close it, so the refusal says to retry (review,
		// 2026-09-19).
		if (!hasBody(original)) {
			throw new org.apache.chemistry.opencmis.commons.exceptions.CmisStorageException(
					bodyMissing(repositoryId, attachmentId));
		}

		String mimeType = original.getMimeType();
		if (mimeType == null || mimeType.isEmpty()) {
			mimeType = "application/octet-stream";
		}

		String fileName = original.getName();
		if (fileName == null || fileName.isEmpty()) {
			fileName = "-";
		}

		ContentStream cs = new ContentStreamImpl(fileName, BigInteger.valueOf(original.getLength()),
				mimeType, original.getInputStream());

		AttachmentNode copy = new AttachmentNode();
		copy.setName(fileName);
		copy.setLength(original.getLength());
		copy.setMimeType(mimeType);
		helper.setSignature(callContext, copy);

		String newAttachmentId = contentDaoService.createAttachment(repositoryId, copy, cs);

		// Invalidate cache: getAttachment cached the original with its InputStream now consumed
		nemakiCachePool.get(repositoryId).getAttachmentCache().remove(attachmentId);

		return newAttachmentId;
	}

	private static String bodyMissing(String repositoryId, String attachmentId) {
		return "the attachment '" + attachmentId + "' in '" + repositoryId + "' has a row but no "
				+ "content body, so there is nothing to copy. This is NOT a finding that the "
				+ "document has no content. If a restore from the archive is in progress for "
				+ "this document, retry shortly.";
	}

	/**
	 * Does this node carry bytes?
	 *
	 * <p>One place decides it, because the wrong answer is a plausible one: keying on the
	 * recorded LENGTH instead would refuse a zero-byte attachment as if its body were missing.
	 * CouchDB answers a zero-byte attachment with an EMPTY stream, not with none — a null
	 * stream means the document carries no {@code content} attachment at all.
	 */
	private static boolean hasBody(AttachmentNode node) {
		return node != null && node.getInputStream() != null;
	}

	/*
	 * copyRenditions lived here and was removed (2026-08-26).
	 *
	 * It persisted renditions with contentDaoService.createRendition from THIS file, so the
	 * P3-2 record was not made and the "only one way in" scan — which read ContentServiceImpl —
	 * could not see it. Its only caller also discarded the ids it returned, so every check-out
	 * stored a full set of rendition copies that no document referenced.
	 *
	 * It is now ContentServiceImpl.copyRenditionsOnto, which runs after the target document
	 * exists, attaches the ids to it, and records each copy.
	 */


	/**
	 * Calculate the actual size of an InputStream by reading through it
	 * This is needed when ContentStream.getLength() returns -1 (unknown size)
	 * @param inputStream The stream to measure
	 * @return The actual size in bytes
	 */
	public long calculateStreamSize(InputStream inputStream) {
		if (inputStream == null) {
			return 0L;
		}

		long totalBytes = 0L;
		byte[] buffer = new byte[8192]; // 8KB buffer for efficient reading

		try {
			// Mark the stream for reset if possible
			if (inputStream.markSupported()) {
				inputStream.mark(Integer.MAX_VALUE);
			}

			int bytesRead;
			while ((bytesRead = inputStream.read(buffer)) != -1) {
				totalBytes += bytesRead;
			}

			// Reset stream to beginning if possible
			if (inputStream.markSupported()) {
				inputStream.reset();
			} else {
				log.warn("InputStream does not support mark/reset - stream position cannot be restored");
			}

		} catch (IOException e) {
			log.error("Error calculating stream size", e);
			return -1L; // Return -1 to indicate error, will be handled by calling code
		}

		return totalBytes;
	}
}
