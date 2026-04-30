package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.rest.purview.journal.*;
import jp.aegif.nemaki.util.spring.SpringContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles lineage event emission for ingest operations.
 *
 * <p>Extracted from CanonicalImportServiceImpl. Contains:
 * emitLineageEvent, emitViaJournal, buildCanonicalSourceUri,
 * resolveProcessType, isAttachmentObjectType.
 */
public class IngestLineageEmitter {

    private static final Logger logger = LoggerFactory.getLogger(IngestLineageEmitter.class);

    /**
     * Emit a lineage event for an imported document.
     *
     * @return eventId string, or null on failure
     */
    public String emitLineageEvent(String repositoryId, String objectId, String targetFolderId,
                                   ConnectorDefinition connector, ExternalIngestRequest request) {
        try {
            LineageProcessType processType = resolveProcessType(connector.getSourceArchetype(), request.getSourceObjectType());
            String sourceUri = buildCanonicalSourceUri(connector, request);

            LineageEventBuilder builder = new LineageEventBuilder()
                    .repositoryId(repositoryId)
                    .processType(processType)
                    .addInput(sourceUri)
                    .addOutputObject(repositoryId, objectId)
                    .correlationId(request.getCorrelationId())
                    .snapshotAttribute("sourceSystem", connector.getSourceSystem())
                    .snapshotAttribute("sourceArchetype",
                            connector.getSourceArchetype() != null ? connector.getSourceArchetype().name() : "")
                    .snapshotAttribute("sourceObjectId", request.getSourceObjectId());

            if (request.getSourceObjectType() != null) {
                builder.snapshotAttribute("sourceObjectType", request.getSourceObjectType());
            }
            if (targetFolderId != null) {
                builder.snapshotAttribute("targetFolderId", targetFolderId);
            }

            LineageEvent event = builder.build();
            emitViaJournal(event);
            return event.eventId();
        } catch (Exception e) {
            logger.warn("Failed to emit lineage event for {}: {}", objectId, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private void emitViaJournal(LineageEvent event) {
        try {
            var ctx = SpringContext.getApplicationContext();
            if (ctx == null) return;
            LineageConfig config = ctx.getBean(LineageConfig.class);
            if (config == null) return;
            LineageMode mode = config.getModeForRepository(event.repositoryId());
            if (mode == LineageMode.DISABLED) return;
            LineageJournalStore store = ctx.getBean(LineageJournalStore.class);
            java.util.List<LineageTargetSink> sinks = (java.util.List<LineageTargetSink>)
                    (java.util.List<?>) ctx.getBeansOfType(LineageTargetSink.class).values().stream().toList();
            LineageEmitter emitter = config.createEmitterForMode(mode, store, sinks);
            if (emitter.isActive()) {
                emitter.emit(event);
            }
        } catch (Exception e) {
            logger.warn("Lineage event emission failed (non-fatal): {}", e.getMessage());
        }
    }

    // ── Static helpers ──

    static String buildCanonicalSourceUri(ConnectorDefinition connector, ExternalIngestRequest request) {
        String system = connector.getSourceSystem();
        String tenant = connector.getTenantId();
        String objectId = request.getSourceObjectId();
        SourceArchetype archetype = connector.getSourceArchetype();

        if (archetype == null) {
            return ExternalSourceUri.build(system, tenant, request.getSourceObjectType(), objectId);
        }
        boolean isAttachment = isAttachmentObjectType(request.getSourceObjectType());
        return switch (archetype) {
            case FILE_SHARE -> ExternalSourceUri.forFileShare(system, tenant, objectId);
            case COMPOUND_NOTE -> {
                if (isAttachment) yield ExternalSourceUri.build(system, tenant, "files", objectId);
                yield ExternalSourceUri.forNotePage(system, tenant, objectId);
            }
            case CHAT_CONTEXT -> {
                String channelId = resolveMetadataString(request, "channelId");
                if (isAttachment) {
                    yield ExternalSourceUri.build(system, tenant,
                            "channels/" + channelId + "/files", objectId);
                }
                yield ExternalSourceUri.forChatMessage(system, tenant, channelId, objectId);
            }
            case BUSINESS_RECORD -> ExternalSourceUri.forBusinessRecord(system, tenant,
                    request.getSourceObjectType() != null ? request.getSourceObjectType() : "record", objectId);
            case MESSAGE_CONTEXT -> {
                String mailboxId = resolveMetadataString(request, "mailboxId");
                if (isAttachment) {
                    String msgId = resolveMetadataString(request, "messageStableId");
                    yield ExternalSourceUri.forMailAttachment(system, tenant,
                            mailboxId != null ? mailboxId : "default",
                            msgId != null ? msgId : "unknown", objectId);
                }
                yield ExternalSourceUri.forMailMessage(system, tenant,
                        mailboxId != null ? mailboxId : "default", objectId);
            }
        };
    }

    static boolean isAttachmentObjectType(String sourceObjectType) {
        if (sourceObjectType == null) return false;
        String lower = sourceObjectType.toLowerCase();
        return "attachment".equals(lower) || "file".equals(lower);
    }

    static LineageProcessType resolveProcessType(SourceArchetype archetype, String sourceObjectType) {
        boolean isAttachment = isAttachmentObjectType(sourceObjectType);
        if (archetype == null) {
            return isAttachment ? LineageProcessType.EXTERNAL_ATTACHMENT_IMPORT : LineageProcessType.IMPORT_UPLOADED;
        }
        return switch (archetype) {
            case FILE_SHARE -> LineageProcessType.FILE_SHARE_SYNC_DOWNLOAD;
            case COMPOUND_NOTE -> isAttachment
                    ? LineageProcessType.EXTERNAL_ATTACHMENT_IMPORT
                    : LineageProcessType.EXTERNAL_NOTE_IMPORT;
            case CHAT_CONTEXT -> isAttachment
                    ? LineageProcessType.EXTERNAL_ATTACHMENT_IMPORT
                    : LineageProcessType.CHAT_ATTACHMENT_IMPORT;
            case BUSINESS_RECORD -> LineageProcessType.BUSINESS_RECORD_IMPORT;
            case MESSAGE_CONTEXT -> isAttachment
                    ? LineageProcessType.MAIL_ATTACHMENT_IMPORT
                    : LineageProcessType.MAIL_MESSAGE_IMPORT;
        };
    }

    private static String resolveMetadataString(ExternalIngestRequest request, String key) {
        if (request.getMetadata() != null) {
            Object val = request.getMetadata().get(key);
            if (val instanceof String s && !s.isBlank()) return s;
        }
        return null;
    }
}
