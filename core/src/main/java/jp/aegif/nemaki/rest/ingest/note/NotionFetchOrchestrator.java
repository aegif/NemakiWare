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

    public void setFetchSupport(FetchSupport fs) { this.fetchSupport = fs; }
    public void setCheckpointManager(CheckpointManager cm) { this.checkpointManager = cm; }
    public void setCanonicalImportService(CanonicalImportService cis) { this.canonicalImportService = cis; }

    @Override public String sourceSystem() { return "notion"; }

    @Override
    public FetchResult execute(CallContext callContext, ImportProfileDefinition profile,
                               ConnectorDefinition connector, Map<String, String> params, int limit) {
        String query = params.get("query"); // null = no filter

        String token = fetchSupport.resolvePassword(connector);
        if (token == null) return new FetchResult(0, 0, List.of("No token for Notion connector"));

        List<String> errors = new ArrayList<>();
        int fetched = 0, imported = 0, skipped = 0;
        try {
            var notion = new NotionConnectorAdapter(token);
            String lastEditedCheckpoint = checkpointManager.loadSimpleCheckpoint(profile.getProfileId(), "notion");
            List<NotionPageSummary> pages = notion.searchPages(query, limit);
            fetched = pages.size();
            String highWaterEditedTime = lastEditedCheckpoint;
            long throttleMs = FetchSupport.calculateThrottleDelayMs(connector);

            for (NotionPageSummary page : pages) {
                fetchSupport.throttle(throttleMs);
                if (lastEditedCheckpoint != null && page.lastEditedTime() != null
                        && page.lastEditedTime().compareTo(lastEditedCheckpoint) <= 0) {
                    skipped++; continue;
                }
                try {
                    String html = notion.fetchPageAsHtml(page.id());
                    ExternalIngestRequest req = new ExternalIngestRequest();
                    req.setProfileId(profile.getProfileId());
                    req.setConnectorId(connector.getConnectorId());
                    req.setRepositoryId(profile.getRepositoryId());
                    req.setSourceObjectId(page.id());
                    req.setSourceObjectType("page");
                    req.setFileName(FetchSupport.sanitizeSubject(page.title()) + ".html");
                    req.setMimeType("text/html");
                    req.setContentStream(new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
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
                        if (page.lastEditedTime() != null
                                && (highWaterEditedTime == null || page.lastEditedTime().compareTo(highWaterEditedTime) > 0)) {
                            highWaterEditedTime = page.lastEditedTime();
                        }
                    }
                    if (result.isSuccess()) imported++;
                    else if (result.skipped()) skipped++;
                    else FetchSupport.addError(errors, "Notion " + page.id() + ": " + String.join(", ", result.errors()));
                } catch (Exception e) {
                    FetchSupport.addError(errors, "Notion page " + page.id() + ": " + e.getMessage());
                }
            }
            if (highWaterEditedTime != null && !highWaterEditedTime.equals(lastEditedCheckpoint)) {
                checkpointManager.saveSimpleCheckpoint(profile.getProfileId(), "notion", highWaterEditedTime);
            }
        } catch (Exception e) {
            FetchSupport.addError(errors, "Notion connection failed: " + e.getMessage());
        }
        return new FetchResult(fetched, imported, skipped, errors);
    }
}
