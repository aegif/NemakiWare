package jp.aegif.nemaki.rest.purview.journal;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CouchLineageDeadLetterStore}.
 */
class CouchLineageDeadLetterStoreTest {

    private CouchLineageDeadLetterStore deadLetterStore;
    private CloudantClientWrapper mockClient;
    private CouchLineageJournalStore mockJournalStore;
    private LineageConfig mockConfig;

    @BeforeEach
    void setUp() throws Exception {
        deadLetterStore = new CouchLineageDeadLetterStore();
        mockClient = mock(CloudantClientWrapper.class);
        mockJournalStore = mock(CouchLineageJournalStore.class);
        mockConfig = mock(LineageConfig.class);

        when(mockJournalStore.isActive()).thenReturn(true);
        when(mockJournalStore.getLineageClient()).thenReturn(mockClient);

        setField(deadLetterStore, "journalStore", mockJournalStore);
        setField(deadLetterStore, "lineageConfig", mockConfig);
    }

    // ==================== record ====================

    @Test
    void record_createsDocWhenNotExists() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .addInputObject("bedroom", "doc-1")
                .addOutputObject("bedroom", "arc-1")
                .build();

        when(mockClient.exists("lineage_dl:" + event.eventId())).thenReturn(false);

        deadLetterStore.record(event, "timeout");

