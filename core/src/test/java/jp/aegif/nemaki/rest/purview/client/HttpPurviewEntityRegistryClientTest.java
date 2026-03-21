package jp.aegif.nemaki.rest.purview.client;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.net.http.HttpClient;

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
}
