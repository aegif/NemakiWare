package jp.aegif.nemaki.api.setup.resource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import jakarta.ws.rs.core.Response;

import jp.aegif.nemaki.api.setup.model.VectorTestRequest;
import jp.aegif.nemaki.util.PropertyManager;

/**
 * Tests for SetupVectorResource.
 * Validates SSRF hardening, response sanitisation, port restriction.
 */
public class SetupVectorResourceTest {

    private SetupVectorResource resource;
    private PropertyManager propertyManager;

    @BeforeEach
    public void setUp() throws Exception {
        resource = new SetupVectorResource();
        propertyManager = mock(PropertyManager.class);

        Field field = SetupVectorResource.class.getDeclaredField("propertyManager");
        field.setAccessible(true);
        field.set(resource, propertyManager);
    }

    // ================================================================
    // GET /vector/state
    // ================================================================

    @Nested
    class GetState {

        @Test
        public void testDefaults() {
            when(propertyManager.readValue("rag.enabled")).thenReturn(null);
            when(propertyManager.readValue("rag.tei.url")).thenReturn(null);

            Response resp = resource.getState();
            assertEquals(200, resp.getStatus());
            String body = (String) resp.getEntity();
            assertTrue(body.contains("\"type\":\"none\""), body);
            assertTrue(body.contains("\"url\":\"\""), body);
        }

        @Test
        public void testConfigured() {
            when(propertyManager.readValue("rag.enabled")).thenReturn("true");
            when(propertyManager.readValue("rag.tei.url")).thenReturn("http://tei:8080");

            Response resp = resource.getState();
            String body = (String) resp.getEntity();
            assertTrue(body.contains("\"type\":\"tei\""), body);
            assertTrue(body.contains("\"url\":\"http://tei:8080\""), body);
        }

        @Test
        public void testDisabledRag() {
            when(propertyManager.readValue("rag.enabled")).thenReturn("false");
            when(propertyManager.readValue("rag.tei.url")).thenReturn("http://tei:80");

            String body = (String) resource.getState().getEntity();
            assertTrue(body.contains("\"type\":\"none\""), body);
            // URL is still returned even when disabled
            assertTrue(body.contains("\"url\":\"http://tei:80\""), body);
        }

        @Test
        public void testNullPropertyManager() throws Exception {
            Field field = SetupVectorResource.class.getDeclaredField("propertyManager");
            field.setAccessible(true);
            field.set(resource, null);

            String body = (String) resource.getState().getEntity();
            assertTrue(body.contains("\"type\":\"none\""), body);
        }
    }

    // ================================================================
    // POST /vector/test-connection
    // ================================================================

    @Nested
    class TestConnection {

        @Test
        public void testNullRequest() {
            assertEquals(400, resource.testConnection(null).getStatus());
        }

        @Test
        public void testEmptyUrl() {
            VectorTestRequest req = new VectorTestRequest();
            req.setUrl("");
            assertEquals(400, resource.testConnection(req).getStatus());
        }

        @Test
        public void testCloudMetadataBlocked() {
            VectorTestRequest req = new VectorTestRequest();
            req.setUrl("http://169.254.169.254/computeMetadata/v1/");
            assertEquals(400, resource.testConnection(req).getStatus());
        }

        @Test
        public void testFileSchemeBlocked() {
            VectorTestRequest req = new VectorTestRequest();
            req.setUrl("file:///etc/passwd");
            assertEquals(400, resource.testConnection(req).getStatus());
        }

        // Private IPs allowed on service ports
        @Test
        public void testLocalhostAllowedOnServicePort() {
            VectorTestRequest req = new VectorTestRequest();
            req.setUrl("http://127.0.0.1:8080");

            Response resp = resource.testConnection(req);
            assertEquals(200, resp.getStatus());
            String body = (String) resp.getEntity();
            assertTrue(body.contains("reachable"), body);
        }

