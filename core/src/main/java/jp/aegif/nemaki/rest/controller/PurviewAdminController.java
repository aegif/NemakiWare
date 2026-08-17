package jp.aegif.nemaki.rest.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.rest.purview.CatalogBackendKind;
import jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver;
import jp.aegif.nemaki.rest.purview.PurviewConnectionService;
import jp.aegif.nemaki.rest.purview.PurviewConnectionStatus;
import jp.aegif.nemaki.rest.purview.state.PurviewCursorState;
import jp.aegif.nemaki.rest.purview.state.PurviewCursorStateService;
import jp.aegif.nemaki.rest.purview.sync.PurviewDeadLetterRetryService;
import jp.aegif.nemaki.rest.purview.state.PurviewDeadLetterState;
import jp.aegif.nemaki.rest.purview.state.PurviewDeadLetterStateService;
import jp.aegif.nemaki.rest.purview.sync.PurviewArchiveReconciliationService;
import jp.aegif.nemaki.rest.purview.sync.PurviewCloudMetadataReconciliationService;
import jp.aegif.nemaki.rest.purview.sync.PurviewContainmentReconciliationService;
import jp.aegif.nemaki.rest.purview.sync.PurviewDeleteResolutionService;
import jp.aegif.nemaki.rest.purview.sync.PurviewFullSyncService;
import jp.aegif.nemaki.rest.purview.sync.PurviewIncrementalSyncService;
import jp.aegif.nemaki.rest.purview.state.PurviewJobState;
import jp.aegif.nemaki.rest.purview.state.PurviewJobStateService;
import jp.aegif.nemaki.rest.purview.state.PurviewLockState;
import jp.aegif.nemaki.rest.purview.schema.PurviewSchemaApplyResult;
import jp.aegif.nemaki.rest.purview.schema.PurviewSchemaBootstrapResult;
import jp.aegif.nemaki.rest.purview.schema.PurviewSchemaBootstrapService;
import jp.aegif.nemaki.rest.purview.schema.PurviewSchemaDiff;
import jp.aegif.nemaki.rest.purview.schema.PurviewSchemaPlannerService;
import jp.aegif.nemaki.rest.purview.state.PurviewSchemaState;
import jp.aegif.nemaki.rest.purview.state.PurviewStateOverview;
import jp.aegif.nemaki.rest.purview.state.PurviewStateOverviewService;
import jp.aegif.nemaki.rest.purview.state.PurviewTombstoneState;
import jp.aegif.nemaki.rest.purview.lineage.PurviewCloudSyncLineageService;
import jp.aegif.nemaki.rest.purview.sync.PurviewTypeReconciliationService;
import jp.aegif.nemaki.util.constant.CallContextKey;

@RestController
@RequestMapping("/v1/admin/purview")
public class PurviewAdminController {

    private final MetadataCatalogConnectionResolver connectionResolver;
    private final PurviewConnectionService purviewConnectionService;
    private final PurviewSchemaPlannerService purviewSchemaPlannerService;
    private final PurviewSchemaBootstrapService purviewSchemaBootstrapService;
    private final PurviewFullSyncService purviewFullSyncService;
    private final PurviewIncrementalSyncService purviewIncrementalSyncService;
    private final PurviewArchiveReconciliationService purviewArchiveReconciliationService;
    private final PurviewCloudMetadataReconciliationService purviewCloudMetadataReconciliationService;
    private final PurviewContainmentReconciliationService purviewContainmentReconciliationService;
    private final PurviewTypeReconciliationService purviewTypeReconciliationService;
    private final PurviewDeleteResolutionService purviewDeleteResolutionService;
    private final PurviewJobStateService purviewJobStateService;
    private final PurviewCursorStateService purviewCursorStateService;
    private final PurviewStateOverviewService purviewStateOverviewService;
    private final PurviewDeadLetterStateService purviewDeadLetterStateService;
    private final PurviewDeadLetterRetryService purviewDeadLetterRetryService;
    private final PurviewCloudSyncLineageService purviewCloudSyncLineageService;
    private final jp.aegif.nemaki.rest.purview.lineage.LineageFolderCompanionBackfill
            folderCompanionBackfill;
    private final jp.aegif.nemaki.rest.purview.lineage.LineageCatalogReconciliationService
            lineageCatalogReconciliationService;

