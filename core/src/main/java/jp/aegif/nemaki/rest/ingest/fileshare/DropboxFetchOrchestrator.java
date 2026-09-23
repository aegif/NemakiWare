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
        try {
            maxRequests = intParam(params, PARAM_MAX_LIST_REQUESTS, DropboxConnectorAdapter.DEFAULT_MAX_LIST_REQUESTS, 1, MAX_LIST_REQUESTS);
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
            jp.aegif.nemaki.rest.ingest.WatermarkCheckpoint checkpoint =
                    jp.aegif.nemaki.rest.ingest.WatermarkCheckpoint.parse(stored);
            // The WHOLE folder (R107). A listing stopped at the per-run limit was the first N
            // names — Dropbox does not list by modification time — so every file after them
            // was never listed on any poll, and the checkpoint raised from the files that were
            // seen excluded any of them modified earlier for ever.
            DropboxConnectorAdapter.FileListing listing = dropbox.listAllFiles(folderPath, maxRequests);
            fetched = listing.files().size();
            List<DropboxConnectorAdapter.DropboxFile> candidates = new ArrayList<>();
            for (var file : listing.files()) {
                if (checkpoint.covers(file.serverModified(), file.id())) {
                    skipped++;
                    continue;
                }
                candidates.add(file);
            }
            // Oldest first, so that what a budget leaves for the next poll is always NEWER than
            // what it took and the checkpoint can move without passing it.
            candidates.sort(java.util.Comparator.comparing((DropboxConnectorAdapter.DropboxFile f) -> f.serverModified(),
                            java.util.Comparator.nullsLast(java.util.Comparator.<String>naturalOrder()))
                    .thenComparing(f -> f.id(), java.util.Comparator.nullsLast(java.util.Comparator.<String>naturalOrder())));

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
                    content = dropbox.downloadFile(file.pathDisplay());
                    req.setContentStream(content);

                    ExternalIngestResult result = canonicalImportService.execute(callContext, req);
                    if (result.isSuccess() || result.skipped()) {
                        // skipped() first: a skipped result also reports
                        // isSuccess()==true (no errors), so it would be
                        // miscounted as imported otherwise.
                        if (result.skipped()) skipped++; else imported++;
                        settled.add(new jp.aegif.nemaki.rest.ingest.WatermarkCheckpoint.Mark(file.serverModified(), file.id()));
                    } else {
                        FetchSupport.addError(errors, "Dropbox " + file.id() + ": " + String.join(", ", result.errors()));
                    }
                } catch (Exception e) {
                    // Download/processing failed before execute()'s own DLQ net —
                    // DLQ so the advancing checkpoint does not silently lose it.
                    FetchSupport.addError(errors, "Dropbox file " + file.id() + ": " + e.getMessage());
                    fetchSupport.saveToDlq(req, "Dropbox file " + file.id() + ": " + e.getMessage(), null);
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
            String next = checkpoint.after(settled).encode();
            if (next != null && !next.equals(stored)) {
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
