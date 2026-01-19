package jp.aegif.nemaki.api.v1;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.filter.log.LogDetail;
import io.restassured.http.ContentType;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Regression tests to ensure CMIS API and legacy API routes coexist.
 *
 * Run with: mvn test -Dtest=RoutingRegressionIT -Dnemaki.test.baseUrl=http://localhost:8080/core
 */
@Ignore("Integration tests require a running NemakiWare instance")
public class RoutingRegressionIT extends ApiV1TestBase {

    @BeforeClass
    public static void setupRoutingRestAssured() {
        baseUrl = getConfigValue("nemaki.test.baseUrl", "NEMAKI_TEST_BASE_URL", "http://localhost:8080/core");
        username = getConfigValue("nemaki.test.username", "NEMAKI_TEST_USERNAME", "admin");
        password = getConfigValue("nemaki.test.password", "NEMAKI_TEST_PASSWORD", "admin");
        repositoryId = getConfigValue("nemaki.test.repositoryId", "NEMAKI_TEST_REPOSITORY_ID", "bedroom");

        RestAssured.baseURI = baseUrl;
        RestAssured.basePath = "";

        requestSpec = new RequestSpecBuilder()
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .addHeader("Authorization", createBasicAuthHeader(username, password))
                .log(LogDetail.METHOD)
                .log(LogDetail.URI)
                .build();
    }

    @Test
    public void testCmisApiRouting_ReturnsOk() {
        given()
                .spec(requestSpec)
        .when()
                .get("/api/v1/cmis/repositories")
        .then()
                .statusCode(200)
                .body("repositories", notNullValue());
    }

    @Test
    public void testLegacyApiRouting_ReturnsOk() {
        given()
                .spec(requestSpec)
        .when()
                .get("/api/v1/repo/" + repositoryId + "/users")
        .then()
                .statusCode(anyOf(equalTo(200), equalTo(204)));
    }
}