        verify(mockClient).create(argThat(doc -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) doc;
            @SuppressWarnings("unchecked")
            Map<String, String> origTargets = (Map<String, String>) m.get("originalPublishStatusByTarget");
            return "lineage_dead_letter".equals(m.get("type"))
                    && event.eventId().equals(m.get("eventId"))
                    && "bedroom".equals(m.get("repositoryId"))
                    && "timeout".equals(m.get("reason"))
                    && Boolean.FALSE.equals(m.get("replayed"))
                    && origTargets != null;
        }));
    }

    @Test
    void record_preservesExactPublishStatusValues() {
        // Mixed status: purview FAILED, atlas PUBLISHED — both must be persisted verbatim
        Map<String, LineagePublishStatus> mixedTargets = new LinkedHashMap<>();
        mixedTargets.put("purview", LineagePublishStatus.FAILED);
        mixedTargets.put("atlas", LineagePublishStatus.PUBLISHED);

        LineageEvent event = new LineageEvent(
                1, "evt-rec-1", "key-rec-1", 1, "2026-01-15T10:00:00Z", "bedroom",
                LineageProcessType.ARCHIVE_LOCAL,
                List.of("nemaki://bedroom/objects/doc-1"),
                List.of("nemaki://bedroom/archives/arc-1"),
                "", "", 1, Map.of(),
                mixedTargets);

        when(mockClient.exists("lineage_dl:evt-rec-1")).thenReturn(false);

        deadLetterStore.record(event, "projection-error");

        verify(mockClient).create(argThat(doc -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) doc;
            @SuppressWarnings("unchecked")
            Map<String, String> origTargets = (Map<String, String>) m.get("originalPublishStatusByTarget");
            // Exact values must be preserved — not just non-null
            return origTargets != null
                    && origTargets.size() == 2
                    && "FAILED".equals(origTargets.get("purview"))
                    && "PUBLISHED".equals(origTargets.get("atlas"));
        }));
    }

    @Test
    void record_preservesSingleFailedTarget() {
        Map<String, LineagePublishStatus> singleTarget = new LinkedHashMap<>();
        singleTarget.put("atlas", LineagePublishStatus.FAILED);

        LineageEvent event = new LineageEvent(
                1, "evt-rec-2", "key-rec-2", 1, "2026-01-15T10:00:00Z", "bedroom",
                LineageProcessType.IMPORT_UPLOADED,
                List.of("nemaki://bedroom/objects/doc-2"),
                List.of(),
                "", "", 1, Map.of(),
                singleTarget);

        when(mockClient.exists("lineage_dl:evt-rec-2")).thenReturn(false);

        deadLetterStore.record(event, "connection-refused");

        verify(mockClient).create(argThat(doc -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) doc;
            @SuppressWarnings("unchecked")
            Map<String, String> origTargets = (Map<String, String>) m.get("originalPublishStatusByTarget");
            return origTargets != null
                    && origTargets.size() == 1
                    && "FAILED".equals(origTargets.get("atlas"));
        }));
    }

    @Test
    void record_skipsDuplicateDoc() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .build();

        when(mockClient.exists("lineage_dl:" + event.eventId())).thenReturn(true);

        deadLetterStore.record(event, "timeout");

        verify(mockClient, never()).create(any());
    }

    @Test
    void record_handlesNullClient() {
        when(mockJournalStore.isActive()).thenReturn(false);
        when(mockJournalStore.getLineageClient()).thenReturn(null);

        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .build();

        // Should not throw
        deadLetterStore.record(event, "timeout");
        verify(mockClient, never()).create(any());
    }

    // ==================== findByEventId ====================

    @SuppressWarnings("unchecked")
    @Test
    void findByEventId_returnsDocWithoutRev() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("_id", "lineage_dl:evt-1");
        doc.put("_rev", "1-abc");
        doc.put("eventId", "evt-1");
        doc.put("reason", "timeout");

        doReturn(doc).when(mockClient).get(eq(Map.class), eq("lineage_dl:evt-1"), isNull());

        Map<String, Object> result = deadLetterStore.findByEventId("evt-1");

        assertNotNull(result);
        assertEquals("evt-1", result.get("eventId"));
        assertNull(result.get("_rev"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void findByEventId_returnsNullWhenNotFound() {
        doReturn(null).when(mockClient).get(eq(Map.class), eq("lineage_dl:nonexistent"), isNull());

        Map<String, Object> result = deadLetterStore.findByEventId("nonexistent");
        assertNull(result);
    }

    // ==================== replay ====================

    @SuppressWarnings("unchecked")
    @Test
    void replay_resetsStatusWhenOriginalExists() {
        // Dead-letter record
        Map<String, Object> dlDoc = new LinkedHashMap<>();
        dlDoc.put("_id", "lineage_dl:evt-1");
        dlDoc.put("_rev", "1-abc");
        dlDoc.put("eventId", "evt-1");
        dlDoc.put("replayed", false);

        doReturn(dlDoc).when(mockClient).get(eq(Map.class), eq("lineage_dl:evt-1"), isNull());

        // Original journal event exists with FAILED target
        LineageEvent existing = new LineageEvent(
                1, "evt-1", "key-1", 1, "2026-01-15T10:00:00Z", "bedroom",
                LineageProcessType.ARCHIVE_LOCAL,
                List.of("nemaki://bedroom/objects/doc-1"),
                List.of("nemaki://bedroom/archives/arc-1"),
                "", "", 1, Map.of(),
                Map.of("purview", LineagePublishStatus.FAILED));

        LineageJournalStore targetStore = mock(LineageJournalStore.class);
        when(targetStore.findByEventId("evt-1")).thenReturn(existing);
        when(targetStore.updatePublishStatus("evt-1", "purview", LineagePublishStatus.PENDING)).thenReturn(1);

        boolean result = deadLetterStore.replay("evt-1", targetStore);

        assertTrue(result);
        // Should reset FAILED target to PENDING, not call append
        verify(targetStore).updatePublishStatus("evt-1", "purview", LineagePublishStatus.PENDING);
        verify(targetStore, never()).append(any(LineageEvent.class));
        // Dead-letter marked as replayed
        verify(mockClient).update(argThat(updated -> {
            Map<String, Object> m = (Map<String, Object>) updated;
            return Boolean.TRUE.equals(m.get("replayed"));
        }));
    }

    @SuppressWarnings("unchecked")
    @Test
    void replay_resetsOnlyFailedTargetsWhenOriginalSurvives_mixedStatus() {
        // Original journal event survives with mixed targets:
        // purview=DISCARDED (should reset to PENDING), atlas=PUBLISHED (must stay untouched)
        Map<String, Object> dlDoc = new LinkedHashMap<>();
        dlDoc.put("_id", "lineage_dl:evt-surv-1");
        dlDoc.put("_rev", "1-abc");
        dlDoc.put("eventId", "evt-surv-1");
        dlDoc.put("replayed", false);

        doReturn(dlDoc).when(mockClient).get(eq(Map.class), eq("lineage_dl:evt-surv-1"), isNull());

        // Surviving original with mixed status
        Map<String, LineagePublishStatus> mixedStatus = new LinkedHashMap<>();
        mixedStatus.put("purview", LineagePublishStatus.DISCARDED);
        mixedStatus.put("atlas", LineagePublishStatus.PUBLISHED);

        LineageEvent existing = new LineageEvent(
                1, "evt-surv-1", "key-surv-1", 1, "2026-01-15T10:00:00Z", "bedroom",
                LineageProcessType.ARCHIVE_LOCAL,
                List.of("nemaki://bedroom/objects/doc-1"),
                List.of("nemaki://bedroom/archives/arc-1"),
                "", "", 1, Map.of(),
                mixedStatus);

        LineageJournalStore targetStore = mock(LineageJournalStore.class);
        when(targetStore.findByEventId("evt-surv-1")).thenReturn(existing);
        when(targetStore.updatePublishStatus("evt-surv-1", "purview", LineagePublishStatus.PENDING)).thenReturn(1);

        boolean result = deadLetterStore.replay("evt-surv-1", targetStore);

        assertTrue(result);
        // DISCARDED target must be reset to PENDING
        verify(targetStore).updatePublishStatus("evt-surv-1", "purview", LineagePublishStatus.PENDING);
        // PUBLISHED target must NOT be touched
        verify(targetStore, never()).updatePublishStatus(eq("evt-surv-1"), eq("atlas"), any());
        // Should NOT re-append since original exists
        verify(targetStore, never()).append(any(LineageEvent.class));
        // Dead-letter marked as replayed
        verify(mockClient).update(argThat(updated -> {
            Map<String, Object> m = (Map<String, Object>) updated;
            return Boolean.TRUE.equals(m.get("replayed"));
        }));
    }

    @SuppressWarnings("unchecked")
    @Test
    void replay_resetsMultipleFailedTargetsWhenOriginalSurvives() {
        // Original survives with purview=FAILED, atlas=FAILED, dataplex=PUBLISHED
        Map<String, Object> dlDoc = new LinkedHashMap<>();
        dlDoc.put("_id", "lineage_dl:evt-surv-2");
        dlDoc.put("_rev", "1-abc");
        dlDoc.put("eventId", "evt-surv-2");
        dlDoc.put("replayed", false);

        doReturn(dlDoc).when(mockClient).get(eq(Map.class), eq("lineage_dl:evt-surv-2"), isNull());

        Map<String, LineagePublishStatus> tripleStatus = new LinkedHashMap<>();
        tripleStatus.put("purview", LineagePublishStatus.FAILED);
        tripleStatus.put("atlas", LineagePublishStatus.FAILED);
        tripleStatus.put("dataplex", LineagePublishStatus.PUBLISHED);

        LineageEvent existing = new LineageEvent(
                1, "evt-surv-2", "key-surv-2", 1, "2026-01-15T10:00:00Z", "bedroom",
                LineageProcessType.IMPORT_UPLOADED,
                List.of("nemaki://bedroom/objects/doc-2"),
                List.of(),
                "", "", 1, Map.of(),
                tripleStatus);

        LineageJournalStore targetStore = mock(LineageJournalStore.class);
        when(targetStore.findByEventId("evt-surv-2")).thenReturn(existing);
        when(targetStore.updatePublishStatus(eq("evt-surv-2"), anyString(), eq(LineagePublishStatus.PENDING))).thenReturn(1);

        boolean result = deadLetterStore.replay("evt-surv-2", targetStore);

        assertTrue(result);
        // Both FAILED targets must be reset
        verify(targetStore).updatePublishStatus("evt-surv-2", "purview", LineagePublishStatus.PENDING);
        verify(targetStore).updatePublishStatus("evt-surv-2", "atlas", LineagePublishStatus.PENDING);
        // PUBLISHED target must NOT be touched
        verify(targetStore, never()).updatePublishStatus(eq("evt-surv-2"), eq("dataplex"), any());
        verify(targetStore, never()).append(any(LineageEvent.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void replay_noResetWhenAllTargetsAlreadyPublished() {
        // Original survives but all targets are already PUBLISHED — no reset needed
        Map<String, Object> dlDoc = new LinkedHashMap<>();
        dlDoc.put("_id", "lineage_dl:evt-surv-3");
        dlDoc.put("_rev", "1-abc");
        dlDoc.put("eventId", "evt-surv-3");
        dlDoc.put("replayed", false);

        doReturn(dlDoc).when(mockClient).get(eq(Map.class), eq("lineage_dl:evt-surv-3"), isNull());

        LineageEvent existing = new LineageEvent(
                1, "evt-surv-3", "key-surv-3", 1, "2026-01-15T10:00:00Z", "bedroom",
                LineageProcessType.ARCHIVE_LOCAL,
                List.of(), List.of(),
                "", "", 1, Map.of(),
                Map.of("purview", LineagePublishStatus.PUBLISHED, "atlas", LineagePublishStatus.PUBLISHED));

        LineageJournalStore targetStore = mock(LineageJournalStore.class);
        when(targetStore.findByEventId("evt-surv-3")).thenReturn(existing);

        boolean result = deadLetterStore.replay("evt-surv-3", targetStore);

        // Should still return true (dead-letter is marked replayed) but no targets reset
        assertTrue(result);
        verify(targetStore, never()).updatePublishStatus(anyString(), anyString(), any());
        verify(targetStore, never()).append(any(LineageEvent.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void replay_appendsOnlyFailedTargetsWhenOriginalPurged() {
        // Dead-letter record: purview FAILED, atlas PUBLISHED
        // Only purview should be re-queued; atlas is excluded entirely
        Map<String, Object> dlDoc = new LinkedHashMap<>();
        dlDoc.put("_id", "lineage_dl:evt-1");
        dlDoc.put("_rev", "1-abc");
        dlDoc.put("eventId", "evt-1");
        dlDoc.put("eventKey", "bedroom:ARCHIVE_LOCAL:doc-1:arc-1");
        dlDoc.put("repositoryId", "bedroom");
        dlDoc.put("processType", "ARCHIVE_LOCAL");
        dlDoc.put("occurredAt", "2026-01-15T10:00:00Z");
        dlDoc.put("inputs", List.of("nemaki://bedroom/objects/doc-1"));
        dlDoc.put("outputs", List.of("nemaki://bedroom/archives/arc-1"));
        dlDoc.put("snapshotAttributes", Map.of("name", "test.txt"));
        // Mixed original targets: purview failed, atlas already published
        dlDoc.put("originalPublishStatusByTarget", Map.of("purview", "FAILED", "atlas", "PUBLISHED"));
        dlDoc.put("replayed", false);

        doReturn(dlDoc).when(mockClient).get(eq(Map.class), eq("lineage_dl:evt-1"), isNull());

        LineageJournalStore targetStore = mock(LineageJournalStore.class);
        when(targetStore.findByEventId("evt-1")).thenReturn(null);

        boolean result = deadLetterStore.replay("evt-1", targetStore);

        assertTrue(result);
        // Only purview (FAILED→PENDING) should be in the reconstructed event.
        // atlas (PUBLISHED) must be excluded entirely to prevent duplicate publishes.
        verify(targetStore).append(argThat(event ->
                event.publishStatusByTarget().get("purview") == LineagePublishStatus.PENDING
                        && !event.publishStatusByTarget().containsKey("atlas")
                        && event.publishStatusByTarget().size() == 1
        ));
        verify(mockConfig, never()).getTargets();
    }

    @SuppressWarnings("unchecked")
    @Test
    void replay_appendsAllFailedTargetsWhenOriginalPurged() {
        // Dead-letter record: both targets FAILED — both should become PENDING
        Map<String, Object> dlDoc = new LinkedHashMap<>();
        dlDoc.put("_id", "lineage_dl:evt-3");
        dlDoc.put("_rev", "1-abc");
        dlDoc.put("eventId", "evt-3");
        dlDoc.put("eventKey", "bedroom:ARCHIVE_LOCAL:doc-3:arc-3");
        dlDoc.put("repositoryId", "bedroom");
        dlDoc.put("processType", "ARCHIVE_LOCAL");
        dlDoc.put("occurredAt", "2026-01-15T10:00:00Z");
        dlDoc.put("inputs", List.of("nemaki://bedroom/objects/doc-3"));
        dlDoc.put("outputs", List.of("nemaki://bedroom/archives/arc-3"));
        dlDoc.put("originalPublishStatusByTarget", Map.of("purview", "FAILED", "atlas", "DISCARDED"));
        dlDoc.put("replayed", false);

        doReturn(dlDoc).when(mockClient).get(eq(Map.class), eq("lineage_dl:evt-3"), isNull());

        LineageJournalStore targetStore = mock(LineageJournalStore.class);
        when(targetStore.findByEventId("evt-3")).thenReturn(null);

        boolean result = deadLetterStore.replay("evt-3", targetStore);

        assertTrue(result);
        verify(targetStore).append(argThat(event ->
                event.publishStatusByTarget().get("purview") == LineagePublishStatus.PENDING
                        && event.publishStatusByTarget().get("atlas") == LineagePublishStatus.PENDING
                        && event.publishStatusByTarget().size() == 2
        ));
    }

    @SuppressWarnings("unchecked")
    @Test
    void replay_fallsBackToConfigWhenLegacyDocLacksOriginalTargets() {
        // Legacy dead-letter doc without originalPublishStatusByTarget
        Map<String, Object> dlDoc = new LinkedHashMap<>();
        dlDoc.put("_id", "lineage_dl:evt-2");
        dlDoc.put("_rev", "1-abc");
        dlDoc.put("eventId", "evt-2");
        dlDoc.put("eventKey", "bedroom:ARCHIVE_LOCAL:doc-2:arc-2");
        dlDoc.put("repositoryId", "bedroom");
        dlDoc.put("processType", "ARCHIVE_LOCAL");
        dlDoc.put("occurredAt", "2026-01-15T10:00:00Z");
        dlDoc.put("inputs", List.of());
        dlDoc.put("outputs", List.of());
        dlDoc.put("replayed", false);
        // No originalPublishStatusByTarget field (legacy)

        doReturn(dlDoc).when(mockClient).get(eq(Map.class), eq("lineage_dl:evt-2"), isNull());

        LineageJournalStore targetStore = mock(LineageJournalStore.class);
        when(targetStore.findByEventId("evt-2")).thenReturn(null);
        when(mockConfig.getTargets()).thenReturn(List.of("purview"));

        boolean result = deadLetterStore.replay("evt-2", targetStore);

        assertTrue(result);
        // Falls back to current config targets
        verify(targetStore).append(argThat(event ->
                event.publishStatusByTarget().get("purview") == LineagePublishStatus.PENDING
        ));
    }

    @SuppressWarnings("unchecked")
    @Test
    void replay_returnsFalseWhenAlreadyReplayed() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("_id", "lineage_dl:evt-1");
        doc.put("replayed", true);

        doReturn(doc).when(mockClient).get(eq(Map.class), eq("lineage_dl:evt-1"), isNull());

        LineageJournalStore targetStore = mock(LineageJournalStore.class);
        boolean result = deadLetterStore.replay("evt-1", targetStore);

        assertFalse(result);
        verify(targetStore, never()).append(any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void replay_returnsFalseWhenNotFound() {
        doReturn(null).when(mockClient).get(eq(Map.class), eq("lineage_dl:nonexistent"), isNull());

        LineageJournalStore targetStore = mock(LineageJournalStore.class);
        boolean result = deadLetterStore.replay("nonexistent", targetStore);

        assertFalse(result);
    }

    // ==================== replayAll ====================

    @SuppressWarnings("unchecked")
    @Test
    void replayAll_stopsOnNoProgress() {
        // All dead-letters are already replayed — batchReplayed stays 0, loop exits immediately
        ViewResult viewResult = mock(ViewResult.class);
        when(viewResult.getRows()).thenReturn(List.of());
        when(mockClient.queryView(eq("lineage"), anyString(), any(Map.class))).thenReturn(viewResult);

        LineageJournalStore targetStore = mock(LineageJournalStore.class);
        int count = deadLetterStore.replayAll(targetStore);

        assertEquals(0, count);
        // findAll is called once with empty result → loop exits
    }

    @SuppressWarnings("unchecked")
    @Test
    void replayAll_processesMultipleBatches() {
        // Setup: two dead-letter records across two "batches"
        // First findAll returns one record, second findAll returns empty (loop exits)
        com.ibm.cloud.cloudant.v1.model.Document sdkDoc1 = mock(com.ibm.cloud.cloudant.v1.model.Document.class);
        when(sdkDoc1.getId()).thenReturn("lineage_dl:evt-ra-1");
        Map<String, Object> props1 = new LinkedHashMap<>();
        props1.put("eventId", "evt-ra-1");
        when(sdkDoc1.getProperties()).thenReturn(props1);

        ViewResultRow row1 = mock(ViewResultRow.class);
        when(row1.getDoc()).thenReturn(sdkDoc1);
        ViewResult viewResult1 = mock(ViewResult.class);
        when(viewResult1.getRows()).thenReturn(List.of(row1));

        ViewResult emptyResult = mock(ViewResult.class);
        when(emptyResult.getRows()).thenReturn(List.of());

        // First call: one unreplayed record; second call: empty (all done)
        when(mockClient.queryView(eq("lineage"), eq("dead_letter_by_replayed"), any(Map.class)))
                .thenReturn(viewResult1)
                .thenReturn(emptyResult);

        // Setup replay for the single record
        Map<String, Object> dlDoc = new LinkedHashMap<>();
        dlDoc.put("_id", "lineage_dl:evt-ra-1");
        dlDoc.put("_rev", "1-abc");
        dlDoc.put("eventId", "evt-ra-1");
        dlDoc.put("replayed", false);
        dlDoc.put("originalPublishStatusByTarget", Map.of("atlas", "FAILED"));
        dlDoc.put("eventKey", "bedroom:IMPORT_UPLOADED:doc:arc");
        dlDoc.put("repositoryId", "bedroom");
        dlDoc.put("processType", "IMPORT_UPLOADED");
        dlDoc.put("occurredAt", "2026-01-15T10:00:00Z");
        dlDoc.put("inputs", List.of());
        dlDoc.put("outputs", List.of());
        doReturn(dlDoc).when(mockClient).get(eq(Map.class), eq("lineage_dl:evt-ra-1"), isNull());

        LineageJournalStore targetStore = mock(LineageJournalStore.class);
        when(targetStore.findByEventId("evt-ra-1")).thenReturn(null); // original purged

        int count = deadLetterStore.replayAll(targetStore);

        assertEquals(1, count);
        verify(targetStore).append(any(LineageEvent.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void replayAll_exitsOnBatchWithZeroReplayedToAvoidInfiniteLoop() {
        // Simulate a batch where all records fail to replay (e.g., all already replayed)
        com.ibm.cloud.cloudant.v1.model.Document sdkDoc = mock(com.ibm.cloud.cloudant.v1.model.Document.class);
        when(sdkDoc.getId()).thenReturn("lineage_dl:evt-stuck");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("eventId", "evt-stuck");
        when(sdkDoc.getProperties()).thenReturn(props);

        ViewResultRow row = mock(ViewResultRow.class);
        when(row.getDoc()).thenReturn(sdkDoc);
        ViewResult viewResult = mock(ViewResult.class);
        when(viewResult.getRows()).thenReturn(List.of(row));

        // findAll always returns the same record (simulating stuck state)
        when(mockClient.queryView(eq("lineage"), eq("dead_letter_by_replayed"), any(Map.class)))
                .thenReturn(viewResult);

        // replay always returns false (already replayed)
        Map<String, Object> dlDoc = new LinkedHashMap<>();
        dlDoc.put("_id", "lineage_dl:evt-stuck");
        dlDoc.put("replayed", true); // already replayed
        doReturn(dlDoc).when(mockClient).get(eq(Map.class), eq("lineage_dl:evt-stuck"), isNull());

        LineageJournalStore targetStore = mock(LineageJournalStore.class);
        int count = deadLetterStore.replayAll(targetStore);

        assertEquals(0, count);
        // Should exit after first batch due to batchReplayed==0, not loop 10 times
        // (queryView is called once for findAll + once for the already-replayed check = max 2)
    }

    // ==================== count ====================

    @Test
    void count_returnsZeroWhenClientNull() {
        when(mockJournalStore.isActive()).thenReturn(false);
        when(mockJournalStore.getLineageClient()).thenReturn(null);

        long count = deadLetterStore.count(null);
        assertEquals(0, count);
    }

    @Test
    void count_returnsSumFromViewWithReplayedFilter() {
        ViewResult viewResult = mock(ViewResult.class);
        ViewResultRow row1 = mock(ViewResultRow.class);
        when(row1.getValue()).thenReturn(3L);
        when(viewResult.getRows()).thenReturn(List.of(row1));

        when(mockClient.queryView(eq("lineage"), eq("dead_letter_by_replayed"), any(Map.class)))
                .thenReturn(viewResult);

        long count = deadLetterStore.count(false);
        assertEquals(3, count);
    }

    @Test
    void count_returnsSumFromViewWithoutFilter() {
        ViewResult viewResult = mock(ViewResult.class);
        ViewResultRow row1 = mock(ViewResultRow.class);
        ViewResultRow row2 = mock(ViewResultRow.class);
        when(row1.getValue()).thenReturn(5L);
        when(row2.getValue()).thenReturn(3L);
        when(viewResult.getRows()).thenReturn(List.of(row1, row2));

        when(mockClient.queryView(eq("lineage"), eq("dead_letter_by_replayed"), any(Map.class)))
                .thenReturn(viewResult);

        long count = deadLetterStore.count(null);
        assertEquals(8, count);
    }

    // ==================== purgeReplayed ====================

    @SuppressWarnings("unchecked")
    @Test
    void purgeReplayed_deletesReplayedDocs() {
        // findAll returns replayed records
        ViewResult viewResult = mock(ViewResult.class);
        ViewResultRow row = mock(ViewResultRow.class);
        com.ibm.cloud.cloudant.v1.model.Document sdkDoc = mock(com.ibm.cloud.cloudant.v1.model.Document.class);
        when(sdkDoc.getId()).thenReturn("lineage_dl:evt-1");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("eventId", "evt-1");
        when(sdkDoc.getProperties()).thenReturn(props);
        when(row.getDoc()).thenReturn(sdkDoc);
        when(viewResult.getRows()).thenReturn(List.of(row));
        when(mockClient.queryView(eq("lineage"), eq("dead_letter_by_replayed"), any(Map.class)))
                .thenReturn(viewResult);

        // get returns the doc with _rev for deletion
        Map<String, Object> fullDoc = new LinkedHashMap<>();
        fullDoc.put("_id", "lineage_dl:evt-1");
        fullDoc.put("_rev", "2-def");
        doReturn(fullDoc).when(mockClient).get(eq(Map.class), eq("lineage_dl:evt-1"), isNull());

        int purged = deadLetterStore.purgeReplayed();

        assertEquals(1, purged);
        verify(mockClient).delete("lineage_dl:evt-1", "2-def");
    }

    // ==================== isActive ====================

    @Test
    void isActive_returnsTrueWhenClientAvailable() {
        assertTrue(deadLetterStore.isActive());
    }

    @Test
    void isActive_returnsFalseWhenJournalStoreInactive() {
        when(mockJournalStore.isActive()).thenReturn(false);
        when(mockJournalStore.getLineageClient()).thenReturn(null);

        assertFalse(deadLetterStore.isActive());
    }

    // ==================== Helpers ====================

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                var field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
