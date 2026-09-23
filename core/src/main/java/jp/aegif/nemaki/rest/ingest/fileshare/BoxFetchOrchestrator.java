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
        try {
            maxRequests = intParam(params, PARAM_MAX_LIST_REQUESTS, BoxConnectorAdapter.DEFAULT_MAX_LIST_REQUESTS, 1, MAX_LIST_REQUESTS);
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
            jp.aegif.nemaki.rest.ingest.WatermarkCheckpoint checkpoint =
                    jp.aegif.nemaki.rest.ingest.WatermarkCheckpoint.parse(stored);
            // The WHOLE folder (R107). A listing stopped at the per-run limit was the first N
            // names — Box does not list by modification time — so every file after them
            // was never listed on any poll, and the checkpoint raised from the files that were
            // seen excluded any of them modified earlier for ever.
            BoxConnectorAdapter.FileListing listing = box.listAllFiles(folderId, maxRequests);
            fetched = listing.files().size();
            List<BoxConnectorAdapter.BoxFile> candidates = new ArrayList<>();
            for (var file : listing.files()) {
                if (checkpoint.covers(file.modifiedAt(), file.id())) {
                    skipped++;
                    continue;
                }
                candidates.add(file);
            }
            // Oldest first, so that what a budget leaves for the next poll is always NEWER than
            // what it took and the checkpoint can move without passing it.
            candidates.sort(java.util.Comparator.comparing((BoxConnectorAdapter.BoxFile f) -> f.modifiedAt(),
                            java.util.Comparator.nullsLast(java.util.Comparator.<String>naturalOrder()))
                    .thenComparing(f -> f.id(), java.util.Comparator.nullsLast(java.util.Comparator.<String>naturalOrder())));

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
                metadata.put("boxFileId", file.id());
                metadata.put("boxParentId", file.parentId());
                req.setMetadata(metadata);

                InputStream content = null;
                try {
                    content = box.downloadFile(file.id());
                    req.setContentStream(content);

                    ExternalIngestResult result = canonicalImportService.execute(callContext, req);
                    if (result.isSuccess() || result.skipped()) {
                        // skipped() first: a skipped result also reports
                        // isSuccess()==true (no errors), so it would be
                        // miscounted as imported otherwise.
                        if (result.skipped()) skipped++; else imported++;
                        settled.add(new jp.aegif.nemaki.rest.ingest.WatermarkCheckpoint.Mark(file.modifiedAt(), file.id()));
                    } else {
                        FetchSupport.addError(errors, "Box " + file.id() + ": " + String.join(", ", result.errors()));
                        // The import ran and answered that it did not import. Recorded, or the
                        // checkpoint — moved by a newer file that settles — would pass this one
                        // with nothing saying so (review, P1). The row is the same one execute()'s
                        // own net writes for this request, updated in place.
                        if (!fetchSupport.saveSourceReadToDlq(req, "Box " + file.id() + ": " + String.join(", ", result.errors()))) {
                            unrecordedFailures++;
                        }
                    }
                } catch (Exception e) {
                    // Download/processing failed before execute()'s own DLQ net.
                    // DLQ the item so the checkpoint advancing past it (when a
                    // newer file in this batch succeeds) does not silently lose it.
                    FetchSupport.addError(errors, "Box file " + file.id() + ": " + e.getMessage());
                    // saveSourceNeverReadToDlq answers whether the row was WRITTEN; saveToDlq
                    // swallowed that, and a failure whose record did not land would have been
                    // passed by the checkpoint as if it were recorded (review, P1).
                    if (!fetchSupport.saveSourceNeverReadToDlq(req, "Box file " + file.id() + ": " + e.getMessage())) {
                        unrecordedFailures++;
                    }
                } finally {
                    if (content != null) try { content.close(); } catch (Exception ignored) {}
                }
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
            String next = unrecordedFailures > 0 ? null : checkpoint.after(settled).encode();
            if (next != null && !next.equals(stored)) {
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
