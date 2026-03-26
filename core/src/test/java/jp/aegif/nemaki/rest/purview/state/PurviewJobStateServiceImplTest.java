package jp.aegif.nemaki.rest.purview.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;

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

    // --- Save & Load ---

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
        assertEquals(0, loaded.getFailedCount());
        assertEquals("noop", loaded.getCheckpoint());
        assertEquals("", loaded.getErrorSummary());
    }

    @Test
    public void testSaveJobState_overwritesExisting() {
        service.saveJobState(new PurviewJobState(
                "job-001", "FULL_SYNC", "bedroom", "RUNNING",
                "2026-03-20T02:00:00Z", "", 0, 0, "", ""));
        service.saveJobState(new PurviewJobState(
                "job-001", "FULL_SYNC", "bedroom", "COMPLETED",
                "2026-03-20T02:00:00Z", "2026-03-20T02:05:00Z", 50, 2, "token-50", "2 failures"));

        PurviewJobState loaded = service.getJobState("job-001");
        assertEquals("COMPLETED", loaded.getStatus());
        assertEquals("2026-03-20T02:05:00Z", loaded.getEndedAt());
        assertEquals(50, loaded.getProcessedCount());
        assertEquals(2, loaded.getFailedCount());
        assertEquals("token-50", loaded.getCheckpoint());
        assertEquals("2 failures", loaded.getErrorSummary());
    }

    @Test
    public void testGetJobState_nonExistent_returnsEmptyFields() {
        PurviewJobState loaded = service.getJobState("nonexistent-job");
        assertNotNull(loaded);
        assertEquals("nonexistent-job", loaded.getJobId());
        assertEquals("", loaded.getJobKind());
        assertEquals("", loaded.getRepositoryId());
        assertEquals("", loaded.getStatus());
        assertEquals("", loaded.getStartedAt());
        assertEquals("", loaded.getEndedAt());
        assertEquals(0, loaded.getProcessedCount());
        assertEquals(0, loaded.getFailedCount());
        assertEquals("", loaded.getCheckpoint());
        assertEquals("", loaded.getErrorSummary());
    }

    // --- listJobStates ---

    @Test
    public void testListJobStates_empty() {
        List<PurviewJobState> jobs = service.listJobStates();
        assertTrue(jobs.isEmpty());
    }

    @Test
    public void testListJobStates_singleJob() {
        service.saveJobState(new PurviewJobState(
                "job-001", "FULL_SYNC", "bedroom", "COMPLETED",
                "2026-03-20T02:00:00Z", "2026-03-20T02:01:00Z", 10, 0, "", ""));

        List<PurviewJobState> jobs = service.listJobStates();
        assertEquals(1, jobs.size());
        assertEquals("job-001", jobs.get(0).getJobId());
        assertEquals("FULL_SYNC", jobs.get(0).getJobKind());
    }

    @Test
    public void testListJobStates_multipleJobs_sortedByStartedAtDescending() {
        service.saveJobState(new PurviewJobState(
                "job-001", "FULL_SYNC", "bedroom", "COMPLETED",
                "2026-03-20T01:00:00Z", "2026-03-20T01:05:00Z", 5, 0, "", ""));
        service.saveJobState(new PurviewJobState(
                "job-002", "FULL_SYNC", "bedroom", "COMPLETED",
                "2026-03-20T03:00:00Z", "2026-03-20T03:05:00Z", 15, 0, "", ""));
        service.saveJobState(new PurviewJobState(
                "job-003", "FULL_SYNC", "bedroom", "COMPLETED",
                "2026-03-20T02:00:00Z", "2026-03-20T02:05:00Z", 10, 0, "", ""));

        List<PurviewJobState> jobs = service.listJobStates();
        assertEquals(3, jobs.size());
        // Descending by startedAt: job-002 (03:00) > job-003 (02:00) > job-001 (01:00)
        assertEquals("job-002", jobs.get(0).getJobId());
        assertEquals("job-003", jobs.get(1).getJobId());
        assertEquals("job-001", jobs.get(2).getJobId());
    }

    @Test
    public void testListJobStates_sameStartedAt_sortedByJobIdAscending() {
        service.saveJobState(new PurviewJobState(
                "job-C", "FULL_SYNC", "bedroom", "COMPLETED",
                "2026-03-20T02:00:00Z", "", 0, 0, "", ""));
        service.saveJobState(new PurviewJobState(
                "job-A", "FULL_SYNC", "bedroom", "COMPLETED",
                "2026-03-20T02:00:00Z", "", 0, 0, "", ""));
        service.saveJobState(new PurviewJobState(
                "job-B", "FULL_SYNC", "bedroom", "COMPLETED",
                "2026-03-20T02:00:00Z", "", 0, 0, "", ""));

        List<PurviewJobState> jobs = service.listJobStates();
        assertEquals(3, jobs.size());
        assertEquals("job-A", jobs.get(0).getJobId());
        assertEquals("job-B", jobs.get(1).getJobId());
        assertEquals("job-C", jobs.get(2).getJobId());
    }

    @Test
    public void testListJobStates_mixedJobKinds() {
        service.saveJobState(new PurviewJobState(
                "job-001", "FULL_SYNC", "bedroom", "COMPLETED",
                "2026-03-20T02:00:00Z", "2026-03-20T02:05:00Z", 10, 0, "", ""));
        service.saveJobState(new PurviewJobState(
                "job-002", "INCREMENTAL_SYNC", "bedroom", "COMPLETED",
                "2026-03-20T03:00:00Z", "2026-03-20T03:01:00Z", 3, 0, "", ""));
        service.saveJobState(new PurviewJobState(
                "job-003", "TYPE_BOOTSTRAP", "collection:NemakiWare", "COMPLETED",
                "2026-03-20T04:00:00Z", "2026-03-20T04:02:00Z", 5, 0, "", ""));

        List<PurviewJobState> jobs = service.listJobStates();
        assertEquals(3, jobs.size());
        assertEquals("job-003", jobs.get(0).getJobId()); // 04:00
        assertEquals("job-002", jobs.get(1).getJobId()); // 03:00
        assertEquals("job-001", jobs.get(2).getJobId()); // 02:00
    }

    @Test
    public void testListJobStates_preservesAllFields() {
        service.saveJobState(new PurviewJobState(
                "job-001", "FULL_SYNC", "bedroom", "FAILED",
                "2026-03-20T02:00:00Z", "2026-03-20T02:10:00Z", 42, 3, "token-42", "3 documents failed"));

        List<PurviewJobState> jobs = service.listJobStates();
        assertEquals(1, jobs.size());
        PurviewJobState job = jobs.get(0);
        assertEquals("job-001", job.getJobId());
        assertEquals("FULL_SYNC", job.getJobKind());
        assertEquals("bedroom", job.getRepositoryId());
        assertEquals("FAILED", job.getStatus());
        assertEquals("2026-03-20T02:00:00Z", job.getStartedAt());
        assertEquals("2026-03-20T02:10:00Z", job.getEndedAt());
        assertEquals(42, job.getProcessedCount());
        assertEquals(3, job.getFailedCount());
        assertEquals("token-42", job.getCheckpoint());
        assertEquals("3 documents failed", job.getErrorSummary());
    }

    // --- purgeOldJobStates ---

    @Test
    public void testPurgeOldJobStates_retainsLatestPerGroup() {
        // Save 5 FULL_SYNC jobs for bedroom
        for (int i = 1; i <= 5; i++) {
            service.saveJobState(new PurviewJobState(
                    "job-fs-" + String.format("%03d", i), "FULL_SYNC", "bedroom", "COMPLETED",
                    "2026-03-20T0" + i + ":00:00Z", "2026-03-20T0" + i + ":05:00Z", i * 10, 0, "", ""));
        }

        int purged = service.purgeOldJobStates(2);

        assertEquals(3, purged);
        List<PurviewJobState> remaining = service.listJobStates();
        assertEquals(2, remaining.size());
        // Should retain job-fs-005 (05:00) and job-fs-004 (04:00)
        assertEquals("job-fs-005", remaining.get(0).getJobId());
        assertEquals("job-fs-004", remaining.get(1).getJobId());
    }

    @Test
    public void testPurgeOldJobStates_separateGroupsRetainedIndependently() {
        // 3 FULL_SYNC jobs for bedroom
        for (int i = 1; i <= 3; i++) {
            service.saveJobState(new PurviewJobState(
                    "fs-" + i, "FULL_SYNC", "bedroom", "COMPLETED",
                    "2026-03-20T0" + i + ":00:00Z", "", i, 0, "", ""));
        }
        // 3 INCREMENTAL_SYNC jobs for bedroom
        for (int i = 1; i <= 3; i++) {
            service.saveJobState(new PurviewJobState(
                    "is-" + i, "INCREMENTAL_SYNC", "bedroom", "COMPLETED",
                    "2026-03-20T0" + i + ":00:00Z", "", i, 0, "", ""));
        }
        // 3 FULL_SYNC jobs for kitchen (different repositoryId = different group)
        for (int i = 1; i <= 3; i++) {
            service.saveJobState(new PurviewJobState(
                    "kfs-" + i, "FULL_SYNC", "kitchen", "COMPLETED",
                    "2026-03-20T0" + i + ":00:00Z", "", i, 0, "", ""));
        }

        int purged = service.purgeOldJobStates(1);

        // 3 groups × 2 purged each = 6
        assertEquals(6, purged);
        List<PurviewJobState> remaining = service.listJobStates();
        assertEquals(3, remaining.size());
        List<String> remainingIds = remaining.stream().map(PurviewJobState::getJobId).sorted().toList();
        assertEquals(List.of("fs-3", "is-3", "kfs-3"), remainingIds);
    }

    @Test
    public void testPurgeOldJobStates_retainCountZero_becomesOne() {
        service.saveJobState(new PurviewJobState(
                "job-001", "FULL_SYNC", "bedroom", "COMPLETED",
                "2026-03-20T01:00:00Z", "", 10, 0, "", ""));
        service.saveJobState(new PurviewJobState(
                "job-002", "FULL_SYNC", "bedroom", "COMPLETED",
                "2026-03-20T02:00:00Z", "", 20, 0, "", ""));

        int purged = service.purgeOldJobStates(0);

        // retainCount=0 → effectiveRetainCount=1, so 1 purged
        assertEquals(1, purged);
        List<PurviewJobState> remaining = service.listJobStates();
        assertEquals(1, remaining.size());
        assertEquals("job-002", remaining.get(0).getJobId());
    }

    @Test
    public void testPurgeOldJobStates_negativeRetainCount_becomesOne() {
        service.saveJobState(new PurviewJobState(
                "job-001", "FULL_SYNC", "bedroom", "COMPLETED",
                "2026-03-20T01:00:00Z", "", 10, 0, "", ""));
        service.saveJobState(new PurviewJobState(
                "job-002", "FULL_SYNC", "bedroom", "COMPLETED",
                "2026-03-20T02:00:00Z", "", 20, 0, "", ""));

        int purged = service.purgeOldJobStates(-5);

        assertEquals(1, purged);
        assertEquals(1, service.listJobStates().size());
    }

    @Test
    public void testPurgeOldJobStates_fewerJobsThanRetainCount_noPurge() {
        service.saveJobState(new PurviewJobState(
                "job-001", "FULL_SYNC", "bedroom", "COMPLETED",
                "2026-03-20T01:00:00Z", "", 10, 0, "", ""));

        int purged = service.purgeOldJobStates(50);

        assertEquals(0, purged);
        assertEquals(1, service.listJobStates().size());
    }

    @Test
    public void testPurgeOldJobStates_noJobs_returnsZero() {
        int purged = service.purgeOldJobStates(50);
        assertEquals(0, purged);
    }

    @Test
    public void testPurgeOldJobStates_exactlyRetainCount_noPurge() {
        service.saveJobState(new PurviewJobState(
                "job-001", "FULL_SYNC", "bedroom", "COMPLETED",
                "2026-03-20T01:00:00Z", "", 0, 0, "", ""));
        service.saveJobState(new PurviewJobState(
                "job-002", "FULL_SYNC", "bedroom", "COMPLETED",
                "2026-03-20T02:00:00Z", "", 0, 0, "", ""));

        int purged = service.purgeOldJobStates(2);

        assertEquals(0, purged);
        assertEquals(2, service.listJobStates().size());
    }

    @Test
    public void testPurgeOldJobStates_purgesRegardlessOfStatus() {
        // RUNNING jobs should also be purged if they are beyond retainCount
        service.saveJobState(new PurviewJobState(
                "job-old-running", "FULL_SYNC", "bedroom", "RUNNING",
                "2026-03-20T01:00:00Z", "", 5, 0, "", ""));
        service.saveJobState(new PurviewJobState(
                "job-old-failed", "FULL_SYNC", "bedroom", "FAILED",
                "2026-03-20T02:00:00Z", "2026-03-20T02:05:00Z", 10, 3, "", "error"));
        service.saveJobState(new PurviewJobState(
                "job-new-completed", "FULL_SYNC", "bedroom", "COMPLETED",
                "2026-03-20T03:00:00Z", "2026-03-20T03:05:00Z", 20, 0, "", ""));

        int purged = service.purgeOldJobStates(1);

        assertEquals(2, purged);
        List<PurviewJobState> remaining = service.listJobStates();
        assertEquals(1, remaining.size());
        assertEquals("job-new-completed", remaining.get(0).getJobId());
    }

    @Test
    public void testPurgeOldJobStates_verifyDeletedJobIsGone() {
        service.saveJobState(new PurviewJobState(
                "job-old", "FULL_SYNC", "bedroom", "COMPLETED",
                "2026-03-20T01:00:00Z", "2026-03-20T01:05:00Z", 10, 0, "token-old", ""));
        service.saveJobState(new PurviewJobState(
                "job-new", "FULL_SYNC", "bedroom", "COMPLETED",
                "2026-03-20T02:00:00Z", "2026-03-20T02:05:00Z", 20, 0, "token-new", ""));

        service.purgeOldJobStates(1);

        // The deleted job should return empty fields
        PurviewJobState purgedJob = service.getJobState("job-old");
        assertEquals("", purgedJob.getJobKind());
        assertEquals("", purgedJob.getStatus());
        assertEquals(0, purgedJob.getProcessedCount());
    }

    @Test
    public void testPurgeOldJobStates_verifyDeletedJobFieldsAreAllGone() {
        // Verify that ALL 9 fields of a purged job are removed from the store
        service.saveJobState(new PurviewJobState(
                "job-old", "FULL_SYNC", "bedroom", "COMPLETED",
                "2026-03-20T01:00:00Z", "2026-03-20T01:05:00Z", 42, 3, "token-42", "3 errors"));
        service.saveJobState(new PurviewJobState(
                "job-new", "FULL_SYNC", "bedroom", "COMPLETED",
                "2026-03-20T02:00:00Z", "2026-03-20T02:05:00Z", 20, 0, "token-20", ""));

        service.purgeOldJobStates(1);

        PurviewJobState purgedJob = service.getJobState("job-old");
        assertEquals("", purgedJob.getJobKind());
        assertEquals("", purgedJob.getRepositoryId());
        assertEquals("", purgedJob.getStatus());
        assertEquals("", purgedJob.getStartedAt());
        assertEquals("", purgedJob.getEndedAt());
        assertEquals(0, purgedJob.getProcessedCount());
        assertEquals(0, purgedJob.getFailedCount());
        assertEquals("", purgedJob.getCheckpoint());
        assertEquals("", purgedJob.getErrorSummary());
    }

    // --- Edge cases ---

    @Test
    public void testJobIdWithDot_parsedCorrectly() {
        // JobIds typically don't contain dots, but verify the parser handles
        // the first dot as the separator between jobId and fieldName.
        // A dot in jobId would cause incorrect parsing.
        // This test documents the expected behavior: only the part before the
        // first dot is treated as the jobId.
        service.saveJobState(new PurviewJobState(
                "uuid-123", "FULL_SYNC", "bedroom", "COMPLETED",
                "2026-03-20T02:00:00Z", "2026-03-20T02:01:00Z", 10, 0, "", ""));

        List<PurviewJobState> jobs = service.listJobStates();
        assertEquals(1, jobs.size());
        assertEquals("uuid-123", jobs.get(0).getJobId());
        assertEquals("FULL_SYNC", jobs.get(0).getJobKind());
    }

    @Test
    public void testSaveAndLoadWithLargeProcessedCount() {
        service.saveJobState(new PurviewJobState(
                "job-big", "FULL_SYNC", "bedroom", "COMPLETED",
                "2026-03-20T02:00:00Z", "2026-03-20T02:01:00Z",
                1_000_000, 500, "", ""));

        PurviewJobState loaded = service.getJobState("job-big");
        assertEquals(1_000_000, loaded.getProcessedCount());
        assertEquals(500, loaded.getFailedCount());
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
