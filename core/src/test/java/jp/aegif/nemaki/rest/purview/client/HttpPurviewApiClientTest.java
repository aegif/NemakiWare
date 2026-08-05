package jp.aegif.nemaki.rest.purview.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

public class HttpPurviewApiClientTest {

    @Test
    public void testReusesInjectedHttpClient() {
        HttpClient sharedClient = HttpClient.newHttpClient();
        HttpPurviewApiClient client = new HttpPurviewApiClient(sharedClient);
        assertSame(sharedClient, client.getHttpClient());
    }

    @Test
    public void testDefaultConstructorCreatesHttpClient() {
        HttpPurviewApiClient client = new HttpPurviewApiClient();
        assertSame(client.getHttpClient(), client.getHttpClient());
    }

    @Test
    public void testBuildAuthorizationHeaderReturnsBasicForBasicAuth() throws Exception {
        HttpPurviewApiClient client = new HttpPurviewApiClient();
        PurviewConnectionRequest request = new PurviewConnectionRequest(
                "http://localhost:21000",
                "api/atlas/v2",
                "basic",
                "", "", "",
                "admin", "admin",
                5000, 30000);

        String header = client.buildAuthorizationHeader(request);

        assertTrue(header.startsWith("Basic "));
        String expectedEncoded = Base64.getEncoder().encodeToString(
                "admin:admin".getBytes(StandardCharsets.UTF_8));
        assertEquals("Basic " + expectedEncoded, header);
    }

    @Test
    public void testBuildAuthorizationHeaderReturnsBearerForOAuth2() throws Exception {
        PurviewTokenCache tokenCache = new PurviewTokenCache();
        tokenCache.put("tenant-123", "client-123", "mock-token", 3600L);
        HttpPurviewApiClient client = new HttpPurviewApiClient(
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

    /**
     * The Data Map surface requires api-version on every operation, the probe included.
     *
     * <p>Without it the probe fails with a request-shape error while the entity client — which
     * sends the parameter — works, and the resulting "connection failed" points at credentials
     * that are fine.
     */
    @org.junit.jupiter.api.Test
    public void testProbeUriCarriesApiVersionOnDataMapSurface() {
        HttpPurviewApiClient client = new HttpPurviewApiClient();
        PurviewConnectionRequest request = new PurviewConnectionRequest(
                "https://example.purview.azure.com", "datamap/api/atlas/v2", "oauth2",
                "t", "c", "s", "", "", 5000, 30000);
        org.junit.jupiter.api.Assertions.assertEquals(
                "https://example.purview.azure.com/datamap/api/atlas/v2/types/typedefs/headers"
                        + "?api-version=" + PurviewDataMapApi.API_VERSION,
                client.buildProbeUri(request).toString());
    }

    /** The classic surface and Atlas OSS take no version parameter; their URIs stay bare. */
    @org.junit.jupiter.api.Test
    public void testProbeUriStaysBareOffTheDataMapSurface() {
        HttpPurviewApiClient client = new HttpPurviewApiClient();
        org.junit.jupiter.api.Assertions.assertEquals(
                "https://example.purview.azure.com/catalog/api/atlas/v2/types/typedefs/headers",
                client.buildProbeUri(new PurviewConnectionRequest(
                        "https://example.purview.azure.com", "catalog/api/atlas/v2", "oauth2",
                        "t", "c", "s", "", "", 5000, 30000)).toString());
        org.junit.jupiter.api.Assertions.assertEquals(
                "http://localhost:21000/api/atlas/v2/types/typedefs/headers",
                client.buildProbeUri(new PurviewConnectionRequest(
                        "http://localhost:21000", "api/atlas/v2", "basic",
                        "", "", "", "u", "p", 5000, 30000)).toString());
    }
}
