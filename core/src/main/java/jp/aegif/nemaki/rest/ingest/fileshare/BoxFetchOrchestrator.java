package jp.aegif.nemaki.rest.ingest.fileshare;

import jp.aegif.nemaki.rest.ingest.*;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetch orchestrator for Box file-share adapter.
 */
public class BoxFetchOrchestrator implements FetchOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(BoxFetchOrchestrator.class);

    private FetchSupport fetchSupport;
    private CheckpointManager checkpointManager;
    private CanonicalImportService canonicalImportService;

    public void setFetchSupport(FetchSupport fetchSupport) { this.fetchSupport = fetchSupport; }
    public void setCheckpointManager(CheckpointManager checkpointManager) { this.checkpointManager = checkpointManager; }
    public void setCanonicalImportService(CanonicalImportService canonicalImportService) { this.canonicalImportService = canonicalImportService; }

    /**
     * How this orchestrator obtains its adapter (R107). It used to be {@code new BoxConnectorAdapter(token)}
     * inline, which made every arm below reachable only by talking to Box; tests point the
     * REAL adapter at a local stub of the API through this factory (the shape
     * {@code NotionFetchOrchestrator} and {@code ImapIdleMonitor} use).
     */
    java.util.function.Function<String, BoxConnectorAdapter> adapterFactory = BoxConnectorAdapter::new;

    /** Scheduler parameter: how many listing requests one poll may make. */
    static final String PARAM_MAX_LIST_REQUESTS = "boxListMaxRequests";
    static final int MAX_LIST_REQUESTS = 1_000_000;
    /**
     * How many files one run may ATTEMPT, as a multiple of its budget of settled files: the
     * budget counts settled files so that files failing on every poll cannot starve the ones
     * behind them, and the attempts are bounded so that a run in which every import fails
     * cannot outlast the scheduler's fetch timeout (the rule R59 settled on for Notion).
     */
    static final int ATTEMPTS_PER_BUDGET = 4;

    /**
     * Scheduler parameter: how many minutes BEFORE the listing started the checkpoint may
     * reach. A folder listing is not a snapshot: a file added or moved in while the pages are
     * being read, at a name position the marker or cursor has already passed, is not in this
     * listing. Capping the checkpoint at (listing start − this allowance) keeps every file
     * modified around the listing above the checkpoint, so the next listing offers it. What
     * the cap cannot reach is a file moved in with a modification time older than the cap —
     * a limit of any modification-time watermark, and one this connector always had.
     */
    static final String PARAM_CHECKPOINT_LAG_MINUTES = "boxCheckpointLagMinutes";
    static final int DEFAULT_CHECKPOINT_LAG_MINUTES = 5;
    static final int MAX_CHECKPOINT_LAG_MINUTES = 30 * 24 * 60;

    /** The clock the cap is taken from; tests fix it. */
    java.time.Clock clock = java.time.Clock.systemUTC();


    private static int intParam(Map<String, String> params, String name, int fallback, int minimum, int maximum) {
        String raw = params == null ? null : params.get(name);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int value = Integer.parseInt(raw.trim());
            if (value >= minimum && value <= maximum) return value;
        } catch (NumberFormatException notANumber) {
            // fall through
        }
        throw new IllegalArgumentException("Box connector parameter " + name
                + " must be an integer between " + minimum + " and " + maximum + ", not '" + raw + "'");
    }

    private enum Outcome { IMPORTED, SKIPPED, FAILED_RECORDED, FAILED_UNRECORDED }

    /**
     * One file: the download, the import, and — on a failure — its dead-letter row. The
     * download is where a never-read failure comes from; a failure after it is a read one.
     * RECORDED means the row was written; UNRECORDED that it was not, and the caller holds the
     * checkpoint for it.
     */
    private Outcome attempt(CallContext callContext, ImportProfileDefinition profile, ConnectorDefinition connector,
                            BoxConnectorAdapter box, BoxConnectorAdapter.BoxFile file, List<String> errors) {
        // Built before the download so it is in scope for the catch and can be
        // dead-lettered if the download fails before execute().
        ExternalIngestRequest req = requestFor(profile, connector, file);
        InputStream content = null;
        try {
            try {
                content = box.downloadFile(file.id());
            } catch (Exception downloadFailed) {
                // The source item was never read: the download is where this arm's failures
                // come from. A never-read row is replayed by fetching the bytes again — the
                // DLQ controller does that for this connector; it does not import the row's
                // metadata as an empty document.
                FetchSupport.addError(errors, "Box file " + file.id() + ": " + downloadFailed.getMessage());
                return fetchSupport.saveSourceNeverReadToDlq(req, "Box file " + file.id() + ": " + downloadFailed.getMessage())
                        ? Outcome.FAILED_RECORDED : Outcome.FAILED_UNRECORDED;
            }
            req.setContentStream(content);

            ExternalIngestResult result = canonicalImportService.execute(callContext, req);
            // skipped() first: a skipped result also reports isSuccess()==true (no errors).
            if (result.skipped()) return Outcome.SKIPPED;
            if (result.isSuccess()) return Outcome.IMPORTED;
            FetchSupport.addError(errors, "Box " + file.id() + ": " + String.join(", ", result.errors()));
            // The import ran and answered that it did not import. Recorded, or the checkpoint —
            // moved by a newer file that settles — would pass this one with nothing saying so
            // (review, P1). The row is the same one execute()'s own net writes for this
            // request, updated in place.
            return fetchSupport.saveSourceReadToDlq(req, "Box " + file.id() + ": " + String.join(", ", result.errors()))
                    ? Outcome.FAILED_RECORDED : Outcome.FAILED_UNRECORDED;
        } catch (Exception e) {
            // The download succeeded and the import threw: the source item WAS read, so the
            // row records a miss, not a never-read item — a never-read row replayed as
            // "nothing to import" would have been taken for a resolution (review, P1).
            // execute()'s own net may have written this row already (same id, updated in
            // place); its answer here is what decides whether the failure is recorded.
            FetchSupport.addError(errors, "Box file " + file.id() + ": " + e.getMessage());
            return fetchSupport.saveSourceReadToDlq(req, "Box file " + file.id() + ": " + e.getMessage())
                    ? Outcome.FAILED_RECORDED : Outcome.FAILED_UNRECORDED;
        } finally {
            if (content != null) try { content.close(); } catch (Exception ignored) {}
        }
    }

    /** The import request for one listed file — also the dead-letter key when it fails. */
    private static ExternalIngestRequest requestFor(ImportProfileDefinition profile, ConnectorDefinition connector,
                                                    BoxConnectorAdapter.BoxFile file) {
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId(profile.getProfileId());
        req.setConnectorId(connector.getConnectorId());
        req.setRepositoryId(profile.getRepositoryId());
        req.setSourceObjectId(file.id());
        req.setSourceObjectType("file");
        req.setFileName(file.name());
        req.setMimeType(FetchSupport.guessMimeType(file.name()));
        req.setExecutionMode("scheduled");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("boxFileId", file.id());
        metadata.put("boxParentId", file.parentId());
        req.setMetadata(metadata);
        return req;
    }

    @Override
    public String sourceSystem() { return "box"; }

    @Override
    public FetchResult execute(CallContext callContext, ImportProfileDefinition profile,
                               ConnectorDefinition connector, Map<String, String> params, int limit) {
        String folderId = params.getOrDefault("folderId", "0");

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
        if (token == null) return new FetchResult(0, 0, List.of("No token for Box connector"));

        int maxRequests;
        int lagMinutes;
        try {
            maxRequests = intParam(params, PARAM_MAX_LIST_REQUESTS, BoxConnectorAdapter.DEFAULT_MAX_LIST_REQUESTS, 1, MAX_LIST_REQUESTS);
            lagMinutes = intParam(params, PARAM_CHECKPOINT_LAG_MINUTES, DEFAULT_CHECKPOINT_LAG_MINUTES, 0, MAX_CHECKPOINT_LAG_MINUTES);
        } catch (IllegalArgumentException badParameter) {
            // Not a connector failure and not the default either: guessing the default would
            // silently ignore what the operator wrote. Reported, and nothing is read.
            return new FetchResult(0, 0, List.of(badParameter.getMessage()));
        }

        List<String> errors = new ArrayList<>();
        List<String> incompleteReads = new ArrayList<>();
        int fetched = 0, imported = 0, skipped = 0;
        try {
            var box = adapterFactory.apply(token);
            String stored = checkpointManager.loadSimpleCheckpoint(profile.getProfileId(), "box." + folderId);
            jp.aegif.nemaki.rest.ingest.WatermarkCheckpoint parsed =
                    jp.aegif.nemaki.rest.ingest.WatermarkCheckpoint.parse(stored);
            // Checkpoints written before this batch carried the source's own timestamp string;
            // every comparison below is on the canonical form. A stored checkpoint that cannot
            // be read is NOT "no checkpoint": read as none it would silently re-offer the whole
            // folder, and nobody would learn that the stored value is broken (review, P2).
            jp.aegif.nemaki.rest.ingest.WatermarkCheckpoint checkpoint = parsed;
            if (parsed.at() != null) {
                String at = jp.aegif.nemaki.rest.ingest.WatermarkCheckpoint.canonical(parsed.at());
                if (at == null) {
                    FetchSupport.addError(errors, "Box checkpoint '" + stored + "' for folder " + folderId
                            + " is not a timestamp this connector can read; correct or clear it — nothing was read");
                    return new FetchResult(0, 0, 0, errors);
                }
                checkpoint = new jp.aegif.nemaki.rest.ingest.WatermarkCheckpoint(at, parsed.idsAt());
            }
            // The WHOLE folder (R107). A listing stopped at the per-run limit was the first N
            // names — Box does not list by modification time — so every file after them
            // was never listed on any poll, and the checkpoint raised from the files that were
            // seen excluded any of them modified earlier for ever.
            java.time.Instant listingStartedAt = clock.instant();
            // The cap: nothing modified within `lagMinutes` of the listing's start is named,
            // so a file added or moved in while the pages were being read — at a position the
            // marker had passed — is still above the checkpoint next time (review, P1). Marks
            // above the cap are simply not handed to the checkpoint: those files are offered
            // again next poll and the import service's dedupe answers for them.
            String cap;
            try {
                cap = jp.aegif.nemaki.rest.ingest.WatermarkCheckpoint.canonical(
                        listingStartedAt.minus(lagMinutes, java.time.temporal.ChronoUnit.MINUTES));
            } catch (IllegalStateException clock) {
                // The host's clock, not the connector: said so rather than "connection failed"
                // (review, P3). The scheduler still counts it towards the connector's breaker —
                // a host whose clock is outside the four-digit years should not be polling.
                FetchSupport.addError(errors, "Box: " + clock.getMessage() + "; nothing was read");
                return new FetchResult(0, 0, 0, errors);
            }
            // A checkpoint ABOVE this run's cap — written before this batch, or before the
            // allowance was raised — covers nothing above the cap: a file modified within the
            // allowance of an earlier listing may still have been missing from it, and the
            // position that listing wrote must not pass it (review, P1). The stored position is
            // not lowered; it is not trusted above the cap until the cap passes it.
            jp.aegif.nemaki.rest.ingest.WatermarkCheckpoint effective =
                    checkpoint.at() != null && checkpoint.at().compareTo(cap) > 0
                            ? new jp.aegif.nemaki.rest.ingest.WatermarkCheckpoint(cap, java.util.Set.of())
                            : checkpoint;
            BoxConnectorAdapter.FileListing listing = box.listAllFiles(folderId, maxRequests);
            fetched = listing.files().size();
            // Every file's modification time in the canonical form. A file whose time is
            // missing or cannot be read cannot be placed against the checkpoint — it can
            // neither be skipped nor named. It is not a malformed page (Box's schema does not
            // make the field required — review, P2), and a dead-letter row for it was worse
            // than nothing: a never-read row replayed through the plain import created an
            // EMPTY document and deleted the row (review, P1). Such a file is imported on
            // every poll — the import service's dedupe answers after the first — never named,
            // and handled AFTER the files that can be placed, with its own bound, so that it
            // never takes their budget (review, P2). The run says so and is PARTIAL, not failed:
            // an error on every poll would open the connector's breaker for every profile.
            int unrecordedFailures = 0;
            Map<String, String> modifiedAt = new java.util.HashMap<>();
            List<BoxConnectorAdapter.BoxFile> placeable = new ArrayList<>();
            List<BoxConnectorAdapter.BoxFile> unplaceable = new ArrayList<>();
            for (var file : listing.files()) {
                String at = jp.aegif.nemaki.rest.ingest.WatermarkCheckpoint.canonical(file.modifiedAt());
                if (at == null) {
                    unplaceable.add(file);
                    continue;
                }
                modifiedAt.put(file.id(), at);
                placeable.add(file);
            }
            List<BoxConnectorAdapter.BoxFile> candidates = new ArrayList<>();
            for (var file : placeable) {
                if (effective.covers(modifiedAt.get(file.id()), file.id())) {
                    skipped++;
                    continue;
                }
                candidates.add(file);
            }
            // Oldest first, so that what a budget leaves for the next poll is always NEWER than
            // what it took and the checkpoint can move without passing it.
            candidates.sort(java.util.Comparator.comparing((BoxConnectorAdapter.BoxFile f) -> modifiedAt.get(f.id()))
                    .thenComparing(BoxConnectorAdapter.BoxFile::id));

            List<BoxConnectorAdapter.BoxFile> candidatesThisRun;
            if (!listing.complete()) {
                // NOT an error — nothing failed, and putting it in `errors` would have the
                // scheduler count a large folder towards the connector's circuit breaker on
                // every poll. Recorded so the job says PARTIAL rather than COMPLETED — and
                // nothing is imported: the files this poll was not shown are not any
                // particular subset of the folder, so a checkpoint raised over what it was
                // shown would exclude the unseen ones from every later poll (R107).
                incompleteReads.add("Box folder listing: " + listing.truncatedBecause()
                        + " — nothing was imported and the checkpoint holds, because which files "
                        + "were left out cannot be told");
                candidatesThisRun = List.of();
            } else {
                candidatesThisRun = candidates;
            }
            long throttleMs = FetchSupport.calculateThrottleDelayMs(connector);

            // The files whose import SETTLED — succeeded, or was skipped by the import service.
            // A file that failed is dead-lettered inside the loop, does not hold the checkpoint
            // back, and is not named by it either. The budget counts settled files; attempts
            // are bounded at ATTEMPTS_PER_BUDGET × limit.
            List<jp.aegif.nemaki.rest.ingest.WatermarkCheckpoint.Mark> settled = new ArrayList<>();
            int attempted = 0;
            boolean attemptsExhausted = false;
            for (var file : candidatesThisRun) {
                if (settled.size() >= limit) {
                    break;
                }
                if (attempted >= limit * ATTEMPTS_PER_BUDGET) {
                    attemptsExhausted = true;
                    break;
                }
                attempted++;
                fetchSupport.throttle(throttleMs);
                switch (attempt(callContext, profile, connector, box, file, errors)) {
                    case IMPORTED -> {
                        imported++;
                        settled.add(new jp.aegif.nemaki.rest.ingest.WatermarkCheckpoint.Mark(modifiedAt.get(file.id()), file.id()));
                    }
                    case SKIPPED -> {
                        skipped++;
                        settled.add(new jp.aegif.nemaki.rest.ingest.WatermarkCheckpoint.Mark(modifiedAt.get(file.id()), file.id()));
                    }
                    case FAILED_RECORDED -> { }
                    case FAILED_UNRECORDED -> unrecordedFailures++;
                }
            }
            // The files that cannot be placed (see above): after the placeable ones, with
            // their own bound, never named. Not when the listing was cut — then nothing is.
            // The bound counts imports and failures, not the import service's skips: a skip
            // must not use up the tries, or the files behind the first `limit × 4` already-
            // imported ones would never be reached (review, P1). Each offer is a download —
            // the cost the notes state. Failures DO count, so `limit × 4` files that keep
            // failing leave the ones behind them untried — said so below, not claimed away
            // (review, P2).
            int unplaceableImported = 0, unplaceableTried = 0, unplaceableUntried = 0;
            for (var file : listing.complete() ? unplaceable : List.<BoxConnectorAdapter.BoxFile>of()) {
                if (unplaceableImported >= limit || unplaceableTried >= limit * ATTEMPTS_PER_BUDGET) {
                    unplaceableUntried++;
                    continue;
                }
                fetchSupport.throttle(throttleMs);
                switch (attempt(callContext, profile, connector, box, file, errors)) {
                    case IMPORTED -> { imported++; unplaceableImported++; unplaceableTried++; }
                    case SKIPPED -> skipped++;
                    case FAILED_RECORDED -> unplaceableTried++;
                    case FAILED_UNRECORDED -> { unplaceableTried++; unrecordedFailures++; }
                }
            }
            // Not on a cut listing: nothing was offered then, and the cut's own note says so
            // (review, P3 — the note claimed an import that did not happen).
            if (!unplaceable.isEmpty() && listing.complete()) {
                incompleteReads.add("Box folder listing: " + unplaceable.size() + " file(s) have no modification time "
                        + "this connector can read (" + unplaceable.stream().limit(5).map(BoxConnectorAdapter.BoxFile::id)
                                .collect(java.util.stream.Collectors.joining(", "))
                        + (unplaceable.size() > 5 ? ", …" : "") + ") — offered on every poll (up to the run's limit "
                        + "imported and " + ATTEMPTS_PER_BUDGET + " × the limit tried at a time, the import service's dedupe "
                        + "answering for the ones already imported) and never named by the checkpoint"
                        + (unplaceableUntried > 0
                                ? "; " + unplaceableUntried + " of them were left untried this run because the "
                                        + "bound was reached — the failures are in the dead-letter queue, and the "
                                        + "files behind them are reached only once they settle or with a higher limit"
                                : ""));
            }
            int leftForTheNextPoll = candidatesThisRun.size() - attempted;
            if (leftForTheNextPoll > 0) {
                incompleteReads.add(attemptsExhausted
                        ? "Box folder listing: " + attempted + " file(s) were attempted ("
                                + ATTEMPTS_PER_BUDGET + " × the limit of " + limit + ") with only "
                                + settled.size() + " settled, leaving " + leftForTheNextPoll
                                + " newer file(s) untried — the failures are in the dead-letter queue; "
                                + "the files behind them are reached only once they settle or the "
                                + "checkpoint passes them, or with a higher limit"
                        : "Box folder listing: the run's limit of " + limit
                                + " file(s) was reached with " + leftForTheNextPoll
                                + " newer file(s) left for the next poll");
            }
            // HOW FAR the checkpoint may move: to the newest timestamp a settled file carried,
            // naming every settled id at that timestamp, so a budget that stopped inside a
            // group of files sharing a timestamp leaves the rest for the next poll rather than
            // excluding them (R59 / R107).
            // A failure whose dead-letter row could not be written is a file nothing records.
            // Moving the checkpoint past it would lose it silently, so the checkpoint holds
            // for this run and the run says why (the usual cause is the configuration store
            // being unreachable — the same store the checkpoint goes into).
            if (unrecordedFailures > 0) {
                FetchSupport.addError(errors, unrecordedFailures + " Box failure(s) could not be dead-lettered; "
                        + "the checkpoint holds so that they are offered again");
            }
            // Only the marks at or below the cap are handed to the checkpoint (see `cap` above).
            List<jp.aegif.nemaki.rest.ingest.WatermarkCheckpoint.Mark> nameable = new ArrayList<>();
            for (var mark : settled) {
                if (mark.at().compareTo(cap) <= 0) nameable.add(mark);
            }
            // Saved only when the POSITION moved: a legacy checkpoint read in the source's own
            // timestamp form encodes differently after normalisation without having moved.
            String before = checkpoint.encode();
            String next = unrecordedFailures > 0 ? null : checkpoint.after(nameable).encode();
            if (next != null && !next.equals(before)) {
                checkpointManager.saveSimpleCheckpoint(profile.getProfileId(), "box." + folderId, next);
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
            FetchSupport.addError(errors, "Box connection failed: " + e.getMessage());
        }
        return new FetchResult(fetched, imported, skipped, errors, List.copyOf(incompleteReads));
    }
}
