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
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenReturn(p);

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
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenReturn(p);

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
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenReturn(p);

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
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenReturn(p);

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

    @Test
    void connectorOmitted_validDefault_isStampedOntoRequestAndDispatched() {
        // Non-admin omits connectorId; profile.defaultConnectorId is valid,
        // in allowedConnectorIds, exists, and is still delegated. The gate
        // must approve AND stamp the default connectorId onto the request
        // so downstream dispatch can route by connector archetype rather
        // than fall back to filename heuristics.
        CallContext ctx = nonAdminContext();
        ImportProfileDefinition p = delegatedProfile();
        when(importProfileDefinitionService.get(PROF)).thenReturn(p);
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenReturn(p);

        ExternalIngestRequest req = baseRequest();
        req.setConnectorId(null);   // ← the omission case

        when(ingestAuthorizationService.resolveFolderId(REPO, FOLDER, null)).thenReturn(FOLDER);
        when(ingestAuthorizationService.canManageProfileForFolder(ctx, REPO, FOLDER)).thenReturn(true);
        ConnectorDefinition c = delegatedConnector();
        when(connectorDefinitionService.get(CONN)).thenReturn(c);
        when(ingestAuthorizationService.canUseConnectorForDelegatedProfile(ctx, REPO, c, FOLDER))
                .thenReturn(true);

        ExternalIngestResult ok = ExternalIngestResult.success("src-1", "obj-1", "1.0", false, null);
        when(canonicalImportService.execute(eq(ctx), any(ExternalIngestRequest.class))).thenReturn(ok);

        ResponseEntity<ExternalIngestResult> res = ingest(req);
        assertEquals(HttpStatus.OK, res.getStatusCode());

        // Capture the request passed downstream and assert the stamping
        org.mockito.ArgumentCaptor<ExternalIngestRequest> captor =
                org.mockito.ArgumentCaptor.forClass(ExternalIngestRequest.class);
        verify(canonicalImportService).execute(eq(ctx), captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(CONN, captor.getValue().getConnectorId(),
                "default connectorId must be stamped onto the request before dispatch");
    }

    @Test
    void connectorOmitted_defaultNotInAllowedConnectors_isRefused() {
        // Corrupted record: profile.defaultConnectorId points to a connector
        // that isn't in allowedConnectorIds. The runtime gate must close
        // this just like an explicit-but-disallowed connectorId would.
        CallContext ctx = nonAdminContext();
        ImportProfileDefinition p = delegatedProfile();
        p.setDefaultConnectorId("rogue-conn");                 // not in allowed list
        p.setAllowedConnectorIds(java.util.List.of(CONN));     // canonical list
        when(importProfileDefinitionService.get(PROF)).thenReturn(p);
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenReturn(p);

        ExternalIngestRequest req = baseRequest();
        req.setConnectorId(null);

        when(ingestAuthorizationService.resolveFolderId(REPO, FOLDER, null)).thenReturn(FOLDER);
        when(ingestAuthorizationService.canManageProfileForFolder(ctx, REPO, FOLDER)).thenReturn(true);

        ResponseEntity<ExternalIngestResult> res = ingest(req);
        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode());
        // canUseConnector check must NOT be reached for the rogue default,
        // because allowedConnectorIds membership is checked first
        verify(connectorDefinitionService, never()).get(eq("rogue-conn"));
        verifyNoInteractions(canonicalImportService);
    }

    @Test
    void connectorOmitted_noDefault_isRefused() {
        // Defensive: profile has no defaultConnectorId at all. Non-admin
        // ingest can't deterministically resolve a connector, so the gate
        // refuses rather than letting the downstream filename heuristic
        // pick something.
        CallContext ctx = nonAdminContext();
        ImportProfileDefinition p = delegatedProfile();
        p.setDefaultConnectorId(null);
        when(importProfileDefinitionService.get(PROF)).thenReturn(p);
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenReturn(p);

        ExternalIngestRequest req = baseRequest();
        req.setConnectorId(null);

        when(ingestAuthorizationService.resolveFolderId(REPO, FOLDER, null)).thenReturn(FOLDER);
        when(ingestAuthorizationService.canManageProfileForFolder(ctx, REPO, FOLDER)).thenReturn(true);

        ResponseEntity<ExternalIngestResult> res = ingest(req);
        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode());
        verifyNoInteractions(canonicalImportService);
    }

    // ──────────────────────────────────────────────────────────────────
    // 4. Happy path — everything passes, dispatch reaches the service
    // ──────────────────────────────────────────────────────────────────

    @Test
    void twoRowsOfOneProfileInThisRepository_isRefusedBeforeAnyImport() {
        // The gate authorises a folder and a connector from the row it reads; the import
        // service reads the profile AGAIN. With two rows of one profileId in one repository
        // the selector can hand each side a different row, so the folder that was authorised
        // and the folder that receives the content need not be the same. The gate resolves
        // index-free and refuses the pair, so no second read can differ. A review showed the
        // authorisation boundary was wider than the DELETE verb.
        nonAdminContext();
        when(importProfileDefinitionService.get(PROF)).thenReturn(delegatedProfile());
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenThrow(
                new ImportProfileDefinitionServiceImpl.ProfileHasTwinRowsException(
                        "import profile " + PROF + " has more than one definition row"));

        ResponseEntity<ExternalIngestResult> res = ingest(baseRequest());

        assertEquals(HttpStatus.CONFLICT, res.getStatusCode(),
                "a profile with two rows in this repository was authorised from one of them");
        verifyNoInteractions(canonicalImportService);
    }

    @Test
    void aProfileBoundToNoRepository_isRefused() {
        // A row with repositoryId == null is not a wildcard. The confinement check read
        // "repositoryId != null && !equals(caller)", so null slipped through and a corrupt or
        // half-migrated row acted as a profile for EVERY repository — invisible to the admin
        // API, which is repository-confined, while the runtime used it as configuration. The
        // service that lets an administrator delete such a row calls it "belonging to none".
        nonAdminContext();
        ImportProfileDefinition unowned = delegatedProfile();
        unowned.setRepositoryId(null);
        when(importProfileDefinitionService.get(PROF)).thenReturn(unowned);

        ResponseEntity<ExternalIngestResult> res = ingest(baseRequest());

        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode(),
                "a profile bound to no repository was accepted as this repository's");
        verifyNoInteractions(canonicalImportService);
    }

    @Test
    void aRetryableImportRefusal_is503NotAServerError() {
        // Every refusal this batch added to the import path ends in "retry shortly", and the
        // status mapper matched none of its substrings — so a rebuilding index answered 500,
        // the status the admin controller's own comment calls "what opens tickets for a
        // condition a retry resolves". A review measured the split: the same twin state was
        // 409 through this gate and 500 through the import.
        CallContext ctx = nonAdminContext();
        ImportProfileDefinition p = delegatedProfile();
        when(importProfileDefinitionService.get(PROF)).thenReturn(p);
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenReturn(p);
        when(ingestAuthorizationService.resolveFolderId(REPO, FOLDER, null)).thenReturn(FOLDER);
        when(ingestAuthorizationService.canManageProfileForFolder(ctx, REPO, FOLDER)).thenReturn(true);
        ConnectorDefinition c = delegatedConnector();
        when(connectorDefinitionService.get(CONN)).thenReturn(c);
        when(ingestAuthorizationService.canUseConnectorForDelegatedProfile(ctx, REPO, c, FOLDER))
                .thenReturn(true);
        when(canonicalImportService.execute(eq(ctx), any(ExternalIngestRequest.class)))
                .thenReturn(ExternalIngestResult.error("src-1", "import profile " + PROF
                        + " exists but could not be read for this import; retry shortly"));

        ResponseEntity<ExternalIngestResult> res = ingest(baseRequest());

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode(),
                "a read that could not be answered was reported as a server fault");
    }

    @Test
    void aStandingTwinPairFromTheImport_is409NotAServerError() {
        // The admin import path reaches the same state the gate refuses with 409. It answered
        // 500 — one condition, two statuses, depending on which door the caller used.
        CallContext ctx = nonAdminContext();
        ImportProfileDefinition p = delegatedProfile();
        when(importProfileDefinitionService.get(PROF)).thenReturn(p);
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenReturn(p);
        when(ingestAuthorizationService.resolveFolderId(REPO, FOLDER, null)).thenReturn(FOLDER);
        when(ingestAuthorizationService.canManageProfileForFolder(ctx, REPO, FOLDER)).thenReturn(true);
        ConnectorDefinition c = delegatedConnector();
        when(connectorDefinitionService.get(CONN)).thenReturn(c);
        when(ingestAuthorizationService.canUseConnectorForDelegatedProfile(ctx, REPO, c, FOLDER))
                .thenReturn(true);
        when(canonicalImportService.execute(eq(ctx), any(ExternalIngestRequest.class)))
                .thenReturn(ExternalIngestResult.error("src-1", "import profile " + PROF
                        + " has 2 definition rows; an update would write to one of them"));

        ResponseEntity<ExternalIngestResult> res = ingest(baseRequest());

        assertEquals(HttpStatus.CONFLICT, res.getStatusCode(),
                "a standing pair an administrator must resolve was reported as a server fault");
    }

    @Test
    void aGetForRepositoryTwinMessage_is409NotAServerError() {
        // The production getForRepository wording is "more than one definition row"
        // (singular). Matching only "definition rows" left this door at 500. A review
        // measured the split.
        CallContext ctx = nonAdminContext();
        ImportProfileDefinition p = delegatedProfile();
        when(importProfileDefinitionService.get(PROF)).thenReturn(p);
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenReturn(p);
        when(ingestAuthorizationService.resolveFolderId(REPO, FOLDER, null)).thenReturn(FOLDER);
        when(ingestAuthorizationService.canManageProfileForFolder(ctx, REPO, FOLDER)).thenReturn(true);
        ConnectorDefinition c = delegatedConnector();
        when(connectorDefinitionService.get(CONN)).thenReturn(c);
        when(ingestAuthorizationService.canUseConnectorForDelegatedProfile(ctx, REPO, c, FOLDER))
                .thenReturn(true);
        when(canonicalImportService.execute(eq(ctx), any(ExternalIngestRequest.class)))
                .thenReturn(ExternalIngestResult.error("src-1",
                        "import profile " + PROF
                                + " has more than one definition row in repository '"
                                + REPO + "'; resolve the pair first"));

        ResponseEntity<ExternalIngestResult> res = ingest(baseRequest());

        assertEquals(HttpStatus.CONFLICT, res.getStatusCode(),
                "the import-path twin wording was reported as a server fault");
    }

    @Test
    void aMailRepositoryMismatch_is403NotAServerError() {
        // Mail early validation used to answer "Profile repository mismatch", which the
        // status mapper did not match — 500 on the mail door, 403 through execute(). A
        // review measured the split. The short wording is still recognised so a leftover
        // message cannot reopen the ticket.
        CallContext ctx = mock(CallContext.class);
        when(ctx.getUsername()).thenReturn("admin");
        when(httpRequest.getAttribute("CallContext")).thenReturn(ctx);
        when(ingestAuthorizationService.isAdmin(ctx)).thenReturn(true);

        ExternalIngestRequest req = baseRequest();
        req.setSourceObjectType("message");
        req.setFileName("note.eml");
        req.setConnectorId(null);
        when(canonicalImportService.executeMailImport(eq(ctx), any(ExternalIngestRequest.class)))
                .thenReturn(ExternalIngestResult.error("src-1", "Profile repository mismatch"));

        ResponseEntity<ExternalIngestResult> res = ingest(req);

        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode(),
                "a mail-path repository mismatch was reported as a server fault");
    }

    @Test
    void theGateStampsTheRowItAuthorized() {
        // The import resolves the profile again and refuses when the row is not the one that
        // was authorised — which measures nothing unless the gate actually says which row
        // that was. This is the other half of that pair.
        CallContext ctx = nonAdminContext();
        ImportProfileDefinition p = delegatedProfile();
        when(importProfileDefinitionService.get(PROF)).thenReturn(p);
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenReturn(p);
        when(ingestAuthorizationService.resolveFolderId(REPO, FOLDER, null)).thenReturn(FOLDER);
        when(ingestAuthorizationService.canManageProfileForFolder(ctx, REPO, FOLDER)).thenReturn(true);
        ConnectorDefinition c = delegatedConnector();
        when(connectorDefinitionService.get(CONN)).thenReturn(c);
        when(ingestAuthorizationService.canUseConnectorForDelegatedProfile(ctx, REPO, c, FOLDER))
                .thenReturn(true);
        when(canonicalImportService.execute(eq(ctx), any(ExternalIngestRequest.class)))
                .thenReturn(ExternalIngestResult.success("src-1", "obj-1", "1.0", false, null));

        ingest(baseRequest());

        org.mockito.ArgumentCaptor<ExternalIngestRequest> sent =
                org.mockito.ArgumentCaptor.forClass(ExternalIngestRequest.class);
        verify(canonicalImportService).execute(eq(ctx), sent.capture());
        assertEquals(CanonicalImportServiceImpl.authorizationFingerprint(p),
                sent.getValue().getAuthorizedProfileFingerprint(),
                "the import was dispatched without saying which row had been authorised");
    }

    @Test
    void allGatesPass_dispatchesToCanonicalImportService() {
        CallContext ctx = nonAdminContext();
        ImportProfileDefinition p = delegatedProfile();
        when(importProfileDefinitionService.get(PROF)).thenReturn(p);
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenReturn(p);

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

    // ──────────────────────────────────────────────────────────────────
    // A connector read that did not answer must not become "no connector"
    // ──────────────────────────────────────────────────────────────────

    private CallContext adminContext() {
        CallContext ctx = mock(CallContext.class);
        when(ctx.getUsername()).thenReturn("admin");
        when(httpRequest.getAttribute("CallContext")).thenReturn(ctx);
        when(ingestAuthorizationService.isAdmin(ctx)).thenReturn(true);
        return ctx;
    }

    /** A request that names a connector and whose type alone would route it to the mail flow. */
    private ExternalIngestRequest messageOnANamedConnector() {
        ExternalIngestRequest req = baseRequest();
        req.setSourceObjectType("message");
        req.setConnectorId(CONN);
        return req;
    }

    @Test
    void aFailedConnectorReadDoesNotPickTheImportFlowFromTheFileName() {
        // resolveConnectorArchetype answered null for a FAILED read, and null is what the
        // dispatch reads as "no connector context" — so the request was committed through the
        // flow the file name and sourceObjectType suggest. On a CHAT_CONTEXT connector,
        // "message" is parsed as mail, and the chosen flow does not re-check the archetype.
        adminContext();
        when(connectorDefinitionService.get(CONN))
                .thenThrow(new RuntimeException("connection reset"));

        assertThrows(ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException.class,
                () -> ingest(messageOnANamedConnector()),
                "a connector read that failed was answered as 'no connector context'");
        verifyNoInteractions(canonicalImportService);
    }

    @Test
    void aHiddenConnectorRowDoesNotPickTheImportFlowFromTheFileName() {
        // The other half: get() answers null for a row the selector cannot show while its
        // index rebuilds, exactly as it does for an absent connector. Only the index-free
        // walk separates them, and only this one may not fall through.
        adminContext();
        when(connectorDefinitionService.get(CONN)).thenReturn(null);
        when(connectorDefinitionService.existsIndexFree(CONN)).thenReturn(true);

        assertThrows(ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException.class,
                () -> ingest(messageOnANamedConnector()),
                "a connector row the index could not show was answered as 'no connector'");
        verifyNoInteractions(canonicalImportService);
    }

    @Test
    void aConnectorTheWalkSaysIsAbsentStillFallsThroughToTheHeuristics() {
        // The over-throw control. An unknown connectorId must keep behaving as it always has;
        // turning absence into a 503 would make every mistyped id a retry loop.
        CallContext ctx = adminContext();
        when(connectorDefinitionService.get(CONN)).thenReturn(null);
        when(connectorDefinitionService.existsIndexFree(CONN)).thenReturn(false);
        when(canonicalImportService.executeMailImport(eq(ctx), any(ExternalIngestRequest.class)))
                .thenReturn(ExternalIngestResult.error("src-1", "downstream said no"));

        assertDoesNotThrow(() -> ingest(messageOnANamedConnector()),
                "an absent connector started refusing — every unknown id becomes a 503");
        verify(canonicalImportService).executeMailImport(eq(ctx), any(ExternalIngestRequest.class));
    }

    @Test
    void theMultipartDoorAnswersTheSameRefusalAsTheJsonDoor() throws Exception {
        // ingestMultipart wraps parsing AND the whole ingest in one catch(Exception) -> 400
        // "Invalid request", so the same ingest answered 503 as JSON and 400 as multipart —
        // the 400 asserting something about the caller's request that no read established.
        adminContext();
        when(connectorDefinitionService.get(CONN))
                .thenThrow(new ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException(
                        "connector " + CONN + " exists but could not be read as that connector"));
        String json = "{\"profileId\":\"" + PROF + "\",\"connectorId\":\"" + CONN
                + "\",\"sourceObjectId\":\"src-1\",\"sourceObjectType\":\"message\"}";

        assertThrows(ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException.class,
                () -> controller.ingestMultipart(REPO, json, null),
                "the multipart door swallowed a read refusal as \"Invalid request\" (400)");
        verifyNoInteractions(canonicalImportService);
    }

    @Test
    void aConnectorRowWithNoArchetypeDoesNotPickTheFlowFromTheFileName() {
        // The other arm of the same hole: the row READS, and says nothing about what it is.
        // Returning null there reaches the identical wrong dispatch — an audit of the first
        // fix found the arm still open, and the sibling DLQ replay refuses this same input
        // with the same reasoning.
        CallContext ctx = adminContext();
        ConnectorDefinition noArchetype = new ConnectorDefinition();
        noArchetype.setConnectorId(CONN);
        noArchetype.setEnabled(true);
        when(connectorDefinitionService.get(CONN)).thenReturn(noArchetype);

        ExternalIngestController.ConnectorArchetypeUnusableException refused = assertThrows(
                ExternalIngestController.ConnectorArchetypeUnusableException.class,
                () -> ingest(messageOnANamedConnector()),
                "a connector that does not say what it is was answered as 'no connector'");
        assertTrue(refused.getMessage().contains("sourceArchetype"),
                "the refusal does not tell the operator what to fix: " + refused.getMessage());
        verifyNoInteractions(canonicalImportService);
        // Not a retry: the walk is not even consulted, because the row was read.
        verify(connectorDefinitionService, never()).existsIndexFree(CONN);
        assertEquals(HttpStatus.CONFLICT,
                controller.connectorCannotSayWhatItIs(refused).getStatusCode(),
                "a row an operator has to fix was answered as a retry");
        assertNotNull(ctx);
    }

    @Test
    void anUnwiredConnectorServiceDoesNotPickTheFlowFromTheFileName() throws Exception {
        // The third arm, found by two reviewers independently after the first two were
        // closed: an unwired service answered null, and the dispatch reads null as "no
        // connector context". Latent behind Spring's required wiring, but this class exists
        // to exercise exactly the "service is null" modes, and the non-admin path already
        // refuses on this one.
        adminContext();
        inject("connectorDefinitionService", null);

        ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException refused = assertThrows(
                ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException.class,
                () -> ingest(messageOnANamedConnector()),
                "an unwired connector service was answered as 'no connector context'");
        // On the MESSAGE, because the refusal alone does not discriminate: without the guard
        // the very next line dereferences the null service, the NullPointerException lands on
        // the failed-read arm, and the caller still gets a refusal — with a reason that names
        // a read failure instead of the wiring. The control measured that and did not fire
        // until this assertion was added.
        assertTrue(refused.getMessage().contains("not wired on this node"),
                "an unwired node was reported as a failed read: " + refused.getMessage());
        verifyNoInteractions(canonicalImportService);
    }

    @Test
    void aDelegatedIngestRefusedByAReadIsStillAudited() throws Exception {
        // A delegated attempt that leaves by exception left NO audit entry, while the same
        // input was audited before those refusals existed — and the javadoc said the trail
        // covered every outcome. A review found the gap; the note that followed said closing
        // it would mean touching the authorisation gate, and the next review showed the
        // dispatch call site already holds everything the audit needs.
        CallContext ctx = nonAdminContext();
        jp.aegif.nemaki.audit.AuditLogger auditLogger =
                mock(jp.aegif.nemaki.audit.AuditLogger.class);
        inject("auditLogger", auditLogger);
        ImportProfileDefinition p = delegatedProfile();
        when(importProfileDefinitionService.get(PROF)).thenReturn(p);
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenReturn(p);
        when(ingestAuthorizationService.resolveFolderId(REPO, FOLDER, null)).thenReturn(FOLDER);
        when(ingestAuthorizationService.canManageProfileForFolder(ctx, REPO, FOLDER))
                .thenReturn(true);
        when(ingestAuthorizationService.canUseConnectorForDelegatedProfile(
                any(), any(), any(), any())).thenReturn(true);
        // The gate reads the connector and does not look at its archetype; the dispatch
        // reads it and refuses. One row serves both, so the gate passes and the refusal is
        // raised exactly where the audit used to be lost.
        ConnectorDefinition noArchetype = delegatedConnector();
        noArchetype.setSourceArchetype(null);
        when(connectorDefinitionService.get(CONN)).thenReturn(noArchetype);

        assertThrows(ExternalIngestController.ConnectorArchetypeUnusableException.class,
                () -> ingest(messageOnANamedConnector()),
                "the dispatch accepted a connector that does not say what it is");

        verify(auditLogger).logOperation(
                any(jp.aegif.nemaki.audit.AuditOperation.class), any(), any(), any(),
                eq(false), any(), any());
        assertNotNull(ctx);
    }

    @Test
    void theRefusalAnswersTheEndpointsOwnDocument() {
        // The handler returned a bare map while every other answer from this endpoint is an
        // ExternalIngestResult, so a client parsing requestId / success / errors got a shape
        // it does not know from the one path that refuses. A review found the undescribed
        // change.
        ResponseEntity<ExternalIngestResult> res = controller.definitionRowsCouldNotBeRead(
                new ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException("nope"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode());
        assertNotNull(res.getBody(), "the refusal answered no document at all");
        assertFalse(res.getBody().isSuccess(), "a refusal reported success");
    }

    @Test
    void aDelegatedIngestRefusedInsideTheGateIsAlsoAudited() throws Exception {
        // The gate READS the connector too, and its two reads sit outside the catch the
        // previous round added around the dispatch — so this half of the audit gap stayed
        // open while the javadoc and the release notes said every outcome was recorded. The
        // control for the other half stayed green under this one's sabotage, which is how it
        // was found to need its own lock.
        CallContext ctx = nonAdminContext();
        jp.aegif.nemaki.audit.AuditLogger auditLogger =
                mock(jp.aegif.nemaki.audit.AuditLogger.class);
        inject("auditLogger", auditLogger);
        ImportProfileDefinition p = delegatedProfile();
        when(importProfileDefinitionService.get(PROF)).thenReturn(p);
        when(importProfileDefinitionService.getForRepository(PROF, REPO)).thenReturn(p);
        when(ingestAuthorizationService.resolveFolderId(REPO, FOLDER, null)).thenReturn(FOLDER);
        when(ingestAuthorizationService.canManageProfileForFolder(ctx, REPO, FOLDER))
                .thenReturn(true);
        // The GATE's own read is the one that refuses.
        when(connectorDefinitionService.get(CONN)).thenThrow(
                new ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException(
                        "connector " + CONN + " exists but could not be read as that connector"));

        assertThrows(ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException.class,
                () -> ingest(messageOnANamedConnector()));

        verify(auditLogger).logOperation(
                any(jp.aegif.nemaki.audit.AuditOperation.class), any(), any(), any(),
                eq(false), any(), any());
    }
}
