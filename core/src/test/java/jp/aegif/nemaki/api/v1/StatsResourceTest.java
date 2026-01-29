package jp.aegif.nemaki.api.v1;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import jp.aegif.nemaki.api.v1.model.response.StatsResponse;
import jp.aegif.nemaki.api.v1.resource.StatsResource;
import jp.aegif.nemaki.businesslogic.ContentService;

import jakarta.ws.rs.core.Response;

public class StatsResourceTest {

    private StatsResource statsResource;

    @Mock
    private ContentService contentService;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        statsResource = new StatsResource();
    }

    @Test
    public void testStatsEndpointReturns200() {
        String repositoryId = "test-repo";
        Response response = statsResource.getStats(repositoryId);
        assertEquals(200, response.getStatus());
    }

    @Test
    public void testStatsResponseContainsRepositoryStats() {
        String repositoryId = "test-repo";
        Response response = statsResource.getStats(repositoryId);
        StatsResponse stats = (StatsResponse) response.getEntity();
        assertNotNull(stats.getRepository());
    }

    @Test
    public void testStatsResponseContainsJvmStats() {
        String repositoryId = "test-repo";
        Response response = statsResource.getStats(repositoryId);
        StatsResponse stats = (StatsResponse) response.getEntity();
        assertNotNull(stats.getJvm());
    }

    @Test
    public void testStatsResponseContainsTimestamp() {
        String repositoryId = "test-repo";
        Response response = statsResource.getStats(repositoryId);
        StatsResponse stats = (StatsResponse) response.getEntity();
        assertNotNull(stats.getTimestamp());
    }

    @Test
    public void testJvmStatsContainsHeapUsed() {
        String repositoryId = "test-repo";
        Response response = statsResource.getStats(repositoryId);
        StatsResponse stats = (StatsResponse) response.getEntity();
        assertTrue(stats.getJvm().getHeapUsed() >= 0);
    }

    @Test
    public void testJvmStatsContainsHeapMax() {
        String repositoryId = "test-repo";
        Response response = statsResource.getStats(repositoryId);
        StatsResponse stats = (StatsResponse) response.getEntity();
        assertTrue(stats.getJvm().getHeapMax() > 0);
    }

    @Test
    public void testJvmStatsContainsThreadCount() {
        String repositoryId = "test-repo";
        Response response = statsResource.getStats(repositoryId);
        StatsResponse stats = (StatsResponse) response.getEntity();
        assertTrue(stats.getJvm().getThreadCount() > 0);
    }

    @Test
    public void testJvmStatsContainsUptime() {
        String repositoryId = "test-repo";
        Response response = statsResource.getStats(repositoryId);
        StatsResponse stats = (StatsResponse) response.getEntity();
        assertTrue(stats.getJvm().getUptimeMs() >= 0);
    }

    @Test
    public void testRepositoryStatsContainsRepositoryId() {
        String repositoryId = "test-repo";
        Response response = statsResource.getStats(repositoryId);
        StatsResponse stats = (StatsResponse) response.getEntity();
        assertEquals(repositoryId, stats.getRepository().getRepositoryId());
    }
}
