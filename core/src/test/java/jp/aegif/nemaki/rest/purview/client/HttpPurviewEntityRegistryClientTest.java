package jp.aegif.nemaki.rest.purview.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

public class HttpPurviewEntityRegistryClientTest {

    @Test
    public void testReusesInjectedHttpClient() {
        HttpClient sharedClient = HttpClient.newHttpClient();
        HttpPurviewEntityRegistryClient client = new HttpPurviewEntityRegistryClient(sharedClient);
        assertSame(sharedClient, client.getHttpClient());
    }

    @Test
    public void testDefaultConstructorCreatesHttpClient() {
        HttpPurviewEntityRegistryClient client = new HttpPurviewEntityRegistryClient();
        assertSame(client.getHttpClient(), client.getHttpClient());
    }

    /**
     * Entity writes name the configured collection on the Data Map surface.
     *
     * <p>Without it every write lands in the root collection: a 403 under the runbook's
     * least-privilege service principal (Data Curator on the configured collection only), or a
     * silent misplacement under root-level rights. The parameter is scoped to entity
     * create/update by the public API, so reads must not carry it.
     */
    @Test
    public void testBulkUriCarriesCollectionOnDataMapSurface() {
        HttpPurviewEntityRegistryClient client = new HttpPurviewEntityRegistryClient();
        PurviewConnectionRequest request = new PurviewConnectionRequest(
                "https://example.purview.azure.com", "datamap/api/atlas/v2", "oauth2",
                "t", "c", "s", "", "", 5000, 30000, "NemakiWare");

        assertEquals("https://example.purview.azure.com/datamap/api/atlas/v2/entity/bulk"
                        + "?api-version=2023-09-01&collectionId=NemakiWare",
                client.buildEntityBulkUri(request).toString());
    }

    @Test
    public void testBulkUriOmitsCollectionWhenNoneConfigured() {
        HttpPurviewEntityRegistryClient client = new HttpPurviewEntityRegistryClient();
        PurviewConnectionRequest request = new PurviewConnectionRequest(
                "https://example.purview.azure.com", "datamap/api/atlas/v2", "oauth2",
                "t", "c", "s", "", "", 5000, 30000, "");

        assertEquals("https://example.purview.azure.com/datamap/api/atlas/v2/entity/bulk"
                        + "?api-version=2023-09-01",
                client.buildEntityBulkUri(request).toString());
    }

    /**
     * The classic catalog surface has no collectionId parameter, so none may be sent — the
     * write still goes through (to the root collection) and the client warns instead.
     */
    @Test
    public void testBulkUriOmitsCollectionOnClassicSurface() {
        HttpPurviewEntityRegistryClient client = new HttpPurviewEntityRegistryClient();
        PurviewConnectionRequest request = new PurviewConnectionRequest(
                "https://example.purview.azure.com", "catalog/api/atlas/v2", "oauth2",
                "t", "c", "s", "", "", 5000, 30000, "NemakiWare");

        assertEquals("https://example.purview.azure.com/catalog/api/atlas/v2/entity/bulk",
                client.buildEntityBulkUri(request).toString());
    }

    /** Atlas OSS knows neither api-version nor collections; its URI stays bare. */
    @Test
    public void testBulkUriStaysBareForAtlas() {
        HttpPurviewEntityRegistryClient client = new HttpPurviewEntityRegistryClient();
        PurviewConnectionRequest request = new PurviewConnectionRequest(
                "http://localhost:21000", "api/atlas/v2", "basic",
                "", "", "", "u", "p", 5000, 30000, "");

        assertEquals("http://localhost:21000/api/atlas/v2/entity/bulk",
                client.buildEntityBulkUri(request).toString());
    }

    /** A collection reference name is URL-encoded on its way into the query string. */
    @Test
    public void testBulkUriEncodesTheCollection() {
        HttpPurviewEntityRegistryClient client = new HttpPurviewEntityRegistryClient();
        PurviewConnectionRequest request = new PurviewConnectionRequest(
                "https://example.purview.azure.com", "datamap/api/atlas/v2", "oauth2",
                "t", "c", "s", "", "", 5000, 30000, "a&b c");

        assertTrue(client.buildEntityBulkUri(request).toString()
                        .endsWith("&collectionId=a%26b+c"),
                client.buildEntityBulkUri(request).toString());
    }

    @Test
    public void testBuildAuthorizationHeaderReturnsBasicForBasicAuth() throws Exception {
        HttpPurviewEntityRegistryClient client = new HttpPurviewEntityRegistryClient();
        PurviewConnectionRequest request = new PurviewConnectionRequest(
                "http://localhost:21000",
                "api/atlas/v2",
                "basic",
                "", "", "",
                "atlas-user", "atlas-pass",
                5000, 30000);

        String header = client.buildAuthorizationHeader(request);

        assertTrue(header.startsWith("Basic "));
        String expectedEncoded = Base64.getEncoder().encodeToString(
                "atlas-user:atlas-pass".getBytes(StandardCharsets.UTF_8));
        assertEquals("Basic " + expectedEncoded, header);
    }

    @Test
    public void testBuildAuthorizationHeaderReturnsBearerForOAuth2() throws Exception {
        PurviewTokenCache tokenCache = new PurviewTokenCache();
        tokenCache.put("tenant-123", "client-123", "mock-token", 3600L);
        HttpPurviewEntityRegistryClient client = new HttpPurviewEntityRegistryClient(
                HttpClient.newBuilder().build(), tokenCache);

        PurviewConnectionRequest request = new PurviewConnectionRequest(
                "https://example.purview.azure.com",
                "datamap/api/atlas/v2",
                "tenant-123",
                "client-123",
                "secret-123",
                5000, 30000);

        String header = client.buildAuthorizationHeader(request);

        assertEquals("Bearer mock-token", header);
    }
}
