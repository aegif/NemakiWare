package jp.aegif.nemaki.rest.controller;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.rest.purview.journal.LineageConfig;
import jp.aegif.nemaki.rest.purview.journal.LineageEvent;
import jp.aegif.nemaki.rest.purview.journal.LineageJournalStore;
import jp.aegif.nemaki.rest.purview.journal.LineageProcessType;
import jp.aegif.nemaki.util.constant.CallContextKey;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Admin REST API for browsing lineage journal events.
 *
 * <p>All endpoints require admin authentication. Events are read-only;
 * mutation is handled by the journal store and emitter pipeline.
 */
@RestController
@RequestMapping("/v1/admin/lineage-journal")
@CrossOrigin(origins = "*", maxAge = 3600)
public class LineageJournalController {

    private static final Logger logger = LoggerFactory.getLogger(LineageJournalController.class);

    @Autowired
    private LineageJournalStore journalStore;

    @Autowired
    private LineageConfig lineageConfig;

    private HttpServletRequest httpRequest;

    @Autowired
    public void setHttpRequest(HttpServletRequest httpRequest) {
        this.httpRequest = httpRequest;
    }

    // ==================== GET /events ====================

    @GetMapping("/events")
    public ResponseEntity<Map<String, Object>> listEvents(
            @RequestParam(required = false) String repositoryId,
            @RequestParam(required = false) String processType,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        int cappedLimit = Math.min(Math.max(limit, 1), 200);
        int safeOffset = Math.max(offset, 0);

        List<LineageEvent> events;

        if (repositoryId != null && !repositoryId.isBlank() && processType != null && !processType.isBlank()) {
            // Filter by both repositoryId and processType
            LineageProcessType pt;
            try {
                pt = LineageProcessType.valueOf(processType);
            } catch (IllegalArgumentException e) {
                return badRequest("Invalid processType: " + processType);
            }
            events = journalStore.findByProcessType(repositoryId, pt, cappedLimit, safeOffset);
        } else if (repositoryId != null && !repositoryId.isBlank()) {
            // Filter by repositoryId only
            events = journalStore.findByRepositoryId(repositoryId, cappedLimit, safeOffset);
        } else if (processType != null && !processType.isBlank()) {
            // Filter by processType only — server-side view query
            LineageProcessType pt;
            try {
                pt = LineageProcessType.valueOf(processType);
            } catch (IllegalArgumentException e) {
                return badRequest("Invalid processType: " + processType);
            }
            events = journalStore.findByProcessType(pt, cappedLimit, safeOffset);
        } else {
            // No filters — paginated listing
            events = journalStore.findAll(cappedLimit, safeOffset);
        }

        List<Map<String, Object>> eventList = events.stream()
                .map(this::eventToMap)
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("events", eventList);
        response.put("total", eventList.size());
        response.put("limit", cappedLimit);
        response.put("offset", safeOffset);
        return ResponseEntity.ok(response);
    }

    // ==================== GET /events/{eventId} ====================

    @GetMapping("/events/{eventId}")
    public ResponseEntity<Map<String, Object>> getEvent(@PathVariable String eventId) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        LineageEvent event = journalStore.findByEventId(eventId);
        if (event == null) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "error");
            response.put("message", "Event not found: " + eventId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }

        return ResponseEntity.ok(eventToMap(event));
    }

    // ==================== GET /stats ====================

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        Map<LineageProcessType, Long> byProcessType = journalStore.countByProcessType();
        long totalEvents = byProcessType.values().stream().mapToLong(Long::longValue).sum();
        long nonTerminalCount = journalStore.countNonTerminalByTarget("purview");

        Map<String, Long> byProcessTypeStrings = byProcessType.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> e.getKey().name(),
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("mode", lineageConfig.getMode().name().toLowerCase());
        response.put("totalEvents", totalEvents);
        response.put("nonTerminalCount", nonTerminalCount);
        response.put("byProcessType", byProcessTypeStrings);
        response.put("storeActive", journalStore.isActive());
        return ResponseEntity.ok(response);
    }

    // ==================== Helpers ====================

    private Map<String, Object> eventToMap(LineageEvent event) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("eventId", event.eventId());
        map.put("eventKey", event.eventKey());
        map.put("repositoryId", event.repositoryId());
        map.put("processType", event.processType() != null ? event.processType().name() : null);
        map.put("occurredAt", event.occurredAt());
        map.put("inputs", event.inputs());
        map.put("outputs", event.outputs());
        map.put("snapshotAttributes", event.snapshotAttributes());

        Map<String, String> statusMap = new LinkedHashMap<>();
        event.publishStatusByTarget().forEach((k, v) -> statusMap.put(k, v.name()));
        map.put("publishStatusByTarget", statusMap);
        return map;
    }

    private ResponseEntity<Map<String, Object>> requireAdminOrForbidden() {
        if (!isAdmin()) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "error");
            response.put("message", "Admin access required");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        }
        return null;
    }

    private boolean isAdmin() {
        if (httpRequest == null) {
            return false;
        }
        CallContext callContext = (CallContext) httpRequest.getAttribute("CallContext");
        if (callContext == null) {
            return false;
        }
        Boolean isAdmin = (Boolean) callContext.get(CallContextKey.IS_ADMIN);
        return isAdmin != null && isAdmin;
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "error");
        response.put("message", message);
        return ResponseEntity.badRequest().body(response);
    }
}
