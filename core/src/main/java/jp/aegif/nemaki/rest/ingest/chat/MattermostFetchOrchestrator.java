package jp.aegif.nemaki.rest.ingest.chat;

import jp.aegif.nemaki.rest.ingest.*;
import jp.aegif.nemaki.rest.ingest.chat.MattermostConnectorAdapter.MattermostFile;
import jp.aegif.nemaki.rest.ingest.chat.MattermostConnectorAdapter.MattermostPost;
import org.apache.chemistry.opencmis.commons.server.CallContext;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Mattermost: the channel is read down to the checkpoint and the OLDEST new posts are taken first.
 *
 * <p>Mattermost lists a channel's posts newest first. The previous shape asked for the first
 * {@code limit} posts — the {@code limit} NEWEST — skipped the ones at or below the checkpoint,
 * and then raised the checkpoint to the newest it had seen, so in a burst every older new post
 * fell below the checkpoint for ever (R107). Now the listing walks the pages down to the
 * checkpoint, the candidates are taken oldest first within a budget of settled posts, and the
 * checkpoint moves to the newest post that settled — a {@link WatermarkCheckpoint}, naming the
 * ids at its creation time (a millisecond, which two posts may share).
 *
 * <p>A post settles when its body and every attachment were imported or skipped by the import
 * service. A post that failed is dead-lettered — a file whose info or bytes could not be read as
 * a never-read row that the DLQ controller fetches again by the file id, a failure after the read
 * as a read row — and is not named; a newer post that settles passes it, the row being the
 * record. A failure whose row could not be written holds the checkpoint. A listing cut before the
 * checkpoint imports nothing: the posts it did not reach are the OLDER ones.
 *
 * <p>Not covered: edits (the creation time does not change), deleted posts (not listed), and
 * posts that appear later with an older creation time (imports) — a time watermark cannot see
 * them (the same limit as R109). Mattermost's newest-first order is read from the server's
 * source, not measured against a live server.
 */
public class MattermostFetchOrchestrator implements FetchOrchestrator {

    static final String PARAM_MAX_POST_REQUESTS = "mattermostPostMaxRequests";
    static final int MAX_POST_REQUESTS = 1_000_000;
    /** How many posts one run may TRY per unit of budget: bounds a run whose candidates keep failing. */
    static final int ATTEMPTS_PER_BUDGET = 4;
    /** A checkpoint written before this batch: the newest {@code create_at} seen, Unix milliseconds. */
    static final Pattern LEGACY_MILLIS = Pattern.compile("[0-9]{1,18}");

    private FetchSupport fetchSupport;
    private CheckpointManager checkpointManager;
    private CanonicalImportService canonicalImportService;
    /** The adapter, by endpoint and token; tests point it at a local stub of the Mattermost API. */
    java.util.function.BiFunction<String, String, MattermostConnectorAdapter> adapterFactory = MattermostConnectorAdapter::new;

    public void setFetchSupport(FetchSupport fs) { this.fetchSupport = fs; }
    public void setCheckpointManager(CheckpointManager cm) { this.checkpointManager = cm; }
    public void setCanonicalImportService(CanonicalImportService cis) { this.canonicalImportService = cis; }

    @Override public String sourceSystem() { return "mattermost"; }

