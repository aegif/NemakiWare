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
 * Teams: the channel is followed through Graph's delta feed, page by page.
 *
 * <p>Graph lists channel messages sorted by the last modified time of the whole reply chain — a
 * root message with a fresh reply comes first whatever its creation time (List channel messages,
 * "Response") — and offers no filter on the creation time. Every shape that read that listing and
 * kept a creation-time checkpoint failed on the order: the {@code limit} NEWEST were taken and the
 * checkpoint raised over the rest (R107); a stop at the first message created before the
 * checkpoint, with a check that the listing was in creation order, refused every channel with a
 * live thread for ever, or — without the check — reported as complete a span with newer messages
 * behind it (review, P1 ×2); reading the whole channel on every poll still lost a message whose
 * thread rose above the page cursor while the pages were being read (R112). The delta feed
 * ({@code /messages/delta}) is Graph's own change tracking and has none of these: it is followed
 * instead, and the checkpoint is the link to continue from ({@code delta:<link>}).
 *
 * <p>Where the feed starts: with no checkpoint, the whole feed (Graph documents it with an
 * eight-month window — older history is not imported); with a creation-time checkpoint written by
 * an earlier version, every message created or changed after it — from a millisecond before it,
 * so a message at that time comes again and the import service's dedupe answers for it.
 *
 * <p>A page is passed — the next link saved — only when every message on it was imported,
 * skipped by the import service, or dead-lettered. The run's budget ({@code limit} messages
 * imported; a skip spends none, so a page read again is not held by what it already took) stops
 * INSIDE a page without passing it, and the next poll reads the same link again; a message that
 * imported part of itself before a part failed spends it too. A failure whose
 * row could not be written holds the page. At most {@code limit × 4} messages are tried in a run,
 * counted between pages. A run cut at the request cap saves the link it reached, and the next poll
 * continues from it.
 *
 * <p>A message is imported with its body and every attachment. A failure is dead-lettered — a
 * download failure as a never-read row that the DLQ controller fetches again by the file's URL, a
 * body failure as a read row that carries the body, an attachment failure after the read as a read
 * row — and an attachment row names the message document, so that a replay links it again.
 *
 * <p>Not covered: replies (not in the feed), history older than the feed's window, and a message
 * edited after its import (it comes again; the import service's dedupe decides — the default
 * skips it). Graph's delta feed is measured against a stub, not against Graph.
 */
public class TeamsFetchOrchestrator implements FetchOrchestrator {

    static final String PARAM_MAX_MESSAGE_REQUESTS = "teamsMessageMaxRequests";
    static final int MAX_MESSAGE_REQUESTS = 1_000_000;
    /** How many messages one run may TRY per unit of budget: bounds a run whose messages keep failing. */
    static final int ATTEMPTS_PER_BUDGET = 4;
    /** A stored checkpoint that is a link into Graph's delta feed. */
    static final String DELTA_PREFIX = "delta:";

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