        // Non-service ports blocked
        @Test
        public void testNonServicePortBlocked() {
            VectorTestRequest req = new VectorTestRequest();
            req.setUrl("http://internal:6379");  // Redis
            assertEquals(400, resource.testConnection(req).getStatus());
        }

        // Response sanitisation: no exception details
        @Test
        public void testErrorResponseSanitised() {
            VectorTestRequest req = new VectorTestRequest();
            req.setUrl("http://nonexistent-host-12345.local:8080");

            Response resp = resource.testConnection(req);
            assertEquals(200, resp.getStatus());
            String body = (String) resp.getEntity();
            assertFalse(body.contains("UnknownHostException"), "Should not leak exception class: " + body);
            assertTrue(body.contains("Connection failed"), body);
        }

        // Response should NOT include HTTP status codes (information leakage)
        @Test
        public void testResponseDoesNotIncludeHttpStatus() {
            // Even if the server responds, we should not expose the exact status code
            VectorTestRequest req = new VectorTestRequest();
            req.setUrl("http://nonexistent-host-12345.local:8080");

            Response resp = resource.testConnection(req);
            String body = (String) resp.getEntity();
            assertFalse(body.contains("httpStatus"),
                    "Response should not include httpStatus to limit information leakage: " + body);
        }
    }

    // ================================================================
    // POST /vector/apply
    // ================================================================

    @Test
    public void testApplyNullRequest() {
        assertEquals(400, resource.apply(null).getStatus());
    }

    @Test
    public void testApplyWithRequest() {
        VectorTestRequest req = new VectorTestRequest();
        req.setType("none");
        Response resp = resource.apply(req);
        int status = resp.getStatus();
        // Returns 200 (success) or 500 (CouchDB unavailable in unit test)
        assertTrue(status == 200 || status == 500,
                "Expected 200 or 500, got " + status);
    }

    // ================================================================
    // POST /vector/apply — validation
    // ================================================================

    @Nested
    @DisplayName("Apply validation: reject incomplete payloads")
    class ApplyValidation {

        @Test
        @DisplayName("TEI with null URL → 400")
        public void testTeiNullUrl() {
            VectorTestRequest req = new VectorTestRequest();
            req.setType("tei");
            req.setUrl(null);
            Response resp = resource.apply(req);
            assertEquals(400, resp.getStatus());
            String body = (String) resp.getEntity();
            assertTrue(body.contains("url is required"), body);
        }

        @Test
        @DisplayName("TEI with empty URL → 400")
        public void testTeiEmptyUrl() {
            VectorTestRequest req = new VectorTestRequest();
            req.setType("tei");
            req.setUrl("   ");
            Response resp = resource.apply(req);
            assertEquals(400, resp.getStatus());
        }

        @Test
        @DisplayName("TEI with SSRF URL → 400")
        public void testTeiSsrfUrl() {
            VectorTestRequest req = new VectorTestRequest();
            req.setType("tei");
            req.setUrl("http://169.254.169.254/latest/meta-data/");
            Response resp = resource.apply(req);
            assertEquals(400, resp.getStatus());
            String body = (String) resp.getEntity();
            assertTrue(body.contains("blocked"), body);
        }

        @Test
        @DisplayName("Bedrock with null region → 400")
        public void testBedrockNullRegion() {
            VectorTestRequest req = new VectorTestRequest();
            req.setType("bedrock");
            req.setRegion(null);
            Response resp = resource.apply(req);
            assertEquals(400, resp.getStatus());
            String body = (String) resp.getEntity();
            assertTrue(body.contains("region is required"), body);
        }

        @Test
        @DisplayName("Bedrock with empty region → 400")
        public void testBedrockEmptyRegion() {
            VectorTestRequest req = new VectorTestRequest();
            req.setType("bedrock");
            req.setRegion("  ");
            Response resp = resource.apply(req);
            assertEquals(400, resp.getStatus());
        }
    }

    // ================================================================
    // POST /vector/apply — provider switch & credential lifecycle
    // ================================================================

    @Nested
    @DisplayName("Apply provider-switch: credential cleanup")
    class ApplyProviderSwitch {

