package jp.aegif.nemaki.api.v1;

import io.restassured.response.Response;
import org.junit.Test;
import org.junit.Ignore;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * E2E/Integration tests for RelationshipResource endpoints.
 * 
 * Tests the following endpoints:
 * - POST /repositories/{repoId}/relationships - Create relationship
 * - GET /repositories/{repoId}/relationships/object/{objectId} - Get relationships for object
 * 
 * Run with: mvn test -Dtest=RelationshipResourceIT -Dnemaki.test.baseUrl=http://localhost:8080/core
 */
@Ignore("Integration tests require a running NemakiWare instance")
public class RelationshipResourceIT extends ApiV1TestBase {
    
    @Test
    public void testGetObjectRelationships_RootFolder_ReturnsOk() {
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
            .get(relationshipsPath() + "/object/" + rootFolderId)
        .then()
            .statusCode(200)
            .contentType("application/json")
            .body("objects", notNullValue())
            .body("hasMoreItems", notNullValue())
            .body("links", notNullValue());
    }
    
    @Test
    public void testGetObjectRelationships_WithPagination_ReturnsOk() {
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
            .get(relationshipsPath() + "/object/" + rootFolderId)
        .then()
            .statusCode(200)
            .body("maxItems", equalTo(10))
            .body("skipCount", equalTo(0));
    }
    
    @Test
    public void testGetObjectRelationships_WithSourceDirection_ReturnsOk() {
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");
        
        given()
            .spec(requestSpec)
            .queryParam("relationshipDirection", "source")
        .when()
            .get(relationshipsPath() + "/object/" + rootFolderId)
        .then()
            .statusCode(200)
            .body("objects", notNullValue());
    }
    
    @Test
    public void testGetObjectRelationships_WithTargetDirection_ReturnsOk() {
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");
        
        given()
            .spec(requestSpec)
            .queryParam("relationshipDirection", "target")
        .when()
            .get(relationshipsPath() + "/object/" + rootFolderId)
        .then()
            .statusCode(200)
            .body("objects", notNullValue());
    }
    
    @Test
    public void testGetObjectRelationships_WithEitherDirection_ReturnsOk() {
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");
        
        given()
            .spec(requestSpec)
            .queryParam("relationshipDirection", "either")
        .when()
            .get(relationshipsPath() + "/object/" + rootFolderId)
        .then()
            .statusCode(200)
            .body("objects", notNullValue());
    }
    
    @Test
    public void testGetObjectRelationships_InvalidObjectId_Returns404() {
        given()
            .spec(requestSpec)
        .when()
            .get(relationshipsPath() + "/object/nonexistent-object-12345")
        .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("type", containsString("/errors/"))
            .body("status", equalTo(404));
    }
    
