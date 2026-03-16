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
	
	public AttachmentNode convert(){
		AttachmentNode a = new AttachmentNode(super.convert());
		
		a.setName(getName());
		// CRITICAL FIX: Use actual length from CouchDB _attachments instead of stored field
		a.setLength(getActualLength());
		// CRITICAL FIX: Use actual MIME type from CouchDB _attachments instead of stored field
		a.setMimeType(getActualMimeType());
		
		// CRITICAL FIX FOR JAVA 17 MIGRATION: Set InputStream from CouchDB attachment
		// This was broken during Java 17/Jakarta EE migration - InputStream was never retrieved
		try {
			// CRITICAL FIX: Use proper DI instead of manual Spring context lookup
			jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool connectorPool = 
				jp.aegif.nemaki.util.spring.SpringContext.getApplicationContext()
					.getBean("connectorPool", jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool.class);
			
			if (connectorPool != null && getId() != null) {
				// Try all main repository IDs + nemaki_conf to find the attachment
				java.util.List<String> repositoryIds = new java.util.ArrayList<>();
				try {
					jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap repoInfoMap =
						jp.aegif.nemaki.util.spring.SpringContext.getApplicationContext()
							.getBean("repositoryInfoMap", jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap.class);
					if (repoInfoMap != null) {
						repositoryIds.addAll(repoInfoMap.getMainRepositoryKeys());
					}
				} catch (Exception e) {
					// RepositoryInfoMap not available yet — use known defaults
					repositoryIds.add("bedroom");
					repositoryIds.add("canopy");
				}
				if (!repositoryIds.contains("nemaki_conf")) {
					repositoryIds.add("nemaki_conf");
				}
				boolean streamSet = false;
				
				for (String repositoryId : repositoryIds) {
					try {
						jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper client = connectorPool.getClient(repositoryId);
						if (client != null) {
							// Get the actual binary attachment from CouchDB
							Object attachmentData = client.getAttachment(getId(), "content");
							
							if (attachmentData instanceof java.io.InputStream) {
								a.setInputStream((java.io.InputStream) attachmentData);
								streamSet = true;
								break;
							}
						}
					} catch (Exception repoEx) {
						// Try next repository
						continue;
					}
				}
				
				if (!streamSet) {
					// Log warning but don't fail - allows system to continue working
					if (log.isDebugEnabled()) {
						log.debug("Could not retrieve InputStream for attachment " + getId() + " from any repository");
					}
				}
			} else {
				if (log.isDebugEnabled()) {
					log.debug("connectorPool is null or getId() is null");
				}
			}
		} catch (Exception e) {
			// Log error but don't fail the conversion - allows system to continue working
			if (log.isDebugEnabled()) {
				log.debug("Failed to retrieve InputStream for attachment " + getId() + ": " + e.getMessage());
			}
		}

		return a;
	}
	
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
