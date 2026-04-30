package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.rest.controller.IntegrationSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CheckpointManager with a mock IntegrationSettingsService.
 */
class CheckpointManagerTest {

    private CheckpointManager manager;
    private MockSettingsService mockSettings;

    @BeforeEach
    void setUp() {
        mockSettings = new MockSettingsService();
        manager = new CheckpointManager();
        manager.setSettingsService(mockSettings);
    }

    // ── loadSimpleCheckpoint ──

    @Test
    void loadSimple_returnsNullWhenNotSet() {
        assertNull(manager.loadSimpleCheckpoint("p1", "gmail"));
    }

    @Test
    void loadSimple_returnsValueWhenSet() {
        mockSettings.store.put("ingest.checkpoint.p1.gmail", "2026-04-01T00:00:00Z");
        assertEquals("2026-04-01T00:00:00Z", manager.loadSimpleCheckpoint("p1", "gmail"));
    }

    @Test
    void loadSimple_returnsNullForBlank() {
        mockSettings.store.put("ingest.checkpoint.p1.gmail", "  ");
        assertNull(manager.loadSimpleCheckpoint("p1", "gmail"));
    }

    // ── saveSimpleCheckpoint ──

    @Test
    void saveSimple_writesValue() {
        manager.saveSimpleCheckpoint("p1", "slack.C123", "1234567890.123456");
        assertEquals("1234567890.123456", mockSettings.store.get("ingest.checkpoint.p1.slack.C123"));
    }

    @Test
    void saveSimple_ignoresNullValue() {
        manager.saveSimpleCheckpoint("p1", "gmail", null);
        assertFalse(mockSettings.store.containsKey("ingest.checkpoint.p1.gmail"));
    }

    // ── loadCheckpointWithValidity (IMAP) ──

    @Test
    void loadValidity_returnsZerosWhenNotSet() {
        long[] result = manager.loadCheckpointWithValidity("p1", "INBOX");
        assertArrayEquals(new long[]{0, 0}, result);
    }

    @Test
    void loadValidity_parsesValidityColonUid() {
        mockSettings.store.put("ingest.checkpoint.p1.INBOX", "12345:678");
        long[] result = manager.loadCheckpointWithValidity("p1", "INBOX");
        assertEquals(12345, result[0]);
        assertEquals(678, result[1]);
    }

    @Test
    void loadValidity_legacyUidOnlyFormat() {
        mockSettings.store.put("ingest.checkpoint.p1.INBOX", "999");
        long[] result = manager.loadCheckpointWithValidity("p1", "INBOX");
        assertEquals(0, result[0]);
        assertEquals(999, result[1]);
    }

    @Test
    void loadValidity_invalidFormatReturnsZeros() {
        mockSettings.store.put("ingest.checkpoint.p1.INBOX", "not-a-number");
        long[] result = manager.loadCheckpointWithValidity("p1", "INBOX");
        assertArrayEquals(new long[]{0, 0}, result);
    }

    // ── saveCheckpointWithValidity ──

    @Test
    void saveValidity_writesFormat() {
        manager.saveCheckpointWithValidity("p1", "INBOX", 12345, 678);
        assertEquals("12345:678", mockSettings.store.get("ingest.checkpoint.p1.INBOX"));
    }

    // ── resetCheckpoint ──

    @Test
    void resetCheckpoint_specificScope() {
        mockSettings.store.put("ingest.checkpoint.p1.gmail", "somevalue");
        manager.resetCheckpoint("p1", "gmail");
        assertEquals("", mockSettings.store.get("ingest.checkpoint.p1.gmail"));
    }

    @Test
    void resetCheckpoint_allScopes() {
        mockSettings.store.put("ingest.checkpoint.p1.gmail", "val1");
        mockSettings.store.put("ingest.checkpoint.p1.notion", "val2");
        manager.resetCheckpoint("p1", null);
        assertEquals("", mockSettings.store.get("ingest.checkpoint.p1.gmail"));
        assertEquals("", mockSettings.store.get("ingest.checkpoint.p1.notion"));
    }

    // ── getCheckpoints ──

    @Test
    void getCheckpoints_returnsStaticScopes() {
        mockSettings.store.put("ingest.checkpoint.p1.gmail", "date1");
        mockSettings.store.put("ingest.checkpoint.p1.salesforce", "date2");
        Map<String, Object> result = manager.getCheckpoints("p1");
        assertEquals("date1", result.get("gmail"));
        assertEquals("date2", result.get("salesforce"));
        assertFalse(result.containsKey("notion")); // not set
    }

    // ── null settingsService ──

    @Test
    void nullSettingsService_gracefulDegradation() {
        CheckpointManager noSettings = new CheckpointManager();
        assertNull(noSettings.loadSimpleCheckpoint("p1", "gmail"));
        assertDoesNotThrow(() -> noSettings.saveSimpleCheckpoint("p1", "gmail", "val"));
        assertArrayEquals(new long[]{0, 0}, noSettings.loadCheckpointWithValidity("p1", "INBOX"));
        assertTrue(noSettings.getCheckpoints("p1").isEmpty());
    }

    // ── Mock IntegrationSettingsService ──

    private static class MockSettingsService extends IntegrationSettingsService {
        final Map<String, String> store = new HashMap<>();

        MockSettingsService() {
            // No-arg constructor; do not call super with dependencies
        }

        @Override
        public String readSetting(String key) {
            return store.get(key);
        }

        @Override
        public void writeSetting(String key, String value) {
            store.put(key, value);
        }

        @Override
        public void deleteSettings(Set<String> keys) {
            keys.forEach(store::remove);
        }
    }
}