    @Test
    public void testCreateRelationship_ValidRequest_ReturnsCreated() {
        // First create two test objects (folders) to create a relationship between
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");
        
        // Create source folder
        Map<String, Object> sourceRequest = new HashMap<>();
        sourceRequest.put("parentId", rootFolderId);
        sourceRequest.put("name", "rel-source-" + System.currentTimeMillis());
        sourceRequest.put("typeId", "cmis:folder");
        
        Response sourceResponse = given()
            .spec(requestSpec)
            .body(sourceRequest)
        .when()
            .post(foldersPath())
        .then()
            .statusCode(201)
            .extract()
            .response();
        
        String sourceId = sourceResponse.path("objectId");
        
        // Create target folder
        Map<String, Object> targetRequest = new HashMap<>();
        targetRequest.put("parentId", rootFolderId);
        targetRequest.put("name", "rel-target-" + System.currentTimeMillis());
        targetRequest.put("typeId", "cmis:folder");
        
        Response targetResponse = given()
            .spec(requestSpec)
            .body(targetRequest)
        .when()
            .post(foldersPath())
        .then()
            .statusCode(201)
            .extract()
            .response();
        
        String targetId = targetResponse.path("objectId");
        
        try {
            // Create relationship
            Map<String, Object> relRequest = new HashMap<>();
            relRequest.put("sourceId", sourceId);
            relRequest.put("targetId", targetId);
            relRequest.put("typeId", "cmis:relationship");
            
            Response relResponse = given()
                .spec(requestSpec)
                .body(relRequest)
            .when()
                .post(relationshipsPath())
            .then()
                .statusCode(anyOf(equalTo(201), equalTo(200)))
                .contentType("application/json")
                .body("objectId", notNullValue())
                .body("sourceId", equalTo(sourceId))
                .body("targetId", equalTo(targetId))
                .body("links", notNullValue())
                .extract()
                .response();
            
            // Verify relationship appears in source's relationships
            given()
                .spec(requestSpec)
                .queryParam("relationshipDirection", "source")
            .when()
                .get(relationshipsPath() + "/object/" + sourceId)
            .then()
                .statusCode(200)
                .body("objects", notNullValue());
            
            // Clean up relationship if created
            String relId = relResponse.path("objectId");
            if (relId != null) {
                given()
                    .spec(requestSpec)
                .when()
                    .delete(objectPath(relId))
                .then()
                    .statusCode(anyOf(equalTo(200), equalTo(204)));
            }
            
        } finally {
            // Clean up source and target folders
            given()
                .spec(requestSpec)
            .when()
                .delete(objectPath(sourceId))
            .then()
                .statusCode(anyOf(equalTo(200), equalTo(204)));
            
            given()
                .spec(requestSpec)
            .when()
                .delete(objectPath(targetId))
            .then()
                .statusCode(anyOf(equalTo(200), equalTo(204)));
        }
    }
    
    @Test
    public void testCreateRelationship_MissingSourceId_Returns400() {
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");
        
        Map<String, Object> relRequest = new HashMap<>();
        relRequest.put("targetId", rootFolderId);
        relRequest.put("typeId", "cmis:relationship");
        
        given()
            .spec(requestSpec)
            .body(relRequest)
        .when()
            .post(relationshipsPath())
        .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("type", containsString("/errors/"))
            .body("status", equalTo(400));
    }
    
    @Test
    public void testCreateRelationship_MissingTargetId_Returns400() {
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");
        
        Map<String, Object> relRequest = new HashMap<>();
        relRequest.put("sourceId", rootFolderId);
        relRequest.put("typeId", "cmis:relationship");
        
        given()
            .spec(requestSpec)
            .body(relRequest)
        .when()
            .post(relationshipsPath())
        .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("status", equalTo(400));
    }
    
    @Test
    public void testCreateRelationship_InvalidSourceId_Returns404() {
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");
        
        Map<String, Object> relRequest = new HashMap<>();
        relRequest.put("sourceId", "nonexistent-source-12345");
        relRequest.put("targetId", rootFolderId);
        relRequest.put("typeId", "cmis:relationship");
        
        given()
            .spec(requestSpec)
            .body(relRequest)
        .when()
            .post(relationshipsPath())
        .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("status", equalTo(404));
    }
    
    @Test
    public void testCreateRelationship_InvalidTargetId_Returns404() {
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");
        
        Map<String, Object> relRequest = new HashMap<>();
        relRequest.put("sourceId", rootFolderId);
        relRequest.put("targetId", "nonexistent-target-12345");
        relRequest.put("typeId", "cmis:relationship");
        
        given()
            .spec(requestSpec)
            .body(relRequest)
        .when()
            .post(relationshipsPath())
        .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("status", equalTo(404));
    }
    
    @Test
    public void testGetObjectRelationships_ContainsHATEOASLinks() {
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
            .get(relationshipsPath() + "/object/" + rootFolderId)
        .then()
            .statusCode(200)
            .body("links.self", notNullValue())
            .body("links.self.href", containsString("relationships"));
    }
}
