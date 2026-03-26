package jp.aegif.nemaki.rest.purview.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.util.StubContentDaoServiceBase;
import jp.aegif.nemaki.util.constant.SystemConst;

public class PurviewDeadLetterStateServiceImplTest {

    private StubContentDaoService contentDaoService;
    private PurviewStateStoreImpl stateStore;
    private PurviewDeadLetterStateServiceImpl service;

    @BeforeEach
    public void setUp() {
        contentDaoService = new StubContentDaoService();
        stateStore = new PurviewStateStoreImpl(contentDaoService);
        service = new PurviewDeadLetterStateServiceImpl(stateStore);
    }

    // --- Save & Load (consolidated format) ---

    @Test
    public void testSaveAndLoadDeadLetterState() {
        PurviewDeadLetterState deadLetterState = new PurviewDeadLetterState(
                "bedroom",
                "content-change-log",
                "doc.001",
                "nemaki_document",
                "nemaki://bedroom/objects/doc.001",
                "2026-03-20T10:00:00Z",
                "2026-03-20T10:00:05Z",
                2,
                "token-101",
                "publish failed");

        service.saveDeadLetterState(deadLetterState);
        PurviewDeadLetterState loaded = service.getDeadLetterState("bedroom", "content-change-log", "doc.001");

        assertNotNull(loaded);
        assertEquals("doc.001", loaded.getEntryKey());
        assertEquals("nemaki_document", loaded.getTypeName());
        assertEquals("nemaki://bedroom/objects/doc.001", loaded.getQualifiedName());
        assertEquals("2026-03-20T10:00:00Z", loaded.getFirstFailedAt());
        assertEquals("2026-03-20T10:00:05Z", loaded.getLastFailedAt());
        assertEquals(2, loaded.getFailureCount());
        assertEquals("token-101", loaded.getCheckpoint());
        assertEquals("publish failed", loaded.getErrorSummary());
    }

    @Test
    public void testSaveDeadLetterState_overwritesExisting() {
        service.saveDeadLetterState(new PurviewDeadLetterState(
                "bedroom", "content-change-log", "doc-001",
                "nemaki_document", "nemaki://bedroom/objects/doc-001",
                "2026-03-20T10:00:00Z", "2026-03-20T10:00:05Z",
                1, "token-101", "first failure"));
        service.saveDeadLetterState(new PurviewDeadLetterState(
                "bedroom", "content-change-log", "doc-001",
                "nemaki_document", "nemaki://bedroom/objects/doc-001",
                "2026-03-20T10:00:00Z", "2026-03-20T11:00:00Z",
                5, "token-105", "fifth failure"));

        PurviewDeadLetterState loaded = service.getDeadLetterState("bedroom", "content-change-log", "doc-001");
        assertEquals(5, loaded.getFailureCount());
        assertEquals("2026-03-20T11:00:00Z", loaded.getLastFailedAt());
        assertEquals("fifth failure", loaded.getErrorSummary());
    }

    @Test
    public void testGetDeadLetterState_nonExistent_returnsNull() {
        PurviewDeadLetterState loaded = service.getDeadLetterState("bedroom", "content-change-log", "nonexistent");
        assertNull(loaded);
    }

    // --- Legacy fallback reading ---

    @Test
    public void testGetDeadLetterState_legacyFormat_fallbackRead() {
        // Manually write legacy 10-document format directly into the state store
        String legacyPrefix = "purview.dead-letter.state.bedroom.content-change-log.ZG9jLTk5OQ";
        // ZG9jLTk5OQ is Base64url for "doc-999"
        Map<String, Object> legacyEntries = new HashMap<>();
        legacyEntries.put(legacyPrefix + ".repositoryId", "bedroom");
        legacyEntries.put(legacyPrefix + ".streamKind", "content-change-log");
        legacyEntries.put(legacyPrefix + ".entryKey", "doc-999");
        legacyEntries.put(legacyPrefix + ".typeName", "nemaki_folder");
        legacyEntries.put(legacyPrefix + ".qualifiedName", "nemaki://bedroom/objects/doc-999");
        legacyEntries.put(legacyPrefix + ".firstFailedAt", "2026-01-01T00:00:00Z");
        legacyEntries.put(legacyPrefix + ".lastFailedAt", "2026-01-01T01:00:00Z");
        legacyEntries.put(legacyPrefix + ".failureCount", 3);
        legacyEntries.put(legacyPrefix + ".checkpoint", "token-999");
        legacyEntries.put(legacyPrefix + ".errorSummary", "legacy error");
        stateStore.putAll(legacyEntries);

        // Reading should fall back to legacy format
        PurviewDeadLetterState loaded = service.getDeadLetterState("bedroom", "content-change-log", "doc-999");
        assertNotNull(loaded);
        assertEquals("doc-999", loaded.getEntryKey());
        assertEquals("nemaki_folder", loaded.getTypeName());
        assertEquals(3, loaded.getFailureCount());
        assertEquals("legacy error", loaded.getErrorSummary());
    }

