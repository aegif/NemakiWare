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

    // A row the mapper refuses is dropped from the listing. Returning the list bare made a
    // PARTIAL or FAILED run written by a newer node look like it never happened. The DLQ
    // listing in this same controller says how many rows it could not decode; a review found
    // the job listing silent. The shape stays an array when nothing was dropped, so existing
    // clients are unaffected.
    @GetMapping("/jobs")
    public ResponseEntity<?> listJobs(@RequestParam(defaultValue = "50") int limit) {
        if (!isAdmin()) return forbidden();
        return jobsOrEnvelope(ingestJobService.listJobsPage(limit));
    }

    @GetMapping("/jobs/profile/{profileId}")
    public ResponseEntity<?> listJobsByProfile(@PathVariable String profileId) {
        if (!isAdmin()) return forbidden();
        return jobsOrEnvelope(ingestJobService.listJobsByProfilePage(profileId));
    }

    private ResponseEntity<?> jobsOrEnvelope(IngestJobService.JobPage page) {
        if (page.unreadable() == 0) return ResponseEntity.ok(page.entries());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobs", page.entries());
        response.put("unreadableEntries", page.unreadable());
        response.put("unreadableNote", "rows this node could not decode are not in 'jobs';"
                + " they are named in the server log by document id");
        return ResponseEntity.ok(response);
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
        // The service decodes exactly this page and answers "is there more" from a probe row
        // it does NOT put in the page. Counting the probe row into the page made a page cover
        // a different span of raw rows than the caller's next offset assumes, so entries were
        // repeated or skipped across pages; and counting only DECODED rows made a page whose
        // probe row was the broken one answer "hasMore: false", which told the operator the
        // queue ended there. Two reviewers built both halves.
        IngestJobService.DlqPage fetched = ingestJobService.listDlqPage(cappedLimit, safeOffset, true);
        List<IngestDeadLetterRecord> entries = fetched.entries();
        boolean hasMore = fetched.hasMore();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", entries.size());
        response.put("limit", cappedLimit);
        response.put("offset", safeOffset);
        response.put("hasMore", hasMore);
        // Advance by the PAGE, not by the number of entries returned: some rows on this page
        // may not have decoded, and paging by count would re-read them for ever. Only when
        // there IS a next page — a continuation token on the last page walks a client through
        // an endless run of empty ones. A review found it.
        if (hasMore) response.put("nextOffset", safeOffset + cappedLimit);
        if (fetched.unreadable() > 0) {
            // Or "count" reads as the whole page. Each of these is the only record that a
            // source item was lost, so their absence has to be said, not left in the log.
            response.put("unreadableEntries", fetched.unreadable());
            response.put("unreadableNote", "rows this node could not decode are not in"
                    + " 'entries' and are not counted in 'count'; they are named in the server"
                    + " log by document id. Page with 'nextOffset', not with 'count'");
        }
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
        int deleted;
        try {
            deleted = ingestJobService.purgeDlqOlderThan(cutoff);
        } catch (IngestJobService.DlqPurgeIncompleteException stopped) {
            Map<String, Object> partial = new LinkedHashMap<>();
            partial.put("status", "error");
            partial.put("message", stopped.getMessage());
            partial.put("deleted", stopped.getDeletedBeforeStopping());
            partial.put("cutoff", cutoff.toString());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(partial);
        }
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
        try {
            if (!ingestJobService.reserveDlqRetry(dlq)) {
                return errorResponse(HttpStatus.TOO_MANY_REQUESTS,
                        "Retry already in progress for this entry (concurrent request)");
            }
        } catch (IngestJobService.DlqRetryNotReservableException couldNotAsk) {
            // "Could not attempt the reservation" is not "someone else holds it".
            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, couldNotAsk.getMessage()
                    + "; the entry is kept and nothing was imported");
        }

        CallContext callContext = getCallContext();
        if (callContext == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            // Reconstruct request from stored JSON
            ExternalIngestRequest request = MAPPER.readValue(
                    dlq.getOriginalRequestJson(), ExternalIngestRequest.class);

            // Declared here rather than after the dispatch: the payload-restore block below
            // has something to say about how it resolved the entry's recorded state.
            Map<String, Object> response = new LinkedHashMap<>();

            // Bytes this entry HAD but that were never stored are not "this item has no
            // content". Replaying without them creates an empty document, reports success and
            // DELETES the row — the only record that the source item was lost. payloadDropReason
            // is written when encryption refused the bytes (a missing NEMAKI_ENCRYPTION_KEY does
            // exactly this), and nothing read it: the whole codebase had no reader for the field.
            // The item has to come back through the connector, not through here. A review traced
            // the chain end to end.
            if (dlq.getPayloadDropReason() != null) {
                // NOT gated on hasContent. A row can carry an OLDER attempt's attachment
                // (hasContent=true) while THIS attempt's bytes were refused — the request JSON
                // on the row is the newer attempt's, so replaying pairs the old payload with
                // the new metadata and calls the hybrid the recovered item. Codex named that
                // inverse in the round after the first version of this guard, which tested
                // !hasContent and let it through.
                return errorResponse(HttpStatus.CONFLICT, "DLQ entry " + dlqId
                        + " describes an attempt whose content was never stored ("
                        + dlq.getPayloadDropReason() + ")"
                        + (dlq.isHasContent()
                                ? ", and the payload on this row is from an EARLIER attempt, so"
                                        + " replaying would pair those bytes with this"
                                        + " attempt's metadata"
                                : ", so replaying it would import an empty document in place of"
                                        + " the original")
                        + "; the entry is kept and nothing was imported. Re-fetch the source"
                        + " item through its connector instead");
            }

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
                } else if (dlq.isPayloadPresenceAssumed()) {
                    // hasContent on this row was ASSUMED, not read — set while writing over a
                    // row this node could not decode, when the attachment probe could not
                    // answer either. loadDlqContent has now READ the row and found no
                    // attachment (it refuses instead of answering null when it cannot see the
                    // row at all), so the assumption is disproven and this entry genuinely has
                    // no payload. Refusing here made the flag a fixed point: a metadata-only
                    // entry — which is every orchestrator's shape — could never be retried
                    // again, and deleting the row was the only way out. Two reviewers derived
                    // it. Replay it, and say the flag was cleared by an answer.
                    response.put("payloadPresenceAssumptionCleared", true);
                    response.put("payloadPresenceNote", "this entry was recorded as carrying a"
                            + " payload while the store could not be asked; the store has now"
                            + " answered that it carries none, so it was replayed without one");
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

            if (dlq.getRequestBinaryStrippedCount() > 0) {
                // The stored request is byte-free by rule; say so, or a "success" here reads as
                // "everything came back" when the attachments did not.
                response.put("strippedBinaryCount", dlq.getRequestBinaryStrippedCount());
                response.put("strippedBinaryNote", "attachment bytes were not stored with this "
                        + "entry and were not replayed; the next connector poll re-fetches them");
            }
            if (result.skipped() && dlq.isSourceNeverRead()) {
                // A skip is an idempotent RESOLUTION only when the item is actually in the
                // repository. On a row whose source was never read, the replay can report
                // "nothing to import" — the Notion page arm does exactly this under the
                // default files_only policy when the attachment list was never fetched — and
                // deleting on that destroys the only record that the item was lost, using the
                // tool that exists to recover it. A review traced the chain. The row stays.
                response.put("status", "skipped");
                response.put("entryKept", true);
                response.put("skipReason", result.skipReason());
                response.put("entryKeptNote", "this entry records a source item that was never"
                        + " read, so 'nothing to import' is not evidence that it was recovered."
                        + " The entry is kept. Re-fetch through the connector, then delete this"
                        + " entry if the item is confirmed present");
                return ResponseEntity.ok(response);
            }
            if (result.skipped()) {
                // Idempotent outcome — object already exists, remove from DLQ
                int removed = ingestJobService.deleteDlqEntry(dlqId);
                response.put("status", removed > 0 ? "resolved" : "resolved-entry-kept");
                if (removed == 0) {
                    // The delete walks a Mango selector; a rebuilding index removes nothing.
                    // Saying "resolved" alone left the row to reappear in the next listing
                    // with no hint of why. A review found the return value ignored here.
                    response.put("entryKeptNote", "the import was resolved but no stored row"
                            + " was returned to delete; the entry may reappear until the index"
                            + " catches up");
                }
                if (result.objectId() != null) response.put("objectId", result.objectId());
                response.put("skipReason", result.skipReason());
            } else if (result.isSuccess()) {
                int removed = ingestJobService.deleteDlqEntry(dlqId);
                response.put("status", removed > 0 ? "success" : "success-entry-kept");
                if (removed == 0) {
                    response.put("entryKeptNote", "the import succeeded but no stored row was"
                            + " returned to delete; the entry may reappear until the index"
                            + " catches up");
                }
                response.put("objectId", result.objectId());
            } else {
                // A PERMANENT refusal must not read as a failed attempt. "200 + failed +
                // retryCount" says "try again"; an authorisation refusal will answer the same
                // way forever. The branch's write-point re-authorisation made this reachable:
                // this door is bound to the DEFAULT repository (AuthenticationFilter maps
                // /v1/admin/* that way), the replayed request carries its ORIGINAL one, and
                // the confinement check runs before the admin short-circuit — so replaying a
                // delegated entry of another repository is refused every time. A review found
                // it answering 200. The same classifier the ingest door uses decides, so the
                // two doors cannot drift apart.
                HttpStatus refusal = ExternalIngestController.classifyErrorStatus(result);
                if (refusal == HttpStatus.FORBIDDEN) {
                    return errorResponse(HttpStatus.FORBIDDEN, (result.errors() == null
                            || result.errors().isEmpty() ? "the retry was refused"
                                    : result.errors().get(0))
                            + "; the entry is kept and nothing was imported");
                }
                response.put("status", "failed");
                response.put("errors", result.errors());
                // reserveDlqRetry already incremented this object AND persisted it, so adding
                // one again reported N+2 for a row that stores N+1 — the answer was stronger
                // than the stored fact. Two reviewers found it.
                response.put("retryCount", dlq.getRetryCount());
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
        // "success" used to be unconditional. The delete walks a Mango selector, so an index
        // that is rebuilding returns no row, nothing is deleted, and the operator is told the
        // entry is gone — while it is still there and will be back in the next listing. The
        // purge sibling was given this exact distinction a round earlier; a review found the
        // single delete still asserting it.
        int deleted = ingestJobService.deleteDlqEntry(dlqId);
        Map<String, Object> response = new LinkedHashMap<>();
        if (deleted == 0) {
            return errorResponse(HttpStatus.NOT_FOUND, "no stored row of DLQ entry " + dlqId
                    + " was returned to delete. If the entry is listed, the index has not"
                    + " caught up — nothing was deleted, so retry");
        }
        response.put("status", "success");
        response.put("deleted", deleted);
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
