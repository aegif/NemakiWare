package jp.aegif.nemaki.rest.ingest;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Direct controller-level tests for the non-admin runtime gate in
 * {@link ExternalIngestController}. Complements the API E2E suite —
 * those need a live deployment and therefore cannot easily exercise
 * "service is null" or "in-memory record corrupted" failure modes
 * that the gate exists to defend against. These tests can.
 *
 * <p>Each test wires a mocked dependency graph by reflection so we can
 * assert exactly which downstream methods are reached. The dependency
 * surface is small — three services + the request — so reflection
 * setter is preferable to standing up a Spring context.
 */
class ExternalIngestControllerGateTest {

    private static final String REPO = "bedroom";
    private static final String USER = "alice";
    private static final String FOLDER = "F-1";
    private static final String PROF = "delg-prof";
    private static final String CONN = "delg-conn";

    private ExternalIngestController controller;
    private CanonicalImportService canonicalImportService;
    private ConnectorDefinitionService connectorDefinitionService;
    private ImportProfileDefinitionService importProfileDefinitionService;
    private IngestAuthorizationService ingestAuthorizationService;
    private HttpServletRequest httpRequest;

    @BeforeEach
    void setUp() throws Exception {
        controller = new ExternalIngestController();
        canonicalImportService = mock(CanonicalImportService.class);
        connectorDefinitionService = mock(ConnectorDefinitionService.class);
        importProfileDefinitionService = mock(ImportProfileDefinitionService.class);
        ingestAuthorizationService = mock(IngestAuthorizationService.class);
        httpRequest = mock(HttpServletRequest.class);

        inject("canonicalImportService", canonicalImportService);
        inject("connectorDefinitionService", connectorDefinitionService);
        inject("importProfileDefinitionService", importProfileDefinitionService);
        inject("ingestAuthorizationService", ingestAuthorizationService);
        inject("httpRequest", httpRequest);
    }

    private void inject(String fieldName, Object value) throws Exception {
        Field f = ExternalIngestController.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(controller, value);
    }

    private CallContext nonAdminContext() {
        CallContext ctx = mock(CallContext.class);
        when(ctx.getUsername()).thenReturn(USER);
        when(httpRequest.getAttribute("CallContext")).thenReturn(ctx);
        when(ingestAuthorizationService.isAdmin(ctx)).thenReturn(false);
        return ctx;
    }

    private ImportProfileDefinition delegatedProfile() {
        ImportProfileDefinition p = new ImportProfileDefinition();
        p.setProfileId(PROF);
        p.setRepositoryId(REPO);
        p.setTargetFolderId(FOLDER);
        p.setDelegated(true);
        p.setEnabled(true);
        p.setAllowedConnectorIds(List.of(CONN));
        p.setDefaultConnectorId(CONN);
        return p;
    }

    private ConnectorDefinition delegatedConnector() {
        ConnectorDefinition c = new ConnectorDefinition();
        c.setConnectorId(CONN);
        c.setSourceArchetype(SourceArchetype.FILE_SHARE);
        c.setSourceSystem("box");
        c.setEnabled(true);
        c.setDelegated(true);
        c.setAllowedFolderIds(List.of(FOLDER));
        return c;
    }

    private ExternalIngestRequest baseRequest() {
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId(PROF);
        req.setConnectorId(CONN);
        req.setSourceObjectId("src-1");
        req.setSourceObjectType("file");
        return req;
    }

    /**
     * Exercise the controller through the public {@code ingestJson} entry
     * so the {@code doIngest} private path is included.
     */
    @SuppressWarnings("unchecked")
    private ResponseEntity<ExternalIngestResult> ingest(ExternalIngestRequest req) {
        return controller.ingestJson(REPO, req);
    }

    // ──────────────────────────────────────────────────────────────────
    // 1. Service-missing fail-closed
    // ──────────────────────────────────────────────────────────────────

