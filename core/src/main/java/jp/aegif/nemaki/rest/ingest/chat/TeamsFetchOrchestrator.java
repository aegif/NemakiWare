package jp.aegif.nemaki.rest.ingest.chat;

import jp.aegif.nemaki.rest.ingest.*;
import jp.aegif.nemaki.rest.ingest.chat.TeamsConnectorAdapter.TeamsFile;
import jp.aegif.nemaki.rest.ingest.chat.TeamsConnectorAdapter.TeamsMessage;
import org.apache.chemistry.opencmis.commons.server.CallContext;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Teams: the channel is read down to the checkpoint and the OLDEST new messages are taken first.
 *
 * <p>Graph lists channel messages newest first and offers no filter on the creation time. The
 * previous shape asked for the first {@code limit} messages — the {@code limit} NEWEST — and then
 * raised the checkpoint to the newest it had seen, so in a burst every older new message fell
 * below the checkpoint for ever (R107). Now the listing runs down to the checkpoint, the
 * candidates are taken oldest first within a budget of settled messages, and the checkpoint moves
 * to the newest message that settled — a {@link WatermarkCheckpoint}, naming the ids at its
 * timestamp, since two messages may share a creation time.
 *
 * <p>A message settles when its body and every attachment were imported or skipped by the
 * import service. A message that failed is dead-lettered — a download failure as a never-read
 * row that the DLQ controller fetches again by the file's URL, a failure after the read as a read
 * row — and is not named; a newer message that settles passes it, the row being the record. A
 * failure whose row could not be written holds the checkpoint. A listing cut at the request cap
 * imports nothing: the messages it did not reach are the OLDER ones.
 *
 * <p>Not covered: edits (the creation time does not change), replies (not listed by the channel
 * messages call), and messages that appear later with an older creation time — a time watermark
 * cannot see them (the same limit as R109). Graph's newest-first order is assumed, not measured.
 */
public class TeamsFetchOrchestrator implements FetchOrchestrator {

    static final String PARAM_MAX_MESSAGE_REQUESTS = "teamsMessageMaxRequests";
    static final int MAX_MESSAGE_REQUESTS = 1_000_000;
    /** How many messages one run may TRY per unit of budget: bounds a run whose candidates keep failing. */
    static final int ATTEMPTS_PER_BUDGET = 4;

    private FetchSupport fetchSupport;
    private CheckpointManager checkpointManager;
    private CanonicalImportService canonicalImportService;
    /** The adapter, by token; tests point it at a local stub of Graph. */
    java.util.function.Function<String, TeamsConnectorAdapter> adapterFactory = TeamsConnectorAdapter::new;

    public void setFetchSupport(FetchSupport fs) { this.fetchSupport = fs; }
    public void setCheckpointManager(CheckpointManager cm) { this.checkpointManager = cm; }
    public void setCanonicalImportService(CanonicalImportService cis) { this.canonicalImportService = cis; }

    @Override public String sourceSystem() { return "teams"; }

