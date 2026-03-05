package jp.aegif.nemaki.api.v1;

import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * E2E/Integration tests for RepositoryResource endpoints.
 *
 * Tests the following endpoints:
 * - GET /repositories - List all repositories (returns JSON array)
 * - GET /repositories/{repositoryId} - Get repository info
 *
 * Run with: mvn test -Dtest=RepositoryResourceIT -Dnemaki.test.baseUrl=http://localhost:8080/core
 */
@EnabledIfNemakiRunning
public class RepositoryResourceIT extends ApiV1TestBase {

    @Test
    public void testListRepositories_ReturnsOk() {
        given()
            .spec(requestSpec)
        .when()
            .get(repositoriesPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            // Response is a JSON array (not a wrapper object)
            .body("$", not(empty()));
    }

    @Test
    public void testListRepositories_ContainsRepositoryId() {
        given()
            .spec(requestSpec)
        .when()
            .get(repositoriesPath())
        .then()
            .statusCode(200)
            // Array elements have repositoryId field
            .body("repositoryId", hasItem(repositoryId));
    }

    @Test
    public void testGetRepositoryInfo_ReturnsOk() {
        given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("repositoryId", equalTo(repositoryId))
            .body("repositoryName", notNullValue())
            .body("rootFolderId", notNullValue())
            .body("cmisVersionSupported", notNullValue());
    }

    @Test
    public void testGetRepositoryInfo_ContainsCapabilities() {
        given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .statusCode(200)
            .body("capabilities", notNullValue())
            .body("capabilities.capabilityContentStreamUpdatability", notNullValue())
            .body("capabilities.capabilityChanges", notNullValue())
            .body("capabilities.capabilityRenditions", notNullValue());
    }

    @Test
    public void testGetRepositoryInfo_ContainsAclCapabilities() {
        given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .statusCode(200)
            // Field name is "aclCapabilities" (plural), not "aclCapability"
            .body("aclCapabilities", notNullValue())
            .body("aclCapabilities.supportedPermissions", notNullValue())
            .body("aclCapabilities.aclPropagation", notNullValue());
    }

    @Test
    public void testGetRepositoryInfo_ContainsLinks() {
        given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .statusCode(200)
            // Links field is "_links" (HATEOAS convention)
            .body("_links", notNullValue())
            .body("_links.self", notNullValue())
            .body("_links.self.href", containsString(repositoryId));
    }

    /**
     * Non-existent repository returns 401 (not 404) because the authentication
     * filter extracts the repository ID from the path and attempts login against
     * that repository — which fails when the repository doesn't exist.
     */
    @Test
    public void testGetRepositoryInfo_InvalidRepository_Returns401() {
        given()
            .spec(requestSpec)
        .when()
            .get("/repositories/nonexistent-repo-12345")
        .then()
            .statusCode(401);
    }

    @Test
    public void testListRepositories_Unauthenticated_Returns401() {
        given()
            .contentType("application/json")
            .accept("application/json")
        .when()
            .get(repositoriesPath())
        .then()
            .statusCode(401)
            .contentType(containsString("application/problem+json"))
            .body("type", containsString("/errors/"))
            .body("status", equalTo(401));
    }
}
