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

    /**
     * RC5 (v2 §12.1): operator opt-in for delegated scheduling. When
     * {@code nemakiware.ingest.delegated.schedulerEnabled=true}, non-
     * admins are allowed to set {@code schedulerEnabled=true} on
     * delegated profiles they own; the scheduler then runs the tick under
     * the creator's synthesised CallContext with per-tick ACL re-eval.
     * Default false preserves RC4 behaviour (refuse with
     * {@code SCHEDULER_REQUIRES_ADMIN}).
     */
    @Autowired(required = false)
    private jp.aegif.nemaki.util.PropertyManager propertyManager;

    @Autowired
    private HttpServletRequest httpRequest;

    /** Reads a boolean property, defaulting on null / blank / unset. */
    private boolean readBool(String key, boolean defaultValue) {
        if (propertyManager == null) return defaultValue;
        String val = propertyManager.readValue(key);
        if (val == null || val.isBlank()) return defaultValue;
        return Boolean.parseBoolean(val.trim());
    }

    /** Single source of truth for the v2 delegated-scheduling opt-in. */
    private boolean isDelegatedSchedulingEnabled() {
        return readBool("nemakiware.ingest.delegated.schedulerEnabled", false);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody ImportProfileDefinition def) {
        CallContext ctx = currentCallContext();
        if (ctx == null) return errorResponse(HttpStatus.UNAUTHORIZED, "No call context");
        // Cross-repository confinement: you may only create a profile in the
        // repository you authenticated against (even as an admin).
        ResponseEntity<Map<String, Object>> repoErr = requireSameRepository(ctx, def.getRepositoryId());
        if (repoErr != null) return repoErr;
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
            // P1-1(e) §1.3: server-stamped, even for admins — the admin create path passes the
            // body through otherwise, and "who configured the schedule" must be an observation,
            // not an assertion (Codex H4).
            def.setScheduleConfiguredByUserId(ctx.getUsername());
            def.setScheduleConfiguredAtMs(System.currentTimeMillis());
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
            @RequestParam(required = false) String repositoryId,
            @RequestParam(required = false) String autoDisabledSince) {
        CallContext ctx = currentCallContext();
        if (ctx == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        boolean admin = ingestAuthorizationService.isAdmin(ctx);
        // Cross-repository confinement: list only the authenticated repository's
        // profiles (an admin no longer sees every repository's profiles). A
        // repositoryId param, when supplied, must match the authenticated repo.
        String authRepo = authRepository(ctx);
        if (repositoryId != null && !repositoryId.isBlank() && !repositoryId.equals(authRepo)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        List<ImportProfileDefinition> all = importProfileDefinitionService.listByRepository(authRepo);
        // W1 (RC5.3): server-side "auto-disabled since" filter.
        // R4 (RC5.4): malformed ISO-8601 → 400 BAD_REQUEST (was
        // fail-safe pass-through in RC5.3). Empty / missing param
        // still passes through (no filter applied) — only malformed
        // non-empty values 400.
        try {
            all = applyAutoDisabledSinceFilter(all, autoDisabledSince);
        } catch (IllegalArgumentException badInput) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
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
        // Cross-repository confinement: a profile in another repository is 404.
        if (!belongsToAuthRepository(ctx, def)) return errorResponse(HttpStatus.NOT_FOUND, "Profile not found");

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

        // Cross-repository confinement: the stored profile must belong to the
        // authenticated repository. Absent → 404; other repository → 404 (no
        // cross-repository existence disclosure). This runs before any mutation
        // and covers both the admin and delegated paths below.
        ImportProfileDefinition existingForRepo = importProfileDefinitionService.get(profileId);
        if (existingForRepo == null || !belongsToAuthRepository(ctx, existingForRepo)) {
            return errorResponse(HttpStatus.NOT_FOUND, "Profile not found");
        }
        // A caller must not relocate a profile into another repository via the body.
        def.setRepositoryId(existingForRepo.getRepositoryId());

        // P1-1(e) §1.3 (Codex H3): scheduleConfiguredBy* is SERVER-owned. The full-replace PUT
        // would otherwise let any caller plant a false operator or erase the record by omitting
        // the field. Client values are always discarded; a schedule-relevant change stamps the
        // authenticated operator, anything else preserves the stored value.
        boolean scheduleChanged = def.isEnabled() != existingForRepo.isEnabled()
                || def.isSchedulerEnabled() != existingForRepo.isSchedulerEnabled()
                || def.isDelegated() != existingForRepo.isDelegated()
                || !java.util.Objects.equals(def.getDefaultConnectorId(),
                        existingForRepo.getDefaultConnectorId())
                || !java.util.Objects.equals(def.getAllowedConnectorIds(),
                        existingForRepo.getAllowedConnectorIds())
                || !java.util.Objects.equals(def.getSchedulerParams(),
                        existingForRepo.getSchedulerParams());
        if (scheduleChanged) {
            def.setScheduleConfiguredByUserId(ctx.getUsername());
            def.setScheduleConfiguredAtMs(System.currentTimeMillis());
        } else {
            def.setScheduleConfiguredByUserId(existingForRepo.getScheduleConfiguredByUserId());
            def.setScheduleConfiguredAtMs(existingForRepo.getScheduleConfiguredAtMs());
        }

        // F1 (RC5 ext): a non-admin must never be able to spoof the
        // scheduler-controlled marker fields via the PUT payload. Strip
        // them BEFORE the V1 handshake so the rest of update() operates
        // on clean data. Admin payloads are trusted: admins can audit
        // and adjust the markers manually if needed (e.g. data repair).
        if (!ingestAuthorizationService.isAdmin(ctx)) {
            def.setLastAutoDisabledAt(null);
            def.setLastAutoDisabledReason(null);
        }

        // V1 (RC5 ext): handle the auto-disable re-enable handshake.
        // - On re-enable (existing.enabled=false → def.enabled=true) for
        //   a profile that the scheduler had auto-disabled, clear the
        //   marker fields so admin sees a clean state. The audit detail
        //   records the reset for accountability.
        // - On any other update, preserve the marker if the caller's
        //   payload doesn't include it (so an unrelated PUT — e.g.
        //   flipping rateLimitRpm — doesn't accidentally erase the
        //   audit trail).
        boolean clearedAutoDisableMarker = false;
        ImportProfileDefinition existingForMarker =
                importProfileDefinitionService.get(profileId);
        if (existingForMarker != null) {
            boolean reEnableFromAutoDisable = def.isEnabled()
                    && !existingForMarker.isEnabled()
                    && existingForMarker.getLastAutoDisabledAt() != null;
            if (reEnableFromAutoDisable) {
                def.setLastAutoDisabledAt(null);
                def.setLastAutoDisabledReason(null);
                clearedAutoDisableMarker = true;
            } else if (def.getLastAutoDisabledAt() == null
                    && existingForMarker.getLastAutoDisabledAt() != null) {
                def.setLastAutoDisabledAt(existingForMarker.getLastAutoDisabledAt());
                def.setLastAutoDisabledReason(existingForMarker.getLastAutoDisabledReason());
            }
        }

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
            if (clearedAutoDisableMarker) {
                auditAutoDisableReset(ctx, def);
            }
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
        // Cross-repository confinement: a profile in another repository is 404.
        if (!belongsToAuthRepository(ctx, existing)) return errorResponse(HttpStatus.NOT_FOUND, "Profile not found");
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
        // Cross-repository confinement: a profile in another repository is 404.
        if (!belongsToAuthRepository(ctx, existing)) return errorResponse(HttpStatus.NOT_FOUND, "Profile not found");

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
                // Audit this denial too — every other transfer denial
                // flows through denyTransfer() and we don't want this
                // branch silently dropping out of the security trail.
                // folderId is null by definition here, so the details
                // map records targetFolderId only if the caller passed
                // a partial profile that has it set (resolveFolderId
                // returned null because of a path/ID mismatch).
                return denyTransfer(ctx, existing, newOwner, null,
                        HttpStatus.BAD_REQUEST, DenialReason.TARGET_FOLDER_UNRESOLVABLE,
                        "Profile's target folder cannot be resolved");
            }
            // Mirror the non-admin POST invariants exactly — same code path
            // would otherwise be reachable via "admin POSTs delegated=true"
            // anyway, but we want a single canonical entry point.
            if (existing.getAllowedConnectorIds() == null || existing.getAllowedConnectorIds().isEmpty()) {
                return denyTransfer(ctx, existing, newOwner, folderId,
                        HttpStatus.BAD_REQUEST, DenialReason.EMPTY_ALLOWED_CONNECTORS,
                        "Profile has no allowedConnectorIds; cannot transfer to delegated mode");
            }
            // defaultConnectorId, when set, must be in allowedConnectorIds.
            // The normal delegated PUT enforces this in validateDelegatedConnectors —
            // transfer must apply the same shape invariant before flipping the
            // delegated flag, otherwise a transferred profile could land in a
            // state the runtime gate would later refuse.
            String existingDefault = existing.getDefaultConnectorId();
            if (existingDefault != null && !existingDefault.isBlank()
                    && !existing.getAllowedConnectorIds().contains(existingDefault)) {
                return denyTransfer(ctx, existing, newOwner, folderId,
                        HttpStatus.BAD_REQUEST, DenialReason.DEFAULT_CONNECTOR_NOT_IN_ALLOWED,
                        "Profile's defaultConnectorId is not in allowedConnectorIds; transfer refused");
            }
            // Re-evaluate cmis:all for the new owner using the
            // username-keyed overload — avoids synthesising a CallContext
            // for a user who may not even be the current caller. The
            // overload deliberately does NOT short-circuit for admin
            // (transfers go through the delegation gate regardless of
            // who the new owner is, so admin → admin transfers via this
            // endpoint still validate folder ownership).
            if (!ingestAuthorizationService.canManageProfileForFolderAsUser(newOwner, existing.getRepositoryId(), folderId)) {
                return denyTransfer(ctx, existing, newOwner, folderId,
                        HttpStatus.FORBIDDEN, DenialReason.CMIS_ALL_REQUIRED,
                        "Target user " + newOwner + " does not hold cmis:all on the profile's folder");
            }
            for (String cid : existing.getAllowedConnectorIds()) {
                if (cid == null || cid.isBlank()) {
                    return denyTransfer(ctx, existing, newOwner, folderId,
                            HttpStatus.BAD_REQUEST, DenialReason.BLANK_CONNECTOR_ENTRY,
                            "allowedConnectorIds contains a blank entry");
                }
                ConnectorDefinition c = connectorDefinitionService.get(cid);
                if (c == null) {
                    return denyTransfer(ctx, existing, newOwner, folderId,
                            HttpStatus.BAD_REQUEST, DenialReason.UNKNOWN_CONNECTOR,
                            "Unknown connector: " + cid);
                }
                if (!ingestAuthorizationService.canUseConnectorForDelegatedProfileAsUser(
                        newOwner, existing.getRepositoryId(), c, folderId)) {
                    return denyTransfer(ctx, existing, newOwner, folderId,
                            HttpStatus.FORBIDDEN, DenialReason.CONNECTOR_NOT_DELEGATED,
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

    /**
     * Build a denial response for a delegated-mode transfer attempt AND
     * record the matching audit entry in one call. Inline because the
     * audit shape (transferTo + newOwnerUserId) is specific enough to
     * this endpoint that folding it into the generic {@code auditDenial}
     * would over-couple the two — the rest of the controller doesn't
     * need to know about transfer semantics.
     */
    private ResponseEntity<Map<String, Object>> denyTransfer(
            CallContext ctx, ImportProfileDefinition existing, String newOwner, String folderId,
            HttpStatus status, DenialReason reason, String message) {
        ResponseEntity<Map<String, Object>> resp = denied(status, reason, message);
        auditTransferDenial(ctx, existing, newOwner, folderId, reason, message);
        return resp;
    }

    private void auditTransferDenial(CallContext ctx, ImportProfileDefinition existing,
                                     String newOwner, String folderId,
                                     DenialReason reason, String message) {
        if (ctx == null) return;
        String actor = ctx.getUsername() != null ? ctx.getUsername() : "anonymous";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("delegated", existing != null && existing.isDelegated());
        details.put("actorUserId", actor);
        details.put("transferTo", "delegated");
        if (newOwner != null) details.put("newOwnerUserId", newOwner);
        if (folderId != null) details.put("targetFolderId", folderId);
        if (reason != null) details.put("denialReason", reason.name());
        String repoId = existing != null ? existing.getRepositoryId() : null;
        String objectId = existing != null && existing.getProfileId() != null ? existing.getProfileId() : "";
        // H1 (RC5.5): safeEmit logs WARN on audit pipeline failure
        // (was: catch (RuntimeException ignored) silent swallow).
        jp.aegif.nemaki.audit.AuditEmitSupport.safeEmit(auditLogger,
                AuditOperation.EXTERNAL_PROFILE_UPDATED,
                repoId, actor, objectId, false, message, details);
    }

    /**
     * H1 (RC5.5): all audit emit failures now go through
     * {@link jp.aegif.nemaki.audit.AuditEmitSupport#safeEmit} which
     * logs a WARN with op + actor + object + error message (but not
     * the audit `details` map, which stays segregated to the audit
     * pipeline). Replaces the previous {@code catch (RuntimeException
     * ignored)} pattern that left audit-pipeline outages silent.
     */

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
        String actor = ctx.getUsername() != null ? ctx.getUsername() : "anonymous";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("delegated", profile.isDelegated());
        details.put("actorUserId", actor);
        details.put("transferTo", newMode);
        if (newOwner != null) details.put("newOwnerUserId", newOwner);
        if (profile.getTargetFolderId() != null) details.put("targetFolderId", profile.getTargetFolderId());
        jp.aegif.nemaki.audit.AuditEmitSupport.safeEmit(auditLogger,
                AuditOperation.EXTERNAL_PROFILE_UPDATED,
                profile.getRepositoryId(), actor, profile.getProfileId(), true, null, details);
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
        // RC5 (v2 §12.1): the scheduler gate is property-controlled.
        // Default false → keep the v1 behaviour (refuse non-admin scheduled
        // profiles outright). When the operator opts in, the scheduler
        // itself enforces per-tick cmis:all + connector re-eval under a
        // synthesised CallContext, so it is safe to let the profile be
        // created here.
        boolean delegatedSchedulingOn = isDelegatedSchedulingEnabled();
        if (def.isSchedulerEnabled() && !delegatedSchedulingOn) {
            return denied(HttpStatus.FORBIDDEN, DenialReason.SCHEDULER_REQUIRES_ADMIN,
                    "Scheduled ingestion requires admin privileges (or operator opt-in via "
                            + "nemakiware.ingest.delegated.schedulerEnabled=true)");
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
        // RC5 (v2 §12.1): preserve user's schedulerEnabled choice when the
        // operator has opted in; otherwise force it off (v1 behaviour).
        if (!delegatedSchedulingOn) {
            def.setSchedulerEnabled(false);
        }
        def.setDefaultProfile(false);
        // F1 (RC5 ext): scheduler-controlled marker fields. A non-admin
        // payload that tries to set these is ignored — only the scheduler
        // (admin-level code path) is allowed to write them. The handshake
        // in update() handles preservation from the existing record for
        // admin path; on create, the markers must always start null.
        def.setLastAutoDisabledAt(null);
        def.setLastAutoDisabledReason(null);
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
        // RC5 (v2 §12.1): see enforceDelegationOnCreate — property-gated.
        boolean delegatedSchedulingOn = isDelegatedSchedulingEnabled();
        if (def.isSchedulerEnabled() && !delegatedSchedulingOn) {
            return denied(HttpStatus.FORBIDDEN, DenialReason.SCHEDULER_REQUIRES_ADMIN,
                    "Scheduled ingestion requires admin privileges (or operator opt-in via "
                            + "nemakiware.ingest.delegated.schedulerEnabled=true)");
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
        // RC5 (v2 §12.1): preserve user's schedulerEnabled choice when the
        // operator has opted in; otherwise force it off (v1 behaviour).
        if (!delegatedSchedulingOn) {
            def.setSchedulerEnabled(false);
        }
        def.setDefaultProfile(false);
        // Marker fields are governed exclusively by the update() handshake
        // (above this method's call). F1 ensures non-admin payloads can't
        // spoof markers by stripping them BEFORE the handshake runs.
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

    /** The repository the caller authenticated against (see AuthenticationFilter /v1/admin/* handling). */
    private String authRepository(CallContext ctx) {
        return ctx == null ? null : ctx.getRepositoryId();
    }

    /**
     * Cross-repository confinement for by-id operations: a stored profile is
     * only visible/editable to a caller authenticated against the profile's own
     * repository. Callers use the returned 404 (not 403) so a profile in
     * another repository is indistinguishable from a non-existent one — no
     * cross-repository existence disclosure.
     */
    private boolean belongsToAuthRepository(CallContext ctx, ImportProfileDefinition def) {
        return def != null
                && def.getRepositoryId() != null
                && def.getRepositoryId().equals(authRepository(ctx));
    }

    /**
     * Cross-repository confinement for create: the target repositoryId supplied
     * in the request body must be the repository the caller authenticated
     * against. Returns 400 if missing, 403 on mismatch, else null.
     */
    private ResponseEntity<Map<String, Object>> requireSameRepository(CallContext ctx, String targetRepositoryId) {
        if (targetRepositoryId == null || targetRepositoryId.isBlank()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "repositoryId is required");
        }
        if (!targetRepositoryId.equals(authRepository(ctx))) {
            return errorResponse(HttpStatus.FORBIDDEN,
                    "Operation targets a different repository than the authenticated one");
        }
        return null;
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

    /**
     * W1 (RC5.3) / R4 (RC5.4): server-side analogue of V6's client-side
     * "auto-disabled within last N days" filter. The UI ships
     * `autoDisabledSince` as an ISO-8601 instant (the cutoff). A
     * profile passes if it carries a `lastAutoDisabledAt` that is
     * &gt;= cutoff.
     *
     * <p>R4 (RC5.4): strict on malformed input — throws
     * {@link IllegalArgumentException} so the controller returns 400.
     * Pre-RC5.4 behaviour was fail-safe pass-through, which the
     * closure review flagged as risky because a typo could
     * silently return the full list and let an operator misread
     * "no recent shutdowns". The RC5.3 UI only ever ships
     * `Date.toISOString()` output, so this strictness has no
     * impact on the shipped UI flow; it surfaces UI bugs / CLI
     * typos immediately.
     *
     * <p>Profiles without a `lastAutoDisabledAt` (never auto-disabled
     * or marker cleared on re-enable) do NOT pass the filter — the
     * point of the filter is to surface recent scheduler shutdowns,
     * which only marker-bearing profiles can be.
     *
     * <p>Profile-side malformed marker is still defensively excluded
     * (one bad record must not be silently surfaced as "recent").
     */
    private List<ImportProfileDefinition> applyAutoDisabledSinceFilter(
            List<ImportProfileDefinition> input, String autoDisabledSince) {
        if (autoDisabledSince == null || autoDisabledSince.isBlank()) return input;
        long cutoffMs;
        try {
            cutoffMs = java.time.Instant.parse(autoDisabledSince).toEpochMilli();
        } catch (java.time.format.DateTimeParseException | ArithmeticException e) {
            // R4 (RC5.4) / C1 (RC5.5): strict — bubble up to 400.
            // Controller's catch(IllegalArgumentException) maps to
            // BAD_REQUEST. RC5.4 only caught DateTimeParseException;
            // RC5.5 (C1) adds ArithmeticException because
            // Instant.parse can succeed on extreme values (e.g.
            // +999999999-12-31T23:59:59Z) but then overflow on
            // toEpochMilli() — the external review caught this as a
            // 500 leak in the otherwise-strict 400 contract.
            throw new IllegalArgumentException(
                    "autoDisabledSince must be a valid ISO-8601 instant within Long-epoch range: "
                            + autoDisabledSince, e);
        }
        List<ImportProfileDefinition> out = new ArrayList<>();
        for (ImportProfileDefinition p : input) {
            String at = p.getLastAutoDisabledAt();
            if (at == null || at.isBlank()) continue;       // no marker = exclude
            try {
                long t = java.time.Instant.parse(at).toEpochMilli();
                if (t >= cutoffMs) out.add(p);
            } catch (java.time.format.DateTimeParseException | ArithmeticException e) {
                // C1 (RC5.5): profile-side defensive exclude now also
                // covers ArithmeticException. A single corrupted
                // profile with an overflow-prone marker no longer
                // 500s the entire list response — it is excluded the
                // same way malformed parse failures are.
            }
        }
        return out;
    }

    /**
     * V1 (RC5 ext): emit a dedicated audit entry when admin/folder-owner
     * deliberately clears the scheduler's auto-disable marker. Lets SOC
     * tooling distinguish "the scheduler turned this profile off" from
     * "a human turned it back on" — important when investigating a
     * second auto-disable that follows a re-enable.
     */
    private void auditAutoDisableReset(CallContext ctx, ImportProfileDefinition def) {
        if (ctx == null || def == null) return;
        String actor = ctx.getUsername() != null ? ctx.getUsername() : "anonymous";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("actorUserId", actor);
        details.put("delegated", def.isDelegated());
        details.put("profileId", def.getProfileId());
        details.put("clearedAutoDisableMarker", true);
        jp.aegif.nemaki.audit.AuditEmitSupport.safeEmit(auditLogger,
                AuditOperation.EXTERNAL_PROFILE_UPDATED,
                def.getRepositoryId(), actor,
                def.getProfileId() != null ? def.getProfileId() : "",
                true, "Auto-disable marker cleared (deliberate re-enable)",
                details);
    }

    private void auditWithReason(AuditOperation op, CallContext ctx, ImportProfileDefinition def,
                                 boolean success, String errorMessage, DenialReason denialReason) {
        if (ctx == null || def == null) return;
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
        jp.aegif.nemaki.audit.AuditEmitSupport.safeEmit(auditLogger,
                op, repoId, actor, objectId, success, errorMessage, details);
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
