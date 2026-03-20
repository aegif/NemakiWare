package jp.aegif.nemaki.rest.purview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.util.StubContentDaoServiceBase;
import jp.aegif.nemaki.util.constant.SystemConst;

public class PurviewTombstoneStateServiceImplTest {

    private StubContentDaoService contentDaoService;
    private PurviewTombstoneStateServiceImpl service;

    @BeforeEach
    public void setUp() {
        contentDaoService = new StubContentDaoService();
        service = new PurviewTombstoneStateServiceImpl(new PurviewStateStoreImpl(contentDaoService));
    }

    @Test
    public void testSaveAndLoadTombstoneState() {
        PurviewTombstoneState tombstoneState = new PurviewTombstoneState(
                "bedroom",
                "doc-001",
                "nemaki_document",
                "nemaki://bedroom/objects/doc-001",
                "token-101",
                "2026-03-20T10:00:00Z",
                "2026-03-20T10:00:05Z",
                "PENDING");

        service.saveTombstoneState(tombstoneState);
        PurviewTombstoneState loaded = service.getTombstoneState("bedroom", "doc-001");

        assertEquals("nemaki_document", loaded.getTypeName());
        assertEquals("nemaki://bedroom/objects/doc-001", loaded.getQualifiedName());
        assertEquals("token-101", loaded.getChangeToken());
        assertEquals("PENDING", loaded.getStatus());
    }

    @Test
    public void testDeleteTombstoneStateClearsPersistedKeys() {
        service.saveTombstoneState(new PurviewTombstoneState(
                "bedroom",
                "doc-001",
                "nemaki_document",
                "nemaki://bedroom/objects/doc-001",
                "token-101",
                "2026-03-20T10:00:00Z",
                "2026-03-20T10:00:05Z",
                "PENDING"));

        service.deleteTombstoneState("bedroom", "doc-001");

        PurviewTombstoneState loaded = service.getTombstoneState("bedroom", "doc-001");
        assertEquals("", loaded.getQualifiedName());
        assertEquals("", loaded.getStatus());
    }

    @Test
    public void testListDueTombstoneStatesFiltersByRepositoryDueAtAndStatus() {
        service.saveTombstoneState(new PurviewTombstoneState(
                "bedroom",
                "doc-001",
                "nemaki_document",
                "nemaki://bedroom/objects/doc-001",
                "101",
                "2026-03-20T10:00:00Z",
                "2026-03-20T10:00:05Z",
                "PENDING"));
        service.saveTombstoneState(new PurviewTombstoneState(
                "bedroom",
                "doc-002",
                "nemaki_document",
                "nemaki://bedroom/objects/doc-002",
                "102",
                "2026-03-20T10:00:01Z",
                "2026-03-20T10:00:30Z",
                "PENDING"));
        service.saveTombstoneState(new PurviewTombstoneState(
                "bedroom",
                "doc-003",
                "nemaki_document",
                "nemaki://bedroom/objects/doc-003",
                "103",
                "2026-03-20T10:00:02Z",
                "2026-03-20T10:00:03Z",
                "ARCHIVED"));
        service.saveTombstoneState(new PurviewTombstoneState(
                "kitchen",
                "doc-004",
                "nemaki_document",
                "nemaki://kitchen/objects/doc-004",
                "104",
                "2026-03-20T10:00:02Z",
                "2026-03-20T10:00:03Z",
                "PENDING"));

        List<PurviewTombstoneState> dueStates = service.listDueTombstoneStates(
                "bedroom",
                Instant.parse("2026-03-20T10:00:05Z"));

        assertEquals(1, dueStates.size());
        assertEquals("doc-001", dueStates.get(0).getObjectId());
        assertIterableEquals(
                List.of("doc-003", "doc-001", "doc-002"),
                service.listTombstoneStates("bedroom").stream().map(PurviewTombstoneState::getObjectId).toList());
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
