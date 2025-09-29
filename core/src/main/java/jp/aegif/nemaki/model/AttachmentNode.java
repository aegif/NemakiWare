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
		if(rangeOffset == null && rangeLength == null){
			return inputStream;
		}else{
			// CRITICAL FIX: Properly handle range requests
			// The old implementation only read 1024 bytes and corrupted content

			if(inputStream == null) {
				return null;
			}

			// Calculate actual offset and length
			long offset = (rangeOffset != null) ? rangeOffset.longValue() : 0L;
			long rangeLen = (rangeLength != null) ? rangeLength.longValue() : (length - offset);

			// Validate range
			if(offset < 0) {
				offset = 0;
			}
			if(offset >= length) {
				// Offset beyond file size - return empty stream
				return new ByteArrayInputStream(new byte[0]);
			}
			if(offset + rangeLen > length) {
				rangeLen = length - offset;
			}

			try {
				// Read the entire content first (for now - can be optimized later)
				// This ensures we don't corrupt content
				byte[] fullContent = inputStream.readAllBytes();

				// Apply range to the full content
				int actualOffset = (int) Math.min(offset, fullContent.length);
				int actualLength = (int) Math.min(rangeLen, fullContent.length - actualOffset);

				if(actualLength <= 0) {
					return new ByteArrayInputStream(new byte[0]);
				}

				// Return the ranged portion
				return new ByteArrayInputStream(fullContent, actualOffset, actualLength);
			} catch (IOException e) {
				log.error("[attachment id=" + getId() + "]getInputStream with rangeOffset=" + offset + " rangeLength=" + rangeLen + " failed.", e);
				// Fall back to returning the original stream
				return inputStream;
			}
		}
	}

	public void setInputStream(InputStream inputStream) {
		this.inputStream = inputStream;
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
