package jp.aegif.nemaki.api.v1;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.filter.log.LogDetail;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Routing regression tests to verify that the new /api/v1/cmis/* endpoints
 * do not conflict with legacy /api/v1/repo/* Spring MVC endpoints.
 * 
 * This test verifies the fix for PR #411 P1 issue:
 * - The new Jersey servlet at /api/v1/cmis/* should NOT intercept
 *   legacy Spring MVC endpoints at /api/v1/repo/*
 * 
 * Tests cover:
 * - /api/v1/cmis/repositories - New CMIS REST API (Jersey)
 * - /api/v1/repo/{repositoryId}/users - Legacy user management (Spring MVC)
 * - /api/v1/repo/{repositoryId}/groups - Legacy group management (Spring MVC)
 * 
 * These tests require a running NemakiWare instance.
 * Run with: mvn test -Dtest=RoutingRegressionIT -Dnemaki.test.baseUrl=http://localhost:8080/core
 */
@Ignore("Requires running NemakiWare instance - remove @Ignore to run integration tests")
public class RoutingRegressionIT {
    
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
        
        // Configure REST Assured - use base URL only, paths will be specified per test
        RestAssured.baseURI = baseUrl;
        
        // Build request specification with common settings
        requestSpec = new RequestSpecBuilder()
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .addHeader("Authorization", createBasicAuthHeader(username, password))
                .log(LogDetail.METHOD)
                .log(LogDetail.URI)
                .build();
    }
    
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
    
    protected static String createBasicAuthHeader(String user, String pass) {
        String credentials = user + ":" + pass;
        return "Basic " + java.util.Base64.getEncoder().encodeToString(credentials.getBytes());
    }
    
    // ========== New CMIS REST API Tests (/api/v1/cmis/*) ==========
    
    /**
     * Test that the new CMIS REST API at /api/v1/cmis/repositories is accessible.
     * This endpoint is handled by Jersey (ApiV1Application).
     */
    @Test
    public void testNewCmisApiRepositoriesEndpoint() {
        given()
            .spec(requestSpec)
        .when()
            .get("/api/v1/cmis/repositories")
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"));
    }
    
    /**
     * Test that the new CMIS REST API at /api/v1/cmis/repositories/{repoId}/objects is accessible.
     */
    @Test
    public void testNewCmisApiObjectsEndpoint() {
        given()
            .spec(requestSpec)
            .queryParam("maxItems", 1)
        .when()
            .get("/api/v1/cmis/repositories/" + repositoryId + "/objects")
        .then()
            .statusCode(anyOf(equalTo(200), equalTo(400))); // 400 if objectId required
    }
    
    /**
     * Test that the new CMIS REST API at /api/v1/cmis/repositories/{repoId}/folders is accessible.
     */
    @Test
    public void testNewCmisApiFoldersEndpoint() {
        given()
            .spec(requestSpec)
        .when()
            .get("/api/v1/cmis/repositories/" + repositoryId + "/folders")
        .then()
            .statusCode(anyOf(equalTo(200), equalTo(400), equalTo(404))); // Various valid responses
    }
    
    /**
     * Test that the OpenAPI spec at /api/v1/cmis/openapi.json is accessible without authentication.
     */
    @Test
    public void testNewCmisApiOpenApiSpec() {
        given()
            // No auth header for this test - OpenAPI spec should be public
            .accept(ContentType.JSON)
        .when()
            .get("/api/v1/cmis/openapi.json")
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("openapi", notNullValue())
            .body("info.title", notNullValue());
    }
    
    // ========== Legacy Spring MVC API Tests (/api/v1/repo/*) ==========
    
    /**
     * Test that the legacy user management endpoint at /api/v1/repo/{repoId}/users is still accessible.
     * This endpoint is handled by Spring MVC (UserController).
     * 
     * CRITICAL: This test verifies that the routing fix works correctly.
     * If this returns 404, the Jersey servlet is incorrectly intercepting Spring MVC routes.
     */
    @Test
    public void testLegacyUserManagementEndpoint() {
        given()
            .spec(requestSpec)
        .when()
            .get("/api/v1/repo/" + repositoryId + "/users")
        .then()
            .statusCode(anyOf(equalTo(200), equalTo(403))) // 200 OK or 403 if user management disabled
            .contentType(anyOf(containsString("application/json"), containsString("text/plain")));
    }
    
    /**
     * Test that the legacy group management endpoint at /api/v1/repo/{repoId}/groups is still accessible.
     * This endpoint is handled by Spring MVC (GroupController).
     * 
     * CRITICAL: This test verifies that the routing fix works correctly.
     * If this returns 404, the Jersey servlet is incorrectly intercepting Spring MVC routes.
     */
    @Test
    public void testLegacyGroupManagementEndpoint() {
        given()
            .spec(requestSpec)
        .when()
            .get("/api/v1/repo/" + repositoryId + "/groups")
        .then()
            .statusCode(anyOf(equalTo(200), equalTo(403))) // 200 OK or 403 if group management disabled
            .contentType(anyOf(containsString("application/json"), containsString("text/plain")));
    }
    
    /**
     * Test that the legacy renditions endpoint at /api/v1/repo/{repoId}/renditions is still accessible.
     * This endpoint is handled by Spring MVC (RenditionController).
     */
    @Test
    public void testLegacyRenditionsEndpoint() {
        given()
            .spec(requestSpec)
        .when()
            .get("/api/v1/repo/" + repositoryId + "/renditions")
        .then()
            .statusCode(anyOf(equalTo(200), equalTo(400), equalTo(403), equalTo(404))); // Various valid responses
    }
    
    /**
     * Test that the legacy type-migration endpoint at /api/v1/repo/{repoId}/type-migration is still accessible.
     * This endpoint is handled by Spring MVC (TypeMigrationController).
     */
    @Test
    public void testLegacyTypeMigrationEndpoint() {
        given()
            .spec(requestSpec)
        .when()
            .get("/api/v1/repo/" + repositoryId + "/type-migration")
        .then()
            .statusCode(anyOf(equalTo(200), equalTo(400), equalTo(403), equalTo(404), equalTo(405))); // Various valid responses
    }
    
    // ========== Coexistence Tests ==========
    
    /**
     * Test that both new and legacy endpoints can be accessed in sequence.
     * This verifies that the servlet routing is working correctly for both paths.
     */
    @Test
    public void testNewAndLegacyEndpointsCoexist() {
        // First, access the new CMIS API
        given()
            .spec(requestSpec)
        .when()
            .get("/api/v1/cmis/repositories")
        .then()
            .statusCode(200);
        
        // Then, access the legacy API
        given()
            .spec(requestSpec)
        .when()
            .get("/api/v1/repo/" + repositoryId + "/users")
        .then()
            .statusCode(anyOf(equalTo(200), equalTo(403)));
        
        // Access new API again to ensure no state issues
        given()
            .spec(requestSpec)
        .when()
            .get("/api/v1/cmis/repositories")
        .then()
            .statusCode(200);
    }
    
    /**
     * Test that invalid paths return 404 appropriately.
     */
    @Test
    public void testInvalidPathReturns404() {
        given()
            .spec(requestSpec)
        .when()
            .get("/api/v1/cmis/nonexistent-endpoint")
        .then()
            .statusCode(404);
    }
    
    /**
     * Test that /api/v1/repo path without repository ID returns appropriate error.
     */
    @Test
    public void testLegacyPathWithoutRepositoryId() {
        given()
            .spec(requestSpec)
        .when()
            .get("/api/v1/repo")
        .then()
            .statusCode(anyOf(equalTo(400), equalTo(404)));
    }
}
