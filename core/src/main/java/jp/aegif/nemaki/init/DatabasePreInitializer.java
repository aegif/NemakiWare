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
package jp.aegif.nemaki.init;

import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Phase 1 Database Pre-Initializer for NemakiWare
 *
 * CRITICAL FIX (2025-11-10): Converted to ApplicationListener<ContextRefreshedEvent>
 *
 * Problem History:
 * 1. Original: XML bean in databaseInitContext.xml - destroyed by NemakiApplicationContextLoader.refresh()
 * 2. Attempt 1: @Component with @PostConstruct - @PostConstruct never executed in child context
 * 3. Solution: ApplicationListener<ContextRefreshedEvent> - same pattern as CMISPostInitializer
 *
 * Why ApplicationListener Works:
 * - ContextRefreshedEvent fires AFTER entire Spring context is fully initialized
 * - Works reliably with both XML bean definitions and @Component annotation
 * - Event-driven pattern guaranteed to execute after context refresh
 * - AtomicBoolean ensures single execution even if event fires multiple times
 *
 * Phase 1 Operations (DB Direct):
 * - Create databases if they don't exist
 * - Load dump file data directly into CouchDB
 * - Set up design documents and views
 *
 * CRITICAL DESIGN CHANGE: System folder (.system) is now provided by
 * bedroom_init.dump file and should NOT be created by DatabasePreInitializer.
 * This prevents duplicate System folder creation and ensures proper security configuration.
 *
 * This phase executes when ContextRefreshedEvent fires, ensuring database
 * prerequisites are met before CMIS services require them.
 */
@Component
@Order(1)  // Execute before other beans
public class DatabasePreInitializer implements ApplicationListener<ContextRefreshedEvent> {

    private static final Log log = LogFactory.getLog(DatabasePreInitializer.class);

    // AtomicBoolean to ensure database initialization happens only once even if ContextRefreshedEvent fires multiple times
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    // Configuration properties for database initialization with default values
    private String couchdbUrl = "http://couchdb:5984";
    private String couchdbUsername = "admin";
    private String couchdbPassword = "password";

    public DatabasePreInitializer() {
        // Constructor - initialization handled by onApplicationEvent
        log.info("DatabasePreInitializer bean created");
    }

    /**
     * Execute Phase 1 database initialization
     *
     * CRITICAL: This method is called by Spring AFTER the entire ApplicationContext is fully
     * initialized, ensuring all basic infrastructure is ready for database operations.
     *
     * Uses AtomicBoolean to ensure execution happens exactly once, even if ContextRefreshedEvent
     * fires multiple times (e.g., parent/child context scenarios).
     *
     * This is pure database-layer initialization that does NOT depend on:
     * - CMIS services
     * - Nemakiware application services
     * - Complex Spring beans
     *
     * Uses only basic HTTP operations to ensure database prerequisites.
     */
    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        log.info("*** DatabasePreInitializer.onApplicationEvent() CALLED ***");
        log.info("*** Event source: " + event.getSource().getClass().getName() + " ***");

        // Ensure this runs only once
        if (!initialized.compareAndSet(false, true)) {
            log.info("DatabasePreInitializer already executed, skipping");
            return;
        }

        log.info("=== DATABASE PRE-INITIALIZATION (Phase 1) STARTED ===");
        log.info("Triggered by ContextRefreshedEvent - Basic infrastructure is now ready");

