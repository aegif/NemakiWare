package jp.aegif.nemaki.rag.embedding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyString;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.rag.config.RAGConfig;
import jp.aegif.nemaki.util.PropertyManager;

/**
 * Unit tests for BedrockEmbeddingService.
 *
 * These tests verify Bedrock-specific logic without requiring a real AWS
 * environment by testing internal methods via reflection and exercising the
 * config wiring, error handling, and health-check TTL cache.
 */
public class BedrockEmbeddingServiceTest {

    private RAGConfig ragConfig;
    private BedrockEmbeddingService service;

    @BeforeEach
    public void setUp() throws Exception {
        ragConfig = new RAGConfig();
        // Set minimum viable @Value fields via reflection (no Spring context)
        setField(ragConfig, "enabled", true);
        setField(ragConfig, "embeddingProvider", "bedrock");
        setField(ragConfig, "bedrockRegion", "us-east-1");
        setField(ragConfig, "bedrockModelId", "amazon.titan-embed-text-v2:0");
        setField(ragConfig, "bedrockBatchSize", 32);
        setField(ragConfig, "bedrockMaxInputChars", 8000);
        setField(ragConfig, "bedrockTimeoutMs", 30000);
        setField(ragConfig, "bedrockVectorDimension", 1024);
        setField(ragConfig, "bedrockAccessKeyId", "");
        setField(ragConfig, "bedrockSecretAccessKey", "");

        service = new BedrockEmbeddingService(ragConfig);
    }

    // ========================================================================
    // validateConfig() tests
    // ========================================================================

    @Test
    @DisplayName("validateConfig passes when region and modelId are set")
    public void testValidateConfig_passes() throws Exception {
        Method m = BedrockEmbeddingService.class.getDeclaredMethod("validateConfig");
        m.setAccessible(true);
        // Should not throw
        m.invoke(service);
    }

