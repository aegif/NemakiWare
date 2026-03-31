package jp.aegif.nemaki.rest.purview.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.util.PropertyManager;

public class AtlasConfigTest {

    @Test
    public void testReadsDynamicValuesFromPropertyManager() throws Exception {
        AtlasConfig config = new AtlasConfig();
        setField(config, "enabled", false);
        setField(config, "endpoint", "https://fallback.atlas.local");
        setField(config, "username", "fallback-user");
        setField(config, "password", "fallback-pass");
        setField(config, "connectTimeoutMs", 5000);
        setField(config, "readTimeoutMs", 30000);
        setField(config, "syncCron", "");
        setField(config, "collection", "NemakiWare");

        PropertyManager propertyManager = mock(PropertyManager.class);
        when(propertyManager.readValue("atlas.enabled")).thenReturn("true");
        when(propertyManager.readValue("atlas.endpoint")).thenReturn("https://dynamic.atlas.local");
        when(propertyManager.readValue("atlas.username")).thenReturn("dynamic-user");
        when(propertyManager.readValue("atlas.password")).thenReturn("dynamic-pass");
        when(propertyManager.readValue("atlas.connect.timeout.ms")).thenReturn("7000");
        when(propertyManager.readValue("atlas.read.timeout.ms")).thenReturn("45000");
        when(propertyManager.readValue("atlas.sync.cron")).thenReturn("0 */5 * * * ?");
        when(propertyManager.readValue("atlas.collection")).thenReturn("CustomCollection");
        setField(config, "propertyManager", propertyManager);

        assertTrue(config.isEnabled());
        assertEquals("https://dynamic.atlas.local", config.getEndpoint());
        assertEquals("dynamic-user", config.getUsername());
        assertEquals("dynamic-pass", config.getPassword());
        assertEquals(7000, config.getConnectTimeoutMs());
        assertEquals(45000, config.getReadTimeoutMs());
        assertEquals("0 */5 * * * ?", config.getSyncCron());
        assertEquals("CustomCollection", config.getCollection());
    }

    @Test
    public void testFallsBackToStartupDefaultsWhenPropertyManagerReturnsNull() throws Exception {
        AtlasConfig config = new AtlasConfig();
        setField(config, "enabled", true);
        setField(config, "endpoint", "https://startup.atlas.local");
        setField(config, "username", "startup-user");
        setField(config, "password", "startup-pass");
        setField(config, "connectTimeoutMs", 3000);
        setField(config, "readTimeoutMs", 15000);
        setField(config, "syncCron", "0 0 * * * ?");
        setField(config, "collection", "StartupCollection");

        PropertyManager propertyManager = mock(PropertyManager.class);
        // All return null → fall back to startup defaults
        setField(config, "propertyManager", propertyManager);

        assertTrue(config.isEnabled());
        assertEquals("https://startup.atlas.local", config.getEndpoint());
        assertEquals("startup-user", config.getUsername());
        assertEquals("startup-pass", config.getPassword());
        assertEquals(3000, config.getConnectTimeoutMs());
        assertEquals(15000, config.getReadTimeoutMs());
        assertEquals("0 0 * * * ?", config.getSyncCron());
        assertEquals("StartupCollection", config.getCollection());
    }

    @Test
    public void testCollectionDefaultsToNemakiWareWhenEmpty() throws Exception {
        AtlasConfig config = new AtlasConfig();
        setField(config, "enabled", false);
        setField(config, "endpoint", "");
        setField(config, "username", "");
        setField(config, "password", "");
        setField(config, "connectTimeoutMs", 5000);
        setField(config, "readTimeoutMs", 30000);
        setField(config, "syncCron", "");
        setField(config, "collection", "");

        assertEquals("NemakiWare", config.getCollection());
    }

    @Test
    public void testWorksWithoutPropertyManager() throws Exception {
        AtlasConfig config = new AtlasConfig();
        setField(config, "enabled", true);
        setField(config, "endpoint", "https://no-pm.atlas.local");
        setField(config, "username", "user");
        setField(config, "password", "pass");
        setField(config, "connectTimeoutMs", 5000);
        setField(config, "readTimeoutMs", 30000);
        setField(config, "syncCron", "");
        setField(config, "collection", "NemakiWare");
        // propertyManager is null by default

        assertTrue(config.isEnabled());
        assertEquals("https://no-pm.atlas.local", config.getEndpoint());
        assertEquals("user", config.getUsername());
        assertEquals("pass", config.getPassword());
    }

    @Test
    public void testInvalidIntFallsBackToDefault() throws Exception {
        AtlasConfig config = new AtlasConfig();
        setField(config, "enabled", false);
        setField(config, "endpoint", "");
        setField(config, "username", "");
        setField(config, "password", "");
        setField(config, "connectTimeoutMs", 5000);
        setField(config, "readTimeoutMs", 30000);
        setField(config, "syncCron", "");
        setField(config, "collection", "NemakiWare");

        PropertyManager propertyManager = mock(PropertyManager.class);
        when(propertyManager.readValue("atlas.connect.timeout.ms")).thenReturn("not-a-number");
        when(propertyManager.readValue("atlas.read.timeout.ms")).thenReturn("");
        setField(config, "propertyManager", propertyManager);

        assertEquals(5000, config.getConnectTimeoutMs());
        assertEquals(30000, config.getReadTimeoutMs());
    }

    @Test
    public void testEnabledParsing() throws Exception {
        AtlasConfig config = new AtlasConfig();
        setField(config, "enabled", false);
        setField(config, "endpoint", "");
        setField(config, "username", "");
        setField(config, "password", "");
        setField(config, "connectTimeoutMs", 5000);
        setField(config, "readTimeoutMs", 30000);
        setField(config, "syncCron", "");
        setField(config, "collection", "NemakiWare");

        assertFalse(config.isEnabled());

        PropertyManager propertyManager = mock(PropertyManager.class);
        when(propertyManager.readValue("atlas.enabled")).thenReturn("false");
        setField(config, "propertyManager", propertyManager);

        assertFalse(config.isEnabled());
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
