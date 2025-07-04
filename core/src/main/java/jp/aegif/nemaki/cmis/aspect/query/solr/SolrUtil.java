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
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;
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
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Common utility class for Solr query
 *
 * @author linzhixing
 *
 */
public class SolrUtil {
	private static final Logger log = LoggerFactory.getLogger(SolrUtil.class);

	private final HashMap<String, String> map;

	private PropertyManager propertyManager;
	private TypeService typeService;

	public SolrUtil() {
		map = new HashMap<String, String>();
		map.put(PropertyIds.OBJECT_ID, "object_id");
		map.put(PropertyIds.BASE_TYPE_ID, "basetype");
		map.put(PropertyIds.OBJECT_TYPE_ID, "objecttype");
		map.put(PropertyIds.NAME, "name");
		map.put(PropertyIds.DESCRIPTION, "cmis_description");
		map.put(PropertyIds.CREATION_DATE, "created");
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
	}

	/**
	 * Get Solr server instance
	 *
	 * @return
	 */
	public SolrClient getSolrClient() {
		String url = getSolrUrl();
		log.info("Creating Solr client for URL: " + url);
		
		// Force debug output to verify this method is called
		System.err.println("=== SOLR DEBUG: getSolrClient() called with URL: " + url);
		
		try {
			java.io.FileWriter fw = new java.io.FileWriter("/tmp/solr-debug.log", true);
			fw.write(java.time.LocalDateTime.now() + ": getSolrClient() called with URL: " + url + "\n");
			fw.close();
		} catch (Exception e) {
			// Ignore file write errors
		}
		
		// Use Http2SolrClient for Solr 9.x compatibility
		try {
			log.debug("Attempting Http2SolrClient for Solr 9.x compatibility");
			System.err.println("=== SOLR DEBUG: Creating Http2SolrClient with URL: " + url);
			Http2SolrClient client = new Http2SolrClient.Builder(url)
				.connectionTimeout(30000)
				.idleTimeout(30000)
				.build();
			System.err.println("=== SOLR DEBUG: Http2SolrClient created successfully");
			return client;
		} catch (Exception e) {
			System.err.println("=== SOLR DEBUG: Http2SolrClient creation failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
			log.debug("Http2SolrClient failed, trying fallback to HttpSolrClient");
		}
		
		// Fallback to HttpSolrClient for compatibility
		try {
			log.debug("Attempting HttpSolrClient fallback");
			System.err.println("=== SOLR DEBUG: Creating HttpSolrClient fallback with URL: " + url);
			@SuppressWarnings("deprecation")
			HttpSolrClient client = new HttpSolrClient.Builder(url)
				.withConnectionTimeout(30000)
				.withSocketTimeout(30000)
				.build();
			System.err.println("=== SOLR DEBUG: HttpSolrClient fallback created successfully");
			return client;
		} catch (Exception e) {
			System.err.println("=== SOLR DEBUG: HttpSolrClient fallback failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
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
	 * Index a single document in Solr using standard SolrJ API
	 */
	public void indexDocument(String repositoryId, Content content) {
		System.out.println("=== SLF4J TEST: indexDocument called for " + content.getId());
		log.info("SolrUtil.indexDocument called for document: " + content.getId() + " in repository: " + repositoryId);
		
		String _force = propertyManager
				.readValue(PropertyKey.SOLR_INDEXING_FORCE);
		boolean force = (Boolean.TRUE.toString().equals(_force)) ? true : false;

		log.info("Solr indexing force setting: " + force);

		if (!force) {
			log.info("Solr indexing is disabled (force=false), skipping indexing");
			return;
		}

		// Execute Solr indexing asynchronously to avoid blocking CMIS operations
		CompletableFuture.runAsync(() -> {
			try {
				log.info("Starting async Solr indexing for document: " + content.getId());
				SolrClient solrClient = getSolrClient();
				
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
				
				UpdateResponse response = updateRequest.process(solrClient, "nemaki");
				
				log.info("Solr response status: " + response.getStatus() + " for document: " + content.getId());
				
				if (response.getStatus() == 0) {
					log.info("Document indexed successfully in Solr: " + content.getId() + " for repository: " + repositoryId);
				} else {
					log.error("Document indexing failed with status: " + response.getStatus() + " for document: " + content.getId());
				}
				
				solrClient.close();
			} catch (SolrServerException e) {
				log.error("Solr server error during indexing for document: " + content.getId() + " in repository: " + repositoryId + ", details: " + e.getMessage(), e);
			} catch (IOException e) {
				log.error("IO error during Solr indexing for document: " + content.getId() + " in repository: " + repositoryId + ", details: " + e.getMessage(), e);
			} catch (Exception e) {
				log.error("Unexpected error during Solr indexing for document: " + content.getId() + " in repository: " + repositoryId + ", details: " + e.getMessage(), e);
			}
		});
	}

	/**
	 * Create SolrInputDocument from NemakiWare Content
	 */
	private SolrInputDocument createSolrDocument(String repositoryId, Content content) {
		SolrInputDocument doc = new SolrInputDocument();
		
		log.debug("Creating Solr document for content ID: {} in repository: {}", content.getId(), repositoryId);
		
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
		
		// Timestamps
		if (content.getCreated() != null) {
			doc.addField("created", content.getCreated());
		}
		if (content.getModified() != null) {
			doc.addField("modified", content.getModified());
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
		
		// Type-specific fields
		if (content instanceof Document) {
			Document document = (Document) content;
			
			// Basic document fields available
			if (document.getAttachmentNodeId() != null) {
				doc.addField("content_id", document.getAttachmentNodeId());
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
			// Folder specific fields
			if (folder.getParentId() != null) {
				doc.addField("parent_id", folder.getParentId());
			}
		}
		
		// Change token
		if (content.getChangeToken() != null) {
			doc.addField("change_token", content.getChangeToken());
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
				
				UpdateResponse response = updateRequest.process(solrClient, "nemaki");
				
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

		System.out.println("DEBUG SolrUtil.getSolrUrl: protocol=" + protocol + ", host=" + host + ", port=" + port + ", context=" + context);

		String url = null;
		try {
			URL _url = new URL(protocol, host, port, "");
			// Directly include nemaki core in URL for Solr 9.x compatibility
			url = _url.toString() + "/" + context + "/nemaki";
			System.out.println("DEBUG SolrUtil.getSolrUrl: final URL=" + url);
		} catch (MalformedURLException e) {
			System.out.println("DEBUG SolrUtil.getSolrUrl: MalformedURLException: " + e.getMessage());
			e.printStackTrace();
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
}
