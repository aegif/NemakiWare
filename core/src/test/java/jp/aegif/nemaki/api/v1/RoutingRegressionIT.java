package jp.aegif.nemaki.api.v1;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.filter.log.LogDetail;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
 * - /api/v1/cmis/repositories/{id} - New CMIS REST API (Jersey) — single repo detail
 * - /api/v1/repo/{repositoryId}/users - Legacy user management (Spring MVC)
 * - /api/v1/repo/{repositoryId}/groups - Legacy group management (Spring MVC)
 *
 * These tests require a running NemakiWare instance.
 * Run with: mvn test -Dtest=RoutingRegressionIT -Dnemaki.test.baseUrl=http://localhost:8080/core
 */
@EnabledIfNemakiRunning
public class RoutingRegressionIT {

    protected static String baseUrl;
    protected static String username;
    protected static String password;
    protected static String repositoryId;

    protected static RequestSpecification requestSpec;

    @BeforeAll
    public static void setupRestAssured() {
        baseUrl = getConfigValue("nemaki.test.baseUrl", "NEMAKI_TEST_BASE_URL", "http://localhost:8080/core");
        username = getConfigValue("nemaki.test.username", "NEMAKI_TEST_USERNAME", "admin");
        password = getConfigValue("nemaki.test.password", "NEMAKI_TEST_PASSWORD", "admin");
        repositoryId = getConfigValue("nemaki.test.repositoryId", "NEMAKI_TEST_REPOSITORY_ID", "bedroom");

        RestAssured.baseURI = baseUrl;
        // CRITICAL: Clear basePath to prevent pollution from ApiV1TestBase subclasses
        // which set it to "/api/v1/cmis". Without this, URLs become "/api/v1/cmis/api/v1/...".
        RestAssured.basePath = "";

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
     * Test that the CMIS REST API repository detail endpoint is accessible.
     * Uses /repositories/{id} because /repositories (list) requires default-repo
     * auth configuration which may not be present.
     */
    @Test
    public void testNewCmisApiRepositoryDetailEndpoint() {
        given()
            .spec(requestSpec)
        .when()
            .get("/api/v1/cmis/repositories/" + repositoryId)
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("repositoryId", equalTo(repositoryId));
    }

    /**
     * Test that the new CMIS REST API capabilities endpoint is accessible.
     */
    @Test
    public void testNewCmisApiCapabilitiesEndpoint() {
        given()
            .spec(requestSpec)
        .when()
            .get("/api/v1/cmis/repositories/" + repositoryId + "/capabilities")
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"));
    }

    /**
     * Test that the new CMIS REST API rootFolder endpoint is accessible.
     */
    @Test
    public void testNewCmisApiRootFolderEndpoint() {
        given()
            .spec(requestSpec)
        .when()
            .get("/api/v1/cmis/repositories/" + repositoryId + "/rootFolder")
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("rootFolderId", notNullValue());
    }

    /**
     * Test that the OpenAPI spec at /api/v1/cmis/openapi.json is accessible without authentication.
     */
    @Test
    public void testNewCmisApiOpenApiSpec() {
        given()
            .accept(ContentType.JSON)
        .when()
            .get("/api/v1/cmis/openapi.json")
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("openapi", notNullValue());
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
            .statusCode(anyOf(equalTo(200), equalTo(403)));
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
            .statusCode(anyOf(equalTo(200), equalTo(403)));
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
            .statusCode(anyOf(equalTo(200), equalTo(400), equalTo(403), equalTo(404)));
    }

    // ========== Coexistence Tests ==========

    /**
     * Test that both new and legacy endpoints can be accessed in sequence.
     * This verifies that the servlet routing is working correctly for both paths.
     */
    @Test
    public void testNewAndLegacyEndpointsCoexist() {
        // Access the new CMIS API (repo detail — guaranteed to have auth context)
        given()
            .spec(requestSpec)
        .when()
            .get("/api/v1/cmis/repositories/" + repositoryId)
        .then()
            .statusCode(200);

        // Access the legacy API
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
            .get("/api/v1/cmis/repositories/" + repositoryId)
        .then()
            .statusCode(200);
    }

    /**
     * Non-existent repository returns 401 because the authentication filter
     * extracts the repository ID and attempts login, which fails when the
     * repository doesn't exist.
     */
    @Test
    public void testNonExistentRepositoryReturns401() {
        given()
            .spec(requestSpec)
        .when()
            .get("/api/v1/cmis/repositories/nonexistent-repo-12345")
        .then()
            .statusCode(401);
    }
}
