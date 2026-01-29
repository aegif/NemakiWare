package jp.aegif.nemaki.api.v1.jmx;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

public class JobManagerMBeanTest {

    private JobManager jobManager;

    @Before
    public void setUp() {
        jobManager = new JobManager();
    }

    @Test
    public void testGetPendingJobsReturnsNonNegative() {
        int pendingJobs = jobManager.getPendingJobs();
        assertTrue(pendingJobs >= 0);
    }

    @Test
    public void testGetRunningJobsReturnsNonNegative() {
        int runningJobs = jobManager.getRunningJobs();
        assertTrue(runningJobs >= 0);
    }

    @Test
    public void testPauseDoesNotThrow() {
        try {
            jobManager.pause();
        } catch (Exception e) {
            fail("pause should not throw exception: " + e.getMessage());
        }
    }

    @Test
    public void testResumeDoesNotThrow() {
        try {
            jobManager.resume();
        } catch (Exception e) {
            fail("resume should not throw exception: " + e.getMessage());
        }
    }

    @Test
    public void testIsPausedReturnsFalseByDefault() {
        assertFalse(jobManager.isPaused());
    }

    @Test
    public void testPauseSetsIsPausedToTrue() {
        jobManager.pause();
        assertTrue(jobManager.isPaused());
    }

    @Test
    public void testResumeSetsIsPausedToFalse() {
        jobManager.pause();
        jobManager.resume();
        assertFalse(jobManager.isPaused());
    }
}
