package jp.aegif.nemaki.odata;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.filter.log.LogDetail;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.BeforeClass;

/**
 * Base class for OData 4.0 E2E/integration tests using REST Assured.
 * 
 * These tests are designed to run against a deployed NemakiWare instance.
 * Configure the base URL and credentials via system properties or environment variables:
 * 
 * - nemaki.test.baseUrl: Base URL of the NemakiWare instance (default: http://localhost:8080/core)
 * - nemaki.test.username: Test user username (default: admin)
 * - nemaki.test.password: Test user password (default: admin)
 * - nemaki.test.repositoryId: Repository ID to test against (default: bedroom)
 * 
 * Example:
 *   mvn test -Dnemaki.test.baseUrl=http://localhost:8080/core -Dnemaki.test.username=admin
 */
public abstract class ODataTestBase {
    
    protected static final String ODATA_PATH = "/odata";
    
    protected static String baseUrl;
    protected static String username;
    protected static String password;
    protected static String repositoryId;
    
    protected static RequestSpecification requestSpec;
    
    @BeforeClass
    public static void setupRestAssured() {
        // Load configuration from system properties or environment variables
        baseUrl = getConfigValue("nemaki.test.baseUrl", "NEMAKI_TEST_BASE_URL", "http://localhost:8080/core");
        username = getConfigValue("nemaki.test.username", "NEMAKI_TEST_USERNAME", "admin");
        password = getConfigValue("nemaki.test.password", "NEMAKI_TEST_PASSWORD", "admin");
        repositoryId = getConfigValue("nemaki.test.repositoryId", "NEMAKI_TEST_REPOSITORY_ID", "bedroom");
        
        // Configure REST Assured for OData
        RestAssured.baseURI = baseUrl;
        RestAssured.basePath = ODATA_PATH;
        
        // Build request specification with common settings
        // OData uses application/json for responses
        requestSpec = new RequestSpecBuilder()
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .addHeader("Authorization", createBasicAuthHeader(username, password))
                .log(LogDetail.METHOD)
                .log(LogDetail.URI)
                .build();
    }
    
    /**
     * Get configuration value from system property, environment variable, or default.
     */
    protected static String getConfigValue(String sysProp, String envVar, String defaultValue) {
        String value = System.getProperty(sysProp);
        if (value == null || value.isEmpty()) {
            value = System.getenv(envVar);
        }
        if (value == null || value.isEmpty()) {
            value = defaultValue;
        }
        return value;
    }
    
    /**
     * Create Basic Authentication header value.
     */
    protected static String createBasicAuthHeader(String user, String pass) {
        String credentials = user + ":" + pass;
        return "Basic " + java.util.Base64.getEncoder().encodeToString(credentials.getBytes());
    }
    
    /**
     * Get the OData service root path for the configured repository.
     */
    protected String serviceRoot() {
        return "/" + repositoryId;
    }
    
    /**
     * Get the Documents entity set path.
     */
    protected String documentsPath() {
        return serviceRoot() + "/Documents";
    }
    
    /**
     * Get the path for a specific document by ID.
     */
    protected String documentPath(String objectId) {
        return documentsPath() + "('" + objectId + "')";
    }
    
    /**
     * Get the Folders entity set path.
     */
    protected String foldersPath() {
        return serviceRoot() + "/Folders";
    }
    
    /**
     * Get the path for a specific folder by ID.
     */
    protected String folderPath(String objectId) {
        return foldersPath() + "('" + objectId + "')";
    }
    
    /**
     * Get the Objects entity set path.
     */
    protected String objectsPath() {
        return serviceRoot() + "/Objects";
    }
    
    /**
     * Get the path for a specific object by ID.
     */
    protected String objectPath(String objectId) {
        return objectsPath() + "('" + objectId + "')";
    }
    
    /**
     * Get the Relationships entity set path.
     */
    protected String relationshipsPath() {
        return serviceRoot() + "/Relationships";
    }
    
    /**
     * Get the Policies entity set path.
     */
    protected String policiesPath() {
        return serviceRoot() + "/Policies";
    }
    
    /**
     * Get the $metadata path.
     */
    protected String metadataPath() {
        return serviceRoot() + "/$metadata";
    }
}
