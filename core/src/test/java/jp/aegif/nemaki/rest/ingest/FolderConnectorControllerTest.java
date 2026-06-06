package jp.aegif.nemaki.rest.ingest;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.rest.controller.IntegrationSettingsService;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link FolderConnectorController}. The write-permission check is
 * a static helper backed by Spring context; in these unit tests we exercise
 * the admin path (isAdmin=true) and the result-mapping / authError logic.
 */
class FolderConnectorControllerTest {

    private static final String REPO = "bedroom";
    private static final String FOLDER = "folder-1";
    private static final String PROFILE = "p1";

    private FolderConnectorController controller;
    private HttpServletRequest httpRequest;
    private ContentService contentService;
    private ImportProfileDefinitionService profileService;
    private ConnectorDefinitionService connectorService;
    private IngestAuthorizationService authService;
    private IngestSchedulerService schedulerService;
    private IntegrationSettingsService integrationSettingsService;

    @BeforeEach
    void setUp() throws Exception {
        controller = new FolderConnectorController();
        httpRequest = mock(HttpServletRequest.class);
        contentService = mock(ContentService.class);
        profileService = mock(ImportProfileDefinitionService.class);
        connectorService = mock(ConnectorDefinitionService.class);
        authService = mock(IngestAuthorizationService.class);
        schedulerService = mock(IngestSchedulerService.class);
        integrationSettingsService = mock(IntegrationSettingsService.class);
        inject("httpRequest", httpRequest);
        inject("contentService", contentService);
        inject("importProfileDefinitionService", profileService);
        inject("connectorDefinitionService", connectorService);
        inject("ingestAuthorizationService", authService);
        inject("schedulerService", schedulerService);
        inject("integrationSettingsService", integrationSettingsService);
    }

    private void inject(String field, Object value) throws Exception {
        Field f = FolderConnectorController.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(controller, value);
    }

    private CallContext adminCtx() {
        CallContext ctx = mock(CallContext.class);
        when(ctx.getUsername()).thenReturn("admin");
        when(httpRequest.getAttribute("CallContext")).thenReturn(ctx);
        when(authService.isAdmin(ctx)).thenReturn(true);
        return ctx;
    }

    private Folder folder() {
        Folder f = mock(Folder.class);
        when(contentService.getFolder(REPO, FOLDER)).thenReturn(f);
        return f;
    }

    private ImportProfileDefinition profile() {
        ImportProfileDefinition p = new ImportProfileDefinition();
        p.setProfileId(PROFILE);
        p.setRepositoryId(REPO);
        p.setEnabled(true);
        p.setTargetFolderId(FOLDER);
        p.setDisplayName("Folder profile");
        return p;
    }

    private ConnectorDefinition connector() {
        ConnectorDefinition c = new ConnectorDefinition();
        c.setConnectorId("c1");
        c.setEnabled(true);
        c.setSourceSystem("slack");
        c.setSourceArchetype(SourceArchetype.CHAT_CONTEXT);
        return c;
    }

    // ── list ──

    @Test
    void list_unauthenticated_returns401() {
        when(httpRequest.getAttribute("CallContext")).thenReturn(null);
        ResponseEntity<Map<String, Object>> r = controller.list(REPO, FOLDER);
        assertEquals(HttpStatus.UNAUTHORIZED, r.getStatusCode());
    }

    @Test
    void list_folderNotFound_returns404() {
        adminCtx();
        when(contentService.getFolder(REPO, FOLDER)).thenReturn(null);
        ResponseEntity<Map<String, Object>> r = controller.list(REPO, FOLDER);
        assertEquals(HttpStatus.NOT_FOUND, r.getStatusCode());
    }

