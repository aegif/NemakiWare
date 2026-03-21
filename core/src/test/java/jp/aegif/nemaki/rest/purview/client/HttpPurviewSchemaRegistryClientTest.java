package jp.aegif.nemaki.rest.purview.client;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.net.http.HttpClient;

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
}