        /** System properties that must be cleaned up after each test. */
        private static final String[] PROPS = {
                "rag.enabled", "rag.embedding.provider",
                "rag.tei.url",
                "rag.bedrock.region", "rag.bedrock.model.id",
                "rag.bedrock.access.key.id", "rag.bedrock.secret.access.key"
        };

        @AfterEach
        public void cleanupSystemProperties() {
            for (String prop : PROPS) {
                System.clearProperty(prop);
            }
        }

        @Test
        @DisplayName("Bedrock → TEI switch clears rag.bedrock.* credentials")
        public void testBedrockToTei_clearsBedrockCredentials() {
            System.setProperty("rag.bedrock.access.key.id", "AKIAIOSFODNN7EXAMPLE");
            System.setProperty("rag.bedrock.secret.access.key", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");

            VectorTestRequest req = new VectorTestRequest();
            req.setType("tei");
            req.setUrl("http://tei:80");

            try (MockedStatic<CouchDbConfigWriter> mocked = mockStatic(CouchDbConfigWriter.class)) {
                mocked.when(() -> CouchDbConfigWriter.putConfigValue(anyString(), anyString(), anyString(), anyString()))
                        .then(invocation -> null);
                mocked.when(() -> CouchDbConfigWriter.basicAuth(anyString(), anyString()))
                        .thenCallRealMethod();
                mocked.when(() -> CouchDbConfigWriter.escapeJson(anyString()))
                        .thenCallRealMethod();

                Response resp = resource.apply(req);
                assertEquals(200, resp.getStatus());
            }

            assertNull(System.getProperty("rag.bedrock.access.key.id"),
                    "rag.bedrock.access.key.id should be cleared after switching to TEI");
            assertNull(System.getProperty("rag.bedrock.secret.access.key"),
                    "rag.bedrock.secret.access.key should be cleared after switching to TEI");

            // TEI settings should be set
            assertEquals("true", System.getProperty("rag.enabled"));
            assertEquals("tei", System.getProperty("rag.embedding.provider"));
            assertEquals("http://tei:80", System.getProperty("rag.tei.url"));
        }

        @Test
        @DisplayName("Bedrock → none switch clears rag.bedrock.* credentials")
        public void testBedrockToNone_clearsBedrockCredentials() {
            System.setProperty("rag.bedrock.access.key.id", "AKIAIOSFODNN7EXAMPLE");
            System.setProperty("rag.bedrock.secret.access.key", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");

            VectorTestRequest req = new VectorTestRequest();
            req.setType("none");

            try (MockedStatic<CouchDbConfigWriter> mocked = mockStatic(CouchDbConfigWriter.class)) {
                mocked.when(() -> CouchDbConfigWriter.putConfigValue(anyString(), anyString(), anyString(), anyString()))
                        .then(invocation -> null);
                mocked.when(() -> CouchDbConfigWriter.basicAuth(anyString(), anyString()))
                        .thenCallRealMethod();

                Response resp = resource.apply(req);
                assertEquals(200, resp.getStatus());
            }

            assertNull(System.getProperty("rag.bedrock.access.key.id"),
                    "rag.bedrock.access.key.id should be cleared after disabling vector search");
            assertNull(System.getProperty("rag.bedrock.secret.access.key"),
                    "rag.bedrock.secret.access.key should be cleared after disabling vector search");
            assertEquals("false", System.getProperty("rag.enabled"));
        }

        @Test
        @DisplayName("Bedrock with empty credentials clears rag.bedrock.* (IAM/default chain fallback)")
        public void testBedrockEmptyCredentials_clearsBedrockCredentials() {
            System.setProperty("rag.bedrock.access.key.id", "OLD_KEY");
            System.setProperty("rag.bedrock.secret.access.key", "OLD_SECRET");

            VectorTestRequest req = new VectorTestRequest();
            req.setType("bedrock");
            req.setRegion("us-east-1");
            req.setModelId("amazon.titan-embed-text-v2:0");
            req.setAccessKeyId("");
            req.setSecretAccessKey("");

            try (MockedStatic<CouchDbConfigWriter> mocked = mockStatic(CouchDbConfigWriter.class)) {
                mocked.when(() -> CouchDbConfigWriter.putConfigValue(anyString(), anyString(), anyString(), anyString()))
                        .then(invocation -> null);
                mocked.when(() -> CouchDbConfigWriter.basicAuth(anyString(), anyString()))
                        .thenCallRealMethod();

                Response resp = resource.apply(req);
                assertEquals(200, resp.getStatus());
            }

            assertNull(System.getProperty("rag.bedrock.access.key.id"),
                    "rag.bedrock.access.key.id should be cleared for IAM role fallback");
            assertNull(System.getProperty("rag.bedrock.secret.access.key"),
                    "rag.bedrock.secret.access.key should be cleared for IAM role fallback");

            assertEquals("true", System.getProperty("rag.enabled"));
            assertEquals("bedrock", System.getProperty("rag.embedding.provider"));
            assertEquals("us-east-1", System.getProperty("rag.bedrock.region"));
        }

        @Test
        @DisplayName("Bedrock with explicit credentials sets only rag.bedrock.* (not aws.*)")
        public void testBedrockWithCredentials_setsOnlyRagProperties() {
            VectorTestRequest req = new VectorTestRequest();
            req.setType("bedrock");
            req.setRegion("ap-northeast-1");
            req.setModelId("amazon.titan-embed-text-v2:0");
            req.setAccessKeyId("AKIAEXAMPLE");
            req.setSecretAccessKey("SECRET123");

            try (MockedStatic<CouchDbConfigWriter> mocked = mockStatic(CouchDbConfigWriter.class)) {
                mocked.when(() -> CouchDbConfigWriter.putConfigValue(anyString(), anyString(), anyString(), anyString()))
                        .then(invocation -> null);
                mocked.when(() -> CouchDbConfigWriter.basicAuth(anyString(), anyString()))
                        .thenCallRealMethod();

                Response resp = resource.apply(req);
                assertEquals(200, resp.getStatus());
            }

            assertEquals("AKIAEXAMPLE", System.getProperty("rag.bedrock.access.key.id"));
            assertEquals("SECRET123", System.getProperty("rag.bedrock.secret.access.key"));
            assertEquals("ap-northeast-1", System.getProperty("rag.bedrock.region"));
            // aws.* must NOT be set — BedrockEmbeddingService reads rag.bedrock.* directly
            assertNull(System.getProperty("aws.accessKeyId"),
                    "aws.accessKeyId must not be set — it is JVM-global and affects other AWS clients");
            assertNull(System.getProperty("aws.secretAccessKey"),
                    "aws.secretAccessKey must not be set — it is JVM-global and affects other AWS clients");
        }

        @Test
        @DisplayName("Bedrock with null credentials treated as empty (IAM fallback)")
        public void testBedrockNullCredentials_treatedAsEmpty() {
            System.setProperty("rag.bedrock.access.key.id", "OLD_KEY");

            VectorTestRequest req = new VectorTestRequest();
            req.setType("bedrock");
            req.setRegion("us-west-2");
            req.setAccessKeyId(null);
            req.setSecretAccessKey(null);

            try (MockedStatic<CouchDbConfigWriter> mocked = mockStatic(CouchDbConfigWriter.class)) {
                mocked.when(() -> CouchDbConfigWriter.putConfigValue(anyString(), anyString(), anyString(), anyString()))
                        .then(invocation -> null);
                mocked.when(() -> CouchDbConfigWriter.basicAuth(anyString(), anyString()))
                        .thenCallRealMethod();

                Response resp = resource.apply(req);
                assertEquals(200, resp.getStatus());
            }

            assertNull(System.getProperty("rag.bedrock.access.key.id"),
                    "null credentials should clear rag.bedrock.access.key.id");
            assertNull(System.getProperty("rag.bedrock.secret.access.key"));
        }
    }
}
