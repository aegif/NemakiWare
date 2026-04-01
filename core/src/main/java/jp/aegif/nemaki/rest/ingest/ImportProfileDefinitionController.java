package jp.aegif.nemaki.rest.ingest;

import jakarta.servlet.http.HttpServletRequest;
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
@CrossOrigin(origins = "*", maxAge = 3600)
public class ImportProfileDefinitionController {

    @Autowired
    private ImportProfileDefinitionService importProfileDefinitionService;

    @Autowired
    private HttpServletRequest httpRequest;

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody ImportProfileDefinition def) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdmin();
        if (forbidden != null) return forbidden;
        try {
            ImportProfileDefinition created = importProfileDefinitionService.create(def);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("profileId", created.getProfileId());
            List<String> warnings = getPhase2Warnings(def);
            if (!warnings.isEmpty()) response.put("warnings", warnings);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return errorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping
    public ResponseEntity<List<ImportProfileDefinition>> list(
            @RequestParam(required = false) String repositoryId) {
        if (!isAdmin()) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        if (repositoryId != null && !repositoryId.isBlank()) {
            return ResponseEntity.ok(importProfileDefinitionService.listByRepository(repositoryId));
        }
        return ResponseEntity.ok(importProfileDefinitionService.list());
    }

    @GetMapping("/{profileId}")
    public ResponseEntity<ImportProfileDefinition> get(@PathVariable String profileId) {
        if (!isAdmin()) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        ImportProfileDefinition def = importProfileDefinitionService.get(profileId);
        if (def == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(def);
    }

    @PutMapping("/{profileId}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable String profileId, @RequestBody ImportProfileDefinition def) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdmin();
        if (forbidden != null) return forbidden;
        def.setProfileId(profileId);
        try {
            importProfileDefinitionService.update(def);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            List<String> warnings = getPhase2Warnings(def);
            if (!warnings.isEmpty()) response.put("warnings", warnings);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return errorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @DeleteMapping("/{profileId}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String profileId) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdmin();
        if (forbidden != null) return forbidden;
        importProfileDefinitionService.delete(profileId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        return ResponseEntity.ok(response);
    }

    /**
     * Returns warnings for profile fields that are persisted but not yet enforced at runtime.
     */
    private List<String> getPhase2Warnings(ImportProfileDefinition def) {
        List<String> warnings = new ArrayList<>();
        if (def.getDedupePolicy() != null && !"skip_if_same_version".equals(def.getDedupePolicy())) {
            warnings.add("dedupePolicy '" + def.getDedupePolicy() + "' is configured but not yet enforced (Phase 2)");
        }
        if (def.getUpdatePolicy() != null && !"version_up_on_content_change".equals(def.getUpdatePolicy())) {
            warnings.add("updatePolicy '" + def.getUpdatePolicy() + "' is configured but not yet enforced (Phase 2)");
        }
        if (def.getVersioningPolicy() != null && !"major".equals(def.getVersioningPolicy())) {
            warnings.add("versioningPolicy '" + def.getVersioningPolicy() + "' is configured but not yet enforced (Phase 2)");
        }
        if (def.getRelationshipPolicy() != null) {
            warnings.add("relationshipPolicy is configured but not yet enforced (Phase 2)");
        }
        if (def.getSecondaryTypeIds() != null && !def.getSecondaryTypeIds().isEmpty()) {
            warnings.add("secondaryTypeIds is configured but not yet auto-applied (Phase 2)");
        }
        if (def.getRetentionDays() != null) {
            warnings.add("retentionDays is configured but not yet enforced (Phase 2)");
        }
        return warnings;
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

    private ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "error");
        response.put("message", message);
        return ResponseEntity.status(status).body(response);
    }
}
