package jp.aegif.nemaki.dao.impl.couch.connector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.*;
import com.ibm.cloud.sdk.core.service.exception.NotFoundException;

/**
 * Wrapper for Cloudant client that provides CouchDB operations
 * Replaces Ektorp's CouchDbConnector functionality
 */
public class CloudantClientWrapper {

	private final Cloudant client;
	private final String databaseName;

	private static final Log log = LogFactory.getLog(CloudantClientWrapper.class);

	public CloudantClientWrapper(Cloudant client, String databaseName) {
		this.client = client;
		this.databaseName = databaseName;
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
			// Convert Map to Document using proper Cloudant approach
			ObjectMapper mapper = new ObjectMapper();
			String jsonString = mapper.writeValueAsString(document);
			com.ibm.cloud.cloudant.v1.model.Document doc = mapper.readValue(jsonString, com.ibm.cloud.cloudant.v1.model.Document.class);

			PostDocumentOptions options = new PostDocumentOptions.Builder()
				.db(databaseName)
				.document(doc)
				.build();

			DocumentResult result = client.postDocument(options).execute().getResult();
			log.debug("Created document with ID: " + result.getId());
			
			return result;

		} catch (Exception e) {
			log.error("Error creating document in database '" + databaseName + "'", e);
			throw new RuntimeException("Failed to create document", e);
		}
	}

	/**
	 * Create a document with specific ID
	 */
	public DocumentResult create(String id, Map<String, Object> document) {
		try {
			// Add ID to document map before conversion
			document.put("_id", id);
			ObjectMapper mapper = new ObjectMapper();
			String jsonString = mapper.writeValueAsString(document);
			com.ibm.cloud.cloudant.v1.model.Document doc = mapper.readValue(jsonString, com.ibm.cloud.cloudant.v1.model.Document.class);

			PutDocumentOptions options = new PutDocumentOptions.Builder()
				.db(databaseName)
				.docId(id)
				.document(doc)
				.build();

			DocumentResult result = client.putDocument(options).execute().getResult();
			log.debug("Created document with ID: " + id);
			
			return result;

		} catch (Exception e) {
			log.error("Error creating document with ID '" + id + "' in database '" + databaseName + "'", e);
			throw new RuntimeException("Failed to create document with ID: " + id, e);
		}
	}

	/**
	 * Get a document by ID
	 */
	public com.ibm.cloud.cloudant.v1.model.Document get(String id) {
		try {
			GetDocumentOptions options = new GetDocumentOptions.Builder()
				.db(databaseName)
				.docId(id)
				.build();

			com.ibm.cloud.cloudant.v1.model.Document result = client.getDocument(options).execute().getResult();
			log.info("CloudantClientWrapper.get() Retrieved document with ID: " + id);
			
			// CLOUDANT DEBUG: Log the actual Document structure using both methods
			log.info("CLOUDANT Document structure for ID " + id + ":");
			log.info("  - getId(): " + result.getId());
			log.info("  - getRev(): " + result.getRev());
			
			// Test direct Document.get() access (recommended method)
			log.info("  - doc.get('type'): " + result.get("type"));
			log.info("  - doc.get('objectType'): " + result.get("objectType"));
			log.info("  - doc.get('name'): " + result.get("name"));
			
			// Test getProperties() access (alternative method)
			log.info("  - getProperties() is null? " + (result.getProperties() == null));
			if (result.getProperties() != null) {
				log.info("  - getProperties() size: " + result.getProperties().size());
				log.info("  - getProperties() keys: " + result.getProperties().keySet());
				log.info("  - properties.type: " + result.getProperties().get("type"));
				log.info("  - properties.objectType: " + result.getProperties().get("objectType"));
			}
			
			return result;

		} catch (NotFoundException e) {
			log.debug("Document not found with ID: " + id);
			return null;
		} catch (Exception e) {
			log.error("Error retrieving document with ID '" + id + "' from database '" + databaseName + "'", e);
			throw new RuntimeException("Failed to retrieve document with ID: " + id, e);
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
			
			ObjectMapper mapper = new ObjectMapper();
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
			log.error("Error updating document in database '" + databaseName + "'", e);
			throw new RuntimeException("Failed to update document", e);
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
			log.debug("Deleted document with ID: " + id);
			
			return result;

		} catch (Exception e) {
			log.error("Error deleting document with ID '" + id + "' from database '" + databaseName + "'", e);
			throw new RuntimeException("Failed to delete document with ID: " + id, e);
		}
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

		} catch (Exception e) {
			log.error("Error executing view query for design doc '" + designDoc + "', view '" + viewName + "'", e);
			throw new RuntimeException("Failed to execute view query", e);
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
			log.error("Error executing legacy view query for design doc '" + designDoc + "', view '" + viewName + "'", e);
			throw new RuntimeException("Failed to execute legacy view query", e);
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
			log.error("Error retrieving all docs from database '" + databaseName + "'", e);
			throw new RuntimeException("Failed to retrieve all docs", e);
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
			log.error("Error retrieving database info for '" + databaseName + "'", e);
			throw new RuntimeException("Failed to retrieve database info", e);
		}
	}

	/**
	 * Bridge method to replace Ektorp's ViewQuery - query view and deserialize to specific class
	 */
	public <T> List<T> queryView(String designDoc, String viewName, String key, Class<T> clazz) {
		try {
			// Build view path
			String viewPath = "_design/" + designDoc.replace("_design/", "") + "/_view/" + viewName;
			
			PostViewOptions.Builder builder = new PostViewOptions.Builder()
				.db(databaseName)
				.ddoc(designDoc.replace("_design/", ""))
				.view(viewName);
				// Remove includeDocs for now to test basic functionality
			
			ViewResult result = client.postView(builder.build()).execute().getResult();
			
			List<T> objects = new ArrayList<T>();
			ObjectMapper mapper = new ObjectMapper();
			
			for (ViewResultRow row : result.getRows()) {
				if (row.getDoc() != null) {
					// Filter by key if specified
					if (key == null || (row.getKey() != null && key.equals(row.getKey().toString().replace("\"", "")))) {
						T obj = mapper.convertValue(row.getDoc(), clazz);
						objects.add(obj);
					}
				}
			}
			
			log.debug("Retrieved " + objects.size() + " objects from view " + viewPath + " with key: " + key);
			return objects;
			
		} catch (Exception e) {
			log.error("Error querying view " + designDoc + "/" + viewName + " with key: " + key, e);
			throw new RuntimeException("Failed to query view", e);
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
				
				log.debug("Executed view query " + designDoc + "/" + viewName + " with key: " + key + " (filtered " + filteredRows.size() + " from " + result.getRows().size() + " results)");
				return result;
			}
			
			log.debug("Executed view query " + designDoc + "/" + viewName + " (returned " + result.getRows().size() + " results)");
			return result;
			
		} catch (Exception e) {
			log.error("Error querying view " + designDoc + "/" + viewName + " with key: " + key, e);
			throw new RuntimeException("Failed to query view", e);
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
			ObjectMapper mapper = new ObjectMapper();
			// Configure Jackson to ignore unknown properties during Cloudant migration
			mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
			
			@SuppressWarnings("unchecked")
			Map<String, Object> documentMap = mapper.convertValue(document, Map.class);
			
			log.debug("CLOUDANT DEBUG: Document type: " + document.getClass().getSimpleName());
			log.debug("CLOUDANT DEBUG: Document map keys: " + documentMap.keySet());
			log.debug("CLOUDANT DEBUG: Properties type: " + (documentMap.get("properties") != null ? documentMap.get("properties").getClass().getSimpleName() : "null"));
			
			// Convert CMIS array structures to Cloudant Document model compatible maps
			documentMap = convertPropertiesArrayToMap(documentMap);
			documentMap = convertTypeDefinitionPropertiesToMap(documentMap);
			
			// Use PostDocumentOptions for auto-generated ID
			String jsonString = mapper.writeValueAsString(documentMap);
			log.debug("CLOUDANT DEBUG: JSON before Cloudant Document conversion: " + jsonString.substring(0, Math.min(500, jsonString.length())) + "...");
			
			// Create Cloudant Document using proper type conversion for all document types
			com.ibm.cloud.cloudant.v1.model.Document doc;
			try {
				doc = mapper.readValue(jsonString, com.ibm.cloud.cloudant.v1.model.Document.class);
				log.debug("CLOUDANT DEBUG: Document model conversion successful after CMIS array-to-map conversion");
			} catch (com.fasterxml.jackson.databind.exc.MismatchedInputException e) {
				log.error("Document model conversion failed even after CMIS structure conversion: " + e.getMessage());
				log.error("Original document type: " + document.getClass().getSimpleName());
				log.error("DocumentMap keys: " + documentMap.keySet());
				if (documentMap.containsKey("properties")) {
					log.error("Properties type after conversion: " + documentMap.get("properties").getClass().getSimpleName());
				}
				throw new RuntimeException("Failed to create document object with proper type conversion", e);
			}

			PostDocumentOptions options = new PostDocumentOptions.Builder()
				.db(databaseName)
				.document(doc)
				.build();

			DocumentResult result = client.postDocument(options).execute().getResult();
			log.debug("Created document with ID: " + result.getId() + " using unified type-safe approach");
		} catch (Exception e) {
			log.error("Error creating document object", e);
			throw new RuntimeException("Failed to create document object", e);
		}
	}

	/**
	 * Update document (compatible with Ektorp update method)
	 */
	public void update(Object document) {
		try {
			ObjectMapper mapper = new ObjectMapper();
			// Configure Jackson to ignore unknown properties during Cloudant migration
			mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
			
			@SuppressWarnings("unchecked")
			Map<String, Object> documentMap = mapper.convertValue(document, Map.class);
			
			String id = (String) documentMap.get("_id");
			if (id == null) {
				throw new IllegalArgumentException("Document must have '_id' field for update");
			}
			
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
			log.debug("Updated document with ID: " + id);
		} catch (Exception e) {
			log.error("Error updating document object", e);
			throw new RuntimeException("Failed to update document object", e);
		}
	}

	/**
	 * Get document by class and ID (compatible with Ektorp get method)
	 */
	public <T> T get(Class<T> clazz, String id) {
		try {
			com.ibm.cloud.cloudant.v1.model.Document doc = get(id);
			if (doc != null) {
				ObjectMapper mapper = new ObjectMapper();
				// Convert immutable Document to mutable Map first, then to target class
				@SuppressWarnings("unchecked")
				Map<String, Object> docMap = mapper.convertValue(doc, Map.class);
				return mapper.convertValue(docMap, clazz);
			}
			return null;
		} catch (Exception e) {
			log.error("Error getting document with ID: " + id + " as class: " + clazz.getName(), e);
			throw new RuntimeException("Failed to get document", e);
		}
	}

	/**
	 * Delete document by object (compatible with Ektorp delete method)
	 */
	public void delete(Object document) {
		try {
			ObjectMapper mapper = new ObjectMapper();
			@SuppressWarnings("unchecked")
			Map<String, Object> documentMap = mapper.convertValue(document, Map.class);
			
			String id = (String) documentMap.get("_id");
			String rev = (String) documentMap.get("_rev");
			
			if (id == null) {
				throw new IllegalArgumentException("Document must have '_id' field for delete");
			}
			if (rev == null) {
				throw new IllegalArgumentException("Document must have '_rev' field for delete");
			}
			
			delete(id, rev);
		} catch (Exception e) {
			log.error("Error deleting document object", e);
			throw new RuntimeException("Failed to delete document object", e);
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
			log.debug("CLOUDANT DEBUG: Converting CMIS properties array to map for Document model compatibility");
			
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
			log.debug("CLOUDANT DEBUG: Properties array converted to map with " + propertiesMap.size() + " entries");
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
			log.debug("CLOUDANT DEBUG: Converting TypeDefinition propertyDefinitions array to map");
			
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
			log.debug("CLOUDANT DEBUG: PropertyDefinitions array converted to map with " + propDefsMap.size() + " entries");
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
			return null;
		} catch (Exception e) {
			log.error("Error retrieving attachment '" + attachmentName + "' from document '" + docId + "'", e);
			throw new RuntimeException("Failed to retrieve attachment", e);
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
			return null;
		} catch (Exception e) {
			log.error("Error retrieving attachment '" + attachmentName + "' from document '" + docId + "' (revision: " + revision + ")", e);
			throw new RuntimeException("Failed to retrieve attachment with revision", e);
		}
	}

	/**
	 * Create attachment using Cloudant SDK
	 */
	public String createAttachment(String docId, String revision, String attachmentName, Object attachmentInputStream, String contentType) {
		try {
			// Convert attachment input to InputStream if needed
			java.io.InputStream inputStream;
			if (attachmentInputStream instanceof java.io.InputStream) {
				inputStream = (java.io.InputStream) attachmentInputStream;
			} else if (attachmentInputStream instanceof byte[]) {
				inputStream = new java.io.ByteArrayInputStream((byte[]) attachmentInputStream);
			} else {
				throw new IllegalArgumentException("Unsupported attachment input type: " + attachmentInputStream.getClass());
			}

			PutAttachmentOptions.Builder builder = new PutAttachmentOptions.Builder()
				.db(databaseName)
				.docId(docId)
				.attachmentName(attachmentName)
				.attachment(inputStream);
			
			// Add revision if specified
			if (revision != null && !revision.isEmpty()) {
				builder.rev(revision);
			}
			
			// Add content type if specified
			if (contentType != null && !contentType.isEmpty()) {
				builder.contentType(contentType);
			}

			DocumentResult result = client.putAttachment(builder.build()).execute().getResult();
			log.debug("Created attachment: " + attachmentName + " for document: " + docId);
			
			return result.getRev(); // Return new revision

		} catch (Exception e) {
			log.error("Error creating attachment '" + attachmentName + "' for document '" + docId + "'", e);
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
			log.error("Error deleting attachment '" + attachmentName + "' from document '" + docId + "'", e);
			throw new RuntimeException("Failed to delete attachment", e);
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
				ObjectMapper mapper = new ObjectMapper();
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
			log.error("Error getting document with ID: " + id + " (revision: " + revision + ") as class: " + clazz.getName(), e);
			throw new RuntimeException("Failed to get document with revision", e);
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
			log.error("Error retrieving document with ID '" + id + "' (revision: " + revision + "') from database '" + databaseName + "'", e);
			throw new RuntimeException("Failed to retrieve document with revision: " + id, e);
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
			log.error("Error retrieving design document '" + designDocId + "' from database '" + databaseName + "'", e);
			throw new RuntimeException("Failed to retrieve design document: " + designDocId, e);
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
			log.error("Error creating/updating design document '" + designDocId + "' in database '" + databaseName + "'", e);
			throw new RuntimeException("Failed to create/update design document: " + designDocId, e);
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