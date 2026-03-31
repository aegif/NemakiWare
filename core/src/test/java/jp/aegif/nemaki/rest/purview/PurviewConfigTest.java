package jp.aegif.nemaki.rest.purview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jp.aegif.nemaki.rest.controller.IntegrationSettingsService;
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

    @Nested
    class LegacyMigration {

        @Test
        public void testMigratesBasicAuthToAtlasSettingsAtomically() throws Exception {
            PurviewConfig config = new PurviewConfig();
            setField(config, "enabled", true);
            setField(config, "endpoint", "https://atlas.example.com");
            setField(config, "legacyAuthType", "basic");
            setField(config, "legacyBasicUsername", "atlas-user");
            setField(config, "legacyBasicPassword", "atlas-pass");
            setField(config, "accountName", "");
            setField(config, "atlasBasePath", "datamap/api/atlas/v2");
            setField(config, "tenantId", "");
            setField(config, "clientId", "");
            setField(config, "clientSecret", "");
            setField(config, "connectTimeoutMs", 5000);
            setField(config, "readTimeoutMs", 30000);
            setField(config, "deleteResolutionDelayMs", 5000L);
            setField(config, "syncCron", "");
            setField(config, "collection", "MyCustomCollection");

            IntegrationSettingsService settingsService = mock(IntegrationSettingsService.class);
            setField(config, "integrationSettingsService", settingsService);

            config.migrateBasicAuthToAtlas();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, String>> writeCaptor = ArgumentCaptor.forClass(Map.class);
            verify(settingsService).writeSettings(writeCaptor.capture());
            Map<String, String> written = writeCaptor.getValue();
            assertEquals("true", written.get("atlas.enabled"));
            assertEquals("https://atlas.example.com", written.get("atlas.endpoint"));
            assertEquals("atlas-user", written.get("atlas.username"));
            assertEquals("atlas-pass", written.get("atlas.password"));
            // P1: purview.enabled must be disabled so resolver picks Atlas
            assertEquals("false", written.get("purview.enabled"));
            // P2: collection must be preserved to avoid silent scope changes
            assertEquals("MyCustomCollection", written.get("atlas.collection"));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Set<String>> deleteCaptor = ArgumentCaptor.forClass(Set.class);
            verify(settingsService).deleteSettings(deleteCaptor.capture());
            Set<String> deleted = deleteCaptor.getValue();
            assertTrue(deleted.contains("purview.auth.type"));
            assertTrue(deleted.contains("purview.basic.username"));
            assertTrue(deleted.contains("purview.basic.password"));
        }

        @Test
        public void testSkipsMigrationWhenAlreadyMigratedToCouchDB() throws Exception {
            PurviewConfig config = new PurviewConfig();
            setField(config, "enabled", true);
            setField(config, "endpoint", "https://atlas.example.com");
            setField(config, "legacyAuthType", "basic");
            setField(config, "legacyBasicUsername", "atlas-user");
            setField(config, "legacyBasicPassword", "atlas-pass");
            setField(config, "accountName", "");
            setField(config, "atlasBasePath", "datamap/api/atlas/v2");
            setField(config, "tenantId", "");
            setField(config, "clientId", "");
            setField(config, "clientSecret", "");
            setField(config, "connectTimeoutMs", 5000);
            setField(config, "readTimeoutMs", 30000);
            setField(config, "deleteResolutionDelayMs", 5000L);
            setField(config, "syncCron", "");
            setField(config, "collection", "NemakiWare");

            IntegrationSettingsService settingsService = mock(IntegrationSettingsService.class);
            // atlas.enabled already exists in CouchDB from a previous migration
            when(settingsService.readSetting("atlas.enabled")).thenReturn("true");
            when(settingsService.readSettingSource("atlas.enabled")).thenReturn("couchdb");
            setField(config, "integrationSettingsService", settingsService);

            config.migrateBasicAuthToAtlas();

            // Should not write or delete anything — already migrated to CouchDB
            verify(settingsService, never()).writeSettings(anyMap());
            verify(settingsService, never()).deleteSettings(anySet());
        }

        @Test
        public void testRunsMigrationWhenAtlasEnabledFromPropertiesFileOnly() throws Exception {
            PurviewConfig config = new PurviewConfig();
            setField(config, "enabled", true);
            setField(config, "endpoint", "https://atlas.example.com");
            setField(config, "legacyAuthType", "basic");
            setField(config, "legacyBasicUsername", "atlas-user");
            setField(config, "legacyBasicPassword", "atlas-pass");
            setField(config, "accountName", "");
            setField(config, "atlasBasePath", "datamap/api/atlas/v2");
            setField(config, "tenantId", "");
            setField(config, "clientId", "");
            setField(config, "clientSecret", "");
            setField(config, "connectTimeoutMs", 5000);
            setField(config, "readTimeoutMs", 30000);
            setField(config, "deleteResolutionDelayMs", 5000L);
            setField(config, "syncCron", "");
            setField(config, "collection", "NemakiWare");

            IntegrationSettingsService settingsService = mock(IntegrationSettingsService.class);
            // atlas.enabled=true comes from properties file, NOT from a previous CouchDB migration
            when(settingsService.readSetting("atlas.enabled")).thenReturn("true");
            when(settingsService.readSettingSource("atlas.enabled")).thenReturn("properties_file");
            setField(config, "integrationSettingsService", settingsService);

            config.migrateBasicAuthToAtlas();

            // Migration must still run to write purview.enabled=false to CouchDB
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, String>> writeCaptor = ArgumentCaptor.forClass(Map.class);
            verify(settingsService).writeSettings(writeCaptor.capture());
            Map<String, String> written = writeCaptor.getValue();
            assertEquals("false", written.get("purview.enabled"));
            assertEquals("true", written.get("atlas.enabled"));
        }

        @Test
        public void testSkipsMigrationWhenAuthTypeIsNotBasic() throws Exception {
            PurviewConfig config = new PurviewConfig();
            setField(config, "enabled", false);
            setField(config, "endpoint", "");
            setField(config, "legacyAuthType", "oauth2");
            setField(config, "legacyBasicUsername", "");
            setField(config, "legacyBasicPassword", "");
            setField(config, "accountName", "");
            setField(config, "atlasBasePath", "datamap/api/atlas/v2");
            setField(config, "tenantId", "");
            setField(config, "clientId", "");
            setField(config, "clientSecret", "");
            setField(config, "connectTimeoutMs", 5000);
            setField(config, "readTimeoutMs", 30000);
            setField(config, "deleteResolutionDelayMs", 5000L);
            setField(config, "syncCron", "");
            setField(config, "collection", "NemakiWare");

            IntegrationSettingsService settingsService = mock(IntegrationSettingsService.class);
            setField(config, "integrationSettingsService", settingsService);

            config.migrateBasicAuthToAtlas();

            verify(settingsService, never()).writeSettings(anyMap());
            verify(settingsService, never()).deleteSettings(anySet());
        }

        @Test
        public void testSkipsMigrationWhenIntegrationSettingsServiceIsNull() throws Exception {
            PurviewConfig config = new PurviewConfig();
            setField(config, "enabled", false);
            setField(config, "endpoint", "");
            setField(config, "legacyAuthType", "basic");
            setField(config, "legacyBasicUsername", "user");
            setField(config, "legacyBasicPassword", "pass");
            setField(config, "accountName", "");
            setField(config, "atlasBasePath", "datamap/api/atlas/v2");
            setField(config, "tenantId", "");
            setField(config, "clientId", "");
            setField(config, "clientSecret", "");
            setField(config, "connectTimeoutMs", 5000);
            setField(config, "readTimeoutMs", 30000);
            setField(config, "deleteResolutionDelayMs", 5000L);
            setField(config, "syncCron", "");
            setField(config, "collection", "NemakiWare");
            // integrationSettingsService is null by default

            // Should not throw, just log a warning
            config.migrateBasicAuthToAtlas();
        }
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
