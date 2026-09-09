package jp.aegif.nemaki.rest.ingest;

import tools.jackson.databind.ObjectMapper;
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
import jp.aegif.nemaki.config.ObjectMapperFactory;

/**
 * Admin REST API for ingest job history and dead-letter queue management.
 */
@RestController
@RequestMapping("/v1/admin/ingest")
public class IngestDlqController {

    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultObjectMapper();

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

    /**
     * A page of dead-letter entries.
     *
     * <p>{@code offset} exists because the fetch underneath was capped at a hardcoded 200 with no
     * ordering: past that point an entry could be neither listed, retried nor deleted, and each
     * entry is the only record that a source item was lost (external review).
     */
    @GetMapping("/dlq")
    public ResponseEntity<?> listDlq(@RequestParam(defaultValue = "100") int limit,
                                     @RequestParam(defaultValue = "0") int offset) {
        if (!isAdmin()) return forbidden();
        int cappedLimit = Math.min(Math.max(limit, 1), 500);
        int safeOffset = Math.max(offset, 0);
        // Fetch one extra to say whether more exist without a second count query.
        List<IngestDeadLetterRecord> page = ingestJobService.listDlq(cappedLimit + 1, safeOffset);
        boolean hasMore = page.size() > cappedLimit;
        List<IngestDeadLetterRecord> entries = hasMore ? page.subList(0, cappedLimit) : page;
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", entries.size());
        response.put("limit", cappedLimit);
        response.put("offset", safeOffset);
        response.put("hasMore", hasMore);
        response.put("entries", entries);
        return ResponseEntity.ok(response);
    }

