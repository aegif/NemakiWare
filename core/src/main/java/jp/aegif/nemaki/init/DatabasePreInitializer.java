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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Phase 1 Database Pre-Initializer for NemakiWare
 * 
 * This class handles database-level initialization operations that complete
 * early in the Spring context initialization process. It uses InitializingBean
 * with high precedence to ensure database setup happens before most other beans.
 * 
 * Phase 1 Operations (DB Direct):
 * - Create databases if they don't exist
 * - Load dump file data directly into CouchDB
 * - Set up design documents and views
 * 
 * This phase executes early in Spring context initialization,
 * ensuring that the database prerequisites are met before services
 * that depend on complex beans.
 */
public class DatabasePreInitializer {
    
    private static final Log log = LogFactory.getLog(DatabasePreInitializer.class);
    
    // Configuration properties for database initialization (injected via Spring)
    private String couchdbUrl = "http://couchdb:5984";
    private String couchdbUsername = "admin";
    private String couchdbPassword = "password";
    
    // Setters for Spring property injection
    public void setCouchdbUrl(String couchdbUrl) {
        this.couchdbUrl = couchdbUrl;
    }
    
    public void setCouchdbUsername(String couchdbUsername) {
        this.couchdbUsername = couchdbUsername;
    }
    
    public void setCouchdbPassword(String couchdbPassword) {
        this.couchdbPassword = couchdbPassword;
    }
    
    public DatabasePreInitializer() {
        System.out.println("=== DatabasePreInitializer CONSTRUCTOR CALLED ===");
        log.info("=== DatabasePreInitializer CONSTRUCTOR CALLED ===");
    }
    
