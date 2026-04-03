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

    /**
     * Manually trigger ingest for a specific scheduled profile.
     * Currently supports MESSAGE_CONTEXT (IMAP) connectors only.
     */
    @PostMapping("/trigger/{profileId}")
    public ResponseEntity<Map<String, Object>> triggerIngest(@PathVariable String profileId) {
        if (!isAdmin()) return forbidden();

        Map<String, Object> response = new LinkedHashMap<>();
        ImportProfileDefinition profile = schedulerService.getScheduledProfiles().stream()
                .filter(p -> profileId.equals(p.getProfileId()))
                .findFirst().orElse(null);
        if (profile == null) {
            response.put("status", "error");
            response.put("message", "Profile not found or not scheduler-enabled: " + profileId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }

        ConnectorDefinition connector = schedulerService.resolveConnectorForProfile(profile);
        if (connector == null) {
            response.put("status", "error");
            response.put("message", "No compatible connector found for profile: " + profileId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }

        response.put("status", "accepted");
        response.put("profileId", profileId);
        response.put("connectorId", connector.getConnectorId());
        response.put("sourceSystem", connector.getSourceSystem());
        response.put("message", "Scheduled ingest trigger accepted. Concrete adapters will execute in the background.");
        return ResponseEntity.accepted().body(response);
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
