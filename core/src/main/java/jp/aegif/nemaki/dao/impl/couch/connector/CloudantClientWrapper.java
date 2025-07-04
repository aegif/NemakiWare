package jp.aegif.nemaki.dao.impl.couch.connector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

	private static final Logger log = LoggerFactory.getLogger(CloudantClientWrapper.class);

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
			// CORRECT APPROACH: Remove null _id and _rev from new document creation
			// CouchDB should generate ID automatically when _id is not provided
			if (document.get("_id") == null) {
				document.remove("_id");
			}
			if (document.get("_rev") == null) {
				document.remove("_rev");
			}
			
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
			// CORRECT APPROACH: Remove null _id and _rev for consistency
			// Even with provided ID, ensure no null values are sent
			if (document.get("_id") == null) {
				document.remove("_id");
			}
			if (document.get("_rev") == null) {
				document.remove("_rev");
			}
			
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
			log.debug("Retrieved document with ID: " + id);
			
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
		log.error("CLOUDANT CREATE ENTRY: Starting create() method for: " + 
			(document != null ? document.getClass().getSimpleName() : "null"));
		try {
			log.error("CLOUDANT CREATE DEBUG: Creating document of type: " + document.getClass().getSimpleName());
			
			// Add comprehensive debug logging for CouchFolder objects
			if (document instanceof jp.aegif.nemaki.model.couch.CouchFolder) {
				jp.aegif.nemaki.model.couch.CouchFolder folder = (jp.aegif.nemaki.model.couch.CouchFolder) document;
				log.error("CLOUDANT CREATE: CouchFolder before mapping - DETAILED ANALYSIS");
				log.error("  - ID: " + folder.getId());
				log.error("  - Revision: " + folder.getRevision());
				log.error("  - Name: " + folder.getName());
				log.error("  - ObjectType: " + folder.getObjectType());
				log.error("  - Type: " + folder.getType());
				log.error("  - ParentId: " + folder.getParentId());
				log.error("  - Description: " + folder.getDescription());
				log.error("  - AclInherited: " + folder.isAclInherited());
				
				// Check class hierarchy
				log.error("CLOUDANT CREATE: Class hierarchy analysis:");
				Class<?> clazz = folder.getClass();
				while (clazz != null) {
					log.error("  - Class: " + clazz.getName());
					clazz = clazz.getSuperclass();
				}
				
				// Check available methods (getters)
				log.error("CLOUDANT CREATE: Available getter methods:");
				java.lang.reflect.Method[] methods = folder.getClass().getMethods();
				for (java.lang.reflect.Method method : methods) {
					if (method.getName().startsWith("get") && method.getParameterCount() == 0) {
						try {
							Object value = method.invoke(folder);
							log.error("  - " + method.getName() + "(): " + value);
						} catch (Exception e) {
							log.error("  - " + method.getName() + "(): ERROR - " + e.getMessage());
						}
					}
				}
			}
			
			log.error("CLOUDANT CREATE: About to create ObjectMapper");
			ObjectMapper mapper = new ObjectMapper();
			// Configure Jackson to ignore unknown properties during Cloudant migration
			mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
			
			// Log ObjectMapper configuration
			log.error("CLOUDANT CREATE: ObjectMapper configuration:");
			log.error("  - Visibility Checker: " + mapper.getVisibilityChecker());
			log.error("  - PropertyNamingStrategy: " + mapper.getPropertyNamingStrategy());
			log.error("  - SerializationConfig: " + mapper.getSerializationConfig().toString());
			
			log.error("CLOUDANT CREATE: About to call mapper.convertValue");
			@SuppressWarnings("unchecked")
			Map<String, Object> documentMap = mapper.convertValue(document, Map.class);
			
			log.error("CLOUDANT CREATE: After ObjectMapper.convertValue - DETAILED ANALYSIS");
			log.error("  - Map size: " + documentMap.size());
			log.error("  - Map is empty: " + documentMap.isEmpty());
			
			// Log all key-value pairs in the converted map
			log.error("CLOUDANT CREATE: All key-value pairs in converted map:");
			if (documentMap.isEmpty()) {
				log.error("  - MAP IS EMPTY!");
			} else {
				for (Map.Entry<String, Object> entry : documentMap.entrySet()) {
					Object value = entry.getValue();
					String valueStr = (value != null) ? value.toString() : "null";
					log.error("  - " + entry.getKey() + " = " + valueStr + " (type: " + 
						(value != null ? value.getClass().getSimpleName() : "null") + ")");
				}
			}
			
			// Check specific expected properties
			log.error("CLOUDANT CREATE: Checking specific expected properties:");
			log.error("  - objectType: " + documentMap.get("objectType"));
			log.error("  - name: " + documentMap.get("name"));
			log.error("  - type: " + documentMap.get("type"));
			log.error("  - id: " + documentMap.get("id"));
			log.error("  - _id: " + documentMap.get("_id"));
			log.error("  - revision: " + documentMap.get("revision"));
			log.error("  - _rev: " + documentMap.get("_rev"));
			
			// Convert CMIS array structures to Cloudant Document model compatible maps
			log.error("CLOUDANT CREATE: About to call convertPropertiesArrayToMap");
			documentMap = convertPropertiesArrayToMap(documentMap);
			log.error("CLOUDANT CREATE: After convertPropertiesArrayToMap - map size: " + documentMap.size());
			
			log.error("CLOUDANT CREATE: About to call convertTypeDefinitionPropertiesToMap");
			documentMap = convertTypeDefinitionPropertiesToMap(documentMap);
			log.error("CLOUDANT CREATE: After convertTypeDefinitionPropertiesToMap - map size: " + documentMap.size());
			
			// Log map contents after property conversion
			log.error("CLOUDANT CREATE: After property conversion - DETAILED ANALYSIS");
			if (documentMap.isEmpty()) {
				log.error("  - MAP IS STILL EMPTY AFTER CONVERSION!");
			} else {
				log.error("  - Map size after conversion: " + documentMap.size());
				for (Map.Entry<String, Object> entry : documentMap.entrySet()) {
					Object value = entry.getValue();
					String valueStr = (value != null) ? value.toString() : "null";
					log.error("  - " + entry.getKey() + " = " + valueStr);
				}
			}
			
			// CORRECT APPROACH: Remove null _id and _rev from new document creation
			// CouchDB should generate ID automatically when _id is not provided
			if (documentMap.get("_id") == null) {
				documentMap.remove("_id");
				log.error("CLOUDANT CREATE: Removed null _id - CouchDB will auto-generate ID");
			} else {
				log.error("CLOUDANT CREATE: Using existing _id: " + documentMap.get("_id"));
			}
			
			if (documentMap.get("_rev") == null) {
				documentMap.remove("_rev");
				log.error("CLOUDANT CREATE: Removed null _rev - new document doesn't need revision");
			} else {
				log.error("CLOUDANT CREATE: Using existing _rev: " + documentMap.get("_rev"));
			}
			
			// Use PostDocumentOptions for auto-generated ID
			log.error("CLOUDANT CREATE: About to serialize to JSON string");
			String jsonString = mapper.writeValueAsString(documentMap);
			
			log.error("CLOUDANT CREATE: JSON serialization results:");
			log.error("  - JSON string length: " + jsonString.length());
			log.error("  - JSON string content: " + jsonString);
			
			// Verify JSON is not empty
			if (jsonString.equals("{}") || jsonString.equals("null")) {
				log.error("CLOUDANT CREATE: WARNING - JSON string is empty or null!");
			}
			
			// Create Cloudant Document using proper type conversion for all document types
			com.ibm.cloud.cloudant.v1.model.Document doc;
			try {
				doc = mapper.readValue(jsonString, com.ibm.cloud.cloudant.v1.model.Document.class);
			} catch (com.fasterxml.jackson.databind.exc.MismatchedInputException e) {
				log.error("Document model conversion failed: " + e.getMessage());
				throw new RuntimeException("Failed to create document object", e);
			}

			PostDocumentOptions options = new PostDocumentOptions.Builder()
				.db(databaseName)
				.document(doc)
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
			ObjectMapper mapper = new ObjectMapper();
			// Configure Jackson to ignore unknown properties during Cloudant migration
			mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
			
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
			log.error("Revision conflict in Ektorp-style update - object revision was stale or concurrent modification occurred");
			throw new RuntimeException("Revision conflict: the object's revision is outdated. " +
				"This indicates concurrent modification or stale object state.", e);
		} catch (Exception e) {
			log.error("Error in Ektorp-style document update", e);
			throw new RuntimeException("Failed to update document in Ektorp-style operation", e);
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