    private static int intParam(Map<String, String> params, String name, int fallback, int minimum, int maximum) {
        String raw = params == null ? null : params.get(name);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int value = Integer.parseInt(raw.trim());
            if (value >= minimum && value <= maximum) return value;
        } catch (NumberFormatException notANumber) {
            // fall through
        }
        throw new IllegalArgumentException("Teams connector parameter " + name
                + " must be an integer between " + minimum + " and " + maximum + ", not '" + raw + "'");
    }

    private enum Outcome { SETTLED, FAILED_RECORDED, FAILED_UNRECORDED }

    private static final class Counts { int imported, skipped; }

    @Override
    public FetchResult execute(CallContext callContext, ImportProfileDefinition profile,
                               ConnectorDefinition connector, Map<String, String> params, int limit) {
        String teamId = params.getOrDefault("teamId", "");
        String channelId = params.getOrDefault("channelId", "");
        // resolvePasswordOrRefuse: a configuration read that FAILED used to arrive here as
        // "no token", which this method states as a fact. The refusal is NOT caught here: the
        // outer catch below rethrows it explicitly, so the scheduler can tell a configuration
        // outage from the connector failing.
        String token = fetchSupport.resolvePasswordOrRefuse(connector);
        if (token == null) return new FetchResult(0, 0, List.of("No token for Teams connector"));

        int maxRequests;
        try {
            maxRequests = intParam(params, PARAM_MAX_MESSAGE_REQUESTS, TeamsConnectorAdapter.DEFAULT_MAX_MESSAGE_REQUESTS, 1, MAX_MESSAGE_REQUESTS);
        } catch (IllegalArgumentException badParameter) {
            return new FetchResult(0, 0, List.of(badParameter.getMessage()));
        }

        List<String> errors = new ArrayList<>();
        List<String> incompleteReads = new ArrayList<>();
        Counts counts = new Counts();
        int fetched = 0;
        String key = "teams." + teamId + "." + channelId;
        try {
            var teams = adapterFactory.apply(token);
            String stored = checkpointManager.loadSimpleCheckpoint(profile.getProfileId(), key);
            WatermarkCheckpoint parsed = WatermarkCheckpoint.parse(stored);
            // Checkpoints written before this batch carried Graph's own timestamp string; every
            // comparison below is on the canonical form. A stored checkpoint that cannot be read
            // is NOT "no checkpoint": read as none it would silently re-offer the whole channel.
            WatermarkCheckpoint checkpoint = parsed;
            if (parsed.at() != null) {
                String at = WatermarkCheckpoint.canonical(parsed.at());
                if (at == null) {
                    FetchSupport.addError(errors, "Teams checkpoint '" + stored + "' for " + key
                            + " is not a timestamp this connector can read; correct or clear it — nothing was read");
                    return new FetchResult(0, 0, 0, errors);
                }
                checkpoint = new WatermarkCheckpoint(at, parsed.idsAt());
            }
            // Down to the checkpoint (R107): the adapter stops at the first message at or below
            // it — Graph lists newest first — and reports a cut when the cap comes first.
            TeamsConnectorAdapter.MessageListing listing = teams.listSince(teamId, channelId, checkpoint.at(), maxRequests);
            fetched = listing.messages().size();
            Map<String, String> createdAt = new java.util.HashMap<>();
            List<TeamsMessage> candidates = new ArrayList<>();
            for (TeamsMessage msg : listing.messages()) {
                if (msg.id() == null || msg.id().isBlank()) {
                    FetchSupport.addError(errors, "Graph listed a message in " + key + " without an id; nothing was imported and the checkpoint holds");
                    return new FetchResult(fetched, 0, 0, errors);
                }
                String at = WatermarkCheckpoint.canonical(msg.createdDateTime());
                createdAt.put(msg.id(), at);
                // The adapter stopped at the checkpoint; the id-level part (a message AT the
                // checkpoint's timestamp already named) is applied here.
                if (checkpoint.covers(at, msg.id())) {
                    counts.skipped++;
                    continue;
                }
                candidates.add(msg);
            }
            // Oldest first, so that what a budget leaves for the next poll is always NEWER than
            // what it took and the checkpoint can move without passing it.
            candidates.sort(java.util.Comparator.comparing((TeamsMessage m) -> createdAt.get(m.id()))
                    .thenComparing(TeamsMessage::id));

            List<TeamsMessage> candidatesThisRun;
            if (!listing.complete()) {
                // NOT an error — nothing failed. Recorded so the job says PARTIAL — and nothing is
                // imported: the messages this poll was not shown are the OLDER ones, so a
                // checkpoint raised over the ones it was shown would exclude them for ever.
                incompleteReads.add("Teams channel " + channelId + ": " + listing.truncatedBecause()
                        + " — nothing was imported and the checkpoint holds, because the messages "
                        + "left out are the older ones");
                candidatesThisRun = List.of();
            } else {
                candidatesThisRun = candidates;
            }
            long throttleMs = FetchSupport.calculateThrottleDelayMs(connector);

            List<WatermarkCheckpoint.Mark> settled = new ArrayList<>();
            int attempted = 0;
            boolean attemptsExhausted = false;
            int unrecordedFailures = 0;
            for (TeamsMessage msg : candidatesThisRun) {
                if (settled.size() >= limit) {
                    break;
                }
                if (attempted >= limit * ATTEMPTS_PER_BUDGET) {
                    attemptsExhausted = true;
                    break;
                }
                attempted++;
                fetchSupport.throttle(throttleMs);
                switch (attempt(callContext, profile, connector, teams, teamId, channelId, msg, errors, counts)) {
                    case SETTLED -> settled.add(new WatermarkCheckpoint.Mark(createdAt.get(msg.id()), msg.id()));
                    case FAILED_RECORDED -> { }
                    case FAILED_UNRECORDED -> unrecordedFailures++;
                }
            }
            int leftForTheNextPoll = candidatesThisRun.size() - attempted;
            if (leftForTheNextPoll > 0) {
                incompleteReads.add(attemptsExhausted
                        ? "Teams channel " + channelId + ": " + attempted + " message(s) were attempted ("
                                + ATTEMPTS_PER_BUDGET + " × the limit of " + limit + ") with only "
                                + settled.size() + " settled, leaving " + leftForTheNextPoll
                                + " newer message(s) untried — the failures are in the dead-letter queue; "
                                + "the messages behind them are reached only once they settle or the "
                                + "checkpoint passes them, or with a higher limit"
                        : "Teams channel " + channelId + ": the run's limit of " + limit
                                + " message(s) was reached with " + leftForTheNextPoll
                                + " newer message(s) left for the next poll");
            }
            if (unrecordedFailures > 0) {
                FetchSupport.addError(errors, unrecordedFailures + " Teams failure(s) could not be dead-lettered; "
                        + "the checkpoint holds so that they are offered again");
            }
            // Saved only when the POSITION moved: a legacy checkpoint read in Graph's own form
            // encodes differently after normalisation without having moved.
            String before = checkpoint.encode();
            String next = unrecordedFailures > 0 ? null : checkpoint.after(settled).encode();
            if (next != null && !next.equals(before)) {
                checkpointManager.saveSimpleCheckpoint(profile.getProfileId(), key, next);
            }
        } catch (jp.aegif.nemaki.rest.controller.IntegrationSettingsService
                .SettingUnreadableException couldNotAsk) {
            // NOT the connector's failure: a configuration-store outage, rethrown so the
            // scheduler leaves the circuit breaker alone.
            throw couldNotAsk;
        } catch (Exception e) {
            FetchSupport.addError(errors, "Teams connection failed: " + e.getMessage());
        }
        return new FetchResult(fetched, counts.imported, counts.skipped, errors, List.copyOf(incompleteReads));
    }

    /**
     * One message: its body and every attachment. SETTLED when all of them were imported or
     * skipped; otherwise the failures are dead-lettered — a download failure as a never-read row
     * (the DLQ controller fetches the bytes again by the URL the row carries), a failure after
     * the read as a read row — and RECORDED / UNRECORDED says whether every row was written.
     */
    private Outcome attempt(CallContext callContext, ImportProfileDefinition profile, ConnectorDefinition connector,
                            TeamsConnectorAdapter teams, String teamId, String channelId, TeamsMessage msg,
                            List<String> errors, Counts counts) {
        boolean failed = false, unrecorded = false;
        String messageBody = msg.body() != null ? msg.body() : "";
        String parentObjectId = null;
        ExternalIngestRequest msgReq = new ExternalIngestRequest();
        msgReq.setProfileId(profile.getProfileId());
        msgReq.setConnectorId(connector.getConnectorId());
        msgReq.setRepositoryId(profile.getRepositoryId());
        msgReq.setSourceObjectId(msg.id());
        msgReq.setSourceObjectType("chat_message");
        msgReq.setFileName("teams-" + msg.id() + ".html");
        msgReq.setMimeType("text/html");
        msgReq.setContentStream(new ByteArrayInputStream(messageBody.getBytes(StandardCharsets.UTF_8)));
        msgReq.setExecutionMode("scheduled");
        Map<String, Object> msgMeta = new LinkedHashMap<>();
        msgMeta.put("channelId", channelId);
        msgMeta.put("messageId", msg.id());
        msgMeta.put("senderId", msg.from());
        msgMeta.put("messageText", FetchSupport.truncateForContext(messageBody));
        msgMeta.put("workspaceId", teamId);
        if (msg.replyToId() != null) msgMeta.put("threadId", msg.replyToId());
        msgReq.setMetadata(msgMeta);
        try {
            ExternalIngestResult result = canonicalImportService.executeChatContextImport(callContext, msgReq);
            if (result.skipped()) {
                counts.skipped++;
                parentObjectId = result.objectId();
            } else if (result.isSuccess()) {
                counts.imported++;
                parentObjectId = result.objectId();
            } else {
                failed = true;
                FetchSupport.addError(errors, "Teams msg " + msg.id() + ": " + String.join(", ", result.errors()));
                if (!fetchSupport.saveSourceReadToDlq(msgReq, "Teams msg " + msg.id() + ": " + String.join(", ", result.errors()))) {
                    unrecorded = true;
                }
            }
        } catch (Exception e) {
            failed = true;
            FetchSupport.addError(errors, "Teams msg " + msg.id() + ": " + e.getMessage());
            if (!fetchSupport.saveSourceReadToDlq(msgReq, "Teams msg " + msg.id() + ": " + e.getMessage())) {
                unrecorded = true;
            }
        }
        for (TeamsFile file : msg.attachments()) {
            if (file.contentUrl() == null) continue;
            // Built BEFORE the download, so a download failure still has an item-naming row to
            // write. The URL travels with the row: the DLQ controller fetches the bytes again by it.
            ExternalIngestRequest req = new ExternalIngestRequest();
            req.setProfileId(profile.getProfileId());
            req.setConnectorId(connector.getConnectorId());
            req.setRepositoryId(profile.getRepositoryId());
            req.setSourceObjectId(file.id());
            req.setSourceObjectType("attachment");
            req.setFileName(file.name());
            req.setMimeType(file.contentType());
            req.setExecutionMode("scheduled");
            Map<String, Object> fileMeta = new LinkedHashMap<>();
            fileMeta.put("channelId", channelId);
            fileMeta.put("messageId", msg.id());
            fileMeta.put("senderId", msg.from());
            fileMeta.put("messageText", FetchSupport.truncateForContext(messageBody));
            fileMeta.put("workspaceId", teamId);
            if (msg.replyToId() != null) fileMeta.put("threadId", msg.replyToId());
            fileMeta.put("teamsFileUrl", file.contentUrl());
            req.setMetadata(fileMeta);
            InputStream content = null;
            try {
                try {
                    content = teams.downloadFile(file.contentUrl());
                } catch (Exception downloadFailed) {
                    failed = true;
                    FetchSupport.addError(errors, "Teams file " + file.id() + ": " + downloadFailed.getMessage());
                    if (!fetchSupport.saveSourceNeverReadToDlq(req, "Teams file " + file.id() + ": " + downloadFailed.getMessage())) {
                        unrecorded = true;
                    }
                    continue;
                }
                req.setContentStream(content);
                ExternalIngestResult result = canonicalImportService.executeChatContextImport(callContext, req);
                if (result.skipped() || result.isSuccess()) {
                    if (result.skipped()) counts.skipped++; else counts.imported++;
                    if (parentObjectId != null && result.objectId() != null) {
                        fetchSupport.createRelationshipSafe(callContext, profile.getRepositoryId(),
                                parentObjectId, result.objectId(), profile, req, errors);
                    }
                } else {
                    failed = true;
                    FetchSupport.addError(errors, "Teams " + file.id() + ": " + String.join(", ", result.errors()));
                    if (!fetchSupport.saveSourceReadToDlq(req, "Teams " + file.id() + ": " + String.join(", ", result.errors()))) {
                        unrecorded = true;
                    }
                }
            } catch (Exception e) {
                failed = true;
                FetchSupport.addError(errors, "Teams file " + file.id() + ": " + e.getMessage());
                if (!fetchSupport.saveSourceReadToDlq(req, "Teams file " + file.id() + ": " + e.getMessage())) {
                    unrecorded = true;
                }
            } finally {
                if (content != null) try { content.close(); } catch (Exception ignored) {}
            }
        }
        return unrecorded ? Outcome.FAILED_UNRECORDED : failed ? Outcome.FAILED_RECORDED : Outcome.SETTLED;
    }
}
