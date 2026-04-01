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
        String repositoryId = request.getRepositoryId();
        String targetFolderId = profile.getTargetFolderId();
        if (targetFolderId == null || targetFolderId.isBlank()) {
            return ExternalIngestResult.error(requestId, "Profile has no targetFolderId configured");
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
            String lineageEventId = emitLineageEvent(repositoryId, objectId, connector, request);

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

            List<Property> extProps = new ArrayList<>();
            extProps.add(new Property("nemaki:sourceArchetype",
                    connector.getSourceArchetype() != null ? connector.getSourceArchetype().name() : ""));
            extProps.add(new Property("nemaki:sourceSystem",
                    connector.getSourceSystem() != null ? connector.getSourceSystem() : ""));
            extProps.add(new Property("nemaki:sourceObjectType",
                    request.getSourceObjectType() != null ? request.getSourceObjectType() : ""));
            extProps.add(new Property("nemaki:sourceObjectId",
                    request.getSourceObjectId() != null ? request.getSourceObjectId() : ""));
            extProps.add(new Property("nemaki:sourceUrl",
                    request.getSourceUrl() != null ? request.getSourceUrl() : ""));
            extProps.add(new Property("nemaki:ingestionRunId", request.getRequestId()));
            extProps.add(new Property("nemaki:externalSourceType",
                    connector.getSourceArchetype() != null ? connector.getSourceArchetype().name().toLowerCase() : ""));
            extProps.add(new Property("nemaki:externalSourceId",
                    connector.getSourceSystem() != null ? connector.getSourceSystem() : ""));
            extProps.add(new Property("nemaki:externalContextUpdatedAt", new GregorianCalendar()));

            if (extAspect != null) {
                extAspect.setProperties(extProps);
            } else {
                aspects.add(new Aspect("nemaki:externalIntegration", extProps));
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

    private String emitLineageEvent(String repositoryId, String objectId,
                                    ConnectorDefinition connector, ExternalIngestRequest request) {
        try {
            LineageProcessType processType = resolveProcessType(connector.getSourceArchetype());
            String sourceUri = ExternalSourceUri.build(
                    connector.getSourceSystem(),
                    connector.getTenantId(),
                    request.getSourceObjectType(),
                    request.getSourceObjectId());

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

    private static LineageProcessType resolveProcessType(SourceArchetype archetype) {
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
