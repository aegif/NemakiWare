package jp.aegif.nemaki.rest.purview.client;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.net.http.HttpClient;

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
}
