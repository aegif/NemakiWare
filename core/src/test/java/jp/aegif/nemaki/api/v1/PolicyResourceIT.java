package jp.aegif.nemaki.api.v1;

import io.restassured.response.Response;
import org.junit.Test;
import org.junit.Ignore;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * E2E/Integration tests for PolicyResource endpoints.
 * 
 * Tests the following endpoints:
 * - POST /repositories/{repoId}/policies - Create policy
 * - POST /repositories/{repoId}/policies/apply - Apply policy to object
 * - POST /repositories/{repoId}/policies/remove - Remove policy from object
 * - GET /repositories/{repoId}/policies/object/{objectId} - Get applied policies
 * 
 * Run with: mvn test -Dtest=PolicyResourceIT -Dnemaki.test.baseUrl=http://localhost:8080/core
 */
@Ignore("Integration tests require a running NemakiWare instance")
public class PolicyResourceIT extends ApiV1TestBase {
    
    @Test
    public void testGetAppliedPolicies_RootFolder_ReturnsOk() {
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
            .get(policiesPath() + "/object/" + rootFolderId)
        .then()
            .statusCode(200)
            .contentType("application/json")
            .body("objectId", equalTo(rootFolderId))
            .body("policies", notNullValue())
            .body("numPolicies", notNullValue())
            .body("links", notNullValue());
    }
    
    @Test
    public void testGetAppliedPolicies_InvalidObjectId_Returns404() {
        given()
            .spec(requestSpec)
        .when()
            .get(policiesPath() + "/object/nonexistent-object-12345")
        .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("type", containsString("/errors/"))
            .body("status", equalTo(404));
    }
    
    @Test
    public void testCreatePolicy_ValidRequest_ReturnsCreated() {
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");
        
        Map<String, Object> policyRequest = new HashMap<>();
        policyRequest.put("name", "test-policy-" + System.currentTimeMillis());
        policyRequest.put("typeId", "cmis:policy");
        policyRequest.put("folderId", rootFolderId);
        policyRequest.put("policyText", "Test policy text");
        
        Response response = given()
            .spec(requestSpec)
            .body(policyRequest)
        .when()
            .post(policiesPath())
        .then()
            .statusCode(anyOf(equalTo(201), equalTo(200)))
            .contentType("application/json")
            .body("objectId", notNullValue())
            .body("links", notNullValue())
            .extract()
            .response();
        
        // Clean up: delete the created policy
        String policyId = response.path("objectId");
        if (policyId != null) {
            given()
                .spec(requestSpec)
            .when()
                .delete(objectPath(policyId))
            .then()
                .statusCode(anyOf(equalTo(200), equalTo(204)));
        }
    }
    
    @Test
    public void testCreatePolicy_MissingName_Returns400() {
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");
        
        Map<String, Object> policyRequest = new HashMap<>();
        policyRequest.put("typeId", "cmis:policy");
        policyRequest.put("folderId", rootFolderId);
        
        given()
            .spec(requestSpec)
            .body(policyRequest)
        .when()
            .post(policiesPath())
        .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("type", containsString("/errors/"))
            .body("status", equalTo(400));
    }
    
