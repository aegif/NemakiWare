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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
}
