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
		indexDocument(repositoryId, content, forceSync, skipRAGIndexing, null);
	}

	/**
	 * As {@link #indexDocument(String, Content, boolean, boolean)}, but with an
	 * optional {@code onPermanentFailure} callback run when an ASYNC index write
	 * ultimately fails after exhausting its bounded retries. Used by the
	 * search-index ACL refresh path to record the object in the reconciliation
	 * queue (so a permanently-failed readers write is not lost to a WARN). Null
	 * (the default overload) preserves the existing behaviour exactly.
	 */
	public void indexDocument(String repositoryId, Content content, boolean forceSync, boolean skipRAGIndexing,
			Runnable onPermanentFailure) {
		indexDocument(repositoryId, content, forceSync, skipRAGIndexing, onPermanentFailure, false);
	}

	/**
	 * As above, with {@code strict}: when true (the synchronous reconciliation
	 * re-drive), a transient computation failure of a security/completeness field
	 * ({@code readers} / body / path) THROWS instead of persisting a degraded
	 * document — see {@link #createSolrDocument(String, Content, boolean)}. Only
	 * meaningful with {@code forceSync=true} (the async path swallows + retries).
	 */
	public void indexDocument(String repositoryId, Content content, boolean forceSync, boolean skipRAGIndexing,
			Runnable onPermanentFailure, boolean strict) {
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
			indexDocumentInternal(repositoryId, content, skipRAGIndexing, strict);
		} else {
			// Execute Solr indexing asynchronously to avoid blocking CMIS operations
			CompletableFuture.runAsync(() -> {
				indexDocumentInternal(repositoryId, content, skipRAGIndexing, strict);
			}, asyncSolrExecutor).exceptionally(ex -> {
				log.warn("Solr async indexing failed for {}, scheduling retry: {}", content.getId(), ex.getMessage());
				scheduleRetry(() -> indexDocumentInternal(repositoryId, content, skipRAGIndexing, strict), content.getId(), 1,
						onPermanentFailure);
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
		scheduleRetry(task, docId, attempt, null);
	}

	private void scheduleRetry(Runnable task, String docId, int attempt, Runnable onPermanentFailure) {
		if (attempt > SOLR_INDEX_MAX_RETRY) {
			log.error("Solr indexing permanently failed for document {} after {} retries", docId, SOLR_INDEX_MAX_RETRY);
			if (onPermanentFailure != null) {
				try {
					onPermanentFailure.run();
				} catch (Exception e) {
					log.warn("onPermanentFailure hook failed for {}: {}", docId, e.getMessage());
				}
			}
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
			scheduleRetry(task, docId, attempt + 1, onPermanentFailure);
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
		indexDocumentInternal(repositoryId, content, skipRAGIndexing, false);
	}

	/** Bounded retry for the single-doc index {@code _version_} CAS loop. */
	private static final int INDEX_CAS_MAX_ATTEMPTS = 6;

	private void indexDocumentInternal(String repositoryId, Content content, boolean skipRAGIndexing, boolean strict) {
		SolrClient solrClient = null;
		// Generation-fenced write (single-doc). Every CONTENT write of an object carries
		// content_generation = the CouchDB _rev leading integer (monotonic per object,
		// scoped by content_incarnation). To make ALL content writers converge on the
		// latest state, a single-doc index does an OPTIMISTIC-CONCURRENCY add:
		//   1. realtime-GET the current _version_ + stored generation,
		//   2. SKIP if a strictly-newer generation already landed (a late stale writer,
		//      e.g. a delayed applyAcl-refresh or move add, must not overwrite it),
		//   3. otherwise add with `_version_` CAS (create-if-absent when the doc is
		//      absent), retrying on 409 by re-reading + re-evaluating the generation.
		// This closes the "reconcile succeeds + task deleted, then an old normal writer
		// lands stale readers with no further convergence event" hole. Batch indexing
		// (full reindex) stays a plain add — it clears the index first, so there is no
		// stale generation to fence. A 0/unknown generation is fail-open (plain add) to
		// preserve behaviour for docs without a parsable _rev.
		long myGen = parseRevGeneration(content.getRevision());
		int attempt = 0;
		while (true) {
		try {
			log.info("Starting Solr indexing for document: " + content.getId());
			solrClient = getSolrClient();

			if (solrClient == null) {
				throw new RuntimeException("Solr client is not available, cannot index document: " + content.getId());
			}

			Long versionField = null; // null => plain add (no CAS), fail-open
			SolrDocument cur = null;
			String incarnation = null;
			if (myGen > 0) {
				cur = readVersionAndGeneration(repositoryId, content.getId());

				// CONTENT FENCE (wiring gate 3, design §4.4/§8.1). The incarnation is resolved
				// AUTHORITATIVELY first — ContentIncarnation.resolve persists it by _rev CAS and
				// throws rather than handing back a value CouchDB does not hold, so this writer can
				// never stamp an identity two concurrent writers would disagree about.
				jp.aegif.nemaki.epoch.ContentIncarnation.ContentStore store = contentStore();
				if (store != null) {
					incarnation = jp.aegif.nemaki.epoch.ContentIncarnation.resolve(
							repositoryId, content.getId(), store);
				}
				jp.aegif.nemaki.epoch.ContentWriterFence.Decision decision =
						jp.aegif.nemaki.epoch.ContentWriterFence.decide(cur, incarnation, myGen);
				if (decision == jp.aegif.nemaki.epoch.ContentWriterFence.Decision.FAIL_CLOSED) {
					throw new RuntimeException("Content fence: no authoritative content_incarnation for "
							+ content.getId() + " — refusing to stamp Solr (retry)");
				}
				if (decision == jp.aegif.nemaki.epoch.ContentWriterFence.Decision.SKIP_STALE) {
					log.info("Content fence: skipping write for {} — Solr holds a newer generation in "
							+ "the same incarnation", content.getId());
					return; // clean: a strictly-fresher content write already landed
				}
				if (cur == null) {
					versionField = -1L; // create-if-absent
				} else {
					long version = toLongOrDefault(cur.getFieldValue("_version_"), 0L);
					versionField = (version != 0L) ? Long.valueOf(version) : null;
				}
			}

			SolrInputDocument doc = createSolrDocument(repositoryId, content, strict);
			applyContentFence(doc, cur, incarnation, myGen, repositoryId, content.getId());
			if (versionField != null) {
				doc.addField("_version_", versionField);
			}

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
			return;
		} catch (org.apache.solr.client.solrj.RemoteSolrException e) {
			if (e.code() == 409 && myGen > 0 && attempt < INDEX_CAS_MAX_ATTEMPTS) {
				// Concurrent write changed the doc/version (or a create-if-absent lost the
				// race) — re-read + re-evaluate the generation and retry.
				attempt++;
				log.debug("Index CAS conflict for {} (attempt {}), re-reading", content.getId(), attempt);
				continue;
			}
			log.error("Solr error during indexing for document: " + content.getId() + " in repository: " + repositoryId + ", code=" + e.code() + ", details: " + e.getMessage(), e);
			throw new RuntimeException("Solr indexing failed: " + e.getMessage(), e);
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
		} // end while(true) CAS retry loop (exited only via return on success/skip or throw)
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

		// PWC exclusion (security): a Private Working Copy is a checkout-owner-only
		// draft. PermissionServiceImpl authorizes a PWC by CHECKOUT OWNERSHIP and
		// deliberately ignores the normal inherited ACL, but RAG authorizes by
		// inherited-ACL token intersection (the Solr readers fq + the live
		// isReadableByTokens gate), which does NOT know the PWC rule. An indexed PWC
		// would therefore (a) let a same-group non-owner use the in-progress draft as
		// a findSimilarDocuments seed — an existence + semantic-neighbourhood oracle
		// (the result stage is still filtered by PermissionService, but the seed is
		// only token-gated), and (b) hide the draft from its own owner when the owner
		// is not in the inherited ACL. PWCs are transient drafts, not semantic-search
		// targets, so exclude them from RAG entirely and remove any block a prior
		// build indexed for this id (the CMIS content doc is unaffected — the CMIS
		// query path still has PermissionService.getFiltered enforcing the PWC rule).
		if (Boolean.TRUE.equals(document.isPrivateWorkingCopy())) {
			triggerRAGDeletion(repositoryId, document.getId());
			return;
		}

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
	/**
	 * ACL-in-Solr readers for a relationship: the UNION of the source object's
	 * and target object's readers, reproducing {@code read(source) OR read(target)}
	 * (PermissionServiceImpl.checkRelationshipPermission). A relationship has no
	 * ACL of its own, so this lets the query-side readers fq apply to
	 * relationships like any other content instead of exempting them (which would
	 * inflate numFound with unauthorized relationships). If both source and target
	 * are missing (a dangling relationship) the set is empty — fail-closed, and
	 * the in-memory check returns false for a dangling relationship anyway.
	 *
	 * <p>Staleness is bounded: an endpoint ACL change (applyAcl) and a move both
	 * reverse-look-up the relationships referencing the changed object and
	 * re-index them (AclServiceImpl.updateSearchIndexACLRecursively), so a GRANT
	 * becomes searchable and a REVOKE stops inflating numFound once that async
	 * refresh runs. The in-memory getFiltered re-evaluates
	 * checkRelationshipPermission live, so the RESULT is always correct even
	 * within that brief refresh window.
	 */
	private List<String> relationshipReaders(String repositoryId, Relationship relationship,
			jp.aegif.nemaki.rag.acl.ACLExpander aclExpander, boolean strict) {
		ContentService cs = getContentServiceSafely();
		if (cs == null) {
			if (strict) {
				// Reconciliation re-drive: we cannot compute readers without the
				// ContentService — do not persist an empty (invisible) relationship as
				// success. Fail so the task retries.
				throw new RuntimeException("Strict reindex: ContentService unavailable for relationship "
						+ relationship.getId());
			}
			return unionReaders(null, null);
		}
		List<String> sourceReaders = resolveEndpointReaders(repositoryId, relationship.getSourceId(),
				cs, aclExpander, strict, relationship.getId(), "source");
		List<String> targetReaders = resolveEndpointReaders(repositoryId, relationship.getTargetId(),
				cs, aclExpander, strict, relationship.getId(), "target");
		return unionReaders(sourceReaders, targetReaders);
	}

	/**
	 * Resolve one relationship endpoint's reader tokens.
	 *
	 * <p>A null endpoint id contributes nothing. Otherwise the endpoint is read via
	 * {@code getContent} — but the DAO layers collapse BOTH a genuine 404 and a
	 * transient read error to {@code null}, so on a null read the behaviour depends on
	 * {@code strict}:
	 * <ul>
	 *   <li>{@code strict == false} (normal indexing): treat null as "no contribution"
	 *       (the historical best-effort behaviour).</li>
	 *   <li>{@code strict == true} (reconciliation re-drive): probe the store
	 *       authoritatively (tri-state). NOT_FOUND ⇒ a genuinely dangling endpoint,
	 *       contribute nothing; ERROR / still-present-but-unreadable ⇒ a TRANSIENT
	 *       failure, THROW so the task retries rather than persisting a one-sided (or
	 *       empty) readers set that under-exposes the relationship.</li>
	 * </ul>
	 * Per-endpoint (not per-relationship-whole) so one dangling far endpoint does not
	 * block the reconciliation of the near object's other relationships.
	 */
	private List<String> resolveEndpointReaders(String repositoryId, String endpointId,
			ContentService cs, jp.aegif.nemaki.rag.acl.ACLExpander aclExpander, boolean strict,
			String relationshipId, String side) {
		if (endpointId == null) {
			return null;
		}
		Content endpoint = cs.getContent(repositoryId, endpointId);
		if (endpoint != null) {
			return aclExpander.expandToReaders(repositoryId, endpoint);
		}
		if (!strict) {
			return null;
		}
		DocState state = probeEndpointExists(repositoryId, endpointId);
		if (state == DocState.NOT_FOUND) {
			return null; // genuinely dangling endpoint — the union with the other side stands
		}
		throw new RuntimeException("Strict reindex: relationship " + relationshipId + " " + side
				+ " endpoint " + endpointId + " unreadable (" + state + ") — retrying");
	}

	/**
	 * Pure union of a relationship's source and target reader tokens (dedup,
	 * source order first). A null side contributes nothing; two null sides (a
	 * dangling relationship, or ContentService unavailable) yield an EMPTY list,
	 * which is fail-closed — the query-side readers fq then excludes the
	 * relationship for every non-admin caller. Extracted + package-private so the
	 * union / fail-closed contract is unit-testable without Spring.
	 */
	static List<String> unionReaders(List<String> sourceReaders, List<String> targetReaders) {
		return jp.aegif.nemaki.acl.AclSemantics.relationshipReaders(sourceReaders, targetReaders);
	}

	/** Tri-state document existence used by strict relationship-endpoint reads. */
	private enum DocState { FOUND, NOT_FOUND, ERROR }

	/**
	 * Authoritative tri-state existence probe against CouchDB (distinguishing a
	 * genuine 404/tombstone from a transient read error, which the DAO layers both
	 * collapse to {@code null}). Mirrors {@code AclServiceImpl.probeContentExists}.
	 */
	private DocState probeEndpointExists(String repositoryId, String objectId) {
		jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool pool = getConnectorPoolSafely();
		if (pool == null) {
			return DocState.ERROR;
		}
		try {
			jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper client = pool.getClient(repositoryId);
			if (client == null) {
				return DocState.ERROR;
			}
			com.ibm.cloud.cloudant.v1.model.Document doc = client.getClient().getDocument(
					new com.ibm.cloud.cloudant.v1.model.GetDocumentOptions.Builder()
							.db(client.getDatabaseName()).docId(objectId).build())
					.execute().getResult();
			if (doc == null) {
				return DocState.NOT_FOUND;
			}
			if (doc.getProperties() != null && Boolean.TRUE.equals(doc.getProperties().get("_deleted"))) {
				return DocState.NOT_FOUND;
			}
			return DocState.FOUND;
		} catch (com.ibm.cloud.sdk.core.service.exception.NotFoundException e) {
			return DocState.NOT_FOUND;
		} catch (Exception e) {
			log.warn("Strict reindex: existence probe errored for {}: {}", objectId, e.getMessage());
			return DocState.ERROR;
		}
	}

	private volatile jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool connectorPoolCache;

	/**
	 * CouchDB access for {@link jp.aegif.nemaki.epoch.ContentIncarnation#resolve} (wiring gate 3).
	 * Straight to CouchDB, never through the content cache: the incarnation is an IDENTITY that must
	 * be established authoritatively, and a cached read could hand back a value a competing writer
	 * has already superseded.
	 */
	private jp.aegif.nemaki.epoch.ContentIncarnation.ContentStore contentStore() {
		jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool pool = getConnectorPoolSafely();
		if (pool == null) {
			return null;
		}
		return new jp.aegif.nemaki.epoch.ContentIncarnation.ContentStore() {
			@Override public com.ibm.cloud.cloudant.v1.model.Document read(String repositoryId, String docId) {
				var client = pool.getClient(repositoryId);
				if (client == null) {
					throw new jp.aegif.nemaki.epoch.ContentIncarnation.IncarnationUnavailableException(
							"no content DB client for repository '" + repositoryId + "'");
				}
				try {
					return client.getClient().getDocument(
							new com.ibm.cloud.cloudant.v1.model.GetDocumentOptions.Builder()
									.db(client.getDatabaseName()).docId(docId).build()).execute().getResult();
				} catch (com.ibm.cloud.sdk.core.service.exception.NotFoundException e) {
					return null; // genuinely deleted — the caller decides, never a guess
				}
			}
			@Override public String write(String repositoryId, com.ibm.cloud.cloudant.v1.model.Document doc) {
				var client = pool.getClient(repositoryId);
				try {
					var r = client.getClient().putDocument(
							new com.ibm.cloud.cloudant.v1.model.PutDocumentOptions.Builder()
									.db(client.getDatabaseName()).docId(doc.getId()).document(doc).build())
							.execute().getResult();
					return (r != null && r.isOk()) ? r.getRev() : null;
				} catch (com.ibm.cloud.sdk.core.service.exception.ConflictException e) {
					return null; // 409 — the caller re-reads and adopts the winner's value
				}
			}
		};
	}

	/** Lazily resolve the CouchDB connector pool from Spring (mirrors {@link #getContentServiceSafely()}). */
	private jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool getConnectorPoolSafely() {
		jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool cached = connectorPoolCache;
		if (cached != null) {
			return cached;
		}
		if (applicationContext == null) {
			return null;
		}
		try {
			synchronized (this) {
				cached = connectorPoolCache;
				if (cached != null) {
					return cached;
				}
				cached = applicationContext.getBean("connectorPool",
						jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool.class);
				connectorPoolCache = cached;
				return cached;
			}
		} catch (Exception e) {
			log.debug("connectorPool not yet available: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * Parse the monotonic generation from a CouchDB {@code _rev} ("N-hash"): the
	 * leading integer N, which increments on every document write (including every
	 * applyAcl / move / update). Returns 0 when the revision is null or unparseable,
	 * which disables the generation fence for that write (never skips).
	 */
	public static long parseRevGeneration(String revision) {
		if (revision == null) {
			return 0L;
		}
		int dash = revision.indexOf('-');
		String head = (dash > 0) ? revision.substring(0, dash) : revision;
		try {
			long gen = Long.parseLong(head.trim());
			return gen > 0 ? gen : 0L;
		} catch (NumberFormatException e) {
			return 0L;
		}
	}


	/** Bounded retry for the {@code _version_} optimistic-concurrency loop. */
	private static final int READERS_CAS_MAX_ATTEMPTS = 6;

	/**
	 * Read the current {@code _version_} + {@code content_generation} for an object, or
	 * {@code null} if it is not indexed. Uses Solr REALTIME GET ({@code /get}) — NOT a
	 * searcher query — so it returns the latest (possibly uncommitted) {@code _version_}.
	 * A searcher query would lag behind by the soft-commit window (commitWithin=1s), so a
	 * {@code _version_} CAS would keep reading a stale version and loop to exhaustion whenever
	 * the doc was written in the last second. The nemaki core has {@code <updateLog>} + a
	 * {@code _version_} field, so realtime get and optimistic concurrency are both available.
	 *
	 * <p>The name is historical: since increment 14 it reads the {@code _version_} and the
	 * CONTENT generation ({@code content_generation}); the ACL generation it was named for no
	 * longer exists.
	 */
	/**
	 * Apply the content fence to a rebuilt document (wiring gate 3).
	 *
	 * <p>Stamps this axis' own fields ({@code content_incarnation} + {@code content_generation}) and
	 * then hands the ACL group back to whatever Solr already holds. The content writer's own
	 * expansion is DISCARDED: the ACL axis has its own writer, fence and CAS, and a body
	 * re-extraction finishing after a fresh applyAcl must not be a second opinion on it.
	 *
	 * <p>BOTH ACL-group fields move together ({@code readers} + {@code effective_acl_epoch}):
	 * restoring the readers while leaving a mismatched epoch beside them would hand the ACL fence a
	 * value that does not describe what it sits next to.
	 */
	private void applyContentFence(SolrInputDocument doc, SolrDocument stored, String incarnation,
			long myGen, String repositoryId, String objectId) {
		if (incarnation != null) {
			doc.setField(jp.aegif.nemaki.epoch.ContentIncarnation.SOLR_FIELD, incarnation);
			doc.setField(jp.aegif.nemaki.epoch.ContentIncarnation.SOLR_GENERATION_FIELD, myGen);
		}
		java.util.Map<String, Object> rebuilt = new java.util.LinkedHashMap<>();
		jp.aegif.nemaki.epoch.ContentWriterFence.AclGroupOutcome outcome =
				jp.aegif.nemaki.epoch.ContentWriterFence.preserveAclGroup(stored, rebuilt);
		switch (outcome) {
			case PRESERVED:
				// Replace, never accumulate: createSolrDocument used addField for readers, so
				// setField here is what actually drops its value.
				doc.removeField("readers");
				for (java.util.Map.Entry<String, Object> e : rebuilt.entrySet()) {
					doc.setField(e.getKey(), e.getValue());
				}
				break;
			case MISSING_ON_EXISTING:
				// An existing document with no ACL group: this writer's expansion is not an
				// authoritative answer for it, so it is dropped and the object is handed to
				// reconciliation (design §4.4) rather than stamped as if settled.
				doc.removeField("readers");
				log.warn("Content fence: {} is indexed without an ACL group — enqueueing for "
						+ "reconciliation rather than stamping this writer's expansion", objectId);
				enqueueReadersReconcile(repositoryId, objectId, null);
				break;
			case BOOTSTRAP_NOT_INDEXED:
			default:
				break; // first index: this writer's own values stand
		}
	}

	private SolrDocument readVersionAndGeneration(String repositoryId, String objectId) throws Exception {
		SolrClient solrClient = getSolrClient();
		if (solrClient == null) {
			throw new RuntimeException("Solr client unavailable");
		}
		SolrDocument doc = solrClient.getById(objectId);
		if (doc == null) {
			return null;
		}
		// Ids are globally-unique CMIS ids, but the core is shared across repositories.
		// A cross-repo id collision is a HARD FAILURE — returning null here would make the
		// caller treat the id as absent and create-if-absent / overwrite ANOTHER repo's
		// document under the same unique key. Fail closed so the write is aborted + retried.
		Object repo = doc.getFieldValue("repository_id");
		if (repo != null && !repositoryId.equals(repo.toString())) {
			throw new RuntimeException("Solr id collision: object " + objectId + " belongs to repository '"
					+ repo + "', not '" + repositoryId + "' — refusing to overwrite");
		}
		return doc;
	}

	private static long toLongOrDefault(Object v, long dflt) {
		if (v instanceof Number) {
			return ((Number) v).longValue();
		}
		if (v != null) {
			try {
				return Long.parseLong(v.toString());
			} catch (NumberFormatException ignore) {
				return dflt;
			}
		}
		return dflt;
	}

	private SolrInputDocument createSolrDocument(String repositoryId, Content content) {
		return createSolrDocument(repositoryId, content, false);
	}

	/**
	 * Build the Solr document for a content node.
	 *
	 * <p>{@code strict} controls how a transient computation failure of a
	 * SECURITY-relevant or completeness-relevant field (the {@code readers} tokens,
	 * the extracted body text, the calculated path) is handled:
	 * <ul>
	 *   <li>{@code strict == false} (normal indexing): the failure is logged and the
	 *       field is left empty — a best-effort content save must not fail wholesale
	 *       on a transient Tika/ACL hiccup.</li>
	 *   <li>{@code strict == true} (the reconciliation re-drive): the failure is
	 *       RE-THROWN so the caller counts it and retries, instead of persisting a
	 *       degraded document (empty readers → invisible; missing body/path →
	 *       clobbers the good copy) and then deleting the reconciliation task as if it
	 *       were clean. See SearchIndexReconciliationScheduler.</li>
	 * </ul>
	 */
	/**
	 * The ONE decision taken when readers cannot be computed. Extracted so it can be bound by a
	 * test: it is the whole difference between the two paths.
	 *
	 * <ul>
	 *   <li><b>strict</b> (reconciliation re-drive) — THROW. A swallowed failure would persist an
	 *       EMPTY readers set (the doc becomes invisible to every non-admin) and then let the
	 *       poller delete the task as if it had reconciled. Failing keeps the task counted and
	 *       retried.</li>
	 *   <li><b>ordinary</b> — return, so the caller leaves {@code readers} empty and the query-side
	 *       fq excludes the doc for non-admin users (fail-closed, never a leak), AND enqueue it for
	 *       reconciliation. Before increment 5T this was a bare {@code log.warn}: because this runs
	 *       inside {@code createSolrDocument}, execution continued and the document was indexed
	 *       with an empty readers set as a SUCCESS, on the most frequent path there is (every
	 *       create and update), with no durable retry — so the stale-deny survived until the next
	 *       ACL change or a full reindex.</li>
	 * </ul>
	 */
	void onReadersComputationFailed(String repositoryId, String objectId, Exception cause, boolean strict) {
		if (strict) {
			throw new RuntimeException("Strict reindex: readers computation failed for "
					+ objectId + ": " + (cause == null ? "unknown" : cause.getMessage()), cause);
		}
		log.warn("Failed to compute readers for content {}: {} — enqueueing for reconciliation",
				objectId, cause == null ? "unknown" : cause.getMessage());
		enqueueReadersReconcile(repositoryId, objectId, cause);
	}

	/**
	 * Increment 5T. Records a readers-computation failure on the ORDINARY (non-strict) index path so
	 * the fail-closed empty {@code readers} is retried durably instead of persisting silently.
	 *
	 * <p>Best-effort by construction: this runs inside the ordinary index write, which must not be
	 * failed by a queue problem. {@code enqueue} never throws, and its own failures are already
	 * surfaced through the reconciliation {@code enqueueFailureCount} metric. The strict path does
	 * not come here at all — it rethrows so the task is counted and retried.
	 */
	private void enqueueReadersReconcile(String repositoryId, String objectId, Exception cause) {
		try {
			jp.aegif.nemaki.reconcile.SearchIndexReconciliationService queue = reconciliationQueue();
			if (queue == null) {
				log.warn("Readers-computation failure for {} could NOT be enqueued: reconciliation "
						+ "queue is not wired; the empty readers set will persist until the next ACL "
						+ "change or a full reindex", objectId);
				return;
			}
			queue.enqueue(repositoryId, objectId,
					"READERS_COMPUTATION_FAILURE: " + (cause == null ? "unknown" : cause.getMessage()));
		} catch (Exception e) {
			log.warn("Failed to enqueue readers-computation failure for {}: {}", objectId, e.getMessage());
		}
	}

	/** Lazily resolved, like {@link #getACLExpander}, to avoid a startup dependency cycle. */
	private jp.aegif.nemaki.reconcile.SearchIndexReconciliationService reconciliationQueue() {
		if (applicationContext == null) {
			return null;
		}
		try {
			return applicationContext.getBean(jp.aegif.nemaki.reconcile.SearchIndexReconciliationService.class);
		} catch (Exception e) {
			return null;
		}
	}

	private SolrInputDocument createSolrDocument(String repositoryId, Content content, boolean strict) {
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
				if (strict) {
					// Reconciliation re-drive: a full-document write with a transiently
					// unresolvable path would clobber the good indexed path. Fail so the
					// task retries instead of persisting a path-less doc as "reconciled".
					throw new RuntimeException("Strict reindex: path calculation failed for "
							+ content.getId() + ": " + e.getMessage(), e);
				}
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
					if (strict) {
						// Reconciliation re-drive: a full-document write with a
						// transiently-failed extraction would replace the good indexed
						// body with an empty one. Fail so the task retries instead of
						// clobbering + deleting the task as "reconciled".
						throw new RuntimeException("Strict reindex: text extraction failed for "
								+ content.getId() + ": " + e.getMessage(), e);
					}
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
		// count). Applied to ALL queryable content: documents, folders, items,
		// principal items (user/group items live under /.system with a normal
		// inherited ACL — default GROUP_EVERYONE:read — and were readable through
		// the in-memory filter, so they must carry readers too), AND relationships.
		// A relationship stores no ACL of its own; its read permission is
		// read(source) OR read(target) (PermissionServiceImpl.checkRelationshipPermission),
		// so it is stamped with the UNION of its source's and target's readers —
		// not exempted from the fq (which would let unauthorized relationships
		// inflate numFound and trip the ACL scan cap). expandToReaders is itself
		// fail-closed (admin-only when the ACL is null/empty), so a stamped doc
		// always carries at least one token.
		jp.aegif.nemaki.rag.acl.ACLExpander aclExpander = getAclExpanderSafely();
		if (aclExpander != null) {
			try {
				List<String> readers = (content instanceof Relationship)
						? relationshipReaders(repositoryId, (Relationship) content, aclExpander, strict)
						: aclExpander.expandToReaders(repositoryId, content);
				if (readers != null) {
					for (String reader : readers) {
						doc.addField("readers", reader);
					}
				}
			} catch (Exception e) {
				onReadersComputationFailed(repositoryId, content.getId(), e, strict);
			}
		}

		// ACL-in-Solr generation fence (#1): stamp the object's CouchDB revision
		// generation (the leading integer of `_rev`, which bumps on every applyAcl /
		// move / update — unlike the CMIS change token, which an ACL change does NOT
		// move). The reconciliation re-drive reads this back before writing and skips
		// its write when Solr already holds a STRICTLY-NEWER generation, so a slow
		// stale re-drive cannot overwrite a concurrent applyAcl's fresher readers.

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
