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
