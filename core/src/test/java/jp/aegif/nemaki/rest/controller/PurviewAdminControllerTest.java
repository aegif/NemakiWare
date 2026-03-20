package jp.aegif.nemaki.rest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import jp.aegif.nemaki.rest.purview.PurviewConnectionService;
import jp.aegif.nemaki.rest.purview.PurviewConnectionStatus;
import jp.aegif.nemaki.rest.purview.PurviewCursorState;
import jp.aegif.nemaki.rest.purview.PurviewCursorStateService;
import jp.aegif.nemaki.rest.purview.PurviewDeadLetterRetryService;
import jp.aegif.nemaki.rest.purview.PurviewDeadLetterState;
import jp.aegif.nemaki.rest.purview.PurviewDeadLetterStateService;
import jp.aegif.nemaki.rest.purview.PurviewArchiveReconciliationService;
import jp.aegif.nemaki.rest.purview.PurviewCloudMetadataReconciliationService;
import jp.aegif.nemaki.rest.purview.PurviewContainmentReconciliationService;
import jp.aegif.nemaki.rest.purview.PurviewDeleteResolutionService;
import jp.aegif.nemaki.rest.purview.PurviewSchemaApplyResult;
import jp.aegif.nemaki.rest.purview.PurviewSchemaDiff;
import jp.aegif.nemaki.rest.purview.PurviewSchemaBootstrapResult;
import jp.aegif.nemaki.rest.purview.PurviewSchemaBootstrapService;
import jp.aegif.nemaki.rest.purview.PurviewFullSyncService;
import jp.aegif.nemaki.rest.purview.PurviewIncrementalSyncService;
import jp.aegif.nemaki.rest.purview.PurviewJobState;
import jp.aegif.nemaki.rest.purview.PurviewJobStateService;
import jp.aegif.nemaki.rest.purview.PurviewLockState;
import jp.aegif.nemaki.rest.purview.PurviewSchemaPlannerService;
import jp.aegif.nemaki.rest.purview.PurviewSchemaState;
import jp.aegif.nemaki.rest.purview.PurviewStateOverview;
import jp.aegif.nemaki.rest.purview.PurviewStateOverviewService;
import jp.aegif.nemaki.rest.purview.PurviewTombstoneState;
import jp.aegif.nemaki.rest.purview.PurviewTypeReconciliationService;
import jp.aegif.nemaki.util.constant.CallContextKey;

public class PurviewAdminControllerTest {

    private PurviewConnectionService connectionService;
    private PurviewSchemaPlannerService schemaPlannerService;
    private PurviewSchemaBootstrapService schemaBootstrapService;
    private PurviewFullSyncService fullSyncService;
    private PurviewIncrementalSyncService incrementalSyncService;
    private PurviewArchiveReconciliationService archiveReconciliationService;
    private PurviewCloudMetadataReconciliationService cloudMetadataReconciliationService;
    private PurviewContainmentReconciliationService containmentReconciliationService;
    private PurviewTypeReconciliationService typeReconciliationService;
    private PurviewDeleteResolutionService deleteResolutionService;
    private PurviewJobStateService jobStateService;
    private PurviewCursorStateService cursorStateService;
    private PurviewStateOverviewService stateOverviewService;
    private PurviewDeadLetterStateService deadLetterStateService;
    private PurviewDeadLetterRetryService deadLetterRetryService;
    private PurviewAdminController controller;