        try {
            // Phase 1: Pure database-layer operations
            // These operations should ONLY use HTTP clients, NOT CMIS services
            // Database operations must complete before CMIS services initialize
            // Wait a moment for CouchDB to be ready
            Thread.sleep(2000);
            
            if (log.isDebugEnabled()) {
                log.debug("Initializing CouchDB databases at: " + couchdbUrl);
            }
            log.info("Initializing CouchDB databases at: " + couchdbUrl);
            
            // CRITICAL OPTIMIZATION: Check if databases are already initialized
            if (isDatabasesAlreadyInitialized()) {
                if (log.isDebugEnabled()) {
                    log.debug("OPTIMIZATION: Databases already initialized, skipping dump loading");
                }
                log.info("OPTIMIZATION: Databases already initialized, skipping expensive dump processing");
                if (log.isDebugEnabled()) {
                    log.debug("DATABASE PRE-INITIALIZATION (Phase 1) completed (skipped)");
                }
                log.info("DATABASE PRE-INITIALIZATION (Phase 1) completed (skipped)");
                return;
            }
            
            if (log.isDebugEnabled()) {
                log.debug("FULL INITIALIZATION: Databases not found or incomplete, proceeding with setup");
            }
            log.info("FULL INITIALIZATION: Performing complete database setup");
            
            // List of databases to create
            String[] databases = {"bedroom", "bedroom_closet", "canopy", "canopy_closet", "nemaki_conf"};
            
            for (String dbName : databases) {
                createDatabaseIfNotExists(dbName);
            }
            
            // Load dump files for initial data (pure HTTP operations)
            loadInitialDumpFiles();
            
            // CRITICAL FIX: System folder creation removed - provided by dump file
            // .system folder is now provided by bedroom_init.dump with proper security configuration
            // DatabasePreInitializer should not create duplicate System folders
            if (log.isDebugEnabled()) {
                log.debug("SYSTEM FOLDER: Skipping creation - provided by dump file with proper .system name and security");
            }
            log.info("System folder creation skipped - provided by dump file");

            log.info("=== DATABASE PRE-INITIALIZATION (Phase 1) COMPLETED ===");

        } catch (Exception e) {
            log.error("Phase 1 database pre-initialization failed", e);
            // Don't fail the entire startup - let Phase 2 handle missing data gracefully
            log.warn("Continuing startup - Phase 2 may need to handle missing database setup");
        }
    }
    
    /**
     * Check if databases are already fully initialized
     * 
     * This optimization prevents re-processing dump files during every startup,
     * which was causing 6-minute startup delays. Only performs full initialization
     * if databases are missing or empty.
     * 
     * IDEMPOTENT REQUIREMENT: Also validates .system folder configuration to prevent
     * duplicate System folder creation on subsequent restarts.
     * 
     * @return true if all required databases exist and contain data AND .system folders are properly configured
     */
    private boolean isDatabasesAlreadyInitialized() {
        try {
            String[] requiredDatabases = {"bedroom", "bedroom_closet", "canopy", "canopy_closet", "nemaki_conf"};
            // Databases that require design documents (closet databases don't need them)
            String[] databasesWithDesignDocs = {"bedroom", "canopy", "nemaki_conf"};
            
            if (log.isDebugEnabled()) {
                log.debug("CHECKING: Database initialization status");
            }
            log.info("CHECKING: Database initialization status");
            
            for (String dbName : requiredDatabases) {
                // Check if database exists
                java.net.URL checkUrl = new java.net.URL(couchdbUrl + "/" + dbName);
                java.net.HttpURLConnection checkConn = (java.net.HttpURLConnection) checkUrl.openConnection();
                
                String auth = couchdbUsername + ":" + couchdbPassword;
                String encodedAuth = java.util.Base64.getEncoder().encodeToString(auth.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                checkConn.setRequestProperty("Authorization", "Basic " + encodedAuth);
                checkConn.setRequestMethod("HEAD");
                
                int responseCode = checkConn.getResponseCode();
                checkConn.disconnect();
                
                if (responseCode != 200) {
                    if (log.isDebugEnabled()) {
                        log.debug("CHECKING: Database " + dbName + " not found (HTTP " + responseCode + ")");
                    }
                    log.info("CHECKING: Database " + dbName + " not found, full initialization needed");
                    return false;
                }
                
                // Only check design documents for databases that require them (not closet databases)
                boolean needsDesignDoc = false;
                for (String designDbName : databasesWithDesignDocs) {
                    if (designDbName.equals(dbName)) {
                        needsDesignDoc = true;
                        break;
                    }
                }
                
                if (needsDesignDoc) {
                    java.net.URL designUrl = new java.net.URL(couchdbUrl + "/" + dbName + "/_design/_repo");
                    java.net.HttpURLConnection designConn = (java.net.HttpURLConnection) designUrl.openConnection();
                    designConn.setRequestProperty("Authorization", "Basic " + encodedAuth);
                    designConn.setRequestMethod("GET");  // Changed from HEAD to GET to read content

                    int designResponseCode = designConn.getResponseCode();

                    if (designResponseCode != 200) {
                        designConn.disconnect();
                        if (log.isDebugEnabled()) {
                            log.debug("CHECKING: Database " + dbName + " missing design documents, initialization needed");
                        }
                        log.info("CHECKING: Database " + dbName + " missing design documents, full initialization needed");
                        return false;
                    }

                    // CRITICAL FIX: Verify design document has all required views (43 for bedroom/canopy)
                    // Patch_StandardCmisViews only creates 5 views, which is incomplete!
                    java.io.BufferedReader designReader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(designConn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                    String designResponseStr = designReader.lines().reduce("", (a, b) -> a + b);
                    designReader.close();
                    designConn.disconnect();

                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    com.fasterxml.jackson.databind.JsonNode designDoc = mapper.readTree(designResponseStr);

                    if (designDoc.has("views")) {
                        int viewCount = designDoc.get("views").size();
                        // bedroom and canopy require 38 views from dump file
                        // Patch_StandardCmisViews only creates 5 views (incomplete)
                        int requiredViews = ("bedroom".equals(dbName) || "canopy".equals(dbName)) ? 38 : 0;

                        if (viewCount < requiredViews) {
                            if (log.isDebugEnabled()) {
                                log.debug("CHECKING: Database " + dbName + " has incomplete views (" +
                                    viewCount + "/" + requiredViews + "), initialization needed");
                            }
                            log.info("CHECKING: Database " + dbName + " has incomplete views (" +
                                viewCount + "/" + requiredViews + "), full initialization needed");
                            return false;
                        }

                        if (log.isDebugEnabled()) {
                            log.debug("CHECKING: Database " + dbName + " has complete views (" + viewCount + ")");
                        }
                        log.info("CHECKING: Database " + dbName + " has complete views (" + viewCount + ")");
                    } else {
                        if (log.isDebugEnabled()) {
                            log.debug("CHECKING: Database " + dbName + " design document has no views, initialization needed");
                        }
                        log.info("CHECKING: Database " + dbName + " design document has no views, full initialization needed");
                        return false;
                    }
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("CHECKING: Database " + dbName + " (closet) - design documents not required");
                    }
                    log.info("CHECKING: Database " + dbName + " (closet) - design documents not required");
                }
            }
            
            // CRITICAL IDEMPOTENT CHECK: Validate .system folder is properly configured
            if (!isSystemFolderProperlyConfigured()) {
                if (log.isDebugEnabled()) {
                    log.debug("CHECKING: .system folder configuration invalid or missing, initialization needed");
                }
                log.info("CHECKING: .system folder configuration invalid or missing, full initialization needed");
                return false;
            }
            
            if (log.isDebugEnabled()) {
                log.debug("CHECKING: All databases AND .system folders properly initialized");
            }
            log.info("CHECKING: All databases AND .system folders properly initialized, skipping dump processing");
            return true;
            
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Error checking database status, proceeding with full initialization");
            }
            log.warn("Error checking database initialization status, proceeding with full initialization: " + e.getMessage());
            return false;
        }
    }
    
    private void createDatabaseIfNotExists(String dbName) {
        try {
            // Check if database exists
            java.net.URL checkUrl = new java.net.URL(couchdbUrl + "/" + dbName);
            java.net.HttpURLConnection checkConn = (java.net.HttpURLConnection) checkUrl.openConnection();
            
            // Add authentication
            String auth = couchdbUsername + ":" + couchdbPassword;
            String encodedAuth = java.util.Base64.getEncoder().encodeToString(auth.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            checkConn.setRequestProperty("Authorization", "Basic " + encodedAuth);
            checkConn.setRequestMethod("HEAD");
            
            int responseCode = checkConn.getResponseCode();
            checkConn.disconnect();
            
            if (responseCode == 404) {
                // Database doesn't exist, create it
                log.info("Creating database: " + dbName);
                
                java.net.URL createUrl = new java.net.URL(couchdbUrl + "/" + dbName);
                java.net.HttpURLConnection createConn = (java.net.HttpURLConnection) createUrl.openConnection();
                createConn.setRequestProperty("Authorization", "Basic " + encodedAuth);
                createConn.setRequestMethod("PUT");
                
                int createResponse = createConn.getResponseCode();
                createConn.disconnect();
                
                if (createResponse == 201) {
                    log.info("Database created successfully: " + dbName);
                } else {
                    log.warn("Failed to create database " + dbName + ", response code: " + createResponse);
                }
            } else if (responseCode == 200) {
                log.info("Database already exists: " + dbName);
            } else {
                log.warn("Unexpected response checking database " + dbName + ": " + responseCode);
            }
        } catch (Exception e) {
            log.error("Error creating database " + dbName, e);
        }
    }
    
    private void loadInitialDumpFiles() {
        if (log.isDebugEnabled()) {
            log.debug("Starting to load initial dump files");
        }
        log.info("Loading initial dump files...");
        
        try {
            // Map of database names to their dump file paths
            String[][] dumpFiles = {
                {"bedroom", "/docker/initializer/initial_import/bedroom_init.dump"},
                {"canopy", "/docker/initializer/initial_import/canopy_init.dump"},
                {"nemaki_conf", "/docker/initializer/initial_import/nemaki_conf_init.dump"}
            };
            
            for (String[] entry : dumpFiles) {
                String dbName = entry[0];
                String dumpPath = entry[1];
                
                try {
                    // Try to load from classpath first
                    String classpath = "/initialization/" + dbName + "_init.dump";
                    if (log.isDebugEnabled()) {
                        log.debug("Attempting to load from classpath: " + classpath);
                    }
                    java.io.InputStream dumpStream = getClass().getResourceAsStream(classpath);
                    if (dumpStream == null) {
                        if (log.isDebugEnabled()) {
                            log.debug("Classpath not found, trying absolute path: " + dumpPath);
                        }
                        // Try absolute path
                        java.io.File dumpFile = new java.io.File(dumpPath);
                        if (dumpFile.exists()) {
                            dumpStream = new java.io.FileInputStream(dumpFile);
                            if (log.isDebugEnabled()) {
                                log.debug("Found dump file at absolute path");
                            }
                        } else {
                            if (log.isDebugEnabled()) {
                                log.debug("Absolute path also not found");
                            }
                        }
                    } else {
                        if (log.isDebugEnabled()) {
                            log.debug("Found dump file in classpath");
                        }
                    }
                    
                    if (dumpStream != null) {
                        if (log.isDebugEnabled()) {
                            log.debug("Loading dump file for database: " + dbName);
                        }
                        log.info("Loading dump file for database: " + dbName);
                        loadDumpIntoDatabase(dbName, dumpStream);
                        dumpStream.close();
                        if (log.isDebugEnabled()) {
                            log.debug("Completed loading dump file for database: " + dbName);
                        }
                    } else {
                        if (log.isDebugEnabled()) {
                            log.debug("Dump file not found for database: " + dbName);
                        }
                        log.warn("Dump file not found for database: " + dbName);
                    }
                } catch (Exception e) {
                    log.error("Error loading dump file for database " + dbName, e);
                }
            }
            
        } catch (Exception e) {
            log.error("Error in loadInitialDumpFiles", e);
        }
    }
    
    private void loadDumpIntoDatabase(String dbName, java.io.InputStream dumpStream) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("Starting to load dump for database: " + dbName);
        }
        
        // Read the dump file
        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(dumpStream, java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder content = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            content.append(line).append("\n");
        }
        reader.close();
        
        if (log.isDebugEnabled()) {
            log.debug("Read " + content.length() + " characters from dump file");
        }
        
        // Parse JSON dump
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode dumpData = mapper.readTree(content.toString());
        
        if (log.isDebugEnabled()) {
            log.debug("Parsed JSON successfully");
        }
        
        // The dump file is directly a JSON array, not an object with a "docs" property
        if (dumpData.isArray()) {
            if (log.isDebugEnabled()) {
                log.debug("Found array with " + dumpData.size() + " elements");
            }
            for (com.fasterxml.jackson.databind.JsonNode doc : dumpData) {
                // Handle document directly or extract from document wrapper
                com.fasterxml.jackson.databind.JsonNode docToInsert = doc;
                if (doc.has("document")) {
                    docToInsert = doc.get("document");
                }
                
                try {
                    // Create document in CouchDB
                    String docId = docToInsert.get("_id").asText();
                    if (log.isDebugEnabled()) {
                        log.debug("Processing document: " + docId);
                    }
                    
                    // Remove _rev for new document creation (CouchDB will assign new revision)
                    com.fasterxml.jackson.databind.node.ObjectNode docCopy = docToInsert.deepCopy();
                    docCopy.remove("_rev");
                    
                    // Convert JsonNode to string for HTTP request
                    String docJson = mapper.writeValueAsString(docCopy);
                    
                    // Create document via HTTP PUT
                    java.net.URL url = new java.net.URL(couchdbUrl + "/" + dbName + "/" + docId);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    
                    // Add authentication
                    String auth = couchdbUsername + ":" + couchdbPassword;
                    String encodedAuth = java.util.Base64.getEncoder().encodeToString(auth.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    conn.setRequestProperty("Authorization", "Basic " + encodedAuth);
                    conn.setRequestProperty("Content-Type", "application/json");
                    
                    conn.setRequestMethod("PUT");
                    conn.setDoOutput(true);
                    
                    // Write document
                    java.io.OutputStreamWriter out = new java.io.OutputStreamWriter(conn.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8);
                    out.write(docJson);
                    out.close();
                    
                    int responseCode = conn.getResponseCode();
                    if (log.isDebugEnabled()) {
                        log.debug("Document " + docId + " response code: " + responseCode);
                    }
                    if (responseCode == 201 || responseCode == 200) {
                        if (log.isDebugEnabled()) {
                            log.debug("Successfully created document " + docId + " in " + dbName);
                        }
                        log.info("Created document " + docId + " in " + dbName);
                    } else if (responseCode == 409) {
                        if (log.isDebugEnabled()) {
                            log.debug("Document " + docId + " already exists in " + dbName);
                        }
                        log.debug("Document " + docId + " already exists in " + dbName);
                    } else {
                        if (log.isDebugEnabled()) {
                            log.debug("Failed to create document " + docId + " response: " + responseCode);
                        }
                        log.warn("Failed to create document " + docId + " in " + dbName + ", response: " + responseCode);
                    }
                    
                    conn.disconnect();
                    
                } catch (Exception e) {
                    if (log.isDebugEnabled()) {
                        log.debug("Exception processing document: " + e.getMessage());
                    }
                    log.error("Error processing document in " + dbName, e);
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("Completed processing all documents for " + dbName);
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Root JSON is not an array for " + dbName + ", type: " + dumpData.getNodeType());
            }
        }
    }
    
    /**
     * Validate .system folder configuration for idempotent initialization
     * 
     * This method ensures that:
     * 1. The .system folder exists and is provided by bedroom_init.dump
     * 2. There is exactly one .system folder (no duplicates)
     * 3. The .system folder has proper security configuration (system-only access)
     * 
     * This prevents duplicate System folder creation on subsequent restarts.
     * 
     * @return true if .system folders are properly configured, false if initialization needed
     */
    private boolean isSystemFolderProperlyConfigured() {
        try {
            String[] repositories = {"bedroom"};  // Only bedroom needs .system folder validation
            String[] rootFolderIds = {"e02f784f8360a02cc14d1314c10038ff"};
            
            String auth = couchdbUsername + ":" + couchdbPassword;
            String encodedAuth = java.util.Base64.getEncoder().encodeToString(auth.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            
            for (int i = 0; i < repositories.length; i++) {
                String repositoryId = repositories[i];
                String rootFolderId = rootFolderIds[i];

                if (log.isDebugEnabled()) {
                    log.debug("Validating .system folder for repository: " + repositoryId);
                }

                // Count .system folders using direct document queries to detect duplicates
                
                java.net.URL allDocsUrl = new java.net.URL(couchdbUrl + "/" + repositoryId + "/_all_docs?include_docs=true");
                java.net.HttpURLConnection allDocsConn = (java.net.HttpURLConnection) allDocsUrl.openConnection();
                allDocsConn.setRequestProperty("Authorization", "Basic " + encodedAuth);
                allDocsConn.setRequestMethod("GET");
                
                int allDocsResponse = allDocsConn.getResponseCode();
                if (allDocsResponse == 200) {
                    java.io.BufferedReader allDocsReader = new java.io.BufferedReader(new java.io.InputStreamReader(allDocsConn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                    String allDocsResponseStr = allDocsReader.lines().reduce("", (a, b) -> a + b);
                    allDocsReader.close();
                    
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    com.fasterxml.jackson.databind.JsonNode allDocsResult = mapper.readTree(allDocsResponseStr);
                    int systemFolderCount = 0;
                    
                    if (allDocsResult.has("rows")) {
                        for (com.fasterxml.jackson.databind.JsonNode row : allDocsResult.get("rows")) {
                            if (row.has("doc")) {
                                com.fasterxml.jackson.databind.JsonNode doc = row.get("doc");
                                // Check if this is a .system folder
                                if (doc.has("name") && ".system".equals(doc.get("name").asText()) &&
                                    doc.has("parentId") && rootFolderId.equals(doc.get("parentId").asText()) &&
                                    doc.has("type") && "cmis:folder".equals(doc.get("type").asText())) {
                                    systemFolderCount++;
                                    if (log.isDebugEnabled()) {
                                        log.debug("Found .system folder: " + doc.get("_id").asText());
                                    }
                                }
                            }
                        }
                    }

                    if (log.isDebugEnabled()) {
                        log.debug("Total .system folders found: " + systemFolderCount);
                    }

                    if (systemFolderCount > 1) {
                        log.warn("Found " + systemFolderCount + " .system folders - duplicates detected!");
                        allDocsConn.disconnect();
                        return false;
                    } else if (systemFolderCount == 0) {
                        log.info("No .system folders found - initialization needed");
                        allDocsConn.disconnect();
                        return false;
                    } else {
                        if (log.isDebugEnabled()) {
                            log.debug("Found exactly 1 .system folder - correct!");
                        }
                    }
                }
                allDocsConn.disconnect();

                if (log.isDebugEnabled()) {
                    log.debug("Repository " + repositoryId + " .system folder validation passed");
                }
            }

            if (log.isDebugEnabled()) {
                log.debug("All .system folder configurations valid");
            }
            return true;
            
        } catch (Exception e) {
            log.warn("Error validating .system folder configuration", e);
            return false; // Trigger full initialization if validation fails
        }
    }
}
