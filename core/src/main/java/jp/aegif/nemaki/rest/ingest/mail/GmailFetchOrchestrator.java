package jp.aegif.nemaki.rest.ingest.mail;

import jp.aegif.nemaki.rest.ingest.*;
import jp.aegif.nemaki.rest.ingest.mail.GmailConnectorAdapter.GmailMessageSummary;
import org.apache.chemistry.opencmis.commons.server.CallContext;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Gmail: the messages the profile's query matches are read from the checkpoint one time window at
 * a time, and the OLDEST are taken first.
 *
 * <p>The previous shape asked for the first {@code limit} messages after a DAY
 * ({@code after:yyyy/MM/dd}, which Gmail documents as midnight Pacific time) and raised the day to
 * that of the newest message it had seen — so in a burst the older new messages fell below the day
 * for ever (R107). A message whose summary could not be read was logged and dropped, and one the
 * import refused was not recorded, while a newer one moved the day past it.
 *
 * <p>Now the checkpoint is the internal date (epoch ms — for SMTP mail the time Gmail accepted it)
 * of the newest message that settled, with the ids settled at it. Gmail's listing is in no
 * documented order and is not newest first in practice, so each listed message's internal date is
 * read and the candidates are sorted by it. So that a backlog does not mean reading the date of
 * every message in it on every poll, the listing is taken one window at a time: from the
 * checkpoint to now, halved while it does not fit in one page — the page is the run's attempt
 * bound ({@code limit × 4}, at most 500) — and one second that holds more than a page is read
 * whole. The search for the next window starts from the bound the last one did not fit under,
 * not from now, so reaching a crowded second behind an empty stretch costs a few requests per
 * window rather than a halving from now each time. A window is finished before the next one is
 * listed, and a finished window moves the
 * checkpoint to its end even when it held nothing, so the requests spent finding it are not spent
 * again. The query is sent in epoch seconds, its lower bound one second before the window: Gmail
 * documents neither bound as inclusive or exclusive, and so the window is listed whole under either
 * reading; what the search returns outside the window is taken with it, in date order, and a
 * message two windows both list is tried once.
 *
 * <p>Every request to Gmail — a listing, a summary, a raw form — is asked again after a growing
 * wait when Gmail answers 429 or 503, as the other adapters' shared client does, and the
 * connector's rate limit paces the summary reads as it paces the imports.
 *
 * <p>Budget, attempts and failures as for Box: the budget counts settled messages, at most
 * {@code limit × 4} are tried in a run, a failure is dead-lettered — a message whose raw form
 * could not be fetched as never read; one the import refused, threw on, or imported without some
 * of its parts (an attachment, its link, the raw .eml — {@link MailImportWarnings}) — on this try
 * or, already imported, again — as read — and passed once a newer message settles or its window is
 * finished;
 * a failure whose row could not be written holds the checkpoint. A message whose internal date
 * cannot be read is tried before the rest of its window and never named. The checkpoint is capped
 * at the listing's start minus {@code gmailCheckpointLagMinutes} (default 5): Gmail's search is an
 * index, and a message it has not indexed yet when its window is listed is offered by a later
 * poll only while it is above the checkpoint. A run cut at the request cap
 * ({@code gmailListMaxRequests}, default 50) keeps what it finished and imports nothing from the
 * window it was looking for.
 *
 * <p>Not covered (R114): a message that comes to match the query after the checkpoint has passed
 * its internal date — a label added, moved back to the inbox, marked unread, or imported through
 * the API with an older internal date. Gmail's history API would see those but cannot evaluate an
 * arbitrary search. Measured against a stub, not Gmail: that {@code after:} / {@code before:}
 * compare the internal date, and that the index catches up within the lag.
 */
public class GmailFetchOrchestrator implements FetchOrchestrator {

