package jp.aegif.nemaki.rest.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.util.constant.CallContextKey;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin REST API for ingest job history and dead-letter queue management.
 */
@RestController
@RequestMapping("/v1/admin/ingest")
@CrossOrigin(origins = "*", maxAge = 3600)
public class IngestDlqController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private IngestJobService ingestJobService;

    @Autowired
    private CanonicalImportService canonicalImportService;

    @Autowired
    private ConnectorDefinitionService connectorDefinitionService;

    @Autowired
    private HttpServletRequest httpRequest;

    // ── Job History ────────────────────────────────────────────────

    @GetMapping("/jobs")
    public ResponseEntity<?> listJobs(@RequestParam(defaultValue = "50") int limit) {
        if (!isAdmin()) return forbidden();
        return ResponseEntity.ok(ingestJobService.listJobs(limit));
    }

    @GetMapping("/jobs/profile/{profileId}")
    public ResponseEntity<?> listJobsByProfile(@PathVariable String profileId) {
        if (!isAdmin()) return forbidden();
        return ResponseEntity.ok(ingestJobService.listJobsByProfile(profileId));
    }

    // ── Dead-Letter Queue ──────────────────────────────────────────

    @GetMapping("/dlq")
    public ResponseEntity<?> listDlq(@RequestParam(defaultValue = "100") int limit) {
        if (!isAdmin()) return forbidden();
        List<IngestDeadLetterRecord> entries = ingestJobService.listDlq(limit);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", entries.size());
        response.put("entries", entries);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/dlq/{dlqId}/retry")
    public ResponseEntity<?> retryDlqEntry(@PathVariable String dlqId) {
        if (!isAdmin()) return forbidden();

        IngestDeadLetterRecord dlq = ingestJobService.getDlqEntry(dlqId);
        if (dlq == null) {
            return errorResponse(HttpStatus.NOT_FOUND, "DLQ entry not found: " + dlqId);
        }

        // Cooldown: allow at most 1 retry per entry per minute to prevent
        // admin retry-spamming that could hit external API rate limits.
        if (dlq.getLastRetryAt() != null && !dlq.getLastRetryAt().isBlank()) {
            try {
                long lastRetry = java.time.Instant.parse(dlq.getLastRetryAt()).toEpochMilli();
                if (System.currentTimeMillis() - lastRetry < 60_000) {
                    return errorResponse(HttpStatus.TOO_MANY_REQUESTS,
                            "Retry cooldown: wait at least 60 seconds between retries for this entry");
                }
            } catch (Exception ignored) { /* unparsable date — allow retry */ }
        }

        CallContext callContext = getCallContext();
        if (callContext == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            // Reconstruct request from stored JSON
            ExternalIngestRequest request = MAPPER.readValue(
                    dlq.getOriginalRequestJson(), ExternalIngestRequest.class);

            // Restore content stream from CouchDB attachment if available
            if (dlq.isHasContent()) {
                byte[] content = ingestJobService.loadDlqContent(dlqId);
                if (content != null) {
                    request.setContentStream(new java.io.ByteArrayInputStream(content));
                }
            }

            // Route through the correct archetype-specific flow
            ExternalIngestResult result = dispatchByArchetype(callContext, request);
            ingestJobService.updateDlqRetry(dlq);

            Map<String, Object> response = new LinkedHashMap<>();
            if (result.skipped()) {
                // Idempotent outcome — object already exists, remove from DLQ
                ingestJobService.deleteDlqEntry(dlqId);
                response.put("status", "resolved");
                if (result.objectId() != null) response.put("objectId", result.objectId());
                response.put("skipReason", result.skipReason());
            } else if (result.isSuccess()) {
                ingestJobService.deleteDlqEntry(dlqId);
                response.put("status", "success");
                response.put("objectId", result.objectId());
            } else {
                response.put("status", "failed");
                response.put("errors", result.errors());
                response.put("retryCount", dlq.getRetryCount() + 1);
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Retry failed: " + e.getMessage());
        }
    }

    @DeleteMapping("/dlq/{dlqId}")
    public ResponseEntity<?> deleteDlqEntry(@PathVariable String dlqId) {
        if (!isAdmin()) return forbidden();
        ingestJobService.deleteDlqEntry(dlqId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        return ResponseEntity.ok(response);
    }

    // ── Internal ───────────────────────────────────────────────────

    /**
     * Dispatch retry through the correct archetype-specific import flow,
     * matching the behavior of ExternalIngestController's primary dispatch.
     */
    private ExternalIngestResult dispatchByArchetype(CallContext callContext, ExternalIngestRequest request) {
        if (request.getConnectorId() == null) {
            return ExternalIngestResult.error(request.getRequestId(),
                    "DLQ retry requires connectorId to determine the import flow");
        }
        ConnectorDefinition connector = connectorDefinitionService.get(request.getConnectorId());
        if (connector == null) {
            return ExternalIngestResult.error(request.getRequestId(),
                    "Connector '" + request.getConnectorId() + "' not found — cannot determine import flow for retry");
        }
        SourceArchetype archetype = connector.getSourceArchetype();
        if (archetype != null) {
            return switch (archetype) {
                case MESSAGE_CONTEXT -> canonicalImportService.executeMailImport(callContext, request);
                case COMPOUND_NOTE -> canonicalImportService.executeNoteImport(callContext, request);
                case BUSINESS_RECORD -> canonicalImportService.executeBusinessRecordImport(callContext, request);
                case CHAT_CONTEXT -> canonicalImportService.executeChatContextImport(callContext, request);
                case FILE_SHARE -> canonicalImportService.execute(callContext, request);
            };
        }
        // Fallback: generic execute
        return canonicalImportService.execute(callContext, request);
    }

    private boolean isAdmin() {
        if (httpRequest == null) return false;
        CallContext ctx = (CallContext) httpRequest.getAttribute("CallContext");
        if (ctx == null) return false;
        Boolean admin = (Boolean) ctx.get(CallContextKey.IS_ADMIN);
        return admin != null && admin;
    }

    private CallContext getCallContext() {
        if (httpRequest == null) return null;
        return (CallContext) httpRequest.getAttribute("CallContext");
    }

    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("status", "error", "message", "Admin access required"));
    }

    private ResponseEntity<?> errorResponse(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(Map.of("status", "error", "message", message));
    }
}
