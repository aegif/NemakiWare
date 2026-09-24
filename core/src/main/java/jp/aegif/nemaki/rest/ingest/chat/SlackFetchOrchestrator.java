package jp.aegif.nemaki.rest.ingest.chat;

import jp.aegif.nemaki.rest.ingest.*;
import jp.aegif.nemaki.rest.ingest.chat.SlackConnectorAdapter.SlackFile;
import jp.aegif.nemaki.rest.ingest.chat.SlackConnectorAdapter.SlackMessage;
import org.apache.chemistry.opencmis.commons.server.CallContext;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Slack: the channel is read to the checkpoint and the OLDEST new messages are taken first.
 *
 * <p>Slack lists newest first. The previous shape asked for the first {@code limit} messages
 * since the checkpoint — the {@code limit} NEWEST — and then raised the checkpoint to the newest
 * it had seen, so in a burst every older new message fell below the checkpoint for ever (R107).
 * Now the listing runs to the end of what is newer than the checkpoint ({@code oldest} is
 * exclusive, as Slack defines it), the candidates are taken oldest first within a budget of
 * settled messages, and the checkpoint moves to the newest message that settled.
 *
 * <p>A message settles when its body (under {@code files_and_body}) and every attachment were
 * imported or skipped by the import service. A message that failed is dead-lettered — a
 * download failure as a never-read row that the DLQ controller fetches again by the file's
 * URL, a failure after the read as a read row — and is not named; a newer message that settles
 * passes it, the row being the record. A failure whose row could not be written holds the
 * checkpoint. A listing cut at the request cap imports nothing: the messages it did not reach
 * are the OLDER ones, and a checkpoint raised over the ones it did reach would exclude them.
 *
 * <p>Not covered: edits (Slack keeps the original {@code ts}, so an edited message is not
 * offered again), thread replies (not listed by conversations.history), and messages that
 * appear later with an older {@code ts} (imports, Slack Connect backfill) — a time watermark
 * cannot see them (the same limit as R109).
 */
public class SlackFetchOrchestrator implements FetchOrchestrator {

    static final String PARAM_MAX_HISTORY_REQUESTS = "slackHistoryMaxRequests";
    static final int MAX_HISTORY_REQUESTS = 1_000_000;
    /** How many messages one run may TRY per unit of budget: bounds a run whose candidates keep failing. */
    static final int ATTEMPTS_PER_BUDGET = 4;
    /** A Slack message timestamp: seconds, a dot, a fraction. The checkpoint is one of these. */
    static final Pattern TS = Pattern.compile("[0-9]+\\.[0-9]+");

    private FetchSupport fetchSupport;
    private CheckpointManager checkpointManager;
    private CanonicalImportService canonicalImportService;
    /** The adapter, by token; tests point it at a local stub of the Slack API. */
    java.util.function.Function<String, SlackConnectorAdapter> adapterFactory = SlackConnectorAdapter::new;

    public void setFetchSupport(FetchSupport fs) { this.fetchSupport = fs; }
    public void setCheckpointManager(CheckpointManager cm) { this.checkpointManager = cm; }
    public void setCanonicalImportService(CanonicalImportService cis) { this.canonicalImportService = cis; }

    @Override public String sourceSystem() { return "slack"; }

