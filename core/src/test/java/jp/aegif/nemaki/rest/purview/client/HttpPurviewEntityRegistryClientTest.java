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
