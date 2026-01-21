package jp.aegif.nemaki.cloudantinit;

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.*;
import com.ibm.cloud.sdk.core.service.exception.ServiceResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Iterator;
import java.util.Map;

/**
 * CouchDB Database Initializer for NemakiWare
 * Creates required databases for NemakiWare operation
 */
public class CouchDBInitializer {
    
    private static final Logger logger = LoggerFactory.getLogger(CouchDBInitializer.class);
    
    private static final List<String> REQUIRED_DATABASES = Arrays.asList(
        "_users",
        "_replicator", 
        "_global_changes",
        "bedroom",
        "canopy"
    );
    
    public static void main(String[] args) {
        // Support both command line arguments and system properties
        String couchdbUrl;
        String username;
        String password;
        String repositoryId;
        String dumpFile;
        String force;
        
        if (args.length >= 4) {
            // Command line arguments: url repositoryId dumpFile force
            couchdbUrl = args[0];
            repositoryId = args[1];
            dumpFile = args[2];
            force = args[3];
            
            // Extract username and password from URL if present
            if (couchdbUrl.contains("@")) {
                String[] urlParts = couchdbUrl.split("@");
                if (urlParts.length == 2 && urlParts[0].contains("://")) {
                    String[] authParts = urlParts[0].split("://")[1].split(":");
                    username = authParts[0];
                    password = authParts.length > 1 ? authParts[1] : "admin";
                    couchdbUrl = urlParts[0].split("://")[0] + "://" + urlParts[1];
                } else {
                    username = "admin";
                    password = "admin";
                }
            } else {
                username = "admin";
                password = "admin";
            }
        } else {
            // System properties (fallback)
            couchdbUrl = System.getProperty("couchdb.url", "http://localhost:5984");
            username = System.getProperty("couchdb.username", "admin");
            password = System.getProperty("couchdb.password", "admin");
            repositoryId = System.getProperty("repository.id", "bedroom");
            dumpFile = System.getProperty("dump.file", "/app/bedroom_init.dump");
            force = System.getProperty("force", "true");
        }
        
        logger.info("Starting CouchDB initialization for NemakiWare");
        logger.info("CouchDB URL: {}", couchdbUrl);
        logger.info("Repository ID: {}", repositoryId);
        logger.info("Dump file: {}", dumpFile);
        logger.info("Force: {}", force);
        
        try {
            CouchDBInitializer initializer = new CouchDBInitializer();
            initializer.initializeDatabases(couchdbUrl, username, password, repositoryId, dumpFile, Boolean.parseBoolean(force));
            logger.info("CouchDB initialization completed successfully");
        } catch (Exception e) {
            logger.error("Failed to initialize CouchDB databases", e);
            System.exit(1);
        }
    }
    
    public void initializeDatabases(String couchdbUrl, String username, String password) {
        initializeDatabases(couchdbUrl, username, password, null, null, false);
    }
    
    public void initializeDatabases(String couchdbUrl, String username, String password, String repositoryId, String dumpFile, boolean force) {
        try {
            System.setProperty("CLOUDANT_URL", couchdbUrl);
            System.setProperty("CLOUDANT_USERNAME", username);
            System.setProperty("CLOUDANT_PASSWORD", password);
            System.setProperty("CLOUDANT_AUTH_TYPE", "COUCHDB_SESSION");
            
            Cloudant client = Cloudant.newInstance();
            
            if (repositoryId != null && dumpFile != null) {
                // Initialize specific repository with dump file
                logger.info("Initializing repository '{}' with dump file '{}'", repositoryId, dumpFile);
                initializeRepositoryWithDump(client, repositoryId, dumpFile, force);
            } else {
                // Initialize all required databases (legacy mode)
                for (String dbName : REQUIRED_DATABASES) {
                    createDatabaseIfNotExists(client, dbName);
                }
            }
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize databases", e);
        }
    }
    
    private void initializeRepositoryWithDump(Cloudant client, String repositoryId, String dumpFile, boolean force) {
        try {
            // Check if database exists
            boolean dbExists = false;
            try {
                GetDatabaseInformationOptions getDbOptions = new GetDatabaseInformationOptions.Builder()
                    .db(repositoryId)
                    .build();
                client.getDatabaseInformation(getDbOptions).execute();
                dbExists = true;
                logger.info("Database '{}' already exists", repositoryId);
            } catch (ServiceResponseException e) {
                if (e.getStatusCode() == 404) {
                    logger.info("Database '{}' does not exist, will create", repositoryId);
                } else {
                    throw e;
                }
            }
            
            if (dbExists && !force) {
                logger.info("Database '{}' exists and force=false, skipping initialization", repositoryId);
                return;
            }
            
            if (dbExists && force) {
                logger.info("Force=true, recreating database '{}'", repositoryId);
                // Delete existing database
                DeleteDatabaseOptions deleteOptions = new DeleteDatabaseOptions.Builder()
                    .db(repositoryId)
                    .build();
                client.deleteDatabase(deleteOptions).execute();
            }
            
            // Create database
            PutDatabaseOptions putDbOptions = new PutDatabaseOptions.Builder()
                .db(repositoryId)
                .build();
            client.putDatabase(putDbOptions).execute();
            logger.info("Created database '{}'", repositoryId);
            
            // Load dump file data
            loadDumpFile(client, repositoryId, dumpFile);
            logger.info("Successfully loaded dump file '{}' into database '{}'", dumpFile, repositoryId);
            
        } catch (Exception e) {
            logger.error("Failed to initialize repository '{}' with dump file '{}'", repositoryId, dumpFile, e);
            throw new RuntimeException("Repository initialization failed for: " + repositoryId, e);
        }
    }
    
    private void loadDumpFile(Cloudant client, String repositoryId, String dumpFile) {
        // This is a placeholder - dump file loading would need to be implemented
        // based on the actual dump file format used by NemakiWare
        logger.warn("Dump file loading not yet implemented for file: {}", dumpFile);
        logger.info("Database '{}' created but dump file loading is not implemented", repositoryId);
    }
    
    private void createDatabaseIfNotExists(Cloudant client, String dbName) {
        try {
            GetDatabaseInformationOptions getDbOptions = new GetDatabaseInformationOptions.Builder()
                .db(dbName)
                .build();
            
            try {
                client.getDatabaseInformation(getDbOptions).execute();
                logger.info("Database '{}' already exists", dbName);
            } catch (ServiceResponseException e) {
                if (e.getStatusCode() == 404) {
                    PutDatabaseOptions putDbOptions = new PutDatabaseOptions.Builder()
                        .db(dbName)
                        .build();
                    
                    client.putDatabase(putDbOptions).execute();
                    logger.info("Created database '{}'", dbName);
                } else {
                    throw e;
                }
            }
        } catch (Exception e) {
            logger.error("Failed to create database '{}'", dbName, e);
            throw new RuntimeException("Database creation failed for: " + dbName, e);
        }
    }
}
