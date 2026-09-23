package jp.aegif.nemaki.rest.ingest.fileshare;

import jp.aegif.nemaki.rest.ingest.*;
import org.apache.chemistry.opencmis.commons.server.CallContext;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetch orchestrator for Dropbox file-share adapter.
 */
public class DropboxFetchOrchestrator implements FetchOrchestrator {

    private FetchSupport fetchSupport;
    private CheckpointManager checkpointManager;
    private CanonicalImportService canonicalImportService;

    public void setFetchSupport(FetchSupport fetchSupport) { this.fetchSupport = fetchSupport; }
    public void setCheckpointManager(CheckpointManager checkpointManager) { this.checkpointManager = checkpointManager; }
    public void setCanonicalImportService(CanonicalImportService canonicalImportService) { this.canonicalImportService = canonicalImportService; }

    /**
     * How this orchestrator obtains its adapter (R107). It used to be {@code new DropboxConnectorAdapter(token)}
     * inline, which made every arm below reachable only by talking to Dropbox; tests point the
     * REAL adapter at a local stub of the API through this factory (the shape
     * {@code NotionFetchOrchestrator} and {@code ImapIdleMonitor} use).
     */
    java.util.function.Function<String, DropboxConnectorAdapter> adapterFactory = DropboxConnectorAdapter::new;

