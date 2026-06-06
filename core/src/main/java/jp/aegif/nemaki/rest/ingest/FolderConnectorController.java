package jp.aegif.nemaki.rest.ingest;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.rest.importexport.ImportExportUtils;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Folder-scoped manual connector execution (3.1.3).
 *
 * <p>Lets a user who is looking at a folder run an ingest connector by hand
 * — useful when a connector is configured for the folder but the profile is
 * not on a schedule, so there would otherwise be no way to pull content
 * without an admin. Two endpoints:
 *
 * <ul>
 *   <li>{@code GET .../folders/{folderId}/connectors} — list the profiles
 *       whose target is this folder and that the caller may run, so the UI
 *       can decide whether to show a "run connector" button.</li>
 *   <li>{@code POST .../folders/{folderId}/connectors/{profileId}/run} —
 *       run one of them now.</li>
 * </ul>
 *
 * <p>Authorization (per 3.1.3 spec): the caller must hold <b>write</b>
 * (create-children) on the folder AND the connector must be <b>delegated</b>
 * to them for that folder (or the caller is an admin). This keeps a
 * connector's credentials from being driven by anyone who merely has write
 * access — the connector owner must have delegated it to this folder.
 */
@RestController
@RequestMapping("/v1/repo/{repositoryId}/folders/{folderId}/connectors")
public class FolderConnectorController {

    private static final Logger logger = LoggerFactory.getLogger(FolderConnectorController.class);

    @Autowired
    private HttpServletRequest httpRequest;

    @Autowired
    private ContentService contentService;

    @Autowired
    private ImportProfileDefinitionService importProfileDefinitionService;

    @Autowired
    private ConnectorDefinitionService connectorDefinitionService;

    @Autowired
    private IngestAuthorizationService ingestAuthorizationService;

    @Autowired
    private IngestSchedulerService schedulerService;

    private CallContext getCallContext() {
        if (httpRequest == null) return null;
        return (CallContext) httpRequest.getAttribute("CallContext");
    }

    /**
     * List the runnable connector profiles whose target is {@code folderId}.
     * Returns only profiles the caller may run (write + delegation/admin),
     * so an empty list means "don't show the run button".
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @PathVariable String repositoryId, @PathVariable String folderId) {
        CallContext ctx = getCallContext();
        if (ctx == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        Folder folder = contentService.getFolder(repositoryId, folderId);
        if (folder == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "error");
            body.put("message", "Folder not found: " + folderId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }

        boolean canWrite = ImportExportUtils.hasCreateChildrenPermission(
                contentService, repositoryId, ctx, folder);
        boolean admin = ingestAuthorizationService.isAdmin(ctx);

        List<Map<String, Object>> runnable = new ArrayList<>();
        if (canWrite || admin) {
            for (ImportProfileDefinition profile : importProfileDefinitionService.listByRepository(repositoryId)) {
                if (!profile.isEnabled()) continue;
                if (!folderId.equals(profile.getTargetFolderId())) continue;
                ConnectorDefinition connector = schedulerService.resolveConnectorForProfile(profile);
                if (connector == null) continue;
                if (!mayRun(ctx, repositoryId, folderId, connector, admin)) continue;
                runnable.add(describe(profile, connector));
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("canWrite", canWrite || admin);
        body.put("connectors", runnable);
        return ResponseEntity.ok(body);
    }

    /**
     * Run the given profile's connector now. Same authorization as
     * {@link #list}. On an authentication failure (the connector's token
     * could not be resolved or was rejected) the response carries
     * {@code authError=true} so the UI can offer to re-set the token.
     */
    @PostMapping("/{profileId}/run")
    public ResponseEntity<Map<String, Object>> run(
            @PathVariable String repositoryId, @PathVariable String folderId,
            @PathVariable String profileId) {
        CallContext ctx = getCallContext();
        if (ctx == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        Map<String, Object> body = new LinkedHashMap<>();

        Folder folder = contentService.getFolder(repositoryId, folderId);
        if (folder == null) {
            body.put("status", "error");
            body.put("message", "Folder not found: " + folderId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }

        boolean admin = ingestAuthorizationService.isAdmin(ctx);
        boolean canWrite = ImportExportUtils.hasCreateChildrenPermission(
                contentService, repositoryId, ctx, folder);
        if (!canWrite && !admin) {
            body.put("status", "error");
            body.put("message", "You do not have permission to run connectors in this folder");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
        }

        ImportProfileDefinition profile = importProfileDefinitionService.get(profileId);
        if (profile == null || !profile.isEnabled() || !folderId.equals(profile.getTargetFolderId())) {
            body.put("status", "error");
            body.put("message", "No runnable profile for this folder: " + profileId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }

        ConnectorDefinition connector = schedulerService.resolveConnectorForProfile(profile);
        if (connector == null) {
            body.put("status", "error");
            body.put("message", "No connector resolved for profile: " + profileId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }

        if (!mayRun(ctx, repositoryId, folderId, connector, admin)) {
            body.put("status", "error");
            body.put("message", "This connector is not delegated to you for this folder");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
        }

        Map<String, String> params = profile.getSchedulerParams() != null
                ? profile.getSchedulerParams() : Map.of();
        FetchResult result;
        try {
            result = schedulerService.executeFetch(ctx, profile, connector, params);
        } catch (Exception e) {
            logger.warn("Manual connector run failed for profile {}: {}", profileId, e.getMessage());
            body.put("status", "error");
            body.put("message", "Connector run failed: " + e.getMessage());
            body.put("authError", isAuthFailure(e.getMessage()));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }

        boolean authError = result.errors() != null
                && result.errors().stream().anyMatch(m -> isAuthFailure(m));
        body.put("status", result.hasErrors() ? "partial" : "success");
        body.put("fetched", result.fetched());
        body.put("imported", result.imported());
        body.put("skipped", result.skipped());
        if (result.hasErrors()) body.put("errors", result.errors());
        // authError lets the UI pop a "re-set token" dialog (dev tokens are short-lived).
        body.put("authError", authError);
        body.put("profileId", profileId);
        body.put("connectorId", connector.getConnectorId());
        body.put("sourceSystem", connector.getSourceSystem());
        return ResponseEntity.ok(body);
    }

    /** write + (admin or connector delegated to caller for this folder). */
    private boolean mayRun(CallContext ctx, String repositoryId, String folderId,
                           ConnectorDefinition connector, boolean admin) {
        if (admin) return true;
        return ingestAuthorizationService.canUseConnectorForDelegatedProfile(
                ctx, repositoryId, connector, folderId);
    }

    private Map<String, Object> describe(ImportProfileDefinition profile, ConnectorDefinition connector) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("profileId", profile.getProfileId());
        m.put("profileName", profile.getDisplayName());
        m.put("connectorId", connector.getConnectorId());
        m.put("connectorName", connector.getDisplayName());
        m.put("sourceSystem", connector.getSourceSystem());
        m.put("sourceArchetype", connector.getSourceArchetype() != null
                ? connector.getSourceArchetype().name() : null);
        m.put("schedulerEnabled", profile.isSchedulerEnabled());
        return m;
    }

    /**
     * Heuristic: treat a fetch error that names a missing/invalid token or an
     * authorization rejection as an auth failure, so the UI can offer to
     * re-enter the connector's token.
     */
    private static boolean isAuthFailure(String message) {
        if (message == null) return false;
        String m = message.toLowerCase();
        return m.contains("no token")
                || m.contains("token")
                || m.contains("unauthorized")
                || m.contains("401")
                || m.contains("403")
                || m.contains("authentication")
                || m.contains("invalid_auth")
                || m.contains("not_authed");
    }
}
