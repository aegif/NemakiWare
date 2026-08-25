package jp.aegif.nemaki.archive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.GregorianCalendar;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RetentionMigrationLog and RetentionJobResult.
 *
 * Tests cover:
 * - computeStatus() logic: SUCCESS, PARTIAL_FAILURE, FAILURE
 * - RetentionJobResult counter arithmetic and immutable skippedDocumentIds
 * - Edge case: processed=0 returns SUCCESS
 */
public class RetentionMigrationLogTest {

    // ========================================================================
    // computeStatus() tests
    // ========================================================================

    @Test
    @DisplayName("processed > 0, failed = 0 → SUCCESS")
    public void testComputeStatus_allSucceeded() {
        RetentionMigrationLog log = new RetentionMigrationLog("cold-move", "bedroom");
        log.setProcessed(10);
        log.setSucceeded(10);
        log.setFailed(0);

        assertEquals("SUCCESS", log.computeStatus());
    }

    @Test
    @DisplayName("failed > 0, succeeded > 0 → PARTIAL_FAILURE")
    public void testComputeStatus_partialFailure() {
        RetentionMigrationLog log = new RetentionMigrationLog("cold-move", "bedroom");
        log.setProcessed(10);
        log.setSucceeded(7);
        log.setFailed(3);

        assertEquals("PARTIAL_FAILURE", log.computeStatus());
    }

    @Test
    @DisplayName("failed > 0, succeeded = 0 → FAILURE")
    public void testComputeStatus_totalFailure() {
        RetentionMigrationLog log = new RetentionMigrationLog("cold-move", "bedroom");
        log.setProcessed(5);
        log.setSucceeded(0);
        log.setFailed(5);

        assertEquals("FAILURE", log.computeStatus());
    }

    @Test
    @DisplayName("processed = 0 → SUCCESS (no work = no failure)")
    public void testComputeStatus_nothingProcessed() {
        RetentionMigrationLog log = new RetentionMigrationLog("local-archive", "bedroom");
        log.setProcessed(0);
        log.setSucceeded(0);
        log.setFailed(0);

        assertEquals("SUCCESS", log.computeStatus());
    }

    @Test
    @DisplayName("failed = 1, succeeded = 1 → PARTIAL_FAILURE (最小ケース)")
    public void testComputeStatus_minimalPartialFailure() {
        RetentionMigrationLog log = new RetentionMigrationLog("cold-move", "bedroom");
        log.setProcessed(2);
        log.setSucceeded(1);
        log.setFailed(1);

        assertEquals("PARTIAL_FAILURE", log.computeStatus());
    }

    @Test
    @DisplayName("failed = 1, succeeded = 0 → FAILURE (最小ケース)")
    public void testComputeStatus_singleFailure() {
        RetentionMigrationLog log = new RetentionMigrationLog("cold-move", "bedroom");
        log.setProcessed(1);
        log.setSucceeded(0);
        log.setFailed(1);

        assertEquals("FAILURE", log.computeStatus());
    }

    // ========================================================================
    // Constructor and field tests
    // ========================================================================

    @Test
    @DisplayName("2引数コンストラクタで jobType と repositoryId が設定される")
    public void testConstructorSetsFields() {
        RetentionMigrationLog log = new RetentionMigrationLog("local-archive", "canopy");

        assertEquals("local-archive", log.getJobType());
        assertEquals("canopy", log.getRepositoryId());
    }

    @Test
    @DisplayName("デフォルトコンストラクタで全フィールドが null/0")
    public void testDefaultConstructor() {
        RetentionMigrationLog log = new RetentionMigrationLog();

        assertNull(log.getId());
        assertNull(log.getJobType());
        assertNull(log.getRepositoryId());
        assertNull(log.getStartedAt());
        assertNull(log.getCompletedAt());
        assertEquals(0, log.getProcessed());
        assertEquals(0, log.getSucceeded());
        assertEquals(0, log.getFailed());
        assertNull(log.getStatus());
        assertNull(log.getDetails());
    }

