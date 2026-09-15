package jp.aegif.nemaki.rest.ingest;

import tools.jackson.databind.ObjectMapper;
import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.DocumentResult;
import com.ibm.cloud.cloudant.v1.model.PostDocumentOptions;
import com.ibm.cloud.cloudant.v1.model.PostFindOptions;
import com.ibm.cloud.cloudant.v1.model.FindResult;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.util.constant.SystemConst;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jp.aegif.nemaki.config.ObjectMapperFactory;

/**
 * CouchDB-backed service for ingest job records and dead-letter queue entries.
 */
public class IngestJobService {

    private static final Logger logger = LoggerFactory.getLogger(IngestJobService.class);
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultObjectMapper();

    private CloudantClientPool connectorPool;

    public void setConnectorPool(CloudantClientPool connectorPool) {
        this.connectorPool = connectorPool;
    }

    // ── Job Records ────────────────────────────────────────────────

    public IngestJobRecord createJob(String profileId, String connectorId, String repositoryId) {
        IngestJobRecord job = new IngestJobRecord();
        job.setJobId("job-" + UUID.randomUUID().toString().substring(0, 8));
        job.setProfileId(profileId);
        job.setConnectorId(connectorId);
        job.setRepositoryId(repositoryId);
        String now = Instant.now().toString();
        job.setStartedAt(now);
        job.setLastHeartbeatAt(now);
        job.setStatus(IngestJobRecord.Status.RUNNING);
        upsertDocument(job.getJobId(), IngestJobRecord.DOC_TYPE, MAPPER.convertValue(job, Map.class));
        return job;
    }

    /**
     * Update the heartbeat timestamp of a running job.
     * Called periodically during long fetches to prove the job is alive.
     */
    public void heartbeat(IngestJobRecord job) {
        if (job == null) return;
        job.setLastHeartbeatAt(Instant.now().toString());
        upsertDocument(job.getJobId(), IngestJobRecord.DOC_TYPE, MAPPER.convertValue(job, Map.class));
    }

    /**
     * Scan for RUNNING jobs whose lastHeartbeatAt is older than
     * {@link IngestJobRecord#STUCK_TIMEOUT_MS} and mark them STUCK.
     * Called once per scheduler poll cycle.
     *
     * @return number of jobs marked STUCK
     */
    @SuppressWarnings("unchecked")
    public int detectStuckJobs() {
        List<IngestJobRecord> running = findByTypeAndField(
                IngestJobRecord.DOC_TYPE, "status", "RUNNING",
                IngestJobRecord.class, 100);
        long now = System.currentTimeMillis();
        int stuckCount = 0;
        for (IngestJobRecord job : running) {
            String hb = job.getLastHeartbeatAt();
            if (hb == null) hb = job.getStartedAt();
            if (hb == null) continue;
            try {
                long hbMs = Instant.parse(hb).toEpochMilli();
                if (now - hbMs > IngestJobRecord.STUCK_TIMEOUT_MS) {
                    job.setStatus(IngestJobRecord.Status.STUCK);
                    job.setCompletedAt(Instant.now().toString());
                    job.setErrors(java.util.List.of("Job stuck: no heartbeat for "
                            + ((now - hbMs) / 60_000) + " minutes"));
                    upsertDocument(job.getJobId(), IngestJobRecord.DOC_TYPE,
                            MAPPER.convertValue(job, Map.class));
                    logger.warn("Marked job {} as STUCK (no heartbeat for {}min)",
                            job.getJobId(), (now - hbMs) / 60_000);
                    stuckCount++;
                }
            } catch (Exception e) {
                logger.debug("Failed to parse heartbeat for job {}: {}", job.getJobId(), e.getMessage());
            }
        }
        return stuckCount;
    }

    public void completeJob(IngestJobRecord job, FetchResult result) {
        job.setCompletedAt(Instant.now().toString());
        job.setFetched(result.fetched());
        job.setImported(result.imported());
        int errorCount = result.errors() != null ? result.errors().size() : 0;
        job.setFailed(errorCount);
        job.setSkipped(result.skipped());
        job.setErrors(result.errors());
        if (result.hasErrors() && result.imported() > 0) {
            job.setStatus(IngestJobRecord.Status.PARTIAL);
        } else if (result.hasErrors()) {
            job.setStatus(IngestJobRecord.Status.FAILED);
        } else {
            job.setStatus(IngestJobRecord.Status.COMPLETED);
        }
        upsertDocument(job.getJobId(), IngestJobRecord.DOC_TYPE, MAPPER.convertValue(job, Map.class));
    }

    /**
     * Record a fetch that was interrupted by the poll timeout. This is
     * deliberately NOT {@code completeJob(..., FetchResult(0,0,[timeout]))}: the
     * interrupted fetch may have imported some items, but those counts live in
     * the orchestrator's stack and are lost when the future is abandoned, so
     * claiming {@code imported=0 / FAILED} misreports a fetch that partially
     * succeeded. We mark it {@link IngestJobRecord.Status#STUCK} (the same status
     * the heartbeat watchdog uses) so operators see "timed out / interrupted"
     * rather than "ran and failed with zero imports". Any items already imported
     * are reconciled on the next poll by source-identity dedupe (no data loss),
     * because the checkpoint is not advanced on a timed-out fetch.
     */
    public void markTimedOut(IngestJobRecord job, long timeoutMinutes) {
        job.setCompletedAt(Instant.now().toString());
        job.setStatus(IngestJobRecord.Status.STUCK);
        List<String> errors = new java.util.ArrayList<>(
                job.getErrors() != null ? job.getErrors() : java.util.List.of());
        errors.add("Fetch timed out after " + timeoutMinutes + " minutes (interrupted; any items "
                + "already imported are reconciled on the next poll via dedupe)");
        job.setErrors(errors);
        job.setFailed(errors.size());
        upsertDocument(job.getJobId(), IngestJobRecord.DOC_TYPE, MAPPER.convertValue(job, Map.class));
    }

    public List<IngestJobRecord> listJobs(int limit) {
        return listJobsPage(limit).entries();
    }

    public JobPage listJobsPage(int limit) {
        return decodeJobs(Map.of("type", IngestJobRecord.DOC_TYPE), Math.max(1, limit));
    }

    public List<IngestJobRecord> listJobsByProfile(String profileId) {
        return listJobsByProfilePage(profileId).entries();
    }

    public JobPage listJobsByProfilePage(String profileId) {
        return decodeJobs(Map.of("type", IngestJobRecord.DOC_TYPE, "profileId", profileId), 50);
    }

    private JobPage decodeJobs(Map<String, Object> selector, int limit) {
        CloudantClientWrapper client = getConfClient();
        List<Document> raw = findRawDocs(client.getClient(), client.getDatabaseName(),
                selector, limit, 0);
        List<IngestJobRecord> results = new ArrayList<>();
        int unreadable = 0;
        for (Document doc : raw) {
            try {
                Map<String, Object> props = new HashMap<>(doc.getProperties());
                props.remove("_id");
                props.remove("_rev");
                props.remove("type");
                results.add(MAPPER.convertValue(props, IngestJobRecord.class));
            } catch (Exception couldNotDecode) {
                unreadable++;
                logger.warn("job row {} could not be decoded and is missing from this listing:"
                        + " {}", doc.getId(), couldNotDecode.getMessage());
            }
        }
        return new JobPage(results, unreadable);
    }

    // ── Dead-Letter Queue ──────────────────────────────────────────

    public void saveToDlq(ExternalIngestRequest request, String errorMessage) {
        saveToDlq(request, errorMessage, null);
    }

    /**
     * For a failure raised BEFORE the import service was reached — the source was never read.
     * The row is marked so a replay that reports "nothing to import" does not delete it.
     */
    public boolean saveSourceNeverReadToDlq(ExternalIngestRequest request, String errorMessage) {
        return saveToDlqReporting(request, errorMessage, null, true, false);
    }

    public void saveToDlq(ExternalIngestRequest request, String errorMessage, byte[] contentBytes) {
        saveToDlq(request, errorMessage, contentBytes, false);
    }

