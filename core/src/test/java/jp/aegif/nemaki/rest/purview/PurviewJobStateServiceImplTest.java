package jp.aegif.nemaki.rest.purview;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.util.StubContentDaoServiceBase;
import jp.aegif.nemaki.util.constant.SystemConst;

public class PurviewJobStateServiceImplTest {

    private StubContentDaoService contentDaoService;
    private PurviewJobStateServiceImpl service;

    @BeforeEach
    public void setUp() {
        contentDaoService = new StubContentDaoService();
        service = new PurviewJobStateServiceImpl(new PurviewStateStoreImpl(contentDaoService));
    }

    @Test
    public void testSaveAndLoadJobState() {
        PurviewJobState jobState = new PurviewJobState(
                "job-001",
                "FULL_SYNC",
                "bedroom",
                "COMPLETED",
                "2026-03-20T02:00:00Z",
                "2026-03-20T02:01:00Z",
                10,
                0,
                "noop",
                "");

        service.saveJobState(jobState);
        PurviewJobState loaded = service.getJobState("job-001");

        assertEquals("FULL_SYNC", loaded.getJobKind());
        assertEquals("bedroom", loaded.getRepositoryId());
        assertEquals("COMPLETED", loaded.getStatus());
        assertEquals(10, loaded.getProcessedCount());
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
