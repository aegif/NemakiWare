package jp.aegif.nemaki.api.v1;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.Before;
import org.junit.Test;

import jp.aegif.nemaki.api.v1.model.response.JobControlResponse;
import jp.aegif.nemaki.api.v1.resource.JobControlResource;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Response;
import org.apache.chemistry.opencmis.commons.server.CallContext;

public class JobControlResourceTest {

    private JobControlResource jobControlResource;
    private HttpServletRequest adminRequest;

    @Before
    public void setUp() {
        jobControlResource = new JobControlResource();
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
    public void testPauseJobsReturns200() {
        String repositoryId = "test-repo";
        Response response = jobControlResource.pauseJobs(repositoryId, adminRequest);
        assertEquals(200, response.getStatus());
    }

    @Test
    public void testResumeJobsReturns200() {
        String repositoryId = "test-repo";
        Response response = jobControlResource.resumeJobs(repositoryId, adminRequest);
        assertEquals(200, response.getStatus());
    }

    @Test
    public void testPauseJobsReturnsCorrectStatus() {
        String repositoryId = "test-repo";
        Response response = jobControlResource.pauseJobs(repositoryId, adminRequest);
        JobControlResponse jobResponse = (JobControlResponse) response.getEntity();
        assertEquals("paused", jobResponse.getStatus());
    }

    @Test
    public void testResumeJobsReturnsCorrectStatus() {
        String repositoryId = "test-repo";
        Response response = jobControlResource.resumeJobs(repositoryId, adminRequest);
        JobControlResponse jobResponse = (JobControlResponse) response.getEntity();
        assertEquals("running", jobResponse.getStatus());
    }

    @Test
    public void testGetJobStatusReturns200() {
        String repositoryId = "test-repo";
        Response response = jobControlResource.getJobStatus(repositoryId, adminRequest);
        assertEquals(200, response.getStatus());
    }

    @Test
    public void testGetJobStatusContainsPendingCount() {
        String repositoryId = "test-repo";
        Response response = jobControlResource.getJobStatus(repositoryId, adminRequest);
        JobControlResponse jobResponse = (JobControlResponse) response.getEntity();
        assertTrue(jobResponse.getPendingJobs() >= 0);
    }

    @Test
    public void testGetJobStatusContainsRunningCount() {
        String repositoryId = "test-repo";
        Response response = jobControlResource.getJobStatus(repositoryId, adminRequest);
        JobControlResponse jobResponse = (JobControlResponse) response.getEntity();
        assertTrue(jobResponse.getRunningJobs() >= 0);
    }

    @Test
    public void testJobControlResponseContainsTimestamp() {
        String repositoryId = "test-repo";
        Response response = jobControlResource.getJobStatus(repositoryId, adminRequest);
        JobControlResponse jobResponse = (JobControlResponse) response.getEntity();
        assertNotNull(jobResponse.getTimestamp());
    }

    @Test
    public void testJobControlResponseContainsMessage() {
        String repositoryId = "test-repo";
        Response response = jobControlResource.pauseJobs(repositoryId, adminRequest);
        JobControlResponse jobResponse = (JobControlResponse) response.getEntity();
        assertNotNull(jobResponse.getMessage());
    }

    @Test
    public void testNonAdminGetsForbidden() {
        HttpServletRequest nonAdminRequest = mock(HttpServletRequest.class);
        when(nonAdminRequest.getAttribute("CallContext")).thenReturn(null);
        Response response = jobControlResource.getJobStatus("test-repo", nonAdminRequest);
        assertEquals(403, response.getStatus());
    }
}
