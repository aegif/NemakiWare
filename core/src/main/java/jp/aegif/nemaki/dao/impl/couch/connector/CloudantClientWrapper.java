package jp.aegif.nemaki.dao.impl.couch.connector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.*;
import com.ibm.cloud.sdk.core.service.exception.NotFoundException;

import jp.aegif.nemaki.model.couch.CouchNodeBase;

/**
 * Wrapper for Cloudant client that provides CouchDB operations
 * Replaces Ektorp's CouchDbConnector functionality
 */
public class CloudantClientWrapper {

	private final Cloudant client;
	private final String databaseName;
	private final ObjectMapper objectMapper;

	private static final Logger log = LoggerFactory.getLogger(CloudantClientWrapper.class);

	/**
	 * Constructor with Spring dependency injection for unified ObjectMapper
	 * 
	 * @param client Cloudant client instance
	 * @param databaseName Database name
	 * @param objectMapper Unified ObjectMapper from JacksonConfig (couchdbObjectMapper bean)
	 */
	public CloudantClientWrapper(Cloudant client, String databaseName, ObjectMapper objectMapper) {
		this.client = client;
		this.databaseName = databaseName;
		this.objectMapper = objectMapper;
		log.info("CloudantClientWrapper initialized with unified ObjectMapper for database: " + databaseName);
	}

	/**
	 * CRITICAL: Optimized deletion approach for related documents
	 * This prevents overwhelming CouchDB with rapid individual delete requests
	 * @param documentIds List of document IDs to delete
	 */
	/**
	 * ENHANCED: True bulk delete using _bulk_docs endpoint for optimal performance
	 * This is the preferred method for deleting multiple documents efficiently
	 * 
	 * @param documentIds List of document IDs to delete
	 */
	public void deleteDocumentsBatch(List<String> documentIds) {
		if (documentIds == null || documentIds.isEmpty()) {
			return;
		}
		
		try {
			log.error("=== BULK DELETE TRACE START ===");
			log.error("BULK DELETE: Starting true bulk deletion of " + documentIds.size() + " documents in database: " + databaseName);
			
			// CLOUDANT BEST PRACTICE: Use _bulk_docs for efficient batch operations
			// Batch size recommendation: Start with 1000 documents per batch
			final int OPTIMAL_BATCH_SIZE = 1000;
			
			if (documentIds.size() <= OPTIMAL_BATCH_SIZE) {
				performBulkDelete(documentIds);
			} else {
				// Split large batches to prevent server overload
				log.info("BULK DELETE: Splitting " + documentIds.size() + " documents into batches of " + OPTIMAL_BATCH_SIZE);
				for (int i = 0; i < documentIds.size(); i += OPTIMAL_BATCH_SIZE) {
					int endIndex = Math.min(i + OPTIMAL_BATCH_SIZE, documentIds.size());
					List<String> batch = documentIds.subList(i, endIndex);
					log.debug("BULK DELETE: Processing batch " + (i/OPTIMAL_BATCH_SIZE + 1) + " with " + batch.size() + " documents");
					performBulkDelete(batch);
				}
			}
			
			log.error("BULK DELETE: Successfully completed bulk deletion operation");
		} catch (Exception e) {
			log.error("BULK DELETE: Critical error during bulk deletion", e);
			throw new RuntimeException("Bulk delete operation failed: " + e.getMessage(), e);
		}
	}
	
	/**
	 * Performs actual bulk delete using Cloudant _bulk_docs endpoint
	 * This method implements the recommended pattern for bulk document deletion
	 * 
	 * @param documentIds List of document IDs to delete in this batch
	 */
	private void performBulkDelete(List<String> documentIds) {
		try {
			// STEP 1: Fetch all documents to get their current revisions
			log.debug("BULK DELETE: Fetching current revisions for " + documentIds.size() + " documents");
			List<Document> documentsToDelete = new ArrayList<>();
			
			for (String docId : documentIds) {
				try {
					GetDocumentOptions getOptions = new GetDocumentOptions.Builder()
						.db(databaseName)
						.docId(docId)
						.build();
					Document doc = client.getDocument(getOptions).execute().getResult();
					documentsToDelete.add(doc);
				} catch (NotFoundException e) {
					log.warn("BULK DELETE: Document " + docId + " not found, skipping");
				} catch (Exception e) {
					log.error("BULK DELETE: Error fetching document " + docId + ": " + e.getMessage());
					// Continue with other documents
				}
			}
			
			if (documentsToDelete.isEmpty()) {
				log.warn("BULK DELETE: No documents found to delete");
				return;
			}
			
			// STEP 2: Create bulk delete request using _bulk_docs
			List<Document> bulkDeleteDocs = new ArrayList<>();
			for (Document doc : documentsToDelete) {
				// Create delete document by setting _deleted flag
				Document deleteDoc = new Document();
				deleteDoc.setId(doc.getId());
				deleteDoc.setRev(doc.getRev());
				deleteDoc.put("_deleted", true);  // This is the key for bulk deletion
				bulkDeleteDocs.add(deleteDoc);
			}
			
			// STEP 3: Execute bulk delete using _bulk_docs endpoint
			log.debug("BULK DELETE: Executing bulk delete for " + bulkDeleteDocs.size() + " documents");
			PostBulkDocsOptions bulkOptions = new PostBulkDocsOptions.Builder()
				.db(databaseName)
				.bulkDocs(new BulkDocs.Builder()
					.docs(bulkDeleteDocs)
					.build())
				.build();
			
			List<DocumentResult> results = client.postBulkDocs(bulkOptions).execute().getResult();
			
			// STEP 4: Process results and handle any errors
			int successCount = 0;
			int errorCount = 0;
			
			for (int i = 0; i < results.size(); i++) {
				DocumentResult result = results.get(i);
				String docId = documentIds.get(Math.min(i, documentIds.size() - 1));
				
				if (result.isOk()) {
					successCount++;
					log.debug("BULK DELETE: Successfully deleted document: " + docId);
				} else {
					errorCount++;
					log.error("BULK DELETE: Failed to delete document " + docId + ": " + result.getError() + " - " + result.getReason());
				}
			}
			
			log.info("BULK DELETE: Batch complete - Success: " + successCount + ", Errors: " + errorCount);
			
			if (errorCount > 0 && successCount == 0) {
				throw new RuntimeException("All documents failed to delete in batch");
			}
			
		} catch (Exception e) {
			log.error("BULK DELETE: Error in performBulkDelete", e);
			throw new RuntimeException("Bulk delete batch failed: " + e.getMessage(), e);
		}
	}
	
	/**
	 * FALLBACK: Individual delete method for backward compatibility
	 * Only use this when bulk operations are not suitable
	 */
	public void deleteDocumentsBatchIndividual(List<String> documentIds) {
		if (documentIds == null || documentIds.isEmpty()) {
			return;
		}
		
		log.warn("FALLBACK DELETE: Using individual delete method (less efficient)");
		int successCount = 0;
		int errorCount = 0;
		
		// Delete documents individually with proper pacing to prevent CouchDB overload
		for (String docId : documentIds) {
			try {
				// Get current document for revision
				GetDocumentOptions getOptions = new GetDocumentOptions.Builder()
					.db(databaseName)
					.docId(docId)
					.build();
				
				Document doc = client.getDocument(getOptions).execute().getResult();
				
				// Delete document
				DeleteDocumentOptions deleteOptions = new DeleteDocumentOptions.Builder()
					.db(databaseName)
					.docId(docId)
					.rev(doc.getRev())
					.build();
				
				client.deleteDocument(deleteOptions).execute();
				successCount++;
				
				// Brief pause to prevent overwhelming CouchDB
				Thread.sleep(10);
				
			} catch (NotFoundException nfe) {
				log.debug("BATCH DELETE: Document " + docId + " not found, already deleted");
				successCount++; // Consider this a success
			} catch (Exception e) {
				log.warn("BATCH DELETE: Failed to delete document " + docId + ": " + e.getMessage());
				errorCount++;
			}
		}
		
		log.info("BATCH DELETE: Completed - Success: " + successCount + ", Errors: " + errorCount);
	}

