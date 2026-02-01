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
 * The actual API is:
 *   GET  /rest/repo/{repositoryId}/cloud-drive/url/{objectId}
 *   POST /rest/repo/{repositoryId}/cloud-drive/push/{objectId}
 *   POST /rest/repo/{repositoryId}/cloud-drive/pull/{objectId}
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
	public void testGetCloudFileUrl_NonExistentObject_ReturnsError() {
		// API is GET /url/{objectId} - using a non-existent objectId should return error
		given()
				.spec(adminSpec)
		.when()
				.get(cloudDrivePath() + "/url/nonexistent-object-id")
		.then()
				.statusCode(200)
				.body("status", is("error"));
	}

	@Test
	public void testPushToCloud_MissingProvider_ReturnsError() {
		given()
				.spec(adminSpec)
				.contentType(ContentType.JSON)
				.body("{\"accessToken\": \"test-token\"}")
		.when()
				.post(cloudDrivePath() + "/push/test-object-id")
		.then()
				.statusCode(200)
				.body("status", is("error"));
	}

	@Test
	public void testPushToCloud_MissingAccessToken_ReturnsError() {
		given()
				.spec(adminSpec)
				.contentType(ContentType.JSON)
				.body("{\"provider\": \"google\"}")
		.when()
				.post(cloudDrivePath() + "/push/test-object-id")
		.then()
				.statusCode(200)
				.body("status", is("error"));
	}

	@Test
	public void testPullFromCloud_MissingCloudFileId_ReturnsError() {
		given()
				.spec(adminSpec)
				.contentType(ContentType.JSON)
				.body("{\"provider\": \"google\", \"accessToken\": \"test-token\"}")
		.when()
				.post(cloudDrivePath() + "/pull/test-object-id")
		.then()
				.statusCode(200)
				.body("status", is("error"));
	}

	@Test
	public void testPullFromCloud_DisabledProvider_ReturnsError() {
		// If cloud.drive.google.enabled is not set to true, should return error
		given()
				.spec(adminSpec)
				.contentType(ContentType.JSON)
				.body("{\"provider\": \"google\", \"accessToken\": \"test-token\", \"cloudFileId\": \"file123\"}")
		.when()
				.post(cloudDrivePath() + "/pull/test-object-id")
		.then()
				.statusCode(200)
				.body("status", is("error"));
	}
}
