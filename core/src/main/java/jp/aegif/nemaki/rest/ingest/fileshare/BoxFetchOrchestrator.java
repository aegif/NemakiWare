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

    @Override
    public String sourceSystem() { return "box"; }

    @Override
    public FetchResult execute(CallContext callContext, ImportProfileDefinition profile,
                               ConnectorDefinition connector, Map<String, String> params, int limit) {
        String folderId = params.getOrDefault("folderId", "0");

        String token = fetchSupport.resolvePassword(connector);
        if (token == null) return new FetchResult(0, 0, List.of("No token for Box connector"));

        List<String> errors = new ArrayList<>();
        int fetched = 0, imported = 0, skipped = 0;
        try {
            var box = new BoxConnectorAdapter(token);
            String lastModified = checkpointManager.loadSimpleCheckpoint(profile.getProfileId(), "box." + folderId);
            var files = box.listFiles(folderId, limit);
            fetched = files.size();
            long throttleMs = FetchSupport.calculateThrottleDelayMs(connector);
            String highWaterModified = lastModified;

            for (var file : files) {
                fetchSupport.throttle(throttleMs);
                // Skip files not modified since checkpoint
                if (lastModified != null && file.modifiedAt() != null
                        && file.modifiedAt().compareTo(lastModified) <= 0) { skipped++; continue; }

                InputStream content = null;
                try {
                    content = box.downloadFile(file.id());
                    ExternalIngestRequest req = new ExternalIngestRequest();
                    req.setProfileId(profile.getProfileId());
                    req.setConnectorId(connector.getConnectorId());
                    req.setRepositoryId(profile.getRepositoryId());
                    req.setSourceObjectId(file.id());
                    req.setSourceObjectType("file");
                    req.setFileName(file.name());
                    req.setMimeType(FetchSupport.guessMimeType(file.name()));
                    req.setContentStream(content);
                    req.setExecutionMode("scheduled");
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("boxFileId", file.id());
                    metadata.put("boxParentId", file.parentId());
                    req.setMetadata(metadata);

                    ExternalIngestResult result = canonicalImportService.execute(callContext, req);
                    if (result.isSuccess() || result.skipped()) {
                        // skipped() first: a skipped result also reports
                        // isSuccess()==true (no errors), so it would be
                        // miscounted as imported otherwise.
                        if (result.skipped()) skipped++; else imported++;
                        if (file.modifiedAt() != null
                                && (highWaterModified == null || file.modifiedAt().compareTo(highWaterModified) > 0)) {
                            highWaterModified = file.modifiedAt();
                        }
                    } else {
                        FetchSupport.addError(errors, "Box " + file.id() + ": " + String.join(", ", result.errors()));
                    }
                } catch (Exception e) {
                    FetchSupport.addError(errors, "Box file " + file.id() + ": " + e.getMessage());
                } finally {
                    if (content != null) try { content.close(); } catch (Exception ignored) {}
                }
            }
            if (highWaterModified != null && !highWaterModified.equals(lastModified)) {
                checkpointManager.saveSimpleCheckpoint(profile.getProfileId(), "box." + folderId, highWaterModified);
            }
        } catch (Exception e) {
            FetchSupport.addError(errors, "Box connection failed: " + e.getMessage());
        }
        return new FetchResult(fetched, imported, skipped, errors);
    }
}