    @Test
    public void testSaveDeadLetterState_cleansUpLegacyKeys() {
        // Write legacy format first
        String legacyPrefix = "purview.dead-letter.state.bedroom.content-change-log.ZG9jLTAwMQ";
        // ZG9jLTAwMQ is Base64url for "doc-001"
        Map<String, Object> legacyEntries = new HashMap<>();
        legacyEntries.put(legacyPrefix + ".repositoryId", "bedroom");
        legacyEntries.put(legacyPrefix + ".streamKind", "content-change-log");
        legacyEntries.put(legacyPrefix + ".entryKey", "doc-001");
        legacyEntries.put(legacyPrefix + ".typeName", "nemaki_document");
        legacyEntries.put(legacyPrefix + ".qualifiedName", "old-qn");
        legacyEntries.put(legacyPrefix + ".firstFailedAt", "2026-01-01T00:00:00Z");
        legacyEntries.put(legacyPrefix + ".lastFailedAt", "2026-01-01T01:00:00Z");
        legacyEntries.put(legacyPrefix + ".failureCount", 1);
        legacyEntries.put(legacyPrefix + ".checkpoint", "token-old");
        legacyEntries.put(legacyPrefix + ".errorSummary", "old error");
        stateStore.putAll(legacyEntries);

        // Now save via new consolidated format — should clean up legacy
        service.saveDeadLetterState(new PurviewDeadLetterState(
                "bedroom", "content-change-log", "doc-001",
                "nemaki_document", "new-qn",
                "2026-03-20T10:00:00Z", "2026-03-20T10:00:05Z",
                2, "token-new", "new error"));

        // Verify legacy keys are gone
        Map<String, Object> allEntries = stateStore.getAll();
        for (String key : allEntries.keySet()) {
            if (key.startsWith("purview.dead-letter.state.bedroom.content-change-log.ZG9jLTAwMQ")) {
                assertTrue(false, "Legacy key should have been cleaned up: " + key);
            }
        }

        // Verify consolidated format is used
        PurviewDeadLetterState loaded = service.getDeadLetterState("bedroom", "content-change-log", "doc-001");
        assertNotNull(loaded);
        assertEquals("new-qn", loaded.getQualifiedName());
        assertEquals(2, loaded.getFailureCount());
    }

    // --- Delete ---

    @Test
    public void testDeleteDeadLetterStateClearsPersistedKeys() {
        service.saveDeadLetterState(new PurviewDeadLetterState(
                "bedroom",
                "content-change-log",
                "doc-001",
                "nemaki_document",
                "nemaki://bedroom/objects/doc-001",
                "2026-03-20T10:00:00Z",
                "2026-03-20T10:00:05Z",
                1,
                "token-101",
                "publish failed"));

        service.deleteDeadLetterState("bedroom", "content-change-log", "doc-001");

        PurviewDeadLetterState loaded = service.getDeadLetterState("bedroom", "content-change-log", "doc-001");
        assertNull(loaded);
    }

