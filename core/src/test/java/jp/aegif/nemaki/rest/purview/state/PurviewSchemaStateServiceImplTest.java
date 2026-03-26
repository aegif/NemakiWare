package jp.aegif.nemaki.rest.purview.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.util.StubContentDaoServiceBase;
import jp.aegif.nemaki.util.constant.SystemConst;

public class PurviewSchemaStateServiceImplTest {

    private StubContentDaoService contentDaoService;
    private PurviewSchemaStateServiceImpl service;

    @BeforeEach
    public void setUp() {
        contentDaoService = new StubContentDaoService();
        service = new PurviewSchemaStateServiceImpl(new PurviewStateStoreImpl(contentDaoService));
    }

    @Test
    public void testGetSchemaStateReturnsEmptyStateWhenNothingIsPersisted() {
        PurviewSchemaState state = service.getSchemaState("NemakiWare");

        assertEquals("NemakiWare", state.getCollection());
        assertEquals("", state.getSchemaVersion());
        assertEquals("", state.getSchemaHash());
        assertEquals("", state.getLastAppliedBy());
    }

    @Test
    public void testSaveSchemaStatePersistsValuesIntoSystemConfiguration() {
        PurviewSchemaState state = new PurviewSchemaState(
                "NemakiWare",
                "1",
                "abc123hash",
                "2026-03-20T00:00:00Z",
                "admin",
                "initial bootstrap");

        service.saveSchemaState(state);
        PurviewSchemaState loaded = service.getSchemaState("NemakiWare");

        assertEquals("1", loaded.getSchemaVersion());
        assertEquals("abc123hash", loaded.getSchemaHash());
        assertEquals("2026-03-20T00:00:00Z", loaded.getLastAppliedAt());
        assertEquals("admin", loaded.getLastAppliedBy());
        assertEquals("initial bootstrap", loaded.getLastDiffSummary());
        assertTrue(contentDaoService.updated || contentDaoService.created);
    }

    @Test
    public void testSaveSchemaStateOverwritesPreviousState() {
        PurviewSchemaState v1 = new PurviewSchemaState(
                "NemakiWare", "1", "hash-v1", "2026-03-20T00:00:00Z", "admin", "v1");
        service.saveSchemaState(v1);

        PurviewSchemaState v2 = new PurviewSchemaState(
                "NemakiWare", "2", "hash-v2", "2026-03-21T00:00:00Z", "system", "v2");
        service.saveSchemaState(v2);

        PurviewSchemaState loaded = service.getSchemaState("NemakiWare");

        assertEquals("2", loaded.getSchemaVersion());
        assertEquals("hash-v2", loaded.getSchemaHash());
        assertEquals("system", loaded.getLastAppliedBy());
    }

    @Test
    public void testDifferentCollectionsAreStoredIndependently() {
        PurviewSchemaState nemaki = new PurviewSchemaState(
                "NemakiWare", "1", "hash-nemaki", "2026-03-20T00:00:00Z", "admin", "nemaki");
        PurviewSchemaState other = new PurviewSchemaState(
                "OtherCollection", "3", "hash-other", "2026-03-21T00:00:00Z", "system", "other");

        service.saveSchemaState(nemaki);
        service.saveSchemaState(other);

        PurviewSchemaState loadedNemaki = service.getSchemaState("NemakiWare");
        PurviewSchemaState loadedOther = service.getSchemaState("OtherCollection");

        assertEquals("hash-nemaki", loadedNemaki.getSchemaHash());
        assertEquals("hash-other", loadedOther.getSchemaHash());
    }

    private static class StubContentDaoService extends StubContentDaoServiceBase {
        private Configuration systemConfiguration = createConfig();
        private boolean created;
        private boolean updated;

        @Override
        public Configuration getConfiguration(String repositoryId) {
            if (SystemConst.NEMAKI_CONF_DB.equals(repositoryId)) {
                return systemConfiguration;
            }
            return new Configuration();
        }

        @Override
        public Configuration update(String repositoryId, Configuration configuration) {
            updated = true;
            systemConfiguration = configuration;
            return configuration;
        }

        @Override
        public Configuration create(String repositoryId, Configuration configuration) {
            created = true;
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
