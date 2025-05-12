package jp.aegif.nemaki.cloudantinit;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Test for CouchDBInitializer
 * 
 * Note: These tests require a running CouchDB instance.
 * For CouchDB 2.x tests: Set COUCHDB_URL environment variable (default: http://localhost:5984)
 * For CouchDB 3.x tests: Set COUCHDB_URL, COUCHDB_USERNAME, COUCHDB_PASSWORD environment variables
 */
public class CouchDBInitializerTest {
    
    private static final String TEST_DB = "nemaki_test_" + System.currentTimeMillis();
    private File testFile;
    private String couchdbUrl;
    private String username;
    private String password;
    
    @Before
    public void setUp() throws IOException {
        couchdbUrl = System.getenv("COUCHDB_URL");
        if (couchdbUrl == null) {
            couchdbUrl = "http://localhost:5984";
        }
        
        username = System.getenv("COUCHDB_USERNAME");
        password = System.getenv("COUCHDB_PASSWORD");
        
        testFile = createTestDataFile();
    }
    
    @After
    public void tearDown() throws IOException {
        if (testFile != null && testFile.exists()) {
            testFile.delete();
        }
        
        try {
            java.net.URL url = new java.net.URL(couchdbUrl + "/" + TEST_DB);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("DELETE");
            
            if (username != null && password != null) {
                String auth = username + ":" + password;
                String encodedAuth = java.util.Base64.getEncoder().encodeToString(auth.getBytes());
                conn.setRequestProperty("Authorization", "Basic " + encodedAuth);
            }
            
            conn.getResponseCode();
        } catch (Exception e) {
        }
    }
    
    @Test
    public void testInitRepository() {
        CouchDBInitializer initializer = new CouchDBInitializer(
                couchdbUrl, username, password, TEST_DB, testFile, true);
        
        boolean result = initializer.initRepository();
        assertTrue("Repository initialization should succeed", result);
        
        result = initializer.initRepository();
        assertFalse("Second initialization should return false", result);
    }
    
    @Test
    public void testLoad() {
        CouchDBInitializer initializer = new CouchDBInitializer(
                couchdbUrl, username, password, TEST_DB, testFile, true);
        
        boolean result = initializer.load();
        assertTrue("Data load should succeed", result);
        
        try {
            java.net.URL url = new java.net.URL(couchdbUrl + "/" + TEST_DB + "/_all_docs");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            
            if (username != null && password != null) {
                String auth = username + ":" + password;
                String encodedAuth = java.util.Base64.getEncoder().encodeToString(auth.getBytes());
                conn.setRequestProperty("Authorization", "Basic " + encodedAuth);
            }
            
            int responseCode = conn.getResponseCode();
            assertEquals("Should get HTTP 200 OK", 200, responseCode);
            
            java.io.BufferedReader in = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream()));
            String inputLine;
            StringBuilder response = new StringBuilder();
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();
            
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response.toString());
            JsonNode rows = root.get("rows");
            
            assertTrue("Should have at least 2 documents", rows.size() >= 2);
            
        } catch (Exception e) {
            fail("Failed to verify data: " + e.getMessage());
        }
    }
    
    /**
     * Create a test data file with sample documents and attachments
     */
    private File createTestDataFile() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode documents = mapper.createArrayNode();
        
        ObjectNode doc1 = mapper.createObjectNode();
        doc1.put("_id", "test_doc_1");
        doc1.put("name", "Test Document 1");
        doc1.put("type", "test");
        
        ObjectNode entry1 = mapper.createObjectNode();
        entry1.set("document", doc1);
        
        ObjectNode doc2 = mapper.createObjectNode();
        doc2.put("_id", "test_doc_2");
        doc2.put("name", "Test Document 2");
        doc2.put("type", "test_with_attachment");
        
        ObjectNode attachments = mapper.createObjectNode();
        ObjectNode attachment = mapper.createObjectNode();
        attachment.put("content_type", "text/plain");
        attachment.put("data", java.util.Base64.getEncoder().encodeToString("Test attachment content".getBytes()));
        attachments.set("test.txt", attachment);
        
        ObjectNode entry2 = mapper.createObjectNode();
        entry2.set("document", doc2);
        entry2.set("attachments", attachments);
        
        documents.add(entry1);
        documents.add(entry2);
        
        Path tempFile = Files.createTempFile("nemaki_test_", ".dump");
        try (FileWriter writer = new FileWriter(tempFile.toFile())) {
            writer.write(mapper.writeValueAsString(documents));
        }
        
        return tempFile.toFile();
    }
}
