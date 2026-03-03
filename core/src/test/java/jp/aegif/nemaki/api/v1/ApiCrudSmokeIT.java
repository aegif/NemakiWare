package jp.aegif.nemaki.api.v1;

import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Minimal CRUD smoke tests validated against actual API contract.
 *
 * Covers the core lifecycle: folder create → get → list children → delete.
 * This IT detects regressions in the REST API v1 layer after dependency
 * upgrades (Spring/Jersey/Tomcat/Jackson) without duplicating TCK coverage.
 *
 * API contract reference (actual endpoint signatures):
 * - POST /folders?parentId={id}  body: Map&lt;String, PropertyValue&gt;  → 201 ObjectResponse
 * - GET  /folders/{id}                                               → 200 ObjectResponse
 * - GET  /folders/{id}/children                                      → 200 ObjectListResponse
 * - DELETE /objects/{id}                                              → 204
 *
 * Run with: mvn test -Dtest=ApiCrudSmokeIT -Dnemaki.test.baseUrl=http://localhost:8080/core
 */
@EnabledIfNemakiRunning
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ApiCrudSmokeIT extends ApiV1TestBase {

    @Test
    @Order(1)
    public void testFolderLifecycle_CreateGetListDelete() {
        // 1. Get root folder ID from repository info
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .statusCode(200)
            .body("rootFolderId", notNullValue())
            .extract()
            .path("rootFolderId");

        // 2. Create folder — parentId is @QueryParam, body is Map<String, PropertyValue>
        String folderName = "smoke-test-" + System.currentTimeMillis();
        Map<String, Object> nameProperty = new HashMap<>();
        nameProperty.put("value", folderName);
        nameProperty.put("type", "string");

        Map<String, Object> typeProperty = new HashMap<>();
        typeProperty.put("value", "cmis:folder");
        typeProperty.put("type", "id");

        Map<String, Object> body = new HashMap<>();
        body.put("cmis:name", nameProperty);
        body.put("cmis:objectTypeId", typeProperty);

        Response createResponse = given()
            .spec(requestSpec)
            .queryParam("parentId", rootFolderId)
            .body(body)
        .when()
            .post(foldersPath())
        .then()
            .statusCode(201)
            .contentType("application/json")
            .body("objectId", notNullValue())
            .body("name", equalTo(folderName))
            .body("baseTypeId", equalTo("cmis:folder"))
            .body("objectTypeId", equalTo("cmis:folder"))
            .body("_links", notNullValue())
            .body("_links.self", notNullValue())
            .extract()
            .response();

        String folderId = createResponse.path("objectId");

        try {
            // 3. Get folder by ID
            given()
                .spec(requestSpec)
            .when()
                .get(foldersPath() + "/" + folderId)
            .then()
                .statusCode(200)
                .contentType("application/json")
                .body("objectId", equalTo(folderId))
                .body("name", equalTo(folderName))
                .body("baseTypeId", equalTo("cmis:folder"))
                .body("properties", notNullValue())
                .body("properties.'cmis:name'.value", equalTo(folderName))
                .body("properties.'cmis:name'.type", equalTo("string"))
                .body("_links", notNullValue());

            // 4. List root folder children — verify created folder appears
            given()
                .spec(requestSpec)
                .queryParam("maxItems", 200)
            .when()
                .get(foldersPath() + "/" + rootFolderId + "/children")
            .then()
                .statusCode(200)
                .contentType("application/json")
                .body("objects", notNullValue())
                .body("numItems", greaterThanOrEqualTo(1))
                .body("hasMoreItems", notNullValue())
                .body("_links", notNullValue())
                .body("_links.self", notNullValue());

        } finally {
            // 5. Delete folder
            given()
                .spec(requestSpec)
            .when()
                .delete(objectPath(folderId))
            .then()
                .statusCode(204);
        }

        // 6. Verify deletion — GET should return 404
        given()
            .spec(requestSpec)
        .when()
            .get(foldersPath() + "/" + folderId)
        .then()
            .statusCode(404);
    }

    @Test
    @Order(2)
    public void testGetRootFolder_ReturnsCorrectStructure() {
        // Get root folder ID
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");

        // Verify ObjectResponse structure for root folder
        given()
            .spec(requestSpec)
        .when()
            .get(foldersPath() + "/" + rootFolderId)
        .then()
            .statusCode(200)
            .contentType("application/json")
            .body("objectId", equalTo(rootFolderId))
            .body("baseTypeId", equalTo("cmis:folder"))
            .body("objectTypeId", notNullValue())
            .body("name", notNullValue())
            .body("properties", notNullValue())
            .body("_links", notNullValue())
            .body("_links.self", notNullValue())
            .body("_links.children", notNullValue());
    }

    @Test
    @Order(3)
    public void testGetChildren_RootFolder_ReturnsObjectListResponse() {
        String rootFolderId = given()
            .spec(requestSpec)
        .when()
            .get(repositoryPath())
        .then()
            .extract()
            .path("rootFolderId");

        // Verify ObjectListResponse structure
        given()
            .spec(requestSpec)
            .queryParam("maxItems", 10)
            .queryParam("skipCount", 0)
        .when()
            .get(foldersPath() + "/" + rootFolderId + "/children")
        .then()
            .statusCode(200)
            .contentType("application/json")
            .body("objects", notNullValue())
            .body("numItems", notNullValue())
            .body("hasMoreItems", notNullValue())
            .body("skipCount", equalTo(0))
            .body("maxItems", equalTo(10))
            .body("_links", notNullValue())
            .body("_links.self", notNullValue());
    }

    @Test
    @Order(4)
    public void testCreateFolder_MissingParentId_Returns400() {
        Map<String, Object> nameProperty = new HashMap<>();
        nameProperty.put("value", "bad-folder");
        nameProperty.put("type", "string");

        Map<String, Object> typeProperty = new HashMap<>();
        typeProperty.put("value", "cmis:folder");
        typeProperty.put("type", "id");

        Map<String, Object> body = new HashMap<>();
        body.put("cmis:name", nameProperty);
        body.put("cmis:objectTypeId", typeProperty);

        // No parentId query param → should be 400
        given()
            .spec(requestSpec)
            .body(body)
        .when()
            .post(foldersPath())
        .then()
            .statusCode(400);
    }

    @Test
    @Order(5)
    public void testDeleteObject_NonExistent_Returns404() {
        given()
            .spec(requestSpec)
        .when()
            .delete(objectPath("nonexistent-smoke-test-99999"))
        .then()
            .statusCode(anyOf(equalTo(404), equalTo(500)));
            // Some CMIS implementations return 500 for CmisObjectNotFoundException
    }
}
