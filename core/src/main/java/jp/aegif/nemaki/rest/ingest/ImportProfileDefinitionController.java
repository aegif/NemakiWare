package jp.aegif.nemaki.rest.ingest;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.audit.AuditLogger;
import jp.aegif.nemaki.audit.AuditOperation;
import jp.aegif.nemaki.util.constant.CallContextKey;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/admin/import-profiles")
public class ImportProfileDefinitionController {

    @Autowired
    private ImportProfileDefinitionService importProfileDefinitionService;

    @Autowired(required = false)
    private IngestSchedulerService ingestSchedulerService;

    @Autowired
    private ConnectorDefinitionService connectorDefinitionService;

    @Autowired
    private IngestAuthorizationService ingestAuthorizationService;

    @Autowired(required = false)
    private AuditLogger auditLogger;

    @Autowired
    private HttpServletRequest httpRequest;

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody ImportProfileDefinition def) {
        CallContext ctx = currentCallContext();
        if (ctx == null) return errorResponse(HttpStatus.UNAUTHORIZED, "No call context");
        boolean admin = ingestAuthorizationService.isAdmin(ctx);

        try {
            if (!admin) {
                ResponseEntity<Map<String, Object>> deniedResp = enforceDelegationOnCreate(ctx, def);
                if (deniedResp != null) {
                    // Audit denied attempts too — security review trail needs
                    // "who tried to do what" not just successes.
                    String reason = extractDenialReason(deniedResp);
                    String message = extractMessage(deniedResp);
                    auditDenial(AuditOperation.EXTERNAL_PROFILE_CREATED, ctx, def,
                            reason != null ? DenialReason.valueOf(reason) : null, message);
                    return deniedResp;
                }
            }
            ImportProfileDefinition created = importProfileDefinitionService.create(def);
            audit(AuditOperation.EXTERNAL_PROFILE_CREATED, ctx, def, true, null);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("profileId", created.getProfileId());
            List<String> warnings = getPhase2Warnings(def);
            if (!warnings.isEmpty()) response.put("warnings", warnings);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            audit(AuditOperation.EXTERNAL_PROFILE_CREATED, ctx, def, false, e.getMessage());
            return errorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping
    public ResponseEntity<List<ImportProfileDefinition>> list(
            @RequestParam(required = false) String repositoryId) {
        CallContext ctx = currentCallContext();
        if (ctx == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        boolean admin = ingestAuthorizationService.isAdmin(ctx);
        List<ImportProfileDefinition> all = (repositoryId != null && !repositoryId.isBlank())
                ? importProfileDefinitionService.listByRepository(repositoryId)
                : importProfileDefinitionService.list();
        if (admin) return ResponseEntity.ok(all);
        // Non-admin: profile-by-profile permission filter (no full folder-tree scan).
        // canManageProfileForFolder does getFolder + calculateAcl + group expansion
        // per call. Many profiles in a real deployment share a small set of target
        // folders (one folder owner manages multiple profiles for the same root).
        // A per-(repo, folder) memo within this single request collapses N profiles
        // on K folders from N ACL evaluations to K — typically 5-20× cheaper at
        // realistic scale, with zero correctness risk (single-request scope, no
        // staleness window). The map is bounded by the response size.
        // Tuple-keyed memo: a record makes equality/hash exact and avoids
        // separator-based string concatenation entirely (an earlier version
        // wrote a NUL byte separator into the source file by accident).
        record CacheKey(String repositoryId, String folderId) {}
        java.util.Map<CacheKey, Boolean> manageCache = new java.util.HashMap<>();
        List<ImportProfileDefinition> visible = new ArrayList<>();
        for (ImportProfileDefinition p : all) {
            if (!p.isDelegated()) continue;
            String folderId = ingestAuthorizationService.resolveFolderId(
                    p.getRepositoryId(), p.getTargetFolderId(), p.getTargetFolderPath());
            if (folderId == null) continue;
            CacheKey cacheKey = new CacheKey(p.getRepositoryId(), folderId);
            Boolean cached = manageCache.computeIfAbsent(cacheKey,
                    k -> ingestAuthorizationService.canManageProfileForFolder(ctx, p.getRepositoryId(), folderId));
            if (Boolean.TRUE.equals(cached)) {
                visible.add(p);
            }
        }
        return ResponseEntity.ok(visible);
    }

    @GetMapping("/{profileId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String profileId) {
        CallContext ctx = currentCallContext();
        if (ctx == null) return errorResponse(HttpStatus.UNAUTHORIZED, "No call context");
        ImportProfileDefinition def = importProfileDefinitionService.get(profileId);
        if (def == null) return errorResponse(HttpStatus.NOT_FOUND, "Profile not found");

        boolean admin = ingestAuthorizationService.isAdmin(ctx);
        if (!admin) {
            if (!def.isDelegated()) {
                return denied(HttpStatus.FORBIDDEN, DenialReason.ADMIN_OWNED_PROFILE, "Admin-managed profile");
            }
            String folderId = ingestAuthorizationService.resolveFolderId(
                    def.getRepositoryId(), def.getTargetFolderId(), def.getTargetFolderPath());
            if (folderId == null
                    || !ingestAuthorizationService.canManageProfileForFolder(ctx, def.getRepositoryId(), folderId)) {
                return denied(HttpStatus.FORBIDDEN, DenialReason.CMIS_ALL_REQUIRED,
                        "cmis:all on target folder required");
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("profile", def);
        List<String> warnings = getPhase2Warnings(def);
        if (!warnings.isEmpty()) response.put("warnings", warnings);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{profileId}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable String profileId, @RequestBody ImportProfileDefinition def) {
        CallContext ctx = currentCallContext();
        if (ctx == null) return errorResponse(HttpStatus.UNAUTHORIZED, "No call context");
        def.setProfileId(profileId);

        boolean admin = ingestAuthorizationService.isAdmin(ctx);
        try {
            if (!admin) {
                ResponseEntity<Map<String, Object>> deniedResp = enforceDelegationOnUpdate(ctx, def);
                if (deniedResp != null) {
                    String reason = extractDenialReason(deniedResp);
                    String message = extractMessage(deniedResp);
                    auditDenial(AuditOperation.EXTERNAL_PROFILE_UPDATED, ctx, def,
                            reason != null ? DenialReason.valueOf(reason) : null, message);
                    return deniedResp;
                }
            }
            importProfileDefinitionService.update(def);
            // If profile was disabled and IDLE is running, stop the IDLE thread
            if (!def.isEnabled() && ingestSchedulerService != null) {
                ingestSchedulerService.stopIdle(profileId);
            }
            audit(AuditOperation.EXTERNAL_PROFILE_UPDATED, ctx, def, true, null);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            List<String> warnings = getPhase2Warnings(def);
            if (!warnings.isEmpty()) response.put("warnings", warnings);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            audit(AuditOperation.EXTERNAL_PROFILE_UPDATED, ctx, def, false, e.getMessage());
            return errorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @DeleteMapping("/{profileId}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String profileId) {
        CallContext ctx = currentCallContext();
        if (ctx == null) return errorResponse(HttpStatus.UNAUTHORIZED, "No call context");

        boolean admin = ingestAuthorizationService.isAdmin(ctx);
        ImportProfileDefinition existing = importProfileDefinitionService.get(profileId);
        if (existing == null) return errorResponse(HttpStatus.NOT_FOUND, "Profile not found");
        if (!admin) {
            if (!existing.isDelegated()) {
                ResponseEntity<Map<String, Object>> resp = denied(HttpStatus.FORBIDDEN,
                        DenialReason.ADMIN_OWNED_PROFILE, "Admin-managed profile");
                auditDenial(AuditOperation.EXTERNAL_PROFILE_DELETED, ctx, existing,
                        DenialReason.ADMIN_OWNED_PROFILE, "Admin-managed profile");
                return resp;
            }
            String folderId = ingestAuthorizationService.resolveFolderId(
                    existing.getRepositoryId(), existing.getTargetFolderId(), existing.getTargetFolderPath());
            if (folderId == null
                    || !ingestAuthorizationService.canManageProfileForFolder(ctx, existing.getRepositoryId(), folderId)) {
                ResponseEntity<Map<String, Object>> resp = denied(HttpStatus.FORBIDDEN,
                        DenialReason.CMIS_ALL_REQUIRED, "cmis:all on target folder required");
                auditDenial(AuditOperation.EXTERNAL_PROFILE_DELETED, ctx, existing,
                        DenialReason.CMIS_ALL_REQUIRED, "cmis:all on target folder required");
                return resp;
            }
        }
        // Stop IDLE thread before deletion (no-op if not running)
        if (ingestSchedulerService != null) ingestSchedulerService.stopIdle(profileId);
        importProfileDefinitionService.delete(profileId);
        audit(AuditOperation.EXTERNAL_PROFILE_DELETED, ctx, existing, true, null);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        return ResponseEntity.ok(response);
    }

    /**
     * Admin-only ownership transfer between admin and delegated modes.
     * This is the missing piece for "I created this profile as admin and
     * now want to hand it to a folder owner" — previously the operator
     * had to delete and re-create. Body shape:
     *
     * <pre>{@code
     * { "mode": "delegated", "createdByUserId": "alice" }   // admin → delegated
     * { "mode": "admin" }                                    // delegated → admin
     * }</pre>
     *
     * <p>Round-trip semantics:
     * <ul>
     *   <li>{@code mode=delegated}: forces {@code delegated=true},
     *       stamps {@code createdByUserId} (defaults to caller if
     *       omitted), forces {@code schedulerEnabled=false} and
     *       {@code defaultProfile=false}, and re-validates that
     *       {@code allowedConnectorIds} is non-empty and that every
     *       listed connector is delegated to the new owner for the
     *       profile's target folder. Refuses if not — surfacing the
     *       same {@link DenialReason} codes as the normal delegated
     *       PUT path, so audit consumers see a uniform shape.</li>
     *   <li>{@code mode=admin}: forces {@code delegated=false} and
     *       leaves the other fields alone. Subsequent admin PUTs can
     *       re-enable scheduler etc.</li>
     * </ul>
     *
     * <p>Always emits an {@link AuditOperation#EXTERNAL_PROFILE_UPDATED}
     * audit with {@code details.transferTo} = mode so the audit trail
     * shows the ownership change explicitly.
     */
    @PostMapping("/{profileId}/ownership")
    public ResponseEntity<Map<String, Object>> transferOwnership(
            @PathVariable String profileId,
            @RequestBody Map<String, Object> body) {
        CallContext ctx = currentCallContext();
        if (ctx == null) return errorResponse(HttpStatus.UNAUTHORIZED, "No call context");
        if (!ingestAuthorizationService.isAdmin(ctx)) {
            return errorResponse(HttpStatus.FORBIDDEN, "Ownership transfer is admin-only");
        }

        ImportProfileDefinition existing = importProfileDefinitionService.get(profileId);
        if (existing == null) return errorResponse(HttpStatus.NOT_FOUND, "Profile not found");

        String mode = body.get("mode") instanceof String s ? s : null;
        if (!"delegated".equals(mode) && !"admin".equals(mode)) {
            return errorResponse(HttpStatus.BAD_REQUEST,
                    "Body must include mode: 'delegated' or 'admin'");
        }

        // We mutate a working copy so a validation failure leaves the
        // stored record untouched.
        try {
            if ("admin".equals(mode)) {
                existing.setDelegated(false);
                importProfileDefinitionService.update(existing);
                auditOwnershipTransfer(ctx, existing, "admin", null);
                return successResponse(existing);
            }

            // mode=delegated
            String newOwner = body.get("createdByUserId") instanceof String s ? s : ctx.getUsername();
            if (newOwner == null || newOwner.isBlank()) {
                return errorResponse(HttpStatus.BAD_REQUEST,
                        "createdByUserId is required when mode=delegated (or use the caller's username)");
            }
            String folderId = ingestAuthorizationService.resolveFolderId(
                    existing.getRepositoryId(), existing.getTargetFolderId(), existing.getTargetFolderPath());
            if (folderId == null) {
                return denied(HttpStatus.BAD_REQUEST, DenialReason.TARGET_FOLDER_UNRESOLVABLE,
                        "Profile's target folder cannot be resolved");
            }
            // Mirror the non-admin POST invariants exactly — same code path
            // would otherwise be reachable via "admin POSTs delegated=true"
            // anyway, but we want a single canonical entry point.
            if (existing.getAllowedConnectorIds() == null || existing.getAllowedConnectorIds().isEmpty()) {
                return denied(HttpStatus.BAD_REQUEST, DenialReason.EMPTY_ALLOWED_CONNECTORS,
                        "Profile has no allowedConnectorIds; cannot transfer to delegated mode");
            }
            // Re-evaluate cmis:all for the new owner using the
            // username-keyed overload — avoids synthesising a CallContext
            // for a user who may not even be the current caller. The
            // overload deliberately does NOT short-circuit for admin
            // (transfers go through the delegation gate regardless of
            // who the new owner is, so admin → admin transfers via this
            // endpoint still validate folder ownership).
            if (!ingestAuthorizationService.canManageProfileForFolderAsUser(newOwner, existing.getRepositoryId(), folderId)) {
                return denied(HttpStatus.FORBIDDEN, DenialReason.CMIS_ALL_REQUIRED,
                        "Target user " + newOwner + " does not hold cmis:all on the profile's folder");
            }
            for (String cid : existing.getAllowedConnectorIds()) {
                if (cid == null || cid.isBlank()) {
                    return denied(HttpStatus.BAD_REQUEST, DenialReason.BLANK_CONNECTOR_ENTRY,
                            "allowedConnectorIds contains a blank entry");
                }
                ConnectorDefinition c = connectorDefinitionService.get(cid);
                if (c == null) {
                    return denied(HttpStatus.BAD_REQUEST, DenialReason.UNKNOWN_CONNECTOR,
                            "Unknown connector: " + cid);
                }
                if (!ingestAuthorizationService.canUseConnectorForDelegatedProfileAsUser(
                        newOwner, existing.getRepositoryId(), c, folderId)) {
                    return denied(HttpStatus.FORBIDDEN, DenialReason.CONNECTOR_NOT_DELEGATED,
                            "Connector not delegated to " + newOwner + " for this folder: " + cid);
                }
            }
            // Apply the transfer
            existing.setDelegated(true);
            existing.setCreatedByUserId(newOwner);
            existing.setSchedulerEnabled(false);
            existing.setDefaultProfile(false);
            existing.setTargetFolderId(folderId);
            existing.setTargetFolderPath(null);
            importProfileDefinitionService.update(existing);
            auditOwnershipTransfer(ctx, existing, "delegated", newOwner);
            return successResponse(existing);
        } catch (IllegalArgumentException e) {
            return errorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private ResponseEntity<Map<String, Object>> successResponse(ImportProfileDefinition profile) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("profileId", profile.getProfileId());
        response.put("delegated", profile.isDelegated());
        if (profile.getCreatedByUserId() != null) {
            response.put("createdByUserId", profile.getCreatedByUserId());
        }
        return ResponseEntity.ok(response);
    }

    private void auditOwnershipTransfer(CallContext ctx, ImportProfileDefinition profile,
                                        String newMode, String newOwner) {
        if (auditLogger == null) return;
        try {
            String actor = ctx.getUsername() != null ? ctx.getUsername() : "anonymous";
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("delegated", profile.isDelegated());
            details.put("actorUserId", actor);
            details.put("transferTo", newMode);
            if (newOwner != null) details.put("newOwnerUserId", newOwner);
            if (profile.getTargetFolderId() != null) details.put("targetFolderId", profile.getTargetFolderId());
            auditLogger.logOperation(AuditOperation.EXTERNAL_PROFILE_UPDATED,
                    profile.getRepositoryId(), actor, profile.getProfileId(), true, null, details);
        } catch (RuntimeException ignored) {
            // Audit must never break the API path
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Delegation enforcement
    // ──────────────────────────────────────────────────────────────────

    /**
     * For non-admins on POST. Resolves the target folder, gates on
     * {@code cmis:all}, restricts the connector list to delegated ones the
     * user can use for this folder, and forces the safe defaults
     * ({@code delegated=true}, no scheduler, no defaultProfile,
     * {@code createdByUserId} stamped from the call context).
     */
    private ResponseEntity<Map<String, Object>> enforceDelegationOnCreate(CallContext ctx, ImportProfileDefinition def) {
        // Strict v1 limits — refuse rather than silently coerce so the UI/CLI
        // sees an explicit error and the operator knows what's restricted.
        if (def.isSchedulerEnabled()) {
            return denied(HttpStatus.FORBIDDEN, DenialReason.SCHEDULER_REQUIRES_ADMIN,
                    "Scheduled ingestion requires admin privileges in this release");
        }
        if (def.isDefaultProfile()) {
            return denied(HttpStatus.FORBIDDEN, DenialReason.DEFAULT_PROFILE_REQUIRES_ADMIN,
                    "defaultProfile=true requires admin privileges (affects repository-wide auto-resolution)");
        }
        String repositoryId = def.getRepositoryId();
        if (repositoryId == null || repositoryId.isBlank()) {
            return denied(HttpStatus.BAD_REQUEST, DenialReason.REPOSITORY_REQUIRED, "repositoryId is required");
        }
        String folderId = ingestAuthorizationService.resolveFolderId(
                repositoryId, def.getTargetFolderId(), def.getTargetFolderPath());
        if (folderId == null) {
            return denied(HttpStatus.BAD_REQUEST, DenialReason.TARGET_FOLDER_UNRESOLVABLE,
                    "targetFolderId or targetFolderPath must resolve");
        }
        if (!ingestAuthorizationService.canManageProfileForFolder(ctx, repositoryId, folderId)) {
            return denied(HttpStatus.FORBIDDEN, DenialReason.CMIS_ALL_REQUIRED,
                    "cmis:all on target folder required");
        }
        // Normalise to ID — avoids the path moving out from under the profile
        def.setTargetFolderId(folderId);
        def.setTargetFolderPath(null);

        // Connector scope check — required (empty = no allowed connectors,
        // which is rejected so the operator doesn't accidentally let a
        // non-admin bypass connector restrictions).
        ResponseEntity<Map<String, Object>> connectorErr = validateDelegatedConnectors(ctx, repositoryId, folderId, def);
        if (connectorErr != null) return connectorErr;

        // Stamp the safe fields LAST so a misuse can't override them via payload.
        def.setDelegated(true);
        def.setCreatedByUserId(ctx.getUsername());
        def.setSchedulerEnabled(false);
        def.setDefaultProfile(false);
        return null;
    }

    /**
     * For non-admins on PUT. TOCTOU: requires {@code cmis:all} on BOTH the
     * current and the new target folder, AND that every connector ID in
     * BOTH the current and the new {@code allowedConnectorIds} is delegated
     * to the user for the relevant folder. This blocks two attacks:
     *
     * <ul>
     *   <li>Folder swap escalation: take a delegated profile bound to
     *       folder A (where the user has {@code cmis:all}) and re-target
     *       it at folder B (where they don't).</li>
     *   <li>Connector swap escalation: swap the connector list to one the
     *       user shouldn't be able to invoke.</li>
     * </ul>
     */
    private ResponseEntity<Map<String, Object>> enforceDelegationOnUpdate(CallContext ctx, ImportProfileDefinition def) {
        ImportProfileDefinition existing = importProfileDefinitionService.get(def.getProfileId());
        if (existing == null) return errorResponse(HttpStatus.NOT_FOUND, "Profile not found");
        if (!existing.isDelegated()) {
            return denied(HttpStatus.FORBIDDEN, DenialReason.ADMIN_OWNED_PROFILE, "Admin-managed profile");
        }
        if (def.isSchedulerEnabled()) {
            return denied(HttpStatus.FORBIDDEN, DenialReason.SCHEDULER_REQUIRES_ADMIN,
                    "Scheduled ingestion requires admin privileges in this release");
        }
        if (def.isDefaultProfile()) {
            return denied(HttpStatus.FORBIDDEN, DenialReason.DEFAULT_PROFILE_REQUIRES_ADMIN,
                    "defaultProfile=true requires admin privileges (affects repository-wide auto-resolution)");
        }

        String repositoryId = existing.getRepositoryId();
        // Override repositoryId from existing — non-admins cannot move a profile across repos
        def.setRepositoryId(repositoryId);

        // Old folder check
        String oldFolderId = ingestAuthorizationService.resolveFolderId(
                repositoryId, existing.getTargetFolderId(), existing.getTargetFolderPath());
        if (oldFolderId == null
                || !ingestAuthorizationService.canManageProfileForFolder(ctx, repositoryId, oldFolderId)) {
            return denied(HttpStatus.FORBIDDEN, DenialReason.CMIS_ALL_REQUIRED_OLD,
                    "cmis:all required on existing target folder");
        }

        // New folder check (which may equal oldFolderId — re-checked anyway)
        String newFolderId = ingestAuthorizationService.resolveFolderId(
                repositoryId, def.getTargetFolderId(), def.getTargetFolderPath());
        if (newFolderId == null) {
            return denied(HttpStatus.BAD_REQUEST, DenialReason.TARGET_FOLDER_UNRESOLVABLE,
                    "targetFolderId or targetFolderPath must resolve");
        }
        if (!ingestAuthorizationService.canManageProfileForFolder(ctx, repositoryId, newFolderId)) {
            return denied(HttpStatus.FORBIDDEN, DenialReason.CMIS_ALL_REQUIRED_NEW,
                    "cmis:all required on new target folder");
        }
        def.setTargetFolderId(newFolderId);
        def.setTargetFolderPath(null);

        // Old connector list — must be valid for OLD folder (so the user
        // wasn't somehow holding a profile they couldn't have created).
        // This is defence in depth: in practice the create path should have
        // ensured this. If it slipped through in some prior version, we
        // refuse to update rather than silently re-bless a stale assignment.
        ResponseEntity<Map<String, Object>> oldErr = validateDelegatedConnectors(ctx, repositoryId, oldFolderId, existing);
        if (oldErr != null) return oldErr;

        // New connector list — must be valid for NEW folder.
        ResponseEntity<Map<String, Object>> newErr = validateDelegatedConnectors(ctx, repositoryId, newFolderId, def);
        if (newErr != null) return newErr;

        // Stamp safe defaults — preserve original createdByUserId
        def.setDelegated(true);
        def.setCreatedByUserId(existing.getCreatedByUserId() != null
                ? existing.getCreatedByUserId() : ctx.getUsername());
        def.setSchedulerEnabled(false);
        def.setDefaultProfile(false);
        return null;
    }

    /**
     * Verifies that the profile's {@code defaultConnectorId} (if any) and
     * every entry in {@code allowedConnectorIds} is delegatable to this
     * user for {@code targetFolderId}. {@code allowedConnectorIds} must be
     * non-empty for delegated profiles — empty would mean "any connector",
     * which we deliberately refuse to grant a non-admin.
     */
    private ResponseEntity<Map<String, Object>> validateDelegatedConnectors(
            CallContext ctx, String repositoryId, String targetFolderId, ImportProfileDefinition def) {
        List<String> allowed = def.getAllowedConnectorIds();
        if (allowed == null || allowed.isEmpty()) {
            return denied(HttpStatus.BAD_REQUEST, DenialReason.EMPTY_ALLOWED_CONNECTORS,
                    "allowedConnectorIds must be a non-empty list of admin-delegated connectors");
        }
        for (String cid : allowed) {
            if (cid == null || cid.isBlank()) {
                return denied(HttpStatus.BAD_REQUEST, DenialReason.BLANK_CONNECTOR_ENTRY,
                        "allowedConnectorIds must not contain blank entries");
            }
            ConnectorDefinition c = connectorDefinitionService.get(cid);
            if (c == null) {
                return denied(HttpStatus.BAD_REQUEST, DenialReason.UNKNOWN_CONNECTOR,
                        "Unknown connector: " + cid);
            }
            if (!ingestAuthorizationService.canUseConnectorForDelegatedProfile(ctx, repositoryId, c, targetFolderId)) {
                return denied(HttpStatus.FORBIDDEN, DenialReason.CONNECTOR_NOT_DELEGATED,
                        "Connector not delegated for this folder/user: " + cid);
            }
        }
        String defConn = def.getDefaultConnectorId();
        if (defConn != null && !defConn.isBlank() && !allowed.contains(defConn)) {
            return denied(HttpStatus.BAD_REQUEST, DenialReason.DEFAULT_CONNECTOR_NOT_IN_ALLOWED,
                    "defaultConnectorId must be one of allowedConnectorIds");
        }
        return null;
    }

    // ──────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────

    private CallContext currentCallContext() {
        if (httpRequest == null) return null;
        return (CallContext) httpRequest.getAttribute("CallContext");
    }

    private List<String> getPhase2Warnings(ImportProfileDefinition def) {
        if (importProfileDefinitionService instanceof ImportProfileDefinitionServiceImpl impl) {
            return impl.collectWarnings(def);
        }
        return new ArrayList<>();
    }

    private void audit(AuditOperation op, CallContext ctx, ImportProfileDefinition def,
                       boolean success, String errorMessage) {
        auditWithReason(op, ctx, def, success, errorMessage, null);
    }

    /** Audit a denial with a stable {@link DenialReason} tag in the details map. */
    private void auditDenial(AuditOperation op, CallContext ctx, ImportProfileDefinition def,
                             DenialReason reason, String message) {
        auditWithReason(op, ctx, def, false, message, reason);
    }

    private void auditWithReason(AuditOperation op, CallContext ctx, ImportProfileDefinition def,
                                 boolean success, String errorMessage, DenialReason denialReason) {
        if (auditLogger == null || ctx == null || def == null) return;
        try {
            String repoId = def.getRepositoryId();
            String objectId = def.getProfileId() != null ? def.getProfileId() : "";
            String actor = ctx.getUsername() != null ? ctx.getUsername() : "anonymous";
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("delegated", def.isDelegated());
            details.put("actorUserId", actor);
            if (def.getTargetFolderId() != null) details.put("targetFolderId", def.getTargetFolderId());
            if (def.getAllowedConnectorIds() != null && !def.getAllowedConnectorIds().isEmpty()) {
                details.put("connectorIds", def.getAllowedConnectorIds());
            }
            if (denialReason != null) details.put("denialReason", denialReason.name());
            auditLogger.logOperation(op, repoId, actor, objectId, success, errorMessage, details);
        } catch (RuntimeException ignored) {
            // Audit must not break the API path
        }
    }

    /** Pulls the error message string out of an {@link #errorResponse} body so we can record it in audit. */
    private static String extractMessage(ResponseEntity<Map<String, Object>> denied) {
        if (denied == null || denied.getBody() == null) return "denied";
        Object msg = denied.getBody().get("message");
        return msg != null ? msg.toString() : "denied";
    }

    /** Pulls the structured {@link DenialReason} key out of a body produced by {@link #denied}. */
    private static String extractDenialReason(ResponseEntity<Map<String, Object>> denied) {
        if (denied == null || denied.getBody() == null) return null;
        Object reason = denied.getBody().get("denialReason");
        return reason != null ? reason.toString() : null;
    }

    /**
     * Build a delegation-denial response that carries both a stable
     * {@link DenialReason} key and the human-readable message. Used by
     * every gate inside {@code enforceDelegation*} so the audit trail
     * gets a structured tag, not just free-form text.
     */
    private ResponseEntity<Map<String, Object>> denied(HttpStatus status, DenialReason reason, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "error");
        response.put("denialReason", reason.name());
        response.put("message", message);
        return ResponseEntity.status(status).body(response);
    }

    private ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "error");
        response.put("message", message);
        return ResponseEntity.status(status).body(response);
    }
}