    public void saveToDlq(ExternalIngestRequest request, String errorMessage, byte[] contentBytes,
            boolean sourceNeverRead) {
        // sourceWasRead is FALSE here, not !sourceNeverRead. "read", "not read" and "partially
        // read" are three states, and inferring the first from the negation of the second let
        // a partial fetch — a Notion page whose attachment list was read but whose download
        // failed — clear a mark that says the row is not evidence of a recovery. The replay
        // then answered "nothing to import", took it for an idempotent resolution, and DELETED
        // the row. Codex and a subagent found the two halves in the same round. Only a caller
        // that can say it read the source clears the mark, and it says so explicitly.
        saveToDlqReporting(request, errorMessage, contentBytes, sourceNeverRead, false);
    }

    /** For a failure raised AFTER the source item was fully read — the import itself failed. */
    public void saveSourceReadToDlq(ExternalIngestRequest request, String errorMessage,
            byte[] contentBytes) {
        saveToDlqReporting(request, errorMessage, contentBytes, false, true);
    }

    /**
     * @param sourceWasRead whether THIS attempt read the source item. Only a caller that did
     *        may clear an earlier attempt's "never read" mark.
     * @return whether a row was written. {@code saveToDlq} swallows every persistence failure
     *         — it is the last-resort record and must not take its caller down — which made
     *         the boolean the IMAP monitor checks meaningless: the helper returned true
     *         whenever the void call returned, and the call could not fail. Two reviewers
     *         found the claim one frame below where the previous round had moved it.
     */
    public boolean saveToDlqReporting(ExternalIngestRequest request, String errorMessage,
            byte[] contentBytes, boolean sourceNeverRead, boolean sourceWasRead) {
        return saveToDlqReporting(request, errorMessage, contentBytes, sourceNeverRead,
                sourceWasRead, false);
    }

    /**
     * A row that RECORDS webhook deliveries this node accepted and did not fetch. It carries no
     * item and is not replayable; the retry door refuses it by this mark. The mark used to be a
     * prefix on {@code sourceObjectId} — a caller-supplied string, so a genuine item named that
     * way was refused for ever and nothing stopped a caller from naming one so (R10).
     */
    public boolean saveWebhookDeliveryRecordToDlq(ExternalIngestRequest request, String errorMessage) {
        return saveToDlqReporting(request, errorMessage, null, true, false, true);
    }