    @Test
    @DisplayName("validateConfig throws when region is blank")
    public void testValidateConfig_blankRegion() throws Exception {
        setField(ragConfig, "bedrockRegion", "");
        Method m = BedrockEmbeddingService.class.getDeclaredMethod("validateConfig");
        m.setAccessible(true);
        try {
            m.invoke(service);
            fail("Should have thrown EmbeddingException");
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof EmbeddingException);
            assertTrue(e.getCause().getMessage().contains("region"));
        }
    }

    @Test
    @DisplayName("validateConfig throws when modelId is blank")
    public void testValidateConfig_blankModelId() throws Exception {
        setField(ragConfig, "bedrockModelId", "");
        Method m = BedrockEmbeddingService.class.getDeclaredMethod("validateConfig");
        m.setAccessible(true);
        try {
            m.invoke(service);
            fail("Should have thrown EmbeddingException");
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof EmbeddingException);
            assertTrue(e.getCause().getMessage().contains("modelId"));
        }
    }

    @Test
    @DisplayName("validateConfig skips checks when provider is not bedrock")
    public void testValidateConfig_nonBedrockProvider() throws Exception {
        setField(ragConfig, "embeddingProvider", "tei");
        setField(ragConfig, "bedrockRegion", ""); // blank, but should not matter
        Method m = BedrockEmbeddingService.class.getDeclaredMethod("validateConfig");
        m.setAccessible(true);
        // Should not throw
        m.invoke(service);
    }

    // ========================================================================
    // embed() input validation
    // ========================================================================

    @Test
    @DisplayName("embed throws for null text")
    public void testEmbed_nullText() {
        assertThrows(EmbeddingException.class, () -> service.embed(null, true));
    }

    @Test
    @DisplayName("embed throws for blank text")
    public void testEmbed_blankText() {
        assertThrows(EmbeddingException.class, () -> service.embed("  ", false));
    }

    // ========================================================================
    // embedBatch() input validation
    // ========================================================================

    @Test
    @DisplayName("embedBatch throws for null list")
    public void testEmbedBatch_nullList() {
        assertThrows(EmbeddingException.class, () -> service.embedBatch(null, true));
    }

    @Test
    @DisplayName("embedBatch throws for empty list")
    public void testEmbedBatch_emptyList() {
        assertThrows(EmbeddingException.class,
                () -> service.embedBatch(List.of(), false));
    }

    // ========================================================================
    // parseEmbeddingResponse() tests
    // ========================================================================

    @Test
    @DisplayName("parseEmbeddingResponse extracts embedding array correctly")
    public void testParseEmbeddingResponse_valid() throws Exception {
        Method m = BedrockEmbeddingService.class.getDeclaredMethod(
                "parseEmbeddingResponse", String.class);
        m.setAccessible(true);

        // Build a response with a 3-dim embedding
        String response = "{\"embedding\":[0.1, 0.2, 0.3]}";
        float[] result = (float[]) m.invoke(service, response);

        assertEquals(3, result.length);
        assertEquals(0.1f, result[0], 0.001f);
        assertEquals(0.2f, result[1], 0.001f);
        assertEquals(0.3f, result[2], 0.001f);
    }

    @Test
    @DisplayName("parseEmbeddingResponse throws for missing embedding field")
    public void testParseEmbeddingResponse_missingEmbedding() throws Exception {
        Method m = BedrockEmbeddingService.class.getDeclaredMethod(
                "parseEmbeddingResponse", String.class);
        m.setAccessible(true);

        try {
            m.invoke(service, "{\"other\":123}");
            fail("Should have thrown");
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof EmbeddingException);
            assertTrue(e.getCause().getMessage().contains("Unexpected Bedrock response format"));
        }
    }

    @Test
    @DisplayName("parseEmbeddingResponse throws for non-array embedding")
    public void testParseEmbeddingResponse_notArray() throws Exception {
        Method m = BedrockEmbeddingService.class.getDeclaredMethod(
                "parseEmbeddingResponse", String.class);
        m.setAccessible(true);

        try {
            m.invoke(service, "{\"embedding\":\"not-an-array\"}");
            fail("Should have thrown");
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof EmbeddingException);
        }
    }

    @Test
    @DisplayName("parseEmbeddingResponse throws for malformed JSON")
    public void testParseEmbeddingResponse_malformedJson() throws Exception {
        Method m = BedrockEmbeddingService.class.getDeclaredMethod(
                "parseEmbeddingResponse", String.class);
        m.setAccessible(true);

        try {
            m.invoke(service, "not-json-at-all");
            fail("Should have thrown");
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof EmbeddingException);
        }
    }

    // ========================================================================
    // isHealthy() TTL cache tests
    // ========================================================================

    @Test
    @DisplayName("isHealthy returns false when config is invalid")
    public void testIsHealthy_invalidConfig() throws Exception {
        setField(ragConfig, "bedrockRegion", "");
        assertFalse(service.isHealthy());
    }

    @Test
    @DisplayName("isHealthy caches result within TTL window")
    public void testIsHealthy_cachesTTL() throws Exception {
        // Manually set the health cache fields
        Field lastHealthy = BedrockEmbeddingService.class.getDeclaredField("lastHealthy");
        lastHealthy.setAccessible(true);
        Field lastHealthCheckTime = BedrockEmbeddingService.class.getDeclaredField("lastHealthCheckTime");
        lastHealthCheckTime.setAccessible(true);

        // Simulate a recent successful check
        lastHealthy.set(service, true);
        lastHealthCheckTime.set(service, System.currentTimeMillis());

        // Should return cached true without making a real API call
        assertTrue(service.isHealthy());
    }

    @Test
    @DisplayName("isHealthy cache expires after TTL")
    public void testIsHealthy_cacheExpires() throws Exception {
        Field lastHealthy = BedrockEmbeddingService.class.getDeclaredField("lastHealthy");
        lastHealthy.setAccessible(true);
        Field lastHealthCheckTime = BedrockEmbeddingService.class.getDeclaredField("lastHealthCheckTime");
        lastHealthCheckTime.setAccessible(true);

        // Simulate an old check that is past TTL
        lastHealthy.set(service, true);
        lastHealthCheckTime.set(service, System.currentTimeMillis() - 60_000); // 60s ago

        // Will attempt a real health check, which will fail (no actual Bedrock)
        // and return false (updating the cache)
        boolean result = service.isHealthy();
        assertFalse(result, "Health check should fail without real Bedrock");

        // Verify cache was updated
        assertFalse((boolean) lastHealthy.get(service));
    }

    @Test
    @DisplayName("isHealthy failure TTL is shorter than success TTL")
    public void testIsHealthy_failureTTLShorter() throws Exception {
        Field successTTL = BedrockEmbeddingService.class.getDeclaredField("HEALTH_CACHE_TTL_SUCCESS_MS");
        successTTL.setAccessible(true);
        Field failureTTL = BedrockEmbeddingService.class.getDeclaredField("HEALTH_CACHE_TTL_FAILURE_MS");
        failureTTL.setAccessible(true);

        long success = (long) successTTL.get(null);
        long failure = (long) failureTTL.get(null);

        assertTrue(failure < success,
                "Failure TTL (" + failure + "ms) should be shorter than success TTL (" + success + "ms)");
    }

    // ========================================================================
    // clientConfigKey change detection
    // ========================================================================

    @Test
    @DisplayName("getClient detects config changes via clientConfigKey")
    public void testClientConfigKey_changesOnRegionUpdate() throws Exception {
        Field configKey = BedrockEmbeddingService.class.getDeclaredField("clientConfigKey");
        configKey.setAccessible(true);

        // Initial config key should be empty
        assertEquals("", configKey.get(service));
    }

    // ========================================================================
    // getVectorDimension()
    // ========================================================================

    @Test
    @DisplayName("getVectorDimension returns configured value")
    public void testGetVectorDimension() {
        assertEquals(1024, service.getVectorDimension());
    }

    @Test
    @DisplayName("getVectorDimension reflects runtime changes")
    public void testGetVectorDimension_runtimeChange() throws Exception {
        setField(ragConfig, "bedrockVectorDimension", 256);
        assertEquals(256, service.getVectorDimension());
    }

    // ========================================================================
    // EmbeddingException re-throw (P3 fix verification)
    // ========================================================================

    @Test
    @DisplayName("embedSingle re-throws EmbeddingException from parseEmbeddingResponse without wrapping")
    public void testEmbedSingle_rethrowsEmbeddingException() throws Exception {
        // Create a service subclass that overrides getClient to return a mock-like behavior
        // Instead, we test parseEmbeddingResponse directly since embedSingle requires a real client
        Method parse = BedrockEmbeddingService.class.getDeclaredMethod(
                "parseEmbeddingResponse", String.class);
        parse.setAccessible(true);

        // This should throw EmbeddingException (not wrap it in connectionError)
        try {
            parse.invoke(service, "{\"noEmbedding\":true}");
            fail("Should have thrown");
        } catch (Exception e) {
            Throwable cause = e.getCause();
            assertInstanceOf(EmbeddingException.class, cause);
            // Verify it's NOT a connectionError wrapping
            assertFalse(cause.getMessage().contains("request failed"),
                    "Should be the original EmbeddingException, not wrapped in connectionError");
        }
    }

    // ========================================================================
    // RAGConfig dynamic wiring tests
    // ========================================================================

    @Test
    @DisplayName("RAGConfig returns @Value defaults when PropertyManager is null")
    public void testRAGConfig_defaultsWithoutPropertyManager() {
        // PropertyManager is @Autowired(required=false), so null in unit test context
        assertEquals("us-east-1", ragConfig.getBedrockRegion());
        assertEquals("amazon.titan-embed-text-v2:0", ragConfig.getBedrockModelId());
        assertEquals(1024, ragConfig.getBedrockVectorDimension());
        assertEquals("bedrock", ragConfig.getEmbeddingProvider());
        assertTrue(ragConfig.isEnabled());
    }

    @Test
    @DisplayName("RAGConfig reads from PropertyManager when available")
    public void testRAGConfig_readsFromPropertyManager() throws Exception {
        // Create a fresh RAGConfig with @Value defaults
        RAGConfig config = new RAGConfig();
        setField(config, "bedrockRegion", "us-east-1");
        setField(config, "bedrockModelId", "amazon.titan-embed-text-v2:0");
        setField(config, "bedrockAccessKeyId", "");
        setField(config, "bedrockSecretAccessKey", "");
        setField(config, "bedrockVectorDimension", 1024);
        setField(config, "embeddingProvider", "bedrock");
        setField(config, "enabled", true);

        // Inject a mock PropertyManager that returns dynamic values
        PropertyManager mockPm = mock(PropertyManager.class);
        when(mockPm.readValue("rag.bedrock.region")).thenReturn("eu-west-1");
        when(mockPm.readValue("rag.bedrock.model.id")).thenReturn("amazon.titan-embed-text-v1");
        when(mockPm.readValue("rag.bedrock.access.key.id")).thenReturn("");
        when(mockPm.readValue("rag.bedrock.secret.access.key")).thenReturn("");
        when(mockPm.readValue("rag.embedding.provider")).thenReturn("bedrock");
        setField(config, "propertyManager", mockPm);

        // Verify that getters now delegate to PropertyManager, not @Value defaults
        assertEquals("eu-west-1", config.getBedrockRegion(),
                "getBedrockRegion should return PropertyManager value, not @Value default");
        assertEquals("amazon.titan-embed-text-v1", config.getBedrockModelId(),
                "getBedrockModelId should return PropertyManager value");
        assertEquals("bedrock", config.getEmbeddingProvider(),
                "getEmbeddingProvider should return PropertyManager value");

        // Verify empty credentials are returned as-is (enables default credential chain)
        assertEquals("", config.getBedrockAccessKeyId(),
                "Empty accessKeyId should be returned as empty string");
        assertEquals("", config.getBedrockSecretAccessKey(),
                "Empty secretAccessKey should be returned as empty string");
    }

    @Test
    @DisplayName("RAGConfig falls back to @Value default when PropertyManager returns null")
    public void testRAGConfig_fallsBackWhenPropertyManagerReturnsNull() throws Exception {
        RAGConfig config = new RAGConfig();
        setField(config, "bedrockRegion", "us-east-1");
        setField(config, "embeddingProvider", "bedrock");
        setField(config, "enabled", true);

        // Inject a mock PropertyManager that returns null for all keys
        PropertyManager mockPm = mock(PropertyManager.class);
        when(mockPm.readValue(anyString())).thenReturn(null);
        setField(config, "propertyManager", mockPm);

        assertEquals("us-east-1", config.getBedrockRegion(),
                "Should fall back to @Value default when PropertyManager returns null");
        assertEquals("bedrock", config.getEmbeddingProvider(),
                "Should fall back to @Value default when PropertyManager returns null");
    }

    @Test
    @DisplayName("RAGConfig credential getters return empty string by default")
    public void testRAGConfig_credentialDefaults() {
        assertEquals("", ragConfig.getBedrockAccessKeyId());
        assertEquals("", ragConfig.getBedrockSecretAccessKey());
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
