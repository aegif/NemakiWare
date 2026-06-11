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

    @Override
    public String sourceSystem() { return "dropbox"; }

    @Override
    public FetchResult execute(CallContext callContext, ImportProfileDefinition profile,
                               ConnectorDefinition connector, Map<String, String> params, int limit) {
        String folderPath = params.getOrDefault("folderPath", "");

        String token = fetchSupport.resolvePassword(connector);
        if (token == null) return new FetchResult(0, 0, List.of("No token for Dropbox connector"));

        List<String> errors = new ArrayList<>();
        int fetched = 0, imported = 0, skipped = 0;
        try {
            var dropbox = new DropboxConnectorAdapter(token);
            String lastModified = checkpointManager.loadSimpleCheckpoint(profile.getProfileId(), "dropbox");
            var files = dropbox.listFiles(folderPath, limit);
            fetched = files.size();
            long throttleMs = FetchSupport.calculateThrottleDelayMs(connector);
            String highWaterModified = lastModified;

            for (var file : files) {
                fetchSupport.throttle(throttleMs);
                if (lastModified != null && file.serverModified() != null
                        && file.serverModified().compareTo(lastModified) <= 0) { skipped++; continue; }

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
                        if (file.serverModified() != null
                                && (highWaterModified == null || file.serverModified().compareTo(highWaterModified) > 0)) {
                            highWaterModified = file.serverModified();
                        }
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
            if (highWaterModified != null && !highWaterModified.equals(lastModified)) {
                checkpointManager.saveSimpleCheckpoint(profile.getProfileId(), "dropbox", highWaterModified);
            }
        } catch (Exception e) {
            FetchSupport.addError(errors, "Dropbox connection failed: " + e.getMessage());
        }
        return new FetchResult(fetched, imported, skipped, errors);
    }
}
