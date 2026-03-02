package jp.aegif.nemaki.api.v1.jmx;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class RepositoryStatsMBeanTest {

    private RepositoryStats repositoryStats;

    @BeforeEach
    public void setUp() {
        repositoryStats = new RepositoryStats();
    }

    @Test
    public void testGetTotalNodesReturnsNonNegative() {
        long totalNodes = repositoryStats.getTotalNodes();
        assertTrue(totalNodes >= 0);
    }

    @Test
    public void testGetTotalFilesReturnsNonNegative() {
        long totalFiles = repositoryStats.getTotalFiles();
        assertTrue(totalFiles >= 0);
    }

    @Test
    public void testGetTotalFoldersReturnsNonNegative() {
        long totalFolders = repositoryStats.getTotalFolders();
        assertTrue(totalFolders >= 0);
    }

    @Test
    public void testReloadConfigurationDoesNotThrow() {
        try {
            repositoryStats.reloadConfiguration();
        } catch (Exception e) {
            fail("reloadConfiguration should not throw exception: " + e.getMessage());
        }
    }

    @Test
    public void testGetRepositoryIdReturnsNonNull() {
        String repositoryId = repositoryStats.getRepositoryId();
        assertNotNull(repositoryId);
    }

    @Test
    public void testSetRepositoryIdUpdatesValue() {
        repositoryStats.setRepositoryId("test-repo");
        assertEquals("test-repo", repositoryStats.getRepositoryId());
    }
}