    @Test
    public void testDeleteDeadLetterState_cleansBothFormats() {
        // Write legacy format manually
        String legacyPrefix = "purview.dead-letter.state.bedroom.content-change-log.ZG9jLTAwMg";
        // ZG9jLTAwMg is Base64url for "doc-002"
        Map<String, Object> legacyEntries = new HashMap<>();
        legacyEntries.put(legacyPrefix + ".entryKey", "doc-002");
        legacyEntries.put(legacyPrefix + ".typeName", "nemaki_document");
        legacyEntries.put(legacyPrefix + ".qualifiedName", "old-qn");
        legacyEntries.put(legacyPrefix + ".firstFailedAt", "2026-01-01T00:00:00Z");
        legacyEntries.put(legacyPrefix + ".lastFailedAt", "2026-01-01T01:00:00Z");
        legacyEntries.put(legacyPrefix + ".failureCount", 1);
        legacyEntries.put(legacyPrefix + ".checkpoint", "token-old");
        legacyEntries.put(legacyPrefix + ".errorSummary", "old error");
        legacyEntries.put(legacyPrefix + ".repositoryId", "bedroom");
        legacyEntries.put(legacyPrefix + ".streamKind", "content-change-log");
        stateStore.putAll(legacyEntries);

        // Also save in consolidated format
        service.saveDeadLetterState(new PurviewDeadLetterState(
                "bedroom", "content-change-log", "doc-002",
                "nemaki_document", "new-qn",
                "2026-03-20T10:00:00Z", "2026-03-20T10:00:05Z",
                2, "token-new", "new error"));

        // Delete should clean both formats
        service.deleteDeadLetterState("bedroom", "content-change-log", "doc-002");

        assertNull(service.getDeadLetterState("bedroom", "content-change-log", "doc-002"));

        // Verify no keys remain
        Map<String, Object> allEntries = stateStore.getAll();
        for (String key : allEntries.keySet()) {
            if (key.contains("doc-002") || key.contains("ZG9jLTAwMg")) {
                assertTrue(false, "Key should have been deleted: " + key);
            }
        }
    }

    // --- List ---

    @Test
    public void testListAndCountDeadLetterStatesFiltersByRepositoryAndStream() {
        service.saveDeadLetterState(new PurviewDeadLetterState(
                "bedroom",
                "content-change-log",
                "doc-002",
                "nemaki_document",
                "nemaki://bedroom/objects/doc-002",
                "2026-03-20T10:00:02Z",
                "2026-03-20T10:00:03Z",
                2,
                "token-102",
                "publish failed"));
        service.saveDeadLetterState(new PurviewDeadLetterState(
                "bedroom",
                "content-change-log",
                "doc-001",
                "nemaki_document",
                "nemaki://bedroom/objects/doc-001",
                "2026-03-20T10:00:00Z",
                "2026-03-20T10:00:01Z",
                1,
                "token-101",
                "publish failed"));
        service.saveDeadLetterState(new PurviewDeadLetterState(
                "bedroom",
                "archive-snapshot",
                "doc-003",
                "nemaki_archive",
                "nemaki://bedroom/archives/doc-003",
                "2026-03-20T10:00:04Z",
                "2026-03-20T10:00:05Z",
                1,
                "archive-1",
                "archive failed"));
        service.saveDeadLetterState(new PurviewDeadLetterState(
                "kitchen",
                "content-change-log",
                "doc-004",
                "nemaki_document",
                "nemaki://kitchen/objects/doc-004",
                "2026-03-20T10:00:06Z",
                "2026-03-20T10:00:07Z",
                1,
                "token-104",
                "publish failed"));

        // Filter by repositoryId + streamKind
        List<PurviewDeadLetterState> bedroomCCL = service.listDeadLetterStates("bedroom", "content-change-log");
        assertEquals(List.of("doc-001", "doc-002"),
                bedroomCCL.stream().map(PurviewDeadLetterState::getEntryKey).toList());
        assertEquals(2, service.countDeadLetterStates("bedroom", "content-change-log"));

        // Filter by repositoryId only
        assertEquals(3, service.listDeadLetterStates("bedroom").size());

        // All entries
        assertEquals(4, service.listDeadLetterStates().size());

        // Non-existent filter
        assertTrue(service.listDeadLetterStates("missing", "content-change-log").isEmpty());
    }

    @Test
    public void testListDeadLetterStates_sortOrder() {
        service.saveDeadLetterState(new PurviewDeadLetterState(
                "kitchen", "content-change-log", "z-doc", "t", "q",
                "2026-03-20T10:00:00Z", "2026-03-20T10:00:00Z", 1, "", ""));
        service.saveDeadLetterState(new PurviewDeadLetterState(
                "bedroom", "content-change-log", "b-doc", "t", "q",
                "2026-03-20T10:00:00Z", "2026-03-20T10:00:00Z", 1, "", ""));
        service.saveDeadLetterState(new PurviewDeadLetterState(
                "bedroom", "archive-snapshot", "a-doc", "t", "q",
                "2026-03-20T10:00:00Z", "2026-03-20T10:00:00Z", 1, "", ""));

        List<PurviewDeadLetterState> all = service.listDeadLetterStates();
        assertEquals(3, all.size());
        // Sorted by repositoryId, then streamKind, then entryKey
        assertEquals("bedroom", all.get(0).getRepositoryId());
        assertEquals("archive-snapshot", all.get(0).getStreamKind());
        assertEquals("bedroom", all.get(1).getRepositoryId());
        assertEquals("content-change-log", all.get(1).getStreamKind());
        assertEquals("kitchen", all.get(2).getRepositoryId());
    }

