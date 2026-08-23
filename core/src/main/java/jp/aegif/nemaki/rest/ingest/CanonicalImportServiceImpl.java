package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.rest.ingest.capture.CaptureIntent;
import jp.aegif.nemaki.rest.ingest.capture.CaptureIntentStore;
import jp.aegif.nemaki.rest.ingest.capture.CaptureScope;
import jp.aegif.nemaki.rest.ingest.capture.MutationOutcome;
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
        // Rule 3: the wrapper owns the root scope. This entry point keeps writing after the
        // internal execute returns — message metadata, the raw .eml, attachments and their
        // relationships — so completing any earlier would describe a state that is not final.
        CaptureScope captureScope = newCaptureScope(callContext, request);
        return withCaptureOutcome(
                executeMailImportInternal(callContext, request, captureScope), captureScope);
    }

    private ExternalIngestResult executeMailImportInternal(CallContext callContext,
            ExternalIngestRequest request, CaptureScope captureScope) {
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

        // Carried so the catch can report what was already committed and what was recorded
        // along the way; both used to be discarded (external review).
        EntryFailureState failureState = new EntryFailureState();
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

            ExternalIngestResult messageResult = execute(callContext, request, captureScope);
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
            // A dry run previews; it must not decorate. Everything past this point WRITES —
            // message metadata, the raw .eml, attachments and their relationships — and none of
            // it used to check (external review).
            if (messageResult.dryRun()) {
                return messageResult;
            }

            String messageObjectId = messageResult.objectId();
            failureState.committedObjectId = messageObjectId;

            // 4. Apply nemaki:messageMetadata secondary type
            List<String> warnings = failureState.warnings;
            warnings.addAll(messageResult.warnings());
            // D-7: D6's rule extended here. A dedupe/idempotency skip returns from execute()
            // BEFORE the emit, so this write used to rewrite the whole messageMetadata aspect —
            // revision bump, capture row and Solr churn included — on EVERY poll of an
            // already-imported message, with no event anywhere. On a skip pass, fill gaps and
            // refuse changes; on a real capture, write as before.
            String metaError;
            if (messageResult.skipped()) {
                boolean[] mailFillAttempted = {false};
                IngestMetadataService.FillOutcome fill =
                        ingestMetadataService.fillMissingMessageMetadata(
                                request.getRepositoryId(), messageObjectId, callContext, parsed,
                                request, () -> {
                                    captureScope.ensureIntentOpened();
                                    mailFillAttempted[0] = true;
                                });
                metaError = fill == null ? null : fill.error();
                if (fill != null && mailFillAttempted[0]) {
                    recordWrapperUpdate(captureScope, "fillMissingMessageMetadata", metaError);
                }
                if (fill != null && !fill.refused().isEmpty()) {
                    warnings.add("This message already carried metadata, so " + fill.refused()
                            + " were left as captured rather than replaced.");
                }
                if (fill != null) {
                    emitReimportEvent(callContext, request, messageResult, fill.filled(),
                            fill.refused(), warnings);
                }
            } else {
                captureScope.ensureIntentOpened();
                metaError = ingestMetadataService.applyMessageMetadata(
                        request.getRepositoryId(), messageObjectId, callContext, parsed, request);
                recordWrapperUpdate(captureScope, "applyMessageMetadata", metaError);
            }
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
                    // A dry run must stay a dry run all the way down. Without this the parent
                    // previews while the child really imports (external review).
                    emlReq.setDryRun(request.isDryRun());
                    emlReq.setMetadata(new LinkedHashMap<>(metadata));

                    // Rule 4: this is a child operation, so it gets its own un-opened scope and
                    // is completed here — after its relationship, which rule 5 says belongs to
                    // the child. Attributing that relationship to the parent would make the raw
                    // .eml look captured while the message was the one reported unresolved.
                    CaptureScope emlScope = newCaptureScope(callContext, emlReq);
                    ExternalIngestResult emlResult = execute(callContext, emlReq, emlScope);
                    if (emlResult.isSuccess()) {
                        String relErr = createDirectRelationship(callContext, request.getRepositoryId(),
                                messageObjectId, emlResult.objectId(), "nemaki:hasAttachment",
                                emlScope);
                        if (relErr != null) warnings.add(relErr);
                    } else if (!emlResult.skipped()) {
                        warnings.add("Raw .eml preservation failed: " + String.join(", ", emlResult.errors()));
                    }
                    // Rule 3: the child's owner completes it, here, after its relationship.
                    // The merge happens on the COMPLETED result so that a capture that could not
                    // be recorded reaches the caller too — a child's warnings are the parent's
                    // problem, and provenance lost on the raw .eml would otherwise vanish before
                    // the client ever sees it (P1-1).
                    mergeChildWarnings(warnings, "raw .eml",
                            withCaptureOutcome(emlResult, emlScope));
                } catch (CaptureScope.CaptureIntentFailedException failClosed) {
                    throw failClosed;
                } catch (Exception e) {
                    warnings.add("Raw .eml preservation failed: " + e.getMessage());
                }
            }

            // 5. Import attachments as separate documents with relationship
            int attachmentCount = 0;
            List<Map<String, String>> mailNotIngested = new ArrayList<>();
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
                    attReq.setDryRun(request.isDryRun());
                    // Do NOT set parentObjectId here — relationship is created directly
                    // via createDirectRelationship after execute() to avoid duplicates.
                    Map<String, Object> attMeta = new LinkedHashMap<>();
                    attMeta.put("mailboxId", metadata.get("mailboxId"));
                    attMeta.put("messageStableId", request.getSourceObjectId());
                    attReq.setMetadata(attMeta);

                    // Rule 4: one scope per attachment. The parent's would be shared by all of
                    // them, so one failed link would mark every attachment unresolved.
                    CaptureScope attScope = newCaptureScope(callContext, attReq);
                    ExternalIngestResult attResult = execute(callContext, attReq, attScope);
                    if (attResult.skipped() && attResult.objectId() == null) {
                        // Same rule as the note wrapper: a skip that produced no object goes
                        // into the parent pass's completion evidence — the only durable place
                        // "we saw it and took nothing" can live (D5).
                        mailNotIngested.add(Map.of(
                                "fileName", attReq.getFileName() == null ? "" : attReq.getFileName(),
                                "reason", attResult.skipReason() == null
                                        ? "skipped" : attResult.skipReason()));
                    }
                    if (attResult.isSuccess() || attResult.skipped()) {
                        attachmentCount++;
                        // Create/update typed relationship
                        String existingId = attResult.isSuccess() ? attResult.objectId()
                                : (attResult.skipped() ? attResult.objectId() : null);
                        if (existingId != null) {
                            // Rule 5: the link is part of THIS attachment's work.
                            String relErr = createDirectRelationship(callContext, request.getRepositoryId(),
                                    messageObjectId, existingId, "nemaki:hasAttachment",
                                    attScope);
                            if (relErr != null) warnings.add(relErr);
                        }
                    } else {
                        warnings.add("Attachment '" + att.filename() + "' import failed: "
                                + String.join(", ", attResult.errors()));
                    }
                    mergeChildWarnings(warnings, "attachment '" + att.filename() + "'",
                            withCaptureOutcome(attResult, attScope));
                } catch (CaptureScope.CaptureIntentFailedException failClosed) {
                    throw failClosed;
                } catch (Exception e) {
                    warnings.add("Attachment '" + att.filename() + "' failed: " + e.getMessage());
                }
            }

            logger.info("Mail import completed: messageId={}, objectId={}, attachments={}",
                    parsed.messageId(), messageObjectId, attachmentCount);

            // Preserve the skipped flag/reason: a dedupe-skipped message body that
            // fell through above must still be reported as skipped, not imported.
            // createdObject rides along: rebuilding through the legacy arity silently reported
            // a freshly created object as pre-existing, which is what decides whether custody
            // time may be recorded at all (external review).
            if (!mailNotIngested.isEmpty()) {
                captureScope.notePassFact("attachmentsNotIngested", mailNotIngested);
            }
            return new ExternalIngestResult(requestId, messageObjectId, messageResult.versionLabel(),
                    messageResult.isNewVersion(), messageResult.dryRun(), messageResult.skipped(), messageResult.skipReason(),
                    messageResult.lineageEventId(), List.of(), warnings,
                    messageResult.createdObject());

        } catch (Exception e) {
            logger.error("Mail import failed: {}", e.getMessage(), e);
            captureScope.operationFailed(e.getMessage());
            return failedAfterEntry(callContext, request, requestId, failureState.committedObjectId,
                    failureState.warnings, "Mail import failed: ", e);
        }
    }

    @Override
    public ExternalIngestResult executeNoteImport(CallContext callContext, ExternalIngestRequest request) {
        // This entry point had NO top-level try, so any failure after execute() returned
        // propagated to the fetch orchestrator — where six of the eleven have no DLQ at all, and
        // the high-water mark is overtaken by a later success in the same batch. The source item
        // was then never re-fetched (external review).
        EntryFailureState failureState = new EntryFailureState();
        // Rule 3: the wrapper owns the root scope, because it keeps updating the same document
        // after the internal execute returns. Completing inside execute would snapshot a state
        // that is not the final one.
        CaptureScope captureScope = newCaptureScope(callContext, request);
        try {
            return withCaptureOutcome(
                    executeNoteImportInternal(callContext, request, failureState, captureScope),
                    captureScope);
        } catch (Exception e) {
            logger.error("Note import failed: {}", e.getMessage(), e);
            captureScope.operationFailed(e.getMessage());
            return withCaptureOutcome(failedAfterEntry(callContext, request, request.getRequestId(),
                    failureState.committedObjectId, failureState.warnings, "Note import failed: ", e),
                    captureScope);
        }
    }

    private ExternalIngestResult executeNoteImportInternal(CallContext callContext,
            ExternalIngestRequest request, EntryFailureState failureState,
            CaptureScope captureScope) {
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
        List<String> warnings = failureState.warnings;
        String pageVersionLabel = null;
        boolean pageNewVersion = false;
        boolean pageCreated = false;
        String pageLineageEventId = null;
        boolean pageSkipped = false;     // files_and_body: page body was dedupe-skipped
        String pageSkipReason = null;

        if (importBody) {
            ExternalIngestResult pageResult = execute(callContext, request, captureScope);
            if (!pageResult.isSuccess()) {
                return pageResult;
            }
            pageObjectId = pageResult.objectId();
            pageVersionLabel = pageResult.versionLabel();
            pageNewVersion = pageResult.isNewVersion();
            pageCreated = pageResult.createdObject();
            pageLineageEventId = pageResult.lineageEventId();
            pageSkipped = pageResult.skipped();
            pageSkipReason = pageResult.skipReason();
            failureState.committedObjectId = pageObjectId;
            warnings.addAll(pageResult.warnings());
            // Apply nemaki:noteMetadata to the page document.
            // Not on a dry run: this is a real aspect write (external review). The note path
            // cannot key on the page result alone — files_only never runs the page import — so
            // every write here is guarded on the REQUEST.
            if (!request.isDryRun()) {
                // D-7: on a page dedupe-skip no event was emitted, so fill gaps and refuse
                // changes rather than rewriting the aspect every poll (D6's rule, extended).
                String metaError;
                if (pageSkipped) {
                    boolean[] noteFillAttempted = {false};
                    IngestMetadataService.FillOutcome fill =
                            ingestMetadataService.fillMissingNoteMetadata(
                                    request.getRepositoryId(), pageObjectId, callContext, request,
                                    () -> {
                                        captureScope.ensureIntentOpened();
                                        noteFillAttempted[0] = true;
                                    });
                    metaError = fill == null ? null : fill.error();
                    if (fill != null && noteFillAttempted[0]) {
                        recordWrapperUpdate(captureScope, "fillMissingNoteMetadata", metaError);
                    }
                    if (fill != null && !fill.refused().isEmpty()) {
                        warnings.add("This page already carried metadata, so " + fill.refused()
                                + " were left as captured rather than replaced.");
                    }
                    if (fill != null) {
                        emitReimportEvent(callContext, request, pageResult, fill.filled(),
                                fill.refused(), warnings);
                    }
                } else {
                    boolean tracked = openIfWriting(captureScope,
                            ingestMetadataService.willWriteNoteMetadata(request));
                    metaError = ingestMetadataService.applyNoteMetadata(request.getRepositoryId(), pageObjectId, callContext, request);
                    if (tracked) {
                        recordWrapperUpdate(captureScope, "applyNoteMetadata", metaError);
                    }
                }
                if (metaError != null) warnings.add(metaError);
            }
        }

        // Import attachments from metadata if provided
        int attachmentCount = 0;
        int importedAttachmentCount = 0;   // genuinely new/updated attachments
        int skippedAttachmentCount = 0;    // dedupe-skipped attachments
        List<Map<String, String>> notIngested = new ArrayList<>();  // 0-byte / pseudo-file skips
        String firstAttachmentObjectId = null;
        boolean firstAttachmentCreated = false;
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
                    attReq.setDryRun(request.isDryRun());
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
                    // Rule 4: one scope per attachment either way. The files_and_body branch used
                    // the PUBLIC execute, which completed the attachment's row BEFORE the
                    // relationship below existed — and then recorded that relationship against
                    // the PARENT. A failed link therefore marked the attachment captured and the
                    // page unresolved: both directions of misattribution rules 4 and 5 exist to
                    // prevent (external review).
                    CaptureScope attScope = importBody ? newCaptureScope(callContext, attReq) : null;
                    if (!importBody) {
                        // attachment carries note metadata since there is no page doc
                        attResult = executeNoteAttachment(callContext, attReq, request);
                    } else {
                        attResult = execute(callContext, attReq, attScope);
                    }
                    if (attResult.isSuccess() || attResult.skipped()) {
                        attachmentCount++;
                        // isSuccess() is true even for a skipped result (it only
                        // means "no errors"), so test skipped() first.
                        if (attResult.skipped()) skippedAttachmentCount++;
                        else importedAttachmentCount++;
                        // A skip that produced NO object is "we saw it and took nothing" — a
                        // decision with no document, no aspect and no event to remember it, so
                        // it goes into this pass's completion evidence (D5). A dedupe skip has
                        // an objectId and is not this.
                        if (attResult.skipped() && attResult.objectId() == null) {
                            notIngested.add(Map.of(
                                    "fileName", attReq.getFileName() == null ? "" : attReq.getFileName(),
                                    "reason", attResult.skipReason() == null
                                            ? "skipped" : attResult.skipReason()));
                        }
                        String attObjectId = attResult.objectId();
                        if (attObjectId != null) {
                            if (firstAttachmentObjectId == null) {
                                firstAttachmentObjectId = attObjectId;
                                firstAttachmentCreated = attResult.createdObject();
                            }
                            if (importBody && pageObjectId != null && !request.isDryRun()) {
                                // Rule 5: the link is part of THIS attachment's work.
                                String relErr = createDirectRelationship(callContext, request.getRepositoryId(),
                                        pageObjectId, attObjectId, "nemaki:hasAttachment",
                                        attScope);
                                if (relErr != null) warnings.add(relErr);
                            }
                        }
                    } else {
                        warnings.add("Attachment import failed: " + String.join(", ", attResult.errors()));
                    }
                    // Merged once, and for a scope owned here that happens AFTER completion so
                    // an unrecorded capture reaches the caller too.
                    mergeChildWarnings(warnings, "attachment",
                            attScope == null ? attResult : withCaptureOutcome(attResult, attScope));
                } catch (CaptureScope.CaptureIntentFailedException failClosed) {
                    // The mail loops guard this; this one did not, so a fail-closed refusal was
                    // downgraded to a warning and the orchestrator advanced its checkpoint
                    // (external review).
                    throw failClosed;
                } catch (Exception e) {
                    warnings.add("Attachment failed: " + e.getMessage());
                }
            }
        }

        if (!notIngested.isEmpty()) {
            // Into the PARENT pass's completion evidence: the skipped attachment has no
            // document, aspect, event or row of its own, and the parent's row is the one
            // durable record that "we saw it and took nothing, and why" (D5). On a re-poll
            // whose parent never opens a row, nothing is re-recorded — anti-flood, and the
            // fact is already on the capture-time row.
            captureScope.notePassFact("attachmentsNotIngested", notIngested);
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
            return new ExternalIngestResult(requestId, null, null, false, request.isDryRun(), true,
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

        // createdObject must describe the object primaryObjectId NAMES, which is the page only
        // when the body was imported — otherwise it is the first attachment (external review).
        return new ExternalIngestResult(requestId, primaryObjectId, pageVersionLabel,
                pageNewVersion, request.isDryRun(), overallSkipped, overallSkipReason, pageLineageEventId,
                List.of(), warnings, importBody ? pageCreated : firstAttachmentCreated);
    }

    /**
     * Import a Notion attachment as a standalone document and apply the note
     * metadata (from the originating page request) to it. Used in files_only
     * mode where the page body document is not created, so the attachment
     * becomes the carrier of the page's source identity / text.
     */
    private ExternalIngestResult executeNoteAttachment(CallContext callContext,
            ExternalIngestRequest attReq, ExternalIngestRequest pageRequest) {
        // Rule 4: a child operation owns its own scope. Sharing the parent's would make several
        // attachments share one intent row, and completing through the public execute would
        // close the row BEFORE the metadata update below — putting that change outside the
        // boundary and attributing its failure to the page rather than to this attachment.
        CaptureScope childScope = newCaptureScope(callContext, attReq);
        ExternalIngestResult attResult = execute(callContext, attReq, childScope);
        if (attResult.isSuccess() && attResult.objectId() != null && !attReq.isDryRun()) {
            // Reuse the page's metadata for the note-metadata secondary type.
            boolean tracked = openIfWriting(childScope,
                    ingestMetadataService.willWriteNoteMetadata(pageRequest));
            String metaError = ingestMetadataService.applyNoteMetadata(
                    attReq.getRepositoryId(), attResult.objectId(), callContext, pageRequest);
            if (tracked) {
                recordWrapperUpdate(childScope, "applyNoteMetadata", metaError);
            }
            if (metaError != null) {
                List<String> w = new ArrayList<>(attResult.warnings());
                w.add(metaError);
                // Carry dryRun, skipped and skipReason. Hardcoding them dropped the skip flag
                // whenever a dedupe-skipped attachment's metadata write failed, so the caller
                // counted it as IMPORTED — which also suppressed the files_only "nothing was
                // imported" return (external review). Same defect shape as createdObject.
                return withCaptureOutcome(new ExternalIngestResult(attResult.requestId(),
                        attResult.objectId(),
                        attResult.versionLabel(), attResult.isNewVersion(), attResult.dryRun(),
                        attResult.skipped(), attResult.skipReason(),
                        attResult.lineageEventId(), List.of(), w, attResult.createdObject()),
                        childScope);
            }
        }
        return withCaptureOutcome(attResult, childScope);
    }

    // applyNoteMetadata → delegated to IngestMetadataService

    @Override
    public ExternalIngestResult executeBusinessRecordImport(CallContext callContext, ExternalIngestRequest request) {
        // This entry point had NO top-level try, so any failure after execute() returned
        // propagated to the fetch orchestrator — where six of the eleven have no DLQ at all, and
        // the high-water mark is overtaken by a later success in the same batch. The source item
        // was then never re-fetched (external review).
        EntryFailureState failureState = new EntryFailureState();
        // Rule 3: the wrapper owns the root scope, because it keeps updating the same document
        // after the internal execute returns. Completing inside execute would snapshot a state
        // that is not the final one.
        CaptureScope captureScope = newCaptureScope(callContext, request);
        try {
            return withCaptureOutcome(
                    executeBusinessRecordImportInternal(callContext, request, failureState, captureScope),
                    captureScope);
        } catch (Exception e) {
            logger.error("Business record import failed: {}", e.getMessage(), e);
            captureScope.operationFailed(e.getMessage());
            return withCaptureOutcome(failedAfterEntry(callContext, request, request.getRequestId(),
                    failureState.committedObjectId, failureState.warnings, "Business record import failed: ", e),
                    captureScope);
        }
    }

    private ExternalIngestResult executeBusinessRecordImportInternal(CallContext callContext,
            ExternalIngestRequest request, EntryFailureState failureState,
            CaptureScope captureScope) {
        if (request.getSourceObjectType() == null || request.getSourceObjectType().isBlank()) {
            request.setSourceObjectType("record");
        }
        ExternalIngestResult result = execute(callContext, request, captureScope);
        if (!result.isSuccess()) return result;
        // An empty/pseudo-file skip (objectId == null) has no object to decorate;
        // return it verbatim. A dedupe skip (objectId != null) falls through to
        // re-apply metadata/relationship idempotently, and its skip flag is
        // preserved in the final return below so it is counted as skipped.
        if (result.skipped() && result.objectId() == null) return result;
        // A dry run previews; the aspect write and the relationship below are real (external
        // review).
        if (result.dryRun()) return result;

        failureState.committedObjectId = result.objectId();
        List<String> warnings = failureState.warnings;
        warnings.addAll(result.warnings());
        String[][] brFields = {
                {"nemaki:recordType", "recordType"}, {"nemaki:recordId", "recordId"},
                {"nemaki:recordUrl", "recordUrl"}, {"nemaki:recordStatus", "recordStatus"},
                {"nemaki:recordOwner", "recordOwner"}, {"nemaki:processInstanceId", "processInstanceId"},
        };
        // D-7: D6's rule, extended — on a skip pass no event was emitted, so fill gaps and
        // refuse changes; on a real capture, write as before.
        String metaError;
        if (result.skipped()) {
            boolean[] brFillAttempted = {false};
            IngestMetadataService.FillOutcome fill =
                    ingestMetadataService.fillMissingArchetypeMetadata(request.getRepositoryId(),
                            result.objectId(), callContext, "nemaki:businessRecordMetadata",
                            request, brFields, () -> {
                                captureScope.ensureIntentOpened();
                                brFillAttempted[0] = true;
                            });
            metaError = fill == null ? null : fill.error();
            if (fill != null && brFillAttempted[0]) {
                recordWrapperUpdate(captureScope, "fillMissingArchetypeMetadata", metaError);
            }
            if (fill != null && !fill.refused().isEmpty()) {
                warnings.add("This record already carried metadata, so " + fill.refused()
                        + " were left as captured rather than replaced.");
            }
            if (fill != null) {
                emitReimportEvent(callContext, request, result, fill.filled(), fill.refused(),
                        warnings);
            }
        } else {
            boolean brFieldsTracked = openIfWriting(captureScope,
                    ingestMetadataService.willWriteArchetypeMetadata(request, brFields));
            metaError = ingestMetadataService.applyArchetypeMetadata(request.getRepositoryId(), result.objectId(), callContext,
                    "nemaki:businessRecordMetadata", request, brFields);
            if (brFieldsTracked) {
                recordWrapperUpdate(captureScope, "applyArchetypeMetadata", metaError);
            }
        }
        if (metaError != null) warnings.add(metaError);

        // Create attachedToRecord relationship if parentRecordId is provided
        if (request.getMetadata() != null) {
            String parentRecordId = resolveMetadataString(request, "parentRecordId");
            if (parentRecordId != null) {
                String relErr = createDirectRelationship(callContext, request.getRepositoryId(),
                        parentRecordId, result.objectId(), "nemaki:attachedToRecord",
                        captureScope);
                if (relErr != null) warnings.add(relErr);
            }
        }

        // Preserve the skipped flag/reason: a dedupe-skipped record that fell
        // through above must still be reported as skipped, not imported. Same for createdObject.
        return new ExternalIngestResult(request.getRequestId(), result.objectId(), result.versionLabel(),
                result.isNewVersion(), result.dryRun(), result.skipped(), result.skipReason(),
                result.lineageEventId(), List.of(), warnings, result.createdObject());
    }

    @Override
    public ExternalIngestResult executeChatContextImport(CallContext callContext, ExternalIngestRequest request) {
        // This entry point had NO top-level try, so any failure after execute() returned
        // propagated to the fetch orchestrator — where six of the eleven have no DLQ at all, and
        // the high-water mark is overtaken by a later success in the same batch. The source item
        // was then never re-fetched (external review).
        EntryFailureState failureState = new EntryFailureState();
        // Rule 3: the wrapper owns the root scope, because it keeps updating the same document
        // after the internal execute returns. Completing inside execute would snapshot a state
        // that is not the final one.
        CaptureScope captureScope = newCaptureScope(callContext, request);
        try {
            return withCaptureOutcome(
                    executeChatContextImportInternal(callContext, request, failureState, captureScope),
                    captureScope);
        } catch (Exception e) {
            logger.error("Chat context import failed: {}", e.getMessage(), e);
            captureScope.operationFailed(e.getMessage());
            return withCaptureOutcome(failedAfterEntry(callContext, request, request.getRequestId(),
                    failureState.committedObjectId, failureState.warnings, "Chat context import failed: ", e),
                    captureScope);
        }
    }

    private ExternalIngestResult executeChatContextImportInternal(CallContext callContext,
            ExternalIngestRequest request, EntryFailureState failureState,
            CaptureScope captureScope) {
        if (request.getSourceObjectType() == null || request.getSourceObjectType().isBlank()) {
            request.setSourceObjectType("message");
        }
        String[][] chatFields = {
                {"nemaki:chatWorkspaceId", "workspaceId"}, {"nemaki:chatChannelId", "channelId"},
                {"nemaki:chatChannelName", "channelName"}, {"nemaki:chatThreadId", "threadId"},
                {"nemaki:chatMessageId", "messageId"}, {"nemaki:chatParticipants", "participants"},
                {"nemaki:chatSelectionReason", "selectionReason"},
                {"nemaki:chatEvidenceScope", "evidenceScope"},
        };
        // P1-1(e) §3: the create-path aspect phase runs INSIDE execute(), after the document
        // exists and before the emit — so the event can carry the applied state (capturedAt's
        // second copy, the mh1 facts). The hook's warnings land in these locals and are merged
        // after execute returns; its exceptions are NOT caught here (H5 — each write records
        // its own outcome on the scope, and execute()'s failure path takes the rest).
        List<String> hookWarnings = new ArrayList<>();
        String[] hookMetaError = {null};
        BeforeEmitHook chatAspectApplication = (objectId, createdObject) -> {
            boolean chatFieldsTracked = openIfWriting(captureScope,
                    ingestMetadataService.willWriteArchetypeMetadata(request, chatFields));
            String err = ingestMetadataService.applyArchetypeMetadata(request.getRepositoryId(),
                    objectId, callContext, "nemaki:chatContextMetadata", request, chatFields);
            if (chatFieldsTracked) {
                recordWrapperUpdate(captureScope, "applyArchetypeMetadata", err);
            }
            hookMetaError[0] = err;
            applyCaptureWindow(captureScope, callContext, request, objectId, false,
                    hookWarnings, new ArrayList<>(), new ArrayList<>());
            java.util.Map<CaptureEvidenceField, String> facts = new java.util.LinkedHashMap<>();
            String stampedAt = applyChatCapturedAt(captureScope, callContext, request, objectId,
                    createdObject, hookWarnings);
            if (stampedAt != null) {
                facts.put(CaptureEvidenceField.CHAT_CAPTURED_AT, stampedAt);
            }
            return facts;
        };

        ExternalIngestResult result = execute(callContext, request, captureScope,
                chatAspectApplication);
        if (!result.isSuccess()) return result;
        // An empty/pseudo-file skip (objectId == null) produced no object to
        // decorate — applying chat metadata or getContent() on a null id would
        // fail — so return it verbatim. A dedupe skip (objectId != null) falls
        // through: re-decorating the existing object is idempotent and lets a
        // derivedFromContext link be created late (e.g. when the parent context
        // is imported in a later poll). The skip flag is preserved in the final
        // return below so the orchestrator still counts it as skipped.
        if (result.skipped() && result.objectId() == null) return result;
        // A dry run previews; the aspect write, the capture-window update, the custody stamp and
        // the relationship below are all real (external review).
        if (result.dryRun()) return result;

        failureState.committedObjectId = result.objectId();
        List<String> warnings = failureState.warnings;
        warnings.addAll(result.warnings());
        warnings.addAll(hookWarnings);

        // A skip means execute() returned BEFORE emitting a lineage event (dedupe at :1959,
        // idempotency at :1919). Overwriting the evidence here would therefore change eleven
        // properties that P1-1(c) made read-only through CMIS and leave no record that it
        // happened — polling the same source object twice is enough (P1-1(d) D6). Filling a gap
        // is still allowed: that is the retry this fall-through exists for.
        boolean noEventForThisPass = result.skipped();
        String metaError;
        List<String> refusedByThisPass = new ArrayList<>();
        List<String> filledByThisPass = new ArrayList<>();
        if (noEventForThisPass) {
            // The intent opens from INSIDE the fill, between its decision and its write. The
            // first shape ran a willFill preflight and opened here — a second read of the same
            // object, and the two could disagree: one direction opened an intent for a pass
            // that wrote nothing, the other let the fill write WITHOUT an open intent (external
            // review, Codex). One read now makes the decision, and this flag records whether
            // the write was actually attempted, which is what decides whether to record.
            boolean[] writeAttempted = {false};
            IngestMetadataService.FillOutcome fill =
                    ingestMetadataService.fillMissingArchetypeMetadata(request.getRepositoryId(),
                            result.objectId(), callContext, "nemaki:chatContextMetadata",
                            request, chatFields, () -> {
                                captureScope.ensureIntentOpened();
                                writeAttempted[0] = true;
                            });
            if (fill == null) {
                // Unreachable with the real service — every path there returns a record. A null
                // means the collaborator is not the real one, and the safe reading is "this pass
                // wrote nothing and refused nothing": it cannot have overwritten anything either,
                // because the write lives inside the method that did not run.
                fill = new IngestMetadataService.FillOutcome(null, List.of(), List.of());
            }
            metaError = fill.error();
            refusedByThisPass.addAll(fill.refused());
            filledByThisPass.addAll(fill.filled());
            if (writeAttempted[0]) {
                recordWrapperUpdate(captureScope, "fillMissingArchetypeMetadata", metaError);
            }
            if (!fill.refused().isEmpty()) {
                warnings.add("This object already carried chat evidence, so " + fill.refused().size()
                        + " propert" + (fill.refused().size() == 1 ? "y was" : "ies were")
                        + " left as captured rather than replaced with the different values in this "
                        + "request " + fill.refused() + ". Re-import into a new object to capture "
                        + "them afresh.");
            }
        } else {
            // The create/version path's aspect phase already ran inside execute() (the hook).
            metaError = hookMetaError[0];
        }
        if (metaError != null) warnings.add(metaError);

        // The custody stamp moved INTO execute() (the beforeEmit hook) at P1-1(e): (b) §8's
        // retraction said "moving the stamp ahead of emission hits nothing because the aspect
        // does not exist yet" — the hook is the gate that makes the aspect exist first, so the
        // event now carries the stamp as its second copy (D1 resolved for new captures).

        if (noEventForThisPass) {
            applyCaptureWindow(captureScope, callContext, request, result.objectId(),
                    true, warnings, refusedByThisPass, filledByThisPass);
        }

        // Create derivedFromContext relationship if parentContextId is provided
        if (request.getMetadata() != null) {
            String parentContextId = resolveMetadataString(request, "parentContextId");
            if (parentContextId != null) {
                String relErr = createDirectRelationship(callContext, request.getRepositoryId(),
                        parentContextId, result.objectId(), "nemaki:derivedFromContext",
                        captureScope);
                if (relErr != null) warnings.add(relErr);
            }
        }

        // Here, not in execute()'s early return: both fill decisions above are complete, so the
        // event describes what this pass actually did rather than what it was about to try
        // (P1-1(d) D6/R5, external review).
        if (noEventForThisPass) {
            emitReimportEvent(callContext, request, result, filledByThisPass, refusedByThisPass,
                    warnings);
        }

        // Preserve the skipped flag/reason: a dedupe-skipped chat object that fell
        // through above must still be reported as skipped, not imported. Same for createdObject.
        return new ExternalIngestResult(request.getRequestId(), result.objectId(), result.versionLabel(),
                result.isNewVersion(), result.dryRun(), result.skipped(), result.skipReason(),
                result.lineageEventId(), List.of(), warnings, result.createdObject());
    }

    /**
     * The applied-metadata hashes at EMISSION time, as event facts (P1-1(e) §3, Codex M2).
     *
     * <p>One read-back, one computation — the exact instant the event describes. The completion
     * evidence recomputes at scope close; on a create path nothing touches the hashed aspects
     * between emit and completion, and a test pins the two copies equal (Codex M3) — a
     * divergence would mean a write slipped between them, which is worth failing loudly.
     */
    private void appendAppliedHashFacts(String repositoryId, String objectId,
            java.util.Map<CaptureEvidenceField, String> facts) {
        if (objectId == null || contentService == null) {
            return;
        }
        try {
            Content stored = contentService.getContent(repositoryId, objectId);
            if (stored == null) {
                return;
            }
            EvidenceMetadataHash.AppliedHashes hashes =
                    EvidenceMetadataHash.compute(stored.getAspects());
            if (hashes.isEmpty()) {
                return;
            }
            if (hashes.chatEvidenceHash() != null) {
                facts.put(CaptureEvidenceField.APPLIED_CHAT_EVIDENCE_HASH,
                        hashes.chatEvidenceHash());
            }
            if (hashes.sourceIdentityHash() != null) {
                facts.put(CaptureEvidenceField.APPLIED_SOURCE_IDENTITY_HASH,
                        hashes.sourceIdentityHash());
            }
            facts.put(CaptureEvidenceField.METADATA_HASH_SUBJECT, EvidenceMetadataHash.SUBJECT);
            facts.put(CaptureEvidenceField.METADATA_HASH_FORMULA, EvidenceMetadataHash.FORMULA);
        } catch (Exception e) {
            // The event simply carries no hash facts — honest absence; the completion evidence
            // still records its own copy.
            logger.debug("Applied-hash read-back failed for {}: {}", objectId, e.getMessage());
        }
    }

    /**
     * Who ran this import and on whose authority — resolved ONCE, from server-side truth.
     *
     * <p>Autonomy is decided by the CONTEXT'S TYPE (a synthetic context only the delegation
     * factory can mint, or the admin path's null), never by the request's {@code executionMode}
     * — that field is caller-supplied JSON, and a manual caller naming itself "scheduled" must
     * not change what the evidence says (P1-1(e) §1.1). The old "unknown: delegated profile"
     * admission is retired: the actor of an autonomous run IS the scheduler, and the operation
     * that configured it is now recorded on the profile — so both are stated. A profile from
     * before that field exists is reported as configured-by UNRECORDED, not silently credited
     * to its creator (Codex H4 — creation and schedule-enablement can be different people).
     */
    /** Server-side execution-origin truth: synthetic (delegated autonomous) or null (admin). */
    static boolean autonomousExecution(CallContext callContext) {
        return callContext == null || DelegatedCallContextFactory.isSynthetic(callContext);
    }

    static jp.aegif.nemaki.rest.purview.journal.LineageExecutionAttribution
            resolveExecutionAttribution(ImportProfileDefinition profile, CallContext callContext) {
        boolean autonomous = callContext == null
                || DelegatedCallContextFactory.isSynthetic(callContext);
        boolean delegated = profile != null && profile.isDelegated();
        if (!autonomous) {
            return new jp.aegif.nemaki.rest.purview.journal.LineageExecutionAttribution(
                    callContext.getUsername(),
                    delegated ? profile.getCreatedByUserId() : null);
        }
        String profileId = profile != null ? profile.getProfileId() : "unknown";
        String configuredBy;
        if (profile != null && profile.getScheduleConfiguredByUserId() != null
                && !profile.getScheduleConfiguredByUserId().isBlank()) {
            configuredBy = "schedule configured by " + profile.getScheduleConfiguredByUserId();
        } else if (profile != null && profile.getCreatedByUserId() != null
                && !profile.getCreatedByUserId().isBlank()) {
            configuredBy = "schedule configured-by unrecorded (profile created by "
                    + profile.getCreatedByUserId() + ")";
        } else {
            configuredBy = "schedule configured-by unrecorded";
        }
        return new jp.aegif.nemaki.rest.purview.journal.LineageExecutionAttribution(
                "scheduler: " + (delegated ? "delegated" : "admin") + " profile " + profileId
                        + ", " + configuredBy,
                delegated ? profile.getCreatedByUserId() : null);
    }

    /**
     * Records a pass that stored nothing but changed or refused something.
     *
     * <p>The dedupe and idempotency branches return from {@code execute} at {@code :1959} and
     * {@code :1919}, which are before the emit — so the second poll of a source object left no
     * trace whatever, while the wrapper went on writing to the object (P1-1(d) D6). Refusing the
     * rewrite closes half of that; this closes the other half by saying a pass happened.
     *
     * <p><b>Not emitted for a pass that did nothing.</b> A poller re-sending the same metadata
     * every five minutes is the ordinary case, and an event per poll per object would bury the
     * ones that matter — 288 a day for a single unchanged message. What is worth a record is a
     * gap filled or a change refused; a no-op is neither.
     *
     * <p>Emitted from the wrapper, after the fill decision, deliberately. Putting it on
     * {@code execute}'s early return would place it BEFORE the wrapper's writes and reproduce
     * exactly the ordering this increment is about (external review).
     */
    private void emitReimportEvent(CallContext callContext, ExternalIngestRequest request,
                                   ExternalIngestResult result, List<String> filled,
                                   List<String> refused, List<String> warnings) {
        if (ingestLineageEmitter == null || result.objectId() == null) return;
        if (filled.isEmpty() && refused.isEmpty()) return;
        try {
            ConnectorDefinition connector = connectorDefinitionService.get(request.getConnectorId());
            ImportProfileDefinition profile = request.getProfileId() == null ? null
                    : importProfileDefinitionService.get(request.getProfileId());
            if (connector == null) return;
            String repositoryId = request.getRepositoryId();
            // The same resolution execute() uses, not profile.getTargetFolderId(): a profile
            // defined with targetFolderPath only has a null id, and the original capture event
            // for this very document carries the resolved one. Two events disagreeing about the
            // folder is worse than no re-import event at all (external review).
            String folderId = request.getTargetFolderOverride() != null
                    ? request.getTargetFolderOverride()
                    : (profile == null ? null
                            : resolveTargetFolderId(profile, repositoryId, callContext));
            String documentName = null;
            try {
                Content c = contentService.getContent(repositoryId, result.objectId());
                if (c != null) documentName = c.getName();
            } catch (Exception ignored) {
                // A name is a convenience here; its absence must not cost the record.
            }
            Map<CaptureEvidenceField, String> passOutcome = new java.util.LinkedHashMap<>();
            passOutcome.put(CaptureEvidenceField.REIMPORT_OUTCOME,
                    "this pass stored no content (" + describeSkip(result)
                            + ") and did not replace evidence already captured");
            if (!filled.isEmpty()) {
                passOutcome.put(CaptureEvidenceField.REIMPORT_FILLED, String.join(",", filled));
            }
            if (!refused.isEmpty()) {
                passOutcome.put(CaptureEvidenceField.REIMPORT_REFUSED, String.join(",", refused));
            }
            jp.aegif.nemaki.rest.purview.journal.LineageExecutionAttribution reimportAttribution =
                    resolveExecutionAttribution(profile, callContext);
            String eventId = ingestLineageEmitter.emitLineageEvent(repositoryId, result.objectId(),
                    folderId, documentName, java.util.UUID.randomUUID().toString(),
                    connector, request,
                    // No bytes were supplied by this pass, so the content state is read back
                    // rather than asserted — the same three-state answer as any other emit.
                    describeCapturedContent(repositoryId, result.objectId(), null),
                    reimportAttribution.executedBy(), reimportAttribution.onBehalfOf(),
                    passOutcome);
            if (eventId == null) {
                String reason = ingestLineageEmitter.lastEmissionFailure();
                if (reason != null) {
                    warnings.add("This re-import changed evidence on an existing object but the "
                            + "record of it was NOT written (" + reason + "). "
                            + "filled=" + filled + " refused=" + refused);
                }
            }
        } catch (Exception e) {
            logger.warn("Could not record the re-import pass for {}: {}", result.objectId(),
                    e.getMessage());
            warnings.add("This re-import changed evidence on an existing object but the record of "
                    + "it was NOT written (" + e.getMessage() + ").");
        }
    }

    /**
     * Why this pass stored nothing.
     *
     * <p>Classified rather than quoted. The first version scanned {@code result.warnings()} for
     * "already exists", and that list is always empty on this path — every skip here comes from
     * {@code ExternalIngestResult.skipped(...)}, whose warnings are {@code List.of()} — so it
     * always returned the dedupe wording and attributed idempotency skips to dedupe (external
     * review). The reason string itself is deliberately not echoed: the idempotency one embeds
     * the caller's request id, and the ledger should not gain caller-supplied text by accident.
     */
    private static String describeSkip(ExternalIngestResult result) {
        if (!result.skipped()) return "no content was stored";
        String reason = result.skipReason();
        if (reason != null && reason.startsWith("Idempotent")) {
            return "this request had already been processed";
        }
        return "the document already existed";
    }

    private static final String[] CAPTURE_WINDOW_KEYS = {
            "nemaki:chatCaptureWindowStart", "nemaki:chatCaptureWindowEnd"};

    /** How many of the two capture-window properties currently hold a value. */
    private static int countWindowValues(Map<String, Property> propMap) {
        int count = 0;
        for (String key : CAPTURE_WINDOW_KEYS) {
            Property p = propMap.get(key);
            if (p != null && p.getValue() != null) count++;
        }
        return count;
    }

    /**
     * Writes a capture-window value, or keeps the captured one and says so.
     *
     * <p>When no lineage event will be emitted for this pass — a dedupe or idempotency skip — a
     * value that is already there is evidence from an earlier capture, and replacing it would be
     * an unrecorded change to a property P1-1(c) protected against CMIS edits (P1-1(d) D6).
     * A gap is still filled: that is a retry, not a change.
     */
    private static void putCapturedWindowValue(Map<String, Property> propMap, String key,
                                               GregorianCalendar value, boolean noEventForThisPass,
                                               List<String> warnings, List<String> refused,
                                               List<String> pendingFill) {
        Property existing = propMap.get(key);
        if (noEventForThisPass && existing != null && existing.getValue() != null) {
            // Same value said twice is neither a change nor a refusal — the ordinary poll — so it
            // is silent. Only a value that can be SHOWN to differ is a refusal worth reporting.
            if (differsFromStoredInstant(existing.getValue(), value)) {
                refused.add(key);
                warnings.add(key + " was already captured, so this re-import left it as it was "
                        + "rather than replacing it with a different value. Re-import into a new "
                        + "object to capture it afresh.");
            }
            return;
        }
        if (noEventForThisPass) {
            // PENDING, not filled. The write is several lines below and can fail; promoting this
            // to the event before it lands would make the record claim an applied value that is
            // not on the object — D1's defect, in new code (external review).
            pendingFill.add(key);
        }
        propMap.put(key, new Property(key, value));
    }

    /**
     * Whether a stored capture-window value can be shown to differ from an incoming one.
     *
     * <h2>Why this is not {@code instanceof Calendar}</h2>
     *
     * <p>It was, and that could never be true. Aspect property values are carried untyped through
     * {@code CouchContent}'s Map constructor and are NOT normalised —
     * {@code ContentDaoServiceImpl.normalizeJsonNumber} says in as many words that it does not
     * recurse into {@code aspects} — so a datetime written as epoch millis comes back as a
     * {@code Long} or a {@code Double}, never a {@code Calendar}. The product's own read path
     * knows this: {@code CompileServiceImpl} coerces DATETIME aspect values from
     * {@code GregorianCalendar}, {@code String} AND {@code Long}.
     *
     * <p>With the old test, every unchanged re-poll looked like a changed value: a refusal was
     * recorded, a warning raised and a lineage event emitted — 288 a day for one message, which
     * is the exact flood the rule exists to prevent (external review).
     *
     * <p><b>Only a positive difference is reported.</b> A stored value this cannot read is not
     * evidence that the caller believes something different, and treating "cannot tell" as
     * "differs" would restore the flood by another route. The protection is unaffected either
     * way — a present value is never replaced on this path; this decides only whether anything
     * is said about it.
     */
    private static boolean differsFromStoredInstant(Object stored, GregorianCalendar incoming) {
        Long storedMillis = toEpochMillis(stored);
        return storedMillis != null && storedMillis != incoming.getTimeInMillis();
    }

    /**
     * Epoch millis for the shapes a stored datetime can have, or null when unreadable.
     *
     * <p>Delegates to the metadata hash's normalizer so the re-import comparison and the hash
     * canonicalize identically — two normalizers is how they drift.
     */
    private static Long toEpochMillis(Object stored) {
        return EvidenceMetadataHash.toEpochMillis(stored);
    }

    /**
     * Whether this import changed the content, and what digest it is entitled to record.
     *
     * <p>Extracted because the digest half was invisible to the tests: reverting it left every
     * case green while restoring an unsupported claim (external review). The two answers belong
     * together — the same comparison decides both.
     */
    record ContentComparison(boolean contentChanged, String hashToRecord, String versionLabel,
                             boolean matchedRecordedHash, String comparedDigest) {
    }

    static ContentComparison compareContent(String computedHash, String existingHash) {
        if (computedHash == null) {
            // No content stream provided — a metadata-only update, not a version-up.
            return new ContentComparison(false, null, "metadata-only (no content provided)",
                    false, null);
        }
        if (computedHash.equals(existingHash)) {
            // The equality is with a MUTABLE aspect property, not with the stored bytes. Carrying
            // computedHash forward would let a stale or edited nemaki:contentHash certify content
            // this import never stored — and the incoming bytes are discarded either way, so no
            // digest is owed here (external review).
            //
            // But the COMPARISON is a fact, and it used to be thrown away with the digest: the
            // event then said this pass had neither supplied the bytes nor verified them, about a
            // pass that had fetched them, hashed them and found the digest equal. Weaker than
            // fixity, stronger than nothing, and it is the only fixity-adjacent evidence this
            // path ever produces (external review, P1-1(d) D2).
            return new ContentComparison(false, null, "metadata-only (content unchanged)",
                    true, computedHash);
        }
        return new ContentComparison(true, computedHash, null, false, computedHash);
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
        return describeCapturedContent(repositoryId, objectId, computedHash, null);
    }

    /**
     * @param comparison the dedupe comparison this import ran, when it ran one. Carries the one
     *        fact the digest cannot: that the bytes this pass fetched hashed to what was already
     *        recorded. Null when no comparison happened.
     */
    IngestLineageEmitter.CapturedContent describeCapturedContent(
            String repositoryId, String objectId, String computedHash,
            ContentComparison comparison) {
        IngestLineageEmitter.CapturedContent observed =
                describeStoredState(repositoryId, objectId, computedHash);
        if (comparison != null && comparison.matchedRecordedHash()
                && observed.digest() == null) {
            // Augment, do NOT short-circuit. An earlier version returned early here and turned an
            // observed "no content" into an inferred "undetermined" — trading evidence for a
            // claim, which is the opposite of the point (external review). The state stays
            // whatever the read-back found; only the digest and its subject are added.
            return observed.withMatchedInputDigest(comparison.comparedDigest(),
                    "This import fetched the content and its digest equalled the one already "
                            + "recorded for this object, so nothing was re-stored. That is a "
                            + "comparison against a recorded digest, not against the stored "
                            + "bytes.");
        }
        return observed;
    }

    /** The read-back half, unchanged: what the repository can be seen to hold. */
    private IngestLineageEmitter.CapturedContent describeStoredState(
            String repositoryId, String objectId, String computedHash) {
        if (computedHash != null) {
            // This import supplied and hashed the bytes it stored — the strongest case, and no
            // read-back is needed to know it.
            return IngestLineageEmitter.CapturedContent.hashed(computedHash);
        }
        try {
            Content stored = contentService.getContent(repositoryId, objectId);
            if (stored instanceof Document doc) {
                String attachmentId = doc.getAttachmentNodeId();
                if (attachmentId == null || attachmentId.isBlank()) {
                    // The repository itself treats a blank id as no attachment.
                    return IngestLineageEmitter.CapturedContent.none();
                }
                // A reference is not bytes, and NOTHING cheap here can close that gap. Three
                // attempts were made and all three were wrong (external review):
                //
                //   getAttachment          — does a binary GET and returns a live stream this
                //                            path discarded unclosed (one leaked connection and
                //                            one round trip per no-hash import), and the DAO
                //                            swallows a failed fetch and returns the node anyway,
                //                            so its non-null carries no information.
                //   getAttachmentRef       — metadata-only and leak-free, but convertRef falls
                //                            back to the STORED length field when _attachments
                //                            is absent, so it cannot see the binary either.
                //   getAttachmentActualSize— names the "content" attachment, but falls through to
                //                            stream measurement for compressed ones, which is the
                //                            download this path must not do.
                //
                // So the reference is REPORTED, not converted into a claim. Whether the bytes are
                // held and readable is a fixity question (P1-2) with its own cost budget; deciding
                // it as a side effect of ingest is what produced each of the three wrong answers.
                return IngestLineageEmitter.CapturedContent.unknown(
                        "the object references content (" + attachmentId + ") from an earlier "
                                + "import; this import neither supplied those bytes nor verified "
                                + "them, so whether they are held is undetermined here");
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
     *
     * <p>Only for objects THIS operation created. The method runs on every chat import including
     * dedupe-skipped ones, and for an object that was already here nothing available says when we
     * first held it: the clock says today, and {@code cmis:creationDate} survives migration and
     * archive restore and names a later version's own creation. Both were tried and both were
     * wrong (external review), so a pre-existing object is left unstamped. Recovering the answer
     * for legacy objects means reading their provenance events — P1-1(d).
     */
    /**
     * The capture-window writes, shared by the two phases that need them: the beforeEmit hook
     * (create/version path — ahead of the emit since P1-1(e) §3) and the wrapper's dedupe-skip
     * fall-through (fill semantics, D6).
     */
    private void applyCaptureWindow(CaptureScope captureScope, CallContext callContext,
            ExternalIngestRequest request, String objectId, boolean noEventForThisPass,
            List<String> warnings, List<String> refusedByThisPass,
            List<String> filledByThisPass) {
        // Apply capture window datetime properties if provided in metadata
        if (request.getMetadata() != null) {
            String windowStart = resolveMetadataString(request, "captureWindowStart");
            String windowEnd = resolveMetadataString(request, "captureWindowEnd");
            if (windowStart != null || windowEnd != null) {
                try {
                    Content chatContent = contentService.getContent(request.getRepositoryId(), objectId);
                    if (chatContent != null) {
                        List<Aspect> chatAspects = chatContent.getAspects() != null ? chatContent.getAspects() : new ArrayList<>();
                        Aspect chatAspect = chatAspects.stream()
                                .filter(a -> "nemaki:chatContextMetadata".equals(a.getName())).findFirst().orElse(null);
                        if (chatAspect != null && chatAspect.getProperties() != null) {
                            Map<String, Property> propMap = new java.util.LinkedHashMap<>();
                            for (Property p : chatAspect.getProperties()) propMap.put(p.getKey(), p);
                            // Same rule as the eight fields above: on a skip no lineage event is
                            // emitted, so a value already captured is not replaced — only a gap
                            // is filled (P1-1(d) D6).
                            int before = countWindowValues(propMap);
                            List<String> pendingWindowFill = new ArrayList<>();
                            if (windowStart != null) {
                                GregorianCalendar gc = new GregorianCalendar();
                                gc.setTimeInMillis(java.time.Instant.parse(windowStart).toEpochMilli());
                                putCapturedWindowValue(propMap, "nemaki:chatCaptureWindowStart", gc,
                                        noEventForThisPass, warnings, refusedByThisPass,
                                        pendingWindowFill);
                            }
                            if (windowEnd != null) {
                                GregorianCalendar gc = new GregorianCalendar();
                                gc.setTimeInMillis(java.time.Instant.parse(windowEnd).toEpochMilli());
                                putCapturedWindowValue(propMap, "nemaki:chatCaptureWindowEnd", gc,
                                        noEventForThisPass, warnings, refusedByThisPass,
                                        pendingWindowFill);
                            }
                            // Nothing to fill means nothing to write; a bare revision bump would
                            // record a mutation that did not happen.
                            if (countWindowValues(propMap) > before || !noEventForThisPass) {
                                chatAspect.setProperties(new ArrayList<>(propMap.values()));
                                // An aspect update written directly rather than through a helper —
                                // which is exactly why it was missed. It is on the tracked allowlist
                                // (design §5.0) and without this a failed capture window still
                                // completed the row as CAPTURED (external review).
                                captureScope.ensureIntentOpened();
                                contentService.update(callContext, request.getRepositoryId(), chatContent);
                                captureScope.record("applyCaptureWindow", MutationOutcome.SUCCEEDED);
                                // Only now. Before the update returns, these are intentions.
                                filledByThisPass.addAll(pendingWindowFill);
                            }
                        }
                    }
                } catch (CaptureScope.CaptureIntentFailedException failClosed) {
                    throw failClosed;
                } catch (Exception e) {
                    captureScope.record("applyCaptureWindow", MutationOutcome.INDETERMINATE,
                            e.getMessage());
                    warnings.add("Capture window metadata failed: " + e.getMessage());
                }
            }
        }

    }

    /** @return the stamped instant (ISO-8601) when THIS call stamped it; null otherwise. */
    private String applyChatCapturedAt(CaptureScope captureScope,
            CallContext callContext, ExternalIngestRequest request,
                                     String objectId, boolean createdObject,
                                     List<String> warnings) {
        if (objectId == null) {
            return null;
        }
        if (!createdObject) {
            // This object was already here. When we first held it is not knowable from anything
            // available: the clock says today, and cmis:creationDate survives migration and
            // archive restore and names a later version's own creation (external review). Two
            // wrong answers were shipped in review before this one; the third option is to
            // record nothing, which is what an unknown fact deserves. Recovering it for legacy
            // objects means reading their provenance events — P1-1(d).
            return null;
        }
        try {
            Content content = contentService.getContent(request.getRepositoryId(), objectId);
            if (content == null) {
                warnings.add("Capture time (nemaki:chatCapturedAt) was not recorded: the stored "
                        + "object could not be read back");
                return null;
            }
            if (content.getAspects() == null) {
                // A null aspect list means the chat aspect is absent just as surely as a list
                // without it does; returning silently here left half the case unreported.
                warnings.add("Capture time (nemaki:chatCapturedAt) was not recorded: the stored "
                        + "object carries no aspects");
                return null;
            }
            Aspect chatAspect = content.getAspects().stream()
                    .filter(a -> "nemaki:chatContextMetadata".equals(a.getName()))
                    .findFirst().orElse(null);
            if (chatAspect == null || chatAspect.getProperties() == null) {
                // Either shape means the metadata step did not take effect, so the capture time
                // has nowhere to live. Say so rather than returning silently — the caller is
                // otherwise told the import succeeded (external review). The two are reported
                // separately because "the aspect is missing" and "the aspect is there but empty"
                // send an operator to different places.
                warnings.add("Capture time (nemaki:chatCapturedAt) was not recorded: "
                        + (chatAspect == null
                                ? "the chat context aspect is not present on the stored object"
                                : "the chat context aspect carries no properties"));
                return null;
            }
            Map<String, Property> props = new java.util.LinkedHashMap<>();
            for (Property p : chatAspect.getProperties()) {
                props.put(p.getKey(), p);
            }
            if (props.containsKey("nemaki:chatCapturedAt")) {
                // A re-import must not restamp custody: the first observation is the one that
                // means anything, and moving it forward would quietly erase how long we have
                // actually held the record. Since P1-1(c) the property is READONLY through CMIS,
                // so this no longer preserves a value planted by a client — only one this ingest
                // wrote. A second copy now exists on the EVENT for stamps made since P1-1(e)
                // — this early return covers a value stamped by an earlier pass.
                return null;
            }
            // The clock is correct HERE and only here: this operation just created the object,
            // so the moment it ran is the moment this deployment took custody. Applying it to an
            // object that was already present is the bug the guard above exists for.
            java.time.Instant stampedAt = java.time.Instant.now();
            GregorianCalendar now = new GregorianCalendar();
            now.setTimeInMillis(stampedAt.toEpochMilli());
            props.put("nemaki:chatCapturedAt", new Property("nemaki:chatCapturedAt", now));
            chatAspect.setProperties(new ArrayList<>(props.values()));
            captureScope.ensureIntentOpened();
            contentService.update(callContext, request.getRepositoryId(), content);
            captureScope.record("applyChatCapturedAt", MutationOutcome.SUCCEEDED);
            return stampedAt.toString();
        } catch (CaptureScope.CaptureIntentFailedException failClosed) {
            // Never swallowed: the intent could not be written, so nothing may be changed.
            throw failClosed;
        } catch (Exception e) {
            captureScope.record("applyChatCapturedAt", MutationOutcome.INDETERMINATE,
                    e.getMessage());
            warnings.add("Capture time (nemaki:chatCapturedAt) was not recorded: " + e.getMessage());
        }
        return null;
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
     *
     * <p><b>Outside the capture boundary, deliberately.</b> Its one remaining caller is
     * {@code FetchSupport}, which links objects <em>after</em> the entry point has returned and
     * its scope has been completed — design §4 rule 7 puts that case out of scope for this
     * change and closes it alongside the stamp in P1-1(e). Inside an ingest, use the overload
     * that takes a scope; a relationship created through this one is a change no intent covers.
     */
    @Override
    public String createDirectRelationship(CallContext callContext, String repositoryId,
                                           String sourceId, String targetId) {
        return createDirectRelationship(callContext, repositoryId, sourceId, targetId,
                "cmis:relationship", CaptureScope.inactive());
    }

    /**
     * Typed relationship with no capture scope.
     *
     * <p>Kept so a caller that has no scope in hand still compiles, but it is NOT the right
     * overload inside an ingest: a relationship created here is a change that no intent covers.
     * The call sites that legitimately use it are the ones design §4 rule 7 puts outside this
     * PR's boundary — the orchestrators that link objects after the entry point returns.
     */
    String createDirectRelationship(CallContext callContext, String repositoryId,
                                    String sourceId, String targetId, String relationshipTypeId) {
        return createDirectRelationship(callContext, repositoryId, sourceId, targetId,
                relationshipTypeId, CaptureScope.inactive());
    }

    /**
     * Creates a typed CMIS relationship.
     * Falls back to generic cmis:relationship if the custom type is not available.
     */
    String createDirectRelationship(CallContext callContext, String repositoryId,
                                            String sourceId, String targetId,
                                            String relationshipTypeId, CaptureScope captureScope) {
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
            // Opened here, after the idempotent early return above: a link that already exists
            // changes nothing, and an intent for it could never be completed.
            captureScope.ensureIntentOpened();
            objectService.createRelationship(callContext, repositoryId, relProps, null, null, null, null);
            captureScope.record("createRelationship", MutationOutcome.SUCCEEDED);
            return null;
        } catch (CaptureScope.CaptureIntentFailedException failClosed) {
            // Never swallowed. This is the fail-closed point: it means the intent could 
            // not be written, so nothing may be changed. Caught by the surrounding catch
            //  it became a warning, and on the replace path the code then fell through a
            // nd created the replacement anyway (external review).
            throw failClosed;
        } catch (Exception e) {
            // Fallback to generic cmis:relationship if custom type fails
            if (!"cmis:relationship".equals(relationshipTypeId)) {
                logger.debug("Custom relationship type {} failed, falling back to cmis:relationship", relationshipTypeId);
                return createDirectRelationship(callContext, repositoryId, sourceId, targetId,
                        "cmis:relationship", captureScope);
            }
            logger.warn("Relationship {} → {} failed: {}", sourceId, targetId, e.getMessage());
            // INDETERMINATE: the throw may have come from before createRelationship or from the
            // call itself, and the wrapper below it returns null for every kind of failure.
            captureScope.record("createRelationship", MutationOutcome.INDETERMINATE,
                    e.getMessage());
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
    private String applyAclSyncPolicy(CallContext callContext, String repositoryId,
                                     CaptureScope captureScope,
                                     String objectId, String policy,
                                     Content content) {
        if ("inherit_from_folder".equals(policy) || policy.isBlank()) {
            return null; // CMIS default — nothing to do
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
                captureScope.record("breakAclInheritance", MutationOutcome.SUCCEEDED);
                logger.info("ACL inheritance disabled for imported document {}", objectId);
            } catch (Exception e) {
                captureScope.record("breakAclInheritance", MutationOutcome.FAILED, e.getMessage());
                // Returned, not only logged: this decides who can read the imported document.
                // A warn line here left the caller believing the policy had been applied
                // (external review).
                logger.warn("Failed to break ACL inheritance for {}: {}", objectId, e.getMessage());
                return "ACL inheritance was NOT broken for " + objectId + " (aclSyncPolicy=none): "
                        + e.getMessage() + ". The document may be readable by everyone who can "
                        + "read its folder.";
            }
        }
        if ("copy_from_source".equals(policy)) {
            return applySourceAcl(callContext, repositoryId, objectId, content, captureScope);
        }
        return null;
    }

    /**
     * Apply ACL from source system metadata.
     * Expects metadata key "sourceAcl" as a List of maps with "principalId" and "permissions" keys.
     * Example: [{"principalId": "user1", "permissions": ["cmis:read"]}, ...]
     */
    @SuppressWarnings("unchecked")
    private String applySourceAcl(CallContext callContext, String repositoryId,
                                 String objectId, Content content, CaptureScope captureScope) {
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
            if (contextJson == null) {
                // Nothing recorded to copy FROM. Distinguished from a failure below: this is a
                // no-op, not a silent loss (external review).
                return null;
            }

            Map<String, Object> context = JSON_MAPPER.readValue(contextJson,
                    new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
            Object sourceAclObj = context.get("sourceAcl");
            if (!(sourceAclObj instanceof List<?> sourceAclList) || sourceAclList.isEmpty()) {
                return null; // the source carried no ACL — a no-op, not a failure
            }

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
                // Opened here rather than at the top: every branch above returns without writing,
                // and an intent opened for those could never be completed.
                captureScope.ensureIntentOpened();
                contentService.update(callContext, repositoryId, content);
                captureScope.record("applySourceAcl", MutationOutcome.SUCCEEDED);
                // This path writes an ACL without going through AclService, so nothing else
                // advances the cache generation — and other replicas would keep serving the
                // pre-import permissions until their entries expired. Bumping here rather than
                // in the DAO keeps ordinary content updates from clearing every replica.
                jp.aegif.nemaki.util.cache.AclCacheGeneration.advance(repositoryId);
                logger.info("Applied {} source ACEs to imported document {}", localAces.size(), objectId);
            }
        } catch (CaptureScope.CaptureIntentFailedException failClosed) {
            // Never swallowed. This is the fail-closed point: it means the intent could 
            // not be written, so nothing may be changed. Caught by the surrounding catch
            //  it became a warning, and on the replace path the code then fell through a
            // nd created the replacement anyway (external review).
            throw failClosed;
        } catch (Exception e) {
            // Returned, not only logged. Under copy_from_source a corrupted
            // nemaki:externalContext leaves the document on the INHERITED (wider) ACL, and the
            // caller used to be told the import succeeded (external review).
            logger.warn("Failed to apply source ACL for {}: {}", objectId, e.getMessage());
            captureScope.record("applySourceAcl", MutationOutcome.INDETERMINATE, e.getMessage());
            return "Source ACL was NOT applied to " + objectId
                    + " (aclSyncPolicy=copy_from_source): " + e.getMessage()
                    + ". The document is left on the inherited ACL, which may be wider.";
        }
        return null;
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
    private String removeExistingRelationships(CaptureScope captureScope,
            CallContext callContext, String repositoryId, String objectId) {
        if (relationshipService == null) return null;
        try {
            int totalRemoved = 0;
            int totalFailed = 0;
            java.math.BigInteger batchSize = java.math.BigInteger.valueOf(100);
            java.math.BigInteger skipCount = java.math.BigInteger.ZERO;
            // Paginate to handle documents with many relationships
            while (true) {
                org.apache.chemistry.opencmis.commons.data.ObjectList rels = relationshipService.getObjectRelationships(
                        callContext, repositoryId, objectId, true,
                        org.apache.chemistry.opencmis.commons.enums.RelationshipDirection.SOURCE,
                        null, null, false, batchSize, skipCount, null);
                if (rels == null || rels.getObjects() == null || rels.getObjects().isEmpty()) break;

                int removedThisPass = 0;
                for (var relData : rels.getObjects()) {
                    try {
                        captureScope.ensureIntentOpened();
                        objectService.deleteObject(callContext, repositoryId,
                                relData.getId(), true, null);
                        captureScope.record("removeRelationship", MutationOutcome.SUCCEEDED);
                        totalRemoved++;
                        removedThisPass++;
                    } catch (CaptureScope.CaptureIntentFailedException failClosed) {
                        // Never swallowed. This is the fail-closed point: it means the intent could 
                        // not be written, so nothing may be changed. Caught by the surrounding catch
                        //  it became a warning, and on the replace path the code then fell through a
                        // nd created the replacement anyway (external review).
                        throw failClosed;
                    } catch (Exception e) {
                        totalFailed++;
                        captureScope.record("removeRelationship", MutationOutcome.FAILED,
                                e.getMessage());
                        logger.warn("Failed to remove relationship {}: {}", relData.getId(), e.getMessage());
                    }
                }
                // After deleting, re-fetch from start (indices shift after deletion) — which is
                // why skipCount stays at zero. That makes progress depend entirely on deletions
                // succeeding: with every delete failing, the same page came back for ever and
                // hasMoreItems() stayed true, so this looped without end while holding up the
                // import (external review). A pass that removed nothing cannot make progress.
                if (removedThisPass == 0) {
                    break;
                }
                if (!Boolean.TRUE.equals(rels.hasMoreItems())) break;
            }
            if (totalRemoved > 0) {
                logger.info("Resync: removed {} relationships from {}", totalRemoved, objectId);
            }
            if (totalFailed > 0) {
                // Returned, not only logged: replace_relationships_on_resync exists to leave the
                // object with ONLY the incoming relationships. Surviving edges mean the object
                // is not in the state the policy promises.
                return "Resync did not remove " + totalFailed + " existing relationship(s) from "
                        + objectId + "; stale edges remain alongside the re-imported ones";
            }
            return null;
        } catch (CaptureScope.CaptureIntentFailedException failClosed) {
            // The per-item guard rethrows into THIS catch, which turned it straight back into a
            // warning — so the guard was inoperative (external review).
            throw failClosed;
        } catch (Exception e) {
            logger.warn("Failed to query relationships for {}: {}", objectId, e.getMessage());
            return "Existing relationships of " + objectId + " could not be listed, so the resync "
                    + "policy could not be applied: " + e.getMessage();
        }
    }

    private String sanitizeFilename(String name) {
        if (name == null) return "untitled";
        return name.replaceAll("[/\\\\:*?\"<>|\\x00]", "_").trim();
    }

    /**
     * Records what the evidence metadata looked like when this pass finished
     * (design p1-1d-metadata-hash.md).
     *
     * <p>Runs at completion — after every fill this wrapper performed — and reads the object
     * BACK through the raw aspect path, so the hash is of applied values, never of the request
     * (hashing the request would notarize a claim; D1). Two hashes, because the chat properties
     * are READONLY through CMIS while the source-identity ones are not yet: a mismatch means
     * different things for the two sets and mixing them would let a legitimate edit read as
     * tampering.
     *
     * <p>A failed read-back records the REASON and no hash — filling in a hash from request
     * values here would be the exact substitution the subject field exists to prevent.
     */
    private void appendAppliedMetadataHashes(Map<String, Object> evidence,
            String repositoryId, String objectId) {
        try {
            if (contentService == null) {
                evidence.put("appliedMetadataHashUnavailable", "no content service wired");
                return;
            }
            Content content = contentService.getContent(repositoryId, objectId);
            if (content == null) {
                evidence.put("appliedMetadataHashUnavailable",
                        "the object could not be read back at completion");
                return;
            }
            EvidenceMetadataHash.AppliedHashes hashes =
                    EvidenceMetadataHash.compute(content.getAspects());
            if (hashes.isEmpty()) {
                return;
            }
            if (hashes.chatEvidenceHash() != null) {
                evidence.put("appliedChatEvidenceHash", hashes.chatEvidenceHash());
            }
            if (hashes.sourceIdentityHash() != null) {
                evidence.put("appliedSourceIdentityHash", hashes.sourceIdentityHash());
            }
            evidence.put("metadataHashSubject", EvidenceMetadataHash.SUBJECT);
            evidence.put("metadataHashFormula", EvidenceMetadataHash.FORMULA);
        } catch (Exception e) {
            evidence.put("appliedMetadataHashUnavailable",
                    "the object could not be read back at completion: " + e.getMessage());
        }
    }

    /**
     * The {@code nemaki:externalIntegration} property map one capture pass writes.
     *
     * <p>Extracted so a test can pin that this set and
     * {@link EvidenceMetadataHash#SOURCE_IDENTITY_PROPERTIES} (plus the two declared
     * exclusions) are the SAME set — a twelfth put added here without a hash-side decision
     * fails the pin instead of silently leaving a new fact outside the metadata hash
     * (design p1-1d-metadata-hash.md §2.1, external review P2-6).
     */
    Map<String, Object> buildSourceIdentityProps(ConnectorDefinition connector,
            ExternalIngestRequest request, String contentHash) {
        Map<String, Object> newProps = new java.util.LinkedHashMap<>();
        // ABSENT source values stay absent. The first shape put "" for every missing value, and
        // because the merge overwrites any key present in this map, a version-up whose request
        // happened to lack sourceUrl BLANKED the stored source identity — evidence destroyed by
        // an ordinary re-import, with an event that never mentions it (external review, audit
        // #12 / plan D-7). Omitting the key makes the merge preserve the stored value: fill
        // semantics, the same rule the chat evidence got in D6.
        putUnlessBlank(newProps, "nemaki:sourceArchetype",
                connector.getSourceArchetype() != null ? connector.getSourceArchetype().name() : null);
        putUnlessBlank(newProps, "nemaki:sourceSystem", connector.getSourceSystem());
        putUnlessBlank(newProps, "nemaki:sourceObjectType", request.getSourceObjectType());
        putUnlessBlank(newProps, "nemaki:sourceObjectId", request.getSourceObjectId());
        putUnlessBlank(newProps, "nemaki:sourceUrl", request.getSourceUrl());
        putUnlessBlank(newProps, "nemaki:ingestionRunId", request.getRequestId());
        putUnlessBlank(newProps, "nemaki:externalSourceType",
                connector.getSourceArchetype() != null
                        ? connector.getSourceArchetype().name().toLowerCase() : null);
        putUnlessBlank(newProps, "nemaki:externalSourceId", connector.getSourceSystem());
        newProps.put("nemaki:externalContextUpdatedAt", new GregorianCalendar());

        // Persist externalContext from request metadata if provided
        // Strip binary content (contentBase64) to avoid bloating CouchDB documents
        if (request.getMetadata() != null && !request.getMetadata().isEmpty()) {
            // Jackson 3 throws unchecked; the caller's own catch turns it into the metadata
            // warning exactly as before the extraction. Swallowing it here would silently drop
            // externalContext (fail-open-boundary-trap).
            Map<String, Object> sanitized = stripBinaryContent(request.getMetadata());
            newProps.put("nemaki:externalContext", JSON_MAPPER.writeValueAsString(sanitized));
        }

        // Store content hash for future dedupe comparisons
        if (contentHash != null && !contentHash.isBlank()) {
            newProps.put("nemaki:contentHash", contentHash);
        }
        return newProps;
    }

    private static void putUnlessBlank(Map<String, Object> props, String key, String value) {
        if (value != null && !value.isBlank()) {
            props.put(key, value);
        }
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

    /**
     * The capture boundary's write seam.
     *
     * <p>Optional on purpose. Its presence is the condition the fail-closed behaviour hangs on:
     * with no store there is no lineage database to write an intent to, and refusing to ingest
     * because of that would break every deployment that does not run lineage at all. It is the
     * same shape as the existing {@code ingestLineageEmitter != null} guard, not a test flag.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CaptureIntentStore captureIntentStore;

    public void setCaptureIntentStore(CaptureIntentStore captureIntentStore) {
        this.captureIntentStore = captureIntentStore;
    }

    private final java.util.concurrent.atomic.AtomicBoolean captureUnwiredWarned =
            new java.util.concurrent.atomic.AtomicBoolean();

    private void warnCaptureBoundaryUnwiredOnce() {
        if (captureUnwiredWarned.compareAndSet(false, true)) {
            logger.warn("The ingest capture boundary is NOT wired (no CaptureIntentStore bean), "
                    + "so no capture intent rows will be written and the unresolved-capture "
                    + "listing will stay empty for reasons that have nothing to do with ingests "
                    + "succeeding. This is expected only where lineage is not deployed at all.");
        }
    }

    /**
     * Records a wrapper post-processing update against the scope.
     *
     * <p>These run AFTER the internal execute returns, and their failures become warning strings
     * rather than exceptions — which is exactly why the tracker has to sit here: {@code CAPTURED}
     * means "every tracked change succeeded", and without this a metadata failure would still
     * complete the capture (design §5.0, external review).
     *
     * <p>{@code ensureIntentOpened} is called first because on a dedupe-skip path this update is
     * the FIRST change of the whole operation — the internal execute returned without touching
     * anything (rule 1).
     *
     * @param error the helper's own return value: {@code null} means it succeeded
     */
    private void recordWrapperUpdate(CaptureScope captureScope, String operation, String error) {
        captureScope.record(operation,
                error == null ? MutationOutcome.SUCCEEDED : MutationOutcome.INDETERMINATE, error);
    }

    /**
     * Opens the intent only if the helper about to run will actually write.
     *
     * <p>These helpers return {@code null} both when they wrote and when there was nothing to
     * write. Opening unconditionally produced an intent row for an operation that changed
     * nothing — a row that can never be completed — and recording it as SUCCEEDED put a
     * mutation in the evidence that never happened (design §6.10-B2, external review).
     *
     * @return whether the caller should record a mutation for it
     */
    private boolean openIfWriting(CaptureScope captureScope, boolean willWrite) {
        if (!willWrite) {
            return false;
        }
        captureScope.ensureIntentOpened();
        return true;
    }

    /**
     * Builds an un-opened scope for this attempt.
     *
     * <p>Un-opened is the whole point (rule 2): at this moment nobody knows whether anything
     * will be changed. Dry runs, dedupe skips, idempotency skips and imports whose attachments
     * are all skipped finish normally having changed nothing, and a row opened for them could
     * never be completed.
     */
    CaptureScope newCaptureScope(CallContext callContext, ExternalIngestRequest request) {
        if (captureIntentStore == null || request == null) {
            if (captureIntentStore == null) {
                // Said once, loudly. The store is field-injected into a bean this context defines
                // in XML, so if annotation processing ever stops covering it the boundary turns
                // off with no other symptom — every ingest keeps working and no evidence is
                // written. "Off because nothing is wired" must be visible, not inferred from an
                // empty listing.
                warnCaptureBoundaryUnwiredOnce();
            }
            return CaptureScope.inactive();
        }
        String intentId = java.util.UUID.randomUUID().toString();
        return new CaptureScope(captureIntentStore, new CaptureIntent(
                CaptureIntent.documentIdFor(intentId),
                intentId,
                System.currentTimeMillis(),
                request.getRepositoryId(),
                request.getConnectorId(),
                // The source system and the process type come from the connector and the profile,
                // which are resolved inside execute. They are filled in by describe() before the
                // row is written; nothing is lost, because nothing is written until then.
                null,
                request.getSourceObjectType(),
                request.getSourceObjectId(),
                request.getRequestId(),
                null,
                callContext == null ? null : callContext.getUsername(),
                resolveMetadataString(request, "onBehalfOf")));
    }

    /**
     * Completes the scope and folds anything the caller must be told into the result.
     *
     * <p>A capture that could not be recorded is a warning, not an error: the content is already
     * committed by this point, and turning that into a failure would tell the caller nothing was
     * imported when something was. The unresolved listing is what surfaces it.
     */
    ExternalIngestResult withCaptureOutcome(ExternalIngestResult result, CaptureScope scope) {
        Map<String, Object> evidence = new java.util.LinkedHashMap<>();
        if (result != null && result.objectId() != null) {
            evidence.put("objectId", result.objectId());
            // Only for a scope that opened: an unopened scope writes no row, so reading the
            // object back would cost a getContent per no-op poll for a hash nobody stores.
            if (scope.isOpened() && scope.intent() != null) {
                appendAppliedMetadataHashes(evidence, scope.intent().repositoryId(),
                        result.objectId());
            }
        }
        if (scope.wasOpenRefused()) {
            // Belt and braces with the rethrow guards on the individual catches. If one of them
            // is ever missed, the ingest still cannot report success: a change with no preceding
            // intent is the state the whole boundary exists to prevent.
            return ExternalIngestResult.error(
                    result == null ? null : result.requestId(),
                    result == null ? null : result.objectId(),
                    "The capture intent could not be written, so this ingest is reported as "
                            + "failed rather than leaving changes no evidence records.",
                    result == null ? List.of() : result.warnings());
        }
        if (result != null && result.errors() != null && !result.errors().isEmpty()) {
            // One rule covering both the thrown and the returned failure: an ingest that reports
            // errors did not run to the end, however many of its individual changes succeeded.
            scope.operationFailed("the ingest returned: " + String.join("; ", result.errors()));
        }
        CaptureScope.CaptureResult outcome = scope.complete(evidence);
        if (outcome.warning() == null || result == null) {
            return result;
        }
        List<String> warnings = new ArrayList<>(
                result.warnings() == null ? List.of() : result.warnings());
        warnings.add(outcome.warning());
        return result.withWarnings(warnings);
    }

    @Override
    public ExternalIngestResult execute(CallContext callContext, ExternalIngestRequest request) {
        // The public entry owns its own root scope (rule 3). A wrapper that has post-processing
        // of its own passes its scope to the overload below and completes it after that work,
        // so the evidence describes the final state rather than an intermediate one.
        CaptureScope scope = newCaptureScope(callContext, request);
        ExternalIngestResult result = execute(callContext, request, scope);
        return withCaptureOutcome(result, scope);
    }

    /**
     * The archetype-agnostic seam P1-1(e) §3 adds: runs AFTER the document exists (create or
     * new version) and BEFORE the lineage emit, so an archetype's aspect writes can precede the
     * event — the ordering (b) §7 said had no gate. The wrapper supplies it; execute() knows
     * nothing about chat.
     *
     * <p>Failure contract (Codex H5): implementations do not swallow. A
     * {@code CaptureIntentFailedException} propagates fail-closed; any other exception must be
     * recorded on the scope by the write that failed (as the wrapper phase always did) and then
     * propagate — execute()'s own failure path, DLQ included, takes it from there. Returning
     * extra pass facts is optional; null reads as none.
     */
    interface BeforeEmitHook {
        java.util.Map<CaptureEvidenceField, String> beforeEmit(String objectId,
                boolean createdObject) throws Exception;
    }

    ExternalIngestResult execute(CallContext callContext, ExternalIngestRequest request,
            CaptureScope captureScope) {
        return execute(callContext, request, captureScope, null);
    }

    ExternalIngestResult execute(CallContext callContext, ExternalIngestRequest request,
            CaptureScope captureScope, BeforeEmitHook beforeEmitHook) {
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

        // Everything the row must carry is known by now, and nothing has been written yet.
        // Resolved ONCE; the same instance reaches the intent row here and the lineage emit
        // below — outbox actor == event actor by construction (D7, AC9).
        jp.aegif.nemaki.rest.purview.journal.LineageExecutionAttribution attribution =
                resolveExecutionAttribution(profile, callContext);
        captureScope.describe(connector == null ? null : connector.getSourceSystem(),
                connector == null || connector.getSourceArchetype() == null
                        ? null : connector.getSourceArchetype().name(),
                attribution.executedBy(), attribution.onBehalfOf());

        byte[] bufferedContent = null; // retained for DLQ on failure
        // Hoisted so the catch below can report what actually happened. Declared inside the try,
        // they were invisible to it, so every failure said "no object, no warnings" even when a
        // document had been committed and warnings had accumulated (external review).
        String committedObjectId = null;
        List<String> accumulatedWarnings = new ArrayList<>();
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
            // Held so the emit can see it: the unchanged-content branch nulls computedHash, and
            // the fact that the digests MATCHED goes with it unless it is kept (P1-1(d) D2).
            ContentComparison dedupeComparison = null;
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
                                        // NOT on a dry run. This block runs BEFORE the dry-run
                                        // gate below, so a preview used to durably delete the
                                        // idempotency record (external review).
                                        if (!request.isDryRun()) {
                                            integrationSettingsService.deleteSettings(java.util.Set.of(idempKey));
                                        }
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
            boolean createdObject = false;
            String versionLabel = "1.0";
            // Dedupe-stage problems have to survive until the result is built, further down —
            // AND until the catch, if one of the later steps throws. Held in the hoisted list
            // rather than a local one: a `replace` whose delete failed adds its warning here and
            // then falls through to createDocument, so a throw there used to discard the very
            // example the failure javadoc gives (external review).
            List<String> dedupeWarnings = accumulatedWarnings;

            if (existingDoc != null && existingDoc.isDocument()) {
                // Existing document found — apply dedupe policy
                if ("skip_if_same_version".equals(dedupePolicy)) {
                    return ExternalIngestResult.skipped(requestId, existingDoc.getId(),
                            "Document already exists with same source identity");
                } else if ("replace".equals(dedupePolicy)) {
                    // Delete existing and create fresh
                    try {
                        // The first change in this path, so the intent opens here — not at the
                        // entry, where a skip or a dry run would leave a row with nowhere to go.
                        captureScope.ensureIntentOpened();
                        objectService.deleteObject(callContext, repositoryId, existingDoc.getId(), true, null);
                        captureScope.record("replaceDelete", MutationOutcome.SUCCEEDED);
                        // Recorded NOW: the delete has happened. If the replacement then fails,
                        // the caller must be told which document was removed — reporting "no
                        // object" for an operation that deleted one is the opposite of the truth.
                        committedObjectId = existingDoc.getId();
                        logger.info("Dedupe replace: deleted existing document {}", existingDoc.getId());
                    } catch (CaptureScope.CaptureIntentFailedException failClosed) {
                        // Never swallowed. This is the fail-closed point: it means the intent could 
                        // not be written, so nothing may be changed. Caught by the surrounding catch
                        //  it became a warning, and on the replace path the code then fell through a
                        // nd created the replacement anyway (external review).
                        throw failClosed;
                    } catch (Exception e) {
                        // Returned, not only logged. The code falls through and creates the
                        // replacement regardless, so a failed delete leaves BOTH documents —
                        // and the caller was told the import succeeded (external review).
                        logger.warn("Dedupe replace: failed to delete {}: {}", existingDoc.getId(), e.getMessage());
                        captureScope.record("replaceDelete", MutationOutcome.FAILED, e.getMessage());
                        dedupeWarnings.add("The document being replaced (" + existingDoc.getId()
                                + ") could not be deleted: " + e.getMessage()
                                + ". A replacement was created, so both now exist.");
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
                    String relRemovalError = removeExistingRelationships(captureScope, callContext, repositoryId,
                            existingDoc.getId());
                    if (relRemovalError != null) {
                        dedupeWarnings.add(relRemovalError);
                    }
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
                // From here on this object is what the operation is acting on. A checkOut that
                // succeeds and a checkIn that throws leaves it CHECKED OUT and locked; without
                // this the caller is not told which document is stuck, and every later import of
                // the same item fails at checkOut for ever (external review).
                committedObjectId = objectId;

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
                    // checkOut is itself a CMIS change — it creates a PWC and alters the version
                    // series — and on this path it is the FIRST one, so the intent must precede
                    // it or the guarantee is broken before the document is even touched.
                    captureScope.ensureIntentOpened();
                    versioningService.checkOut(callContext, repositoryId, objectIdHolder, contentCopied, null);
                    captureScope.record("checkOut", MutationOutcome.SUCCEEDED);
                    String pwcId = objectIdHolder.getValue();
                    boolean isMajor = !"minor".equalsIgnoreCase(profile.getVersioningPolicy());
                    Holder<String> checkinHolder = new Holder<>(pwcId);
                    versioningService.checkIn(callContext, repositoryId, checkinHolder, isMajor,
                            null, contentStream, "Imported from " + connector.getSourceSystem(),
                            null, null, null, null);
                    captureScope.record("checkIn", MutationOutcome.SUCCEEDED);
                    objectId = checkinHolder.getValue();
                    versionLabel = "new version (always)";
                } else {
                    // version_up_on_content_change (default): compare content hash before versioning
                    String existingHash = computedHash == null ? null : getAspectProperty(
                            existingDoc, "nemaki:externalIntegration", "nemaki:contentHash");
                    ContentComparison comparison = compareContent(computedHash, existingHash);
                    dedupeComparison = comparison;
                    boolean contentChanged = comparison.contentChanged();
                    computedHash = comparison.hashToRecord();
                    if (comparison.versionLabel() != null) {
                        versionLabel = comparison.versionLabel();
                        logger.info("Dedupe: {} for {} (existing hash={})", versionLabel, objectId,
                                existingHash);
                    }
                    if (contentChanged) {
                        isNewVersion = true;
                        Holder<String> objectIdHolder = new Holder<>(objectId);
                        Holder<Boolean> contentCopied = new Holder<>(Boolean.FALSE);
                        captureScope.ensureIntentOpened();
                        versioningService.checkOut(callContext, repositoryId, objectIdHolder, contentCopied, null);
                        captureScope.record("checkOut", MutationOutcome.SUCCEEDED);
                        String pwcId = objectIdHolder.getValue();

                        boolean isMajor = !"minor".equalsIgnoreCase(profile.getVersioningPolicy());
                        Holder<String> checkinHolder = new Holder<>(pwcId);
                        versioningService.checkIn(callContext, repositoryId, checkinHolder, isMajor,
                                null, contentStream, "Imported from " + connector.getSourceSystem(),
                                null, null, null, null);
                        captureScope.record("checkIn", MutationOutcome.SUCCEEDED);
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
                captureScope.ensureIntentOpened();
                objectId = objectService.createDocument(callContext, repositoryId, properties,
                        targetFolderId, contentStream, vs, null, null, null, null);
                captureScope.record("createDocument", MutationOutcome.SUCCEEDED);
                createdObject = true;
            }

            committedObjectId = objectId;

            // 6. Apply secondary types
            List<String> warnings = accumulatedWarnings;
            String metadataError = applySourceMetadata(captureScope, repositoryId, objectId, callContext, connector, request, profile, computedHash);
            if (metadataError != null) {
                warnings.add(metadataError);
            }

            // 6b. Create relationship if parentObjectId is provided and policy allows.
            // Relationship failures are warnings, not errors — the document was already
            // created/versioned, so the import is a partial success, not a hard failure.
            String relError = applyRelationship(captureScope, callContext, repositoryId, objectId, profile, request);
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
            // P1-1(e) §3: the wrapper's aspect writes run HERE — after the document exists,
            // before the emit — so the event can carry what was applied. No catch: the hook's
            // failure contract (H5) routes exceptions into this method's own failure path.
            java.util.Map<CaptureEvidenceField, String> emitFacts = new java.util.LinkedHashMap<>();
            if (beforeEmitHook != null && objectId != null && !request.isDryRun()) {
                java.util.Map<CaptureEvidenceField, String> hookFacts =
                        beforeEmitHook.beforeEmit(objectId, createdObject);
                if (hookFacts != null) {
                    emitFacts.putAll(hookFacts);
                }
            }
            appendAppliedHashFacts(repositoryId, objectId, emitFacts);
            String lineageEventId = ingestLineageEmitter != null
                    ? ingestLineageEmitter.emitLineageEvent(repositoryId, objectId, targetFolderId,
                            lineageDocumentName, lineageOperationId, connector, request,
                            describeCapturedContent(repositoryId, objectId, computedHash,
                                    dedupeComparison),
                            // The SAME resolution the capture intent received at describe() —
                            // one resolver, one call, two records (D7, AC9).
                            attribution.executedBy(),
                            attribution.onBehalfOf(),
                            emitFacts)
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
                    false, false, null, lineageEventId, List.of(), warnings, createdObject);

        } catch (Exception e) {
            logger.error("Canonical import failed: requestId={}, error={}", requestId, e.getMessage(), e);
            // The committed object id, not null. An audit line that says "failed, object unknown"
            // cannot be matched against the document that is actually sitting in the repository.
            emitAuditEvent(request.getRepositoryId(), committedObjectId, callContext, false,
                    e.getMessage());

            boolean isTransient = isTransientError(e);
            // Save all non-manual failures to the DLQ so that no item is
            // silently lost when the scheduler advances its checkpoint past
            // this source item.  Permanent errors are marked retryable=false
            // so the operator can inspect (and optionally retry after fixing
            // the root cause) without auto-retry loops wasting resources.
            // NOT on a dry run. A preview that throws before the dry-run gate (an IOException
            // reading the content stream, or content over the size bound) used to persist a DLQ
            // document — with dryRun:true inside it and the buffered bytes attached — into the
            // conf database. Retrying that entry then deletes it without importing anything,
            // because a dry-run result reports success (external review).
            // Autonomy decided by the context's type, not the request's executionMode — a
            // manual caller could NAME itself "scheduled" and enroll its failure in the DLQ
            // (P1-1(e) §1.1, Codex L1). executionMode stays informational.
            if (ingestJobService != null && autonomousExecution(callContext)
                    && !request.isDryRun()) {
                try {
                    ingestJobService.saveToDlq(request,
                            (isTransient ? "[transient] " : "[permanent] ") + e.getMessage(),
                            bufferedContent);
                } catch (Exception dlqErr) {
                    logger.warn("Failed to save to DLQ — item may be lost: {}", dlqErr.getMessage());
                }
            }
            return ExternalIngestResult.error(requestId, committedObjectId,
                    (isTransient ? "[transient] " : "[permanent] ") + e.getMessage(),
                    accumulatedWarnings);
        }
    }

    /**
     * The failure path for a public entry point, after {@code execute()} has already returned.
     *
     * <p>Three things used to be lost here, and each of them is what an operator needs:
     *
     * <ul>
     *   <li><b>The object.</b> These wrappers fail AFTER the document is committed. Reporting
     *       {@code objectId = null} says the opposite of the truth.</li>
     *   <li><b>The warnings.</b> Everything accumulated up to the failure — a replaced document
     *       that could not be deleted, provenance that was not recorded — was discarded by
     *       {@code error(requestId, message)}.</li>
     *   <li><b>The DLQ entry.</b> {@code saveToDlq} is only reached from {@code execute()}'s own
     *       catch. A wrapper that turns the exception into a value never gets there, and the
     *       fetch orchestrators advance their high-water mark past it, so the source item is
     *       never re-fetched (external review).</li>
     * </ul>
     *
     * <p>The DLQ entry here is metadata-only: the content stream was consumed by the import that
     * has already run. That is still worth recording — it names the source item and stays
     * retryable.
     */
    /**
     * What a failing public entry point must still be able to report.
     *
     * <p>Carried rather than held in locals because three of the four entry points are large
     * enough that wrapping them in a try would mean re-indenting the whole body; the internal
     * method fills this in as it goes and the thin public wrapper reads it from the catch.
     */
    private static final class EntryFailureState {
        private String committedObjectId;
        private final List<String> warnings = new ArrayList<>();
    }

    private ExternalIngestResult failedAfterEntry(CallContext callContext,
            ExternalIngestRequest request, String requestId,
            String committedObjectId, List<String> warnings, String prefix, Exception e) {
        if (ingestJobService != null && autonomousExecution(callContext)
                && !request.isDryRun()) {
            try {
                ingestJobService.saveToDlq(request,
                        (isTransientError(e) ? "[transient] " : "[permanent] ") + prefix
                                + e.getMessage(),
                        null);
            } catch (Exception dlqErr) {
                logger.warn("Failed to save to DLQ — item may be lost: {}", dlqErr.getMessage());
            }
        }
        return ExternalIngestResult.error(requestId, committedObjectId, prefix + e.getMessage(),
                warnings);
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

    private String applySourceMetadata(CaptureScope captureScope,
            String repositoryId, String objectId, CallContext callContext,
                                       ConnectorDefinition connector, ExternalIngestRequest request,
                                       ImportProfileDefinition profile, String contentHash) {
        try {
            Content content = contentService.getContent(repositoryId, objectId);
            if (content == null) {
                logger.warn("Content not found after creation: {}", objectId);
                // INDETERMINATE, and recorded. The DAO returns null for a failed read as well as
                // for a genuinely absent object, so this skips the source identity, the content
                // hash, the external context AND the ACL policy — and the row still completed as
                // CAPTURED (external review).
                captureScope.record("applySourceMetadata", MutationOutcome.INDETERMINATE,
                        "the object could not be read back after creation");
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

            Map<String, Object> newProps =
                    buildSourceIdentityProps(connector, request, contentHash);

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

            // Take the RESULT. The DAO wraps the model in a CouchDocument and the new revision
            // lands on the WRAPPER, so this object keeps the revision it came in with. Passing it
            // on unchanged made the next update a guaranteed 409 — and that 409 was swallowed, so
            // aclSyncPolicy=none and copy_from_source never took effect and nothing said so
            // (external review).
            // ContentService.update cannot return null here — writeChangeEvent dereferences the
            // result before returning, so a null would already have thrown, and the object is
            // always a Document so the subtype dispatch cannot miss. A real failure surfaces as
            // the exception caught below (external review).
            // Unconditional: this method always writes. The intent opens immediately before it,
            // which on a metadata-only update is the first change of the whole operation.
            captureScope.ensureIntentOpened();
            Content updated = contentService.update(callContext, repositoryId, content);
            captureScope.record("applySourceMetadata", MutationOutcome.SUCCEEDED);

            // Apply ACL sync policy.
            // The error is HELD, not returned here: the cached DAO puts the very object we are
            // about to hand over into contentCache and returns THAT reference, so the ACL step
            // mutates the live cache entry (aclInherited=false, and the source ACEs) BEFORE its
            // write. If the write then fails and we returned early, this JVM would keep serving
            // an ACL that was never persisted — and the aclCache entry was already evicted by
            // the DAO, so the next authorization would recompute from the poisoned object
            // (external review). Invalidating first is what makes the failure safe.
            String aclError = null;
            if (profile != null && profile.getAclSyncPolicy() != null) {
                aclError = applyAclSyncPolicy(callContext, repositoryId, captureScope, objectId,
                        profile.getAclSyncPolicy(), updated);
            }

            // Invalidate cache
            if (nemakiCachePool != null) {
                try {
                    nemakiCachePool.get(repositoryId).removeCmisAndContentCache(objectId);
                } catch (CaptureScope.CaptureIntentFailedException failClosed) {
                    // Never swallowed. This is the fail-closed point: it means the intent could 
                    // not be written, so nothing may be changed. Caught by the surrounding catch
                    //  it became a warning, and on the replace path the code then fell through a
                    // nd created the replacement anyway (external review).
                    throw failClosed;
                } catch (Exception e) {
                    logger.debug("Cache invalidation failed for {}: {}", objectId, e.getMessage());
                }
            }
            return aclError;
        } catch (CaptureScope.CaptureIntentFailedException failClosed) {
            // Never swallowed. On a metadata-only update this method holds the FIRST change of
            // the whole operation, and this catch turns anything thrown into a warning while the
            // ingest returns success — so the fail-closed refusal became a note in the margin
            // (external review).
            throw failClosed;
        } catch (Exception e) {
            logger.warn("Failed to apply source metadata to {}: {}", objectId, e.getMessage());
            // INDETERMINATE, not FAILED: the throw may have come from before the write, from the
            // write itself, or from the ACL step after it. Which one cannot be told from here,
            // and calling it a clean failure would claim knowledge we do not have.
            captureScope.record("applySourceMetadata", MutationOutcome.INDETERMINATE,
                    e.getMessage());
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
    private String applyRelationship(CaptureScope captureScope,
            CallContext callContext, String repositoryId, String objectId,
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
        return createDirectRelationship(callContext, repositoryId, parentObjectId, objectId,
                "cmis:relationship", captureScope);
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
