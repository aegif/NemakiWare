package jp.aegif.nemaki.api.v1;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.filter.log.LogDetail;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.BeforeClass;

/**
 * Base class for OData E2E/integration tests using REST Assured.
 *
 * Tests are designed to run against a deployed NemakiWare instance.
 */
public abstract class ODataTestBase extends ApiV1TestBase {

    protected static final String ODATA_PATH = "/odata";

    protected static RequestSpecification odataRequestSpec;

    @BeforeClass
    public static void setupODataRestAssured() {
        baseUrl = getConfigValue("nemaki.test.baseUrl", "NEMAKI_TEST_BASE_URL", "http://localhost:8080/core");
        username = getConfigValue("nemaki.test.username", "NEMAKI_TEST_USERNAME", "admin");
        password = getConfigValue("nemaki.test.password", "NEMAKI_TEST_PASSWORD", "admin");
        repositoryId = getConfigValue("nemaki.test.repositoryId", "NEMAKI_TEST_REPOSITORY_ID", "bedroom");

        RestAssured.baseURI = baseUrl;
        RestAssured.basePath = ODATA_PATH;

        odataRequestSpec = new RequestSpecBuilder()
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .addHeader("Authorization", createBasicAuthHeader(username, password))
                .log(LogDetail.METHOD)
                .log(LogDetail.URI)
                .build();
    }

    protected String odataDocumentsPath() {
        return "/" + repositoryId + "/Documents";
    }

    protected String odataFoldersPath() {
        return "/" + repositoryId + "/Folders";
    }
}
