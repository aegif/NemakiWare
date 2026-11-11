package jp.aegif.nemaki.odata;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.filter.log.LogDetail;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;

/**
 * Base class for OData 4.0 E2E/integration tests using REST Assured.
 * 
 * These tests are designed to run against a deployed NemakiWare instance.
 * Configure the base URL and credentials via system properties or environment variables:
 * 
 * - nemaki.test.baseUrl: Base URL of the NemakiWare instance (default: http://localhost:8080/core)
 * - nemaki.test.username: Test user username (default: admin)
 * - nemaki.test.password: Test user password (default: admin)
 * - nemaki.test.repositoryId: Repository ID to test against (default: bedroom)
 * 
 * Example:
 *   mvn test -Dnemaki.test.baseUrl=http://localhost:8080/core -Dnemaki.test.username=admin
 */
public abstract class ODataTestBase {
    
    protected static final String ODATA_PATH = "/odata";
    
    protected static String baseUrl;
    protected static String username;
    protected static String password;
    protected static String repositoryId;
    
    protected static RequestSpecification requestSpec;
    
    @BeforeAll
    public static void setupRestAssured() {
        // Load configuration from system properties or environment variables
        baseUrl = getConfigValue("nemaki.test.baseUrl", "NEMAKI_TEST_BASE_URL", "http://localhost:8080/core");
        username = getConfigValue("nemaki.test.username", "NEMAKI_TEST_USERNAME", "admin");
        password = getConfigValue("nemaki.test.password", "NEMAKI_TEST_PASSWORD", "admin");
        repositoryId = getConfigValue("nemaki.test.repositoryId", "NEMAKI_TEST_REPOSITORY_ID", "bedroom");
        
        // Configure REST Assured for OData
        RestAssured.baseURI = baseUrl;
        RestAssured.basePath = ODATA_PATH;
        
        // Build request specification with common settings
        // OData uses application/json for responses
        requestSpec = new RequestSpecBuilder()
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .addHeader("Authorization", createBasicAuthHeader(username, password))
                // The OData servlet enforces CSRF on state-changing methods
                // (POST/PUT/PATCH/DELETE). Basic auth does not bypass it (it is
                // an ambient credential), so send the non-ambient XHR header the
                // CsrfValidator accepts; otherwise every write would get 403.
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .log(LogDetail.METHOD)
                .log(LogDetail.URI)
                .build();
    }
    
    /**
     * Get configuration value from system property, environment variable, or default.
     */
    protected static String getConfigValue(String sysProp, String envVar, String defaultValue) {
        String value = System.getProperty(sysProp);
        if (value == null || value.isEmpty()) {
            value = System.getenv(envVar);
        }
        if (value == null || value.isEmpty()) {
            value = defaultValue;
        }
        return value;
    }
    
    /**
     * Create Basic Authentication header value.
     */
    protected static String createBasicAuthHeader(String user, String pass) {
        String credentials = user + ":" + pass;
        return "Basic " + java.util.Base64.getEncoder().encodeToString(credentials.getBytes());
    }
    
    /**
     * Get the OData service root path for the configured repository.
     */
    protected String serviceRoot() {
        return "/" + repositoryId;
    }
    
    /**
     * Get the Documents entity set path.
     */
    protected String documentsPath() {
        return serviceRoot() + "/Documents";
    }
    
    /**
     * Get the path for a specific document by ID.
     */
    protected String documentPath(String objectId) {
        return documentsPath() + "('" + objectId + "')";
    }
    
    /**
     * Get the Folders entity set path.
     */
    protected String foldersPath() {
        return serviceRoot() + "/Folders";
    }
    
    /**
     * Get the path for a specific folder by ID.
     */
    protected String folderPath(String objectId) {
        return foldersPath() + "('" + objectId + "')";
    }
    
    /**
     * Get the Objects entity set path.
     */
    protected String objectsPath() {
        return serviceRoot() + "/Objects";
    }
    
