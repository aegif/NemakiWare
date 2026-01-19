package jp.aegif.nemaki.api.v1;

import io.restassured.response.Response;
import org.junit.Test;
import org.junit.Ignore;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * E2E/Integration tests for DocumentResource endpoints.
 * 
 * Tests the following endpoints:
 * - POST /repositories/{repoId}/documents - Create document
 * - POST /repositories/{repoId}/documents/{docId}/checkout - Check out document
 * - POST /repositories/{repoId}/documents/{docId}/checkin - Check in document
 * - POST /repositories/{repoId}/documents/{docId}/cancelCheckout - Cancel checkout
 * - GET /repositories/{repoId}/documents/{docId}/versions - Get all versions
 * - GET /repositories/{repoId}/documents/{docId}/latestVersion - Get latest version
 * - GET /repositories/{repoId}/documents/checkedout - Get checked out documents
 * 
 * Run with: mvn test -Dtest=DocumentResourceIT -Dnemaki.test.baseUrl=http://localhost:8080/core
 */
@Ignore("Integration tests require a running NemakiWare instance")
public class DocumentResourceIT extends ApiV1TestBase {
    
    @Test
    public void testCreateDocument_ValidRequest_ReturnsCreated() {
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");
        
        Map<String, Object> docRequest = new HashMap<>();
        docRequest.put("parentId", rootFolderId);
        docRequest.put("name", "test-document-" + System.currentTimeMillis() + ".txt");
        docRequest.put("typeId", "cmis:document");
        
        Response response = given()
            .spec(requestSpec)
            .body(docRequest)
        .when()
            .post(documentsPath())
        .then()
            .statusCode(201)
            .contentType("application/json")
            .body("objectId", notNullValue())
            .body("objectTypeId", equalTo("cmis:document"))
            .body("links", notNullValue())
            .extract()
            .response();
        
        // Clean up: delete the created document
        String docId = response.path("objectId");
        if (docId != null) {
            given()
                .spec(requestSpec)
            .when()
                .delete(objectPath(docId))
            .then()
                .statusCode(anyOf(equalTo(200), equalTo(204)));
        }
    }
    
    @Test
    public void testCreateDocument_WithProperties_ReturnsCreated() {
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");
        
        Map<String, Object> nameProperty = new HashMap<>();
        nameProperty.put("value", "test-doc-with-props-" + System.currentTimeMillis() + ".txt");
        nameProperty.put("type", "string");
        
        Map<String, Object> properties = new HashMap<>();
        properties.put("cmis:name", nameProperty);
        
        Map<String, Object> docRequest = new HashMap<>();
        docRequest.put("parentId", rootFolderId);
        docRequest.put("typeId", "cmis:document");
        docRequest.put("properties", properties);
        
        Response response = given()
            .spec(requestSpec)
            .body(docRequest)
        .when()
            .post(documentsPath())
        .then()
            .statusCode(201)
            .body("objectId", notNullValue())
            .extract()
            .response();
        
        // Clean up
        String docId = response.path("objectId");
        if (docId != null) {
            given()
                .spec(requestSpec)
            .when()
                .delete(objectPath(docId))
            .then()
                .statusCode(anyOf(equalTo(200), equalTo(204)));
        }
    }
    
    @Test
    public void testCreateDocument_MissingParentId_Returns400() {
        Map<String, Object> docRequest = new HashMap<>();
        docRequest.put("name", "test-document-no-parent.txt");
        docRequest.put("typeId", "cmis:document");
        
        given()
            .spec(requestSpec)
            .body(docRequest)
        .when()
            .post(documentsPath())
        .then()
            .statusCode(400)
            .contentType("application/problem+json")
            .body("type", containsString("/errors/"))
            .body("status", equalTo(400));
    }
    
    @Test
    public void testCreateDocument_InvalidParentId_Returns404() {
        Map<String, Object> docRequest = new HashMap<>();
        docRequest.put("parentId", "nonexistent-parent-12345");
        docRequest.put("name", "test-document-invalid-parent.txt");
        docRequest.put("typeId", "cmis:document");
        
        given()
            .spec(requestSpec)
            .body(docRequest)
        .when()
            .post(documentsPath())
        .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("status", equalTo(404));
    }
    
