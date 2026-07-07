package jp.aegif.nemaki.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.util.spring.SpringPropertiesUtil;

/**
 * Tests for PropertyManager.readValue(repositoryId, key) priority chain.
 *
 * Priority order (highest → lowest):
 *   1. System property  (JVM -D flags, Jetty environment overrides)
 *   2. Environment variable (Docker/container support)
 *   3. Repo-specific dynamic value (CouchDB Configuration for the repository)
 *   4. Global dynamic value (CouchDB Configuration for nemaki_conf)
 *   5. Properties file value (SpringPropertiesUtil)
 *
 * Uses hand-written stubs (same pattern as AbstractNemakiPatchTest).
 */
public class PropertyManagerConfigTest {

    // Unique key prefix to avoid collisions with real system properties
    private static final String TEST_KEY_PREFIX = "nemaki.test.propManagerTest.";

    // ========================================================================
    // Stub: ContentDaoService that returns configurable Configuration objects
    // ========================================================================
    private static class StubContentDaoService extends StubContentDaoServiceBase {
        private final Map<String, Configuration> configs = new HashMap<>();

        void putConfig(String repositoryId, Configuration config) {
            configs.put(repositoryId, config);
        }

        @Override
        public Configuration getConfiguration(String repositoryId) {
            Configuration c = configs.get(repositoryId);
            return c != null ? c : new Configuration();
        }
    }

    // ========================================================================
    // Stub: SpringPropertiesUtil returning configurable values
    // ========================================================================
    private static class StubSpringPropertiesUtil extends SpringPropertiesUtil {
        private final Map<String, String> values = new HashMap<>();

        void putValue(String key, String value) {
            values.put(key, value);
        }

        @Override
        public String getValue(String key) {
            return values.get(key);
        }
    }

    // ========================================================================
    // Setup / Teardown
    // ========================================================================

    private PropertyManager pm;
    private StubContentDaoService stubDao;
    private StubSpringPropertiesUtil stubProps;

    @BeforeEach
    void setUp() {
        pm = new PropertyManager();
        stubDao = new StubContentDaoService();
        stubProps = new StubSpringPropertiesUtil();
        pm.setContentDaoService(stubDao);
        pm.setPropertyConfigurer(stubProps);
    }

    @AfterEach
    void tearDown() {
        // Clean up any system properties we set (test-prefix + the admin-managed
        // cloud.* keys exercised by the precedence tests below).
        System.getProperties().entrySet().removeIf(e -> {
            String k = e.getKey().toString();
            return k.startsWith(TEST_KEY_PREFIX)
                    || k.startsWith("cloud.auth.") || k.startsWith("cloud.drive.");
        });
    }

    // ========================================================================
    // Helper
    // ========================================================================

    private static Configuration configWith(String key, Object value) {
        Configuration c = new Configuration();
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        c.setConfiguration(map);
        return c;
    }

    // ========================================================================
    // Tests
    // ========================================================================

    @Test
    public void testRepoSpecificOverridesGlobal() {
        String key = "system.folder";

        // Global dynamic config (nemaki_conf)
        stubDao.putConfig("nemaki_conf", configWith(key, "/global/system"));

        // Repo-specific config
        stubDao.putConfig("bedroom", configWith(key, "/bedroom/system"));

        // Properties file fallback
        stubProps.putValue(key, "/props/system");

        String result = pm.readValue("bedroom", key);
        assertEquals("/bedroom/system", result,
                "Repo-specific dynamic value should override global dynamic value");
    }

    @Test
    public void testFallsBackToGlobalDynamic() {
        String key = "some.global.key";

        // Only global dynamic config
        stubDao.putConfig("nemaki_conf", configWith(key, "global-val"));

        // Properties file fallback
        stubProps.putValue(key, "props-val");

        String result = pm.readValue("bedroom", key);
        assertEquals("global-val", result,
                "Should fall back to global dynamic value when repo-specific is absent");
    }

    @Test
    public void testFallsBackToProperties() {
        String key = "only.in.properties";

        // No dynamic config at all
        stubDao.putConfig("nemaki_conf", new Configuration());
        stubDao.putConfig("bedroom", new Configuration());

        stubProps.putValue(key, "from-properties");

        String result = pm.readValue("bedroom", key);
        assertEquals("from-properties", result,
                "Should fall back to properties file when no dynamic config exists");
    }

    @Test
    public void testSystemPropertyOverridesGlobalAndProps() {
        String key = TEST_KEY_PREFIX + "sysprop";

        // Set global, properties, and system property — but NOT repo-specific
        stubDao.putConfig("nemaki_conf", configWith(key, "global-val"));
        stubProps.putValue(key, "props-val");
        System.setProperty(key, "system-val");

        // readValue("bedroom", key): no repo-specific → falls back to readValue(key)
        // readValue(key): system property wins over global dynamic and props
        String result = pm.readValue("bedroom", key);
        assertEquals("system-val", result,
                "System property should override global dynamic and properties");
    }