    /**
     * Execute Phase 1 database initialization
     * 
     * This is pure database-layer initialization that does NOT depend on:
     * - CMIS services
     * - Nemakiware application services  
     * - Complex Spring beans
     * 
     * Uses only basic HTTP operations to ensure database prerequisites.
     * Called via Spring init-method configuration.
     */
    public void initializeDatabase() {
        System.out.println("========================================");
        System.out.println("=== PHASE 1: DatabasePreInitializer.initializeDatabase() EXECUTING ===");
        System.out.println("=== This should create databases and load dump files ===");
        System.out.println("========================================");
        System.out.println("=== DATABASE PRE-INITIALIZATION (Phase 1) STARTED ===");
        log.info("=== PHASE 1: DatabasePreInitializer.initializeDatabase() EXECUTING ===");
        log.info("=== DATABASE PRE-INITIALIZATION (Phase 1) STARTED - Pure database layer operations ===");
        
        try {
            // Wait a moment for CouchDB to be ready
            Thread.sleep(2000);
            
            System.out.println("Initializing CouchDB databases at: " + couchdbUrl);
            log.info("Initializing CouchDB databases at: " + couchdbUrl);
            
            // CRITICAL OPTIMIZATION: Check if databases are already initialized
            if (isDatabasesAlreadyInitialized()) {
                System.out.println("=== OPTIMIZATION: Databases already initialized, skipping dump loading ===");
                log.info("=== OPTIMIZATION: Databases already initialized, skipping expensive dump processing ===");
                System.out.println("=== DATABASE PRE-INITIALIZATION (Phase 1) COMPLETED (SKIPPED) ===");
                log.info("=== DATABASE PRE-INITIALIZATION (Phase 1) COMPLETED (SKIPPED) ===");
                return;
            }
            
            System.out.println("=== FULL INITIALIZATION: Databases not found or incomplete, proceeding with setup ===");
            log.info("=== FULL INITIALIZATION: Performing complete database setup ===");
            
            // List of databases to create
            String[] databases = {"bedroom", "bedroom_closet", "canopy", "canopy_closet", "nemaki_conf"};
            
            for (String dbName : databases) {
                createDatabaseIfNotExists(dbName);
            }
            
            // Load dump files for initial data (pure HTTP operations)
            loadInitialDumpFiles();
            
            System.out.println("=== DATABASE PRE-INITIALIZATION (Phase 1) COMPLETED ===");
            log.info("=== DATABASE PRE-INITIALIZATION (Phase 1) COMPLETED ===");
            
        } catch (Exception e) {
            System.err.println("Phase 1 database pre-initialization failed: " + e.getMessage());
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
     * @return true if all required databases exist and contain data
     */
    private boolean isDatabasesAlreadyInitialized() {
        try {
            String[] requiredDatabases = {"bedroom", "bedroom_closet", "canopy", "canopy_closet", "nemaki_conf"};
            // Databases that require design documents (closet databases don't need them)
            String[] databasesWithDesignDocs = {"bedroom", "canopy", "nemaki_conf"};
            
            System.out.println("=== CHECKING: Database initialization status ===");
            log.info("=== CHECKING: Database initialization status ===");
            
            for (String dbName : requiredDatabases) {
                // Check if database exists
                java.net.URL checkUrl = new java.net.URL(couchdbUrl + "/" + dbName);
                java.net.HttpURLConnection checkConn = (java.net.HttpURLConnection) checkUrl.openConnection();
                
                String auth = couchdbUsername + ":" + couchdbPassword;
                String encodedAuth = java.util.Base64.getEncoder().encodeToString(auth.getBytes());
                checkConn.setRequestProperty("Authorization", "Basic " + encodedAuth);
                checkConn.setRequestMethod("HEAD");
                
                int responseCode = checkConn.getResponseCode();
                checkConn.disconnect();
                
                if (responseCode != 200) {
                    System.out.println("=== CHECKING: Database " + dbName + " not found (HTTP " + responseCode + ") ===");
                    log.info("=== CHECKING: Database " + dbName + " not found, full initialization needed ===");
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
                    designConn.setRequestMethod("HEAD");
                    
                    int designResponseCode = designConn.getResponseCode();
                    designConn.disconnect();
                    
                    if (designResponseCode != 200) {
                        System.out.println("=== CHECKING: Database " + dbName + " missing design documents, initialization needed ===");
                        log.info("=== CHECKING: Database " + dbName + " missing design documents, full initialization needed ===");
                        return false;
                    }
                } else {
                    System.out.println("=== CHECKING: Database " + dbName + " (closet) - design documents not required ===");
                    log.info("=== CHECKING: Database " + dbName + " (closet) - design documents not required ===");
                }
            }
            
            System.out.println("=== CHECKING: All databases properly initialized ===");
            log.info("=== CHECKING: All databases properly initialized, skipping dump processing ===");
            return true;
            
        } catch (Exception e) {
            System.out.println("=== CHECKING: Error checking database status, proceeding with full initialization ===");
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
            String encodedAuth = java.util.Base64.getEncoder().encodeToString(auth.getBytes());
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
        System.out.println("=== DUMP LOADING: Starting to load initial dump files ===");
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
                    System.out.println("=== DUMP DEBUG: Attempting to load from classpath: " + classpath);
                    java.io.InputStream dumpStream = getClass().getResourceAsStream(classpath);
                    if (dumpStream == null) {
                        System.out.println("=== DUMP DEBUG: Classpath not found, trying absolute path: " + dumpPath);
                        // Try absolute path
                        java.io.File dumpFile = new java.io.File(dumpPath);
                        if (dumpFile.exists()) {
                            dumpStream = new java.io.FileInputStream(dumpFile);
                            System.out.println("=== DUMP DEBUG: Found dump file at absolute path");
                        } else {
                            System.out.println("=== DUMP DEBUG: Absolute path also not found");
                        }
                    } else {
                        System.out.println("=== DUMP DEBUG: Found dump file in classpath");
                    }
                    
                    if (dumpStream != null) {
                        System.out.println("=== DUMP DEBUG: Loading dump file for database: " + dbName);
                        log.info("Loading dump file for database: " + dbName);
                        loadDumpIntoDatabase(dbName, dumpStream);
                        dumpStream.close();
                        System.out.println("=== DUMP DEBUG: Completed loading dump file for database: " + dbName);
                    } else {
                        System.out.println("=== DUMP DEBUG: Dump file not found for database: " + dbName);
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
        System.out.println("=== DUMP LOAD DEBUG: Starting to load dump for database: " + dbName);
        
        // Read the dump file
        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(dumpStream));
        StringBuilder content = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            content.append(line).append("\n");
        }
        reader.close();
        
        System.out.println("=== DUMP LOAD DEBUG: Read " + content.length() + " characters from dump file");
        
        // Parse JSON dump
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode dumpData = mapper.readTree(content.toString());
        
        System.out.println("=== DUMP LOAD DEBUG: Parsed JSON successfully");
        
        // The dump file is directly a JSON array, not an object with a "docs" property
        if (dumpData.isArray()) {
            System.out.println("=== DUMP LOAD DEBUG: Found array with " + dumpData.size() + " elements");
            for (com.fasterxml.jackson.databind.JsonNode doc : dumpData) {
                // Handle document directly or extract from document wrapper
                com.fasterxml.jackson.databind.JsonNode docToInsert = doc;
                if (doc.has("document")) {
                    docToInsert = doc.get("document");
                }
                
                try {
                    // Create document in CouchDB
                    String docId = docToInsert.get("_id").asText();
                    System.out.println("=== DUMP LOAD DEBUG: Processing document: " + docId);
                    
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
                    String encodedAuth = java.util.Base64.getEncoder().encodeToString(auth.getBytes());
                    conn.setRequestProperty("Authorization", "Basic " + encodedAuth);
                    conn.setRequestProperty("Content-Type", "application/json");
                    
                    conn.setRequestMethod("PUT");
                    conn.setDoOutput(true);
                    
                    // Write document
                    java.io.OutputStreamWriter out = new java.io.OutputStreamWriter(conn.getOutputStream());
                    out.write(docJson);
                    out.close();
                    
                    int responseCode = conn.getResponseCode();
                    System.out.println("=== DUMP LOAD DEBUG: Document " + docId + " response code: " + responseCode);
                    if (responseCode == 201 || responseCode == 200) {
                        System.out.println("=== DUMP LOAD DEBUG: Successfully created document " + docId + " in " + dbName);
                        log.info("Created document " + docId + " in " + dbName);
                    } else if (responseCode == 409) {
                        System.out.println("=== DUMP LOAD DEBUG: Document " + docId + " already exists in " + dbName);
                        log.debug("Document " + docId + " already exists in " + dbName);
                    } else {
                        System.out.println("=== DUMP LOAD DEBUG: Failed to create document " + docId + " response: " + responseCode);
                        log.warn("Failed to create document " + docId + " in " + dbName + ", response: " + responseCode);
                    }
                    
                    conn.disconnect();
                    
                } catch (Exception e) {
                    System.out.println("=== DUMP LOAD DEBUG: Exception processing document: " + e.getMessage());
                    log.error("Error processing document in " + dbName, e);
                }
            }
            System.out.println("=== DUMP LOAD DEBUG: Completed processing all documents for " + dbName);
        } else {
            System.out.println("=== DUMP LOAD DEBUG: Root JSON is not an array for " + dbName + ", type: " + dumpData.getNodeType());
        }
    }
}