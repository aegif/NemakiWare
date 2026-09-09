package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.rest.ingest.mail.ImapIdleMonitor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The monitor's own refusal WORDING, where the endpoint's status depends on it.
 *
 * <p>The endpoint classifies a refusal by its text, so a message without the retry marker is
 * answered as a malformed request. The scheduler-wiring refusal was rewritten to carry the
 * marker and nothing measured the rewrite: the controller test feeds the message in by hand,
 * so a control that reverted the product's wording left it green. The control did not fire,
 * and this class is what makes it.
 */
class ImapIdleMonitorWiringTest {

    private ImportProfileDefinition delegatedImapProfile() {
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        profile.setRepositoryId("bedroom");
        profile.setEnabled(true);
        profile.setDelegated(true);
        profile.setDefaultConnectorId("c1");
        return profile;
    }

    private ConnectorDefinition imapConnector() {
        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        connector.setSourceSystem("imap");
        connector.setSourceArchetype(SourceArchetype.MESSAGE_CONTEXT);
        connector.setEnabled(true);
        return connector;
    }

    @Test
    @DisplayName("an unwired scheduler is reported as a wiring fault to retry, not as a "
            + "malformed request")
    void anUnwiredSchedulerSaysSo() {
        ImapIdleMonitor monitor = new ImapIdleMonitor();
        ImportProfileDefinitionService profiles = mock(ImportProfileDefinitionService.class);
        ConnectorDefinitionService connectors = mock(ConnectorDefinitionService.class);
        when(profiles.getOwnedRowIndexFree("p1")).thenReturn(delegatedImapProfile());
        when(connectors.get("c1")).thenReturn(imapConnector());
        when(connectors.countIndexFree("c1")).thenReturn(1);
        monitor.setProfileService(profiles);
        monitor.setConnectorService(connectors);
        // schedulerService deliberately left unwired.

        String refusal = monitor.startIdle("p1");

        assertNotNull(refusal, "a delegated profile started IDLE with no authorization wiring");
        assertTrue(refusal.contains("not wired on this node"),
                "the refusal does not say the node is unwired: " + refusal);
        assertTrue(refusal.contains("retry shortly"),
                "the refusal carries no retry marker, so the endpoint answers 400: " + refusal);
    }
}
