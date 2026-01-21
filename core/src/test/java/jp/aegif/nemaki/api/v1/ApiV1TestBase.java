package jp.aegif.nemaki.api.v1;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.filter.log.LogDetail;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.BeforeClass;

/**
 * Base class for API v1 E2E/integration tests using REST Assured.
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
public abstract class ApiV1TestBase {
    
    // Note: API v1 CMIS endpoints are at /api/v1/cmis/* to avoid conflict with legacy /api/v1/repo/* endpoints
    protected static final String API_V1_PATH = "/api/v1/cmis";
    
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
        
        // Configure REST Assured
        RestAssured.baseURI = baseUrl;
        RestAssured.basePath = API_V1_PATH;
        
        // Build request specification with common settings
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
     * Get the repositories endpoint path.
     */
    protected String repositoriesPath() {
        return "/repositories";
    }
    
    /**
     * Get the repository-specific base path.
     */
    protected String repositoryPath() {
        return "/repositories/" + repositoryId;
    }
    
    /**
     * Get the objects endpoint path for the configured repository.
     */
    protected String objectsPath() {
        return repositoryPath() + "/objects";
    }
    
    /**
     * Get the path for a specific object.
     */
    protected String objectPath(String objectId) {
        return objectsPath() + "/" + objectId;
    }
    
    /**
     * Get the folders endpoint path for the configured repository.
     */
    protected String foldersPath() {
        return repositoryPath() + "/folders";
    }
    
    /**
     * Get the documents endpoint path for the configured repository.
     */
    protected String documentsPath() {
        return repositoryPath() + "/documents";
    }
    
    /**
     * Get the types endpoint path for the configured repository.
     */
    protected String typesPath() {
        return repositoryPath() + "/types";
    }
    
    /**
     * Get the query endpoint path for the configured repository.
     */
    protected String queryPath() {
        return repositoryPath() + "/query";
    }
    
    /**
     * Get the ACL endpoint path for a specific object.
     */
    protected String aclPath(String objectId) {
        return objectsPath() + "/" + objectId + "/acl";
    }
    
    /**
     * Get the relationships endpoint path for the configured repository.
     */
    protected String relationshipsPath() {
        return repositoryPath() + "/relationships";
    }
    
    /**
     * Get the policies endpoint path for the configured repository.
     */
    protected String policiesPath() {
        return repositoryPath() + "/policies";
    }
}
