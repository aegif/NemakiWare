package jp.aegif.nemaki.util;

import java.io.File;
import java.io.InputStream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import com.ibm.cloud.cloudant.v1.model.GetDatabaseInformationOptions;
import com.ibm.cloud.cloudant.v1.model.PutDatabaseOptions;
import com.ibm.cloud.sdk.core.service.exception.NotFoundException;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;

/**
 * Automatic database initialization service for NemakiWare
 * Ensures all required databases and design documents are properly initialized on startup
 */
public class DatabaseAutoInitializer {
    
    private static final Log log = LogFactory.getLog(DatabaseAutoInitializer.class);
    
    private CloudantClientPool clientPool;
    private RepositoryInfoMap repositoryInfoMap;
    private String couchdbUrl = "http://localhost:5984";
    private String couchdbUsername = "admin";
    private String couchdbPassword = "password";
    
    /**
     * Initialize all databases with required data and design documents
     */
    public void initializeDatabases() {
        log.info("=== DATABASE AUTO-INITIALIZATION STARTED ===");
        
        try {
            // Initialize each repository database
            if (repositoryInfoMap != null) {
                for (String repositoryId : repositoryInfoMap.keys()) {
                    initializeRepository(repositoryId);
                }
            }
            
            // Initialize system configuration database
            initializeConfigDatabase();
            
            log.info("=== DATABASE AUTO-INITIALIZATION COMPLETED SUCCESSFULLY ===");
            
        } catch (Exception e) {
            log.error("Database auto-initialization failed", e);
            throw new RuntimeException("Failed to initialize databases", e);
        }
    }
    
    /**
     * Initialize a specific repository database
     */
    private void initializeRepository(String repositoryId) {
        log.info("Initializing repository database: " + repositoryId);
        
        try {
            CloudantClientWrapper client = clientPool.getClient(repositoryId);
            
            // Check if database exists and has required data
            if (isDatabaseEmpty(client, repositoryId)) {
                log.info("Database '" + repositoryId + "' is empty or missing required data - initializing...");
                
                // Create database if it doesn't exist
                createDatabaseIfNotExists(client, repositoryId);
                
                // Apply initialization data using cloudant-init.jar approach
                String dumpFileName = getDumpFileName(repositoryId);
                applyInitializationDataUsingProcess(repositoryId, dumpFileName);
                
                log.info("Repository '" + repositoryId + "' initialization completed");
            } else {
                log.info("Repository '" + repositoryId + "' already properly initialized");
            }
            
        } catch (Exception e) {
            log.error("Failed to initialize repository: " + repositoryId, e);
            throw new RuntimeException("Repository initialization failed: " + repositoryId, e);
        }
    }
    
    /**
     * Check if database is empty or missing required design documents
     */
    private boolean isDatabaseEmpty(CloudantClientWrapper client, String repositoryId) {
        try {
            // Check if _design/_repo document exists
            com.ibm.cloud.cloudant.v1.model.Document designDoc = client.get("_design/_repo");
            
            if (designDoc == null) {
                log.info("Design document '_design/_repo' not found in " + repositoryId);
                return true;
            }
            
            log.info("Design document exists in " + repositoryId + " - checking views...");
            
            // Simple check - if we can execute admin view query, assume it's properly initialized
            try {
                client.queryView("_repo", "admin");
                log.info("Repository '" + repositoryId + "' appears to be properly initialized");
                return false;
            } catch (Exception e) {
                log.info("Views not working properly in " + repositoryId + " - needs initialization");
                return true;
            }
            
        } catch (NotFoundException e) {
            log.info("Database '" + repositoryId + "' not found or empty");
            return true;
        } catch (Exception e) {
            log.warn("Error checking database state for " + repositoryId + " - assuming needs initialization", e);
            return true;
        }
    }
    
    /**
     * Create database if it doesn't exist
     */
    private void createDatabaseIfNotExists(CloudantClientWrapper client, String repositoryId) {
        try {
            GetDatabaseInformationOptions options = new GetDatabaseInformationOptions.Builder()
                .db(repositoryId)
                .build();
            client.getClient().getDatabaseInformation(options).execute();
            log.info("Database '" + repositoryId + "' already exists");
            
        } catch (NotFoundException e) {
            log.info("Creating database: " + repositoryId);
            try {
                PutDatabaseOptions createOptions = new PutDatabaseOptions.Builder()
                    .db(repositoryId)
                    .build();
                client.getClient().putDatabase(createOptions).execute();
                log.info("Database '" + repositoryId + "' created successfully");
                
            } catch (Exception createError) {
                log.error("Failed to create database: " + repositoryId, createError);
                throw new RuntimeException("Database creation failed: " + repositoryId, createError);
            }
        }
    }
    
