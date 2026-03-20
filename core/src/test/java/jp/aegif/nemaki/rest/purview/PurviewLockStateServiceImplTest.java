package jp.aegif.nemaki.rest.purview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.util.StubContentDaoServiceBase;
import jp.aegif.nemaki.util.constant.SystemConst;

public class PurviewLockStateServiceImplTest {

    private StubContentDaoService contentDaoService;
    private PurviewLockStateServiceImpl service;

    @BeforeEach
    public void setUp() {
        contentDaoService = new StubContentDaoService();
        service = new PurviewLockStateServiceImpl(new PurviewStateStoreImpl(contentDaoService));
    }

    @Test
    public void testTryAcquireRepositoryLockPersistsOwnerAndTimestamp() {
        boolean acquired = service.tryAcquireRepositoryLock(
                "bedroom",
                "FULL_SYNC",
                "job-001",
                "2026-03-20T03:00:00Z");

        PurviewLockState lockState = service.getRepositoryLock("bedroom", "FULL_SYNC");

        assertTrue(acquired);
        assertTrue(lockState.isLocked());
        assertEquals("job-001", lockState.getOwnerJobId());
        assertEquals("2026-03-20T03:00:00Z", lockState.getLockedAt());
    }

    @Test
    public void testTryAcquireRepositoryLockRejectsAnotherOwner() {
        service.tryAcquireRepositoryLock("bedroom", "FULL_SYNC", "job-001", "2026-03-20T03:00:00Z");

        boolean acquired = service.tryAcquireRepositoryLock(
                "bedroom",
                "FULL_SYNC",
                "job-002",
                "2026-03-20T03:01:00Z");

        PurviewLockState lockState = service.getRepositoryLock("bedroom", "FULL_SYNC");

        assertFalse(acquired);
        assertEquals("job-001", lockState.getOwnerJobId());
    }

    @Test
    public void testReleaseRepositoryLockClearsStateForOwner() {
        service.tryAcquireRepositoryLock("bedroom", "FULL_SYNC", "job-001", "2026-03-20T03:00:00Z");

        service.releaseRepositoryLock("bedroom", "FULL_SYNC", "job-001");

        PurviewLockState lockState = service.getRepositoryLock("bedroom", "FULL_SYNC");
        assertFalse(lockState.isLocked());
        assertEquals("", lockState.getOwnerJobId());
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
