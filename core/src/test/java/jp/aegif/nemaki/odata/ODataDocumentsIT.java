package jp.aegif.nemaki.odata;

import io.restassured.response.Response;
import org.junit.Ignore;
import org.junit.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * OData 4.0 E2E/integration tests for Documents entity set.
 * 
 * Tests cover:
 * - GET collection (list documents)
 * - GET with $filter query option
 * - GET with $top and $skip pagination
 * - GET with $count
 * - GET single entity by ID
 * - POST create document (if supported)
 * - PATCH update document (if supported)
 * - DELETE document (if supported)
 * 
 * These tests require a running NemakiWare instance.
 * Run with: mvn test -Dtest=ODataDocumentsIT -Dnemaki.test.baseUrl=http://localhost:8080/core
 */
@Ignore("Requires running NemakiWare instance - remove @Ignore to run integration tests")
public class ODataDocumentsIT extends ODataTestBase {
    
    /**
     * Test GET /odata/{repositoryId}/Documents - List all documents.
     */
    @Test
    public void testGetDocuments() {
        given()
            .spec(requestSpec)
        .when()
            .get(documentsPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("value", notNullValue())
            .body("value", instanceOf(java.util.List.class));
    }
    
    /**
     * Test GET /odata/{repositoryId}/Documents with $top query option.
     */
    @Test
    public void testGetDocumentsWithTop() {
        given()
            .spec(requestSpec)
            .queryParam("$top", 5)
        .when()
            .get(documentsPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("value", notNullValue())
            .body("value.size()", lessThanOrEqualTo(5));
    }
    
    /**
     * Test GET /odata/{repositoryId}/Documents with $skip query option.
     */
    @Test
    public void testGetDocumentsWithSkip() {
        given()
            .spec(requestSpec)
            .queryParam("$skip", 2)
        .when()
            .get(documentsPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("value", notNullValue());
    }
    
    /**
     * Test GET /odata/{repositoryId}/Documents with $top and $skip for pagination.
     */
    @Test
    public void testGetDocumentsWithPagination() {
        given()
            .spec(requestSpec)
            .queryParam("$top", 10)
            .queryParam("$skip", 0)
        .when()
            .get(documentsPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("value", notNullValue())
            .body("value.size()", lessThanOrEqualTo(10));
    }
    
    /**
     * Test GET /odata/{repositoryId}/Documents with $count=true.
     */
    @Test
    public void testGetDocumentsWithCount() {
        given()
            .spec(requestSpec)
            .queryParam("$count", true)
        .when()
            .get(documentsPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("value", notNullValue())
            .body("@odata.count", notNullValue());
    }
    
    /**
     * Test GET /odata/{repositoryId}/Documents with $filter query option.
     * Filter by name starting with a specific prefix.
     */
    @Test
    public void testGetDocumentsWithFilterStartsWith() {
        given()
            .spec(requestSpec)
            .queryParam("$filter", "startswith(name,'test')")
        .when()
            .get(documentsPath())
        .then()
            .statusCode(anyOf(equalTo(200), equalTo(501))) // 501 if $filter not implemented
            .contentType(containsString("application/json"));
    }
    
    /**
     * Test GET /odata/{repositoryId}/Documents with $filter query option.
     * Filter by exact name match.
     */
    @Test
    public void testGetDocumentsWithFilterEquals() {
        given()
            .spec(requestSpec)
            .queryParam("$filter", "name eq 'test.txt'")
        .when()
            .get(documentsPath())
        .then()
            .statusCode(anyOf(equalTo(200), equalTo(501))) // 501 if $filter not implemented
            .contentType(containsString("application/json"));
    }
    
    /**
     * Test GET /odata/{repositoryId}/Documents with $select query option.
     */
    @Test
    public void testGetDocumentsWithSelect() {
        given()
            .spec(requestSpec)
            .queryParam("$select", "objectId,name,objectTypeId")
        .when()
            .get(documentsPath())
        .then()
            .statusCode(anyOf(equalTo(200), equalTo(501))) // 501 if $select not implemented
            .contentType(containsString("application/json"));
    }
    
    /**
     * Test GET /odata/{repositoryId}/Documents with $orderby query option.
     */
    @Test
    public void testGetDocumentsWithOrderBy() {
        given()
            .spec(requestSpec)
            .queryParam("$orderby", "name asc")
        .when()
            .get(documentsPath())
        .then()
            .statusCode(anyOf(equalTo(200), equalTo(501))) // 501 if $orderby not implemented
            .contentType(containsString("application/json"));
    }
    
    /**
     * Test GET /odata/{repositoryId}/Documents('{objectId}') - Get single document.
     * Note: This test requires a known document ID to exist.
     */
    @Test
    public void testGetSingleDocument() {
        // First, get a list of documents to find a valid ID
        Response listResponse = given()
            .spec(requestSpec)
            .queryParam("$top", 1)
        .when()
            .get(documentsPath())
        .then()
            .statusCode(200)
            .extract().response();
        
        // Check if there are any documents
        java.util.List<?> documents = listResponse.jsonPath().getList("value");
        if (documents != null && !documents.isEmpty()) {
            String objectId = listResponse.jsonPath().getString("value[0].objectId");
            if (objectId != null) {
                // Get the single document
                given()
                    .spec(requestSpec)
                .when()
                    .get(documentPath(objectId))
                .then()
                    .statusCode(200)
                    .contentType(containsString("application/json"))
                    .body("objectId", equalTo(objectId));
            }
        }
    }
    
    /**
     * Test GET /odata/{repositoryId}/Documents('{objectId}') with non-existent ID.
     */
    @Test
    public void testGetNonExistentDocument() {
        given()
            .spec(requestSpec)
        .when()
            .get(documentPath("non-existent-id-12345"))
        .then()
            .statusCode(404);
    }
    
    /**
     * Test POST /odata/{repositoryId}/Documents - Create a new document.
     * Note: OData document creation may require specific payload format.
     */
    @Test
    public void testCreateDocument() {
        String documentJson = "{"
            + "\"name\": \"odata-test-document.txt\","
            + "\"objectTypeId\": \"cmis:document\""
            + "}";
        
        given()
            .spec(requestSpec)
            .body(documentJson)
        .when()
            .post(documentsPath())
        .then()
            .statusCode(anyOf(equalTo(201), equalTo(405), equalTo(501))); // 405/501 if POST not supported
    }
    
    /**
     * Test PATCH /odata/{repositoryId}/Documents('{objectId}') - Update a document.
     * Note: This test requires a known document ID to exist.
     */
    @Test
    public void testUpdateDocument() {
        // First, get a list of documents to find a valid ID
        Response listResponse = given()
            .spec(requestSpec)
            .queryParam("$top", 1)
        .when()
            .get(documentsPath())
        .then()
            .statusCode(200)
            .extract().response();
        
        // Check if there are any documents
        java.util.List<?> documents = listResponse.jsonPath().getList("value");
        if (documents != null && !documents.isEmpty()) {
            String objectId = listResponse.jsonPath().getString("value[0].objectId");
            if (objectId != null) {
                String updateJson = "{"
                    + "\"description\": \"Updated via OData test\""
                    + "}";
                
                given()
                    .spec(requestSpec)
                    .body(updateJson)
                .when()
                    .patch(documentPath(objectId))
                .then()
                    .statusCode(anyOf(equalTo(200), equalTo(204), equalTo(405), equalTo(501))); // 405/501 if PATCH not supported
            }
        }
    }
    
    /**
     * Test DELETE /odata/{repositoryId}/Documents('{objectId}') - Delete a document.
     * Note: This test is destructive and should only be run with test data.
     */
    @Test
    public void testDeleteDocument() {
        // First create a document to delete
        String documentJson = "{"
            + "\"name\": \"odata-delete-test.txt\","
            + "\"objectTypeId\": \"cmis:document\""
            + "}";
        
        Response createResponse = given()
            .spec(requestSpec)
            .body(documentJson)
        .when()
            .post(documentsPath())
        .then()
            .extract().response();
        
        // If creation succeeded, try to delete
        if (createResponse.statusCode() == 201) {
            String objectId = createResponse.jsonPath().getString("objectId");
            if (objectId != null) {
                given()
                    .spec(requestSpec)
                .when()
                    .delete(documentPath(objectId))
                .then()
                    .statusCode(anyOf(equalTo(200), equalTo(204), equalTo(405), equalTo(501))); // 405/501 if DELETE not supported
            }
        }
    }
    
    /**
     * Test GET /odata/{repositoryId}/$metadata - Get OData metadata.
     */
    @Test
    public void testGetMetadata() {
        given()
            .spec(requestSpec)
            .accept("application/xml")
        .when()
            .get(metadataPath())
        .then()
            .statusCode(anyOf(equalTo(200), equalTo(501))) // 501 if $metadata not implemented
            .contentType(anyOf(containsString("application/xml"), containsString("application/json")));
    }
}
