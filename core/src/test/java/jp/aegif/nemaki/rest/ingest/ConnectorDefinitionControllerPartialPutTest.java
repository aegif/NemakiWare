package jp.aegif.nemaki.rest.ingest;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.util.constant.CallContextKey;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the "PUT partial payload preserves omitted scope lists" semantics on
 * {@link ConnectorDefinitionController#update}. The fix matters because a
 * scripted partial PUT that flips, say, {@code enabled} would otherwise
 * collapse {@code allowedFolderIds} to {@code null} via Jackson, and the
 * service's delegation invariants would then reject it with HTTP 400 even
 * though the operator never intended to change the scope.
 *
 * <p>Semantics under test:
 * <ul>
 *   <li>{@code allowedFolderIds} = {@code null} → preserve existing</li>
 *   <li>{@code allowedFolderIds} = {@code []}   → explicit clear (kept)</li>
 *   <li>{@code allowedFolderIds} = {@code [a]}  → use as-is</li>
 *   <li>Same for {@code allowedPrincipalIds}</li>
 *   <li>Existing {@code [configured]} placeholder for credentialRef /
 *       webhookSecret remains preserved as before (regression check)</li>
 * </ul>
 */
class ConnectorDefinitionControllerPartialPutTest {

    private ConnectorDefinitionController controller;
    private ConnectorDefinitionService connectorDefinitionService;
    private HttpServletRequest httpRequest;

    @BeforeEach
    void setUp() throws Exception {
        controller = new ConnectorDefinitionController();
        connectorDefinitionService = mock(ConnectorDefinitionService.class);
        httpRequest = mock(HttpServletRequest.class);

        // The controller calls IngestAuthorizationService.isAdmin only on the
        // /summary endpoint, not on PUT — PUT uses the local isAdmin() helper
        // which reads CallContextKey.IS_ADMIN directly. So we don't need to
        // wire ingestAuthorizationService here, but the field must be present
        // for Spring-style instantiation.
        inject("connectorDefinitionService", connectorDefinitionService);
        inject("httpRequest", httpRequest);
        inject("ingestAuthorizationService", mock(IngestAuthorizationService.class));

        // Admin context — the gate check passes
        CallContext ctx = mock(CallContext.class);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(httpRequest.getAttribute("CallContext")).thenReturn(ctx);
    }

    private void inject(String fieldName, Object value) throws Exception {
        Field f = ConnectorDefinitionController.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(controller, value);
    }

    private ConnectorDefinition existingDelegatedConnector() {
        ConnectorDefinition c = new ConnectorDefinition();
        c.setConnectorId("conn-1");
        c.setDisplayName("Existing");
        c.setSourceArchetype(SourceArchetype.FILE_SHARE);
        c.setSourceSystem("box");
        c.setAuthType("none");
        c.setEnabled(true);
        c.setDelegated(true);
        c.setAllowedFolderIds(new ArrayList<>(List.of("F-existing")));
        c.setAllowedPrincipalIds(new ArrayList<>(List.of("alice")));
        c.setCredentialRef("real-secret");
        c.setWebhookSecret("real-webhook-secret");
        return c;
    }

    /** Build a fresh payload mirroring what a partial PUT from a UI looks like. */
    private ConnectorDefinition payload(boolean enabled) {
        ConnectorDefinition def = new ConnectorDefinition();
        def.setDisplayName("Existing");
        def.setSourceArchetype(SourceArchetype.FILE_SHARE);
        def.setSourceSystem("box");
        def.setAuthType("none");
        def.setEnabled(enabled);
        def.setDelegated(true);
        // Omit allowedFolderIds, allowedPrincipalIds, credentialRef, webhookSecret
        return def;
    }

    private ConnectorDefinition captureSavedRecord() {
        ArgumentCaptor<ConnectorDefinition> captor =
                ArgumentCaptor.forClass(ConnectorDefinition.class);
        verify(connectorDefinitionService).update(captor.capture());
        return captor.getValue();
    }

    // ────────────────────────────────────────────────────────────────────
    // allowedFolderIds preservation
    // ────────────────────────────────────────────────────────────────────

    @Test
    void omittedAllowedFolderIds_preservesExisting() {
        when(connectorDefinitionService.get("conn-1")).thenReturn(existingDelegatedConnector());

        ConnectorDefinition def = payload(false);
        controller.update("conn-1", def);

        ConnectorDefinition saved = captureSavedRecord();
        assertEquals(List.of("F-existing"), saved.getAllowedFolderIds(),
                "null allowedFolderIds in payload must preserve the stored value");
    }

    @Test
    void explicitEmptyAllowedFolderIds_clearsExisting() {
        when(connectorDefinitionService.get("conn-1")).thenReturn(existingDelegatedConnector());

        ConnectorDefinition def = payload(true);
        def.setAllowedFolderIds(new ArrayList<>()); // explicit clear
        // Need to also turn off delegation OR set delegateAllFolders, otherwise
        // server-side validation would reject the inconsistent state. The
        // controller doesn't validate; the service does. So this test asserts
        // ONLY the controller-level preservation behaviour — the saved record
        // is what we capture, regardless of downstream validation.
        controller.update("conn-1", def);

        ConnectorDefinition saved = captureSavedRecord();
        assertNotNull(saved.getAllowedFolderIds(), "explicit [] must NOT be coerced to null");
        assertTrue(saved.getAllowedFolderIds().isEmpty(),
                "explicit [] is the operator's clear-intent signal and must be honoured");
    }

    @Test
    void explicitNewAllowedFolderIds_isUsedAsIs() {
        when(connectorDefinitionService.get("conn-1")).thenReturn(existingDelegatedConnector());

        ConnectorDefinition def = payload(true);
        def.setAllowedFolderIds(new ArrayList<>(List.of("F-new-1", "F-new-2")));
        controller.update("conn-1", def);

        ConnectorDefinition saved = captureSavedRecord();
        assertEquals(List.of("F-new-1", "F-new-2"), saved.getAllowedFolderIds());
    }

    // ────────────────────────────────────────────────────────────────────
    // allowedPrincipalIds preservation
    // ────────────────────────────────────────────────────────────────────

    @Test
    void omittedAllowedPrincipalIds_preservesExisting() {
        when(connectorDefinitionService.get("conn-1")).thenReturn(existingDelegatedConnector());

        ConnectorDefinition def = payload(true);
        controller.update("conn-1", def);

        ConnectorDefinition saved = captureSavedRecord();
        assertEquals(List.of("alice"), saved.getAllowedPrincipalIds(),
                "null allowedPrincipalIds in payload must preserve the stored value");
    }

    @Test
    void explicitEmptyAllowedPrincipalIds_clearsExisting() {
        when(connectorDefinitionService.get("conn-1")).thenReturn(existingDelegatedConnector());

        ConnectorDefinition def = payload(true);
        def.setAllowedPrincipalIds(new ArrayList<>());
        controller.update("conn-1", def);

        ConnectorDefinition saved = captureSavedRecord();
        assertNotNull(saved.getAllowedPrincipalIds());
        assertTrue(saved.getAllowedPrincipalIds().isEmpty(),
                "explicit [] for principals must NOT be coerced back to existing");
    }

    // ────────────────────────────────────────────────────────────────────
    // Secret-mask regression (was already shipped — pin it)
    // ────────────────────────────────────────────────────────────────────

    @Test
    void omittedCredentialRef_preservesRealSecret() {
        when(connectorDefinitionService.get("conn-1")).thenReturn(existingDelegatedConnector());

        ConnectorDefinition def = payload(true);
        // credentialRef intentionally left null in the partial payload
        controller.update("conn-1", def);

        ConnectorDefinition saved = captureSavedRecord();
        // Pre-existing fix: only "[configured]" placeholder is preserved.
        // A bare null doesn't trigger preservation today — admins must POST/PUT
        // a value or the explicit placeholder. Document the current behaviour.
        assertNull(saved.getCredentialRef(),
                "Current behaviour: null credentialRef wipes; only '[configured]' placeholder preserves. "
                        + "Update this test if the policy changes.");
    }

    @Test
    void placeholderCredentialRef_preservesRealSecret() {
        when(connectorDefinitionService.get("conn-1")).thenReturn(existingDelegatedConnector());

        ConnectorDefinition def = payload(true);
        def.setCredentialRef("[configured]");
        def.setWebhookSecret("[configured]");
        controller.update("conn-1", def);

        ConnectorDefinition saved = captureSavedRecord();
        assertEquals("real-secret", saved.getCredentialRef());
        assertEquals("real-webhook-secret", saved.getWebhookSecret());
    }

    // ────────────────────────────────────────────────────────────────────
    // Non-admin must still be refused (regression)
    // ────────────────────────────────────────────────────────────────────

    @Test
    void nonAdmin_isStillRefused_andServiceNeverCalled() {
        CallContext ctx = mock(CallContext.class);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.FALSE);
        when(httpRequest.getAttribute("CallContext")).thenReturn(ctx);

        ConnectorDefinition def = payload(true);
        var res = controller.update("conn-1", def);
        assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, res.getStatusCode());
        verify(connectorDefinitionService, never()).update(any());
        verify(connectorDefinitionService, never()).get(eq("conn-1"));
    }
}
