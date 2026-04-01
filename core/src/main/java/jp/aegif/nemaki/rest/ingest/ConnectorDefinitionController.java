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
@CrossOrigin(origins = "*", maxAge = 3600)
public class ConnectorDefinitionController {

    @Autowired
    private ConnectorDefinitionService connectorDefinitionService;

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
                        SourceArchetype.valueOf(archetype.toUpperCase())));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        }
        return ResponseEntity.ok(connectorDefinitionService.list());
    }

    @GetMapping("/{connectorId}")
    public ResponseEntity<ConnectorDefinition> get(@PathVariable String connectorId) {
        if (!isAdmin()) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        ConnectorDefinition def = connectorDefinitionService.get(connectorId);
        if (def == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(def);
    }

    @PutMapping("/{connectorId}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable String connectorId, @RequestBody ConnectorDefinition def) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdmin();
        if (forbidden != null) return forbidden;
        def.setConnectorId(connectorId);
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

    private ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "error");
        response.put("message", message);
        return ResponseEntity.status(status).body(response);
    }
}
