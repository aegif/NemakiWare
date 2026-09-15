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

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("the folder's connector list answers 503, not 500, when "
            + "the profile listing could not be completed")
    void theListAnswers503WhenTheListingCannotBeCompleted() throws Exception {
        // The typed refusal escaped this endpoint — no catch, and GlobalExceptionHandler does
        // not cover this package — as a Spring 500. Through MockMvc because the handler is an
        // @ExceptionHandler, which a direct call never reaches.
        adminCtx();
        folder();
        when(profileService.listByRepository(REPO)).thenThrow(
                new ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException(
                        "a full selector page of 'nemaki_conf' carried no new continuation bookmark"));
        org.springframework.test.web.servlet.MockMvc mockMvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();

        assertDoesNotThrow(() -> mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/v1/repo/" + REPO + "/folders/" + FOLDER + "/connectors"))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                .status().isServiceUnavailable()),
                "a listing that could not be completed escaped the folder connector list as a 500");
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
        when(schedulerService.resolveConnectorFor(any())).thenReturn(
                new IngestSchedulerService.ConnectorForProfile(connector(), null));

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
        // Both paths of this controller resolve the row of the CALLING repository without an
        // index (the selector answers on profileId alone, so with the same id in two
        // repositories it hands back an arbitrary twin). A fixture that answers only the
        // selector leaves the controller at 404.
        when(profileService.getForRepository(PROFILE, REPO)).thenReturn(profile());
        when(schedulerService.resolveConnectorFor(any())).thenReturn(
                new IngestSchedulerService.ConnectorForProfile(connector(), null));
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
        // Both paths of this controller resolve the row of the CALLING repository without an
        // index (the selector answers on profileId alone, so with the same id in two
        // repositories it hands back an arbitrary twin). A fixture that answers only the
        // selector leaves the controller at 404.
        when(profileService.getForRepository(PROFILE, REPO)).thenReturn(profile());
        when(schedulerService.resolveConnectorFor(any())).thenReturn(
                new IngestSchedulerService.ConnectorForProfile(connector(), null));
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
        // Both paths of this controller resolve the row of the CALLING repository without an
        // index (the selector answers on profileId alone, so with the same id in two
        // repositories it hands back an arbitrary twin). A fixture that answers only the
        // selector leaves the controller at 404.
        when(profileService.getForRepository(PROFILE, REPO)).thenReturn(profile());
        when(schedulerService.resolveConnectorFor(any())).thenReturn(
                new IngestSchedulerService.ConnectorForProfile(connector(), null));
        when(schedulerService.executeFetch(any(), any(), any(), any()))
                .thenReturn(new FetchResult(2, 1, List.of("channel not found")));

        ResponseEntity<Map<String, Object>> r = controller.run(REPO, FOLDER, PROFILE);
        assertEquals(false, r.getBody().get("authError"));
    }

    private boolean runAuthErrorFor(String errorMessage) {
        adminCtx();
        folder();
        when(profileService.get(PROFILE)).thenReturn(profile());
        // Both paths of this controller resolve the row of the CALLING repository without an
        // index (the selector answers on profileId alone, so with the same id in two
        // repositories it hands back an arbitrary twin). A fixture that answers only the
        // selector leaves the controller at 404.
        when(profileService.getForRepository(PROFILE, REPO)).thenReturn(profile());
        when(schedulerService.resolveConnectorFor(any())).thenReturn(
                new IngestSchedulerService.ConnectorForProfile(connector(), null));
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
        // Both paths of this controller resolve the row of the CALLING repository without an
        // index (the selector answers on profileId alone, so with the same id in two
        // repositories it hands back an arbitrary twin). A fixture that answers only the
        // selector leaves the controller at 404.
        when(profileService.getForRepository(PROFILE, REPO)).thenReturn(profile());
        when(schedulerService.resolveConnectorFor(any())).thenReturn(
                new IngestSchedulerService.ConnectorForProfile(connector(), null));
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
        // Both paths of this controller resolve the row of the CALLING repository without an
        // index (the selector answers on profileId alone, so with the same id in two
        // repositories it hands back an arbitrary twin). A fixture that answers only the
        // selector leaves the controller at 404.
        when(profileService.getForRepository(PROFILE, REPO)).thenReturn(profile());
        when(schedulerService.resolveConnectorFor(any())).thenReturn(
                new IngestSchedulerService.ConnectorForProfile(connector(), null));
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
        // Both paths of this controller resolve the row of the CALLING repository without an
        // index (the selector answers on profileId alone, so with the same id in two
        // repositories it hands back an arbitrary twin). A fixture that answers only the
        // selector leaves the controller at 404.
        when(profileService.getForRepository(PROFILE, REPO)).thenReturn(profile());
        ConnectorDefinition c = connector();
        c.setCredentialRef("INGEST_SLACK_TOKEN");
        when(schedulerService.resolveConnectorFor(any())).thenReturn(
                new IngestSchedulerService.ConnectorForProfile(c, null));

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
        // Both paths of this controller resolve the row of the CALLING repository without an
        // index (the selector answers on profileId alone, so with the same id in two
        // repositories it hands back an arbitrary twin). A fixture that answers only the
        // selector leaves the controller at 404.
        when(profileService.getForRepository(PROFILE, REPO)).thenReturn(profile());
        // connector() leaves credentialRef null
        when(schedulerService.resolveConnectorFor(any())).thenReturn(
                new IngestSchedulerService.ConnectorForProfile(connector(), null));

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
        // Both paths of this controller resolve the row of the CALLING repository without an
        // index (the selector answers on profileId alone, so with the same id in two
        // repositories it hands back an arbitrary twin). A fixture that answers only the
        // selector leaves the controller at 404.
        when(profileService.getForRepository(PROFILE, REPO)).thenReturn(profile());
        ConnectorDefinition c = connector();
        // Documented credentialRef convention: ingest.* namespace.
        c.setCredentialRef("ingest.slack.sales.token");
        when(schedulerService.resolveConnectorFor(any())).thenReturn(
                new IngestSchedulerService.ConnectorForProfile(c, null));

        ResponseEntity<Map<String, Object>> r =
                controller.setCredential(REPO, FOLDER, PROFILE, Map.of("token", "new-token"));
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals("success", r.getBody().get("status"));
        verify(integrationSettingsService).writeSetting("ingest.slack.sales.token", "new-token");
    }

    @Test
    void setCredential_nonIngestCredentialRef_returns400() {
        adminCtx();
        folder();
        when(profileService.get(PROFILE)).thenReturn(profile());
        // Both paths of this controller resolve the row of the CALLING repository without an
        // index (the selector answers on profileId alone, so with the same id in two
        // repositories it hands back an arbitrary twin). A fixture that answers only the
        // selector leaves the controller at 404.
        when(profileService.getForRepository(PROFILE, REPO)).thenReturn(profile());
        ConnectorDefinition c = connector();
        // Allowlist: only ingest.* keys may be written. A non-ingest key (here
        // a core infra key) must be refused so the endpoint can't be
        // repurposed as a general config writer.
        c.setCredentialRef("couchdb.password");
        when(schedulerService.resolveConnectorFor(any())).thenReturn(
                new IngestSchedulerService.ConnectorForProfile(c, null));

        ResponseEntity<Map<String, Object>> r =
                controller.setCredential(REPO, FOLDER, PROFILE, Map.of("token", "x"));
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        verifyNoInteractions(integrationSettingsService);
    }

    @Test
    void setCredential_nonInfraNonIngestKey_alsoRejected() {
        adminCtx();
        folder();
        when(profileService.get(PROFILE)).thenReturn(profile());
        // Both paths of this controller resolve the row of the CALLING repository without an
        // index (the selector answers on profileId alone, so with the same id in two
        // repositories it hands back an arbitrary twin). A fixture that answers only the
        // selector leaves the controller at 404.
        when(profileService.getForRepository(PROFILE, REPO)).thenReturn(profile());
        ConnectorDefinition c = connector();
        // Not infra, but still outside the ingest.* namespace → rejected
        // (allowlist, not denylist).
        c.setCredentialRef("myapp.api.secret");
        when(schedulerService.resolveConnectorFor(any())).thenReturn(
                new IngestSchedulerService.ConnectorForProfile(c, null));

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

    @Test
    void run_connectorCouldNotBeRead_is503NotBadRequest() {
        // "No connector resolved for profile" (400) was the answer for all five reasons,
        // three of which say nothing about the connector. A review found the family across
        // five callers; these two verbs are the ones this controller owns.
        adminCtx();
        folder();
        when(profileService.get(PROFILE)).thenReturn(profile());
        when(profileService.getForRepository(PROFILE, REPO)).thenReturn(profile());
        when(schedulerService.resolveConnectorFor(any())).thenReturn(
                new IngestSchedulerService.ConnectorForProfile(
                        null, IngestSchedulerService.Unresolved.NOT_READ));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                controller.run(REPO, FOLDER, PROFILE).getStatusCode(),
                "a connector that could not be read was reported as a bad request");
    }

    @Test
    void run_connectorEstablishedAbsent_is404_andHidden_is503() {
        adminCtx();
        folder();
        when(profileService.get(PROFILE)).thenReturn(profile());
        when(profileService.getForRepository(PROFILE, REPO)).thenReturn(profile());
        when(schedulerService.resolveConnectorFor(any())).thenReturn(
                new IngestSchedulerService.ConnectorForProfile(
                        null, IngestSchedulerService.Unresolved.ABSENT_OR_HIDDEN));

        when(connectorService.existsIndexFree(any())).thenReturn(true);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                controller.run(REPO, FOLDER, PROFILE).getStatusCode(),
                "a row the index cannot show was reported as absent");

        when(connectorService.existsIndexFree(any())).thenReturn(false);
        assertEquals(HttpStatus.NOT_FOUND,
                controller.run(REPO, FOLDER, PROFILE).getStatusCode(),
                "an absence the walk established was not reported as one");
    }

    @Test
    void list_namesTheProfilesWhoseConnectorCouldNotBeResolved() {
        // Dropping them made an empty list, and the endpoint's own javadoc turns an empty
        // list into an instruction to the UI ("do not show the run button") — while run() for
        // the same profile answers 503. One controller said both.
        adminCtx();
        folder();
        when(profileService.listByRepository(REPO))
                .thenReturn(java.util.List.of(profile()));
        when(schedulerService.resolveConnectorFor(any())).thenReturn(
                new IngestSchedulerService.ConnectorForProfile(
                        null, IngestSchedulerService.Unresolved.NOT_READ));

        Map<String, Object> body = controller.list(REPO, FOLDER).getBody();

        assertNotNull(body);
        assertTrue(((java.util.List<?>) body.get("connectors")).isEmpty());
        assertNotNull(body.get("connectorsUnresolved"),
                "a profile whose connector could not be read vanished from the folder: " + body);
        assertTrue(body.get("connectorsUnresolved").toString().contains(PROFILE));
    }

    @Test
    void aRefusedListingIs503OnRun() {
        // The default arm answered 400 "No connector resolved" for a listing that never ran (R29).
        adminCtx();
        folder();
        when(profileService.get(PROFILE)).thenReturn(profile());
        when(profileService.getForRepository(PROFILE, REPO)).thenReturn(profile());
        when(schedulerService.resolveConnectorFor(any())).thenReturn(
                new IngestSchedulerService.ConnectorForProfile(
                        null, IngestSchedulerService.Unresolved.LISTING_REFUSED));

        ResponseEntity<Map<String, Object>> r = controller.run(REPO, FOLDER, PROFILE);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, r.getStatusCode(),
                "a listing that never ran was reported as the profile's fault: " + r.getBody());
    }
}