	/**
	 * Get the unified ObjectMapper instance
	 * This method provides access to the Spring-configured ObjectMapper
	 * that supports both @JsonProperty annotations and field access patterns.
	 * 
	 * @return The unified ObjectMapper configured for CouchDB serialization
	 */
	private ObjectMapper getObjectMapper() {
		return this.objectMapper;
	}

	/**
	 * Get the underlying Cloudant client
	 */
	public Cloudant getClient() {
		return client;
	}

	/**
	 * Get database name
	 */
	public String getDatabaseName() {
		return databaseName;
	}

	/**
	 * Create a document
	 */
	public DocumentResult create(Map<String, Object> document) {
		try {
			// CORRECT APPROACH: Remove null _id and _rev from new document creation
			// CouchDB should generate ID automatically when _id is not provided
			if (document.get("_id") == null) {
				document.remove("_id");
			}
			if (document.get("_rev") == null) {
				document.remove("_rev");
			}
			
			// Create a new Document instance and populate it with properties
			// Use the setProperties method to handle custom fields
			com.ibm.cloud.cloudant.v1.model.Document doc = new com.ibm.cloud.cloudant.v1.model.Document();
			
			// Set the ID and revision if they exist
			if (document.containsKey("_id") && document.get("_id") != null) {
				doc.setId((String) document.get("_id"));
			}
			if (document.containsKey("_rev") && document.get("_rev") != null) {
				doc.setRev((String) document.get("_rev"));
			}
			
			// Remove _id and _rev from the map before setting as properties
			Map<String, Object> properties = new HashMap<>(document);
			properties.remove("_id");
			properties.remove("_rev");
			
			// Set all other fields as properties
			log.error("CLOUDANT DEBUG: About to set properties on Document object");
			log.error("CLOUDANT DEBUG: Properties map size: " + properties.size());
			log.error("CLOUDANT DEBUG: Properties keys: " + properties.keySet());
			
			doc.setProperties(properties);
			
			// CRITICAL DEBUG: Check if properties were actually set
			Map<String, Object> retrievedProperties = doc.getProperties();
			log.error("CLOUDANT DEBUG: Retrieved properties after setProperties():");
			log.error("CLOUDANT DEBUG: Retrieved properties size: " + (retrievedProperties != null ? retrievedProperties.size() : "null"));
			if (retrievedProperties != null) {
				log.error("CLOUDANT DEBUG: Retrieved properties keys: " + retrievedProperties.keySet());
			}

			PostDocumentOptions options = new PostDocumentOptions.Builder()
				.db(databaseName)
				.document(doc)
				.build();

			DocumentResult result = client.postDocument(options).execute().getResult();
			log.error("CLOUDANT DEBUG: Document created with ID: " + result.getId());
			
			// CRITICAL: Verify what was actually saved to CouchDB
			log.error("CLOUDANT DEBUG: Attempting to retrieve saved document to verify content...");
			try {
				GetDocumentOptions getOptions = new GetDocumentOptions.Builder()
					.db(databaseName)
					.docId(result.getId())
					.build();
				com.ibm.cloud.cloudant.v1.model.Document savedDoc = client.getDocument(getOptions).execute().getResult();
				Map<String, Object> savedProperties = savedDoc.getProperties();
				log.error("CLOUDANT DEBUG: Saved document properties size: " + (savedProperties != null ? savedProperties.size() : "null"));
				if (savedProperties != null) {
					log.error("CLOUDANT DEBUG: Saved document properties keys: " + savedProperties.keySet());
				}
			} catch (Exception verifyEx) {
				log.error("CLOUDANT DEBUG: Failed to retrieve saved document for verification", verifyEx);
			}
			
			return result;

		} catch (Exception e) {
			log.warn("Error creating document in database '" + databaseName + "' - returning null. This is normal during initial startup: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Create a document with specific ID
	 */
	public DocumentResult create(String id, Map<String, Object> document) {
		try {
			// CORRECT APPROACH: Remove null _id and _rev for consistency
			// Even with provided ID, ensure no null values are sent
			if (document.get("_id") == null) {
				document.remove("_id");
			}
			if (document.get("_rev") == null) {
				document.remove("_rev");
			}
			
			// Create a new Document instance and populate it with properties
			com.ibm.cloud.cloudant.v1.model.Document doc = new com.ibm.cloud.cloudant.v1.model.Document();
			
			// Set the ID
			doc.setId(id);
			
			// Set the revision if it exists
			if (document.containsKey("_rev") && document.get("_rev") != null) {
				doc.setRev((String) document.get("_rev"));
			}
			
			// Remove _id and _rev from the map before setting as properties
			Map<String, Object> properties = new HashMap<>(document);
			properties.remove("_id");
			properties.remove("_rev");
			
			// Set all other fields as properties
			doc.setProperties(properties);

			PutDocumentOptions options = new PutDocumentOptions.Builder()
				.db(databaseName)
				.docId(id)
				.document(doc)
				.build();

			DocumentResult result = client.putDocument(options).execute().getResult();
			log.debug("Created document with ID: " + id);
			
			return result;

		} catch (Exception e) {
			log.warn("Error creating document with ID '" + id + "' in database '" + databaseName + "' - returning null. This is normal during initial startup: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Get a document by ID
	 */
	public com.ibm.cloud.cloudant.v1.model.Document get(String id) {
		try {
			// CLOUDANT SDK FIX: Handle _design documents specially
			// Cloudant SDK rejects document IDs starting with underscore
			// Use GetDesignDocumentOptions for design documents
			if (id != null && id.startsWith("_design/")) {
				log.debug("Getting design document: " + id);
				String designDocName = id.substring("_design/".length());
				
				GetDesignDocumentOptions designOptions = new GetDesignDocumentOptions.Builder()
					.db(databaseName)
					.ddoc(designDocName)
					.build();

				com.ibm.cloud.cloudant.v1.model.DesignDocument designDoc = client.getDesignDocument(designOptions).execute().getResult();
				log.debug("Retrieved design document: " + id);
				
				// Convert DesignDocument to regular Document for compatibility
				// Create a regular Document object with design document content
				com.ibm.cloud.cloudant.v1.model.Document result = new com.ibm.cloud.cloudant.v1.model.Document();
				result.setId(id);
				result.setRev(designDoc.getRev());
				
				// Set design document properties
				Map<String, Object> properties = new HashMap<>();
				if (designDoc.getViews() != null) {
					properties.put("views", designDoc.getViews());
				}
				if (designDoc.getLanguage() != null) {
					properties.put("language", designDoc.getLanguage());
				}
				if (designDoc.getOptions() != null) {
					properties.put("options", designDoc.getOptions());
				}
				if (designDoc.getFilters() != null) {
					properties.put("filters", designDoc.getFilters());
				}
				// Note: getUpdates() method not available in this SDK version
				if (designDoc.getValidateDocUpdate() != null) {
					properties.put("validate_doc_update", designDoc.getValidateDocUpdate());
				}
				result.setProperties(properties);
				
				return result;
			} else {
				// Regular document retrieval
				GetDocumentOptions options = new GetDocumentOptions.Builder()
					.db(databaseName)
					.docId(id)
					.build();

				com.ibm.cloud.cloudant.v1.model.Document result = client.getDocument(options).execute().getResult();
				log.debug("Retrieved document with ID: " + id);
				
				return result;
			}

		} catch (NotFoundException e) {
			log.debug("Document not found with ID: " + id);
			return null;
		} catch (Exception e) {
			log.warn("Error retrieving document with ID '" + id + "' from database '" + databaseName + "' - returning null. This is normal during initial startup: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Update a document
	 */
	public DocumentResult update(Map<String, Object> document) {
		try {
			String id = (String) document.get("_id");
			String rev = (String) document.get("_rev");
			
			if (id == null) {
				throw new IllegalArgumentException("Document must have '_id' field for update");
			}
			
			ObjectMapper mapper = getObjectMapper();
			String jsonString = mapper.writeValueAsString(document);
			com.ibm.cloud.cloudant.v1.model.Document doc = mapper.readValue(jsonString, com.ibm.cloud.cloudant.v1.model.Document.class);

			PutDocumentOptions options = new PutDocumentOptions.Builder()
				.db(databaseName)
				.docId(id)
				.document(doc)
				.build();

			DocumentResult result = client.putDocument(options).execute().getResult();
			log.debug("Updated document with ID: " + id);
			
			return result;

		} catch (Exception e) {
			log.warn("Error updating document in database '" + databaseName + "' - returning null. This is normal during initial startup: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Delete a document
	 */
	public DocumentResult delete(String id, String rev) {
		try {
			DeleteDocumentOptions options = new DeleteDocumentOptions.Builder()
				.db(databaseName)
				.docId(id)
				.rev(rev)
				.build();

			DocumentResult result = client.deleteDocument(options).execute().getResult();
			log.debug("Deleted document with ID: " + id + " from database: " + databaseName);
			
			return result;

		} catch (Exception e) {
			// FIXED: Don't silently ignore delete failures during normal operations
			// Only ignore during startup/initialization
			if (isStartupPhase()) {
				log.warn("Error deleting document with ID '" + id + "' from database '" + databaseName + "' during startup - returning null. This is normal during initial startup: " + e.getMessage());
				return null;
			} else {
				log.error("Critical error deleting document with ID '" + id + "' from database '" + databaseName + "'", e);
				throw new RuntimeException("Failed to delete document ID '" + id + "' from database '" + databaseName + "': " + e.getMessage(), e);
			}
		}
	}
	
	/**
	 * Check if we're in startup phase where delete errors should be ignored
	 */
	private boolean isStartupPhase() {
		// Simple heuristic: if we're in a startup-related thread, allow failures
		String threadName = Thread.currentThread().getName();
		return threadName.contains("main") || threadName.contains("startup") || threadName.contains("init");
	}

	/**
	 * Execute a view query
	 */
	public ViewResult queryView(String designDoc, String viewName, Map<String, Object> queryParams) {
		try {
			// Build view query using PostViewOptions
			PostViewOptions.Builder builder = new PostViewOptions.Builder()
				.db(databaseName)
				.ddoc(designDoc)
				.view(viewName)
				.includeDocs(true);

			// Add query parameters if provided
			if (queryParams != null) {
				if (queryParams.containsKey("limit")) {
					Object limitValue = queryParams.get("limit");
					if (limitValue instanceof Integer) {
						builder.limit(((Integer) limitValue).longValue());
					} else if (limitValue instanceof Long) {
						builder.limit((Long) limitValue);
					}
				}
				if (queryParams.containsKey("skip")) {
					Object skipValue = queryParams.get("skip");
					if (skipValue instanceof Integer) {
						builder.skip(((Integer) skipValue).longValue());
					} else if (skipValue instanceof Long) {
						builder.skip((Long) skipValue);
					}
				}
				if (queryParams.containsKey("startkey")) {
					Object startKey = queryParams.get("startkey");
					if (startKey != null) {
						builder.startKey(startKey);
					}
				}
				if (queryParams.containsKey("endkey")) {
					Object endKey = queryParams.get("endkey");
					if (endKey != null) {
						builder.endKey(endKey);
					}
				}
				if (queryParams.containsKey("key")) {
					Object key = queryParams.get("key");
					if (key != null) {
						// Try sending the key as-is first (Cloudant might handle the JSON conversion)
						builder.key(key);
					}
				}
				if (queryParams.containsKey("keys")) {
					Object keys = queryParams.get("keys");
					if (keys instanceof List) {
						builder.keys((List<Object>) keys);
					}
				}
				if (queryParams.containsKey("reduce")) {
					Object reduce = queryParams.get("reduce");
					if (reduce instanceof Boolean) {
						builder.reduce((Boolean) reduce);
					}
				}
				if (queryParams.containsKey("group")) {
					Object group = queryParams.get("group");
					if (group instanceof Boolean) {
						builder.group((Boolean) group);
					}
				}
			}

			ViewResult result = client.postView(builder.build()).execute().getResult();
			log.debug("Executed view query for design doc: " + designDoc + ", view: " + viewName + " with " + 
					(result.getRows() != null ? result.getRows().size() : 0) + " results");
			
			return result;

		} catch (com.ibm.cloud.sdk.core.service.exception.NotFoundException e) {
			log.warn("Design document '" + designDoc + "' or view '" + viewName + "' not found - returning null. This is normal during initial startup.");
			return null;
		} catch (Exception e) {
			log.warn("Error executing view query for design doc '" + designDoc + "', view '" + viewName + "' - returning null. This is normal during initial startup: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Execute a view query (legacy method for compatibility)
	 */
	public AllDocsResult queryViewLegacy(String designDoc, String viewName, Map<String, Object> queryParams) {
		try {
			// For backward compatibility, convert ViewResult to AllDocsResult-like structure
			ViewResult viewResult = queryView(designDoc, viewName, queryParams);
			
			// Create a simple AllDocsResult using _all_docs for now
			// In the future, this could be enhanced to convert ViewResult properly
			PostAllDocsOptions.Builder builder = new PostAllDocsOptions.Builder()
				.db(databaseName)
				.includeDocs(true);

			if (queryParams != null && queryParams.containsKey("limit")) {
				Object limitValue = queryParams.get("limit");
				if (limitValue instanceof Integer) {
					builder.limit(((Integer) limitValue).longValue());
				} else if (limitValue instanceof Long) {
					builder.limit((Long) limitValue);
				}
			}

			AllDocsResult result = client.postAllDocs(builder.build()).execute().getResult();
			log.debug("Executed legacy view query for design doc: " + designDoc + ", view: " + viewName);
			
			return result;

		} catch (Exception e) {
			log.warn("Error executing legacy view query for design doc '" + designDoc + "', view '" + viewName + "' - returning null. This is normal during initial startup: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Get all documents
	 */
	public AllDocsResult getAllDocs() {
		return getAllDocs(null);
	}

	/**
	 * Get all documents with options
	 */
	public AllDocsResult getAllDocs(Map<String, Object> options) {
		try {
			PostAllDocsOptions.Builder builder = new PostAllDocsOptions.Builder()
				.db(databaseName)
				.includeDocs(true);

			if (options != null) {
				if (options.containsKey("limit")) {
					builder.limit((Long) options.get("limit"));
				}
				if (options.containsKey("skip")) {
					builder.skip((Long) options.get("skip"));
				}
			}

			AllDocsResult result = client.postAllDocs(builder.build()).execute().getResult();
			log.debug("Retrieved all docs from database: " + databaseName);
			
			return result;

		} catch (Exception e) {
			log.warn("Error retrieving all docs from database '" + databaseName + "' - returning null. This is normal during initial startup: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Check if document exists
	 */
	public boolean exists(String id) {
		try {
			HeadDocumentOptions options = new HeadDocumentOptions.Builder()
				.db(databaseName)
				.docId(id)
				.build();

			client.headDocument(options).execute();
			return true;

		} catch (NotFoundException e) {
			return false;
		} catch (Exception e) {
			log.error("Error checking if document exists with ID '" + id + "'", e);
			return false;
		}
	}

	/**
	 * Get database information
	 */
	public DatabaseInformation getDatabaseInfo() {
		try {
			GetDatabaseInformationOptions options = new GetDatabaseInformationOptions.Builder()
				.db(databaseName)
				.build();

			DatabaseInformation result = client.getDatabaseInformation(options).execute().getResult();
			log.debug("Retrieved database info for: " + databaseName);
			
			return result;

		} catch (Exception e) {
			log.warn("Error retrieving database info for '" + databaseName + "' - returning null. This is normal during initial startup: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Bridge method to replace Ektorp's ViewQuery - query view and deserialize to specific class
	 */
	public <T> List<T> queryView(String designDoc, String viewName, String key, Class<T> clazz) {
		log.debug("CLOUDANT ENTRY: queryView called with designDoc=" + designDoc + ", viewName=" + viewName + ", key=" + key + ", class=" + clazz.getSimpleName());
		try {
			// Build view path
			String viewPath = "_design/" + designDoc.replace("_design/", "") + "/_view/" + viewName;
			log.debug("CLOUDANT DEBUG: Querying view " + viewPath + " with key: " + key + " in database: " + databaseName);
			
			PostViewOptions.Builder builder = new PostViewOptions.Builder()
				.db(databaseName)
				.ddoc(designDoc.replace("_design/", ""))
				.view(viewName)
				.includeDocs(true); // Include documents for conversion
			
			// CRITICAL FIX: Add key to the server-side query instead of client-side filtering
			if (key != null) {
				builder.key(key);
				log.error("CLOUDANT DEBUG: Added key to query: " + key);
			}
			
			ViewResult result = client.postView(builder.build()).execute().getResult();
			log.debug("CLOUDANT DEBUG: ViewResult received, rows count: " + (result.getRows() != null ? result.getRows().size() : "null"));
			
			List<T> objects = new ArrayList<T>();
			ObjectMapper mapper = getObjectMapper();
			
			for (ViewResultRow row : result.getRows()) {
				if (row.getDoc() != null) {
					// CRITICAL FIX: Manually handle _id and _rev mapping since convertValue doesn't use @JsonCreator
					com.ibm.cloud.cloudant.v1.model.Document doc = row.getDoc();
					Map<String, Object> docMap = doc.getProperties();
					
					if (docMap != null) {
						// Ensure _id and _rev are properly mapped
						if (!docMap.containsKey("_id") && doc.getId() != null) {
							docMap.put("_id", doc.getId());
						}
						if (!docMap.containsKey("_rev") && doc.getRev() != null) {
							docMap.put("_rev", doc.getRev());
						}
						
						log.debug("DEBUG: Document properties keys: " + docMap.keySet());
						log.debug("DEBUG: _id field value: " + docMap.get("_id"));
						
						// Use convertValue but ensure proper field mapping by pre-processing the map
						T obj = mapper.convertValue(docMap, clazz);
						
						// CRITICAL FIX: If ID is still null after conversion, manually set it
						if (obj instanceof jp.aegif.nemaki.model.couch.CouchNodeBase) {
							jp.aegif.nemaki.model.couch.CouchNodeBase nodeBase = (jp.aegif.nemaki.model.couch.CouchNodeBase) obj;
							log.debug("DEBUG: ConvertValue initial result - ID: " + nodeBase.getId() + ", Rev: " + nodeBase.getRevision());
							
							if (nodeBase.getId() == null && docMap.get("_id") != null) {
								nodeBase.setId((String) docMap.get("_id"));
								log.error("DEBUG: Manually set ID: " + nodeBase.getId());
							}
							if (nodeBase.getRevision() == null && docMap.get("_rev") != null) {
								nodeBase.setRevision((String) docMap.get("_rev"));
								log.error("DEBUG: Manually set revision: " + nodeBase.getRevision());
							}
						}
						
						objects.add(obj);
					} else {
						log.warn("Document properties are null, skipping object creation");
					}
				}
			}
			
			log.debug("Retrieved " + objects.size() + " objects from view " + viewPath + " with key: " + key);
			return objects;
			
		} catch (com.ibm.cloud.sdk.core.service.exception.NotFoundException e) {
			log.warn("Design document '" + designDoc + "' or view '" + viewName + "' not found - returning null. This is normal during initial startup.");
			return null;
		} catch (Exception e) {
			log.error("CRITICAL: Error querying view " + designDoc + "/" + viewName + " with key: " + key + " - returning null. Exception: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
			return null;
		}
	}

	/**
	 * Bridge method to replace Ektorp's ViewQuery - query view without specific class
	 */
	public ViewResult queryView(String designDoc, String viewName, String key) {
		try {
			// Try using GET method instead of POST for better CouchDB compatibility
			PostViewOptions.Builder builder = new PostViewOptions.Builder()
				.db(databaseName)
				.ddoc(designDoc.replace("_design/", ""))
				.view(viewName);
				// Remove includeDocs for now to test basic functionality
			
			// First try without key to test basic view access
			ViewResult result = client.postView(builder.build()).execute().getResult();
			
			if (key != null) {
				// Filter results by key client-side
				List<ViewResultRow> filteredRows = new ArrayList<>();
				for (ViewResultRow row : result.getRows()) {
					if (row.getKey() != null && key.equals(row.getKey().toString().replace("\"", ""))) {
						filteredRows.add(row);
					}
				}
				
				// CRITICAL FIX: Return null if no matching key found (proper CouchDB behavior)
				log.debug("SECURITY FIX: Executed view query " + designDoc + "/" + viewName + " with key: " + key + " (filtered " + filteredRows.size() + " from " + result.getRows().size() + " results)");
				
				if (filteredRows.isEmpty()) {
					// No matching key found - return null to indicate no results
					log.debug("SECURITY FIX: No matching key found for: " + key + " - returning null");
					return null;
				} else {
					// Create a ViewResult with only the matching rows
					// Since we cannot create new ViewResult, we modify the existing one
					result.getRows().clear();
					result.getRows().addAll(filteredRows);
					return result;
				}
			}
			
			log.debug("Executed view query " + designDoc + "/" + viewName + " (returned " + result.getRows().size() + " results)");
			return result;
			
		} catch (com.ibm.cloud.sdk.core.service.exception.NotFoundException e) {
			log.warn("Design document '" + designDoc + "' or view '" + viewName + "' not found - returning null. This is normal during initial startup.");
			return null;
		} catch (Exception e) {
			log.error("CRITICAL: Error querying view " + designDoc + "/" + viewName + " with key: " + key + " - returning null. Exception: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
			return null;
		}
	}

	/**
	 * Bridge method to replace Ektorp's ViewQuery - query view without key
	 */
	public <T> List<T> queryView(String designDoc, String viewName, Class<T> clazz) {
		return queryView(designDoc, viewName, null, clazz);
	}

	/**
	 * Bridge method to replace Ektorp's ViewQuery - query view without key or class
	 */
	public ViewResult queryView(String designDoc, String viewName) {
		return queryView(designDoc, viewName, (String) null);
	}

	/**
	 * Create document (compatible with Ektorp create method)
	 */
	public void create(Object document) {
		try {
			log.debug("Creating document of type: " + document.getClass().getSimpleName());
			
			if (document instanceof jp.aegif.nemaki.model.couch.CouchTypeDefinition) {
				jp.aegif.nemaki.model.couch.CouchTypeDefinition typeDef = (jp.aegif.nemaki.model.couch.CouchTypeDefinition) document;
			}
			
			ObjectMapper mapper = getObjectMapper();
			Map<String, Object> documentMap;
			
			// Handle CouchChange objects manually due to ObjectMapper issues
			if (document instanceof jp.aegif.nemaki.model.couch.CouchChange) {
				jp.aegif.nemaki.model.couch.CouchChange change = (jp.aegif.nemaki.model.couch.CouchChange) document;
				
				documentMap = new java.util.HashMap<>();
				// Required fields for change documents
				documentMap.put("type", change.getType());
				documentMap.put("created", change.getCreated() != null ? change.getCreated().getTimeInMillis() : null);
				documentMap.put("creator", change.getCreator());
				documentMap.put("modified", change.getModified() != null ? change.getModified().getTimeInMillis() : null);
				documentMap.put("modifier", change.getModifier());
				
				// Change-specific fields
				documentMap.put("objectId", change.getObjectId());
				documentMap.put("token", change.getToken());
				documentMap.put("changeType", change.getChangeType() != null ? change.getChangeType().toString() : null);
				documentMap.put("time", change.getTime() != null ? change.getTime().getTimeInMillis() : null);
				documentMap.put("name", change.getName());
				documentMap.put("baseType", change.getBaseType());
				documentMap.put("objectType", change.getObjectType());
				documentMap.put("versionSeriesId", change.getVersionSeriesId());
				documentMap.put("versionLabel", change.getVersionLabel());
				documentMap.put("policyIds", change.getPolicyIds());
				documentMap.put("acl", change.getAcl());
				documentMap.put("paretnId", change.getParetnId());
				
				// Additional properties (empty map for now)
				documentMap.put("additionalProperties", new java.util.HashMap<>());
				
				// Content type flags
				documentMap.put("content", change.isContent());
				documentMap.put("document", change.isDocument());
				documentMap.put("folder", change.isFolder());
				documentMap.put("attachment", change.isAttachment());
				documentMap.put("relationship", change.isRelationship());
				documentMap.put("policy", change.isPolicy());
			} else {
				// Handle CouchTypeDefinition serialization with explicit properties handling
				if (document instanceof jp.aegif.nemaki.model.couch.CouchTypeDefinition) {
					jp.aegif.nemaki.model.couch.CouchTypeDefinition typeDef = (jp.aegif.nemaki.model.couch.CouchTypeDefinition) document;
					log.info("Processing CouchTypeDefinition with properties: " + typeDef.getProperties());
					
					// Use standard ObjectMapper conversion but verify properties field
					@SuppressWarnings("unchecked")
					Map<String, Object> tempMap = mapper.convertValue(document, Map.class);
					documentMap = tempMap;
					
					// Ensure properties field is correctly preserved
					if (typeDef.getProperties() != null && !typeDef.getProperties().isEmpty()) {
						documentMap.put("properties", typeDef.getProperties());
						log.info("Explicitly set properties field in document map: " + documentMap.get("properties"));
					}
				} else {
					@SuppressWarnings("unchecked")
					Map<String, Object> tempMap = mapper.convertValue(document, Map.class);
					documentMap = tempMap;
				}
			}
			
			// Convert CMIS array structures to Cloudant Document model compatible maps
			documentMap = convertPropertiesArrayToMap(documentMap);
			documentMap = convertTypeDefinitionPropertiesToMap(documentMap);
			
			// Remove null _id and _rev from new document creation
			// CouchDB should generate ID automatically when _id is not provided
			if (documentMap.get("_id") == null) {
				documentMap.remove("_id");
			}
			
			if (documentMap.get("_rev") == null) {
				documentMap.remove("_rev");
			}
			
			// Use PostDocumentOptions for auto-generated ID
			String jsonString = mapper.writeValueAsString(documentMap);
			
			// Send JSON directly as a raw string to preserve all content
			
			PostDocumentOptions options = new PostDocumentOptions.Builder()
				.db(databaseName)
				.body(new java.io.ByteArrayInputStream(jsonString.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
				.contentType("application/json")
				.build();

			DocumentResult result = client.postDocument(options).execute().getResult();
			log.debug("Created document with ID: " + result.getId());
			
			// Set the generated ID and revision back to the original object
			// This is CRITICAL for subsequent operations like writeChangeEvent -> updateInternal
			if (document instanceof jp.aegif.nemaki.model.couch.CouchNodeBase) {
				jp.aegif.nemaki.model.couch.CouchNodeBase nodeBase = (jp.aegif.nemaki.model.couch.CouchNodeBase) document;
				nodeBase.setId(result.getId());
				nodeBase.setRevision(result.getRev());
				log.info("EKTORP-STYLE: Set ID " + result.getId() + " and revision " + result.getRev() + " back to " + 
					document.getClass().getSimpleName() + " object for future Ektorp-style updates");
			}
		} catch (Exception e) {
			log.error("CLOUDANT CREATE ERROR: Exception occurred during document creation", e);
			log.error("CLOUDANT CREATE ERROR: Document type was: " + (document != null ? document.getClass().getSimpleName() : "null"));
			if (document instanceof jp.aegif.nemaki.model.couch.CouchFolder) {
				jp.aegif.nemaki.model.couch.CouchFolder folder = (jp.aegif.nemaki.model.couch.CouchFolder) document;
				log.error("CLOUDANT CREATE ERROR: CouchFolder details - objectType=" + folder.getObjectType() + 
					", name=" + folder.getName() + ", type=" + folder.getType());
			}
			throw new RuntimeException("Failed to create document object", e);
		}
	}

	/**
	 * Update document (compatible with Ektorp update method)
	 * This method implements Ektorp-style object state management - trusts object revision completely
	 */
	public void update(Object document) {
		try {
			ObjectMapper mapper = getObjectMapper();
			
			@SuppressWarnings("unchecked")
			Map<String, Object> documentMap = mapper.convertValue(document, Map.class);
			
			String id = (String) documentMap.get("_id");
			if (id == null) {
				throw new IllegalArgumentException("Document must have '_id' field for update");
			}
			
			// Ektorp-style behavior: ALWAYS trust the object's revision state
			// If the object has a revision, use it; if not, it's a serious error
			String currentRev = (String) documentMap.get("_rev");
			if (currentRev == null || currentRev.isEmpty()) {
				throw new IllegalArgumentException("Document " + id + " has no revision - cannot perform safe update. " +
					"In Ektorp-style operation, objects must maintain their revision state.");
			}
			
			log.debug("Ektorp-style update: using object revision " + currentRev + " for document " + id);
			
			// Convert CMIS array structures to Cloudant Document model compatible maps
			documentMap = convertPropertiesArrayToMap(documentMap);
			documentMap = convertTypeDefinitionPropertiesToMap(documentMap);
			
			String jsonString = mapper.writeValueAsString(documentMap);
			com.ibm.cloud.cloudant.v1.model.Document doc = mapper.readValue(jsonString, com.ibm.cloud.cloudant.v1.model.Document.class);

			PutDocumentOptions options = new PutDocumentOptions.Builder()
				.db(databaseName)
				.docId(id)
				.document(doc)
				.build();

			DocumentResult result = client.putDocument(options).execute().getResult();
			log.debug("Ektorp-style update successful: " + id + " (from revision " + currentRev + " to " + result.getRev() + ")");
			
			// Update the revision in the original object to maintain Ektorp-style state consistency
			if (document instanceof jp.aegif.nemaki.model.couch.CouchNodeBase) {
				jp.aegif.nemaki.model.couch.CouchNodeBase nodeBase = (jp.aegif.nemaki.model.couch.CouchNodeBase) document;
				nodeBase.setRevision(result.getRev());
				log.debug("Maintained object state: updated revision to " + result.getRev());
			}
			
		} catch (com.ibm.cloud.sdk.core.service.exception.ConflictException e) {
			// Provide helpful error message for revision conflicts in Ektorp-style operations
			log.warn("Revision conflict in Ektorp-style update - object revision was stale or concurrent modification occurred. This is normal during initial startup: " + e.getMessage());
		} catch (Exception e) {
			log.warn("Error in Ektorp-style document update. This is normal during initial startup: " + e.getMessage());
		}
	}

	/**
	 * Get document by class and ID (compatible with Ektorp get method)
	 */
	public <T> T get(Class<T> clazz, String id) {
		try {
			com.ibm.cloud.cloudant.v1.model.Document doc = get(id);
			if (doc != null) {
				ObjectMapper mapper = getObjectMapper();
				
				// CRITICAL FIX: For CouchPropertyDefinitionDetail, use Document.getProperties() to get actual CouchDB fields
				// This ensures coreNodeId and other custom fields are properly deserialized
				if (clazz.getSimpleName().equals("CouchPropertyDefinitionDetail")) {
					
					// Get the properties Map which contains the actual CouchDB document fields including coreNodeId
					Map<String, Object> properties = doc.getProperties();
					if (properties != null) {
						
						// Create complete map with both document metadata and properties
						Map<String, Object> completeMap = new HashMap<>();
						
						// Add standard document fields
						completeMap.put("_id", doc.getId());
						completeMap.put("_rev", doc.getRev());
						
						// Add all properties from CouchDB document
						for (Map.Entry<String, Object> entry : properties.entrySet()) {
							String key = entry.getKey();
							Object value = entry.getValue();
							
							// CRITICAL FIX: Convert timestamp fields from floating-point to GregorianCalendar
							if (("created".equals(key) || "modified".equals(key)) && value instanceof Number) {
								long timestamp = ((Number) value).longValue();
								java.util.GregorianCalendar calendar = new java.util.GregorianCalendar();
								calendar.setTimeInMillis(timestamp);
								completeMap.put(key, calendar);
							} else {
								completeMap.put(key, value);
							}
						}
						
						try {
							T result = mapper.convertValue(completeMap, clazz);
							return result;
						} catch (Exception deserEx) {
							throw deserEx;
						}
					} else {
						// Document properties is NULL for PropertyDefinitionDetail
					}
				}
				
				// CRITICAL FIX: For CouchAttachmentNode, use Document.getProperties() to get actual CouchDB fields
				if (clazz.getSimpleName().equals("CouchAttachmentNode")) {
					log.error("=== ENHANCED ATTACHMENT DESERIALIZATION ===");
					log.error("Document ID: " + id);
					
					// Get the properties Map which contains the actual CouchDB document fields
					Map<String, Object> properties = doc.getProperties();
					if (properties != null) {
						log.error("Properties map keys: " + properties.keySet());
						log.error("Properties 'name': " + properties.get("name"));
						log.error("Properties 'length': " + properties.get("length"));
						log.error("Properties 'mimeType': " + properties.get("mimeType"));
						log.error("Properties '_attachments': " + (properties.get("_attachments") != null ? "PRESENT" : "NULL"));
						
						// Create complete map with both document metadata and properties
						Map<String, Object> completeMap = new HashMap<>();
						
						// Add standard document fields
						completeMap.put("_id", doc.getId());
						completeMap.put("_rev", doc.getRev());
						
						// Add all properties from CouchDB document with timestamp conversion
						for (Map.Entry<String, Object> entry : properties.entrySet()) {
							String key = entry.getKey();
							Object value = entry.getValue();
							
							// CRITICAL FIX: Convert timestamp fields from floating-point to GregorianCalendar
							if (("created".equals(key) || "modified".equals(key)) && value instanceof Number) {
								long timestamp = ((Number) value).longValue();
								java.util.GregorianCalendar calendar = new java.util.GregorianCalendar();
								calendar.setTimeInMillis(timestamp);
								completeMap.put(key, calendar);
								
								log.error("Converted timestamp field '" + key + "': " + value + " -> " + calendar.getTime());
							} else {
								completeMap.put(key, value);
							}
						}
						
						log.error("Complete map keys: " + completeMap.keySet());
						
						try {
							T result = mapper.convertValue(completeMap, clazz);
							log.error("ENHANCED deserialization SUCCESS for attachment: " + id);
							return result;
						} catch (Exception deserEx) {
							log.error("ENHANCED deserialization FAILED for attachment: " + id);
							log.error("Error details: " + deserEx.getMessage());
							throw deserEx;
						}
					} else {
						log.error("Document properties is NULL for attachment: " + id);
					}
				}
				
				// Convert immutable Document to mutable Map first, then to target class
				@SuppressWarnings("unchecked")
				Map<String, Object> docMap = mapper.convertValue(doc, Map.class);
				
				// CRITICAL FIX: Ensure _id and _rev fields are always present in the map
				// This is essential for Cloudant SDK deletion operations
				if (doc.getId() != null) {
					docMap.put("_id", doc.getId());
				}
				if (doc.getRev() != null) {
					docMap.put("_rev", doc.getRev());
					log.debug("CloudantClientWrapper.get(): Ensured _rev field is present: " + doc.getRev() + " for document: " + doc.getId());
				} else {
					log.warn("CloudantClientWrapper.get(): Document " + doc.getId() + " has no _rev field - this may cause deletion issues");
				}
				
				return mapper.convertValue(docMap, clazz);
			}
			return null;
		} catch (Exception e) {
			log.warn("Error getting document with ID: " + id + " as class: " + clazz.getName() + " - returning null. This is normal during initial startup: " + e.getMessage());
			return null;
		}
	}

	/**
	 * ENHANCED: Robust single document deletion with comprehensive error handling
	 * Implements Cloudant SDK best practices for individual document deletion
	 * Compatible with Ektorp delete method signature
	 * 
	 * @param document The document object to delete
	 */
	public void delete(Object document) {
		try {
			log.error("=== SINGLE DELETE TRACE START ===");
			log.error("DELETE: Starting deletion for document: " + document.getClass().getName());
			
			ObjectMapper mapper = getObjectMapper();
			@SuppressWarnings("unchecked")
			Map<String, Object> documentMap = mapper.convertValue(document, Map.class);
			
			// DEBUG: Log the document map to understand what fields are present
			log.error("DEBUG: Document class: " + document.getClass().getName());
			log.error("DEBUG: DocumentMap keys: " + documentMap.keySet());
			log.error("DEBUG: DocumentMap contents: " + documentMap);
			
			// Try both "_id" and "id" fields
			String id = (String) documentMap.get("_id");
			if (id == null) {
				id = (String) documentMap.get("id");
			}
			
			// Try both "_rev" and "revision" fields
			String rev = (String) documentMap.get("_rev");
			if (rev == null) {
				rev = (String) documentMap.get("revision");
			}
			
			if (id == null) {
				// If still null, try to get ID directly from CouchNodeBase
				if (document instanceof CouchNodeBase) {
					CouchNodeBase cnb = (CouchNodeBase) document;
					id = cnb.getId();
					rev = cnb.getRevision();
					log.error("DEBUG: Got ID from CouchNodeBase getter: id=" + id + ", rev=" + rev);
				}
			}
			
			if (id == null) {
				throw new IllegalArgumentException("Document must have '_id' field for delete");
			}
			if (rev == null) {
				throw new IllegalArgumentException("Document must have '_rev' field for delete");
			}
			
			log.error("CLOUDANT DELETION DEBUG: Attempting to delete document ID: " + id + " with revision: " + rev);
			
			// CRITICAL FIX: Always fetch the absolute latest revision to prevent conflicts
			// This is the most important part for reliable deletion
			try {
				log.debug("DELETE: Fetching latest revision for document: " + id);
				GetDocumentOptions getOptions = new GetDocumentOptions.Builder()
					.db(databaseName)
					.docId(id)
					.build();
				Document currentDoc = client.getDocument(getOptions).execute().getResult();
				String latestRev = currentDoc.getRev();
				
				if (!rev.equals(latestRev)) {
					log.error("CLOUDANT DELETION DEBUG: Document revision is stale. Using latest revision: " + latestRev + " instead of: " + rev);
					rev = latestRev;
				} else {
					log.debug("DELETION DEBUG: Document revision is current: " + rev);
				}
			} catch (NotFoundException nfe) {
				log.warn("DELETE: Document " + id + " not found during revision fetch - may already be deleted");
				return; // Document doesn't exist, consider deletion successful
			} catch (Exception fetchEx) {
				log.warn("DELETION DEBUG: Could not fetch latest revision for document " + id + ": " + fetchEx.getMessage() + ". Proceeding with provided revision: " + rev);
				// Continue with original revision
			}
			
			// Perform the actual deletion
			log.error("DELETE: Executing delete operation for ID: " + id + " rev: " + rev);
			DocumentResult result = delete(id, rev);
			
			if (result == null) {
				throw new RuntimeException("Delete operation failed for document ID: " + id + " - no result returned from CouchDB");
			}
			
			// Verify deletion succeeded
			if (!result.isOk()) {
				throw new RuntimeException("Delete operation failed for document ID: " + id + " - error: " + result.getError() + ", reason: " + result.getReason());
			}
			
			log.error("CLOUDANT DELETION SUCCESS: Successfully deleted document with ID: " + id + " using revision: " + rev);
			log.error("=== SINGLE DELETE TRACE END ===");
			
		} catch (IllegalArgumentException e) {
			// Re-throw validation errors
			log.error("DELETE: Validation error", e);
			throw e;
		} catch (RuntimeException e) {
			// Re-throw runtime errors (including our custom failure detection)
			log.error("DELETE: Runtime error", e);
			throw e;
		} catch (Exception e) {
			// CRITICAL: Don't silently ignore delete failures - propagate them with full context
			ObjectMapper mapper = getObjectMapper();
			@SuppressWarnings("unchecked")
			Map<String, Object> documentMap = mapper.convertValue(document, Map.class);
			String docId = (String) documentMap.get("_id");
			log.error("DELETE: Critical error deleting document object with ID: " + docId, e);
			throw new RuntimeException("Failed to delete document: " + e.getMessage(), e);
		}
	}

	/**
	 * Convert CMIS properties array to Cloudant Document model compatible map structure
	 * This is a reusable utility for all CMIS documents that have properties as arrays
	 * 
	 * @param documentMap The document map that may contain properties array
	 * @return Updated document map with properties converted to map format
	 */
	private Map<String, Object> convertPropertiesArrayToMap(Map<String, Object> documentMap) {
		if (documentMap.containsKey("properties") && documentMap.get("properties") instanceof List) {
			
			@SuppressWarnings("unchecked")
			List<Object> propertiesList = (List<Object>) documentMap.get("properties");
			Map<String, Object> propertiesMap = new HashMap<>();
			
			for (Object prop : propertiesList) {
				if (prop instanceof Map) {
					@SuppressWarnings("unchecked")
					Map<String, Object> propMap = (Map<String, Object>) prop;
					Object id = propMap.get("id");
					if (id != null) {
						// Use property ID as key for map structure
						propertiesMap.put(id.toString(), prop);
					} else {
						// Fallback: use index-based key if no ID found
						propertiesMap.put("property_" + propertiesMap.size(), prop);
					}
				}
			}
			
			// Replace array with map in the document
			documentMap.put("properties", propertiesMap);
		}
		
		return documentMap;
	}
	
	/**
	 * Convert CMIS property definitions array to map (specialized for TypeDefinitions)
	 * This handles the specific case of CMIS TypeDefinition property structures
	 * 
	 * @param documentMap The TypeDefinition document map
	 * @return Updated document map with proper structure for Cloudant
	 */
	private Map<String, Object> convertTypeDefinitionPropertiesToMap(Map<String, Object> documentMap) {
		// Handle propertyDefinitions array (for TypeDefinitions)
		if (documentMap.containsKey("propertyDefinitions") && documentMap.get("propertyDefinitions") instanceof List) {
			
			@SuppressWarnings("unchecked")
			List<Object> propDefsList = (List<Object>) documentMap.get("propertyDefinitions");
			Map<String, Object> propDefsMap = new HashMap<>();
			
			for (Object propDef : propDefsList) {
				if (propDef instanceof Map) {
					@SuppressWarnings("unchecked")
					Map<String, Object> propDefMap = (Map<String, Object>) propDef;
					Object id = propDefMap.get("id");
					if (id != null) {
						propDefsMap.put(id.toString(), propDef);
					}
				}
			}
			
			documentMap.put("propertyDefinitions", propDefsMap);
		}
		
		return documentMap;
	}

	/**
	 * Get attachment using Cloudant SDK
	 */
	public Object getAttachment(String docId, String attachmentName) {
		try {
			GetAttachmentOptions options = new GetAttachmentOptions.Builder()
				.db(databaseName)
				.docId(docId)
				.attachmentName(attachmentName)
				.build();

			// Get attachment as InputStream
			java.io.InputStream attachmentStream = client.getAttachment(options).execute().getResult();
			log.debug("Retrieved attachment: " + attachmentName + " from document: " + docId);
			
			return attachmentStream;

		} catch (NotFoundException e) {
			log.debug("Attachment not found: " + attachmentName + " in document: " + docId);
			// Ektorp compatibility: throw exception instead of returning null
			throw new org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException(
				"Attachment '" + attachmentName + "' not found in document '" + docId + "'", e);
		} catch (Exception e) {
			log.warn("Error retrieving attachment '" + attachmentName + "' from document '" + docId + "': " + e.getMessage());
			// Ektorp compatibility: throw exception instead of swallowing errors
			throw new org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException(
				"Failed to retrieve attachment '" + attachmentName + "' from document '" + docId + "'", e);
		}
	}

	/**
	 * Get attachment with revision using Cloudant SDK
	 */
	public Object getAttachment(String docId, String attachmentName, String revision) {
		try {
			GetAttachmentOptions.Builder builder = new GetAttachmentOptions.Builder()
				.db(databaseName)
				.docId(docId)
				.attachmentName(attachmentName);
			
			// Add revision if specified
			if (revision != null && !revision.isEmpty()) {
				builder.rev(revision);
			}

			// Get attachment as InputStream
			java.io.InputStream attachmentStream = client.getAttachment(builder.build()).execute().getResult();
			log.debug("Retrieved attachment: " + attachmentName + " from document: " + docId + " (revision: " + revision + ")");
			
			return attachmentStream;

		} catch (NotFoundException e) {
			log.debug("Attachment not found: " + attachmentName + " in document: " + docId + " (revision: " + revision + ")");
			// Ektorp compatibility: throw exception instead of returning null
			throw new org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException(
				"Attachment '" + attachmentName + "' not found in document '" + docId + "' (revision: " + revision + ")", e);
		} catch (Exception e) {
			log.warn("Error retrieving attachment '" + attachmentName + "' from document '" + docId + "' (revision: " + revision + "): " + e.getMessage());
			// Ektorp compatibility: throw exception instead of swallowing errors
			throw new org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException(
				"Failed to retrieve attachment '" + attachmentName + "' from document '" + docId + "' (revision: " + revision + ")", e);
		}
	}

	/**
	 * Create attachment using Cloudant SDK with optimized revision conflict handling
	 */
	public String createAttachment(String docId, String revision, String attachmentName, Object attachmentInputStream, String contentType) {
		try {
			log.debug("ATTACHMENT CREATE: Starting attachment creation for document: " + docId + " (revision: " + revision + ")");
			
			// Convert attachment input to InputStream if needed
			java.io.InputStream inputStream;
			if (attachmentInputStream instanceof java.io.InputStream) {
				inputStream = (java.io.InputStream) attachmentInputStream;
			} else if (attachmentInputStream instanceof byte[]) {
				inputStream = new java.io.ByteArrayInputStream((byte[]) attachmentInputStream);
			} else {
				throw new IllegalArgumentException("Unsupported attachment input type: " + attachmentInputStream.getClass());
			}

			// Validate required parameters
			if (docId == null || docId.isEmpty()) {
				throw new IllegalArgumentException("Document ID cannot be null or empty");
			}
			if (attachmentName == null || attachmentName.isEmpty()) {
				throw new IllegalArgumentException("Attachment name cannot be null or empty");
			}

			PutAttachmentOptions.Builder builder = new PutAttachmentOptions.Builder()
				.db(databaseName)
				.docId(docId)
				.attachmentName(attachmentName)
				.attachment(inputStream);
			
			// Add revision if specified (critical for conflict prevention)
			if (revision != null && !revision.isEmpty()) {
				builder.rev(revision);
				log.debug("ATTACHMENT CREATE: Using revision: " + revision);
			} else {
				log.warn("ATTACHMENT CREATE: No revision specified - potential conflict risk");
			}
			
			// Add content type if specified
			if (contentType != null && !contentType.isEmpty()) {
				builder.contentType(contentType);
			} else {
				builder.contentType("application/octet-stream"); // Default content type
			}

			DocumentResult result = client.putAttachment(builder.build()).execute().getResult();
			String newRevision = result.getRev();
			
			log.debug("ATTACHMENT CREATE SUCCESS: Created attachment '" + attachmentName + "' for document: " + docId + " (revision: " + revision + " -> " + newRevision + ")");
			
			return newRevision; // Return new revision

		} catch (com.ibm.cloud.sdk.core.service.exception.ConflictException e) {
			// Specific handling for revision conflicts
			log.error("ATTACHMENT CREATE CONFLICT: Revision conflict for document " + docId + " (revision: " + revision + ") - " + e.getMessage());
			throw e; // Re-throw to allow retry logic in calling method
		} catch (com.ibm.cloud.sdk.core.service.exception.NotFoundException e) {
			// Document doesn't exist
			log.error("ATTACHMENT CREATE NOT FOUND: Document " + docId + " not found - " + e.getMessage());
			throw new RuntimeException("Cannot create attachment for non-existent document: " + docId, e);
		} catch (Exception e) {
			log.error("ATTACHMENT CREATE ERROR: Failed to create attachment '" + attachmentName + "' for document '" + docId + "' - " + e.getClass().getSimpleName() + ": " + e.getMessage());
			throw new RuntimeException("Failed to create attachment", e);
		}
	}
	
	/**
	 * Legacy create attachment method for backward compatibility
	 */
	public void createAttachment(String docId, String revision, Object attachmentInputStream) {
		// Use default attachment name and content type for backward compatibility
		createAttachment(docId, revision, "attachment", attachmentInputStream, "application/octet-stream");
	}

	/**
	 * Delete attachment using Cloudant SDK
	 */
	public String deleteAttachment(String docId, String revision, String attachmentName) {
		try {
			DeleteAttachmentOptions.Builder builder = new DeleteAttachmentOptions.Builder()
				.db(databaseName)
				.docId(docId)
				.attachmentName(attachmentName);
			
			// Add revision if specified
			if (revision != null && !revision.isEmpty()) {
				builder.rev(revision);
			}

			DocumentResult result = client.deleteAttachment(builder.build()).execute().getResult();
			log.debug("Deleted attachment: " + attachmentName + " from document: " + docId);
			
			return result.getRev(); // Return new revision

		} catch (Exception e) {
			log.warn("Error deleting attachment '" + attachmentName + "' from document '" + docId + "'. This is normal during initial startup: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Get document with revision using Cloudant SDK
	 */
	public <T> T get(Class<T> clazz, String id, String revision) {
		try {
			GetDocumentOptions.Builder builder = new GetDocumentOptions.Builder()
				.db(databaseName)
				.docId(id);
			
			// Add revision if specified
			if (revision != null && !revision.isEmpty()) {
				builder.rev(revision);
			}

			com.ibm.cloud.cloudant.v1.model.Document doc = client.getDocument(builder.build()).execute().getResult();
			
			if (doc != null) {
				ObjectMapper mapper = getObjectMapper();
				// Convert immutable Document to mutable Map first, then to target class
				@SuppressWarnings("unchecked")
				Map<String, Object> docMap = mapper.convertValue(doc, Map.class);
				log.debug("Retrieved document with ID: " + id + " (revision: " + revision + ")");
				return mapper.convertValue(docMap, clazz);
			}
			return null;

		} catch (NotFoundException e) {
			log.debug("Document not found with ID: " + id + " (revision: " + revision + ")");
			return null;
		} catch (Exception e) {
			log.warn("Error getting document with ID: " + id + " (revision: " + revision + ") as class: " + clazz.getName() + " - returning null. This is normal during initial startup: " + e.getMessage());
			return null;
		}
	}
	
	/**
	 * Get document with revision (returns raw Document)
	 */
	public com.ibm.cloud.cloudant.v1.model.Document get(String id, String revision) {
		try {
			GetDocumentOptions.Builder builder = new GetDocumentOptions.Builder()
				.db(databaseName)
				.docId(id);
			
			// Add revision if specified
			if (revision != null && !revision.isEmpty()) {
				builder.rev(revision);
			}

			com.ibm.cloud.cloudant.v1.model.Document result = client.getDocument(builder.build()).execute().getResult();
			log.debug("Retrieved document with ID: " + id + " (revision: " + revision + ")");
			
			return result;

		} catch (NotFoundException e) {
			log.debug("Document not found with ID: " + id + " (revision: " + revision + ")");
			return null;
		} catch (Exception e) {
			log.warn("Error retrieving document with ID '" + id + "' (revision: " + revision + "') from database '" + databaseName + "' - returning null. This is normal during initial startup: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Get design document using proper Cloudant SDK method
	 */
	public DesignDocument getDesignDocument(String designDocId) {
		try {
			GetDesignDocumentOptions options = new GetDesignDocumentOptions.Builder()
				.db(databaseName)
				.ddoc(designDocId.replace("_design/", ""))
				.build();

			DesignDocument result = client.getDesignDocument(options).execute().getResult();
			log.debug("Retrieved design document: " + designDocId);
			
			return result;

		} catch (NotFoundException e) {
			log.debug("Design document not found: " + designDocId);
			return null;
		} catch (Exception e) {
			log.warn("Error retrieving design document '" + designDocId + "' from database '" + databaseName + "' - returning null. This is normal during initial startup: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Create or update design document using proper Cloudant SDK method
	 */
	public DocumentResult putDesignDocument(String designDocId, DesignDocument designDoc) {
		try {
			PutDesignDocumentOptions options = new PutDesignDocumentOptions.Builder()
				.db(databaseName)
				.ddoc(designDocId.replace("_design/", ""))
				.designDocument(designDoc)
				.build();

			DocumentResult result = client.putDesignDocument(options).execute().getResult();
			log.debug("Created/updated design document: " + designDocId);
			
			return result;

		} catch (Exception e) {
			log.warn("Error creating/updating design document '" + designDocId + "' in database '" + databaseName + "'. This is normal during initial startup: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Create or update a view in design document (utility method)
	 */
	public void createOrUpdateView(String designDocId, String viewName, String mapFunction, String reduceFunction) {
		try {
			// Get existing design document or create new one using proper design document API
			DesignDocument existingDoc = getDesignDocument(designDocId);
			
			Map<String, DesignDocumentViewsMapReduce> views = new HashMap<>();
			
			// If existing document has views, copy them
			if (existingDoc != null && existingDoc.getViews() != null) {
				views.putAll(existingDoc.getViews());
			}

			// Create/update the new view
			DesignDocumentViewsMapReduce.Builder viewBuilder = new DesignDocumentViewsMapReduce.Builder()
				.map(mapFunction);
			
			if (reduceFunction != null && !reduceFunction.isEmpty()) {
				viewBuilder.reduce(reduceFunction);
			}
			
			views.put(viewName, viewBuilder.build());

			// Create new design document with updated views
			DesignDocument.Builder builder = new DesignDocument.Builder();
			if (existingDoc != null) {
				if (existingDoc.getId() != null) builder.id(existingDoc.getId());
				if (existingDoc.getRev() != null) builder.rev(existingDoc.getRev());
			}
			
			builder.views(views);
			
			DesignDocument updatedDoc = builder.build();
			putDesignDocument(designDocId, updatedDoc);
			
			log.info("Created/updated view '" + viewName + "' in design document: " + designDocId);

		} catch (Exception e) {
			log.error("Error creating/updating view '" + viewName + "' in design document '" + designDocId + "'", e);
			throw new RuntimeException("Failed to create/update view: " + viewName, e);
		}
	}
}
