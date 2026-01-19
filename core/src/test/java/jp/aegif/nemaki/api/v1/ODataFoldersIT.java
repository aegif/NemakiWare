package jp.aegif.nemaki.api.v1;

import io.restassured.response.Response;
import org.junit.Ignore;
import org.junit.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * E2E/Integration tests for OData Folders endpoints.
 *
 * Run with: mvn test -Dtest=ODataFoldersIT -Dnemaki.test.baseUrl=http://localhost:8080/core
 */
@Ignore("Integration tests require a running NemakiWare instance")
public class ODataFoldersIT extends ODataTestBase {

    @Test
    public void testListFolders_ReturnsOk() {
        given()
                .spec(odataRequestSpec)
        .when()
                .get(odataFoldersPath())
        .then()
                .statusCode(200)
                .body("value", notNullValue());
    }

    @Test
    public void testFilterStartswith_ReturnsOk() {
        String filter = URLEncoder.encode("startswith(name,'A')", StandardCharsets.UTF_8);

        given()
                .spec(odataRequestSpec)
        .when()
                .get(odataFoldersPath() + "?$filter=" + filter)
        .then()
                .statusCode(200)
                .body("value", notNullValue());
    }

    @Test
    public void testFilterContains_ReturnsOk() {
        String filter = URLEncoder.encode("contains(name,'folder')", StandardCharsets.UTF_8);

        given()
                .spec(odataRequestSpec)
        .when()
                .get(odataFoldersPath() + "?$filter=" + filter)
        .then()
                .statusCode(200)
                .body("value", notNullValue());
    }

    @Test
    public void testFilterEndswith_ReturnsOk() {
        String filter = URLEncoder.encode("endswith(name,'s')", StandardCharsets.UTF_8);

        given()
                .spec(odataRequestSpec)
        .when()
                .get(odataFoldersPath() + "?$filter=" + filter)
        .then()
                .statusCode(200)
                .body("value", notNullValue());
    }

    @Test
    public void testTopSkip_Paginates() {
        given()
                .spec(odataRequestSpec)
        .when()
                .get(odataFoldersPath() + "?$top=1&$skip=0")
        .then()
                .statusCode(200)
                .body("value.size()", lessThanOrEqualTo(1));
    }

    @Test
    public void testCount_ReturnsCount() {
        given()
                .spec(odataRequestSpec)
        .when()
                .get(odataFoldersPath() + "?$count=true")
        .then()
                .statusCode(200)
                .body("@odata.count", notNullValue());
    }

    @Test
    public void testSelectOrderBy_ReturnsOk() {
        given()
                .spec(odataRequestSpec)
        .when()
                .get(odataFoldersPath() + "?$select=objectId,name&$orderby=name")
        .then()
                .statusCode(200)
                .body("value", notNullValue());
    }

    @Test
    public void testCreateUpdateDelete_FolderLifecycle() {
        String parentId = fetchAnyFolderId();
        String name = "odata-folder-" + System.currentTimeMillis();

        Map<String, Object> payload = new HashMap<>();
        payload.put("name", name);
        payload.put("objectTypeId", "cmis:folder");
        payload.put("parentId", parentId);

        Response createResponse = given()
                .spec(odataRequestSpec)
                .body(payload)
        .when()
                .post(odataFoldersPath())
        .then()
                .statusCode(anyOf(equalTo(200), equalTo(201)))
                .extract()
                .response();

        String folderId = extractObjectId(createResponse);
        assertThat(folderId, notNullValue());

        Map<String, Object> updatePayload = new HashMap<>();
        updatePayload.put("name", name + "-updated");

        given()
                .spec(odataRequestSpec)
                .header("If-Match", "*")
                .body(updatePayload)
        .when()
                .patch(odataFoldersPath() + "('" + folderId + "')")
        .then()
                .statusCode(anyOf(equalTo(200), equalTo(204)));

        given()
                .spec(odataRequestSpec)
        .when()
                .get(odataFoldersPath() + "('" + folderId + "')")
        .then()
                .statusCode(200)
                .body("objectId", notNullValue());

        given()
                .spec(odataRequestSpec)
                .header("If-Match", "*")
        .when()
                .delete(odataFoldersPath() + "('" + folderId + "')")
        .then()
                .statusCode(anyOf(equalTo(200), equalTo(204)));
    }

    private String fetchAnyFolderId() {
        Response response = given()
                .spec(odataRequestSpec)
        .when()
                .get(odataFoldersPath() + "?$top=1")
        .then()
                .statusCode(200)
                .body("value", notNullValue())
                .extract()
                .response();

        String objectId = response.jsonPath().getString("value[0].objectId");
        if (objectId == null) {
            objectId = response.jsonPath().getString("value[0].id");
        }
        assertThat(objectId, notNullValue());
        return objectId;
    }

    private String extractObjectId(Response response) {
        String objectId = response.jsonPath().getString("objectId");
        if (objectId == null) {
            objectId = response.jsonPath().getString("id");
        }
        return objectId;
    }
}
