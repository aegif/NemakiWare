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
                ExternalIngestRequest req = null;
                try {
                    // Built BEFORE the fetch, with a null stream, so a remote failure —
                    // the likeliest per-item failure — still records an entry that names
                    // the item. Previously the request was built after the fetch, so that
                    // exact case left nothing behind (external review).
                    req = fetchSupport.buildMailRequest(
                            profile, connector, msg.id(), msg.subject(), null, "inbox");
                    req.setContentStream(gmail.fetchRawMessage(msg.id()));
                    req.getMetadata().put("internetMessageId", msg.id());
                    req.getMetadata().put("gmailThreadId", msg.threadId());

                    ExternalIngestResult result = canonicalImportService.executeMailImport(callContext, req);
                    boolean hasAttachmentWarning = result.warnings() != null
                            && result.warnings().stream().anyMatch(w -> w.contains("Attachment") || w.contains("attachment"));
                    if ((result.isSuccess() && !hasAttachmentWarning) || result.skipped()) {
                        if (msg.internalDate() > highWaterEpochMs) highWaterEpochMs = msg.internalDate();
                        // skipped() first: a skipped result also reports isSuccess()==true
                        // (no errors), so it would be miscounted as imported otherwise.
                        if (result.skipped()) skipped++; else imported++;
                    } else {
                        FetchSupport.addError(errors, "Gmail " + msg.id() + ": " + String.join(", ", result.errors()));
                    }
                } catch (Exception e) {
                    FetchSupport.addError(errors, "Gmail " + msg.id() + ": " + e.getMessage());
                    // DLQ before the checkpoint can move past this item: the high-water
                    // mark is overtaken by a LATER success in the same batch, and this
                    // orchestrator had no DLQ at all, so the item was never re-fetched
                    // (external review). A null request means the fetch itself failed
                    // before one could be built — there is nothing to retry from here.
                    if (req != null) {
                        fetchSupport.saveToDlq(req, "Gmail " + msg.id() + ": " + e.getMessage(), null);
                    }
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
