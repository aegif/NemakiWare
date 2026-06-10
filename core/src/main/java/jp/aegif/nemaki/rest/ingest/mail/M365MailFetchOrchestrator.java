package jp.aegif.nemaki.rest.ingest.mail;

import jp.aegif.nemaki.rest.ingest.*;
import org.apache.chemistry.opencmis.commons.server.CallContext;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fetch orchestrator for Microsoft 365 Mail adapter.
 */
public class M365MailFetchOrchestrator implements FetchOrchestrator {

    private FetchSupport fetchSupport;
    private CheckpointManager checkpointManager;
    private CanonicalImportService canonicalImportService;

    public void setFetchSupport(FetchSupport fs) { this.fetchSupport = fs; }
    public void setCheckpointManager(CheckpointManager cm) { this.checkpointManager = cm; }
    public void setCanonicalImportService(CanonicalImportService cis) { this.canonicalImportService = cis; }

    @Override public String sourceSystem() { return "m365_mail"; }

    @Override
    public FetchResult execute(CallContext callContext, ImportProfileDefinition profile,
                               ConnectorDefinition connector, Map<String, String> params, int limit) {
        String folderId = params.getOrDefault("folderId", "inbox");

        String token = fetchSupport.resolvePassword(connector);
        if (token == null) return new FetchResult(0, 0, List.of("No access token for M365 Mail connector"));

        List<String> errors = new ArrayList<>();
        int fetched = 0, imported = 0, skipped = 0;
        try {
            Map<String, String> sp = profile.getSchedulerParams();
            String userId = (sp != null) ? sp.get("userId") : null;
            var m365 = new M365MailConnectorAdapter(token, userId);
            String mailboxScope = folderId;
            String lastDateTime = checkpointManager.loadSimpleCheckpoint(profile.getProfileId(), "m365mail." + mailboxScope);
            String filter = lastDateTime != null ? "receivedDateTime gt " + lastDateTime : null;
            var messages = m365.listMessages(mailboxScope, limit, filter);
            fetched = messages.size();
            long throttleMs = FetchSupport.calculateThrottleDelayMs(connector);
            String highWaterDateTime = lastDateTime;

            for (var msg : messages) {
                fetchSupport.throttle(throttleMs);
                try {
                    InputStream eml = m365.fetchMimeMessage(msg.id());
                    ExternalIngestRequest req = fetchSupport.buildMailRequest(profile, connector, msg.id(), msg.subject(), eml, folderId);
                    if (msg.internetMessageId() != null) req.getMetadata().put("internetMessageId", msg.internetMessageId());

                    ExternalIngestResult result = canonicalImportService.executeMailImport(callContext, req);
                    boolean hasAttachmentWarning = result.warnings() != null
                            && result.warnings().stream().anyMatch(w -> w.contains("Attachment") || w.contains("attachment"));
                    if ((result.isSuccess() && !hasAttachmentWarning) || result.skipped()) {
                        if (msg.receivedDateTime() != null
                                && (highWaterDateTime == null || msg.receivedDateTime().compareTo(highWaterDateTime) > 0)) {
                            highWaterDateTime = msg.receivedDateTime();
                        }
                        // skipped() first: a skipped result also reports isSuccess()==true
                        // (no errors), so it would be miscounted as imported otherwise.
                        if (result.skipped()) skipped++; else imported++;
                    } else {
                        FetchSupport.addError(errors, "M365 " + msg.id() + ": " + String.join(", ", result.errors()));
                    }
                } catch (Exception e) {
                    FetchSupport.addError(errors, "M365 " + msg.id() + ": " + e.getMessage());
                }
            }
            if (highWaterDateTime != null && !highWaterDateTime.equals(lastDateTime)) {
                checkpointManager.saveSimpleCheckpoint(profile.getProfileId(), "m365mail." + mailboxScope, highWaterDateTime);
            }
        } catch (Exception e) {
            FetchSupport.addError(errors, "M365 Mail connection failed: " + e.getMessage());
        }
        return new FetchResult(fetched, imported, skipped, errors);
    }
}
