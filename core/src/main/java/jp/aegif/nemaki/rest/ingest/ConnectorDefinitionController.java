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
     * RC6 B3-2: group-membership governance view — "what does deleting
     * this group cost?".
     *
     * <p>Admin-only. For a given {@code groupId}, returns:
     * <ul>
     *   <li>{@code memberUserIds} — direct member users of the group,
     *       capped at {@code memberLimit} entries (review M). The full
     *       count is preserved in {@code memberCount} and
     *       {@code memberUserIdsTruncated=true} signals truncation.</li>
     *   <li>{@code subGroupIds} — direct sub-groups, if any (NemakiWare
     *       groups don't nest in practice, but the model exposes the
     *       field, so we surface it verbatim).</li>
     *   <li>{@code directGrants} — connectors whose
     *       {@code allowedPrincipalIds} contains {@code groupId} directly
     *       (same shape as {@code /by-principal/{groupId}} matches).</li>
     *   <li>{@code perMemberImpact} — for each member user (within the
     *       same {@code memberLimit} cap), the set of connectors they
     *       would lose if {@code groupId} were removed from their
     *       effective principal set (sole-route detection over this
     *       group only). Always present (review L). Empty array when
     *       {@code includeMembers=false} — the fast path that skips per-
     *       member expansion entirely.</li>
     *   <li>{@code perMemberImpactTruncated} — {@code true} iff
     *       {@code includeMembers=true} AND the member list was capped.
     *       {@code false} when {@code includeMembers=false} (which is
     *       "we didn't attempt expansion", not "we truncated it").</li>
     * </ul>
     *
     * <p>The existing {@code /by-principal/{groupId}} endpoint answers
     * "what connectors does this group ID grant?" but stops there. This
     * endpoint adds the membership lens — operators can answer
     * "deleting this group would impact N users; here's what each loses"
     * without N round-trips to {@code /by-principal/{userId}} per member.
     *
     * <p>If {@code groupId} doesn't resolve to a Group, the response
     * still returns 200 with {@code groupType=UNKNOWN} and empty member
     * lists — governance views are read-only and best served partial-but-
     * honest. The directGrants section uses {@code allowedPrincipalIds}
     * lookup which works regardless of whether the principal is actually
     * a group, so admins can probe arbitrary IDs.
     *
     * <p>Query params:
     * <ul>
     *   <li>{@code repositoryId} (required) — group lookup is per-repo
     *       because users / groups are per-repo in NemakiWare.</li>
     *   <li>{@code includeMembers} (default {@code true}) — when false,
     *       skips per-member impact computation (cheap fast-path for
     *       UI that only needs counts).</li>
     *   <li>{@code memberLimit} (default {@code 200}, clamped to
     *       {@link #MAX_MEMBER_LIMIT}) — caps {@code memberUserIds} AND
     *       the {@code perMemberImpact} computation in lock-step. The
     *       UI can paginate by issuing follow-up queries with a higher
     *       limit if it really needs more, but the server will never
     *       return more than {@code MAX_MEMBER_LIMIT} entries in one
     *       call.</li>
     * </ul>
     */
    @GetMapping("/by-group/{groupId}")
    public ResponseEntity<?> listByGroup(
            @PathVariable String groupId,
            @RequestParam String repositoryId,
            @RequestParam(required = false, defaultValue = "true") boolean includeMembers,
            @RequestParam(required = false, defaultValue = "200") int memberLimit) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdmin();
        if (forbidden != null) return forbidden;
        if (groupId == null || groupId.isBlank()
                || repositoryId == null || repositoryId.isBlank()) {
            return errorResponse(HttpStatus.BAD_REQUEST,
                    "groupId and repositoryId are required");
        }
        // RC6 B3-2 review M: clamp memberLimit to a hard server-side max
        // so an attacker can't request memberLimit=1_000_000 and force
        // the controller to allocate a per-member impact list of that
        // size. Default is 200 when the caller omits / negates the value.
        if (memberLimit < 1) memberLimit = 200;
        if (memberLimit > MAX_MEMBER_LIMIT) memberLimit = MAX_MEMBER_LIMIT;

        // Resolve the group (UNKNOWN if PrincipalService isn't wired or
        // the ID isn't actually a group). Never blocks the lookup.
        jp.aegif.nemaki.model.Group group = null;
        String groupType = "UNKNOWN";
        if (principalService != null) {
            try {
                group = principalService.getGroupById(repositoryId, groupId);
                if (group != null) groupType = "GROUP";
            } catch (RuntimeException ignored) {
                // group remains null, type stays UNKNOWN
            }
        }

        java.util.List<String> allMemberUserIds = group != null && group.getUsers() != null
                ? new java.util.ArrayList<>(group.getUsers())
                : java.util.Collections.emptyList();
        java.util.List<String> subGroupIds = group != null && group.getGroups() != null
                ? new java.util.ArrayList<>(group.getGroups())
                : java.util.Collections.emptyList();

        // RC6 B3-2 review M: cap memberUserIds itself by memberLimit
        // (not just perMemberImpact) so the response stays bounded for
        // very large groups. memberCount preserves the untruncated size
        // so the UI can show "N members, showing M".
        int memberCap = Math.min(memberLimit, allMemberUserIds.size());
        java.util.List<String> memberUserIds = memberCap < allMemberUserIds.size()
                ? new java.util.ArrayList<>(allMemberUserIds.subList(0, memberCap))
                : allMemberUserIds;
        boolean memberUserIdsTruncated = memberCap < allMemberUserIds.size();

        // Direct grants: same logic as /by-principal/{groupId} with
        // expand=false. We don't expand for groups (NemakiWare groups
        // don't nest; the by-principal endpoint already documents this).
        java.util.Set<String> directSet = new java.util.LinkedHashSet<>();
        directSet.add(groupId);
        List<Map<String, Object>> directGrants = buildMatches(groupId, directSet);

        // RC6 B3-2 review L: compute perMemberImpact (and its companion
        // flags) unconditionally so the response shape is stable for the
        // UI. includeMembers=false returns an empty array — no per-member
        // expansion is done — which is the documented fast path.
        List<Map<String, Object>> impact = new java.util.ArrayList<>();
        if (includeMembers) {
            for (String memberId : memberUserIds) {
                // For each member, compute their effective principal set
                // expanded across ALL their group memberships, then ask:
                // "what would they lose if groupId were removed?" — a
                // sole-route detection over the {groupId} removal set.
                java.util.Set<String> memberPrincipals = new java.util.LinkedHashSet<>();
                memberPrincipals.add(memberId);
                try {
                    memberPrincipals.addAll(
                            ingestAuthorizationService.expandPrincipals(repositoryId, memberId));
                } catch (RuntimeException ignored) {
                    // expansion fail-closed: caller still sees direct-only
                }
                List<Map<String, Object>> memberMatches =
                        buildMatches(memberId, memberPrincipals);
                List<Map<String, Object>> lost = new java.util.ArrayList<>();
                for (Map<String, Object> m : memberMatches) {
                    @SuppressWarnings("unchecked")
                    List<String> matched = (List<String>) m.get("matchedPrincipalIds");
                    if (matched == null || matched.isEmpty()) continue;
                    // "lost via groupId" iff every matched principal equals
                    // groupId — i.e., this connector is reachable ONLY
                    // through the queried group for this member.
                    boolean allViaThisGroup = matched.stream()
                            .allMatch(p -> groupId.equals(p));
                    if (allViaThisGroup) lost.add(m);
                }
                var memberEntry = new LinkedHashMap<String, Object>();
                memberEntry.put("userId", memberId);
                memberEntry.put("lostIfGroupRemoved", lost);
                impact.add(memberEntry);
            }
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("groupId", groupId);
        body.put("groupType", groupType);
        body.put("groupName", group != null ? group.getName() : null);
        body.put("repositoryId", repositoryId);
        body.put("memberCount", allMemberUserIds.size());
        body.put("subGroupCount", subGroupIds.size());
        body.put("memberUserIds", memberUserIds);
        body.put("memberUserIdsTruncated", memberUserIdsTruncated);
        body.put("subGroupIds", subGroupIds);
        body.put("memberLimit", memberLimit);
        body.put("directGrants", directGrants);
        body.put("perMemberImpact", impact);
        // perMemberImpactTruncated tracks whether the impact computation
        // saw fewer members than the group actually has. Always present
        // (review L); will be false when includeMembers=false (we didn't
        // attempt the expansion at all in that case, which is not the
        // same as truncation).
        body.put("perMemberImpactTruncated",
                includeMembers && memberUserIdsTruncated);
        return ResponseEntity.ok(body);
    }

    /**
     * RC6 B3-2 review M: server-side hard cap for the {@code memberLimit}
     * query param on {@code /by-group/{id}}. Even an admin can't request
     * a per-member impact list larger than this. Tuned for a single
     * response that still fits comfortably in working memory at JSON
     * encoding time — increase only if real groups exceed it AND the UI
     * is prepared to paginate.
     */
    private static final int MAX_MEMBER_LIMIT = 1000;

    /**
     * RC6 M2: server-side hard cap on the {@code removePrincipalIds}
     * array on {@code /simulate-remove}. Validated BEFORE the per-entry
     * loop allocates, so an oversized payload short-circuits at O(1).
     * The endpoint is admin-only; this cap is defence in depth against
     * a compromised admin token, NOT the primary access control.
     */
    private static final int MAX_REMOVE_PRINCIPAL_IDS = 500;

    /**
     * RC6 M2: maximum byte-length-ish cap for a single
     * {@code removePrincipalIds} entry (counted as Java chars — not
     * UTF-8 bytes — but real principal IDs are ASCII-heavy, so the
     * approximation is close enough). Realistic principal IDs are
     * under 100 chars; >512 is either a bug or an attempt to bloat
     * the audit details map and downstream log sinks.
     */
    private static final int MAX_PRINCIPAL_ID_LENGTH = 512;

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
        // RC6 M2: hard cap the inbound array size BEFORE the per-entry
        // loop allocates. The endpoint is admin-gated, so abuse requires
        // a compromised admin token — but bounding the input still pays
        // off as defence in depth: a 1M-entry removePrincipalIds payload
        // would otherwise allocate a 1M-element LinkedHashSet and
        // iterate every connector's allowedPrincipalIds against it. 500
        // is way past any realistic "what if I remove these groups"
        // simulation (a user belongs to dozens, not hundreds, of groups
        // in any plausible directory).
        if (removeList.size() > MAX_REMOVE_PRINCIPAL_IDS) {
            return errorResponse(HttpStatus.BAD_REQUEST,
                    "removePrincipalIds exceeds maximum size ("
                            + removeList.size() + " > " + MAX_REMOVE_PRINCIPAL_IDS + ")");
        }
        java.util.Set<String> removalSet = new java.util.LinkedHashSet<>();
        for (Object o : removeList) {
            if (o instanceof String s && !s.isBlank()) {
                // RC6 M2: also bound individual entry length. Real
                // principal IDs are typically <100 chars; >512 is
                // either a bug or abuse. We reject the whole request
                // rather than silently dropping the offender so the
                // caller notices.
                if (s.length() > MAX_PRINCIPAL_ID_LENGTH) {
                    return errorResponse(HttpStatus.BAD_REQUEST,
                            "removePrincipalIds entry exceeds maximum length ("
                                    + s.length() + " > " + MAX_PRINCIPAL_ID_LENGTH + ")");
                }
                removalSet.add(s);
            }
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
        CallContext ctx = currentCallContext();
        String actor = ctx != null && ctx.getUsername() != null ? ctx.getUsername() : "admin";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("actorUserId", actor);
        details.put("principalId", principalId);
        details.put("expandedPrincipals", new java.util.ArrayList<>(expandedPrincipals));
        details.put("removePrincipalIds", new java.util.ArrayList<>(removePrincipalIds));
        details.put("lostCount", lostCount);
        // H1 (RC5.5): silent catch replaced by safeEmit (WARN on failure)
        jp.aegif.nemaki.audit.AuditEmitSupport.safeEmit(auditLogger,
                jp.aegif.nemaki.audit.AuditOperation.EXTERNAL_GOVERNANCE_SIMULATE,
                repositoryId, actor, principalId, true, null, details);
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
