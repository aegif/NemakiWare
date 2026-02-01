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
 * Integration tests for CloudDirectorySyncResource REST endpoints.
 * Requires a running NemakiWare instance.
 *
 * Run with: mvn test -Dtest=CloudDirectorySyncResourceIT -Dnemaki.test.baseUrl=http://localhost:8080/core
 */
public class CloudDirectorySyncResourceIT {

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

	private String cloudSyncPath() {
		return "/rest/repo/" + repositoryId + "/cloud-sync";
	}

	@Test
	public void testGetSyncStatus_Google_Returns200() {
		given()
				.spec(adminSpec)
		.when()
				.get(cloudSyncPath() + "/status?provider=google")
		.then()
				.statusCode(200)
				.body("status", notNullValue());
	}

	@Test
	public void testGetSyncStatus_Microsoft_Returns200() {
		given()
				.spec(adminSpec)
		.when()
				.get(cloudSyncPath() + "/status?provider=microsoft")
		.then()
				.statusCode(200)
				.body("status", notNullValue());
	}

	@Test
	public void testTestConnection_Google() {
		given()
				.spec(adminSpec)
		.when()
				.get(cloudSyncPath() + "/test-connection?provider=google")
		.then()
				.statusCode(200);
	}

	@Test
	public void testTriggerDeltaSync_MissingProvider_Returns400or500() {
		given()
				.spec(adminSpec)
				.contentType(ContentType.URLENC)
		.when()
				.post(cloudSyncPath() + "/trigger")
		.then()
				.statusCode(anyOf(is(400), is(500)));
	}

	@Test
	public void testTriggerDeltaSync_WithProvider() {
		given()
				.spec(adminSpec)
				.contentType(ContentType.URLENC)
				.formParam("provider", "google")
		.when()
				.post(cloudSyncPath() + "/trigger")
		.then()
				.statusCode(200);
	}

	@Test
	public void testFullReconciliation_WithProvider() {
		given()
				.spec(adminSpec)
				.contentType(ContentType.URLENC)
				.formParam("provider", "google")
		.when()
				.post(cloudSyncPath() + "/full-reconciliation")
		.then()
				.statusCode(200);
	}

	@Test
	public void testCancelSync() {
		given()
				.spec(adminSpec)
				.contentType(ContentType.URLENC)
				.formParam("provider", "google")
		.when()
				.post(cloudSyncPath() + "/cancel")
		.then()
				.statusCode(200);
	}
}