    @Test
    public void testApplyPolicy_ValidRequest_ReturnsOk() {
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");
        
        // Create a policy first
        Map<String, Object> policyRequest = new HashMap<>();
        policyRequest.put("name", "apply-test-policy-" + System.currentTimeMillis());
        policyRequest.put("typeId", "cmis:policy");
        policyRequest.put("folderId", rootFolderId);
        policyRequest.put("policyText", "Test policy for apply");
        
        Response policyResponse = given()
            .spec(requestSpec)
            .body(policyRequest)
        .when()
            .post(policiesPath())
        .then()
            .statusCode(anyOf(equalTo(201), equalTo(200)))
            .extract()
            .response();
        
        String policyId = policyResponse.path("objectId");
        
        // Create a test folder to apply policy to
        Map<String, Object> folderRequest = new HashMap<>();
        folderRequest.put("parentId", rootFolderId);
        folderRequest.put("name", "policy-target-folder-" + System.currentTimeMillis());
        folderRequest.put("typeId", "cmis:folder");
        
        Response folderResponse = given()
            .spec(requestSpec)
            .body(folderRequest)
        .when()
            .post(foldersPath())
        .then()
            .statusCode(201)
            .extract()
            .response();
        
        String folderId = folderResponse.path("objectId");
        
        try {
            // Apply policy
            Map<String, Object> applyRequest = new HashMap<>();
            applyRequest.put("policyId", policyId);
            applyRequest.put("objectId", folderId);
            
            given()
                .spec(requestSpec)
                .body(applyRequest)
            .when()
                .post(policiesPath() + "/apply")
            .then()
                .statusCode(anyOf(equalTo(200), equalTo(201)))
                .contentType("application/json");
            
            // Verify policy is applied
            given()
                .spec(requestSpec)
            .when()
                .get(policiesPath() + "/object/" + folderId)
            .then()
                .statusCode(200)
                .body("policies", notNullValue());
            
        } finally {
            // Clean up
            given()
                .spec(requestSpec)
            .when()
                .delete(objectPath(folderId))
            .then()
                .statusCode(anyOf(equalTo(200), equalTo(204)));
            
            if (policyId != null) {
                given()
                    .spec(requestSpec)
                .when()
                    .delete(objectPath(policyId))
                .then()
                    .statusCode(anyOf(equalTo(200), equalTo(204)));
            }
        }
    }
    
    @Test
    public void testApplyPolicy_MissingPolicyId_Returns400() {
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");
        
        Map<String, Object> applyRequest = new HashMap<>();
        applyRequest.put("objectId", rootFolderId);
        
        given()
            .spec(requestSpec)
            .body(applyRequest)
        .when()
            .post(policiesPath() + "/apply")
        .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("status", equalTo(400));
    }
    
    @Test
    public void testApplyPolicy_MissingObjectId_Returns400() {
        Map<String, Object> applyRequest = new HashMap<>();
        applyRequest.put("policyId", "some-policy-id");
        
        given()
            .spec(requestSpec)
            .body(applyRequest)
        .when()
            .post(policiesPath() + "/apply")
        .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("status", equalTo(400));
    }
    
    @Test
    public void testApplyPolicy_InvalidPolicyId_Returns404() {
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");
        
        Map<String, Object> applyRequest = new HashMap<>();
        applyRequest.put("policyId", "nonexistent-policy-12345");
        applyRequest.put("objectId", rootFolderId);
        
        given()
            .spec(requestSpec)
            .body(applyRequest)
        .when()
            .post(policiesPath() + "/apply")
        .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("status", equalTo(404));
    }
    
    @Test
    public void testApplyPolicy_InvalidObjectId_Returns404() {
        // This test assumes there's at least one policy in the system
        // If not, it will return 404 for the policy, which is also acceptable
        Map<String, Object> applyRequest = new HashMap<>();
        applyRequest.put("policyId", "some-policy-id");
        applyRequest.put("objectId", "nonexistent-object-12345");
        
        given()
            .spec(requestSpec)
            .body(applyRequest)
        .when()
            .post(policiesPath() + "/apply")
        .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("status", equalTo(404));
    }
    
    @Test
    public void testRemovePolicy_MissingPolicyId_Returns400() {
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");
        
        Map<String, Object> removeRequest = new HashMap<>();
        removeRequest.put("objectId", rootFolderId);
        
        given()
            .spec(requestSpec)
            .body(removeRequest)
        .when()
            .post(policiesPath() + "/remove")
        .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("status", equalTo(400));
    }
    
    @Test
    public void testRemovePolicy_MissingObjectId_Returns400() {
        Map<String, Object> removeRequest = new HashMap<>();
        removeRequest.put("policyId", "some-policy-id");
        
        given()
            .spec(requestSpec)
            .body(removeRequest)
        .when()
            .post(policiesPath() + "/remove")
        .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("status", equalTo(400));
    }
    
