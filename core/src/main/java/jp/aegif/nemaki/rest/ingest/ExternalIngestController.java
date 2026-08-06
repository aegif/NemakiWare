package jp.aegif.nemaki.rest.ingest;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jp.aegif.nemaki.config.ObjectMapperFactory;

/**
 * REST endpoint for canonical external ingestion.
 *
 * <p>Supports two content types:
 * <ul>
 *   <li>{@code application/json} — metadata-only import (no file content)</li>
 *   <li>{@code multipart/form-data} — file import with {@code content} part + {@code request} JSON part</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/repo/{repositoryId}/ingest")
public class ExternalIngestController {

    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultObjectMapper();

    @Autowired
    private CanonicalImportService canonicalImportService;

    @Autowired
    private HttpServletRequest httpRequest;

    /**
     * Required dependencies. Spring fails fast if any of these beans is
     * missing — they participate in the security gates and must not
     * fall back to "best effort" behaviour. Specifically:
     *
     * <ul>
     *   <li>{@link IngestAuthorizationService}: missing means non-admins
     *       would fall through to the legacy admin path, defeating
     *       folder-scoped delegation entirely (targetFolderOverride
     *       bypass, connector credential indirect delegation, etc.).</li>
     *   <li>{@link ConnectorDefinitionService}: missing means the
     *       runtime cannot re-verify that the connector is still
     *       delegated to the user/folder, so the {@code canUseConnector}
     *       check would be silently skipped — a privilege escalation
     *       window if an admin revokes delegation between profile
     *       create and execute.</li>
     *   <li>{@link ImportProfileDefinitionService}: missing means we
     *       cannot load the profile to re-evaluate cmis:all on its
     *       target folder.</li>
     * </ul>
     *
     * The runtime null checks below are defence in depth; with
     * {@code required=true} they should be unreachable.
     */
    @Autowired
    private ConnectorDefinitionService connectorDefinitionService;

    @Autowired
    private ImportProfileDefinitionService importProfileDefinitionService;

    @Autowired
    private IngestAuthorizationService ingestAuthorizationService;

    @Autowired(required = false)
    private jp.aegif.nemaki.audit.AuditLogger auditLogger;

    /** JSON-only ingest (metadata-only, no file content). */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ExternalIngestResult> ingestJson(
            @PathVariable String repositoryId,
            @RequestBody ExternalIngestRequest request) {
        return doIngest(repositoryId, request);
    }

    /** Multipart ingest with file content. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ExternalIngestResult> ingestMultipart(
            @PathVariable String repositoryId,
            @RequestPart("request") String requestJson,
            @RequestPart(value = "content", required = false) MultipartFile content) {
        try {
            ExternalIngestRequest request = MAPPER.readValue(requestJson, ExternalIngestRequest.class);
            if (content != null && !content.isEmpty()) {
                // Guard against oversized uploads (100MB default)
                if (content.getSize() > 100 * 1024 * 1024) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(ExternalIngestResult.error("unknown", "File exceeds maximum size (100MB)"));
                }
                request.setContentStream(content.getInputStream());
                if (request.getFileName() == null || request.getFileName().isBlank()) {
                    request.setFileName(sanitizeFilename(content.getOriginalFilename()));
                }
                if (request.getMimeType() == null || request.getMimeType().isBlank()) {
                    request.setMimeType(content.getContentType());
                }
            }
            return doIngest(repositoryId, request);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ExternalIngestResult.error("unknown", "Invalid request"));
        }
    }

    private ResponseEntity<ExternalIngestResult> doIngest(String repositoryId, ExternalIngestRequest request) {
        CallContext callContext = getCallContext();
        if (callContext == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        request.setRepositoryId(repositoryId);

        // Delegated execution gate. The authorization service is a required
        // bean (see field declaration); the null check below is defence in
        // depth — if it ever does come back null in some misconfigured
        // context, refuse rather than fall through to admin path.
        //
        // For non-admin callers, enforces that:
        //   1. profileId is given and resolves to a delegated profile;
        //   2. caller still holds cmis:all on the profile's target folder;
        //   3. connectorId (if any) is in the profile's allowedConnectorIds;
        //   4. targetFolderOverride is rejected (initial release boundary);
        // Admin path is unchanged.
        boolean delegatedRequest = false;
        if (ingestAuthorizationService == null) {
            // Missing means deny — never silently downgrade non-admins.
            // Audit emits SERVICES_UNAVAILABLE without consulting the
            // (null) authorization service.
            auditDelegatedAttempt(callContext, repositoryId, request, false,
                    "Authorization service unavailable; ingest disabled",
                    DenialReason.SERVICES_UNAVAILABLE);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ExternalIngestResult.error("unknown",
                            "Authorization service unavailable; ingest disabled"));
        }
        if (!ingestAuthorizationService.isAdmin(callContext)) {
            delegatedRequest = true;
            Denial denial = enforceDelegatedExecution(callContext, repositoryId, request);
            if (denial != null) {
                auditDelegatedAttempt(callContext, repositoryId, request, false,
                        denial.message(), denial.reason());
                return denial.toResponse();
            }
        }

        // Dispatch to specialized import flow based on connector archetype first,
        // then sourceObjectType, with .eml extension as a hint only for MESSAGE_CONTEXT
        ExternalIngestResult result;
        String sourceObjectType = request.getSourceObjectType();
        boolean isEml = request.getFileName() != null && request.getFileName().toLowerCase().endsWith(".eml");

        // Check connector archetype first — it takes precedence over filename
        SourceArchetype connectorArchetype = resolveConnectorArchetype(request.getConnectorId());

        if (connectorArchetype != null) {
            // Connector archetype is known — dispatch by archetype, not filename
            result = switch (connectorArchetype) {
                case MESSAGE_CONTEXT -> canonicalImportService.executeMailImport(callContext, request);
                case CHAT_CONTEXT -> canonicalImportService.executeChatContextImport(callContext, request);
                case COMPOUND_NOTE -> canonicalImportService.executeNoteImport(callContext, request);
                case BUSINESS_RECORD -> canonicalImportService.executeBusinessRecordImport(callContext, request);
                case FILE_SHARE -> canonicalImportService.execute(callContext, request);
            };
        } else if (isEml && !"file".equals(sourceObjectType)) {
            // No connector context + .eml extension + not explicitly typed as "file" → mail parser
            result = canonicalImportService.executeMailImport(callContext, request);
        } else if ("page".equals(sourceObjectType)) {
            result = canonicalImportService.executeNoteImport(callContext, request);
        } else if ("record".equals(sourceObjectType)) {
            result = canonicalImportService.executeBusinessRecordImport(callContext, request);
        } else if ("chat_message".equals(sourceObjectType) || "thread".equals(sourceObjectType)) {
            result = canonicalImportService.executeChatContextImport(callContext, request);
        } else if ("message".equals(sourceObjectType)) {
            // "message" could be mail or chat — check connector archetype to decide
            // Only route to mail parser if we can confirm MESSAGE_CONTEXT archetype
            result = resolveMessageImport(callContext, request);
        } else {
            result = canonicalImportService.execute(callContext, request);
        }
        if (result.isSuccess() || result.skipped() || result.dryRun()) {
            if (delegatedRequest) auditDelegatedAttempt(callContext, repositoryId, request, true, null);
            return ResponseEntity.ok(result);
        }
        // Map validation/config errors to appropriate HTTP status
        HttpStatus errorStatus = classifyErrorStatus(result);
        if (delegatedRequest) {
            auditDelegatedAttempt(callContext, repositoryId, request, false,
                    result.errors() != null && !result.errors().isEmpty() ? result.errors().get(0) : "ingest failed");
        }
        return ResponseEntity.status(errorStatus).body(result);
    }

    /**
     * Records a delegated ingest attempt regardless of outcome — gives the
     * security review trail for the new non-admin code path. Admin ingests
     * continue through the existing AOP audit and don't double-log here.
     */
    private void auditDelegatedAttempt(CallContext ctx, String repositoryId,
                                       ExternalIngestRequest request, boolean success, String errorMessage) {
        auditDelegatedAttempt(ctx, repositoryId, request, success, errorMessage, null);
    }

    private void auditDelegatedAttempt(CallContext ctx, String repositoryId,
                                       ExternalIngestRequest request, boolean success,
                                       String errorMessage, DenialReason denialReason) {
        if (ctx == null) return;
        String actor = ctx.getUsername() != null ? ctx.getUsername() : "anonymous";
        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("delegated", true);
        details.put("actorUserId", actor);
        if (request.getProfileId() != null) details.put("profileId", request.getProfileId());
        if (request.getConnectorId() != null) details.put("connectorId", request.getConnectorId());
        if (request.getTargetFolderOverride() != null) details.put("targetFolderOverrideAttempted", true);
        if (denialReason != null) details.put("denialReason", denialReason.name());
        jp.aegif.nemaki.audit.AuditOperation op = success
                ? jp.aegif.nemaki.audit.AuditOperation.EXTERNAL_INGEST
                : jp.aegif.nemaki.audit.AuditOperation.EXTERNAL_INGEST_FAILED;
        // H1 (RC5.5): silent catch replaced by safeEmit (WARN on failure)
        jp.aegif.nemaki.audit.AuditEmitSupport.safeEmit(auditLogger,
                op, repositoryId, actor,
                request.getSourceObjectId() != null ? request.getSourceObjectId() : "",
                success, errorMessage, details);
    }

    /**
     * Fallback dispatch for "message" sourceObjectType when connector archetype
     * was not resolved by the primary dispatch (connectorId absent or lookup failed).
     * Defaults to mail parser since "message" without archetype context is most
     * likely an email.
     */
    private ExternalIngestResult resolveMessageImport(CallContext callContext, ExternalIngestRequest request) {
        // connectorArchetype was already checked in doIngest() and returned null,
        // so no point re-looking up — default to mail
        return canonicalImportService.executeMailImport(callContext, request);
    }

    /** Look up the connector's archetype, returning null if unavailable. */
    private SourceArchetype resolveConnectorArchetype(String connectorId) {
        if (connectorId == null) return null;
        try {
            if (connectorDefinitionService != null) {
                ConnectorDefinition connector = connectorDefinitionService.get(connectorId);
                if (connector != null) return connector.getSourceArchetype();
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(ExternalIngestController.class)
                    .warn("Connector lookup failed for {}: {}", connectorId, e.getMessage());
        }
        return null;
    }

    private static HttpStatus classifyErrorStatus(ExternalIngestResult result) {
        if (result.errors() == null || result.errors().isEmpty()) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String firstError = result.errors().get(0);
        if (firstError == null) return HttpStatus.INTERNAL_SERVER_ERROR;
        firstError = firstError.toLowerCase();
        if (firstError.contains("not found")) return HttpStatus.NOT_FOUND;
        if (firstError.contains("not allowed") || firstError.contains("scoped to repository")) return HttpStatus.FORBIDDEN;
        if (firstError.contains("disabled") || firstError.contains("is required")
                || firstError.contains("no resolvable")) return HttpStatus.BAD_REQUEST;
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    /** Strip path separators, control characters, and parent-directory traversal from uploaded filenames. */
    static String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) return name;
        // Remove null bytes and control characters (prevent injection)
        name = name.replaceAll("[\\x00-\\x1f\\x7f]", "");
        // Extract basename (after last / or \)
        int lastSlash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        // Remove leading dots to prevent hidden files / traversal
        name = name.replaceAll("^\\.+", "");
        return name.isBlank() ? "imported-file" : name;
    }

    private CallContext getCallContext() {
        if (httpRequest == null) return null;
        return (CallContext) httpRequest.getAttribute("CallContext");
    }

    /**
     * Carries a refusal across the gate boundary. The {@link DenialReason}
     * is recorded in the audit details map so SOC tooling can search by
     * code rather than by free-form English. The wire body still uses the
     * existing {@link ExternalIngestResult#error} factory so external
     * clients see no schema change.
     */
    private record Denial(HttpStatus status, DenialReason reason, String requestId, String message) {
        ResponseEntity<ExternalIngestResult> toResponse() {
            return ResponseEntity.status(status).body(ExternalIngestResult.error(requestId, message));
        }
    }

    /**
     * Runs the non-admin runtime gate. Returns {@code null} when the caller
     * is allowed; otherwise returns a {@link Denial}. Every failure mode is
     * logged but the client message stays terse — we don't want to leak
     * which step failed (folder absent vs. not delegated vs. wrong
     * connector).
     */
    private Denial enforceDelegatedExecution(
            CallContext callContext, String repositoryId, ExternalIngestRequest request) {

        // (4) Override forbidden in v1 — keep the gate explicit even if a
        // future release re-enables it under separate ACL evaluation.
        if (request.getTargetFolderOverride() != null && !request.getTargetFolderOverride().isBlank()) {
            return new Denial(HttpStatus.FORBIDDEN, DenialReason.TARGET_FOLDER_OVERRIDE_FORBIDDEN,
                    "unknown", "targetFolderOverride is not permitted for non-admin callers");
        }
        // Defence in depth — these services are @Autowired (required) so
        // the bean factory has already failed if they were missing. The
        // null check exists in case some misconfigured custom context
        // strips them; missing means deny.
        if (importProfileDefinitionService == null || connectorDefinitionService == null) {
            return new Denial(HttpStatus.SERVICE_UNAVAILABLE, DenialReason.SERVICES_UNAVAILABLE,
                    "unknown", "Ingest services unavailable; non-admin ingest disabled");
        }
        // (1) profileId is mandatory for non-admin — admin auto-resolution
        // would happily pick a profile the caller can't manage.
        String profileId = request.getProfileId();
        if (profileId == null || profileId.isBlank()) {
            return new Denial(HttpStatus.FORBIDDEN, DenialReason.PROFILE_ID_REQUIRED,
                    "unknown", "profileId is required for non-admin ingestion");
        }
        ImportProfileDefinition profile = importProfileDefinitionService.get(profileId);
        if (profile == null) {
            return new Denial(HttpStatus.NOT_FOUND, DenialReason.PROFILE_NOT_FOUND,
                    "unknown", "Profile not found");
        }
        if (!profile.isDelegated()) {
            return new Denial(HttpStatus.FORBIDDEN, DenialReason.ADMIN_OWNED_PROFILE,
                    "unknown", "Admin-managed profile");
        }
        // Repo must match — non-admins cannot route a profile across repos.
        if (profile.getRepositoryId() != null && !profile.getRepositoryId().equals(repositoryId)) {
            return new Denial(HttpStatus.FORBIDDEN, DenialReason.PROFILE_REPO_MISMATCH,
                    "unknown", "Profile is not bound to this repository");
        }

        // (2) Runtime fail-closed for the profile shape itself. The create
        // / update controller refuses delegated profiles with empty
        // allowedConnectorIds — but a legacy record, manual CouchDB write,
        // or a future migration bug could leave one in that state. Empty
        // here would mean "any connector allowed" via {@link
        // ImportProfileDefinition#isConnectorAllowed}, which is exactly
        // the credential-indirect-delegation hole we are guarding against.
        // So we close it explicitly in the runtime gate too.
        if (profile.getAllowedConnectorIds() == null || profile.getAllowedConnectorIds().isEmpty()) {
            return new Denial(HttpStatus.FORBIDDEN, DenialReason.EMPTY_ALLOWED_CONNECTORS,
                    "unknown",
                    "Delegated profile has no allowedConnectorIds; refusing to fall back to 'any connector'");
        }

        // (3) Re-evaluate cmis:all at execution time — guards against ACL
        // changes between profile-create and execute.
        String folderId = ingestAuthorizationService.resolveFolderId(
                repositoryId, profile.getTargetFolderId(), profile.getTargetFolderPath());
        if (folderId == null) {
            return new Denial(HttpStatus.NOT_FOUND, DenialReason.TARGET_FOLDER_UNRESOLVABLE,
                    "unknown", "Target folder no longer resolvable");
        }
        if (!ingestAuthorizationService.canManageProfileForFolder(callContext, repositoryId, folderId)) {
            return new Denial(HttpStatus.FORBIDDEN, DenialReason.CMIS_ALL_REQUIRED,
                    "unknown", "cmis:all on target folder required");
        }

        // (4) connectorId, if provided, must be in the profile's saved
        // allowedConnectorIds — and that connector must still be delegated
        // for this user/folder. Empty connectorId triggers a strict
        // fallback to the profile's defaultConnectorId below; it is NOT
        // a free pass to skip the connector gate entirely.
        String connectorId = request.getConnectorId();
        if (connectorId != null && !connectorId.isBlank()) {
            if (!profile.isConnectorAllowed(connectorId)) {
                return new Denial(HttpStatus.FORBIDDEN, DenialReason.CONNECTOR_NOT_IN_PROFILE,
                        connectorId, "Connector not in profile's allowedConnectorIds");
            }
            ConnectorDefinition connector = connectorDefinitionService.get(connectorId);
            if (connector == null) {
                return new Denial(HttpStatus.NOT_FOUND, DenialReason.UNKNOWN_CONNECTOR,
                        connectorId, "Connector not found");
            }
            if (!ingestAuthorizationService.canUseConnectorForDelegatedProfile(
                    callContext, repositoryId, connector, folderId)) {
                return new Denial(HttpStatus.FORBIDDEN, DenialReason.CONNECTOR_NOT_DELEGATED,
                        connectorId, "Connector no longer delegated for this folder/user");
            }
        } else {
            // No explicit connector — non-admin must have a profile-provided
            // default that is itself valid. Every check the explicit path
            // does, the default path must do too: blank guard, membership
            // in allowedConnectorIds, existence, and live delegation.
            // After approval we *stamp the default onto the request* so
            // downstream dispatch (resolveConnectorArchetype) sees a real
            // connectorId and doesn't fall back to filename heuristics
            // that could pick a wrong import flow.
            String def = profile.getDefaultConnectorId();
            if (def == null || def.isBlank()) {
                return new Denial(HttpStatus.FORBIDDEN, DenialReason.PROFILE_ID_REQUIRED,
                        "unknown",
                        "No connectorId supplied and profile has no defaultConnectorId; "
                                + "non-admin ingest must resolve a connector deterministically");
            }
            if (!profile.isConnectorAllowed(def)) {
                // The profile's own default falls outside its allowed list —
                // a corrupt record. Same gate as for an explicit-but-wrong
                // connectorId.
                return new Denial(HttpStatus.FORBIDDEN, DenialReason.DEFAULT_CONNECTOR_NOT_IN_ALLOWED,
                        def, "Profile's defaultConnectorId is not in allowedConnectorIds");
            }
            ConnectorDefinition connector = connectorDefinitionService.get(def);
            if (connector == null) {
                return new Denial(HttpStatus.NOT_FOUND, DenialReason.UNKNOWN_CONNECTOR,
                        def, "Profile's default connector not found");
            }
            if (!ingestAuthorizationService.canUseConnectorForDelegatedProfile(
                    callContext, repositoryId, connector, folderId)) {
                return new Denial(HttpStatus.FORBIDDEN, DenialReason.DEFAULT_CONNECTOR_NOT_DELEGATED,
                        def, "Profile's default connector no longer delegated");
            }
            // Stamp the validated default onto the request so the dispatch
            // path that follows sees an explicit connectorId.
            request.setConnectorId(def);
        }
        return null;
    }
}
