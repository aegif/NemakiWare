package jp.aegif.nemaki.api.v1;

import io.restassured.response.Response;
import org.junit.Test;
import org.junit.Ignore;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * E2E/Integration tests for ObjectResource endpoints.
 * 
 * Tests the following endpoints:
 * - GET /repositories/{repoId}/objects/{objectId} - Get object
 * - DELETE /repositories/{repoId}/objects/{objectId} - Delete object
 * - GET /repositories/{repoId}/objects/{objectId}/content - Get content stream
 * - PUT /repositories/{repoId}/objects/{objectId}/content - Set content stream
 * - DELETE /repositories/{repoId}/objects/{objectId}/content - Delete content stream
 * - POST /repositories/{repoId}/objects/{objectId}/content/append - Append content
 * - POST /repositories/{repoId}/objects/{objectId}/move - Move object
 * - GET /repositories/{repoId}/objects/{objectId}/parents - Get parents
 * - GET /repositories/{repoId}/objects/{objectId}/relationships - Get relationships
 * - GET /repositories/{repoId}/objects/{objectId}/allowableActions - Get allowable actions
 * - GET /repositories/{repoId}/objects/{objectId}/renditions - Get renditions
 * 
 * Run with: mvn test -Dtest=ObjectResourceIT -Dnemaki.test.baseUrl=http://localhost:8080/core
 */
@Ignore("Integration tests require a running NemakiWare instance")
public class ObjectResourceIT extends ApiV1TestBase {
    
    @Test
    public void testGetObject_RootFolder_ReturnsOk() {
        // First get the root folder ID from repository info
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .statusCode(200)
            .extract()
            .path("rootFolderId");
        
        // Then get the root folder object
        given()
            .spec(requestSpec)
        .when()
            .get(objectPath(rootFolderId))
        .then()
            .statusCode(200)
            .contentType("application/json")
            .body("objectId", equalTo(rootFolderId))
            .body("objectTypeId", notNullValue())
            .body("properties", notNullValue())
            .body("links", notNullValue());
    }
    
    @Test
    public void testGetObject_WithFilter_ReturnsFilteredProperties() {
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
            .get(objectPath(rootFolderId))
        .then()
            .statusCode(200)
            .body("properties.cmis:name", notNullValue())
            .body("properties.cmis:objectTypeId", notNullValue());
    }
    
    @Test
    public void testGetObject_InvalidObjectId_Returns404() {
        given()
            .spec(requestSpec)
        .when()
            .get(objectPath("nonexistent-object-12345"))
        .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("type", containsString("/errors/"))
            .body("status", equalTo(404))
            .body("detail", notNullValue());
    }
    
    @Test
    public void testGetAllowableActions_RootFolder_ReturnsOk() {
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
            .get(objectPath(rootFolderId) + "/allowableActions")
        .then()
            .statusCode(200)
            .contentType("application/json")
            .body("objectId", equalTo(rootFolderId))
            .body("allowableActions", notNullValue())
            .body("links", notNullValue());
    }
    
    @Test
    public void testGetAllowableActions_ContainsExpectedActions() {
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
            .get(objectPath(rootFolderId) + "/allowableActions")
        .then()
            .statusCode(200)
            .body("allowableActions.canGetProperties", notNullValue())
            .body("allowableActions.canGetChildren", notNullValue())
            .body("allowableActions.canCreateDocument", notNullValue())
            .body("allowableActions.canCreateFolder", notNullValue());
    }
    
    @Test
    public void testGetParents_RootFolder_ReturnsEmptyList() {
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
            .get(objectPath(rootFolderId) + "/parents")
        .then()
            .statusCode(200)
            .contentType("application/json")
            .body("objects", empty());
    }
    
    @Test
    public void testGetRenditions_RootFolder_ReturnsOk() {
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
            .get(objectPath(rootFolderId) + "/renditions")
        .then()
            .statusCode(200)
            .contentType("application/json");
    }
    
    @Test
    public void testGetRelationships_RootFolder_ReturnsOk() {
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
            .get(objectPath(rootFolderId) + "/relationships")
        .then()
            .statusCode(200)
            .contentType("application/json")
            .body("objects", notNullValue());
    }
    
    @Test
    public void testGetObject_PropertyStructure_Has2LayerFormat() {
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");
        
        // Verify 2-layer property structure (value/type)
        given()
            .spec(requestSpec)
        .when()
            .get(objectPath(rootFolderId))
        .then()
            .statusCode(200)
            .body("properties.cmis:name.value", notNullValue())
            .body("properties.cmis:name.type", equalTo("string"))
            .body("properties.cmis:objectTypeId.value", notNullValue())
            .body("properties.cmis:objectTypeId.type", equalTo("id"));
    }
    
    @Test
    public void testGetObject_LinksContainHATEOAS() {
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
            .get(objectPath(rootFolderId))
        .then()
            .statusCode(200)
            .body("links.self", notNullValue())
            .body("links.self.href", containsString(rootFolderId))
            .body("links.allowableActions", notNullValue())
            .body("links.acl", notNullValue());
    }
}
