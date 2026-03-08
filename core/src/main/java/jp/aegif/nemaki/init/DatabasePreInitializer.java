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

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired(required = false)
    private StartupProbeService startupProbeService;

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

        // CRITICAL: If Setup Wizard is active, defer database initialization.
        // The Setup Wizard will create databases via the apply endpoint instead.
        try {
            StartupProbeService probeService = event.getApplicationContext().getBean(StartupProbeService.class);
            if (probeService != null && probeService.isSetupRequired()) {
                log.info("=== DATABASE PRE-INITIALIZATION DEFERRED: Setup Wizard is active ===");
                log.info("Databases will be created by Setup Wizard apply flow");
                return;
            }
        } catch (Exception e) {
            log.debug("StartupProbeService not available, proceeding with normal initialization: " + e.getMessage());
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
                // Re-evaluate startup state (DB is confirmed ready)
                reprobeStartupState(event);
                return;
            }
            
            if (log.isDebugEnabled()) {
                log.debug("FULL INITIALIZATION: Databases not found or incomplete, proceeding with setup");
            }
            log.info("FULL INITIALIZATION: Performing complete database setup");
            
            // List of databases to create (dynamically resolved)
            List<String> databases = getRepositoryDbNames();
            log.info("Databases to initialize: " + databases);

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

            // Re-evaluate startup state after DB initialization
            reprobeStartupState(event);

        } catch (Exception e) {
            log.error("Phase 1 database pre-initialization failed", e);
            // Don't fail the entire startup - let Phase 2 handle missing data gracefully
            log.warn("Continuing startup - Phase 2 may need to handle missing database setup");
            // Still reprobe — partial init may have changed the state
            reprobeStartupState(event);
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
            List<String> requiredDatabases = getRepositoryDbNames();
            
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
                
                // Check design documents for all databases
                {
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

                    // Verify design document has all required views
                    java.io.BufferedReader designReader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(designConn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                    String designResponseStr = designReader.lines().reduce("", (a, b) -> a + b);
                    designReader.close();
                    designConn.disconnect();

                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    com.fasterxml.jackson.databind.JsonNode designDoc = mapper.readTree(designResponseStr);

                    if (designDoc.has("views")) {
                        int viewCount = designDoc.get("views").size();
                        // Main repos require 38 views, closet repos require 8 archive views
                        int requiredViews;
                        boolean isMain = startupProbeService != null
                                ? startupProbeService.isMainRepository(dbName)
                                : (!"nemaki_conf".equals(dbName) && !dbName.endsWith("_closet"));
                        if ("nemaki_conf".equals(dbName)) {
                            requiredViews = 0;
                        } else if (isMain) {
                            requiredViews = StartupProbeService.REQUIRED_VIEWS_MAIN;
                        } else {
                            requiredViews = StartupProbeService.REQUIRED_VIEWS_CLOSET;
                        }

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
            List<String> databases = getRepositoryDbNames();

            for (String dbName : databases) {
                try {
                    // Determine dump file name:
                    //   archive DBs → archive_init.dump
                    //   nemaki_conf → nemaki_conf_init.dump
                    //   main repos → {dbName}_init.dump, with bedroom_init.dump as fallback
                    boolean isMain = startupProbeService != null
                            ? startupProbeService.isMainRepository(dbName)
                            : (!"nemaki_conf".equals(dbName) && !dbName.endsWith("_closet"));
                    String classpathFile;
                    if ("nemaki_conf".equals(dbName)) {
                        classpathFile = "nemaki_conf_init.dump";
                    } else if (isMain) {
                        classpathFile = dbName + "_init.dump";
                    } else {
                        classpathFile = "archive_init.dump";
                    }

                    String classpath = "/initialization/" + classpathFile;
                    if (log.isDebugEnabled()) {
                        log.debug("Attempting to load from classpath: " + classpath);
                    }
                    java.io.InputStream dumpStream = getClass().getResourceAsStream(classpath);

                    // Fallback: for main repos with no specific dump, use bedroom_init.dump as template
                    if (dumpStream == null && isMain) {
                        String fallback = "/initialization/bedroom_init.dump";
                        log.info("Dump file not found for " + dbName + ", trying fallback: " + fallback);
                        dumpStream = getClass().getResourceAsStream(fallback);
                    }

                    // Final fallback: try absolute Docker path
                    if (dumpStream == null) {
                        String dumpPath = "/docker/initializer/initial_import/" + classpathFile;
                        java.io.File dumpFile = new java.io.File(dumpPath);
                        if (dumpFile.exists()) {
                            dumpStream = new java.io.FileInputStream(dumpFile);
                            if (log.isDebugEnabled()) {
                                log.debug("Found dump file at absolute path: " + dumpPath);
                            }
                        }
                    }

                    if (dumpStream != null) {
                        log.info("Loading dump file for database: " + dbName);
                        loadDumpIntoDatabase(dbName, dumpStream);
                        dumpStream.close();
                        if (log.isDebugEnabled()) {
                            log.debug("Completed loading dump file for database: " + dbName);
                        }
                    } else {
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
        
        // Auth header for all requests
        String auth = couchdbUsername + ":" + couchdbPassword;
        String encodedAuth = java.util.Base64.getEncoder().encodeToString(auth.getBytes(java.nio.charset.StandardCharsets.UTF_8));

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
                    
                    int responseCode = putDocument(dbName, docId, mapper.writeValueAsString(docCopy), encodedAuth);

                    if (responseCode == 201 || responseCode == 200) {
                        log.info("Created document " + docId + " in " + dbName);
                    } else if (responseCode == 409) {
                        // Document already exists.
                        // For design documents (_design/*), merge views from the dump
                        // into the existing document. This preserves custom views added
                        // by users or patches while ensuring required views are current.
                        // For regular documents, skip silently (data is already present).
                        if (docId.startsWith("_design/")) {
                            log.info("Design document " + docId + " exists in " + dbName + ", merging views");
                            mergeDesignDocument(dbName, docId, docCopy, mapper, encodedAuth);
                        } else {
                            if (log.isDebugEnabled()) {
                                log.debug("Document " + docId + " already exists in " + dbName);
                            }
                        }
                    } else {
                        log.warn("Failed to create document " + docId + " in " + dbName + ", response: " + responseCode);
                    }
                    
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
     * PUT a document to CouchDB and return the HTTP response code.
     */
    private int putDocument(String dbName, String docId, String docJson, String encodedAuth) throws Exception {
        java.net.URL url = new java.net.URL(couchdbUrl + "/" + dbName + "/" + docId);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestProperty("Authorization", "Basic " + encodedAuth);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestMethod("PUT");
        conn.setDoOutput(true);

        java.io.OutputStreamWriter out = new java.io.OutputStreamWriter(conn.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8);
        out.write(docJson);
        out.close();

        int responseCode = conn.getResponseCode();
        conn.disconnect();
        return responseCode;
    }

    /**
     * Merge views from the dump design document into the existing design document.
     * Preserves custom views that exist in the DB but not in the dump file,
     * while ensuring all dump views are up-to-date (dump views win on conflict).
     */
    private void mergeDesignDocument(String dbName, String docId,
            com.fasterxml.jackson.databind.node.ObjectNode dumpDoc,
            com.fasterxml.jackson.databind.ObjectMapper mapper,
            String encodedAuth) {
        try {
            // Fetch the existing design document (full body, not just HEAD)
            java.net.URL url = new java.net.URL(couchdbUrl + "/" + dbName + "/" + docId);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestProperty("Authorization", "Basic " + encodedAuth);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                log.warn("Could not fetch existing design document " + docId + " in " + dbName + " (HTTP " + code + ")");
                return;
            }

            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            String existingBody = reader.lines().reduce("", (a, b) -> a + b);
            reader.close();
            conn.disconnect();

            com.fasterxml.jackson.databind.JsonNode existingDoc = mapper.readTree(existingBody);
            String currentRev = existingDoc.has("_rev") ? existingDoc.get("_rev").asText() : null;
            if (currentRev == null) {
                log.warn("Existing design document " + docId + " has no _rev, cannot update");
                return;
            }

            // Start with the existing doc as base, then overlay dump fields
            com.fasterxml.jackson.databind.node.ObjectNode merged = existingDoc.deepCopy();

            // Merge "views": existing views are preserved, dump views override by name
            if (dumpDoc.has("views") && dumpDoc.get("views").isObject()) {
                com.fasterxml.jackson.databind.node.ObjectNode mergedViews;
                if (merged.has("views") && merged.get("views").isObject()) {
                    mergedViews = (com.fasterxml.jackson.databind.node.ObjectNode) merged.get("views");
                } else {
                    mergedViews = mapper.createObjectNode();
                    merged.set("views", mergedViews);
                }
                // Overlay dump views (dump wins on conflict)
                com.fasterxml.jackson.databind.JsonNode dumpViews = dumpDoc.get("views");
                var fieldNames = dumpViews.fieldNames();
                while (fieldNames.hasNext()) {
                    String viewName = fieldNames.next();
                    mergedViews.set(viewName, dumpViews.get(viewName).deepCopy());
                }
            }

            // Copy non-view, non-metadata fields from dump (e.g. language, filters)
            var dumpFields = dumpDoc.fieldNames();
            while (dumpFields.hasNext()) {
                String field = dumpFields.next();
                if ("_id".equals(field) || "_rev".equals(field) || "views".equals(field)) {
                    continue; // already handled
                }
                merged.set(field, dumpDoc.get(field).deepCopy());
            }

            int updateCode = putDocument(dbName, docId, mapper.writeValueAsString(merged), encodedAuth);
            if (updateCode == 201 || updateCode == 200) {
                int viewCount = merged.has("views") ? merged.get("views").size() : 0;
                log.info("Merged design document " + docId + " in " + dbName + " (" + viewCount + " views)");
            } else {
                log.warn("Failed to merge design document " + docId + " in " + dbName + ", response: " + updateCode);
            }
        } catch (Exception e) {
            log.error("Error merging design document " + docId + " in " + dbName, e);
        }
    }

    /**
     * Validate .system folder configuration for idempotent initialization.
     *
     * Checks all main repositories (dynamically discovered) for .system folder presence.
     * Uses CouchDB Mango query instead of _all_docs to avoid loading all documents.
     *
     * @return true if .system folders are properly configured, false if initialization needed
     */
    private boolean isSystemFolderProperlyConfigured() {
        try {
            String auth = couchdbUsername + ":" + couchdbPassword;
            String encodedAuth = java.util.Base64.getEncoder().encodeToString(auth.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // Check all main repositories (using authoritative source from repositories.yml)
            List<String> mainRepos = getRepositoryDbNames().stream()
                    .filter(db -> startupProbeService != null
                            ? startupProbeService.isMainRepository(db)
                            : (!"nemaki_conf".equals(db) && !db.endsWith("_closet")))
                    .toList();

            for (String repositoryId : mainRepos) {
                if (log.isDebugEnabled()) {
                    log.debug("Validating .system folder for repository: " + repositoryId);
                }

                // Use Mango query to find .system folders efficiently
                java.net.URL findUrl = new java.net.URL(couchdbUrl + "/" + repositoryId + "/_find");
                java.net.HttpURLConnection findConn = (java.net.HttpURLConnection) findUrl.openConnection();
                findConn.setConnectTimeout(5000);
                findConn.setReadTimeout(5000);
                findConn.setRequestProperty("Authorization", "Basic " + encodedAuth);
                findConn.setRequestProperty("Content-Type", "application/json");
                findConn.setRequestMethod("POST");
                findConn.setDoOutput(true);

                String query = "{\"selector\":{\"type\":\"cmis:folder\",\"name\":\".system\"},\"limit\":2,\"fields\":[\"_id\"]}";
                try (java.io.OutputStreamWriter out = new java.io.OutputStreamWriter(
                        findConn.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8)) {
                    out.write(query);
                }

                int findCode = findConn.getResponseCode();
                if (findCode == 200) {
                    java.io.BufferedReader findReader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(findConn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                    String findBody = findReader.lines().reduce("", (a, b) -> a + b);
                    findReader.close();
                    findConn.disconnect();

                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    com.fasterxml.jackson.databind.JsonNode findResult = mapper.readTree(findBody);
                    int count = findResult.has("docs") ? findResult.get("docs").size() : 0;

                    if (count == 0) {
                        log.info("No .system folder found in " + repositoryId + " - initialization needed");
                        return false;
                    } else if (count > 1) {
                        log.warn("Found " + count + " .system folders in " + repositoryId + " - duplicates detected!");
                        return false;
                    }
                    if (log.isDebugEnabled()) {
                        log.debug("Repository " + repositoryId + " .system folder validation passed");
                    }
                } else {
                    findConn.disconnect();
                    log.info("Could not query .system folder in " + repositoryId + " (HTTP " + findCode + ") - initialization needed");
                    return false;
                }
            }

            if (log.isDebugEnabled()) {
                log.debug("All .system folder configurations valid");
            }
            return true;

        } catch (Exception e) {
            log.warn("Error validating .system folder configuration", e);
            return false;
        }
    }

    /**
     * External entry point for DB initialization.
     * Called from Setup Wizard apply flow with explicit credentials.
     *
     * Synchronized to prevent concurrent calls from overwriting the shared
     * connection fields (couchdbUrl/Username/Password) that downstream methods
     * (createDatabaseIfNotExists, loadDumpIntoDatabase, etc.) reference.
     */
    public synchronized void initializeDatabases(String url, String user, String pass) {
        log.info("initializeDatabases() called externally with url=" + url);
        // Save and restore original values to prevent side effects on the
        // fields that onApplicationEvent() may have already set.
        String origUrl = this.couchdbUrl;
        String origUser = this.couchdbUsername;
        String origPass = this.couchdbPassword;
        try {
            this.couchdbUrl = url;
            this.couchdbUsername = user;
            this.couchdbPassword = pass;

            List<String> databases = getRepositoryDbNames();
            log.info("initializeDatabases: target databases: " + databases);
            for (String dbName : databases) {
                createDatabaseIfNotExists(dbName);
            }
            loadInitialDumpFiles();
            log.info("initializeDatabases() completed");
        } finally {
            this.couchdbUrl = origUrl;
            this.couchdbUsername = origUser;
            this.couchdbPassword = origPass;
        }
    }

    /**
     * Get the list of database names to initialize.
     *
     * <p>Delegates to {@link StartupProbeService#discoverNemakiDatabases} which
     * reads {@code repositories.yml} as the authoritative source.
     * Falls back to the default list if the probe service is unavailable.
     */
    private List<String> getRepositoryDbNames() {
        if (startupProbeService != null) {
            try {
                List<String> discovered = startupProbeService.discoverNemakiDatabases(
                        couchdbUrl, couchdbUsername, couchdbPassword);
                if (discovered != null && !discovered.isEmpty()) {
                    return discovered;
                }
            } catch (Exception e) {
                log.debug("getRepositoryDbNames: StartupProbeService discovery failed: " + e.getMessage());
            }
        }
        return Arrays.asList("bedroom", "bedroom_closet", "canopy", "canopy_closet", "nemaki_conf");
    }

    /**
     * Ask StartupProbeService to re-evaluate CouchDB state after DB initialization.
     * This ensures the startup state reflects the actual post-init reality.
     */
    private void reprobeStartupState(ContextRefreshedEvent event) {
        try {
            StartupProbeService probeService = event.getApplicationContext().getBean(StartupProbeService.class);
            if (probeService != null) {
                log.info("DatabasePreInitializer: triggering StartupProbeService reprobe");
                probeService.reprobe();
            }
        } catch (Exception e) {
            log.debug("StartupProbeService not available for reprobe: " + e.getMessage());
        }
    }
}
