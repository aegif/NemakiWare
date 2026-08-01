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
     * Emit the lineage fact for an imported document.
     *
     * <p>The return value keeps the old contract exactly: the v1 eventId of the stored journal
     * row, or null when nothing was emitted. The id is preset on the {@link
     * LineageFact.LegacyV1Projection} so the projected event carries the same id we return —
     * returning the operation id instead was rejected in review as a compat break (the ingest
     * response's {@code lineageEventId} must resolve against the journal). At the v2 flip the
     * projection dies and this becomes the operation id, which v2 events carry verbatim.
     *
     * @param documentName the created document's cmis:name — the typed CMIS_DOCUMENT endpoint
     *                     requires it, and only the caller that created the document knows it
     * @param operationId  issued by the caller at the start of the import operation (§3) —
     *                     not here, which would stamp it after the mutation already happened
     * @return the v1 eventId of the emitted event, or null when nothing was emitted
     */
    public String emitLineageEvent(String repositoryId, String objectId, String targetFolderId,
                                   String documentName, String operationId,
                                   ConnectorDefinition connector, ExternalIngestRequest request) {
        try {
            // Two classifications on purpose. The v1 type participates in eventKey and keeps
            // its historical labels (null-archetype non-attachment = IMPORT_UPLOADED); the fact's
            // own type is what v2 calls the operation — GENERIC_EXTERNAL_INGEST for that case,
            // because unclassified connector ingest is not a user upload and the two have
            // different v2 shapes.
            LineageProcessType legacyProcessType = resolveProcessType(connector.getSourceArchetype(), request.getSourceObjectType());
            LineageProcessType factProcessType =
                    legacyProcessType == LineageProcessType.IMPORT_UPLOADED
                            ? LineageProcessType.GENERIC_EXTERNAL_INGEST
                            : legacyProcessType;
            String sourceUri = buildCanonicalSourceUri(connector, request);
            String v1EventId = java.util.UUID.randomUUID().toString();

            // The v1 event-level snapshot, conditionals preserved exactly (it rides the legacy
            // projection verbatim; most keys have no v2 home — the endpoint attributes carry
            // sourceSystem and the stable key, and targetFolderId is a §3 Process attribute).
            java.util.Map<String, String> v1Snapshot = new java.util.LinkedHashMap<>();
            v1Snapshot.put("sourceSystem", connector.getSourceSystem());
            v1Snapshot.put("sourceArchetype",
                    connector.getSourceArchetype() != null ? connector.getSourceArchetype().name() : "");
            v1Snapshot.put("sourceObjectId", request.getSourceObjectId());
            if (request.getSourceObjectType() != null) {
                v1Snapshot.put("sourceObjectType", request.getSourceObjectType());
            }
            if (targetFolderId != null) {
                v1Snapshot.put("targetFolderId", targetFolderId);
            }

            boolean emitted = LineageFactEmission.emitSafely(resolveEmitter(repositoryId), () -> {
                String occurredAt = java.time.Instant.now().toString();
                return new LineageFact(
                        repositoryId,
                        factProcessType,
                        operationId,
                        occurredAt,
                        java.util.List.of(LineageEndpoint.externalAsset(
                                repositoryId, sourceUri, connector.getSourceSystem())),
                        java.util.List.of(LineageEndpoint.document(
                                repositoryId, objectId, documentName)),
                        lineageTargets(),
                        request.getCorrelationId(),
                        new LineageFact.LegacyV1Projection(
                                legacyProcessType,
                                java.util.List.of(sourceUri),
                                java.util.List.of(LineageEvent.qualifiedName(repositoryId, objectId)),
                                v1Snapshot,
                                v1EventId));
            }, "repo=" + repositoryId + " op=" + operationId + " type=" + factProcessType);
            return emitted ? v1EventId : null;
        } catch (Exception e) {
            logger.warn("Failed to emit lineage event for {}: {}", objectId, e.getMessage());
            return null;
        }
    }

    private java.util.List<String> lineageTargets() {
        try {
            var ctx = SpringContext.getApplicationContext();
            if (ctx == null) return java.util.List.of();
            LineageConfig config = ctx.getBean(LineageConfig.class);
            return config != null ? config.getTargets() : java.util.List.of();
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private LineageEmitter resolveEmitter(String repositoryId) {
        try {
            var ctx = SpringContext.getApplicationContext();
            if (ctx == null) return null;
            LineageConfig config = ctx.getBean(LineageConfig.class);
            if (config == null) return null;
            LineageMode mode = config.getModeForRepository(repositoryId);
            if (mode == LineageMode.DISABLED) return null;
            LineageJournalStore store = ctx.getBean(LineageJournalStore.class);
            java.util.List<LineageTargetSink> sinks = (java.util.List<LineageTargetSink>)
                    (java.util.List<?>) ctx.getBeansOfType(LineageTargetSink.class).values().stream().toList();
            return config.createEmitterForMode(mode, store, sinks);
        } catch (Exception e) {
            logger.warn("Lineage emitter resolution failed (non-fatal): {}", e.getMessage());
            return null;
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