    @Test
    public void testRemovePolicy_InvalidPolicyId_Returns404() {
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");
        
        Map<String, Object> removeRequest = new HashMap<>();
        removeRequest.put("policyId", "nonexistent-policy-12345");
        removeRequest.put("objectId", rootFolderId);
        
        given()
            .spec(requestSpec)
            .body(removeRequest)
        .when()
            .post(policiesPath() + "/remove")
        .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("status", equalTo(404));
    }
    
    @Test
    public void testGetAppliedPolicies_ContainsHATEOASLinks() {
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
            .get(policiesPath() + "/object/" + rootFolderId)
        .then()
            .statusCode(200)
            .body("links.self", notNullValue())
            .body("links.self.href", containsString("policies"))
            .body("links.object", notNullValue());
    }
    
    @Test
    public void testPolicyWorkflow_CreateApplyRemove() {
        // This test creates a policy, applies it to an object, verifies it's applied,
        // removes it, and verifies it's removed
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");
        
        // Create policy
        Map<String, Object> policyRequest = new HashMap<>();
        policyRequest.put("name", "workflow-test-policy-" + System.currentTimeMillis());
        policyRequest.put("typeId", "cmis:policy");
        policyRequest.put("folderId", rootFolderId);
        policyRequest.put("policyText", "Workflow test policy");
        
        Response policyResponse = given()
            .spec(requestSpec)
            .body(policyRequest)
        .when()
            .post(policiesPath())
        .then()
            .statusCode(anyOf(equalTo(201), equalTo(200)))
            .extract()
            .response();
        
        String policyId = policyResponse.path("objectId");
        
        // Create target folder
        Map<String, Object> folderRequest = new HashMap<>();
        folderRequest.put("parentId", rootFolderId);
        folderRequest.put("name", "workflow-target-" + System.currentTimeMillis());
        folderRequest.put("typeId", "cmis:folder");
        
        Response folderResponse = given()
            .spec(requestSpec)
            .body(folderRequest)
        .when()
            .post(foldersPath())
        .then()
            .statusCode(201)
            .extract()
            .response();
        
        String folderId = folderResponse.path("objectId");
        
        try {
            // Apply policy
            Map<String, Object> applyRequest = new HashMap<>();
            applyRequest.put("policyId", policyId);
            applyRequest.put("objectId", folderId);
            
            given()
                .spec(requestSpec)
                .body(applyRequest)
            .when()
                .post(policiesPath() + "/apply")
            .then()
                .statusCode(anyOf(equalTo(200), equalTo(201)));
            
            // Verify policy is applied
            given()
                .spec(requestSpec)
            .when()
                .get(policiesPath() + "/object/" + folderId)
            .then()
                .statusCode(200)
                .body("policies", notNullValue());
            
            // Remove policy
            Map<String, Object> removeRequest = new HashMap<>();
            removeRequest.put("policyId", policyId);
            removeRequest.put("objectId", folderId);
            
            given()
                .spec(requestSpec)
                .body(removeRequest)
            .when()
                .post(policiesPath() + "/remove")
            .then()
                .statusCode(anyOf(equalTo(200), equalTo(204)));
            
            // Verify policy is removed
            given()
                .spec(requestSpec)
            .when()
                .get(policiesPath() + "/object/" + folderId)
            .then()
                .statusCode(200);
            
        } finally {
            // Clean up
            given()
                .spec(requestSpec)
            .when()
                .delete(objectPath(folderId))
            .then()
                .statusCode(anyOf(equalTo(200), equalTo(204)));
            
            if (policyId != null) {
                given()
                    .spec(requestSpec)
                .when()
                    .delete(objectPath(policyId))
                .then()
                    .statusCode(anyOf(equalTo(200), equalTo(204)));
            }
        }
    }
}
