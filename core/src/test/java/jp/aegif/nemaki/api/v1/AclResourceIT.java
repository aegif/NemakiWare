package jp.aegif.nemaki.api.v1;

import io.restassured.response.Response;
import org.junit.Test;
import org.junit.Ignore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * E2E/Integration tests for AclResource endpoints.
 * 
 * Tests the following endpoints:
 * - GET /repositories/{repoId}/objects/{objectId}/acl - Get ACL
 * - PUT /repositories/{repoId}/objects/{objectId}/acl - Apply ACL
 * 
 * Run with: mvn test -Dtest=AclResourceIT -Dnemaki.test.baseUrl=http://localhost:8080/core
 */
@Ignore("Integration tests require a running NemakiWare instance")
public class AclResourceIT extends ApiV1TestBase {
    
    @Test
    public void testGetAcl_RootFolder_ReturnsOk() {
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
            .get(aclPath(rootFolderId))
        .then()
            .statusCode(200)
            .contentType("application/json")
            .body("objectId", equalTo(rootFolderId))
            .body("aces", notNullValue())
            .body("links", notNullValue());
    }
    
    @Test
    public void testGetAcl_ContainsAceEntries() {
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
            .get(aclPath(rootFolderId))
        .then()
            .statusCode(200)
            .body("aces", notNullValue());
    }
    
    @Test
    public void testGetAcl_ContainsExactFlag() {
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
            .get(aclPath(rootFolderId))
        .then()
            .statusCode(200)
            .body("exact", notNullValue());
    }
    
    @Test
    public void testGetAcl_InvalidObjectId_Returns404() {
        given()
            .spec(requestSpec)
        .when()
            .get(aclPath("nonexistent-object-12345"))
        .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("type", containsString("/errors/"))
            .body("status", equalTo(404));
    }
    
    @Test
    public void testApplyAcl_ValidRequest_ReturnsOk() {
        // First create a test folder to apply ACL to
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");
        
        Map<String, Object> folderRequest = new HashMap<>();
        folderRequest.put("parentId", rootFolderId);
        folderRequest.put("name", "acl-test-folder-" + System.currentTimeMillis());
        folderRequest.put("typeId", "cmis:folder");
        
        Response createResponse = given()
            .spec(requestSpec)
            .body(folderRequest)
        .when()
            .post(foldersPath())
        .then()
            .statusCode(201)
            .extract()
            .response();
        
        String folderId = createResponse.path("objectId");
        
        try {
            // Apply ACL
            Map<String, Object> aceEntry = new HashMap<>();
            aceEntry.put("principalId", "admin");
            aceEntry.put("permissions", new String[]{"cmis:all"});
            
            List<Map<String, Object>> aces = new ArrayList<>();
            aces.add(aceEntry);
            
            Map<String, Object> aclRequest = new HashMap<>();
            aclRequest.put("aces", aces);
            aclRequest.put("aclPropagation", "REPOSITORYDETERMINED");
            
            given()
                .spec(requestSpec)
                .body(aclRequest)
            .when()
                .put(aclPath(folderId))
            .then()
                .statusCode(200)
                .contentType("application/json")
                .body("objectId", equalTo(folderId))
                .body("aces", notNullValue());
            
        } finally {
            // Clean up
            given()
                .spec(requestSpec)
            .when()
                .delete(objectPath(folderId))
            .then()
                .statusCode(anyOf(equalTo(200), equalTo(204)));
        }
    }
    
