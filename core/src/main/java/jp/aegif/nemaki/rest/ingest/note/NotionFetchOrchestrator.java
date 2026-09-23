package jp.aegif.nemaki.rest.ingest.note;

import jp.aegif.nemaki.rest.ingest.*;
import jp.aegif.nemaki.rest.ingest.note.NotionConnectorAdapter.NotionFile;
import jp.aegif.nemaki.rest.ingest.note.NotionConnectorAdapter.NotionPageSummary;
import org.apache.chemistry.opencmis.commons.server.CallContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetch orchestrator for Notion compound-note adapter.
 */
public class NotionFetchOrchestrator implements FetchOrchestrator {

    private FetchSupport fetchSupport;
    private CheckpointManager checkpointManager;
    private CanonicalImportService canonicalImportService;

    /**
     * How this orchestrator obtains its adapter (R14, plan A-8).
     *
     * <p>It used to be {@code new NotionConnectorAdapter(token)} inline, which made every arm
     * below — the per-page dead letter, the attachment dead letter, the checkpoint hold — reachable
     * only by talking to Notion. They were written from reasoning and never run. Same shape and
     * same package-private visibility as {@code ImapIdleMonitor.adapterFactory}.
     */
    java.util.function.Function<String, NotionConnectorAdapter> adapterFactory =
            NotionConnectorAdapter::new;

    public void setFetchSupport(FetchSupport fs) { this.fetchSupport = fs; }
    public void setCheckpointManager(CheckpointManager cm) { this.checkpointManager = cm; }
    public void setCanonicalImportService(CanonicalImportService cis) { this.canonicalImportService = cis; }

    /**
     * The clock an edit minute is judged CLOSED against; tests fix it.
     */
    java.time.Clock clock = java.time.Clock.systemUTC();

    /** Scheduler parameter: how many {@code /search} requests one listing may make. */
    static final String PARAM_MAX_SEARCH_REQUESTS = "notionSearchMaxRequests";
    /**
     * Scheduler parameter: how many minutes after an edit minute ENDS before the checkpoint may
     * name it. Notion's search index is not immediate and its documentation gives no bound; a
     * page whose edit reaches the index later than this, at a minute the checkpoint has already
     * passed, is not seen again until it is edited again (R106).
     */
    static final String PARAM_INDEX_LAG_MINUTES = "notionIndexLagMinutes";
    static final int DEFAULT_INDEX_LAG_MINUTES = 10;

    /**
     * What the stored checkpoint says: the newest edit minute this profile has imported from,
     * and the pages imported AT that minute.
     *
     * <p>Notion rounds {@code last_edited_time} DOWN to the minute, so a minute is a GROUP of
     * pages, and a per-run budget can stop inside one. Naming only the minute would either
     * exclude the rest of the group for ever (what R59 was) or read the whole group again on
     * every poll; naming the ids as well lets the next poll skip what was done and take the
     * rest. Stored as {@code <minute>} or {@code <minute>|<id>,<id>,…}; the first form is what
     * every checkpoint written before R59 looks like, and it reads as "no id at that minute is
     * known to be done", so such a minute is read again once — imported only where the
     * profile's dedupe policy imports a source id it already holds (the default,
     * {@code skip_if_same_version}, skips it without comparing versions).
     */
    record Checkpoint(String editedAt, java.util.Set<String> idsAtThatMinute) {

        static final Checkpoint NONE = new Checkpoint(null, java.util.Set.of());

        static Checkpoint parse(String stored) {
            if (stored == null || stored.isBlank()) return NONE;
            int bar = stored.indexOf('|');
            if (bar < 0) return new Checkpoint(stored, java.util.Set.of());
            String ids = stored.substring(bar + 1);
            return new Checkpoint(stored.substring(0, bar), ids.isBlank()
                    ? java.util.Set.of()
                    : java.util.Set.copyOf(java.util.Arrays.asList(ids.split(","))));
        }

        String encode() {
            if (editedAt == null) return null;
            if (idsAtThatMinute.isEmpty()) return editedAt;
            return editedAt + "|" + String.join(",", new java.util.TreeSet<>(idsAtThatMinute));
        }

