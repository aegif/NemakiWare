package jp.aegif.nemaki.rest.purview.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

public class HttpPurviewSchemaRegistryClientTest {

    @Test
    public void testReusesInjectedHttpClient() {
        HttpClient sharedClient = HttpClient.newHttpClient();
        HttpPurviewSchemaRegistryClient client = new HttpPurviewSchemaRegistryClient(sharedClient);
        assertSame(sharedClient, client.getHttpClient());
    }

    @Test
    public void testDefaultConstructorCreatesHttpClient() {
        HttpPurviewSchemaRegistryClient client = new HttpPurviewSchemaRegistryClient();
        assertSame(client.getHttpClient(), client.getHttpClient());
    }

    @Test
    public void testBuildAuthorizationHeaderReturnsBasicForBasicAuth() throws Exception {
        HttpPurviewSchemaRegistryClient client = new HttpPurviewSchemaRegistryClient();
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
        HttpPurviewSchemaRegistryClient client = new HttpPurviewSchemaRegistryClient(
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