    private HttpServletRequest httpRequest;

    @Autowired
    public PurviewAdminController(
            MetadataCatalogConnectionResolver connectionResolver,
            PurviewConnectionService purviewConnectionService,
            PurviewSchemaPlannerService purviewSchemaPlannerService,
            PurviewSchemaBootstrapService purviewSchemaBootstrapService,
            PurviewFullSyncService purviewFullSyncService,
            PurviewIncrementalSyncService purviewIncrementalSyncService,
            PurviewArchiveReconciliationService purviewArchiveReconciliationService,
            PurviewCloudMetadataReconciliationService purviewCloudMetadataReconciliationService,
            PurviewContainmentReconciliationService purviewContainmentReconciliationService,
            PurviewTypeReconciliationService purviewTypeReconciliationService,
            PurviewDeleteResolutionService purviewDeleteResolutionService,
            PurviewJobStateService purviewJobStateService,
            PurviewCursorStateService purviewCursorStateService,
            PurviewStateOverviewService purviewStateOverviewService,
            PurviewDeadLetterStateService purviewDeadLetterStateService,
            PurviewDeadLetterRetryService purviewDeadLetterRetryService,
            PurviewCloudSyncLineageService purviewCloudSyncLineageService,
            jp.aegif.nemaki.rest.purview.lineage.LineageFolderCompanionBackfill
                    folderCompanionBackfill,
            jp.aegif.nemaki.rest.purview.lineage.LineageCatalogReconciliationService
                    lineageCatalogReconciliationService) {
        this.connectionResolver = connectionResolver;
        this.purviewConnectionService = purviewConnectionService;
        this.purviewSchemaPlannerService = purviewSchemaPlannerService;
        this.purviewSchemaBootstrapService = purviewSchemaBootstrapService;
        this.purviewFullSyncService = purviewFullSyncService;
        this.purviewIncrementalSyncService = purviewIncrementalSyncService;
        this.purviewArchiveReconciliationService = purviewArchiveReconciliationService;
        this.purviewCloudMetadataReconciliationService = purviewCloudMetadataReconciliationService;
        this.purviewContainmentReconciliationService = purviewContainmentReconciliationService;
        this.purviewTypeReconciliationService = purviewTypeReconciliationService;
        this.purviewDeleteResolutionService = purviewDeleteResolutionService;
        this.purviewJobStateService = purviewJobStateService;
        this.purviewCursorStateService = purviewCursorStateService;
        this.purviewStateOverviewService = purviewStateOverviewService;
        this.purviewDeadLetterStateService = purviewDeadLetterStateService;
        this.purviewDeadLetterRetryService = purviewDeadLetterRetryService;
        this.purviewCloudSyncLineageService = purviewCloudSyncLineageService;
        this.folderCompanionBackfill = folderCompanionBackfill;
        this.lineageCatalogReconciliationService = lineageCatalogReconciliationService;
    }

    @Autowired
    public void setHttpRequest(HttpServletRequest httpRequest) {
        this.httpRequest = httpRequest;
    }

    private ResponseEntity<Map<String, Object>> requireAdminOrForbidden() {
        if (!isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(buildForbiddenResponse());
        }
        return null;
    }

