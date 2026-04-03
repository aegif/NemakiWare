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

/**
 * Admin REST API for the external ingest scheduler.
 */
@RestController
@RequestMapping("/v1/admin/ingest-scheduler")
@CrossOrigin(origins = "*", maxAge = 3600)
public class IngestSchedulerController {

    @Autowired
    private IngestSchedulerService schedulerService;

    @Autowired
    private HttpServletRequest httpRequest;

    /**
     * Returns the list of profiles with schedulerEnabled=true and their
     * resolved default connectors.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        if (!isAdmin()) return forbidden();

        List<ImportProfileDefinition> scheduled = schedulerService.getScheduledProfiles();
        List<Map<String, Object>> entries = scheduled.stream().map(profile -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("profileId", profile.getProfileId());
            entry.put("repositoryId", profile.getRepositoryId());
            entry.put("displayName", profile.getDisplayName());
            ConnectorDefinition connector = schedulerService.resolveConnectorForProfile(profile);
            entry.put("connectorId", connector != null ? connector.getConnectorId() : null);
            entry.put("connectorSystem", connector != null ? connector.getSourceSystem() : null);
            entry.put("ready", connector != null);
            return entry;
        }).toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scheduledProfiles", entries);
        response.put("count", entries.size());
        return ResponseEntity.ok(response);
    }

    private boolean isAdmin() {
        if (httpRequest == null) return false;
        CallContext ctx = (CallContext) httpRequest.getAttribute("CallContext");
        if (ctx == null) return false;
        Boolean admin = (Boolean) ctx.get(CallContextKey.IS_ADMIN);
        return admin != null && admin;
    }

    private ResponseEntity<Map<String, Object>> forbidden() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "error");
        r.put("message", "Admin access required");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(r);
    }
}
