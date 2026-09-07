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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/admin/import-profiles")
public class ImportProfileDefinitionController {

    private static final Logger logger =
            LoggerFactory.getLogger(ImportProfileDefinitionController.class);

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
        } catch (ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException e) {
            audit(AuditOperation.EXTERNAL_PROFILE_CREATED, ctx, def, false, e.getMessage());
            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        } catch (IllegalArgumentException | IllegalStateException e) {
            audit(AuditOperation.EXTERNAL_PROFILE_CREATED, ctx, def, false, e.getMessage());
            return errorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * A listing the service could not complete — a selector page it could not continue,
     * a walk that did not answer — escaped every endpoint without an explicit catch as a
     * Spring 500 ({@code GlobalExceptionHandler} does not cover this package). The typed
     * refusals exist so the answer can be 503, "retry", not "our bug"; this is the floor.
     * Endpoints that catch them themselves keep their own mapping.
     */
    @ExceptionHandler({ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class,
            ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException.class})
    public ResponseEntity<Map<String, Object>> definitionRowsCouldNotBeRead(RuntimeException e) {
        return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
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
        // The caller's own row FIRST, index-free: a profile that only has a legacy
        // generated-id row is invisible to the selector and to the id-addressed fallback, so
        // GET answered 503 for it while the ownership transfer (which resolves this way)
        // answered 200. A review found the split.
        ImportProfileDefinition[] mine = new ImportProfileDefinition[1];
        ResponseEntity<Map<String, Object>> refused = resolveMine(ctx, profileId, def, r -> mine[0] = r);
        if (refused != null) return refused;
        def = mine[0];
        if (def == null) {
            // Absence established index-free by the walk above — no second walk.
            return errorResponse(HttpStatus.NOT_FOUND, "Profile not found");
        }
        // Confinement is already established: def is this repository's row.

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
        ImportProfileDefinition selectedForPut = importProfileDefinitionService.get(profileId);
        ImportProfileDefinition[] minePut = new ImportProfileDefinition[1];
        ResponseEntity<Map<String, Object>> refusedPut =
                resolveMine(ctx, profileId, selectedForPut, r -> minePut[0] = r);
        if (refusedPut != null) return refusedPut;
        ImportProfileDefinition existingForRepo = minePut[0];
        if (existingForRepo == null) {
            // Absence established index-free — no second walk.
            return errorResponse(HttpStatus.NOT_FOUND, "Profile not found");
        }
        // Confinement is already established above.
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
                        existingForRepo.getSchedulerParams())
                // The target folder is where every autonomous run LANDS — repointing it is a
                // schedule-relevant change of the same kind as swapping the connector, and
                // leaving the prior operator on record for it was H3's defect in one more
                // field (external review). Representation changes (path ↔ id for the same
                // folder) over-stamp at worst, which records the operator who touched it.
                || !java.util.Objects.equals(def.getTargetFolderId(),
                        existingForRepo.getTargetFolderId())
                || !java.util.Objects.equals(def.getTargetFolderPath(),
                        existingForRepo.getTargetFolderPath());
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
        // The row already resolved for THIS repository: a second unconfined read would
        // decide the marker from whichever twin the selector returned. A review found the
        // same arbitrary-twin read here as in the delegation gate.
        ImportProfileDefinition existingForMarker = existingForRepo;
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
                ResponseEntity<Map<String, Object>> deniedResp = enforceDelegationOnUpdate(ctx, def, existingForRepo);
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
        } catch (ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException e) {
            // Transient and retryable: the profile's rows could not all be seen while an
            // index rebuilds. Same mapping the connector controller gained in round 5; a 500
            // here is what opens tickets for a condition a retry resolves.
            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        } catch (ImportProfileDefinitionServiceImpl.ProfileHasTwinRowsException e) {
            // Standing, not transient: two rows define this profile and an update would
            // choose between them. 409 — a retry does not resolve it, an administrator does.
            audit(AuditOperation.EXTERNAL_PROFILE_UPDATED, ctx, def, false, e.getMessage());
            return errorResponse(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @DeleteMapping("/{profileId}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String profileId,
            @org.springframework.web.bind.annotation.RequestParam(value = "docId",
                    required = false) String docId) {
        CallContext ctx = currentCallContext();
        if (ctx == null) return errorResponse(HttpStatus.UNAUTHORIZED, "No call context");

        boolean admin = ingestAuthorizationService.isAdmin(ctx);
        if (docId != null && !docId.isBlank()) {
            // The row-addressed resolver runs FIRST and on its own. Everything below reads
            // whichever twin the selector returned, and when that twin belongs to another
            // repository the caller was refused 404 for a row of their OWN repository — the
            // documented repair path made unusable by storage order. A review found it. The
            // service validates the addressed row against the caller's repository itself.
            return deleteOneRow(ctx, profileId, docId, admin);
        }
        // Cross-repository confinement: a profile in another repository is 404 — UNLESS the
        // caller's own repository also has a row of this profileId. get() is a selector over
        // profileId alone, so with the same id in two repositories it returns an arbitrary
        // twin, and this check refused the owner of the OTHER row: the documented repair was
        // unreachable through the API.
        //
        // The caller's OWN row is fetched index-free and everything below then reasons about
        // it. The first fix for this was a separate branch that skipped those checks and was
        // administrator-only — which made the delegation rule depend on which twin the
        // selector happened to return. A review found that; one path is the answer.
        // null, not the selector's row: this verb AUTHORISES from what it resolves and then
        // removes every row of the repository, so it must see a pair. The selector cannot
        // tell one row from two.
        ImportProfileDefinition[] mineDel = new ImportProfileDefinition[1];
        ResponseEntity<Map<String, Object>> refusedDel =
                resolveMine(ctx, profileId, null, r -> mineDel[0] = r);
        if (refusedDel != null) return refusedDel;
        // No selector read above this line. One stood here, assigned and then overwritten
        // unread — and get() does not wrap its Mango call, so a rebuilding index threw
        // straight out as 500 in front of the very verb we made index-free. A review found
        // the dead read still able to decide the answer.
        ImportProfileDefinition existing = mineDel[0];
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

        int elsewhere;
        try {
            elsewhere = importProfileDefinitionService.delete(profileId, authRepository(ctx));
        } catch (ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException e) {
            // "Deleted" must mean deleted: a row that could not be read is a retry, not a
            // success with a survivor. Nothing was written, but the attempt is auditable.
            audit(AuditOperation.EXTERNAL_PROFILE_DELETED, ctx, existing, false, e.getMessage());
            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        } catch (ImportProfileDefinitionServiceImpl.ProfilePartiallyDeletedException partly) {
            // Rows ARE gone and at least one is not: CouchDB has no transaction across
            // documents. Letting it escape answered 500 with no audit entry for a deletion
            // that had partly happened. Retryable — a retry removes what remains. Narrow on
            // purpose: the first version caught every RuntimeException, so a store that
            // refused the whole operation (authentication, permission) was also told to
            // retry, for ever. A review caught the over-broad arm.
            audit(AuditOperation.EXTERNAL_PROFILE_DELETED, ctx, existing, false,
                    partly.getMessage());
            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, partly.getMessage());
        }
        // Stop the IDLE thread AFTER the deletion. It used to run first, and once the delete
        // could refuse retryably that order stopped live IMAP capture for a profile that
        // still existed — a 503 with mail capture silently disabled (stopIdle disconnects
        // the adapter and nothing restarts it). A round-3 review caught the ordering.
        stopSchedulerIfThisRepositoryLosesIt(profileId, authRepository(ctx), elsewhere);
        audit(AuditOperation.EXTERNAL_PROFILE_DELETED, ctx, existing, true, null);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        if (elsewhere < 0) {
            // A failed read is not an answered one: the caller was getting the same body as
            // for a known survivor count. A review found the two indistinguishable.
            response.put("warning", "the rows were removed, but whether any repository still"
                    + " has this profile could not be established");
        }
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
        // The same shared-profileId resolution as GET/PUT/DELETE.
        ImportProfileDefinition[] mineOwn = new ImportProfileDefinition[1];
        ResponseEntity<Map<String, Object>> refusedOwn =
                resolveMine(ctx, profileId, existing, r -> mineOwn[0] = r);
        if (refusedOwn != null) return refusedOwn;
        existing = mineOwn[0];
        if (existing == null) {
            // Absence established index-free — no second walk.
            return errorResponse(HttpStatus.NOT_FOUND, "Profile not found");
        }
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
                // An ownership transfer IS a schedule-relevant change; skipping the stamp here
                // left the previous operator on record for a schedule someone else just
                // reshaped (Codex H3/H4 residual).
                existing.setScheduleConfiguredByUserId(ctx.getUsername());
                existing.setScheduleConfiguredAtMs(System.currentTimeMillis());
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
            // Schedule-relevant: stamp the operator performing the transfer (Codex H3/H4).
            existing.setScheduleConfiguredByUserId(ctx.getUsername());
            existing.setScheduleConfiguredAtMs(System.currentTimeMillis());
            importProfileDefinitionService.update(existing);
            auditOwnershipTransfer(ctx, existing, "delegated", newOwner);
            return successResponse(existing);
        } catch (IllegalArgumentException e) {
            return errorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException e) {
            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        } catch (ImportProfileDefinitionServiceImpl.ProfileHasTwinRowsException e) {
            return errorResponse(HttpStatus.CONFLICT, e.getMessage());
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
    private ResponseEntity<Map<String, Object>> enforceDelegationOnUpdate(CallContext ctx,
            ImportProfileDefinition def, ImportProfileDefinition alreadyResolved) {
        // Through the same gate as the other verbs. This second read is where a delegated
        // PUT still answered a bare 404 for a profile that IS there — and where it then
        // decided delegation from whichever twin the selector returned, so a shared
        // profileId could authorise or deny from ANOTHER repository's row. A review found
        // both; the caller's own row is fetched index-free.
        // The row the caller's verb already resolved for this repository. Reading it again
        // meant a second index-free walk on every non-admin PUT while the selector could not
        // show the row — the double walk that was just removed elsewhere, in another form.
        ImportProfileDefinition existing = alreadyResolved;
        if (existing == null) {
            // Absence established index-free by the resolution above — no second walk.
            return errorResponse(HttpStatus.NOT_FOUND, "Profile not found");
        }
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

    /**
     * Stops the profile's IDLE thread when this deletion takes away the row that session
     * was importing into.
     *
     * <p>The first version stopped only when NO repository still had the profileId. That
     * protected another repository's live capture, but a review showed the other half: the
     * IDLE map is keyed by profileId alone and a session captures one row's repositoryId
     * for every message it imports. Delete repository A's row while B keeps the id, and a
     * session started for A kept importing into A — a deletion that does not stop the
     * capture it authorised. So the question is not "does anyone still have this id" but
     * "was the live session sending mail HERE".
     *
     * <p>A session we cannot attribute (running, but no repository recorded) is stopped:
     * losing capture is recoverable by restarting IDLE, a live connection to a mailbox that
     * no repository still authorises is not (the imports themselves are refused by the
     * message loop, which reloads the profile every time). A count of {@code -1} still cannot stop a session that belongs to someone
     * else — that would act on a guess — but it no longer protects an unattributable one.
     */
    private void stopSchedulerIfThisRepositoryLosesIt(String profileId, String repositoryId,
            int leftAnywhere) {
        try {
            if (ingestSchedulerService == null) return;
            if (leftAnywhere == 0) {
                ingestSchedulerService.stopIdle(profileId);
                return;
            }
            boolean running = ingestSchedulerService.getIdleProfiles().contains(profileId);
            if (!running) {
                logger.info("import profile {} still has {} definition row(s) somewhere and no"
                        + " IDLE session is running", profileId,
                        leftAnywhere < 0 ? "an unknown number of" : String.valueOf(leftAnywhere));
                return;
            }
            String servedRepository = ingestSchedulerService.getIdleRepository(profileId);
            if (servedRepository == null || servedRepository.equals(repositoryId)) {
                logger.info("import profile {} still exists elsewhere, but its IDLE session"
                        + " imports into {}; stopping it", profileId,
                        servedRepository == null ? "a repository that cannot be established"
                                : servedRepository);
                ingestSchedulerService.stopIdle(profileId);
            } else {
                logger.info("import profile {} still has {} definition row(s) somewhere and its"
                        + " IDLE session imports into {}; leaving it alone", profileId,
                        leftAnywhere < 0 ? "an unknown number of" : String.valueOf(leftAnywhere),
                        servedRepository);
            }
        } catch (RuntimeException idleShutdownFailed) {
            // The rows are ALREADY deleted. Letting this escape would drop the audit entry
            // for a deletion that happened and answer 500 — the caller would read it as "not
            // deleted" and the security trail would have no record.
            logger.warn("Import profile {} was deleted, but stopping its IMAP IDLE thread"
                    + " failed: {}", profileId, idleShutdownFailed.getMessage());
        }
    }

    /**
     * The row-addressed twin resolver, on its own path. It must not depend on which twin a
     * selector returns: the caller's repository travels with the request and the service
     * validates the ADDRESSED row against it.
     */
    private ResponseEntity<Map<String, Object>> deleteOneRow(CallContext ctx, String profileId,
            String docId, boolean admin) {
            // The divergent-twin resolver: the plain delete removes every row of the caller's repository for the
            // profile, so the migration's "delete the row you do not want" needs an
            // id-addressed operation. The profile SURVIVES this call (one twin goes), so
            // the scheduler is not stopped and no deletion event is emitted; and the
            // caller's repository travels with the request so the addressed row itself is
            // authorised — the ownership check above ran on whichever twin the selector
            // returned first.
            //
            // Administrators only. The delegated checks above (delegated flag, cmis:all on
            // the target folder) also ran on the selector's twin, not on the addressed
            // row — so a delegated user managing twin A could delete an admin-managed
            // twin B. Resolving divergent rows is a migration follow-up, not a
            // self-service operation; a round-2 review named the gap.
            if (!admin) {
                // Audited like every other denial on this controller. The attempt that this
                // round's fix made impossible (one repository's administrator reaching
                // another's row) is exactly the one a security trail has to carry; a review
                // found this branch silent.
                auditRow(AuditOperation.EXTERNAL_PROFILE_DELETED, ctx, profileId,
                        authRepository(ctx), false,
                        "row-addressed deletion is an administrator operation",
                        DenialReason.ADMIN_OWNED_PROFILE, docId);
                return errorResponse(HttpStatus.FORBIDDEN,
                        "resolving divergent definition rows is an administrator operation");
            }
            int remaining;
            try {
                remaining = importProfileDefinitionService.delete(profileId, docId,
                        authRepository(ctx));
            } catch (ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException e) {
                // The count that decides "is this a pair?" could not read a row. That is
                // "could not ask", and every other caller of the same count answers 503 with
                // an audit entry; this path used to let it escape as an unclassified 500.
                auditRow(AuditOperation.EXTERNAL_PROFILE_DELETED, ctx, profileId,
                        authRepository(ctx), false, e.getMessage(), null, docId);
                return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
            } catch (ImportProfileDefinitionServiceImpl.ProfileHasNoTwinException e) {
                // The row is there and the address is right; there is simply no pair to
                // resolve. 409, not the 404 below — that would say the row does not exist.
                auditRow(AuditOperation.EXTERNAL_PROFILE_DELETED, ctx, profileId,
                        authRepository(ctx), false, e.getMessage(), null, docId);
                return errorResponse(HttpStatus.CONFLICT, e.getMessage());
            } catch (IllegalArgumentException e) {
                // Uniformly "not found", like every other cross-repository refusal here:
                // a row in another repository must not be distinguishable from no row. The
                // ATTEMPT is still audited — the trail must show who tried to remove which
                // row, whatever the answer was.
                auditRow(AuditOperation.EXTERNAL_PROFILE_DELETED, ctx, profileId,
                        authRepository(ctx), false,
                        "the addressed row is not a row of this profile in this repository",
                        null, docId);
                return errorResponse(HttpStatus.NOT_FOUND, "Profile row not found");
            }
            // The row IS destroyed and the surviving twin's content becomes the effective
            // configuration — a change of settings with no trace, since this path emitted no
            // audit entry at all. Every other state-changing verb on this controller audits;
            // a review found this one silent. (The deletion EVENT and the scheduler stop stay
            // out: the profile itself is still there. That part is deliberate and recorded.)
            if (remaining < 0) {
                // The count after the delete could not answer. That is not "a row survives",
                // which is the branch this used to fall into — the batch's own defect, on
                // the far side of a destructive write. The scheduler is NOT stopped: doing it
                // for a profile that may still exist silently disables live mail capture,
                // which is the worse of the two mistakes. Say what is unknown instead.
                logger.error("row {} of import profile {} was deleted, but whether any"
                        + " definition row remains could not be established", docId, profileId);
                auditRow(AuditOperation.EXTERNAL_PROFILE_DELETED, ctx, profileId,
                        authRepository(ctx), true, "the row was deleted; the survivors could"
                                + " not be counted", null, docId,
                        "unknown: the surviving rows could not be counted");
                Map<String, Object> unknown = new LinkedHashMap<>();
                unknown.put("status", "success");
                unknown.put("deletedRow", docId);
                unknown.put("warning", "the row was deleted, but whether the profile still"
                        + " has a definition row could not be established");
                return ResponseEntity.ok(unknown);
            }
            if (remaining == 0) {
                // The count before the delete said "a pair", and none is left: another
                // administrator removed the other row concurrently. The profile is GONE, and
                // this path deliberately skips the scheduler stop and the deletion record
                // because it assumes a survivor — so finish that work here rather than leave
                // a deleted profile with a live IMAP thread and no deletion record.
                logger.warn("import profile {} lost its last definition row to a concurrent"
                        + " row-addressed delete; stopping its scheduler and recording the"
                        + " deletion", profileId);
                try {
                    if (ingestSchedulerService != null) ingestSchedulerService.stopIdle(profileId);
                } catch (RuntimeException idleShutdownFailed) {
                    logger.warn("stopping the IMAP IDLE thread of {} failed: {}", profileId,
                            idleShutdownFailed.getMessage());
                }
                // Not necessarily a race: a row that belongs to NO repository is exempt
                // from the "this is the only row" refusal (nothing else reaches it), so an
                // ordinary cleanup lands here too. The wording said "lost a race" for both.
                auditRow(AuditOperation.EXTERNAL_PROFILE_DELETED, ctx, profileId,
                        authRepository(ctx), true, "no definition row remains after removing"
                                + " this one; the profile is deleted", null, docId,
                        "profile deleted: no definition row remains");
                Map<String, Object> raced = new LinkedHashMap<>();
                raced.put("status", "success");
                raced.put("deletedRow", docId);
                raced.put("warning", "no definition row remains; the profile is deleted");
                return ResponseEntity.ok(raced);
            }
            auditRow(AuditOperation.EXTERNAL_PROFILE_DELETED, ctx, profileId,
                    authRepository(ctx), true, "one row of a divergent pair was removed",
                    null, docId);
            Map<String, Object> resolved = new LinkedHashMap<>();
            resolved.put("status", "success");
            resolved.put("deletedRow", docId);
            return ResponseEntity.ok(resolved);

    }

    /**
     * The row this repository owns, when the selector handed back another repository's twin.
     * {@code get} selects on profileId alone; with the same id in two repositories the caller
     * was refused 404 for a profile they own — the DELETE path was fixed for this and the
     * read verbs were left behind. Returns null when this repository really has none.
     */
    private ImportProfileDefinition mineInstead(CallContext ctx, ImportProfileDefinition selected,
            String profileId) {
        // The selector's row when it is this repository's, otherwise the index-free read.
        //
        // Making this unconditional was tried and withdrawn: it puts a full walk of the config
        // database on every administrator request, and a review traced some thirty tests —
        // including two classes outside this batch — to a red result, because their fixtures
        // answer the selector and not the walk. (That count came from reading, not from
        // running the variant; the batch itself has since been measured.) What it was reaching for is real but narrow: the selector cannot
        // tell ONE row from a PAIR, and a delegated DELETE authorised from one twin removes
        // every row of the repository, including the other. That path resolves index-free
        // unconditionally (see delete); the reads and the writes are covered by the write
        // side, which counts rows without the index and refuses a pair with 409.
        if (selected != null && belongsToAuthRepository(ctx, selected)) {
            return selected;
        }
        return importProfileDefinitionService.getForRepository(profileId, authRepository(ctx));
    }

    /**
     * Resolves this repository's row and turns the two refusals into responses: a pair is
     * 409 (resolve it first), an unreadable row is 503. Returns null when the row really is
     * absent — established index-free, so the caller answers 404 WITHOUT a second walk. The
     * first version asked {@code hiddenOrAbsent} after this, which walked the whole database
     * again and could turn a settled absence into a 503; a review found the double walk.
     */
    private ResponseEntity<Map<String, Object>> resolveMine(CallContext ctx, String profileId,
            ImportProfileDefinition selected, java.util.function.Consumer<ImportProfileDefinition> sink) {
        try {
            sink.accept(mineInstead(ctx, selected, profileId));
        } catch (ImportProfileDefinitionServiceImpl.ProfileHasTwinRowsException pair) {
            return errorResponse(HttpStatus.CONFLICT, pair.getMessage());
        } catch (ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException e) {
            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
        return null;
    }

    // hiddenOrAbsent(...) lived here: a second index-free walk that asked "does a row
    // exist?" after a read had already answered. Every verb now resolves this repository's
    // row once — that read refuses when it cannot see (503) and answers null only when the
    // row is genuinely absent (404) — so the second walk was double cost and could turn a
    // settled absence into a 503 when only it failed. A review found the pair.


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

    /**
     * The audit of a ROW-addressed operation. Deliberately not {@link #auditWithReason}: that
     * one describes a PROFILE (delegated, target folder, connector ids), and this call
     * establishes none of them — it addresses one row of a divergent pair, and the row that
     * SURVIVES may differ in every one of those fields. A review found a synthetic definition
     * emitting {@code delegated=false} as though it were a fact, and the row id riding in the
     * errorMessage, which the logger drops on success.
     */
    private void auditRow(AuditOperation op, CallContext ctx, String profileId,
                          String repositoryId, boolean success, String message,
                          DenialReason reason, String docId) {
        auditRow(op, ctx, profileId, repositoryId, success, message, reason, docId, null);
    }

    private void auditRow(AuditOperation op, CallContext ctx, String profileId,
                          String repositoryId, boolean success, String message,
                          DenialReason reason, String docId, String outcome) {
        if (ctx == null) return;
        String actor = ctx.getUsername() != null ? ctx.getUsername() : "anonymous";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("actorUserId", actor);
        details.put("definitionRowId", docId);
        // In DETAILS, not in the message: the message becomes errorMessage, and the logger
        // drops that on a SUCCESS. The javadoc above records finding that trap once; the
        // race repair then put "the profile is deleted" into the same dropped field, so the
        // audit of a deletion was indistinguishable from resolving one row of a pair. A
        // review caught the second occurrence.
        if (outcome != null) details.put("outcome", outcome);
        if (reason != null) details.put("denialReason", reason.name());
        jp.aegif.nemaki.audit.AuditEmitSupport.safeEmit(auditLogger, op, repositoryId, actor,
                profileId != null ? profileId : "", success, message, details);
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
