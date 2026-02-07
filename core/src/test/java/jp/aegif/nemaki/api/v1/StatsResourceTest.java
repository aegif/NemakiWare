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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Response;
import org.apache.chemistry.opencmis.commons.server.CallContext;

public class StatsResourceTest {

    private StatsResource statsResource;

    @Mock
    private ContentService contentService;

    private HttpServletRequest adminRequest;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        statsResource = new StatsResource();
        adminRequest = createAdminRequest();
    }

    private HttpServletRequest createAdminRequest() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext callContext = mock(CallContext.class);
        when(callContext.get(jp.aegif.nemaki.util.constant.CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(request.getAttribute("CallContext")).thenReturn(callContext);
        return request;
    }

    @Test
    public void testStatsEndpointReturns200() {
        String repositoryId = "test-repo";
        Response response = statsResource.getStats(repositoryId, adminRequest);
        assertEquals(200, response.getStatus());
    }

    @Test
    public void testStatsResponseContainsRepositoryStats() {
        String repositoryId = "test-repo";
        Response response = statsResource.getStats(repositoryId, adminRequest);
        StatsResponse stats = (StatsResponse) response.getEntity();
        assertNotNull(stats.getRepository());
    }

    @Test
    public void testStatsResponseContainsJvmStats() {
        String repositoryId = "test-repo";
        Response response = statsResource.getStats(repositoryId, adminRequest);
        StatsResponse stats = (StatsResponse) response.getEntity();
        assertNotNull(stats.getJvm());
    }

    @Test
    public void testStatsResponseContainsTimestamp() {
        String repositoryId = "test-repo";
        Response response = statsResource.getStats(repositoryId, adminRequest);
        StatsResponse stats = (StatsResponse) response.getEntity();
        assertNotNull(stats.getTimestamp());
    }

    @Test
    public void testJvmStatsContainsHeapUsed() {
        String repositoryId = "test-repo";
        Response response = statsResource.getStats(repositoryId, adminRequest);
        StatsResponse stats = (StatsResponse) response.getEntity();
        assertTrue(stats.getJvm().getHeapUsed() >= 0);
    }

    @Test
    public void testJvmStatsContainsHeapMax() {
        String repositoryId = "test-repo";
        Response response = statsResource.getStats(repositoryId, adminRequest);
        StatsResponse stats = (StatsResponse) response.getEntity();
        assertTrue(stats.getJvm().getHeapMax() > 0);
    }

    @Test
    public void testJvmStatsContainsThreadCount() {
        String repositoryId = "test-repo";
        Response response = statsResource.getStats(repositoryId, adminRequest);
        StatsResponse stats = (StatsResponse) response.getEntity();
        assertTrue(stats.getJvm().getThreadCount() > 0);
    }

    @Test
    public void testJvmStatsContainsUptime() {
        String repositoryId = "test-repo";
        Response response = statsResource.getStats(repositoryId, adminRequest);
        StatsResponse stats = (StatsResponse) response.getEntity();
        assertTrue(stats.getJvm().getUptimeMs() >= 0);
    }

    @Test
    public void testRepositoryStatsContainsRepositoryId() {
        String repositoryId = "test-repo";
        Response response = statsResource.getStats(repositoryId, adminRequest);
        StatsResponse stats = (StatsResponse) response.getEntity();
        assertEquals(repositoryId, stats.getRepository().getRepositoryId());
    }

    @Test
    public void testNonAdminGetsForbidden() {
        HttpServletRequest nonAdminRequest = mock(HttpServletRequest.class);
        when(nonAdminRequest.getAttribute("CallContext")).thenReturn(null);
        Response response = statsResource.getStats("test-repo", nonAdminRequest);
        assertEquals(403, response.getStatus());
    }
}
