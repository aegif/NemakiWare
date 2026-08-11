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
package jp.aegif.nemaki.model.couch;

import java.util.Map;
import java.util.HashMap;
import java.util.GregorianCalendar;
import com.fasterxml.jackson.annotation.JsonProperty;
import jp.aegif.nemaki.model.AttachmentNode;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class CouchAttachmentNode extends CouchNodeBase{
	
	private static final Log log = LogFactory.getLog(CouchAttachmentNode.class);
	private static final long serialVersionUID = 1984059866949665299L;
	public static final String TYPE = "attachment";

	private String name;
	private long length;
	private String mimeType;
	
	// CouchDB _attachments field to get actual file size
	@JsonProperty("_attachments")
	private Map<String, AttachmentInfo> attachments;
	
	public CouchAttachmentNode(){
		super();
	}
	
	public CouchAttachmentNode(AttachmentNode a){
		super(a);
		setName(a.getName());
		setMimeType(a.getMimeType());
		setLength(a.getLength());
	}
	
	/**
	 * CRITICAL CLOUDANT SDK COMPATIBILITY: Map constructor required for deserialization
	 * This constructor ensures CouchAttachmentNode can be deserialized from CouchDB documents
	 * like all other Couch* classes in the system.
	 */
	public CouchAttachmentNode(Map<String, Object> properties) {
		super(properties);
		
		// Extract attachment-specific properties from the map
		if (properties.containsKey("name")) {
			this.name = (String) properties.get("name");
		}
		
		if (properties.containsKey("length")) {
			Object lengthObj = properties.get("length");
			if (lengthObj instanceof Number) {
				this.length = ((Number) lengthObj).longValue();
			}
		}
		
		if (properties.containsKey("mimeType")) {
			this.mimeType = (String) properties.get("mimeType");
		}
		
		// Handle CouchDB _attachments field for actual file size retrieval
		if (properties.containsKey("_attachments")) {
			@SuppressWarnings("unchecked")
			Map<String, Map<String, Object>> attachmentData = (Map<String, Map<String, Object>>) properties.get("_attachments");
			if (attachmentData != null && !attachmentData.isEmpty()) {
				Map<String, AttachmentInfo> attachmentInfoMap = new HashMap<>();
				for (Map.Entry<String, Map<String, Object>> entry : attachmentData.entrySet()) {
					AttachmentInfo info = new AttachmentInfo();
					Map<String, Object> attachmentMeta = entry.getValue();
					
					if (attachmentMeta.containsKey("content_type")) {
						info.setContentType((String) attachmentMeta.get("content_type"));
					}
					if (attachmentMeta.containsKey("length")) {
						Object lengthObj = attachmentMeta.get("length");
						if (lengthObj instanceof Number) {
							info.setLength(((Number) lengthObj).longValue());
						}
					}
					if (attachmentMeta.containsKey("digest")) {
						info.setDigest((String) attachmentMeta.get("digest"));
					}
					if (attachmentMeta.containsKey("revpos")) {
						Object revposObj = attachmentMeta.get("revpos");
						if (revposObj instanceof Number) {
							info.setRevpos(((Number) revposObj).intValue());
						}
					}
					if (attachmentMeta.containsKey("stub")) {
						Object stubObj = attachmentMeta.get("stub");
						if (stubObj instanceof Boolean) {
							info.setStub((Boolean) stubObj);
						}
					}
					
					attachmentInfoMap.put(entry.getKey(), info);
				}
				this.attachments = attachmentInfoMap;
			}
		}
	}
	
	/**
	 *Getter & Setter 
	 */
	public String getName(){
		return name;
	}
	
	public void setName(String name) {
		this.name = name;
	}
	
	public long getLength() {
		return length;
	}

	public void setLength(long length) {
		this.length = length;
	}

	public String getMimeType() {
		return mimeType;
	}

	public void setMimeType(String mimeType) {
		this.mimeType = mimeType;
	}

	public Map<String, AttachmentInfo> getAttachments() {
		return attachments;
	}
	
	public void setAttachments(Map<String, AttachmentInfo> attachments) {
		this.attachments = attachments;
	}
	
	/**
	 * Gets the actual file length from CouchDB _attachments or falls back to stored length.
	 * 
	 * Fallback order:
	 * 1. _attachments metadata length (non-gzip: this is the true uncompressed size)
	 * 2. Stored metadata length field (set from uncompressed ContentStream at upload time)
	 * 3. -1 (unknown) as last resort
	 * 
	 * @return actual file length, or -1 if unknown
	 */
	public long getActualLength() {
		// Check _attachments metadata first
		if (attachments != null && !attachments.isEmpty()) {
			for (AttachmentInfo info : attachments.values()) {
				if (info != null) {
					long contentLength = info.getActualContentLength();
					if (contentLength >= 0) {
						// Non-gzip: _attachments length is the true uncompressed size
						return contentLength;
					}
				}
			}
		}

		// Fallback to stored metadata length field.
		// This value was set from the uncompressed ContentStream at upload time
		// (see AttachmentServiceDelegate.createAttachment), so it is reliable
		// even when CouchDB uses gzip encoding for the stored attachment.
		if (length >= 0) {
			return length;
		}

		return -1;
	}
	
	/**
	 * Gets the actual MIME type from CouchDB _attachments or falls back to stored mimeType.
	 * If _attachments content_type is the generic "application/octet-stream" but the stored
	 * mimeType field has a more specific value, prefer the stored value.
	 * @return actual MIME type
	 */
	public String getActualMimeType() {
		String attachmentContentType = null;
		// First try to get MIME type from CouchDB _attachments
		if (attachments != null && !attachments.isEmpty()) {
			for (AttachmentInfo info : attachments.values()) {
				if (info != null && info.getContentType() != null && !info.getContentType().isEmpty()) {
					attachmentContentType = info.getContentType();
					break;
				}
			}
		}

		// If the stored mimeType field has a specific (non-generic) value, prefer it
		// over the generic application/octet-stream from _attachments
		if (mimeType != null && !mimeType.isEmpty() && !"application/octet-stream".equals(mimeType)) {
			// Stored mimeType is specific, use it
			return mimeType;
		}

		// Otherwise use _attachments content_type if available
		if (attachmentContentType != null) {
			return attachmentContentType;
		}

		// Final fallback to stored mimeType field (may be null or octet-stream)
		return mimeType;
	}
	
	/**
	 * Metadata only — no attachment body is fetched.
	 *
	 * <p>Name, length and MIME type all come from the document that was already read, so this
	 * costs nothing beyond the conversion. {@link #convert()} additionally opens the binary
	 * stream and hands ownership of it to the caller; anything that only inspects metadata must
	 * use THIS method, or it leaks one CouchDB connection per call and downloads the whole
	 * attachment to learn what it already had.
	 */
	public AttachmentNode convertRef(){
		AttachmentNode a = new AttachmentNode(super.convert());

		a.setName(getName());
		// Use actual length from CouchDB _attachments instead of the stored field
		a.setLength(getActualLength());
		// Use actual MIME type from CouchDB _attachments instead of the stored field
		a.setMimeType(getActualMimeType());

		return a;
	}

	// convert() — "metadata AND an open binary stream" — used to live here and is deliberately
	// gone. It opened the attachment body itself by looking the connector pool up from
	// SpringContext and trying EVERY repository in turn until one answered. The DAO then opened
	// the body a SECOND time and assigned over the first reference without closing it, so every
	// attachment read leaked exactly one CouchDB connection and downloaded the attachment twice.
	// Measured before the fix: a 2,510-document reindex took ESTABLISHED connections from 3 to
	// 1,289, and they stayed for about ninety seconds after it finished.
	//
	// Opening the body belongs where the repository is already known — AttachmentDaoDelegate
	// .getAttachment does it once, there. This class returns metadata only (convertRef), which
	// is what every caller that is not about to READ the bytes actually wants. It had no callers
	// left when it was removed; it is recorded here because re-adding a convenience method that
	// opens a stream is how this comes back.

	/**
	 * Inner class to represent CouchDB attachment info
	 */
	public static class AttachmentInfo {
		@JsonProperty("content_type")
		private String contentType;
		
		private long length;
		
		private String digest;
		
		private int revpos;
		
		private boolean stub;

		private String encoding;

		@JsonProperty("encoded_length")
		private long encodedLength;
		
		public String getContentType() {
			return contentType;
		}
		
		public void setContentType(String contentType) {
			this.contentType = contentType;
		}
		
		public long getLength() {
			return length;
		}
		
		public void setLength(long length) {
			this.length = length;
		}
		
		public String getDigest() {
			return digest;
		}
		
		public void setDigest(String digest) {
			this.digest = digest;
		}
		
		public int getRevpos() {
			return revpos;
		}
		
		public void setRevpos(int revpos) {
			this.revpos = revpos;
		}
		
		public boolean isStub() {
			return stub;
		}
		
		public void setStub(boolean stub) {
			this.stub = stub;
		}

		public String getEncoding() {
			return encoding;
		}

		public void setEncoding(String encoding) {
			this.encoding = encoding;
		}

		public long getEncodedLength() {
			return encodedLength;
		}

		public void setEncodedLength(long encodedLength) {
			this.encodedLength = encodedLength;
		}

		/**
		 * Returns the actual uncompressed content length.
		 * When CouchDB uses gzip encoding, _attachments.length may be the compressed size
		 * (especially for edge cases like empty content). This method detects gzip encoding
		 * and returns -1 to indicate the length from _attachments is unreliable.
		 * @return actual length, or -1 if gzip-encoded (unreliable)
		 */
		public long getActualContentLength() {
			if ("gzip".equals(encoding)) {
				// When gzip encoding is present, _attachments.length may be compressed size
				// Return -1 to signal caller should not trust this value
				return -1;
			}
			return length;
		}
	}
}
