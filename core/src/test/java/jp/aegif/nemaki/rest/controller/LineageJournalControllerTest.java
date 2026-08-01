package jp.aegif.nemaki.rest.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.*;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.rest.purview.journal.*;
import jp.aegif.nemaki.util.constant.CallContextKey;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public class LineageJournalControllerTest {

    private LineageJournalController controller;
    private LineageJournalStore store;
    private LineageConfig lineageConfig;
    private LineageMetrics lineageMetrics;
    private LineageDeadLetterStore deadLetterStore;
    private HttpServletRequest httpRequest;
    private CallContext callContext;

    @BeforeEach
    void setUp() throws Exception {
        controller = new LineageJournalController();
        store = mock(LineageJournalStore.class);
        lineageConfig = mock(LineageConfig.class);
        lineageMetrics = mock(LineageMetrics.class);
        deadLetterStore = mock(LineageDeadLetterStore.class);
        httpRequest = mock(HttpServletRequest.class);
        callContext = mock(CallContext.class);

        when(httpRequest.getAttribute("CallContext")).thenReturn(callContext);
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);

        setField(controller, "journalStore", store);
        setField(controller, "lineageConfig", lineageConfig);
        setField(controller, "lineageMetrics", lineageMetrics);
        setField(controller, "deadLetterStore", deadLetterStore);
        controller.setHttpRequest(httpRequest);
    }

    // ==================== GET /events ====================

    @Test
    void listEventsReturnsEventsForAdmin() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .addInputObject("bedroom", "doc-1")
                .addOutputObject("bedroom", "arc-1")
                .build();
        when(store.findAll(51, 0)).thenReturn(rows(event));

        ResponseEntity<Map<String, Object>> response = controller.listEvents(null, null, null, null, 50, 0);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) response.getBody().get("events");
        assertEquals(1, events.size());
        assertEquals("bedroom", events.get(0).get("repositoryId"));
        assertEquals("ARCHIVE_LOCAL", events.get(0).get("processType"));
    }

    @Test
    void listEventsFiltersByRepositoryId() {
        when(store.findByRepositoryId("bedroom", 51, 0)).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.listEvents("bedroom", null, null, null, 50, 0);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(store).findByRepositoryId("bedroom", 51, 0);
    }

    @Test
    void listEventsFiltersByProcessType() {
        when(store.findByProcessType(LineageProcessType.ARCHIVE_LOCAL, 51, 0)).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.listEvents(null, "ARCHIVE_LOCAL", null, null, 50, 0);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(store).findByProcessType(LineageProcessType.ARCHIVE_LOCAL, 51, 0);
    }

    @Test
    void listEventsFiltersByBothRepoAndProcessType() {
        when(store.findByProcessType("bedroom", LineageProcessType.ARCHIVE_LOCAL, 51, 0)).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.listEvents("bedroom", "ARCHIVE_LOCAL", null, null, 50, 0);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(store).findByProcessType("bedroom", LineageProcessType.ARCHIVE_LOCAL, 51, 0);
    }

    @Test
    void listEventsPassesOffsetToFilteredQuery() {
        when(store.findByRepositoryId("bedroom", 51, 10)).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.listEvents("bedroom", null, null, null, 50, 10);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(store).findByRepositoryId("bedroom", 51, 10);
    }

    @Test
    void listEventsRejectsForbiddenUser() {
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.FALSE);

        ResponseEntity<Map<String, Object>> response = controller.listEvents(null, null, null, null, 50, 0);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void listEventsRejectsInvalidProcessType() {
        ResponseEntity<Map<String, Object>> response = controller.listEvents(null, "INVALID_TYPE", null, null, 50, 0);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void listEventsCapsLimitAt200() {
        when(store.findAll(201, 0)).thenReturn(List.of());

        controller.listEvents(null, null, null, null, 999, 0);

        verify(store).findAll(201, 0);
    }

    // ==================== GET /events — date range (D-13) ====================

    @Test
    void listEventsFiltersByDateRange() {
        String start = "2026-01-01T00:00:00Z";
        String end = "2026-01-31T23:59:59Z";
        when(store.findByDateRange(start, end, 51, 0)).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.listEvents(null, null, start, end, 50, 0);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(store).findByDateRange(start, end, 51, 0);
    }

    @Test
    void listEventsDateRangeTakesPriorityOverOtherFilters() {
        String start = "2026-01-01T00:00:00Z";
        String end = "2026-01-31T23:59:59Z";
        when(store.findByDateRange(start, end, 51, 0)).thenReturn(List.of());

        // Even with repositoryId and processType, date range should take priority
        ResponseEntity<Map<String, Object>> response = controller.listEvents("bedroom", "ARCHIVE_LOCAL", start, end, 50, 0);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(store).findByDateRange(start, end, 51, 0);
        verify(store, never()).findByRepositoryId(anyString(), anyInt(), anyInt());
        verify(store, never()).findByProcessType(any(LineageProcessType.class), anyInt(), anyInt());
    }

    @Test
    void listEventsRejectsInvalidStartDate() {
        ResponseEntity<Map<String, Object>> response = controller.listEvents(null, null, "not-a-date", "2026-01-31T23:59:59Z", 50, 0);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().get("message").toString().contains("Invalid date format"));
    }

    @Test
    void listEventsRejectsInvalidEndDate() {
        ResponseEntity<Map<String, Object>> response = controller.listEvents(null, null, "2026-01-01T00:00:00Z", "bad-date", 50, 0);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().get("message").toString().contains("Invalid date format"));
    }

    @Test
    void listEventsIgnoresPartialDateRange() {
        // Only startDate set, endDate null — should fall through to no-filter path
        when(store.findAll(51, 0)).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.listEvents(null, null, "2026-01-01T00:00:00Z", null, 50, 0);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(store).findAll(51, 0);
        verify(store, never()).findByDateRange(anyString(), anyString(), anyInt(), anyInt());
    }

    // ==================== GET /events — hasMore / estimatedTotal ====================

    @Test
    void listEventsReturnsHasMoreWhenExtraResultExists() {
        // Store returns limit+1 (51) results → hasMore=true, page truncated to 50
        List<LineageEvent> fiftyOneEvents = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            fiftyOneEvents.add(new LineageEventBuilder()
                    .repositoryId("bedroom")
                    .processType(LineageProcessType.ARCHIVE_LOCAL)
                    .build());
        }
        when(store.findAll(51, 0)).thenReturn(fiftyOneEvents.stream().map(LineageJournalControllerTest::row).toList());

        ResponseEntity<Map<String, Object>> response = controller.listEvents(null, null, null, null, 50, 0);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) body.get("events");
        assertEquals(50, events.size());
        assertEquals(true, body.get("hasMore"));
        // estimatedTotal = offset(0) + cappedLimit(50) + 1 = 51
        assertEquals(51, body.get("total"));
    }

    @Test
    void listEventsReturnsNoMoreWhenFewerResults() {
        // Store returns exactly 3 results (< limit+1) → hasMore=false
        List<LineageEvent> threeEvents = List.of(
                new LineageEventBuilder().repositoryId("bedroom").processType(LineageProcessType.ARCHIVE_LOCAL).build(),
                new LineageEventBuilder().repositoryId("bedroom").processType(LineageProcessType.IMPORT_FILESYSTEM).build(),
                new LineageEventBuilder().repositoryId("bedroom").processType(LineageProcessType.EXPORT_ZIP_FOLDER).build()
        );
        when(store.findAll(51, 0)).thenReturn(threeEvents.stream().map(LineageJournalControllerTest::row).toList());

        ResponseEntity<Map<String, Object>> response = controller.listEvents(null, null, null, null, 50, 0);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) body.get("events");
        assertEquals(3, events.size());
        assertEquals(false, body.get("hasMore"));
        // estimatedTotal = offset(0) + eventList.size(3) = 3
        assertEquals(3, body.get("total"));
    }

    @Test
    void listEventsEstimatesTotalWithOffset() {
        // offset=100, store returns 51 → hasMore=true, estimatedTotal=100+50+1=151
        List<LineageEvent> fiftyOneEvents = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            fiftyOneEvents.add(new LineageEventBuilder()
                    .repositoryId("bedroom")
                    .processType(LineageProcessType.ARCHIVE_LOCAL)
                    .build());
        }
        when(store.findAll(51, 100)).thenReturn(fiftyOneEvents.stream().map(LineageJournalControllerTest::row).toList());

        ResponseEntity<Map<String, Object>> response = controller.listEvents(null, null, null, null, 50, 100);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertEquals(true, body.get("hasMore"));
        assertEquals(151, body.get("total"));
        assertEquals(50, body.get("limit"));
        assertEquals(100, body.get("offset"));
    }

    // ==================== GET /events/{eventId} ====================

    @Test
    void getEventReturnsEventWhenFound() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.IMPORT_FILESYSTEM)
                .build();
        when(store.findByRecordId("evt-123")).thenReturn(row(event));

        ResponseEntity<Map<String, Object>> response = controller.getEvent("evt-123");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("bedroom", response.getBody().get("repositoryId"));
    }

    @Test
    void getEventReturns404WhenNotFound() {
        when(store.findByRecordId("nonexistent")).thenReturn(null);

        ResponseEntity<Map<String, Object>> response = controller.getEvent("nonexistent");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void getEventRejectsForbiddenUser() {
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.FALSE);

        ResponseEntity<Map<String, Object>> response = controller.getEvent("evt-123");

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    // ==================== POST /events/{eventId}/replay (D-12) ====================

    @Test
    void replayEventResetsStatusToPending() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .build();
        when(store.findByRecordId("evt-1")).thenReturn(row(event));
        when(store.updatePublishStatus("evt-1", "purview", LineagePublishStatus.PENDING)).thenReturn(1);

        ResponseEntity<Map<String, Object>> response = controller.replayEvent("evt-1", "purview");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("ok", response.getBody().get("status"));
        verify(store).updatePublishStatus("evt-1", "purview", LineagePublishStatus.PENDING);
    }

    @Test
    void replayEventReturnsErrorWhenNotFound() {
        when(store.findByRecordId("nonexistent")).thenReturn(null);

        ResponseEntity<Map<String, Object>> response = controller.replayEvent("nonexistent", "purview");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void replayEventRejectsForbiddenUser() {
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.FALSE);

        ResponseEntity<Map<String, Object>> response = controller.replayEvent("evt-1", "purview");

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    // ==================== POST /events/{eventId}/discard (D-12) ====================

    @Test
    void discardEventSucceeds() {
        when(store.discardEvent("evt-1", "purview")).thenReturn(1);

        ResponseEntity<Map<String, Object>> response = controller.discardEvent("evt-1", "purview");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("ok", response.getBody().get("status"));
        verify(store).discardEvent("evt-1", "purview");
    }

    @Test
    void discardEventReturnsErrorWhenFailed() {
        when(store.discardEvent("evt-1", "purview")).thenReturn(0);

        ResponseEntity<Map<String, Object>> response = controller.discardEvent("evt-1", "purview");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("error", response.getBody().get("status"));
    }

    @Test
    void discardEventRejectsForbiddenUser() {
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.FALSE);

        ResponseEntity<Map<String, Object>> response = controller.discardEvent("evt-1", "purview");

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    // ==================== GET /stats ====================

    @SuppressWarnings("unchecked")
    @Test
    void getStatsReturnsPerTargetBacklog() {
        Map<LineageProcessType, Long> counts = new LinkedHashMap<>();
        counts.put(LineageProcessType.ARCHIVE_LOCAL, 80L);
        counts.put(LineageProcessType.IMPORT_FILESYSTEM, 30L);
        counts.put(LineageProcessType.EXPORT_ZIP_FOLDER, 32L);
        when(store.countByProcessType()).thenReturn(counts);
        when(lineageConfig.getTargets()).thenReturn(List.of("purview", "atlas"));
        when(store.countNonTerminalByTarget("purview")).thenReturn(5L);
        when(store.countNonTerminalByTarget("atlas")).thenReturn(12L);
        when(store.isActive()).thenReturn(true);
        when(lineageConfig.getMode()).thenReturn(LineageMode.JOURNALED);

        ResponseEntity<Map<String, Object>> response = controller.getStats();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertEquals("journaled", body.get("mode"));
        assertEquals(142L, body.get("totalEvents"));
        assertTrue((Boolean) body.get("storeActive"));

        // Per-target backlog
        Map<String, Long> nonTerminal = (Map<String, Long>) body.get("nonTerminalByTarget");
        assertEquals(5L, nonTerminal.get("purview"));
        assertEquals(12L, nonTerminal.get("atlas"));

        // Targets list
        List<String> targets = (List<String>) body.get("targets");
        assertEquals(List.of("purview", "atlas"), targets);

        Map<String, Long> byType = (Map<String, Long>) body.get("byProcessType");
        assertEquals(80L, byType.get("ARCHIVE_LOCAL"));
        assertEquals(30L, byType.get("IMPORT_FILESYSTEM"));
        assertEquals(32L, byType.get("EXPORT_ZIP_FOLDER"));
    }

    @Test
    void getStatsRejectsForbiddenUser() {
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.FALSE);

        ResponseEntity<Map<String, Object>> response = controller.getStats();

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void getStatsHandlesInactiveStore() {
        when(store.countByProcessType()).thenReturn(Map.of());
        when(lineageConfig.getTargets()).thenReturn(List.of());
        when(store.isActive()).thenReturn(false);
        when(lineageConfig.getMode()).thenReturn(LineageMode.DISABLED);

        ResponseEntity<Map<String, Object>> response = controller.getStats();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("disabled", response.getBody().get("mode"));
        assertEquals(0L, response.getBody().get("totalEvents"));
        assertFalse((Boolean) response.getBody().get("storeActive"));
        // No hasRepositoryOverrides when both disabled and inactive
        assertNull(response.getBody().get("hasRepositoryOverrides"));
    }

    @Test
    void getStatsSurfacesOverridesWhenDisabledButActive() {
        when(store.countByProcessType()).thenReturn(Map.of());
        when(lineageConfig.getTargets()).thenReturn(List.of("purview"));
        when(store.countNonTerminalByTarget("purview")).thenReturn(3L);
        when(store.isActive()).thenReturn(true);
        when(lineageConfig.getMode()).thenReturn(LineageMode.DISABLED);

        ResponseEntity<Map<String, Object>> response = controller.getStats();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("disabled", response.getBody().get("mode"));
        assertTrue((Boolean) response.getBody().get("storeActive"));
        assertTrue((Boolean) response.getBody().get("hasRepositoryOverrides"));
    }

    // ==================== GET /metrics (D-12) ====================

    @Test
    void getMetricsReturnsSnapshotAndBacklog() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("eventsPublished", 100L);
        snapshot.put("eventsFailed", 5L);
        when(lineageMetrics.snapshot()).thenReturn(snapshot);
        when(lineageConfig.getTargets()).thenReturn(List.of("purview"));
        when(store.countNonTerminalByTarget("purview")).thenReturn(10L);
        when(lineageConfig.getBacklogMaxDocs()).thenReturn(50000);
        when(store.getEstimatedNonTerminalSizeBytes("purview")).thenReturn(1024L);

        ResponseEntity<Map<String, Object>> response = controller.getMetrics();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertEquals(100L, body.get("eventsPublished"));
        assertEquals(5L, body.get("eventsFailed"));

        @SuppressWarnings("unchecked")
        Map<String, Object> backlog = (Map<String, Object>) body.get("backlog");
        assertNotNull(backlog);
        @SuppressWarnings("unchecked")
        Map<String, Object> purviewBacklog = (Map<String, Object>) backlog.get("purview");
        assertEquals(10L, purviewBacklog.get("nonTerminal"));
        assertEquals(50000, purviewBacklog.get("maxDocs"));
        assertEquals(1024L, purviewBacklog.get("estimatedSizeBytes"));
    }

    @Test
    void getMetricsRejectsForbiddenUser() {
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.FALSE);

        ResponseEntity<Map<String, Object>> response = controller.getMetrics();

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void getMetricsHandlesNullMetrics() throws Exception {
        setField(controller, "lineageMetrics", null);
        when(lineageConfig.getTargets()).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.getMetrics();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    // ==================== Dead-letter endpoints (D-12) ====================

    @Test
    void listDeadLettersReturnsRecords() {
        List<Map<String, Object>> records = List.of(
                Map.of("eventId", "dl-1", "reason", "timeout"),
                Map.of("eventId", "dl-2", "reason", "connection refused")
        );
        when(deadLetterStore.findAll(50, 0, null)).thenReturn(records);
        when(deadLetterStore.count(null)).thenReturn(2L);

        ResponseEntity<Map<String, Object>> response = controller.listDeadLetters(50, 0, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> deadLetters = (List<Map<String, Object>>) response.getBody().get("deadLetters");
        assertEquals(2, deadLetters.size());
        assertEquals(2L, response.getBody().get("total"));
    }

    @Test
    void listDeadLettersFiltersByReplayed() {
        when(deadLetterStore.findAll(50, 0, false)).thenReturn(List.of());
        when(deadLetterStore.count(false)).thenReturn(0L);

        ResponseEntity<Map<String, Object>> response = controller.listDeadLetters(50, 0, false);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(deadLetterStore).findAll(50, 0, false);
    }

    @Test
    void listDeadLettersRejectsForbiddenUser() {
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.FALSE);

        ResponseEntity<Map<String, Object>> response = controller.listDeadLetters(50, 0, null);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void listDeadLettersReturnsBadRequestWhenStoreUnavailable() throws Exception {
        setField(controller, "deadLetterStore", null);

        ResponseEntity<Map<String, Object>> response = controller.listDeadLetters(50, 0, null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void getDeadLetterReturnsRecord() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("eventId", "dl-1");
        record.put("reason", "timeout");
        when(deadLetterStore.findByEventId("dl-1")).thenReturn(record);

        ResponseEntity<Map<String, Object>> response = controller.getDeadLetter("dl-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("dl-1", response.getBody().get("eventId"));
    }

    @Test
    void getDeadLetterReturns404WhenNotFound() {
        when(deadLetterStore.findByEventId("nonexistent")).thenReturn(null);

        ResponseEntity<Map<String, Object>> response = controller.getDeadLetter("nonexistent");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void getDeadLetterReturnsBadRequestWhenStoreUnavailable() throws Exception {
        setField(controller, "deadLetterStore", null);

        ResponseEntity<Map<String, Object>> response = controller.getDeadLetter("dl-1");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void replayDeadLetterSucceeds() {
        when(deadLetterStore.replay("dl-1", store)).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.replayDeadLetter("dl-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("ok", response.getBody().get("status"));
    }

    @Test
    void replayDeadLetterFailsWhenNotFound() {
        when(deadLetterStore.replay("dl-1", store)).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = controller.replayDeadLetter("dl-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("error", response.getBody().get("status"));
    }

    @Test
    void replayDeadLetterReturnsBadRequestWhenStoreUnavailable() throws Exception {
        setField(controller, "deadLetterStore", null);

        ResponseEntity<Map<String, Object>> response = controller.replayDeadLetter("dl-1");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void replayAllDeadLettersReturnsCount() {
        when(deadLetterStore.replayAll(store)).thenReturn(5);

        ResponseEntity<Map<String, Object>> response = controller.replayAllDeadLetters();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("ok", response.getBody().get("status"));
        assertEquals(5, response.getBody().get("replayed"));
    }

    @Test
    void replayAllDeadLettersReturnsBadRequestWhenStoreUnavailable() throws Exception {
        setField(controller, "deadLetterStore", null);

        ResponseEntity<Map<String, Object>> response = controller.replayAllDeadLetters();

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void countDeadLettersReturnsCount() {
        when(deadLetterStore.count(null)).thenReturn(42L);

        ResponseEntity<Map<String, Object>> response = controller.countDeadLetters(null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(42L, response.getBody().get("count"));
    }

    @Test
    void countDeadLettersFiltersByReplayed() {
        when(deadLetterStore.count(false)).thenReturn(10L);

        ResponseEntity<Map<String, Object>> response = controller.countDeadLetters(false);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(10L, response.getBody().get("count"));
    }

    @Test
    void countDeadLettersReturnsBadRequestWhenStoreUnavailable() throws Exception {
        setField(controller, "deadLetterStore", null);

        ResponseEntity<Map<String, Object>> response = controller.countDeadLetters(null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    // ==================== Helpers ====================

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static java.util.List<jp.aegif.nemaki.rest.purview.journal.LineageJournalRow> rows(
            LineageEvent... events) {
        java.util.List<jp.aegif.nemaki.rest.purview.journal.LineageJournalRow> rows =
                new java.util.ArrayList<>();
        for (LineageEvent event : events) {
            rows.add(new jp.aegif.nemaki.rest.purview.journal.LineageJournalRow.Decoded(
                    jp.aegif.nemaki.rest.purview.journal.LineageJournalEntry.ofV1(event)));
        }
        return rows;
    }

    private static jp.aegif.nemaki.rest.purview.journal.LineageJournalRow row(LineageEvent event) {
        return new jp.aegif.nemaki.rest.purview.journal.LineageJournalRow.Decoded(
                jp.aegif.nemaki.rest.purview.journal.LineageJournalEntry.ofV1(event));
    }

}