    @Test
    void list_admin_returnsMatchingFolderProfiles() {
        adminCtx();
        folder();
        when(profileService.listByRepository(REPO)).thenReturn(List.of(profile()));
        when(schedulerService.resolveConnectorForProfile(any())).thenReturn(connector());

        ResponseEntity<Map<String, Object>> r = controller.list(REPO, FOLDER);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals(true, r.getBody().get("canWrite"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> connectors = (List<Map<String, Object>>) r.getBody().get("connectors");
        assertEquals(1, connectors.size());
        assertEquals(PROFILE, connectors.get(0).get("profileId"));
        assertEquals("slack", connectors.get(0).get("sourceSystem"));
    }

    @Test
    void list_admin_excludesProfilesForOtherFolders() {
        adminCtx();
        folder();
        ImportProfileDefinition other = profile();
        other.setTargetFolderId("other-folder");
        when(profileService.listByRepository(REPO)).thenReturn(List.of(other));

        ResponseEntity<Map<String, Object>> r = controller.list(REPO, FOLDER);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> connectors = (List<Map<String, Object>>) r.getBody().get("connectors");
        assertTrue(connectors.isEmpty());
    }

    // ── run ──

    @Test
    void run_admin_success_mapsResult() {
        adminCtx();
        folder();
        when(profileService.get(PROFILE)).thenReturn(profile());
        when(schedulerService.resolveConnectorForProfile(any())).thenReturn(connector());
        when(schedulerService.executeFetch(any(), any(), any(), any()))
                .thenReturn(new FetchResult(5, 3, 1, List.of()));

        ResponseEntity<Map<String, Object>> r = controller.run(REPO, FOLDER, PROFILE);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals("success", r.getBody().get("status"));
        assertEquals(3, r.getBody().get("imported"));
        assertEquals(false, r.getBody().get("authError"));
    }

    @Test
    void run_admin_authFailure_setsAuthErrorTrue() {
        adminCtx();
        folder();
        when(profileService.get(PROFILE)).thenReturn(profile());
        when(schedulerService.resolveConnectorForProfile(any())).thenReturn(connector());
        when(schedulerService.executeFetch(any(), any(), any(), any()))
                .thenReturn(new FetchResult(0, 0, List.of("No token for Slack connector")));

        ResponseEntity<Map<String, Object>> r = controller.run(REPO, FOLDER, PROFILE);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals("partial", r.getBody().get("status"));
        assertEquals(true, r.getBody().get("authError"),
                "a 'No token' error must be flagged as an auth failure");
    }

    @Test
    void run_admin_genericError_authErrorFalse() {
        adminCtx();
        folder();
        when(profileService.get(PROFILE)).thenReturn(profile());
        when(schedulerService.resolveConnectorForProfile(any())).thenReturn(connector());
        when(schedulerService.executeFetch(any(), any(), any(), any()))
                .thenReturn(new FetchResult(2, 1, List.of("channel not found")));

        ResponseEntity<Map<String, Object>> r = controller.run(REPO, FOLDER, PROFILE);
        assertEquals(false, r.getBody().get("authError"));
    }

    private boolean runAuthErrorFor(String errorMessage) {
        adminCtx();
        folder();
        when(profileService.get(PROFILE)).thenReturn(profile());
        when(schedulerService.resolveConnectorForProfile(any())).thenReturn(connector());
        when(schedulerService.executeFetch(any(), any(), any(), any()))
                .thenReturn(new FetchResult(1, 0, List.of(errorMessage)));
        return (boolean) controller.run(REPO, FOLDER, PROFILE).getBody().get("authError");
    }

    @Test
    void run_slackInvalidAuth_isAuthError() {
        assertTrue(runAuthErrorFor("Slack API error: invalid_auth"));
    }

    @Test
    void run_slackNotAuthed_isAuthError() {
        assertTrue(runAuthErrorFor("Slack: not_authed"));
    }

    @Test
    void run_graphInvalidAuthenticationToken_isAuthError() {
        // "InvalidAuthenticationToken" lowercased contains "authentication".
        assertTrue(runAuthErrorFor("Graph 401: InvalidAuthenticationToken"));
    }

    @Test
    void run_benignTokenMention_isNotAuthError() {
        // Mentions a token but is not an auth failure — must NOT prompt re-set.
        assertFalse(runAuthErrorFor("token refresh rate-limited, retry later"));
    }

    @Test
    void run_profileForOtherFolder_returns404() {
        adminCtx();
        folder();
        ImportProfileDefinition other = profile();
        other.setTargetFolderId("other-folder");
        when(profileService.get(PROFILE)).thenReturn(other);

        ResponseEntity<Map<String, Object>> r = controller.run(REPO, FOLDER, PROFILE);
        assertEquals(HttpStatus.NOT_FOUND, r.getStatusCode());
    }

    @Test
    void run_profileForOtherRepository_returns404() {
        adminCtx();
        folder();
        // Same folder id but a different repository — must not be operable
        // (cross-repo IDOR guard).
        ImportProfileDefinition other = profile();
        other.setRepositoryId("canopy");
        when(profileService.get(PROFILE)).thenReturn(other);

        ResponseEntity<Map<String, Object>> r = controller.run(REPO, FOLDER, PROFILE);
        assertEquals(HttpStatus.NOT_FOUND, r.getStatusCode());
        verify(schedulerService, never()).executeFetch(any(), any(), any(), any());
    }

    @Test
    void run_nonAdmin_connectorNotDelegated_returns403() {
        // Non-admin: isAdmin=false. The static write check falls back to
        // isAdminUser (false) in unit context, so this also exercises the
        // "no write" 403. Either way the run must be refused.
        CallContext ctx = mock(CallContext.class);
        when(ctx.getUsername()).thenReturn("bob");
        when(httpRequest.getAttribute("CallContext")).thenReturn(ctx);
        when(authService.isAdmin(ctx)).thenReturn(false);
        folder();
        when(profileService.get(PROFILE)).thenReturn(profile());
        when(schedulerService.resolveConnectorForProfile(any())).thenReturn(connector());
        when(authService.canUseConnectorForDelegatedProfile(any(), any(), any(), any()))
                .thenReturn(false);

        ResponseEntity<Map<String, Object>> r = controller.run(REPO, FOLDER, PROFILE);
        assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode());
        verify(schedulerService, never()).executeFetch(any(), any(), any(), any());
    }

    @Test
    void run_admin_setsCanManageCredentialTrue() {
        adminCtx();
        folder();
        when(profileService.get(PROFILE)).thenReturn(profile());
        when(schedulerService.resolveConnectorForProfile(any())).thenReturn(connector());
        when(schedulerService.executeFetch(any(), any(), any(), any()))
                .thenReturn(new FetchResult(0, 0, List.of("No token for Slack connector")));

        ResponseEntity<Map<String, Object>> r = controller.run(REPO, FOLDER, PROFILE);
        assertEquals(true, r.getBody().get("canManageCredential"));
    }

    // ── credential re-set ──

    @Test
    void setCredential_nonAdmin_returns403() {
        CallContext ctx = mock(CallContext.class);
        when(ctx.getUsername()).thenReturn("bob");
        when(httpRequest.getAttribute("CallContext")).thenReturn(ctx);
        when(authService.isAdmin(ctx)).thenReturn(false);

        ResponseEntity<Map<String, Object>> r =
                controller.setCredential(REPO, FOLDER, PROFILE, Map.of("token", "x"));
        assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode());
        verifyNoInteractions(integrationSettingsService);
    }

