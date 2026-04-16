package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.service.ObjectService;
import jp.aegif.nemaki.cmis.service.VersioningService;
import jp.aegif.nemaki.dao.ContentDaoService;
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
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.aegif.nemaki.rest.ingest.mail.MailMessageParser;
import jp.aegif.nemaki.rest.ingest.mail.MailMessageParser.ParsedMailMessage;
import jp.aegif.nemaki.rest.ingest.mail.MailMessageParser.ParsedAttachment;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
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
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    /** Idempotency key TTL: 7 days.  After this period the key is considered
     *  expired and a new import with the same key will proceed normally. */
    private static final long IDEMPOTENCY_TTL_MS = 7L * 24 * 60 * 60 * 1000;

    private ConnectorDefinitionService connectorDefinitionService;
    private ImportProfileDefinitionService importProfileDefinitionService;
    private ContentService contentService;
    private ContentDaoService contentDaoService;
    private ObjectService objectService;
    private VersioningService versioningService;
    private NemakiCachePool nemakiCachePool;
    private IngestJobService ingestJobService;
    private jp.aegif.nemaki.cmis.service.RelationshipService relationshipService;

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

    public void setContentDaoService(ContentDaoService contentDaoService) {
        this.contentDaoService = contentDaoService;
    }

    public void setObjectService(ObjectService objectService) {
        this.objectService = objectService;
    }

    public void setVersioningService(VersioningService versioningService) {
        this.versioningService = versioningService;
    }

    public void setNemakiCachePool(NemakiCachePool nemakiCachePool) {
        this.nemakiCachePool = nemakiCachePool;
    }

    public void setIngestJobService(IngestJobService ingestJobService) {
        this.ingestJobService = ingestJobService;
    }

    public void setRelationshipService(jp.aegif.nemaki.cmis.service.RelationshipService relationshipService) {
        this.relationshipService = relationshipService;
    }

    /**
     * Cloud Drive UI and REST use short provider ids ({@code google}, {@code microsoft}) while
     * scheduler docs and some deployments register FILE_SHARE connectors as {@code google_drive} /
     * {@code onedrive}. Try aliases so canonical cloud import auto-resolves either way.
     */
    static List<String> connectorLookupKeysForAutoResolve(String sourceSystem, SourceArchetype archetype) {
        if (sourceSystem == null || sourceSystem.isBlank()) {
            return List.of();
        }
        if (archetype == SourceArchetype.FILE_SHARE) {
            return switch (sourceSystem) {
                case "google" -> List.of("google", "google_drive");
                case "google_drive" -> List.of("google_drive", "google");
                case "microsoft" -> List.of("microsoft", "onedrive");
                case "onedrive" -> List.of("onedrive", "microsoft");
                default -> List.of(sourceSystem);
            };
        }
        return List.of(sourceSystem);
    }

    @Override
    public ExternalIngestResult executeWithAutoResolve(CallContext callContext, ExternalIngestRequest request,
                                                       String sourceSystem, SourceArchetype archetype) {
        String requestId = request.getRequestId();

        // Auto-resolve connector if not explicitly set
        if (request.getConnectorId() == null || request.getConnectorId().isBlank()) {
            ConnectorDefinition autoConnector = null;
            List<String> keysTried = connectorLookupKeysForAutoResolve(sourceSystem, archetype);
            for (String key : keysTried) {
                autoConnector = connectorDefinitionService.findBySystemAndArchetype(key, archetype);
                if (autoConnector != null) {
                    break;
                }
            }
            if (autoConnector == null) {
                String hint = keysTried.size() > 1
                        ? " (looked up as: " + String.join(", ", keysTried) + ")"
                        : "";
                return ExternalIngestResult.error(requestId,
                        "No enabled connector found for sourceSystem='" + sourceSystem + "'" + hint + ", archetype=" + archetype
                        + ". Create a connector definition via /v1/admin/connectors first.");
            }
            request.setConnectorId(autoConnector.getConnectorId());
        }

        // Auto-resolve profile if not explicitly set
        if (request.getProfileId() == null || request.getProfileId().isBlank()) {
            ImportProfileDefinition autoProfile;
            try {
                autoProfile = importProfileDefinitionService
                        .findDefaultForRepository(request.getRepositoryId(), archetype, request.getConnectorId());
            } catch (IllegalStateException e) {
                // Ambiguous: multiple profiles match — fail closed, do NOT fall through
                return ExternalIngestResult.error(requestId, e.getMessage());
            }
            if (autoProfile == null) {
                return ExternalIngestResult.error(requestId,
                        "No enabled import profile found for repository='" + request.getRepositoryId()
                        + "', archetype=" + archetype
                        + ". Create an import profile via /v1/admin/import-profiles first.");
            }
            request.setProfileId(autoProfile.getProfileId());
        }

        return execute(callContext, request);
    }

    @Override
    public ExternalIngestResult executeMailImport(CallContext callContext, ExternalIngestRequest request) {
        String requestId = request.getRequestId();

        if (request.getContentStream() == null) {
            return ExternalIngestResult.error(requestId, "Content stream (.eml file) is required for mail import");
        }

        try {
            // 1. Buffer raw .eml bytes before parsing (for optional original preservation)
            ByteArrayOutputStream rawEmlBuffer = new ByteArrayOutputStream();
            request.getContentStream().transferTo(rawEmlBuffer);
            byte[] rawEmlBytes = rawEmlBuffer.toByteArray();

            // 1b. Parse .eml from buffered bytes
            MailMessageParser parser = new MailMessageParser();
            ParsedMailMessage parsed = parser.parse(new ByteArrayInputStream(rawEmlBytes));

            // 2. Build metadata from parsed envelope
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (parsed.messageId() != null) metadata.put("internetMessageId", parsed.messageId());
            if (parsed.subject() != null) metadata.put("subject", parsed.subject());
            if (parsed.from() != null) metadata.put("from", parsed.from());
            if (parsed.to() != null) metadata.put("to", parsed.to());
            if (parsed.cc() != null) metadata.put("cc", parsed.cc());
            if (parsed.inReplyTo() != null) metadata.put("inReplyTo", parsed.inReplyTo());
            if (parsed.references() != null) metadata.put("references", parsed.references());
            // Preserve caller metadata (mailboxId, messageStableId, etc.)
            if (request.getMetadata() != null) {
                for (Map.Entry<String, Object> entry : request.getMetadata().entrySet()) {
                    metadata.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
            request.setMetadata(metadata);

            // 3. Import message body as main document
            String bodyText = parsed.textBody() != null ? parsed.textBody()
                    : (parsed.htmlBody() != null ? parsed.htmlBody() : "");
            String bodyMimeType = parsed.htmlBody() != null && parsed.textBody() == null
                    ? "text/html" : "text/plain";
            String subject = parsed.subject() != null ? parsed.subject() : "Untitled Message";

            request.setFileName(sanitizeFilename(subject) + (bodyMimeType.contains("html") ? ".html" : ".txt"));
            request.setMimeType(bodyMimeType);
            request.setContentStream(new ByteArrayInputStream(bodyText.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if (request.getSourceObjectType() == null || request.getSourceObjectType().isBlank()) {
                request.setSourceObjectType("message");
            }

            ExternalIngestResult messageResult = execute(callContext, request);
            if (!messageResult.isSuccess()) {
                return messageResult;
            }

            String messageObjectId = messageResult.objectId();

            // 4. Apply nemaki:messageMetadata secondary type
            List<String> warnings = new ArrayList<>(messageResult.warnings());
            String metaError = applyMessageMetadata(request.getRepositoryId(), messageObjectId, callContext, parsed, request);
            if (metaError != null) warnings.add(metaError);

            // 4b. Preserve raw .eml as a separate document if profile requests it
            ImportProfileDefinition mailProfile = request.getProfileId() != null
                    ? importProfileDefinitionService.get(request.getProfileId()) : null;
            if (mailProfile != null && mailProfile.isPreserveOriginalEml() && rawEmlBytes.length > 0) {
                try {
                    ExternalIngestRequest emlReq = new ExternalIngestRequest();
                    emlReq.setProfileId(request.getProfileId());
                    emlReq.setConnectorId(request.getConnectorId());
                    emlReq.setRepositoryId(request.getRepositoryId());
                    emlReq.setSourceObjectId(request.getSourceObjectId());
                    emlReq.setSourceObjectType("eml_original");
                    emlReq.setFileName(sanitizeFilename(subject) + ".eml");
                    emlReq.setMimeType("message/rfc822");
                    emlReq.setContentStream(new ByteArrayInputStream(rawEmlBytes));
                    emlReq.setExecutionMode(request.getExecutionMode());
                    emlReq.setMetadata(new LinkedHashMap<>(metadata));

                    ExternalIngestResult emlResult = execute(callContext, emlReq);
                    if (emlResult.isSuccess()) {
                        String relErr = createDirectRelationship(callContext, request.getRepositoryId(),
                                messageObjectId, emlResult.objectId(), "nemaki:hasAttachment");
                        if (relErr != null) warnings.add(relErr);
                    } else if (!emlResult.skipped()) {
                        warnings.add("Raw .eml preservation failed: " + String.join(", ", emlResult.errors()));
                    }
                } catch (Exception e) {
                    warnings.add("Raw .eml preservation failed: " + e.getMessage());
                }
            }

            // 5. Import attachments as separate documents with relationship
            int attachmentCount = 0;
            for (ParsedAttachment att : parsed.attachments()) {
                try {
                    ExternalIngestRequest attReq = new ExternalIngestRequest();
                    attReq.setProfileId(request.getProfileId());
                    attReq.setConnectorId(request.getConnectorId());
                    attReq.setRepositoryId(request.getRepositoryId());
                    attReq.setSourceObjectId(request.getSourceObjectId() + "/att-" + att.partIndex());
                    attReq.setSourceObjectType("attachment");
                    attReq.setFileName(att.filename());
                    attReq.setMimeType(att.mimeType());
                    attReq.setContentStream(new ByteArrayInputStream(att.content()));
                    attReq.setExecutionMode(request.getExecutionMode());
                    // Do NOT set parentObjectId here — relationship is created directly
                    // via createDirectRelationship after execute() to avoid duplicates.
                    Map<String, Object> attMeta = new LinkedHashMap<>();
                    attMeta.put("mailboxId", metadata.get("mailboxId"));
                    attMeta.put("messageStableId", request.getSourceObjectId());
                    attReq.setMetadata(attMeta);

                    ExternalIngestResult attResult = execute(callContext, attReq);
                    if (attResult.isSuccess()) {
                        attachmentCount++;
                        // Create typed relationship (not dependent on profile policy)
                        String relErr = createDirectRelationship(callContext, request.getRepositoryId(),
                                messageObjectId, attResult.objectId(), "nemaki:hasAttachment");
                        if (relErr != null) warnings.add(relErr);
                    } else {
                        warnings.add("Attachment '" + att.filename() + "' import failed: "
                                + String.join(", ", attResult.errors()));
                    }
                } catch (Exception e) {
                    warnings.add("Attachment '" + att.filename() + "' failed: " + e.getMessage());
                }
            }

            logger.info("Mail import completed: messageId={}, objectId={}, attachments={}",
                    parsed.messageId(), messageObjectId, attachmentCount);

            return new ExternalIngestResult(requestId, messageObjectId, messageResult.versionLabel(),
                    messageResult.isNewVersion(), false, false, null, messageResult.lineageEventId(),
                    List.of(), warnings);

        } catch (Exception e) {
            logger.error("Mail import failed: {}", e.getMessage(), e);
            return ExternalIngestResult.error(requestId, "Mail import failed: " + e.getMessage());
        }
    }

    @Override
    public ExternalIngestResult executeNoteImport(CallContext callContext, ExternalIngestRequest request) {
        String requestId = request.getRequestId();

        // Set sourceObjectType to "page" if not specified
        if (request.getSourceObjectType() == null || request.getSourceObjectType().isBlank()) {
            request.setSourceObjectType("page");
        }

        // Import page body as main document
        ExternalIngestResult pageResult = execute(callContext, request);
        if (!pageResult.isSuccess()) {
            return pageResult;
        }

        String pageObjectId = pageResult.objectId();
        List<String> warnings = new ArrayList<>(pageResult.warnings());

        // Apply nemaki:noteMetadata
        String metaError = applyNoteMetadata(request.getRepositoryId(), pageObjectId, callContext, request);
        if (metaError != null) warnings.add(metaError);

        // Import attachments from metadata if provided
        int attachmentCount = 0;
        if (request.getMetadata() != null && request.getMetadata().get("attachments") instanceof List<?> attList) {
            for (Object attObj : attList) {
                if (!(attObj instanceof Map<?, ?> attMap)) continue;
                try {
                    ExternalIngestRequest attReq = new ExternalIngestRequest();
                    attReq.setProfileId(request.getProfileId());
                    attReq.setConnectorId(request.getConnectorId());
                    attReq.setRepositoryId(request.getRepositoryId());
                    Object attId = attMap.get("attachmentId");
                    attReq.setSourceObjectId(request.getSourceObjectId() + "/att-" + (attId != null ? attId : attachmentCount));
                    attReq.setSourceObjectType("attachment");
                    Object fn = attMap.get("filename");
                    attReq.setFileName(fn instanceof String s ? s : "attachment-" + attachmentCount);
                    Object mt = attMap.get("mimeType");
                    attReq.setMimeType(mt instanceof String s ? s : "application/octet-stream");
                    attReq.setExecutionMode(request.getExecutionMode());
                    // Do NOT set parentObjectId — relationship created via createDirectRelationship
                    attReq.setMetadata(new LinkedHashMap<>());
                    // Decode attachment content from base64 in metadata if provided
                    Object contentB64 = attMap.get("contentBase64");
                    if (contentB64 instanceof String b64 && !b64.isBlank()) {
                        byte[] bytes = java.util.Base64.getDecoder().decode(b64);
                        attReq.setContentStream(new ByteArrayInputStream(bytes));
                    } else {
                        // No content available — skip this attachment with warning
                        warnings.add("Attachment '" + attReq.getFileName() + "' has no content (provide contentBase64 in metadata)");
                        continue;
                    }
                    ExternalIngestResult attResult = execute(callContext, attReq);
                    if (attResult.isSuccess()) {
                        attachmentCount++;
                        String relErr = createDirectRelationship(callContext, request.getRepositoryId(),
                                pageObjectId, attResult.objectId(), "nemaki:hasAttachment");
                        if (relErr != null) warnings.add(relErr);
                    } else {
                        warnings.add("Attachment import failed: " + String.join(", ", attResult.errors()));
                    }
                } catch (Exception e) {
                    warnings.add("Attachment failed: " + e.getMessage());
                }
            }
        }

        logger.info("Note import completed: objectId={}, attachments={}, profile={}",
                pageObjectId, attachmentCount, request.getProfileId());

        return new ExternalIngestResult(requestId, pageObjectId, pageResult.versionLabel(),
                pageResult.isNewVersion(), false, false, null, pageResult.lineageEventId(),
                List.of(), warnings);
    }

    /**
     * Apply nemaki:noteMetadata secondary type from request metadata.
     */
    private String applyNoteMetadata(String repositoryId, String objectId, CallContext callContext,
                                     ExternalIngestRequest request) {
        try {
            Content content = contentService.getContent(repositoryId, objectId);
            if (content == null) return "Content not found: " + objectId;

            List<Aspect> aspects = content.getAspects();
            if (aspects == null) aspects = new ArrayList<>();

            List<Property> noteProps = new ArrayList<>();
            Map<String, Object> meta = request.getMetadata();
            if (meta != null) {
                addStringProp(noteProps, "nemaki:notePageId", meta.get("pageId"));
                addStringProp(noteProps, "nemaki:notePageUrl", meta.get("pageUrl"));
                addStringProp(noteProps, "nemaki:noteParentPageId", meta.get("parentPageId"));
                addStringProp(noteProps, "nemaki:noteWorkspaceId", meta.get("workspaceId"));
                addStringProp(noteProps, "nemaki:noteAuthor", meta.get("author"));
                addStringProp(noteProps, "nemaki:noteLastEditedBy", meta.get("lastEditedBy"));
            }

            if (!noteProps.isEmpty()) {
                Aspect existingNote = aspects.stream()
                        .filter(a -> "nemaki:noteMetadata".equals(a.getName())).findFirst().orElse(null);
                if (existingNote != null) {
                    Map<String, Property> m = new LinkedHashMap<>();
                    if (existingNote.getProperties() != null) for (Property p : existingNote.getProperties()) m.put(p.getKey(), p);
                    for (Property p : noteProps) m.put(p.getKey(), p);
                    existingNote.setProperties(new ArrayList<>(m.values()));
                } else {
                    aspects.add(new Aspect("nemaki:noteMetadata", noteProps));
                }
                List<String> secondaryIds = content.getSecondaryIds();
                if (secondaryIds == null) secondaryIds = new ArrayList<>();
                if (!secondaryIds.contains("nemaki:noteMetadata")) {
                    secondaryIds.add("nemaki:noteMetadata");
                }
                content.setSecondaryIds(secondaryIds);
                content.setAspects(aspects);
                contentService.update(callContext, repositoryId, content);

                if (nemakiCachePool != null) {
                    try { nemakiCachePool.get(repositoryId).removeCmisAndContentCache(objectId); }
                    catch (Exception e) { /* ignore */ }
                }
            }
            return null;
        } catch (Exception e) {
            logger.warn("Failed to apply noteMetadata to {}: {}", objectId, e.getMessage());
            return "Note metadata failed: " + e.getMessage();
        }
    }

    @Override
    public ExternalIngestResult executeBusinessRecordImport(CallContext callContext, ExternalIngestRequest request) {
        if (request.getSourceObjectType() == null || request.getSourceObjectType().isBlank()) {
            request.setSourceObjectType("record");
        }
        ExternalIngestResult result = execute(callContext, request);
        if (!result.isSuccess()) return result;

        List<String> warnings = new ArrayList<>(result.warnings());
        String metaError = applyArchetypeMetadata(request.getRepositoryId(), result.objectId(), callContext,
                "nemaki:businessRecordMetadata", request, new String[][]{
                        {"nemaki:recordType", "recordType"}, {"nemaki:recordId", "recordId"},
                        {"nemaki:recordUrl", "recordUrl"}, {"nemaki:recordStatus", "recordStatus"},
                        {"nemaki:recordOwner", "recordOwner"}, {"nemaki:processInstanceId", "processInstanceId"},
                });
        if (metaError != null) warnings.add(metaError);

        // Create attachedToRecord relationship if parentRecordId is provided
        if (request.getMetadata() != null) {
            String parentRecordId = resolveMetadataString(request, "parentRecordId");
            if (parentRecordId != null) {
                String relErr = createDirectRelationship(callContext, request.getRepositoryId(),
                        parentRecordId, result.objectId(), "nemaki:attachedToRecord");
                if (relErr != null) warnings.add(relErr);
            }
        }

        return new ExternalIngestResult(request.getRequestId(), result.objectId(), result.versionLabel(),
                result.isNewVersion(), false, false, null, result.lineageEventId(), List.of(), warnings);
    }

    @Override
    public ExternalIngestResult executeChatContextImport(CallContext callContext, ExternalIngestRequest request) {
        if (request.getSourceObjectType() == null || request.getSourceObjectType().isBlank()) {
            request.setSourceObjectType("message");
        }
        ExternalIngestResult result = execute(callContext, request);
        if (!result.isSuccess()) return result;

        List<String> warnings = new ArrayList<>(result.warnings());
        String metaError = applyArchetypeMetadata(request.getRepositoryId(), result.objectId(), callContext,
                "nemaki:chatContextMetadata", request, new String[][]{
                        {"nemaki:chatWorkspaceId", "workspaceId"}, {"nemaki:chatChannelId", "channelId"},
                        {"nemaki:chatChannelName", "channelName"}, {"nemaki:chatThreadId", "threadId"},
                        {"nemaki:chatMessageId", "messageId"}, {"nemaki:chatParticipants", "participants"},
                        {"nemaki:chatSelectionReason", "selectionReason"},
                        {"nemaki:chatEvidenceScope", "evidenceScope"},
                });
        if (metaError != null) warnings.add(metaError);

        // Apply capture window datetime properties if provided in metadata
        if (request.getMetadata() != null) {
            String windowStart = resolveMetadataString(request, "captureWindowStart");
            String windowEnd = resolveMetadataString(request, "captureWindowEnd");
            if (windowStart != null || windowEnd != null) {
                try {
                    Content chatContent = contentService.getContent(request.getRepositoryId(), result.objectId());
                    if (chatContent != null) {
                        List<Aspect> chatAspects = chatContent.getAspects() != null ? chatContent.getAspects() : new ArrayList<>();
                        Aspect chatAspect = chatAspects.stream()
                                .filter(a -> "nemaki:chatContextMetadata".equals(a.getName())).findFirst().orElse(null);
                        if (chatAspect != null && chatAspect.getProperties() != null) {
                            Map<String, Property> propMap = new java.util.LinkedHashMap<>();
                            for (Property p : chatAspect.getProperties()) propMap.put(p.getKey(), p);
                            if (windowStart != null) {
                                GregorianCalendar gc = new GregorianCalendar();
                                gc.setTimeInMillis(java.time.Instant.parse(windowStart).toEpochMilli());
                                propMap.put("nemaki:chatCaptureWindowStart", new Property("nemaki:chatCaptureWindowStart", gc));
                            }
                            if (windowEnd != null) {
                                GregorianCalendar gc = new GregorianCalendar();
                                gc.setTimeInMillis(java.time.Instant.parse(windowEnd).toEpochMilli());
                                propMap.put("nemaki:chatCaptureWindowEnd", new Property("nemaki:chatCaptureWindowEnd", gc));
                            }
                            chatAspect.setProperties(new ArrayList<>(propMap.values()));
                            contentService.update(callContext, request.getRepositoryId(), chatContent);
                        }
                    }
                } catch (Exception e) {
                    warnings.add("Capture window metadata failed: " + e.getMessage());
                }
            }
        }

        // Create derivedFromContext relationship if parentContextId is provided
        if (request.getMetadata() != null) {
            String parentContextId = resolveMetadataString(request, "parentContextId");
            if (parentContextId != null) {
                String relErr = createDirectRelationship(callContext, request.getRepositoryId(),
                        parentContextId, result.objectId(), "nemaki:derivedFromContext");
                if (relErr != null) warnings.add(relErr);
            }
        }

        return new ExternalIngestResult(request.getRequestId(), result.objectId(), result.versionLabel(),
                result.isNewVersion(), false, false, null, result.lineageEventId(), List.of(), warnings);
    }

    /**
     * Generic archetype metadata applicator — attaches a secondary type with
     * properties read from request metadata.
     */
    private String applyArchetypeMetadata(String repositoryId, String objectId, CallContext callContext,
                                          String secondaryTypeId, ExternalIngestRequest request,
                                          String[][] fieldMappings) {
        try {
            Content content = contentService.getContent(repositoryId, objectId);
            if (content == null) return "Content not found: " + objectId;

            List<Aspect> aspects = content.getAspects();
            if (aspects == null) aspects = new ArrayList<>();

            List<Property> props = new ArrayList<>();
            Map<String, Object> meta = request.getMetadata();
            if (meta != null) {
                for (String[] mapping : fieldMappings) {
                    addStringProp(props, mapping[0], meta.get(mapping[1]));
                }
            }
            if (!props.isEmpty()) {
                // Update existing aspect or add new one
                Aspect existingAspect = aspects.stream()
                        .filter(a -> secondaryTypeId.equals(a.getName()))
                        .findFirst().orElse(null);
                if (existingAspect != null) {
                    // Merge: preserve existing, overwrite with new
                    Map<String, Property> merged = new LinkedHashMap<>();
                    if (existingAspect.getProperties() != null) {
                        for (Property p : existingAspect.getProperties()) merged.put(p.getKey(), p);
                    }
                    for (Property p : props) merged.put(p.getKey(), p);
                    existingAspect.setProperties(new ArrayList<>(merged.values()));
                } else {
                    aspects.add(new Aspect(secondaryTypeId, props));
                }
                List<String> secondaryIds = content.getSecondaryIds();
                if (secondaryIds == null) secondaryIds = new ArrayList<>();
                if (!secondaryIds.contains(secondaryTypeId)) secondaryIds.add(secondaryTypeId);
                content.setSecondaryIds(secondaryIds);
                content.setAspects(aspects);
                contentService.update(callContext, repositoryId, content);
                if (nemakiCachePool != null) {
                    try { nemakiCachePool.get(repositoryId).removeCmisAndContentCache(objectId); }
                    catch (Exception e) { /* */ }
                }
            }
            return null;
        } catch (Exception e) {
            logger.warn("Failed to apply {} to {}: {}", secondaryTypeId, objectId, e.getMessage());
            return secondaryTypeId + " metadata failed: " + e.getMessage();
        }
    }

    /**
     * Creates a CMIS relationship unconditionally (not dependent on profile policy).
     * Used by specialized import flows (mail, note) where parent-child relationships
     * are inherent to the archetype.
     */
    String createDirectRelationship(CallContext callContext, String repositoryId,
                                            String sourceId, String targetId) {
        return createDirectRelationship(callContext, repositoryId, sourceId, targetId, "cmis:relationship");
    }

    /**
     * Creates a typed CMIS relationship.
     * Falls back to generic cmis:relationship if the custom type is not available.
     */
    String createDirectRelationship(CallContext callContext, String repositoryId,
                                            String sourceId, String targetId, String relationshipTypeId) {
        try {
            PropertiesImpl relProps = new PropertiesImpl();
            relProps.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, relationshipTypeId));
            relProps.addProperty(new PropertyIdImpl(PropertyIds.SOURCE_ID, sourceId));
            relProps.addProperty(new PropertyIdImpl(PropertyIds.TARGET_ID, targetId));
            objectService.createRelationship(callContext, repositoryId, relProps, null, null, null, null);
            return null;
        } catch (Exception e) {
            // Fallback to generic cmis:relationship if custom type fails
            if (!"cmis:relationship".equals(relationshipTypeId)) {
                logger.debug("Custom relationship type {} failed, falling back to cmis:relationship", relationshipTypeId);
                return createDirectRelationship(callContext, repositoryId, sourceId, targetId, "cmis:relationship");
            }
            logger.warn("Relationship {} → {} failed: {}", sourceId, targetId, e.getMessage());
            return "Relationship failed: " + e.getMessage();
        }
    }

    /**
     * Computes SHA-256 hash of content bytes.
     */
    private static String computeContentHash(byte[] content) {
        if (content == null || content.length == 0) return null;
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(content));
        } catch (Exception e) {
            return null;
        }
    }

    private static void addStringProp(List<Property> props, String key, Object value) {
        if (value instanceof String s && !s.isBlank()) {
            props.add(new Property(key, s));
        }
    }

    /** Emit audit event for external ingest operations. */
    private void emitAuditEvent(String repositoryId, String objectId,
                                CallContext callContext, boolean success, String error) {
        try {
            var ctx = jp.aegif.nemaki.util.spring.SpringContext.getApplicationContext();
            if (ctx == null) return;
            var audit = ctx.getBean("auditLogger", jp.aegif.nemaki.audit.AuditLogger.class);
            if (audit == null) return;
            String userId = callContext != null ? callContext.getUsername() : "system";
            jp.aegif.nemaki.audit.AuditOperation op = success
                    ? jp.aegif.nemaki.audit.AuditOperation.EXTERNAL_INGEST
                    : jp.aegif.nemaki.audit.AuditOperation.EXTERNAL_INGEST_FAILED;
            audit.logOperation(op, repositoryId, userId, objectId, success, error);
        } catch (Exception e) {
            logger.debug("Audit event emission failed: {}", e.getMessage());
        }
    }

    /**
     * Apply ACL sync policy after document creation/update.
     * - inherit_from_folder: no-op (CMIS default)
     * - none: break ACL inheritance, leaving only admin ACL
     * - copy_from_source: reserved for future adapter integration
     */
    private void applyAclSyncPolicy(CallContext callContext, String repositoryId,
                                     String objectId, String policy,
                                     Content content) {
        if ("inherit_from_folder".equals(policy) || policy.isBlank()) {
            return; // CMIS default — nothing to do
        }
        if ("none".equals(policy)) {
            // Break inheritance: set the content to not inherit parent ACL
            try {
                content.setAclInherited(false);
                contentService.update(callContext, repositoryId, content);
                logger.info("ACL inheritance disabled for imported document {}", objectId);
            } catch (Exception e) {
                logger.warn("Failed to break ACL inheritance for {}: {}", objectId, e.getMessage());
            }
        }
        if ("copy_from_source".equals(policy)) {
            applySourceAcl(callContext, repositoryId, objectId, content);
        }
    }

    /**
     * Apply ACL from source system metadata.
     * Expects metadata key "sourceAcl" as a List of maps with "principalId" and "permissions" keys.
     * Example: [{"principalId": "user1", "permissions": ["cmis:read"]}, ...]
     */
    @SuppressWarnings("unchecked")
    private void applySourceAcl(CallContext callContext, String repositoryId,
                                 String objectId, Content content) {
        try {
            // Read sourceAcl from the persisted externalContext
            String contextJson = null;
            if (content.getAspects() != null) {
                for (var aspect : content.getAspects()) {
                    if ("nemaki:externalIntegration".equals(aspect.getName()) && aspect.getProperties() != null) {
                        for (var prop : aspect.getProperties()) {
                            if ("nemaki:externalContext".equals(prop.getKey()) && prop.getValue() instanceof String s) {
                                contextJson = s;
                            }
                        }
                    }
                }
            }
            if (contextJson == null) return;

            Map<String, Object> context = JSON_MAPPER.readValue(contextJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            Object sourceAclObj = context.get("sourceAcl");
            if (!(sourceAclObj instanceof List<?> sourceAclList) || sourceAclList.isEmpty()) return;

            // Break inheritance first
            content.setAclInherited(false);

            // Build ACL from source
            List<jp.aegif.nemaki.model.Ace> localAces = new ArrayList<>();
            for (Object entry : sourceAclList) {
                if (!(entry instanceof Map<?, ?> aceMap)) continue;
                String principalId = aceMap.get("principalId") instanceof String s ? s : null;
                Object permsObj = aceMap.get("permissions");
                if (principalId == null || permsObj == null) continue;

                List<String> permissions;
                if (permsObj instanceof List<?> permList) {
                    permissions = permList.stream()
                            .filter(p -> p instanceof String)
                            .map(p -> (String) p)
                            .toList();
                } else if (permsObj instanceof String s) {
                    permissions = List.of(s);
                } else {
                    continue;
                }

                localAces.add(new jp.aegif.nemaki.model.Ace(principalId, permissions, true));
            }

            if (!localAces.isEmpty()) {
                jp.aegif.nemaki.model.Acl acl = content.getAcl() != null ? content.getAcl()
                        : new jp.aegif.nemaki.model.Acl();
                acl.setLocalAces(localAces);
                content.setAcl(acl);
                contentService.update(callContext, repositoryId, content);
                logger.info("Applied {} source ACEs to imported document {}", localAces.size(), objectId);
            }
        } catch (Exception e) {
            logger.warn("Failed to apply source ACL for {}: {}", objectId, e.getMessage());
        }
    }

    /**
     * Check if the parent context has changed between the existing document and the incoming request.
     * Compares archetype-specific context fields stored in nemaki:externalContext.
     */
    private boolean hasParentContextChanged(Content existingDoc, ExternalIngestRequest request) {
        if (request.getMetadata() == null) return false;
        try {
            String existingContextJson = getAspectProperty(existingDoc, "nemaki:externalIntegration", "nemaki:externalContext");
            if (existingContextJson == null) return false;
            Map<String, Object> existingContext = JSON_MAPPER.readValue(existingContextJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            // Compare parent-context fields: channelId, threadId, parentPageId, mailboxId
            for (String key : List.of("channelId", "threadId", "parentPageId", "workspaceId", "mailboxId")) {
                Object existing = existingContext.get(key);
                Object incoming = request.getMetadata().get(key);
                if (existing != null && incoming != null && !existing.toString().equals(incoming.toString())) {
                    logger.info("Parent context changed: {}={} → {}", key, existing, incoming);
                    return true;
                }
            }
        } catch (Exception e) {
            logger.debug("Parent context comparison failed: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Remove all existing CMIS relationships where the given object is the source.
     * Used by replace_relationships_on_resync policy.
     */
    private void removeExistingRelationships(CallContext callContext, String repositoryId, String objectId) {
        if (relationshipService == null) return;
        try {
            int totalRemoved = 0;
            java.math.BigInteger batchSize = java.math.BigInteger.valueOf(100);
            java.math.BigInteger skipCount = java.math.BigInteger.ZERO;
            // Paginate to handle documents with many relationships
            while (true) {
                org.apache.chemistry.opencmis.commons.data.ObjectList rels = relationshipService.getObjectRelationships(
                        callContext, repositoryId, objectId, true,
                        org.apache.chemistry.opencmis.commons.enums.RelationshipDirection.SOURCE,
                        null, null, false, batchSize, skipCount, null);
                if (rels == null || rels.getObjects() == null || rels.getObjects().isEmpty()) break;

                for (var relData : rels.getObjects()) {
                    try {
                        objectService.deleteObject(callContext, repositoryId,
                                relData.getId(), true, null);
                        totalRemoved++;
                    } catch (Exception e) {
                        logger.warn("Failed to remove relationship {}: {}", relData.getId(), e.getMessage());
                    }
                }
                // After deleting, re-fetch from start (indices shift after deletion)
                if (!Boolean.TRUE.equals(rels.hasMoreItems())) break;
            }
            if (totalRemoved > 0) {
                logger.info("Resync: removed {} relationships from {}", totalRemoved, objectId);
            }
        } catch (Exception e) {
            logger.warn("Failed to query relationships for {}: {}", objectId, e.getMessage());
        }
    }

    private String sanitizeFilename(String name) {
        if (name == null) return "untitled";
        return name.replaceAll("[/\\\\:*?\"<>|]", "_").trim();
    }

    /**
     * Deep-copy metadata map, stripping {@code contentBase64} keys from nested
     * attachment entries to prevent multi-MB binary blobs from being persisted
     * into {@code nemaki:externalContext}.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> stripBinaryContent(Map<String, Object> metadata) {
        Map<String, Object> result = new LinkedHashMap<>(metadata);
        Object attachments = result.get("attachments");
        if (attachments instanceof List<?> attList) {
            List<Map<String, Object>> cleaned = new ArrayList<>();
            for (Object item : attList) {
                if (item instanceof Map<?, ?> attMap) {
                    Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) attMap);
                    copy.remove("contentBase64");
                    cleaned.add(copy);
                }
            }
            result.put("attachments", cleaned);
        }
        return result;
    }

    /**
     * Apply nemaki:messageMetadata secondary type to a message document.
     */
    private String applyMessageMetadata(String repositoryId, String objectId, CallContext callContext,
                                        ParsedMailMessage parsed, ExternalIngestRequest request) {
        try {
            Content content = contentService.getContent(repositoryId, objectId);
            if (content == null) return "Content not found: " + objectId;

            List<Aspect> aspects = content.getAspects();
            if (aspects == null) aspects = new ArrayList<>();

            List<Property> msgProps = new ArrayList<>();
            if (parsed.messageId() != null) msgProps.add(new Property("nemaki:internetMessageId", parsed.messageId()));
            if (parsed.subject() != null) msgProps.add(new Property("nemaki:mailSubject", parsed.subject()));
            if (parsed.from() != null) msgProps.add(new Property("nemaki:mailFrom", parsed.from()));
            if (parsed.to() != null) msgProps.add(new Property("nemaki:mailTo", parsed.to()));
            if (parsed.cc() != null) msgProps.add(new Property("nemaki:mailCc", parsed.cc()));
            if (parsed.inReplyTo() != null) msgProps.add(new Property("nemaki:mailInReplyTo", parsed.inReplyTo()));
            if (parsed.references() != null) msgProps.add(new Property("nemaki:mailReferences", parsed.references()));
            if (parsed.sentDate() != null) {
                GregorianCalendar cal = new GregorianCalendar();
                cal.setTime(parsed.sentDate());
                msgProps.add(new Property("nemaki:mailSentAt", cal));
            }
            if (parsed.receivedDate() != null) {
                GregorianCalendar cal = new GregorianCalendar();
                cal.setTime(parsed.receivedDate());
                msgProps.add(new Property("nemaki:mailReceivedAt", cal));
            }
            String mailboxId = resolveMetadataString(request, "mailboxId");
            if (mailboxId != null) msgProps.add(new Property("nemaki:mailboxId", mailboxId));
            String messageStableId = resolveMetadataString(request, "messageStableId");
            if (messageStableId != null) msgProps.add(new Property("nemaki:messageStableId", messageStableId));

            Aspect existingMsg = aspects.stream()
                    .filter(a -> "nemaki:messageMetadata".equals(a.getName())).findFirst().orElse(null);
            if (existingMsg != null) {
                Map<String, Property> m = new LinkedHashMap<>();
                if (existingMsg.getProperties() != null) for (Property p : existingMsg.getProperties()) m.put(p.getKey(), p);
                for (Property p : msgProps) m.put(p.getKey(), p);
                existingMsg.setProperties(new ArrayList<>(m.values()));
            } else {
                aspects.add(new Aspect("nemaki:messageMetadata", msgProps));
            }

            List<String> secondaryIds = content.getSecondaryIds();
            if (secondaryIds == null) secondaryIds = new ArrayList<>();
            if (!secondaryIds.contains("nemaki:messageMetadata")) {
                secondaryIds.add("nemaki:messageMetadata");
            }
            content.setSecondaryIds(secondaryIds);
            content.setAspects(aspects);
            contentService.update(callContext, repositoryId, content);

            if (nemakiCachePool != null) {
                try { nemakiCachePool.get(repositoryId).removeCmisAndContentCache(objectId); }
                catch (Exception e) { logger.debug("Cache invalidation: {}", e.getMessage()); }
            }
            return null;
        } catch (Exception e) {
            logger.warn("Failed to apply messageMetadata to {}: {}", objectId, e.getMessage());
            return "Message metadata failed: " + e.getMessage();
        }
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

        // 3b. Archetype-specific validation
        if (connector.getSourceArchetype() == SourceArchetype.CHAT_CONTEXT) {
            String channelId = resolveChannelId(request);
            if (channelId == null) {
                return ExternalIngestResult.error(requestId,
                        "metadata.channelId is required for CHAT_CONTEXT archetype");
            }
        }
        if (connector.getSourceArchetype() == SourceArchetype.MESSAGE_CONTEXT) {
            String mailboxId = resolveMetadataString(request, "mailboxId");
            if (mailboxId == null) {
                return ExternalIngestResult.error(requestId,
                        "metadata.mailboxId is required for MESSAGE_CONTEXT archetype");
            }
            // Require messageStableId for attachment imports to prevent non-canonical URIs
            if (isAttachmentObjectType(request.getSourceObjectType())) {
                String msgStableId = resolveMetadataString(request, "messageStableId");
                if (msgStableId == null) {
                    return ExternalIngestResult.error(requestId,
                            "metadata.messageStableId is required for MESSAGE_CONTEXT attachment imports");
                }
            }
        }

        // 4. Resolve target folder
        // (needed for both dry-run and real execution)
        String targetFolderId = (request.getTargetFolderOverride() != null && !request.getTargetFolderOverride().isBlank())
                ? request.getTargetFolderOverride()
                : resolveTargetFolderId(profile, repositoryId, callContext);
        if (targetFolderId == null || targetFolderId.isBlank()) {
            return ExternalIngestResult.error(requestId,
                    "Profile has no resolvable target folder (neither targetFolderId nor targetFolderPath)");
        }

        byte[] bufferedContent = null; // retained for DLQ on failure
        try {
            String objectTypeId = profile.getDefaultObjectTypeId();
            if (objectTypeId == null || objectTypeId.isBlank()) {
                objectTypeId = "cmis:document";
            }

            String fileName = request.getFileName();
            if (fileName == null || fileName.isBlank()) {
                fileName = "imported-" + request.getSourceObjectId();
            }

            ContentStream contentStream = null;
            String computedHash = null;
            if (request.getContentStream() != null) {
                String mimeType = request.getMimeType() != null ? request.getMimeType() : "application/octet-stream";
                // Buffer content to compute hash for dedupe and persistence
                ByteArrayOutputStream contentBuf = new ByteArrayOutputStream();
                request.getContentStream().transferTo(contentBuf);
                byte[] contentBytes = contentBuf.toByteArray();
                bufferedContent = contentBytes; // retain for DLQ if this import fails
                computedHash = computeContentHash(contentBytes);
                contentStream = new ContentStreamImpl(fileName, BigInteger.valueOf(contentBytes.length),
                        mimeType, new ByteArrayInputStream(contentBytes));
            }

            // 5a. Dedupe: check for existing document by source identity
            String dedupePolicy = profile.getDedupePolicy() != null ? profile.getDedupePolicy() : "create_new_version";
            Content existingDoc = findExistingDocument(repositoryId, targetFolderId, fileName,
                    connector.getSourceSystem(), request.getSourceObjectId(), request.getSourceObjectType());

            // 5b. Idempotency check: skip if same key already succeeded
            if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank()) {
                // Check persisted idempotency record (works for all dedupe modes).
                // Value format: "objectId|epochMillis" — TTL is 7 days.
                String idempKey = "ingest.idempotency." + request.getIdempotencyKey();
                try {
                    var ctx = jp.aegif.nemaki.util.spring.SpringContext.getApplicationContext();
                    if (ctx != null) {
                        var settings = ctx.getBean(jp.aegif.nemaki.rest.controller.IntegrationSettingsService.class);
                        String existing = settings.readSetting(idempKey);
                        if (existing != null && !existing.isBlank()) {
                            String existingObjectId = existing;
                            // Parse TTL: if value contains "|", extract objectId and timestamp
                            int sep = existing.indexOf('|');
                            if (sep > 0) {
                                existingObjectId = existing.substring(0, sep);
                                try {
                                    long savedAt = Long.parseLong(existing.substring(sep + 1));
                                    long ageMs = System.currentTimeMillis() - savedAt;
                                    if (ageMs > IDEMPOTENCY_TTL_MS) {
                                        logger.info("Idempotency key expired after {}h, allowing re-import: {}",
                                                ageMs / 3_600_000, request.getIdempotencyKey());
                                        settings.deleteSettings(java.util.Set.of(idempKey));
                                        // Fall through to normal import
                                    } else {
                                        return ExternalIngestResult.skipped(requestId, existingObjectId,
                                                "Idempotent: request '" + request.getIdempotencyKey() + "' already completed");
                                    }
                                } catch (NumberFormatException nfe) {
                                    // Legacy value without timestamp — honour it
                                    return ExternalIngestResult.skipped(requestId, existingObjectId,
                                            "Idempotent: request '" + request.getIdempotencyKey() + "' already completed");
                                }
                            } else {
                                // Legacy value without "|" separator
                                return ExternalIngestResult.skipped(requestId, existingObjectId,
                                        "Idempotent: request '" + request.getIdempotencyKey() + "' already completed");
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Idempotency check failed: {}", e.getMessage());
                }
            }

            // 5c. Dry-run: return preview without making changes
            if (request.isDryRun()) {
                boolean wouldBeNewVersion = existingDoc != null && existingDoc.isDocument();
                String existingId = wouldBeNewVersion ? existingDoc.getId() : null;
                return ExternalIngestResult.dryRun(requestId, existingId, wouldBeNewVersion);
            }

            String objectId;
            boolean isNewVersion = false;
            String versionLabel = "1.0";

            if (existingDoc != null && existingDoc.isDocument()) {
                // Existing document found — apply dedupe policy
                if ("skip_if_same_version".equals(dedupePolicy)) {
                    return ExternalIngestResult.skipped(requestId, existingDoc.getId(),
                            "Document already exists with same source identity");
                }
                if ("replace".equals(dedupePolicy)) {
                    // Delete existing and create fresh
                    try {
                        objectService.deleteObject(callContext, repositoryId, existingDoc.getId(), true, null);
                        logger.info("Dedupe replace: deleted existing document {}", existingDoc.getId());
                    } catch (Exception e) {
                        logger.warn("Dedupe replace: failed to delete {}: {}", existingDoc.getId(), e.getMessage());
                    }
                    existingDoc = null; // Fall through to create new
                }
                if ("create_new_if_parent_context_changed".equals(dedupePolicy)) {
                    // Compare parent context fields — if changed, create new document
                    if (hasParentContextChanged(existingDoc, request)) {
                        logger.info("Dedupe: parent context changed for {}, creating new document", existingDoc.getId());
                        existingDoc = null; // Fall through to create new
                    }
                }
                if ("replace_relationships_on_resync".equals(dedupePolicy) && existingDoc != null) {
                    // Delete existing relationships before re-import
                    removeExistingRelationships(callContext, repositoryId, existingDoc.getId());
                }

                // "create_new_version" (default): no special dedupe action — falls through
                // to updatePolicy which governs whether a new version is actually created
            }

            // Re-check existingDoc: dedupe policies (replace, parent_context_changed)
            // may have nullified it, requiring new-document creation instead
            if (existingDoc != null && existingDoc.isDocument()) {
                String updatePolicy = profile.getUpdatePolicy() != null ? profile.getUpdatePolicy() : "version_up_on_content_change";
                objectId = existingDoc.getId();

                if ("update_metadata_only".equals(updatePolicy)) {
                    // Update metadata only — no version change, no content update
                    versionLabel = "metadata-only";
                    logger.info("Dedupe: metadata-only update for existing document {}", objectId);
                } else if ("always_version_up".equals(updatePolicy)) {
                    // Always create a new version regardless of content changes
                    isNewVersion = true;
                    Holder<String> objectIdHolder = new Holder<>(objectId);
                    Holder<Boolean> contentCopied = new Holder<>(Boolean.FALSE);
                    versioningService.checkOut(callContext, repositoryId, objectIdHolder, contentCopied, null);
                    String pwcId = objectIdHolder.getValue();
                    boolean isMajor = !"minor".equalsIgnoreCase(profile.getVersioningPolicy());
                    Holder<String> checkinHolder = new Holder<>(pwcId);
                    versioningService.checkIn(callContext, repositoryId, checkinHolder, isMajor,
                            null, contentStream, "Imported from " + connector.getSourceSystem(),
                            null, null, null, null);
                    objectId = checkinHolder.getValue();
                    versionLabel = "new version (always)";
                } else {
                    // version_up_on_content_change (default): compare content hash before versioning
                    boolean contentChanged;
                    if (computedHash == null) {
                        // No content stream provided — treat as metadata-only update, not version-up
                        contentChanged = false;
                        versionLabel = "metadata-only (no content provided)";
                        logger.info("Dedupe: no content stream for {}, metadata-only update", objectId);
                    } else {
                        String existingHash = getAspectProperty(existingDoc, "nemaki:externalIntegration", "nemaki:contentHash");
                        if (computedHash.equals(existingHash)) {
                            contentChanged = false;
                            versionLabel = "metadata-only (content unchanged)";
                            logger.info("Dedupe: content unchanged for {} (hash={}), metadata-only", objectId, computedHash);
                        } else {
                            contentChanged = true;
                        }
                    }
                    if (contentChanged) {
                        isNewVersion = true;
                        Holder<String> objectIdHolder = new Holder<>(objectId);
                        Holder<Boolean> contentCopied = new Holder<>(Boolean.FALSE);
                        versioningService.checkOut(callContext, repositoryId, objectIdHolder, contentCopied, null);
                        String pwcId = objectIdHolder.getValue();

                        boolean isMajor = !"minor".equalsIgnoreCase(profile.getVersioningPolicy());
                        Holder<String> checkinHolder = new Holder<>(pwcId);
                        versioningService.checkIn(callContext, repositoryId, checkinHolder, isMajor,
                                null, contentStream, "Imported from " + connector.getSourceSystem(),
                                null, null, null, null);
                        objectId = checkinHolder.getValue();
                        versionLabel = "new version";
                        logger.info("Dedupe: updated existing document {} with new version", objectId);
                    }
                }
            } else {
                // New document
                PropertiesImpl properties = new PropertiesImpl();
                properties.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, objectTypeId));
                properties.addProperty(new PropertyStringImpl(PropertyIds.NAME, fileName));
                VersioningState vs = "minor".equalsIgnoreCase(profile.getVersioningPolicy())
                        ? VersioningState.MINOR
                        : "none".equalsIgnoreCase(profile.getVersioningPolicy())
                                ? VersioningState.NONE
                                : VersioningState.MAJOR;
                objectId = objectService.createDocument(callContext, repositoryId, properties,
                        targetFolderId, contentStream, vs, null, null, null, null);
            }

            // 6. Apply secondary types
            List<String> warnings = new ArrayList<>();
            String metadataError = applySourceMetadata(repositoryId, objectId, callContext, connector, request, profile, computedHash);
            if (metadataError != null) {
                warnings.add(metadataError);
            }

            // 6b. Create relationship if parentObjectId is provided and policy allows
            String relError = applyRelationship(callContext, repositoryId, objectId, profile, request);
            if (relError != null) {
                warnings.add(relError);
            }

            // 7. Emit lineage event
            String lineageEventId = emitLineageEvent(repositoryId, objectId, targetFolderId, connector, request);

            logger.info("Canonical import completed: requestId={}, objectId={}, profile={}, connector={}",
                    requestId, objectId, profile.getProfileId(), connector.getConnectorId());

            // Save idempotency record if key was provided
            if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank()) {
                try {
                    var ctx = jp.aegif.nemaki.util.spring.SpringContext.getApplicationContext();
                    if (ctx != null) {
                        var settings = ctx.getBean(jp.aegif.nemaki.rest.controller.IntegrationSettingsService.class);
                        settings.writeSetting("ingest.idempotency." + request.getIdempotencyKey(),
                                objectId + "|" + System.currentTimeMillis());
                    }
                } catch (Exception e) {
                    logger.debug("Idempotency save failed: {}", e.getMessage());
                }
            }

            // Audit log
            emitAuditEvent(request.getRepositoryId(), objectId, callContext, true, null);

            return new ExternalIngestResult(requestId, objectId, versionLabel, isNewVersion,
                    false, false, null, lineageEventId, List.of(), warnings);

        } catch (Exception e) {
            logger.error("Canonical import failed: requestId={}, error={}", requestId, e.getMessage(), e);
            emitAuditEvent(request.getRepositoryId(), null, callContext, false, e.getMessage());

            boolean isTransient = isTransientError(e);
            // Save to dead-letter queue for retry only for non-manual and
            // non-permanent errors.  Permanent errors (config/validation)
            // would never succeed on retry and just pollute the DLQ.
            if (ingestJobService != null && !"manual".equals(request.getExecutionMode())) {
                if (isTransient) {
                    try {
                        ingestJobService.saveToDlq(request, e.getMessage(), bufferedContent);
                    } catch (Exception dlqErr) {
                        logger.debug("Failed to save to DLQ: {}", dlqErr.getMessage());
                    }
                } else {
                    logger.info("Permanent error — not sending to DLQ: requestId={}, error={}",
                            requestId, e.getMessage());
                }
            }
            return ExternalIngestResult.error(requestId,
                    (isTransient ? "[transient] " : "[permanent] ") + e.getMessage());
        }
    }

    /**
     * @return error message if metadata application failed, null on success
     */
    /**
     * Classify whether an import error is transient (worth retrying) or
     * permanent (config/validation — will never succeed on retry).
     *
     * Transient: network timeouts, CouchDB/Solr unavailability, HTTP 429/503,
     * optimistic lock conflicts, temporary I/O failures.
     *
     * Permanent: profile/connector not found, archetype not allowed,
     * repository mismatch, dry-run (should not reach here), parse errors,
     * type definition errors, permission denied.
     */
    private boolean isTransientError(Exception e) {
        // Walk the cause chain looking for known transient signals
        Throwable t = e;
        for (int depth = 0; t != null && depth < 5; depth++, t = t.getCause()) {
            String name = t.getClass().getName();
            // Network / I/O
            if (t instanceof java.net.SocketTimeoutException
                    || t instanceof java.net.ConnectException
                    || t instanceof java.net.NoRouteToHostException
                    || t instanceof java.net.UnknownHostException) {
                return true;
            }
            // CouchDB / HTTP 409 conflict (optimistic lock)
            if (name.contains("UpdateConflictException") || name.contains("DocumentConflict")) {
                return true;
            }
            // HTTP status in message (crude but catches adapter wrappers)
            String msg = t.getMessage();
            if (msg != null) {
                if (msg.contains("429") || msg.contains("503") || msg.contains("502")
                        || msg.contains("504") || msg.contains("Read timed out")
                        || msg.contains("Connection reset") || msg.contains("Connection refused")) {
                    return true;
                }
            }
        }
        return false;
    }

    private String applySourceMetadata(String repositoryId, String objectId, CallContext callContext,
                                       ConnectorDefinition connector, ExternalIngestRequest request,
                                       ImportProfileDefinition profile, String contentHash) {
        try {
            Content content = contentService.getContent(repositoryId, objectId);
            if (content == null) {
                logger.warn("Content not found after creation: {}", objectId);
                return "Content not found after creation: " + objectId;
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
            // Strip binary content (contentBase64) to avoid bloating CouchDB documents
            if (request.getMetadata() != null && !request.getMetadata().isEmpty()) {
                Map<String, Object> sanitized = stripBinaryContent(request.getMetadata());
                String contextJson = JSON_MAPPER.writeValueAsString(sanitized);
                newProps.put("nemaki:externalContext", contextJson);
            }

            // Store content hash for future dedupe comparisons
            if (contentHash != null && !contentHash.isBlank()) {
                newProps.put("nemaki:contentHash", contentHash);
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
            // Apply profile-defined secondary types
            if (profile != null && profile.getSecondaryTypeIds() != null) {
                for (String secTypeId : profile.getSecondaryTypeIds()) {
                    if (secTypeId != null && !secTypeId.isBlank() && !secondaryIds.contains(secTypeId)) {
                        secondaryIds.add(secTypeId);
                    }
                }
            }
            // Apply retention if configured
            if (profile != null && profile.getRetentionDays() != null && profile.getRetentionDays() > 0) {
                if (!secondaryIds.contains("cmis:rm_clientMgtRetention")) {
                    secondaryIds.add("cmis:rm_clientMgtRetention");
                }
                GregorianCalendar expiration = new GregorianCalendar();
                expiration.add(java.util.Calendar.DAY_OF_MONTH, profile.getRetentionDays());
                List<Property> retProps = new ArrayList<>();
                retProps.add(new Property("cmis:rm_expirationDate", expiration));
                Aspect retAspect = aspects.stream()
                        .filter(a -> "cmis:rm_clientMgtRetention".equals(a.getName()))
                        .findFirst().orElse(null);
                if (retAspect != null) {
                    retAspect.setProperties(retProps);
                } else {
                    aspects.add(new Aspect("cmis:rm_clientMgtRetention", retProps));
                }
            }

            // Apply default classification if configured
            if (profile != null && profile.getDefaultClassification() != null
                    && !profile.getDefaultClassification().isBlank()) {
                if (!secondaryIds.contains("nemaki:classificationInfo")) {
                    secondaryIds.add("nemaki:classificationInfo");
                }
                List<Property> classProps = new ArrayList<>();
                classProps.add(new Property("nemaki:classification",
                        List.of(profile.getDefaultClassification())));
                Aspect classAspect = aspects.stream()
                        .filter(a -> "nemaki:classificationInfo".equals(a.getName()))
                        .findFirst().orElse(null);
                if (classAspect != null) {
                    // Merge: preserve existing properties, overwrite classification
                    Map<String, Property> propMap = new java.util.LinkedHashMap<>();
                    if (classAspect.getProperties() != null) {
                        for (Property p : classAspect.getProperties()) propMap.put(p.getKey(), p);
                    }
                    for (Property p : classProps) propMap.put(p.getKey(), p);
                    classAspect.setProperties(new ArrayList<>(propMap.values()));
                } else {
                    aspects.add(new Aspect("nemaki:classificationInfo", classProps));
                }
            }

            // Apply nemaki:cloudDriveMetadata if cloud metadata present in request
            String cloudProvider = resolveMetadataString(request, "cloudProvider");
            if (cloudProvider != null && connector.getSourceArchetype() == SourceArchetype.FILE_SHARE) {
                if (!secondaryIds.contains("nemaki:cloudDriveMetadata")) {
                    secondaryIds.add("nemaki:cloudDriveMetadata");
                }
                List<Property> cloudProps = new ArrayList<>();
                cloudProps.add(new Property("nemaki:cloudProvider", cloudProvider));
                String cloudFileId = resolveMetadataString(request, "cloudFileId");
                if (cloudFileId != null) cloudProps.add(new Property("nemaki:cloudFileId", cloudFileId));
                String cloudFileUrl = resolveMetadataString(request, "cloudFileUrl");
                if (cloudFileUrl != null) cloudProps.add(new Property("nemaki:cloudFileUrl", cloudFileUrl));
                cloudProps.add(new Property("nemaki:cloudLastSyncedAt", new GregorianCalendar()));

                Aspect existingCloud = aspects.stream()
                        .filter(a -> "nemaki:cloudDriveMetadata".equals(a.getName()))
                        .findFirst().orElse(null);
                if (existingCloud != null) {
                    Map<String, Property> merged = new java.util.LinkedHashMap<>();
                    if (existingCloud.getProperties() != null) {
                        for (Property p : existingCloud.getProperties()) merged.put(p.getKey(), p);
                    }
                    for (Property p : cloudProps) merged.put(p.getKey(), p);
                    existingCloud.setProperties(new ArrayList<>(merged.values()));
                } else {
                    aspects.add(new Aspect("nemaki:cloudDriveMetadata", cloudProps));
                }
            }

            content.setSecondaryIds(secondaryIds);
            content.setAspects(aspects);

            contentService.update(callContext, repositoryId, content);

            // Apply ACL sync policy
            if (profile != null && profile.getAclSyncPolicy() != null) {
                applyAclSyncPolicy(callContext, repositoryId, objectId, profile.getAclSyncPolicy(), content);
            }

            // Invalidate cache
            if (nemakiCachePool != null) {
                try {
                    nemakiCachePool.get(repositoryId).removeCmisAndContentCache(objectId);
                } catch (Exception e) {
                    logger.debug("Cache invalidation failed for {}: {}", objectId, e.getMessage());
                }
            }
            return null;
        } catch (Exception e) {
            logger.warn("Failed to apply source metadata to {}: {}", objectId, e.getMessage());
            return "Source metadata application failed: " + e.getMessage();
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
     * Creates a CMIS relationship from parentObjectId to the imported document
     * if the profile's relationshipPolicy is set and metadata contains parentObjectId.
     *
     * @return error message if failed, null on success or no-op
     */
    private String applyRelationship(CallContext callContext, String repositoryId, String objectId,
                                     ImportProfileDefinition profile, ExternalIngestRequest request) {
        String relPolicy = profile.getRelationshipPolicy();
        if (relPolicy == null || "none".equalsIgnoreCase(relPolicy)) {
            return null;
        }
        String parentObjectId = resolveMetadataString(request, "parentObjectId");
        if (parentObjectId == null) {
            return null; // No parent specified — skip silently
        }
        try {
            PropertiesImpl relProps = new PropertiesImpl();
            relProps.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, "cmis:relationship"));
            relProps.addProperty(new PropertyIdImpl(PropertyIds.SOURCE_ID, parentObjectId));
            relProps.addProperty(new PropertyIdImpl(PropertyIds.TARGET_ID, objectId));

            objectService.createRelationship(callContext, repositoryId, relProps, null, null, null, null);
            logger.info("Created relationship: {} → {}", parentObjectId, objectId);
            return null;
        } catch (Exception e) {
            logger.warn("Failed to create relationship {} → {}: {}", parentObjectId, objectId, e.getMessage());
            return "Relationship creation failed: " + e.getMessage();
        }
    }

    /**
     * Finds an existing document in the target folder by source identity or filename.
     * Priority: sourceSystem + sourceObjectId match > filename match.
     * Only considers documents (not folders) in the target folder.
     */
    private Content findExistingDocument(String repositoryId, String targetFolderId,
                                         String fileName, String sourceSystem, String sourceObjectId,
                                         String sourceObjectType) {
        if (contentDaoService == null) return null;

        // First pass: search by sourceObjectId in target folder children
        if (sourceObjectId != null && !sourceObjectId.isBlank()) {
            try {
                List<Content> children = contentDaoService.getChildren(repositoryId, targetFolderId);
                if (children != null) {
                    for (Content child : children) {
                        if (child == null || !child.isDocument()) continue;
                        String existingSourceId = getAspectProperty(child, "nemaki:externalIntegration", "nemaki:sourceObjectId");
                        String existingSourceSystem = getAspectProperty(child, "nemaki:externalIntegration", "nemaki:sourceSystem");
                        String existingSourceType = getAspectProperty(child, "nemaki:externalIntegration", "nemaki:sourceObjectType");
                        if (sourceObjectId.equals(existingSourceId)
                                && (sourceSystem == null || sourceSystem.equals(existingSourceSystem))
                                && (sourceObjectType == null || sourceObjectType.equals(existingSourceType))) {
                            logger.debug("Dedupe: found existing document {} by sourceObjectId={}", child.getId(), sourceObjectId);
                            return child;
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("Dedupe source-identity search failed: {}", e.getMessage());
            }
        }

        // No source-identity match found — return null (new document will be created).
        // Filename-based fallback was removed to prevent conflating distinct external
        // objects that happen to share the same name.
        return null;
    }

    private static String getAspectProperty(Content content, String aspectName, String propertyKey) {
        if (content.getAspects() == null) return null;
        for (Aspect aspect : content.getAspects()) {
            if (aspectName.equals(aspect.getName()) && aspect.getProperties() != null) {
                for (Property prop : aspect.getProperties()) {
                    if (propertyKey.equals(prop.getKey())) {
                        return prop.getValue() instanceof String s ? s : null;
                    }
                }
            }
        }
        return null;
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
                        folderPath, null, Boolean.FALSE,
                        org.apache.chemistry.opencmis.commons.enums.IncludeRelationships.NONE,
                        null, Boolean.FALSE, Boolean.FALSE, null);
                if (objectData != null && objectData.getId() != null) {
                    // Verify it's actually a folder, not a document
                    Object baseTypeId = objectData.getProperties() != null
                            ? objectData.getProperties().getProperties().get("cmis:baseTypeId")
                            : null;
                    String baseType = baseTypeId instanceof org.apache.chemistry.opencmis.commons.data.PropertyData<?> pd
                            ? String.valueOf(pd.getFirstValue()) : null;
                    if (baseType != null && !"cmis:folder".equals(baseType)) {
                        logger.warn("targetFolderPath '{}' resolved to a {} ({}), not a folder",
                                folderPath, baseType, objectData.getId());
                        return null;
                    }
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
        boolean isAttachment = isAttachmentObjectType(request.getSourceObjectType());
        return switch (archetype) {
            case FILE_SHARE -> ExternalSourceUri.forFileShare(system, tenant, objectId);
            case COMPOUND_NOTE -> {
                // Attachments within notes use the note system's files path
                if (isAttachment) {
                    yield ExternalSourceUri.build(system, tenant, "files", objectId);
                }
                yield ExternalSourceUri.forNotePage(system, tenant, objectId);
            }
            case CHAT_CONTEXT -> {
                String channelId = resolveChannelId(request);
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

    private static boolean isAttachmentObjectType(String sourceObjectType) {
        if (sourceObjectType == null) return false;
        String lower = sourceObjectType.toLowerCase();
        return "attachment".equals(lower) || "file".equals(lower);
    }

    private static String resolveChannelId(ExternalIngestRequest request) {
        return resolveMetadataString(request, "channelId");
    }

    private static String resolveMetadataString(ExternalIngestRequest request, String key) {
        if (request.getMetadata() != null) {
            Object val = request.getMetadata().get(key);
            if (val instanceof String s && !s.isBlank()) return s;
        }
        return null;
    }

    /**
     * Resolves the lineage process type from archetype and sourceObjectType.
     * If sourceObjectType indicates an attachment (e.g. "attachment", "file"),
     * uses EXTERNAL_ATTACHMENT_IMPORT regardless of archetype.
     */
    private static LineageProcessType resolveProcessType(SourceArchetype archetype, String sourceObjectType) {
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
}
