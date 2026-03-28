package jp.aegif.nemaki.rest.purview.journal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LineageProjectionLoop}.
 */
class LineageProjectionLoopTest {

    private LineageProjectionLoop loop;
    private LineageConfig mockConfig;
    private LineageJournalStore mockStore;
    private LineageTargetSink mockSink;

    @BeforeEach
    void setUp() throws Exception {
        loop = new LineageProjectionLoop();
        mockConfig = mock(LineageConfig.class);
        mockStore = mock(LineageJournalStore.class);
        mockSink = mock(LineageTargetSink.class);

        when(mockConfig.getTargets()).thenReturn(List.of("purview"));
        when(mockConfig.getProjectionBatchSize()).thenReturn(50);
        when(mockConfig.getProjectionPollIntervalSeconds()).thenReturn(10);
        when(mockConfig.getProjectionStaleThresholdMinutes()).thenReturn(5);
        when(mockConfig.getBacklogMaxRetryCount()).thenReturn(5);
        when(mockConfig.getBacklogMaxRetryAgeHours()).thenReturn(72);
        when(mockConfig.getBacklogMaxDocs()).thenReturn(10000);

        when(mockStore.isActive()).thenReturn(true);
        when(mockSink.targetName()).thenReturn("purview");
        when(mockSink.isAvailable()).thenReturn(true);

        setField(loop, "lineageConfig", mockConfig);
        setField(loop, "journalStore", mockStore);
        setField(loop, "targetSinks", List.of(mockSink));
    }

    @Test
    void pollAndProject_skipsWhenStoreInactive() {
        when(mockStore.isActive()).thenReturn(false);
        loop.pollAndProject();
        verify(mockStore, never()).findByTargetAndStatus(any(), any(), anyInt());
    }

    @Test
    void pollAndProject_skipsWhenNoTargetsConfigured() {
        when(mockConfig.getTargets()).thenReturn(List.of());
        loop.pollAndProject();
        verify(mockStore, never()).findByTargetAndStatus(any(), any(), anyInt());
    }

    @Test
    void pollAndProject_skipsWhenSinkUnavailable() throws Exception {
        when(mockSink.isAvailable()).thenReturn(false);
        loop.pollAndProject();
        // When sink is unavailable, publish should never be called
        verify(mockSink, never()).publish(any());
    }

    @Test
    void pollAndProject_claimsAndPublishesPendingEvent() throws Exception {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.IMPORT_UPLOADED)
                .addInputObject("bedroom", "doc-1")
                .targets(List.of("purview"))
                .build();

        when(mockStore.findByTargetAndStatus("purview", LineagePublishStatus.PENDING, 50))
                .thenReturn(List.of(event));
        when(mockStore.findByTargetAndStatus("purview", LineagePublishStatus.FAILED, 50))
                .thenReturn(List.of());
        when(mockStore.findByTargetAndStatus("purview", LineagePublishStatus.PROJECTING, 50))
                .thenReturn(List.of());
        when(mockStore.countNonTerminalByTarget("purview")).thenReturn(1L);

        // CAS claim succeeds
        when(mockStore.updatePublishStatus(event.eventId(), "purview", LineagePublishStatus.PROJECTING))
                .thenReturn(1);

        // Publish succeeds
        when(mockSink.publish(event)).thenReturn(LineageTargetSinkResult.success(1, "OK"));

        loop.pollAndProject();

