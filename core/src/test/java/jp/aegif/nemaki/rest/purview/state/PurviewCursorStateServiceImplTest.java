package jp.aegif.nemaki.rest.purview.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.util.StubContentDaoServiceBase;
import jp.aegif.nemaki.util.constant.SystemConst;

public class PurviewCursorStateServiceImplTest {

    private StubContentDaoService contentDaoService;
    private PurviewCursorStateServiceImpl service;

    @BeforeEach
    public void setUp() {
        contentDaoService = new StubContentDaoService();
        service = new PurviewCursorStateServiceImpl(new PurviewStateStoreImpl(contentDaoService));
    }

    @Test
    public void testSaveAndLoadCursorState() {
        PurviewCursorState cursorState = new PurviewCursorState(
                "bedroom",
                "content-change-log",
                "token-100",
                "changeToken",
                "2026-03-20T04:00:00Z",
                "2026-03-20T04:00:00Z",
                "",
                "",
                0,
                0);

        service.saveCursorState(cursorState);
        PurviewCursorState loaded = service.getCursorState("bedroom", "content-change-log");

        assertEquals("token-100", loaded.getCursor());
        assertEquals("changeToken", loaded.getCursorKind());
        assertEquals("2026-03-20T04:00:00Z", loaded.getLastRunAt());
        assertEquals(0, loaded.getConsecutiveFailureCount());
    }

    @Test
    public void testGetCursorStateReturnsEmptyStateForMissingStream() {
        PurviewCursorState loaded = service.getCursorState("bedroom", "nonexistent-stream");

        assertNotNull(loaded);
        assertEquals("bedroom", loaded.getRepositoryId());
        assertEquals("nonexistent-stream", loaded.getStreamKind());
        assertEquals("", loaded.getCursor());
        assertEquals(0, loaded.getDeadLetterCount());
    }

    @Test
    public void testSaveCursorStatePreservesFailureCount() {
        PurviewCursorState cursorState = new PurviewCursorState(
                "bedroom",
                "content-change-log",
                "token-200",
                "changeToken",
                "2026-03-20T05:00:00Z",
                "",
                "2026-03-20T05:00:00Z",
                "Connection timed out",
                3,
                2);

        service.saveCursorState(cursorState);
        PurviewCursorState loaded = service.getCursorState("bedroom", "content-change-log");

        assertEquals(3, loaded.getConsecutiveFailureCount());
        assertEquals(2, loaded.getDeadLetterCount());
        assertEquals("Connection timed out", loaded.getLastErrorMessage());
        assertEquals("2026-03-20T05:00:00Z", loaded.getLastErrorAt());
    }

    @Test
    public void testDifferentStreamsAreSavedIndependently() {
        PurviewCursorState changeLog = new PurviewCursorState(
                "bedroom", "content-change-log", "token-100", "changeToken",
                "2026-03-20T04:00:00Z", "2026-03-20T04:00:00Z", "", "", 0, 0);
        PurviewCursorState archiveSnapshot = new PurviewCursorState(
                "bedroom", "archive-snapshot", "snapshot-50", "snapshot",
                "2026-03-20T05:00:00Z", "2026-03-20T05:00:00Z", "", "", 0, 1);

        service.saveCursorState(changeLog);
        service.saveCursorState(archiveSnapshot);

        PurviewCursorState loadedChangeLog = service.getCursorState("bedroom", "content-change-log");
        PurviewCursorState loadedArchive = service.getCursorState("bedroom", "archive-snapshot");

        assertEquals("token-100", loadedChangeLog.getCursor());
        assertEquals("snapshot-50", loadedArchive.getCursor());
        assertEquals(1, loadedArchive.getDeadLetterCount());
    }

    @Test
    public void testSaveCursorStateOverwritesPreviousState() {
        PurviewCursorState initial = new PurviewCursorState(
                "bedroom", "content-change-log", "token-100", "changeToken",
                "2026-03-20T04:00:00Z", "2026-03-20T04:00:00Z", "", "", 0, 0);
        service.saveCursorState(initial);

        PurviewCursorState updated = new PurviewCursorState(
                "bedroom", "content-change-log", "token-200", "changeToken",
                "2026-03-20T06:00:00Z", "2026-03-20T06:00:00Z", "", "", 0, 0);
        service.saveCursorState(updated);

        PurviewCursorState loaded = service.getCursorState("bedroom", "content-change-log");

        assertEquals("token-200", loaded.getCursor());
        assertEquals("2026-03-20T06:00:00Z", loaded.getLastRunAt());
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
