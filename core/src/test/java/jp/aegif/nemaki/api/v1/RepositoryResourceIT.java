package jp.aegif.nemaki.api.v1;

import io.restassured.response.Response;
import org.junit.Test;
import org.junit.Ignore;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * E2E/Integration tests for RepositoryResource endpoints.
 * 
 * Tests the following endpoints:
 * - GET /repositories - List all repositories
 * - GET /repositories/{repositoryId} - Get repository info
 * 
 * Run with: mvn test -Dtest=RepositoryResourceIT -Dnemaki.test.baseUrl=http://localhost:8080/core
 */
@Ignore("Integration tests require a running NemakiWare instance")
public class RepositoryResourceIT extends ApiV1TestBase {
    
    @Test
    public void testListRepositories_ReturnsOk() {
        given()
            .spec(requestSpec)
        .when()
            .get(repositoriesPath())
        .then()
            .statusCode(200)
            .contentType("application/json")
            .body("repositories", notNullValue())
            .body("repositories", not(empty()));
    }
    
    @Test
    public void testListRepositories_ContainsRepositoryId() {
        given()
            .spec(requestSpec)
        .when()
            .get(repositoriesPath())
        .then()
            .statusCode(200)
            .body("repositories.repositoryId", hasItem(repositoryId));
    }
    
    @Test
    public void testGetRepositoryInfo_ReturnsOk() {
        given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .statusCode(200)
            .contentType("application/json")
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
    public void testGetRepositoryInfo_ContainsAclCapability() {
        given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .statusCode(200)
            .body("aclCapability", notNullValue())
            .body("aclCapability.supportedPermissions", notNullValue())
            .body("aclCapability.propagation", notNullValue());
    }
    
    @Test
    public void testGetRepositoryInfo_ContainsLinks() {
        given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .statusCode(200)
            .body("links", notNullValue())
            .body("links.self", notNullValue())
            .body("links.self.href", containsString(repositoryId));
    }
    
    @Test
    public void testGetRepositoryInfo_InvalidRepository_Returns404() {
        given()
            .spec(requestSpec)
        .when()
            .get("/repositories/nonexistent-repo-12345")
        .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("type", containsString("/errors/"))
            .body("title", notNullValue())
            .body("status", equalTo(404))
            .body("detail", notNullValue());
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
            .contentType("application/problem+json")
            .body("type", containsString("/errors/"))
            .body("status", equalTo(401));
    }
}