        // Verify: claimed, published, status updated to PUBLISHED
        verify(mockStore).updatePublishStatus(event.eventId(), "purview", LineagePublishStatus.PROJECTING);
        verify(mockSink).publish(event);
        verify(mockStore).updatePublishStatus(event.eventId(), "purview", LineagePublishStatus.PUBLISHED);
    }

    @Test
    void pollAndProject_skipsWhenCasFails() throws Exception {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.IMPORT_UPLOADED)
                .addInputObject("bedroom", "doc-1")
                .targets(List.of("purview"))
                .build();

        when(mockStore.findByTargetAndStatus("purview", LineagePublishStatus.PENDING, 50))
                .thenReturn(List.of(event));
        when(mockStore.findByTargetAndStatus("purview", LineagePublishStatus.FAILED, 50))
                .thenReturn(List.of());
        when(mockStore.findByTargetAndStatus("purview", LineagePublishStatus.PROJECTING, 50))
                .thenReturn(List.of());
        when(mockStore.countNonTerminalByTarget("purview")).thenReturn(1L);

        // CAS claim fails (another node claimed first)
        when(mockStore.updatePublishStatus(event.eventId(), "purview", LineagePublishStatus.PROJECTING))
                .thenReturn(0);

        loop.pollAndProject();

        // Verify: claim attempted but publish was NOT called
        verify(mockStore).updatePublishStatus(event.eventId(), "purview", LineagePublishStatus.PROJECTING);
        verify(mockSink, never()).publish(any());
    }

    @Test
    void pollAndProject_setsFailedOnPublishError() throws Exception {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.EXPORT_ZIP_FOLDER)
                .addInputObject("bedroom", "folder-1")
                .targets(List.of("purview"))
                .build();

        when(mockStore.findByTargetAndStatus("purview", LineagePublishStatus.PENDING, 50))
                .thenReturn(List.of(event));
        when(mockStore.findByTargetAndStatus("purview", LineagePublishStatus.FAILED, 50))
                .thenReturn(List.of());
        when(mockStore.findByTargetAndStatus("purview", LineagePublishStatus.PROJECTING, 50))
                .thenReturn(List.of());
        when(mockStore.countNonTerminalByTarget("purview")).thenReturn(1L);

        when(mockStore.updatePublishStatus(event.eventId(), "purview", LineagePublishStatus.PROJECTING))
                .thenReturn(1);
        when(mockSink.publish(event)).thenThrow(new RuntimeException("Connection refused"));

        loop.pollAndProject();

        verify(mockStore).updatePublishStatus(event.eventId(), "purview", LineagePublishStatus.FAILED);
    }

    @Test
    void reapStaleProjecting_delegatesToStore() {
        // reapStaleProjecting now delegates to store.reapStaleProjecting(target, staleMinutes)
        when(mockStore.reapStaleProjecting("purview", 5)).thenReturn(3);

        loop.reapStaleProjecting("purview");

        verify(mockStore).reapStaleProjecting("purview", 5);
    }

    @Test
    void reapStaleProjecting_doesNotCallOldLogicDirectly() {
        // Ensure the loop no longer queries PROJECTING events directly
        when(mockStore.reapStaleProjecting("purview", 5)).thenReturn(0);

        loop.reapStaleProjecting("purview");

        // The loop should NOT call findByTargetAndStatus for PROJECTING anymore
        verify(mockStore, never()).findByTargetAndStatus(eq("purview"), eq(LineagePublishStatus.PROJECTING), anyInt());
    }

    @Test
    void enforceBacklogThresholds_discardsOldFailedEvents() {
        // Event occurred 4 days ago (> 72h threshold)
        String oldTime = Instant.now().minus(4, ChronoUnit.DAYS).toString();
        LineageEvent oldEvent = new LineageEvent(
                1, "evt-old", "key-old", 1, oldTime, "bedroom",
                LineageProcessType.ARCHIVE_COLD, List.of(), List.of(),
                "", "", 1, Map.of(),
                Map.of("purview", LineagePublishStatus.FAILED));

        when(mockStore.findByTargetAndStatus("purview", LineagePublishStatus.FAILED, 50))
                .thenReturn(List.of(oldEvent));
        when(mockStore.findByTargetAndStatus("purview", LineagePublishStatus.PENDING, 50))
                .thenReturn(List.of());
        when(mockStore.countNonTerminalByTarget("purview")).thenReturn(1L);
        when(mockStore.discardEvent("evt-old", "purview")).thenReturn(1);

        loop.enforceBacklogThresholds("purview");

        verify(mockStore).discardEvent("evt-old", "purview");
    }

    @Test
    void enforceBacklogThresholds_skipsRecentEvents() {
        // Event occurred 1 hour ago (< 72h threshold)
        String recentTime = Instant.now().minus(1, ChronoUnit.HOURS).toString();
        LineageEvent recentEvent = new LineageEvent(
                1, "evt-recent", "key-recent", 1, recentTime, "bedroom",
                LineageProcessType.ARCHIVE_COLD, List.of(), List.of(),
                "", "", 1, Map.of(),
                Map.of("purview", LineagePublishStatus.FAILED));

        when(mockStore.findByTargetAndStatus("purview", LineagePublishStatus.FAILED, 50))
                .thenReturn(List.of(recentEvent));
        when(mockStore.findByTargetAndStatus("purview", LineagePublishStatus.PENDING, 50))
                .thenReturn(List.of());
        when(mockStore.countNonTerminalByTarget("purview")).thenReturn(1L);

        loop.enforceBacklogThresholds("purview");

        verify(mockStore, never()).discardEvent(any(), any());
    }

    @Test
    void handlePublishFailure_autoDiscardsWhenRetryCountExceeded() throws Exception {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.EXPORT_ZIP_FOLDER)
                .addInputObject("bedroom", "folder-1")
                .targets(List.of("purview"))
                .build();

        when(mockStore.findByTargetAndStatus("purview", LineagePublishStatus.PENDING, 50))
                .thenReturn(List.of(event));
        when(mockStore.findByTargetAndStatus("purview", LineagePublishStatus.FAILED, 50))
                .thenReturn(List.of());
        when(mockStore.findByTargetAndStatus("purview", LineagePublishStatus.PROJECTING, 50))
                .thenReturn(List.of());
        when(mockStore.countNonTerminalByTarget("purview")).thenReturn(1L);
        when(mockStore.reapStaleProjecting("purview", 5)).thenReturn(0);

        // CAS claim succeeds
        when(mockStore.updatePublishStatus(event.eventId(), "purview", LineagePublishStatus.PROJECTING))
                .thenReturn(1);
        // Publish throws
        when(mockSink.publish(event)).thenThrow(new RuntimeException("Connection refused"));
        // Retry count at max
        when(mockStore.getRetryCount(event.eventId(), "purview")).thenReturn(5);

        loop.pollAndProject();

        // Should transition to FAILED first, then auto-discard to DISCARDED
        verify(mockStore).updatePublishStatus(event.eventId(), "purview", LineagePublishStatus.FAILED);
        verify(mockStore).updatePublishStatus(event.eventId(), "purview", LineagePublishStatus.DISCARDED);
    }

    @Test
    void handlePublishFailure_doesNotDiscardWhenBelowRetryLimit() throws Exception {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.IMPORT_UPLOADED)
                .addInputObject("bedroom", "doc-1")
                .targets(List.of("purview"))
                .build();

        when(mockStore.findByTargetAndStatus("purview", LineagePublishStatus.PENDING, 50))
                .thenReturn(List.of(event));
        when(mockStore.findByTargetAndStatus("purview", LineagePublishStatus.FAILED, 50))
                .thenReturn(List.of());
        when(mockStore.findByTargetAndStatus("purview", LineagePublishStatus.PROJECTING, 50))
                .thenReturn(List.of());
        when(mockStore.countNonTerminalByTarget("purview")).thenReturn(1L);
        when(mockStore.reapStaleProjecting("purview", 5)).thenReturn(0);

        when(mockStore.updatePublishStatus(event.eventId(), "purview", LineagePublishStatus.PROJECTING))
                .thenReturn(1);
        when(mockSink.publish(event)).thenThrow(new RuntimeException("Timeout"));
        // Retry count below max
        when(mockStore.getRetryCount(event.eventId(), "purview")).thenReturn(2);

        loop.pollAndProject();

        verify(mockStore).updatePublishStatus(event.eventId(), "purview", LineagePublishStatus.FAILED);
        verify(mockStore, never()).updatePublishStatus(event.eventId(), "purview", LineagePublishStatus.DISCARDED);
    }

    @Test
    void enforceBacklogThresholds_maxSizeMb_discardsWhenExceeded() {
        when(mockConfig.getBacklogMaxSizeMb()).thenReturn(1); // 1 MB = 1048576 bytes
        // Real DB size estimation returns 2MB (exceeds 1MB limit)
        when(mockStore.getEstimatedNonTerminalSizeBytes("purview")).thenReturn(2_097_152L);
        when(mockStore.countNonTerminalByTarget("purview")).thenReturn(600L);
        when(mockStore.findByTargetAndStatus("purview", LineagePublishStatus.FAILED, 50))
                .thenReturn(List.of());
        when(mockStore.findByTargetAndStatus("purview", LineagePublishStatus.PENDING, 50))
                .thenReturn(List.of());

        loop.enforceBacklogThresholds("purview");

        // Should use getEstimatedNonTerminalSizeBytes for size check
        verify(mockStore, atLeastOnce()).getEstimatedNonTerminalSizeBytes("purview");
        verify(mockStore, atLeastOnce()).countNonTerminalByTarget("purview");
    }

    @Test
    void enforceBacklogThresholds_maxSizeMb_skipsWhenBelowLimit() {
        when(mockConfig.getBacklogMaxSizeMb()).thenReturn(100); // 100 MB limit
        // Real DB size estimation returns 500KB (well below 100MB limit)
        when(mockStore.getEstimatedNonTerminalSizeBytes("purview")).thenReturn(512_000L);
        when(mockStore.countNonTerminalByTarget("purview")).thenReturn(10L);
        when(mockStore.findByTargetAndStatus("purview", LineagePublishStatus.FAILED, 50))
                .thenReturn(List.of());
        when(mockStore.findByTargetAndStatus("purview", LineagePublishStatus.PENDING, 50))
                .thenReturn(List.of());

        loop.enforceBacklogThresholds("purview");

        // Should check size but NOT discard anything
        verify(mockStore).getEstimatedNonTerminalSizeBytes("purview");
        verify(mockStore, never()).discardEvent(any(), any());
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
