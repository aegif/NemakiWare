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
public class ImportProfileDefinitionController {

    @Autowired
    private ImportProfileDefinitionService importProfileDefinitionService;

    @Autowired(required = false)
    private IngestSchedulerService ingestSchedulerService;

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
    public ResponseEntity<Map<String, Object>> get(@PathVariable String profileId) {
        if (!isAdmin()) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        ImportProfileDefinition def = importProfileDefinitionService.get(profileId);
        if (def == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("profile", def);
        List<String> warnings = getPhase2Warnings(def);
        if (!warnings.isEmpty()) response.put("warnings", warnings);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{profileId}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable String profileId, @RequestBody ImportProfileDefinition def) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdmin();
        if (forbidden != null) return forbidden;
        def.setProfileId(profileId);
        try {
            importProfileDefinitionService.update(def);
            // If profile was disabled and IDLE is running, stop the IDLE thread
            if (!def.isEnabled() && ingestSchedulerService != null) {
                ingestSchedulerService.stopIdle(profileId);
            }
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
        // Stop IDLE thread before deletion (no-op if not running)
        if (ingestSchedulerService != null) {
            ingestSchedulerService.stopIdle(profileId);
        }
        importProfileDefinitionService.delete(profileId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        return ResponseEntity.ok(response);
    }

    /**
     * Returns warnings for profile configuration issues (surfaced to admin via API/UI).
     */
    private List<String> getPhase2Warnings(ImportProfileDefinition def) {
        if (importProfileDefinitionService instanceof ImportProfileDefinitionServiceImpl impl) {
            return impl.collectWarnings(def);
        }
        // Fallback if interface-only injection
        return new ArrayList<>();
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
