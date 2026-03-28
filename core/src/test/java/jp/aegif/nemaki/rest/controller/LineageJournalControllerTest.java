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
    private HttpServletRequest httpRequest;
    private CallContext callContext;

    @BeforeEach
    void setUp() throws Exception {
        controller = new LineageJournalController();
        store = mock(LineageJournalStore.class);
        lineageConfig = mock(LineageConfig.class);
        httpRequest = mock(HttpServletRequest.class);
        callContext = mock(CallContext.class);

        when(httpRequest.getAttribute("CallContext")).thenReturn(callContext);
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);

        setField(controller, "journalStore", store);
        setField(controller, "lineageConfig", lineageConfig);
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
        when(store.findAll(50, 0)).thenReturn(List.of(event));

        ResponseEntity<Map<String, Object>> response = controller.listEvents(null, null, 50, 0);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) response.getBody().get("events");
        assertEquals(1, events.size());
        assertEquals("bedroom", events.get(0).get("repositoryId"));
        assertEquals("ARCHIVE_LOCAL", events.get(0).get("processType"));
    }

    @Test
    void listEventsFiltersByRepositoryId() {
        when(store.findByRepositoryId("bedroom", 50, 0)).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.listEvents("bedroom", null, 50, 0);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(store).findByRepositoryId("bedroom", 50, 0);
    }

    @Test
    void listEventsFiltersByProcessType() {
        when(store.findByProcessType(LineageProcessType.ARCHIVE_LOCAL, 50, 0)).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.listEvents(null, "ARCHIVE_LOCAL", 50, 0);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(store).findByProcessType(LineageProcessType.ARCHIVE_LOCAL, 50, 0);
    }

    @Test
    void listEventsFiltersByBothRepoAndProcessType() {
        when(store.findByProcessType("bedroom", LineageProcessType.ARCHIVE_LOCAL, 50, 0)).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.listEvents("bedroom", "ARCHIVE_LOCAL", 50, 0);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(store).findByProcessType("bedroom", LineageProcessType.ARCHIVE_LOCAL, 50, 0);
    }

    @Test
    void listEventsPassesOffsetToFilteredQuery() {
        when(store.findByRepositoryId("bedroom", 50, 10)).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.listEvents("bedroom", null, 50, 10);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(store).findByRepositoryId("bedroom", 50, 10);
    }

    @Test
    void listEventsRejectsForbiddenUser() {
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.FALSE);

        ResponseEntity<Map<String, Object>> response = controller.listEvents(null, null, 50, 0);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void listEventsRejectsInvalidProcessType() {
        ResponseEntity<Map<String, Object>> response = controller.listEvents(null, "INVALID_TYPE", 50, 0);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void listEventsCapsLimitAt200() {
        when(store.findAll(200, 0)).thenReturn(List.of());

        controller.listEvents(null, null, 999, 0);

        verify(store).findAll(200, 0);
    }

    // ==================== GET /events/{eventId} ====================

    @Test
    void getEventReturnsEventWhenFound() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.IMPORT_FILESYSTEM)
                .build();
        when(store.findByEventId("evt-123")).thenReturn(event);

        ResponseEntity<Map<String, Object>> response = controller.getEvent("evt-123");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("bedroom", response.getBody().get("repositoryId"));
    }

    @Test
    void getEventReturns404WhenNotFound() {
        when(store.findByEventId("nonexistent")).thenReturn(null);

        ResponseEntity<Map<String, Object>> response = controller.getEvent("nonexistent");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void getEventRejectsForbiddenUser() {
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.FALSE);

        ResponseEntity<Map<String, Object>> response = controller.getEvent("evt-123");

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    // ==================== GET /stats ====================

    @Test
    void getStatsReturnsAggregatedData() {
        Map<LineageProcessType, Long> counts = new LinkedHashMap<>();
        counts.put(LineageProcessType.ARCHIVE_LOCAL, 80L);
        counts.put(LineageProcessType.IMPORT_FILESYSTEM, 30L);
        counts.put(LineageProcessType.EXPORT_ZIP_FOLDER, 32L);
        when(store.countByProcessType()).thenReturn(counts);
        when(store.countNonTerminalByTarget("purview")).thenReturn(5L);
        when(store.isActive()).thenReturn(true);
        when(lineageConfig.getMode()).thenReturn(LineageMode.JOURNALED);

        ResponseEntity<Map<String, Object>> response = controller.getStats();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertEquals("journaled", body.get("mode"));
        assertEquals(142L, body.get("totalEvents"));
        assertEquals(5L, body.get("nonTerminalCount"));
        assertTrue((Boolean) body.get("storeActive"));

        @SuppressWarnings("unchecked")
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
        when(store.countNonTerminalByTarget("purview")).thenReturn(0L);
        when(store.isActive()).thenReturn(false);
        when(lineageConfig.getMode()).thenReturn(LineageMode.DISABLED);

        ResponseEntity<Map<String, Object>> response = controller.getStats();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("disabled", response.getBody().get("mode"));
        assertEquals(0L, response.getBody().get("totalEvents"));
        assertFalse((Boolean) response.getBody().get("storeActive"));
    }

    // ==================== Helpers ====================

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