    /** Scheduler parameter: how many listing requests one poll may make. */
    static final String PARAM_MAX_LIST_REQUESTS = "dropboxListMaxRequests";
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
    static final String PARAM_CHECKPOINT_LAG_MINUTES = "dropboxCheckpointLagMinutes";
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
        throw new IllegalArgumentException("Dropbox connector parameter " + name
                + " must be an integer between " + minimum + " and " + maximum + ", not '" + raw + "'");
    }

    @Override
    public String sourceSystem() { return "dropbox"; }

    @Override
    public FetchResult execute(CallContext callContext, ImportProfileDefinition profile,
                               ConnectorDefinition connector, Map<String, String> params, int limit) {
        String folderPath = params.getOrDefault("folderPath", "");

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
        if (token == null) return new FetchResult(0, 0, List.of("No token for Dropbox connector"));

        int maxRequests;
        int lagMinutes;
        try {
            maxRequests = intParam(params, PARAM_MAX_LIST_REQUESTS, DropboxConnectorAdapter.DEFAULT_MAX_LIST_REQUESTS, 1, MAX_LIST_REQUESTS);
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
            var dropbox = adapterFactory.apply(token);
            String stored = checkpointManager.loadSimpleCheckpoint(profile.getProfileId(), "dropbox");
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
                    FetchSupport.addError(errors, "Dropbox checkpoint '" + stored + "' for folder '" + folderPath + "'"
                            + " is not a timestamp this connector can read; correct or clear it — nothing was read");
                    return new FetchResult(0, 0, 0, errors);
                }
                checkpoint = new jp.aegif.nemaki.rest.ingest.WatermarkCheckpoint(at, parsed.idsAt());
            }
            // The WHOLE folder (R107). A listing stopped at the per-run limit was the first N
            // names — Dropbox does not list by modification time — so every file after them
            // was never listed on any poll, and the checkpoint raised from the files that were
            // seen excluded any of them modified earlier for ever.
            java.time.Instant listingStartedAt = clock.instant();
            // The cap: nothing modified within `lagMinutes` of the listing's start is named,
            // so a file added or moved in while the pages were being read — at a position the
            // marker had passed — is still above the checkpoint next time (review, P1). Marks
            // above the cap are simply not handed to the checkpoint: those files are offered
            // again next poll and the import service's dedupe answers for them.
            String cap = jp.aegif.nemaki.rest.ingest.WatermarkCheckpoint.canonical(
                    listingStartedAt.minus(lagMinutes, java.time.temporal.ChronoUnit.MINUTES));
            // A checkpoint ABOVE this run's cap — written before this batch, or before the
            // allowance was raised — covers nothing above the cap: a file modified within the
            // allowance of an earlier listing may still have been missing from it, and the
            // position that listing wrote must not pass it (review, P1). The stored position is
            // not lowered; it is not trusted above the cap until the cap passes it.
            jp.aegif.nemaki.rest.ingest.WatermarkCheckpoint effective =
                    checkpoint.at() != null && checkpoint.at().compareTo(cap) > 0
                            ? new jp.aegif.nemaki.rest.ingest.WatermarkCheckpoint(cap, java.util.Set.of())
                            : checkpoint;
            DropboxConnectorAdapter.FileListing listing = dropbox.listAllFiles(folderPath, maxRequests);
            fetched = listing.files().size();
            // Every file's modification time in the canonical form, the listing refused if one
            // cannot be read: Dropbox writes it for every file, so one that cannot be read is a
            // malformed page — and a file that cannot be placed against the checkpoint can
            // neither be skipped nor named, so offered on every poll it would take the budget
            // from the files behind it (review, P2).
            Map<String, String> modifiedAt = new java.util.HashMap<>();
            for (var file : listing.files()) {
                String at = jp.aegif.nemaki.rest.ingest.WatermarkCheckpoint.canonical(file.serverModified());
                if (at == null) {
                    FetchSupport.addError(errors, "Dropbox listed file " + file.id()
                            + " with a modification time this connector cannot read ('" + file.serverModified()
                            + "'); nothing was imported and the checkpoint holds");
                    return new FetchResult(fetched, 0, 0, errors);
                }
                modifiedAt.put(file.id(), at);
            }
            List<DropboxConnectorAdapter.DropboxFile> candidates = new ArrayList<>();
            for (var file : listing.files()) {
                if (effective.covers(modifiedAt.get(file.id()), file.id())) {
                    skipped++;
                    continue;
                }
                candidates.add(file);
            }
            // Oldest first, so that what a budget leaves for the next poll is always NEWER than
            // what it took and the checkpoint can move without passing it.
            candidates.sort(java.util.Comparator.comparing((DropboxConnectorAdapter.DropboxFile f) -> modifiedAt.get(f.id()))
                    .thenComparing(DropboxConnectorAdapter.DropboxFile::id));

            List<DropboxConnectorAdapter.DropboxFile> candidatesThisRun;
            if (!listing.complete()) {
                // NOT an error — nothing failed, and putting it in `errors` would have the
                // scheduler count a large folder towards the connector's circuit breaker on
                // every poll. Recorded so the job says PARTIAL rather than COMPLETED — and
                // nothing is imported: the files this poll was not shown are not any
                // particular subset of the folder, so a checkpoint raised over what it was
                // shown would exclude the unseen ones from every later poll (R107).
                incompleteReads.add("Dropbox folder listing: " + listing.truncatedBecause()
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
            int unrecordedFailures = 0;
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
                // Build the request before the download so it is in scope for the
                // catch and can be DLQ-ed if the download fails before execute().
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
                metadata.put("dropboxPath", file.pathDisplay());
                metadata.put("dropboxFileId", file.id());
                req.setMetadata(metadata);

                InputStream content = null;
                try {
                    try {
                        content = dropbox.downloadFile(file.pathDisplay());
                    } catch (Exception downloadFailed) {
                        // The source item was never read: the download is where this arm's
                        // failures come from, and a never-read row is replayable by re-fetching.
                        FetchSupport.addError(errors, "Dropbox file " + file.id() + ": " + downloadFailed.getMessage());
                        if (!fetchSupport.saveSourceNeverReadToDlq(req, "Dropbox file " + file.id() + ": " + downloadFailed.getMessage())) {
                            unrecordedFailures++;
                        }
                        continue;
                    }
                    req.setContentStream(content);

                    ExternalIngestResult result = canonicalImportService.execute(callContext, req);
                    if (result.isSuccess() || result.skipped()) {
                        // skipped() first: a skipped result also reports
                        // isSuccess()==true (no errors), so it would be
                        // miscounted as imported otherwise.
                        if (result.skipped()) skipped++; else imported++;
                        settled.add(new jp.aegif.nemaki.rest.ingest.WatermarkCheckpoint.Mark(modifiedAt.get(file.id()), file.id()));
                    } else {
                        FetchSupport.addError(errors, "Dropbox " + file.id() + ": " + String.join(", ", result.errors()));
                        // The import ran and answered that it did not import. Recorded, or the
                        // checkpoint — moved by a newer file that settles — would pass this one
                        // with nothing saying so (review, P1). The row is the same one execute()'s
                        // own net writes for this request, updated in place.
                        if (!fetchSupport.saveSourceReadToDlq(req, "Dropbox " + file.id() + ": " + String.join(", ", result.errors()))) {
                            unrecordedFailures++;
                        }
                    }
                } catch (Exception e) {
                    // The download succeeded and the import threw: the source item WAS read,
                    // so the row records a miss, not a never-read item — a never-read row
                    // replayed as "nothing to import" would have been taken for a resolution
                    // (review, P1). execute()'s own net may have written this row already
                    // (same id, updated in place); its answer here is what decides whether the
                    // failure is recorded.
                    FetchSupport.addError(errors, "Dropbox file " + file.id() + ": " + e.getMessage());
                    if (!fetchSupport.saveSourceReadToDlq(req, "Dropbox file " + file.id() + ": " + e.getMessage())) {
                        unrecordedFailures++;
                    }
                } finally {
                    if (content != null) try { content.close(); } catch (Exception ignored) {}
                }
            }
            int leftForTheNextPoll = candidatesThisRun.size() - attempted;
            if (leftForTheNextPoll > 0) {
                incompleteReads.add(attemptsExhausted
                        ? "Dropbox folder listing: " + attempted + " file(s) were attempted ("
                                + ATTEMPTS_PER_BUDGET + " × the limit of " + limit + ") with only "
                                + settled.size() + " settled, leaving " + leftForTheNextPoll
                                + " newer file(s) untried — the failures are in the dead-letter queue; "
                                + "the files behind them are reached only once they settle or the "
                                + "checkpoint passes them, or with a higher limit"
                        : "Dropbox folder listing: the run's limit of " + limit
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
                FetchSupport.addError(errors, unrecordedFailures + " Dropbox failure(s) could not be dead-lettered; "
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
                checkpointManager.saveSimpleCheckpoint(profile.getProfileId(), "dropbox", next);
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
            FetchSupport.addError(errors, "Dropbox connection failed: " + e.getMessage());
        }
        return new FetchResult(fetched, imported, skipped, errors, List.copyOf(incompleteReads));
    }
}
