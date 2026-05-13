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
                ResponseEntity<Map<String, Object>> denied = enforceDelegationOnCreate(ctx, def);
                if (denied != null) return denied;
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
        List<ImportProfileDefinition> visible = new ArrayList<>();
        for (ImportProfileDefinition p : all) {
            if (!p.isDelegated()) continue;
            String folderId = ingestAuthorizationService.resolveFolderId(
                    p.getRepositoryId(), p.getTargetFolderId(), p.getTargetFolderPath());
            if (folderId == null) continue;
            if (ingestAuthorizationService.canManageProfileForFolder(ctx, p.getRepositoryId(), folderId)) {
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
            if (!def.isDelegated()) return errorResponse(HttpStatus.FORBIDDEN, "Admin-managed profile");
            String folderId = ingestAuthorizationService.resolveFolderId(
                    def.getRepositoryId(), def.getTargetFolderId(), def.getTargetFolderPath());
            if (folderId == null
                    || !ingestAuthorizationService.canManageProfileForFolder(ctx, def.getRepositoryId(), folderId)) {
                return errorResponse(HttpStatus.FORBIDDEN, "cmis:all on target folder required");
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
                ResponseEntity<Map<String, Object>> denied = enforceDelegationOnUpdate(ctx, def);
                if (denied != null) return denied;
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
            if (!existing.isDelegated()) return errorResponse(HttpStatus.FORBIDDEN, "Admin-managed profile");
            String folderId = ingestAuthorizationService.resolveFolderId(
                    existing.getRepositoryId(), existing.getTargetFolderId(), existing.getTargetFolderPath());
            if (folderId == null
                    || !ingestAuthorizationService.canManageProfileForFolder(ctx, existing.getRepositoryId(), folderId)) {
                return errorResponse(HttpStatus.FORBIDDEN, "cmis:all on target folder required");
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
            return errorResponse(HttpStatus.FORBIDDEN,
                    "Scheduled ingestion requires admin privileges in this release");
        }
        if (def.isDefaultProfile()) {
            return errorResponse(HttpStatus.FORBIDDEN,
                    "defaultProfile=true requires admin privileges (affects repository-wide auto-resolution)");
        }
        String repositoryId = def.getRepositoryId();
        if (repositoryId == null || repositoryId.isBlank()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "repositoryId is required");
        }
        String folderId = ingestAuthorizationService.resolveFolderId(
                repositoryId, def.getTargetFolderId(), def.getTargetFolderPath());
        if (folderId == null) {
            return errorResponse(HttpStatus.BAD_REQUEST, "targetFolderId or targetFolderPath must resolve");
        }
        if (!ingestAuthorizationService.canManageProfileForFolder(ctx, repositoryId, folderId)) {
            return errorResponse(HttpStatus.FORBIDDEN, "cmis:all on target folder required");
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
            return errorResponse(HttpStatus.FORBIDDEN, "Admin-managed profile");
        }
        if (def.isSchedulerEnabled()) {
            return errorResponse(HttpStatus.FORBIDDEN,
                    "Scheduled ingestion requires admin privileges in this release");
        }
        if (def.isDefaultProfile()) {
            return errorResponse(HttpStatus.FORBIDDEN,
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
            return errorResponse(HttpStatus.FORBIDDEN, "cmis:all required on existing target folder");
        }

        // New folder check (which may equal oldFolderId — re-checked anyway)
        String newFolderId = ingestAuthorizationService.resolveFolderId(
                repositoryId, def.getTargetFolderId(), def.getTargetFolderPath());
        if (newFolderId == null) {
            return errorResponse(HttpStatus.BAD_REQUEST, "targetFolderId or targetFolderPath must resolve");
        }
        if (!ingestAuthorizationService.canManageProfileForFolder(ctx, repositoryId, newFolderId)) {
            return errorResponse(HttpStatus.FORBIDDEN, "cmis:all required on new target folder");
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
            return errorResponse(HttpStatus.BAD_REQUEST,
                    "allowedConnectorIds must be a non-empty list of admin-delegated connectors");
        }
        for (String cid : allowed) {
            if (cid == null || cid.isBlank()) {
                return errorResponse(HttpStatus.BAD_REQUEST, "allowedConnectorIds must not contain blank entries");
            }
            ConnectorDefinition c = connectorDefinitionService.get(cid);
            if (c == null) {
                return errorResponse(HttpStatus.BAD_REQUEST, "Unknown connector: " + cid);
            }
            if (!ingestAuthorizationService.canUseConnectorForDelegatedProfile(ctx, repositoryId, c, targetFolderId)) {
                return errorResponse(HttpStatus.FORBIDDEN,
                        "Connector not delegated for this folder/user: " + cid);
            }
        }
        String defConn = def.getDefaultConnectorId();
        if (defConn != null && !defConn.isBlank() && !allowed.contains(defConn)) {
            return errorResponse(HttpStatus.BAD_REQUEST,
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
            auditLogger.logOperation(op, repoId, actor, objectId, success, errorMessage, details);
        } catch (RuntimeException ignored) {
            // Audit must not break the API path
        }
    }

    private ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "error");
        response.put("message", message);
        return ResponseEntity.status(status).body(response);
    }
}
