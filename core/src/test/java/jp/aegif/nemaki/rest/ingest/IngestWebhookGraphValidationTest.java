package jp.aegif.nemaki.rest.ingest;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Graph validation helper + Spring mapping contract (Graph sends {@code text/plain} for subscription validation).
 */
@ExtendWith(MockitoExtension.class)
class IngestWebhookGraphValidationTest {

    @Mock
    private ConnectorDefinitionService connectorDefinitionService;
    @Mock
    private IngestSchedulerService schedulerService;
    @Mock
    private ImportProfileDefinitionService profileService;
    @Mock
    private HttpServletRequest httpRequest;

    @InjectMocks
    private IngestWebhookController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMvc() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void graphConnectorsAllowValidationTokenHandshake() {
        assertTrue(IngestWebhookController.isMicrosoftGraphSubscriptionValidation("teams", "abc"));
        assertTrue(IngestWebhookController.isMicrosoftGraphSubscriptionValidation("m365_mail", "abc"));
    }

    @Test
    void nonGraphSystemsMustNotShortCircuitOnValidationToken() {
        assertFalse(IngestWebhookController.isMicrosoftGraphSubscriptionValidation("slack", "abc"));
        assertFalse(IngestWebhookController.isMicrosoftGraphSubscriptionValidation("chatwork", "abc"));
        assertFalse(IngestWebhookController.isMicrosoftGraphSubscriptionValidation("generic", "abc"));
        assertFalse(IngestWebhookController.isMicrosoftGraphSubscriptionValidation(null, "abc"));
    }

    @Test
    void blankTokenNeverShortCircuits() {
        assertFalse(IngestWebhookController.isMicrosoftGraphSubscriptionValidation("teams", null));
        assertFalse(IngestWebhookController.isMicrosoftGraphSubscriptionValidation("teams", "  "));
        assertFalse(IngestWebhookController.isMicrosoftGraphSubscriptionValidation("m365_mail", ""));
    }

    /** Microsoft sends Content-Type: text/plain; charset=utf-8 — must match {@code consumes} mapping. */
    @Test
    void graphValidation_acceptsMicrosoftTextPlainCharsetUtf8Handshake() throws Exception {
        ConnectorDefinition teams = new ConnectorDefinition();
        teams.setConnectorId("c-teams");
        teams.setEnabled(true);
        teams.setSourceSystem("teams");
        when(connectorDefinitionService.get("c-teams")).thenReturn(teams);

        mockMvc.perform(post("/v1/ingest-webhook/c-teams")
                        .contentType("text/plain;charset=UTF-8")
                        .content("")
                        .param("validationToken", "ms-graph-validation-token"))
                .andExpect(status().isOk())
                .andExpect(content().string("ms-graph-validation-token"));
    }

    @Test
    void graphValidation_acceptsApplicationJsonWithValidationQuery() throws Exception {
        ConnectorDefinition m = new ConnectorDefinition();
        m.setConnectorId("c-mail");
        m.setEnabled(true);
        m.setSourceSystem("m365_mail");
        when(connectorDefinitionService.get("c-mail")).thenReturn(m);

        mockMvc.perform(post("/v1/ingest-webhook/c-mail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .param("validationToken", "tok-json"))
                .andExpect(status().isOk())
                .andExpect(content().string("tok-json"));
    }

    @Test
    void slackTextPlainWithValidationToken_doesNotBypassSignature() throws Exception {
        ConnectorDefinition slack = new ConnectorDefinition();
        slack.setConnectorId("c-slack");
        slack.setEnabled(true);
        slack.setSourceSystem("slack");
        when(connectorDefinitionService.get("c-slack")).thenReturn(slack);

        mockMvc.perform(post("/v1/ingest-webhook/c-slack")
                        .contentType("text/plain;charset=UTF-8")
                        .content("")
                        .param("validationToken", "must-not-echo"))
                .andExpect(status().isUnauthorized());
    }

    // ── parseGraphResourceScope tests ──

    @Test
    void parseGraphResourceScope_Teams() {
        var scope = IngestWebhookController.parseGraphResourceScope(
                "teams('T01ABC')/channels('C02DEF')/messages");
        assertEquals("T01ABC", scope.get("teamId"));
        assertEquals("C02DEF", scope.get("channelId"));
        assertNull(scope.get("userId"));
    }

    @Test
    void parseGraphResourceScope_M365MailWithFolder() {
        var scope = IngestWebhookController.parseGraphResourceScope(
                "users/admin@contoso.com/mailFolders('inbox')/messages");
        assertEquals("admin@contoso.com", scope.get("userId"));
        assertEquals("inbox", scope.get("folderId"));
        assertNull(scope.get("teamId"));
    }

    @Test
    void parseGraphResourceScope_M365MailNoFolder() {
        var scope = IngestWebhookController.parseGraphResourceScope(
                "users/admin@contoso.com/messages");
        assertEquals("admin@contoso.com", scope.get("userId"));
        // M365 Mail without explicit mailFolder defaults to "inbox"
        assertEquals("inbox", scope.get("folderId"));
    }

    @Test
    void parseGraphResourceScope_NullResource() {
        var scope = IngestWebhookController.parseGraphResourceScope(null);
        assertTrue(scope.isEmpty());
    }

    @Test
    void parseGraphResourceScope_NoOverlapWithShortIds() {
        // "ann" should NOT match "joann" — parsed from structured path, not substring
        var scope = IngestWebhookController.parseGraphResourceScope(
                "users/joann@contoso.com/mailFolders('inbox')/messages");
        assertEquals("joann@contoso.com", scope.get("userId"));
        // A profile with userId="ann" should NOT match this — the caller compares
        // parsed scope values with exact equals, not contains.
    }
}
