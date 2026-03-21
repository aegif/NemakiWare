package jp.aegif.nemaki.rest.purview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.util.PropertyManager;

public class PurviewConfigTest {

    @Test
    public void testReadsDynamicValuesAndNormalizesEndpoint() throws Exception {
        PurviewConfig config = new PurviewConfig();
        setField(config, "enabled", false);
        setField(config, "accountName", "");
        setField(config, "endpoint", "https://fallback.purview.azure.com/");
        setField(config, "atlasBasePath", "datamap/api/atlas/v2");
        setField(config, "tenantId", "tenant-default");
        setField(config, "clientId", "client-default");
        setField(config, "clientSecret", "secret-default");
        setField(config, "connectTimeoutMs", 5000);
        setField(config, "readTimeoutMs", 30000);
        setField(config, "deleteResolutionDelayMs", 5000L);

        PropertyManager propertyManager = mock(PropertyManager.class);
        when(propertyManager.readValue("purview.enabled")).thenReturn("true");
        when(propertyManager.readValue("purview.account.name")).thenReturn("example-account");
        when(propertyManager.readValue("purview.endpoint")).thenReturn("https://example-account.purview.azure.com/");
        when(propertyManager.readValue("purview.atlas.base-path")).thenReturn("catalog/api/atlas/v2/");
        when(propertyManager.readValue("purview.tenant.id")).thenReturn("tenant-dynamic");
        when(propertyManager.readValue("purview.client.id")).thenReturn("client-dynamic");
        when(propertyManager.readValue("purview.client.secret")).thenReturn("secret-dynamic");
        when(propertyManager.readValue("purview.timeout.connect.ms")).thenReturn("7000");
        when(propertyManager.readValue("purview.timeout.read.ms")).thenReturn("45000");
        when(propertyManager.readValue("purview.delete-resolution.delay.ms")).thenReturn("15000");
        setField(config, "propertyManager", propertyManager);

        assertTrue(config.isEnabled());
        assertEquals("example-account", config.getAccountName());
        assertEquals("https://example-account.purview.azure.com", config.getEndpoint());
        assertEquals("catalog/api/atlas/v2", config.getAtlasBasePath());
        assertEquals("tenant-dynamic", config.getTenantId());
        assertEquals("client-dynamic", config.getClientId());
        assertEquals("secret-dynamic", config.getClientSecret());
        assertEquals(7000, config.getConnectTimeoutMs());
        assertEquals(45000, config.getReadTimeoutMs());
        assertEquals(15000L, config.getDeleteResolutionDelayMs());
    }

    @Test
    public void testBuildsEndpointFromAccountNameWhenEndpointIsBlank() throws Exception {
        PurviewConfig config = new PurviewConfig();
        setField(config, "enabled", false);
        setField(config, "accountName", "constructed-account");
        setField(config, "endpoint", "");
        setField(config, "atlasBasePath", "datamap/api/atlas/v2");
        setField(config, "tenantId", "");
        setField(config, "clientId", "");
        setField(config, "clientSecret", "");
        setField(config, "connectTimeoutMs", 5000);
        setField(config, "readTimeoutMs", 30000);
        setField(config, "deleteResolutionDelayMs", 5000L);

        assertEquals("https://constructed-account.purview.azure.com", config.getEndpoint());
        assertFalse(config.isEnabled());
    }

    @Test
    public void testPlaintextSecretIsDetected() {
        assertTrue(PurviewConfig.looksLikePlaintext("my-secret-value"));
        assertTrue(PurviewConfig.looksLikePlaintext("abc123"));
    }

    @Test
    public void testEncryptedSecretIsNotFlagged() {
        assertFalse(PurviewConfig.looksLikePlaintext("ENC(abc123==)"));
        assertFalse(PurviewConfig.looksLikePlaintext("${PURVIEW_SECRET}"));
        assertFalse(PurviewConfig.looksLikePlaintext("vault:secret/data/purview"));
    }

    @Test
    public void testEmptySecretIsNotFlagged() {
        assertFalse(PurviewConfig.looksLikePlaintext(""));
        assertFalse(PurviewConfig.looksLikePlaintext(null));
    }

    @Test
    public void testWarnIfPlaintextSecretDoesNotThrowWhenDisabled() throws Exception {
        PurviewConfig config = new PurviewConfig();
        setField(config, "enabled", false);
        setField(config, "clientSecret", "plaintext-secret");
        setField(config, "accountName", "");
        setField(config, "endpoint", "");
        setField(config, "atlasBasePath", "");
        setField(config, "tenantId", "");
        setField(config, "clientId", "");
        setField(config, "connectTimeoutMs", 5000);
        setField(config, "readTimeoutMs", 30000);
        setField(config, "deleteResolutionDelayMs", 5000L);
        // Should not throw — disabled means no warning
        config.warnIfPlaintextSecret();
    }

    @Test
    public void testWarnIfPlaintextSecretRunsWhenEnabled() throws Exception {
        PurviewConfig config = new PurviewConfig();
        setField(config, "enabled", true);
        setField(config, "clientSecret", "plaintext-secret");
        setField(config, "accountName", "");
        setField(config, "endpoint", "");
        setField(config, "atlasBasePath", "");
        setField(config, "tenantId", "");
        setField(config, "clientId", "");
        setField(config, "connectTimeoutMs", 5000);
        setField(config, "readTimeoutMs", 30000);
        setField(config, "deleteResolutionDelayMs", 5000L);
        // Should log WARN but not throw
        config.warnIfPlaintextSecret();
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
