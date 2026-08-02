package jp.aegif.nemaki.rest.controller;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.rest.purview.journal.LineageConfig;
import jp.aegif.nemaki.rest.purview.journal.LineageDeadLetterStore;
import jp.aegif.nemaki.rest.purview.journal.LineageAssetRef;
import jp.aegif.nemaki.rest.purview.journal.LineageEvent;
import jp.aegif.nemaki.rest.purview.journal.LineageRecord;
import jp.aegif.nemaki.rest.purview.journal.LineageJournalStore;
import jp.aegif.nemaki.rest.purview.journal.LineageMetrics;
import jp.aegif.nemaki.rest.purview.journal.LineageProcessType;
import jp.aegif.nemaki.util.constant.CallContextKey;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.format.DateTimeParseException;
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
public class LineageJournalController {

    private static final Logger logger = LoggerFactory.getLogger(LineageJournalController.class);

    @Autowired
    private LineageJournalStore journalStore;

    @Autowired
    private LineageConfig lineageConfig;

    @Autowired(required = false)
    private LineageMetrics lineageMetrics;

    @Autowired(required = false)
    private LineageDeadLetterStore deadLetterStore;

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
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        int cappedLimit = Math.min(Math.max(limit, 1), 200);
        int safeOffset = Math.max(offset, 0);

        // Fetch limit+1 to detect whether more results exist beyond this page
        int fetchLimit = cappedLimit + 1;
        List<jp.aegif.nemaki.rest.purview.journal.LineageJournalRow> events;

        // Date range filter takes priority
        if (startDate != null && !startDate.isBlank() && endDate != null && !endDate.isBlank()) {
            try {
                Instant.parse(startDate);
                Instant.parse(endDate);
            } catch (DateTimeParseException e) {
                return badRequest("Invalid date format (expected ISO-8601): " + e.getMessage());
            }
            events = journalStore.findByDateRange(startDate, endDate, fetchLimit, safeOffset);
        } else if (repositoryId != null && !repositoryId.isBlank() && processType != null && !processType.isBlank()) {
            // Filter by both repositoryId and processType
            LineageProcessType pt;
            try {
                pt = LineageProcessType.valueOf(processType);
            } catch (IllegalArgumentException e) {
                return badRequest("Invalid processType: " + processType);
            }
            events = journalStore.findByProcessType(repositoryId, pt, fetchLimit, safeOffset);
        } else if (repositoryId != null && !repositoryId.isBlank()) {
            // Filter by repositoryId only
            events = journalStore.findByRepositoryId(repositoryId, fetchLimit, safeOffset);
        } else if (processType != null && !processType.isBlank()) {
            // Filter by processType only — server-side view query
            LineageProcessType pt;
            try {
                pt = LineageProcessType.valueOf(processType);
            } catch (IllegalArgumentException e) {
                return badRequest("Invalid processType: " + processType);
            }
            events = journalStore.findByProcessType(pt, fetchLimit, safeOffset);
        } else {
            // No filters — paginated listing
            events = journalStore.findAll(fetchLimit, safeOffset);
        }

        boolean hasMore = events.size() > cappedLimit;
        List<jp.aegif.nemaki.rest.purview.journal.LineageJournalRow> pageEvents =
                hasMore ? events.subList(0, cappedLimit) : events;

        List<Map<String, Object>> eventList = pageEvents.stream()
                .map(this::rowToDisplayMap)
                .toList();