    @Test
    public void testGetCheckedOutDocuments_ReturnsOk() {
        given()
            .spec(requestSpec)
        .when()
            .get(documentsPath() + "/checkedout")
        .then()
            .statusCode(200)
            .contentType("application/json")
            .body("objects", notNullValue())
            .body("hasMoreItems", notNullValue());
    }
    
    @Test
    public void testGetCheckedOutDocuments_WithPagination_ReturnsOk() {
        given()
            .spec(requestSpec)
            .queryParam("maxItems", 10)
            .queryParam("skipCount", 0)
        .when()
            .get(documentsPath() + "/checkedout")
        .then()
            .statusCode(200)
            .body("maxItems", equalTo(10))
            .body("skipCount", equalTo(0));
    }
    
    @Test
    public void testCheckout_InvalidDocumentId_Returns404() {
        given()
            .spec(requestSpec)
        .when()
            .post(documentsPath() + "/nonexistent-doc-12345/checkout")
        .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("status", equalTo(404));
    }
    
    @Test
    public void testCheckin_InvalidDocumentId_Returns404() {
        Map<String, Object> checkinRequest = new HashMap<>();
        checkinRequest.put("major", true);
        checkinRequest.put("checkinComment", "Test checkin");
        
        given()
            .spec(requestSpec)
            .body(checkinRequest)
        .when()
            .post(documentsPath() + "/nonexistent-doc-12345/checkin")
        .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("status", equalTo(404));
    }
    
    @Test
    public void testCancelCheckout_InvalidDocumentId_Returns404() {
        given()
            .spec(requestSpec)
        .when()
            .post(documentsPath() + "/nonexistent-doc-12345/cancelCheckout")
        .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("status", equalTo(404));
    }
    
    @Test
    public void testGetVersions_InvalidDocumentId_Returns404() {
        given()
            .spec(requestSpec)
        .when()
            .get(documentsPath() + "/nonexistent-doc-12345/versions")
        .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("status", equalTo(404));
    }
    
    @Test
    public void testGetLatestVersion_InvalidDocumentId_Returns404() {
        given()
            .spec(requestSpec)
        .when()
            .get(documentsPath() + "/nonexistent-doc-12345/latestVersion")
        .then()
            .statusCode(404)
            .contentType("application/problem+json")
            .body("status", equalTo(404));
    }
    
    @Test
    public void testDocumentVersioningWorkflow() {
        // This test creates a document, checks it out, checks it in, and verifies versions
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");
        
        // Create document
        Map<String, Object> docRequest = new HashMap<>();
        docRequest.put("parentId", rootFolderId);
        docRequest.put("name", "versioning-test-" + System.currentTimeMillis() + ".txt");
        docRequest.put("typeId", "cmis:document");
        
        Response createResponse = given()
            .spec(requestSpec)
            .body(docRequest)
        .when()
            .post(documentsPath())
        .then()
            .statusCode(201)
            .extract()
            .response();
        
        String docId = createResponse.path("objectId");
        
        try {
            // Check out
            Response checkoutResponse = given()
                .spec(requestSpec)
            .when()
                .post(documentsPath() + "/" + docId + "/checkout")
            .then()
                .statusCode(anyOf(equalTo(200), equalTo(201)))
                .extract()
                .response();
            
            String pwcId = checkoutResponse.path("objectId");
            
            // Check in
            Map<String, Object> checkinRequest = new HashMap<>();
            checkinRequest.put("major", true);
            checkinRequest.put("checkinComment", "First major version");
            
            given()
                .spec(requestSpec)
                .body(checkinRequest)
            .when()
                .post(documentsPath() + "/" + pwcId + "/checkin")
            .then()
                .statusCode(anyOf(equalTo(200), equalTo(201)));
            
            // Get versions
            given()
                .spec(requestSpec)
            .when()
                .get(documentsPath() + "/" + docId + "/versions")
            .then()
                .statusCode(200)
                .body("objects", notNullValue());
            
        } finally {
            // Clean up
            given()
                .spec(requestSpec)
            .when()
                .delete(objectPath(docId))
            .then()
                .statusCode(anyOf(equalTo(200), equalTo(204)));
        }
    }
}