    @BeforeEach
    public void setUp() {
        connectionService = mock(PurviewConnectionService.class);
        schemaPlannerService = mock(PurviewSchemaPlannerService.class);
        schemaBootstrapService = mock(PurviewSchemaBootstrapService.class);
        fullSyncService = mock(PurviewFullSyncService.class);
        incrementalSyncService = mock(PurviewIncrementalSyncService.class);
        archiveReconciliationService = mock(PurviewArchiveReconciliationService.class);
        cloudMetadataReconciliationService = mock(PurviewCloudMetadataReconciliationService.class);
        containmentReconciliationService = mock(PurviewContainmentReconciliationService.class);
        typeReconciliationService = mock(PurviewTypeReconciliationService.class);
        deleteResolutionService = mock(PurviewDeleteResolutionService.class);
        jobStateService = mock(PurviewJobStateService.class);
        cursorStateService = mock(PurviewCursorStateService.class);
        stateOverviewService = mock(PurviewStateOverviewService.class);
        deadLetterStateService = mock(PurviewDeadLetterStateService.class);
        deadLetterRetryService = mock(PurviewDeadLetterRetryService.class);
        controller = new PurviewAdminController(
                connectionService, schemaPlannerService, schemaBootstrapService, fullSyncService,
                incrementalSyncService, archiveReconciliationService, cloudMetadataReconciliationService, containmentReconciliationService, typeReconciliationService,
                deleteResolutionService, jobStateService,
                cursorStateService, stateOverviewService, deadLetterStateService, deadLetterRetryService);
    }

