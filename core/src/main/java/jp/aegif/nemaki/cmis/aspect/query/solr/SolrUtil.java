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
package jp.aegif.nemaki.cmis.aspect.query.solr;

import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.TextExtractionService;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;

import java.util.List;
import java.util.ArrayList;
import org.antlr.runtime.tree.Tree;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.impl.Http2SolrClient;

import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrInputDocument;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.Relationship;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.model.AttachmentNode;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * Common utility class for Solr query
 *
 * @author linzhixing
 *
 */
public class SolrUtil implements ApplicationContextAware {
	private static final Logger log = LoggerFactory.getLogger(SolrUtil.class);

	private final HashMap<String, String> map;

	private PropertyManager propertyManager;
	private TypeService typeService;
	private TextExtractionService textExtractionService;

	// CRITICAL FIX (2025-11-19): Use ApplicationContext for lazy ContentService retrieval
	// to break circular dependency between SolrUtil and ContentService
	private ApplicationContext applicationContext;

	// Cached ContentService instance to avoid repeated applicationContext.getBean() calls
	private volatile ContentService contentServiceCache;

	public SolrUtil() {
		map = new HashMap<String, String>();
		map.put(PropertyIds.OBJECT_ID, "object_id");
		map.put(PropertyIds.BASE_TYPE_ID, "basetype");
		map.put(PropertyIds.OBJECT_TYPE_ID, "objecttype");
		map.put(PropertyIds.NAME, "name");
		map.put(PropertyIds.DESCRIPTION, "cmis_description");
		map.put(PropertyIds.CREATION_DATE, "creation_date");
		map.put(PropertyIds.CREATED_BY, "creator");
		map.put(PropertyIds.LAST_MODIFICATION_DATE, "modified");
		map.put(PropertyIds.LAST_MODIFIED_BY, "modifier");
		map.put(PropertyIds.SECONDARY_OBJECT_TYPE_IDS,
				"secondary_object_type_ids");

		map.put(PropertyIds.IS_LATEST_VERSION, "is_latest_version");
		map.put(PropertyIds.IS_MAJOR_VERSION, "is_major_version");
		map.put(PropertyIds.IS_PRIVATE_WORKING_COPY, "is_pwc");
		map.put(PropertyIds.IS_VERSION_SERIES_CHECKED_OUT, "is_checkedout");
		map.put(PropertyIds.VERSION_SERIES_CHECKED_OUT_ID, "checkedout_id");
		map.put(PropertyIds.VERSION_SERIES_CHECKED_OUT_BY, "checkedout_by");
		map.put(PropertyIds.CHECKIN_COMMENT, "checkin_comment");
		map.put(PropertyIds.VERSION_LABEL, "version_label");
		map.put(PropertyIds.VERSION_SERIES_ID, "version_series_id");
		map.put(PropertyIds.CONTENT_STREAM_ID, "content_id");
		map.put(PropertyIds.CONTENT_STREAM_FILE_NAME, "content_name");
		map.put(PropertyIds.CONTENT_STREAM_LENGTH, "content_length");
		map.put(PropertyIds.CONTENT_STREAM_MIME_TYPE, "content_mimetype");

		map.put(PropertyIds.PARENT_ID, "parent_id");
		map.put(PropertyIds.PATH, "path");
		map.put(PropertyIds.ALLOWED_CHILD_OBJECT_TYPE_IDS, "allowed_child_object_type_ids");
	}