    @Test
    public void testSystemPropertyOverridesRepoSpecific() {
        String key = TEST_KEY_PREFIX + "sysprop2";

        // Repo-specific + system property both set
        stubDao.putConfig("bedroom", configWith(key, "repo-val"));
        System.setProperty(key, "system-val");

        // readValue("bedroom", key): system property is checked FIRST (Priority 1)
        String result = pm.readValue("bedroom", key);
        assertEquals("system-val", result,
                "System property should override repo-specific dynamic value (JVM override)");
    }

    @Test
    public void testSystemFolderKeyWorks() {
        String key = "system.folder";

        stubDao.putConfig("bedroom", configWith(key, "abc123-folder-id"));

        String result = pm.readValue("bedroom", key);
        assertEquals("abc123-folder-id", result,
                "key='system.folder' should be retrievable as repo-specific value");
    }

    // ========================================================================
    // Admin-managed integration keys (3.2.1): nemaki_conf (admin UI) takes
    // precedence over deploy-time -D/env for cloud.auth.* / cloud.drive.*, so a
    // Google/Microsoft client ID set from the admin menu takes effect and
    // persists without editing config files. A blank stored value falls through
    // to the deploy bootstrap.
    // ========================================================================

    @Test
    public void testIsAdminManagedDynamicKey() {
        assertTrue(PropertyManager.isAdminManagedDynamicKey("cloud.auth.google.clientId"));
        assertTrue(PropertyManager.isAdminManagedDynamicKey("cloud.auth.microsoft.tenantId"));
        assertTrue(PropertyManager.isAdminManagedDynamicKey("cloud.drive.microsoft.enabled"));
        // SSO / OIDC (Keycloak) / SAML are admin-managed too, so an operator can
        // configure them from the admin menu after the setup wizard has written
        // them as -D system properties.
        assertTrue(PropertyManager.isAdminManagedDynamicKey("sso.oidc.enabled"));
        assertTrue(PropertyManager.isAdminManagedDynamicKey("sso.saml.enabled"));
        assertTrue(PropertyManager.isAdminManagedDynamicKey("oidc.issuer"));
        assertTrue(PropertyManager.isAdminManagedDynamicKey("oidc.clientId"));
        assertTrue(PropertyManager.isAdminManagedDynamicKey("saml.idp.sso.url"));
        assertFalse(PropertyManager.isAdminManagedDynamicKey("db.couchdb.auth.password"));
        assertFalse(PropertyManager.isAdminManagedDynamicKey(null));
    }

    @Test
    public void testAdminManagedKey_couchdbOverridesSystemProperty() {
        String key = "cloud.auth.google.clientId";
        stubDao.putConfig("nemaki_conf", configWith(key, "ui-set-id"));
        System.setProperty(key, "deploy-D-id");

        assertEquals("ui-set-id", pm.readValue(key),
                "admin-managed key: nemaki_conf (admin UI) overrides the -D system property");
        assertEquals("ui-set-id", pm.readValue("bedroom", key),
                "admin-managed key (repo overload): nemaki_conf overrides the -D system property");
    }

    @Test
    public void testAdminManagedKey_blankCouchdbFallsThroughToSystemProperty() {
        String key = "cloud.auth.google.clientId";
        stubDao.putConfig("nemaki_conf", configWith(key, ""));   // blank = not set
        System.setProperty(key, "deploy-D-id");

        assertEquals("deploy-D-id", pm.readValue(key),
                "blank nemaki_conf value falls through to the deploy bootstrap");
        assertEquals("deploy-D-id", pm.readValue("bedroom", key));
    }

    @Test
    public void testAdminManagedKey_absentCouchdbFallsThroughToSystemProperty() {
        String key = "cloud.auth.microsoft.enabled";
        System.setProperty(key, "true");   // no nemaki_conf value

        assertEquals("true", pm.readValue(key));
        assertEquals("true", pm.readValue("bedroom", key));
    }

    @Test
    public void testAdminManagedKey_repoSpecificCouchdbWins() {
        String key = "cloud.auth.google.clientId";
        stubDao.putConfig("bedroom", configWith(key, "repo-ui-id"));
        stubDao.putConfig("nemaki_conf", configWith(key, "global-ui-id"));
        System.setProperty(key, "deploy-D-id");

        assertEquals("repo-ui-id", pm.readValue("bedroom", key),
                "repo-specific nemaki_conf wins over global nemaki_conf and the -D");
    }

    @Test
    public void testNonAdminManagedKey_systemPropertyStillWins() {
        // Regression: a non-cloud key keeps the historical "system property first"
        // precedence — the admin-managed override is scoped to cloud.* only.
        String key = TEST_KEY_PREFIX + "regular";
        stubDao.putConfig("nemaki_conf", configWith(key, "couchdb-val"));
        System.setProperty(key, "sysprop-val");

        assertEquals("sysprop-val", pm.readValue(key),
                "non-admin-managed key: system property still takes precedence");
    }
}