        // Provide a total estimate for the UI pagination component.
        // Exact total would require a separate count query; the limit+1 approach
        // guarantees the "next page" button appears when more results exist.
        int estimatedTotal = hasMore ? safeOffset + cappedLimit + 1 : safeOffset + eventList.size();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("events", eventList);
        response.put("total", estimatedTotal);
        response.put("hasMore", hasMore);
        response.put("limit", cappedLimit);
        response.put("offset", safeOffset);
        return ResponseEntity.ok(response);
    }

    // ==================== GET /events/{eventId} ====================

    /**
     * The path variable is a <b>record id</b> — v1's eventId, v2's deliveryId. For every v1 row
     * the two are the same value, so nothing a client held before this rename stopped working;
     * for a v2 row the record id is the only value that addresses the document, and it is what
     * the list response has carried as {@code recordId} since Slice 2b.
     */
    @GetMapping("/events/{recordId}")
    public ResponseEntity<Map<String, Object>> getEvent(@PathVariable String recordId) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        jp.aegif.nemaki.rest.purview.journal.LineageJournalRow row =
                journalStore.findByRecordId(recordId);
        if (row == null) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "error");
            response.put("message", "Event not found: " + recordId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }

        return ResponseEntity.ok(rowToDisplayMap(row));
    }

    // ==================== GET /stats ====================

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        Map<LineageProcessType, Long> byProcessType = journalStore.countByProcessType();
        long totalEvents = byProcessType.values().stream().mapToLong(Long::longValue).sum();

        // Build per-target backlog instead of hardcoding "purview"
        List<String> targets = lineageConfig.getTargets();
        Map<String, Long> nonTerminalByTarget = new LinkedHashMap<>();
        for (String target : targets) {
            nonTerminalByTarget.put(target, journalStore.countNonTerminalByTarget(target));
        }

        Map<String, Long> byProcessTypeStrings = byProcessType.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> e.getKey().name(),
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new));

        String globalMode = lineageConfig.getMode().name().toLowerCase();
        boolean storeActive = journalStore.isActive();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("mode", globalMode);
        response.put("totalEvents", totalEvents);
        response.put("nonTerminalByTarget", nonTerminalByTarget);
        response.put("byProcessType", byProcessTypeStrings);
        response.put("storeActive", storeActive);
        response.put("targets", targets);
        // When global mode is disabled but the store is active, repository
        // overrides must be enabling journaling. Surface this so the UI does
        // not show a misleading "disabled" status.
        if ("disabled".equals(globalMode) && storeActive) {
            response.put("hasRepositoryOverrides", true);
        }
        return ResponseEntity.ok(response);
    }

    // ==================== Event actions ====================

    @PostMapping("/events/{recordId}/replay")
    public ResponseEntity<Map<String, Object>> replayEvent(
            @PathVariable String recordId,
            @RequestParam(defaultValue = "purview") String target) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        jp.aegif.nemaki.rest.purview.journal.LineageJournalRow row =
                journalStore.findByRecordId(recordId);
        if (row == null) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "error");
            response.put("message", "Event not found: " + recordId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
        if (row instanceof jp.aegif.nemaki.rest.purview.journal.LineageJournalRow.Undecodable u) {
            // Resetting an undecodable row to PENDING would hand the projector a row it can only
            // surface and never publish — a replay that cannot succeed is refused, with the reason.
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "error");
            response.put("message", "Row cannot be replayed — it does not decode: " + u.reason());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }

        // Reset status to PENDING for the target to allow re-projection
        int updated = journalStore.updatePublishStatus(recordId, target,
                jp.aegif.nemaki.rest.purview.journal.LineagePublishStatus.PENDING);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", updated > 0 ? "ok" : "error");
        response.put("message", updated > 0 ? "Event queued for replay" : "Failed to reset status");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/events/{recordId}/discard")
    public ResponseEntity<Map<String, Object>> discardEvent(
            @PathVariable String recordId,
            @RequestParam(defaultValue = "purview") String target) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        // Same guard as replay, for a harder reason: discard makes the row terminal, terminal
        // rows are purge-eligible, and an undecodable row's stored document is the only evidence
        // of what it was. Without this check, one admin call destroys it politely. The status
        // flip itself would succeed — it mutates the raw document without decoding — which is
        // exactly why the refusal has to live here.
        jp.aegif.nemaki.rest.purview.journal.LineageJournalRow existing =
                journalStore.findByRecordId(recordId);
        if (existing == null) {
            // Null is "absent" OR "the read failed" — findByRecordId cannot say which. Proceeding
            // on null would let a transient read error bypass the undecodable guard above and
            // discard the very row the guard protects. Fail closed; a real absence gets its 404,
            // a transient error gets retried by the operator.
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "error");
            response.put("message", "Event not found (or not readable right now): " + recordId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
        if (existing instanceof jp.aegif.nemaki.rest.purview.journal.LineageJournalRow.Undecodable u) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "error");
            response.put("message", "Row cannot be discarded — it does not decode, and discard"
                    + " would make its only stored copy purge-eligible: " + u.reason());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }

        int updated = journalStore.discardEvent(recordId, target);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", updated > 0 ? "ok" : "error");
        response.put("message", updated > 0 ? "Event discarded" : "Failed to discard");
        return ResponseEntity.ok(response);
    }

    // ==================== Dead-letter endpoints ====================

    @GetMapping("/dead-letters")
    public ResponseEntity<Map<String, Object>> listDeadLetters(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false) Boolean replayed) {

        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        if (deadLetterStore == null) {
            return badRequest("Dead-letter store not available");
        }

        List<Map<String, Object>> records = deadLetterStore.findAll(limit, offset, replayed);
        long total = deadLetterStore.count(replayed);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("deadLetters", records);
        response.put("total", total);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/dead-letters/{eventId}")
    public ResponseEntity<Map<String, Object>> getDeadLetter(@PathVariable String eventId) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        if (deadLetterStore == null) {
            return badRequest("Dead-letter store not available");
        }

        Map<String, Object> record = deadLetterStore.findByEventId(eventId);
        if (record == null) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "error");
            response.put("message", "Dead-letter not found: " + eventId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
        return ResponseEntity.ok(record);
    }

    @PostMapping("/dead-letters/{eventId}/replay")
    public ResponseEntity<Map<String, Object>> replayDeadLetter(@PathVariable String eventId) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        if (deadLetterStore == null) {
            return badRequest("Dead-letter store not available");
        }

        boolean success = deadLetterStore.replay(eventId, journalStore);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", success ? "ok" : "error");
        response.put("message", success ? "Event replayed" : "Failed to replay (not found or already replayed)");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/dead-letters/replay-all")
    public ResponseEntity<Map<String, Object>> replayAllDeadLetters() {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        if (deadLetterStore == null) {
            return badRequest("Dead-letter store not available");
        }

        int count = deadLetterStore.replayAll(journalStore);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("replayed", count);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/dead-letters/count")
    public ResponseEntity<Map<String, Object>> countDeadLetters(
            @RequestParam(required = false) Boolean replayed) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        if (deadLetterStore == null) {
            return badRequest("Dead-letter store not available");
        }

        long count = deadLetterStore.count(replayed);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", count);
        return ResponseEntity.ok(response);
    }

    // ==================== GET /metrics ====================

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        Map<String, Object> response = new LinkedHashMap<>();
        if (lineageMetrics != null) {
            response.putAll(lineageMetrics.snapshot());
        }

        // Add backlog info per target
        Map<String, Object> backlog = new LinkedHashMap<>();
        for (String target : lineageConfig.getTargets()) {
            Map<String, Object> targetBacklog = new LinkedHashMap<>();
            targetBacklog.put("nonTerminal", journalStore.countNonTerminalByTarget(target));
            targetBacklog.put("maxDocs", lineageConfig.getBacklogMaxDocs());
            targetBacklog.put("estimatedSizeBytes", journalStore.getEstimatedNonTerminalSizeBytes(target));
            backlog.put(target, targetBacklog);
        }
        response.put("backlog", backlog);
        return ResponseEntity.ok(response);
    }

    // ==================== Helpers ====================

    /**
     * The display shape of one journal record.
     *
     * <h2>Old keys kept, new keys added</h2>
     *
     * <p>{@code eventKey} and {@code snapshotAttributes} stay, so the shipped UI and the Playwright
     * spec keep working unchanged. But they cannot be the whole contract: on a v2 record
     * {@code processIdentity} is a {@code processKey}, and putting it under a key named
     * {@code eventKey} would be a lie that the admin page then displays. So the version-neutral
     * names are added alongside, and the old ones are documented as v1 aliases.
     *
     * <p>{@code recordId} is added now rather than at Slice 2d for the same reason: after the store
     * switches, a client needs to name the delivery it is talking about, and adding the field then
     * would be a second API-shape change on a page that has already been rewritten once.
     *
     * <h2>Why the assets appear twice</h2>
     *
     * <p>{@code inputs}/{@code outputs} remain flat qualified-name strings for compatibility.
     * Alone they would collapse the three reference kinds into one: an administrator looking at a
     * publication that failed with "unresolved asset" would have no way to see <em>which</em> asset
     * or <em>why</em>, which is the question Slice 2a's failure message raises. So
     * {@code inputAssets}/{@code outputAssets} carry the discriminant, the kind and the reason.
     */
    private Map<String, Object> recordToMap(LineageRecord record) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("eventId", record.eventId());
        map.put("recordId", record.recordId());
        map.put("schemaVersion", record.schemaVersion());
        map.put("idempotencyKeyVersion", record.idempotencyKeyVersion());
        map.put("processIdentity", record.processIdentity());
        // v1 alias for processIdentity. Retained for existing clients; on a v2 record it holds the
        // processKey, which is why processIdentity above is the field to read.
        map.put("eventKey", record.processIdentity());
        map.put("repositoryId", record.repositoryId());
        map.put("processType", record.processType() != null ? record.processType().name() : null);
        map.put("occurredAt", record.occurredAt());
        map.put("inputs", qualifiedNames(record.inputs()));
        map.put("outputs", qualifiedNames(record.outputs()));
        map.put("inputAssets", assetMaps(record.inputs()));
        map.put("outputAssets", assetMaps(record.outputs()));
        // v1 alias for legacyEventAttributes; empty on a v2 record, whose attributes are on the
        // assets above rather than on the event.
        map.put("snapshotAttributes", record.legacyEventAttributes());

        Map<String, String> statusMap = new LinkedHashMap<>();
        record.publishStatusByTarget().forEach((k, v) -> statusMap.put(k, v.name()));
        map.put("publishStatusByTarget", statusMap);
        return map;
    }

    private static List<String> qualifiedNames(List<LineageAssetRef> refs) {
        return refs.stream().map(LineageAssetRef::qualifiedName).toList();
    }

    private static List<Map<String, Object>> assetMaps(List<LineageAssetRef> refs) {
        return refs.stream().map(LineageJournalController::assetMap).toList();
    }

    private static Map<String, Object> assetMap(LineageAssetRef ref) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("qualifiedName", ref.qualifiedName());
        switch (ref) {
            case LineageAssetRef.Typed typed -> {
                map.put("resolution", "TYPED");
                map.put("kind", typed.kind().name());
                map.put("atlasTypeName", typed.kind().atlasTypeName());
                map.put("attributes", typed.attributes());
            }
            case LineageAssetRef.LegacyName ignored -> {
                map.put("resolution", "LEGACY_NAME");
                map.put("kind", null);
                map.put("atlasTypeName", null);
                map.put("attributes", Map.of());
            }
            case LineageAssetRef.Unresolved unresolved -> {
                map.put("resolution", "UNRESOLVED");
                map.put("kind", null);
                map.put("atlasTypeName", null);
                map.put("attributes", Map.of());
                map.put("unresolvedReason", unresolved.reason());
            }
        }
        return map;
    }

    /**
     * Renders one journal row: the record's full display shape, or — for a row the store could
     * not decode — a diagnostic stub naming what is known and why it failed. Since Slice 2d-2 the
     * store performs decode and projection per row, so the whole list can no longer be taken down
     * by one broken document; the broken one renders as itself.
     */
    private Map<String, Object> rowToDisplayMap(
            jp.aegif.nemaki.rest.purview.journal.LineageJournalRow row) {
        return switch (row) {
            case jp.aegif.nemaki.rest.purview.journal.LineageJournalRow.Decoded decoded ->
                    recordToMap(decoded.entry().record());
            case jp.aegif.nemaki.rest.purview.journal.LineageJournalRow.Undecodable u -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("recordId", u.documentId() != null
                        && u.documentId().startsWith("lineage:")
                        ? u.documentId().substring("lineage:".length()) : null);
                map.put("documentId", u.documentId());
                map.put("documentType", u.documentType());
                map.put("schemaVersion", u.schemaVersion());
                map.put("unprojectable", true);
                map.put("unprojectableReason", u.reason());
                yield map;
            }
        };
    }

    // ==================== D-rest-2: fenced sequencer admin entry (disabled by default) ====

    @Autowired(required = false)
    private jp.aegif.nemaki.rest.purview.journal.LineageSequencerAdminService sequencerAdmin;

    /**
     * Manual, node-local sequencer run. Refuses with 409 while the aggregate D-rest readiness
     * gate is not fully green (switch off, invalid config, view-signature drift, or an
     * unverifiable configured target) — the refusal names every violation.
     */
    @PostMapping("/sequencer/{repositoryId}/run")
    public ResponseEntity<Map<String, Object>> runSequencer(
            @PathVariable("repositoryId") String repositoryId) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;
        if (sequencerAdmin == null) {
            return badRequest("sequencer admin service unavailable");
        }
        var outcome = sequencerAdmin.run(repositoryId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("repositoryId", repositoryId);
        if (!outcome.ran()) {
            response.put("enabled", false);
            response.put("violations", outcome.violations());
            response.put("message", "D-rest readiness gate is not green — the sequencer must"
                    + " not create ordered barriers under a red gate");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }
        var summary = outcome.summary();
        response.put("enabled", true);
        response.put("health", summary.health().name());
        response.put("finalized", summary.finalized());
        response.put("reclaimed", summary.reclaimed());
        response.put("backlog", summary.backlog());
        response.put("lostLease", summary.lostLease());
        return ResponseEntity.ok(response);
    }

    /** Read-only sequencer status; available while disabled (diagnostics). */
    @GetMapping("/sequencer/{repositoryId}")
    public ResponseEntity<Map<String, Object>> sequencerStatus(
            @PathVariable("repositoryId") String repositoryId) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;
        if (sequencerAdmin == null) {
            return badRequest("sequencer admin service unavailable");
        }
        try {
            Map<String, Object> status = sequencerAdmin.status(repositoryId);
            status.put("repositoryId", repositoryId);
            return ResponseEntity.ok(status);
        } catch (IllegalStateException infra) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "error");
            response.put("message", infra.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
        }
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