    static final String PARAM_MAX_LIST_REQUESTS = "gmailListMaxRequests";
    static final int MAX_LIST_REQUESTS = 1_000_000;
    static final String PARAM_CHECKPOINT_LAG_MINUTES = "gmailCheckpointLagMinutes";
    static final int DEFAULT_CHECKPOINT_LAG_MINUTES = 5;
    static final int MAX_CHECKPOINT_LAG_MINUTES = 30 * 24 * 60;
    /** How many messages one run may TRY per unit of budget: bounds a run whose messages keep failing. */
    static final int ATTEMPTS_PER_BUDGET = 4;
    /** The key the previous version wrote its day under; this version's checkpoint goes there too. */
    static final String KEY = "gmail";
    static final String DEFAULT_QUERY = "in:inbox is:unread";
    /** The checkpoint an earlier version wrote: the UTC day of the newest message it had seen. */
    static final Pattern LEGACY_DAY = Pattern.compile("[0-9]{4}/[0-9]{2}/[0-9]{2}");

    /** The adapter, by token; tests point it at a local stub of the Gmail API. */
    @FunctionalInterface
    interface AdapterFactory {
        GmailConnectorAdapter create(String token) throws Exception;
    }

    private FetchSupport fetchSupport;
    private CheckpointManager checkpointManager;
    private CanonicalImportService canonicalImportService;
    AdapterFactory adapterFactory = GmailConnectorAdapter::new;
    /** The clock the cap is taken from; tests fix it. */
    java.time.Clock clock = java.time.Clock.systemUTC();

    public void setFetchSupport(FetchSupport fs) { this.fetchSupport = fs; }
    public void setCheckpointManager(CheckpointManager cm) { this.checkpointManager = cm; }
    public void setCanonicalImportService(CanonicalImportService cis) { this.canonicalImportService = cis; }

    @Override public String sourceSystem() { return "gmail_mail"; }

