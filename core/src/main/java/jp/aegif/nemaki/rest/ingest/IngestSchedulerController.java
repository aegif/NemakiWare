package jp.aegif.nemaki.rest.ingest;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.util.constant.CallContextKey;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import jp.aegif.nemaki.rest.ingest.IngestSchedulerService.FetchResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
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
        response.put("idleProfiles", schedulerService.getIdleProfiles());
        return ResponseEntity.ok(response);
    }

    /**
     * Start the periodic polling scheduler.
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startScheduler() {
        if (!isAdmin()) return forbidden();
        schedulerService.startPolling();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("message", "Ingest scheduler started");
        return ResponseEntity.ok(response);
    }

    /**
     * Stop the periodic polling scheduler.
     */
    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stopScheduler() {
        if (!isAdmin()) return forbidden();
        schedulerService.stopPolling();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("message", "Ingest scheduler stopped");
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

        CallContext callContext = getCallContext();
        if (callContext == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Dispatch to appropriate adapter via unified method, using persisted scope params
        Map<String, String> params = profile.getSchedulerParams() != null
                ? profile.getSchedulerParams() : Map.of();
        IngestSchedulerService.FetchResult fetchResult =
                schedulerService.executeFetch(callContext, profile, connector, params);

        response.put("status", fetchResult.hasErrors() ? "partial" : "success");
        response.put("fetched", fetchResult.fetched());
        response.put("imported", fetchResult.imported());
        if (fetchResult.hasErrors()) {
            response.put("errors", fetchResult.errors());
        }
        response.put("profileId", profileId);
        response.put("connectorId", connector.getConnectorId());
        response.put("sourceSystem", connector.getSourceSystem());
        response.put("archetype", connector.getSourceArchetype() != null ? connector.getSourceArchetype().name() : null);
        return ResponseEntity.ok(response);
    }

    // ── IMAP IDLE endpoints ─────────────────────────────────────────

    @PostMapping("/idle/start/{profileId}")
    public ResponseEntity<Map<String, Object>> startIdle(@PathVariable String profileId) {
        if (!isAdmin()) return forbidden();
        String error = schedulerService.startIdle(profileId);
        Map<String, Object> response = new LinkedHashMap<>();
        if (error != null) {
            response.put("status", "error");
            response.put("message", error);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
        response.put("status", "success");
        response.put("message", "IMAP IDLE started for " + profileId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/idle/stop/{profileId}")
    public ResponseEntity<Map<String, Object>> stopIdle(@PathVariable String profileId) {
        if (!isAdmin()) return forbidden();
        String error = schedulerService.stopIdle(profileId);
        Map<String, Object> response = new LinkedHashMap<>();
        if (error != null) {
            response.put("status", "error");
            response.put("message", error);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
        response.put("status", "success");
        response.put("message", "IMAP IDLE stopped for " + profileId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/idle/status")
    public ResponseEntity<Map<String, Object>> getIdleStatus() {
        if (!isAdmin()) return forbidden();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("idleProfiles", schedulerService.getIdleProfiles());
        return ResponseEntity.ok(response);
    }

    private CallContext getCallContext() {
        if (httpRequest == null) return null;
        return (CallContext) httpRequest.getAttribute("CallContext");
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