    @Test
    public void testListDeadLetterStates_empty() {
        assertTrue(service.listDeadLetterStates().isEmpty());
        assertTrue(service.listDeadLetterStates("bedroom").isEmpty());
        assertTrue(service.listDeadLetterStates("bedroom", "content-change-log").isEmpty());
        assertEquals(0, service.countDeadLetterStates("bedroom", "content-change-log"));
    }

    // --- Deduplication ---

    @Test
    public void testListDeadLetterStates_deduplicatesConsolidatedOverLegacy() {
        // Write legacy format for doc-001
        String legacyPrefix = "purview.dead-letter.state.bedroom.content-change-log.ZG9jLTAwMQ";
        Map<String, Object> legacyEntries = new HashMap<>();
        legacyEntries.put(legacyPrefix + ".repositoryId", "bedroom");
        legacyEntries.put(legacyPrefix + ".streamKind", "content-change-log");
        legacyEntries.put(legacyPrefix + ".entryKey", "doc-001");
        legacyEntries.put(legacyPrefix + ".typeName", "nemaki_document");
        legacyEntries.put(legacyPrefix + ".qualifiedName", "old-qn");
        legacyEntries.put(legacyPrefix + ".firstFailedAt", "2026-01-01T00:00:00Z");
        legacyEntries.put(legacyPrefix + ".lastFailedAt", "2026-01-01T01:00:00Z");
        legacyEntries.put(legacyPrefix + ".failureCount", 1);
        legacyEntries.put(legacyPrefix + ".checkpoint", "token-old");
        legacyEntries.put(legacyPrefix + ".errorSummary", "old error");
        stateStore.putAll(legacyEntries);

        // Save in consolidated format (same entry)
        service.saveDeadLetterState(new PurviewDeadLetterState(
                "bedroom", "content-change-log", "doc-001",
                "nemaki_document", "new-qn",
                "2026-03-20T10:00:00Z", "2026-03-20T10:00:05Z",
                3, "token-new", "new error"));

        // Should only return 1 entry (consolidated preferred over legacy)
        // Note: saveDeadLetterState already cleans up legacy, but even if both existed,
        // the deduplication logic in listDeadLetterStates would handle it
        List<PurviewDeadLetterState> states = service.listDeadLetterStates();
        assertEquals(1, states.size());
        assertEquals("new-qn", states.get(0).getQualifiedName());
        assertEquals(3, states.get(0).getFailureCount());
    }

    // --- Special characters ---

    @Test
    public void testSaveAndLoad_specialCharactersInEntryKey() {
        // Entry keys with dots, slashes, etc. should be Base64-encoded in storage keys
        String entryKey = "folder/subfolder/doc.with.dots";
        service.saveDeadLetterState(new PurviewDeadLetterState(
                "bedroom", "content-change-log", entryKey,
                "nemaki_document", "nemaki://bedroom/objects/abc",
                "2026-03-20T10:00:00Z", "2026-03-20T10:00:05Z",
                1, "token-1", "error"));

        PurviewDeadLetterState loaded = service.getDeadLetterState("bedroom", "content-change-log", entryKey);
        assertNotNull(loaded);
        assertEquals(entryKey, loaded.getEntryKey());
        assertEquals("nemaki_document", loaded.getTypeName());
    }

    @Test
    public void testSaveAndLoad_unicodeEntryKey() {
        String entryKey = "日本語ドキュメント-001";
        service.saveDeadLetterState(new PurviewDeadLetterState(
                "bedroom", "content-change-log", entryKey,
                "nemaki_document", "nemaki://bedroom/objects/unicode",
                "2026-03-20T10:00:00Z", "2026-03-20T10:00:05Z",
                1, "token-1", "error"));

        PurviewDeadLetterState loaded = service.getDeadLetterState("bedroom", "content-change-log", entryKey);
        assertNotNull(loaded);
        assertEquals(entryKey, loaded.getEntryKey());
    }

    @Test
    public void testSaveAndLoad_emptyEntryKey() {
        service.saveDeadLetterState(new PurviewDeadLetterState(
                "bedroom", "content-change-log", "",
                "nemaki_document", "qn",
                "2026-03-20T10:00:00Z", "2026-03-20T10:00:05Z",
                1, "token-1", "error"));

        PurviewDeadLetterState loaded = service.getDeadLetterState("bedroom", "content-change-log", "");
        assertNotNull(loaded);
        assertEquals("", loaded.getEntryKey());
    }