    @Test
    public void testApplyAcl_WithObjectOnlyPropagation_ReturnsOk() {
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");
        
        Map<String, Object> folderRequest = new HashMap<>();
        folderRequest.put("parentId", rootFolderId);
        folderRequest.put("name", "acl-objectonly-test-" + System.currentTimeMillis());
        folderRequest.put("typeId", "cmis:folder");
        
        Response createResponse = given()
            .spec(requestSpec)
            .body(folderRequest)
        .when()
            .post(foldersPath())
        .then()
            .statusCode(201)
            .extract()
            .response();
        
        String folderId = createResponse.path("objectId");
        
        try {
            Map<String, Object> aceEntry = new HashMap<>();
            aceEntry.put("principalId", "admin");
            aceEntry.put("permissions", new String[]{"cmis:read"});
            
            List<Map<String, Object>> aces = new ArrayList<>();
            aces.add(aceEntry);
            
            Map<String, Object> aclRequest = new HashMap<>();
            aclRequest.put("aces", aces);
            aclRequest.put("aclPropagation", "OBJECTONLY");
            
            given()
                .spec(requestSpec)
                .body(aclRequest)
            .when()
                .put(aclPath(folderId))
            .then()
                .statusCode(200);
            
        } finally {
            given()
                .spec(requestSpec)
            .when()
                .delete(objectPath(folderId))
            .then()
                .statusCode(anyOf(equalTo(200), equalTo(204)));
        }
    }
    
    @Test
    public void testApplyAcl_WithPropagatePropagation_ReturnsOk() {
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");
        
        Map<String, Object> folderRequest = new HashMap<>();
        folderRequest.put("parentId", rootFolderId);
        folderRequest.put("name", "acl-propagate-test-" + System.currentTimeMillis());
        folderRequest.put("typeId", "cmis:folder");
        
        Response createResponse = given()
            .spec(requestSpec)
            .body(folderRequest)
        .when()
            .post(foldersPath())
        .then()
            .statusCode(201)
            .extract()
            .response();
        
        String folderId = createResponse.path("objectId");
        
        try {
            Map<String, Object> aceEntry = new HashMap<>();
            aceEntry.put("principalId", "admin");
            aceEntry.put("permissions", new String[]{"cmis:write"});
            
            List<Map<String, Object>> aces = new ArrayList<>();
            aces.add(aceEntry);
            
            Map<String, Object> aclRequest = new HashMap<>();
            aclRequest.put("aces", aces);
            aclRequest.put("aclPropagation", "PROPAGATE");
            
            given()
                .spec(requestSpec)
                .body(aclRequest)
            .when()
                .put(aclPath(folderId))
            .then()
                .statusCode(200);
            
        } finally {
            given()
                .spec(requestSpec)
            .when()
                .delete(objectPath(folderId))
            .then()
                .statusCode(anyOf(equalTo(200), equalTo(204)));
        }
    }
    
    @Test
    public void testApplyAcl_InvalidObjectId_Returns404() {
        Map<String, Object> aceEntry = new HashMap<>();
        aceEntry.put("principalId", "admin");
        aceEntry.put("permissions", new String[]{"cmis:all"});
        
        List<Map<String, Object>> aces = new ArrayList<>();
        aces.add(aceEntry);
        
        Map<String, Object> aclRequest = new HashMap<>();
        aclRequest.put("aces", aces);
        
        given()
            .spec(requestSpec)
            .body(aclRequest)
        .when()
            .put(aclPath("nonexistent-object-12345"))
        .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("status", equalTo(404));
    }
    
    @Test
    public void testApplyAcl_InvalidPropagation_Returns400() {
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");
        
        Map<String, Object> aceEntry = new HashMap<>();
        aceEntry.put("principalId", "admin");
        aceEntry.put("permissions", new String[]{"cmis:all"});
        
        List<Map<String, Object>> aces = new ArrayList<>();
        aces.add(aceEntry);
        
        Map<String, Object> aclRequest = new HashMap<>();
        aclRequest.put("aces", aces);
        aclRequest.put("aclPropagation", "INVALID_PROPAGATION");
        
        given()
            .spec(requestSpec)
            .body(aclRequest)
        .when()
            .put(aclPath(rootFolderId))
        .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("status", equalTo(400));
    }
    
    @Test
    public void testGetAcl_ContainsHATEOASLinks() {
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
            .get(aclPath(rootFolderId))
        .then()
            .statusCode(200)
            .body("links.self", notNullValue())
            .body("links.self.href", containsString("acl"))
            .body("links.object", notNullValue());
    }
}
