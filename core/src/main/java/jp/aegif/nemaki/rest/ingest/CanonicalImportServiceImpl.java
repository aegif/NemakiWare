package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.service.ObjectService;
import jp.aegif.nemaki.cmis.service.VersioningService;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
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
    private static final tools.jackson.databind.ObjectMapper JSON_MAPPER = new tools.jackson.databind.ObjectMapper();

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
    // Optional beans — injected via setter to avoid SpringContext service locator
    private jp.aegif.nemaki.audit.AuditLogger auditLogger;
    private jp.aegif.nemaki.rest.controller.IntegrationSettingsService integrationSettingsService;
    private IngestMetadataService ingestMetadataService;
    private IngestLineageEmitter ingestLineageEmitter;

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

    public void setAuditLogger(jp.aegif.nemaki.audit.AuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

    public void setIntegrationSettingsService(jp.aegif.nemaki.rest.controller.IntegrationSettingsService service) {
        this.integrationSettingsService = service;
    }

    public void setIngestMetadataService(IngestMetadataService ingestMetadataService) {
        this.ingestMetadataService = ingestMetadataService;
    }

    public void setIngestLineageEmitter(IngestLineageEmitter ingestLineageEmitter) {
        this.ingestLineageEmitter = ingestLineageEmitter;
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

        // Early validation: check profile AND connector BEFORE expensive EML parsing
        if (request.getProfileId() != null && importProfileDefinitionService != null) {
            ImportProfileDefinition profile = importProfileDefinitionService.get(request.getProfileId());
            if (profile == null) {
                return ExternalIngestResult.error(requestId, "Import profile not found: " + request.getProfileId());
            }
            if (!profile.isEnabled()) {
                return ExternalIngestResult.error(requestId, "Import profile is disabled: " + request.getProfileId());
            }
            String repositoryId = request.getRepositoryId();
            if (profile.getRepositoryId() != null && !profile.getRepositoryId().equals(repositoryId)) {
                return ExternalIngestResult.error(requestId, "Profile repository mismatch");
            }
            if (request.getConnectorId() != null && connectorDefinitionService != null) {
                ConnectorDefinition conn = connectorDefinitionService.get(request.getConnectorId());
                if (conn == null) {
                    return ExternalIngestResult.error(requestId, "Connector not found: " + request.getConnectorId());
                }
                if (!conn.isEnabled()) {
                    return ExternalIngestResult.error(requestId, "Connector is disabled: " + request.getConnectorId());
                }
                if (!profile.isConnectorAllowed(conn.getConnectorId())) {
                    return ExternalIngestResult.error(requestId, "Connector not allowed for this profile");
                }
            }
        }

        try {
            // 1. Buffer raw .eml bytes before parsing (for optional original preservation)
            byte[] rawEmlBytes;
            try (java.io.InputStream emlIn = request.getContentStream()) {
                rawEmlBytes = readBounded(emlIn, MAX_CONTENT_SIZE, "Mail (.eml) content");
            }

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
            // An empty/pseudo-file skip (objectId == null) produced no object to
            // decorate — return it verbatim. A dedupe skip (objectId != null)
            // falls through: the existing message is re-decorated and, crucially,
            // any previously-failed attachment is retried against the existing
            // objectId. The dedupe skip flag is preserved in the final return
            // below so the orchestrator still counts it as skipped, not imported.
            if (messageResult.skipped() && messageResult.objectId() == null) {
                return messageResult;
            }

            String messageObjectId = messageResult.objectId();

            // 4. Apply nemaki:messageMetadata secondary type
            List<String> warnings = new ArrayList<>(messageResult.warnings());
            String metaError = ingestMetadataService.applyMessageMetadata(request.getRepositoryId(), messageObjectId, callContext, parsed, request);
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
                    // A child's warnings are the parent's problem: provenance lost on the raw
                    // .eml would otherwise vanish before the client ever sees it (P1-1).
                    mergeChildWarnings(warnings, "raw .eml", emlResult);
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
                    mergeChildWarnings(warnings, "attachment '" + att.filename() + "'", attResult);
                    if (attResult.isSuccess() || attResult.skipped()) {
                        attachmentCount++;
                        // Create/update typed relationship
                        String existingId = attResult.isSuccess() ? attResult.objectId()
                                : (attResult.skipped() ? attResult.objectId() : null);
                        if (existingId != null) {
                            String relErr = createDirectRelationship(callContext, request.getRepositoryId(),
                                    messageObjectId, existingId, "nemaki:hasAttachment");
                            if (relErr != null) warnings.add(relErr);
                        }
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

            // Preserve the skipped flag/reason: a dedupe-skipped message body that
            // fell through above must still be reported as skipped, not imported.
            return new ExternalIngestResult(requestId, messageObjectId, messageResult.versionLabel(),
                    messageResult.isNewVersion(), false, messageResult.skipped(), messageResult.skipReason(),
                    messageResult.lineageEventId(), List.of(), warnings);

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

        // files_only (default): do NOT create a document for the page body
        // (HTML). Import only the attached files, carrying the page's
        // identity/text as metadata on each attachment (nemaki:noteMetadata
        // + nemaki:externalContext). files_and_body: keep the page body as a
        // document and link attachments to it (legacy behaviour).
        boolean importBody = "files_and_body".equals(request.getImportPolicy());

        String pageObjectId = null;
        List<String> warnings = new ArrayList<>();
        String pageVersionLabel = null;
        boolean pageNewVersion = false;
        String pageLineageEventId = null;
        boolean pageSkipped = false;     // files_and_body: page body was dedupe-skipped
        String pageSkipReason = null;

        if (importBody) {
            ExternalIngestResult pageResult = execute(callContext, request);
            if (!pageResult.isSuccess()) {
                return pageResult;
            }
            pageObjectId = pageResult.objectId();
            pageVersionLabel = pageResult.versionLabel();
            pageNewVersion = pageResult.isNewVersion();
            pageLineageEventId = pageResult.lineageEventId();
            pageSkipped = pageResult.skipped();
            pageSkipReason = pageResult.skipReason();
            warnings.addAll(pageResult.warnings());
            // Apply nemaki:noteMetadata to the page document
            String metaError = ingestMetadataService.applyNoteMetadata(request.getRepositoryId(), pageObjectId, callContext, request);
            if (metaError != null) warnings.add(metaError);
        }

        // Import attachments from metadata if provided
        int attachmentCount = 0;
        int importedAttachmentCount = 0;   // genuinely new/updated attachments
        int skippedAttachmentCount = 0;    // dedupe-skipped attachments
        String firstAttachmentObjectId = null;
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
                    // In files_only mode the page body is not imported, so carry the
                    // page's metadata (id/url/parent/workspace + any body text the
                    // orchestrator put in metadata) onto the attachment so it isn't
                    // lost. Strip the heavy attachment list to avoid recursion.
                    Map<String, Object> attMeta = new LinkedHashMap<>();
                    if (!importBody && request.getMetadata() != null) {
                        for (Map.Entry<String, Object> e : request.getMetadata().entrySet()) {
                            if (!"attachments".equals(e.getKey())) attMeta.put(e.getKey(), e.getValue());
                        }
                    }
                    attReq.setMetadata(attMeta);
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
                    ExternalIngestResult attResult;
                    if (!importBody) {
                        // attachment carries note metadata since there is no page doc
                        attResult = executeNoteAttachment(callContext, attReq, request);
                    } else {
                        attResult = execute(callContext, attReq);
                    }
                    mergeChildWarnings(warnings, "attachment", attResult);
                    if (attResult.isSuccess() || attResult.skipped()) {
                        attachmentCount++;
                        // isSuccess() is true even for a skipped result (it only
                        // means "no errors"), so test skipped() first.
                        if (attResult.skipped()) skippedAttachmentCount++;
                        else importedAttachmentCount++;
                        String attObjectId = attResult.objectId();
                        if (attObjectId != null) {
                            if (firstAttachmentObjectId == null) firstAttachmentObjectId = attObjectId;
                            if (importBody && pageObjectId != null) {
                                String relErr = createDirectRelationship(callContext, request.getRepositoryId(),
                                        pageObjectId, attObjectId, "nemaki:hasAttachment");
                                if (relErr != null) warnings.add(relErr);
                            }
                        }
                    } else {
                        warnings.add("Attachment import failed: " + String.join(", ", attResult.errors()));
                    }
                } catch (Exception e) {
                    warnings.add("Attachment failed: " + e.getMessage());
                }
            }
        }

        // In files_only mode, if nothing new was imported for this page (no
        // attachments at all, or every attachment was dedupe-skipped) report
        // the page as skipped — not imported — so run stats are accurate and
        // the checkpoint still advances.
        if (!importBody && pageObjectId == null && importedAttachmentCount == 0) {
            String skipReason = skippedAttachmentCount > 0
                    ? "files_only: all " + skippedAttachmentCount + " attachment(s) already imported"
                    : "files_only: page has no attachments";
            // (requestId, objectId, versionLabel, isNewVersion, dryRun,
            //  skipped, skipReason, lineageEventId, errors, warnings)
            return new ExternalIngestResult(requestId, null, null, false, false, true,
                    skipReason, null, List.of(), warnings);
        }

        String primaryObjectId = importBody ? pageObjectId : firstAttachmentObjectId;
        logger.info("Note import completed: pageObjectId={}, attachments={}, importPolicy={}, profile={}",
                pageObjectId, attachmentCount, request.getImportPolicy(), request.getProfileId());

        // files_and_body: if the page body was dedupe-skipped and no attachment
        // was newly imported either, nothing new was created — report skipped so
        // the orchestrator counts it accurately (the files_only path is already
        // handled by the early return above).
        boolean overallSkipped = importBody && pageSkipped && importedAttachmentCount == 0;
        String overallSkipReason = overallSkipped ? pageSkipReason : null;

        return new ExternalIngestResult(requestId, primaryObjectId, pageVersionLabel,
                pageNewVersion, false, overallSkipped, overallSkipReason, pageLineageEventId,
                List.of(), warnings);
    }

    /**
     * Import a Notion attachment as a standalone document and apply the note
     * metadata (from the originating page request) to it. Used in files_only
     * mode where the page body document is not created, so the attachment
     * becomes the carrier of the page's source identity / text.
     */
    private ExternalIngestResult executeNoteAttachment(CallContext callContext,
            ExternalIngestRequest attReq, ExternalIngestRequest pageRequest) {
        ExternalIngestResult attResult = execute(callContext, attReq);
        if (attResult.isSuccess() && attResult.objectId() != null) {
            // Reuse the page's metadata for the note-metadata secondary type.
            String metaError = ingestMetadataService.applyNoteMetadata(
                    attReq.getRepositoryId(), attResult.objectId(), callContext, pageRequest);
            if (metaError != null) {
                List<String> w = new ArrayList<>(attResult.warnings());
                w.add(metaError);
                return new ExternalIngestResult(attResult.requestId(), attResult.objectId(),
                        attResult.versionLabel(), attResult.isNewVersion(), false, false, null,
                        attResult.lineageEventId(), List.of(), w);
            }
        }
        return attResult;
    }

    // applyNoteMetadata → delegated to IngestMetadataService

    @Override
    public ExternalIngestResult executeBusinessRecordImport(CallContext callContext, ExternalIngestRequest request) {
        if (request.getSourceObjectType() == null || request.getSourceObjectType().isBlank()) {
            request.setSourceObjectType("record");
        }
        ExternalIngestResult result = execute(callContext, request);
        if (!result.isSuccess()) return result;
        // An empty/pseudo-file skip (objectId == null) has no object to decorate;
        // return it verbatim. A dedupe skip (objectId != null) falls through to
        // re-apply metadata/relationship idempotently, and its skip flag is
        // preserved in the final return below so it is counted as skipped.
        if (result.skipped() && result.objectId() == null) return result;

        List<String> warnings = new ArrayList<>(result.warnings());
        String[][] brFields = {
                {"nemaki:recordType", "recordType"}, {"nemaki:recordId", "recordId"},
                {"nemaki:recordUrl", "recordUrl"}, {"nemaki:recordStatus", "recordStatus"},
                {"nemaki:recordOwner", "recordOwner"}, {"nemaki:processInstanceId", "processInstanceId"},
        };
        String metaError = ingestMetadataService.applyArchetypeMetadata(request.getRepositoryId(), result.objectId(), callContext,
                "nemaki:businessRecordMetadata", request, brFields);
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

        // Preserve the skipped flag/reason: a dedupe-skipped record that fell
        // through above must still be reported as skipped, not imported.
        return new ExternalIngestResult(request.getRequestId(), result.objectId(), result.versionLabel(),
                result.isNewVersion(), false, result.skipped(), result.skipReason(),
                result.lineageEventId(), List.of(), warnings);
    }

    @Override
    public ExternalIngestResult executeChatContextImport(CallContext callContext, ExternalIngestRequest request) {
        if (request.getSourceObjectType() == null || request.getSourceObjectType().isBlank()) {
            request.setSourceObjectType("message");
        }
        ExternalIngestResult result = execute(callContext, request);
        if (!result.isSuccess()) return result;
        // An empty/pseudo-file skip (objectId == null) produced no object to
        // decorate — applying chat metadata or getContent() on a null id would
        // fail — so return it verbatim. A dedupe skip (objectId != null) falls
        // through: re-decorating the existing object is idempotent and lets a
        // derivedFromContext link be created late (e.g. when the parent context
        // is imported in a later poll). The skip flag is preserved in the final
        // return below so the orchestrator still counts it as skipped.
        if (result.skipped() && result.objectId() == null) return result;

        List<String> warnings = new ArrayList<>(result.warnings());
        String[][] chatFields = {
                {"nemaki:chatWorkspaceId", "workspaceId"}, {"nemaki:chatChannelId", "channelId"},
                {"nemaki:chatChannelName", "channelName"}, {"nemaki:chatThreadId", "threadId"},
                {"nemaki:chatMessageId", "messageId"}, {"nemaki:chatParticipants", "participants"},
                {"nemaki:chatSelectionReason", "selectionReason"},
                {"nemaki:chatEvidenceScope", "evidenceScope"},
        };

        String metaError = ingestMetadataService.applyArchetypeMetadata(request.getRepositoryId(), result.objectId(), callContext,
                "nemaki:chatContextMetadata", request, chatFields);
        if (metaError != null) warnings.add(metaError);

        // chatCapturedAt has been on the type since it was introduced but nothing ever set it,
        // so every chat import carried a capture-time property it left empty. It is stamped here
        // from the server clock rather than read from the request.
        //
        // NOT yet a protected attribute. The property is still READWRITE, so a client with
        // update permission can change it afterwards, or plant it before a re-import and have
        // the no-overwrite rule below preserve their value. Making it evidence needs the
        // updatability migration described in authenticity-roadmap.md P1-1(c) — until then the
        // event snapshot, which a client cannot edit, is the trustworthy copy (external review).
        applyChatCapturedAt(callContext, request, result.objectId(), warnings);

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

        // Preserve the skipped flag/reason: a dedupe-skipped chat object that fell
        // through above must still be reported as skipped, not imported.
        return new ExternalIngestResult(request.getRequestId(), result.objectId(), result.versionLabel(),
                result.isNewVersion(), false, result.skipped(), result.skipReason(),
                result.lineageEventId(), List.of(), warnings);
    }

    /**
     * What the repository actually holds for this object, decided by looking rather than by
     * inferring from which update branch ran.
     *
     * <p>Every branch was guessing separately and getting it wrong in a different way:
     * metadata-only and no-change updates retain their existing attachment but reported
     * "no content"; a version carried forward reported "no content" too until it reported
     * "stored" even when the prior version had none. The object itself is the authority, and
     * this runs once, after all of them (external review, P1-1(b)).
     *
     * <p>Three outcomes, and "we could not tell" is deliberately NOT collapsed into "none":
     * a transient read failure followed by a successful check-in would otherwise assert that
     * nothing is stored while bytes sit there.
     */
    IngestLineageEmitter.CapturedContent describeCapturedContent(
            String repositoryId, String objectId, String computedHash) {
        if (computedHash != null) {
            // This import supplied and hashed the bytes it stored — the strongest case, and no
            // read-back is needed to know it.
            return IngestLineageEmitter.CapturedContent.hashed(computedHash);
        }
        try {
            Content stored = contentService.getContent(repositoryId, objectId);
            if (stored instanceof Document doc) {
                // A non-null attachment id proves a REFERENCE exists. Resolving the bytes is
                // stronger evidence and belongs with the fixity work (P1-2); the wording below
                // therefore says what was checked rather than asserting the bytes are readable.
                return doc.getAttachmentNodeId() != null
                        ? IngestLineageEmitter.CapturedContent.storedWithoutDigest(
                                "the object references content from an earlier import; this "
                                        + "import supplied no bytes and did not read the stored "
                                        + "ones back")
                        : IngestLineageEmitter.CapturedContent.none();
            }
            // The DAO layer catches its own failures and returns null, so a null read is NOT
            // evidence of emptiness — and this object was just written successfully, so a null
            // here is a read problem rather than an absent object (external review).
            return IngestLineageEmitter.CapturedContent.unknown(stored == null
                    ? "the stored object could not be read back (the read returned nothing)"
                    : "the stored object is not a document, so its content state is undetermined");
        } catch (Exception e) {
            logger.debug("Could not read back content state for {}: {}", objectId, e.getMessage());
            return IngestLineageEmitter.CapturedContent.unknown(
                    "the stored object could not be read back to determine its content state");
        }
    }

    /**
     * Stamp {@code nemaki:chatCapturedAt} with the moment this deployment took custody.
     *
     * <p>Deliberately not taken from the request: the property answers "when did WE observe
     * this", and a source that could choose the answer would make it evidence of nothing. It is
     * also written after the aspect exists, because writing before would have nowhere to go.
     */
    private void applyChatCapturedAt(CallContext callContext, ExternalIngestRequest request,
                                     String objectId, List<String> warnings) {
        if (objectId == null) {
            return;
        }
        try {
            Content content = contentService.getContent(request.getRepositoryId(), objectId);
            if (content == null) {
                warnings.add("Capture time (nemaki:chatCapturedAt) was not recorded: the stored "
                        + "object could not be read back");
                return;
            }
            if (content.getAspects() == null) {
                // A null aspect list means the chat aspect is absent just as surely as a list
                // without it does; returning silently here left half the case unreported.
                warnings.add("Capture time (nemaki:chatCapturedAt) was not recorded: the stored "
                        + "object carries no aspects");
                return;
            }
            Aspect chatAspect = content.getAspects().stream()
                    .filter(a -> "nemaki:chatContextMetadata".equals(a.getName()))
                    .findFirst().orElse(null);
            if (chatAspect == null || chatAspect.getProperties() == null) {
                // Absent aspect on a CHAT_CONTEXT import means the metadata step did not take
                // effect, so the capture time has nowhere to live. Say so rather than returning
                // silently — the caller is otherwise told the import succeeded (external review).
                warnings.add("Capture time (nemaki:chatCapturedAt) was not recorded: the chat "
                        + "context aspect is not present on the stored object");
                return;
            }
            Map<String, Property> props = new java.util.LinkedHashMap<>();
            for (Property p : chatAspect.getProperties()) {
                props.put(p.getKey(), p);
            }
            if (props.containsKey("nemaki:chatCapturedAt")) {
                // A re-import must not restamp custody: the first observation is the one that
                // means anything, and moving it forward would quietly erase how long we have
                // actually held the record. Note the limitation above — while the property is
                // READWRITE this also preserves a value a client planted, which is why the
                // event snapshot rather than this property is the copy to rely on.
                return;
            }
            GregorianCalendar now = new GregorianCalendar();
            now.setTimeInMillis(java.time.Instant.now().toEpochMilli());
            props.put("nemaki:chatCapturedAt", new Property("nemaki:chatCapturedAt", now));
            chatAspect.setProperties(new ArrayList<>(props.values()));
            contentService.update(callContext, request.getRepositoryId(), content);
        } catch (Exception e) {
            warnings.add("Capture time (nemaki:chatCapturedAt) was not recorded: " + e.getMessage());
        }
    }

    /**
     * Carry a child import's warnings up to the parent result.
     *
     * <p>A composite import (mail with its raw .eml and attachments) returns ONE result to the
     * client. A child that stored its content but lost its provenance reports that in its own
     * warnings — and those were being discarded, so the very failure P1-1 exists to surface
     * disappeared before anyone could see it (external review).
     */
    static void mergeChildWarnings(List<String> parentWarnings, String childLabel,
                                   ExternalIngestResult childResult) {
        if (childResult == null || childResult.warnings() == null) {
            return;
        }
        for (String w : childResult.warnings()) {
            parentWarnings.add(childLabel + ": " + w);
        }
    }

    /**
     * Generic archetype metadata applicator — attaches a secondary type with
     * properties read from request metadata.
     */
    // applyArchetypeMetadata → delegated to IngestMetadataService

    /**
     * Creates a CMIS relationship unconditionally (not dependent on profile policy).
     * Used by specialized import flows (mail, note) where parent-child relationships
     * are inherent to the archetype.
     */
    @Override
    public String createDirectRelationship(CallContext callContext, String repositoryId,
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
            // Idempotent: if this source→target link already exists, do not
            // create a duplicate. Relationship creation is otherwise re-run on
            // every poll for already-imported objects (e.g. dedupe-skipped
            // chat attachments), which would accumulate duplicate edges.
            if (sourceId != null && targetId != null
                    && relationshipExists(repositoryId, sourceId, targetId)) {
                return null;
            }
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
     * True if a relationship with the given source already targets {@code targetId}.
     * Used to keep {@link #createDirectRelationship} idempotent.
     *
     * <p>The match is intentionally <b>type-agnostic</b> (source→target only).
     * Each ingest flow links a given source/target pair with exactly one
     * semantic type (nemaki:hasAttachment / nemaki:attachedToRecord /
     * nemaki:derivedFromContext), so "an edge exists" means "already linked".
     * Type-agnostic matching also means a custom-type retry won't duplicate an
     * edge that a previous call had to create as the cmis:relationship fallback.
     *
     * <p>Fails open to {@code false} (allows creation) on query error, so a
     * transient lookup failure never blocks a legitimate first link.
     */
    private boolean relationshipExists(String repositoryId, String sourceId, String targetId) {
        if (contentService == null) return false;
        try {
            List<jp.aegif.nemaki.model.Relationship> rels = contentService.getRelationsipsOfObject(
                    repositoryId, sourceId,
                    org.apache.chemistry.opencmis.commons.enums.RelationshipDirection.SOURCE);
            if (rels != null) {
                for (jp.aegif.nemaki.model.Relationship r : rels) {
                    if (r != null && targetId.equals(r.getTargetId())) return true;
                }
            }
        } catch (Exception e) {
            logger.debug("Relationship existence check failed for {} -> {}: {}", sourceId, targetId, e.getMessage());
        }
        return false;
    }

    /**
     * Computes SHA-256 hash of content bytes.
     */
    private static String computeContentHash(byte[] content) {
        // Empty content that WAS stored still has a digest, and SHA-256 of zero bytes is a
        // perfectly valid one. Only the absence of content is absence (external review).
        if (content == null) return null;
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(content));
        } catch (Exception e) {
            return null;
        }
    }

    // addStringProp → moved to IngestMetadataService

    /** Emit audit event for external ingest operations. */
    private void emitAuditEvent(String repositoryId, String objectId,
                                CallContext callContext, boolean success, String error) {
        try {
            var audit = this.auditLogger;
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
                // This path writes an ACL without going through AclService, so nothing else
                // advances the cache generation — and other replicas would keep serving the
                // pre-import permissions until their entries expired. Bumping here rather than
                // in the DAO keeps ordinary content updates from clearing every replica.
                jp.aegif.nemaki.util.cache.AclCacheGeneration.advance(repositoryId);
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
                    new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
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
                // This path writes an ACL without going through AclService, so nothing else
                // advances the cache generation — and other replicas would keep serving the
                // pre-import permissions until their entries expired. Bumping here rather than
                // in the DAO keeps ordinary content updates from clearing every replica.
                jp.aegif.nemaki.util.cache.AclCacheGeneration.advance(repositoryId);
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
                    new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
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
        return name.replaceAll("[/\\\\:*?\"<>|\\x00]", "_").trim();
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

    // applyMessageMetadata → delegated to IngestMetadataService

    private static final int MAX_METADATA_SIZE = 1_000_000; // 1 MB
    private static final int MAX_METADATA_DEPTH = 10;

    /**
     * Hard cap on a single fetched/ingested content stream, matching the
     * 100 MB limit the manual multipart path enforces in
     * {@code ExternalIngestController}. Scheduler/connector/webhook fetches
     * previously buffered the adapter {@code InputStream} into memory with
     * no bound, so a large (or hostile) remote object could exhaust the
     * heap. {@link #readBounded} enforces this cap.
     */
    private static final int MAX_CONTENT_SIZE = 100 * 1024 * 1024; // 100 MB

    /**
     * Read {@code in} fully into a byte[] but fail fast once more than
     * {@code maxBytes} have been read, so an unbounded remote stream cannot
     * exhaust memory. The stream is NOT closed here (callers use
     * try-with-resources).
     */
    static byte[] readBounded(java.io.InputStream in, int maxBytes, String what) throws java.io.IOException {
        // Delegates to the shared bounded-read utility (single source of truth).
        return jp.aegif.nemaki.util.io.BoundedIO.readBounded(in, maxBytes, what);
    }

    @Override
    public ExternalIngestResult execute(CallContext callContext, ExternalIngestRequest request) {
        String requestId = request.getRequestId();
        // §3: the lineage operation id is issued when the business operation starts, not when
        // the lineage event is emitted afterwards — retries of the emission reuse this id.
        String lineageOperationId = java.util.UUID.randomUUID().toString();

        // 0. Validate metadata size and depth to prevent DoS
        if (request.getMetadata() != null) {
            try {
                byte[] metaBytes = JSON_MAPPER.writeValueAsBytes(request.getMetadata());
                if (metaBytes.length > MAX_METADATA_SIZE) {
                    return ExternalIngestResult.error(requestId,
                            "Metadata exceeds max size of " + (MAX_METADATA_SIZE / 1024) + " KB");
                }
            } catch (Exception e) {
                return ExternalIngestResult.error(requestId, "Invalid metadata format");
            }
        }

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
            String channelId = resolveMetadataString(request, "channelId");
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
            if (IngestLineageEmitter.isAttachmentObjectType(request.getSourceObjectType())) {
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
                byte[] contentBytes;
                try (java.io.InputStream rawIn = request.getContentStream()) {
                    contentBytes = readBounded(rawIn, MAX_CONTENT_SIZE, "Content");
                }
                bufferedContent = contentBytes; // retain for DLQ if this import fails

                // Skip empty attachments. A 0-byte download — e.g. a macOS
                // .textClipping placeholder uploaded to Notion, or an
                // expired/empty file URL from any chat/mail connector — would
                // otherwise be persisted as a content-less document. This is the
                // single choke point every connector's attachment passes through
                // (Notion/Slack/Teams/Mattermost/Chatwork attachments + mail
                // attachments all reach execute() with sourceObjectType
                // "attachment"). Message / page / record bodies and file-share
                // bodies are intentionally exempt: they still carry value via
                // metadata, and a 0-byte Box/Dropbox file may be a legitimate
                // user-placed placeholder we must not silently drop.
                if (contentBytes.length == 0
                        && "attachment".equals(request.getSourceObjectType())) {
                    logger.warn("Skipping empty attachment '{}' (0 bytes) from {} source '{}'",
                            fileName, connector.getSourceSystem(), request.getSourceObjectId());
                    return ExternalIngestResult.skipped(requestId,
                            "Empty attachment (0 bytes) — not imported");
                }

                // Skip OS/desktop pseudo files (e.g. a macOS .textClipping — a
                // ~12 KB Apple-proprietary plist that opens to nothing, or a
                // .DS_Store). These have a non-zero body so the size check above
                // does not catch them; they are filtered by filename instead.
                // This is the all-connector backstop; adapters that can recognise
                // them earlier (e.g. NotionConnectorAdapter.extractFiles) skip the
                // download entirely.
                if (FetchSupport.isPseudoSystemFile(fileName)) {
                    logger.warn("Skipping OS pseudo file '{}' from {} source '{}'",
                            fileName, connector.getSourceSystem(), request.getSourceObjectId());
                    return ExternalIngestResult.skipped(requestId,
                            "OS/desktop pseudo file (e.g. .textClipping/.DS_Store) — not imported");
                }
                computedHash = computeContentHash(contentBytes);
                contentStream = new ContentStreamImpl(fileName, BigInteger.valueOf(contentBytes.length),
                        mimeType, new ByteArrayInputStream(contentBytes));
            }

            // 5a. Dedupe: check for existing document by source identity (or filename)
            String dedupePolicy = profile.getDedupePolicy() != null ? profile.getDedupePolicy() : "skip_if_same_version";
            String dedupeMatchBy = profile.getDedupeMatchBy() != null ? profile.getDedupeMatchBy() : "source_id";
            Content existingDoc = findExistingDocument(repositoryId, targetFolderId, fileName,
                    connector.getSourceSystem(), request.getSourceObjectId(), request.getSourceObjectType(),
                    dedupeMatchBy);

            // 5b. Idempotency check: skip if same key already succeeded
            if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank()) {
                // Check persisted idempotency record (works for all dedupe modes).
                // Value format: "objectId|epochMillis" — TTL is 7 days.
                // Namespace by repository + profile to prevent cross-scope collisions
                String idempKey = "ingest.idempotency." + repositoryId + "." + profile.getProfileId()
                        + "." + request.getIdempotencyKey();
                try {
                    if (integrationSettingsService != null) {
                        String existing = integrationSettingsService.readSetting(idempKey);
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
                                        integrationSettingsService.deleteSettings(java.util.Set.of(idempKey));
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
                } else if ("replace".equals(dedupePolicy)) {
                    // Delete existing and create fresh
                    try {
                        objectService.deleteObject(callContext, repositoryId, existingDoc.getId(), true, null);
                        logger.info("Dedupe replace: deleted existing document {}", existingDoc.getId());
                    } catch (Exception e) {
                        logger.warn("Dedupe replace: failed to delete {}: {}", existingDoc.getId(), e.getMessage());
                    }
                    existingDoc = null; // Fall through to create new
                } else if ("create_new_if_parent_context_changed".equals(dedupePolicy)) {
                    // Compare parent context fields — if changed, create new document
                    if (hasParentContextChanged(existingDoc, request)) {
                        logger.info("Dedupe: parent context changed for {}, creating new document", existingDoc.getId());
                        existingDoc = null; // Fall through to create new
                    }
                } else if ("replace_relationships_on_resync".equals(dedupePolicy) && existingDoc != null) {
                    // Delete existing relationships before re-import
                    removeExistingRelationships(callContext, repositoryId, existingDoc.getId());
                }

                // Other policies (including "create_new_version"): no special dedupe action — falls through
                // to updatePolicy which governs whether a new version is actually created.
                // Note: default dedupePolicy is "skip_if_same_version" (set at line 925).
            }

            // Re-check existingDoc: dedupe policies (replace, parent_context_changed)
            // may have nullified it, requiring new-document creation instead
            if (existingDoc != null && existingDoc.isDocument()) {
                String updatePolicy = profile.getUpdatePolicy() != null ? profile.getUpdatePolicy() : "version_up_on_content_change";
                objectId = existingDoc.getId();

                if ("update_metadata_only".equals(updatePolicy)) {
                    // Update metadata only — no version change, no content update
                    versionLabel = "metadata-only";
                    // The incoming bytes are deliberately NOT stored here, so their digest is
                    // not the digest of anything this repository holds. Recording it would
                    // describe content that was thrown away — and would poison the dedupe
                    // baseline for the next import (external review, P1-1(b)).
                    computedHash = null;
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
                            // Equality here is with a MUTABLE aspect property, not with the
                            // bytes. Keeping computedHash would let a stale or edited
                            // nemaki:contentHash certify content this import never stored —
                            // and the incoming bytes are discarded either way. Drop it and let
                            // describeCapturedContent read the object (external review).
                            computedHash = null;
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

            // 6b. Create relationship if parentObjectId is provided and policy allows.
            // Relationship failures are warnings, not errors — the document was already
            // created/versioned, so the import is a partial success, not a hard failure.
            String relError = applyRelationship(callContext, repositoryId, objectId, profile, request);
            if (relError != null) {
                warnings.add(relError);
            }

            // 7. Emit lineage event. The document that actually exists may not carry the
            // requested fileName (the version-update branch keeps the existing document's
            // name), so the name is read back from the final object — guarded, because a
            // lineage-only read must never fail an import that succeeded.
            String lineageDocumentName = fileName;
            try {
                Content lineageContent = contentService.getContent(repositoryId, objectId);
                if (lineageContent != null && lineageContent.getName() != null
                        && !lineageContent.getName().isBlank()) {
                    lineageDocumentName = lineageContent.getName();
                }
            } catch (Exception e) {
                logger.debug("Lineage name read failed; using requested fileName: {}", e.getMessage());
            }
            String lineageEventId = ingestLineageEmitter != null
                    ? ingestLineageEmitter.emitLineageEvent(repositoryId, objectId, targetFolderId,
                            lineageDocumentName, lineageOperationId, connector, request,
                            describeCapturedContent(repositoryId, objectId, computedHash),
                            // A delegated run's context is SYNTHESIZED from the profile creator,
                            // so getUsername() names the authority, not the actor. Putting it in
                            // both fields said "the creator ran it", which is what the split
                            // exists to stop. Until an execution-origin identity is threaded
                            // through, a delegated run records the service as the executor and
                            // the profile's creator as the authority (external review).
                            // A delegated profile can be driven manually by an authenticated
                            // caller OR autonomously by the scheduler, and this code cannot
                            // currently tell which. Naming a service outright would assert an
                            // actor we did not observe, so the executor is recorded as unknown
                            // WITH the reason — an honest gap beats a plausible label
                            // (external review). Threading execution origin through is P1-1(e).
                            profile.isDelegated()
                                    ? "unknown: delegated profile " + profile.getProfileId()
                                            + " — execution origin is not recorded yet"
                                    : (callContext != null ? callContext.getUsername() : null),
                            profile.isDelegated() ? profile.getCreatedByUserId() : null)
                    : null;
            // The document is committed by now. If provenance could not be recorded, the import
            // is NOT wholly successful: content exists with no evidence of where it came from,
            // and a caller that sees only success will never come back for it. Say so in the
            // result rather than leaving it in a log line nobody reads (P1-1).
            if (lineageEventId == null && ingestLineageEmitter != null) {
                String emissionFailure = ingestLineageEmitter.lastEmissionFailure();
                if (emissionFailure != null) {
                    warnings.add("Provenance was NOT recorded for this document ("
                            + emissionFailure + "). The content is stored, but no lineage event "
                            + "exists for it — re-import or record it manually before relying on "
                            + "this object's evidence.");
                }
            }

            logger.info("Canonical import completed: requestId={}, objectId={}, profile={}, connector={}",
                    requestId, objectId, profile.getProfileId(), connector.getConnectorId());

            // Save idempotency record if key was provided
            if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank()) {
                try {
                    if (integrationSettingsService != null) {
                        integrationSettingsService.writeSetting("ingest.idempotency." + repositoryId + "."
                                + profile.getProfileId() + "." + request.getIdempotencyKey(),
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
            // Save all non-manual failures to the DLQ so that no item is
            // silently lost when the scheduler advances its checkpoint past
            // this source item.  Permanent errors are marked retryable=false
            // so the operator can inspect (and optionally retry after fixing
            // the root cause) without auto-retry loops wasting resources.
            if (ingestJobService != null && !"manual".equals(request.getExecutionMode())) {
                try {
                    ingestJobService.saveToDlq(request,
                            (isTransient ? "[transient] " : "[permanent] ") + e.getMessage(),
                            bufferedContent);
                } catch (Exception dlqErr) {
                    logger.warn("Failed to save to DLQ — item may be lost: {}", dlqErr.getMessage());
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
    /* package */ boolean isTransientError(Exception e) {
        // Walk the cause chain looking for known transient signals.
        // Permanent errors (SSL cert, auth, schema) are explicitly excluded.
        Throwable t = e;
        for (int depth = 0; t != null && depth < 8; depth++, t = t.getCause()) {
            // --- Definitely permanent ---
            if (t instanceof javax.net.ssl.SSLHandshakeException
                    || t instanceof java.security.cert.CertificateException) {
                return false; // Certificate issues don't self-heal
            }

            String name = t.getClass().getName();
            // --- Definitely transient: network / I/O ---
            if (t instanceof java.net.SocketTimeoutException
                    || t instanceof java.net.SocketException
                    || t instanceof java.net.ConnectException
                    || t instanceof java.net.NoRouteToHostException) {
                return true;
            }
            // UnknownHostException is usually permanent (DNS), but may be
            // transient in container environments where DNS updates propagate
            if (t instanceof java.net.UnknownHostException) {
                return true;
            }
            // CouchDB / HTTP 409 conflict (optimistic lock)
            if (name.contains("UpdateConflictException") || name.contains("DocumentConflict")) {
                return true;
            }
            // HTTP status in message (catches adapter RuntimeException wrappers)
            String msg = t.getMessage();
            if (msg != null) {
                // Transient HTTP statuses
                if (msg.contains("429") || msg.contains("502") || msg.contains("503")
                        || msg.contains("504") || msg.contains("Read timed out")
                        || msg.contains("Connection reset") || msg.contains("Connection refused")
                        || msg.contains("rate limit")) {
                    return true;
                }
                // Permanent HTTP statuses — explicit exclusion
                if (msg.contains("401") || msg.contains("403") || msg.contains("404")
                        || msg.contains("auth error") || msg.contains("Unauthorized")
                        || msg.contains("Forbidden")) {
                    return false;
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

    // emitLineageEvent / emitViaJournal → delegated to IngestLineageEmitter

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
        // Route through the idempotent helper so a re-run (e.g. a webhook-
        // triggered incremental fetch of an already-imported object) does not
        // create a duplicate parent→child edge.
        return createDirectRelationship(callContext, repositoryId, parentObjectId, objectId);
    }

    /**
     * Finds an existing document in the target folder by source identity or filename.
     * Priority: sourceSystem + sourceObjectId match > filename match.
     * Only considers documents (not folders) in the target folder.
     */
    /**
     * Find an existing document for deduplication.
     *
     * <p>Matching strategy is controlled by {@code dedupeMatchBy}:
     * <ul>
     *   <li>{@code source_id} (default) — match by sourceObjectId + sourceSystem aspect</li>
     *   <li>{@code filename} — match by cmis:name within the target folder</li>
     *   <li>{@code source_id_or_filename} — try source_id first, fall back to filename</li>
     * </ul>
     *
     * <p>The {@code filename} strategy is designed for chat attachments and similar
     * scenarios where the external system assigns a new ID every time the same file
     * is re-uploaded.
     */
    private Content findExistingDocument(String repositoryId, String targetFolderId,
                                         String fileName, String sourceSystem, String sourceObjectId,
                                         String sourceObjectType, String dedupeMatchBy) {
        if (contentDaoService == null) return null;

        boolean trySourceId = !"filename".equals(dedupeMatchBy);
        boolean tryFilename = "filename".equals(dedupeMatchBy) || "source_id_or_filename".equals(dedupeMatchBy);

        // Load children once (shared by both passes)
        List<Content> children = null;
        if (trySourceId || tryFilename) {
            try {
                children = contentDaoService.getChildren(repositoryId, targetFolderId);
            } catch (Exception e) {
                logger.debug("Dedupe: failed to load children for {}: {}", targetFolderId, e.getMessage());
                return null;
            }
        }
        if (children == null) return null;

        // Pass 1: search by sourceObjectId (unless filename-only mode)
        if (trySourceId && sourceObjectId != null && !sourceObjectId.isBlank()) {
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

        // Pass 2: fallback to filename matching (only for filename / source_id_or_filename modes).
        // If multiple documents share the same filename in the target folder,
        // the first match in iteration order is returned (non-deterministic).
        // This is acceptable for chat attachments where duplicate filenames are rare.
        if (tryFilename && fileName != null && !fileName.isBlank()) {
            for (Content child : children) {
                if (child == null || !child.isDocument()) continue;
                if (fileName.equals(child.getName())) {
                    logger.debug("Dedupe: found existing document {} by filename='{}'", child.getId(), fileName);
                    return child;
                }
            }
        }

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
     *
     * <p>Results are cached for 5 minutes per (repositoryId, folderPath)
     * pair to avoid repeated CMIS calls during scheduled batch imports.</p>
     */
    private record CachedFolderId(String folderId, long cachedAt) {}
    private static final java.util.concurrent.ConcurrentHashMap<String, CachedFolderId> folderPathCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long FOLDER_PATH_CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final int FOLDER_PATH_CACHE_MAX_SIZE = 1000;

    /** Evict expired entries and enforce max size to prevent unbounded growth. */
    private static void evictExpiredFolderPathCache() {
        long now = System.currentTimeMillis();
        folderPathCache.entrySet().removeIf(e -> now - e.getValue().cachedAt > FOLDER_PATH_CACHE_TTL_MS);
        // Hard cap: if still too large, clear all
        if (folderPathCache.size() > FOLDER_PATH_CACHE_MAX_SIZE) {
            folderPathCache.clear();
        }
    }

    private String resolveTargetFolderId(ImportProfileDefinition profile, String repositoryId,
                                         CallContext callContext) {
        String folderId = profile.getTargetFolderId();
        if (folderId != null && !folderId.isBlank()) {
            return folderId;
        }
        String folderPath = profile.getTargetFolderPath();
        if (folderPath != null && !folderPath.isBlank()) {
            evictExpiredFolderPathCache();
            // Check cache
            String cacheKey = repositoryId + "|" + folderPath;
            CachedFolderId cached = folderPathCache.get(cacheKey);
            if (cached != null && System.currentTimeMillis() - cached.cachedAt < FOLDER_PATH_CACHE_TTL_MS) {
                return cached.folderId;
            }

            try {
                var objectData = objectService.getObjectByPath(callContext, repositoryId,
                        folderPath, null, Boolean.FALSE,
                        org.apache.chemistry.opencmis.commons.enums.IncludeRelationships.NONE,
                        null, Boolean.FALSE, Boolean.FALSE, null);
                if (objectData != null && objectData.getId() != null) {
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
                    folderPathCache.put(cacheKey, new CachedFolderId(objectData.getId(), System.currentTimeMillis()));
                    return objectData.getId();
                }
            } catch (Exception e) {
                logger.warn("Failed to resolve targetFolderPath '{}' in repository '{}': {}",
                        folderPath, repositoryId, e.getMessage());
            }
        }
        return null;
    }

    // buildCanonicalSourceUri, isAttachmentObjectType, resolveProcessType
    // → moved to IngestLineageEmitter

    private static String resolveMetadataString(ExternalIngestRequest request, String key) {
        if (request.getMetadata() != null) {
            Object val = request.getMetadata().get(key);
            if (val instanceof String s && !s.isBlank()) return s;
        }
        return null;
    }
}
