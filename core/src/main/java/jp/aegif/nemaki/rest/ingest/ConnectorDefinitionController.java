package jp.aegif.nemaki.rest.ingest;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.util.constant.CallContextKey;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/admin/connectors")
public class ConnectorDefinitionController {

    @Autowired
    private ConnectorDefinitionService connectorDefinitionService;

    @Autowired
    private IngestAuthorizationService ingestAuthorizationService;

    /**
     * V2 (RC5 ext): used by the governance view to classify
     * {@code principalId} as {@code USER}, {@code GROUP}, or
     * {@code UNKNOWN}. Resolved {@code required=false} so the rest of the
     * controller still works when the bean is absent (e.g. in
     * unit-test setups that don't wire a PrincipalService); the
     * governance endpoint just reports {@code UNKNOWN} in that case.
     */
    @Autowired(required = false)
    private jp.aegif.nemaki.businesslogic.PrincipalService principalService;

    /**
     * W2 (RC5.3): audit sink for governance simulate-remove. Optional
     * so unit tests can run without wiring; null check in auditSimulate
     * makes that a graceful skip.
     */
    @Autowired(required = false)
    private jp.aegif.nemaki.audit.AuditLogger auditLogger;

    @Autowired
    private HttpServletRequest httpRequest;

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody ConnectorDefinition def) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdmin();
        if (forbidden != null) return forbidden;
        try {
            ConnectorDefinition created = connectorDefinitionService.create(def);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("connectorId", created.getConnectorId());
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return errorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping
    public ResponseEntity<List<ConnectorDefinition>> list(
            @RequestParam(required = false) String archetype) {
        ResponseEntity<List<ConnectorDefinition>> forbidden = requireAdminList();
        if (forbidden != null) return forbidden;
        if (archetype != null && !archetype.isBlank()) {
            try {
                return ResponseEntity.ok(connectorDefinitionService.listByArchetype(
                        SourceArchetype.valueOf(archetype.toUpperCase())).stream()
                        .map(ConnectorDefinitionController::maskSecrets).toList());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        }
        return ResponseEntity.ok(connectorDefinitionService.list().stream()
                .map(ConnectorDefinitionController::maskSecrets).toList());
    }

    @GetMapping("/{connectorId}")
    public ResponseEntity<ConnectorDefinition> get(@PathVariable String connectorId) {
        if (!isAdmin()) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        ConnectorDefinition def = connectorDefinitionService.get(connectorId);
        if (def == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(maskSecrets(def));
    }

    @PutMapping("/{connectorId}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable String connectorId, @RequestBody ConnectorDefinition def) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdmin();
        if (forbidden != null) return forbidden;
        def.setConnectorId(connectorId);
        // Preserve real secrets when client sends back the masked placeholder
        ConnectorDefinition existing = connectorDefinitionService.get(connectorId);
        if (existing != null) {
            if ("[configured]".equals(def.getCredentialRef())) {
                def.setCredentialRef(existing.getCredentialRef());
            }
            if ("[configured]".equals(def.getWebhookSecret())) {
                def.setWebhookSecret(existing.getWebhookSecret());
            }
            // Preserve delegation-scope arrays when the payload omits them.
            // Jackson deserialisation collapses an absent key and an explicit
            // null into a null Java field — both signal "I'm not touching
            // this; keep what's stored". An explicit empty list is the
            // operator's "clear it" intent and IS honoured. This stops a
            // scripted partial PUT (e.g. flipping only `enabled`) from
            // accidentally wiping `allowedFolderIds`/`allowedPrincipalIds`
            // and tripping the "delegated=true requires non-empty scope"
            // validation. Lists only — primitive flags have no null state
            // and admins must always send the desired boolean.
            if (def.getAllowedFolderIds() == null) {
                def.setAllowedFolderIds(existing.getAllowedFolderIds());
            }
            if (def.getAllowedPrincipalIds() == null) {
                def.setAllowedPrincipalIds(existing.getAllowedPrincipalIds());
            }
        }
        try {
            connectorDefinitionService.update(def);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return errorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @DeleteMapping("/{connectorId}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String connectorId) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdmin();
        if (forbidden != null) return forbidden;
        connectorDefinitionService.delete(connectorId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        return ResponseEntity.ok(response);
    }

    private boolean isAdmin() {
        if (httpRequest == null) return false;
        CallContext ctx = (CallContext) httpRequest.getAttribute("CallContext");
        if (ctx == null) return false;
        Boolean admin = (Boolean) ctx.get(CallContextKey.IS_ADMIN);
        return admin != null && admin;
    }

    private ResponseEntity<Map<String, Object>> requireAdmin() {
        if (!isAdmin()) return errorResponse(HttpStatus.FORBIDDEN, "Admin access required");
        return null;
    }

    @SuppressWarnings("unchecked")
    private <T> ResponseEntity<T> requireAdminList() {
        if (!isAdmin()) return (ResponseEntity<T>) ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        return null;
    }

    /** Mask sensitive fields before returning to client (creates a shallow copy to avoid mutating cache). */
    private static ConnectorDefinition maskSecrets(ConnectorDefinition src) {
        ConnectorDefinition copy = new ConnectorDefinition();
        copy.setConnectorId(src.getConnectorId());
        copy.setDisplayName(src.getDisplayName());
        copy.setSourceArchetype(src.getSourceArchetype());
        copy.setSourceSystem(src.getSourceSystem());
        copy.setAuthType(src.getAuthType());
        copy.setEndpoint(src.getEndpoint());
        copy.setTenantId(src.getTenantId());
        copy.setAdapterKind(src.getAdapterKind());
        copy.setRateLimitRpm(src.getRateLimitRpm());
        copy.setEnabled(src.isEnabled());
        copy.setDelegated(src.isDelegated());
        copy.setDelegateAllFolders(src.isDelegateAllFolders());
        copy.setAllowedFolderIds(src.getAllowedFolderIds());
        copy.setAllowedPrincipalIds(src.getAllowedPrincipalIds());
        copy.setCreatedAt(src.getCreatedAt());
        copy.setUpdatedAt(src.getUpdatedAt());
        // Mask secrets: show "[configured]" instead of actual value
        copy.setCredentialRef(src.getCredentialRef() != null && !src.getCredentialRef().isBlank()
                ? "[configured]" : null);
        copy.setWebhookSecret(src.getWebhookSecret() != null && !src.getWebhookSecret().isBlank()
                ? "[configured]" : null);
        return copy;
    }

    /**
     * Non-admin discovery endpoint: lists connectors that are delegated to
     * the caller for {@code targetFolderId}. Returns a slim summary without
     * any secret material, endpoint, or scope metadata. The caller must
     * already hold {@code cmis:all} on {@code targetFolderId}, or admin.
     *
     * <p>Use case: the delegated profile editor needs to populate the
     * connector picker without exposing the full admin connector view.
     */
    @GetMapping("/summary")
    public ResponseEntity<?> listSummary(
            @RequestParam String repositoryId,
            @RequestParam String targetFolderId) {
        CallContext ctx = currentCallContext();
        if (ctx == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (repositoryId == null || repositoryId.isBlank()
                || targetFolderId == null || targetFolderId.isBlank()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "repositoryId and targetFolderId are required");
        }
        if (!ingestAuthorizationService.canManageProfileForFolder(ctx, repositoryId, targetFolderId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        var visible = new java.util.ArrayList<Map<String, Object>>();
        for (ConnectorDefinition c : connectorDefinitionService.list()) {
            if (!ingestAuthorizationService.canUseConnectorForDelegatedProfile(ctx, repositoryId, c, targetFolderId)) {
                continue;
            }
            var entry = new LinkedHashMap<String, Object>();
            entry.put("connectorId", c.getConnectorId());
            entry.put("displayName", c.getDisplayName());
            entry.put("sourceArchetype", c.getSourceArchetype() != null ? c.getSourceArchetype().name() : null);
            entry.put("sourceSystem", c.getSourceSystem());
            entry.put("adapterKind", c.getAdapterKind());
            visible.add(entry);
        }
        return ResponseEntity.ok(visible);
    }

    private CallContext currentCallContext() {
        if (httpRequest == null) return null;
        return (CallContext) httpRequest.getAttribute("CallContext");
    }

    /**
     * RC5 (v2 §12.3): governance view — "which connectors does this
     * principal have access to?"
     *
     * <p>Admin-only. For a given {@code principalId} (user or group),
     * returns the list of delegated connectors whose
     * {@code allowedPrincipalIds} contains the principal — either
     * directly, or (when {@code expand=true}) via group expansion of a
     * user principal through {@link IngestAuthorizationService#expandPrincipals}.
     *
     * <p>This is the operator answer to "who can use what?". Without it,
     * removing a user from a group leaves you without a way to audit
     * what they will lose access to. The endpoint returns a slim summary
     * including which principal IDs caused the match, so the operator
     * can distinguish direct grants (likely intentional) from group-
     * derived grants (likely indirect / candidate for cleanup).
     *
     * <p>Query params:
     * <ul>
     *   <li>{@code repositoryId} — required for group expansion when
     *       {@code expand=true}; also scopes the listing to connectors
     *       whose principal-set could reach this repository.</li>
     *   <li>{@code expand} — {@code true} to include group-derived
     *       matches; default {@code false} returns only direct
     *       {@code allowedPrincipalIds} hits.</li>
     * </ul>
     *
     * <p>Note: connector records are repository-agnostic; the
     * {@code repositoryId} is required only because group expansion is
     * a per-repository operation (a user can belong to different group
     * sets in different repositories).
     */
    @GetMapping("/by-principal/{principalId}")
    public ResponseEntity<?> listByPrincipal(
            @PathVariable String principalId,
            @RequestParam String repositoryId,
            @RequestParam(required = false, defaultValue = "false") boolean expand) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdmin();
        if (forbidden != null) return forbidden;
        if (principalId == null || principalId.isBlank()
                || repositoryId == null || repositoryId.isBlank()) {
            return errorResponse(HttpStatus.BAD_REQUEST,
                    "principalId and repositoryId are required");
        }

        // V2 (RC5 ext): classify the input principal so the operator
        // sees explicit context (querying a user vs a group). Falls back
        // to UNKNOWN when PrincipalService isn't wired or the principal
        // doesn't resolve — never blocks the lookup.
        String principalType = resolvePrincipalType(repositoryId, principalId);

        // Build the set of principal IDs to test against each connector's
        // allowedPrincipalIds. expand=false → just the principal itself
        // (lets operators view group ACEs as well as user ACEs). expand=true
        // → for users, include the groups they belong to; for groups, the
        // expansion is a no-op (groups don't contain groups in NemakiWare).
        java.util.Set<String> principalsToMatch = new java.util.LinkedHashSet<>();
        principalsToMatch.add(principalId);
        if (expand) {
            // expandPrincipals is fail-closed on lookup error — caller sees
            // the direct-only view, which is the safer governance default.
            // For GROUP principals the expansion is conceptually a no-op
            // (NemakiWare's group model doesn't nest), so we skip the
            // call to avoid PrincipalService impl-dependent surprises.
            if (!"GROUP".equals(principalType)) {
                principalsToMatch.addAll(
                        ingestAuthorizationService.expandPrincipals(repositoryId, principalId));
            }
        }

        var matches = buildMatches(principalId, principalsToMatch);

        var body = new LinkedHashMap<String, Object>();
        body.put("principalId", principalId);
        body.put("principalType", principalType);
        body.put("repositoryId", repositoryId);
        body.put("expand", expand);
        body.put("expandedPrincipals", new java.util.ArrayList<>(principalsToMatch));
        body.put("matches", matches);
        return ResponseEntity.ok(body);
    }

    /**
     * W2 (RC5.3): server-side simulate-remove. Body specifies the
     * principal-set to remove from the expansion. Returns
     * {@code lost} (matches where every {@code matchedPrincipalIds}
     * entry is in the removal set — sole-route detection) plus
     * {@code kept} (matches that survive). Same logic as the V5/V7
     * client-computed simulator, but invokable from CLI / scripts and
     * usable by the UI when the result set is large.
     *
     * <p>Admin only. Audited as
     * {@link AuditOperation#EXTERNAL_GOVERNANCE_SIMULATE} with the
     * queried principal, removal set, and lost count for post-hoc
     * analysis.
     *
     * <p>Body shape:
     * <pre>
     * {
     *   "repositoryId": "bedroom",
     *   "expand": true,
     *   "removePrincipalIds": ["group-a", "group-b"]
     * }
     * </pre>
     */
    @PostMapping("/by-principal/{principalId}/simulate-remove")
    public ResponseEntity<?> simulateRemove(
            @PathVariable String principalId,
            @RequestBody Map<String, Object> body) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdmin();
        if (forbidden != null) return forbidden;
        if (principalId == null || principalId.isBlank()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "principalId is required");
        }
        String repositoryId = (String) body.get("repositoryId");
        if (repositoryId == null || repositoryId.isBlank()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "repositoryId is required");
        }
        boolean expand = Boolean.TRUE.equals(body.get("expand"));
        Object removeRaw = body.get("removePrincipalIds");
        if (!(removeRaw instanceof List<?>)) {
            return errorResponse(HttpStatus.BAD_REQUEST,
                    "removePrincipalIds must be an array");
        }
        List<?> removeList = (List<?>) removeRaw;
        java.util.Set<String> removalSet = new java.util.LinkedHashSet<>();
        for (Object o : removeList) {
            if (o instanceof String s && !s.isBlank()) removalSet.add(s);
        }
        if (removalSet.isEmpty()) {
            return errorResponse(HttpStatus.BAD_REQUEST,
                    "removePrincipalIds must contain at least one non-blank entry");
        }

        // Build the same principal set the V3 governance view uses, so
        // simulate-remove results align exactly with what the admin would
        // see in the matches table before deciding to simulate.
        String principalType = resolvePrincipalType(repositoryId, principalId);
        java.util.Set<String> principalsToMatch = new java.util.LinkedHashSet<>();
        principalsToMatch.add(principalId);
        if (expand && !"GROUP".equals(principalType)) {
            principalsToMatch.addAll(
                    ingestAuthorizationService.expandPrincipals(repositoryId, principalId));
        }

        List<Map<String, Object>> allMatches = buildMatches(principalId, principalsToMatch);
        List<Map<String, Object>> lost = new java.util.ArrayList<>();
        List<Map<String, Object>> kept = new java.util.ArrayList<>();
        for (Map<String, Object> m : allMatches) {
            @SuppressWarnings("unchecked")
            List<String> matched = (List<String>) m.get("matchedPrincipalIds");
            if (matched == null || matched.isEmpty()) {
                kept.add(m);
                continue;
            }
            boolean allInRemoval = matched.stream().allMatch(removalSet::contains);
            if (allInRemoval) lost.add(m); else kept.add(m);
        }

        // Audit the invocation. SOC tooling can correlate
        // EXTERNAL_GOVERNANCE_SIMULATE entries with subsequent group /
        // ACL changes to spot "asked, then acted" patterns.
        auditSimulate(principalId, repositoryId, principalsToMatch, removalSet, lost.size());

        var resp = new LinkedHashMap<String, Object>();
        resp.put("principalId", principalId);
        resp.put("principalType", principalType);
        resp.put("repositoryId", repositoryId);
        resp.put("expand", expand);
        resp.put("expandedPrincipals", new java.util.ArrayList<>(principalsToMatch));
        resp.put("removePrincipalIds", new java.util.ArrayList<>(removalSet));
        resp.put("lost", lost);
        resp.put("kept", kept);
        return ResponseEntity.ok(resp);
    }

    /**
     * Shared connector-match builder used by both V3 (listByPrincipal)
     * and W2 (simulateRemove). Returns the same shape so the two
     * endpoints stay byte-identical for any given principal set.
     */
    private List<Map<String, Object>> buildMatches(
            String principalId, java.util.Set<String> principalsToMatch) {
        List<Map<String, Object>> matches = new java.util.ArrayList<>();
        for (ConnectorDefinition c : connectorDefinitionService.list()) {
            List<String> allowed = c.getAllowedPrincipalIds();
            if (allowed == null || allowed.isEmpty()) continue;
            // Intersect — preserves the matched principal IDs so the
            // operator can see WHY this connector is visible to the
            // principal (direct vs. via group X).
            java.util.List<String> matched = new java.util.ArrayList<>();
            for (String p : allowed) {
                if (p != null && principalsToMatch.contains(p)) matched.add(p);
            }
            if (matched.isEmpty()) continue;

            var entry = new LinkedHashMap<String, Object>();
            entry.put("connectorId", c.getConnectorId());
            entry.put("displayName", c.getDisplayName());
            entry.put("sourceArchetype",
                    c.getSourceArchetype() != null ? c.getSourceArchetype().name() : null);
            entry.put("sourceSystem", c.getSourceSystem());
            entry.put("adapterKind", c.getAdapterKind());
            entry.put("delegated", c.isDelegated());
            entry.put("enabled", c.isEnabled());
            entry.put("matchedPrincipalIds", matched);
            // matchType: "direct" iff the principal itself is the only
            // matched entry; "group" if at least one matched id is NOT
            // the principal (i.e. group-derived). Mixed grants surface
            // as "direct+group" so operators can investigate.
            boolean direct = matched.contains(principalId);
            boolean groupDerived = matched.stream().anyMatch(m -> !principalId.equals(m));
            entry.put("matchType", direct && groupDerived ? "direct+group"
                    : direct ? "direct" : "group");
            matches.add(entry);
        }
        return matches;
    }

    /**
     * W2 (RC5.3): audit a simulate-remove invocation. Records the
     * queried principal, the principals an admin asked to simulate
     * removing, and the lost count so SOC can correlate "what-if"
     * questions with subsequent group / ACL changes. Audit failures
     * are swallowed — the simulator must not be blocked by audit
     * pipeline hiccups.
     */
    private void auditSimulate(String principalId, String repositoryId,
                               java.util.Set<String> expandedPrincipals,
                               java.util.Set<String> removePrincipalIds, int lostCount) {
        if (auditLogger == null) return;
        try {
            CallContext ctx = currentCallContext();
            String actor = ctx != null && ctx.getUsername() != null ? ctx.getUsername() : "admin";
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("actorUserId", actor);
            details.put("principalId", principalId);
            details.put("expandedPrincipals", new java.util.ArrayList<>(expandedPrincipals));
            details.put("removePrincipalIds", new java.util.ArrayList<>(removePrincipalIds));
            details.put("lostCount", lostCount);
            auditLogger.logOperation(
                    jp.aegif.nemaki.audit.AuditOperation.EXTERNAL_GOVERNANCE_SIMULATE,
                    repositoryId, actor, principalId, true, null, details);
        } catch (RuntimeException ignored) {
            // Audit must not break the API path
        }
    }

    /**
     * V2 (RC5 ext): classify {@code principalId} as {@code USER},
     * {@code GROUP}, or {@code UNKNOWN} using the wired
     * {@link jp.aegif.nemaki.businesslogic.PrincipalService}. UNKNOWN
     * is the safe fallback when the bean is missing, the lookup
     * throws, or the principal isn't a user AND isn't a group (e.g.
     * pseudo-principals like Anyone / Authenticated, or a typo). Never
     * fails the request — governance lookups are read-only and best
     * served partial-but-honest over noisy.
     */
    private String resolvePrincipalType(String repositoryId, String principalId) {
        if (principalService == null) return "UNKNOWN";
        try {
            if (principalService.getUserById(repositoryId, principalId) != null) return "USER";
        } catch (RuntimeException ignored) {
            // fall through to GROUP check
        }
        try {
            if (principalService.getGroupById(repositoryId, principalId) != null) return "GROUP";
        } catch (RuntimeException ignored) {
            // fall through to UNKNOWN
        }
        return "UNKNOWN";
    }

    /**
     * Return the adapter registry — all supported source systems with their
     * required/optional params, archetype, and webhook scope keys.
     * Used by the UI for dynamic form generation and Help documentation.
     */
    @GetMapping("/adapter-registry")
    public ResponseEntity<?> getAdapterRegistry() {
        ResponseEntity<Map<String, Object>> forbidden = requireAdmin();
        if (forbidden != null) return forbidden;
        var result = new java.util.ArrayList<Map<String, Object>>();
        for (AdapterDescriptor desc : AdapterRegistry.all()) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("sourceSystem", desc.sourceSystem());
            entry.put("displayName", desc.displayName());
            entry.put("archetype", desc.archetype().name());
            entry.put("requiredParams", desc.requiredParams());
            entry.put("optionalParams", desc.optionalParams());
            entry.put("webhookScopeKeys", desc.webhookScopeKeys());
            entry.put("apiCallsPerItem", desc.apiCallsPerItem());
            entry.put("paramsExample", desc.paramsExample());
            result.add(entry);
        }
        return ResponseEntity.ok(result);
    }

    private ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "error");
        response.put("message", message);
        return ResponseEntity.status(status).body(response);
    }
}