    private static int intParam(Map<String, String> params, String name, int fallback, int minimum, int maximum) {
        String raw = params == null ? null : params.get(name);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int value = Integer.parseInt(raw.trim());
            if (value >= minimum && value <= maximum) return value;
        } catch (NumberFormatException notANumber) {
            // fall through
        }
        throw new IllegalArgumentException("Mattermost connector parameter " + name
                + " must be an integer between " + minimum + " and " + maximum + ", not '" + raw + "'");
    }

    private enum Outcome { SETTLED, FAILED_RECORDED, FAILED_UNRECORDED }

    private static final class Counts { int imported, skipped; }

    @Override
    public FetchResult execute(CallContext callContext, ImportProfileDefinition profile,
                               ConnectorDefinition connector, Map<String, String> params, int limit) {
        String channelId = params.getOrDefault("channelId", "");
        // resolvePasswordOrRefuse: a configuration read that FAILED used to arrive here as
        // "no token", which this method states as a fact. The scheduler counts that towards
        // opening the connector's circuit breaker and the folder endpoint turns it into
        // authError=true, prompting an admin to overwrite a credential that was never wrong.
        // The refusal is NOT caught here: the outer catch below rethrows it explicitly, so the
        // scheduler can tell a configuration outage from the connector failing.
        String token = fetchSupport.resolvePasswordOrRefuse(connector);
        if (token == null) return new FetchResult(0, 0, List.of("No token for Mattermost connector"));

        int maxRequests;
        try {
            maxRequests = intParam(params, PARAM_MAX_POST_REQUESTS, MattermostConnectorAdapter.DEFAULT_MAX_POST_REQUESTS, 1, MAX_POST_REQUESTS);
        } catch (IllegalArgumentException badParameter) {
            // Not a connector failure and not the default either: guessing the default would
            // silently ignore what the operator wrote. Reported, and nothing is read.
            return new FetchResult(0, 0, List.of(badParameter.getMessage()));
        }

        List<String> errors = new ArrayList<>();
        List<String> incompleteReads = new ArrayList<>();
        Counts counts = new Counts();
        int fetched = 0;
        String key = "mattermost." + channelId;
        try {
            // RC6.8 P2: re-validate the connector endpoint at runtime as defense-in-depth.
            // ConnectorDefinitionServiceImpl validates on save, but an endpoint that was saved
            // before this hardening landed, or modified at storage level, would otherwise reach
            // the adapter without revalidation. sendWithRetry inside the adapter will validate +
            // IP-pin again (RC6.8 P1), but failing early here gives a clearer audit message.
            jp.aegif.nemaki.rest.ingest.AdapterHttpClient.validateExternalUrl(connector.getEndpoint());
            var mm = adapterFactory.apply(connector.getEndpoint(), token);
            String stored = checkpointManager.loadSimpleCheckpoint(profile.getProfileId(), key);
            WatermarkCheckpoint checkpoint = readCheckpoint(stored);
            if (checkpoint == null) {
                // NOT "no checkpoint": read as none it would silently re-offer the whole channel,
                // and nobody would learn that the stored value is broken.
                FetchSupport.addError(errors, "Mattermost checkpoint '" + stored + "' for " + key
                        + " is not a creation time this connector can read; correct or clear it — nothing was read");
                return new FetchResult(0, 0, 0, errors);
            }
            // Down to the checkpoint (R107): the adapter stops at the first post created before
            // it — Mattermost lists newest first — and reports a cut when the cap comes first.
            MattermostConnectorAdapter.PostListing listing = mm.listSince(channelId, checkpoint.at(), maxRequests);
            fetched = listing.posts().size();
            Map<String, String> createdAt = new java.util.HashMap<>();
            List<MattermostPost> candidates = new ArrayList<>();
            for (MattermostPost post : listing.posts()) {
                String at = MattermostConnectorAdapter.creationTime(post);
                createdAt.put(post.id(), at);
                // The adapter stopped at the checkpoint; the id-level part (a post AT the
                // checkpoint's time already named) is applied here.
                if (checkpoint.covers(at, post.id())) {
                    counts.skipped++;
                    continue;
                }
                candidates.add(post);
            }
            // Oldest first, so that what a budget leaves for the next poll is always NEWER than
            // what it took and the checkpoint can move without passing it.
            candidates.sort(java.util.Comparator.comparing((MattermostPost p) -> createdAt.get(p.id()))
                    .thenComparing(MattermostPost::id));

            List<MattermostPost> candidatesThisRun;
            if (!listing.complete()) {
                // NOT an error — nothing failed. Recorded so the job says PARTIAL — and nothing is
                // imported: the posts this poll was not shown are the OLDER ones, so a checkpoint
                // raised over the ones it was shown would exclude them for ever.
                incompleteReads.add("Mattermost channel " + channelId + ": " + listing.truncatedBecause()
                        + " — nothing was imported and the checkpoint holds, because the posts "
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
            for (MattermostPost post : candidatesThisRun) {
                if (settled.size() >= limit) {
                    break;
                }
                if (attempted >= limit * ATTEMPTS_PER_BUDGET) {
                    attemptsExhausted = true;
                    break;
                }
                attempted++;
                fetchSupport.throttle(throttleMs);
                switch (attempt(callContext, profile, connector, mm, channelId, post, errors, counts)) {
                    case SETTLED -> settled.add(new WatermarkCheckpoint.Mark(createdAt.get(post.id()), post.id()));
                    case FAILED_RECORDED -> { }
                    case FAILED_UNRECORDED -> unrecordedFailures++;
                }
            }
            int leftForTheNextPoll = candidatesThisRun.size() - attempted;
            if (leftForTheNextPoll > 0) {
                incompleteReads.add(attemptsExhausted
                        ? "Mattermost channel " + channelId + ": " + attempted + " post(s) were attempted ("
                                + ATTEMPTS_PER_BUDGET + " × the limit of " + limit + ") with only "
                                + settled.size() + " settled, leaving " + leftForTheNextPoll
                                + " newer post(s) untried — the failures are in the dead-letter queue; "
                                + "the posts behind them are reached only once they settle or the "
                                + "checkpoint passes them, or with a higher limit"
                        : "Mattermost channel " + channelId + ": the run's limit of " + limit
                                + " post(s) was reached with " + leftForTheNextPoll
                                + " newer post(s) left for the next poll");
            }
            if (unrecordedFailures > 0) {
                FetchSupport.addError(errors, unrecordedFailures + " Mattermost failure(s) could not be dead-lettered; "
                        + "the checkpoint holds so that they are offered again");
            }
            // Saved only when the POSITION moved: a legacy checkpoint (Unix milliseconds) encodes
            // differently after normalisation without having moved.
            String before = checkpoint.encode();
            String next = unrecordedFailures > 0 ? null : checkpoint.after(settled).encode();
            if (next != null && !next.equals(before)) {
                checkpointManager.saveSimpleCheckpoint(profile.getProfileId(), key, next);
            }
        } catch (jp.aegif.nemaki.rest.controller.IntegrationSettingsService
                .SettingUnreadableException couldNotAsk) {
            // NOT the connector's failure. The checkpoint read refuses from INSIDE this try,
            // so swallowing it here turned a configuration-store outage into
            // "<connector> connection failed" — an error the scheduler counts towards opening
            // that connector's circuit breaker.
            throw couldNotAsk;
        } catch (Exception e) {
            FetchSupport.addError(errors, "Mattermost connection failed: " + e.getMessage());
        }
        return new FetchResult(fetched, counts.imported, counts.skipped, errors, List.copyOf(incompleteReads));
    }

    /**
     * The stored checkpoint in canonical form: a value written before this batch is the newest
     * {@code create_at} seen (Unix milliseconds, no ids — its posts are offered once more, the
     * import service's dedupe answering for them); a value written by this batch is
     * {@code <canonical time>|<id>,…}. Null when the value can be read as neither.
     */
    static WatermarkCheckpoint readCheckpoint(String stored) {
        if (stored == null || stored.isBlank()) return WatermarkCheckpoint.NONE;
        String value = stored.trim();
        if (LEGACY_MILLIS.matcher(value).matches()) {
            try {
                return new WatermarkCheckpoint(WatermarkCheckpoint.canonical(java.time.Instant.ofEpochMilli(Long.parseLong(value))),
                        java.util.Set.of());
            } catch (IllegalStateException | java.time.DateTimeException | NumberFormatException unreadable) {
                return null;
            }
        }
        WatermarkCheckpoint parsed = WatermarkCheckpoint.parse(value);
        String at = WatermarkCheckpoint.canonical(parsed.at());
        return at == null ? null : new WatermarkCheckpoint(at, parsed.idsAt());
    }

    /**
     * One post: its body and every attachment. SETTLED when all of them were imported or skipped;
     * otherwise the failures are dead-lettered — a file whose info or bytes could not be read as a
     * never-read row (the DLQ controller fetches the bytes again by the file id the row names), a
     * failure after the read as a read row — and RECORDED / UNRECORDED says whether every row was
     * written.
     */
    private Outcome attempt(CallContext callContext, ImportProfileDefinition profile, ConnectorDefinition connector,
                            MattermostConnectorAdapter mm, String channelId, MattermostPost post,
                            List<String> errors, Counts counts) {
        boolean failed = false, unrecorded = false;
        String postText = post.message() != null ? post.message() : "";
        String parentObjectId = null;
        ExternalIngestRequest msgReq = new ExternalIngestRequest();
        msgReq.setProfileId(profile.getProfileId());
        msgReq.setConnectorId(connector.getConnectorId());
        msgReq.setRepositoryId(profile.getRepositoryId());
        msgReq.setSourceObjectId(post.id());
        msgReq.setSourceObjectType("chat_message");
        msgReq.setFileName("mm-" + post.id() + ".txt");
        msgReq.setMimeType("text/plain");
        msgReq.setContentStream(new ByteArrayInputStream(postText.getBytes(StandardCharsets.UTF_8)));
        msgReq.setExecutionMode("scheduled");
        Map<String, Object> msgMeta = new LinkedHashMap<>();
        msgMeta.put("channelId", channelId);
        msgMeta.put("messageId", post.id());
        msgMeta.put("senderId", post.userId());
        msgMeta.put("messageText", FetchSupport.truncateForContext(postText));
        msgMeta.put("workspaceId", connector.getTenantId());
        if (post.rootId() != null && !post.rootId().isEmpty()) msgMeta.put("threadId", post.rootId());
        msgReq.setMetadata(msgMeta);
        try {
            ExternalIngestResult result = canonicalImportService.executeChatContextImport(callContext, msgReq);
            // skipped() first: a skipped result also reports isSuccess()==true (no errors).
            if (result.skipped()) {
                counts.skipped++;
                parentObjectId = result.objectId();
            } else if (result.isSuccess()) {
                counts.imported++;
                parentObjectId = result.objectId();
            } else {
                failed = true;
                FetchSupport.addError(errors, "MM msg " + post.id() + ": " + String.join(", ", result.errors()));
                if (!fetchSupport.saveSourceReadToDlq(msgReq, "MM msg " + post.id() + ": " + String.join(", ", result.errors()))) {
                    unrecorded = true;
                }
            }
        } catch (Exception e) {
            failed = true;
            FetchSupport.addError(errors, "MM msg " + post.id() + ": " + e.getMessage());
            if (!fetchSupport.saveSourceReadToDlq(msgReq, "MM msg " + post.id() + ": " + e.getMessage())) {
                unrecorded = true;
            }
        }
        for (String fileId : post.fileIds()) {
            // Built BEFORE the info call and the download, so a failure of either — the item
            // never read — still has an item-naming row to write. The file id the row names is
            // what the DLQ controller fetches the bytes again by; the name is the id until the
            // info call answers.
            ExternalIngestRequest req = new ExternalIngestRequest();
            req.setProfileId(profile.getProfileId());
            req.setConnectorId(connector.getConnectorId());
            req.setRepositoryId(profile.getRepositoryId());
            req.setSourceObjectId(fileId);
            req.setSourceObjectType("attachment");
            req.setFileName(fileId);
            req.setExecutionMode("scheduled");
            Map<String, Object> fileMeta = new LinkedHashMap<>();
            fileMeta.put("channelId", channelId);
            fileMeta.put("messageId", post.id());
            fileMeta.put("senderId", post.userId());
            fileMeta.put("messageText", FetchSupport.truncateForContext(postText));
            fileMeta.put("workspaceId", connector.getTenantId());
            if (post.rootId() != null && !post.rootId().isEmpty()) fileMeta.put("threadId", post.rootId());
            req.setMetadata(fileMeta);
            InputStream content = null;
            try {
                try {
                    MattermostFile fileInfo = mm.getFileInfo(fileId);
                    req.setFileName(fileInfo.name());
                    req.setMimeType(fileInfo.mimeType());
                    content = mm.downloadFile(fileId);
                } catch (Exception notRead) {
                    // The source item was never read: a never-read row, fetched again by file
                    // id on replay — not imported as a content-less document.
                    failed = true;
                    FetchSupport.addError(errors, "MM file " + fileId + ": " + notRead.getMessage());
                    if (!fetchSupport.saveSourceNeverReadToDlq(req, "MM file " + fileId + ": " + notRead.getMessage())) {
                        unrecorded = true;
                    }
                    continue;
                }
                req.setContentStream(content);
                ExternalIngestResult result = canonicalImportService.executeChatContextImport(callContext, req);
                if (result.skipped() || result.isSuccess()) {
                    if (result.skipped()) counts.skipped++; else counts.imported++;
                    // Relationship creation is idempotent, so a skipped attachment is still
                    // (re)linked to its post — this repairs a missing edge without duplicating.
                    if (parentObjectId != null && result.objectId() != null) {
                        fetchSupport.createRelationshipSafe(callContext, profile.getRepositoryId(),
                                parentObjectId, result.objectId(), profile, req, errors);
                    }
                } else {
                    failed = true;
                    FetchSupport.addError(errors, "MM " + fileId + ": " + String.join(", ", result.errors()));
                    if (!fetchSupport.saveSourceReadToDlq(req, "MM " + fileId + ": " + String.join(", ", result.errors()))) {
                        unrecorded = true;
                    }
                }
            } catch (Exception e) {
                // The download succeeded and the import threw: the item WAS read.
                failed = true;
                FetchSupport.addError(errors, "MM file " + fileId + ": " + e.getMessage());
                if (!fetchSupport.saveSourceReadToDlq(req, "MM file " + fileId + ": " + e.getMessage())) {
                    unrecorded = true;
                }
            } finally {
                if (content != null) try { content.close(); } catch (Exception ignored) {}
            }
        }
        return unrecorded ? Outcome.FAILED_UNRECORDED : failed ? Outcome.FAILED_RECORDED : Outcome.SETTLED;
    }
}
