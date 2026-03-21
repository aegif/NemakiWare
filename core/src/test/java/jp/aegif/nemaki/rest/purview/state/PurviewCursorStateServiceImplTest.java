package jp.aegif.nemaki.rest.purview.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
