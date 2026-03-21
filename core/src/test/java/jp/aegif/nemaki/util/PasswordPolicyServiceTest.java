package jp.aegif.nemaki.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.util.spring.SpringPropertiesUtil;

/**
 * Tests for PasswordPolicyService.
 *
 * Uses hand-written stubs to avoid Spring context dependency.
 */
public class PasswordPolicyServiceTest {

    private StubContentDaoService stubDao;
    private StubSpringPropertiesUtil stubProps;
    private PasswordPolicyService service;

    // Stub: ContentDaoService
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

    // Stub: SpringPropertiesUtil
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

    @BeforeEach
    void setUp() {
        stubDao = new StubContentDaoService();
        stubProps = new StubSpringPropertiesUtil();

        PropertyManager pm = new PropertyManager();
        pm.setPropertyConfigurer(stubProps);
        pm.setContentDaoService(stubDao);

        service = new PasswordPolicyService();
        service.setPropertyManager(pm);
    }

    // --- getMinLength ---

    @Test
    void defaultMinLengthIsZero() {
        assertEquals(0, service.getMinLength());
        assertEquals(0, service.getMinLength("bedroom"));
    }

    @Test
    void readsMinLengthFromGlobalConfig() {
        Configuration conf = new Configuration();
        Map<String, Object> map = new HashMap<>();
        map.put("password.policy.minLength", "12");
        conf.setConfiguration(map);
        stubDao.putConfig("nemaki_conf", conf);

        assertEquals(12, service.getMinLength());
    }

    @Test
    void readsMinLengthFromRepoConfig() {
        Configuration repoConf = new Configuration();
        Map<String, Object> map = new HashMap<>();
        map.put("password.policy.minLength", "10");
        repoConf.setConfiguration(map);
        stubDao.putConfig("bedroom", repoConf);

        assertEquals(10, service.getMinLength("bedroom"));
    }

    @Test
    void repoConfigOverridesGlobal() {
        Configuration globalConf = new Configuration();
        Map<String, Object> globalMap = new HashMap<>();
        globalMap.put("password.policy.minLength", "8");
        globalConf.setConfiguration(globalMap);
        stubDao.putConfig("nemaki_conf", globalConf);

        Configuration repoConf = new Configuration();
        Map<String, Object> repoMap = new HashMap<>();
        repoMap.put("password.policy.minLength", "12");
        repoConf.setConfiguration(repoMap);
        stubDao.putConfig("bedroom", repoConf);

        assertEquals(12, service.getMinLength("bedroom"));
    }

    @Test
    void invalidValueFallsBackToDefault() {
        Configuration conf = new Configuration();
        Map<String, Object> map = new HashMap<>();
        map.put("password.policy.minLength", "not-a-number");
        conf.setConfiguration(map);
        stubDao.putConfig("nemaki_conf", conf);

        assertEquals(0, service.getMinLength());
    }

    // --- validate ---

    @Test
    void validateRejectsNullPassword() {
        var result = service.validate(null, "bedroom");
        assertFalse(result.isOk());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    void validateRejectsEmptyPassword() {
        var result = service.validate("", "bedroom");
        assertFalse(result.isOk());
    }

    @Test
    void validateAcceptsAnyNonEmptyPasswordWhenMinLengthIsZero() {
        // Default minLength = 0
        var result = service.validate("a", "bedroom");
        assertTrue(result.isOk());
    }

    @Test
    void validateRejectsShortPasswordWhenMinLengthSet() {
        Configuration conf = new Configuration();
        Map<String, Object> map = new HashMap<>();
        map.put("password.policy.minLength", "8");
        conf.setConfiguration(map);
        stubDao.putConfig("bedroom", conf);

        var result = service.validate("short", "bedroom");
        assertFalse(result.isOk());
        assertTrue(result.getErrorMessage().contains("8"));
    }

    @Test
    void validateAcceptsLongEnoughPassword() {
        Configuration conf = new Configuration();
        Map<String, Object> map = new HashMap<>();
        map.put("password.policy.minLength", "8");
        conf.setConfiguration(map);
        stubDao.putConfig("bedroom", conf);

        var result = service.validate("longpassword", "bedroom");
        assertTrue(result.isOk());
    }

    @Test
    void validateAcceptsExactMinLengthPassword() {
        Configuration conf = new Configuration();
        Map<String, Object> map = new HashMap<>();
        map.put("password.policy.minLength", "5");
        conf.setConfiguration(map);
        stubDao.putConfig("bedroom", conf);

        var result = service.validate("12345", "bedroom");
        assertTrue(result.isOk());
    }

    @Test
    void validateWithNullRepositoryIdUsesGlobalPolicy() {
        Configuration conf = new Configuration();
        Map<String, Object> map = new HashMap<>();
        map.put("password.policy.minLength", "10");
        conf.setConfiguration(map);
        stubDao.putConfig("nemaki_conf", conf);

        var result = service.validate("short", null);
        assertFalse(result.isOk());
    }

    @Test
    void adminPasswordPassesWhenNoConstraint() {
        // Default setup: minLength = 0
        var result = service.validate("admin", null);
        assertTrue(result.isOk());
    }
}
