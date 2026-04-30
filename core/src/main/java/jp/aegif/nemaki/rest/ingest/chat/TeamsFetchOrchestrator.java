package jp.aegif.nemaki.rest.ingest.chat;

import jp.aegif.nemaki.rest.ingest.*;
import jp.aegif.nemaki.rest.ingest.chat.TeamsConnectorAdapter.TeamsFile;
import jp.aegif.nemaki.rest.ingest.chat.TeamsConnectorAdapter.TeamsMessage;
import org.apache.chemistry.opencmis.commons.server.CallContext;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TeamsFetchOrchestrator implements FetchOrchestrator {

    private FetchSupport fetchSupport;
    private CheckpointManager checkpointManager;
    private CanonicalImportService canonicalImportService;

    public void setFetchSupport(FetchSupport fs) { this.fetchSupport = fs; }
    public void setCheckpointManager(CheckpointManager cm) { this.checkpointManager = cm; }
    public void setCanonicalImportService(CanonicalImportService cis) { this.canonicalImportService = cis; }

    @Override public String sourceSystem() { return "teams"; }

    @Override
    public FetchResult execute(CallContext callContext, ImportProfileDefinition profile,
                               ConnectorDefinition connector, Map<String, String> params, int limit) {
        String teamId = params.getOrDefault("teamId", "");
        String channelId = params.getOrDefault("channelId", "");
        String token = fetchSupport.resolvePassword(connector);
        if (token == null) return new FetchResult(0, 0, List.of("No token for Teams connector"));

        List<String> errors = new ArrayList<>();
        int fetched = 0, imported = 0, skipped = 0;
        try {
            var teams = new TeamsConnectorAdapter(token);
            var messages = teams.getMessages(teamId, channelId, limit);
            fetched = messages.size();
            String lastDateTime = checkpointManager.loadSimpleCheckpoint(profile.getProfileId(), "teams." + teamId + "." + channelId);
            String highWaterDateTime = lastDateTime;
            long throttleMs = FetchSupport.calculateThrottleDelayMs(connector);

            for (TeamsMessage msg : messages) {
                fetchSupport.throttle(throttleMs);
                if (lastDateTime != null && msg.createdDateTime() != null
                        && msg.createdDateTime().compareTo(lastDateTime) <= 0) { skipped++; continue; }
                try {
                    String messageBody = msg.body() != null ? msg.body() : "";
                    ExternalIngestRequest msgReq = new ExternalIngestRequest();
                    msgReq.setProfileId(profile.getProfileId());
                    msgReq.setConnectorId(connector.getConnectorId());
                    msgReq.setRepositoryId(profile.getRepositoryId());
                    msgReq.setSourceObjectId(msg.id());
                    msgReq.setSourceObjectType("chat_message");
                    msgReq.setFileName("teams-" + msg.id() + ".html");
                    msgReq.setMimeType("text/html");
                    msgReq.setContentStream(new ByteArrayInputStream(messageBody.getBytes(StandardCharsets.UTF_8)));
                    msgReq.setExecutionMode("scheduled");
                    Map<String, Object> msgMeta = new LinkedHashMap<>();
                    msgMeta.put("channelId", channelId);
                    msgMeta.put("messageId", msg.id());
                    msgMeta.put("senderId", msg.from());
                    msgMeta.put("messageText", FetchSupport.truncateForContext(messageBody));
                    msgMeta.put("workspaceId", teamId);
                    if (msg.replyToId() != null) msgMeta.put("threadId", msg.replyToId());
                    msgReq.setMetadata(msgMeta);

                    ExternalIngestResult msgResult = canonicalImportService.executeChatContextImport(callContext, msgReq);
                    String parentObjectId = msgResult.isSuccess() ? msgResult.objectId() : null;
                    boolean messageOk = msgResult.isSuccess() || msgResult.skipped();
                    if (msgResult.isSuccess()) imported++;
                    else if (msgResult.skipped()) skipped++;
                    else FetchSupport.addError(errors, "Teams msg " + msg.id() + ": " + String.join(", ", msgResult.errors()));
                    boolean attachmentFailed = false;

                    for (TeamsFile file : msg.attachments()) {
                        if (file.contentUrl() == null) continue;
                        InputStream content = null;
                        try {
                            content = teams.downloadFile(file.contentUrl());
                            ExternalIngestRequest req = new ExternalIngestRequest();
                            req.setProfileId(profile.getProfileId());
                            req.setConnectorId(connector.getConnectorId());
                            req.setRepositoryId(profile.getRepositoryId());
                            req.setSourceObjectId(file.id());
                            req.setSourceObjectType("attachment");
                            req.setFileName(file.name());
                            req.setMimeType(file.contentType());
                            req.setContentStream(content);
                            req.setExecutionMode("scheduled");
                            Map<String, Object> fileMeta = new LinkedHashMap<>();
                            fileMeta.put("channelId", channelId);
                            fileMeta.put("messageId", msg.id());
                            fileMeta.put("senderId", msg.from());
                            fileMeta.put("messageText", FetchSupport.truncateForContext(messageBody));
                            fileMeta.put("workspaceId", teamId);
                            if (msg.replyToId() != null) fileMeta.put("threadId", msg.replyToId());
                            req.setMetadata(fileMeta);
                            ExternalIngestResult result = canonicalImportService.executeChatContextImport(callContext, req);
                            if (result.isSuccess()) {
                                imported++;
                                if (parentObjectId != null) fetchSupport.createRelationshipSafe(callContext, profile.getRepositoryId(), parentObjectId, result.objectId(), errors);
                            } else if (result.skipped()) { skipped++; }
                            else { attachmentFailed = true; FetchSupport.addError(errors, "Teams " + file.id() + ": " + String.join(", ", result.errors())); }
                        } catch (Exception e) { attachmentFailed = true; FetchSupport.addError(errors, "Teams file " + file.id() + ": " + e.getMessage()); }
                        finally { if (content != null) try { content.close(); } catch (Exception ignored) {} }
                    }
                    if (messageOk && !attachmentFailed) {
                        if (msg.createdDateTime() != null && (highWaterDateTime == null || msg.createdDateTime().compareTo(highWaterDateTime) > 0))
                            highWaterDateTime = msg.createdDateTime();
                    }
                } catch (Exception e) { FetchSupport.addError(errors, "Teams msg " + msg.id() + ": " + e.getMessage()); }
            }
            if (highWaterDateTime != null && !highWaterDateTime.equals(lastDateTime))
                checkpointManager.saveSimpleCheckpoint(profile.getProfileId(), "teams." + teamId + "." + channelId, highWaterDateTime);
        } catch (Exception e) { FetchSupport.addError(errors, "Teams connection failed: " + e.getMessage()); }
        return new FetchResult(fetched, imported, skipped, errors);
    }
}
