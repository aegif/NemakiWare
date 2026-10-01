package jp.aegif.nemaki.rest.ingest.chat;

import jp.aegif.nemaki.rest.ingest.*;
import jp.aegif.nemaki.rest.ingest.chat.ChatworkConnectorAdapter.ChatworkMessage;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chatwork: the room's latest messages are read, and the ones newer than the checkpoint are taken
 * oldest first.
 *
 * <p>Chatwork's API answers a room's latest 100 messages and nothing older — it has no paging. The
 * previous shape stopped with an error when the oldest message it was answered was newer than the
 * checkpoint: the messages between cannot be fetched, so every later poll stopped the same way until
 * an operator cleared the checkpoint (R107, P2). It decided so on any answer, even one from a room
 * holding fewer than 100 messages whose checkpoint message had been deleted. A message whose id was
 * not a number was skipped silently, a refused import was not recorded, and a failed one was
 * recorded without its text.
 *
 * <p>Now the answer is checked whole — every message with a numeric id, or nothing is taken — and
 * sorted by id. A gap is possible only when the answer is full (the API's 100) and its oldest message
 * is newer than the checkpoint. Whether there IS one the answer cannot say — the ids are not
 * consecutive, and exactly 100 new messages look the same — so it is recorded as a possible gap: a
 * dead-letter row naming the room and the ids it would lie between, marked as a gap record by the
 * service (the DLQ controller does not replay such a row), and the run goes on. The messages newer
 * than the checkpoint are taken
 * oldest first within a budget of settled messages, at most {@code limit × 4} tried; a failure is
 * dead-lettered as read with the message's text, and passed; a failure or a gap whose row could not
 * be written stops the run with the checkpoint before it. The checkpoint is the newest message
 * passed.
 *
 * <p>Not covered: the messages that left the 100-message window before a poll reached them — the gap
 * row records where they would have been. Files: the room's latest 100, offered on every scheduled poll (the
 * import service's dedupe answers for the ones imported).
 */
public class ChatworkFetchOrchestrator implements FetchOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(ChatworkFetchOrchestrator.class);
    /** The most messages Chatwork answers for a room: its latest ones. */
    static final int WINDOW = 100;
    /** How many messages one run may TRY per unit of budget: bounds a run whose messages keep failing. */
    static final int ATTEMPTS_PER_BUDGET = 4;
    /**
     * The source object type of a gap row, for whoever reads the queue. The DLQ controller refuses to
     * replay such a row by the mark the service writes, not by this type: any caller may send it.
     */
    public static final String GAP_TYPE = "chat_gap";

    private FetchSupport fetchSupport;
    private CheckpointManager checkpointManager;
    private CanonicalImportService canonicalImportService;
    /** The adapter, by token; tests point it at a local stub of the Chatwork API. */
    java.util.function.Function<String, ChatworkConnectorAdapter> adapterFactory = ChatworkConnectorAdapter::new;

    public void setFetchSupport(FetchSupport fs) { this.fetchSupport = fs; }
    public void setCheckpointManager(CheckpointManager cm) { this.checkpointManager = cm; }
    public void setCanonicalImportService(CanonicalImportService cis) { this.canonicalImportService = cis; }

    @Override public String sourceSystem() { return "chatwork"; }

    private enum Outcome { SETTLED, FAILED_RECORDED, FAILED_UNRECORDED }

    /** The per-item counts one run adds to. */
    private static final class Counts { int imported, skipped; }

    /** A message id as a number, or -1 when it is not one. */
    private static long idOf(ChatworkMessage message) {
        if (message == null || message.messageId() == null) return -1;
        try {
            long id = Long.parseLong(message.messageId().trim());
            return id > 0 ? id : -1;
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    @Override
    public FetchResult execute(CallContext callContext, ImportProfileDefinition profile,
                               ConnectorDefinition connector, Map<String, String> params, int limit) {
        String roomId = params.getOrDefault("roomId", "");
        // resolvePasswordOrRefuse: a configuration read that FAILED used to arrive here as
        // "no token", which this method states as a fact. The scheduler counts that towards
        // opening the connector's circuit breaker and the folder endpoint turns it into
        // authError=true, prompting an admin to overwrite a credential that was never wrong.
        //
        // The refusal is NOT caught here. An earlier version of this comment said it lands in
        // this orchestrator's outer catch — it does not, this call is above the try — and a
        // review found the sentence false in all eleven copies. The outer catch below rethrows
        // it explicitly, so the scheduler can tell a configuration outage from the connector
        // failing and leave the circuit breaker alone.
        String token = fetchSupport.resolvePasswordOrRefuse(connector);
        if (token == null) return new FetchResult(0, 0, List.of("No token for Chatwork connector"));
        if (roomId.isBlank()) return new FetchResult(0, 0, List.of("roomId is required for Chatwork"));

        List<String> errors = new ArrayList<>();
        List<String> incompleteReads = new ArrayList<>();
        Counts counts = new Counts();
        int fetched = 0;
        String key = "chatwork." + roomId;
        try {
            var chatwork = adapterFactory.apply(token);
            String stored = checkpointManager.loadSimpleCheckpoint(profile.getProfileId(), key);
            long checkpoint = 0;
            if (stored != null && !stored.isBlank()) {
                try {
                    checkpoint = Long.parseLong(stored.trim());
                } catch (NumberFormatException e) {
                    String msg = "Chatwork checkpoint for room " + roomId + " is corrupted: '" + stored
                            + "'. Delete the checkpoint via admin API to re-sync, or set a valid numeric ID.";
                    logger.error(msg);
                    FetchSupport.addError(errors, msg);
                    return new FetchResult(0, 0, 0, errors);
                }
            }

            List<ChatworkMessage> answer = chatwork.getMessages(roomId, true);
            fetched = answer.size();
            // The answer, checked whole: a message this connector cannot place by its id is not
            // skipped — skipped, the checkpoint would pass it with nothing saying so.
            for (ChatworkMessage message : answer) {
                if (idOf(message) < 0) {
                    FetchSupport.addError(errors, "Chatwork answered a message of room " + roomId + " without a numeric id ('"
                            + (message == null ? null : message.messageId()) + "'); nothing on the answer was taken");
                    return new FetchResult(fetched, 0, 0, errors, List.copyOf(incompleteReads));
                }
            }
            List<ChatworkMessage> ordered = new ArrayList<>(answer);
            ordered.sort(Comparator.comparingLong(ChatworkFetchOrchestrator::idOf));

            long passed = checkpoint;
            if (checkpoint > 0 && ordered.size() >= WINDOW && idOf(ordered.get(0)) > checkpoint) {
                // The window no longer reaches the checkpoint: if messages were sent between, they
                // were not answered, and this API cannot give them. Whether there were any the answer
                // cannot say — Chatwork's ids are not consecutive, and exactly 100 new messages would
                // look the same (review, P2). Recorded as a POSSIBLE gap, and the run goes on:
                // stopping could not bring them back, and it stopped every later poll too (R107, P2).
                long oldest = idOf(ordered.get(0));
                String why = "Chatwork room " + roomId + ": a possible gap — the API answers only the latest " + WINDOW
                        + " messages, the answer was full, and its oldest, id " + oldest + ", is newer than the checkpoint, id "
                        + checkpoint + "; messages sent between them, if there were any, were not answered and cannot be fetched";
                FetchSupport.addError(errors, why);
                if (!fetchSupport.saveGapRecordToDlq(gapRequest(profile, connector, roomId, checkpoint, oldest), why)) {
                    FetchSupport.addError(errors, "the possible Chatwork gap in room " + roomId + " could not be dead-lettered; the "
                            + "checkpoint holds so that it is found again");
                    return new FetchResult(fetched, 0, 0, errors, List.copyOf(incompleteReads));
                }
            }

            long throttleMs = FetchSupport.calculateThrottleDelayMs(connector);
            int settled = 0, attempted = 0;
            boolean unrecorded = false;
            for (ChatworkMessage message : ordered) {
                long id = idOf(message);
                if (id <= checkpoint) {
                    counts.skipped++;
                    continue;
                }
                if (settled >= limit) {
                    incompleteReads.add("Chatwork room " + roomId + ": the run's limit of " + limit + " message(s) was reached; "
                            + "the newer messages are left for the next poll (while they are among the latest " + WINDOW + ")");
                    break;
                }
                if (attempted >= limit * ATTEMPTS_PER_BUDGET) {
                    incompleteReads.add("Chatwork room " + roomId + ": " + attempted + " message(s) were attempted ("
                            + ATTEMPTS_PER_BUDGET + " × the limit of " + limit + ") with only " + settled + " settled — the "
                            + "failures are in the dead-letter queue; the newer messages are left for the next poll");
                    break;
                }
                attempted++;
                fetchSupport.throttle(throttleMs);
                Outcome outcome = attempt(callContext, profile, connector, roomId, message, errors, counts);
                if (outcome == Outcome.FAILED_UNRECORDED) {
                    unrecorded = true;
                    break;
                }
                if (outcome == Outcome.SETTLED) settled++;
                passed = id;
            }
            if (unrecorded) {
                FetchSupport.addError(errors, "a Chatwork failure could not be dead-lettered; the checkpoint holds before it "
                        + "so that it is offered again");
            }

            // Import files during scheduled runs only (not webhook small fetches)
            if (limit > 10) try {
                var files = chatwork.listFiles(roomId);
                for (var file : files) {
                    fetchSupport.throttle(throttleMs);
                    InputStream content = null;
                    try {
                        String dlUrl = chatwork.getFileDownloadUrl(roomId, file.fileId());
                        if (dlUrl == null) continue;
                        content = chatwork.downloadFile(dlUrl);
                        ExternalIngestRequest fileReq = new ExternalIngestRequest();
                        fileReq.setProfileId(profile.getProfileId());
                        fileReq.setConnectorId(connector.getConnectorId());
                        fileReq.setRepositoryId(profile.getRepositoryId());
                        fileReq.setSourceObjectId("file-" + file.fileId());
                        fileReq.setSourceObjectType("attachment");
                        fileReq.setFileName(file.name());
                        fileReq.setMimeType(FetchSupport.guessMimeType(file.name()));
                        fileReq.setContentStream(content);
                        fileReq.setExecutionMode("scheduled");
                        Map<String, Object> fileMeta = new LinkedHashMap<>();
                        fileMeta.put("channelId", roomId);
                        fileMeta.put("workspaceId", connector.getTenantId());
                        fileReq.setMetadata(fileMeta);

                        ExternalIngestResult fileResult = canonicalImportService.executeChatContextImport(callContext, fileReq);
                        // skipped() first: a skipped result also reports
                        // isSuccess()==true (no errors), so it would be
                        // miscounted as imported otherwise.
                        if (fileResult.skipped()) counts.skipped++;
                        else if (fileResult.isSuccess()) counts.imported++;
                        else FetchSupport.addError(errors, "Chatwork file " + file.fileId() + ": " + String.join(", ", fileResult.errors()));
                    } catch (Exception e) {
                        FetchSupport.addError(errors, "Chatwork file " + file.fileId() + ": " + e.getMessage());
                    } finally {
                        if (content != null) try { content.close(); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) { FetchSupport.addError(errors, "Chatwork file list failed: " + e.getMessage()); }

            if (passed > checkpoint) {
                checkpointManager.saveSimpleCheckpoint(profile.getProfileId(), key, String.valueOf(passed));
            }
        } catch (jp.aegif.nemaki.rest.controller.IntegrationSettingsService
                .SettingUnreadableException couldNotAsk) {
            // NOT the connector's failure. The checkpoint read refuses from INSIDE this try,
            // so swallowing it here turned a configuration-store outage into
            // "<connector> connection failed" — an error the scheduler counts towards opening
            // that connector's circuit breaker, and which the folder and trigger endpoints
            // repeat back as the connector being in trouble. The credential half was exempted
            // a round earlier by catching it in the scheduler; a review found the checkpoint
            // half never reaching there because this catch stood in the way.
            throw couldNotAsk;
        } catch (Exception e) { FetchSupport.addError(errors, "Chatwork connection failed: " + e.getMessage()); }
        return new FetchResult(fetched, counts.imported, counts.skipped, errors, List.copyOf(incompleteReads));
    }

    /** The dead-letter row of a gap: the room and the ids the unanswered messages lie between. */
    private static ExternalIngestRequest gapRequest(ImportProfileDefinition profile, ConnectorDefinition connector,
                                                    String roomId, long after, long before) {
        ExternalIngestRequest gap = new ExternalIngestRequest();
        gap.setProfileId(profile.getProfileId());
        gap.setConnectorId(connector.getConnectorId());
        gap.setRepositoryId(profile.getRepositoryId());
        gap.setSourceObjectId("gap:" + roomId + ":" + after + "-" + before);
        gap.setSourceObjectType(GAP_TYPE);
        gap.setFileName("chatwork-gap-" + after + "-" + before + ".txt");
        gap.setMimeType("text/plain");
        gap.setExecutionMode("scheduled");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("channelId", roomId);
        metadata.put("gapAfterMessageId", String.valueOf(after));
        metadata.put("gapBeforeMessageId", String.valueOf(before));
        metadata.put("workspaceId", connector.getTenantId());
        gap.setMetadata(metadata);
        return gap;
    }

    /**
     * One message: its text, imported. A failure — the import refused, or threw — is dead-lettered
     * as read WITH the text, so the replay imports it; RECORDED / UNRECORDED says whether the row
     * was written.
     */
    private Outcome attempt(CallContext callContext, ImportProfileDefinition profile, ConnectorDefinition connector,
                            String roomId, ChatworkMessage msg, List<String> errors, Counts counts) {
        String messageText = msg.body() != null ? msg.body() : "";
        byte[] bytes = messageText.getBytes(StandardCharsets.UTF_8);
        ExternalIngestRequest msgReq = new ExternalIngestRequest();
        msgReq.setProfileId(profile.getProfileId());
        msgReq.setConnectorId(connector.getConnectorId());
        msgReq.setRepositoryId(profile.getRepositoryId());
        msgReq.setSourceObjectId(msg.messageId());
        msgReq.setSourceObjectType("chat_message");
        msgReq.setFileName("chatwork-" + msg.messageId() + ".txt");
        msgReq.setMimeType("text/plain");
        msgReq.setContentStream(new ByteArrayInputStream(bytes));
        msgReq.setExecutionMode("scheduled");
        Map<String, Object> msgMeta = new LinkedHashMap<>();
        msgMeta.put("channelId", roomId);
        msgMeta.put("messageId", msg.messageId());
        msgMeta.put("senderId", msg.accountId());
        msgMeta.put("senderName", msg.accountName());
        msgMeta.put("messageText", FetchSupport.truncateForContext(messageText));
        msgMeta.put("workspaceId", connector.getTenantId());
        msgReq.setMetadata(msgMeta);
        try {
            ExternalIngestResult result = canonicalImportService.executeChatContextImport(callContext, msgReq);
            // skipped() first: a skipped result also reports isSuccess()==true (no errors).
            if (result.skipped()) {
                counts.skipped++;
                return Outcome.SETTLED;
            }
            if (result.isSuccess()) {
                counts.imported++;
                return Outcome.SETTLED;
            }
            String why = "Chatwork msg " + msg.messageId() + ": " + String.join(", ", result.errors());
            FetchSupport.addError(errors, why);
            return fetchSupport.saveSourceReadToDlq(msgReq, why, bytes) ? Outcome.FAILED_RECORDED : Outcome.FAILED_UNRECORDED;
        } catch (Exception e) {
            String why = "Chatwork msg " + msg.messageId() + ": " + e.getMessage();
            FetchSupport.addError(errors, why);
            return fetchSupport.saveSourceReadToDlq(msgReq, why, bytes) ? Outcome.FAILED_RECORDED : Outcome.FAILED_UNRECORDED;
        }
    }
}
