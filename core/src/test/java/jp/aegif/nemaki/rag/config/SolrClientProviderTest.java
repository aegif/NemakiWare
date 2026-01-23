package jp.aegif.nemaki.rag.config;

import org.apache.solr.client.solrj.SolrClient;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.*;

/**
 * Unit tests for SolrClientProvider.
 *
 * Tests:
 * - Singleton pattern (same instance returned)
 * - Lazy initialization
 * - Thread-safety (basic verification)
 */
public class SolrClientProviderTest {

    private SolrClientProvider provider;

    @Before
    public void setUp() throws Exception {
        provider = new SolrClientProvider();

        // Set default values using reflection since @Value annotations won't work in unit tests
        setField(provider, "solrHost", "localhost");
        setField(provider, "solrPort", 8983);
        setField(provider, "solrProtocol", "http");
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = SolrClientProvider.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Object getField(Object target, String fieldName) throws Exception {
        Field field = SolrClientProvider.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    // ========== Singleton Pattern Tests ==========

    @Test
    public void testGetClientReturnsSameInstance() {
        // First call creates the client
        SolrClient client1 = provider.getClient();
        assertNotNull("First getClient() should return non-null", client1);

        // Second call should return the same instance
        SolrClient client2 = provider.getClient();
        assertNotNull("Second getClient() should return non-null", client2);

        // Should be the same instance (singleton)
        assertSame("getClient() should return the same instance (singleton)",
                client1, client2);
    }

    @Test
    public void testGetClientMultipleCalls() {
        // Multiple calls should all return the same instance
        SolrClient client1 = provider.getClient();
        SolrClient client2 = provider.getClient();
        SolrClient client3 = provider.getClient();

        assertSame("All calls should return same instance", client1, client2);
        assertSame("All calls should return same instance", client2, client3);
    }

    // ========== Lazy Initialization Tests ==========

    @Test
    public void testLazyInitialization() throws Exception {
        // Before calling getClient(), solrClient field should be null
        Object clientField = getField(provider, "solrClient");
        assertNull("SolrClient should be null before first getClient() call", clientField);

        // After calling getClient(), solrClient field should be non-null
        provider.getClient();
        clientField = getField(provider, "solrClient");
        assertNotNull("SolrClient should be non-null after getClient() call", clientField);
    }

    // ========== URL Construction Tests ==========

    @Test
    public void testGetSolrUrl() {
        String url = provider.getSolrUrl();

        assertNotNull("getSolrUrl() should return non-null", url);
        assertEquals("URL should be correctly formatted",
                "http://localhost:8983/solr", url);
    }

    @Test
    public void testGetSolrUrlWithHttps() throws Exception {
        setField(provider, "solrProtocol", "https");

        String url = provider.getSolrUrl();
        assertTrue("URL should use https", url.startsWith("https://"));
    }

    @Test
    public void testGetSolrUrlWithCustomPort() throws Exception {
        setField(provider, "solrPort", 9999);

        String url = provider.getSolrUrl();
        assertTrue("URL should contain custom port", url.contains(":9999"));
    }

    @Test
    public void testGetSolrUrlWithCustomHost() throws Exception {
        setField(provider, "solrHost", "solr.example.com");

        String url = provider.getSolrUrl();
        assertTrue("URL should contain custom host", url.contains("solr.example.com"));
    }

    // ========== Thread Safety Tests (Basic) ==========

    @Test
    public void testConcurrentGetClient() throws InterruptedException {
        final int threadCount = 10;
        final SolrClient[] clients = new SolrClient[threadCount];
        final Thread[] threads = new Thread[threadCount];

        // Create multiple threads that all call getClient() simultaneously
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                clients[index] = provider.getClient();
            });
        }

        // Start all threads
        for (Thread t : threads) {
            t.start();
        }

        // Wait for all threads to complete
        for (Thread t : threads) {
            t.join();
        }

        // Verify all threads got the same instance
        SolrClient firstClient = clients[0];
        assertNotNull("First client should not be null", firstClient);

        for (int i = 1; i < threadCount; i++) {
            assertSame("All threads should receive the same SolrClient instance",
                    firstClient, clients[i]);
        }
    }

    // ========== Cleanup Tests ==========

    @Test
    public void testCleanupWhenClientNotInitialized() {
        // cleanup() should not throw exception when client is not initialized
        try {
            provider.cleanup();
            // No exception means success
        } catch (Exception e) {
            fail("cleanup() should not throw exception when client is null: " + e.getMessage());
        }
    }

    @Test
    public void testCleanupAfterGetClient() {
        // Initialize the client
        provider.getClient();

        // cleanup() should not throw exception
        try {
            provider.cleanup();
            // No exception means success
        } catch (Exception e) {
            fail("cleanup() should not throw exception: " + e.getMessage());
        }
    }
}
