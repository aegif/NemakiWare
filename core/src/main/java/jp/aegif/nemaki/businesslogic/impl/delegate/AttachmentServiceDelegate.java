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

	public String createAttachment(CallContext callContext, String repositoryId, ContentStream contentStream) {
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
		return contentDaoService.createAttachment(repositoryId, a, contentStream);
	}

	public String copyAttachment(CallContext callContext, String repositoryId, String attachmentId) {
		// CRITICAL FIX (2025-12-16): Handle null attachmentId (document without content)
		if (attachmentId == null || attachmentId.isEmpty()) {
			log.debug("copyAttachment: attachmentId is null or empty, returning null (document has no content)");
			return null;
		}

		AttachmentNode original = contentDaoService.getAttachment(repositoryId, attachmentId);

		// CRITICAL FIX (2025-12-16): Handle null attachment (corrupted or deleted)
		if (original == null) {
			log.warn("copyAttachment: Could not retrieve attachment with ID '{}', returning null", attachmentId);
			return null;
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

	public List<String> copyRenditions(CallContext callContext, String repositoryId, List<String> renditionIds) {
		if (CollectionUtils.isEmpty(renditionIds))
			return null;

		List<String> list = new ArrayList<String>();
		for (String renditionId : renditionIds) {
			Rendition original = contentDaoService.getRendition(repositoryId, renditionId);
			ContentStream cs = new ContentStreamImpl("content", BigInteger.valueOf(original.getLength()),
					original.getMimetype(), original.getInputStream());

			Rendition copy = new Rendition();
			copy.setKind(original.getKind());
			copy.setHeight(original.getHeight());
			copy.setWidth(original.getWidth());
			copy.setLength(original.getLength());
			copy.setMimetype(original.getMimetype());
			helper.setSignature(callContext, copy);

			String createdId = contentDaoService.createRendition(repositoryId, copy, cs);
			list.add(createdId);
		}
		return list;
	}

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
