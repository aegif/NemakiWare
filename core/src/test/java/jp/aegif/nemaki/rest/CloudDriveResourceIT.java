package jp.aegif.nemaki.rest;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.BeforeClass;
import org.junit.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for CloudDriveResource REST endpoints.
 * Requires a running NemakiWare instance.
 *
 * Run with: mvn test -Dtest=CloudDriveResourceIT -Dnemaki.test.baseUrl=http://localhost:8080/core
 */
public class CloudDriveResourceIT {

	private static String baseUrl;
	private static String repositoryId;
	private static RequestSpecification adminSpec;

	@BeforeClass
	public static void setup() {
		baseUrl = System.getProperty("nemaki.test.baseUrl", "http://localhost:8080/core");
		repositoryId = System.getProperty("nemaki.test.repositoryId", "bedroom");

		RestAssured.baseURI = baseUrl;

		adminSpec = new RequestSpecBuilder()
				.setAccept(ContentType.JSON)
				.addHeader("Authorization", "Basic "
						+ java.util.Base64.getEncoder().encodeToString("admin:admin".getBytes()))
				.build();
	}

	private String cloudDrivePath() {
		return "/rest/repo/" + repositoryId + "/cloud-drive";
	}

	@Test
	public void testGetCloudFileUrl_Google() {
		given()
				.spec(adminSpec)
		.when()
				.get(cloudDrivePath() + "/url?provider=google&fileId=testfile123")
		.then()
				.statusCode(200)
				.body("url", containsString("drive.google.com"));
	}

	@Test
	public void testGetCloudFileUrl_Microsoft() {
		given()
				.spec(adminSpec)
		.when()
				.get(cloudDrivePath() + "/url?provider=microsoft&fileId=testfile456")
		.then()
				.statusCode(200)
				.body("url", containsString("onedrive.live.com"));
	}

	@Test
	public void testGetCloudFileUrl_UnknownProvider() {
		given()
				.spec(adminSpec)
		.when()
				.get(cloudDrivePath() + "/url?provider=dropbox&fileId=testfile")
		.then()
				.statusCode(anyOf(is(200), is(400), is(404)));
	}
}
