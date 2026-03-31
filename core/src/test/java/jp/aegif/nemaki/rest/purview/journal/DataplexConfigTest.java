package jp.aegif.nemaki.rest.purview.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.util.PropertyManager;

public class DataplexConfigTest {

    @Test
    public void testReadsDynamicValuesFromPropertyManager() throws Exception {
        DataplexConfig config = new DataplexConfig();
        setField(config, "enabled", false);
        setField(config, "projectId", "fallback-project");
        setField(config, "location", "us-central1");
        setField(config, "credentialsFile", "/fallback/creds.json");

        PropertyManager propertyManager = mock(PropertyManager.class);
        when(propertyManager.readValue("dataplex.enabled")).thenReturn("true");
        when(propertyManager.readValue("dataplex.project-id")).thenReturn("dynamic-project");
        when(propertyManager.readValue("dataplex.location")).thenReturn("asia-northeast1");
        when(propertyManager.readValue("dataplex.credentials-file")).thenReturn("/dynamic/creds.json");
        setField(config, "propertyManager", propertyManager);

        assertTrue(config.isEnabled());
        assertEquals("dynamic-project", config.getProjectId());
        assertEquals("asia-northeast1", config.getLocation());
        assertEquals("/dynamic/creds.json", config.getCredentialsFile());
    }

    @Test
    public void testFallsBackToStartupDefaultsWhenPropertyManagerReturnsNull() throws Exception {
        DataplexConfig config = new DataplexConfig();
        setField(config, "enabled", true);
        setField(config, "projectId", "startup-project");
        setField(config, "location", "europe-west1");
        setField(config, "credentialsFile", "/startup/creds.json");

        PropertyManager propertyManager = mock(PropertyManager.class);
        // All return null → fall back to startup defaults
        setField(config, "propertyManager", propertyManager);

        assertTrue(config.isEnabled());
        assertEquals("startup-project", config.getProjectId());
        assertEquals("europe-west1", config.getLocation());
        assertEquals("/startup/creds.json", config.getCredentialsFile());
    }

    @Test
    public void testWorksWithoutPropertyManager() throws Exception {
        DataplexConfig config = new DataplexConfig();
        setField(config, "enabled", true);
        setField(config, "projectId", "no-pm-project");
        setField(config, "location", "us-east1");
        setField(config, "credentialsFile", "");
        // propertyManager is null by default

        assertTrue(config.isEnabled());
        assertEquals("no-pm-project", config.getProjectId());
        assertEquals("us-east1", config.getLocation());
        assertEquals("", config.getCredentialsFile());
    }

    @Test
    public void testEnabledParsing() throws Exception {
        DataplexConfig config = new DataplexConfig();
        setField(config, "enabled", false);
        setField(config, "projectId", "");
        setField(config, "location", "");
        setField(config, "credentialsFile", "");

        assertFalse(config.isEnabled());

        PropertyManager propertyManager = mock(PropertyManager.class);
        when(propertyManager.readValue("dataplex.enabled")).thenReturn("false");
        setField(config, "propertyManager", propertyManager);

        assertFalse(config.isEnabled());
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