    boolean saveToDlqReporting(ExternalIngestRequest request, String errorMessage,
            byte[] contentBytes, boolean sourceNeverRead, boolean sourceWasRead,
            boolean webhookDeliveryRecord) {
        try {
            String dlqId = deadLetterIdFor(request);
            // The WRITE path must not inherit the read path's refusal. getDlqEntry refuses a
            // stored row it cannot decode so the endpoint stops answering 404 for it — but
            // here the refusal aborted the save, and the id is deterministic, so EVERY later
            // failure of the same item refused too. Nothing was recorded, and the row this
            // class calls the only record of a lost item was never repaired: before, the
            // undecodable row was simply overwritten by the raw upsert below. Two reviewers
            // found the regression in the round that introduced it. Merging with "no previous
            // entry" is the same thing the old swallow did, and the upsert repairs the row.
            IngestDeadLetterRecord existing;
            // Tri-state on purpose: TRUE = the stored row carries a payload, FALSE = it was
            // read and carries none, null = the question could not be asked.
            Boolean earlierPayloadIsStillAttached = null;
            boolean rowWasUnreadable = false;
            try {
                existing = getDlqEntry(dlqId);
                if (existing != null && existing.isHasContent()
                        && existing.isPayloadPresenceAssumed()) {
                    // The row we can now read says it HAS a payload, but that flag was
                    // assumed, not read. Inheriting it made an assumption into a settled fact
                    // one save later — and the row lost the caveat with it. The row is
                    // readable now, so re-ask; the answer settles it either way.
                    earlierPayloadIsStillAttached = storedDocumentHasAttachment(dlqId);
                } else if (existing != null) {
                    earlierPayloadIsStillAttached = existing.isHasContent();
                } else {
                    // The read ANSWERED that there is no row. Nothing is attached.
                    earlierPayloadIsStillAttached = Boolean.FALSE;
                }
            } catch (DlqEntryUnreadableException couldNotRead) {
                logger.warn("the existing DLQ row for {} could not be read ({}); this failure"
                        + " is being written over it", dlqId, couldNotRead.getMessage());
                existing = null;
                rowWasUnreadable = true;
                // null is the ANSWERED-nothing value, and it decides hasContent below — so
                // writing over an unreadable row set "no payload" on a row whose attachment
                // the upsert carries forward, and the next retry then imported content-less
                // and DELETED the row. That is the loss chain this class exists to prevent,
                // reopened through the flag instead of the loader. Two reviewers found it in
                // the round that added this catch. The attachment is a property of the stored
                // document, so it is read from the document rather than assumed absent.
                earlierPayloadIsStillAttached = storedDocumentHasAttachment(dlqId);
            }

            IngestDeadLetterRecord dlq = buildDlqRecord(request, errorMessage, existing,
                    Instant.now().toString(), rowWasUnreadable);
            // Inherited only while the source STILL has not been read. The first version kept
            // the mark even when a later attempt read the page fully and failed during the
            // import — the row's request is then complete and replayable, so refusing to
            // resolve it for ever was an over-throw. A review found it. A save that read the
            // source clears the mark; one that did not, keeps it.
            dlq.setSourceNeverRead(sourceNeverRead
                    || (!sourceWasRead && existing != null && existing.isSourceNeverRead()));
            // Set by the WRITER, never inherited: the row means what its latest save says.
            dlq.setWebhookDeliveryRecord(webhookDeliveryRecord);

            // The payload is encrypted or it is not written. Storing ingested bytes in the
            // clear in nemaki_conf — no ACL of its own, no retention — is not an acceptable
            // fallback for a system whose subject is evidence (external review).
            byte[] payload = null;
            String dropReason = null;
            if (contentBytes != null && contentBytes.length > 0) {
                try {
                    payload = encryptDeadLetterPayload(contentBytes);
                } catch (Exception keyErr) {
                    dropReason = "payload not stored: " + keyErr.getMessage();
                    logger.warn("DLQ payload for {} was NOT stored: {}",
                            request.getSourceObjectId(), keyErr.getMessage());
                }
            }
            // A later failure for the same item often carries no bytes (the stream was consumed
            // by the import that has already run). That must not be read as "this item has no
            // payload" — the earlier attempt's payload is still attached and still the thing a
            // retry needs.
            boolean presenceCouldNotBeEstablished = earlierPayloadIsStillAttached == null;
            boolean keptEarlierPayload = payload == null
                    && (Boolean.TRUE.equals(earlierPayloadIsStillAttached)
                            // Could not ask -> assume there IS one. Wrong in this direction
                            // costs a retry that refuses (409/CONFLICT) and keeps the row;
                            // wrong in the other direction loses the payload silently.
                            || presenceCouldNotBeEstablished);
            dlq.setHasContent(payload != null || keptEarlierPayload);
            // The assumption is recorded as an assumption, in its own field. It used to be
            // smuggled into payloadDropReason, which meant the next save could not tell an
            // assumed presence from an established one, and a genuine drop reason was
            // DISCARDED whenever a payload was assumed or inherited. Both were reviewed.
            // A payload that has been ENCRYPTED is not a payload that has been STORED: the
            // attachment write happens after this row lands, and if it fails — or if the
            // correcting write below loses a revision race — the row was left claiming a
            // payload outright, with no marker and no reason. Codex enumerated the whole state
            // machine and named that as the one state nothing can repair. So the row says
            // "assumed" until the attachment is confirmed; the confirmation is a second write,
            // and if THAT is the one that fails, "assumed" is still true.
            dlq.setPayloadPresenceAssumed(payload != null
                    || (payload == null && presenceCouldNotBeEstablished));
            // The drop reason survives whatever hasContent ends up saying: it is about THIS
            // attempt's bytes, which were refused, not about whether some older payload is
            // attached. The retry door reads it to refuse rather than import an empty
            // document over an item whose bytes were never kept.
            //
            // It also survives the NEXT save. Setting it from this attempt alone wrote null
            // over the earlier reason as soon as one byte-less failure arrived for the same
            // item — and every orchestrator saves with no bytes — so the 409 that protects the
            // entry lasted exactly until the next failure. A review traced it. Only a payload
            // that was actually STORED clears it.
            String carriedForward = existing != null ? existing.getPayloadDropReason() : null;
            dlq.setPayloadDropReason(payload != null ? null
                    : (dropReason != null ? dropReason : carriedForward));
            // The window between this row and the attachment has its OWN field. Writing it
            // into payloadDropReason made it permanent: the retry door reads that field as
            // "the bytes were deliberately not written", so a confirming write that lost a
            // revision race left the entry refusing every replay for ever — for content that
            // WAS stored, with DELETE the only exit. Two reviewers found it in the round
            // after. A token is cleared by any later save and answers "retry shortly".
            // A byte-less save INHERITS an unfinished token rather than clearing it. Clearing
            // it rested on the re-probe answering TRUE — but TRUE may be the previous
            // attempt's attachment, which is exactly the payload the unfinished write never
            // replaced, and clearing turned it into "confirmed". A parallel review found that
            // path reaching the same hybrid without waiting for any lease. Only a save that
            // brings its own bytes takes the row over.
            String writeToken;
            if (payload != null) {
                writeToken = java.util.UUID.randomUUID().toString();
            } else if (existing != null) {
                writeToken = existing.getPayloadWriteToken();
            } else if (rowWasUnreadable) {
                // The stored row could not be read, so whether it carries an unfinished token
                // is UNKNOWN — and null is the answer "it carries none". Writing null here let
                // the next replay use whatever attachment the unreadable row still holds (the
                // previous attempt's): the hybrid the freeze closed. hasContent is written as
                // "unknown" in the same catch; the token was the one field left answering.
                // A Phase 2 review found it. A fresh token puts the row on the existing 409
                // door — no new field, no lease, no self-heal, no re-read of the attachment —
                // and the next save WITH bytes resolves it, as for any unfinished write.
                writeToken = java.util.UUID.randomUUID().toString();
            } else {
                // The read ANSWERED that there is no row: nothing to inherit, nothing in flight.
                writeToken = null;
            }
            dlq.setPayloadWriteToken(writeToken);
            @SuppressWarnings("unchecked")
            Map<String, Object> jsonMap = MAPPER.convertValue(dlq, Map.class);
            String docId = upsertDocument(dlq.getDlqId(), IngestDeadLetterRecord.DOC_TYPE, jsonMap);

            // Attach binary content to CouchDB document if available
            if (payload != null && docId != null) {
                try {
                    // A review asked for the ownership check to run BEFORE the bytes as well,
                    // to stop this save replacing the fixed payload attachment on a row another
                    // save now owns. It was written and then withdrawn: the check cannot tell
                    // "another save owns this row" from "the row I have just written is not
                    // visible to the index yet", and on the second reading it SKIPS the
                    // attachment — dropping a payload that was encrypted and never stored, with
                    // the row left saying "assumed". The existing lock caught it. The
                    // interleaving it was meant to close is part of the upsertDocument
                    // lost-update class, which is recorded as a residual: upsertDocument adopts
                    // the current revision instead of conditioning on the one this save read,
                    // and nothing short of a compare-and-swap closes it.
                    attachContentToDlq(docId, request.getFileName(), request.getMimeType(), payload);
                    // Confirmed. Only now does the row stop saying "assumed".
                    //
                    // Re-read first, and flip only the two fields on whatever is stored NOW.
                    // Writing this save's whole record back would revert a concurrent save's
                    // metadata while keeping the attachment the other save had just replaced —
                    // one attempt's metadata with another attempt's bytes, marked confirmed.
                    // A review built the interleaving. This does not make the write atomic;
                    // the general lost-update shape of upsertDocument is recorded as a
                    // residual. It does stop THIS write from being the one that loses it.
                    IngestDeadLetterRecord current = dlq;
                    try {
                        IngestDeadLetterRecord stored = getDlqEntry(dlq.getDlqId());
                        if (stored != null) current = stored;
                    } catch (DlqEntryUnreadableException couldNotRead) {
                        logger.warn("the DLQ row of {} could not be re-read before confirming"
                                + " its payload; confirming this attempt's own record: {}",
                                dlq.getDlqId(), couldNotRead.getMessage());
                    }
                    if (current != dlq && !writeToken.equals(current.getPayloadWriteToken())) {
                        // Another save has written this row since. Clearing the payload fields
                        // now would hand ITS metadata our bytes and call the pair confirmed —
                        // and would wipe a genuine drop reason that save had just recorded. A
                        // review built both interleavings. Leave the row to its owner: it
                        // carries its own token, so it refuses replays until it finishes.
                        logger.warn("the payload of DLQ entry {} was stored, but another save"
                                + " now owns the row; leaving its state alone", dlq.getDlqId());
                        return docId != null;
                    }
                    current.setPayloadPresenceAssumed(false);
                    current.setPayloadDropReason(null);
                    current.setPayloadWriteToken(null);
                    current.setHasContent(true);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> confirmed = MAPPER.convertValue(current, Map.class);
                    if (upsertDocument(dlq.getDlqId(), IngestDeadLetterRecord.DOC_TYPE,
                            confirmed) == null) {
                        logger.warn("the payload of DLQ entry {} is stored, but the row still"
                                + " says its presence is assumed: the confirming write did not"
                                + " land. A replay will refuse until the next save",
                                dlq.getDlqId());
                    }
                } catch (DlqPayloadNotStoredException notStored) {
                    // Put the row back in step with the store: it says it carries a payload
                    // and it does not. Written a second time rather than left wrong, because
                    // the first write is what the retry door reads.
                    //
                    // FIRST re-read, because putAttachment can COMMIT and then throw — a lost
                    // response is the commonest shape of this failure. Recording a drop reason
                    // for a payload that is actually stored would refuse a legitimate replay
                    // for ever. A review named that inverse.
                    Boolean reallyThere = storedDocumentHasAttachment(dlq.getDlqId());
                    if (Boolean.FALSE.equals(reallyThere)) {
                        // The ONLY arm that knows something: the row was read and carries no
                        // attachment at all, so these bytes are certainly not stored.
                        dlq.setHasContent(false);
                        dlq.setPayloadPresenceAssumed(false);
                        dlq.setPayloadDropReason(notStored.getMessage());
                        dlq.setPayloadWriteToken(null);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> corrected = MAPPER.convertValue(dlq, Map.class);
                        // The correction's own result is CHECKED. upsertDocument answers null
                        // on a _rev race and the first version ignored it, while the log line
                        // above — emitted before the write — already claimed the row had been
                        // corrected. A review found both halves.
                        String correctedId = upsertDocument(dlq.getDlqId(),
                                IngestDeadLetterRecord.DOC_TYPE, corrected);
                        if (correctedId == null) {
                            logger.error("DLQ row {} still claims a payload that was not"
                                    + " stored: the correcting write did not land. The retry"
                                    + " door will refuse this entry on the stored-payload arm"
                                    + " instead of naming the drop", dlq.getDlqId());
                        } else {
                            logger.warn("DLQ row {} claimed a payload that was not stored;"
                                    + " corrected", dlq.getDlqId());
                        }
                    } else {
                        // TRUE and null are BOTH "we do not know". TRUE was read as proof that
                        // this payload landed — but upsertDocument deliberately carries an
                        // EARLIER attempt's attachment forward, so the probe cannot tell one
                        // from the other, and the row was left claiming a payload that may be
                        // the wrong one. null was written as hasContent=false, which the
                        // re-probe gate below never revisits (it requires hasContent), so an
                        // unanswered read settled into a fact one save later. Codex and a
                        // subagent found the two halves independently.
                        //
                        // So: say we do not know. hasContent stays true (the retry refuses
                        // rather than importing an empty document), the assumption is marked
                        // so the NEXT save re-probes, and the reason says what is unknown.
                        dlq.setHasContent(true);
                        dlq.setPayloadPresenceAssumed(true);
                        dlq.setPayloadDropReason("the payload was encrypted, and whether the"
                                + " store took it could not be established: "
                                + notStored.getMessage());
                        dlq.setPayloadWriteToken(null);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> unknown = MAPPER.convertValue(dlq, Map.class);
                        if (upsertDocument(dlq.getDlqId(), IngestDeadLetterRecord.DOC_TYPE,
                                unknown) == null) {
                            logger.error("DLQ row {} does not record that its payload's"
                                    + " presence is unestablished: the correcting write did"
                                    + " not land", dlq.getDlqId());
                        }
                    }
                }
            }

            logger.info("Saved to DLQ: {} (source={}, failures={}, contentSize={})",
                    dlq.getDlqId(), request.getSourceObjectId(), dlq.getFailureCount(),
                    payload != null ? payload.length : 0);
            // docId is null when upsertDocument's own write did not land (a _rev race), and
            // the "Saved to DLQ" line above used to be printed for that too.
            return docId != null;
        } catch (Exception e) {
            logger.error("Failed to save to DLQ: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Everything about a dead-letter row except the payload attachment and the upsert.
     *
     * <p>Extracted so the byte-free rule on the request JSON is testable without a live
     * CouchDB — {@code saveToDlq}'s tail (payload encryption, upsert, attachment) stays
     * untestable here, which the DLQ test states rather than implies.
     */
    static IngestDeadLetterRecord buildDlqRecord(ExternalIngestRequest request,
            String errorMessage, IngestDeadLetterRecord existing, String now) {
        return buildDlqRecord(request, errorMessage, existing, now, false);
    }

    /**
     * @param historyUnknown true when {@code existing} is null because the stored row could
     *        not be READ. The counters are then left unclaimed rather than reset to "this is
     *        the first failure".
     */
    static IngestDeadLetterRecord buildDlqRecord(ExternalIngestRequest request,
            String errorMessage, IngestDeadLetterRecord existing, String now,
            boolean historyUnknown) {
        IngestDeadLetterRecord dlq = new IngestDeadLetterRecord();
        dlq.setDlqId(deadLetterIdFor(request));
        dlq.setProfileId(request.getProfileId());
        dlq.setConnectorId(request.getConnectorId());
        dlq.setRepositoryId(request.getRepositoryId());
        dlq.setSourceObjectId(request.getSourceObjectId());
        dlq.setSourceObjectType(request.getSourceObjectType());
        dlq.setFileName(request.getFileName());
        dlq.setFailedAt(now);
        dlq.setErrorMessage(errorMessage);
        // The same source item failing again is the SAME entry, not a new one. With a random
        // id, a persistent outage wrote one document per item per poll — every five minutes,
        // with the payload attached each time, into the configuration database, for ever
        // (external review).
        if (existing != null) {
            dlq.setFailureCount(existing.getFailureCount() + 1);
            dlq.setFirstFailedAt(existing.getFirstFailedAt() != null
                    ? existing.getFirstFailedAt() : existing.getFailedAt());
            dlq.setRetryCount(existing.getRetryCount());
            dlq.setLastRetryAt(existing.getLastRetryAt());
        } else if (historyUnknown) {
            // existing == null because the row could not be READ, not because there is none.
            // Claiming failureCount=1 and firstFailedAt=now rewrites a long-running outage as
            // a first failure — and IngestDeadLetterRecord calls firstFailedAt the field that
            // "does not move". The head of this class fixed exactly this shape for hasContent
            // and left the four fields beside it; a review found them. Nothing is claimed:
            // the counters stay at their defaults and firstFailedAt is left null, which the
            // listing already renders as "unknown" rather than as a date.
            dlq.setFailureCount(0);
            dlq.setRetryCount(0);
        } else {
            dlq.setFailureCount(1);
            dlq.setFirstFailedAt(now);
            dlq.setRetryCount(0);
        }
        // The request JSON obeys the same rule as the payload: INGESTED BYTES DO NOT ENTER
        // nemaki_conf IN THE CLEAR. The payload channel above is encrypted-or-dropped, but the
        // note orchestrator transiently injects attachment bytes into request metadata as
        // contentBase64, and a failure inside that window used to serialize them verbatim into
        // originalRequestJson — the same bytes the 20 lines above refuse to store, through a
        // side door (external review, Codex/audit N1).
        //
        // Replay semantics, decided: bytes travel ONLY through the encrypted attachment
        // channel. A replay restores the main content from it; stripped attachment bytes are
        // NOT restored — for orchestrator-fetched attachments the next poll re-fetches them
        // and the dedupe-skip fall-through retries the attachment import, and a caller who
        // supplied contentBase64 by hand re-sends it. The count is recorded so neither the
        // operator nor the replay result mistakes the absence for "there were none".
        SerializedRequest serialized = serializeRequestForDlq(request);
        dlq.setOriginalRequestJson(serialized.json());
        dlq.setRequestBinaryStrippedCount(serialized.strippedBinaryCount());
        return dlq;
    }

    /** The byte-free request JSON, and how many binary values were removed to make it so. */
    record SerializedRequest(String json, int strippedBinaryCount) {
    }

    /**
     * Serializes a request for the DLQ with every {@code contentBase64} value removed,
     * wherever it nests. Works on the serialized tree rather than the live request, so the
     * caller's in-flight object — whose metadata the orchestrator is still using — is never
     * mutated by the act of saving it.
     */
    static SerializedRequest serializeRequestForDlq(ExternalIngestRequest request) {
        @SuppressWarnings("unchecked")
        Map<String, Object> tree = MAPPER.convertValue(request, Map.class);
        int stripped = stripBinaryKeys(tree);
        return new SerializedRequest(MAPPER.writeValueAsString(tree), stripped);
    }

    /** Removes {@code contentBase64} entries recursively; returns how many were removed. */
    private static int stripBinaryKeys(Object node) {
        int removed = 0;
        if (node instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) rawMap;
            if (map.remove("contentBase64") != null) {
                removed++;
            }
            for (Object value : map.values()) {
                removed += stripBinaryKeys(value);
            }
        } else if (node instanceof Iterable<?> list) {
            for (Object item : list) {
                removed += stripBinaryKeys(item);
            }
        }
        return removed;
    }

    /**
     * A stable id for one source item, so a repeated failure updates its entry.
     *
     * <p>Derived from the identity the ingest path itself dedupes on. A blank source id falls
     * back to a random suffix rather than colliding every anonymous failure into one row.
     */
    private static String deadLetterIdFor(ExternalIngestRequest request) {
        String source = request.getSourceObjectId();
        if (source == null || source.isBlank()) {
            return "dlq-" + UUID.randomUUID().toString().substring(0, 8);
        }
        String key = String.join("\u0000",
                nullToEmpty(request.getRepositoryId()),
                nullToEmpty(request.getProfileId()),
                nullToEmpty(request.getSourceObjectType()),
                source);
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("dlq-");
            for (int i = 0; i < 12; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * Encrypt a dead-letter payload, or refuse.
     *
     * @throws IllegalStateException when no encryption key is configured — the caller records
     *         that the payload was dropped and keeps the metadata-only entry, which still names
     *         the source item so it can be fetched again.
     */
    private byte[] encryptDeadLetterPayload(byte[] contentBytes) {
        String encoded = java.util.Base64.getEncoder().encodeToString(contentBytes);
        String wrapped = jp.aegif.nemaki.sync.util.PasswordEncryptionUtil.encrypt(encoded);
        return wrapped.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Whether the STORED document for this dlqId carries an attachment, read raw.
     *
     * <p>Used only when the typed read refused: the merge below must not conclude "this item
     * has no payload" from a row it could not decode, because the upsert carries the
     * attachment forward and the retry then imports content-less and deletes the row.
     *
     * <p>Returns {@code null} when the question itself could not be asked. It used to return
     * {@code false} there — the same value as an answered "no attachment" — and its javadoc
     * recorded that as a known limit rather than fixing it. A review pointed out the limit is
     * the very loss chain this method was added to close, only reached through a failed raw
     * read instead of a failed typed one: the row then says {@code hasContent=false} while the
     * upsert carries the physical attachment forward, and the next retry imports content-less
     * and deletes the row. The caller resolves {@code null} by ASSUMING a payload is there,
     * which is the harmless direction — the retry refuses with 409/503 and KEEPS the row.
     */
    private Boolean storedDocumentHasAttachment(String dlqId) {
        try {
            CloudantClientWrapper client = getConfClient();
            List<Document> raw = findRawDocs(client.getClient(), client.getDatabaseName(),
                    Map.of("type", IngestDeadLetterRecord.DOC_TYPE, "dlqId", dlqId), 1, 0);
            // Three outcomes, and only the last is an ANSWER. The first version collapsed all
            // three into false, which is the same value as "read it, there is no attachment" —
            // the very defect the null arm below was added to close, one line down. A review
            // found it in the round that added the null arm.
            if (raw.isEmpty()) {
                // The caller reached here because the typed read said "stored but undecodable"
                // or "the selector itself failed". Seeing no row now contradicts the first and
                // means nothing under the second. It is not "the row has no attachment".
                logger.warn("the stored DLQ row for {} was not returned by the index, so"
                        + " whether it carries a payload could not be established", dlqId);
                return null;
            }
            // No attachment block IS the answer "there is no attachment": CouchDB omits
            // _attachments entirely for a document that has none, so a non-null-but-empty map
            // essentially never occurs. Reporting this shape as unanswerable made FALSE
            // unreachable and turned the assumption below into a FIXED POINT — a metadata-only
            // entry (every orchestrator saves with no bytes) became permanently un-retryable
            // after one blip, with deleting the row the only way out. Two reviewers derived it
            // independently in the round after it was written.
            //
            // The dependency this rests on — that a Mango _find returns attachment stubs — is
            // shared with loadDlqContent and with upsertDocument's carry-forward, which would
            // DESTROY payloads on every update if it did not hold. It is not measured here.
            return raw.get(0).getAttachments() != null
                    && !raw.get(0).getAttachments().isEmpty();
        } catch (RuntimeException couldNotAsk) {
            logger.warn("whether the stored DLQ row for {} still carries its payload could not"
                    + " be established: {}", dlqId, couldNotAsk.getMessage());
            return null;
        }
    }

    /** A payload this node could not read — never the same answer as "there is none". */
    public static class DlqContentUnreadableException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public DlqContentUnreadableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The stored payload, or null when the entry HAS none. Loaded from the DLQ document's
     * attachment.
     *
     * <p>It used to answer null for a read that failed as well — a rotated encryption key, a
     * ciphertext this node cannot decrypt (the refusal that exists so ciphertext is never fed
     * to a retry), an attachment read that timed out. The caller then retried the entry with
     * NO content, the import succeeded as metadata-only, and the DLQ row — which this class
     * calls the only record that the source item was lost — was deleted. A review found it.
     *
     * @throws DlqContentUnreadableException when the entry has a payload this node could not
     *         read. The retry must refuse rather than run content-less.
     */
    public byte[] loadDlqContent(String dlqId) {
        try {
            CloudantClientWrapper client = getConfClient();
            String dbName = client.getDatabaseName();
            var cloudant = client.getClient();

            List<Document> docs = findRawDocs(cloudant, dbName,
                    Map.of("type", IngestDeadLetterRecord.DOC_TYPE, "dlqId", dlqId));
            if (docs.isEmpty()) {
                // The caller has just READ this row. The index not returning it now is not
                // "the row has no attachment" — and the retry door reads a null from here as
                // an ANSWER about the payload, which is how it decides whether an assumed
                // presence has been disproven.
                throw new DlqContentUnreadableException("the stored row of DLQ entry " + dlqId
                        + " was not returned by the index, so its payload could not be read",
                        null);
            }

            Document doc = docs.get(0);
            // Get the first attachment
            if (doc.getAttachments() == null || doc.getAttachments().isEmpty()) return null;
            // Prefer the stable name. Rows written before it exists can carry more than one
            // attachment, and taking whichever key came first meant an older attempt's bytes.
            String attName = doc.getAttachments().containsKey("payload") ? "payload"
                    : doc.getAttachments().keySet().iterator().next();

            // Check attachment size before loading to prevent OOM
            var attMeta = doc.getAttachments().get(attName);
            if (attMeta != null && attMeta.length() != null && attMeta.length() > 100L * 1024 * 1024) {
                logger.warn("DLQ attachment too large ({} bytes) for {}", attMeta.length(), dlqId);
                // Also not "there is none": the payload is there and this node will not load
                // it. Retrying content-less would import an empty document and then delete
                // the only record of the original.
                throw new DlqContentUnreadableException("the stored payload of DLQ entry "
                        + dlqId + " is " + attMeta.length() + " bytes, above this node's"
                        + " 100MB limit, so it was not loaded", null);
            }

            var getAttOpts = new com.ibm.cloud.cloudant.v1.model.GetAttachmentOptions.Builder()
                    .db(dbName).docId(doc.getId()).attachmentName(attName).build();
            try (java.io.InputStream is = cloudant.getAttachment(getAttOpts).execute().getResult()) {
                return decryptDeadLetterPayload(is.readAllBytes());
            }
        } catch (Exception e) {
            logger.warn("Failed to load DLQ content for {}: {}", dlqId, e.getMessage());
            throw new DlqContentUnreadableException("the stored payload of DLQ entry " + dlqId
                    + " could not be read: " + e.getMessage(), e);
        }
    }

    /**
     * Decrypt a dead-letter payload, tolerating entries written before encryption existed.
     *
     * <p>An entry stored by an older build is raw bytes with no {@code ENC(...)} wrapper. Those
     * must stay retryable — refusing them would turn an upgrade into data loss. Anything that
     * IS wrapped must decrypt; a wrapped payload that will not decrypt is not silently returned
     * as ciphertext.
     */
    private byte[] decryptDeadLetterPayload(byte[] stored) {
        if (stored == null || stored.length == 0) {
            return stored;
        }
        String asText;
        try {
            asText = new String(stored, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception notText) {
            return stored; // pre-encryption binary payload
        }
        if (!asText.startsWith("ENC(")) {
            return stored; // written before payloads were encrypted
        }
        String decoded;
        try {
            decoded = jp.aegif.nemaki.sync.util.PasswordEncryptionUtil.decrypt(asText);
        } catch (Exception e) {
            // Wrapped so the operator gets the actionable sentence rather than an AEAD tag
            // length. Never returned as-is: handing ciphertext back as content would feed
            // garbage into a retry and then record it as a successful re-import.
            throw new IllegalStateException(
                    "the stored payload is encrypted but could not be decrypted — check that "
                            + "NEMAKI_ENCRYPTION_KEY is the key it was written with (" 
                            + e.getMessage() + ")", e);
        }
        if (decoded == null || decoded.equals(asText)) {
            throw new IllegalStateException(
                    "the stored payload is encrypted but could not be decrypted — check that "
                            + "NEMAKI_ENCRYPTION_KEY is the key it was written with");
        }
        return java.util.Base64.getDecoder().decode(decoded);
    }

    /** Attach binary content to a DLQ CouchDB document. */
    private void attachContentToDlq(String docId, String fileName, String mimeType, byte[] content) {
        try {
            CloudantClientWrapper client = getConfClient();
            String dbName = client.getDatabaseName();
            var cloudant = client.getClient();

            // Get current rev
            var docResponse = cloudant.getDocument(
                    new com.ibm.cloud.cloudant.v1.model.GetDocumentOptions.Builder()
                            .db(dbName).docId(docId).build()).execute().getResult();
            String rev = docResponse.getRev();

            // A FIXED name, not the filename. upsertDocument carries an earlier attempt's
            // attachment forward, so a later attempt with a different filename left TWO
            // attachments on the row — and loadDlqContent takes the first key, which is the
            // older one. A replay then paired old bytes with new metadata and called the
            // hybrid the recovered item. CouchDB replaces an attachment of the same name, so a
            // stable name makes the row carry exactly one payload: this attempt's. The
            // original filename is on the record itself. Codex traced it.
            String attName = "payload";
            String attMime = mimeType != null ? mimeType : "application/octet-stream";
            var putAttOpts = new com.ibm.cloud.cloudant.v1.model.PutAttachmentOptions.Builder()
                    .db(dbName).docId(docId).rev(rev)
                    .attachmentName(attName).contentType(attMime)
                    .attachment(new java.io.ByteArrayInputStream(content))
                    .build();
            cloudant.putAttachment(putAttOpts).execute();
        } catch (Exception couldNotStore) {
            // The row above has ALREADY been written with hasContent=true and no drop reason,
            // and the encrypted bytes exist only in this frame. Swallowing left a row that
            // says it holds a payload, holds none, and re-derives the same claim on every
            // later save — a fixed point whose retry answers 409 for ever and whose only exit
            // is deleting the loss record. A review traced it. Reported so the caller can put
            // the row back in step with what is actually stored.
            logger.error("the payload of DLQ entry {} could not be attached: {}",
                    docId, couldNotStore.getMessage());
            throw new DlqPayloadNotStoredException("the payload was encrypted but could not be"
                    + " stored on the dead-letter row: " + couldNotStore.getMessage(),
                    couldNotStore);
        }
    }

    /** The bytes were produced but the store did not take them. Never "there are none". */
    public static class DlqPayloadNotStoredException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public DlqPayloadNotStoredException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * A page of job records, plus how many rows on it could not be decoded.
     *
     * <p>The DLQ listing 250 lines below was given this a round earlier; the job history was
     * left silently dropping rows, so a PARTIAL or FAILED run written by a newer node looks
     * like it never happened. A review found the pair disagreeing.
     */
    public record JobPage(List<IngestJobRecord> entries, int unreadable) {}

    public List<IngestDeadLetterRecord> listDlq(int limit) {
        return listDlq(limit, 0);
    }

    /**
     * A page of dead-letter entries.
     *
     * <p>Paging matters here more than in most listings: an entry is the only record that a
     * source item was lost, and before this the fetch was capped at a hardcoded 200 with no
     * ordering — so past that point entries could not be seen, retried or deleted.
     */
    public List<IngestDeadLetterRecord> listDlq(int limit, int offset) {
        return listDlqPage(limit, offset).entries();
    }

    /**
     * A page, plus how many rows on it could not be decoded.
     *
     * <p>The skip itself stays — one broken row must not hide the queue. What could not stay is
     * the SILENCE: the caller derived {@code count} and {@code hasMore} from the shortened list,
     * so a page whose extra probe row was the undecodable one answered "hasMore: false" and told
     * the operator the queue ended there. Two reviewers traced it. The count travels out so the
     * answer can say the page is incomplete instead of asserting it is whole.
     *
     * @param unreadable rows returned by the store that this node could not turn into records
     */
    public record DlqPage(List<IngestDeadLetterRecord> entries, int unreadable,
            boolean hasMore) {}

    public DlqPage listDlqPage(int limit, int offset) {
        return listDlqPage(limit, offset, false);
    }

    /**
     * @param withProbe fetch one row beyond {@code limit} to answer "is there more" without a
     *        second query. The probe row is NOT decoded into the page: counting it made an
     *        offset page cover a different span of raw rows than the caller's next offset
     *        assumes, so entries were repeated or skipped across pages. A review built both.
     */
    public DlqPage listDlqPage(int limit, int offset, boolean withProbe) {
        CloudantClientWrapper client = getConfClient();
        String dbName = client.getDatabaseName();
        var cloudant = client.getClient();
        int pageSize = Math.max(1, limit);
        List<Document> rawDocs = findRawDocs(cloudant, dbName,
                Map.of("type", IngestDeadLetterRecord.DOC_TYPE),
                withProbe ? pageSize + 1 : pageSize, Math.max(0, offset));
        boolean more = withProbe && rawDocs.size() > pageSize;
        List<Document> onThisPage = more ? rawDocs.subList(0, pageSize) : rawDocs;
        List<IngestDeadLetterRecord> results = new ArrayList<>();
        int unreadable = 0;
        for (Document rawDoc : onThisPage) {
            try {
                Map<String, Object> props = new HashMap<>(rawDoc.getProperties());
                props.remove("_id");
                props.remove("_rev");
                props.remove("type");
                results.add(MAPPER.convertValue(props, IngestDeadLetterRecord.class));
            } catch (Exception e) {
                // Name the row. The old line said only what went wrong, so the one entry an
                // operator most needs to look at by hand could not be found.
                unreadable++;
                logger.warn("DLQ entry {} could not be decoded and is missing from this page:"
                        + " {}", rawDoc.getId(), e.getMessage());
            }
        }
        return new DlqPage(results, unreadable, more);
    }

    /**
     * Delete dead-letter entries whose LAST failure is older than the cutoff.
     *
     * <p>Not scheduled and not defaulted on: an unresolved entry is the only trace that a source
     * item was lost, so deleting one on a timer would finish the loss it exists to prevent. An
     * operator asks for this explicitly, having decided those items are not coming back.
     *
     * @return how many were deleted
     */
    public int purgeDlqOlderThan(java.time.Instant cutoff) {
        int deleted = 0;
        try {
            CloudantClientWrapper client = getConfClient();
            String dbName = client.getDatabaseName();
            var cloudant = client.getClient();
            List<Document> docs = findRawDocs(cloudant, dbName,
                    Map.of("type", IngestDeadLetterRecord.DOC_TYPE), 1000, 0);
            for (Document doc : docs) {
                Object failedAt = doc.getProperties().get("failedAt");
                if (!(failedAt instanceof String ts)) continue;
                try {
                    if (java.time.Instant.parse(ts).isBefore(cutoff)) {
                        Object dlqId = doc.getProperties().get("dlqId");
                        if (dlqId instanceof String id) {
                            // Deleted by the revision this walk SAW, not by re-querying the id.
                            // Re-querying took whatever is there now, so a failure recorded
                            // since the walk began — the same deterministic id, a current
                            // failedAt — was deleted as an old entry, and the purge reported it
                            // as such. That destroys the only record of a fresh loss. Codex
                            // built it. A conflict here means the row moved and is no longer
                            // the old one, so it is left alone.
                            //
                            // The COUNT is what came back, not the intent: a rebuilding index
                            // removes nothing, and incrementing regardless told the operator
                            // entries were cleared that are still there.
                            deleted += deleteExactRevision(cloudant, dbName, doc, id);
                        }
                    }
                } catch (java.time.format.DateTimeParseException parseErr) {
                    // ONLY the parse. This catch used to cover deleteDlqEntry as well, so a
                    // deletion that failed because the store went away was logged as an
                    // unparseable date, the loop carried on, and the endpoint answered
                    // "success". A review found the swallow inside the arm that had just been
                    // added to stop the outer one.
                    logger.warn("DLQ entry {} has an unparseable failedAt ({}); left in place",
                            doc.getId(), ts);
                }
            }
        } catch (Exception couldNotFinish) {
            // Returning the partial count made the caller answer {"status":"success",
            // "deleted":0} for a read that never ran — indistinguishable from a completed
            // purge that found nothing older than the cutoff. Two reviewers found it. What was
            // already deleted is real, so the count travels with the refusal.
            logger.error("DLQ purge failed: {}", couldNotFinish.getMessage(), couldNotFinish);
            throw new DlqPurgeIncompleteException("the dead-letter purge did not complete ("
                    + couldNotFinish.getMessage() + "); " + deleted + " entries were deleted"
                    + " before it stopped", deleted, couldNotFinish);
        }
        return deleted;
    }

    /** The purge stopped part-way — never the same answer as "nothing was old enough". */
    public static class DlqPurgeIncompleteException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int deletedBeforeStopping;
        public DlqPurgeIncompleteException(String message, int deletedBeforeStopping,
                Throwable cause) {
            super(message, cause);
            this.deletedBeforeStopping = deletedBeforeStopping;
        }
        public int getDeletedBeforeStopping() { return deletedBeforeStopping; }
    }

    /**
     * One dead-letter entry, or null when there is none.
     *
     * <p>A row that IS there and cannot be deserialised refuses instead of answering null.
     * The listing skips such rows on purpose — one broken row must not hide the queue — but
     * the caller of this method answers 404 "DLQ entry not found", and this class says three
     * times that the entry is the only record a source item was lost. Telling an operator
     * that record does not exist is the worst answer available. A review found it.
     */
    public IngestDeadLetterRecord getDlqEntry(String dlqId) {
        Map<String, Object> selector =
                Map.of("type", IngestDeadLetterRecord.DOC_TYPE, "dlqId", dlqId);
        List<IngestDeadLetterRecord> results;
        try {
            results = findBySelector(selector, IngestDeadLetterRecord.class, 1);
        } catch (RuntimeException couldNotAsk) {
            // The selector itself did not answer. Not "there is no such entry".
            throw new DlqEntryUnreadableException("whether DLQ entry " + dlqId + " exists could"
                    + " not be established; retry shortly: " + couldNotAsk.getMessage());
        }
        if (!results.isEmpty()) return results.get(0);
        try {
            CloudantClientWrapper client = getConfClient();
            List<Document> raw = findRawDocs(client.getClient(), client.getDatabaseName(),
                    selector, 1, 0);
            if (!raw.isEmpty()) {
                throw new DlqEntryUnreadableException("DLQ entry " + dlqId + " is stored but"
                        + " could not be read; it has NOT been lost, and it is not safe to"
                        + " report it as absent");
            }
        } catch (DlqEntryUnreadableException unreadable) {
            throw unreadable;
        } catch (RuntimeException couldNotAsk) {
            throw new DlqEntryUnreadableException("whether DLQ entry " + dlqId + " exists could"
                    + " not be established; retry shortly: " + couldNotAsk.getMessage());
        }
        return null;
    }

    /** A dead-letter entry that is stored and could not be read as one. */
    public static class DlqEntryUnreadableException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public DlqEntryUnreadableException(String message) { super(message); }
    }

    /**
     * Reserve a DLQ entry for retry by atomically updating lastRetryAt
     * BEFORE the actual dispatch.  Uses CouchDB _rev as an optimistic
     * lock: if another thread already reserved the same entry, the
     * upsert fails with 409 Conflict and we return false.
     *
     * @return true if reservation succeeded (caller should proceed with dispatch),
     *         false if another concurrent retry already claimed this entry
     */
    public boolean reserveDlqRetry(IngestDeadLetterRecord dlq) {
        dlq.setRetryCount(dlq.getRetryCount() + 1);
        dlq.setLastRetryAt(Instant.now().toString());
        String savedId;
        try {
            savedId = upsertDocument(dlq.getDlqId(), IngestDeadLetterRecord.DOC_TYPE,
                    MAPPER.convertValue(dlq, Map.class));
        } catch (com.ibm.cloud.sdk.core.service.exception.ConflictException lostTheRace) {
            // The 409 the javadoc describes. It never reached the arm above — the SDK throws
            // on a conflict rather than answering ok=false — so the loser of a race landed in
            // the catch below and was told the reservation could not be ATTEMPTED (503): a
            // settled answer, "someone else holds it", reported as could-not-ask, with the 429
            // the notes promise unreachable. A review found the arm dead in the round after
            // the catch below was added.
            logger.debug("DLQ retry reservation lost a write race: dlqId={}", dlq.getDlqId());
            return false;
        } catch (Exception couldNotAsk) {
            // NOT the same as losing a write conflict. CouchDB being unreachable used to
            // return the same false, and the caller then told the operator "another retry is
            // already in progress" — a fact about a concurrent request that nothing
            // established, with a status telling them to slow down. Two reviewers found it.
            logger.warn("the retry of DLQ entry {} could not be reserved: {}",
                    dlq.getDlqId(), couldNotAsk.getMessage());
            throw new DlqRetryNotReservableException("the retry of DLQ entry " + dlq.getDlqId()
                    + " could not be reserved: " + couldNotAsk.getMessage(), couldNotAsk);
        }
        if (savedId == null) {
            // The store answered ok=false without throwing: a write this node cannot vouch
            // for. Not "someone else holds it" — a _rev race is raised as ConflictException —
            // so it must not become the 429 the controller reserves for a rival. Codex found
            // the arm still answering false after the conflict arm was split out.
            throw new DlqRetryNotReservableException("the retry of DLQ entry " + dlq.getDlqId()
                    + " could not be reserved: the store did not accept the reservation write",
                    null);
        }
        return true;
    }

    /** The reservation could not be attempted — never the same as losing it to a rival. */
    public static class DlqRetryNotReservableException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public DlqRetryNotReservableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** @deprecated Use {@link #reserveDlqRetry(IngestDeadLetterRecord)} instead. */
    @Deprecated
    public void updateDlqRetry(IngestDeadLetterRecord dlq) {
        reserveDlqRetry(dlq);
    }

    /**
     * Delete exactly the revision a walk saw. A conflict means the row changed since — a newer
     * failure for the same source item — and the caller must not treat that as the old entry.
     */
    private int deleteExactRevision(com.ibm.cloud.cloudant.v1.Cloudant cloudant, String dbName,
            Document seen, String dlqId) {
        try {
            DocumentResult answer = cloudant.deleteDocument(
                    new com.ibm.cloud.cloudant.v1.model.DeleteDocumentOptions.Builder()
                            .db(dbName).docId(seen.getId()).rev(seen.getRev()).build())
                    .execute().getResult();
            // Counted only on the store's affirmative answer: "execute() returned" counted a
            // deletion nothing confirmed. A 404 is NOT caught as "already gone" — the SDK's
            // NotFoundException is any 404, a missing database included — so it propagates to
            // the outer refusal like every other non-conflict failure. A row someone else
            // deleted first then costs one re-run, which is recoverable; a success reported
            // over a vanished database is not. Codex found both in the same review.
            if (answer == null || !Boolean.TRUE.equals(answer.isOk())) {
                throw new IllegalStateException("the store did not confirm the delete of DLQ"
                        + " entry " + dlqId);
            }
            return 1;
        } catch (com.ibm.cloud.sdk.core.service.exception.ConflictException moved) {
            // ONLY the conflict is "the row changed". This arm caught every RuntimeException,
            // so a timeout or a 5xx on one delete counted as a row that had moved, the loop
            // went on, and the purge answered 200 success with old entries still there — the
            // outer refusal existed for exactly that and was never reached. Codex found it in
            // the round after the revision-pinned delete was added. Anything else propagates
            // to that refusal, with the count so far.
            logger.warn("DLQ entry {} was not purged: the row changed since the walk saw it"
                    + " ({}). If it failed again it is no longer an old entry", dlqId,
                    moved.getMessage());
            return 0;
        }
    }

    /** What a delete walk established: rows the store confirmed gone, and rows it did not. */
    public record DlqDeletion(int confirmed, int unconfirmed) {
        /** True only when no row is left that the store did not confirm. */
        public boolean complete() { return unconfirmed == 0; }
    }

    /**
     * The store's answers, row by row. 0 confirmed is not "it was already gone", and a walk
     * over twin rows (the same dlqId stored twice — a recorded residual) that confirmed one
     * and not the other is not "gone" either: Codex found the sum reported as success.
     */
    public DlqDeletion deleteDlqEntry(String dlqId) {
        CloudantClientWrapper client = getConfClient();
        String dbName = client.getDatabaseName();
        var cloudant = client.getClient();
        List<Document> docs = findRawDocs(cloudant, dbName,
                Map.of("type", IngestDeadLetterRecord.DOC_TYPE, "dlqId", dlqId));
        int confirmed = 0;
        int unconfirmed = 0;
        for (Document doc : docs) {
            DocumentResult answer;
            try {
                answer = cloudant.deleteDocument(
                        new com.ibm.cloud.cloudant.v1.model.DeleteDocumentOptions.Builder()
                                .db(dbName).docId(doc.getId()).rev(doc.getRev()).build())
                        .execute().getResult();
            } catch (RuntimeException couldNotAsk) {
                // Raw, this reached the DELETE endpoint as a Spring 500 — "our bug" for a
                // store that did not answer. The typed refusal the listings use answers 503
                // there; the retry door's cleanup still answers 500 for it (recorded, R37).
                // Rows confirmed before the failure are named: the walk did not finish (R27).
                throw new IngestStoreDidNotAnswerException("the store did not answer the delete"
                        + " of row " + doc.getId() + " of DLQ entry " + dlqId + " (" + confirmed
                        + " row(s) confirmed before it); retry shortly: "
                        + couldNotAsk.getMessage());
            }
            // Counted only on the store's affirmative answer, as the purge does: "execute()
            // returned" counted a deletion nothing confirmed, and the endpoint said success
            // over a row still there. Codex found it in the pass that fixed the purge. NOT
            // thrown — the retry door calls this after a successful import, and a throw
            // there reported that import as failed; 0 already reads as "entry kept".
            if (answer != null && Boolean.TRUE.equals(answer.isOk())) {
                confirmed++;
            } else {
                unconfirmed++;
                logger.warn("the store did not confirm the delete of DLQ entry {} (row {});"
                        + " it is reported as kept", dlqId, doc.getId());
            }
        }
        return new DlqDeletion(confirmed, unconfirmed);
    }

    // ── Internal ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String upsertDocument(String docKey, String docType, Map<String, Object> jsonMap) {
        CloudantClientWrapper client = getConfClient();
        String dbName = client.getDatabaseName();
        var cloudant = client.getClient();

        jsonMap.put("type", docType);
        Document doc = new Document();
        for (Map.Entry<String, Object> entry : jsonMap.entrySet()) {
            doc.put(entry.getKey(), entry.getValue());
        }

        // Find existing by docKey field name (jobId or dlqId)
        String keyField = docType.equals(IngestJobRecord.DOC_TYPE) ? "jobId" : "dlqId";
        List<Document> existing = findRawDocs(cloudant, dbName,
                Map.of("type", docType, keyField, docKey));
        if (!existing.isEmpty()) {
            Document prior = existing.get(0);
            doc.setId(prior.getId());
            doc.setRev(prior.getRev());
            // Carry the attachments forward. This builds a FRESH Document, so anything not
            // copied is dropped by CouchDB — and a dead-letter entry's attachment is the
            // ingested payload, the one thing that makes the entry retryable. It mattered
            // little while ids were random (an update was rare); it matters on every repeat now
            // that the id identifies the source item (external review).
            if (prior.getAttachments() != null && !prior.getAttachments().isEmpty()
                    && doc.getAttachments() == null) {
                doc.setAttachments(prior.getAttachments());
            }
        } else if (IngestDeadLetterRecord.DOC_TYPE.equals(docType)) {
            // No prior row was RETURNED — which is also what a rebuilding index answers for
            // a row that is there. A generated id then wrote a second row for the same item,
            // and the id-addressed read that closed this for definitions does not exist here.
            // A deterministic id turns that second write into a 409 (the save reports it as
            // "could not record", the safe direction) instead of a twin. No compare-and-swap
            // is involved (R1 is separate): this only stops the selector's empty answer from
            // being written as "there is no row". Rows written before this carry generated
            // ids and are not migrated: a twin of such a LEGACY row while the index rebuilds
            // remains possible and is recorded (R23).
            doc.setId("ingest_dlq:" + docKey);
        }

        PostDocumentOptions options = new PostDocumentOptions.Builder()
                .db(dbName).document(doc).build();
        DocumentResult result = cloudant.postDocument(options).execute().getResult();
        if (!result.isOk()) {
            logger.error("Failed to save {} {}: {}", docType, docKey, result.getError());
            return null;
        }
        return result.getId();
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> findByType(String docType, Class<T> clazz, int limit) {
        return findBySelector(Map.of("type", docType), clazz, limit);
    }

    private <T> List<T> findByTypeAndField(String docType, String field, String value,
                                            Class<T> clazz, int limit) {
        return findBySelector(Map.of("type", docType, field, value), clazz, limit);
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> findBySelector(Map<String, Object> selector, Class<T> clazz, int limit) {
        CloudantClientWrapper client = getConfClient();
        String dbName = client.getDatabaseName();
        var cloudant = client.getClient();

        List<Document> rawDocs = findRawDocs(cloudant, dbName, selector);
        List<T> results = new ArrayList<>();
        int count = 0;
        for (Document rawDoc : rawDocs) {
            if (count >= limit) break;
            try {
                Map<String, Object> props = new HashMap<>(rawDoc.getProperties());
                props.remove("_id");
                props.remove("_rev");
                props.remove("type");
                results.add(MAPPER.convertValue(props, clazz));
                count++;
            } catch (Exception e) {
                logger.warn("Failed to deserialize {}: {}", clazz.getSimpleName(), e.getMessage());
            }
        }
        return results;
    }

    private List<Document> findRawDocs(com.ibm.cloud.cloudant.v1.Cloudant cloudant,
                                       String dbName, Map<String, Object> selector) {
        return findRawDocs(cloudant, dbName, selector, 200, 0);
    }

    /**
     * @param limit how many documents to fetch; the caller's own limit is applied afterwards
     * @param skip how many to pass over — without this the fetch was capped at a hardcoded 200
     *        with no ordering, so an entry beyond the first arbitrary 200 could be neither listed
     *        nor deleted, and which 200 you got was not stable (external review)
     */
    private List<Document> findRawDocs(com.ibm.cloud.cloudant.v1.Cloudant cloudant,
                                       String dbName, Map<String, Object> selector,
                                       int limit, int skip) {
        PostFindOptions.Builder builder = new PostFindOptions.Builder()
                .db(dbName).selector(selector).limit(Math.max(1, limit));
        if (skip > 0) {
            builder.skip((long) skip);
        }
        FindResult findResult;
        try {
            findResult = cloudant.postFind(builder.build()).execute().getResult();
        } catch (RuntimeException couldNotAsk) {
            // Raw, a transport failure here reached the DELETE endpoint as a Spring 500 and
            // the retry door's cleanup as 500 "Retry failed" — the sibling of the null-list
            // case below, left unwrapped when that one was typed (R40).
            throw new IngestStoreDidNotAnswerException("the ingest store did not answer the"
                    + " query for " + selector.keySet() + "; retry shortly: "
                    + couldNotAsk.getMessage());
        }
        List<Document> docs = findResult == null ? null : findResult.getDocs();
        // A response without a document list is the store NOT ANSWERING — NemakiConfFind
        // defines the same shape that way and refuses. Here it was collapsed into "there are
        // no rows": an empty DLQ listing, a 404 on retry, a purge that deleted nothing and
        // said success. Codex read the two definitions side by side. Every caller already
        // handles a RuntimeException from this read (the transport throws here too), so this
        // joins that class rather than adding a new one.
        if (docs == null) {
            throw new IngestStoreDidNotAnswerException("the ingest store returned no document"
                    + " list for " + selector.keySet() + "; retry shortly");
        }
        return docs;
    }

    /** A _find that came back without a document list — never the same answer as "no rows". */
    public static class IngestStoreDidNotAnswerException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public IngestStoreDidNotAnswerException(String message) { super(message); }
    }

    private CloudantClientWrapper getConfClient() {
        CloudantClientWrapper client = connectorPool.getClient(SystemConst.NEMAKI_CONF_DB);
        if (client == null) {
            // Unwired is "could not ask", typed like every other answer this store fails to
            // give — an IllegalStateException here became a 500 in front of the same
            // endpoints (R40).
            throw new IngestStoreDidNotAnswerException("the ingest store is not wired on this"
                    + " node (nemaki_conf database client not available); retry shortly against"
                    + " a node that runs it");
        }
        return client;
    }

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }
}