    // --- Count ---

    @Test
    public void testCountDeadLetterStates_empty() {
        assertEquals(0, service.countDeadLetterStates("bedroom", "content-change-log"));
    }

    @Test
    public void testCountDeadLetterStates_afterDeleteIsZero() {
        service.saveDeadLetterState(new PurviewDeadLetterState(
                "bedroom", "content-change-log", "doc-001",
                "nemaki_document", "qn",
                "2026-03-20T10:00:00Z", "2026-03-20T10:00:05Z",
                1, "token-1", "error"));

        assertEquals(1, service.countDeadLetterStates("bedroom", "content-change-log"));
        service.deleteDeadLetterState("bedroom", "content-change-log", "doc-001");
        assertEquals(0, service.countDeadLetterStates("bedroom", "content-change-log"));
    }

    // --- Legacy-only list ---

    @Test
    public void testListDeadLetterStates_legacyOnlyEntries() {
        // Write only legacy format entries
        String prefix1 = "purview.dead-letter.state.bedroom.content-change-log.ZG9jLUw";
        // ZG9jLUw is Base64url for "doc-L"
        Map<String, Object> legacy1 = new HashMap<>();
        legacy1.put(prefix1 + ".repositoryId", "bedroom");
        legacy1.put(prefix1 + ".streamKind", "content-change-log");
        legacy1.put(prefix1 + ".entryKey", "doc-L");
        legacy1.put(prefix1 + ".typeName", "nemaki_document");
        legacy1.put(prefix1 + ".qualifiedName", "qn-L");
        legacy1.put(prefix1 + ".firstFailedAt", "2026-01-01T00:00:00Z");
        legacy1.put(prefix1 + ".lastFailedAt", "2026-01-01T01:00:00Z");
        legacy1.put(prefix1 + ".failureCount", 1);
        legacy1.put(prefix1 + ".checkpoint", "token-L");
        legacy1.put(prefix1 + ".errorSummary", "legacy error");
        stateStore.putAll(legacy1);

        List<PurviewDeadLetterState> states = service.listDeadLetterStates();
        assertEquals(1, states.size());
        assertEquals("doc-L", states.get(0).getEntryKey());
        assertEquals("nemaki_document", states.get(0).getTypeName());
        assertEquals(1, states.get(0).getFailureCount());
    }

    @Test
    public void testDeleteDeadLetterState_nonExistent_noError() {
        // Should not throw even when the entry does not exist
        service.deleteDeadLetterState("bedroom", "content-change-log", "nonexistent");
        assertNull(service.getDeadLetterState("bedroom", "content-change-log", "nonexistent"));
    }

    // --- Null-safe fields ---

    @Test
    public void testSaveDeadLetterState_nullFields_storedAsEmpty() {
        service.saveDeadLetterState(new PurviewDeadLetterState(
                "bedroom", "content-change-log", "doc-001",
                null, null, null, null, 0, null, null));

        PurviewDeadLetterState loaded = service.getDeadLetterState("bedroom", "content-change-log", "doc-001");
        assertNotNull(loaded);
        assertEquals("", loaded.getTypeName());
        assertEquals("", loaded.getQualifiedName());
        assertEquals("", loaded.getFirstFailedAt());
        assertEquals("", loaded.getLastFailedAt());
        assertEquals(0, loaded.getFailureCount());
        assertEquals("", loaded.getCheckpoint());
        assertEquals("", loaded.getErrorSummary());
    }

    private static class StubContentDaoService extends StubContentDaoServiceBase {
        private Configuration systemConfiguration = createConfig();

        @Override
        public Configuration getConfiguration(String repositoryId) {
            if (SystemConst.NEMAKI_CONF_DB.equals(repositoryId)) {
                return systemConfiguration;
            }
            return new Configuration();
        }

        @Override
        public Configuration update(String repositoryId, Configuration configuration) {
            systemConfiguration = configuration;
            return configuration;
        }

        @Override
        public Configuration create(String repositoryId, Configuration configuration) {
            systemConfiguration = configuration;
            return configuration;
        }

        private static Configuration createConfig() {
            Configuration configuration = new Configuration();
            configuration.setId("config_" + SystemConst.NEMAKI_CONF_DB);
            configuration.setConfiguration(new HashMap<>());
            return configuration;
        }
    }
}