    @Test
    public void testTestConnectionReturnsForbiddenForNonAdmin() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext callContext = mock(CallContext.class);
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.FALSE);
        when(request.getAttribute("CallContext")).thenReturn(callContext);
        controller.setHttpRequest(request);

        ResponseEntity<Map<String, Object>> response = controller.testConnection();

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("error", response.getBody().get("status"));
        assertFalse((Boolean) response.getBody().get("connected"));
    }

    @Test
    public void testTestConnectionReturnsProbeResultForAdmin() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext callContext = mock(CallContext.class);
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(request.getAttribute("CallContext")).thenReturn(callContext);
        controller.setHttpRequest(request);

        PurviewConnectionStatus status = new PurviewConnectionStatus(
                true,
                false,
                "https://example-account.purview.azure.com",
                "datamap/api/atlas/v2",
                "Purview connection succeeded using atlas base path datamap/api/atlas/v2");
        when(connectionService.testConnection()).thenReturn(status);

        ResponseEntity<Map<String, Object>> response = controller.testConnection();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("success", response.getBody().get("status"));
        assertEquals(Boolean.TRUE, response.getBody().get("connected"));
        assertEquals("https://example-account.purview.azure.com", response.getBody().get("endpoint"));
        assertEquals("datamap/api/atlas/v2", response.getBody().get("atlasBasePath"));
        assertEquals(Boolean.FALSE, response.getBody().get("featureEnabled"));
        assertNotNull(response.getBody().get("message"));
        assertTrue(response.getBody().get("message").toString().contains("datamap/api/atlas/v2"));
        verify(connectionService).testConnection();
    }

    @Test
    public void testGetSchemaStateReturnsCurrentStateForAdmin() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext callContext = mock(CallContext.class);
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(request.getAttribute("CallContext")).thenReturn(callContext);
        controller.setHttpRequest(request);

        PurviewSchemaState schemaState = new PurviewSchemaState(
                "NemakiWare",
                "1",
                "schema-hash",
                "2026-03-20T00:00:00Z",
                "admin",
                "applied");
        when(schemaPlannerService.getCurrentSchemaState()).thenReturn(schemaState);

        ResponseEntity<Map<String, Object>> response = controller.getSchemaState();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("NemakiWare", response.getBody().get("collection"));
        assertEquals("1", response.getBody().get("schemaVersion"));
        assertEquals("schema-hash", response.getBody().get("schemaHash"));
    }

    @Test
    public void testGetSchemaDiffReturnsDiffForAdmin() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext callContext = mock(CallContext.class);
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(request.getAttribute("CallContext")).thenReturn(callContext);
        controller.setHttpRequest(request);

        PurviewSchemaDiff diff = new PurviewSchemaDiff(
                "NemakiWare",
                "",
                "",
                "1",
                "desired-hash",
                true,
                java.util.List.of("nemaki_external_asset"),
                java.util.List.of("nemaki_document_has_archive"),
                java.util.List.of("nemakiGovernance"));
        when(schemaPlannerService.getSchemaDiff()).thenReturn(diff);

        ResponseEntity<Map<String, Object>> response = controller.getTypeDefinitionDiff();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Boolean.TRUE, response.getBody().get("applyRequired"));
        assertEquals("desired-hash", response.getBody().get("desiredSchemaHash"));
    }

    @Test
    public void testApplyTypeDefinitionsReturnsBootstrapResultForAdmin() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext callContext = mock(CallContext.class);
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(callContext.getUsername()).thenReturn("admin");
        when(request.getAttribute("CallContext")).thenReturn(callContext);
        controller.setHttpRequest(request);

        PurviewSchemaState schemaState = new PurviewSchemaState(
                "NemakiWare",
                "1",
                "desired-hash",
                "2026-03-20T01:00:00Z",
                "admin",
                "applied");
        PurviewSchemaDiff diff = new PurviewSchemaDiff(
                "NemakiWare", "", "", "1", "desired-hash", true,
                java.util.List.of("nemaki_external_asset"),
                java.util.List.of("nemaki_document_has_archive"),
                java.util.List.of("nemakiGovernance"));
        PurviewSchemaApplyResult applyResult = new PurviewSchemaApplyResult(true, "schema applied", schemaState, diff);
        PurviewJobState jobState = new PurviewJobState(
                "job-bootstrap-001", "TYPE_BOOTSTRAP", "collection:NemakiWare", "COMPLETED",
                "2026-03-20T01:00:00Z", "2026-03-20T01:00:01Z", 3, 0, "desired-hash", "");
        when(schemaBootstrapService.startTypeBootstrap("admin"))
                .thenReturn(new PurviewSchemaBootstrapResult(jobState, applyResult));

        ResponseEntity<Map<String, Object>> response = controller.applyTypeDefinitions();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Boolean.TRUE, response.getBody().get("applied"));
        assertEquals("schema applied", response.getBody().get("message"));
        assertEquals("desired-hash", response.getBody().get("schemaHash"));
        assertEquals("job-bootstrap-001", response.getBody().get("jobId"));
        assertEquals("TYPE_BOOTSTRAP", response.getBody().get("jobKind"));
        assertEquals("COMPLETED", response.getBody().get("status"));
        verify(schemaBootstrapService).startTypeBootstrap("admin");
    }

    @Test
    public void testStartFullSyncReturnsJobStateForAdmin() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext callContext = mock(CallContext.class);
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(callContext.getUsername()).thenReturn("admin");
        when(request.getAttribute("CallContext")).thenReturn(callContext);
        controller.setHttpRequest(request);

        PurviewJobState jobState = new PurviewJobState(
                "job-001", "FULL_SYNC", "bedroom", "COMPLETED",
                "2026-03-20T02:00:00Z", "2026-03-20T02:01:00Z", 0, 0, "noop", "");
        when(fullSyncService.startFullSync("bedroom", "admin")).thenReturn(jobState);

        ResponseEntity<Map<String, Object>> response = controller.startFullSync("bedroom");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("job-001", response.getBody().get("jobId"));
        assertEquals("COMPLETED", response.getBody().get("status"));
        verify(fullSyncService).startFullSync("bedroom", "admin");
    }

    @Test
    public void testGetDeadLettersReturnsPersistedEntriesForAdmin() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext callContext = mock(CallContext.class);
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(request.getAttribute("CallContext")).thenReturn(callContext);
        controller.setHttpRequest(request);

        when(deadLetterStateService.listDeadLetterStates()).thenReturn(java.util.List.of(new PurviewDeadLetterState(
                "bedroom",
                "content-change-log",
                "object-101",
                "nemaki_document",
                "nemaki://bedroom/objects/object-101",
                "2026-03-20T05:00:00Z",
                "2026-03-20T05:01:00Z",
                2,
                "101",
                "publish failed")));

        ResponseEntity<Map<String, Object>> response = controller.getDeadLetters();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, ((java.util.List<?>) response.getBody().get("deadLetters")).size());
        Map<?, ?> deadLetter = (Map<?, ?>) ((java.util.List<?>) response.getBody().get("deadLetters")).get(0);
        assertEquals("object-101", deadLetter.get("entryKey"));
        assertEquals("content-change-log", deadLetter.get("streamKind"));
        verify(deadLetterStateService).listDeadLetterStates();
    }

    @Test
    public void testStartRetryFailedReturnsJobStateForAdmin() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext callContext = mock(CallContext.class);
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(callContext.getUsername()).thenReturn("admin");
        when(request.getAttribute("CallContext")).thenReturn(callContext);
        controller.setHttpRequest(request);

        PurviewJobState jobState = new PurviewJobState(
                "job-retry-001", "RETRY_FAILED", "bedroom", "COMPLETED",
                "2026-03-20T02:00:00Z", "2026-03-20T02:01:00Z", 2, 0, "token-2", "");
        when(deadLetterRetryService.startRetryFailed("bedroom", "admin")).thenReturn(jobState);

        ResponseEntity<Map<String, Object>> response = controller.startRetryFailed("bedroom");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("job-retry-001", response.getBody().get("jobId"));
        assertEquals("RETRY_FAILED", response.getBody().get("jobKind"));
        verify(deadLetterRetryService).startRetryFailed("bedroom", "admin");
    }

    @Test
    public void testStartIncrementalSyncReturnsJobStateForAdmin() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext callContext = mock(CallContext.class);
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(callContext.getUsername()).thenReturn("admin");
        when(request.getAttribute("CallContext")).thenReturn(callContext);
        controller.setHttpRequest(request);

        PurviewJobState jobState = new PurviewJobState(
                "job-002", "INCREMENTAL_SYNC", "bedroom", "COMPLETED",
                "2026-03-20T03:00:00Z", "2026-03-20T03:00:01Z", 2, 0, "101", "");
        when(incrementalSyncService.startIncrementalSync("bedroom", "admin")).thenReturn(jobState);

        ResponseEntity<Map<String, Object>> response = controller.startIncrementalSync("bedroom");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("job-002", response.getBody().get("jobId"));
        assertEquals("INCREMENTAL_SYNC", response.getBody().get("jobKind"));
        assertEquals(2, response.getBody().get("processedCount"));
        verify(incrementalSyncService).startIncrementalSync("bedroom", "admin");
    }

    @Test
    public void testStartCloudMetadataReconciliationReturnsJobStateForAdmin() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext callContext = mock(CallContext.class);
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(callContext.getUsername()).thenReturn("admin");
        when(request.getAttribute("CallContext")).thenReturn(callContext);
        controller.setHttpRequest(request);

        PurviewJobState jobState = new PurviewJobState(
                "job-002b", "CLOUD_METADATA_RECONCILIATION", "bedroom", "COMPLETED",
                "2026-03-20T03:10:00Z", "2026-03-20T03:10:01Z", 2, 0, "", "");
        when(cloudMetadataReconciliationService.startCloudMetadataReconciliation("bedroom", "admin")).thenReturn(jobState);

        ResponseEntity<Map<String, Object>> response = controller.startCloudMetadataReconciliation("bedroom");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("job-002b", response.getBody().get("jobId"));
        assertEquals("CLOUD_METADATA_RECONCILIATION", response.getBody().get("jobKind"));
        verify(cloudMetadataReconciliationService).startCloudMetadataReconciliation("bedroom", "admin");
    }

    @Test
    public void testStartContainmentReconciliationReturnsJobStateForAdmin() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext callContext = mock(CallContext.class);
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(callContext.getUsername()).thenReturn("admin");
        when(request.getAttribute("CallContext")).thenReturn(callContext);
        controller.setHttpRequest(request);

        PurviewJobState jobState = new PurviewJobState(
                "job-002c", "CONTAINMENT_RECONCILIATION", "bedroom", "COMPLETED",
                "2026-03-20T03:11:00Z", "2026-03-20T03:11:01Z", 2, 0, "", "");
        when(containmentReconciliationService.startContainmentReconciliation("bedroom", "admin")).thenReturn(jobState);

        ResponseEntity<Map<String, Object>> response = controller.startContainmentReconciliation("bedroom");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("job-002c", response.getBody().get("jobId"));
        assertEquals("CONTAINMENT_RECONCILIATION", response.getBody().get("jobKind"));
        verify(containmentReconciliationService).startContainmentReconciliation("bedroom", "admin");
    }

    @Test
    public void testStartArchiveReconciliationReturnsJobStateForAdmin() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext callContext = mock(CallContext.class);
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(callContext.getUsername()).thenReturn("admin");
        when(request.getAttribute("CallContext")).thenReturn(callContext);
        controller.setHttpRequest(request);

        PurviewJobState jobState = new PurviewJobState(
                "job-002a", "ARCHIVE_RECONCILIATION", "bedroom", "COMPLETED",
                "2026-03-20T03:05:00Z", "2026-03-20T03:05:01Z", 1, 0, "token-101", "");
        when(archiveReconciliationService.startArchiveReconciliation("bedroom", "admin")).thenReturn(jobState);

        ResponseEntity<Map<String, Object>> response = controller.startArchiveReconciliation("bedroom");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("job-002a", response.getBody().get("jobId"));
        assertEquals("ARCHIVE_RECONCILIATION", response.getBody().get("jobKind"));
        verify(archiveReconciliationService).startArchiveReconciliation("bedroom", "admin");
    }

    @Test
    public void testStartTypeReconciliationReturnsJobStateForAdmin() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext callContext = mock(CallContext.class);
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(callContext.getUsername()).thenReturn("admin");
        when(request.getAttribute("CallContext")).thenReturn(callContext);
        controller.setHttpRequest(request);

        PurviewJobState jobState = new PurviewJobState(
                "job-002b", "TYPE_RECONCILIATION", "bedroom", "COMPLETED",
                "2026-03-20T03:06:00Z", "2026-03-20T03:06:01Z", 4, 0, "", "");
        when(typeReconciliationService.startTypeReconciliation("bedroom", "admin")).thenReturn(jobState);

        ResponseEntity<Map<String, Object>> response = controller.startTypeReconciliation("bedroom");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("job-002b", response.getBody().get("jobId"));
        assertEquals("TYPE_RECONCILIATION", response.getBody().get("jobKind"));
        verify(typeReconciliationService).startTypeReconciliation("bedroom", "admin");
    }

    @Test
    public void testGetJobReturnsPersistedJobStateForAdmin() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext callContext = mock(CallContext.class);
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(request.getAttribute("CallContext")).thenReturn(callContext);
        controller.setHttpRequest(request);

        PurviewJobState jobState = new PurviewJobState(
                "job-001", "FULL_SYNC", "bedroom", "COMPLETED",
                "2026-03-20T02:00:00Z", "2026-03-20T02:01:00Z", 0, 0, "noop", "");
        when(jobStateService.getJobState("job-001")).thenReturn(jobState);

        ResponseEntity<Map<String, Object>> response = controller.getJob("job-001");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("job-001", response.getBody().get("jobId"));
        assertEquals("FULL_SYNC", response.getBody().get("jobKind"));
        assertEquals("bedroom", response.getBody().get("repositoryId"));
    }

    @Test
    public void testStartDeleteResolutionReturnsJobStateForAdmin() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext callContext = mock(CallContext.class);
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(callContext.getUsername()).thenReturn("admin");
        when(request.getAttribute("CallContext")).thenReturn(callContext);
        controller.setHttpRequest(request);

        PurviewJobState jobState = new PurviewJobState(
                "job-003", "DELETE_RESOLUTION", "bedroom", "COMPLETED",
                "2026-03-20T03:10:00Z", "2026-03-20T03:10:01Z", 1, 0, "101", "");
        when(deleteResolutionService.startDeleteResolution("bedroom", "admin")).thenReturn(jobState);

        ResponseEntity<Map<String, Object>> response = controller.startDeleteResolution("bedroom");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("job-003", response.getBody().get("jobId"));
        assertEquals("DELETE_RESOLUTION", response.getBody().get("jobKind"));
        verify(deleteResolutionService).startDeleteResolution("bedroom", "admin");
    }

    @Test
    public void testGetCursorStateReturnsPersistedCursorForAdmin() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext callContext = mock(CallContext.class);
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(request.getAttribute("CallContext")).thenReturn(callContext);
        controller.setHttpRequest(request);

        PurviewCursorState cursorState = new PurviewCursorState(
                "bedroom",
                "content-change-log",
                "token-100",
                "changeToken",
                "2026-03-20T04:00:00Z",
                "2026-03-20T04:00:00Z",
                "",
                "",
                0,
                0);
        when(cursorStateService.getCursorState("bedroom", "content-change-log")).thenReturn(cursorState);

        ResponseEntity<Map<String, Object>> response = controller.getCursorState("bedroom", "content-change-log");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("token-100", response.getBody().get("cursor"));
        assertEquals("changeToken", response.getBody().get("cursorKind"));
        assertEquals("2026-03-20T04:00:00Z", response.getBody().get("lastRunAt"));
    }

    @Test
    public void testGetStateReturnsAggregatedOverviewForAdmin() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext callContext = mock(CallContext.class);
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(request.getAttribute("CallContext")).thenReturn(callContext);
        controller.setHttpRequest(request);

        PurviewStateOverview overview = new PurviewStateOverview(
                "NemakiWare",
                new PurviewSchemaState("NemakiWare", "1", "schema-hash", "2026-03-20T01:00:00Z", "admin", "applied"),
                java.util.List.of(
                        new PurviewJobState(
                                "job-002", "TYPE_BOOTSTRAP", "collection:NemakiWare", "COMPLETED",
                                "2026-03-20T03:00:00Z", "2026-03-20T03:00:05Z", 5, 0, "schema-hash", "")),
                java.util.List.of(
                        new PurviewCursorState(
                                "bedroom", "content-change-log", "token-100", "changeToken",
                                "2026-03-20T04:00:00Z", "2026-03-20T04:00:01Z", "", "", 0, 0)),
                java.util.List.of(
                        new PurviewLockState("bedroom", "FULL_SYNC", true, "job-001", "2026-03-20T02:00:00Z")),
                java.util.List.of(
                        new PurviewTombstoneState(
                                "bedroom", "doc-001", "nemaki_document",
                                "nemaki://bedroom/objects/doc-001", "token-101",
                                "2026-03-20T05:00:00Z", "2026-03-20T05:00:05Z", "PENDING")),
                java.util.List.of(
                        new PurviewDeadLetterState(
                                "bedroom", "content-change-log", "object-101", "nemaki_document",
                                "nemaki://bedroom/objects/object-101",
                                "2026-03-20T05:10:00Z", "2026-03-20T05:11:00Z", 2, "token-102", "publish failed")));
        when(schemaPlannerService.getCurrentSchemaState())
                .thenReturn(new PurviewSchemaState("NemakiWare", "", "", "", "", ""));
        when(stateOverviewService.getStateOverview("NemakiWare")).thenReturn(overview);

        ResponseEntity<Map<String, Object>> response = controller.getState();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("NemakiWare", response.getBody().get("collection"));
        assertTrue(response.getBody().containsKey("schemaState"));
        assertEquals(1, ((java.util.List<?>) response.getBody().get("jobs")).size());
        assertEquals(1, ((java.util.List<?>) response.getBody().get("cursors")).size());
        assertEquals(1, ((java.util.List<?>) response.getBody().get("locks")).size());
        assertEquals(1, ((java.util.List<?>) response.getBody().get("tombstones")).size());
        assertEquals(1, ((java.util.List<?>) response.getBody().get("deadLetters")).size());
        verify(stateOverviewService).getStateOverview("NemakiWare");
    }
}