    /** IMPORTED spends the budget; SKIPPED (already imported) does not; both let the page pass. */
    private enum Outcome { IMPORTED, SKIPPED, FAILED_RECORDED, FAILED_UNRECORDED }

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
            // Not a connector failure and not the default either: guessing the default would
            // silently ignore what the operator wrote. Reported, and nothing is read.
            return new FetchResult(0, 0, List.of(badParameter.getMessage()));
        }

        List<String> errors = new ArrayList<>();
        List<String> incompleteReads = new ArrayList<>();
        Counts counts = new Counts();
        int fetched = 0;
        String key = "teams." + teamId + "." + channelId;
        String start = null;
        String reached = null;
        try {
            var teams = adapterFactory.apply(token);
            String stored = checkpointManager.loadSimpleCheckpoint(profile.getProfileId(), key);
            String value = stored == null ? "" : stored.trim();
            if (value.startsWith(DELTA_PREFIX)) {
                String link = value.substring(DELTA_PREFIX.length());
                if (!teams.isOwnDeltaLink(link, teamId, channelId)) {
                    // A checkpoint is not a place to put a URL: a link that does not point into
                    // THIS channel's feed on THIS endpoint is not followed.
                    FetchSupport.addError(errors, "Teams checkpoint for " + key + " is a delta link this connector did not write ('"
                            + link + "'); correct or clear it — nothing was read");
                    return new FetchResult(0, 0, 0, errors);
                }
                start = link;
            } else if (value.isEmpty()) {
                start = teams.initialDeltaLink(teamId, channelId, null);
            } else {
                // A creation-time checkpoint written by an earlier version — Graph's own string,
                // or <canonical time>|<ids>. The feed starts a millisecond before it. One that
                // cannot be read is NOT "no checkpoint": read as none it would silently re-offer
                // the whole feed, and nobody would learn that the stored value is broken.
                String at = WatermarkCheckpoint.canonical(WatermarkCheckpoint.parse(value).at());
                if (at == null) {
                    FetchSupport.addError(errors, "Teams checkpoint '" + stored + "' for " + key
                            + " is neither a delta link nor a timestamp this connector can read; correct or clear it — nothing was read");
                    return new FetchResult(0, 0, 0, errors);
                }
                java.time.Instant from = java.time.Instant.parse(at)
                        .truncatedTo(java.time.temporal.ChronoUnit.MILLIS).minusMillis(1);
                start = teams.initialDeltaLink(teamId, channelId, from);
            }
            reached = start;

            long throttleMs = FetchSupport.calculateThrottleDelayMs(connector);
            int pages = 0;
            int imported = 0;
            int attempted = 0;
            int unrecordedFailures = 0;
            boolean roundComplete = false;
            String cutBecause = null;
            while (true) {
                if (pages >= maxRequests) {
                    cutBecause = "the cap of " + maxRequests + " request(s) was reached with the delta feed still going "
                            + "(raise the profile's " + PARAM_MAX_MESSAGE_REQUESTS + " parameter); the next poll continues from the saved link";
                    break;
                }
                if (imported >= limit) {
                    cutBecause = "the run's limit of " + limit + " message(s) was reached; the next poll continues from the saved link";
                    break;
                }
                if (attempted >= limit * ATTEMPTS_PER_BUDGET) {
                    cutBecause = attempted + " message(s) were tried (" + ATTEMPTS_PER_BUDGET + " × the limit of " + limit
                            + ") with " + imported + " imported — the failures are in the dead-letter queue; "
                            + "the next poll continues from the saved link";
                    break;
                }
                TeamsConnectorAdapter.DeltaPage page = teams.delta(reached);
                pages++;
                fetched += page.messages().size();
                counts.skipped += page.deleted();
                // The page is checked whole before anything on it is imported: a message that
                // cannot be named, or a link out of this channel's feed, holds the page — and a
                // page held after half of it was imported is only read again with that half skipped.
                for (TeamsMessage msg : page.messages()) {
                    if (msg.id() == null || msg.id().isBlank()) {
                        FetchSupport.addError(errors, "Graph's delta feed for " + key
                                + " carried a message without an id; the page was not passed and nothing on it was imported");
                        return finish(profile, key, start, reached, fetched, counts, errors, incompleteReads);
                    }
                }
                String after = page.nextLink() != null ? page.nextLink() : page.deltaLink();
                if (!teams.isOwnDeltaLink(after, teamId, channelId)) {
                    FetchSupport.addError(errors, "Graph's delta feed for " + key + " answered a link outside this channel's feed ('"
                            + after + "'); the page was not passed and nothing on it was imported");
                    return finish(profile, key, start, reached, fetched, counts, errors, incompleteReads);
                }
                boolean stoppedInsideThePage = false;
                for (TeamsMessage msg : page.messages()) {
                    if (imported >= limit) {
                        stoppedInsideThePage = true;
                        break;
                    }
                    fetchSupport.throttle(throttleMs);
                    int documentsBefore = counts.imported;
                    switch (attempt(callContext, profile, connector, teams, teamId, channelId, msg, errors, counts)) {
                        case IMPORTED -> { imported++; attempted++; }
                        case SKIPPED -> { }
                        case FAILED_RECORDED -> {
                            attempted++;
                            // A message that imported part of itself — its body, say — before an
                            // attachment failed has spent the budget as much as one imported whole;
                            // not counting it let one page import past the limit (review, P2).
                            if (counts.imported > documentsBefore) imported++;
                        }
                        case FAILED_UNRECORDED -> { attempted++; unrecordedFailures++; }
                    }
                    if (unrecordedFailures > 0) {
                        break;
                    }
                }
                if (unrecordedFailures > 0) {
                    // A message nothing records: passing the page would lose it silently.
                    FetchSupport.addError(errors, unrecordedFailures + " Teams failure(s) could not be dead-lettered; "
                            + "the page holds so that they are offered again");
                    break;
                }
                if (stoppedInsideThePage) {
                    cutBecause = "the run's limit of " + limit + " message(s) was reached inside a page of the delta feed; "
                            + "the page is read again next poll";
                    break;
                }
                reached = after;
                if (page.nextLink() == null) {
                    roundComplete = true;
                    break;
                }
            }
            if (!roundComplete && unrecordedFailures == 0 && cutBecause != null) {
                // NOT an error — nothing failed, and an error on every poll would open the
                // connector's breaker. Recorded so the job says PARTIAL.
                incompleteReads.add("Teams channel " + channelId + ": " + cutBecause);
            }
            return finish(profile, key, start, reached, fetched, counts, errors, incompleteReads);
        } catch (jp.aegif.nemaki.rest.controller.IntegrationSettingsService
                .SettingUnreadableException couldNotAsk) {
            // NOT the connector's failure: a configuration-store outage, rethrown so the
            // scheduler leaves the circuit breaker alone.
            throw couldNotAsk;
        } catch (Exception e) {
            FetchSupport.addError(errors, "Teams connection failed: " + e.getMessage());
            // The pages passed before the failure stay passed: what they carried was imported or
            // recorded, and reading them again would only repeat the work.
            return finish(profile, key, start, reached, fetched, counts, errors, incompleteReads);
        }
    }

    /** The feed position after a run: saved when it moved. */
    private FetchResult finish(ImportProfileDefinition profile, String key, String start, String reached,
                               int fetched, Counts counts, List<String> errors, List<String> incompleteReads) {
        if (reached != null && !reached.equals(start)) {
            checkpointManager.saveSimpleCheckpoint(profile.getProfileId(), key, DELTA_PREFIX + reached);
        }
        return new FetchResult(fetched, counts.imported, counts.skipped, errors, List.copyOf(incompleteReads));
    }

    /**
     * One message: its body and every attachment. IMPORTED when something was imported and the
     * rest skipped, SKIPPED when everything was; otherwise the failures are dead-lettered — a
     * download failure as a never-read row (the DLQ controller fetches the bytes again by the
     * URL the row carries), a body failure as a read row carrying the body, an attachment failure
     * after the read as a read row — and RECORDED / UNRECORDED says whether every row was written.
     */
    private Outcome attempt(CallContext callContext, ImportProfileDefinition profile, ConnectorDefinition connector,
                            TeamsConnectorAdapter teams, String teamId, String channelId, TeamsMessage msg,
                            List<String> errors, Counts counts) {
        boolean failed = false, unrecorded = false;
        int importedBefore = counts.imported;
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
                if (!fetchSupport.saveSourceReadToDlq(msgReq, "Teams msg " + msg.id() + ": " + String.join(", ", result.errors()),
                        messageBody.getBytes(StandardCharsets.UTF_8))) {
                    unrecorded = true;
                }
            }
        } catch (Exception e) {
            failed = true;
            FetchSupport.addError(errors, "Teams msg " + msg.id() + ": " + e.getMessage());
            if (!fetchSupport.saveSourceReadToDlq(msgReq, "Teams msg " + msg.id() + ": " + e.getMessage(), messageBody.getBytes(StandardCharsets.UTF_8))) {
                unrecorded = true;
            }
        }
        for (TeamsFile file : msg.attachments()) {
            // Built BEFORE the download, so a download failure still has an item-naming row to
            // write. The URL travels with the row: the DLQ controller fetches the bytes again by it.
            // A file attachment without a content URL cannot be read: skipped, the message would
            // settle and the page pass a file nothing recorded (review, P1 on Slack) — so it is a
            // never-read failure, recorded.
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
            if (file.contentUrl() == null) {
                failed = true;
                FetchSupport.addError(errors, "Teams file " + file.id() + ": no content URL — the file cannot be read");
                nameTheParent(req, parentObjectId);
                if (!fetchSupport.saveSourceNeverReadToDlq(req, "Teams file " + file.id() + ": no content URL — the file cannot be read")) {
                    unrecorded = true;
                }
                continue;
            }
            InputStream content = null;
            try {
                try {
                    content = teams.downloadFile(file.contentUrl());
                } catch (Exception downloadFailed) {
                    failed = true;
                    FetchSupport.addError(errors, "Teams file " + file.id() + ": " + downloadFailed.getMessage());
                    nameTheParent(req, parentObjectId);
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
                    nameTheParent(req, parentObjectId);
                    if (!fetchSupport.saveSourceReadToDlq(req, "Teams " + file.id() + ": " + String.join(", ", result.errors()))) {
                        unrecorded = true;
                    }
                }
            } catch (Exception e) {
                failed = true;
                FetchSupport.addError(errors, "Teams file " + file.id() + ": " + e.getMessage());
                nameTheParent(req, parentObjectId);
                if (!fetchSupport.saveSourceReadToDlq(req, "Teams file " + file.id() + ": " + e.getMessage())) {
                    unrecorded = true;
                }
            } finally {
                if (content != null) try { content.close(); } catch (Exception ignored) {}
            }
        }
        if (unrecorded) return Outcome.FAILED_UNRECORDED;
        if (failed) return Outcome.FAILED_RECORDED;
        return counts.imported > importedBefore ? Outcome.IMPORTED : Outcome.SKIPPED;
    }

    /**
     * The message document the attachment belongs to, named on the dead-letter row so that a
     * replay links the attachment to it again: the DLQ controller creates the relationship from
     * this metadata, as the orchestrator does on the normal path. Without it a replayed
     * attachment stood alone for ever (review, P2).
     */
    private static void nameTheParent(ExternalIngestRequest req, String parentObjectId) {
        if (parentObjectId != null && req.getMetadata() != null) {
            req.getMetadata().put("parentObjectId", parentObjectId);
        }
    }
}