    private static int intParam(Map<String, String> params, String name, int fallback, int minimum, int maximum) {
        String raw = params == null ? null : params.get(name);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int value = Integer.parseInt(raw.trim());
            if (value >= minimum && value <= maximum) return value;
        } catch (NumberFormatException notANumber) {
            // fall through
        }
        throw new IllegalArgumentException("Gmail connector parameter " + name
                + " must be an integer between " + minimum + " and " + maximum + ", not '" + raw + "'");
    }

    private enum Outcome { IMPORTED, SKIPPED, FAILED_RECORDED, FAILED_UNRECORDED }

    /** Why a window was left before its end. */
    private enum Stop { NONE, BUDGET, ATTEMPTS, UNRECORDED }

    /** What one run has done so far: enough to save the checkpoint from wherever it ends. */
    private static final class Run {
        WatermarkCheckpoint checkpoint = WatermarkCheckpoint.NONE;
        String cap;
        /** The end of the last finished window, capped; null when none finished. */
        String finishedTo;
        final List<WatermarkCheckpoint.Mark> settled = new ArrayList<>();
        final Set<String> attemptedIds = new HashSet<>();
        /** Summaries already read this run: a second two windows share is listed by both. */
        final Map<String, GmailMessageSummary> summaries = new java.util.HashMap<>();
        int settledCount, attempted, unrecordedFailures, fetched, imported, skipped;
    }

    /**
     * The stored checkpoint, read. Null when it cannot be: a value this connector cannot place is
     * NOT "no checkpoint" — read as none it would silently re-offer every message the query
     * matches, and nobody would learn that the stored value is broken.
     */
    static WatermarkCheckpoint readCheckpoint(String stored) {
        String value = stored == null ? "" : stored.trim();
        if (value.isEmpty()) return WatermarkCheckpoint.NONE;
        if (LEGACY_DAY.matcher(value).matches()) {
            // The day an earlier version wrote. Read from the START of that UTC day: Gmail documents
            // a date as Pacific midnight — hours after it — so the messages between were never
            // offered for it; now they are, and the import service's dedupe answers for the ones
            // that were imported.
            try {
                java.time.LocalDate day = java.time.LocalDate.parse(value, java.time.format.DateTimeFormatter
                        .ofPattern("uuuu/MM/dd").withResolverStyle(java.time.format.ResolverStyle.STRICT));
                return new WatermarkCheckpoint(WatermarkCheckpoint.canonical(
                        day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()), Set.of());
            } catch (java.time.format.DateTimeParseException notADay) {
                return null;
            }
        }
        WatermarkCheckpoint parsed = WatermarkCheckpoint.parse(value);
        String at = WatermarkCheckpoint.canonical(parsed.at());
        // Before 1970 no window can be listed from: Gmail's bounds are epoch seconds, and a
        // negative one is not a number to its search.
        if (at == null || Instant.parse(at).isBefore(Instant.EPOCH)) return null;
        return new WatermarkCheckpoint(at, parsed.idsAt());
    }

    @Override
    public FetchResult execute(CallContext callContext, ImportProfileDefinition profile,
                               ConnectorDefinition connector, Map<String, String> params, int limit) {
        String query = params == null ? DEFAULT_QUERY : params.getOrDefault("query", DEFAULT_QUERY);

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
        if (token == null) return new FetchResult(0, 0, List.of("No access token for Gmail connector"));

        int maxRequests;
        int lagMinutes;
        try {
            maxRequests = intParam(params, PARAM_MAX_LIST_REQUESTS, GmailConnectorAdapter.DEFAULT_MAX_LIST_REQUESTS, 1, MAX_LIST_REQUESTS);
            lagMinutes = intParam(params, PARAM_CHECKPOINT_LAG_MINUTES, DEFAULT_CHECKPOINT_LAG_MINUTES, 0, MAX_CHECKPOINT_LAG_MINUTES);
        } catch (IllegalArgumentException badParameter) {
            // Not a connector failure and not the default either: guessing the default would
            // silently ignore what the operator wrote. Reported, and nothing is read.
            return new FetchResult(0, 0, List.of(badParameter.getMessage()));
        }

        List<String> errors = new ArrayList<>();
        List<String> incompleteReads = new ArrayList<>();
        Run run = new Run();
        try {
            GmailConnectorAdapter gmail = adapterFactory.create(token);
            String stored = checkpointManager.loadSimpleCheckpoint(profile.getProfileId(), KEY);
            WatermarkCheckpoint checkpoint = readCheckpoint(stored);
            if (checkpoint == null) {
                FetchSupport.addError(errors, "Gmail checkpoint '" + stored + "' is not a position this connector can read; "
                        + "correct or clear it — nothing was read");
                return new FetchResult(0, 0, 0, errors);
            }
            Instant listingStartedAt = clock.instant();
            String cap;
            try {
                cap = WatermarkCheckpoint.canonical(listingStartedAt.minus(lagMinutes, java.time.temporal.ChronoUnit.MINUTES));
            } catch (IllegalStateException clockOutOfRange) {
                // The host's clock, not the connector: said so rather than "connection failed".
                FetchSupport.addError(errors, "Gmail: " + clockOutOfRange.getMessage() + "; nothing was read");
                return new FetchResult(0, 0, 0, errors);
            }
            run.checkpoint = checkpoint;
            run.cap = cap;
            // A checkpoint ABOVE this run's cap — written before the allowance was raised — covers
            // nothing above the cap: a message that was not yet in the index when that position was
            // written may still be missing. The stored position is not lowered; it is not trusted
            // above the cap until the cap passes it.
            WatermarkCheckpoint effective = checkpoint.at() != null && checkpoint.at().compareTo(cap) > 0
                    ? new WatermarkCheckpoint(cap, Set.of()) : checkpoint;

            int pageSize = Math.max(1, Math.min(GmailConnectorAdapter.MAX_PAGE_SIZE, limit * ATTEMPTS_PER_BUDGET));
            long throttleMs = FetchSupport.calculateThrottleDelayMs(connector);
            long untilSeconds = listingStartedAt.getEpochSecond() + 1;
            // The first window starts at the checkpoint's second — the ids settled in it are named,
            // the rest of it is not done — or, with no checkpoint, has no lower bound at all.
            Long windowFrom = effective.at() == null ? null : Instant.parse(effective.at()).getEpochSecond();
            int requests = 0;
            String cutBecause = null;
            Stop stop = Stop.NONE;
            // The lowest bound a window was found not to fit under: the next window is looked for
            // below it first.
            long ceiling = untilSeconds;
            windows:
            while (windowFrom == null || windowFrom < untilSeconds) {
                long lower = windowFrom == null ? 0 : windowFrom;
                Long after = windowFrom == null || windowFrom == 0 ? null : windowFrom - 1;
                long hi = ceiling;
                List<String> ids;
                while (true) {
                    if (requests >= maxRequests) {
                        cutBecause = "the cap of " + maxRequests + " listing request(s) was reached (raise the profile's "
                                + PARAM_MAX_LIST_REQUESTS + " parameter); what this run finished is kept, nothing was "
                                + "imported from the window it was looking for, and the next poll continues from the checkpoint";
                        break windows;
                    }
                    requests++;
                    GmailConnectorAdapter.ListPage first = gmail.listPage(query, after, hi, pageSize, null);
                    if (first.nextPageToken() == null) {
                        ids = first.ids();
                        break;
                    }
                    if (hi - lower <= 1) {
                        // One second holds more than a page: it cannot be narrowed, so it is read whole.
                        List<String> all = new ArrayList<>(first.ids());
                        Set<String> tokensSeen = new HashSet<>();
                        String pageToken = first.nextPageToken();
                        while (pageToken != null) {
                            if (!tokensSeen.add(pageToken)) {
                                cutBecause = "Gmail answered the page token '" + pageToken + "' twice for one second's "
                                        + "messages; the listing does not advance, so nothing was imported from that window";
                                break windows;
                            }
                            if (requests >= maxRequests) {
                                cutBecause = "the cap of " + maxRequests + " listing request(s) was reached inside one second's "
                                        + "messages (raise the profile's " + PARAM_MAX_LIST_REQUESTS + " parameter); what this run "
                                        + "finished is kept, and nothing was imported from that second";
                                break windows;
                            }
                            requests++;
                            GmailConnectorAdapter.ListPage next = gmail.listPage(query, after, hi, pageSize, pageToken);
                            all.addAll(next.ids());
                            pageToken = next.nextPageToken();
                        }
                        ids = all;
                        break;
                    }
                    ceiling = hi;
                    hi = lower + (hi - lower) / 2;
                }
                stop = window(callContext, profile, connector, gmail, new LinkedHashSet<>(ids), effective, limit,
                        throttleMs, run, errors);
                if (stop != Stop.NONE) break;
                // The window is finished: everything in it settled or was recorded. The checkpoint
                // may move to its end — never above the cap.
                String end = WatermarkCheckpoint.canonical(Instant.ofEpochSecond(hi));
                run.finishedTo = end.compareTo(cap) <= 0 ? end : cap;
                windowFrom = hi;
                if (hi >= ceiling) ceiling = untilSeconds;
            }
            if (stop == Stop.BUDGET) {
                incompleteReads.add("Gmail: the run's limit of " + limit + " message(s) was reached; the newer messages are "
                        + "left for the next poll");
            } else if (stop == Stop.ATTEMPTS) {
                incompleteReads.add("Gmail: " + run.attempted + " message(s) were attempted (" + ATTEMPTS_PER_BUDGET
                        + " × the limit of " + limit + ") with only " + run.settledCount + " settled — the failures are in the "
                        + "dead-letter queue; the newer messages are reached once the failures settle or the checkpoint "
                        + "passes them, or with a higher limit");
            } else if (cutBecause != null) {
                // NOT an error — nothing failed, and an error on every poll would open the
                // connector's breaker. Recorded so the job says PARTIAL.
                incompleteReads.add("Gmail: " + cutBecause);
            }
            return finish(profile, run, errors, incompleteReads);
        } catch (jp.aegif.nemaki.rest.controller.IntegrationSettingsService
                .SettingUnreadableException couldNotAsk) {
            // NOT the connector's failure. The checkpoint read refuses from INSIDE this try,
            // so swallowing it here turned a configuration-store outage into
            // "<connector> connection failed" — an error the scheduler counts towards opening
            // that connector's circuit breaker.
            throw couldNotAsk;
        } catch (Exception e) {
            FetchSupport.addError(errors, "Gmail connection failed: " + e.getMessage());
            // What settled before the failure stays settled: saved as far as it reached.
            return finish(profile, run, errors, incompleteReads);
        }
    }

    /**
     * One window: every listed message's internal date is read, and the ones the checkpoint does
     * not cover and this run has not tried are tried oldest first. A message whose date cannot be
     * read is tried first and never named: its place is unknown, so it is settled or recorded
     * before anything in the window can move the checkpoint.
     */
    private Stop window(CallContext callContext, ImportProfileDefinition profile, ConnectorDefinition connector,
                        GmailConnectorAdapter gmail, Set<String> ids, WatermarkCheckpoint effective,
                        int limit, long throttleMs, Run run, List<String> errors) {
        run.fetched += ids.size();
        List<GmailMessageSummary> unplaceable = new ArrayList<>();
        List<GmailMessageSummary> placeable = new ArrayList<>();
        java.util.Map<String, String> at = new java.util.HashMap<>();
        for (String id : ids) {
            if (run.attemptedIds.contains(id)) continue;
            GmailMessageSummary summary = run.summaries.get(id);
            try {
                if (summary == null) {
                    // Paced like the imports: a window's summary reads are requests to Gmail too.
                    fetchSupport.throttle(throttleMs);
                    summary = gmail.summary(id);
                    run.summaries.put(id, summary);
                }
            } catch (Exception unreadable) {
                // Not dropped: the message is tried by its id, and a failure to read it is
                // dead-lettered like any other.
                unplaceable.add(new GmailMessageSummary(id, null, null, null, null));
                continue;
            }
            String canonical = summary.internalDate() == null || summary.internalDate() < 0 ? null
                    : placeable(summary.internalDate());
            if (canonical == null) {
                unplaceable.add(summary);
                continue;
            }
            if (effective.covers(canonical, id)) {
                run.skipped++;
                continue;
            }
            at.put(id, canonical);
            placeable.add(summary);
        }
        placeable.sort(java.util.Comparator.comparing((GmailMessageSummary s) -> at.get(s.id()))
                .thenComparing(GmailMessageSummary::id));
        List<GmailMessageSummary> order = new ArrayList<>(unplaceable);
        order.addAll(placeable);
        for (GmailMessageSummary msg : order) {
            if (run.settledCount >= limit) return Stop.BUDGET;
            if (run.attempted >= limit * ATTEMPTS_PER_BUDGET) return Stop.ATTEMPTS;
            run.attempted++;
            run.attemptedIds.add(msg.id());
            fetchSupport.throttle(throttleMs);
            switch (attempt(callContext, profile, connector, gmail, msg, errors, run)) {
                case IMPORTED, SKIPPED -> {
                    run.settledCount++;
                    String when = at.get(msg.id());
                    if (when != null) run.settled.add(new WatermarkCheckpoint.Mark(when, msg.id()));
                }
                case FAILED_RECORDED -> { }
                case FAILED_UNRECORDED -> {
                    run.unrecordedFailures++;
                    return Stop.UNRECORDED;
                }
            }
        }
        return Stop.NONE;
    }

    /** An internal date in the checkpoint's form, or null when that form cannot hold it. */
    private static String placeable(long epochMillis) {
        try {
            return WatermarkCheckpoint.canonical(Instant.ofEpochMilli(epochMillis));
        } catch (IllegalStateException outsideTheFourDigitYears) {
            return null;
        }
    }

    /** The checkpoint after a run, saved when its position moved — held while a failure is unrecorded. */
    private FetchResult finish(ImportProfileDefinition profile, Run run, List<String> errors, List<String> incompleteReads) {
        if (run.unrecordedFailures > 0) {
            // A message nothing records: moving the checkpoint past it would lose it silently.
            FetchSupport.addError(errors, run.unrecordedFailures + " Gmail failure(s) could not be dead-lettered; "
                    + "the checkpoint holds so that they are offered again");
        } else if (run.cap != null) {
            // Only the marks at or below the cap are named (see the cap in execute()).
            List<WatermarkCheckpoint.Mark> nameable = new ArrayList<>();
            for (var mark : run.settled) {
                if (mark.at().compareTo(run.cap) <= 0) nameable.add(mark);
            }
            WatermarkCheckpoint next = run.checkpoint.after(nameable);
            if (run.finishedTo != null && (next.at() == null || next.at().compareTo(run.finishedTo) < 0)) {
                next = new WatermarkCheckpoint(run.finishedTo, Set.of());
            }
            String before = run.checkpoint.encode();
            String encoded = next.encode();
            // Saved only when the POSITION moved: a legacy day read as its canonical start encodes
            // differently without having moved.
            if (encoded != null && !encoded.equals(before)) {
                checkpointManager.saveSimpleCheckpoint(profile.getProfileId(), KEY, encoded);
            }
        }
        return new FetchResult(run.fetched, run.imported, run.skipped, errors, List.copyOf(incompleteReads));
    }

    /**
     * One message: its raw form, fetched, and imported. IMPORTED / SKIPPED as the import service
     * answers; otherwise the failure is dead-lettered — a raw form that could not be fetched as a
     * never-read row, an import that refused, threw, or dropped an attachment as a read row — and
     * RECORDED / UNRECORDED says whether the row was written. A message imported without some of its
     * attachments counts as imported (the document is there) and as failed (the attachment is not).
     */
    private Outcome attempt(CallContext callContext, ImportProfileDefinition profile, ConnectorDefinition connector,
                            GmailConnectorAdapter gmail, GmailMessageSummary msg, List<String> errors, Run run) {
        // Built BEFORE the fetch, with a null stream, so a fetch failure — the likeliest per-item
        // failure — still records a row that names the item.
        ExternalIngestRequest req = fetchSupport.buildMailRequest(profile, connector, msg.id(), msg.subject(), null, "inbox");
        req.getMetadata().put("internetMessageId", msg.id());
        if (msg.threadId() != null) req.getMetadata().put("gmailThreadId", msg.threadId());
        InputStream content;
        try {
            content = gmail.fetchRawMessage(msg.id());
        } catch (Exception notRead) {
            FetchSupport.addError(errors, "Gmail " + msg.id() + ": " + notRead.getMessage());
            return fetchSupport.saveSourceNeverReadToDlq(req, "Gmail " + msg.id() + ": " + notRead.getMessage())
                    ? Outcome.FAILED_RECORDED : Outcome.FAILED_UNRECORDED;
        }
        req.setContentStream(content);
        try {
            ExternalIngestResult result = canonicalImportService.executeMailImport(callContext, req);
            // The parts of the mail the import could not import or link (MailImportWarnings) —
            // not every warning: the others are about evidence (review, P1).
            List<String> attachmentWarnings = MailImportWarnings.missingParts(result.warnings());
            // skipped() first: a skipped result also reports isSuccess()==true (no errors).
            if (result.skipped()) {
                run.skipped++;
                if (attachmentWarnings.isEmpty()) return Outcome.SKIPPED;
                // Imported before; the import service tried its missing parts again and some still
                // failed. Passed as a skip, nothing would record them (review, P1 on M365).
                String missing = "Gmail " + msg.id() + ": already imported, but some of its parts are still missing — "
                        + String.join(", ", attachmentWarnings);
                FetchSupport.addError(errors, missing);
                return fetchSupport.saveSourceReadToDlq(req, missing) ? Outcome.FAILED_RECORDED : Outcome.FAILED_UNRECORDED;
            }
            if (result.isSuccess() && attachmentWarnings.isEmpty()) {
                run.imported++;
                return Outcome.IMPORTED;
            }
            String why;
            if (result.isSuccess()) {
                // The message is in the repository, some of its attachments are not: recorded — it
                // used to be neither counted nor recorded, and the day moved past it.
                run.imported++;
                why = "Gmail " + msg.id() + ": imported without some of its parts — " + String.join(", ", attachmentWarnings);
            } else {
                why = "Gmail " + msg.id() + ": " + String.join(", ", result.errors());
            }
            FetchSupport.addError(errors, why);
            return fetchSupport.saveSourceReadToDlq(req, why) ? Outcome.FAILED_RECORDED : Outcome.FAILED_UNRECORDED;
        } catch (Exception e) {
            FetchSupport.addError(errors, "Gmail " + msg.id() + ": " + e.getMessage());
            return fetchSupport.saveSourceReadToDlq(req, "Gmail " + msg.id() + ": " + e.getMessage())
                    ? Outcome.FAILED_RECORDED : Outcome.FAILED_UNRECORDED;
        } finally {
            try { content.close(); } catch (Exception ignored) { }
        }
    }
}