    /**
     * Public endpoint (authentication required, admin NOT required).
     * Returns whether any catalog backend is enabled, for the DocumentViewer governance tab.
     */
    @GetMapping("/governance-available")
    public ResponseEntity<Map<String, Object>> getGovernanceAvailability() {
        if (!isAuthenticated()) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "error");
            response.put("message", "Authentication required");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("enabled", connectionResolver.isAnyEnabled());
        response.put("backend", connectionResolver.activeBackend().name());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/test-connection")
    public ResponseEntity<Map<String, Object>> testConnection() {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        CatalogBackendKind backend = connectionResolver.activeBackend();
        PurviewConnectionStatus status = switch (backend) {
            case ATLAS -> purviewConnectionService.testAtlasConnection(null);
            default -> purviewConnectionService.testConnection();
        };
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", status.isConnected() ? "success" : "failure");
        response.put("connected", status.isConnected());
        response.put("featureEnabled", status.isFeatureEnabled());
        response.put("endpoint", status.getEndpoint());
        response.put("atlasBasePath", status.getAtlasBasePath());
        response.put("backend", backend.name());
        response.put("message", status.getMessage());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/schema-state")
    public ResponseEntity<Map<String, Object>> getSchemaState() {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        PurviewSchemaState schemaState = purviewSchemaPlannerService.getCurrentSchemaState();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("collection", schemaState.getCollection());
        response.put("schemaVersion", schemaState.getSchemaVersion());
        response.put("schemaHash", schemaState.getSchemaHash());
        response.put("lastAppliedAt", schemaState.getLastAppliedAt());
        response.put("lastAppliedBy", schemaState.getLastAppliedBy());
        response.put("lastDiffSummary", schemaState.getLastDiffSummary());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/type-definitions/diff")
    public ResponseEntity<Map<String, Object>> getTypeDefinitionDiff() {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        PurviewSchemaDiff schemaDiff = purviewSchemaPlannerService.getSchemaDiff();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("collection", schemaDiff.getCollection());
        response.put("currentSchemaVersion", schemaDiff.getCurrentSchemaVersion());
        response.put("currentSchemaHash", schemaDiff.getCurrentSchemaHash());
        response.put("desiredSchemaVersion", schemaDiff.getDesiredSchemaVersion());
        response.put("desiredSchemaHash", schemaDiff.getDesiredSchemaHash());
        response.put("applyRequired", schemaDiff.isApplyRequired());
        response.put("customTypeNames", schemaDiff.getCustomTypeNames());
        response.put("relationshipTypeNames", schemaDiff.getRelationshipTypeNames());
        response.put("businessMetadataNames", schemaDiff.getBusinessMetadataNames());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/type-definitions/apply")
    public ResponseEntity<Map<String, Object>> applyTypeDefinitions() {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        PurviewSchemaBootstrapResult bootstrapResult = purviewSchemaBootstrapService.startTypeBootstrap(
                getAuthenticatedUsername());
        PurviewSchemaApplyResult applyResult = bootstrapResult.getApplyResult();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("applied", applyResult.isApplied());
        response.put("message", applyResult.getMessage());
        if (applyResult.getSchemaState() != null) {
            response.put("collection", applyResult.getSchemaState().getCollection());
            response.put("schemaVersion", applyResult.getSchemaState().getSchemaVersion());
            response.put("schemaHash", applyResult.getSchemaState().getSchemaHash());
            response.put("lastAppliedAt", applyResult.getSchemaState().getLastAppliedAt());
            response.put("lastAppliedBy", applyResult.getSchemaState().getLastAppliedBy());
        }
        response.putAll(buildJobResponse(bootstrapResult.getJobState()));
        return ResponseEntity.ok(response);
    }

    // ------------------------------------------------------------------
    // 増分 B: folder companion の backfill と reconciliation
    // ------------------------------------------------------------------

    /**
     * What the backfill would do, without doing any of it.
     *
     * <p>{@code GET}, because an operator deciding whether to run this against production is
     * entitled to ask without the asking being the running.
     */
    @GetMapping("/lineage/backfill/folder-dataset/{repositoryId}/plan")
    public ResponseEntity<Map<String, Object>> planFolderCompanionBackfill(
            @PathVariable("repositoryId") String repositoryId) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        var plan = folderCompanionBackfill.plan(repositoryId);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("repositoryId", plan.repositoryId());
        body.put("folderCount", plan.folderCount());
        body.put("alreadyProcessed", plan.alreadyPresent());
        body.put("schemaReady", plan.schemaReady());
        body.put("catalogReachable", plan.catalogReachable());
        body.put("refusal", plan.refusal().name());
        body.put("runnable", plan.runnable());
        return ResponseEntity.ok(body);
    }

    /** Where a repository's backfill stands, without touching the catalog. */
    @GetMapping("/lineage/backfill/folder-dataset/{repositoryId}")
    public ResponseEntity<Map<String, Object>> folderCompanionBackfillProgress(
            @PathVariable("repositoryId") String repositoryId) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        return ResponseEntity.ok(backfillBody(folderCompanionBackfill.progress(repositoryId)));
    }

    /**
     * Runs a bounded slice of the backfill and returns where it stopped.
     *
     * <p>Bounded by {@code maxBatches} so this fits a maintenance window; call it again to
     * continue from the persisted frontier.
     */
    @PostMapping("/lineage/backfill/folder-dataset/{repositoryId}")
    public ResponseEntity<Map<String, Object>> runFolderCompanionBackfill(
            @PathVariable("repositoryId") String repositoryId,
            @RequestParam(name = "maxBatches", defaultValue = "10") int maxBatches) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        return ResponseEntity.ok(
                backfillBody(folderCompanionBackfill.run(repositoryId, maxBatches)));
    }

