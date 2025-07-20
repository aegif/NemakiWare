package jp.aegif.nemaki.cloudantinit;

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.*;
import com.ibm.cloud.sdk.core.service.exception.ServiceResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

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
        String couchdbUrl = System.getProperty("couchdb.url", "http://localhost:5984");
        String username = System.getProperty("couchdb.username", "admin");
        String password = System.getProperty("couchdb.password", "admin");
        
        logger.info("Starting CouchDB initialization for NemakiWare");
        logger.info("CouchDB URL: {}", couchdbUrl);
        
        try {
            CouchDBInitializer initializer = new CouchDBInitializer();
            initializer.initializeDatabases(couchdbUrl, username, password);
            logger.info("CouchDB initialization completed successfully");
        } catch (Exception e) {
            logger.error("Failed to initialize CouchDB databases", e);
            System.exit(1);
        }
    }
    
    public void initializeDatabases(String couchdbUrl, String username, String password) {
        try {
            System.setProperty("CLOUDANT_URL", couchdbUrl);
            System.setProperty("CLOUDANT_USERNAME", username);
            System.setProperty("CLOUDANT_PASSWORD", password);
            System.setProperty("CLOUDANT_AUTH_TYPE", "COUCHDB_SESSION");
            
            Cloudant client = Cloudant.newInstance();
            
            for (String dbName : REQUIRED_DATABASES) {
                createDatabaseIfNotExists(client, dbName);
            }
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize databases", e);
        }
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
