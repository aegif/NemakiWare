package jp.aegif.nemaki.rest.ingest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestWebhookGraphValidationTest {

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
}