    /**
     * Reconciles folders against their companions.
     *
     * <p>{@code repair=false} by default: the read-only pass is the one an operator runs first,
     * and a repair should be asked for rather than assumed.
     */
    @PostMapping("/lineage/reconcile/folder-dataset/{repositoryId}")
    public ResponseEntity<Map<String, Object>> reconcileFolderCompanions(
            @PathVariable("repositoryId") String repositoryId,
            @RequestParam(name = "maxFolders", defaultValue = "1000") int maxFolders,
            @RequestParam(name = "repair", defaultValue = "false") boolean repair) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        var report = lineageCatalogReconciliationService.reconcile(repositoryId, maxFolders, repair);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("repositoryId", report.repositoryId());
        body.put("checked", report.checked());
        body.put("inSync", report.inSync());
        body.put("companionMissing", report.companionMissing());
        body.put("sourceMissing", report.sourceMissing());
        body.put("undetermined", report.undetermined());
        body.put("repaired", report.repaired());
        body.put("markedOrphan", report.markedOrphan());
        // Read this, not the individual counts: undetermined counts against clean, because a
        // pass that could not look has not found nothing wrong.
        body.put("clean", report.clean());
        return ResponseEntity.ok(body);
    }

    private static Map<String, Object> backfillBody(
            jp.aegif.nemaki.rest.purview.lineage.LineageFolderCompanionBackfill.Progress progress) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("repositoryId", progress.repositoryId());
        body.put("state", progress.state().name());
        body.put("processed", progress.processed());
        body.put("created", progress.created());
        body.put("alreadyPresent", progress.alreadyPresent());
        body.put("failed", progress.failed());
        body.put("pendingFrontier", progress.pendingFrontier());
        body.put("refusal", progress.refusal().name());
        // COMPLETE alone is not done: a walk that finished with failures is COMPLETE and not
        // successful, and an operator checking one field must not read it as finished.
        body.put("successful", progress.successful());
        return ResponseEntity.ok(body).getBody();
    }

    @PostMapping("/full-sync/{repositoryId}")
    public ResponseEntity<Map<String, Object>> startFullSync(
            @PathVariable("repositoryId") String repositoryId) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        PurviewJobState jobState = purviewFullSyncService.startFullSync(repositoryId, getAuthenticatedUsername());
        return ResponseEntity.ok(buildJobResponse(jobState));
    }

    @PostMapping("/incremental-sync/{repositoryId}")
    public ResponseEntity<Map<String, Object>> startIncrementalSync(
            @PathVariable("repositoryId") String repositoryId) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        PurviewJobState jobState = purviewIncrementalSyncService.startIncrementalSync(
                repositoryId, getAuthenticatedUsername());
        return ResponseEntity.ok(buildJobResponse(jobState));
    }

    @PostMapping("/reconcile/archives/{repositoryId}")
    public ResponseEntity<Map<String, Object>> startArchiveReconciliation(
            @PathVariable("repositoryId") String repositoryId) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        PurviewJobState jobState = purviewArchiveReconciliationService.startArchiveReconciliation(
                repositoryId, getAuthenticatedUsername());
        return ResponseEntity.ok(buildJobResponse(jobState));
    }

    @PostMapping("/reconcile/cloud-metadata/{repositoryId}")
    public ResponseEntity<Map<String, Object>> startCloudMetadataReconciliation(
            @PathVariable("repositoryId") String repositoryId) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        PurviewJobState jobState = purviewCloudMetadataReconciliationService.startCloudMetadataReconciliation(
                repositoryId, getAuthenticatedUsername());
        return ResponseEntity.ok(buildJobResponse(jobState));
    }

    @PostMapping("/cleanup/cloud-sync-lineage/{repositoryId}")
    public ResponseEntity<Map<String, Object>> cleanupCloudSyncLineage(
            @PathVariable("repositoryId") String repositoryId,
            @RequestParam("objectId") String objectId,
            @RequestParam(value = "stableKey", required = false) String stableKey) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        try {
            int deletedCount = purviewCloudSyncLineageService.deleteCloudSyncLineageByObjectId(
                    repositoryId, objectId, stableKey);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("repositoryId", repositoryId);
            result.put("objectId", objectId);
            result.put("stableKey", stableKey != null ? stableKey : "");
            result.put("deletedCount", deletedCount);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("repositoryId", repositoryId);
            error.put("objectId", objectId);
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @PostMapping("/reconcile/containment/{repositoryId}")
    public ResponseEntity<Map<String, Object>> startContainmentReconciliation(
            @PathVariable("repositoryId") String repositoryId) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        PurviewJobState jobState = purviewContainmentReconciliationService.startContainmentReconciliation(
                repositoryId, getAuthenticatedUsername());
        return ResponseEntity.ok(buildJobResponse(jobState));
    }

    @PostMapping("/reconcile/types/{repositoryId}")
    public ResponseEntity<Map<String, Object>> startTypeReconciliation(
            @PathVariable("repositoryId") String repositoryId) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        PurviewJobState jobState = purviewTypeReconciliationService.startTypeReconciliation(
                repositoryId, getAuthenticatedUsername());
        return ResponseEntity.ok(buildJobResponse(jobState));
    }

    @PostMapping("/delete-resolution/{repositoryId}")
    public ResponseEntity<Map<String, Object>> startDeleteResolution(
            @PathVariable("repositoryId") String repositoryId) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        PurviewJobState jobState = purviewDeleteResolutionService.startDeleteResolution(
                repositoryId, getAuthenticatedUsername());
        return ResponseEntity.ok(buildJobResponse(jobState));
    }

    @GetMapping("/dead-letters")
    public ResponseEntity<Map<String, Object>> getDeadLetters() {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("deadLetters", purviewDeadLetterStateService.listDeadLetterStates().stream()
                .map(this::buildDeadLetterResponse)
                .toList());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/retry-failed/{repositoryId}")
    public ResponseEntity<Map<String, Object>> startRetryFailed(
            @PathVariable("repositoryId") String repositoryId) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        PurviewJobState jobState = purviewDeadLetterRetryService.startRetryFailed(
                repositoryId,
                getAuthenticatedUsername());
        return ResponseEntity.ok(buildJobResponse(jobState));
    }

    @GetMapping("/jobs")
    public ResponseEntity<Map<String, Object>> listJobs() {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobs", purviewJobStateService.listJobStates().stream()
                .map(this::buildJobResponse)
                .toList());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/purge-job-history")
    public ResponseEntity<Map<String, Object>> purgeJobHistory(
            @RequestParam(name = "retainCount", defaultValue = "50") int retainCount) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        int effectiveRetainCount = Math.max(1, retainCount);
        int purgedCount = purviewJobStateService.purgeOldJobStates(effectiveRetainCount);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("purgedCount", purgedCount);
        response.put("retainCount", effectiveRetainCount);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<Map<String, Object>> getJob(
            @PathVariable("jobId") String jobId) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        return ResponseEntity.ok(buildJobResponse(purviewJobStateService.getJobState(jobId)));
    }

    private static String sanitizeCursorForResponse(String streamKind, String cursor) {
        if (jp.aegif.nemaki.rest.purview.publish.CloudMetadataSnapshotFormat.STREAM_KIND
                .equals(streamKind)) {
            return jp.aegif.nemaki.rest.purview.publish.CloudMetadataSnapshotFormat
                    .normalize(cursor);
        }
        return cursor;
    }

    @GetMapping("/cursor-state/{repositoryId}/{streamKind}")
    public ResponseEntity<Map<String, Object>> getCursorState(
            @PathVariable("repositoryId") String repositoryId,
            @PathVariable("streamKind") String streamKind) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        PurviewCursorState cursorState = purviewCursorStateService.getCursorState(repositoryId, streamKind);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("repositoryId", cursorState.getRepositoryId());
        response.put("streamKind", cursorState.getStreamKind());
        // The cloud-metadata cursor is a snapshot whose old shape carried raw drive URLs (and
        // with them any sharing tokens). Stored cursors scrub themselves on the next successful
        // sync; until then, nothing stored may reach a response unsanitised.
        response.put("cursor", sanitizeCursorForResponse(streamKind, cursorState.getCursor()));
        response.put("cursorKind", cursorState.getCursorKind());
        response.put("lastRunAt", cursorState.getLastRunAt());
        response.put("lastSuccessAt", cursorState.getLastSuccessAt());
        response.put("lastErrorAt", cursorState.getLastErrorAt());
        response.put("lastErrorMessage", cursorState.getLastErrorMessage());
        response.put("consecutiveFailureCount", cursorState.getConsecutiveFailureCount());
        response.put("deadLetterCount", cursorState.getDeadLetterCount());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/state")
    public ResponseEntity<Map<String, Object>> getState() {
        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) return forbidden;

        String collection = purviewSchemaPlannerService.getCurrentSchemaState().getCollection();
        PurviewStateOverview overview = purviewStateOverviewService.getStateOverview(collection);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("activeBackend", connectionResolver.activeBackend().name());
        response.put("collection", overview.getCollection());
        response.put("schemaState", buildSchemaStateResponse(overview.getSchemaState()));
        response.put("jobs", overview.getJobs().stream().map(this::buildJobResponse).toList());
        response.put("cursors", overview.getCursors().stream().map(this::buildCursorResponse).toList());
        response.put("locks", overview.getLocks().stream().map(this::buildLockResponse).toList());
        response.put("tombstones", overview.getTombstones().stream().map(this::buildTombstoneResponse).toList());
        response.put("deadLetters", overview.getDeadLetters().stream().map(this::buildDeadLetterResponse).toList());
        return ResponseEntity.ok(response);
    }

    private boolean isAuthenticated() {
        if (httpRequest == null) {
            return false;
        }
        CallContext callContext = (CallContext) httpRequest.getAttribute("CallContext");
        return callContext != null && callContext.getUsername() != null && !callContext.getUsername().isBlank();
    }

    private boolean isAdmin() {
        if (httpRequest == null) {
            return false;
        }

        CallContext callContext = (CallContext) httpRequest.getAttribute("CallContext");
        if (callContext == null) {
            return false;
        }

        Boolean isAdmin = (Boolean) callContext.get(CallContextKey.IS_ADMIN);
        return isAdmin != null && isAdmin;
    }

    private Map<String, Object> buildForbiddenResponse() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "error");
        response.put("connected", false);
        response.put("message", "Admin access required");
        return response;
    }

    private String getAuthenticatedUsername() {
        if (httpRequest == null) {
            return "system";
        }

        CallContext callContext = (CallContext) httpRequest.getAttribute("CallContext");
        if (callContext == null || callContext.getUsername() == null || callContext.getUsername().isBlank()) {
            return "system";
        }
        return callContext.getUsername();
    }

    private Map<String, Object> buildJobResponse(PurviewJobState jobState) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobId", jobState.getJobId());
        response.put("jobKind", jobState.getJobKind());
        response.put("repositoryId", jobState.getRepositoryId());
        response.put("status", jobState.getStatus());
        response.put("startedAt", jobState.getStartedAt());
        response.put("endedAt", jobState.getEndedAt());
        response.put("processedCount", jobState.getProcessedCount());
        response.put("failedCount", jobState.getFailedCount());
        // A job checkpoint has no streamKind to gate on, so the sanitiser is applied by shape:
        // normalize() rewrites only five-field snapshot lines and passes everything else
        // (change tokens, timestamps) through untouched.
        response.put("checkpoint", jp.aegif.nemaki.rest.purview.publish
                .CloudMetadataSnapshotFormat.normalize(jobState.getCheckpoint()));
        response.put("errorSummary", jobState.getErrorSummary());
        return response;
    }

    private Map<String, Object> buildCursorResponse(PurviewCursorState cursorState) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("repositoryId", cursorState.getRepositoryId());
        response.put("streamKind", cursorState.getStreamKind());
        // The overview is what the admin UI renders in full, so it is the response that matters
        // most — and the one this sanitisation was missing from while the single-cursor endpoint
        // had it. Every path that emits a stored cursor goes through sanitizeCursorForResponse,
        // or the cloud-metadata snapshot's raw drive URLs (sharing tokens included) ride along.
        response.put("cursor", sanitizeCursorForResponse(
                cursorState.getStreamKind(), cursorState.getCursor()));
        response.put("cursorKind", cursorState.getCursorKind());
        response.put("lastRunAt", cursorState.getLastRunAt());
        response.put("lastSuccessAt", cursorState.getLastSuccessAt());
        response.put("lastErrorAt", cursorState.getLastErrorAt());
        response.put("lastErrorMessage", cursorState.getLastErrorMessage());
        response.put("consecutiveFailureCount", cursorState.getConsecutiveFailureCount());
        response.put("deadLetterCount", cursorState.getDeadLetterCount());
        return response;
    }

    private Map<String, Object> buildLockResponse(PurviewLockState lockState) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("repositoryId", lockState.getRepositoryId());
        response.put("jobKind", lockState.getJobKind());
        response.put("locked", lockState.isLocked());
        response.put("ownerJobId", lockState.getOwnerJobId());
        response.put("lockedAt", lockState.getLockedAt());
        return response;
    }

    private Map<String, Object> buildSchemaStateResponse(PurviewSchemaState schemaState) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("collection", schemaState.getCollection());
        response.put("schemaVersion", schemaState.getSchemaVersion());
        response.put("schemaHash", schemaState.getSchemaHash());
        response.put("lastAppliedAt", schemaState.getLastAppliedAt());
        response.put("lastAppliedBy", schemaState.getLastAppliedBy());
        response.put("lastDiffSummary", schemaState.getLastDiffSummary());
        return response;
    }

    private Map<String, Object> buildTombstoneResponse(PurviewTombstoneState tombstoneState) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("repositoryId", tombstoneState.getRepositoryId());
        response.put("objectId", tombstoneState.getObjectId());
        response.put("typeName", tombstoneState.getTypeName());
        response.put("qualifiedName", tombstoneState.getQualifiedName());
        response.put("changeToken", tombstoneState.getChangeToken());
        response.put("firstSeenAt", tombstoneState.getFirstSeenAt());
        response.put("dueAt", tombstoneState.getDueAt());
        response.put("status", tombstoneState.getStatus());
        return response;
    }

    private Map<String, Object> buildDeadLetterResponse(PurviewDeadLetterState deadLetterState) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("repositoryId", deadLetterState.getRepositoryId());
        response.put("streamKind", deadLetterState.getStreamKind());
        response.put("entryKey", deadLetterState.getEntryKey());
        response.put("typeName", deadLetterState.getTypeName());
        response.put("qualifiedName", deadLetterState.getQualifiedName());
        response.put("firstFailedAt", deadLetterState.getFirstFailedAt());
        response.put("lastFailedAt", deadLetterState.getLastFailedAt());
        response.put("failureCount", deadLetterState.getFailureCount());
        response.put("checkpoint", sanitizeCursorForResponse(
                deadLetterState.getStreamKind(), deadLetterState.getCheckpoint()));
        response.put("errorSummary", deadLetterState.getErrorSummary());
        return response;
    }
}
