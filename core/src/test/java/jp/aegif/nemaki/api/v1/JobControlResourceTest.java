package jp.aegif.nemaki.api.v1;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

import jp.aegif.nemaki.api.v1.model.response.JobControlResponse;
import jp.aegif.nemaki.api.v1.resource.JobControlResource;

import jakarta.ws.rs.core.Response;

public class JobControlResourceTest {

    private JobControlResource jobControlResource;

    @Before
    public void setUp() {
        jobControlResource = new JobControlResource();
    }

    @Test
    public void testPauseJobsReturns200() {
        String repositoryId = "test-repo";
        Response response = jobControlResource.pauseJobs(repositoryId);
        assertEquals(200, response.getStatus());
    }

    @Test
    public void testResumeJobsReturns200() {
        String repositoryId = "test-repo";
        Response response = jobControlResource.resumeJobs(repositoryId);
        assertEquals(200, response.getStatus());
    }

    @Test
    public void testPauseJobsReturnsCorrectStatus() {
        String repositoryId = "test-repo";
        Response response = jobControlResource.pauseJobs(repositoryId);
        JobControlResponse jobResponse = (JobControlResponse) response.getEntity();
        assertEquals("paused", jobResponse.getStatus());
    }

    @Test
    public void testResumeJobsReturnsCorrectStatus() {
        String repositoryId = "test-repo";
        Response response = jobControlResource.resumeJobs(repositoryId);
        JobControlResponse jobResponse = (JobControlResponse) response.getEntity();
        assertEquals("running", jobResponse.getStatus());
    }

    @Test
    public void testGetJobStatusReturns200() {
        String repositoryId = "test-repo";
        Response response = jobControlResource.getJobStatus(repositoryId);
        assertEquals(200, response.getStatus());
    }

    @Test
    public void testGetJobStatusContainsPendingCount() {
        String repositoryId = "test-repo";
        Response response = jobControlResource.getJobStatus(repositoryId);
        JobControlResponse jobResponse = (JobControlResponse) response.getEntity();
        assertTrue(jobResponse.getPendingJobs() >= 0);
    }

    @Test
    public void testGetJobStatusContainsRunningCount() {
        String repositoryId = "test-repo";
        Response response = jobControlResource.getJobStatus(repositoryId);
        JobControlResponse jobResponse = (JobControlResponse) response.getEntity();
        assertTrue(jobResponse.getRunningJobs() >= 0);
    }

    @Test
    public void testJobControlResponseContainsTimestamp() {
        String repositoryId = "test-repo";
        Response response = jobControlResource.getJobStatus(repositoryId);
        JobControlResponse jobResponse = (JobControlResponse) response.getEntity();
        assertNotNull(jobResponse.getTimestamp());
    }

    @Test
    public void testJobControlResponseContainsMessage() {
        String repositoryId = "test-repo";
        Response response = jobControlResource.pauseJobs(repositoryId);
        JobControlResponse jobResponse = (JobControlResponse) response.getEntity();
        assertNotNull(jobResponse.getMessage());
    }
}
