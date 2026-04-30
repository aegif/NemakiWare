package jp.aegif.nemaki.rest.ingest.mail;

import jp.aegif.nemaki.rest.ingest.*;
import jp.aegif.nemaki.rest.ingest.mail.ImapConnectorAdapter.MessageSummary;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ImapFetchOrchestrator implements FetchOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(ImapFetchOrchestrator.class);

    private FetchSupport fetchSupport;
    private CheckpointManager checkpointManager;
    private CanonicalImportService canonicalImportService;

    public void setFetchSupport(FetchSupport fs) { this.fetchSupport = fs; }
    public void setCheckpointManager(CheckpointManager cm) { this.checkpointManager = cm; }
    public void setCanonicalImportService(CanonicalImportService cis) { this.canonicalImportService = cis; }

    @Override public String sourceSystem() { return "imap"; }

    @Override
    public FetchResult execute(CallContext callContext, ImportProfileDefinition profile,
                               ConnectorDefinition connector, Map<String, String> params, int limit) {
        String mailboxFolder = params.getOrDefault("mailbox", "INBOX");

        if (canonicalImportService == null)
            return new FetchResult(0, 0, List.of("CanonicalImportService not available"));

        String password = fetchSupport.resolvePassword(connector);
        if (password == null)
            return new FetchResult(0, 0, List.of("Could not resolve IMAP password for connector: " + connector.getConnectorId()));

        var imap = new ImapConnectorAdapter(connector, password);
        List<String> errors = new ArrayList<>();
        int fetched = 0, imported = 0, skipped = 0;

        try {
            imap.connect();
            List<MessageSummary> messages = imap.listMessages(mailboxFolder, null, limit);

            long[] checkpoint = checkpointManager.loadCheckpointWithValidity(profile.getProfileId(), mailboxFolder);
            long lastUidValidity = checkpoint[0];
            long lastImportedUid = checkpoint[1];

            long currentUidValidity = messages.isEmpty() ? 0 : messages.get(0).uidValidity();
            if (lastUidValidity > 0 && currentUidValidity > 0 && lastUidValidity != currentUidValidity) {
                logger.warn("UIDVALIDITY changed ({} → {}), resetting checkpoint for {}/{}",
                        lastUidValidity, currentUidValidity, profile.getProfileId(), mailboxFolder);
                lastImportedUid = 0;
            }

            final long filterUid = lastImportedUid;
            if (filterUid > 0) messages = messages.stream().filter(m -> m.uid() > filterUid).toList();
            fetched = messages.size();
            logger.info("IMAP fetch: {} new messages from {}:{} (checkpoint UID {}, validity {})",
                    fetched, connector.getEndpoint(), mailboxFolder, lastImportedUid, currentUidValidity);

            List<MessageSummary> oldestFirst = new ArrayList<>(messages);
            oldestFirst.sort((a, b) -> Long.compare(a.uid(), b.uid()));

            long highWaterMark = lastImportedUid;
            long throttleMs = FetchSupport.calculateThrottleDelayMs(connector);

            for (MessageSummary msg : oldestFirst) {
                fetchSupport.throttle(throttleMs);
                try {
                    InputStream eml = imap.fetchMessage(mailboxFolder, msg.uid());
                    ExternalIngestRequest req = new ExternalIngestRequest();
                    req.setProfileId(profile.getProfileId());
                    req.setConnectorId(connector.getConnectorId());
                    req.setRepositoryId(profile.getRepositoryId());
                    req.setSourceObjectId(msg.stableKey());
                    req.setSourceObjectType("message");
                    req.setFileName(FetchSupport.sanitizeSubject(msg.subject()) + ".eml");
                    req.setMimeType("message/rfc822");
                    req.setContentStream(eml);
                    req.setExecutionMode("scheduled");

                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("mailboxId", mailboxFolder);
                    metadata.put("messageStableId", msg.stableKey());
                    metadata.put("uidValidity", String.valueOf(msg.uidValidity()));
                    if (msg.messageId() != null) metadata.put("internetMessageId", msg.messageId());
                    req.setMetadata(metadata);

                    ExternalIngestResult result = canonicalImportService.executeMailImport(callContext, req);
                    if (result.isSuccess()) {
                        boolean hasAttachmentFailure = result.warnings().stream()
                                .anyMatch(w -> w.contains("Attachment") && w.contains("failed"));
                        if (hasAttachmentFailure) {
                            logger.warn("Message UID {} imported with attachment failures, not checkpointing", msg.uid());
                            FetchSupport.addError(errors, "Message UID " + msg.uid() + " partial: " + String.join(", ", result.warnings()));
                        } else {
                            imported++;
                            highWaterMark = Math.max(highWaterMark, msg.uid());
                        }
                    } else if (result.skipped()) {
                        skipped++;
                        highWaterMark = Math.max(highWaterMark, msg.uid());
                    } else {
                        FetchSupport.addError(errors, "Message UID " + msg.uid() + ": " + String.join(", ", result.errors()));
                    }
                } catch (Exception e) {
                    FetchSupport.addError(errors, "Message UID " + msg.uid() + ": " + e.getMessage());
                }
            }

            if (highWaterMark > lastImportedUid) {
                checkpointManager.saveCheckpointWithValidity(profile.getProfileId(), mailboxFolder, currentUidValidity, highWaterMark);
            }
        } catch (Exception e) {
            FetchSupport.addError(errors, "IMAP connection failed: " + e.getMessage());
            logger.error("IMAP fetch failed for {}: {}", connector.getEndpoint(), e.getMessage());
        } finally {
            imap.disconnect();
        }

        logger.info("IMAP fetch complete: fetched={}, imported={}, errors={}", fetched, imported, errors.size());
        return new FetchResult(fetched, imported, skipped, errors);
    }
}
