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
package jp.aegif.nemaki.model;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;

import jp.aegif.nemaki.util.constant.NodeType;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * CMIS content stream (attachment for document)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AttachmentNode extends NodeBase {
	
	private static final Log log = LogFactory
			.getLog(AttachmentNode.class);

	private String name;
	private long length;
	private String mimeType;
	private InputStream inputStream;

	// CRITICAL FIX: Cache content bytes for reusable InputStreams
	// Original inputStream can only be read once, so we cache it as bytes
	// and create new ByteArrayInputStream instances for each getInputStream() call
	private byte[] contentBytes;

	private BigInteger rangeOffset;
	private BigInteger rangeLength;
	
	public AttachmentNode(){
		super();
		setType(NodeType.ATTACHMENT.value());
	}
	
	public AttachmentNode(NodeBase n){
		setId(n.getId());
		setType(n.getType());
		setCreated(n.getCreated());
		setCreator(n.getCreator());
		setModified(n.getModified());
		setModifier(n.getModifier());
	}
	
	/**
	 * Getter & Setter
	 */
	public String getName(){
		return name;
	}
	
	public void setName(String name) {
		this.name = name;
	}

	public String getMimeType() {
		return mimeType;
	}

	public void setMimeType(String mimeType) {
		this.mimeType = mimeType;
	}

	public long getLength() {
		return length;
	}

	public void setLength(long length) {
		this.length = length;
	}

	public InputStream getInputStream() {
		// CRITICAL FIX: Use cached contentBytes for reusable InputStreams
		// If content has been cached, always use it to create new streams
		if (contentBytes != null) {
			if (rangeOffset == null && rangeLength == null) {
				// No range request - return full content
				return new ByteArrayInputStream(contentBytes);
			} else {
				// Range request - return portion of cached content
				long offset = (rangeOffset != null) ? rangeOffset.longValue() : 0L;
				long rangeLen = (rangeLength != null) ? rangeLength.longValue() : (contentBytes.length - offset);

				// Validate range
				if (offset < 0) offset = 0;
				if (offset >= contentBytes.length) {
					return new ByteArrayInputStream(new byte[0]);
				}
				if (offset + rangeLen > contentBytes.length) {
					rangeLen = contentBytes.length - offset;
				}

				int actualOffset = (int) offset;
				int actualLength = (int) Math.min(rangeLen, contentBytes.length - actualOffset);

				if (actualLength <= 0) {
					return new ByteArrayInputStream(new byte[0]);
				}

				return new ByteArrayInputStream(contentBytes, actualOffset, actualLength);
			}
		}

		// Fallback to original behavior if content not cached (legacy compatibility)
		if (rangeOffset == null && rangeLength == null) {
			return inputStream;
		} else {
			if (inputStream == null) {
				return null;
			}

			// Calculate actual offset and length
			long offset = (rangeOffset != null) ? rangeOffset.longValue() : 0L;
			long rangeLen = (rangeLength != null) ? rangeLength.longValue() : (length - offset);

			// Validate range
			if (offset < 0) {
				offset = 0;
			}
			if (offset >= length) {
				return new ByteArrayInputStream(new byte[0]);
			}
			if (offset + rangeLen > length) {
				rangeLen = length - offset;
			}

			try {
				byte[] fullContent = inputStream.readAllBytes();
				int actualOffset = (int) Math.min(offset, fullContent.length);
				int actualLength = (int) Math.min(rangeLen, fullContent.length - actualOffset);

				if (actualLength <= 0) {
					return new ByteArrayInputStream(new byte[0]);
				}

				return new ByteArrayInputStream(fullContent, actualOffset, actualLength);
			} catch (IOException e) {
				log.error("[attachment id=" + getId() + "]getInputStream with rangeOffset=" + offset + " rangeLength=" + rangeLen + " failed.", e);
				return inputStream;
			}
		}
	}

	public void setInputStream(InputStream inputStream) {
		this.inputStream = inputStream;
		// CRITICAL FIX: Cache InputStream content as bytes for reusability
		// TCK tests read content multiple times, but InputStream can only be read once
		if (inputStream != null && contentBytes == null) {
			try {
				contentBytes = inputStream.readAllBytes();
				log.debug("[attachment id=" + getId() + "] Cached " + contentBytes.length + " bytes from InputStream");
				// Clear the original stream reference since we've cached the content
				this.inputStream = null;
			} catch (IOException e) {
				log.error("[attachment id=" + getId() + "] Failed to cache InputStream content", e);
				// Keep the original inputStream if caching fails
				contentBytes = null;
			}
		}
	}

	public BigInteger getRangeOffset() {
		return rangeOffset;
	}

	public void setRangeOffset(BigInteger rangeOffset) {
		this.rangeOffset = rangeOffset;
	}

	public BigInteger getRangeLength() {
		return rangeLength;
	}

	public void setRangeLength(BigInteger rangeLength) {
		this.rangeLength = rangeLength;
	}
}
