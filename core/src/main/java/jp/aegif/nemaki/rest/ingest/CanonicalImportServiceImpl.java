package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.service.ObjectService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.rest.purview.journal.LineageConfig;
import jp.aegif.nemaki.rest.purview.journal.LineageEvent;
import jp.aegif.nemaki.rest.purview.journal.LineageEmitter;
import jp.aegif.nemaki.rest.purview.journal.LineageEventBuilder;
import jp.aegif.nemaki.rest.purview.journal.LineageMode;
import jp.aegif.nemaki.rest.purview.journal.LineageProcessType;
import jp.aegif.nemaki.rest.purview.journal.LineageJournalStore;
import jp.aegif.nemaki.rest.purview.journal.LineageTargetSink;
import jp.aegif.nemaki.util.spring.SpringContext;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;

/**
 * Default implementation of the canonical import pipeline.
 *
 * <p>Phase 1 scope: profile/connector validation, document creation with
 * secondary type attachment, and lineage emission. Dedupe, versioning, and
 * relationship generation are stubbed for Phase 2.
 */
public class CanonicalImportServiceImpl implements CanonicalImportService {

    private static final Logger logger = LoggerFactory.getLogger(CanonicalImportServiceImpl.class);

    private ConnectorDefinitionService connectorDefinitionService;
    private ImportProfileDefinitionService importProfileDefinitionService;
    private ContentService contentService;
    private ObjectService objectService;
    private NemakiCachePool nemakiCachePool;

    // --- DI setters ---

    public void setConnectorDefinitionService(ConnectorDefinitionService service) {
        this.connectorDefinitionService = service;
    }

    public void setImportProfileDefinitionService(ImportProfileDefinitionService service) {
        this.importProfileDefinitionService = service;
    }

    public void setContentService(ContentService contentService) {
        this.contentService = contentService;
    }

    public void setObjectService(ObjectService objectService) {
        this.objectService = objectService;
    }

    public void setNemakiCachePool(NemakiCachePool nemakiCachePool) {
        this.nemakiCachePool = nemakiCachePool;
    }

    @Override
    public ExternalIngestResult execute(CallContext callContext, ExternalIngestRequest request) {
        String requestId = request.getRequestId();

        // 1. Resolve profile
        ImportProfileDefinition profile = importProfileDefinitionService.get(request.getProfileId());
        if (profile == null) {
            return ExternalIngestResult.error(requestId, "Import profile not found: " + request.getProfileId());
        }
        if (!profile.isEnabled()) {
            return ExternalIngestResult.error(requestId, "Import profile is disabled: " + request.getProfileId());
        }
        // Enforce repository scope: profile must match the request's repository
        String repositoryId = request.getRepositoryId();
        if (profile.getRepositoryId() != null && !profile.getRepositoryId().equals(repositoryId)) {
            return ExternalIngestResult.error(requestId,
                    "Profile '" + profile.getProfileId() + "' is scoped to repository '"
                    + profile.getRepositoryId() + "', not '" + repositoryId + "'");
        }

        // 2. Resolve connector
        ConnectorDefinition connector = connectorDefinitionService.get(request.getConnectorId());
        if (connector == null) {
            return ExternalIngestResult.error(requestId, "Connector not found: " + request.getConnectorId());
        }
        if (!connector.isEnabled()) {
            return ExternalIngestResult.error(requestId, "Connector is disabled: " + request.getConnectorId());
        }

        // 3. Validate connector allowed by profile
        if (!profile.isConnectorAllowed(connector.getConnectorId())) {
            return ExternalIngestResult.error(requestId,
                    "Connector '" + connector.getConnectorId() + "' is not allowed by profile '" + profile.getProfileId() + "'");
        }
        if (!profile.isArchetypeAllowed(connector.getSourceArchetype())) {
            return ExternalIngestResult.error(requestId,
                    "Archetype " + connector.getSourceArchetype() + " is not allowed by profile '" + profile.getProfileId() + "'");
        }

        // 4. Dry-run check
        if (request.isDryRun()) {
            // TODO Phase 2: actual dedupe check
            return ExternalIngestResult.dryRun(requestId, null, true);
        }

        // 5. Create document
        String targetFolderId = resolveTargetFolderId(profile, repositoryId, callContext);
        if (targetFolderId == null || targetFolderId.isBlank()) {
            return ExternalIngestResult.error(requestId,
                    "Profile has no resolvable target folder (neither targetFolderId nor targetFolderPath)");
        }

        try {
            String objectTypeId = profile.getDefaultObjectTypeId();
            if (objectTypeId == null || objectTypeId.isBlank()) {
                objectTypeId = "cmis:document";
            }

            String fileName = request.getFileName();
            if (fileName == null || fileName.isBlank()) {
                fileName = "imported-" + request.getSourceObjectId();
            }

            PropertiesImpl properties = new PropertiesImpl();
            properties.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, objectTypeId));
            properties.addProperty(new PropertyStringImpl(PropertyIds.NAME, fileName));

