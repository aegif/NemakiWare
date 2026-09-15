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
        } catch (ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException
                | ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException
                | ConnectorArchetypeUnusableException refused) {
            // NOT "Invalid request". This arm exists for a malformed multipart body, and it
            // was swallowing the typed "this row could not be read" refusals raised deep
            // inside doIngest — so the same ingest answered 503 as JSON and 400 as multipart,
            // the 400 asserting something about the caller's request that no read
            // established. Two reviews found it in the same round. Rethrown for the handler
            // below, which answers 503 for both shapes.
            throw refused;
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
            Denial denial;
            try {
                denial = enforceDelegatedExecution(callContext, repositoryId, request);
            } catch (RuntimeException refused) {
                // The gate READS the connector too, and get() rethrows when the deterministic
                // id holds another document — so a delegated attempt refused inside the gate
                // left no audit entry at all. The previous round audited the dispatch's
                // refusals and then wrote, in this class's javadoc and in the release notes,
                // that every outcome is recorded. It was not: a review found the gate's two
                // reads outside the catch. Audited here, then rethrown for the handler.
                auditDelegatedAttempt(callContext, repositoryId, request, false,
                        refused.getMessage(), DenialReason.SERVICES_UNAVAILABLE);
                throw refused;
            }
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
        SourceArchetype connectorArchetype;
        try {
            connectorArchetype = resolveConnectorArchetype(request.getConnectorId());
        } catch (RuntimeException refused) {
            // The refusals raised in there leave this method by exception, and a delegated
            // attempt that leaves by exception used to leave no audit entry at all — while
            // the same input was audited before those refusals existed. An earlier note here
            // said moving the audit would mean touching the authorisation gate; a review
            // showed the call site knows everything it needs. Audited, then rethrown for the
            // handler that decides the status.
            if (delegatedRequest) {
                auditDelegatedAttempt(callContext, repositoryId, request, false,
                        refused.getMessage(), DenialReason.SERVICES_UNAVAILABLE);
            }
            throw refused;
        }

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
     * Records a delegated ingest attempt for every outcome this method is REACHED for —
     * gives the security review trail for the non-admin code path. Admin ingests continue
     * through the existing AOP audit and don't double-log here.
     *
     * <p>That includes the outcomes that leave by EXCEPTION — a connector that could not be
     * read, or whose row does not say which flow the request belongs to. Those refusals once
     * left no audit entry, while the same input had been audited before they existed. The
     * first note about it said "regardless of outcome" and did not mention the gap; the
     * second recorded the gap as not worth closing, on the ground that the refusals are
     * raised below the point that knows the delegated context. A review showed that ground
     * was wrong: the dispatch call site holds both, so it is closed there.
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
     * Fallback dispatch for "message" sourceObjectType when no connector archetype was
     * resolved. That means the caller named NO connector, or the walk ESTABLISHED that the
     * one it named does not exist. Everything else refuses before reaching here: a lookup
     * that failed, a row the index could not show, a row without an archetype, and an unwired
     * connector service. An earlier version of this note listed "lookup failed" among the
     * reasons, and the version after it still let the unwired case through while claiming
     * otherwise; two reviews found the two. Defaults to the mail parser, since "message" with no connector context
     * is most likely an email.
     */
    private ExternalIngestResult resolveMessageImport(CallContext callContext, ExternalIngestRequest request) {
        return canonicalImportService.executeMailImport(callContext, request);
    }

    /** A connector row that was READ and does not say which import flow it belongs to. */
    public static class ConnectorArchetypeUnusableException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public ConnectorArchetypeUnusableException(String message) { super(message); }
    }

    /**
     * The named connector's archetype. There are TWO {@code return null} statements below,
     * and they are the whole of it: the caller named no connector, and a connector the
     * index-free walk ESTABLISHES is absent. Everything else that could once produce a null
     * here now refuses — a read that failed, a row the index cannot show, a row that carries
     * no archetype, and this service being unwired. Each was found by a review AFTER the
     * previous one was closed, three rounds running, because the dispatch above reads null as
     * "no connector context" and picks the flow from the file name.
     *
     * <p>Two earlier versions of this paragraph opened in universal terms ("null ONLY when…",
     * then "Null means ONE thing…") while a contradicting return was open or, the second
     * time, while the exception was named six lines below. Both were found by review. Count
     * the returns before rewriting this.
     *
     * <p>It used to answer null for a failed lookup too, and null is what the dispatch above
     * reads as "no connector context" — so a transient read failure sent the request into the
     * filename / sourceObjectType heuristics and COMMITTED it through a different import flow
     * ({@code sourceObjectType=message} on a CHAT_CONTEXT connector is parsed as mail). The
     * chosen flow does not re-check the archetype, so the wrong shape is what gets stored. A
     * review found it; it is the batch's rule applied to a dispatch rather than to a status
     * code — an explicitly named connector whose archetype cannot be ESTABLISHED must not be
     * replaced by a guess from the file name.
     *
     * <p>Absence still behaves as it always has: a connector the walk says is not there
     * answers null and the heuristics run, so this does not turn an unknown connectorId into
     * a new refusal. The walk costs one pass of the config database, and only on the path
     * where the ordinary read already failed to produce a connector.
     */
    private SourceArchetype resolveConnectorArchetype(String connectorId) {
        if (connectorId == null) return null;
        if (connectorDefinitionService == null) {
            // The third arm of the same hole, found by two reviewers independently after the
            // first two were closed. An unwired service answered null, and null is what the
            // dispatch reads as "no connector context" — so the flow came from the file name
            // again. Latent (Spring wires this or fails to start), but the class already
            // refuses on exactly this shape for the non-admin path and for the authorization
            // service; only the admin dispatch degraded silently.
            throw new ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException(
                    "the connector service is not wired on this node, so which import flow"
                            + " this request belongs to cannot be established; retry shortly"
                            + " against a node that runs it");
        }
        try {
            ConnectorDefinition connector = connectorDefinitionService.get(connectorId);
            if (connector != null) {
                if (connector.getSourceArchetype() == null) {
                    // The row READ, and it does not say what it is. Returning null here was
                    // the same hole through its other arm: the dispatch reads null as "no
                    // connector context" and picks the flow from the file name. The API's
                    // create and update reject a null archetype, so this is a row written
                    // before that check, by hand, or by a half-run migration — the DLQ
                    // replay refuses exactly this input with the same reasoning. Not a
                    // retry: no read makes the field appear.
                    throw new ConnectorArchetypeUnusableException("connector " + connectorId
                            + " has no sourceArchetype, so which import flow this request"
                            + " belongs to cannot be established; set the connector's"
                            + " sourceArchetype and submit again");
                }
                return connector.getSourceArchetype();
            }
        } catch (ConnectorArchetypeUnusableException unusable) {
            throw unusable;
        } catch (ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException refused) {
            throw refused;
        } catch (RuntimeException lookupFailed) {
            org.slf4j.LoggerFactory.getLogger(ExternalIngestController.class)
                    .warn("Connector lookup failed for {}: {}", connectorId,
                            lookupFailed.getMessage());
            throw new ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException(
                    "connector " + connectorId + " could not be read, so which import flow this"
                            + " request belongs to cannot be established; retry shortly: "
                            + lookupFailed.getMessage());
        }
        // null from get() is "absent" OR "the selector answered nothing while its index
        // rebuilds". Only the index-free walk tells them apart, and only the second may not
        // fall through to the heuristics.
        if (connectorDefinitionService.existsIndexFree(connectorId)) {
            throw new ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException(
                    "connector " + connectorId + " exists but could not be read, so which"
                            + " import flow this request belongs to cannot be established;"
                            + " retry shortly");
        }
        return null;
    }

    // Package-private, not private: the locks that measure this join two files. They used to
    // assert only that the import's message still CARRIED the token this method keys on,
    // which stays green when an arm here is deleted. They now run the real message through
    // the real classifier.
    static HttpStatus classifyErrorStatus(ExternalIngestResult result) {
        if (result.errors() == null || result.errors().isEmpty()) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String firstError = result.errors().get(0);
        if (firstError == null) return HttpStatus.INTERNAL_SERVER_ERROR;
        firstError = firstError.toLowerCase();
        // The retryable arms come FIRST, and before "not found": these messages name a read
        // that could not be answered, and one of them ends in "...could not be read ...;
        // retry shortly" — which contains "not found" nowhere but did land on the 500
        // fallback. Every refusal this batch added to the import path was answering 500, the
        // status the admin controller's own comment calls "what opens tickets for a condition
        // a retry resolves". The same twin pair was 409 through the non-admin gate and 500
        // here. A review measured the split.
        // The THIRD checkpoint, and it has to be asked FIRST. The gate refuses at the door,
        // the write point re-asks, and the CMIS ACL evaluation inside the write refuses last —
        // CmisPermissionDeniedException, wrapped as "[permanent] Permission Denied!
        // repositoryId=... content={id:..., name:...}". Only that last one answered 500.
        //
        // Above the retryable block because isTransientError() tests "503" against the RAW
        // message before it tests "403"/"Forbidden", and that message interpolates the
        // repository id, the object id and the object NAME — so a folder called "err-503"
        // made a denial come back marked "[transient]" and answer 503. A denial is never a
        // retry. The token carries the bang and is matched against the product's own format,
        // not the bare words, so an id or name containing "permission denied" does not land
        // here. A review found both halves.
        // ExceptionServiceImpl builds TWO denial formats and both have to be here; matching
        // only the first left every top-level-folder denial on 500, which is the one a
        // delegated profile targeting the repository root produces on EVERY import.
        if (firstError.contains("permission denied! repositoryid=")
                || firstError.contains("permission denied for the importing user")
                || firstError.contains("permission denied to top level folders")) {
            return HttpStatus.FORBIDDEN;
        }
        if (firstError.contains("retry shortly") || firstError.contains("temporarily unavailable")
                // The import's own re-check of the delegation cannot ASK when the
                // authorization service is not wired. The gate one frame up answers 503 for
                // the same state (SERVICES_UNAVAILABLE); this door answered 500. The sibling
                // message "no caller to authorise" is deliberately not listed: doIngest
                // answers 401 before dispatching without a CallContext, so that refusal
                // cannot arrive here, and if it ever did the 500 would be the true answer.
                || firstError.contains("the authorization service is not available")
                // The import's own verdict, not a guess about the words: execute() prefixes
                // "[transient] " only after isTransientError() has classified the cause
                // (socket timeout, conflict, 429/502/503/504, rate limit — with 401/403/404
                // explicitly excluded). That verdict was then thrown away here and the same
                // condition answered 500, while the identical store failure seen one frame
                // earlier ("...; retry shortly") answered 503. This arm is above "not found"
                // on purpose: a transient failure whose text happens to carry a foreign
                // "not found" is still a retry, not an absence.
                || firstError.contains("[transient] ")) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        if (firstError.contains("definition rows")
                || firstError.contains("more than one definition row")
                || firstError.contains("more than one owned definition row")) {
            // A standing pair an administrator has to resolve — not a retry, not a 500.
            // getForRepository says "more than one definition row" (singular); the update
            // path says "definition rows". Matching only the plural left the import door
            // at 500. A review measured the split.
            return HttpStatus.CONFLICT;
        }
        if (firstError.contains("not found")) return HttpStatus.NOT_FOUND;
        if (firstError.contains("not allowed") || firstError.contains("scoped to repository")
                || firstError.contains("repository mismatch")
                // The three authorisation refusals the import raises when it re-asks the
                // delegation at the write point. The gate one frame up answers 403 for each
                // of the same states — PROFILE_REPO_MISMATCH, CMIS_ALL_REQUIRED and
                // CONNECTOR_NOT_DELEGATED — while this door answered 500 for two of them and
                // 400 for the third, by the accident of its wording containing "is required".
                // A denial read as a server fault, and a denial read as the caller's bad
                // request. This arm must stay ABOVE the "is required" arm below for that
                // third one. A review found the split.
                || firstError.contains("not the repository this caller authenticated")
                || firstError.contains("was not held when this import ran")
                || firstError.contains("no longer delegated")) return HttpStatus.FORBIDDEN;
        if (firstError.contains("disabled") || firstError.contains("is required")
                || firstError.contains("no resolvable")
                // A standing profile misconfiguration the read ANSWERED. Making the refusal
                // permanent (dropping "; retry shortly") took it off the 503 arm and dropped
                // it onto the 500 fallback below — worse than the 400 the vaguer message
                // "no resolvable target folder" had always produced. Two reviewers measured
                // it in the round that made the suffix conditional.
                || firstError.contains("fix the profile")
                // Two caller mistakes that answered 500. The same door already answers 400
                // for the sibling mistake one layer up ("File exceeds maximum size (100MB)"),
                // so one endpoint gave two different answers to "your request was too big".
                || firstError.contains("exceeds max size")
                || firstError.contains("invalid metadata format")) return HttpStatus.BAD_REQUEST;
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
     * A retryable refusal when an index-free read says the definition exists (or cannot say),
     * and null when it really is absent. "Not found" is a claim about the database; every
     * read that can answer it here is index-backed, so it has to be checked without the index
     * before it is made.
     */
    private Denial profileHiddenOrAbsent(String profileId, String repositoryId) {
        try {
            if (importProfileDefinitionService.existsIndexFree(profileId, repositoryId)) {
                return new Denial(HttpStatus.SERVICE_UNAVAILABLE, DenialReason.SERVICES_UNAVAILABLE,
                        "unknown", "import profile " + profileId + " exists but could not be"
                                + " read; retry shortly");
            }
        } catch (ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException e) {
            return new Denial(HttpStatus.SERVICE_UNAVAILABLE, DenialReason.SERVICES_UNAVAILABLE,
                    "unknown", "whether import profile " + profileId + " exists could not be"
                            + " established; retry shortly");
        }
        return null;
    }

    /** The connector twin of {@link #profileHiddenOrAbsent}. */
    private Denial connectorHiddenOrAbsent(String connectorId) {
        try {
            if (connectorDefinitionService.existsIndexFree(connectorId)) {
                return new Denial(HttpStatus.SERVICE_UNAVAILABLE, DenialReason.SERVICES_UNAVAILABLE,
                        connectorId, "connector " + connectorId + " exists but could not be"
                                + " read; retry shortly");
            }
        } catch (ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException e) {
            return new Denial(HttpStatus.SERVICE_UNAVAILABLE, DenialReason.SERVICES_UNAVAILABLE,
                    connectorId, "whether connector " + connectorId + " exists could not be"
                            + " established; retry shortly");
        }
        return null;
    }

    /**
     * Carries a refusal across the gate boundary. The {@link DenialReason} is the stable tag
     * the audit trail and the UI key on; the message is for a human.
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
        // The row this gate AUTHORISES has to be the row the import then uses. Both sides
        // resolve the profile independently, and the selector answers on profileId alone —
        // so with two rows of one profileId in ONE repository the gate could authorise the
        // folder and connector of row A while the import ran with row B's target. The walk
        // is authoritative here and refuses that pair outright; the selector is left to do
        // nothing but LABEL an absence (elsewhere → 403, nowhere → 404/503 below). A review
        // showed that "only DELETE crosses an authorisation boundary" was too narrow.
        ImportProfileDefinition profile;
        try {
            profile = importProfileDefinitionService.getForRepository(profileId, repositoryId);
        } catch (ImportProfileDefinitionServiceImpl.ProfileHasTwinRowsException pair) {
            return new Denial(HttpStatus.CONFLICT, DenialReason.SERVICES_UNAVAILABLE,
                    "unknown", pair.getMessage());
        } catch (ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException e) {
            return new Denial(HttpStatus.SERVICE_UNAVAILABLE, DenialReason.SERVICES_UNAVAILABLE,
                    "unknown", "import profile " + profileId + " could not be resolved for"
                            + " this repository; retry shortly");
        }
        if (profile == null) {
            ImportProfileDefinition selected = importProfileDefinitionService.get(profileId);
            if (selected != null && repositoryId.equals(selected.getRepositoryId())) {
                // The walk says this repository has no such row and the selector says it
                // does. Authorising the selector's row would undo the paragraph above, so
                // the disagreement is reported as one.
                return new Denial(HttpStatus.SERVICE_UNAVAILABLE, DenialReason.SERVICES_UNAVAILABLE,
                        "unknown", "import profile " + profileId + " is reported by the index"
                                + " but not by the stored rows of this repository; retry shortly");
            }
            profile = selected;
        }
        if (profile == null) {
            // This gate runs BEFORE the import service, so its 404 is the answer the caller
            // gets — the split inside the service never reaches them. A rebuilding index
            // makes "not found" a claim about the index, not the database. A review found
            // this entry point still reporting failure as absence.
            Denial hidden = profileHiddenOrAbsent(profileId, repositoryId);
            if (hidden != null) return hidden;
            return new Denial(HttpStatus.NOT_FOUND, DenialReason.PROFILE_NOT_FOUND,
                    "unknown", "Profile not found");
        }
        if (!profile.isDelegated()) {
            return new Denial(HttpStatus.FORBIDDEN, DenialReason.ADMIN_OWNED_PROFILE,
                    "unknown", "Admin-managed profile");
        }
        // Repo must match — non-admins cannot route a profile across repos.
        // A row that names NO repository is not a wildcard. The guard used to pass it
        // through (null fails the != null test), so a corrupt or half-migrated row acted
        // as a profile for EVERY repository — invisible to the admin API, which is
        // repository-confined, while the runtime happily used it as configuration. The
        // service that lets an administrator delete such a row says plainly that it
        // "belongs to none"; this is the other half of that sentence. A review found the
        // two disagreeing.
        if (profile.getRepositoryId() == null
                || !profile.getRepositoryId().equals(repositoryId)) {
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
        // Stamp the row this gate is authorising. The import resolves the profile again, and
        // a PUT landing in between moves the target folder — by someone who need not be this
        // caller, so nothing about that update authorises this caller for the new folder. The
        // import refuses when the row it resolves is not this one.
        request.setAuthorizedProfileFingerprint(
                CanonicalImportServiceImpl.authorizationFingerprint(profile));
        // And the folder itself, not just the row: cmis:all was checked on THIS object. A
        // path-only profile re-resolves at import time, so moving the authorised folder away
        // and putting another at the same path changes nothing the row can see.
        request.setAuthorizedTargetFolderId(folderId);

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
                Denial hidden = connectorHiddenOrAbsent(connectorId);
                if (hidden != null) return hidden;
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
                Denial hidden = connectorHiddenOrAbsent(def);
                if (hidden != null) return hidden;
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

    /**
     * The typed "this row could not be read" refusals reach the ingest endpoints from the
     * connector and profile services — {@code get()} rethrows them for a deterministic id
     * holding another document — with nothing catching them, and Spring answers 500: "our
     * bug" for a condition whose whole point is that a retry fixes it. The definition APIs
     * have had this floor since the batch began; a review found the ingest, DLQ and webhook
     * controllers without it. Endpoints that map these themselves keep their own mapping.
     */
    @ExceptionHandler({ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class,
            ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException.class})
    public ResponseEntity<ExternalIngestResult> definitionRowsCouldNotBeRead(RuntimeException e) {
        // The endpoint's own document, not a different one. The first version answered a
        // bare map, so a caller parsing requestId / success / errors got a shape it does not
        // know from the one path that refuses; a review found the undescribed change.
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ExternalIngestResult.error("unknown", String.valueOf(e.getMessage())));
    }

    /** A connector whose stored row cannot say which flow the request belongs to. */
    @ExceptionHandler(ConnectorArchetypeUnusableException.class)
    public ResponseEntity<ExternalIngestResult> connectorCannotSayWhatItIs(
            ConnectorArchetypeUnusableException e) {
        // 409, not 503: no retry makes the field appear, and not 400 either — the request is
        // well formed and names a connector that exists. The operator has to fix the row.
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ExternalIngestResult.error("unknown", String.valueOf(e.getMessage())));
    }
}
