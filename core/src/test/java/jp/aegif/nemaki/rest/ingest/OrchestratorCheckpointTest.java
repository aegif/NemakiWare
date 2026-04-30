package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.rest.controller.IntegrationSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Orchestrator checkpoint and skip logic via CheckpointManager.
 *
 * <p>Uses a mock SettingsService to verify checkpoint reads/writes
 * without requiring CouchDB or Spring context.
 */
class OrchestratorCheckpointTest {

    private CheckpointManager checkpointManager;
    private MockSettingsService mockSettings;

    @BeforeEach
    void setUp() {
        mockSettings = new MockSettingsService();
        checkpointManager = new CheckpointManager();
        checkpointManager.setSettingsService(mockSettings);
    }

    // ── Simple checkpoint round-trip ──

    @Test
    void simpleCheckpoint_saveAndLoad() {
        checkpointManager.saveSimpleCheckpoint("p1", "slack.C01", "1234567890.123456");
        assertEquals("1234567890.123456", checkpointManager.loadSimpleCheckpoint("p1", "slack.C01"));
    }

    @Test
    void simpleCheckpoint_differentScopes_independent() {
        checkpointManager.saveSimpleCheckpoint("p1", "slack.C01", "ts1");
        checkpointManager.saveSimpleCheckpoint("p1", "slack.C02", "ts2");
        assertEquals("ts1", checkpointManager.loadSimpleCheckpoint("p1", "slack.C01"));
        assertEquals("ts2", checkpointManager.loadSimpleCheckpoint("p1", "slack.C02"));
    }

    @Test
    void simpleCheckpoint_differentProfiles_independent() {
        checkpointManager.saveSimpleCheckpoint("p1", "gmail", "2026/04/01");
        checkpointManager.saveSimpleCheckpoint("p2", "gmail", "2026/04/15");
        assertEquals("2026/04/01", checkpointManager.loadSimpleCheckpoint("p1", "gmail"));
        assertEquals("2026/04/15", checkpointManager.loadSimpleCheckpoint("p2", "gmail"));
    }

    @Test
    void simpleCheckpoint_overwrite() {
        checkpointManager.saveSimpleCheckpoint("p1", "notion", "2026-01-01T00:00:00Z");
        checkpointManager.saveSimpleCheckpoint("p1", "notion", "2026-02-01T00:00:00Z");
        assertEquals("2026-02-01T00:00:00Z", checkpointManager.loadSimpleCheckpoint("p1", "notion"));
    }

    // ── IMAP checkpoint with validity ──

    @Test
    void imapCheckpoint_saveAndLoad() {
        checkpointManager.saveCheckpointWithValidity("p1", "INBOX", 12345, 678);
        long[] cp = checkpointManager.loadCheckpointWithValidity("p1", "INBOX");
        assertEquals(12345, cp[0]);
        assertEquals(678, cp[1]);
    }

    @Test
    void imapCheckpoint_legacyFormat_uidOnly() {
        mockSettings.store.put("ingest.checkpoint.p1.INBOX", "999");
        long[] cp = checkpointManager.loadCheckpointWithValidity("p1", "INBOX");
        assertEquals(0, cp[0]); // no validity
        assertEquals(999, cp[1]);
    }

    @Test
    void imapCheckpoint_corruptedFormat_returnsZeros() {
        mockSettings.store.put("ingest.checkpoint.p1.INBOX", "not-a-number");
        long[] cp = checkpointManager.loadCheckpointWithValidity("p1", "INBOX");
        assertEquals(0, cp[0]);
        assertEquals(0, cp[1]);
    }

    // ── Reset ──

    @Test
    void resetCheckpoint_specificScope_clearsOnly() {
        checkpointManager.saveSimpleCheckpoint("p1", "gmail", "date1");
        checkpointManager.saveSimpleCheckpoint("p1", "notion", "date2");
        checkpointManager.resetCheckpoint("p1", "gmail");
        assertNull(checkpointManager.loadSimpleCheckpoint("p1", "gmail"));
        assertEquals("date2", checkpointManager.loadSimpleCheckpoint("p1", "notion"));
    }

    @Test
    void resetCheckpoint_allScopes() {
        checkpointManager.saveSimpleCheckpoint("p1", "gmail", "date1");
        checkpointManager.saveSimpleCheckpoint("p1", "notion", "date2");
        checkpointManager.saveSimpleCheckpoint("p1", "salesforce", "date3");
        checkpointManager.resetCheckpoint("p1", null);
        assertNull(checkpointManager.loadSimpleCheckpoint("p1", "gmail"));
        assertNull(checkpointManager.loadSimpleCheckpoint("p1", "notion"));
        assertNull(checkpointManager.loadSimpleCheckpoint("p1", "salesforce"));
    }

    // ── Checkpoint key scoping ──

    @Test
    void checkpointKey_includesProfileAndScope() {
        checkpointManager.saveSimpleCheckpoint("profile-abc", "teams.T1.C1", "2026-01-01");
        assertTrue(mockSettings.store.containsKey("ingest.checkpoint.profile-abc.teams.T1.C1"));
    }

    @Test
    void checkpointKey_boxIncludesFolderId() {
        checkpointManager.saveSimpleCheckpoint("p1", "box.0", "2026-01-01T00:00:00Z");
        assertEquals("2026-01-01T00:00:00Z", mockSettings.store.get("ingest.checkpoint.p1.box.0"));
    }

    @Test
    void checkpointKey_mattermostIncludesChannelId() {
        checkpointManager.saveSimpleCheckpoint("p1", "mattermost.ch123", "1234567890000");
        assertEquals("1234567890000", checkpointManager.loadSimpleCheckpoint("p1", "mattermost.ch123"));
    }

    // ── Mattermost immutable checkpoint (batch safety) ──

    @Test
    void mattermost_checkpointFormat_numericTimestamp() {
        // Mattermost uses numeric createAt (epoch ms) as checkpoint
        checkpointManager.saveSimpleCheckpoint("p1", "mattermost.ch1", "1714500000000");
        String loaded = checkpointManager.loadSimpleCheckpoint("p1", "mattermost.ch1");
        assertEquals("1714500000000", loaded);
        // Verify it's parseable as long
        assertDoesNotThrow(() -> Long.parseLong(loaded));
    }

    // ── Salesforce checkpoint format ──

    @Test
    void salesforce_iso8601Format() {
        String ts = "2026-04-01T12:00:00.000Z";
        checkpointManager.saveSimpleCheckpoint("p1", "salesforce", ts);
        assertEquals(ts, checkpointManager.loadSimpleCheckpoint("p1", "salesforce"));
    }

    // ── Gmail checkpoint format ──

    @Test
    void gmail_dateFormat() {
        checkpointManager.saveSimpleCheckpoint("p1", "gmail", "2026/04/01");
        assertEquals("2026/04/01", checkpointManager.loadSimpleCheckpoint("p1", "gmail"));
    }

    // ── Mock ──

    private static class MockSettingsService extends IntegrationSettingsService {
        final Map<String, String> store = new HashMap<>();
        MockSettingsService() {}
        @Override public String readSetting(String key) { return store.get(key); }
        @Override public void writeSetting(String key, String value) { store.put(key, value); }
        @Override public void deleteSettings(Set<String> keys) { keys.forEach(store::remove); }
    }
}
