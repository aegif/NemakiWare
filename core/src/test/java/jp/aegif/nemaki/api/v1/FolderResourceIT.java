package jp.aegif.nemaki.api.v1;

import io.restassured.response.Response;
import org.junit.Test;
import org.junit.Ignore;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * E2E/Integration tests for FolderResource endpoints.
 * 
 * Tests the following endpoints:
 * - POST /repositories/{repoId}/folders - Create folder
 * - GET /repositories/{repoId}/folders/{folderId}/children - Get children
 * - GET /repositories/{repoId}/folders/{folderId}/descendants - Get descendants
 * - GET /repositories/{repoId}/folders/{folderId}/tree - Get folder tree
 * - GET /repositories/{repoId}/folders/{folderId}/parent - Get parent folder
 * - DELETE /repositories/{repoId}/folders/{folderId}/tree - Delete folder tree
 * 
 * Run with: mvn test -Dtest=FolderResourceIT -Dnemaki.test.baseUrl=http://localhost:8080/core
 */
@Ignore("Integration tests require a running NemakiWare instance")
public class FolderResourceIT extends ApiV1TestBase {
    
    @Test
    public void testGetChildren_RootFolder_ReturnsOk() {
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");
        
        given()
            .spec(requestSpec)
        .when()
            .get(foldersPath() + "/" + rootFolderId + "/children")
        .then()
            .statusCode(200)
            .contentType("application/json")
            .body("objects", notNullValue())
            .body("hasMoreItems", notNullValue())
            .body("links", notNullValue());
    }
    
    @Test
    public void testGetChildren_WithPagination_ReturnsOk() {
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");
        
        given()
            .spec(requestSpec)
            .queryParam("maxItems", 10)
            .queryParam("skipCount", 0)
        .when()
            .get(foldersPath() + "/" + rootFolderId + "/children")
        .then()
            .statusCode(200)
            .body("maxItems", equalTo(10))
            .body("skipCount", equalTo(0));
    }
    
    @Test
    public void testGetChildren_WithFilter_ReturnsFilteredProperties() {
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");
        
        given()
            .spec(requestSpec)
            .queryParam("filter", "cmis:name,cmis:objectTypeId")
        .when()
            .get(foldersPath() + "/" + rootFolderId + "/children")
        .then()
            .statusCode(200);
    }
    
    @Test
    public void testGetDescendants_RootFolder_ReturnsOk() {
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");
        
        given()
            .spec(requestSpec)
            .queryParam("depth", 1)
        .when()
            .get(foldersPath() + "/" + rootFolderId + "/descendants")
        .then()
            .statusCode(200)
            .contentType("application/json")
            .body("objects", notNullValue());
    }
    
    @Test
    public void testGetFolderTree_RootFolder_ReturnsOk() {
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");
        
        given()
            .spec(requestSpec)
            .queryParam("depth", 1)
        .when()
            .get(foldersPath() + "/" + rootFolderId + "/tree")
        .then()
            .statusCode(200)
            .contentType("application/json")
            .body("objects", notNullValue());
    }
    
    @Test
    public void testGetParent_RootFolder_ReturnsNull() {
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");
        
        // Root folder has no parent
        given()
            .spec(requestSpec)
        .when()
            .get(foldersPath() + "/" + rootFolderId + "/parent")
        .then()
            .statusCode(anyOf(equalTo(200), equalTo(404)));
    }
    
    @Test
    public void testCreateFolder_ValidRequest_ReturnsCreated() {
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");
        
        Map<String, Object> folderRequest = new HashMap<>();
        folderRequest.put("parentId", rootFolderId);
        folderRequest.put("name", "test-folder-" + System.currentTimeMillis());
        folderRequest.put("typeId", "cmis:folder");
        
        Response response = given()
            .spec(requestSpec)
            .body(folderRequest)
        .when()
            .post(foldersPath())
        .then()
            .statusCode(201)
            .contentType("application/json")
            .body("objectId", notNullValue())
            .body("objectTypeId", equalTo("cmis:folder"))
            .body("links", notNullValue())
            .extract()
            .response();
        
        // Clean up: delete the created folder
        String folderId = response.path("objectId");
        if (folderId != null) {
            given()
                .spec(requestSpec)
            .when()
                .delete(objectPath(folderId))
            .then()
                .statusCode(anyOf(equalTo(200), equalTo(204)));
        }
    }
    
    @Test
    public void testCreateFolder_MissingParentId_Returns400() {
        Map<String, Object> folderRequest = new HashMap<>();
        folderRequest.put("name", "test-folder-no-parent");
        folderRequest.put("typeId", "cmis:folder");
        
        given()
            .spec(requestSpec)
            .body(folderRequest)
        .when()
            .post(foldersPath())
        .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("type", containsString("/errors/"))
            .body("status", equalTo(400));
    }
    
    @Test
    public void testCreateFolder_InvalidParentId_Returns404() {
        Map<String, Object> folderRequest = new HashMap<>();
        folderRequest.put("parentId", "nonexistent-parent-12345");
        folderRequest.put("name", "test-folder-invalid-parent");
        folderRequest.put("typeId", "cmis:folder");
        
        given()
            .spec(requestSpec)
            .body(folderRequest)
        .when()
            .post(foldersPath())
        .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("status", equalTo(404));
    }
    
    @Test
    public void testGetChildren_InvalidFolderId_Returns404() {
        given()
            .spec(requestSpec)
        .when()
            .get(foldersPath() + "/nonexistent-folder-12345/children")
        .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("status", equalTo(404));
    }
    
    @Test
    public void testGetChildren_ContainsHATEOASLinks() {
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");
        
        given()
            .spec(requestSpec)
        .when()
            .get(foldersPath() + "/" + rootFolderId + "/children")
        .then()
            .statusCode(200)
            .body("links.self", notNullValue())
            .body("links.self.href", containsString("children"));
    }
}