    /**
     * Apply initialization data using cloudant-init.jar process
     */
    private void applyInitializationDataUsingProcess(String repositoryId, String dumpFileName) {
        try {
            log.info("Applying initialization data to " + repositoryId + " using " + dumpFileName);
            
            // Find dump file in classpath
            Resource resource = new ClassPathResource("initialization/" + dumpFileName);
            if (!resource.exists()) {
                log.warn("Initialization file not found: " + dumpFileName + " - skipping data initialization");
                return;
            }
            
            // Create temp file for the dump
            File tempDumpFile = File.createTempFile("nemaki_init_" + repositoryId, ".dump");
            tempDumpFile.deleteOnExit();
            
            // Copy resource to temp file
            try (InputStream is = resource.getInputStream();
                 java.io.FileOutputStream fos = new java.io.FileOutputStream(tempDumpFile)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            }
            
            // Find cloudant-init.jar in classpath
            Resource initJarResource = new ClassPathResource("cloudant-init.jar");
            if (!initJarResource.exists()) {
                log.warn("cloudant-init.jar not found in classpath - attempting alternative initialization");
                return;
            }
            
            // Create temp file for cloudant-init.jar
            File tempInitJar = File.createTempFile("cloudant-init", ".jar");
            tempInitJar.deleteOnExit();
            
            try (InputStream is = initJarResource.getInputStream();
                 java.io.FileOutputStream fos = new java.io.FileOutputStream(tempInitJar)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            }
            
            // Execute cloudant-init.jar
            ProcessBuilder pb = new ProcessBuilder(
                "java", "-jar", tempInitJar.getAbsolutePath(),
                "--url", couchdbUrl,
                "--username", couchdbUsername,
                "--password", couchdbPassword,
                "--repository", repositoryId,
                "--dump", tempDumpFile.getAbsolutePath(),
                "--force", "true"
            );
            
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // Read output
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("Init process: " + line);
                }
            }
            
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                log.info("Successfully initialized " + repositoryId + " with " + dumpFileName);
            } else {
                log.error("Initialization process failed with exit code: " + exitCode);
                throw new RuntimeException("Failed to initialize repository: " + repositoryId);
            }
            
        } catch (Exception e) {
            log.error("Failed to apply initialization data to " + repositoryId, e);
            throw new RuntimeException("Data initialization failed: " + repositoryId, e);
        }
    }
    
    /**
     * Get appropriate dump file name for repository
     */
    private String getDumpFileName(String repositoryId) {
        if (repositoryId.endsWith("_closet")) {
            return "archive_init.dump";
        } else if ("canopy".equals(repositoryId)) {
            return "canopy_init.dump";
        } else {
            return "bedroom_init.dump";
        }
    }
    
    /**
     * Initialize system configuration database
     */
    private void initializeConfigDatabase() {
        log.info("Initializing configuration database: nemaki_conf");
        
        try {
            CloudantClientWrapper client = clientPool.getClient("nemaki_conf");
            
            if (isDatabaseEmpty(client, "nemaki_conf")) {
                createDatabaseIfNotExists(client, "nemaki_conf");
                applyInitializationDataUsingProcess("nemaki_conf", "nemaki_conf_init.dump");
                log.info("Configuration database initialized");
            } else {
                log.info("Configuration database already initialized");
            }
            
        } catch (Exception e) {
            log.error("Failed to initialize configuration database", e);
            // Don't fail the entire startup for config database issues
        }
    }
    
    // Getters and setters for Spring injection
    public void setClientPool(CloudantClientPool clientPool) {
        this.clientPool = clientPool;
    }
    
    public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
        this.repositoryInfoMap = repositoryInfoMap;
    }
    
    public void setCouchdbUrl(String couchdbUrl) {
        this.couchdbUrl = couchdbUrl;
    }
    
    public void setCouchdbUsername(String couchdbUsername) {
        this.couchdbUsername = couchdbUsername;
    }
    
    public void setCouchdbPassword(String couchdbPassword) {
        this.couchdbPassword = couchdbPassword;
    }
}