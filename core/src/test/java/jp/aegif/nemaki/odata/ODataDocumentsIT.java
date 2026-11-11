package jp.aegif.nemaki.odata;

import io.restassured.response.Response;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

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
@Disabled("Requires running NemakiWare instance - remove @Ignore to run integration tests")
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
            // GPath needs the literal '@odata.count' key quoted (the '@' and '.'
            // are otherwise interpreted as navigation/attribute operators).
            .body("'@odata.count'", notNullValue());
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
            .statusCode(200)
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
            .statusCode(200)
            .contentType(containsString("application/json"));
    }
    
    /**
     * Test GET /odata/{repositoryId}/Documents with $filter query option.
     * Filter by name containing a substring.
     */
    @Test
    public void testGetDocumentsWithFilterContains() {
        java.util.Map<String, Object> withoutTheFilter = java.util.Map.of();
        Response response = given()
            .spec(requestSpec)
            .queryParams(withoutTheFilter)
            .queryParam("$filter", "contains(name,'test')")
        .when()
            .get(documentsPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .extract().response();
        assertOnlyAdmittedNames(response, name -> name.contains("test"), "a name containing 'test'");
        assertTheRequestWithoutTheFilterReturnsANameOutside(documentsPath(), withoutTheFilter, name -> name.contains("test"), "a name containing 'test'");
        assertNoAdmittedNameIsLeftOut(documentsPath(), response, name -> name.contains("test"), "a name containing 'test'");
    }

    /**
     * Test GET /odata/{repositoryId}/Documents with $filter=contains() on a part of a name the collection has:
     * the name is returned, whatever the data (R248, 3.4.2).
     */
    @Test
    public void testDocumentsContainsFindsANameFromAPartOfIt() {
        assertContainsFindsANameFromAPartOfIt(documentsPath());
    }
    
    /**
     * Test GET /odata/{repositoryId}/Documents with $filter query option.
     * Filter by name ending with a suffix.
     */
    @Test
    public void testGetDocumentsWithFilterEndsWith() {
        given()
            .spec(requestSpec)
            .queryParam("$filter", "endswith(name,'.pdf')")
        .when()
            .get(documentsPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"));
    }
    
    /**
     * Test GET /odata/{repositoryId}/Documents with compound $filter query option.
     * Filter using AND/OR operators.
     */
    @Test
    public void testGetDocumentsWithFilterCompound() {
        java.util.Map<String, Object> withoutTheFilter = java.util.Map.of();
        Response response = given()
            .spec(requestSpec)
            .queryParams(withoutTheFilter)
            .queryParam("$filter", "name eq 'test.pdf' or contains(name,'draft')")
        .when()
            .get(documentsPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .extract().response();
        assertOnlyAdmittedNames(response, name -> name.equals("test.pdf") || name.contains("draft"), "'test.pdf' or a name containing 'draft'");
        assertTheRequestWithoutTheFilterReturnsANameOutside(documentsPath(), withoutTheFilter, name -> name.equals("test.pdf") || name.contains("draft"), "'test.pdf' or a name containing 'draft'");
        assertNoAdmittedNameIsLeftOut(documentsPath(), response, name -> name.equals("test.pdf") || name.contains("draft"), "'test.pdf' or a name containing 'draft'");
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
            .statusCode(200)
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
            .statusCode(200)
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
        // Unique name so the test is repeatable against a persistent repository
        // (a fixed name would collide on re-run and now correctly yields 409).
        String documentJson = "{"
            + "\"name\": \"odata-test-document-" + System.nanoTime() + ".txt\","
            + "\"objectTypeId\": \"cmis:document\""
            + "}";

        Response response = given()
            .spec(requestSpec)
            .body(documentJson)
        .when()
            .post(documentsPath())
        .then()
            .statusCode(anyOf(equalTo(201), equalTo(405))) // 405 if POST not supported
            .extract().response();

        // Clean up the created document so runs do not accumulate residue.
        if (response.getStatusCode() == 201) {
            String createdId = response.jsonPath().getString("objectId");
            if (createdId != null) {
                given().spec(requestSpec).when().delete(documentPath(createdId));
            }
        }
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
                    .statusCode(anyOf(equalTo(200), equalTo(204), equalTo(405))); // 405 if PATCH not supported
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
                    .statusCode(anyOf(equalTo(200), equalTo(204), equalTo(405))); // 405 if DELETE not supported
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
            .statusCode(200)
            .contentType(anyOf(containsString("application/xml"), containsString("application/json")));
    }
    
    /**
     * GET /odata/{repositoryId}/Documents with $expand=parent is refused (R260, 3.4.2). It used to be answered 200 with no navigation property at all, and this test,
     * reading the status alone, took that for the expansion.
     */
    @Test
    public void testGetDocumentsWithExpandParent() {
        assertExpandOnAListingIsRefused(documentsPath(), java.util.Map.of("$expand", "parent", "$top", 5));
    }
    
    /**
     * GET /odata/{repositoryId}/Documents with $expand=parents is refused (R260, 3.4.2). It used to be answered 200 with no navigation property at all, and this test,
     * reading the status alone, took that for the expansion.
     */
    @Test
    public void testGetDocumentsWithExpandParents() {
        assertExpandOnAListingIsRefused(documentsPath(), java.util.Map.of("$expand", "parents", "$top", 5));
    }
    
    /**
     * Test GET /odata/{repositoryId}/Documents('{objectId}') with $expand=parent and with $expand=parents: the folder
     * the document is in, as its parents navigation answers it (R260, 3.4.2). $expand=parent used to answer null for a
     * document that is in a folder.
     */
    @Test
    public void testGetSingleDocumentWithExpandParent() {
        String objectId = given()
            .spec(requestSpec)
            .queryParam("$top", 1)
        .when()
            .get(documentsPath())
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("value[0].objectId");
        org.junit.jupiter.api.Assertions.assertNotNull(objectId, "there is no document to read the parent of");
        java.util.List<String> parents = idsAt(documentPath(objectId) + "/parents");
        org.junit.jupiter.api.Assertions.assertEquals(1, parents.size(), "the document is not in one folder: " + parents);

        given()
            .spec(requestSpec)
            .queryParam("$expand", "parent")
        .when()
            .get(documentPath(objectId))
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("parent.objectId", equalTo(parents.get(0)));

        java.util.List<String> expanded = given()
            .spec(requestSpec)
            .queryParam("$expand", "parents")
        .when()
            .get(documentPath(objectId))
        .then()
            .statusCode(200)
            .extract().jsonPath().getList("parents.objectId", String.class);
        org.junit.jupiter.api.Assertions.assertEquals(parents, expanded, "$expand=parents did not answer the document's parents");
    }
    
    /**
     * GET /odata/{repositoryId}/Documents with $expand and $select is refused (R260, 3.4.2). It used to be answered 200 with no navigation property at all, and this test,
     * reading the status alone, took that for the expansion.
     */
    @Test
    public void testGetDocumentsWithExpandAndSelect() {
        assertExpandOnAListingIsRefused(documentsPath(), java.util.Map.of("$expand", "parent", "$select", "objectId,name", "$top", 5));
    }
    
    /**
     * GET /odata/{repositoryId}/Documents with $expand and $filter is refused (R260, 3.4.2). It used to be answered 200 with no navigation property at all, and this test,
     * reading the status alone, took that for the expansion.
     */
    @Test
    public void testGetDocumentsWithExpandAndFilter() {
        assertExpandOnAListingIsRefused(documentsPath(), java.util.Map.of("$expand", "parent", "$filter", "contains(name,'test')", "$top", 5));
    }
    
    // ==================== OData Actions Tests ====================
    
    /**
     * Test POST /odata/{repositoryId}/Documents('objectId')/NemakiWare.CMIS.CheckOut
     * CheckOut action should return the Private Working Copy (PWC).
     *
     * <p>The document is this test's own. It used to ask the listing for one that is not checked out
     * ({@code $filter=isVersionSeriesCheckedOut eq false}); the search index does not hold that property, and since
     * 3.4.1 a query on it is refused with a 400 instead of being answered with the condition dropped — so the test
     * failed on its first request and never reached the action (red on CI from 2026-10-27). It also checked out
     * whichever document came first and left it so.
     */
    @Test
    public void testCheckOutAction() throws Throwable {
        String name = "odata-checkout-" + System.nanoTime() + ".txt";
        String objectId = given()
            .spec(requestSpec)
            .body("{\"name\": \"" + name + "\", \"objectTypeId\": \"cmis:document\"}")
        .when()
            .post(documentsPath())
        .then()
            .statusCode(201)
            .extract().response().jsonPath().getString("objectId");
        org.junit.jupiter.api.Assertions.assertNotNull(objectId, "the document this test checks out was not created");

        Throwable failed = null;
        try {
            Response checkedOut = given()
                .spec(requestSpec)
            .when()
                .post(documentPath(objectId) + "/NemakiWare.CMIS.CheckOut")
            .then()
                .statusCode(200)
                .contentType(containsString("application/json"))
                .extract().response();
            String pwcId = checkedOut.jsonPath().getString("objectId");
            org.junit.jupiter.api.Assertions.assertNotNull(pwcId, "CheckOut answered no entity: " + checkedOut.asString());
            org.junit.jupiter.api.Assertions.assertNotEquals(objectId, pwcId, "CheckOut answered the document, not its working copy");
            org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE, checkedOut.jsonPath().getBoolean("isPrivateWorkingCopy"),
                    "CheckOut did not answer a private working copy: " + checkedOut.asString());
            org.junit.jupiter.api.Assertions.assertEquals(name, checkedOut.jsonPath().getString("name"),
                    "CheckOut answered the working copy of another document");
        } catch (Throwable thrown) {
            failed = thrown;
        }
        String leftBehind = cleanUpTheCheckedOutDocument(objectId, name);
        if (failed != null) {
            if (leftBehind != null) {
                failed.addSuppressed(new IllegalStateException("and this test's own document was left behind — " + leftBehind));
            }
            throw failed;
        }
        org.junit.jupiter.api.Assertions.assertNull(leftBehind, "this test's own document was left behind — " + leftBehind);
    }

    /**
     * Leaves the repository as it was: the working copy first, then the document. The working copy is the one the
     * DOCUMENT names, never an id an answer gave — a CheckOut that answered another document's id must not have that
     * document's check-out cancelled by this test. Answers what was left behind, or null; it does not throw.
     */
    private String cleanUpTheCheckedOutDocument(String objectId, String name) {
        try {
            Response document = given().spec(requestSpec).when().get(documentPath(objectId));
            String workingCopy = document.statusCode() == 200 ? document.jsonPath().getString("versionSeriesCheckedOutId") : null;
            int cancelled = workingCopy == null ? 204
                    : given().spec(requestSpec).when().post(documentPath(workingCopy) + "/NemakiWare.CMIS.CancelCheckOut").statusCode();
            int deleted = given().spec(requestSpec).when().delete(documentPath(objectId)).statusCode();
            int after = given().spec(requestSpec).when().get(documentPath(objectId)).statusCode();
            int copyAfter = workingCopy == null ? 404 : given().spec(requestSpec).when().get(documentPath(workingCopy)).statusCode();
            if ((cancelled != 200 && cancelled != 204) || deleted != 204 || after != 404 || copyAfter != 404) {
                return name + " (" + objectId + "): the document answered " + document.statusCode() + " and named the working copy "
                        + workingCopy + "; CancelCheckOut " + cancelled + ", DELETE " + deleted + ", then the document answers " + after
                        + " and the working copy " + copyAfter;
            }
            return null;
        } catch (RuntimeException cleanUpFailed) {
            return name + " (" + objectId + "): the clean-up itself failed — " + cleanUpFailed;
        }
    }
    
    /**
     * Test POST /odata/{repositoryId}/Documents('objectId')/NemakiWare.CMIS.CancelCheckOut
     * CancelCheckOut action should cancel the checkout and delete the PWC.
     */
    @Test
    public void testCancelCheckOutAction() {
        // First, get a checked-out document (PWC)
        Response listResponse = given()
            .spec(requestSpec)
            .queryParam("$top", 1)
            .queryParam("$filter", "isPrivateWorkingCopy eq true")
        .when()
            .get(documentsPath())
        .then()
            .statusCode(200)
            .extract().response();
        
        java.util.List<?> documents = listResponse.jsonPath().getList("value");
        if (documents == null || documents.isEmpty()) {
            // No PWC available for cancel checkout test
            return;
        }
        
        String objectId = listResponse.jsonPath().getString("value[0].objectId");
        
        // Execute CancelCheckOut action
        given()
            .spec(requestSpec)
            .contentType("application/json")
        .when()
            .post(documentsPath() + "('" + objectId + "')/NemakiWare.CMIS.CancelCheckOut")
        .then()
            .statusCode(anyOf(equalTo(200), equalTo(204)));
    }
    
    /**
     * Test POST /odata/{repositoryId}/Documents('objectId')/NemakiWare.CMIS.CheckIn
     * CheckIn action should check in the PWC and create a new version.
     */
    @Test
    public void testCheckInAction() {
        // First, get a checked-out document (PWC)
        Response listResponse = given()
            .spec(requestSpec)
            .queryParam("$top", 1)
            .queryParam("$filter", "isPrivateWorkingCopy eq true")
        .when()
            .get(documentsPath())
        .then()
            .statusCode(200)
            .extract().response();
        
        java.util.List<?> documents = listResponse.jsonPath().getList("value");
        if (documents == null || documents.isEmpty()) {
            // No PWC available for checkin test
            return;
        }
        
        String objectId = listResponse.jsonPath().getString("value[0].objectId");
        
        // Execute CheckIn action with parameters
        String checkInParams = "{\"major\": true, \"checkinComment\": \"Test checkin via OData\"}";
        
        given()
            .spec(requestSpec)
            .contentType("application/json")
            .body(checkInParams)
        .when()
            .post(documentsPath() + "('" + objectId + "')/NemakiWare.CMIS.CheckIn")
        .then()
            .statusCode(anyOf(equalTo(200), equalTo(204)))
            .contentType(anyOf(containsString("application/json"), emptyOrNullString()));
    }
    
    /**
     * Test POST /odata/{repositoryId}/Documents('objectId')/NemakiWare.CMIS.CheckIn
     * CheckIn action with minor version.
     */
    @Test
    public void testCheckInActionMinorVersion() {
        // First, get a checked-out document (PWC)
        Response listResponse = given()
            .spec(requestSpec)
            .queryParam("$top", 1)
            .queryParam("$filter", "isPrivateWorkingCopy eq true")
        .when()
            .get(documentsPath())
        .then()
            .statusCode(200)
            .extract().response();
        
        java.util.List<?> documents = listResponse.jsonPath().getList("value");
        if (documents == null || documents.isEmpty()) {
            // No PWC available for checkin test
            return;
        }
        
        String objectId = listResponse.jsonPath().getString("value[0].objectId");
        
        // Execute CheckIn action with minor version
        String checkInParams = "{\"major\": false, \"checkinComment\": \"Minor version via OData\"}";
        
        given()
            .spec(requestSpec)
            .contentType("application/json")
            .body(checkInParams)
        .when()
            .post(documentsPath() + "('" + objectId + "')/NemakiWare.CMIS.CheckIn")
        .then()
            .statusCode(anyOf(equalTo(200), equalTo(204)));
    }
    
    // ==================== OData $search Tests ====================
    
    /**
     * Test GET /odata/{repositoryId}/Documents with $search query option.
     * Full-text search using Solr integration.
     */
    @Test
    public void testGetDocumentsWithSearch() {
        given()
            .spec(requestSpec)
            .queryParam("$search", "test")
        .when()
            .get(documentsPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"));
    }
    
    /**
     * Test GET /odata/{repositoryId}/Documents with $search and $top.
     * Full-text search with pagination.
     */
    @Test
    public void testGetDocumentsWithSearchAndTop() {
        given()
            .spec(requestSpec)
            .queryParam("$search", "document")
            .queryParam("$top", 5)
        .when()
            .get(documentsPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"));
    }
    
    /**
     * Test GET /odata/{repositoryId}/Documents with $search and $filter combined.
     * Full-text search combined with property filter.
     */
    @Test
    public void testGetDocumentsWithSearchAndFilter() {
        java.util.Map<String, Object> withoutTheFilter = java.util.Map.of("$search", "invoice", "$top", 10);
        Response response = given()
            .spec(requestSpec)
            .queryParams(withoutTheFilter)
            .queryParam("$filter", "contains(name,'report')")
        .when()
            .get(documentsPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .extract().response();
        assertOnlyAdmittedNames(response, name -> name.contains("report"), "a name containing 'report'");
        // Not asked: the same request without the filter has a name outside it. Whether it does depends on
        // the content: on bedroom it does (measured 2026-11-05 — a server that dropped the filter returned
        // "CMIS 1.1 Specification Resources.pdf" and this failed). On CI's fresh database it was not measured —
        // Patch_InitialContentSetup creates the same PDF there, so it may (3.4.2).
    }
    
    /**
     * Test GET /odata/{repositoryId}/Documents with $search and $select.
     * Full-text search with property projection.
     */
    @Test
    public void testGetDocumentsWithSearchAndSelect() {
        given()
            .spec(requestSpec)
            .queryParam("$search", "contract")
            .queryParam("$select", "objectId,name,contentStreamMimeType")
            .queryParam("$top", 5)
        .when()
            .get(documentsPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"));
    }
    
    /**
     * Test GET /odata/{repositoryId}/Documents with $search and $orderby.
     * Full-text search with ordering.
     */
    @Test
    public void testGetDocumentsWithSearchAndOrderBy() {
        given()
            .spec(requestSpec)
            .queryParam("$search", "report")
            .queryParam("$orderby", "lastModificationDate desc")
            .queryParam("$top", 10)
        .when()
            .get(documentsPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"));
    }
    
    /**
     * Test GET /odata/{repositoryId}/Documents with $search containing special characters.
     * Full-text search with special characters should be properly escaped.
     */
    @Test
    public void testGetDocumentsWithSearchSpecialChars() {
        given()
            .spec(requestSpec)
            .queryParam("$search", "test's document")
        .when()
            .get(documentsPath())
        .then()
            .statusCode(anyOf(equalTo(200), equalTo(400))); // 400 if invalid search term
    }
}
