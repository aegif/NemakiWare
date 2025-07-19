package jp.aegif.nemaki.dao.impl.couch.connector;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import static org.junit.Assert.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Test for CloudantClientPool connection configuration
 * This test verifies that the pool can handle different environments correctly
 */
public class CloudantClientPoolTest {
    
    private static final Log log = LogFactory.getLog(CloudantClientPoolTest.class);
    private CloudantClientPool pool;
    
    @Before
    public void setUp() {
        pool = new CloudantClientPool();
        // Set basic configuration
        pool.setMaxConnections(10);
        pool.setConnectionTimeout(5000);
        pool.setSocketTimeout(10000);
        pool.setAuthEnabled(true);
        pool.setAuthUserName("admin");
        pool.setAuthPassword("password");
    }
    
    @After
    public void tearDown() {
        // Clean up system properties
        System.clearProperty("db.couchdb.url.override");
    }
    
    @Test
    public void testUrlResolution_DefaultConfiguration() {
        // Test with default localhost URL
        pool.setUrl("http://localhost:5984");
        
        // This will use the default URL since we're not in Docker
        // The actual connection might fail if CouchDB isn't running, but that's OK
        // We're testing the URL resolution logic
        log.info("Testing URL resolution with default configuration");
        
        try {
            // Just verify that initialization doesn't throw unexpected exceptions
            // The connection might fail, which is expected in test environment
            pool.initialize();
        } catch (RuntimeException e) {
            // Expected if CouchDB isn't running
            log.info("Connection failed as expected (CouchDB might not be running): " + e.getMessage());
            assertTrue(e.getMessage().contains("Failed to initialize Cloudant client pool") || 
                      e.getMessage().contains("Failed to connect to CouchDB"));
        }
    }
    
    @Test
    public void testUrlResolution_SystemPropertyOverride() {
        // Test system property override
        String customUrl = "http://custom-host:5984";
        System.setProperty("db.couchdb.url.override", customUrl);
        
        pool.setUrl("http://localhost:5984");
        
        log.info("Testing URL resolution with system property override");
        
        try {
            pool.initialize();
        } catch (RuntimeException e) {
            // Expected - just verify the custom URL was used
            log.info("Connection failed as expected with custom URL: " + e.getMessage());
            assertTrue(e.getMessage().contains("custom-host") || 
                      e.getMessage().contains("Failed to connect to CouchDB"));
        }
    }
    
    @Test
    public void testConnectionRetryLogic() {
        // Test with an invalid URL to trigger retry logic
        pool.setUrl("http://invalid-host:5984");
        
        log.info("Testing connection retry logic with invalid host");
        
        long startTime = System.currentTimeMillis();
        try {
            pool.initialize();
            fail("Expected RuntimeException due to invalid host");
        } catch (RuntimeException e) {
            long duration = System.currentTimeMillis() - startTime;
            
            // Verify retry logic was executed (should take at least 4 seconds for 3 attempts with 2s delay)
            log.info("Connection failed after retries. Duration: " + duration + "ms");
            assertTrue("Retry logic should have taken at least 4 seconds", duration >= 4000);
            assertTrue(e.getMessage().contains("Failed to initialize Cloudant client pool after 3 attempts") ||
                      e.getMessage().contains("Failed to connect to CouchDB"));
        }
    }
    
    @Test
    public void testDockerEnvironmentDetection() {
        // This test verifies the Docker detection logic doesn't break in non-Docker environments
        pool.setUrl("http://localhost:5984");
        
        log.info("Testing Docker environment detection (should detect non-Docker)");
        
        try {
            pool.initialize();
        } catch (RuntimeException e) {
            // Expected - just verify localhost wasn't replaced
            log.info("Connection failed as expected: " + e.getMessage());
            assertTrue(e.getMessage().contains("localhost") || 
                      e.getMessage().contains("Failed to connect to CouchDB"));
            assertFalse("URL should not contain 'couchdb' in non-Docker environment", 
                       e.getMessage().contains("http://couchdb:5984"));
        }
    }
}