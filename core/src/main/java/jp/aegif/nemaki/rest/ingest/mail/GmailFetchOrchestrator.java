package jp.aegif.nemaki.rest.ingest.mail;

import jp.aegif.nemaki.rest.ingest.*;
import org.apache.chemistry.opencmis.commons.server.CallContext;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fetch orchestrator for Gmail mail adapter.
 */
public class GmailFetchOrchestrator implements FetchOrchestrator {

    private FetchSupport fetchSupport;
    private CheckpointManager checkpointManager;
    private CanonicalImportService canonicalImportService;

    public void setFetchSupport(FetchSupport fs) { this.fetchSupport = fs; }
    public void setCheckpointManager(CheckpointManager cm) { this.checkpointManager = cm; }
    public void setCanonicalImportService(CanonicalImportService cis) { this.canonicalImportService = cis; }

    @Override public String sourceSystem() { return "gmail_mail"; }

    @Override
    public FetchResult execute(CallContext callContext, ImportProfileDefinition profile,
                               ConnectorDefinition connector, Map<String, String> params, int limit) {
        String query = params.getOrDefault("query", "in:inbox is:unread");

        String token = fetchSupport.resolvePassword(connector);
        if (token == null) return new FetchResult(0, 0, List.of("No access token for Gmail connector"));

        List<String> errors = new ArrayList<>();
        int fetched = 0, imported = 0, skipped = 0;
        try {
            var gmail = new GmailConnectorAdapter(token);
            String lastDate = checkpointManager.loadSimpleCheckpoint(profile.getProfileId(), "gmail");
            String effectiveQuery = query;
            if (lastDate != null) {
                effectiveQuery = query + " after:" + lastDate;
            }
            var messages = gmail.listMessages(effectiveQuery, limit);
            fetched = messages.size();
            long throttleMs = FetchSupport.calculateThrottleDelayMs(connector);
            long highWaterEpochMs = 0;
            if (lastDate != null) {
                try {
                    highWaterEpochMs = java.time.LocalDate.parse(lastDate,
                            java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"))
                            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
                } catch (Exception e) { /* reset to 0 */ }
            }

            for (var msg : messages) {
                fetchSupport.throttle(throttleMs);
                try {
                    InputStream eml = gmail.fetchRawMessage(msg.id());
                    ExternalIngestRequest req = fetchSupport.buildMailRequest(profile, connector, msg.id(), msg.subject(), eml, "inbox");
                    req.getMetadata().put("internetMessageId", msg.id());
                    req.getMetadata().put("gmailThreadId", msg.threadId());

                    ExternalIngestResult result = canonicalImportService.executeMailImport(callContext, req);
                    boolean hasAttachmentWarning = result.warnings() != null
                            && result.warnings().stream().anyMatch(w -> w.contains("Attachment") || w.contains("attachment"));
                    if ((result.isSuccess() && !hasAttachmentWarning) || result.skipped()) {
                        if (msg.internalDate() > highWaterEpochMs) highWaterEpochMs = msg.internalDate();
                        if (result.isSuccess()) imported++; else skipped++;
                    } else {
                        FetchSupport.addError(errors, "Gmail " + msg.id() + ": " + String.join(", ", result.errors()));
                    }
                } catch (Exception e) {
                    FetchSupport.addError(errors, "Gmail " + msg.id() + ": " + e.getMessage());
                }
            }
            if (highWaterEpochMs > 0) {
                String newDate = java.time.Instant.ofEpochMilli(highWaterEpochMs)
                        .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"));
                if (lastDate == null || !newDate.equals(lastDate)) {
                    checkpointManager.saveSimpleCheckpoint(profile.getProfileId(), "gmail", newDate);
                }
            }
        } catch (Exception e) {
            FetchSupport.addError(errors, "Gmail connection failed: " + e.getMessage());
        }
        return new FetchResult(fetched, imported, skipped, errors);
    }
}
