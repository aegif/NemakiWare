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
import jp.aegif.nemaki.businesslogic.RAGIndexMaintenanceService;
import jp.aegif.nemaki.rag.indexing.RAGIndexingService;
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
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;

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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
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

	// ACL-in-Solr: lazily-resolved ACLExpander (RAG @Component) used to stamp
	// repository-scoped reader tokens onto the `readers` field of every content
	// document, so the query path can filter by the caller's principals in Solr.
	private volatile jp.aegif.nemaki.rag.acl.ACLExpander aclExpanderCache;

	// BTL-004: Shared SolrClient instance — HttpSolrClient is thread-safe
	private volatile SolrClient sharedSolrClient;
	private final Object solrClientLock = new Object();

	// BTL-009: Dedicated executor for async Solr operations.
	// CallerRunsPolicy provides backpressure: when the queue is full the calling
	// thread executes the task synchronously, ensuring no index/delete operation
	// is silently lost (which would cause search result inconsistency).
	// Instance field (not static) so a fresh executor is created on Spring context restart.
	// corePoolSize=2 ensures parallel indexing without waiting for queue saturation.
	// Initialized in constructor (not instance initializer) for Mockito compatibility.
	private java.util.concurrent.ExecutorService asyncSolrExecutor;

	// Retry configuration for async Solr indexing
	private static final int SOLR_INDEX_MAX_RETRY = 2;
	private static final long[] SOLR_INDEX_RETRY_DELAYS_MS = {1000, 3000};

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

		// Initialize async executor in constructor for Mockito compatibility
		java.util.concurrent.ThreadPoolExecutor tpe = new java.util.concurrent.ThreadPoolExecutor(
			2, 4, 60L, java.util.concurrent.TimeUnit.SECONDS,
			new java.util.concurrent.LinkedBlockingQueue<>(512),
			Thread.ofVirtual().name("solr-async-", 0).factory(),
			new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
		tpe.allowCoreThreadTimeOut(true);
		this.asyncSolrExecutor = tpe;
	}

	/**
	 * Get shared Solr server instance (thread-safe, lazy-initialized).
	 * HttpSolrClient is thread-safe and reusable — creating one per call
	 * wastes TCP connections and increases GC pressure.
	 *
	 * @return shared SolrClient instance, or null if Solr is unreachable
	 */
	public SolrClient getSolrClient() {
		SolrClient client = sharedSolrClient;
		if (client != null) {
			return client;
		}
		synchronized (solrClientLock) {
			client = sharedSolrClient;
			if (client != null) {
				return client;
			}
			String url = getSolrUrl();
			if (url == null) {
				log.error("Solr URL is null — cannot create SolrClient");
				return null;
			}
			log.info("Creating shared Solr client for URL: " + url);
			try {
				// Force HTTP/1.1: the JDK client defaults to HTTP/2, which against
				// Solr 10 / Jetty 12 throws intermittent "RST_STREAM: Protocol
				// error" on update/block-join requests.
				HttpJdkSolrClient newClient = new HttpJdkSolrClient.Builder(url)
					.useHttp1_1(true)
					.withConnectionTimeout(30000, java.util.concurrent.TimeUnit.MILLISECONDS)
					.withRequestTimeout(30000, java.util.concurrent.TimeUnit.MILLISECONDS)
					.build();
				sharedSolrClient = newClient;
				log.info("Shared HttpJdkSolrClient created successfully for URL: " + url);
				return newClient;
			} catch (Exception e) {
				log.error("HttpJdkSolrClient creation failed: " + e.getMessage(), e);
				return null;
			}
		}
	}

	/**
	 * CMIS to Solr property name dictionary
	 *
	 * @param cmisColName
	 * @return
	 */
	public String getPropertyNameInSolr(String repositoryId, String cmisColName) {
		// First check the static mapping for standard CMIS properties
		String val = map.get(cmisColName);
		if (val != null) {
			return val;
		}

		// For custom properties (including secondary type properties),
		// look up the property definition to determine the field type
		NemakiPropertyDefinitionCore pd = null;
		if (typeService != null) {
			pd = typeService.getPropertyDefinitionCoreByPropertyId(repositoryId, cmisColName);
		} else {
			log.warn("TypeService is null, cannot determine property type for: " + cmisColName);
		}

		// Handle DATETIME properties with special field type
		if (pd != null && PropertyType.DATETIME.equals(pd.getPropertyType())) {
			return "dynamicDate.property." + cmisColName;
		}

		// Default to STRING type for all other properties
		// (including secondary type properties when pd is null)
		// Note: Escape colons in field names for Solr query syntax
		return "dynamic.property." + cmisColName.replace(":", "\\:").replace("\\\\:", "\\:");
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
		indexDocument(repositoryId, content, false, false);
	}

	/**
	 * Index a single document in Solr using standard SolrJ API
	 * @param repositoryId the repository ID
	 * @param content the content to index
	 * @param forceSync if true, bypasses the solr.indexing.force setting and indexes synchronously
	 *                  (used for maintenance operations)
	 */
	public void indexDocument(String repositoryId, Content content, boolean forceSync) {
		indexDocument(repositoryId, content, forceSync, false);
	}

	/**
	 * Index a single document in Solr using standard SolrJ API
	 * @param repositoryId the repository ID
	 * @param content the content to index
	 * @param forceSync if true, bypasses the solr.indexing.force setting and indexes synchronously
	 * @param skipRAGIndexing if true, skip RAG re-indexing (TEI embedding).
	 *                        Use this for metadata-only changes (e.g. ACL) where document content has not changed.
	 */
	public void indexDocument(String repositoryId, Content content, boolean forceSync, boolean skipRAGIndexing) {
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
			indexDocumentInternal(repositoryId, content, skipRAGIndexing);
		} else {
			// Execute Solr indexing asynchronously to avoid blocking CMIS operations
			CompletableFuture.runAsync(() -> {
				indexDocumentInternal(repositoryId, content, skipRAGIndexing);
			}, asyncSolrExecutor).exceptionally(ex -> {
				log.warn("Solr async indexing failed for {}, scheduling retry: {}", content.getId(), ex.getMessage());
				scheduleRetry(() -> indexDocumentInternal(repositoryId, content, skipRAGIndexing), content.getId(), 1);
				return null;
			});
		}
	}

	/**
	 * Schedule a retry for a failed async Solr operation with exponential backoff.
	 * @param task the operation to retry
	 * @param docId document ID for logging
	 * @param attempt current retry attempt (1-based)
	 */
	private void scheduleRetry(Runnable task, String docId, int attempt) {
		if (attempt > SOLR_INDEX_MAX_RETRY) {
			log.error("Solr indexing permanently failed for document {} after {} retries", docId, SOLR_INDEX_MAX_RETRY);
			return;
		}
		long delay = SOLR_INDEX_RETRY_DELAYS_MS[Math.min(attempt - 1, SOLR_INDEX_RETRY_DELAYS_MS.length - 1)];
		log.info("Scheduling Solr indexing retry {} for document {} in {}ms", attempt, docId, delay);
		CompletableFuture.runAsync(() -> {
			try {
				Thread.sleep(delay);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			task.run();
		}, asyncSolrExecutor).exceptionally(ex -> {
			log.warn("Solr indexing retry {} failed for {}: {}", attempt, docId, ex.getMessage());
			scheduleRetry(task, docId, attempt + 1);
			return null;
		});
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
		return indexDocumentsBatch(repositoryId, contents, commitWithinMs, false);
	}

	/**
	 * Batch index multiple documents in Solr for improved performance.
	 * Uses a single UpdateRequest with commitWithin for efficient bulk indexing.
	 * @param repositoryId the repository ID
	 * @param contents list of contents to index
	 * @param commitWithinMs commit within milliseconds (default 5000 for batch operations)
	 * @param skipRAGIndexing if true, skip RAG re-indexing after batch Solr update
	 * @return number of successfully indexed documents
	 */
	public int indexDocumentsBatch(String repositoryId, List<Content> contents, int commitWithinMs, boolean skipRAGIndexing) {
		if (contents == null || contents.isEmpty()) {
			return 0;
		}
		
		log.info("Batch indexing " + contents.size() + " documents for repository: " + repositoryId);
		
		SolrClient solrClient = null;
		int successCount = 0;
		List<Content> indexedContents = new ArrayList<>();
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
					indexedContents.add(content);
					successCount++;
				} catch (Exception e) {
					log.warn("Failed to create Solr document for " + content.getId() + ": " + e.getMessage());
				}
			}
			
			if (successCount > 0) {
				UpdateResponse response = updateRequest.process(solrClient);
				if (response.getStatus() == 0) {
					log.info("Batch indexed " + successCount + " documents successfully");
					// Trigger RAG indexing for each document if not skipped
					if (!skipRAGIndexing) {
						for (Content content : indexedContents) {
							triggerRAGIndexing(repositoryId, content);
						}
					}
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
		}

		return successCount;
	}

	/**
	 * Internal method to perform the actual Solr indexing
	 * @param skipRAGIndexing if true, skip RAG re-indexing after Solr update
	 */
	private void indexDocumentInternal(String repositoryId, Content content, boolean skipRAGIndexing) {
		SolrClient solrClient = null;
		try {
			log.info("Starting Solr indexing for document: " + content.getId());
			solrClient = getSolrClient();
			
			if (solrClient == null) {
				throw new RuntimeException("Solr client is not available, cannot index document: " + content.getId());
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
				// Trigger RAG indexing asynchronously if enabled (skip for metadata-only changes like ACL)
				if (!skipRAGIndexing) {
					triggerRAGIndexing(repositoryId, content);
				} else {
					log.debug("Skipping RAG re-indexing for document: " + content.getId() + " (metadata-only change)");
				}
			} else {
				throw new RuntimeException("Solr indexing failed with status: " + response.getStatus() + " for document: " + content.getId());
			}
		} catch (SolrServerException e) {
			log.error("Solr server error during indexing for document: " + content.getId() + " in repository: " + repositoryId + ", details: " + e.getMessage(), e);
			throw new RuntimeException("Solr indexing failed: " + e.getMessage(), e);
		} catch (IOException e) {
			log.error("IO error during Solr indexing for document: " + content.getId() + " in repository: " + repositoryId + ", details: " + e.getMessage(), e);
			throw new RuntimeException("Solr indexing failed: " + e.getMessage(), e);
		} catch (RuntimeException e) {
			// Re-throw RuntimeExceptions (including the non-zero status one above)
			if (e.getMessage() != null && e.getMessage().startsWith("Solr")) {
				throw e;
			}
			log.error("Unexpected error during Solr indexing for document: " + content.getId() + " in repository: " + repositoryId + ", details: " + e.getMessage(), e);
			throw new RuntimeException("Solr indexing failed: " + e.getMessage(), e);
		} catch (Exception e) {
			log.error("Unexpected error during Solr indexing for document: " + content.getId() + " in repository: " + repositoryId + ", details: " + e.getMessage(), e);
			throw new RuntimeException("Solr indexing failed: " + e.getMessage(), e);
		}
	}

	/**
	 * Trigger RAG indexing asynchronously if RAG is enabled.
	 * This is called after successful Solr indexing.
	 * Only Document objects are indexed for RAG (folders are skipped).
	 */
	private void triggerRAGIndexing(String repositoryId, Content content) {
		// Only index documents (not folders or other content types)
		if (!(content instanceof Document)) {
			return;
		}
		Document document = (Document) content;

		CompletableFuture.runAsync(() -> {
			try {
				RAGIndexingService ragService = getRAGIndexingServiceSafely();
				if (ragService != null) {
					ragService.indexDocument(repositoryId, document);
					log.debug("RAG indexing triggered for document: " + document.getId());
				}
			} catch (Exception e) {
				// RAG indexing failure should not affect normal operations
				log.warn("RAG indexing failed for document: " + document.getId() + ", error: " + e.getMessage());
			}
		}, asyncSolrExecutor);
	}

	/**
	 * Trigger RAG document deletion asynchronously if RAG is enabled.
	 * Public so that callers like deleteContentStream can remove stale RAG embeddings
	 * without deleting the Solr document itself.
	 */
	public void triggerRAGDeletion(String repositoryId, String documentId) {
		CompletableFuture.runAsync(() -> {
			try {
				RAGIndexingService ragService = getRAGIndexingServiceSafely();
				if (ragService != null) {
					ragService.deleteDocument(repositoryId, documentId);
					log.debug("RAG document deletion triggered for: " + documentId);
				}
			} catch (Exception e) {
				// RAG deletion failure should not affect normal operations
				log.warn("RAG document deletion failed for: " + documentId + ", error: " + e.getMessage());
			}
		}, asyncSolrExecutor);
	}

	/**
	 * Trigger a full RAG re-index for the given repository.
	 * Called after a full Solr re-index to rebuild RAG embeddings that were
	 * deleted together with the Solr index (both share the same Solr core).
	 * No-op if RAG is not enabled.
	 */
	public void triggerFullRAGReindex(String repositoryId) {
		if (applicationContext == null) {
			return;
		}
		try {
			RAGIndexMaintenanceService ragMaintenance =
				applicationContext.getBean(RAGIndexMaintenanceService.class);
			if (ragMaintenance != null && ragMaintenance.isRAGEnabled()) {
				boolean started = ragMaintenance.startFullRAGReindex(repositoryId);
				if (started) {
					log.info("Full RAG re-index triggered for repository: " + repositoryId);
				} else {
					log.info("Full RAG re-index skipped (already running or not available) for repository: " + repositoryId);
				}
			}
		} catch (Exception e) {
			log.debug("RAGIndexMaintenanceService not available: " + e.getMessage());
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

		// User/Group search: index userId/groupId with lowercase normalized versions
		if (content instanceof jp.aegif.nemaki.model.UserItem) {
			jp.aegif.nemaki.model.UserItem userItem = (jp.aegif.nemaki.model.UserItem) content;
			if (userItem.getUserId() != null) {
				doc.addField("dynamic.usersearch.user_id", userItem.getUserId());
				doc.addField("dynamic.usersearch.user_id_lc", userItem.getUserId().toLowerCase(java.util.Locale.ROOT));
			}
			if (content.getName() != null) {
				doc.addField("dynamic.usersearch.name_lc", content.getName().toLowerCase(java.util.Locale.ROOT));
			}
		}
		if (content instanceof jp.aegif.nemaki.model.GroupItem) {
			jp.aegif.nemaki.model.GroupItem groupItem = (jp.aegif.nemaki.model.GroupItem) content;
			if (groupItem.getGroupId() != null) {
				doc.addField("dynamic.usersearch.group_id", groupItem.getGroupId());
				doc.addField("dynamic.usersearch.group_id_lc", groupItem.getGroupId().toLowerCase(java.util.Locale.ROOT));
			}
			if (content.getName() != null) {
				doc.addField("dynamic.usersearch.name_lc", content.getName().toLowerCase(java.util.Locale.ROOT));
			}
		}

		// ACL-in-Solr: stamp repository-scoped reader tokens onto the content doc
		// so the CMIS query path can filter by the caller's principals in Solr
		// (returning only authorized documents; numFound becomes the authorized
		// count). Applied to all queryable content (documents, folders, items,
		// relationships, policies) but NOT to principal items (users/groups are
		// not returned by cmis:document/folder queries and calculateAcl on them is
		// not meaningful). expandToReaders is itself fail-closed (admin-only when
		// the ACL is null/empty), so a document always carries at least one token.
		if (!(content instanceof jp.aegif.nemaki.model.UserItem)
				&& !(content instanceof jp.aegif.nemaki.model.GroupItem)) {
			jp.aegif.nemaki.rag.acl.ACLExpander aclExpander = getAclExpanderSafely();
			if (aclExpander != null) {
				try {
					List<String> readers = aclExpander.expandToReaders(repositoryId, content);
					if (readers != null) {
						for (String reader : readers) {
							doc.addField("readers", reader);
						}
					}
				} catch (Exception e) {
					// Fail-closed: leave `readers` empty so the query-side fq excludes
					// this doc for non-admin users until it is re-indexed. Never leaks.
					log.warn("Failed to compute readers for content {}: {}", content.getId(), e.getMessage());
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
		deleteDocument(repositoryId, documentId, false);
	}

	/**
	 * Delete a document from Solr
	 * @param forceSync if true, bypass solr.indexing.force setting and execute synchronously
	 */
	public void deleteDocument(String repositoryId, String documentId, boolean forceSync) {
		String _force = propertyManager
				.readValue(PropertyKey.SOLR_INDEXING_FORCE);
		boolean force = (Boolean.TRUE.toString().equals(_force)) ? true : false;

		if (!force && !forceSync)
			return;

		if (forceSync) {
			// Synchronous execution for admin/maintenance operations — propagate failures
			SolrClient solrClient = getSolrClient();
			if (solrClient == null) {
				throw new RuntimeException("Solr client is not available, cannot delete document: " + documentId);
			}
			try {
				UpdateRequest updateRequest = new UpdateRequest();
				updateRequest.deleteById(documentId);
				updateRequest.setCommitWithin(1000);
				UpdateResponse response = updateRequest.process(solrClient);

				if (response.getStatus() == 0) {
					log.debug("Document deleted successfully from Solr: " + documentId + " for repository: " + repositoryId);
					triggerRAGDeletion(repositoryId, documentId);
				} else {
					throw new RuntimeException("Solr deletion failed with status: " + response.getStatus() + " for document: " + documentId);
				}
			} catch (RuntimeException e) {
				throw e;
			} catch (Exception e) {
				throw new RuntimeException("Solr document deletion failed for document: " + documentId + ": " + e.getMessage(), e);
			}
			return;
		}

		CompletableFuture.runAsync(() -> {
			try {
				SolrClient solrClient = getSolrClient();

				UpdateRequest updateRequest = new UpdateRequest();
				updateRequest.deleteById(documentId);
				updateRequest.setCommitWithin(1000);

				UpdateResponse response = updateRequest.process(solrClient);

				if (response.getStatus() == 0) {
					log.debug("Document deleted successfully from Solr: " + documentId + " for repository: " + repositoryId);
					triggerRAGDeletion(repositoryId, documentId);
				} else {
					log.warn("Document deletion failed with status: " + response.getStatus() + " for document: " + documentId);
					throw new RuntimeException("Solr deletion failed with status: " + response.getStatus());
				}

			} catch (SolrServerException | IOException e) {
				log.warn("Solr document deletion failed for document: " + documentId + " in repository: " + repositoryId + ", error: " + e.getMessage());
				throw new RuntimeException("Solr deletion failed: " + e.getMessage(), e);
			}
		}, asyncSolrExecutor).exceptionally(ex -> {
			log.warn("Solr async deletion failed for {}, scheduling retry: {}", documentId, ex.getMessage());
			scheduleRetry(() -> {
				try {
					SolrClient solrClient = getSolrClient();
					UpdateRequest req = new UpdateRequest();
					req.deleteById(documentId);
					req.setCommitWithin(1000);
					req.process(solrClient);
					log.info("Solr deletion retry succeeded for document: {}", documentId);
					triggerRAGDeletion(repositoryId, documentId);
				} catch (Exception retryEx) {
					throw new RuntimeException("Solr deletion retry failed: " + retryEx.getMessage(), retryEx);
				}
			}, documentId, 1);
			return null;
		});
	}

	public String getSolrUrl(){
		String protocol = propertyManager.readValue(PropertyKey.SOLR_PROTOCOL);
		String host = propertyManager.readValue(PropertyKey.SOLR_HOST);
		String portStr = propertyManager.readValue(PropertyKey.SOLR_PORT);
		String context = propertyManager.readValue(PropertyKey.SOLR_CONTEXT);

		// Validate required properties to avoid NPE
		if (protocol == null || protocol.isEmpty()) {
			log.error("SolrUtil.getSolrUrl: solr.protocol is not configured");
			return null;
		}
		if (host == null || host.isEmpty()) {
			log.error("SolrUtil.getSolrUrl: solr.host is not configured");
			return null;
		}
		if (portStr == null || portStr.isEmpty()) {
			log.error("SolrUtil.getSolrUrl: solr.port is not configured");
			return null;
		}

		int port;
		try {
			port = Integer.parseInt(portStr);
		} catch (NumberFormatException e) {
			log.error("SolrUtil.getSolrUrl: solr.port is not a valid integer: " + portStr);
			return null;
		}

		if (context == null || context.isEmpty()) {
			context = "solr";
		}

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
		// Clear cache BEFORE setting new context to ensure old cache is not used
		// Use synchronized to coordinate with getContentServiceSafely()
		synchronized (this) {
			this.contentServiceCache = null;
			this.applicationContext = applicationContext;
		}
	}


	// ── IN_TREE folder hierarchy cache ──────────────────────────────────
	// Caches the parent→children map per repository to avoid full Solr scan
	// on every IN_TREE predicate. TTL-based with explicit invalidation.
	// Uses a generation counter to prevent stale rebuild results from overwriting
	// a concurrent invalidation.

	private static final long DEFAULT_IN_TREE_CACHE_TTL_MS = 10_000L; // 10 seconds

	private static class FolderHierarchyCacheEntry {
		final Map<String, List<String>> childrenMap;
		final long createdAt;
		final long generation;

		FolderHierarchyCacheEntry(Map<String, List<String>> childrenMap, long generation) {
			this.childrenMap = childrenMap;
			this.createdAt = System.currentTimeMillis();
			this.generation = generation;
		}

		boolean isExpired(long ttlMs) {
			return System.currentTimeMillis() - createdAt > ttlMs;
		}
	}

	private final java.util.concurrent.ConcurrentHashMap<String, FolderHierarchyCacheEntry>
		folderHierarchyCache = new java.util.concurrent.ConcurrentHashMap<>();

	// Generation counter per repository — incremented on invalidation.
	// Rebuild checks generation before storing to discard stale results.
	private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>
		folderHierarchyGenerations = new java.util.concurrent.ConcurrentHashMap<>();

	// Guard against concurrent rebuilds for the same repository
	private final java.util.concurrent.ConcurrentHashMap<String, Object>
		folderHierarchyBuildLocks = new java.util.concurrent.ConcurrentHashMap<>();

	private long getGeneration(String repositoryId) {
		java.util.concurrent.atomic.AtomicLong gen =
			folderHierarchyGenerations.computeIfAbsent(repositoryId,
				k -> new java.util.concurrent.atomic.AtomicLong(0));
		return gen.get();
	}

	/**
	 * Returns the parent→children map for folders in the given repository.
	 * Uses a TTL-based cache to avoid full Solr scans on every IN_TREE call.
	 *
	 * @return childrenMap (parentId → list of childIds), or null on Solr error
	 */
	public Map<String, List<String>> getFolderHierarchy(String repositoryId) {
		long ttl = getInTreeCacheTtlMs();

		// Fast-path: lock-free cache check.
		// Read generation BEFORE entry so that a concurrent invalidation
		// (which increments generation, then removes entry) is detected:
		//   - If invalidate runs before genRead: we read new gen, entry may
		//     still be present but entry.generation < gen → MISS (safe).
		//   - If invalidate runs between genRead and entryRead: entry is
		//     removed → null → MISS (safe).
		//   - If invalidate runs after entryRead: stale entry returned once.
		//     The re-check of generation before return minimises this window.
		long currentGen = getGeneration(repositoryId);
		FolderHierarchyCacheEntry entry = folderHierarchyCache.get(repositoryId);
		if (entry != null && !entry.isExpired(ttl) && entry.generation == currentGen) {
			// Re-verify generation to narrow the race window: if an invalidation
			// occurred between the reads above and now, generation will have advanced.
			if (getGeneration(repositoryId) == currentGen) {
				log.debug("IN_TREE cache HIT for repository={}", repositoryId);
				return entry.childrenMap;
			}
			// Generation moved — fall through to synchronized rebuild
		}

		// Serialize rebuild per repository to prevent thundering herd
		Object lock = folderHierarchyBuildLocks.computeIfAbsent(repositoryId, k -> new Object());
		synchronized (lock) {
			// Double-check after acquiring lock
			currentGen = getGeneration(repositoryId);
			entry = folderHierarchyCache.get(repositoryId);
			if (entry != null && !entry.isExpired(ttl) && entry.generation == currentGen) {
				return entry.childrenMap;
			}

			// Build with retry: if generation changes during build, retry up to
			// 3 times. Only cache when afterGen == buildGen (consistent snapshot).
			// If all 3 attempts see generation drift, return the last result
			// WITHOUT caching — the next caller will retry, and by then the
			// invalidation storm will likely have subsided.
			Map<String, List<String>> childrenMap = null;
			for (int attempt = 1; attempt <= 3; attempt++) {
				long buildGen = getGeneration(repositoryId);
				long start = System.currentTimeMillis();
				log.debug("IN_TREE cache MISS for repository={}, rebuilding folder hierarchy (gen={}, attempt={})",
					repositoryId, buildGen, attempt);

				childrenMap = buildFolderHierarchyFromSolr(repositoryId);
				if (childrenMap == null) {
					return null; // Solr error
				}

				long afterGen = getGeneration(repositoryId);
				if (afterGen == buildGen) {
					folderHierarchyCache.put(repositoryId, new FolderHierarchyCacheEntry(childrenMap, buildGen));
					long elapsed = System.currentTimeMillis() - start;
					log.info("IN_TREE cache rebuilt for repository={} in {}ms ({} folders, gen={}, attempt={})",
						repositoryId, elapsed, childrenMap.values().stream().mapToInt(List::size).sum(),
						buildGen, attempt);
					return childrenMap;
				}

				if (attempt < 3) {
					log.info("IN_TREE cache rebuild for repository={} gen changed ({}→{}), retrying (attempt={}/3)",
						repositoryId, buildGen, afterGen, attempt);
				} else {
					// All 3 attempts saw generation changes. Return the build result
					// for this request (best-effort) but do NOT cache it — caching
					// with a mismatched generation would serve stale data to
					// subsequent requests for the full TTL period.
					log.warn("IN_TREE cache rebuild for repository={} saw continuous gen changes ({}→{}), "
						+ "returning uncached best-effort result (attempt=3)", repositoryId, buildGen, afterGen);
				}
			}
			return childrenMap;
		}
	}

	/**
	 * Invalidates the folder hierarchy cache for the given repository.
	 * Called after folder create/move/delete operations.
	 * Increments the generation counter so any in-flight rebuild will discard
	 * its stale result.
	 *
	 * IMPORTANT: generation must be incremented BEFORE cache removal.
	 * This ordering ensures the fast-path in getFolderHierarchy() is safe:
	 * reading entry before generation guarantees that any concurrent invalidation
	 * causes entry.generation < currentGen → cache MISS.
	 */
	public void invalidateFolderHierarchyCache(String repositoryId) {
		java.util.concurrent.atomic.AtomicLong gen =
			folderHierarchyGenerations.computeIfAbsent(repositoryId,
				k -> new java.util.concurrent.atomic.AtomicLong(0));
		long newGen = gen.incrementAndGet();
		folderHierarchyCache.remove(repositoryId);
		log.debug("IN_TREE cache invalidated for repository={} (new gen={})", repositoryId, newGen);
	}

	private long getInTreeCacheTtlMs() {
		try {
			String val = propertyManager.readValue(PropertyKey.SOLR_IN_TREE_CACHE_TTL_MS);
			if (val != null && !val.isEmpty()) {
				return Long.parseLong(val);
			}
		} catch (Exception e) {
			// ignore
		}
		return DEFAULT_IN_TREE_CACHE_TTL_MS;
	}

	/**
	 * Fetches all folders from Solr using cursorMark pagination and builds
	 * a parent→children map.
	 *
	 * @return childrenMap, or null on Solr error
	 */
	private Map<String, List<String>> buildFolderHierarchyFromSolr(String repositoryId) {
		SolrClient solrClient = getSolrClient();
		if (solrClient == null) {
			return null;
		}

		Map<String, String> folderParentMap = new HashMap<>();
		try {
			String cursorMark = org.apache.solr.common.params.CursorMarkParams.CURSOR_MARK_START;
			boolean done = false;
			while (!done) {
				SolrQuery solrQuery = new SolrQuery(
					"basetype:cmis\\:folder AND repository_id:" +
					org.apache.solr.client.solrj.util.ClientUtils.escapeQueryChars(repositoryId));
				solrQuery.setFields("object_id", "parent_id");
				solrQuery.setRows(10000);
				solrQuery.setSort("object_id", SolrQuery.ORDER.asc);
				solrQuery.addSort("id", SolrQuery.ORDER.asc);
				solrQuery.set(org.apache.solr.common.params.CursorMarkParams.CURSOR_MARK_PARAM, cursorMark);

				QueryResponse resp = solrClient.query(solrQuery);
				SolrDocumentList docs = resp.getResults();

				for (SolrDocument doc : docs) {
					String objectId = extractSolrStringFieldStatic(doc, "object_id");
					String parentId = extractSolrStringFieldStatic(doc, "parent_id");
					if (objectId != null) {
						folderParentMap.put(objectId, parentId);
					}
				}

				String nextCursorMark = resp.getNextCursorMark();
				if (cursorMark.equals(nextCursorMark)) {
					done = true;
				}
				cursorMark = nextCursorMark;
			}
		} catch (SolrServerException | IOException e) {
			log.error("Error fetching folder hierarchy for IN_TREE cache: " + e.getMessage(), e);
			return null;
		}

		Map<String, List<String>> childrenMap = new HashMap<>();
		for (Map.Entry<String, String> entry : folderParentMap.entrySet()) {
			if (entry.getValue() != null) {
				childrenMap.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
			}
		}
		return childrenMap;
	}

	/**
	 * Extracts a string field from a SolrDocument, handling multi-valued fields.
	 */
	private static String extractSolrStringFieldStatic(SolrDocument doc, String fieldName) {
		Object val = doc.getFieldValue(fieldName);
		if (val == null) return null;
		if (val instanceof String) return (String) val;
		if (val instanceof java.util.Collection) {
			java.util.Collection<?> col = (java.util.Collection<?>) val;
			if (!col.isEmpty()) {
				Object first = col.iterator().next();
				return first != null ? first.toString() : null;
			}
			return null;
		}
		return val.toString();
	}

	/**
	 * Shutdown hook — closes the shared SolrClient and async executor on
	 * application shutdown. Called by Spring via destroy-method="destroy".
	 */
	public void destroy() {
		// Shutdown async executor to prevent thread leaks on redeploy
		asyncSolrExecutor.shutdown();
		try {
			if (!asyncSolrExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
				log.warn("asyncSolrExecutor did not terminate within timeout, forcing shutdown. "
					+ "Active tasks may be interrupted.");
				asyncSolrExecutor.shutdownNow();
			}
		} catch (InterruptedException e) {
			log.warn("asyncSolrExecutor shutdown interrupted, forcing shutdown");
			asyncSolrExecutor.shutdownNow();
			Thread.currentThread().interrupt();
		}

		SolrClient client = sharedSolrClient;
		if (client != null) {
			sharedSolrClient = null;
			try {
				client.close();
				log.info("Shared SolrClient closed successfully");
			} catch (IOException e) {
				log.warn("Error closing shared SolrClient: " + e.getMessage());
			}
		}
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
	 * Resolve the ACLExpander lazily from the Spring context (it is a RAG
	 * {@code @Component}), mirroring {@link #getContentServiceSafely()} to avoid a
	 * hard construction-time dependency. Returns null if unavailable, in which
	 * case content is indexed without reader tokens — fail-closed at query time
	 * (the query-side readers fq simply will not match, so the document is not
	 * returned to non-admin users until re-indexed; it never leaks).
	 */
	private jp.aegif.nemaki.rag.acl.ACLExpander getAclExpanderSafely() {
		jp.aegif.nemaki.rag.acl.ACLExpander cached = aclExpanderCache;
		if (cached != null) {
			return cached;
		}
		if (applicationContext == null) {
			return null;
		}
		try {
			synchronized (this) {
				cached = aclExpanderCache;
				if (cached != null) {
					return cached;
				}
				cached = applicationContext.getBean(jp.aegif.nemaki.rag.acl.ACLExpander.class);
				aclExpanderCache = cached;
				return cached;
			}
		} catch (Exception e) {
			log.debug("ACLExpander not yet available: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * Get RAGIndexingService lazily from ApplicationContext.
	 * Returns null if RAG is not enabled or service is not available.
	 */
	private RAGIndexingService getRAGIndexingServiceSafely() {
		if (applicationContext == null) {
			return null;
		}
		try {
			RAGIndexingService service = applicationContext.getBean(RAGIndexingService.class);
			// Only return if RAG is enabled
			if (service != null && service.isEnabled()) {
				return service;
			}
			return null;
		} catch (Exception e) {
			// RAG service not available - this is normal when RAG is disabled
			log.debug("RAGIndexingService not available: {}", e.getMessage());
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
	 * Calculate ancestors for IN_TREE queries.
	 *
	 * Note: Currently unused. IN_TREE queries are implemented using parent_id
	 * relationships in SolrPredicateWalker.walkInTreeInternal() instead.
	 *
	 * Future consideration: If performance issues arise with deep folder hierarchies,
	 * consider implementing ancestor indexing using the 'ancestor_path' field type
	 * defined in Solr schema (managed-schema.xml). This would enable more efficient
	 * IN_TREE queries by pre-computing and indexing ancestor paths at index time.
	 *
	 * @param repositoryId the repository ID
	 * @param content the content object
	 * @return list of ancestor IDs (currently returns empty list)
	 */
	@SuppressWarnings("unused")
	private List<String> calculateAncestors(String repositoryId, Content content) {
		// Reserved for future implementation
		return new ArrayList<>();
	}
}
