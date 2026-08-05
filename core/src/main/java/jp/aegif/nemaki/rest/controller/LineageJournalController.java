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
        ResponseEntity<Map<String, Object>> notAdmitted = requireAdmittedReader();
        if (notAdmitted != null) return notAdmitted;

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

        // §8-d (D-rest-3): a v2 row routes to the generation-CAS replay machine; the v1
        // branch below stays byte-identical (including its HTTP-200 failure shape).
        if (row instanceof jp.aegif.nemaki.rest.purview.journal.LineageJournalRow.Decoded d
                && d.entry().envelope()
                        instanceof jp.aegif.nemaki.rest.purview.journal.LineageJournalEntry.V2) {
            return replayV2(recordId, target);
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
        ResponseEntity<Map<String, Object>> notAdmittedDiscard = requireAdmittedReader();
        if (notAdmittedDiscard != null) return notAdmittedDiscard;

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

    // ==================== D-rest-3: §8-d replay machine (v2 rows) ====

    @Autowired(required = false)
    private jp.aegif.nemaki.rest.purview.journal.LineageReplayService replayService;

    private ResponseEntity<Map<String, Object>> replayV2(String recordId, String target) {
        ResponseEntity<Map<String, Object>> notAdmitted = requireAdmittedReader();
        if (notAdmitted != null) return notAdmitted;
        if (replayService == null) {
            return badRequest("replay service unavailable");
        }
        var outcome = replayService.execute(recordId, target);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("recordId", recordId);
        response.put("target", target);
        response.put("state", outcome.state());
        switch (outcome.state()) {
            case "ACKED" -> {
                response.put("generation", outcome.generation());
                response.put("requestId", outcome.requestId());
                response.put("compensationDeliveryId", outcome.compensationDeliveryId());
                return ResponseEntity.ok(response);
            }
            case "NOT_READY" -> {
                response.put("violations", outcome.violations());
                response.put("message", "D-rest readiness gate is not green");
                return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
            }
            case "REFUSED", "INDETERMINATE" -> {
                response.put("message", outcome.message());
                return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
            }
            default -> { // FAILED — the design's admin-route rule: collision = 500
                response.put("message", outcome.message());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }
        }
    }

    /** Manual §8-d crash recovery trigger (the automatic pass runs per gated leader poll). */
    @PostMapping("/replay-recovery")
    public ResponseEntity<Map<String, Object>> replayRecovery(
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;
        ResponseEntity<Map<String, Object>> notAdmittedRecovery = requireAdmittedReader();
        if (notAdmittedRecovery != null) return notAdmittedRecovery;
        if (replayService == null) {
            return badRequest("replay service unavailable");
        }
        int bounded = Math.min(Math.max(limit, 1), 500);
        var outcome = replayService.recoverUnackedOutcome(bounded);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("limit", bounded);
        if (!outcome.ready()) {
            // Dormancy must be distinguishable from an empty queue (F4).
            response.put("violations", outcome.violations());
            response.put("message", "D-rest readiness gate is not green — recovery is"
                    + " dormant");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }
        response.put("recovered", outcome.recovered());
        response.put("moreRemaining", outcome.moreRemaining());
        return ResponseEntity.ok(response);
    }

    // ==================== D-rest-4: manual spool scan ====

    @Autowired(required = false)
    private jp.aegif.nemaki.rest.purview.journal.LineageProjectionLoop projectionLoop;

    @Autowired(required = false)
    private jp.aegif.nemaki.rest.purview.journal.LineageDrestReadiness drestReadinessBean;

    @Autowired(required = false)
    private jp.aegif.nemaki.rest.purview.journal.LineageReaderAdmission readerAdmission;

    @Autowired(required = false)
    private jp.aegif.nemaki.rest.purview.journal.LineageBarrierService barrierService;

    @Autowired(required = false)
    private jp.aegif.nemaki.rest.purview.journal.LineageBarrierReader barrierReader;

    @Autowired(required = false)
    private jp.aegif.nemaki.rest.purview.journal.LineageBinaryDigest binaryDigest;

    /** Manual bounded spool scan (the automatic pass runs per poll on every node). */
    @PostMapping("/spool-scan")
    public ResponseEntity<Map<String, Object>> spoolScan(
            @RequestParam(name = "maxFiles", defaultValue = "2000") int maxFiles,
            @RequestParam(name = "maxMaterializations", defaultValue = "100")
                    int maxMaterializations,
            @RequestParam(name = "maxMillis", defaultValue = "5000") long maxMillis) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;
        if (projectionLoop == null || drestReadinessBean == null) {
            return badRequest("spool scan unavailable");
        }
        // §6-a admission applies here too (4a). This route is not a side door: a manual scan
        // under a REFUSED reader would materialize v2 rows the automatic pass is forbidden to
        // touch, and it runs on a request thread where the tick's guard never sees it.
        var admission = readerAdmission == null ? null : readerAdmission.evaluate();
        if (admission != null && !admission.admitted()) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("admission", admission.decision().name());
            response.put("violations", admission.violations());
            if (admission.decision()
                    == jp.aegif.nemaki.rest.purview.journal.LineageReaderAdmission.Decision
                            .REFUSED) {
                response.put("message", "the lineage reader is refused on this node — the"
                        + " spool is not touched");
                return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
            }
        }
        var verdict = drestReadinessBean.evaluate();
        if (!verdict.ready()) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("violations", verdict.violations());
            response.put("message", "D-rest readiness gate is not green — the scanner is"
                    + " dormant");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }
        var summary = projectionLoop.runSpoolScan(
                new jp.aegif.nemaki.rest.purview.journal.LineageSpoolScanner.ScanBudget(
                        Math.min(Math.max(maxFiles, 1), 10_000),
                        Math.min(Math.max(maxMaterializations, 1), 1_000),
                        Math.min(Math.max(maxMillis, 1L), 60_000L)),
                // The view THIS request evaluated, pinned for its own scan only.
                admission == null ? null : admission.view());
        Map<String, Object> response = new LinkedHashMap<>();
        if (summary == null) {
            response.put("message", "scanner cannot run on this node (no spool dir or no"
                    + " machinery)");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }
        response.put("verified", summary.verified());
        response.put("acked", summary.acked());
        response.put("alreadyAcked", summary.alreadyAcked());
        response.put("unresolved", summary.unresolved());
        response.put("partial", summary.partial());
        response.put("failed", summary.failed());
        response.put("ackBroken", summary.ackBroken());
        response.put("quarantinedNow", summary.quarantinedNow());
        response.put("alreadyQuarantined", summary.alreadyQuarantined());
        response.put("budgetExhausted", summary.budgetExhausted());
        return ResponseEntity.ok(response);
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
        ResponseEntity<Map<String, Object>> notAdmitted = requireAdmittedReader();
        if (notAdmitted != null) return notAdmitted;
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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private jp.aegif.nemaki.rest.purview.journal.LineageObligationScanner obligationScanner;

    /**
     * Manual obligation pass. The scheduled passes in the projection loop are the ordinary
     * driver; this exists so an operator can force one during diagnosis and see exactly what a
     * pass did. Refuses with 409 while the aggregate D-rest readiness gate is not green,
     * naming the violations — running the settler under a red gate would write catalog
     * entities on a node that has not proven its wiring.
     */
    @PostMapping("/obligations/run")
    public ResponseEntity<Map<String, Object>> runObligations(
            @org.springframework.web.bind.annotation.RequestParam(value = "limit",
                    required = false, defaultValue = "0") int limit) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;
        ResponseEntity<Map<String, Object>> notAdmitted = requireAdmittedReader();
        if (notAdmitted != null) return notAdmitted;
        if (obligationScanner == null) {
            return badRequest("obligation scanner unavailable");
        }
        Map<String, Object> response = new LinkedHashMap<>();
        var readiness = drestReadinessBean == null ? null : drestReadinessBean.evaluate();
        if (readiness == null || !readiness.ready()) {
            response.put("enabled", false);
            response.put("violations", readiness == null ? java.util.List.of("readiness"
                    + " unavailable") : readiness.violations());
            response.put("message", "D-rest readiness gate is not green — the settler must not"
                    + " write catalog entities under a red gate");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }
        var pass = obligationScanner.runBoundedPass(limit);
        response.put("enabled", true);
        response.put("claimed", pass.claimed());
        response.put("resolved", pass.resolved());
        response.put("released", pass.released());
        response.put("gaveUp", pass.gaveUp());
        response.put("reclaimed", pass.reclaimed());
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



    /**
     * §6-a's reader admission, applied at EVERY v2 driver boundary (4a).
     *
     * <p>The projection loop's guard only covers the loop. Replay, recovery and the sequencer
     * are separately wired and separately reachable, so a REFUSED reader could still drive v2
     * state through one of these routes — which is precisely the "the lineage subsystem fails
     * closed" property §6-a asks for. UNDETERMINED counts as not-admitted here: unlike the
     * spool scan, these paths have no way to accumulate safely and decide later.
     *
     * @return a 409 body when the caller must not proceed, or null when it may
     */
    private ResponseEntity<Map<String, Object>> requireAdmittedReader() {
        if (readerAdmission == null) {
            return null;
        }
        var admission = readerAdmission.evaluate();
        if (admission.admitted()) {
            return null;
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("admission", admission.decision().name());
        response.put("violations", admission.violations());
        response.put("message", "the lineage reader is not admitted on this node — v2 state"
                + " must not be driven from here");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    // ==================== §6-a rollout fence (A-2 Slice 4a) ====

    /**
     * What the barrier says, what currently blocks activation, and whether this node's reader
     * is admitted. Reads through the memo so an operator never acts on a stale answer.
     */
    @GetMapping("/barrier")
    public ResponseEntity<Map<String, Object>> barrier() {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;
        if (barrierReader == null || barrierService == null) {
            return badRequest("the barrier machinery is not wired on this node");
        }
        var view = barrierReader.viewUncached();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("requiredCapabilities", barrierService.requiredCapabilities());
        if (view instanceof jp.aegif.nemaki.rest.purview.journal.LineageBarrierReader
                .BarrierView.Present present) {
            var b = present.barrier();
            response.put("present", true);
            response.put("state", b.state().name());
            response.put("generation", b.generation());
            response.put("writeSchemaVersion", b.writeSchemaVersion());
            response.put("minReaderSchemaVersion", b.minReaderSchemaVersion());
            response.put("expectedNodes", b.expectedNodes().stream()
                    .map(n -> Map.of("nodeId", n.nodeId(), "bootId", n.bootId())).toList());
            response.put("approvedBinaryDigests", b.approvedBinaryDigests());
            Map<String, Object> acks = new LinkedHashMap<>();
            b.acks().forEach((nodeId, ack) -> acks.put(nodeId, Map.of(
                    "generation", ack.generation(),
                    "bootId", ack.bootId(),
                    // For COMPARISON against a digest computed independently from the
                    // approved artifact — see binaryDigestNote below.
                    "binaryDigest", ack.binaryDigest(),
                    "capabilities", ack.capabilities(),
                    "readSchemaVersions", ack.readSchemaVersions(),
                    "spoolReady", ack.spoolReady(),
                    "drestReady", ack.drestReady(),
                    "ackedAtMs", ack.ackedAtMs(),
                    "expiresAtMs", ack.expiresAtMs())));
            response.put("acks", acks);
            response.put("blockingConditions", barrierService.activationViolations(b));
        } else if (view instanceof jp.aegif.nemaki.rest.purview.journal.LineageBarrierReader
                .BarrierView.Pristine) {
            response.put("present", false);
            response.put("message", "no barrier exists — this deployment writes v1");
        } else if (view instanceof jp.aegif.nemaki.rest.purview.journal.LineageBarrierReader
                .BarrierView.Indeterminate indeterminate) {
            response.put("present", false);
            response.put("indeterminate", indeterminate.reasonClass());
            response.put("message", "the barrier cannot be read — facts spool until it can");
        }
        if (readerAdmission != null) {
            var admission = readerAdmission.evaluate(view);
            response.put("readerAdmission", admission.decision().name());
            response.put("readerAdmissionViolations", admission.violations());
        }
        // This node's own measurement, and the warning that makes it usable.
        response.put("measuredBinaryDigest", measuredBinaryDigestOrNull());
        response.put("binaryDigestNote", BINARY_DIGEST_NOTE);
        return ResponseEntity.ok(response);
    }

    /**
     * Why the digests are here, stated in the response itself: approving the value this route
     * reports, on the strength of this route reporting it, establishes nothing — the node is
     * vouching for itself. The value is for COMPARISON against a digest computed
     * independently from the approved artifact
     * ({@code java -cp ... LineageBinaryDigest <exploded-war>}).
     */
    private static final String BINARY_DIGEST_NOTE =
            "Compare these against a digest computed independently from the approved"
            + " artifact (LineageBinaryDigest's CLI over the exploded WAR). Approving the"
            + " value this API reports, because this API reported it, is circular: the node"
            + " would be vouching for itself.";

    private String measuredBinaryDigestOrNull() {
        if (binaryDigest == null) {
            return null;
        }
        try {
            return binaryDigest.digest();
        } catch (RuntimeException unmeasurable) {
            return null; // reported as unmeasurable by /preflight; never a fabricated value
        }
    }

    /**
     * Creates or re-arms the barrier for THIS node.
     *
     * <p>There is deliberately no membership parameter: v3.3's normative rollout is a single
     * AP, and a route that accepted an invented {@code expectedNodes} would let an operator
     * describe a cluster the fence cannot actually check.
     */
    @PostMapping("/barrier/prepare")
    public ResponseEntity<Map<String, Object>> prepareBarrier(
            @RequestBody(required = false) Map<String, Object> body) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;
        if (barrierService == null) {
            return badRequest("the barrier machinery is not wired on this node");
        }
        // Absent/null PRESERVES what the document holds; an explicit [] clears it.
        java.util.Set<String> digests = stringSetOrNull(body, "approvedBinaryDigests");
        java.util.Set<String> extra = stringSetOrNull(body, "additionalRequiredCapabilities");
        return barrierOutcome(barrierService.prepare(digests, extra));
    }

    /** Records this node's ACK, computed fresh at the revision it is written against. */
    @PostMapping("/barrier/ack")
    public ResponseEntity<Map<String, Object>> ackBarrier() {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;
        if (barrierService == null) {
            return badRequest("the barrier machinery is not wired on this node");
        }
        return barrierOutcome(barrierService.ack());
    }

    /** {@code PREPARING → ACTIVE}. One-way: {@code minReaderSchemaVersion} never comes back. */
    @PostMapping("/barrier/activate")
    public ResponseEntity<Map<String, Object>> activateBarrier() {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;
        if (barrierService == null) {
            return badRequest("the barrier machinery is not wired on this node");
        }
        return barrierOutcome(barrierService.activate());
    }

    /** Writes go back to v1. Always allowed — it is movement toward safety. */
    @PostMapping("/barrier/rollback")
    public ResponseEntity<Map<String, Object>> rollbackBarrier() {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;
        if (barrierService == null) {
            return badRequest("the barrier machinery is not wired on this node");
        }
        return barrierOutcome(barrierService.rollback());
    }

    private ResponseEntity<Map<String, Object>> barrierOutcome(
            jp.aegif.nemaki.rest.purview.journal.LineageBarrierService.BarrierOutcome outcome) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (!outcome.applied()) {
            response.put("applied", false);
            response.put("violations", outcome.violations());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }
        response.put("applied", true);
        if (outcome.barrier() != null) {
            response.put("state", outcome.barrier().state().name());
            response.put("generation", outcome.barrier().generation());
            response.put("writeSchemaVersion", outcome.barrier().writeSchemaVersion());
            response.put("minReaderSchemaVersion",
                    outcome.barrier().minReaderSchemaVersion());
        }
        return ResponseEntity.ok(response);
    }

    /** null = the caller said nothing (preserve); empty set = the caller said "none". */
    private static java.util.Set<String> stringSetOrNull(Map<String, Object> body, String key) {
        if (body == null || !body.containsKey(key) || body.get(key) == null) {
            return null;
        }
        if (!(body.get(key) instanceof java.util.List<?> list)) {
            throw new IllegalArgumentException(key + " must be a list of strings");
        }
        java.util.Set<String> values = new java.util.LinkedHashSet<>();
        for (Object element : list) {
            if (!(element instanceof String s) || s.isBlank()) {
                throw new IllegalArgumentException(key + " must hold non-blank strings");
            }
            values.add(s);
        }
        return values;
    }


    // ==================== 4b preflight (v2.3.27) ====

    @Autowired(required = false)
    private jp.aegif.nemaki.rest.purview.state.PurviewCursorStateService cursorStateService;

    @Autowired(required = false)
    private jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap repositoryInfoMap;

    @Autowired(required = false)
    private jp.aegif.nemaki.rest.purview.journal.LineageSpoolMachinery preflightSpoolMachinery;

    // The §2 obligation machine, for the preflight's own section. Every one is optional so a
    // node without the machine still answers — as a FAIL that names what is missing, never as
    // an omission that reads as fine.

    @Autowired(required = false)
    private jp.aegif.nemaki.rest.purview.journal.LineageCatalogObligationStore
            preflightObligationStore;

    @Autowired(required = false)
    private jp.aegif.nemaki.rest.purview.journal.LineageHistoricalPublishIntentStore
            preflightIntentStore;

    @Autowired(required = false)
    private jp.aegif.nemaki.rest.purview.journal.LineageHistoricalCompensationStore
            preflightCompensationStore;

    @Autowired(required = false)
    private jp.aegif.nemaki.rest.purview.journal.LineageObligationWiring preflightWiring;

    @Autowired(required = false)
    private jp.aegif.nemaki.rest.purview.journal.LineageOperationBudgetProvider preflightBudgets;

    @Autowired(required = false)
    private jp.aegif.nemaki.rest.purview.journal.LineagePurgeLedger preflightPurgeLedger;

    @Autowired(required = false)
    private java.util.List<jp.aegif.nemaki.rest.purview.journal.LineageTargetSink>
            preflightTargetSinks;

    /**
     * The stored {@code cloud-metadata-snapshot} cursors, as verdicts (4b acceptance).
     *
     * <p>The ordinary cursor route normalizes on the way out, deliberately — nothing stored may
     * reach a response unsanitised — which is precisely why reading it can never be evidence
     * ABOUT the stored value. This inspects the raw value where it lives and returns counts and
     * a verdict: no cursor, URL, token or fragment of one appears in the response, in a log, or
     * in an exception message.
     */
    @GetMapping("/preflight/cursors")
    public ResponseEntity<Map<String, Object>> preflightCursors() {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;
        if (cursorStateService == null) {
            return badRequest("cursor state service unavailable");
        }
        return ResponseEntity.ok(cursorPreflight());
    }

    private Map<String, Object> cursorPreflight() {
        java.util.Collection<String> configured;
        try {
            if (repositoryInfoMap == null) {
                // Not "no repositories": an inventory we could not obtain is one we did not
                // check, and treating it as empty would report all-clean for everything.
                Map<String, Object> unknown = new LinkedHashMap<>();
                unknown.put("verdict", "FAIL");
                unknown.put("reason", "the configured repository inventory is unavailable");
                return unknown;
            }
            configured = repositoryInfoMap.keys();
        } catch (RuntimeException e) {
            Map<String, Object> unknown = new LinkedHashMap<>();
            unknown.put("verdict", "FAIL");
            unknown.put("reason", "the configured repository inventory could not be read ("
                    + e.getClass().getSimpleName() + ")");
            return unknown;
        }
        var inspections = cursorStateService.inspectCloudMetadataCursors(configured);
        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
        boolean allClean = true;
        for (var inspection : inspections) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("repositoryId", inspection.repositoryId());
            row.put("presence", inspection.presence().name());
            row.put("lines", inspection.lines());
            row.put("malformedLines", inspection.malformedLines());
            row.put("populatedUrlLines", inspection.populatedUrlLines());
            row.put("clean", inspection.clean());
            if (inspection.reasonClass() != null) {
                // The exception CLASS only — enough to act on, never enough to leak a value.
                row.put("reasonClass", inspection.reasonClass());
            }
            rows.add(row);
            allClean &= inspection.clean();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("verdict", allClean ? "PASS" : "FAIL");
        result.put("checked", rows.size());
        result.put("repositories", rows);
        result.put("predicate", "every non-blank line splits into exactly 5 fields with the"
                + " URL slot empty; an unrecognized shape, a read failure, or an unreadable"
                + " inventory FAILS (equality with normalize() would call an unrecognized"
                + " shape clean, which is the case this check exists to catch)");
        return result;
    }

    /**
     * Everything the deployment itself can contribute to the 4b acceptance decision — and, by
     * name, everything it cannot.
     *
     * <p>The overall verdict is deliberately three-valued. {@code PASS} would claim more than
     * the application knows: old-AP absence, volume and backup encryption, key custody and
     * restart persistence are measurable only outside it, so a deployment that is otherwise
     * green reports {@code EXTERNAL_EVIDENCE_REQUIRED} rather than green.
     */
    @GetMapping("/preflight")
    public ResponseEntity<Map<String, Object>> preflight() {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        Map<String, Object> response = new LinkedHashMap<>();
        boolean fail = false;

        // --- cursors
        if (cursorStateService == null) {
            response.put("cursors", Map.of("verdict", "FAIL",
                    "reason", "cursor state service unavailable"));
            fail = true;
        } else {
            Map<String, Object> cursors = cursorPreflight();
            response.put("cursors", cursors);
            fail |= !"PASS".equals(cursors.get("verdict"));
        }

        // --- spool: the real path and the FileStore, because an absolute pathname is not a
        // mount and says nothing about which volume an operator must check the encryption of
        Map<String, Object> spool = new LinkedHashMap<>();
        String spoolDir = lineageConfig == null ? "" : lineageConfig.getSpoolDir();
        spool.put("configured", spoolDir);
        if (spoolDir == null || spoolDir.isBlank()) {
            spool.put("verdict", "FAIL");
            spool.put("reason", "lineage.spool.dir is not set; journaled mode requires it");
            fail = true;
        } else {
            try {
                java.nio.file.Path real = java.nio.file.Path.of(spoolDir).toRealPath();
                spool.put("realPath", real.toString());
                boolean fileStoreKnown;
                try {
                    spool.put("fileStore", java.nio.file.Files.getFileStore(real).toString());
                    fileStoreKnown = true;
                } catch (java.io.IOException noStore) {
                    // The FileStore is the thing an operator correlates the encryption
                    // evidence with; without it that evidence cannot be tied to anything.
                    spool.put("fileStore", null);
                    spool.put("fileStoreError", noStore.getClass().getSimpleName());
                    fileStoreKnown = false;
                }
                boolean probe = preflightSpoolMachinery != null
                        && preflightSpoolMachinery.probeReadiness();
                spool.put("probe", probe);
                spool.put("verdict", probe && fileStoreKnown ? "PASS" : "FAIL");
                fail |= !probe || !fileStoreKnown;
            } catch (java.io.IOException | RuntimeException e) {
                spool.put("verdict", "FAIL");
                spool.put("reason", "real path unavailable: " + e.getClass().getSimpleName());
                fail = true;
            }
        }
        response.put("spool", spool);

        // --- readiness and admission
        if (drestReadinessBean != null) {
            var verdict = drestReadinessBean.evaluate();
            response.put("drestReadiness", Map.of("ready", verdict.ready(),
                    "violations", verdict.violations()));
            fail |= !verdict.ready();
        } else {
            response.put("drestReadiness", Map.of("ready", false,
                    "violations", java.util.List.of("readiness gate not wired")));
            fail = true;
        }
        if (readerAdmission != null) {
            var admission = readerAdmission.evaluate();
            response.put("readerAdmission", Map.of("decision", admission.decision().name(),
                    "violations", admission.violations()));
            fail |= !admission.admitted();
        } else {
            // Silently omitting an unwired gate would let the response read as complete.
            response.put("readerAdmission", Map.of("decision", "UNWIRED",
                    "violations", java.util.List.of("the reader admission gate is not wired;"
                            + " this node cannot say whether it may read")));
            fail = true;
        }

        // --- barrier + digest policy
        Map<String, Object> barrier = new LinkedHashMap<>();
        String measured = measuredBinaryDigestOrNull();
        barrier.put("measuredBinaryDigest", measured);
        barrier.put("binaryDigestMeasurable", measured != null);
        barrier.put("binaryDigestNote", BINARY_DIGEST_NOTE);
        fail |= measured == null; // ack() refuses without it, so an unmeasurable node cannot pass
        if (barrierReader != null && barrierService != null) {
            var view = barrierReader.viewUncached();
            if (view instanceof jp.aegif.nemaki.rest.purview.journal.LineageBarrierReader
                    .BarrierView.Present present) {
                var b = present.barrier();
                barrier.put("state", b.state().name());
                barrier.put("generation", b.generation());
                barrier.put("writeSchemaVersion", b.writeSchemaVersion());
                barrier.put("minReaderSchemaVersion", b.minReaderSchemaVersion());
                barrier.put("blockingConditions", barrierService.activationViolations(b));
                barrier.put("ackBinaryDigests", b.acks().values().stream()
                        .map(a -> a.binaryDigest()).distinct().toList());
                // Condition 9 skips an empty allowlist, so blockingConditions will say
                // nothing about it. The policy has to be asserted HERE or not at all.
                boolean allowlistOk = !b.approvedBinaryDigests().isEmpty();
                barrier.put("approvedBinaryDigests", b.approvedBinaryDigests());
                barrier.put("approvedBinaryDigestsPolicy", allowlistOk ? "ok"
                        : "empty-allowlist-not-acceptable-in-production");
                fail |= !allowlistOk;
            } else {
                barrier.put("state", view instanceof jp.aegif.nemaki.rest.purview.journal
                        .LineageBarrierReader.BarrierView.Pristine ? "ABSENT" : "INDETERMINATE");
                barrier.put("approvedBinaryDigestsPolicy",
                        "empty-allowlist-not-acceptable-in-production");
                // No barrier yet is the ordinary pre-4b state, but it is not a pass: the
                // allowlist cannot have been set on a document that does not exist.
                fail = true;
            }
        } else {
            barrier.put("state", "UNWIRED");
            // No barrier machinery means no allowlist either — the policy is unmet, and
            // reporting only "UNWIRED" would leave that unsaid.
            barrier.put("approvedBinaryDigestsPolicy",
                    "empty-allowlist-not-acceptable-in-production");
            fail = true;
        }
        response.put("barrier", barrier);

        // --- the §2 obligation machine
        Map<String, Object> obligations = obligationPreflight();
        response.put("catalogObligations", obligations);
        fail |= !((java.util.List<?>) obligations.get("blockingConditions")).isEmpty();

        // --- named, not omitted: an omission reads as "fine"
        response.put("notCheckableByThisApplication", java.util.List.of(
                "old-AP absence (scale-to-one): old binaries are already deployed and carry no"
                        + " guard; the projector's view selection is doc.type only",
                "spool volume encryption at rest",
                "spool backup/snapshot encryption",
                "encryption key custody and recovery",
                "spool persistence across a restart (the probe is cached and tests"
                        + " write/link/fsync support only)",
                "E-20 on Purview: measured on Apache Atlas OSS; Purview is a different backend"
                        + " and must be re-measured before activating against it"));
        response.put("verdict", fail ? "FAIL" : "EXTERNAL_EVIDENCE_REQUIRED");
        response.put("verdictNote", "PASS is not a value this endpoint can return: the items"
                + " under notCheckableByThisApplication are measurable only outside the"
                + " application, so a green deployment still needs their evidence.");
        return ResponseEntity.ok(response);
    }

    // ==================== §2 obligation machine preflight (v2.3.55) ====

    /**
     * Everything a 4b decision needs to know about the obligation machine.
     *
     * <h2>Counts are never green zeros</h2>
     *
     * <p>Every count carries whether it is exact. A store that could not be read, a view that is
     * missing and a reduce that answered something unreadable all come back as a lower bound —
     * because the alternative is a zero, and a zero here reads as "no backlog, nothing to
     * worry about" on exactly the deployment that cannot answer.
     *
     * <h2>Ordinary work is not a blocking condition</h2>
     *
     * <p>PENDING and CLAIMED obligations are the machine doing its job. They are reported with
     * their oldest waiting age so an operator can judge, but they do not block activation —
     * refusing to activate while any obligation is outstanding would mean never activating on a
     * system that is in use.
     */
    private Map<String, Object> obligationPreflight() {
        Map<String, Object> section = new LinkedHashMap<>();
        java.util.List<String> blocking = new java.util.ArrayList<>();

        // --- obligation counts
        if (preflightObligationStore == null) {
            section.put("obligations", Map.of("verdict", "UNWIRED"));
            blocking.add("the catalog obligation store is not wired");
        } else {
            Map<String, Object> counts = new LinkedHashMap<>();
            boolean anyInexact = false;
            long unresolved = 0L;
            try {
                var byState = preflightObligationStore.countByState();
                for (var entry : byState.entrySet()) {
                    counts.put(entry.getKey().name(), countView(entry.getValue()));
                    anyInexact |= entry.getValue().truncated();
                    if (entry.getKey()
                            == jp.aegif.nemaki.rest.purview.journal.LineageCatalogObligation
                                    .State.UNRESOLVED) {
                        unresolved = entry.getValue().count();
                    }
                }
            } catch (RuntimeException e) {
                counts.put("error", e.getClass().getSimpleName());
                anyInexact = true;
            }
            counts.put("allExact", !anyInexact);
            section.put("obligations", counts);
            if (anyInexact) {
                blocking.add("obligation counts could not be established exactly");
            }
            if (unresolved > 0) {
                // Terminal-unresolved obligations mean events that can never be projected.
                blocking.add("there are " + unresolved + " terminally UNRESOLVED obligation(s)");
            }
        }

        // --- intent counts, fences, and the states that cannot converge
        if (preflightIntentStore == null) {
            section.put("historicalIntents", Map.of("verdict", "UNWIRED"));
            blocking.add("the historical publish intent store is not wired");
        } else {
            Map<String, Object> counts = new LinkedHashMap<>();
            boolean anyInexact = false;
            long compensationRequired = 0L;
            try {
                var byState = preflightIntentStore.countByState();
                for (var entry : byState.entrySet()) {
                    counts.put(entry.getKey().name(), countView(entry.getValue()));
                    anyInexact |= entry.getValue().truncated();
                    if (entry.getKey() == jp.aegif.nemaki.rest.purview.journal
                            .LineageHistoricalPublishIntent.State.COMPENSATION_REQUIRED) {
                        compensationRequired = entry.getValue().count();
                    }
                }
            } catch (RuntimeException e) {
                counts.put("error", e.getClass().getSimpleName());
                anyInexact = true;
            }
            counts.put("allExact", !anyInexact);
            section.put("historicalIntents", counts);
            if (anyInexact) {
                blocking.add("historical intent counts could not be established exactly");
            }
            if (compensationRequired > 0) {
                // A catalog holds an entity that disagrees with the repository, and nothing
                // has put it right yet.
                blocking.add("there are " + compensationRequired
                        + " intent(s) awaiting compensation");
            }
            Map<String, Object> fences = new LinkedHashMap<>();
            try {
                var counted = preflightIntentStore.countFences(System.currentTimeMillis(), 10_000);
                fences.put("active", counted.truncated() ? null : counted.active());
                fences.put("expired", counted.truncated() ? null : counted.expired());
                fences.put("exact", !counted.truncated());
                if (counted.truncated()) {
                    blocking.add("subject fence counts could not be established");
                }
            } catch (RuntimeException e) {
                fences.put("exact", false);
                fences.put("error", e.getClass().getSimpleName());
                blocking.add("subject fence counts could not be established");
            }
            section.put("subjectFences", fences);
        }

        // --- compensation counts
        if (preflightCompensationStore == null) {
            section.put("compensations", Map.of("verdict", "UNWIRED"));
            blocking.add("the historical compensation store is not wired");
        } else {
            Map<String, Object> counts = new LinkedHashMap<>();
            boolean anyInexact = false;
            long pending = 0L;
            try {
                var byState = preflightCompensationStore.countByState();
                for (var entry : byState.entrySet()) {
                    counts.put(entry.getKey().name(), countView(entry.getValue()));
                    anyInexact |= entry.getValue().truncated();
                    if (entry.getKey() != jp.aegif.nemaki.rest.purview.journal
                            .LineageHistoricalCompensation.State.RESOLVED) {
                        pending += entry.getValue().count();
                    }
                }
            } catch (RuntimeException e) {
                counts.put("error", e.getClass().getSimpleName());
                anyInexact = true;
            }
            counts.put("allExact", !anyInexact);
            section.put("compensations", counts);
            if (anyInexact) {
                blocking.add("compensation counts could not be established exactly");
            }
            if (pending > 0) {
                blocking.add("there are " + pending + " unresolved compensation(s): a catalog"
                        + " still holds an entity that disagrees with the repository");
            }
        }

        // --- oldest waiting obligation, so ordinary backlog is visible without blocking
        section.put("oldestWaitingAgeMs", oldestWaitingAgeMs(section, blocking));

        // --- adapters, resolvers and budgets, per target and per kind
        java.util.Set<String> targets = configuredTargetNames();
        section.put("configuredTargets", targets);
        if (targets.isEmpty()) {
            // Nothing was checked. Reporting PASS here would say "the machine is ready" on the
            // strength of an empty loop — the per-target adapter checks, the per-kind resolver
            // checks and every budget are all skipped when there is no target. A node with no
            // lineage targets genuinely owes nothing, but it also cannot be activated for
            // lineage, so this is stated rather than left to look green.
            section.put("configuredTargetsNote", "no lineage target is enabled on this node, so"
                    + " no per-target adapter, per-kind resolver or operation budget was"
                    + " checked; this section says nothing about activation readiness");
            blocking.add("no lineage target is enabled, so the obligation machine cannot be"
                    + " shown ready for any catalog");
        }
        if (preflightWiring == null) {
            section.put("wiring", Map.of("verdict", "UNWIRED"));
            blocking.add("the obligation wiring descriptor is not wired, so no adapter can be"
                    + " shown to exist");
        } else {
            java.util.List<String> violations = preflightWiring.violations(targets);
            section.put("wiring", Map.of("violations", violations));
            // Every wiring violation is a missing adapter, an unprovable source, or a budget
            // that does not fit — all of them blocking by construction.
            blocking.addAll(violations);
        }
        // Which kinds can actually receive a tombstone. Named per kind rather than summarised:
        // a type with nowhere to record the purge would otherwise be discovered only when its
        // obligations started ending SNAPSHOT_INCOMPLETE in production.
        Map<String, Object> tombstonable = new LinkedHashMap<>();
        for (var kind : jp.aegif.nemaki.rest.purview.journal.EndpointKind.values()) {
            String marker = jp.aegif.nemaki.rest.purview.journal.LineageHistoricalEntityFactory
                    .tombstoneMarkerAttribute(kind);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("atlasType", kind.atlasTypeName());
            entry.put("markerAttribute", marker);
            entry.put("historicalEntitySupported", marker != null);
            tombstonable.put(kind.name(), entry);
        }
        section.put("historicalEntitySupportByKind", tombstonable);
        section.put("purgeLedger", Map.of("available",
                preflightPurgeLedger != null && preflightPurgeLedger.available()));
        section.put("operationBudgets", budgetView(targets));

        section.put("blockingConditions", blocking);
        section.put("verdict", blocking.isEmpty() ? "PASS" : "FAIL");
        return section;
    }

    /** A count with its own confidence attached — never a bare number. */
    private static Map<String, Object> countView(
            jp.aegif.nemaki.rest.purview.journal.LineageCatalogObligationStore.StateCount count) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("count", count.count());
        view.put("exact", !count.truncated());
        view.put("basis", count.truncated() ? "lowerBound" : "exact");
        return view;
    }

    /**
     * How long the oldest outstanding obligation has been waiting.
     *
     * <p>Reported rather than blocked on: an obligation that is PENDING is the machine working.
     * The age is what tells an operator whether it is working or stuck.
     *
     * @return null when it cannot be established, with a blocking condition recorded — null is
     *         not zero, and zero would read as "nothing is waiting"
     */
    private Long oldestWaitingAgeMs(Map<String, Object> section,
            java.util.List<String> blocking) {
        if (preflightObligationStore == null) {
            return null;
        }
        try {
            long now = System.currentTimeMillis();
            Long oldest = null;
            for (var state : java.util.List.of(
                    jp.aegif.nemaki.rest.purview.journal.LineageCatalogObligation.State.PENDING,
                    jp.aegif.nemaki.rest.purview.journal.LineageCatalogObligation.State.CLAIMED)) {
                for (var obligation : preflightObligationStore.findByState(state, 1_000)) {
                    long age = now - obligation.createdAtMs();
                    if (oldest == null || age > oldest) {
                        oldest = age;
                    }
                }
            }
            return oldest;
        } catch (RuntimeException e) {
            blocking.add("the oldest waiting obligation age could not be established: "
                    + e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * The targets this node publishes lineage to.
     *
     * <p>From {@code lineage.targets}, the same list D-rest readiness uses. Enumerating the sink
     * beans instead reported every sink Spring happened to construct — including one for a
     * backend this deployment does not publish to — and the preflight then demanded adapters for
     * a target nobody configured.
     */
    private java.util.Set<String> configuredTargetNames() {
        java.util.List<String> configured =
                lineageConfig == null ? null : lineageConfig.getTargets();
        return configured == null ? java.util.Set.of()
                : new java.util.LinkedHashSet<>(configured);
    }

    /**
     * The worst-case fenced section per target and kind, as numbers an operator can check.
     *
     * <p>Reported alongside the wiring violations rather than instead of them: the violation
     * says a budget does not fit, and this says by how much.
     */
    private Map<String, Object> budgetView(java.util.Set<String> targets) {
        Map<String, Object> byTarget = new LinkedHashMap<>();
        if (preflightBudgets == null) {
            byTarget.put("verdict", "UNWIRED");
            return byTarget;
        }
        long lease = jp.aegif.nemaki.rest.purview.journal.LineageHistoricalPublishMachine
                .INTENT_LEASE.toMillis();
        byTarget.put("subjectFenceLeaseMs", lease);
        byTarget.put("safetyMarginMs", jp.aegif.nemaki.rest.purview.journal
                .LineageObligationWiring.fenceSafetyMarginMs(lease));
        for (String target : targets) {
            Map<String, Object> byKind = new LinkedHashMap<>();
            for (var kind : jp.aegif.nemaki.rest.purview.journal.EndpointKind.values()) {
                try {
                    var budget = preflightBudgets.budgetFor(target, kind);
                    if (budget.isEmpty()) {
                        byKind.put(kind.name(), Map.of("resolvable", false));
                        continue;
                    }
                    long worst = budget.get().worstCaseMs();
                    byKind.put(kind.name(), Map.of("resolvable", true,
                            "worstCaseMs", worst == Long.MAX_VALUE ? -1L : worst,
                            "bounded", budget.get().bounded()));
                } catch (RuntimeException e) {
                    // A configuration read that throws is not a small budget.
                    byKind.put(kind.name(), Map.of("resolvable", false,
                            "error", e.getClass().getSimpleName()));
                }
            }
            byTarget.put(target, byKind);
        }
        return byTarget;
    }
}
