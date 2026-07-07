package jp.aegif.nemaki.rest.ingest;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.audit.AuditLogger;
import jp.aegif.nemaki.util.constant.CallContextKey;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the behaviour of
 * {@link ImportProfileDefinitionController#transferOwnership(String, Map)}.
 *
 * <p>The endpoint exists so an admin can take an existing admin-owned
 * profile and hand it to a folder owner (or pull a delegated profile
 * back into admin management) without the delete + recreate dance.
 * Wrong handling here would defeat the entire delegation gate: e.g.
 * letting a profile become delegated to a user who doesn't actually
 * hold {@code cmis:all} on the target folder.
 *
 * <p>Coverage:
 * <ul>
 *   <li>Auth: non-admin caller → 403, service untouched</li>
 *   <li>Body validation: mode must be "admin" or "delegated"</li>
 *   <li>admin → delegated happy path: forces flags + stamps owner</li>
 *   <li>admin → delegated where new owner lacks cmis:all → 403</li>
 *   <li>admin → delegated where a connector isn't delegated to new
 *       owner → 403</li>
 *   <li>delegated → admin: clears {@code delegated}, leaves other
 *       fields alone</li>
 *   <li>Audit: every successful transfer records mode + new owner</li>
 * </ul>
 */
class ImportProfileOwnershipTransferTest {

    private static final String REPO = "bedroom";
    private static final String PROF = "p-1";
    private static final String FOLDER = "F-1";
    private static final String CONN = "c-1";
    private static final String NEW_OWNER = "alice";

    private ImportProfileDefinitionController controller;
    private ImportProfileDefinitionService importProfileDefinitionService;
    private ConnectorDefinitionService connectorDefinitionService;
    private IngestAuthorizationService ingestAuthorizationService;
    private AuditLogger auditLogger;
    private HttpServletRequest httpRequest;

    @BeforeEach
    void setUp() throws Exception {
        controller = new ImportProfileDefinitionController();
        importProfileDefinitionService = mock(ImportProfileDefinitionService.class);
        connectorDefinitionService = mock(ConnectorDefinitionService.class);
        ingestAuthorizationService = mock(IngestAuthorizationService.class);
        auditLogger = mock(AuditLogger.class);
        httpRequest = mock(HttpServletRequest.class);

        inject("importProfileDefinitionService", importProfileDefinitionService);
        inject("connectorDefinitionService", connectorDefinitionService);
        inject("ingestAuthorizationService", ingestAuthorizationService);
        inject("auditLogger", auditLogger);
        inject("httpRequest", httpRequest);
    }

    private void inject(String fieldName, Object value) throws Exception {
        Field f = ImportProfileDefinitionController.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(controller, value);
    }

    private CallContext adminCtx() {
        CallContext ctx = mock(CallContext.class);
        when(ctx.getUsername()).thenReturn("admin");
        // Cross-repository confinement (3.2.1): profile ops target the auth repo.
        lenient().when(ctx.getRepositoryId()).thenReturn(REPO);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(httpRequest.getAttribute("CallContext")).thenReturn(ctx);
        when(ingestAuthorizationService.isAdmin(ctx)).thenReturn(true);
        return ctx;
    }

    private CallContext nonAdminCtx() {
        CallContext ctx = mock(CallContext.class);
        when(ctx.getUsername()).thenReturn("bob");
        lenient().when(ctx.getRepositoryId()).thenReturn(REPO);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.FALSE);
        when(httpRequest.getAttribute("CallContext")).thenReturn(ctx);
        when(ingestAuthorizationService.isAdmin(ctx)).thenReturn(false);
        return ctx;
    }

    private ImportProfileDefinition adminOwnedProfile() {
        ImportProfileDefinition p = new ImportProfileDefinition();
        p.setProfileId(PROF);
        p.setRepositoryId(REPO);
        p.setTargetFolderId(FOLDER);
        p.setDelegated(false);
        p.setEnabled(true);
        p.setSchedulerEnabled(true);          // admin had it scheduled
        p.setDefaultProfile(false);
        p.setAllowedConnectorIds(List.of(CONN));
        p.setDefaultConnectorId(CONN);
        return p;
    }

    private ImportProfileDefinition delegatedProfile() {
        ImportProfileDefinition p = adminOwnedProfile();
        p.setDelegated(true);
        p.setCreatedByUserId(NEW_OWNER);
        p.setSchedulerEnabled(false);
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

    // ────────────────────────────────────────────────────────────────────
    // Auth
    // ────────────────────────────────────────────────────────────────────

    @Test
    void nonAdmin_isRefused() {
        nonAdminCtx();
        ResponseEntity<Map<String, Object>> res = controller.transferOwnership(
                PROF, Map.of("mode", "delegated", "createdByUserId", NEW_OWNER));
        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode());
        verify(importProfileDefinitionService, never()).update(any());
    }

    @Test
    void noCallContext_returnsUnauthorized() {
        // No CallContext attribute on the request
        ResponseEntity<Map<String, Object>> res = controller.transferOwnership(
                PROF, Map.of("mode", "delegated"));
        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
    }

    // ────────────────────────────────────────────────────────────────────
    // Body validation
    // ────────────────────────────────────────────────────────────────────

    @Test
    void missingMode_returnsBadRequest() {
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(adminOwnedProfile());
        ResponseEntity<Map<String, Object>> res = controller.transferOwnership(
                PROF, Map.of("createdByUserId", NEW_OWNER));
        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
    }

    @Test
    void invalidMode_returnsBadRequest() {
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(adminOwnedProfile());
        ResponseEntity<Map<String, Object>> res = controller.transferOwnership(
                PROF, Map.of("mode", "guest"));
        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
    }

    @Test
    void profileNotFound_returnsNotFound() {
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(null);
        ResponseEntity<Map<String, Object>> res = controller.transferOwnership(
                PROF, Map.of("mode", "delegated", "createdByUserId", NEW_OWNER));
        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode());
    }

    // ────────────────────────────────────────────────────────────────────
    // admin → delegated
    // ────────────────────────────────────────────────────────────────────

    @Test
    void adminToDelegated_happyPath_stampsOwnerAndForcesSafeDefaults() {
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(adminOwnedProfile());
        when(ingestAuthorizationService.resolveFolderId(REPO, FOLDER, null)).thenReturn(FOLDER);
        when(ingestAuthorizationService.canManageProfileForFolderAsUser(NEW_OWNER, REPO, FOLDER))
                .thenReturn(true);
        ConnectorDefinition c = delegatedConnector();
        when(connectorDefinitionService.get(CONN)).thenReturn(c);
        when(ingestAuthorizationService.canUseConnectorForDelegatedProfileAsUser(
                NEW_OWNER, REPO, c, FOLDER)).thenReturn(true);

        ResponseEntity<Map<String, Object>> res = controller.transferOwnership(
                PROF, Map.of("mode", "delegated", "createdByUserId", NEW_OWNER));
        assertEquals(HttpStatus.OK, res.getStatusCode());

        ArgumentCaptor<ImportProfileDefinition> captor =
                ArgumentCaptor.forClass(ImportProfileDefinition.class);
        verify(importProfileDefinitionService).update(captor.capture());
        ImportProfileDefinition saved = captor.getValue();
        assertTrue(saved.isDelegated());
        assertEquals(NEW_OWNER, saved.getCreatedByUserId());
        // Safe defaults forced — the admin-era scheduler / defaultProfile
        // must not survive the transfer
        assertFalse(saved.isSchedulerEnabled(),
                "schedulerEnabled must be cleared during admin → delegated transfer");
        assertFalse(saved.isDefaultProfile());
        // targetFolderPath nulled so the picker / cache always agree on ID
        assertNull(saved.getTargetFolderPath());
        assertEquals(FOLDER, saved.getTargetFolderId());
    }

    @Test
    void adminToDelegated_newOwnerLacksCmisAll_isRefused() {
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(adminOwnedProfile());
        when(ingestAuthorizationService.resolveFolderId(REPO, FOLDER, null)).thenReturn(FOLDER);
        when(ingestAuthorizationService.canManageProfileForFolderAsUser(NEW_OWNER, REPO, FOLDER))
                .thenReturn(false);  // ← would-be owner doesn't have cmis:all

        ResponseEntity<Map<String, Object>> res = controller.transferOwnership(
                PROF, Map.of("mode", "delegated", "createdByUserId", NEW_OWNER));
        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode());
        assertEquals("CMIS_ALL_REQUIRED", res.getBody().get("denialReason"));
        verify(importProfileDefinitionService, never()).update(any());
    }

    @Test
    void adminToDelegated_connectorNotDelegatedToNewOwner_isRefused() {
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(adminOwnedProfile());
        when(ingestAuthorizationService.resolveFolderId(REPO, FOLDER, null)).thenReturn(FOLDER);
        when(ingestAuthorizationService.canManageProfileForFolderAsUser(NEW_OWNER, REPO, FOLDER))
                .thenReturn(true);
        ConnectorDefinition c = delegatedConnector();
        when(connectorDefinitionService.get(CONN)).thenReturn(c);
        when(ingestAuthorizationService.canUseConnectorForDelegatedProfileAsUser(
                NEW_OWNER, REPO, c, FOLDER)).thenReturn(false);  // ← scope mismatch

        ResponseEntity<Map<String, Object>> res = controller.transferOwnership(
                PROF, Map.of("mode", "delegated", "createdByUserId", NEW_OWNER));
        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode());
        assertEquals("CONNECTOR_NOT_DELEGATED", res.getBody().get("denialReason"));
        verify(importProfileDefinitionService, never()).update(any());
    }

    @Test
    void adminToDelegated_unknownConnector_isRefused() {
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(adminOwnedProfile());
        when(ingestAuthorizationService.resolveFolderId(REPO, FOLDER, null)).thenReturn(FOLDER);
        when(ingestAuthorizationService.canManageProfileForFolderAsUser(NEW_OWNER, REPO, FOLDER))
                .thenReturn(true);
        when(connectorDefinitionService.get(CONN)).thenReturn(null);  // disappeared

        ResponseEntity<Map<String, Object>> res = controller.transferOwnership(
                PROF, Map.of("mode", "delegated", "createdByUserId", NEW_OWNER));
        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
        assertEquals("UNKNOWN_CONNECTOR", res.getBody().get("denialReason"));
    }

    @Test
    void adminToDelegated_emptyAllowedConnectorIds_isRefused() {
        adminCtx();
        ImportProfileDefinition p = adminOwnedProfile();
        p.setAllowedConnectorIds(List.of());
        when(importProfileDefinitionService.get(PROF)).thenReturn(p);
        when(ingestAuthorizationService.resolveFolderId(REPO, FOLDER, null)).thenReturn(FOLDER);

        ResponseEntity<Map<String, Object>> res = controller.transferOwnership(
                PROF, Map.of("mode", "delegated", "createdByUserId", NEW_OWNER));
        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
        assertEquals("EMPTY_ALLOWED_CONNECTORS", res.getBody().get("denialReason"));
    }

    @Test
    void adminToDelegated_defaultConnectorNotInAllowed_isRefused() {
        // Same invariant as the normal delegated PUT path
        // (validateDelegatedConnectors): defaultConnectorId, when set,
        // must be in allowedConnectorIds. Transfer must close this
        // before flipping delegated=true — otherwise the runtime gate
        // would later refuse with the same DenialReason and the profile
        // would be DOA.
        adminCtx();
        ImportProfileDefinition p = adminOwnedProfile();
        p.setDefaultConnectorId("rogue-conn");      // not in allowed list
        p.setAllowedConnectorIds(List.of(CONN));    // canonical list
        when(importProfileDefinitionService.get(PROF)).thenReturn(p);
        when(ingestAuthorizationService.resolveFolderId(REPO, FOLDER, null)).thenReturn(FOLDER);

        ResponseEntity<Map<String, Object>> res = controller.transferOwnership(
                PROF, Map.of("mode", "delegated", "createdByUserId", NEW_OWNER));
        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
        assertEquals("DEFAULT_CONNECTOR_NOT_IN_ALLOWED", res.getBody().get("denialReason"));
        // Must refuse BEFORE flipping the flag
        verify(importProfileDefinitionService, never()).update(any());
        // canManageProfileForFolderAsUser is reached AFTER this check so
        // it doesn't need to have fired; the test doesn't stub it which
        // would have caused an NPE if reached.
        verify(ingestAuthorizationService, never()).canManageProfileForFolderAsUser(any(), any(), any());
    }

    @Test
    void adminToDelegated_newOwnerLacksCmisAll_recordsAuditDenial() {
        // Pins the audit shape for transfer denials — newly required so
        // SOC tooling can see who tried to hand a profile to whom and
        // why it was refused. Operation = EXTERNAL_PROFILE_UPDATED with
        // success=false; details include transferTo + newOwnerUserId +
        // denialReason.
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(adminOwnedProfile());
        when(ingestAuthorizationService.resolveFolderId(REPO, FOLDER, null)).thenReturn(FOLDER);
        when(ingestAuthorizationService.canManageProfileForFolderAsUser(NEW_OWNER, REPO, FOLDER))
                .thenReturn(false);

        ResponseEntity<Map<String, Object>> res = controller.transferOwnership(
                PROF, Map.of("mode", "delegated", "createdByUserId", NEW_OWNER));
        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, ?>> detailsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditLogger).logOperation(
                eq(jp.aegif.nemaki.audit.AuditOperation.EXTERNAL_PROFILE_UPDATED),
                eq(REPO), eq("admin"), eq(PROF),
                eq(false), any(),
                detailsCaptor.capture());
        Map<String, ?> details = detailsCaptor.getValue();
        assertEquals("CMIS_ALL_REQUIRED", details.get("denialReason"));
        assertEquals("delegated", details.get("transferTo"));
        assertEquals(NEW_OWNER, details.get("newOwnerUserId"));
        assertEquals(FOLDER, details.get("targetFolderId"));
    }

    @Test
    void adminToDelegated_targetFolderUnresolvable_isRefusedAndAudited() {
        // resolveFolderId returns null when neither targetFolderId nor
        // targetFolderPath resolves to an existing folder — typically the
        // folder was deleted out from under the profile, or the path
        // moved. This is the only transfer denial branch that previously
        // bypassed the audit trail; H10 patches it to flow through
        // denyTransfer like every other failure mode. We also check the
        // captured details map records denialReason=TARGET_FOLDER_UNRESOLVABLE
        // and that update() is never reached.
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(adminOwnedProfile());
        when(ingestAuthorizationService.resolveFolderId(REPO, FOLDER, null)).thenReturn(null);

        ResponseEntity<Map<String, Object>> res = controller.transferOwnership(
                PROF, Map.of("mode", "delegated", "createdByUserId", NEW_OWNER));
        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
        assertEquals("TARGET_FOLDER_UNRESOLVABLE", res.getBody().get("denialReason"));
        verify(importProfileDefinitionService, never()).update(any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, ?>> detailsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditLogger).logOperation(
                eq(jp.aegif.nemaki.audit.AuditOperation.EXTERNAL_PROFILE_UPDATED),
                eq(REPO), eq("admin"), eq(PROF),
                eq(false), any(),
                detailsCaptor.capture());
        Map<String, ?> details = detailsCaptor.getValue();
        assertEquals("TARGET_FOLDER_UNRESOLVABLE", details.get("denialReason"));
        assertEquals("delegated", details.get("transferTo"));
        assertEquals(NEW_OWNER, details.get("newOwnerUserId"));
        // folderId is null at this point in the flow — the helper omits
        // the key rather than emitting a null
        org.junit.jupiter.api.Assertions.assertFalse(details.containsKey("targetFolderId"),
                "targetFolderId must not appear when resolveFolderId returned null");
    }

    @Test
    void adminToDelegated_defaultsCreatedByToCaller_whenOmitted() {
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(adminOwnedProfile());
        when(ingestAuthorizationService.resolveFolderId(REPO, FOLDER, null)).thenReturn(FOLDER);
        when(ingestAuthorizationService.canManageProfileForFolderAsUser("admin", REPO, FOLDER))
                .thenReturn(true);
        ConnectorDefinition c = delegatedConnector();
        when(connectorDefinitionService.get(CONN)).thenReturn(c);
        when(ingestAuthorizationService.canUseConnectorForDelegatedProfileAsUser(
                "admin", REPO, c, FOLDER)).thenReturn(true);

        // createdByUserId omitted from body — defaults to caller's username
        ResponseEntity<Map<String, Object>> res = controller.transferOwnership(
                PROF, Map.of("mode", "delegated"));
        assertEquals(HttpStatus.OK, res.getStatusCode());

        ArgumentCaptor<ImportProfileDefinition> captor =
                ArgumentCaptor.forClass(ImportProfileDefinition.class);
        verify(importProfileDefinitionService).update(captor.capture());
        assertEquals("admin", captor.getValue().getCreatedByUserId());
    }

    // ────────────────────────────────────────────────────────────────────
    // delegated → admin
    // ────────────────────────────────────────────────────────────────────

    @Test
    void delegatedToAdmin_clearsDelegatedFlag_preservesOtherFields() {
        adminCtx();
        ImportProfileDefinition p = delegatedProfile();
        when(importProfileDefinitionService.get(PROF)).thenReturn(p);

        ResponseEntity<Map<String, Object>> res = controller.transferOwnership(
                PROF, Map.of("mode", "admin"));
        assertEquals(HttpStatus.OK, res.getStatusCode());

        ArgumentCaptor<ImportProfileDefinition> captor =
                ArgumentCaptor.forClass(ImportProfileDefinition.class);
        verify(importProfileDefinitionService).update(captor.capture());
        ImportProfileDefinition saved = captor.getValue();
        assertFalse(saved.isDelegated());
        // createdByUserId is kept — it's history. Subsequent admin PUTs
        // can change it if desired.
        assertEquals(NEW_OWNER, saved.getCreatedByUserId());
        // No folder / connector re-validation needed for the reverse
        // direction
        verify(ingestAuthorizationService, never())
                .canManageProfileForFolderAsUser(any(), any(), any());
    }

    // ────────────────────────────────────────────────────────────────────
    // Audit
    // ────────────────────────────────────────────────────────────────────

    @Test
    void successfulTransfer_recordsAuditWithTransferToAndNewOwner() {
        adminCtx();
        when(importProfileDefinitionService.get(PROF)).thenReturn(adminOwnedProfile());
        when(ingestAuthorizationService.resolveFolderId(REPO, FOLDER, null)).thenReturn(FOLDER);
        when(ingestAuthorizationService.canManageProfileForFolderAsUser(NEW_OWNER, REPO, FOLDER))
                .thenReturn(true);
        ConnectorDefinition c = delegatedConnector();
        when(connectorDefinitionService.get(CONN)).thenReturn(c);
        when(ingestAuthorizationService.canUseConnectorForDelegatedProfileAsUser(
                NEW_OWNER, REPO, c, FOLDER)).thenReturn(true);

        controller.transferOwnership(PROF, Map.of("mode", "delegated", "createdByUserId", NEW_OWNER));

        ArgumentCaptor<Map<String, ?>> detailsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditLogger).logOperation(
                eq(jp.aegif.nemaki.audit.AuditOperation.EXTERNAL_PROFILE_UPDATED),
                eq(REPO), eq("admin"), eq(PROF),
                eq(true), any(),
                detailsCaptor.capture());
        Map<String, ?> details = detailsCaptor.getValue();
        assertEquals("delegated", details.get("transferTo"));
        assertEquals(NEW_OWNER, details.get("newOwnerUserId"));
        assertEquals(FOLDER, details.get("targetFolderId"));
    }
}