    @Test
    void setCredential_admin_blankToken_returns400() {
        adminCtx();
        folder();
        when(profileService.get(PROFILE)).thenReturn(profile());
        ConnectorDefinition c = connector();
        c.setCredentialRef("INGEST_SLACK_TOKEN");
        when(schedulerService.resolveConnectorForProfile(any())).thenReturn(c);

        ResponseEntity<Map<String, Object>> r =
                controller.setCredential(REPO, FOLDER, PROFILE, Map.of());
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        verifyNoInteractions(integrationSettingsService);
    }

    @Test
    void setCredential_admin_noCredentialRef_returns400() {
        adminCtx();
        folder();
        when(profileService.get(PROFILE)).thenReturn(profile());
        // connector() leaves credentialRef null
        when(schedulerService.resolveConnectorForProfile(any())).thenReturn(connector());

        ResponseEntity<Map<String, Object>> r =
                controller.setCredential(REPO, FOLDER, PROFILE, Map.of("token", "x"));
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        verifyNoInteractions(integrationSettingsService);
    }

    @Test
    void setCredential_admin_success_writesSetting() {
        adminCtx();
        folder();
        when(profileService.get(PROFILE)).thenReturn(profile());
        ConnectorDefinition c = connector();
        c.setCredentialRef("INGEST_SLACK_TOKEN");
        when(schedulerService.resolveConnectorForProfile(any())).thenReturn(c);

        ResponseEntity<Map<String, Object>> r =
                controller.setCredential(REPO, FOLDER, PROFILE, Map.of("token", "new-token"));
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals("success", r.getBody().get("status"));
        verify(integrationSettingsService).writeSetting("INGEST_SLACK_TOKEN", "new-token");
    }

    @Test
    void setCredential_reservedCredentialRef_returns400() {
        adminCtx();
        folder();
        when(profileService.get(PROFILE)).thenReturn(profile());
        ConnectorDefinition c = connector();
        // Pointing a connector's credentialRef at a core infra key must be
        // refused so the endpoint can't be repurposed as a config writer.
        c.setCredentialRef("couchdb.password");
        when(schedulerService.resolveConnectorForProfile(any())).thenReturn(c);

        ResponseEntity<Map<String, Object>> r =
                controller.setCredential(REPO, FOLDER, PROFILE, Map.of("token", "x"));
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        verifyNoInteractions(integrationSettingsService);
    }

    @Test
    void setCredential_profileForOtherRepository_returns404() {
        adminCtx();
        folder();
        ImportProfileDefinition other = profile();
        other.setRepositoryId("canopy");
        when(profileService.get(PROFILE)).thenReturn(other);

        ResponseEntity<Map<String, Object>> r =
                controller.setCredential(REPO, FOLDER, PROFILE, Map.of("token", "x"));
        assertEquals(HttpStatus.NOT_FOUND, r.getStatusCode());
        verifyNoInteractions(integrationSettingsService);
    }

    @Test
    void setCredential_profileForOtherFolder_returns404() {
        adminCtx();
        folder();
        ImportProfileDefinition other = profile();
        other.setTargetFolderId("other-folder");
        when(profileService.get(PROFILE)).thenReturn(other);

        ResponseEntity<Map<String, Object>> r =
                controller.setCredential(REPO, FOLDER, PROFILE, Map.of("token", "x"));
        assertEquals(HttpStatus.NOT_FOUND, r.getStatusCode());
        verifyNoInteractions(integrationSettingsService);
    }
}