    /**
     * Delete dead-letter entries whose last failure is older than {@code olderThanDays}.
     *
     * <p>Deliberately an explicit admin action rather than a schedule or a default: an unresolved
     * entry is the only trace that a source item was lost, so expiring one on a timer would
     * complete the loss it exists to prevent.
     */
    @PostMapping("/dlq/purge")
    public ResponseEntity<?> purgeDlq(@RequestParam int olderThanDays) {
        if (!isAdmin()) return forbidden();
        if (olderThanDays < 1) {
            return errorResponse(HttpStatus.BAD_REQUEST,
                    "olderThanDays must be at least 1 — this deletes the only record that these "
                            + "source items were lost");
        }
        java.time.Instant cutoff = java.time.Instant.now()
                .minus(java.time.Duration.ofDays(olderThanDays));
        int deleted = ingestJobService.purgeDlqOlderThan(cutoff);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("deleted", deleted);
        response.put("cutoff", cutoff.toString());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/dlq/{dlqId}/retry")
    public ResponseEntity<?> retryDlqEntry(@PathVariable String dlqId) {
        if (!isAdmin()) return forbidden();

        IngestDeadLetterRecord dlq = ingestJobService.getDlqEntry(dlqId);
        if (dlq == null) {
            return errorResponse(HttpStatus.NOT_FOUND, "DLQ entry not found: " + dlqId);
        }

        // Cooldown: allow at most 1 retry per entry per 60 seconds.
        if (dlq.getLastRetryAt() != null && !dlq.getLastRetryAt().isBlank()) {
            try {
                long lastRetry = java.time.Instant.parse(dlq.getLastRetryAt()).toEpochMilli();
                if (System.currentTimeMillis() - lastRetry < 60_000) {
                    return errorResponse(HttpStatus.TOO_MANY_REQUESTS,
                            "Retry cooldown: wait at least 60 seconds between retries for this entry");
                }
            } catch (Exception ignored) { /* unparsable date — allow retry */ }
        }

        // Reserve the retry BEFORE dispatch.  This atomically updates
        // lastRetryAt in CouchDB using _rev as an optimistic lock.
        // If two concurrent retries race, only one wins the write;
        // the loser gets a 409 and returns 429 to the caller.
        if (!ingestJobService.reserveDlqRetry(dlq)) {
            return errorResponse(HttpStatus.TOO_MANY_REQUESTS,
                    "Retry already in progress for this entry (concurrent request)");
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
                // A read that could not answer must not become "this entry had nothing to
                // restore": the retry would import a content-less document, report success,
                // and DELETE the row that is the only record the source item was lost.
                byte[] content;
                try {
                    content = ingestJobService.loadDlqContent(dlqId);
                } catch (IngestJobService.DlqContentUnreadableException unreadable) {
                    return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, unreadable.getMessage()
                            + "; the entry is kept and nothing was imported");
                }
                if (content != null) {
                    request.setContentStream(new java.io.ByteArrayInputStream(content));
                } else {
                    // The record says it HAS content and the store says there is none. Not a
                    // retry: importing without it would record an empty document as the
                    // recovered item.
                    return errorResponse(HttpStatus.CONFLICT, "DLQ entry " + dlqId
                            + " is recorded as carrying content, but no stored payload came"
                            + " back; the entry is kept and nothing was imported");
                }
            }

            // Route through the correct archetype-specific flow
            ExternalIngestResult result = dispatchByArchetype(callContext, request);

            Map<String, Object> response = new LinkedHashMap<>();
            if (dlq.getRequestBinaryStrippedCount() > 0) {
                // The stored request is byte-free by rule; say so, or a "success" here reads as
                // "everything came back" when the attachments did not.
                response.put("strippedBinaryCount", dlq.getRequestBinaryStrippedCount());
                response.put("strippedBinaryNote", "attachment bytes were not stored with this "
                        + "entry and were not replayed; the next connector poll re-fetches them");
            }
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
        } catch (ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException
                | ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException refused) {
            // This catch-all made the handler below unreachable: the one call in this
            // controller that can raise a typed refusal sits inside it, so a retry of an
            // entry whose connector row cannot be read answered 500, "our bug", for a
            // condition a retry fixes. A review found the handler was dead code.
            throw refused;
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
            // A retry that answers "not found" for a connector the rebuilding index cannot
            // show records a wrong reason against the entry — it stays in the queue (only a
            // skip or a success takes it out), but the operator reads "no such connector" for
            // one that is there. Ask index-free before saying it. (The first version of this
            // comment said the entry would be moved out; a review checked and it is not.)
            try {
                if (connectorDefinitionService.existsIndexFree(request.getConnectorId())) {
                    return ExternalIngestResult.error(request.getRequestId(), "connector '"
                            + request.getConnectorId() + "' exists but could not be read;"
                            + " retry shortly");
                }
            } catch (ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException e) {
                return ExternalIngestResult.error(request.getRequestId(), "whether connector '"
                        + request.getConnectorId() + "' exists could not be established;"
                        + " retry shortly: " + e.getMessage());
            }
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
        // NO generic fallback. Replaying an archetype-less connector through the plain
        // execute() emitted an event carrying the request's chat.* facts while the chat aspect
        // was never attached — "the event asserts, the object lacks" (data-model D1's DLQ
        // remnant, audit #21). An archetype that is null is a connector-definition defect, and
        // replaying THROUGH the defect turns one broken row into a permanently mismatched
        // object. Fix the connector, then replay.
        return ExternalIngestResult.error(request.getRequestId(),
                "Connector '" + request.getConnectorId() + "' has no sourceArchetype, so the "
                        + "replay cannot pick the import flow that attaches this item's "
                        + "evidence. Set the archetype on the connector definition, then retry.");
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

    /**
     * A stored entry this node could not read is a retry, never "there is no such entry".
     *
     * <p>Inserted between the block below and the method it documents, this handler took that
     * block's javadoc and left {@code definitionRowsCouldNotBeRead} with none — Java attaches
     * the last preceding doc comment. A review caught it in the round that added this method,
     * and the sibling paragraph in the scheduler controller carries the same warning: if you
     * insert a method here, check which comment its neighbour ends up with.
     */
    @ExceptionHandler(IngestJobService.DlqEntryUnreadableException.class)
    public ResponseEntity<?> dlqEntryCouldNotBeRead(IngestJobService.DlqEntryUnreadableException e) {
        return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    /**
     * The typed "this row could not be read" refusals reach this controller from the
     * connector and profile services with nothing catching them, and Spring answers 500 —
     * "our bug" for a condition whose whole point is that a retry fixes it. The definition
     * APIs have had this floor since the batch began; a review found the DLQ, ingest and
     * webhook controllers without it. Endpoints that map these themselves keep their mapping.
     */
    @ExceptionHandler({ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class,
            ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException.class})
    public ResponseEntity<?> definitionRowsCouldNotBeRead(RuntimeException e) {
        return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }
}
