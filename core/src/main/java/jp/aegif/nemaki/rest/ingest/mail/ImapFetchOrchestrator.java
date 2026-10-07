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

    /**
     * How the adapter is built. A field so a test can drive a fetch without a mail server;
     * until it existed nothing in this method had ever been measured.
     */
    java.util.function.BiFunction<ConnectorDefinition, String, ImapConnectorAdapter> adapterFactory =
            ImapConnectorAdapter::new;

    @Override public String sourceSystem() { return "imap"; }

    @Override
    public FetchResult execute(CallContext callContext, ImportProfileDefinition profile,
                               ConnectorDefinition connector, Map<String, String> params, int limit) {
        String mailboxFolder = params.getOrDefault("mailbox", "INBOX");

        if (canonicalImportService == null)
            return new FetchResult(0, 0, List.of("CanonicalImportService not available"));

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
        String password = fetchSupport.resolvePasswordOrRefuse(connector);
        if (password == null)
            return new FetchResult(0, 0, List.of("Could not resolve IMAP password for connector: " + connector.getConnectorId()));

        var imap = adapterFactory.apply(connector, password);
        List<String> errors = new ArrayList<>();
        List<String> incompleteReads = new ArrayList<>();
        int fetched = 0, imported = 0, skipped = 0;

        try {
            imap.connect();
            long[] checkpoint = checkpointManager.loadCheckpointWithValidity(profile.getProfileId(), mailboxFolder);
            long lastUidValidity = checkpoint[0];
            long lastImportedUid = checkpoint[1];

            // The messages ABOVE the checkpoint, oldest first, up to the limit. The listing
            // used to be the newest `limit` messages of the folder with the checkpoint applied
            // afterwards: more than `limit` new messages since the last run, and the oldest of
            // them were never listed — then the checkpoint moved to the newest and they never
            // would be (R107's shape; 9-6 review, P1).
            ImapConnectorAdapter.Listing listing = imap.listMessagesAfterUid(mailboxFolder, lastImportedUid, limit);
            long currentUidValidity = listing.uidValidity();
            if (lastUidValidity > 0 && currentUidValidity > 0 && lastUidValidity != currentUidValidity) {
                logger.warn("UIDVALIDITY changed ({} → {}), resetting checkpoint for {}/{}",
                        lastUidValidity, currentUidValidity, profile.getProfileId(), mailboxFolder);
                lastImportedUid = 0;
                listing = imap.listMessagesAfterUid(mailboxFolder, 0, limit);
            }
            List<MessageSummary> messages = listing.messages();
            fetched = messages.size();
            logger.info("IMAP fetch: {} new messages from {}:{} (checkpoint UID {}, validity {})",
                    fetched, connector.getEndpoint(), mailboxFolder, lastImportedUid, currentUidValidity);
            if (listing.more()) {
                incompleteReads.add("IMAP " + mailboxFolder + ": more than the run's limit of " + limit
                        + " message(s) are newer than the checkpoint (UID " + lastImportedUid + "); the oldest "
                        + fetched + " were read and the rest are left for the next run — the checkpoint stops at"
                        + " the last one read");
            }

            List<MessageSummary> oldestFirst = new ArrayList<>(messages);
            oldestFirst.sort((a, b) -> Long.compare(a.uid(), b.uid()));

            long highWaterMark = lastImportedUid;
            long throttleMs = FetchSupport.calculateThrottleDelayMs(connector);

            for (MessageSummary msg : oldestFirst) {
                fetchSupport.throttle(throttleMs);
                ExternalIngestRequest req = null;
                try {
                    // Built BEFORE the fetch so a remote failure — the likeliest per-item
                    // failure — still records an entry that names the item (external review).
                    req = new ExternalIngestRequest();
                    req.setProfileId(profile.getProfileId());
                    req.setConnectorId(connector.getConnectorId());
                    req.setRepositoryId(profile.getRepositoryId());
                    req.setSourceObjectId(msg.stableKey());
                    req.setSourceObjectType("message");
                    req.setFileName(FetchSupport.sanitizeSubject(msg.subject()) + ".eml");
                    req.setMimeType("message/rfc822");
                    req.setExecutionMode("scheduled");
                    // The identity is set first, so a failed fetch still produces an entry that
                    // NAMES the message rather than an anonymous one.
                    req.setContentStream(imap.fetchMessage(mailboxFolder, msg.uid()));

                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("mailboxId", mailboxFolder);
                    metadata.put("messageStableId", msg.stableKey());
                    metadata.put("uidValidity", String.valueOf(msg.uidValidity()));
                    if (msg.messageId() != null) metadata.put("internetMessageId", msg.messageId());
                    req.setMetadata(metadata);

                    ExternalIngestResult result = canonicalImportService.executeMailImport(callContext, req);
                    // skipped() first: a skipped result also reports isSuccess()==true
                    // (no errors), so it would be miscounted as imported otherwise.
                    if (result.skipped()) {
                        skipped++;
                        highWaterMark = Math.max(highWaterMark, msg.uid());
                    } else if (result.isSuccess()) {
                        boolean hasAttachmentFailure = result.warnings().stream()
                                .anyMatch(w -> w.contains("Attachment") && w.contains("failed"));
                        if (hasAttachmentFailure) {
                            logger.warn("Message UID {} imported with attachment failures, not checkpointing", msg.uid());
                            String why = "Message UID " + msg.uid() + " partial: " + String.join(", ", result.warnings());
                            FetchSupport.addError(errors, why);
                            // Dead-lettered like the failures below: "not checkpointing" holds
                            // only until a later message in the same run succeeds and moves the
                            // high-water mark past this one (9-6 review, P1).
                            fetchSupport.saveToDlq(req, why, null);
                        } else {
                            imported++;
                            highWaterMark = Math.max(highWaterMark, msg.uid());
                        }
                    } else {
                        String why = "Message UID " + msg.uid() + ": " + String.join(", ", result.errors());
                        FetchSupport.addError(errors, why);
                        // An import that answered with errors was logged and nothing else; the
                        // exception arm below dead-letters, and a later success in the same run
                        // moves the checkpoint past this message either way (9-6 review, P1).
                        fetchSupport.saveToDlq(req, why, null);
                    }
                } catch (Exception e) {
                    FetchSupport.addError(errors, "Message UID " + msg.uid() + ": " + e.getMessage());
                    // DLQ before the checkpoint can move past this item: the high-water
                    // mark is overtaken by a LATER success in the same batch, and this
                    // orchestrator had no DLQ at all, so the item was never re-fetched
                    // (external review).
                    if (req != null) {
                        fetchSupport.saveToDlq(req,
                                "Message UID " + msg.uid() + ": " + e.getMessage(), null);
                    }
                }
            }

            if (highWaterMark > lastImportedUid) {
                checkpointManager.saveCheckpointWithValidity(profile.getProfileId(), mailboxFolder, currentUidValidity, highWaterMark);
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
            FetchSupport.addError(errors, "IMAP connection failed: " + e.getMessage());
            logger.error("IMAP fetch failed for {}: {}", connector.getEndpoint(), e.getMessage());
        } finally {
            imap.disconnect();
        }

        logger.info("IMAP fetch complete: fetched={}, imported={}, errors={}", fetched, imported, errors.size());
        return new FetchResult(fetched, imported, skipped, errors, List.copyOf(incompleteReads));
    }
}
