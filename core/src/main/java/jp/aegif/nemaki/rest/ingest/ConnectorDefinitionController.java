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
