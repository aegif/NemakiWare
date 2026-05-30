package jp.aegif.nemaki.rest.ingest.chat;

import jp.aegif.nemaki.rest.ingest.*;
import jp.aegif.nemaki.rest.ingest.chat.MattermostConnectorAdapter.MattermostFile;
import jp.aegif.nemaki.rest.ingest.chat.MattermostConnectorAdapter.MattermostPost;
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

public class MattermostFetchOrchestrator implements FetchOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(MattermostFetchOrchestrator.class);

    private FetchSupport fetchSupport;
    private CheckpointManager checkpointManager;
    private CanonicalImportService canonicalImportService;

    public void setFetchSupport(FetchSupport fs) { this.fetchSupport = fs; }
    public void setCheckpointManager(CheckpointManager cm) { this.checkpointManager = cm; }
    public void setCanonicalImportService(CanonicalImportService cis) { this.canonicalImportService = cis; }

    @Override public String sourceSystem() { return "mattermost"; }

    @Override
    public FetchResult execute(CallContext callContext, ImportProfileDefinition profile,
                               ConnectorDefinition connector, Map<String, String> params, int limit) {
        String channelId = params.getOrDefault("channelId", "");
        String token = fetchSupport.resolvePassword(connector);
        if (token == null) return new FetchResult(0, 0, List.of("No token for Mattermost connector"));

        List<String> errors = new ArrayList<>();
        int fetched = 0, imported = 0, skipped = 0;
        try {
            // RC6.8 P2: re-validate the connector endpoint at runtime as
            // defense-in-depth. ConnectorDefinitionServiceImpl validates on
            // save, but an endpoint that was saved before this hardening
            // landed, or modified at storage level, would otherwise reach
            // the adapter without revalidation. sendWithRetry inside the
            // adapter will validate + IP-pin again (RC6.8 P1), but failing
            // early here gives a clearer audit message and avoids building
            // the adapter for an obviously-bad endpoint.
            jp.aegif.nemaki.rest.ingest.AdapterHttpClient.validateExternalUrl(connector.getEndpoint());
            var mm = new MattermostConnectorAdapter(connector.getEndpoint(), token);
            String lastCreateAt = checkpointManager.loadSimpleCheckpoint(profile.getProfileId(), "mattermost." + channelId);
            List<MattermostPost> posts = mm.getPosts(channelId, limit);
            fetched = posts.size();
            long initialCheckpoint = 0;
            try {
                if (lastCreateAt != null) initialCheckpoint = Long.parseLong(lastCreateAt);
            } catch (NumberFormatException e) {
                logger.warn("Invalid Mattermost checkpoint '{}', resetting", lastCreateAt);
            }
            long highWaterCreateAt = initialCheckpoint;
            long throttleMs = FetchSupport.calculateThrottleDelayMs(connector);

            for (MattermostPost post : posts) {
                fetchSupport.throttle(throttleMs);
                if (post.createAt() > 0 && post.createAt() <= initialCheckpoint) { skipped++; continue; }
                try {
                    String postText = post.message() != null ? post.message() : "";
                    ExternalIngestRequest msgReq = new ExternalIngestRequest();
                    msgReq.setProfileId(profile.getProfileId());
                    msgReq.setConnectorId(connector.getConnectorId());
                    msgReq.setRepositoryId(profile.getRepositoryId());
                    msgReq.setSourceObjectId(post.id());
                    msgReq.setSourceObjectType("chat_message");
                    msgReq.setFileName("mm-" + post.id() + ".txt");
                    msgReq.setMimeType("text/plain");
                    msgReq.setContentStream(new ByteArrayInputStream(postText.getBytes(StandardCharsets.UTF_8)));
                    msgReq.setExecutionMode("scheduled");
                    Map<String, Object> msgMeta = new LinkedHashMap<>();
                    msgMeta.put("channelId", channelId);
                    msgMeta.put("messageId", post.id());
                    msgMeta.put("senderId", post.userId());
                    msgMeta.put("messageText", FetchSupport.truncateForContext(postText));
                    msgMeta.put("workspaceId", connector.getTenantId());
                    if (post.rootId() != null && !post.rootId().isEmpty()) msgMeta.put("threadId", post.rootId());
                    msgReq.setMetadata(msgMeta);

                    ExternalIngestResult msgResult = canonicalImportService.executeChatContextImport(callContext, msgReq);
                    String parentObjectId = msgResult.isSuccess() ? msgResult.objectId() : null;
                    boolean messageOk = msgResult.isSuccess() || msgResult.skipped();
                    if (msgResult.isSuccess()) imported++;
                    else if (msgResult.skipped()) skipped++;
                    else FetchSupport.addError(errors, "MM msg " + post.id() + ": " + String.join(", ", msgResult.errors()));
                    boolean attachmentFailed = false;

                    for (String fileId : post.fileIds()) {
                        InputStream content = null;
                        try {
                            MattermostFile fileInfo = mm.getFileInfo(fileId);
                            content = mm.downloadFile(fileId);
                            ExternalIngestRequest req = new ExternalIngestRequest();
                            req.setProfileId(profile.getProfileId());
                            req.setConnectorId(connector.getConnectorId());
                            req.setRepositoryId(profile.getRepositoryId());
                            req.setSourceObjectId(fileId);
                            req.setSourceObjectType("attachment");
                            req.setFileName(fileInfo.name());
                            req.setMimeType(fileInfo.mimeType());
                            req.setContentStream(content);
                            req.setExecutionMode("scheduled");
                            Map<String, Object> fileMeta = new LinkedHashMap<>();
                            fileMeta.put("channelId", channelId);
                            fileMeta.put("messageId", post.id());
                            fileMeta.put("senderId", post.userId());
                            fileMeta.put("messageText", FetchSupport.truncateForContext(postText));
                            fileMeta.put("workspaceId", connector.getTenantId());
                            if (post.rootId() != null && !post.rootId().isEmpty()) fileMeta.put("threadId", post.rootId());
                            req.setMetadata(fileMeta);
                            ExternalIngestResult result = canonicalImportService.executeChatContextImport(callContext, req);
                            if (result.isSuccess()) {
                                imported++;
                                if (parentObjectId != null) fetchSupport.createRelationshipSafe(callContext, profile.getRepositoryId(), parentObjectId, result.objectId(), errors);
                            } else if (result.skipped()) { skipped++; }
                            else { attachmentFailed = true; FetchSupport.addError(errors, "MM " + fileId + ": " + String.join(", ", result.errors())); }
                        } catch (Exception e) { attachmentFailed = true; FetchSupport.addError(errors, "MM file " + fileId + ": " + e.getMessage()); }
                        finally { if (content != null) try { content.close(); } catch (Exception ignored) {} }
                    }
                    if (messageOk && !attachmentFailed) {
                        if (post.createAt() > highWaterCreateAt) highWaterCreateAt = post.createAt();
                    }
                } catch (Exception e) { FetchSupport.addError(errors, "MM post " + post.id() + ": " + e.getMessage()); }
            }
            if (highWaterCreateAt > initialCheckpoint) {
                checkpointManager.saveSimpleCheckpoint(profile.getProfileId(), "mattermost." + channelId, String.valueOf(highWaterCreateAt));
            }
        } catch (Exception e) { FetchSupport.addError(errors, "Mattermost connection failed: " + e.getMessage()); }
        return new FetchResult(fetched, imported, skipped, errors);
    }
}
