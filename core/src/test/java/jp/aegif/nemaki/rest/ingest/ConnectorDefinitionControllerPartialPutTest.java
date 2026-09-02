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
        // Connector CRUD is now gated to a default-repository admin (3.2.1):
        // wire a RepositoryInfoMap whose default repo matches the auth context.
        jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap repositoryInfoMap =
                mock(jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap.class);
        when(repositoryInfoMap.getDefaultRepositoryId()).thenReturn("bedroom");
        inject("repositoryInfoMap", repositoryInfoMap);

        // Admin context on the default repository — the gate check passes
        CallContext ctx = mock(CallContext.class);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(ctx.getRepositoryId()).thenReturn("bedroom");
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
    // The mask must never be written AS the secret
    // ────────────────────────────────────────────────────────────────────

    @Test
    void aMaskedSecretIsNotWrittenWhenTheStoredRowCouldNotBeReadBack() {
        // GET hands out "[configured]" in place of the real credential, and an administrator
        // who edits that payload and PUTs it back relies on this method restoring the real
        // value from the stored row. The restore reads it through a MANGO SELECTOR, and a
        // selector whose index is rebuilding answers "no such connector" — after which the
        // literal string "[configured]" was written AS the credential and the real one was
        // gone. The connector then stops authenticating with nothing in the response saying
        // why.
        //
        // The window was noticed while reviewing a DIFFERENT change (a service-layer refusal
        // that had been relaxed to "adopt the row"), which is the only reason it surfaced:
        // the refusal downstream had been quietly standing in for this guard.
        when(connectorDefinitionService.get(eq("conn-1"))).thenReturn(null);

        ConnectorDefinition def = payload(true);
        def.setCredentialRef("[configured]");

        var res = controller.update("conn-1", def);

        assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                res.getStatusCode(),
                "the write went ahead with an unrestorable mask, or was refused as something "
                        + "a caller would not retry");
        verify(connectorDefinitionService, never()).update(any());
    }

    @Test
    void aRealSecretIsStillAcceptedWhenTheRowCannotBeReadBack() {
        // The boundary. Refusing every update whose read-back missed would make a connector
        // unmanageable while its index rebuilds; what cannot be written is the MASK, because
        // that is the only value with nothing behind it.
        when(connectorDefinitionService.get(eq("conn-1"))).thenReturn(null);

        ConnectorDefinition def = payload(true);
        def.setCredentialRef("a-real-credential-ref");

        var res = controller.update("conn-1", def);

        assertEquals(org.springframework.http.HttpStatus.OK, res.getStatusCode(),
                "an update carrying real values was refused because the read-back missed, "
                        + "which locks an administrator out of a connector they can fully "
                        + "specify");
        verify(connectorDefinitionService).update(any());
    }

    // ────────────────────────────────────────────────────────────────────
    // The retryable refusal reaches the client as retryable
    // ────────────────────────────────────────────────────────────────────

    @Test
    void theRetryableRefusalReachesTheClientAs503() {
        // BEHAVIOURAL, beside the source lock, because a round-6 audit listed the source
        // lock's defeat: `update()` names SERVICE_UNAVAILABLE twice (the masked-secret gate
        // and this catch), so rewording the CATCH to a 500 keeps both `contains()` green
        // while the retryable condition reaches clients as a 500 again — the exact defect
        // the lock was written for. Driving a thrown ConnectorIndexNotReadyException through
        // the controller cannot be fooled by spelling.
        ConnectorDefinition stored = payload(true);
        stored.setCredentialRef("real-ref");
        when(connectorDefinitionService.get(eq("conn-1"))).thenReturn(stored);
        when(connectorDefinitionService.update(any())).thenThrow(
                new ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException(
                        "the index has not caught up"));

        ConnectorDefinition def = payload(true);
        def.setCredentialRef("another-real-ref");
        var res = controller.update("conn-1", def);

        assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                res.getStatusCode(),
                "a transient, retryable refusal reached the client as something else — a "
                        + "caller that would have succeeded on retry opens a ticket instead");
    }

    // ────────────────────────────────────────────────────────────────────
    // The mask gate covers BOTH secrets, and the CREATE side
    // ────────────────────────────────────────────────────────────────────

    @Test
    void aMaskedWebhookSecretIsNotWrittenEither() {
        // The webhook twin. The first masked-secret test sent only credentialRef, so the
        // `|| webhookSecret` half of the gate could be deleted with every test green — the
        // one-arm shape, inside the guard OF was added for, named by a round-6 audit.
        when(connectorDefinitionService.get(eq("conn-1"))).thenReturn(null);

        ConnectorDefinition def = payload(true);
        def.setWebhookSecret("[configured]");

        var res = controller.update("conn-1", def);

        assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                res.getStatusCode(),
                "a masked webhookSecret with no row to restore it from was written as the "
                        + "secret — webhook validation then compares against the literal "
                        + "placeholder");
        verify(connectorDefinitionService, never()).update(any());
    }

    @Test
    void aCreateCarryingTheMaskIsRefused() {
        // The POST arm. Round 5 gated the PUT; a round-6 sibling sweep found create()
        // accepting "[configured]" and storing the literal sentinel — a connector that can
        // never authenticate, created with a 201. On create there is nothing to restore
        // the mask from, so it is a 400 (the request can never be right), not a 503.
        ConnectorDefinition def = payload(true);
        def.setCredentialRef("[configured]");
        // Unused on the healthy tree — the gate answers before the service is reached. It
        // is here for the CONTROL (ON): with the gate removed, an unstubbed create() returns
        // null and the controller NPEs on created.getConnectorId(), so the runner scored the
        // firing as "broke the harness" rather than the lock's own assertion. Stubbed, the
        // sabotaged flow completes with a 201 and the assertEquals below is what fails.
        when(connectorDefinitionService.create(any())).thenReturn(def);

        var res = controller.create(def);

        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, res.getStatusCode(),
                "a create carrying the placeholder was accepted, so the literal string "
                        + "\"[configured]\" is stored as the credential");
        verify(connectorDefinitionService, never()).create(any());
    }

    @Test
    void aCreateCarryingAMaskedWebhookSecretIsRefusedToo() {
        // The webhook arm of the CREATE gate. The gate itself was written as an OR from the
        // start, but the lock above and control ON exercise only the credentialRef clause —
        // so `|| "[configured]".equals(def.getWebhookSecret())` could be deleted with
        // everything green. That is the SAME one-arm gap this round closed on the PUT side
        // as A5 (aMaskedWebhookSecretIsNotWrittenEither + OR), sitting inside a gate the
        // same round added. A parallel review caught it after the sweep; the sweep could
        // not have — no control measured the clause.
        ConnectorDefinition def = payload(true);
        def.setCredentialRef("a-real-credential-ref");
        def.setWebhookSecret("[configured]");
        // Unused on the healthy tree — the gate answers first. Present for the control
        // (OT): with the webhook clause narrowed away, an unstubbed create() returns null
        // and the controller NPEs before the assertion, which the runner scores as broken
        // harness rather than a firing. Same rationale as the stub in the credentialRef
        // twin above.
        when(connectorDefinitionService.create(any())).thenReturn(def);

        var res = controller.create(def);

        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, res.getStatusCode(),
                "a create carrying the masked webhook placeholder was accepted, so the "
                        + "literal string \"[configured]\" is stored as the webhook secret "
                        + "and every webhook signature check compares against it");
        verify(connectorDefinitionService, never()).create(any());
    }

    @Test
    void aCreateWithRealValuesStillWorks() {
        // The boundary of the new gate.
        ConnectorDefinition def = payload(true);
        def.setCredentialRef("a-real-credential-ref");
        when(connectorDefinitionService.create(any())).thenReturn(def);

        var res = controller.create(def);

        assertEquals(org.springframework.http.HttpStatus.CREATED, res.getStatusCode(),
                "an ordinary create was refused by the mask gate: " + res.getBody());
        verify(connectorDefinitionService).create(any());
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
