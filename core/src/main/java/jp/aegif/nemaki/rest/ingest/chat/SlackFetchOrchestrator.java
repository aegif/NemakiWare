package jp.aegif.nemaki.rest.ingest.chat;

import jp.aegif.nemaki.rest.ingest.*;
import jp.aegif.nemaki.rest.ingest.chat.SlackConnectorAdapter.SlackFile;
import jp.aegif.nemaki.rest.ingest.chat.SlackConnectorAdapter.SlackMessage;
import org.apache.chemistry.opencmis.commons.server.CallContext;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SlackFetchOrchestrator implements FetchOrchestrator {

    private FetchSupport fetchSupport;
    private CheckpointManager checkpointManager;
    private CanonicalImportService canonicalImportService;

    public void setFetchSupport(FetchSupport fs) { this.fetchSupport = fs; }
    public void setCheckpointManager(CheckpointManager cm) { this.checkpointManager = cm; }
    public void setCanonicalImportService(CanonicalImportService cis) { this.canonicalImportService = cis; }

    @Override public String sourceSystem() { return "slack"; }

    @Override
    public FetchResult execute(CallContext callContext, ImportProfileDefinition profile,
                               ConnectorDefinition connector, Map<String, String> params, int limit) {
        String channelId = params.getOrDefault("channelId", "");
        String token = fetchSupport.resolvePassword(connector);
        if (token == null) return new FetchResult(0, 0, List.of("No token for Slack connector"));

        // files_only (default): import only attached files, keep the message
        // text as metadata. files_and_body: also import the message text as a
        // .txt document (legacy behaviour).
        boolean importBody = "files_and_body".equals(profile.getImportPolicy());

        List<String> errors = new ArrayList<>();
        int fetched = 0, imported = 0, skipped = 0;
        try {
            var slack = new SlackConnectorAdapter(token);
            String lastTs = checkpointManager.loadSimpleCheckpoint(profile.getProfileId(), "slack." + channelId);
            List<SlackMessage> messages = slack.getHistory(channelId, lastTs, limit);
            fetched = messages.size();
            String highWaterTs = lastTs;
            long throttleMs = FetchSupport.calculateThrottleDelayMs(connector);

            for (SlackMessage msg : messages) {
                fetchSupport.throttle(throttleMs);
                ExternalIngestRequest msgReq = null;
                try {
                    String messageText = msg.text() != null ? msg.text() : "";
                    String parentObjectId = null;
                    boolean messageOk = true;
                    if (importBody) {
                        msgReq = new ExternalIngestRequest();
                        msgReq.setProfileId(profile.getProfileId());
                        msgReq.setConnectorId(connector.getConnectorId());
                        msgReq.setRepositoryId(profile.getRepositoryId());
                        msgReq.setSourceObjectId(msg.ts());
                        msgReq.setSourceObjectType("chat_message");
                        msgReq.setFileName("slack-" + msg.ts().replace(".", "-") + ".txt");
                        msgReq.setMimeType("text/plain");
                        msgReq.setContentStream(new ByteArrayInputStream(messageText.getBytes(StandardCharsets.UTF_8)));
                        msgReq.setExecutionMode("scheduled");
                        Map<String, Object> msgMeta = new LinkedHashMap<>();
                        msgMeta.put("channelId", channelId);
                        msgMeta.put("threadId", msg.threadTs());
                        msgMeta.put("messageId", msg.ts());
                        msgMeta.put("senderId", msg.userId());
                        msgMeta.put("messageText", FetchSupport.truncateForContext(messageText));
                        msgMeta.put("workspaceId", connector.getTenantId());
                        msgReq.setMetadata(msgMeta);

                        ExternalIngestResult msgResult = canonicalImportService.executeChatContextImport(callContext, msgReq);
                        parentObjectId = msgResult.isSuccess() ? msgResult.objectId() : null;
                        messageOk = msgResult.isSuccess() || msgResult.skipped();
                        // skipped() first: isSuccess() (no-errors) is also true
                        // for a skipped result, so a skip would be miscounted.
                        if (msgResult.skipped()) skipped++;
                        else if (msgResult.isSuccess()) imported++;
                        else FetchSupport.addError(errors, "Slack msg " + msg.ts() + ": " + String.join(", ", msgResult.errors()));
                    }
                    boolean attachmentFailed = false;

                    for (SlackFile file : msg.files()) {
                        if (file.urlPrivateDownload() == null) continue;
                        InputStream content = null;
                        // Built BEFORE the download, so a download failure — the likeliest
                        // per-file failure — still has an item-naming entry to record.
                        ExternalIngestRequest fileReq = new ExternalIngestRequest();
                        fileReq.setProfileId(profile.getProfileId());
                        fileReq.setConnectorId(connector.getConnectorId());
                        fileReq.setRepositoryId(profile.getRepositoryId());
                        fileReq.setSourceObjectId(file.id());
                        fileReq.setSourceObjectType("attachment");
                        fileReq.setExecutionMode("scheduled");
                        try {
                            content = slack.downloadFile(file.urlPrivateDownload());
                            ExternalIngestRequest req = fileReq;
                            req.setProfileId(profile.getProfileId());
                            req.setConnectorId(connector.getConnectorId());
                            req.setRepositoryId(profile.getRepositoryId());
                            req.setSourceObjectId(file.id());
                            req.setSourceObjectType("attachment");
                            req.setFileName(file.name());
                            req.setMimeType(file.mimeType());
                            req.setContentStream(content);
                            req.setExecutionMode("scheduled");
                            Map<String, Object> fileMeta = new LinkedHashMap<>();
                            fileMeta.put("channelId", channelId);
                            fileMeta.put("threadId", msg.threadTs());
                            fileMeta.put("messageId", msg.ts());
                            fileMeta.put("senderId", msg.userId());
                            fileMeta.put("messageText", FetchSupport.truncateForContext(messageText));
                            fileMeta.put("workspaceId", connector.getTenantId());
                            req.setMetadata(fileMeta);

                            ExternalIngestResult result = canonicalImportService.executeChatContextImport(callContext, req);
                            // skipped() first: a skipped result also reports
                            // isSuccess()==true (no errors), so check it first.
                            // Relationship creation is idempotent, so we still
                            // (re)link a skipped attachment to its message —
                            // this repairs a missing edge without duplicating.
                            if (result.skipped()) {
                                skipped++;
                                if (parentObjectId != null && result.objectId() != null) {
                                    fetchSupport.createRelationshipSafe(callContext, profile.getRepositoryId(),
                                            parentObjectId, result.objectId(), errors);
                                }
                            } else if (result.isSuccess()) {
                                imported++;
                                if (parentObjectId != null) {
                                    fetchSupport.createRelationshipSafe(callContext, profile.getRepositoryId(),
                                            parentObjectId, result.objectId(), errors);
                                }
                            } else { attachmentFailed = true; FetchSupport.addError(errors, "Slack file " + file.id() + ": " + String.join(", ", result.errors())); }
                        } catch (Exception e) {
                            attachmentFailed = true;
                            FetchSupport.addError(errors, "Slack file " + file.id() + ": " + e.getMessage());
                            // This is the loss path, and it is the one that was missing. The
                            // outer catch below only sees a message-level throw, and its request
                            // is built inside `if (importBody)` — which files_only, the DEFAULT,
                            // never enters. Teams and Mattermost already DLQ here (external
                            // review).
                            if (fileReq != null) {
                                fetchSupport.saveToDlq(fileReq,
                                        "Slack file " + file.id() + ": " + e.getMessage(), null);
                            }
                        } finally {
                            if (content != null) try { content.close(); } catch (Exception ignored) {}
                        }
                    }
                    if (messageOk && !attachmentFailed) {
                        if (highWaterTs == null || msg.ts().compareTo(highWaterTs) > 0) highWaterTs = msg.ts();
                    }
                } catch (Exception e) {
                    FetchSupport.addError(errors, "Slack msg " + msg.ts() + ": " + e.getMessage());
                    // DLQ before the checkpoint can move past this item: the high-water
                    // mark is overtaken by a LATER success in the same batch, and this
                    // orchestrator had no DLQ at all, so the item was never re-fetched
                    // (external review).
                    if (msgReq != null) {
                        fetchSupport.saveToDlq(msgReq,
                                "Slack msg " + msg.ts() + ": " + e.getMessage(), null);
                    }
                }
            }
            if (highWaterTs != null && !highWaterTs.equals(lastTs)) {
                checkpointManager.saveSimpleCheckpoint(profile.getProfileId(), "slack." + channelId, highWaterTs);
            }
        } catch (Exception e) { FetchSupport.addError(errors, "Slack connection failed: " + e.getMessage()); }
        return new FetchResult(fetched, imported, skipped, errors);
    }
}