    private static int intParam(Map<String, String> params, String name, int fallback, int minimum, int maximum) {
        String raw = params == null ? null : params.get(name);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int value = Integer.parseInt(raw.trim());
            if (value >= minimum && value <= maximum) return value;
        } catch (NumberFormatException notANumber) {
            // fall through
        }
        throw new IllegalArgumentException("Slack connector parameter " + name
                + " must be an integer between " + minimum + " and " + maximum + ", not '" + raw + "'");
    }

    private enum Outcome { SETTLED, FAILED_RECORDED, FAILED_UNRECORDED }

    /** The per-item counts one attempt adds to. */
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
        if (token == null) return new FetchResult(0, 0, List.of("No token for Slack connector"));

        // files_only (default): import only attached files, keep the message text as
        // metadata. files_and_body: also import the message text as a .txt document.
        boolean importBody = "files_and_body".equals(profile.getImportPolicy());

        int maxRequests;
        try {
            maxRequests = intParam(params, PARAM_MAX_HISTORY_REQUESTS, SlackConnectorAdapter.DEFAULT_MAX_HISTORY_REQUESTS, 1, MAX_HISTORY_REQUESTS);
        } catch (IllegalArgumentException badParameter) {
            // Not a connector failure and not the default either: guessing the default would
            // silently ignore what the operator wrote. Reported, and nothing is read.
            return new FetchResult(0, 0, List.of(badParameter.getMessage()));
        }

        List<String> errors = new ArrayList<>();
        List<String> incompleteReads = new ArrayList<>();
        Counts counts = new Counts();
        int fetched = 0;
        try {
            var slack = adapterFactory.apply(token);
            String stored = checkpointManager.loadSimpleCheckpoint(profile.getProfileId(), "slack." + channelId);
            String checkpoint = stored == null || stored.isBlank() ? null : stored.trim();
            // A stored checkpoint that is not a Slack timestamp is NOT "no checkpoint": read as
            // none it would silently re-offer the whole channel, and nobody would learn that
            // the stored value is broken.
            if (checkpoint != null && !TS.matcher(checkpoint).matches()) {
                FetchSupport.addError(errors, "Slack checkpoint '" + stored + "' for channel " + channelId
                        + " is not a message timestamp this connector can read; correct or clear it — nothing was read");
                return new FetchResult(0, 0, 0, errors);
            }
            // EVERYTHING newer than the checkpoint (R107): Slack lists newest first, and a
            // listing stopped at the per-run limit was the newest N, so every older new message
            // was passed by the checkpoint raised from them.
            SlackConnectorAdapter.HistoryListing listing = slack.listSince(channelId, checkpoint, maxRequests);
            fetched = listing.messages().size();
            // Every message must carry a timestamp this connector can place: one without cannot
            // be skipped or named, and Slack writes ts for every message — a page without one is
            // malformed, and the listing is refused rather than read around it.
            for (SlackMessage msg : listing.messages()) {
                if (msg.ts() == null || !TS.matcher(msg.ts()).matches()) {
                    FetchSupport.addError(errors, "Slack listed a message in channel " + channelId
                            + " without a timestamp this connector can read ('" + msg.ts() + "'); nothing was imported and the checkpoint holds");
                    return new FetchResult(fetched, 0, 0, errors);
                }
            }
            // Slack's `oldest` is exclusive, so the listing should hold only newer messages; the
            // checkpoint is still applied here — an API that ignored the parameter would otherwise
            // re-import the whole channel, and a checkpoint must never move backwards.
            BigDecimal floor = checkpoint == null ? null : new BigDecimal(checkpoint);
            List<SlackMessage> candidates = new ArrayList<>();
            for (SlackMessage msg : listing.messages()) {
                if (floor != null && new BigDecimal(msg.ts()).compareTo(floor) <= 0) {
                    counts.skipped++;
                    continue;
                }
                candidates.add(msg);
            }
            // Oldest first, so that what a budget leaves for the next poll is always NEWER than
            // what it took and the checkpoint can move without passing it.
            candidates.sort(java.util.Comparator.comparing((SlackMessage m) -> new BigDecimal(m.ts())));

            List<SlackMessage> candidatesThisRun;
            if (!listing.complete()) {
                // NOT an error — nothing failed, and an error on every poll would open the
                // connector's breaker. Recorded so the job says PARTIAL — and nothing is
                // imported: the messages this poll was not shown are the OLDER ones, so a
                // checkpoint raised over the ones it was shown would exclude them for ever.
                incompleteReads.add("Slack channel " + channelId + ": " + listing.truncatedBecause()
                        + " — nothing was imported and the checkpoint holds, because the messages "
                        + "left out are the older ones");
                candidatesThisRun = List.of();
            } else {
                candidatesThisRun = candidates;
            }
            long throttleMs = FetchSupport.calculateThrottleDelayMs(connector);

            // The messages that SETTLED — body and every attachment imported or skipped. A
            // message that failed is dead-lettered inside attempt(), does not hold the checkpoint
            // back, and is not named by it either. The budget counts settled messages; attempts
            // are bounded at ATTEMPTS_PER_BUDGET × limit.
            List<String> settled = new ArrayList<>();
            int attempted = 0;
            boolean attemptsExhausted = false;
            int unrecordedFailures = 0;
            for (SlackMessage msg : candidatesThisRun) {
                if (settled.size() >= limit) {
                    break;
                }
                if (attempted >= limit * ATTEMPTS_PER_BUDGET) {
                    attemptsExhausted = true;
                    break;
                }
                attempted++;
                fetchSupport.throttle(throttleMs);
                switch (attempt(callContext, profile, connector, slack, channelId, msg, importBody, errors, counts)) {
                    case SETTLED -> settled.add(msg.ts());
                    case FAILED_RECORDED -> { }
                    case FAILED_UNRECORDED -> unrecordedFailures++;
                }
            }
            int leftForTheNextPoll = candidatesThisRun.size() - attempted;
            if (leftForTheNextPoll > 0) {
                incompleteReads.add(attemptsExhausted
                        ? "Slack channel " + channelId + ": " + attempted + " message(s) were attempted ("
                                + ATTEMPTS_PER_BUDGET + " × the limit of " + limit + ") with only "
                                + settled.size() + " settled, leaving " + leftForTheNextPoll
                                + " newer message(s) untried — the failures are in the dead-letter queue; "
                                + "the messages behind them are reached only once they settle or the "
                                + "checkpoint passes them, or with a higher limit"
                        : "Slack channel " + channelId + ": the run's limit of " + limit
                                + " message(s) was reached with " + leftForTheNextPoll
                                + " newer message(s) left for the next poll");
            }
            // A failure whose dead-letter row could not be written is a message nothing records.
            // Moving the checkpoint past it would lose it silently, so the checkpoint holds for
            // this run and the run says why.
            if (unrecordedFailures > 0) {
                FetchSupport.addError(errors, unrecordedFailures + " Slack failure(s) could not be dead-lettered; "
                        + "the checkpoint holds so that they are offered again");
            }
            // HOW FAR the checkpoint may move: to the newest timestamp a settled message carried,
            // never backwards. Slack timestamps are unique per channel, so naming the ids at the
            // timestamp (R59) is not needed here.
            BigDecimal newest = floor;
            String next = null;
            for (String ts : settled) {
                BigDecimal value = new BigDecimal(ts);
                if (newest == null || value.compareTo(newest) > 0) {
                    newest = value;
                    next = ts;
                }
            }
            if (unrecordedFailures == 0 && next != null) {
                checkpointManager.saveSimpleCheckpoint(profile.getProfileId(), "slack." + channelId, next);
            }
        } catch (jp.aegif.nemaki.rest.controller.IntegrationSettingsService
                .SettingUnreadableException couldNotAsk) {
            // NOT the connector's failure. The checkpoint read refuses from INSIDE this try,
            // so swallowing it here turned a configuration-store outage into
            // "<connector> connection failed" — an error the scheduler counts towards opening
            // that connector's circuit breaker.
            throw couldNotAsk;
        } catch (Exception e) {
            FetchSupport.addError(errors, "Slack connection failed: " + e.getMessage());
        }
        return new FetchResult(fetched, counts.imported, counts.skipped, errors, List.copyOf(incompleteReads));
    }

    /**
     * One message: its body (under files_and_body) and every attachment. SETTLED when all of
     * them were imported or skipped; otherwise the failures are dead-lettered — a download
     * failure as a never-read row (the DLQ controller fetches the bytes again by the URL the
     * row carries), a failure after the read as a read row — and RECORDED / UNRECORDED says
     * whether every row was written.
     */
    private Outcome attempt(CallContext callContext, ImportProfileDefinition profile, ConnectorDefinition connector,
                            SlackConnectorAdapter slack, String channelId, SlackMessage msg, boolean importBody,
                            List<String> errors, Counts counts) {
        boolean failed = false, unrecorded = false;
        String messageText = msg.text() != null ? msg.text() : "";
        String parentObjectId = null;
        if (importBody) {
            ExternalIngestRequest msgReq = new ExternalIngestRequest();
            msgReq.setProfileId(profile.getProfileId());
            msgReq.setConnectorId(connector.getConnectorId());
            msgReq.setRepositoryId(profile.getRepositoryId());
            msgReq.setSourceObjectId(msg.ts());
            msgReq.setSourceObjectType("chat_message");
            msgReq.setFileName("slack-" + msg.ts().replace(".", "-") + ".txt");
            msgReq.setMimeType("text/plain");
            msgReq.setContentStream(new ByteArrayInputStream(messageText.getBytes(StandardCharsets.UTF_8)));
            msgReq.setExecutionMode("scheduled");
            Map<String, Object> msgMeta = new LinkedHashMap<>();
            msgMeta.put("channelId", channelId);
            msgMeta.put("threadId", msg.threadTs());
            msgMeta.put("messageId", msg.ts());
            msgMeta.put("senderId", msg.userId());
            msgMeta.put("messageText", FetchSupport.truncateForContext(messageText));
            msgMeta.put("workspaceId", connector.getTenantId());
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
                    FetchSupport.addError(errors, "Slack msg " + msg.ts() + ": " + String.join(", ", result.errors()));
                    if (!fetchSupport.saveSourceReadToDlq(msgReq, "Slack msg " + msg.ts() + ": " + String.join(", ", result.errors()))) {
                        unrecorded = true;
                    }
                }
            } catch (Exception e) {
                failed = true;
                FetchSupport.addError(errors, "Slack msg " + msg.ts() + ": " + e.getMessage());
                if (!fetchSupport.saveSourceReadToDlq(msgReq, "Slack msg " + msg.ts() + ": " + e.getMessage())) {
                    unrecorded = true;
                }
            }
        }
        for (SlackFile file : msg.files()) {
            // A file Slack only links to (external) has no download URL by design and is not an
            // attachment to import. Any OTHER file without a URL cannot be read: skipped, the
            // message would settle and the checkpoint pass a file nothing recorded (review, P1)
            // — so it is a never-read failure, recorded (and not replayable: there is no URL).
            if (file.external()) continue;
            // Built BEFORE the download, so a download failure — the likeliest per-file
            // failure — still has an item-naming row to write. The URL travels with the row:
            // it is what the DLQ controller fetches the bytes again by.
            ExternalIngestRequest fileReq = new ExternalIngestRequest();
            fileReq.setProfileId(profile.getProfileId());
            fileReq.setConnectorId(connector.getConnectorId());
            fileReq.setRepositoryId(profile.getRepositoryId());
            fileReq.setSourceObjectId(file.id());
            fileReq.setSourceObjectType("attachment");
            fileReq.setFileName(file.name());
            fileReq.setMimeType(file.mimeType());
            fileReq.setExecutionMode("scheduled");
            Map<String, Object> fileMeta = new LinkedHashMap<>();
            fileMeta.put("channelId", channelId);
            fileMeta.put("threadId", msg.threadTs());
            fileMeta.put("messageId", msg.ts());
            fileMeta.put("senderId", msg.userId());
            fileMeta.put("messageText", FetchSupport.truncateForContext(messageText));
            fileMeta.put("workspaceId", connector.getTenantId());
            fileMeta.put("slackFileUrl", file.urlPrivateDownload());
            fileReq.setMetadata(fileMeta);
            if (file.urlPrivateDownload() == null) {
                failed = true;
                FetchSupport.addError(errors, "Slack file " + file.id() + ": no download URL — the file cannot be read");
                if (!fetchSupport.saveSourceNeverReadToDlq(fileReq, "Slack file " + file.id() + ": no download URL — the file cannot be read")) {
                    unrecorded = true;
                }
                continue;
            }

            InputStream content = null;
            try {
                try {
                    content = slack.downloadFile(file.urlPrivateDownload());
                } catch (Exception downloadFailed) {
                    // The source item was never read: a never-read row, fetched again by URL
                    // on replay — not imported as a content-less document.
                    failed = true;
                    FetchSupport.addError(errors, "Slack file " + file.id() + ": " + downloadFailed.getMessage());
                    if (!fetchSupport.saveSourceNeverReadToDlq(fileReq, "Slack file " + file.id() + ": " + downloadFailed.getMessage())) {
                        unrecorded = true;
                    }
                    continue;
                }
                fileReq.setContentStream(content);
                ExternalIngestResult result = canonicalImportService.executeChatContextImport(callContext, fileReq);
                if (result.skipped() || result.isSuccess()) {
                    if (result.skipped()) counts.skipped++; else counts.imported++;
                    // Relationship creation is idempotent, so a skipped attachment is still
                    // (re)linked to its message — this repairs a missing edge without duplicating.
                    if (parentObjectId != null && result.objectId() != null) {
                        fetchSupport.createRelationshipSafe(callContext, profile.getRepositoryId(),
                                parentObjectId, result.objectId(), profile, fileReq, errors);
                    }
                } else {
                    failed = true;
                    FetchSupport.addError(errors, "Slack file " + file.id() + ": " + String.join(", ", result.errors()));
                    if (!fetchSupport.saveSourceReadToDlq(fileReq, "Slack file " + file.id() + ": " + String.join(", ", result.errors()))) {
                        unrecorded = true;
                    }
                }
            } catch (Exception e) {
                // The download succeeded and the import threw: the item WAS read.
                failed = true;
                FetchSupport.addError(errors, "Slack file " + file.id() + ": " + e.getMessage());
                if (!fetchSupport.saveSourceReadToDlq(fileReq, "Slack file " + file.id() + ": " + e.getMessage())) {
                    unrecorded = true;
                }
            } finally {
                if (content != null) try { content.close(); } catch (Exception ignored) {}
            }
        }
        return unrecorded ? Outcome.FAILED_UNRECORDED : failed ? Outcome.FAILED_RECORDED : Outcome.SETTLED;
    }
}