        /** True when this checkpoint already accounts for the page: older than the minute, or done at it. */
        boolean covers(NotionPageSummary page) {
            if (editedAt == null || page.lastEditedTime() == null) return false;
            int order = page.lastEditedTime().compareTo(editedAt);
            return order < 0 || (order == 0 && idsAtThatMinute.contains(page.id()));
        }
    }

    /** Oldest first, so that what a budget leaves for the next poll is always NEWER than what it took. */
    private static final java.util.Comparator<NotionPageSummary> OLDEST_FIRST =
            java.util.Comparator.comparing(NotionPageSummary::lastEditedTime,
                    java.util.Comparator.nullsLast(java.util.Comparator.<String>naturalOrder()))
                    .thenComparing(NotionPageSummary::id, java.util.Comparator.nullsLast(java.util.Comparator.<String>naturalOrder()));

    /** The largest index-lag allowance accepted: 30 days, in minutes. Beyond it the checkpoint would never move. */
    static final int MAX_INDEX_LAG_MINUTES = 30 * 24 * 60;
    /** The largest request cap accepted: 1,000,000 requests (100 million rows), well past Notion's own limit. */
    static final int MAX_SEARCH_REQUESTS = 1_000_000;

    private static int intParam(Map<String, String> params, String name, int fallback,
            int minimum, int maximum) {
        String raw = params == null ? null : params.get(name);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int value = Integer.parseInt(raw.trim());
            if (value >= minimum && value <= maximum) return value;
        } catch (NumberFormatException notANumber) {
            // fall through
        }
        throw new IllegalArgumentException("Notion connector parameter " + name
                + " must be an integer between " + minimum + " and " + maximum + ", not '" + raw + "'");
    }

    /**
     * Whether every edit that Notion will ever stamp with this minute has had time to reach
     * the search index — the minute ended, and {@code lagMinutes} more have passed. A value
     * this cannot parse is never closed: the checkpoint then holds, which is the side to fail on.
     */
    boolean isClosed(String editedAt, int lagMinutes) {
        try {
            // long arithmetic: 1 + Integer.MAX_VALUE wrapped negative and closed every minute
            // at once (review, P1). The parameter is bounded as well; this is the second wall.
            return !java.time.Instant.parse(editedAt)
                    .plus(1L + lagMinutes, java.time.temporal.ChronoUnit.MINUTES)
                    .isAfter(clock.instant());
        } catch (java.time.DateTimeException | ArithmeticException unreadable) {
            logger.warn("Notion last_edited_time '{}' cannot be placed in time ({}); the checkpoint will not name it",
                    editedAt, unreadable.getMessage());
            return false;
        }
    }

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(NotionFetchOrchestrator.class);

    @Override public String sourceSystem() { return "notion"; }

    @Override
    public FetchResult execute(CallContext callContext, ImportProfileDefinition profile,
                               ConnectorDefinition connector, Map<String, String> params, int limit) {
        String query = params.get("query"); // null = no filter

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
        if (token == null) return new FetchResult(0, 0, List.of("No token for Notion connector"));

        int maxRequests;
        int lagMinutes;
        try {
            maxRequests = intParam(params, PARAM_MAX_SEARCH_REQUESTS,
                    NotionConnectorAdapter.DEFAULT_MAX_SEARCH_REQUESTS, 1, MAX_SEARCH_REQUESTS);
            lagMinutes = intParam(params, PARAM_INDEX_LAG_MINUTES, DEFAULT_INDEX_LAG_MINUTES,
                    0, MAX_INDEX_LAG_MINUTES);
        } catch (IllegalArgumentException badParameter) {
            // A setting that cannot be read as a number is not a connector failure, and it is
            // not the default either: guessing the default would silently ignore what the
            // operator wrote. It is reported and nothing is read.
            return new FetchResult(0, 0, List.of(badParameter.getMessage()));
        }

        List<String> errors = new ArrayList<>();
        List<String> incompleteReads = new ArrayList<>();
        int fetched = 0, imported = 0, skipped = 0;
        try {
            var notion = adapterFactory.apply(token);
            String stored = checkpointManager.loadSimpleCheckpoint(profile.getProfileId(), "notion");
            Checkpoint checkpoint = Checkpoint.parse(stored);
            // Newest first, read down to the checkpoint minute (R59). The listing takes no
            // limit: a limit on an ORDERED listing returns the same end of the workspace on
            // every poll — ascending, that was the oldest pages, and nothing past the first
            // batch was ever imported.
            NotionConnectorAdapter.PageListing listing =
                    notion.searchPages(query, checkpoint.editedAt(), maxRequests);
            fetched = listing.pages().size();
            List<NotionPageSummary> candidates = new ArrayList<>();
            for (NotionPageSummary page : listing.pages()) {
                if (checkpoint.covers(page)) {
                    skipped++;
                    continue;
                }
                candidates.add(page);
            }
            candidates.sort(OLDEST_FIRST);

            List<NotionPageSummary> candidatesThisRun;
            if (!listing.complete()) {
                // NOT an error — nothing failed, and putting it in `errors` would have the
                // scheduler count a healthy large workspace towards the connector's circuit
                // breaker on every poll. It is recorded so the job record says PARTIAL rather
                // than COMPLETED.
                //
                // And nothing is imported. The rows this poll was NOT shown are older than
                // every row it was (the listing is newest first), so a checkpoint raised over
                // any of them would exclude the unseen ones from every later poll — which is
                // exactly what R59 was. Holding the checkpoint and importing the newest rows
                // would import the same rows again on every poll instead; the operator is told
                // what to raise.
                //
                // The reason is stated only where it holds: for an ORDERED listing the unseen
                // rows are older than the seen ones; for one Notion refused to sort, nothing
                // says which rows were left out, and the message says that instead (review,
                // P3 — the first draft claimed "older" for both).
                incompleteReads.add("Notion page listing: " + listing.truncatedBecause()
                        + " — nothing was imported and the checkpoint holds: "
                        + (listing.ordered()
                                ? "the pages this poll was not shown are older than the ones it was"
                                : "the listing came back unordered, so which pages were left out cannot be told"));
                candidatesThisRun = List.of();
            } else {
                candidatesThisRun = candidates;
            }
            long throttleMs = FetchSupport.calculateThrottleDelayMs(connector);

            // The pages whose import SETTLED — succeeded without an attachment failure, or was
            // skipped by the import service. A page that failed is dead-lettered inside the
            // loop, does not hold the checkpoint back, and is not named by it either.
            //
            // The per-run budget counts SETTLED pages, not attempts. Oldest first, so what is
            // left is newer than everything taken and the checkpoint can move without passing
            // it. Counting attempts let pages that fail on every poll — offered again because
            // the checkpoint does not name them — fill the budget from the head of the list, so
            // nothing behind them was ever tried and the checkpoint never moved (review, P1).
            // A failure costs its attempt and its dead-letter row (one row per item, updated in
            // place), and the pages behind it still get their turn; once a newer minute settles,
            // the checkpoint passes the failing page and it is not offered again.
            List<NotionPageSummary> settled = new ArrayList<>();
            int attempted = 0;
            for (NotionPageSummary page : candidatesThisRun) {
                if (settled.size() >= limit) {
                    break;
                }
                attempted++;
                fetchSupport.throttle(throttleMs);
                // Declared OUTSIDE the try so the catch can dead-letter the page. Everything
                // that throws before executeNoteImport — fetchPageAsHtml, extractFiles —
                // happens above the import service's own DLQ net, so this arm was the only
                // place that could record the loss, and it recorded nothing.
                ExternalIngestRequest req = null;
                try {
                    boolean importBody = "files_and_body".equals(profile.getImportPolicy());
                    req = new ExternalIngestRequest();
                    req.setProfileId(profile.getProfileId());
                    req.setConnectorId(connector.getConnectorId());
                    req.setRepositoryId(profile.getRepositoryId());
                    req.setSourceObjectId(page.id());
                    req.setSourceObjectType("page");
                    // files_only (default) makes executeNoteImport skip the page-body
                    // document and import only attachments — so we don't even need to
                    // render the page HTML (the user does not want HTML fragments).
                    req.setImportPolicy(profile.getImportPolicy());
                    if (importBody) {
                        String html = notion.fetchPageAsHtml(page.id());
                        req.setFileName(FetchSupport.sanitizeSubject(page.title()) + ".html");
                        req.setMimeType("text/html");
                        req.setContentStream(new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
                    }
                    req.setExecutionMode("scheduled");

                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("pageId", page.id());
                    metadata.put("pageUrl", page.url());
                    metadata.put("parentPageId", page.parentId());
                    metadata.put("workspaceId", connector.getTenantId());

                    // Fetch file attachments
                    List<NotionFile> files = notion.extractFiles(page.id());
                    List<byte[]> attachmentBinaries = new ArrayList<>();
                    boolean attachmentDownloadFailed = false;
                    if (!files.isEmpty()) {
                        List<Map<String, Object>> attachments = new ArrayList<>();
                        for (NotionFile f : files) {
                            try {
                                final int MAX_ATTACHMENT_BYTES = 50 * 1024 * 1024;
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                try (var in = notion.downloadFile(f.url())) {
                                    byte[] buf = new byte[8192];
                                    int total = 0, n;
                                    while ((n = in.read(buf)) != -1) {
                                        total += n;
                                        if (total > MAX_ATTACHMENT_BYTES) {
                                            throw new RuntimeException("Attachment '" + f.name()
                                                    + "' exceeds " + (MAX_ATTACHMENT_BYTES / 1024 / 1024) + " MB limit");
                                        }
                                        baos.write(buf, 0, n);
                                    }
                                }
                                Map<String, Object> att = new LinkedHashMap<>();
                                att.put("attachmentId", f.blockId());
                                att.put("filename", f.name());
                                att.put("mimeType", f.type().equals("image") ? "image/png" : "application/octet-stream");
                                attachments.add(att);
                                attachmentBinaries.add(baos.toByteArray());
                            } catch (Exception e) {
                                attachmentDownloadFailed = true;
                                FetchSupport.addError(errors, "Notion file " + f.name() + ": " + e.getMessage());
                            }
                        }
                        metadata.put("attachments", attachments);
                    }
                    req.setMetadata(metadata);

                    // Inject contentBase64 transiently
                    if (!attachmentBinaries.isEmpty()) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> attList = (List<Map<String, Object>>) metadata.get("attachments");
                        for (int ai = 0; ai < attList.size() && ai < attachmentBinaries.size(); ai++) {
                            attList.get(ai).put("contentBase64",
                                    java.util.Base64.getEncoder().encodeToString(attachmentBinaries.get(ai)));
                        }
                    }

                    ExternalIngestResult result;
                    try {
                        result = canonicalImportService.executeNoteImport(callContext, req);
                    } finally {
                        if (!attachmentBinaries.isEmpty()) {
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> attList = (List<Map<String, Object>>) metadata.get("attachments");
                            if (attList != null) {
                                for (Map<String, Object> att : attList) att.remove("contentBase64");
                            }
                        }
                    }

                    boolean hasAttachmentWarning = result.warnings() != null
                            && result.warnings().stream().anyMatch(w -> w.toLowerCase().contains("attachment"));
                    if ((result.isSuccess() && !hasAttachmentWarning && !attachmentDownloadFailed) || result.skipped()) {
                        settled.add(page);
                    }
                    // skipped() first: isSuccess() is true whenever there are no
                    // errors, which includes a skipped result — so a skip would
                    // otherwise be miscounted as an import.
                    if (result.skipped()) skipped++;
                    else if (result.isSuccess()) imported++;
                    else FetchSupport.addError(errors, "Notion " + page.id() + ": " + String.join(", ", result.errors()));

                    // If an attachment download failed, the page's own high-water is
                    // held back (above), but a NEWER page succeeding later in this
                    // batch would advance the checkpoint past this page and strand
                    // its attachment. DLQ the page so the attachment stays retryable.
                    if (attachmentDownloadFailed) {
                        // saveSourceNeverReadToDlq, like the page arm below. The attachment
                        // list was read but its BYTES were not, so the stored request carries
                        // descriptors with no content: a replay imports nothing, reports
                        // "files_only: page has no attachments", and — while this row was
                        // written as an ordinary save — the retry door took that for an
                        // idempotent resolution and DELETED the row. A review traced the chain
                        // through the arm four lines above, which round 53 had just closed for
                        // the page while leaving this one open.
                        fetchSupport.saveSourceNeverReadToDlq(req, "Notion page " + page.id()
                                + ": attachment download failed");
                    }
                } catch (Exception e) {
                    FetchSupport.addError(errors, "Notion page " + page.id() + ": " + e.getMessage());
                    // DLQ before the checkpoint can move past this page. A LATER page
                    // succeeding in the same batch raises the high-water mark past this one,
                    // and every later poll then filters it out — permanently uncaptured, with
                    // no row saying so. The identical arm was closed for Chatwork, Salesforce,
                    // Dropbox, Box and Slack, and for this file's own attachment arm four
                    // lines above; a review found the page arm still open.
                    if (req != null) {
                        // saveSourceNeverReadToDlq, not saveToDlq: the fetch threw before the
                        // import service was reached, so the row carries no content and — when
                        // extractFiles was the thrower — not even the attachment list. Replaying
                        // it under the default files_only policy reports "nothing to import",
                        // which the retry door treated as an idempotent resolution and deleted
                        // the row with. A review traced that the fix for the missing DLQ row had
                        // opened a way to destroy it.
                        fetchSupport.saveSourceNeverReadToDlq(req,
                                "Notion page " + page.id() + ": " + e.getMessage());
                    }
                }
            }
            int leftForTheNextPoll = candidatesThisRun.size() - attempted;
            if (leftForTheNextPoll > 0) {
                incompleteReads.add("Notion page listing: the run's limit of " + limit
                        + " page(s) was reached with " + leftForTheNextPoll
                        + " newer page(s) left for the next poll");
            }
            // HOW FAR the checkpoint may move (R59).
            //
            // To the newest CLOSED minute a settled page was edited in — closed meaning the
            // minute ended and the index-lag allowance has passed, so no edit Notion will ever
            // stamp with that minute is still on its way to the search index. Pages in minutes
            // not yet closed are re-listed and read again next poll — a page that reached the
            // index late is imported then; one this profile already holds is skipped by the
            // default dedupe policy (which compares source ids, not versions) — that is the
            // price of a timestamp rounded to the minute and an index that is not immediate. At
            // the chosen minute, the ids of the SETTLED pages are recorded, so a budget that
            // stopped inside the group leaves the rest for the next poll rather than either
            // excluding it (R59) or reading the group again.
            //
            // Processing was oldest first, so nothing left for the next poll is older than the
            // chosen minute, and a listing that was cut short imported nothing (above).
            //
            // The ids are those of SETTLED pages only. A page that failed at that minute is
            // dead-lettered and, left out of the set, is offered to the next poll again —
            // recording it would have named it done (review, P1). A page that failed at an
            // OLDER minute is passed, as before R59: its dead-letter row is the record, and a
            // page that fails on every poll must not hold the checkpoint for ever.
            String newestClosed = null;
            for (NotionPageSummary page : settled) {
                String at = page.lastEditedTime();
                if (at == null || (newestClosed != null && at.compareTo(newestClosed) <= 0)) continue;
                if (checkpoint.editedAt() != null && at.compareTo(checkpoint.editedAt()) < 0) continue;
                if (isClosed(at, lagMinutes)) newestClosed = at;
            }
            if (newestClosed != null) {
                java.util.Set<String> idsAtThatMinute = new java.util.HashSet<>();
                if (newestClosed.equals(checkpoint.editedAt())) {
                    idsAtThatMinute.addAll(checkpoint.idsAtThatMinute());
                }
                for (NotionPageSummary page : settled) {
                    if (newestClosed.equals(page.lastEditedTime())) idsAtThatMinute.add(page.id());
                }
                String next = new Checkpoint(newestClosed, idsAtThatMinute).encode();
                if (!next.equals(stored)) {
                    checkpointManager.saveSimpleCheckpoint(profile.getProfileId(), "notion", next);
                }
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
        } catch (Exception e) {
            FetchSupport.addError(errors, "Notion connection failed: " + e.getMessage());
        }
        return new FetchResult(fetched, imported, skipped, errors, List.copyOf(incompleteReads));
    }
}
