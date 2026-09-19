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
                        if (page.lastEditedTime() != null
                                && (highWaterEditedTime == null || page.lastEditedTime().compareTo(highWaterEditedTime) > 0)) {
                            highWaterEditedTime = page.lastEditedTime();
                        }
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
            if (highWaterEditedTime != null && !highWaterEditedTime.equals(lastEditedCheckpoint)) {
                checkpointManager.saveSimpleCheckpoint(profile.getProfileId(), "notion", highWaterEditedTime);
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
        return new FetchResult(fetched, imported, skipped, errors);
    }
}
