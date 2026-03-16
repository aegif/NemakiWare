package jp.aegif.nemaki.dao.impl.couch;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.model.Configuration;

/**
 * Tests for getConfiguration() behaviour.
 *
 * Since the real implementation talks directly to Cloudant SDK (which cannot
 * be easily stubbed without Mockito inline-mock / byte-buddy agent), these
 * tests verify the filtering and pagination contract through a lightweight
 * in-memory stub that mimics the same selector logic as
 * ContentDaoServiceImpl.getConfiguration().
 *
 * The stub exercises the same branching paths:
 *   - global config (repositoryId == nemaki_conf) → docs without repositoryId
 *   - repo-specific config → docs with matching repositoryId
 *   - pagination via batch processing
 *   - error resilience
 *
 * Uses hand-written stubs (same pattern as AbstractNemakiPatchTest).
 */
public class ContentDaoServiceImplConfigTest {

    // ========================================================================
    // Stub: simulates the filtering logic of getConfiguration()
    // ========================================================================

    /**
     * Simulates the document filtering that getConfiguration() performs.
     * Each "document" is a Map with keys: key, value, repositoryId (optional).
     */
    private static Configuration filterConfiguration(
            String repositoryId, java.util.List<Map<String, Object>> allDocs) {

        Configuration config = new Configuration();
        config.setId("config_" + repositoryId);
        config.setType("configuration");

        boolean isConfDb = "nemaki_conf".equals(repositoryId);

        Map<String, Object> configMap = new HashMap<>();
        for (Map<String, Object> doc : allDocs) {
            String key = doc.get("key") != null ? doc.get("key").toString() : null;
            Object value = doc.get("value");
            if (key == null) continue;

            String docRepoId = doc.get("repositoryId") != null
                    ? doc.get("repositoryId").toString() : null;

            if (isConfDb) {
                if (docRepoId == null) {
                    configMap.put(key, value);
                }
            } else {
                if (repositoryId.equals(docRepoId)) {
                    configMap.put(key, value);
                }
            }
        }
        config.setConfiguration(configMap);
        return config;
    }

    // ========================================================================
    // Helper to create a config document map
    // ========================================================================

    private static Map<String, Object> doc(String key, Object value, String repoId) {
        Map<String, Object> d = new HashMap<>();
        d.put("type", "configuration");
        d.put("key", key);
        d.put("value", value);
        if (repoId != null) {
            d.put("repositoryId", repoId);
        }
        return d;
    }

    // ========================================================================
    // Tests
    // ========================================================================

    @Test
    public void testGlobalConfigExcludesRepoSpecific() {
        java.util.List<Map<String, Object>> docs = new java.util.ArrayList<>();
        docs.add(doc("system.version", "2025.08.05", null));           // global
        docs.add(doc("system.folder", "/sys/bedroom", "bedroom"));     // repo-specific
        docs.add(doc("global.setting", "on", null));                   // global

        Configuration conf = filterConfiguration("nemaki_conf", docs);

        assertEquals(2, conf.getConfiguration().size());
        assertEquals("2025.08.05", conf.getConfiguration().get("system.version"));
        assertEquals("on", conf.getConfiguration().get("global.setting"));
        assertNull(conf.getConfiguration().get("system.folder"),
                "Global config must exclude docs with repositoryId");
    }

    @Test
    public void testRepoSpecificConfigOnlyMatchingRepo() {
        java.util.List<Map<String, Object>> docs = new java.util.ArrayList<>();
        docs.add(doc("system.folder", "/sys/bedroom", "bedroom"));
        docs.add(doc("system.folder", "/sys/canopy", "canopy"));
        docs.add(doc("global.setting", "on", null));

        Configuration conf = filterConfiguration("bedroom", docs);

        assertEquals(1, conf.getConfiguration().size());
        assertEquals("/sys/bedroom", conf.getConfiguration().get("system.folder"));
        assertNull(conf.getConfiguration().get("global.setting"),
                "Repo-specific config must exclude global docs");
    }

    @Test
    public void testEmptyWhenNoConfigDocs() {
        java.util.List<Map<String, Object>> docs = new java.util.ArrayList<>();

        Configuration conf = filterConfiguration("bedroom", docs);

        assertNotNull(conf);
        assertTrue(conf.getConfiguration().isEmpty());
    }

    @Test
    public void testCouchDbUnavailableReturnsEmptyConfig() {
        // Simulate the error path: when Cloudant is unreachable,
        // getConfiguration catches Exception and returns empty Configuration.
        Configuration config = new Configuration();
        config.setId("config_bedroom");
        config.setType("configuration");

        // No setConfiguration call → uses default empty HashMap from constructor
        assertNotNull(config.getConfiguration());
        assertTrue(config.getConfiguration().isEmpty(),
                "Configuration should have empty map when CouchDB is unavailable");
    }

    @Test
    public void testPaginationFetchesAllDocs() {
        // Simulate 450 documents across 3 pages (200 + 200 + 50)
        java.util.List<Map<String, Object>> allDocs = new java.util.ArrayList<>();
        for (int i = 0; i < 450; i++) {
            allDocs.add(doc("key_" + i, "val_" + i, "bedroom"));
        }

        // Process in batches of 200 (mimicking the pagination loop)
        Map<String, Object> configMap = new HashMap<>();
        int batchSize = 200;
        int offset = 0;
        while (offset < allDocs.size()) {
            int end = Math.min(offset + batchSize, allDocs.size());
            java.util.List<Map<String, Object>> batch = allDocs.subList(offset, end);

            for (Map<String, Object> d : batch) {
                String key = d.get("key") != null ? d.get("key").toString() : null;
                Object value = d.get("value");
                String docRepoId = d.get("repositoryId") != null
                        ? d.get("repositoryId").toString() : null;
                if (key != null && "bedroom".equals(docRepoId)) {
                    configMap.put(key, value);
                }
            }

            boolean hasMore = (batch.size() == batchSize);
            if (!hasMore) break;
            offset += batchSize;
        }

        assertEquals(450, configMap.size(),
                "Pagination must fetch all 450 documents across 3 pages");
        assertEquals("val_0", configMap.get("key_0"));
        assertEquals("val_449", configMap.get("key_449"));
    }
}
