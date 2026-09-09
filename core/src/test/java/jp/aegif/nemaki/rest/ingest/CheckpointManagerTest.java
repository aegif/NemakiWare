package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.rest.controller.IntegrationSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void loadSimple_refusesWhenTheStoreDidNotAnswer() {
        // null means "this profile has never polled", and the poll then takes only the first
        // page, treats every item as new, and writes the newest returned timestamp as the
        // checkpoint — moving it PAST the older items it never listed, which are filtered out
        // on every later poll. A failed configuration read used to produce exactly that null,
        // because ContentDaoServiceImpl answers a failed nemaki_conf read with an EMPTY
        // Configuration carrying loadFailed=true and PropertyManager drops the flag. Three
        // reviews reported it.
        IntegrationSettingsService refusing = mock(IntegrationSettingsService.class);
        when(refusing.readSettingOrRefuse("ingest.checkpoint.p1.gmail")).thenThrow(
                new IntegrationSettingsService.SettingUnreadableException(
                        "the configuration database did not answer"));
        CheckpointManager m = new CheckpointManager();
        m.setSettingsService(refusing);

        IntegrationSettingsService.SettingUnreadableException out = assertThrows(
                IntegrationSettingsService.SettingUnreadableException.class,
                () -> m.loadSimpleCheckpoint("p1", "gmail"),
                "a checkpoint read that FAILED was answered as 'this profile has never polled'");
        assertTrue(out.getMessage().contains("did not answer"),
                "the refusal does not say what happened: " + out.getMessage());
    }

    @Test
    void loadValidity_refusesWhenTheStoreDidNotAnswer() {
        // {0, 0} is the IMAP twin of the null above: it restarts the mailbox from UID 0.
        IntegrationSettingsService refusing = mock(IntegrationSettingsService.class);
        when(refusing.readSettingOrRefuse("ingest.checkpoint.p1.INBOX")).thenThrow(
                new IntegrationSettingsService.SettingUnreadableException(
                        "the configuration database did not answer"));
        CheckpointManager m = new CheckpointManager();
        m.setSettingsService(refusing);

        assertThrows(IntegrationSettingsService.SettingUnreadableException.class,
                () -> m.loadCheckpointWithValidity("p1", "INBOX"),
                "a checkpoint read that FAILED was answered as 'never polled'");
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
        CheckpointManager.ResetSummary summary = manager.resetCheckpoint("p1", "gmail");
        assertEquals("", mockSettings.store.get("ingest.checkpoint.p1.gmail"));
        // The named-scope pass never reads the profile row — it does not need to, the caller
        // named the key. Claiming it did says the row took part in a pass that never asked,
        // which is the record's own javadoc read backwards. A review found it.
        assertFalse(summary.profileRowRead(),
                "a pass that never read the profile row reported that it had");
        assertEquals(1, summary.keysReset());
    }

    @Test
    void resetCheckpoint_allScopes() {
        mockSettings.store.put("ingest.checkpoint.p1.gmail", "val1");
        mockSettings.store.put("ingest.checkpoint.p1.notion", "val2");
        manager.resetCheckpoint("p1", null);
        assertEquals("", mockSettings.store.get("ingest.checkpoint.p1.gmail"));
        assertEquals("", mockSettings.store.get("ingest.checkpoint.p1.notion"));
    }

    @Test
    void aResetThatCouldNotNameTheScopedKeysDoesNotReportAll() {
        // The scoped keys are rebuilt from the profile's schedulerParams, and get() answers
        // null for a read that FAILED and for an absent profile alike. The pass then reset
        // the static scopes only — and logged "All checkpoints reset for profile p1", with
        // the endpoint answering an unqualified success. A review found the incomplete reset
        // reported as a complete one.
        ImportProfileDefinitionService couldNotRead = mock(ImportProfileDefinitionService.class);
        when(couldNotRead.get("p1")).thenReturn(null);
        manager.setProfileService(couldNotRead);
        mockSettings.store.put("ingest.checkpoint.p1.gmail", "val1");
        mockSettings.store.put("ingest.checkpoint.p1.slack.C123", "1700000000.1");

        CheckpointManager.ResetSummary summary = manager.resetCheckpoint("p1", null);

        assertFalse(summary.profileRowRead(),
                "a pass that never read the profile row reported that it had");
        assertEquals(1, summary.keysReset(), "the count is of keys this pass could name");
        assertEquals("1700000000.1", mockSettings.store.get("ingest.checkpoint.p1.slack.C123"),
                "a scoped checkpoint was reset without the row that names it — the fixture "
                        + "no longer measures what it means to");
    }

    @Test
    void aResetThatReadTheProfileRowSaysSoAndReachesTheScopedKeys() {
        // The other side of the pair: with the row read, the scoped key IS named and reset,
        // and the summary says the answer is complete.
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        profile.setSchedulerParams(new HashMap<>(Map.of("channelId", "C123")));
        ImportProfileDefinitionService readable = mock(ImportProfileDefinitionService.class);
        when(readable.get("p1")).thenReturn(profile);
        manager.setProfileService(readable);
        mockSettings.store.put("ingest.checkpoint.p1.gmail", "val1");
        mockSettings.store.put("ingest.checkpoint.p1.slack.C123", "1700000000.1");

        CheckpointManager.ResetSummary summary = manager.resetCheckpoint("p1", null);

        assertTrue(summary.profileRowRead(), "the row was read and the summary denied it");
        assertEquals(2, summary.keysReset(), "the scoped key was not reset: " + mockSettings.store);
        assertEquals("", mockSettings.store.get("ingest.checkpoint.p1.slack.C123"));
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