    /**
     * Get the path for a specific object by ID.
     */
    protected String objectPath(String objectId) {
        return objectsPath() + "('" + objectId + "')";
    }
    
    /**
     * Get the Relationships entity set path.
     */
    protected String relationshipsPath() {
        return serviceRoot() + "/Relationships";
    }
    
    /**
     * Get the Policies entity set path.
     */
    protected String policiesPath() {
        return serviceRoot() + "/Policies";
    }
    
    /**
     * Get the $metadata path.
     */
    protected String metadataPath() {
        return serviceRoot() + "/$metadata";
    }

    /**
     * No item of a filtered collection has a name the filter does not admit. A test that checked only the status
     * passed when the server answered a $filter it could not translate without its conditions (up to 3.4.0), and the
     * contains() tests were green on that (3.4.2). An empty answer passes this on its own; the tests without $search
     * also call {@link #assertNoAdmittedNameIsLeftOut} (R248).
     *
     * @param filtered the filtered response, already checked for 200
     * @param admitted whether the filter admits a name (case-sensitive, as OData's contains() is)
     * @param what     the admitted names, for the message
     */
    protected void assertOnlyAdmittedNames(io.restassured.response.Response filtered,
            java.util.function.Predicate<String> admitted, String what) {
        java.util.List<String> names = filtered.jsonPath().getList("value.name", String.class);
        org.junit.jupiter.api.Assertions.assertNotNull(names, "the response has no value array");
        java.util.List<String> outside = names.stream().filter(n -> n == null || !admitted.test(n)).toList();
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(), outside,
                "the filter returned items whose name is not " + what);
    }

    /**
     * The filtered answer is not empty when the collection has a name the filter admits. {@link #assertOnlyAdmittedNames}
     * holds for an empty answer, so a translation that matched nothing was green with it (R248, 3.4.2). The collection
     * is read whole, unfiltered, a page at a time. Where it has no admitted name an empty answer is the right one, and
     * this holds either way — {@link #assertContainsFindsANameFromAPartOfIt} does not depend on the data.
     */
    protected void assertNoAdmittedNameIsLeftOut(String collectionPath, io.restassured.response.Response filtered,
            java.util.function.Predicate<String> admitted, String what) {
        java.util.List<String> answered = filtered.jsonPath().getList("value.name", String.class);
        org.junit.jupiter.api.Assertions.assertNotNull(answered, "the response has no value array");
        java.util.List<String> admittedThere = new java.util.ArrayList<>();
        final int page = 500;
        for (int skip = 0; ; skip += page) {
            java.util.List<String> names = RestAssured.given().spec(requestSpec)
                    .queryParam("$select", "name").queryParam("$top", page).queryParam("$skip", skip)
                    .when().get(collectionPath)
                    .then().statusCode(200)
                    .extract().jsonPath().getList("value.name", String.class);
            org.junit.jupiter.api.Assertions.assertNotNull(names, "a page of " + collectionPath + " has no value array");
            names.stream().filter(n -> n != null && admitted.test(n)).forEach(admittedThere::add);
            if (names.size() < page) {
                break;
            }
            org.junit.jupiter.api.Assertions.assertTrue(skip < 200_000,
                    collectionPath + " did not end within 200,000 items, so it was not read whole");
        }
        org.junit.jupiter.api.Assertions.assertFalse(!admittedThere.isEmpty() && answered.isEmpty(),
                collectionPath + " has " + admittedThere.size() + " names that are " + what + " (" + admittedThere.subList(0,
                        Math.min(3, admittedThere.size())) + ") and the filter returned none");
    }

    /**
     * contains() with a part of a name the collection has returns that name — whatever the data, as long as the
     * collection has an item with a plain name (CI's seed documents and the repository's own folders do). The tests
     * that filter by fixed words cannot show a translation that matches nothing where nothing has those words (R248,
     * 3.4.2).
     */
    protected void assertContainsFindsANameFromAPartOfIt(String collectionPath) {
        java.util.List<String> names = RestAssured.given().spec(requestSpec)
                .queryParam("$select", "name").queryParam("$top", 500)
                .when().get(collectionPath)
                .then().statusCode(200)
                .extract().jsonPath().getList("value.name", String.class);
        org.junit.jupiter.api.Assertions.assertNotNull(names, "the response has no value array");
        String name = names.stream().filter(n -> n != null && n.matches("[A-Za-z0-9._-]{5,}"))
                .max(java.util.Comparator.comparingInt(String::length)).orElse(null);
        org.junit.jupiter.api.Assertions.assertNotNull(name,
                collectionPath + " has no item with a plain name to take a part of: " + names);
        String part = name.substring(1, name.length() - 1);
        java.util.List<String> found = RestAssured.given().spec(requestSpec)
                .queryParam("$filter", "contains(name,'" + part + "')")
                .queryParam("$select", "name").queryParam("$top", 500)
                .when().get(collectionPath)
                .then().statusCode(200)
                .extract().jsonPath().getList("value.name", String.class);
        org.junit.jupiter.api.Assertions.assertNotNull(found, "the filtered response has no value array");
        org.junit.jupiter.api.Assertions.assertTrue(found.contains(name),
                "contains(name,'" + part + "') did not return " + name + ": " + found);
        org.junit.jupiter.api.Assertions.assertTrue(found.stream().allMatch(n -> n != null && n.contains(part)),
                "contains(name,'" + part + "') returned a name without it: " + found);
    }

    /**
     * The same request without its $filter returns a name the filter does not admit. Without one, a filter the
     * server applied and a filter it dropped give the same answer, and {@link #assertOnlyAdmittedNames} holds either
     * way (3.4.2).
     *
     * @param collectionPath   the collection the filtered request was sent to
     * @param withoutTheFilter the filtered request's other query options, as they were sent
     * @param admitted         whether the filter admits a name
     * @param what             the admitted names, for the message
     */
    protected void assertTheRequestWithoutTheFilterReturnsANameOutside(String collectionPath,
            java.util.Map<String, ?> withoutTheFilter, java.util.function.Predicate<String> admitted, String what) {
        java.util.List<String> names = RestAssured.given().spec(requestSpec)
                .queryParams(withoutTheFilter)
                .when().get(collectionPath)
                .then().statusCode(200)
                .extract().jsonPath().getList("value.name", String.class);
        org.junit.jupiter.api.Assertions.assertTrue(
                names != null && names.stream().anyMatch(n -> n != null && !admitted.test(n)),
                "the same request without the filter returns no name that is not " + what + " " + names
                        + ", so this cannot tell a filter the server applied from one it dropped");
    }

    /**
     * A listing given {@code $expand} is refused with the processor's own words (R260, 3.4.2). It read the related
     * objects of every row and wrote none of them: the answer was 200 with no navigation property, which a test that
     * reads the status alone takes for the expansion.
     */
    protected void assertExpandOnAListingIsRefused(String collectionPath, java.util.Map<String, ?> query) {
        String message = RestAssured.given().spec(requestSpec)
                .queryParams(query)
                .when().get(collectionPath)
                .then().statusCode(400)
                .extract().jsonPath().getString("error.message");
        org.junit.jupiter.api.Assertions.assertEquals(CmisEntityCollectionProcessor.EXPAND_NOT_TRANSLATED_ON_A_LISTING, message,
                collectionPath + " " + query + " was refused by another arm");
    }

    /** The ids of what a navigation path answers, read whole a page at a time. */
    protected java.util.List<String> idsAt(String navigationPath) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (int skip = 0; skip < 100000; ) {
            java.util.List<String> page = RestAssured.given().spec(requestSpec)
                    .queryParam("$top", 100).queryParam("$skip", skip)
                    .when().get(navigationPath)
                    .then().statusCode(200)
                    .extract().jsonPath().getList("value.objectId", String.class);
            if (page == null || page.isEmpty()) {
                break;
            }
            ids.addAll(page);
            skip += page.size();
        }
        java.util.Collections.sort(ids);
        return ids;
    }
}
