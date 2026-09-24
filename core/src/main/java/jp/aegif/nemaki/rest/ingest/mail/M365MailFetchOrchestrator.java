package jp.aegif.nemaki.rest.ingest.mail;

import jp.aegif.nemaki.rest.ingest.*;
import jp.aegif.nemaki.rest.ingest.mail.M365MailConnectorAdapter.M365MessageSummary;
import org.apache.chemistry.opencmis.commons.server.CallContext;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Microsoft 365 mail: the folder is followed through Graph's mail delta feed, page by page.
 *
 * <p>The previous shape listed the folder by received time, NEWEST first, cut the listing at the
 * run's {@code limit} and raised the checkpoint to the newest received time it had seen — so in a
 * burst every older new message fell below the checkpoint for ever (R107). Reading the listing
 * oldest first would close that, but not the other gap of a received-time watermark: a message
 * MOVED into the folder (by a rule, or filed by hand) keeps its received time, and once the
 * checkpoint has passed that time no listing by received time offers it again. The folder's delta
 * feed ({@code /mailFolders/{id}/messages/delta}) is Graph's own change tracking and carries a
 * message moved in like one received: it is followed instead, and the checkpoint is the link to
 * continue from, with the id Graph answers for the folder it was written for
 * ({@code delta:<folder id>|<link>}).
 *
 * <p>Where the feed starts: with no checkpoint, the whole folder — and with the received-time
 * checkpoint an earlier version wrote, the whole folder too: Graph caps a delta query with
 * {@code $filter} at 5,000 messages and ends the cut round like a complete one (review, P1), so the
 * folder is read again and the import service's dedupe answers for what was imported. A checkpoint
 * written for another folder — the token now belongs to someone else, or the profile's
 * {@code userId} or {@code folderId} names another mailbox or folder — is an error, not a place to
 * continue from: its link reads that other folder. The folder's id is asked for on every run; the
 * same mailbox named by its UPN or by its object id answers the same folder.
 *
 * <p>A page is passed — the next link saved — only when every message on it was imported, skipped
 * by the import service, or dead-lettered. The run's budget ({@code limit} messages; a skip spends
 * none, a message that imported part of itself spends it — but not a skipped message whose missing
 * attachments the import service imported on this try, which it does not report) stops INSIDE a
 * page without passing it,
 * and the next poll reads the same link again. A failure whose row could not be written holds the
 * page. At most {@code limit × 4} messages are tried in a run, counted between pages. A run cut at
 * the request cap saves the link it reached, and the next poll continues from it.
 *
 * <p>A message whose MIME could not be fetched is dead-lettered as never read; one the import
 * refused, threw on, or imported without some of its attachments — on this try or, already
 * imported, again — is dead-lettered as read. Not
 * covered: messages older than the checkpoint an earlier version passed in a burst (this version
 * starts from that checkpoint; clearing it reads the folder from the start), messages edited after
 * their import (the default dedupe skips them), subfolders. Graph's delta feed is measured against a
 * stub, not against Graph.
 */
public class M365MailFetchOrchestrator implements FetchOrchestrator {

    static final String PARAM_MAX_MESSAGE_REQUESTS = "m365MessageMaxRequests";
    static final int MAX_MESSAGE_REQUESTS = 1_000_000;
    /** How many messages one run may TRY per unit of budget: bounds a run whose messages keep failing. */
    static final int ATTEMPTS_PER_BUDGET = 4;
    /** A stored checkpoint that is a link into Graph's mail delta feed. */
    static final String DELTA_PREFIX = "delta:";

    private FetchSupport fetchSupport;
    private CheckpointManager checkpointManager;
    private CanonicalImportService canonicalImportService;
    /** The adapter, by token and mailbox user id; tests point it at a local stub of Graph. */
    java.util.function.BiFunction<String, String, M365MailConnectorAdapter> adapterFactory = M365MailConnectorAdapter::new;

    public void setFetchSupport(FetchSupport fs) { this.fetchSupport = fs; }
    public void setCheckpointManager(CheckpointManager cm) { this.checkpointManager = cm; }
    public void setCanonicalImportService(CanonicalImportService cis) { this.canonicalImportService = cis; }

