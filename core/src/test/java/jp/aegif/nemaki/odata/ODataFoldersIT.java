package jp.aegif.nemaki.odata;

import io.restassured.response.Response;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * OData 4.0 E2E/integration tests for Folders entity set.
 * 
 * Tests cover:
 * - GET collection (list folders)
 * - GET with $filter query option
 * - GET with $top and $skip pagination
 * - GET with $count
 * - GET single entity by ID
 * - POST create folder (if supported)
 * - PATCH update folder (if supported)
 * - DELETE folder (if supported)
 * 
 * These tests require a running NemakiWare instance.
 * Run with: mvn test -Dtest=ODataFoldersIT -Dnemaki.test.baseUrl=http://localhost:8080/core
 */
@Disabled("Requires running NemakiWare instance - remove @Ignore to run integration tests")
public class ODataFoldersIT extends ODataTestBase {
    
    /**
     * Test GET /odata/{repositoryId}/Folders - List all folders.
     */
    @Test
    public void testGetFolders() {
        given()
            .spec(requestSpec)
        .when()
            .get(foldersPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("value", notNullValue())
            .body("value", instanceOf(java.util.List.class));
    }
    
    /**
     * Test GET /odata/{repositoryId}/Folders with $top query option.
     */
    @Test
    public void testGetFoldersWithTop() {
        given()
            .spec(requestSpec)
            .queryParam("$top", 5)
        .when()
            .get(foldersPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("value", notNullValue())
            .body("value.size()", lessThanOrEqualTo(5));
    }
    
    /**
     * Test GET /odata/{repositoryId}/Folders with $skip query option.
     */
    @Test
    public void testGetFoldersWithSkip() {
        given()
            .spec(requestSpec)
            .queryParam("$skip", 2)
        .when()
            .get(foldersPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("value", notNullValue());
    }
    
    /**
     * Test GET /odata/{repositoryId}/Folders with $top and $skip for pagination.
     */
    @Test
    public void testGetFoldersWithPagination() {
        given()
            .spec(requestSpec)
            .queryParam("$top", 10)
            .queryParam("$skip", 0)
        .when()
            .get(foldersPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("value", notNullValue())
            .body("value.size()", lessThanOrEqualTo(10));
    }
    
    /**
     * Test GET /odata/{repositoryId}/Folders with $count=true.
     */
    @Test
    public void testGetFoldersWithCount() {
        given()
            .spec(requestSpec)
            .queryParam("$count", true)
        .when()
            .get(foldersPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("value", notNullValue())
            // GPath needs the literal '@odata.count' key quoted (the '@' and '.'
            // are otherwise interpreted as navigation/attribute operators).
            .body("'@odata.count'", notNullValue());
    }
    
    /**
     * Test GET /odata/{repositoryId}/Folders with $filter query option.
     * Filter by name starting with a specific prefix.
     */
    @Test
    public void testGetFoldersWithFilterStartsWith() {
        given()
            .spec(requestSpec)
            .queryParam("$filter", "startswith(name,'test')")
        .when()
            .get(foldersPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"));
    }
    
    /**
     * Test GET /odata/{repositoryId}/Folders with $filter query option.
     * Filter by exact name match.
     */
    @Test
    public void testGetFoldersWithFilterEquals() {
        given()
            .spec(requestSpec)
            .queryParam("$filter", "name eq 'TestFolder'")
        .when()
            .get(foldersPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"));
    }
    
    /**
     * Test GET /odata/{repositoryId}/Folders with $filter query option.
     * Filter by name containing a substring.
     */
    @Test
    public void testGetFoldersWithFilterContains() {
        given()
            .spec(requestSpec)
            .queryParam("$filter", "contains(name,'folder')")
        .when()
            .get(foldersPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"));
    }
    
    /**
     * Test GET /odata/{repositoryId}/Folders with $filter query option.
     * Filter by name ending with a suffix.
     */
    @Test
    public void testGetFoldersWithFilterEndsWith() {
        given()
            .spec(requestSpec)
            .queryParam("$filter", "endswith(name,'s')")
        .when()
            .get(foldersPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"));
    }
    
    /**
     * Test GET /odata/{repositoryId}/Folders with compound $filter query option.
     * Filter using AND/OR operators.
     */
    @Test
    public void testGetFoldersWithFilterCompound() {
        given()
            .spec(requestSpec)
            .queryParam("$filter", "name eq 'TestFolder' or contains(name,'test')")
        .when()
            .get(foldersPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"));
    }
    
    /**
     * Test GET /odata/{repositoryId}/Folders with $select query option.
     */
    @Test
    public void testGetFoldersWithSelect() {
        given()
            .spec(requestSpec)
            .queryParam("$select", "objectId,name,objectTypeId,path")
        .when()
            .get(foldersPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"));
    }
    
    /**
     * Test GET /odata/{repositoryId}/Folders with $orderby query option.
     */
    @Test
    public void testGetFoldersWithOrderBy() {
        given()
            .spec(requestSpec)
            .queryParam("$orderby", "name asc")
        .when()
            .get(foldersPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"));
    }
    
    /**
     * Test GET /odata/{repositoryId}/Folders('{objectId}') - Get single folder.
     * Note: This test requires a known folder ID to exist.
     */
    @Test
    public void testGetSingleFolder() {
        // First, get a list of folders to find a valid ID
        Response listResponse = given()
            .spec(requestSpec)
            .queryParam("$top", 1)
        .when()
            .get(foldersPath())
        .then()
            .statusCode(200)
            .extract().response();
        
        // Check if there are any folders
        java.util.List<?> folders = listResponse.jsonPath().getList("value");
        if (folders != null && !folders.isEmpty()) {
            String objectId = listResponse.jsonPath().getString("value[0].objectId");
            if (objectId != null) {
                // Get the single folder
                given()
                    .spec(requestSpec)
                .when()
                    .get(folderPath(objectId))
                .then()
                    .statusCode(200)
                    .contentType(containsString("application/json"))
                    .body("objectId", equalTo(objectId));
            }
        }
    }
    
    /**
     * Test GET /odata/{repositoryId}/Folders('{objectId}') with non-existent ID.
     */
    @Test
    public void testGetNonExistentFolder() {
        given()
            .spec(requestSpec)
        .when()
            .get(folderPath("non-existent-id-12345"))
        .then()
            .statusCode(404);
    }
    
    /**
     * Test POST /odata/{repositoryId}/Folders - Create a new folder.
     * Note: OData folder creation may require specific payload format.
     */
    @Test
    public void testCreateFolder() {
        String folderJson = "{"
            + "\"name\": \"odata-test-folder\","
            + "\"objectTypeId\": \"cmis:folder\""
            + "}";
        
        given()
            .spec(requestSpec)
            .body(folderJson)
        .when()
            .post(foldersPath())
        .then()
            .statusCode(anyOf(equalTo(201), equalTo(405))); // 405 if POST not supported
    }
    
    /**
     * Test PATCH /odata/{repositoryId}/Folders('{objectId}') - Update a folder.
     * Note: This test requires a known folder ID to exist.
     */
    @Test
    public void testUpdateFolder() {
        // First, get a list of folders to find a valid ID
        Response listResponse = given()
            .spec(requestSpec)
            .queryParam("$top", 1)
        .when()
            .get(foldersPath())
        .then()
            .statusCode(200)
            .extract().response();
        
        // Check if there are any folders
        java.util.List<?> folders = listResponse.jsonPath().getList("value");
        if (folders != null && !folders.isEmpty()) {
            String objectId = listResponse.jsonPath().getString("value[0].objectId");
            if (objectId != null) {
                String updateJson = "{"
                    + "\"description\": \"Updated via OData test\""
                    + "}";
                
                given()
                    .spec(requestSpec)
                    .body(updateJson)
                .when()
                    .patch(folderPath(objectId))
                .then()
                    .statusCode(anyOf(equalTo(200), equalTo(204), equalTo(405))); // 405 if PATCH not supported
            }
        }
    }
    
    /**
     * Test DELETE /odata/{repositoryId}/Folders('{objectId}') - Delete a folder.
     * Note: This test is destructive and should only be run with test data.
     */
    @Test
    public void testDeleteFolder() {
        // First create a folder to delete
        String folderJson = "{"
            + "\"name\": \"odata-delete-test-folder\","
            + "\"objectTypeId\": \"cmis:folder\""
            + "}";
        
        Response createResponse = given()
            .spec(requestSpec)
            .body(folderJson)
        .when()
            .post(foldersPath())
        .then()
            .extract().response();
        
        // If creation succeeded, try to delete
        if (createResponse.statusCode() == 201) {
            String objectId = createResponse.jsonPath().getString("objectId");
            if (objectId != null) {
                given()
                    .spec(requestSpec)
                .when()
                    .delete(folderPath(objectId))
                .then()
                    .statusCode(anyOf(equalTo(200), equalTo(204), equalTo(405))); // 405 if DELETE not supported
            }
        }
    }
    
    /**
     * Test GET root folder - The root folder should always exist.
     */
    @Test
    public void testGetRootFolder() {
        // Root folder typically has a well-known path "/"
        given()
            .spec(requestSpec)
            .queryParam("$filter", "path eq '/'")
        .when()
            .get(foldersPath())
        .then()
            .statusCode(200);
    }
    
    /**
     * Test GET /odata/{repositoryId}/Folders with $expand=parent query option.
     * Expands the parent folder navigation property.
     */
    @Test
    public void testGetFoldersWithExpandParent() {
        given()
            .spec(requestSpec)
            .queryParam("$expand", "parent")
            .queryParam("$top", 5)
        .when()
            .get(foldersPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"));
    }
    
    /**
     * Test GET /odata/{repositoryId}/Folders with $expand=children query option.
     * Expands the children collection navigation property.
     */
    @Test
    public void testGetFoldersWithExpandChildren() {
        given()
            .spec(requestSpec)
            .queryParam("$expand", "children")
            .queryParam("$top", 5)
        .when()
            .get(foldersPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"));
    }
    
    /**
     * Test GET /odata/{repositoryId}/Folders('{objectId}') with $expand=children.
     * Expands the children for a single folder.
     */
    @Test
    public void testGetSingleFolderWithExpandChildren() {
        // First, get a list of folders to find a valid ID
        Response listResponse = given()
            .spec(requestSpec)
            .queryParam("$top", 1)
        .when()
            .get(foldersPath())
        .then()
            .statusCode(200)
            .extract().response();
        
        // Check if there are any folders
        java.util.List<?> folders = listResponse.jsonPath().getList("value");
        if (folders != null && !folders.isEmpty()) {
            String objectId = listResponse.jsonPath().getString("value[0].objectId");
            if (objectId != null) {
                // Get the single folder with $expand=children
                given()
                    .spec(requestSpec)
                    .queryParam("$expand", "children")
                .when()
                    .get(folderPath(objectId))
                .then()
                    .statusCode(200)
                    .contentType(containsString("application/json"));
            }
        }
    }
    
    /**
     * Test GET /odata/{repositoryId}/Folders('{objectId}') with $expand=parent.
     * Expands the parent folder for a single folder.
     */
    @Test
    public void testGetSingleFolderWithExpandParent() {
        // First, get a list of folders to find a valid ID
        Response listResponse = given()
            .spec(requestSpec)
            .queryParam("$top", 1)
        .when()
            .get(foldersPath())
        .then()
            .statusCode(200)
            .extract().response();
        
        // Check if there are any folders
        java.util.List<?> folders = listResponse.jsonPath().getList("value");
        if (folders != null && !folders.isEmpty()) {
            String objectId = listResponse.jsonPath().getString("value[0].objectId");
            if (objectId != null) {
                // Get the single folder with $expand=parent
                given()
                    .spec(requestSpec)
                    .queryParam("$expand", "parent")
                .when()
                    .get(folderPath(objectId))
                .then()
                    .statusCode(200)
                    .contentType(containsString("application/json"));
            }
        }
    }
    
    /**
     * Test GET /odata/{repositoryId}/Folders with combined $expand and $select.
     */
    @Test
    public void testGetFoldersWithExpandAndSelect() {
        given()
            .spec(requestSpec)
            .queryParam("$expand", "children")
            .queryParam("$select", "objectId,name,path")
            .queryParam("$top", 5)
        .when()
            .get(foldersPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"));
    }
    
    /**
     * Test GET /odata/{repositoryId}/Folders with multiple $expand properties.
     */
    @Test
    public void testGetFoldersWithMultipleExpand() {
        given()
            .spec(requestSpec)
            .queryParam("$expand", "parent,children")
            .queryParam("$top", 5)
        .when()
            .get(foldersPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"));
    }
    
    // ==================== OData Actions Tests ====================
    
    /**
     * Test POST /odata/{repositoryId}/Folders('objectId')/NemakiWare.CMIS.Move
     * Move action should move a folder to a different parent folder.
     */
    @Test
    public void testMoveAction() {
        // First, get two folders - one to move and one as target
        Response listResponse = given()
            .spec(requestSpec)
            .queryParam("$top", 2)
            .queryParam("$filter", "path ne '/'") // Exclude root folder
        .when()
            .get(foldersPath())
        .then()
            .statusCode(200)
            .extract().response();
        
        java.util.List<?> folders = listResponse.jsonPath().getList("value");
        if (folders == null || folders.size() < 2) {
            // Not enough folders available for move test
            return;
        }
        
        String objectIdToMove = listResponse.jsonPath().getString("value[0].objectId");
        String targetFolderId = listResponse.jsonPath().getString("value[1].objectId");
        
        // Execute Move action with parameters
        String moveParams = "{\"targetFolderId\": \"" + targetFolderId + "\"}";
        
        given()
            .spec(requestSpec)
            .contentType("application/json")
            .body(moveParams)
        .when()
            .post(folderPath(objectIdToMove) + "/NemakiWare.CMIS.Move")
        .then()
            .statusCode(anyOf(equalTo(200), equalTo(204)))
            .contentType(anyOf(containsString("application/json"), emptyOrNullString()));
    }
    
    /**
     * Test POST /odata/{repositoryId}/Folders('objectId')/NemakiWare.CMIS.Move
     * Move action with explicit source folder ID.
     */
    @Test
    public void testMoveActionWithSourceFolder() {
        // Build a deterministic scaffold so the move cannot collide with
        // pre-existing folders (moving to the shared root could hit a name
        // conflict -> 409): a source folder and a target folder under root, and
        // a child inside source that we then move from source to target.
        long stamp = System.nanoTime();
        String rootId = getRootFolderId();
        if (rootId == null) {
            return;
        }
        String srcId = createFolderUnder("odata-move-src-" + stamp, rootId);
        String tgtId = createFolderUnder("odata-move-tgt-" + stamp, rootId);
        String childId = (srcId == null) ? null : createFolderUnder("odata-move-child-" + stamp, srcId);
        if (srcId == null || tgtId == null || childId == null) {
            // Environment did not allow folder creation; nothing to assert.
            deleteFolderQuietly(childId);
            deleteFolderQuietly(srcId);
            deleteFolderQuietly(tgtId);
            return;
        }

        try {
            String moveParams = "{\"targetFolderId\": \"" + tgtId + "\", \"sourceFolderId\": \"" + srcId + "\"}";
            given()
                .spec(requestSpec)
                .contentType("application/json")
                .body(moveParams)
            .when()
                .post(folderPath(childId) + "/NemakiWare.CMIS.Move")
            .then()
                .statusCode(anyOf(equalTo(200), equalTo(204)));
        } finally {
            // child now lives under target; delete it first, then the two folders
            deleteFolderQuietly(childId);
            deleteFolderQuietly(srcId);
            deleteFolderQuietly(tgtId);
        }
    }

    /** Resolve the repository root folder id via OData, or null if unavailable. */
    private String getRootFolderId() {
        return given()
                .spec(requestSpec)
                .queryParam("$filter", "path eq '/'")
            .when()
                .get(foldersPath())
            .then()
                .extract().jsonPath().getString("value[0].objectId");
    }

    /** Create a folder under {@code parentId} via OData; return its id or null. */
    private String createFolderUnder(String name, String parentId) {
        if (parentId == null) {
            return null;
        }
        String body = "{\"name\": \"" + name + "\", \"objectTypeId\": \"cmis:folder\", \"parentId\": \"" + parentId + "\"}";
        Response r = given()
                .spec(requestSpec)
                .body(body)
            .when()
                .post(foldersPath())
            .then()
                .extract().response();
        return r.getStatusCode() == 201 ? r.jsonPath().getString("objectId") : null;
    }

    /** Best-effort OData delete of a folder; ignores the outcome. */
    private void deleteFolderQuietly(String objectId) {
        if (objectId == null) {
            return;
        }
        given()
            .spec(requestSpec)
        .when()
            .delete(folderPath(objectId));
    }
    
    /**
     * Test POST /odata/{repositoryId}/Folders('objectId')/NemakiWare.CMIS.Move
     * Move action without required targetFolderId should fail.
     */
    @Test
    public void testMoveActionMissingTargetFolder() {
        // First, get a folder to move
        Response listResponse = given()
            .spec(requestSpec)
            .queryParam("$top", 1)
            .queryParam("$filter", "path ne '/'") // Exclude root folder
        .when()
            .get(foldersPath())
        .then()
            .statusCode(200)
            .extract().response();
        
        java.util.List<?> folders = listResponse.jsonPath().getList("value");
        if (folders == null || folders.isEmpty()) {
            // No folders available for move test
            return;
        }
        
        String objectIdToMove = listResponse.jsonPath().getString("value[0].objectId");
        
        // Execute Move action without targetFolderId - should fail
        String moveParams = "{}";
        
        given()
            .spec(requestSpec)
            .contentType("application/json")
            .body(moveParams)
        .when()
            .post(folderPath(objectIdToMove) + "/NemakiWare.CMIS.Move")
        .then()
            .statusCode(400); // 400 Bad Request - missing required targetFolderId
    }
    
    // ==================== OData $search Tests ====================
    
    /**
     * Test GET /odata/{repositoryId}/Folders with $search query option.
     * Full-text search using Solr integration.
     */
    @Test
    public void testGetFoldersWithSearch() {
        given()
            .spec(requestSpec)
            .queryParam("$search", "test")
        .when()
            .get(foldersPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"));
    }
    
    /**
     * Test GET /odata/{repositoryId}/Folders with $search and $top.
     * Full-text search with pagination.
     */
    @Test
    public void testGetFoldersWithSearchAndTop() {
        given()
            .spec(requestSpec)
            .queryParam("$search", "folder")
            .queryParam("$top", 5)
        .when()
            .get(foldersPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"));
    }
    
    /**
     * Test GET /odata/{repositoryId}/Folders with $search and $filter combined.
     * Full-text search combined with property filter.
     */
    @Test
    public void testGetFoldersWithSearchAndFilter() {
        given()
            .spec(requestSpec)
            .queryParam("$search", "project")
            .queryParam("$filter", "contains(name,'archive')")
            .queryParam("$top", 10)
        .when()
            .get(foldersPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"));
    }
    
    /**
     * Test GET /odata/{repositoryId}/Folders with $search and $select.
     * Full-text search with property projection.
     */
    @Test
    public void testGetFoldersWithSearchAndSelect() {
        given()
            .spec(requestSpec)
            .queryParam("$search", "documents")
            .queryParam("$select", "objectId,name,path")
            .queryParam("$top", 5)
        .when()
            .get(foldersPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"));
    }
    
    /**
     * Test GET /odata/{repositoryId}/Folders with $search and $orderby.
     * Full-text search with ordering.
     */
    @Test
    public void testGetFoldersWithSearchAndOrderBy() {
        given()
            .spec(requestSpec)
            .queryParam("$search", "backup")
            .queryParam("$orderby", "lastModificationDate desc")
            .queryParam("$top", 10)
        .when()
            .get(foldersPath())
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"));
    }
}