    @Test
    @DisplayName("startedAt/completedAt のセット・ゲット")
    public void testTimestampFields() {
        RetentionMigrationLog log = new RetentionMigrationLog();
        GregorianCalendar start = new GregorianCalendar(2026, 2, 14, 3, 0, 0);
        GregorianCalendar end = new GregorianCalendar(2026, 2, 14, 3, 5, 0);

        log.setStartedAt(start);
        log.setCompletedAt(end);

        assertEquals(start, log.getStartedAt());
        assertEquals(end, log.getCompletedAt());
    }

    // ========================================================================
    // RetentionJobResult tests
    // ========================================================================

    @Test
    @DisplayName("RetentionJobResult: カウンタが正しくインクリメントされる")
    public void testJobResult_counterArithmetic() {
        RetentionJobResult result = new RetentionJobResult("local-archive", "bedroom");

        result.incrementProcessed();
        result.incrementProcessed();
        result.incrementProcessed();
        result.incrementSucceeded();
        result.incrementSucceeded();
        result.incrementFailed();

        assertEquals(3, result.getProcessed());
        assertEquals(2, result.getSucceeded());
        assertEquals(1, result.getFailed());
        assertEquals(0, result.getSkipped());
    }

    @Test
    @DisplayName("RetentionJobResult: skippedDocumentIds は不変リストで返される")
    public void testJobResult_skippedDocumentIdsImmutable() {
        RetentionJobResult result = new RetentionJobResult("local-archive", "bedroom");
        result.addSkippedDocumentId("doc-001");
        result.addSkippedDocumentId("doc-002");

        assertEquals(2, result.getSkippedDocumentIds().size());
        assertTrue(result.getSkippedDocumentIds().contains("doc-001"));
        assertTrue(result.getSkippedDocumentIds().contains("doc-002"));

        // Should throw UnsupportedOperationException
        assertThrows(UnsupportedOperationException.class, () -> {
            result.getSkippedDocumentIds().add("doc-003");
        });
    }

    @Test
    @DisplayName("RetentionJobResult: toString() にジョブ名とリポジトリが含まれる")
    public void testJobResult_toStringContainsInfo() {
        RetentionJobResult result = new RetentionJobResult("cold-move", "canopy");
        result.incrementProcessed();
        result.incrementSucceeded();

        String str = result.toString();
        assertTrue(str.contains("cold-move"), "toString should contain job name");
        assertTrue(str.contains("canopy"), "toString should contain repository id");
        assertTrue(str.contains("processed=1"), "toString should contain processed count");
        assertTrue(str.contains("succeeded=1"), "toString should contain succeeded count");
    }

    @Test
    @DisplayName("RetentionJobResult: skipped + skippedDocumentIds の整合性")
    public void testJobResult_skippedCountAndIdsConsistency() {
        RetentionJobResult result = new RetentionJobResult("local-archive", "bedroom");
        result.incrementSkipped();
        result.addSkippedDocumentId("doc-locked-001");
        result.incrementSkipped();
        result.addSkippedDocumentId("doc-locked-002");

        assertEquals(2, result.getSkipped());
        assertEquals(2, result.getSkippedDocumentIds().size());
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("a run that could not record any disposition is NOT a success")
    void aRefusedRunIsNotASuccess() {
        // The trap this repository keeps relearning: a status that reads "everything is fine"
        // for a state that needs an operator. computeStatus keyed on `failed` only, and a
        // refused disposition is not a failure — the job ran correctly and disposed of nothing,
        // because it could not record doing so. Every archive refused, and the answer was
        // SUCCESS with a skip count nobody reads.
        RetentionMigrationLog log = new RetentionMigrationLog("cold-move", "bedroom");
        log.setProcessed(5);
        log.setSucceeded(0);
        log.setFailed(0);
        log.setRefused(5);

        org.junit.jupiter.api.Assertions.assertNotEquals("SUCCESS", log.computeStatus(),
                "a run in which nothing could be disposed of reported SUCCESS; retention has "
                        + "not been running and the log says it has");
        org.junit.jupiter.api.Assertions.assertEquals("REFUSED_NOT_RECORDABLE",
                log.computeStatus());
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("an ordinary clean run is still a success — the control")
    void aCleanRunIsStillASuccess() {
        RetentionMigrationLog log = new RetentionMigrationLog("cold-move", "bedroom");
        log.setProcessed(5);
        log.setSucceeded(5);
        log.setFailed(0);

        org.junit.jupiter.api.Assertions.assertEquals("SUCCESS", log.computeStatus());
    }
}