    @Test
    void connectorServiceMissing_nonAdmin_returns503() throws Exception {
        nonAdminContext();
        // Wipe just the connector service — should be impossible in production
        // (Spring fails fast), but the runtime null guard must still deny.
        inject("connectorDefinitionService", null);

        ResponseEntity<ExternalIngestResult> res = ingest(baseRequest());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode());
        verifyNoInteractions(canonicalImportService);
    }

    @Test
    void profileServiceMissing_nonAdmin_returns503() throws Exception {
        nonAdminContext();
        inject("importProfileDefinitionService", null);

        ResponseEntity<ExternalIngestResult> res = ingest(baseRequest());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode());
        verifyNoInteractions(canonicalImportService);
    }

    @Test
    void authorizationServiceMissing_nonAdminPath_returns503() throws Exception {
        // Mark caller as non-admin BEFORE we null the service. Then null
        // the service: doIngest's first null check is for ingestAuthorizationService.
        CallContext ctx = mock(CallContext.class);
        when(ctx.getUsername()).thenReturn(USER);
        when(httpRequest.getAttribute("CallContext")).thenReturn(ctx);
        inject("ingestAuthorizationService", null);

        ResponseEntity<ExternalIngestResult> res = ingest(baseRequest());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode());
        verifyNoInteractions(canonicalImportService);
    }

    // ──────────────────────────────────────────────────────────────────
    // 2. Delegated profile with empty allowedConnectorIds is fail-closed
    //    even at runtime (the API rejects it on create/update, but a
    //    legacy or hand-edited record could slip through).
    // ──────────────────────────────────────────────────────────────────

    @Test
    void delegatedProfileWithEmptyAllowedConnectors_isRefused() {
        CallContext ctx = nonAdminContext();
        ImportProfileDefinition p = delegatedProfile();
        p.setAllowedConnectorIds(List.of()); // simulate corrupted record
        when(importProfileDefinitionService.get(PROF)).thenReturn(p);

        ResponseEntity<ExternalIngestResult> res = ingest(baseRequest());
        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode());
        // Must not advance to folder check or import
        verify(ingestAuthorizationService, never()).canManageProfileForFolder(eq(ctx), any(), any());
        verifyNoInteractions(canonicalImportService);
    }

    @Test
    void delegatedProfileWithNullAllowedConnectors_isRefused() {
        nonAdminContext();
        ImportProfileDefinition p = delegatedProfile();
        p.setAllowedConnectorIds(null); // simulate corrupted record
        when(importProfileDefinitionService.get(PROF)).thenReturn(p);

        ResponseEntity<ExternalIngestResult> res = ingest(baseRequest());
        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode());
        verifyNoInteractions(canonicalImportService);
    }

    // ──────────────────────────────────────────────────────────────────
    // 3. Connector delegation revoked between profile-create and execute
    // ──────────────────────────────────────────────────────────────────

    @Test
    void connectorDelegationRevoked_executeIsRefused() {
        CallContext ctx = nonAdminContext();
        ImportProfileDefinition p = delegatedProfile();
        when(importProfileDefinitionService.get(PROF)).thenReturn(p);

        // cmis:all on folder — passes
        when(ingestAuthorizationService.resolveFolderId(REPO, FOLDER, null)).thenReturn(FOLDER);
        when(ingestAuthorizationService.canManageProfileForFolder(ctx, REPO, FOLDER)).thenReturn(true);

        // Connector exists but delegation has been revoked since profile was created
        ConnectorDefinition c = delegatedConnector();
        when(connectorDefinitionService.get(CONN)).thenReturn(c);
        when(ingestAuthorizationService.canUseConnectorForDelegatedProfile(ctx, REPO, c, FOLDER))
                .thenReturn(false);

        ResponseEntity<ExternalIngestResult> res = ingest(baseRequest());
        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode());
        verifyNoInteractions(canonicalImportService);
    }

    @Test
    void defaultConnectorRevoked_executeIsRefused() {
        CallContext ctx = nonAdminContext();
        ImportProfileDefinition p = delegatedProfile();
        when(importProfileDefinitionService.get(PROF)).thenReturn(p);

        // No explicit connectorId on the request → fall back to profile default
        ExternalIngestRequest req = baseRequest();
        req.setConnectorId(null);

        when(ingestAuthorizationService.resolveFolderId(REPO, FOLDER, null)).thenReturn(FOLDER);
        when(ingestAuthorizationService.canManageProfileForFolder(ctx, REPO, FOLDER)).thenReturn(true);

        ConnectorDefinition c = delegatedConnector();
        when(connectorDefinitionService.get(CONN)).thenReturn(c);
        when(ingestAuthorizationService.canUseConnectorForDelegatedProfile(ctx, REPO, c, FOLDER))
                .thenReturn(false);

        ResponseEntity<ExternalIngestResult> res = ingest(req);
        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode());
        verifyNoInteractions(canonicalImportService);
    }

    // ──────────────────────────────────────────────────────────────────
    // 4. Happy path — everything passes, dispatch reaches the service
    // ──────────────────────────────────────────────────────────────────

    @Test
    void allGatesPass_dispatchesToCanonicalImportService() {
        CallContext ctx = nonAdminContext();
        ImportProfileDefinition p = delegatedProfile();
        when(importProfileDefinitionService.get(PROF)).thenReturn(p);

        when(ingestAuthorizationService.resolveFolderId(REPO, FOLDER, null)).thenReturn(FOLDER);
        when(ingestAuthorizationService.canManageProfileForFolder(ctx, REPO, FOLDER)).thenReturn(true);

        ConnectorDefinition c = delegatedConnector();
        when(connectorDefinitionService.get(CONN)).thenReturn(c);
        when(ingestAuthorizationService.canUseConnectorForDelegatedProfile(ctx, REPO, c, FOLDER))
                .thenReturn(true);

        ExternalIngestResult ok = ExternalIngestResult.success("src-1", "obj-1", "1.0", false, null);
        when(canonicalImportService.execute(eq(ctx), any(ExternalIngestRequest.class))).thenReturn(ok);

        ResponseEntity<ExternalIngestResult> res = ingest(baseRequest());
        assertEquals(HttpStatus.OK, res.getStatusCode());
        verify(canonicalImportService).execute(eq(ctx), any(ExternalIngestRequest.class));
    }

    // ──────────────────────────────────────────────────────────────────
    // 5. Admin path remains unchanged (not double-gated)
    // ──────────────────────────────────────────────────────────────────

    @Test
    void adminPath_skipsDelegatedGate() {
        CallContext ctx = mock(CallContext.class);
        when(ctx.getUsername()).thenReturn("admin");
        when(httpRequest.getAttribute("CallContext")).thenReturn(ctx);
        when(ingestAuthorizationService.isAdmin(ctx)).thenReturn(true);

        ExternalIngestResult ok = ExternalIngestResult.success("src-1", "obj-1", "1.0", false, null);
        when(canonicalImportService.execute(eq(ctx), any(ExternalIngestRequest.class))).thenReturn(ok);

        ResponseEntity<ExternalIngestResult> res = ingest(baseRequest());
        assertEquals(HttpStatus.OK, res.getStatusCode());
        // Admin path must not consult the delegation gate methods
        verify(ingestAuthorizationService, never()).resolveFolderId(any(), any(), any());
        verify(ingestAuthorizationService, never()).canManageProfileForFolder(any(), any(), any());
        verify(ingestAuthorizationService, never()).canUseConnectorForDelegatedProfile(any(), any(), any(), any());
        verify(canonicalImportService).execute(eq(ctx), any(ExternalIngestRequest.class));
    }

    // ──────────────────────────────────────────────────────────────────
    // 6. targetFolderOverride forbidden for non-admin
    // ──────────────────────────────────────────────────────────────────

    @Test
    void nonAdminWithTargetFolderOverride_isRefused() {
        nonAdminContext();
        ExternalIngestRequest req = baseRequest();
        req.setTargetFolderOverride("F-other");

        ResponseEntity<ExternalIngestResult> res = ingest(req);
        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode());
        verifyNoInteractions(canonicalImportService);
        // Must not even attempt to load the profile — override check is first
        verifyNoInteractions(importProfileDefinitionService);
    }

    // ──────────────────────────────────────────────────────────────────
    // 7. denialReason → audit propagation
    // ──────────────────────────────────────────────────────────────────

    @Test
    void denialReasonsAreEmittedToAudit() throws Exception {
        // Wire a real audit logger spy to assert details.denialReason flows through
        jp.aegif.nemaki.audit.AuditLogger auditLogger = mock(jp.aegif.nemaki.audit.AuditLogger.class);
        Field f = ExternalIngestController.class.getDeclaredField("auditLogger");
        f.setAccessible(true);
        f.set(controller, auditLogger);

        // 1. targetFolderOverride → TARGET_FOLDER_OVERRIDE_FORBIDDEN
        nonAdminContext();
        ExternalIngestRequest overrideReq = baseRequest();
        overrideReq.setTargetFolderOverride("F-other");
        ingest(overrideReq);

        // 2. profileId missing → PROFILE_ID_REQUIRED
        ExternalIngestRequest noProfileReq = baseRequest();
        noProfileReq.setProfileId(null);
        ingest(noProfileReq);

        // 3. profile not found → PROFILE_NOT_FOUND
        when(importProfileDefinitionService.get(PROF)).thenReturn(null);
        ingest(baseRequest());

        // Verify each call recorded its denialReason in details
        @SuppressWarnings("unchecked")
        java.util.ArrayList<java.util.Map<String, Object>> capturedDetails = new java.util.ArrayList<>();
        verify(auditLogger, times(3)).logOperation(
                any(jp.aegif.nemaki.audit.AuditOperation.class), any(), any(), any(),
                eq(false), any(),
                argThat(detailsMap -> {
                    if (detailsMap == null) return false;
                    capturedDetails.add(new java.util.LinkedHashMap<>(detailsMap));
                    return true;
                }));
        java.util.List<String> reasons = capturedDetails.stream()
                .map(d -> (String) d.get("denialReason"))
                .toList();
        org.junit.jupiter.api.Assertions.assertTrue(reasons.contains("TARGET_FOLDER_OVERRIDE_FORBIDDEN"),
                "expected TARGET_FOLDER_OVERRIDE_FORBIDDEN in " + reasons);
        org.junit.jupiter.api.Assertions.assertTrue(reasons.contains("PROFILE_ID_REQUIRED"),
                "expected PROFILE_ID_REQUIRED in " + reasons);
        org.junit.jupiter.api.Assertions.assertTrue(reasons.contains("PROFILE_NOT_FOUND"),
                "expected PROFILE_NOT_FOUND in " + reasons);
    }
}
