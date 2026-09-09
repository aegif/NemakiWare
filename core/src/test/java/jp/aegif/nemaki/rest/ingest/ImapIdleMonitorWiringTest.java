package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.rest.ingest.mail.ImapIdleMonitor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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

    @Test
    @DisplayName("the wordings the endpoint's 404 and 403 arms depend on come from the "
            + "product, not from the test")
    void theStatusArmsAreCoupledToTheProductsWording() {
        // The controller tests hand the classifier a string literal, so they measure the
        // classifier and not the message. That is how the 503 arm came to have no lock at
        // all — a control reverted the product's wording and every test stayed green. The
        // same gap was still open for the 404 and the 403; these two close it. (The 409
        // "already running" wording needs a live session in the map and is not measured
        // here; that is recorded rather than claimed.)
        ImapIdleMonitor monitor = new ImapIdleMonitor();
        ImportProfileDefinitionService profiles = mock(ImportProfileDefinitionService.class);
        when(profiles.getOwnedRowIndexFree("gone")).thenReturn(null);
        monitor.setProfileService(profiles);

        String absent = monitor.startIdle("gone");
        assertNotNull(absent);
        assertTrue(absent.startsWith("Profile not found"),
                "the 404 arm reads a prefix the product no longer writes: " + absent);

        ConnectorDefinitionService connectors = mock(ConnectorDefinitionService.class);
        when(profiles.getOwnedRowIndexFree("p1")).thenReturn(delegatedImapProfile());
        when(connectors.get("c1")).thenReturn(imapConnector());
        when(connectors.countIndexFree("c1")).thenReturn(1);
        IngestSchedulerService scheduler = mock(IngestSchedulerService.class);
        when(scheduler.authorizeDelegatedFetch(any(), any())).thenReturn(deniedBy(
                DenialReason.CREATOR_USER_INACTIVE));
        monitor.setConnectorService(connectors);
        monitor.setSchedulerService(scheduler);

        String denied = monitor.startIdle("p1");
        assertNotNull(denied, "a denied delegation started IDLE anyway");
        assertTrue(denied.startsWith("Delegated authorization denied"),
                "the 403 arm reads a prefix the product no longer writes: " + denied);
    }

    /** A denial, built through the product's own (private) constructor. */
    private static IngestSchedulerService.DelegatedAuthorization deniedBy(DenialReason reason) {
        try {
            java.lang.reflect.Constructor<IngestSchedulerService.DelegatedAuthorization> c =
                    IngestSchedulerService.DelegatedAuthorization.class.getDeclaredConstructor(
                            boolean.class,
                            org.apache.chemistry.opencmis.commons.server.CallContext.class,
                            DenialReason.class);
            c.setAccessible(true);
            return c.newInstance(false, null, reason);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("the denial shape changed: " + e, e);
        }
    }
}