            ContentStream contentStream = null;
            if (request.getContentStream() != null) {
                String mimeType = request.getMimeType() != null ? request.getMimeType() : "application/octet-stream";
                contentStream = new ContentStreamImpl(fileName, BigInteger.valueOf(-1), mimeType, request.getContentStream());
            }

            String objectId = objectService.createDocument(callContext, repositoryId, properties,
                    targetFolderId, contentStream, VersioningState.MAJOR, null, null, null, null);

            // 6. Apply secondary types
            applySourceMetadata(repositoryId, objectId, callContext, connector, request);

            // 7. Emit lineage event
            String lineageEventId = emitLineageEvent(repositoryId, objectId, targetFolderId, connector, request);

            logger.info("Canonical import completed: requestId={}, objectId={}, profile={}, connector={}",
                    requestId, objectId, profile.getProfileId(), connector.getConnectorId());

            return ExternalIngestResult.success(requestId, objectId, "1.0", false, lineageEventId);

        } catch (Exception e) {
            logger.error("Canonical import failed: requestId={}, error={}", requestId, e.getMessage(), e);
            return ExternalIngestResult.error(requestId, e.getMessage());
        }
    }

    private void applySourceMetadata(String repositoryId, String objectId, CallContext callContext,
                                     ConnectorDefinition connector, ExternalIngestRequest request) {
        try {
            Content content = contentService.getContent(repositoryId, objectId);
            if (content == null) {
                logger.warn("Content not found after creation: {}", objectId);
                return;
            }

            List<Aspect> aspects = content.getAspects();
            if (aspects == null) {
                aspects = new ArrayList<>();
            }

            // Add/update nemaki:externalIntegration aspect with source fields
            Aspect extAspect = aspects.stream()
                    .filter(a -> "nemaki:externalIntegration".equals(a.getName()))
                    .findFirst()
                    .orElse(null);

            // Build property map for merge (preserves existing properties not in this set)
            Map<String, Object> newProps = new java.util.LinkedHashMap<>();
            newProps.put("nemaki:sourceArchetype",
                    connector.getSourceArchetype() != null ? connector.getSourceArchetype().name() : "");
            newProps.put("nemaki:sourceSystem",
                    connector.getSourceSystem() != null ? connector.getSourceSystem() : "");
            newProps.put("nemaki:sourceObjectType",
                    request.getSourceObjectType() != null ? request.getSourceObjectType() : "");
            newProps.put("nemaki:sourceObjectId",
                    request.getSourceObjectId() != null ? request.getSourceObjectId() : "");
            newProps.put("nemaki:sourceUrl",
                    request.getSourceUrl() != null ? request.getSourceUrl() : "");
            newProps.put("nemaki:ingestionRunId", request.getRequestId());
            newProps.put("nemaki:externalSourceType",
                    connector.getSourceArchetype() != null ? connector.getSourceArchetype().name().toLowerCase() : "");
            newProps.put("nemaki:externalSourceId",
                    connector.getSourceSystem() != null ? connector.getSourceSystem() : "");
            newProps.put("nemaki:externalContextUpdatedAt", new GregorianCalendar());

            // Persist externalContext from request metadata if provided
            if (request.getMetadata() != null && !request.getMetadata().isEmpty()) {
                try {
                    String contextJson = new com.fasterxml.jackson.databind.ObjectMapper()
                            .writeValueAsString(request.getMetadata());
                    newProps.put("nemaki:externalContext", contextJson);
                } catch (Exception e) {
                    logger.debug("Failed to serialize metadata as externalContext: {}", e.getMessage());
                }
            }

            // Merge: preserve existing properties, overwrite with new values
            List<Property> mergedProps;
            if (extAspect != null && extAspect.getProperties() != null) {
                Map<String, Property> propMap = new java.util.LinkedHashMap<>();
                for (Property p : extAspect.getProperties()) {
                    propMap.put(p.getKey(), p);
                }
                for (Map.Entry<String, Object> entry : newProps.entrySet()) {
                    propMap.put(entry.getKey(), new Property(entry.getKey(), entry.getValue()));
                }
                mergedProps = new ArrayList<>(propMap.values());
            } else {
                mergedProps = new ArrayList<>();
                for (Map.Entry<String, Object> entry : newProps.entrySet()) {
                    mergedProps.add(new Property(entry.getKey(), entry.getValue()));
                }
            }

            if (extAspect != null) {
                extAspect.setProperties(mergedProps);
            } else {
                aspects.add(new Aspect("nemaki:externalIntegration", mergedProps));
            }

            // Add secondary type to secondaryIds if not present
            List<String> secondaryIds = content.getSecondaryIds();
            if (secondaryIds == null) {
                secondaryIds = new ArrayList<>();
            }
            if (!secondaryIds.contains("nemaki:externalIntegration")) {
                secondaryIds.add("nemaki:externalIntegration");
            }
            content.setSecondaryIds(secondaryIds);
            content.setAspects(aspects);

            contentService.update(callContext, repositoryId, content);

            // Invalidate cache
            if (nemakiCachePool != null) {
                try {
                    nemakiCachePool.get(repositoryId).removeCmisAndContentCache(objectId);
                } catch (Exception e) {
                    logger.debug("Cache invalidation failed for {}: {}", objectId, e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to apply source metadata to {}: {}", objectId, e.getMessage());
        }
    }

    private String emitLineageEvent(String repositoryId, String objectId, String targetFolderId,
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

    /**
     * Resolves a concrete folder ID from the profile. Uses targetFolderId if set,
     * otherwise resolves targetFolderPath via ObjectService.getObjectByPath.
     */
    private String resolveTargetFolderId(ImportProfileDefinition profile, String repositoryId,
                                         CallContext callContext) {
        String folderId = profile.getTargetFolderId();
        if (folderId != null && !folderId.isBlank()) {
            return folderId;
        }
        String folderPath = profile.getTargetFolderPath();
        if (folderPath != null && !folderPath.isBlank()) {
            try {
                var objectData = objectService.getObjectByPath(callContext, repositoryId,
                        folderPath, null, Boolean.FALSE, null, null, Boolean.FALSE, Boolean.FALSE, null);
                if (objectData != null && objectData.getId() != null) {
                    logger.debug("Resolved targetFolderPath '{}' to folderId '{}'", folderPath, objectData.getId());
                    return objectData.getId();
                }
            } catch (Exception e) {
                logger.warn("Failed to resolve targetFolderPath '{}' in repository '{}': {}",
                        folderPath, repositoryId, e.getMessage());
            }
        }
        return null;
    }

    /**
     * Builds a canonical source URI using the archetype-specific helper,
     * normalizing the object type path segment by archetype rather than
     * passing raw caller-supplied values.
     */
    private static String buildCanonicalSourceUri(ConnectorDefinition connector, ExternalIngestRequest request) {
        String system = connector.getSourceSystem();
        String tenant = connector.getTenantId();
        String objectId = request.getSourceObjectId();
        SourceArchetype archetype = connector.getSourceArchetype();

        if (archetype == null) {
            return ExternalSourceUri.build(system, tenant, request.getSourceObjectType(), objectId);
        }
        return switch (archetype) {
            case FILE_SHARE -> ExternalSourceUri.forFileShare(system, tenant, objectId);
            case COMPOUND_NOTE -> ExternalSourceUri.forNotePage(system, tenant, objectId);
            case CHAT_CONTEXT -> {
                // channelId comes from metadata, not sourceObjectType
                String channelId = "unknown";
                if (request.getMetadata() != null) {
                    Object ch = request.getMetadata().get("channelId");
                    if (ch instanceof String s && !s.isBlank()) channelId = s;
                }
                yield ExternalSourceUri.forChatMessage(system, tenant, channelId, objectId);
            }
            case BUSINESS_RECORD -> ExternalSourceUri.forBusinessRecord(system, tenant,
                    request.getSourceObjectType() != null ? request.getSourceObjectType() : "record", objectId);
        };
    }

    /**
     * Resolves the lineage process type from archetype and sourceObjectType.
     * If sourceObjectType indicates an attachment (e.g. "attachment", "file"),
     * uses EXTERNAL_ATTACHMENT_IMPORT regardless of archetype.
     */
    private static LineageProcessType resolveProcessType(SourceArchetype archetype, String sourceObjectType) {
        // Attachment detection: if the source object is an attachment/file within
        // a compound note or chat context, use the attachment-specific process type.
        if (sourceObjectType != null) {
            String lower = sourceObjectType.toLowerCase();
            if ("attachment".equals(lower) || "file".equals(lower)) {
                return LineageProcessType.EXTERNAL_ATTACHMENT_IMPORT;
            }
        }
        if (archetype == null) {
            return LineageProcessType.IMPORT_UPLOADED;
        }
        return switch (archetype) {
            case FILE_SHARE -> LineageProcessType.FILE_SHARE_SYNC_DOWNLOAD;
            case COMPOUND_NOTE -> LineageProcessType.EXTERNAL_NOTE_IMPORT;
            case CHAT_CONTEXT -> LineageProcessType.CHAT_ATTACHMENT_IMPORT;
            case BUSINESS_RECORD -> LineageProcessType.BUSINESS_RECORD_IMPORT;
        };
    }
}
