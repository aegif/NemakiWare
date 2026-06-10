package jp.aegif.nemaki.rest.ingest.chat;

import jp.aegif.nemaki.rest.ingest.*;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ChatworkFetchOrchestrator implements FetchOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(ChatworkFetchOrchestrator.class);

    private FetchSupport fetchSupport;
    private CheckpointManager checkpointManager;
    private CanonicalImportService canonicalImportService;

    public void setFetchSupport(FetchSupport fs) { this.fetchSupport = fs; }
    public void setCheckpointManager(CheckpointManager cm) { this.checkpointManager = cm; }
    public void setCanonicalImportService(CanonicalImportService cis) { this.canonicalImportService = cis; }

    @Override public String sourceSystem() { return "chatwork"; }

    @Override
    public FetchResult execute(CallContext callContext, ImportProfileDefinition profile,
                               ConnectorDefinition connector, Map<String, String> params, int limit) {
        String roomId = params.getOrDefault("roomId", "");
        String token = fetchSupport.resolvePassword(connector);
        if (token == null) return new FetchResult(0, 0, List.of("No token for Chatwork connector"));
        if (roomId.isBlank()) return new FetchResult(0, 0, List.of("roomId is required for Chatwork"));

        List<String> errors = new ArrayList<>();
        int fetched = 0, imported = 0, skipped = 0;
        try {
            var chatwork = new ChatworkConnectorAdapter(token);
            String lastMsgId = checkpointManager.loadSimpleCheckpoint(profile.getProfileId(), "chatwork." + roomId);
            long lastMsgIdNum = 0;
            if (lastMsgId != null && !lastMsgId.isBlank()) {
                try { lastMsgIdNum = Long.parseLong(lastMsgId); }
                catch (NumberFormatException e) {
                    String msg = "Chatwork checkpoint for room " + roomId + " is corrupted: '" + lastMsgId
                            + "'. Delete the checkpoint via admin API to re-sync, or set a valid numeric ID.";
                    logger.error(msg);
                    FetchSupport.addError(errors, msg);
                    return new FetchResult(0, 0, 0, errors);
                }
            }

            var messages = chatwork.getMessages(roomId, true);

            // Detect potential message loss (100-message window gap)
            if (lastMsgIdNum > 0 && !messages.isEmpty()) {
                long oldestReturnedId = Long.MAX_VALUE;
                for (var m : messages) {
                    if (m == null || m.messageId() == null) continue;
                    try { long id = Long.parseLong(m.messageId()); if (id > 0 && id < oldestReturnedId) oldestReturnedId = id; }
                    catch (NumberFormatException ignored) {}
                }
                if (oldestReturnedId != Long.MAX_VALUE && oldestReturnedId > lastMsgIdNum + 1) {
                    String gap = "Chatwork message gap detected for room " + roomId
                            + ": checkpoint=" + lastMsgIdNum + ", oldest returned=" + oldestReturnedId
                            + ". Messages between these IDs may have been lost (Chatwork API limit: 100).";
                    logger.warn(gap);
                    FetchSupport.addError(errors, gap);
                    return new FetchResult(messages.size(), 0, 0, errors);
                }
            }

            if (messages.size() > limit) messages = messages.subList(0, limit);
            fetched = messages.size();
            long throttleMs = FetchSupport.calculateThrottleDelayMs(connector);
            long highWaterMsgIdNum = lastMsgIdNum;

            for (var msg : messages) {
                fetchSupport.throttle(throttleMs);
                long msgIdNum = 0;
                try { msgIdNum = Long.parseLong(msg.messageId()); } catch (NumberFormatException e) {}
                if (lastMsgIdNum > 0 && msgIdNum <= lastMsgIdNum) { skipped++; continue; }

                try {
                    String messageText = msg.body() != null ? msg.body() : "";
                    ExternalIngestRequest msgReq = new ExternalIngestRequest();
                    msgReq.setProfileId(profile.getProfileId());
                    msgReq.setConnectorId(connector.getConnectorId());
                    msgReq.setRepositoryId(profile.getRepositoryId());
                    msgReq.setSourceObjectId(msg.messageId());
                    msgReq.setSourceObjectType("chat_message");
                    msgReq.setFileName("chatwork-" + msg.messageId() + ".txt");
                    msgReq.setMimeType("text/plain");
                    msgReq.setContentStream(new ByteArrayInputStream(messageText.getBytes(StandardCharsets.UTF_8)));
                    msgReq.setExecutionMode("scheduled");
                    Map<String, Object> msgMeta = new LinkedHashMap<>();
                    msgMeta.put("channelId", roomId);
                    msgMeta.put("messageId", msg.messageId());
                    msgMeta.put("senderId", msg.accountId());
                    msgMeta.put("senderName", msg.accountName());
                    msgMeta.put("messageText", FetchSupport.truncateForContext(messageText));
                    msgMeta.put("workspaceId", connector.getTenantId());
                    msgReq.setMetadata(msgMeta);

                    ExternalIngestResult msgResult = canonicalImportService.executeChatContextImport(callContext, msgReq);
                    if (msgResult.isSuccess() || msgResult.skipped()) {
                        if (msgIdNum > highWaterMsgIdNum) highWaterMsgIdNum = msgIdNum;
                    }
                    if (msgResult.isSuccess()) imported++;
                    else if (msgResult.skipped()) skipped++;
                    else FetchSupport.addError(errors, "Chatwork msg " + msg.messageId() + ": " + String.join(", ", msgResult.errors()));
                } catch (Exception e) {
                    FetchSupport.addError(errors, "Chatwork msg " + msg.messageId() + ": " + e.getMessage());
                }
            }

            // Import files during scheduled runs only (not webhook small fetches)
            if (limit > 10) try {
                var files = chatwork.listFiles(roomId);
                for (var file : files) {
                    fetchSupport.throttle(throttleMs);
                    InputStream content = null;
                    try {
                        String dlUrl = chatwork.getFileDownloadUrl(roomId, file.fileId());
                        if (dlUrl == null) continue;
                        content = chatwork.downloadFile(dlUrl);
                        ExternalIngestRequest fileReq = new ExternalIngestRequest();
                        fileReq.setProfileId(profile.getProfileId());
                        fileReq.setConnectorId(connector.getConnectorId());
                        fileReq.setRepositoryId(profile.getRepositoryId());
                        fileReq.setSourceObjectId("file-" + file.fileId());
                        fileReq.setSourceObjectType("attachment");
                        fileReq.setFileName(file.name());
                        fileReq.setMimeType(FetchSupport.guessMimeType(file.name()));
                        fileReq.setContentStream(content);
                        fileReq.setExecutionMode("scheduled");
                        Map<String, Object> fileMeta = new LinkedHashMap<>();
                        fileMeta.put("channelId", roomId);
                        fileMeta.put("workspaceId", connector.getTenantId());
                        fileReq.setMetadata(fileMeta);

                        ExternalIngestResult fileResult = canonicalImportService.executeChatContextImport(callContext, fileReq);
                        // skipped() first: a skipped result also reports
                        // isSuccess()==true (no errors), so it would be
                        // miscounted as imported otherwise.
                        if (fileResult.skipped()) skipped++;
                        else if (fileResult.isSuccess()) imported++;
                        else FetchSupport.addError(errors, "Chatwork file " + file.fileId() + ": " + String.join(", ", fileResult.errors()));
                    } catch (Exception e) {
                        FetchSupport.addError(errors, "Chatwork file " + file.fileId() + ": " + e.getMessage());
                    } finally {
                        if (content != null) try { content.close(); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) { FetchSupport.addError(errors, "Chatwork file list failed: " + e.getMessage()); }

            if (highWaterMsgIdNum > lastMsgIdNum) {
                checkpointManager.saveSimpleCheckpoint(profile.getProfileId(), "chatwork." + roomId, String.valueOf(highWaterMsgIdNum));
            }
        } catch (Exception e) { FetchSupport.addError(errors, "Chatwork connection failed: " + e.getMessage()); }
        return new FetchResult(fetched, imported, skipped, errors);
    }
}