    @Override public String sourceSystem() { return "m365_mail"; }

    private static int intParam(Map<String, String> params, String name, int fallback, int minimum, int maximum) {
        String raw = params == null ? null : params.get(name);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int value = Integer.parseInt(raw.trim());
            if (value >= minimum && value <= maximum) return value;
        } catch (NumberFormatException notANumber) {
            // fall through
        }
        throw new IllegalArgumentException("M365 Mail connector parameter " + name
                + " must be an integer between " + minimum + " and " + maximum + ", not '" + raw + "'");
    }

    /** IMPORTED spends the budget; SKIPPED (already imported) does not; both let the page pass. */
    private enum Outcome { IMPORTED, SKIPPED, FAILED_RECORDED, FAILED_UNRECORDED }

    private static final class Counts { int imported, skipped; }

    @Override
    public FetchResult execute(CallContext callContext, ImportProfileDefinition profile,
                               ConnectorDefinition connector, Map<String, String> params, int limit) {
        String folderId = params.getOrDefault("folderId", "inbox");

        // resolvePasswordOrRefuse: a configuration read that FAILED used to arrive here as
        // "no token", which this method states as a fact. The refusal is NOT caught here: the
        // outer catch below rethrows it explicitly, so the scheduler can tell a configuration
        // outage from the connector failing.
        String token = fetchSupport.resolvePasswordOrRefuse(connector);
        if (token == null) return new FetchResult(0, 0, List.of("No access token for M365 Mail connector"));

        int maxRequests;
        try {
            maxRequests = intParam(params, PARAM_MAX_MESSAGE_REQUESTS, M365MailConnectorAdapter.DEFAULT_MAX_MESSAGE_REQUESTS, 1, MAX_MESSAGE_REQUESTS);
        } catch (IllegalArgumentException badParameter) {
            // Not a connector failure and not the default either: guessing the default would
            // silently ignore what the operator wrote. Reported, and nothing is read.
            return new FetchResult(0, 0, List.of(badParameter.getMessage()));
        }

        List<String> errors = new ArrayList<>();
        List<String> incompleteReads = new ArrayList<>();
        Counts counts = new Counts();
        int fetched = 0;
        // The key the previous version wrote under: a received-time checkpoint stored there is
        // where this version starts from.
        String key = "m365mail." + folderId;
        String start = null;
        String reached = null;
        String folder = null;
        try {
            Map<String, String> sp = profile.getSchedulerParams();
            String userId = (sp != null) ? sp.get("userId") : null;
            var m365 = adapterFactory.apply(token, userId);
            String mailbox = m365.mailbox();
            // The folder this run reads, as Graph identifies it. Not readable: nothing is read — the
            // stored link could not be checked against it.
            folder = m365.folderIdentity(folderId);
            String stored = checkpointManager.loadSimpleCheckpoint(profile.getProfileId(), key);
            String value = stored == null ? "" : stored.trim();
            if (value.startsWith(DELTA_PREFIX)) {
                String rest = value.substring(DELTA_PREFIX.length());
                int bar = rest.indexOf('|');
                String storedFolder = bar < 0 ? null : rest.substring(0, bar);
                String link = bar < 0 ? null : rest.substring(bar + 1);
                if (storedFolder == null || storedFolder.isBlank() || !m365.isMailDeltaLink(link)) {
                    // A checkpoint is not a place to put a URL: one that is not a mail delta link
                    // on this endpoint, with the folder it was written for, is not followed.
                    FetchSupport.addError(errors, "M365 Mail checkpoint for " + key + " is not a delta link this connector wrote ('"
                            + value + "'); correct or clear it — nothing was read");
                    return new FetchResult(0, 0, 0, errors);
                }
                if (!storedFolder.equals(folder)) {
                    // Written for another folder: its link reads THAT folder. Graph's ids are
                    // case-sensitive, so they are compared exactly.
                    FetchSupport.addError(errors, "M365 Mail checkpoint for " + key + " was written for the folder '" + storedFolder
                            + "', and this profile's folder is '" + folder + "' (the token's user, the userId or the folderId "
                            + "changed); clear it to read this folder — nothing was read");
                    return new FetchResult(0, 0, 0, errors);
                }
                start = link;
            } else if (value.isEmpty()) {
                start = m365.initialDeltaLink(folderId);
            } else {
                // The received-time checkpoint an earlier version wrote (Graph's own string): the
                // whole folder is read again (see the class comment). One that cannot be read is NOT
                // "no checkpoint": it is reported rather than silently replaced.
                String at = WatermarkCheckpoint.canonical(value);
                if (at == null) {
                    FetchSupport.addError(errors, "M365 Mail checkpoint '" + stored + "' for " + key
                            + " is neither a delta link nor a timestamp this connector can read; correct or clear it — nothing was read");
                    return new FetchResult(0, 0, 0, errors);
                }
                start = m365.initialDeltaLink(folderId);
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
                M365MailConnectorAdapter.DeltaPage page = m365.delta(reached);
                pages++;
                fetched += page.messages().size();
                counts.skipped += page.removed();
                // The page is checked whole before anything on it is imported: a message that
                // cannot be named, or a link out of the mail delta feed, holds the page.
                for (M365MessageSummary msg : page.messages()) {
                    if (msg.id() == null || msg.id().isBlank()) {
                        FetchSupport.addError(errors, "Graph's mail delta feed for " + key
                                + " carried a message without an id; the page was not passed and nothing on it was imported");
                        return finish(profile, key, folder, start, reached, fetched, counts, errors, incompleteReads);
                    }
                }
                String after = page.nextLink() != null ? page.nextLink() : page.deltaLink();
                if (!m365.isMailDeltaLink(after)) {
                    FetchSupport.addError(errors, "Graph's mail delta feed for " + key + " answered a link that is not a mail delta link on this endpoint ('"
                            + after + "'); the page was not passed and nothing on it was imported");
                    return finish(profile, key, folder, start, reached, fetched, counts, errors, incompleteReads);
                }
                boolean stoppedInsideThePage = false;
                for (M365MessageSummary msg : page.messages()) {
                    if (imported >= limit) {
                        stoppedInsideThePage = true;
                        break;
                    }
                    fetchSupport.throttle(throttleMs);
                    int documentsBefore = counts.imported;
                    switch (attempt(callContext, profile, connector, m365, folderId, mailbox, msg, errors, counts)) {
                        case IMPORTED -> { imported++; attempted++; }
                        case SKIPPED -> { }
                        case FAILED_RECORDED -> {
                            attempted++;
                            // A message imported without some of its attachments has spent the
                            // budget as much as one imported whole.
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
                    FetchSupport.addError(errors, unrecordedFailures + " M365 Mail failure(s) could not be dead-lettered; "
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
                incompleteReads.add("M365 Mail folder " + folderId + ": " + cutBecause);
            }
            return finish(profile, key, folder, start, reached, fetched, counts, errors, incompleteReads);
        } catch (jp.aegif.nemaki.rest.controller.IntegrationSettingsService
                .SettingUnreadableException couldNotAsk) {
            // NOT the connector's failure. The checkpoint read refuses from INSIDE this try,
            // so swallowing it here turned a configuration-store outage into
            // "<connector> connection failed" — an error the scheduler counts towards opening
            // that connector's circuit breaker.
            throw couldNotAsk;
        } catch (Exception e) {
            FetchSupport.addError(errors, "M365 Mail connection failed: " + e.getMessage());
            // The pages passed before the failure stay passed: what they carried was imported or
            // recorded, and reading them again would only repeat the work.
            return finish(profile, key, folder, start, reached, fetched, counts, errors, incompleteReads);
        }
    }

    /** The feed position after a run, with the folder it reads: saved when it moved. */
    private FetchResult finish(ImportProfileDefinition profile, String key, String folder, String start, String reached,
                               int fetched, Counts counts, List<String> errors, List<String> incompleteReads) {
        if (folder != null && reached != null && !reached.equals(start)) {
            checkpointManager.saveSimpleCheckpoint(profile.getProfileId(), key, DELTA_PREFIX + folder + "|" + reached);
        }
        return new FetchResult(fetched, counts.imported, counts.skipped, errors, List.copyOf(incompleteReads));
    }

    /**
     * One message: its MIME, fetched, and imported. IMPORTED / SKIPPED as the import service
     * answers; otherwise the failure is dead-lettered — a MIME that could not be fetched as a
     * never-read row, an import that refused, threw, or dropped an attachment as a read row — and
     * RECORDED / UNRECORDED says whether the row was written. A message imported without some of
     * its attachments counts as imported (the document is there) and as failed (the attachment is not).
     */
    private Outcome attempt(CallContext callContext, ImportProfileDefinition profile, ConnectorDefinition connector,
                            M365MailConnectorAdapter m365, String folderId, String mailbox, M365MessageSummary msg,
                            List<String> errors, Counts counts) {
        // Built BEFORE the fetch, with a null stream, so a fetch failure — the likeliest per-item
        // failure — still records a row that names the item.
        ExternalIngestRequest req = fetchSupport.buildMailRequest(profile, connector, msg.id(), msg.subject(), null, folderId);
        if (msg.internetMessageId() != null) req.getMetadata().put("internetMessageId", msg.internetMessageId());
        req.getMetadata().put("m365Mailbox", mailbox);
        InputStream content;
        try {
            content = m365.fetchMimeMessage(msg.id());
        } catch (Exception notRead) {
            FetchSupport.addError(errors, "M365 " + msg.id() + ": " + notRead.getMessage());
            return fetchSupport.saveSourceNeverReadToDlq(req, "M365 " + msg.id() + ": " + notRead.getMessage())
                    ? Outcome.FAILED_RECORDED : Outcome.FAILED_UNRECORDED;
        }
        req.setContentStream(content);
        try {
            ExternalIngestResult result = canonicalImportService.executeMailImport(callContext, req);
            List<String> attachmentWarnings = result.warnings() == null ? List.of()
                    : result.warnings().stream().filter(w -> w.contains("Attachment") || w.contains("attachment")).toList();
            // skipped() first: a skipped result also reports isSuccess()==true (no errors).
            if (result.skipped()) {
                counts.skipped++;
                if (attachmentWarnings.isEmpty()) return Outcome.SKIPPED;
                // The message was imported before; the import service tried its missing attachments
                // again and some still failed. Passed as a skip, nothing recorded them and the page
                // moved on (review, P1): recorded as read.
                String missing = "M365 " + msg.id() + ": already imported, but some of its attachments are still missing — "
                        + String.join(", ", attachmentWarnings);
                FetchSupport.addError(errors, missing);
                return fetchSupport.saveSourceReadToDlq(req, missing) ? Outcome.FAILED_RECORDED : Outcome.FAILED_UNRECORDED;
            }
            if (result.isSuccess() && attachmentWarnings.isEmpty()) {
                counts.imported++;
                return Outcome.IMPORTED;
            }
            String why;
            if (result.isSuccess()) {
                // The message is in the repository, some of its attachments are not: a partial
                // import. Passed silently before — the attachment was lost once a newer message
                // moved the checkpoint — it is recorded now.
                counts.imported++;
                why = "M365 " + msg.id() + ": imported without some of its attachments — " + String.join(", ", attachmentWarnings);
            } else {
                why = "M365 " + msg.id() + ": " + String.join(", ", result.errors());
            }
            FetchSupport.addError(errors, why);
            return fetchSupport.saveSourceReadToDlq(req, why) ? Outcome.FAILED_RECORDED : Outcome.FAILED_UNRECORDED;
        } catch (Exception e) {
            FetchSupport.addError(errors, "M365 " + msg.id() + ": " + e.getMessage());
            return fetchSupport.saveSourceReadToDlq(req, "M365 " + msg.id() + ": " + e.getMessage())
                    ? Outcome.FAILED_RECORDED : Outcome.FAILED_UNRECORDED;
        } finally {
            try { content.close(); } catch (Exception ignored) { }
        }
    }
}
