package jp.aegif.nemaki.rest.purview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.util.StubContentDaoServiceBase;
import jp.aegif.nemaki.util.constant.SystemConst;

public class PurviewDeadLetterStateServiceImplTest {

    private StubContentDaoService contentDaoService;
    private PurviewDeadLetterStateServiceImpl service;

    @BeforeEach
    public void setUp() {
        contentDaoService = new StubContentDaoService();
        service = new PurviewDeadLetterStateServiceImpl(new PurviewStateStoreImpl(contentDaoService));
    }

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

        assertEquals("nemaki_document", loaded.getTypeName());
        assertEquals("nemaki://bedroom/objects/doc.001", loaded.getQualifiedName());
        assertEquals(2, loaded.getFailureCount());
        assertEquals("publish failed", loaded.getErrorSummary());
    }

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
        assertEquals("", loaded.getTypeName());
        assertEquals(0, loaded.getFailureCount());
    }

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

        List<PurviewDeadLetterState> states = service.listDeadLetterStates("bedroom", "content-change-log");

        assertEquals(List.of("doc-001", "doc-002"),
                states.stream().map(PurviewDeadLetterState::getEntryKey).toList());
        assertEquals(2, service.countDeadLetterStates("bedroom", "content-change-log"));
        assertEquals(3, service.listDeadLetterStates("bedroom").size());
        assertEquals(4, service.listDeadLetterStates().size());
        assertTrue(service.listDeadLetterStates("missing", "content-change-log").isEmpty());
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