	/**
	 * Get Solr server instance
	 *
	 * @return
	 */
	public SolrClient getSolrClient() {
		String url = getSolrUrl();
		log.info("Creating Solr client for URL: " + url);
		
		// Skip Http2SolrClient for Jakarta EE compatibility - use HttpSolrClient directly
		log.debug("Using HttpSolrClient for Jakarta EE compatibility - skipping Http2SolrClient");
		
		// Fallback to HttpSolrClient for compatibility
		try {
			log.debug("Attempting HttpSolrClient fallback");
			@SuppressWarnings("deprecation")
			HttpSolrClient client = new HttpSolrClient.Builder(url)
				.withConnectionTimeout(30000)
				.withSocketTimeout(30000)
				.build();
			log.debug("HttpSolrClient created successfully for URL: {}", url);
			return client;
		} catch (Exception e) {
			log.error("HttpSolrClient creation failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
			e.printStackTrace();
			log.error("All Solr client implementations failed: " + e.getMessage(), e);
		}
		
		// Final fallback: Return null for graceful degradation to database-only queries
		log.error("All Solr client implementations failed - HttpSolrClient unavailable");
		log.error("CMIS queries will use database fallback instead of full-text search");
		log.error("This is expected when Solr server is unavailable or network connectivity issues exist");
		return null;  // Return null to trigger database fallback
	}

	/**
	 * CMIS to Solr property name dictionary
	 *
	 * @param cmisColName
	 * @return
	 */
	public String getPropertyNameInSolr(String repositoryId,String cmisColName) {
		
	//TODO: secondary types
		String val = map.get(cmisColName);
		NemakiPropertyDefinitionCore pd = typeService.getPropertyDefinitionCoreByPropertyId(repositoryId, cmisColName);
		if (val == null) {
			if(pd.getPropertyType().equals(PropertyType.DATETIME)){
				val = "dynamicDate.property." + cmisColName;
			}else{
				// case for STRING
				val = "dynamic.property." + cmisColName.replace(":", "\\:").replace("\\\\:", "\\:");				
			}
			
		}

		return val;
	}

	public String convertToString(Tree propertyNode) {
		List<String> _string = new ArrayList<String>();
		for (int i = 0; i < propertyNode.getChildCount(); i++) {
			_string.add(propertyNode.getChild(i).toString());
		}
		return StringUtils.join(_string, ".");
	}

	/**
	 * Convert GregorianCalendar to ISO8601 string format for Solr
	 */
	private String formatDateForSolr(GregorianCalendar calendar) {
		if (calendar == null) {
			return null;
		}
		// Convert to ISO8601 format: 2025-07-14T12:58:02.056Z
		return String.format("%04d-%02d-%02dT%02d:%02d:%02d.%03dZ",
			calendar.get(Calendar.YEAR),
			calendar.get(Calendar.MONTH) + 1, // Calendar.MONTH is 0-based
			calendar.get(Calendar.DAY_OF_MONTH),
			calendar.get(Calendar.HOUR_OF_DAY),
			calendar.get(Calendar.MINUTE),
			calendar.get(Calendar.SECOND),
			calendar.get(Calendar.MILLISECOND)
		);
	}

	/**
	 * Index a single document in Solr using standard SolrJ API
	 */
	public void indexDocument(String repositoryId, Content content) {
		indexDocument(repositoryId, content, false);
	}

	/**
	 * Index a single document in Solr using standard SolrJ API
	 * @param repositoryId the repository ID
	 * @param content the content to index
	 * @param forceSync if true, bypasses the solr.indexing.force setting and indexes synchronously
	 *                  (used for maintenance operations)
	 */
	public void indexDocument(String repositoryId, Content content, boolean forceSync) {
		if (log.isDebugEnabled()) {
			log.debug("indexDocument called for " + content.getId());
		}
		log.info("SolrUtil.indexDocument called for document: " + content.getId() + " in repository: " + repositoryId);
		
		String _force = propertyManager
				.readValue(PropertyKey.SOLR_INDEXING_FORCE);
		boolean force = (Boolean.TRUE.toString().equals(_force)) ? true : false;

		log.info("Solr indexing force setting: " + force + ", forceSync: " + forceSync);

		// For maintenance operations (forceSync=true), bypass the force setting check
		if (!force && !forceSync) {
			log.info("Solr indexing is disabled (force=false), skipping indexing");
			return;
		}

		// For maintenance operations, execute synchronously to track progress accurately
		if (forceSync) {
			indexDocumentInternal(repositoryId, content);
		} else {
			// Execute Solr indexing asynchronously to avoid blocking CMIS operations
			CompletableFuture.runAsync(() -> {
				indexDocumentInternal(repositoryId, content);
			});
		}
	}

	/**
	 * Batch index multiple documents in Solr for improved performance.
	 * Uses a single UpdateRequest with commitWithin for efficient bulk indexing.
	 * @param repositoryId the repository ID
	 * @param contents list of contents to index
	 * @param commitWithinMs commit within milliseconds (default 5000 for batch operations)
	 * @return number of successfully indexed documents
	 */
	public int indexDocumentsBatch(String repositoryId, List<Content> contents, int commitWithinMs) {
		if (contents == null || contents.isEmpty()) {
			return 0;
		}
		
		log.info("Batch indexing " + contents.size() + " documents for repository: " + repositoryId);
		
		SolrClient solrClient = null;
		int successCount = 0;
		try {
			solrClient = getSolrClient();
			if (solrClient == null) {
				log.warn("Solr client is null, skipping batch indexing");
				return 0;
			}
			
			UpdateRequest updateRequest = new UpdateRequest();
			updateRequest.setCommitWithin(commitWithinMs > 0 ? commitWithinMs : 5000);
			
			for (Content content : contents) {
				try {
					SolrInputDocument doc = createSolrDocument(repositoryId, content);
					updateRequest.add(doc);
					successCount++;
				} catch (Exception e) {
					log.warn("Failed to create Solr document for " + content.getId() + ": " + e.getMessage());
				}
			}
			
			if (successCount > 0) {
				UpdateResponse response = updateRequest.process(solrClient);
				if (response.getStatus() == 0) {
					log.info("Batch indexed " + successCount + " documents successfully");
				} else {
					// Throw exception to trigger fallback to individual indexing in caller
					log.error("Batch indexing failed with status: " + response.getStatus());
					throw new RuntimeException("Solr batch indexing failed with status: " + response.getStatus());
				}
			}
		} catch (SolrServerException e) {
			log.error("Solr server error during batch indexing: " + e.getMessage(), e);
			throw new RuntimeException("Solr batch indexing failed: " + e.getMessage(), e);
		} catch (IOException e) {
			log.error("IO error during batch indexing: " + e.getMessage(), e);
			throw new RuntimeException("Solr batch indexing failed: " + e.getMessage(), e);
		} catch (Exception e) {
			log.error("Unexpected error during batch indexing: " + e.getMessage(), e);
			throw new RuntimeException("Solr batch indexing failed: " + e.getMessage(), e);
		} finally {
			if (solrClient != null) {
				try {
					solrClient.close();
				} catch (IOException e) {
					log.warn("Failed to close Solr client: " + e.getMessage());
				}
			}
		}
		
		return successCount;
	}

	/**
	 * Internal method to perform the actual Solr indexing
	 */
	private void indexDocumentInternal(String repositoryId, Content content) {
		SolrClient solrClient = null;
		try {
			log.info("Starting Solr indexing for document: " + content.getId());
			solrClient = getSolrClient();
			
			if (solrClient == null) {
				log.warn("Solr client is null, skipping indexing for document: " + content.getId());
				return;
			}
			
			SolrInputDocument doc = createSolrDocument(repositoryId, content);
			
			log.info("Created SolrInputDocument with " + doc.size() + " fields for document: " + content.getId());
			log.debug("Document fields: repository_id={}, object_id={}, basetype={}, name={}", 
				doc.getFieldValue("repository_id"), doc.getFieldValue("object_id"), 
				doc.getFieldValue("basetype"), doc.getFieldValue("name"));
			
			UpdateRequest updateRequest = new UpdateRequest();
			updateRequest.add(doc);
			updateRequest.setCommitWithin(1000); // Commit within 1 second
			
			// DIRECT FIX: Don't pass core name to avoid URL duplication
			// getSolrUrl() already returns full URL with core path
			UpdateResponse response = updateRequest.process(solrClient);
			
			log.info("Solr response status: " + response.getStatus() + " for document: " + content.getId());
			
			if (response.getStatus() == 0) {
				log.info("Document indexed successfully in Solr: " + content.getId() + " for repository: " + repositoryId);
			} else {
				log.error("Document indexing failed with status: " + response.getStatus() + " for document: " + content.getId());
			}
		} catch (SolrServerException e) {
			log.error("Solr server error during indexing for document: " + content.getId() + " in repository: " + repositoryId + ", details: " + e.getMessage(), e);
			throw new RuntimeException("Solr indexing failed: " + e.getMessage(), e);
		} catch (IOException e) {
			log.error("IO error during Solr indexing for document: " + content.getId() + " in repository: " + repositoryId + ", details: " + e.getMessage(), e);
			throw new RuntimeException("Solr indexing failed: " + e.getMessage(), e);
		} catch (Exception e) {
			log.error("Unexpected error during Solr indexing for document: " + content.getId() + " in repository: " + repositoryId + ", details: " + e.getMessage(), e);
			throw new RuntimeException("Solr indexing failed: " + e.getMessage(), e);
		} finally {
			if (solrClient != null) {
				try {
					solrClient.close();
				} catch (IOException e) {
					log.warn("Failed to close Solr client: " + e.getMessage());
				}
			}
		}
	}

	/**
	 * Create SolrInputDocument from NemakiWare Content
	 */
	private SolrInputDocument createSolrDocument(String repositoryId, Content content) {
		if (log.isDebugEnabled()) {
			log.debug("Creating Solr document for content: {} (type: {}) in repository: {}",
				content.getId(), content.getType(), repositoryId);
		}

		SolrInputDocument doc = new SolrInputDocument();
		
		// Core system fields
		doc.addField("id", content.getId());
		doc.addField("repository_id", repositoryId);
		doc.addField("object_id", content.getId());
		
		// Fix basetype field - determine proper CMIS base type
		String baseTypeId = determineBaseTypeId(content);
		doc.addField("basetype", baseTypeId);
		log.debug("Set basetype to: {} for content: {}", baseTypeId, content.getId());
		
		doc.addField("objecttype", content.getObjectType());
		doc.addField("name", content.getName());
		
		// Timestamps - convert GregorianCalendar to ISO8601 string for Solr
		if (content.getCreated() != null) {
			String createdISO = formatDateForSolr(content.getCreated());
			doc.addField("created", createdISO);
			doc.addField("creation_date", createdISO);  // Add for ORDER BY queries
		}
		if (content.getModified() != null) {
			String modifiedISO = formatDateForSolr(content.getModified());
			doc.addField("modified", modifiedISO);
			doc.addField("modification_date", modifiedISO);  // Add for ORDER BY queries
		}
		
		// Creator/Modifier
		if (content.getCreator() != null) {
			doc.addField("creator", content.getCreator());
		}
		if (content.getModifier() != null) {
			doc.addField("modifier", content.getModifier());
		}
		
		// Description
		if (content.getDescription() != null) {
			doc.addField("cmis_description", content.getDescription());
		}
		
		// Path field - critical for IN_TREE queries and search results
		// CRITICAL FIX (2025-11-19): Calculate and index path field using lazy ContentService retrieval
		ContentService contentServiceInstance = getContentServiceSafely();
		if (contentServiceInstance != null) {
			try {
				String path = contentServiceInstance.calculatePath(repositoryId, content);
				if (path != null && !path.isEmpty()) {
					doc.addField("path", path);
					log.debug("Added path field: {} for content: {}", path, content.getId());
				}
			} catch (Exception e) {
				log.warn("Failed to calculate path for content {}: {}", content.getId(), e.getMessage());
			}
		} else {
			log.debug("ContentService not yet available during Solr indexing, skipping path field for content: {}", content.getId());
		}

		// Parent ID field - required for IN_FOLDER queries
		if (content.getParentId() != null) {
			doc.addField("parent_id", content.getParentId());
			log.debug("Added parent_id: {} for content: {}", content.getParentId(), content.getId());
		}
		
		// Type-specific fields
		if (content instanceof Document) {
			Document document = (Document) content;

			// Basic document fields available
			if (document.getAttachmentNodeId() != null) {
				doc.addField("content_id", document.getAttachmentNodeId());

				// Extract text content for full-text search
				try {
					String textContent = extractTextContent(repositoryId, document.getAttachmentNodeId());
					if (textContent != null && !textContent.trim().isEmpty()) {
						doc.addField("content", textContent);
						doc.addField("text", textContent);  // Add text field for CONTAINS queries
						if (log.isDebugEnabled()) {
							log.debug("Added text content ({} chars) for document: {}", textContent.length(), content.getId());
						}
					}
				} catch (Exception e) {
					log.warn("Failed to extract text content for document {}: {}", content.getId(), e.getMessage());
				}
				
				// Add content_length field for numeric range queries
				long contentLength = getContentLength(repositoryId, document.getAttachmentNodeId());
				doc.addField("content_length", contentLength);
			}
			
			// Versioning fields
			Boolean isLatest = document.isLatestVersion();
			if (isLatest != null) {
				doc.addField("is_latest_version", isLatest);
			}
			
			Boolean isMajor = document.isMajorVersion();
			if (isMajor != null) {
				doc.addField("is_major_version", isMajor);
			}
			
			Boolean isPwc = document.isPrivateWorkingCopy();
			if (isPwc != null) {
				doc.addField("is_pwc", isPwc);
			}
			
			if (document.getVersionLabel() != null) {
				doc.addField("version_label", document.getVersionLabel());
			}
			if (document.getVersionSeriesId() != null) {
				doc.addField("version_series_id", document.getVersionSeriesId());
			}
			if (document.getCheckinComment() != null) {
				doc.addField("checkin_comment", document.getCheckinComment());
			}
		}
		
		if (content instanceof Folder) {
			Folder folder = (Folder) content;
			// Folder specific fields - parent_id already added above for all content types
		}

		// Relationship specific fields for CMIS query support
		if (content instanceof Relationship) {
			Relationship relationship = (Relationship) content;

			// Source and target IDs - required for relationship queries
			// Use dynamic.* naming convention to match Solr schema
			if (relationship.getSourceId() != null) {
				doc.addField("dynamic.source_id", relationship.getSourceId());
				log.info("Added dynamic.source_id: {} for relationship: {}", relationship.getSourceId(), content.getId());
			}
			if (relationship.getTargetId() != null) {
				doc.addField("dynamic.target_id", relationship.getTargetId());
				log.info("Added dynamic.target_id: {} for relationship: {}", relationship.getTargetId(), content.getId());
			}

			// Index custom properties (subTypeProperties) for relationship type queries
			List<Property> subTypeProperties = relationship.getSubTypeProperties();
			if (subTypeProperties != null && !subTypeProperties.isEmpty()) {
				for (Property prop : subTypeProperties) {
					if (prop.getKey() != null && prop.getValue() != null) {
						// Use dynamic field naming for custom properties
						String fieldName = "dynamic.property." + prop.getKey();
						doc.addField(fieldName, prop.getValue().toString());
						log.info("Added custom property: {} = {} for relationship: {}",
							fieldName, prop.getValue(), content.getId());
					}
				}
			}
		}

		// Change token
		if (content.getChangeToken() != null) {
			doc.addField("change_token", content.getChangeToken());
		}

		// CRITICAL FIX (2025-12-18): Index secondary type IDs for cmis:secondaryObjectTypeIds queries
		List<String> secondaryIds = content.getSecondaryIds();
		if (secondaryIds != null && !secondaryIds.isEmpty()) {
			for (String secondaryId : secondaryIds) {
				doc.addField("secondary_object_type_ids", secondaryId);
			}
			if (log.isDebugEnabled()) {
				log.debug("Added {} secondary type IDs for content: {}", secondaryIds.size(), content.getId());
			}
		}

		// CRITICAL FIX (2025-12-18): Index secondary type (aspect) properties for attribute search
		// This enables queries like: nemaki:comment LIKE '%テスト%'
		// NOTE: Field names should NOT be escaped when adding to SolrInputDocument
		// Escaping is only needed in query strings, not field names
		List<jp.aegif.nemaki.model.Aspect> aspects = content.getAspects();
		if (aspects != null && !aspects.isEmpty()) {
			for (jp.aegif.nemaki.model.Aspect aspect : aspects) {
				List<jp.aegif.nemaki.model.Property> properties = aspect.getProperties();
				if (properties != null) {
					for (jp.aegif.nemaki.model.Property prop : properties) {
						String key = prop.getKey();
						Object value = prop.getValue();
						if (key != null && value != null) {
							// Use dynamic field naming convention for custom properties
							// Matches Solr dynamicField pattern: dynamic.* (no escaping needed for field names)
							String solrFieldName = "dynamic.property." + key;

							// Handle multi-value properties (List) vs single value
							if (value instanceof List) {
								for (Object item : (List<?>) value) {
									if (item != null) {
										doc.addField(solrFieldName, item.toString());
									}
								}
							} else {
								doc.addField(solrFieldName, value.toString());
							}

							if (log.isDebugEnabled()) {
								log.debug("Added aspect property {} = {} for content: {}", key, value, content.getId());
							}
						}
					}
				}
			}
		}

		// CRITICAL FIX (2025-12-18): Index subtype properties (from primary type subtypes)
		List<jp.aegif.nemaki.model.Property> subTypeProperties = content.getSubTypeProperties();
		if (subTypeProperties != null && !subTypeProperties.isEmpty()) {
			for (jp.aegif.nemaki.model.Property prop : subTypeProperties) {
				String key = prop.getKey();
				Object value = prop.getValue();
				if (key != null && value != null) {
					// Use dynamic field naming convention (no escaping for field names)
					String solrFieldName = "dynamic.property." + key;

					if (value instanceof List) {
						for (Object item : (List<?>) value) {
							if (item != null) {
								doc.addField(solrFieldName, item.toString());
							}
						}
					} else {
						doc.addField(solrFieldName, value.toString());
					}

					if (log.isDebugEnabled()) {
						log.debug("Added subtype property {} = {} for content: {}", key, value, content.getId());
					}
				}
			}
		}

		if (log.isDebugEnabled()) {
			log.debug("Created Solr document for content: {} with {} fields", content.getId(), doc.size());
		}
		return doc;
	}

	/**
	 * Determine the correct CMIS base type for content
	 */
	private String determineBaseTypeId(Content content) {
		if (content instanceof Document) {
			return "cmis:document";
		} else if (content instanceof Folder) {
			return "cmis:folder";
		} else if (content.getType() != null) {
			// Use content type if available
			String type = content.getType();
			if (type.equals("cmis:document") || type.equals("cmis:folder") || 
				type.equals("cmis:relationship") || type.equals("cmis:policy") || 
				type.equals("cmis:item") || type.equals("cmis:secondary")) {
				return type;
			}
		}
		
		// Default fallback based on content class
		if (content instanceof Document) {
			return "cmis:document";
		} else if (content instanceof Folder) {
			return "cmis:folder";
		} else {
			return "cmis:item"; // Safe default for other content types
		}
	}

	/**
	 * Delete a document from Solr
	 */
	public void deleteDocument(String repositoryId, String documentId) {
		String _force = propertyManager
				.readValue(PropertyKey.SOLR_INDEXING_FORCE);
		boolean force = (Boolean.TRUE.toString().equals(_force)) ? true : false;

		if (!force)
			return;

		CompletableFuture.runAsync(() -> {
			try {
				SolrClient solrClient = getSolrClient();
				
				UpdateRequest updateRequest = new UpdateRequest();
				updateRequest.deleteById(documentId);
				updateRequest.setCommitWithin(1000);
				
				// DIRECT FIX: Don't pass core name to avoid URL duplication
				// getSolrUrl() already returns full URL with core path
				UpdateResponse response = updateRequest.process(solrClient);
				
				if (response.getStatus() == 0) {
					log.debug("Document deleted successfully from Solr: " + documentId + " for repository: " + repositoryId);
				} else {
					log.warn("Document deletion failed with status: " + response.getStatus() + " for document: " + documentId);
				}
				
				solrClient.close();
			} catch (SolrServerException | IOException e) {
				log.warn("Solr document deletion failed for document: " + documentId + " in repository: " + repositoryId + ", error: " + e.getMessage());
			}
		});
	}

	public String getSolrUrl(){
		String protocol = propertyManager.readValue(PropertyKey.SOLR_PROTOCOL);
		String host = propertyManager.readValue(PropertyKey.SOLR_HOST);
		int port = Integer.valueOf(propertyManager
				.readValue(PropertyKey.SOLR_PORT));
		String context = propertyManager.readValue(PropertyKey.SOLR_CONTEXT);

		if (log.isDebugEnabled()) {
			log.debug("PropertyManager class: " + propertyManager.getClass().getName());
			log.debug("PropertyManager readValue(SOLR_HOST): " + host);
			log.debug("PropertyKey.SOLR_HOST constant: " + PropertyKey.SOLR_HOST);
			log.debug("All property keys: " + propertyManager.getKeys());
			log.debug("SolrUtil.getSolrUrl: protocol=" + protocol + ", host=" + host + ", port=" + port + ", context=" + context);
		}

		String url = null;
		try {
			URL _url = new URL(protocol, host, port, "");
			
			// UPDATED FIX: Return full URL with core name since process() no longer adds it
			// This prevents the /nemaki/nemaki duplication by including core in base URL
			String baseContext = context;
			if (baseContext.contains("/")) {
				baseContext = baseContext.substring(0, baseContext.indexOf("/"));
				if (log.isDebugEnabled()) {
					log.debug("SolrUtil.getSolrUrl: Stripped context from '" + context + "' to '" + baseContext + "'");
				}
			}
			
			// Include the core name "nemaki" in the base URL since process() no longer adds it
			url = _url.toString() + "/" + baseContext + "/nemaki";
			if (log.isDebugEnabled()) {
				log.debug("SolrUtil.getSolrUrl: Built URL with core included: " + url);
			}
			
			// SAFETY: Ensure correct URL pattern with core included
			// Expected pattern: http://host:port/solr/nemaki
			String expectedPattern = protocol + "://" + host + ":" + port + "/solr/nemaki";
			if (!url.equals(expectedPattern)) {
				if (log.isDebugEnabled()) {
					log.debug("SolrUtil.getSolrUrl: URL mismatch, forcing correct pattern");
					log.debug("SolrUtil.getSolrUrl: Expected: " + expectedPattern + ", Got: " + url);
				}
				url = expectedPattern;
			}
			
			if (log.isDebugEnabled()) {
				log.debug("SolrUtil.getSolrUrl: final URL=" + url);
			}
		} catch (MalformedURLException e) {
			log.error("SolrUtil.getSolrUrl: MalformedURLException: " + e.getMessage(), e);
		}
//		log.info("Solr URL:" + url);
		return url;
	}

	public void setPropertyManager(PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}
	public void setTypeService(TypeService typeService) {
		this.typeService = typeService;
	}
	public void setTextExtractionService(TextExtractionService textExtractionService) {
		this.textExtractionService = textExtractionService;
	}
	// CRITICAL FIX (2025-11-19): Implement ApplicationContextAware to break circular dependency
	@Override
	public void setApplicationContext(ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}

	/**
	 * Get ContentService lazily from ApplicationContext to avoid circular dependency.
	 * This method returns null if ContentService is not yet available.
	 * Uses double-checked locking with volatile field for thread-safe lazy initialization.
	 */
	private ContentService getContentServiceSafely() {
		// Use cached instance if available
		ContentService cached = contentServiceCache;
		if (cached != null) {
			return cached;
		}

		if (applicationContext == null) {
			return null;
		}

		try {
			// Double-checked locking pattern
			synchronized (this) {
				cached = contentServiceCache;
				if (cached != null) {
					return cached;
				}
				cached = applicationContext.getBean("ContentService", ContentService.class);
				contentServiceCache = cached;
				return cached;
			}
		} catch (Exception e) {
			log.debug("ContentService not yet available: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * Get content length from AttachmentNode.
	 * Uses ContentService to retrieve the attachment and get its length.
	 *
	 * @param repositoryId Repository ID
	 * @param attachmentId Attachment node ID
	 * @return Content length in bytes, or 0 if not available
	 */
	private long getContentLength(String repositoryId, String attachmentId) {
		if (attachmentId == null || attachmentId.isEmpty()) {
			return 0L;
		}

		try {
			ContentService contentService = getContentServiceSafely();
			if (contentService == null) {
				log.debug("getContentLength: ContentService not available, returning 0");
				return 0L;
			}

			AttachmentNode attachment = contentService.getAttachment(repositoryId, attachmentId);
			if (attachment == null) {
				log.debug("getContentLength: Attachment not found: {}", attachmentId);
				return 0L;
			}

			return attachment.getLength();
		} catch (Exception e) {
			log.warn("getContentLength: Failed to get content length for attachment {}: {}", attachmentId, e.getMessage());
			return 0L;
		}
	}

	/**
	 * Extract text content from attachment for full-text search.
	 * Uses Apache Tika via TextExtractionService to extract text from various document formats
	 * including PDF, Word, Excel, PowerPoint, and plain text files.
	 *
	 * @param repositoryId Repository ID
	 * @param attachmentId Attachment node ID
	 * @return Extracted text content or null if extraction fails
	 */
	private String extractTextContent(String repositoryId, String attachmentId) {
		if (attachmentId == null || attachmentId.isEmpty()) {
			return null;
		}

		// Check if TextExtractionService is available
		if (textExtractionService == null) {
			log.warn("TextExtractionService not available - full-text search may not work properly");
			return null;
		}

		try {
			// Get ContentService to retrieve the attachment
			ContentService contentService = getContentServiceSafely();
			if (contentService == null) {
				return null;
			}

			// Retrieve the attachment node
			AttachmentNode attachment = contentService.getAttachment(repositoryId, attachmentId);
			if (attachment == null) {
				if (log.isDebugEnabled()) {
					log.debug("Attachment not found: {}", attachmentId);
				}
				return null;
			}

			// Get the content stream from the AttachmentNode
			java.io.InputStream contentStream = attachment.getInputStream();
			if (contentStream == null) {
				if (log.isDebugEnabled()) {
					log.debug("No content stream available for attachment: {}", attachmentId);
				}
				return null;
			}

			// Get MIME type and filename for better parsing
			String mimeType = attachment.getMimeType();
			String fileName = attachment.getName();

			// Check if the MIME type is supported for text extraction
			if (mimeType != null && !textExtractionService.isSupported(mimeType)) {
				if (log.isDebugEnabled()) {
					log.debug("MIME type {} not supported for text extraction", mimeType);
				}
				try {
					contentStream.close();
				} catch (Exception e) {
					// Ignore close errors
				}
				return null;
			}

			try {
				// Extract text using Tika via TextExtractionService
				String extractedText = textExtractionService.extractText(contentStream, mimeType, fileName);

				if (extractedText != null && !extractedText.isEmpty()) {
					if (log.isDebugEnabled()) {
						log.debug("Successfully extracted {} characters from {} ({})",
								extractedText.length(), fileName, mimeType);
					}
					return extractedText;
				} else {
					return null;
				}
			} finally {
				// Ensure the content stream is closed
				try {
					contentStream.close();
				} catch (Exception e) {
					// Ignore close errors
				}
			}

		} catch (Exception e) {
			log.warn("Failed to extract text content for attachment {}: {}", attachmentId, e.getMessage());
			return null;
		}
	}
	
	/**
	 * Read text from InputStream
	 */
	private String readTextFromInputStream(java.io.InputStream inputStream) throws Exception {
		try (java.io.BufferedReader reader = new java.io.BufferedReader(
				new java.io.InputStreamReader(inputStream, "UTF-8"))) {
			StringBuilder content = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				content.append(line).append("\n");
			}
			return content.toString();
		}
	}
	
	/**
	 * Calculate ancestors for IN_TREE queries
	 */
	private List<String> calculateAncestors(String repositoryId, Content content) {
		// Note: Ancestors calculation temporarily disabled due to circular dependency
		// TODO: Implement proper ancestors calculation without circular dependency
		return new ArrayList<>();
	}
}
