package jp.aegif.nemaki.rest.ingest.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import jp.aegif.nemaki.rest.ingest.ConnectorDefinition;
import jp.aegif.nemaki.rest.ingest.ConnectorDefinitionService;
import jp.aegif.nemaki.rest.ingest.FetchSupport;
import jp.aegif.nemaki.rest.ingest.ImportProfileDefinition;
import jp.aegif.nemaki.rest.ingest.ImportProfileDefinitionService;
import jp.aegif.nemaki.rest.ingest.ImportProfileDefinitionServiceImpl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The IDLE session registry — the data the DELETE path's scheduler decision reads.
 *
 * <p>The decision itself is locked at the controller with the service mocked, so until this
 * class existed nothing measured the registry underneath it: a review pointed out that
 * {@code ImapIdleMonitor} had no test and no negative control at all, while this batch had
 * just promoted its contents to the basis of an authorisation-shaped decision. These tests
 * drive the registry directly. They do NOT exercise a real IMAP session, a virtual thread,
 * or {@code startIdle}'s I/O — that remains unmeasured.
 */
class ImapIdleSessionRegistryTest {

    private static final String PROF = "cloud-import-bedroom";

    private ImapIdleMonitor.IdleSession session(String repositoryId) {
        return new ImapIdleMonitor.IdleSession(mock(ImapConnectorAdapter.class), repositoryId);
    }

    @Test
    @DisplayName("a stale thread's cleanup does not erase the session that replaced it")
    void retirementIsByIdentityNotByKey() {
        // A stop gives the old thread ten seconds and then moves on; that thread can still be
        // in a backoff sleep and run its cleanup afterwards. Removing by profileId alone took
        // the REPLACEMENT session out of the registry: it stayed live, getIdleProfiles() no
        // longer showed it, so the delete path concluded "no IDLE session is running" and left
        // it holding a connection nobody could close — and stopIdle answered "no session running"
        // for ever after. A review traced the sequence.
        ImapIdleMonitor monitor = new ImapIdleMonitor();
        ImapIdleMonitor.IdleSession stale = session("bedroom");
        ImapIdleMonitor.IdleSession replacement = session("canopy");

        assertTrue(monitor.registerSession(PROF, stale));
        monitor.stopIdle(PROF);                       // the stop that timed out
        assertTrue(monitor.registerSession(PROF, replacement));

        monitor.retireSession(PROF, stale);           // the old thread's finally, arriving late

        assertEquals(List.of(PROF), monitor.getIdleProfiles(),
                "the replacement session was erased by the stale thread's cleanup");
        assertEquals("canopy", monitor.getIdleRepository(PROF),
                "the registry answers for the session that is gone, not the one that is live");
    }

    @Test
    @DisplayName("a second start does not overwrite a live session")
    void registrationIsAtomic() {
        // containsKey-then-put let two callers both pass; the second put replaced a live
        // adapter that nothing could then stop.
        ImapIdleMonitor monitor = new ImapIdleMonitor();
        ImapIdleMonitor.IdleSession first = session("bedroom");

        assertTrue(monitor.registerSession(PROF, first));
        assertFalse(monitor.registerSession(PROF, session("canopy")),
                "a second session claimed a profileId that already had one");
        assertEquals("bedroom", monitor.getIdleRepository(PROF),
                "the live session was replaced by the one that should have been refused");
    }

    @Test
    @DisplayName("a profile deleted while IDLE was starting does not get a session")
    void aStartLosesToADeleteThatFinishedFirst() {
        // startIdle read the profile, then spent time resolving a connector and a password
        // before registering. A DELETE running in that window looked for a session, found
        // none, and returned — and the session installed afterwards kept importing into the
        // row that had just been removed. Registering FIRST and asking again afterwards
        // leaves no window: the delete either sees the registration or this read finds
        // nothing. A review named the race.
        ImapIdleMonitor monitor = new ImapIdleMonitor();
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId(PROF);
        profile.setRepositoryId("bedroom");
        profile.setDefaultConnectorId("conn-1");
        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("conn-1");
        connector.setSourceSystem("imap");

        ImportProfileDefinitionService profiles = mock(ImportProfileDefinitionService.class);
        when(profiles.getOwnedRowIndexFree(PROF)).thenReturn(profile);
        when(profiles.getForRepository(PROF, "bedroom")).thenReturn(null);   // deleted meanwhile
        ConnectorDefinitionService connectors = mock(ConnectorDefinitionService.class);
        when(connectors.get("conn-1")).thenReturn(connector);
        when(connectors.countIndexFree("conn-1")).thenReturn(1);
        FetchSupport fetch = mock(FetchSupport.class);
        when(fetch.resolvePasswordOrRefuse(connector)).thenReturn("pw");
        monitor.setProfileService(profiles);
        monitor.setConnectorService(connectors);
        monitor.setFetchSupport(fetch);

        String refusal = monitor.startIdle(PROF);

        assertTrue(refusal != null && refusal.contains("no longer has a row"),
                "IDLE started for a profile that had been deleted: " + refusal);
        assertEquals(List.of(), monitor.getIdleProfiles(),
                "the refused start left its registration behind");
    }

    @Test
    @DisplayName("a start whose existence check cannot answer does not get a session either")
    void aStartWhoseCheckCannotAnswerIsRefused() {
        // "Could not ask" is not "still there". Starting a capture on an unprovable profile is
        // the failure this batch is about, in the direction that keeps importing.
        ImapIdleMonitor monitor = new ImapIdleMonitor();
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId(PROF);
        profile.setRepositoryId("bedroom");
        profile.setDefaultConnectorId("conn-1");
        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("conn-1");
        connector.setSourceSystem("imap");

        ImportProfileDefinitionService profiles = mock(ImportProfileDefinitionService.class);
        when(profiles.getOwnedRowIndexFree(PROF)).thenReturn(profile);
        when(profiles.getForRepository(PROF, "bedroom"))
                .thenThrow(new RuntimeException("the index could not be read"));
        ConnectorDefinitionService connectors = mock(ConnectorDefinitionService.class);
        when(connectors.get("conn-1")).thenReturn(connector);
        when(connectors.countIndexFree("conn-1")).thenReturn(1);
        FetchSupport fetch = mock(FetchSupport.class);
        when(fetch.resolvePasswordOrRefuse(connector)).thenReturn("pw");
        monitor.setProfileService(profiles);
        monitor.setConnectorService(connectors);
        monitor.setFetchSupport(fetch);

        String refusal = monitor.startIdle(PROF);

        assertTrue(refusal != null && refusal.contains("could not be"),
                "an unprovable profile started importing: " + refusal);
        assertEquals(List.of(), monitor.getIdleProfiles(),
                "the refused start left its registration behind");
    }

    @Test
    @DisplayName("retiring the session that IS registered clears it")
    void retirementStillWorks() {
        // The counterpart: identity-checked removal must still remove. Without this the fix
        // could be "never remove" and the test above would still pass.
        ImapIdleMonitor monitor = new ImapIdleMonitor();
        ImapIdleMonitor.IdleSession only = session("bedroom");
        assertTrue(monitor.registerSession(PROF, only));

        monitor.retireSession(PROF, only);

        assertEquals(List.of(), monitor.getIdleProfiles(), "the session was not retired");
        assertNull(monitor.getIdleRepository(PROF));
    }

    @Test
    @DisplayName("a DELETE that wins during the existence check does not publish a live session")
    void aDeleteDuringExistenceCheckDoesNotPublishAnInvisibleSession() {
        // stillThere can return true after DELETE has already stopIdle'd: getForRepository
        // walks the whole database after it sees the row. The first fix registered first
        // and then asked, and claimed there was no window. Starting the thread after that
        // read re-armed a stopped adapter that was no longer in the map. A review named it.
        ImapIdleMonitor monitor = new ImapIdleMonitor();
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId(PROF);
        profile.setRepositoryId("bedroom");
        profile.setDefaultConnectorId("conn-1");
        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("conn-1");
        connector.setSourceSystem("imap");

        ImportProfileDefinitionService profiles = mock(ImportProfileDefinitionService.class);
        when(profiles.getOwnedRowIndexFree(PROF)).thenReturn(profile);
        when(profiles.getForRepository(PROF, "bedroom")).thenAnswer(inv -> {
            monitor.stopIdle(PROF);
            return profile;
        });
        ConnectorDefinitionService connectors = mock(ConnectorDefinitionService.class);
        when(connectors.get("conn-1")).thenReturn(connector);
        when(connectors.countIndexFree("conn-1")).thenReturn(1);
        FetchSupport fetch = mock(FetchSupport.class);
        when(fetch.resolvePasswordOrRefuse(connector)).thenReturn("pw");
        monitor.setProfileService(profiles);
        monitor.setConnectorService(connectors);
        monitor.setFetchSupport(fetch);

        String refusal = monitor.startIdle(PROF);

        assertTrue(refusal != null && refusal.contains("stopped before IDLE started"),
                "IDLE claimed to start after DELETE had already taken the session: " + refusal);
        assertEquals(List.of(), monitor.getIdleProfiles(),
                "a session that startIdle published after DELETE is invisible and unstoppable");
    }

    @Test
    @DisplayName("stopIdle before the loop starts does not re-arm idleRunning")
    void aStopBeforeStartDoesNotRearmIdle() {
        // ImapConnectorAdapter.startIdle used to write idleRunning=true unconditionally.
        // A DELETE that ran after registration and before the loop then left a live
        // connection. armIdle is the only admission; a review required it.
        ImapConnectorAdapter adapter = new ImapConnectorAdapter(
                mock(ConnectorDefinition.class), "pw");
        adapter.stopIdle();
        assertFalse(adapter.armIdle(), "a stopped adapter accepted a start");
        assertFalse(adapter.isIdleRunning(), "idleRunning was written back to true after stop");
    }

    @Test
    @DisplayName("a selector miss is not absence: a hidden owned row reaches getForRepository")
    void aHiddenOwnedRowReachesTheConfinedRecheck() {
        // startIdle returned "Profile not found" when get() was null, so getForRepository
        // never ran. A hidden legacy row then could not be monitored. A review named it.
        ImapIdleMonitor monitor = new ImapIdleMonitor();
        ImportProfileDefinition hidden = new ImportProfileDefinition();
        hidden.setProfileId(PROF);
        hidden.setRepositoryId("bedroom");
        hidden.setDefaultConnectorId("conn-1");
        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("conn-1");
        connector.setSourceSystem("imap");

        ImportProfileDefinitionService profiles = mock(ImportProfileDefinitionService.class);
        when(profiles.get(PROF)).thenReturn(null);
        when(profiles.getOwnedRowIndexFree(PROF)).thenReturn(hidden);
        when(profiles.getForRepository(PROF, "bedroom")).thenReturn(null);
        ConnectorDefinitionService connectors = mock(ConnectorDefinitionService.class);
        when(connectors.get("conn-1")).thenReturn(connector);
        when(connectors.countIndexFree("conn-1")).thenReturn(1);
        FetchSupport fetch = mock(FetchSupport.class);
        when(fetch.resolvePasswordOrRefuse(connector)).thenReturn("pw");
        monitor.setProfileService(profiles);
        monitor.setConnectorService(connectors);
        monitor.setFetchSupport(fetch);

        String refusal = monitor.startIdle(PROF);

        assertTrue(refusal != null && refusal.contains("no longer has a row"),
                "a hidden owned row was reported absent before the confined re-check: "
                        + refusal);
        org.mockito.Mockito.verify(profiles).getOwnedRowIndexFree(PROF);
        org.mockito.Mockito.verify(profiles).getForRepository(PROF, "bedroom");
        assertEquals(List.of(), monitor.getIdleProfiles());
    }

    @Test
    @DisplayName("an owned selector hit still refuses a cross-repository twin pair")
    void anOwnedSelectorHitStillRefusesATwinPair() {
        // get() returning one owned row used to skip the walk, so IDLE started into
        // whichever repository the selector listed first. A review named the remaining door.
        ImapIdleMonitor monitor = new ImapIdleMonitor();
        ImportProfileDefinition one = new ImportProfileDefinition();
        one.setProfileId(PROF);
        one.setRepositoryId("bedroom");
        ImportProfileDefinitionService profiles = mock(ImportProfileDefinitionService.class);
        when(profiles.get(PROF)).thenReturn(one);
        when(profiles.getOwnedRowIndexFree(PROF)).thenThrow(
                new ImportProfileDefinitionServiceImpl.ProfileHasTwinRowsException(
                        "import profile " + PROF
                                + " has more than one owned definition row; resolve the pair"));
        monitor.setProfileService(profiles);

        String refusal = monitor.startIdle(PROF);

        assertTrue(refusal != null && refusal.contains("more than one owned definition row"),
                "IDLE started for one of a twin pair: " + refusal);
        assertEquals(List.of(), monitor.getIdleProfiles());
        org.mockito.Mockito.verify(profiles, org.mockito.Mockito.never()).get(PROF);
    }

    @Test
    @DisplayName("a hidden connector is not reported as absence")
    void aHiddenConnectorIsNotReportedAsAbsence() {
        ImapIdleMonitor monitor = new ImapIdleMonitor();
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId(PROF);
        profile.setRepositoryId("bedroom");
        profile.setDefaultConnectorId("conn-1");
        ImportProfileDefinitionService profiles = mock(ImportProfileDefinitionService.class);
        when(profiles.getOwnedRowIndexFree(PROF)).thenReturn(profile);
        ConnectorDefinitionService connectors = mock(ConnectorDefinitionService.class);
        when(connectors.get("conn-1")).thenReturn(null);
        when(connectors.existsIndexFree("conn-1")).thenReturn(true);
        monitor.setProfileService(profiles);
        monitor.setConnectorService(connectors);

        String refusal = monitor.startIdle(PROF);

        assertTrue(refusal != null && refusal.contains("retry shortly"),
                "a hidden connector was reported as absence: " + refusal);
        assertEquals(List.of(), monitor.getIdleProfiles());
    }

    @Test
    @DisplayName("a later repository change is read from the live row, not the start snapshot")
    void aLaterRepositoryChangeStopsLiveAdmission() {
        // Per-message auth used the start-time profile. A later target/repository
        // change still passed, while executeMailImport resolved the live row.
        ImapIdleMonitor monitor = new ImapIdleMonitor();
        ImportProfileDefinition live = new ImportProfileDefinition();
        live.setProfileId(PROF);
        live.setRepositoryId("canopy");
        live.setDefaultConnectorId("conn-1");
        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("conn-1");
        connector.setSourceSystem("imap");
        ImportProfileDefinitionService profiles = mock(ImportProfileDefinitionService.class);
        when(profiles.getOwnedRowIndexFree(PROF)).thenReturn(live);
        ConnectorDefinitionService connectors = mock(ConnectorDefinitionService.class);
        when(connectors.get("conn-1")).thenReturn(connector);
        when(connectors.countIndexFree("conn-1")).thenReturn(1);
        monitor.setProfileService(profiles);
        monitor.setConnectorService(connectors);

        ImapIdleMonitor.LiveLoad load = monitor.loadLiveConfig(PROF, "bedroom", "conn-1", "INBOX");

        assertTrue(load.refusal() != null && load.refusal().contains("different repository"),
                "the start-time repository was still admitted: " + load.refusal());
    }

    @Test
    @DisplayName("a later delegation revoke is read from the live row, not the start snapshot")
    void aLaterDelegationRevokeStopsLiveAdmission() {
        ImapIdleMonitor monitor = new ImapIdleMonitor();
        ImportProfileDefinition live = new ImportProfileDefinition();
        live.setProfileId(PROF);
        live.setRepositoryId("bedroom");
        live.setDelegated(false);
        live.setDefaultConnectorId("conn-1");
        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("conn-1");
        connector.setSourceSystem("imap");
        ImportProfileDefinitionService profiles = mock(ImportProfileDefinitionService.class);
        when(profiles.getOwnedRowIndexFree(PROF)).thenReturn(live);
        ConnectorDefinitionService connectors = mock(ConnectorDefinitionService.class);
        when(connectors.get("conn-1")).thenReturn(connector);
        when(connectors.countIndexFree("conn-1")).thenReturn(1);
        monitor.setProfileService(profiles);
        monitor.setConnectorService(connectors);

        ImapIdleMonitor.LiveLoad load = monitor.loadLiveConfig(
                PROF, "bedroom", "conn-1", "INBOX", true);

        assertTrue(load.refusal() != null && load.refusal().contains("no longer delegated"),
                "a revoked delegation was still admitted as admin: " + load.refusal());
    }

    @Test
    @DisplayName("a later connector endpoint change is not fetched through the start-time socket")
    void aLaterConnectorEndpointChangeStopsLiveAdmission() {
        // Per-message auth used the live connector row, but fetchMessage kept the
        // start-time adapter. The same connectorId with a new host still imported
        // from the old mailbox. A review named the split.
        ImapIdleMonitor monitor = new ImapIdleMonitor();
        ImportProfileDefinition liveProfile = new ImportProfileDefinition();
        liveProfile.setProfileId(PROF);
        liveProfile.setRepositoryId("bedroom");
        liveProfile.setDefaultConnectorId("conn-1");
        ConnectorDefinition live = new ConnectorDefinition();
        live.setConnectorId("conn-1");
        live.setSourceSystem("imap");
        live.setEndpoint("imap.new.example:993");
        live.setTenantId("user@example.com");
        ConnectorDefinition started = new ConnectorDefinition();
        started.setEndpoint("imap.old.example:993");
        started.setTenantId("user@example.com");
        ImportProfileDefinitionService profiles = mock(ImportProfileDefinitionService.class);
        when(profiles.getOwnedRowIndexFree(PROF)).thenReturn(liveProfile);
        ConnectorDefinitionService connectors = mock(ConnectorDefinitionService.class);
        when(connectors.get("conn-1")).thenReturn(live);
        when(connectors.countIndexFree("conn-1")).thenReturn(1);
        FetchSupport fetch = mock(FetchSupport.class);
        when(fetch.resolvePasswordOrRefuse(live)).thenReturn("pw");
        monitor.setProfileService(profiles);
        monitor.setConnectorService(connectors);
        monitor.setFetchSupport(fetch);

        ImapIdleMonitor.LiveLoad load = monitor.loadLiveConfig(
                PROF, "bedroom", "conn-1", "INBOX", false,
                ImapIdleMonitor.connectionIdentity(started, "pw"));

        assertTrue(load.refusal() != null && load.refusal().contains("connection changed"),
                "the start-time IMAP socket was still admitted: " + load.refusal());
    }

    @Test
    @DisplayName("a credential the store could not answer is not 'the connection changed'")
    void aCredentialReadThatFailedIsNotAConnectionChange() {
        // resolvePassword answers null for "no credential", "no stored value" and "the store
        // did not answer" alike. The third made the identity comparison fail and this method
        // report that the CONNECTION CHANGED — a fact about the connector that nothing
        // established — after which the caller tore the session down permanently, since
        // startIdle has exactly one caller and nothing re-arms it. A review traced it, and
        // noted that the locks here stub resolvePassword to succeed, so the arm was never
        // measured at all.
        ImapIdleMonitor monitor = new ImapIdleMonitor();
        ImportProfileDefinition liveProfile = new ImportProfileDefinition();
        liveProfile.setProfileId(PROF);
        liveProfile.setRepositoryId("bedroom");
        liveProfile.setDefaultConnectorId("conn-1");
        ConnectorDefinition live = new ConnectorDefinition();
        live.setConnectorId("conn-1");
        live.setEndpoint("imap.example:993");
        live.setTenantId("user@example.com");
        live.setCredentialRef("secret.imap");
        ImportProfileDefinitionService profiles = mock(ImportProfileDefinitionService.class);
        when(profiles.getOwnedRowIndexFree(PROF)).thenReturn(liveProfile);
        ConnectorDefinitionService connectors = mock(ConnectorDefinitionService.class);
        when(connectors.get("conn-1")).thenReturn(live);
        when(connectors.countIndexFree("conn-1")).thenReturn(1);
        FetchSupport fetch = mock(FetchSupport.class);
        when(fetch.resolvePasswordOrRefuse(live)).thenThrow(
                new jp.aegif.nemaki.rest.controller.IntegrationSettingsService
                        .SettingUnreadableException("the credential 'secret.imap' of connector"
                                + " conn-1 could not be read: the configuration database did"
                                + " not answer; retry shortly"));
        monitor.setProfileService(profiles);
        monitor.setConnectorService(connectors);
        monitor.setFetchSupport(fetch);

        ImapIdleMonitor.LiveLoad load = monitor.loadLiveConfig(
                PROF, "bedroom", "conn-1", "INBOX", false,
                ImapIdleMonitor.connectionIdentity(live, "pw"));

        assertTrue(load.refusal() != null,
                "a credential that could not be read was admitted as the same connection");
        assertFalse(load.refusal().contains("connection changed"),
                "a read that could not answer was stated as a change to the connector: "
                        + load.refusal());
        assertTrue(load.refusal().contains("could not be read"),
                "the refusal does not say the read failed: " + load.refusal());
        // And it must land on the arm the caller reads as "could not ask", or the session is
        // torn down for good on a blip.
        assertTrue(ImapIdleMonitor.refusalCouldNotAsk(load.refusal()),
                "the refusal is classified as a settled answer, so IDLE stops permanently: "
                        + load.refusal());
    }

    @Test
    @DisplayName("the deterministic-id mismatch is a settled refusal too")
    void theDeterministicIdMismatchIsSettled() {
        // ConnectorDefinitionServiceImpl says "could not be read as THAT connector" when the
        // document at the deterministic id names a different connector — a standing condition
        // an administrator repairs. The first exclusion list held "as a profile" and "as a
        // connector" and missed this phrasing, so the session NEVER tore down: every arriving
        // message took the transient arm, captured nothing, wrote a DLQ row, and ran a full
        // nemaki_conf walk, for ever. A review found it.
        assertFalse(ImapIdleMonitor.refusalCouldNotAsk(
                "connector conn-1 exists but could not be read as that connector"),
                "the deterministic-id mismatch is standing, not transient");
        assertFalse(ImapIdleMonitor.refusalCouldNotAsk(
                "connector conn-1 could not be read; retry shortly: connector conn-1 exists but"
                        + " could not be read as that connector"),
                "the WRAPPED form is still standing — the wrapper's own 'retry shortly' must "
                        + "not win over what it wrapped");
    }

    @Test
    @DisplayName("a corrupt stored row is NOT 'could not ask' — IDLE must stop, not spin")
    void aCorruptRowIsASettledRefusal() {
        // The predicate the per-message path keys on. It had no test and no control anywhere,
        // so a future edit to its token list would be silent. A review found that.
        assertTrue(ImapIdleMonitor.refusalCouldNotAsk(
                "connector conn-1 exists but could not be read; retry shortly"));
        assertTrue(ImapIdleMonitor.refusalCouldNotAsk(
                "whether import profile p1 exists could not be established"));
        assertFalse(ImapIdleMonitor.refusalCouldNotAsk(
                "row ingest_profile:p1 could not be read as a profile (bad field)"),
                "a corrupt stored row is standing, not transient — leaving IDLE running on it "
                        + "spins a full nemaki_conf walk on every message for ever");
        assertFalse(ImapIdleMonitor.refusalCouldNotAsk(
                "import profile p1 is no longer delegated; IDLE stopping"),
                "a settled revoke must stop the session");
    }

    @Test
    @DisplayName("a newline-crossing tenantId and authType pair is not the same connection")
    void aNewlineCrossingTenantAndAuthTypeIsNotTheSameConnection() {
        ConnectorDefinition started = new ConnectorDefinition();
        started.setTenantId("a");
        started.setAuthType("b\nc");
        ConnectorDefinition live = new ConnectorDefinition();
        live.setTenantId("a\nb");
        live.setAuthType("c");

        assertNotEquals(ImapIdleMonitor.connectionIdentity(started, "pw"),
                ImapIdleMonitor.connectionIdentity(live, "pw"),
                "a newline-crossing pair was treated as the same IMAP socket");
    }

    @Test
    @DisplayName("a newline-crossing identity change stops live admission")
    void aNewlineCrossingIdentityChangeStopsLiveAdmission() {
        ImapIdleMonitor monitor = new ImapIdleMonitor();
        ImportProfileDefinition liveProfile = new ImportProfileDefinition();
        liveProfile.setProfileId(PROF);
        liveProfile.setRepositoryId("bedroom");
        liveProfile.setDefaultConnectorId("conn-1");
        ConnectorDefinition live = new ConnectorDefinition();
        live.setConnectorId("conn-1");
        live.setSourceSystem("imap");
        live.setTenantId("a\nb");
        live.setAuthType("c");
        ConnectorDefinition started = new ConnectorDefinition();
        started.setTenantId("a");
        started.setAuthType("b\nc");
        ImportProfileDefinitionService profiles = mock(ImportProfileDefinitionService.class);
        when(profiles.getOwnedRowIndexFree(PROF)).thenReturn(liveProfile);
        ConnectorDefinitionService connectors = mock(ConnectorDefinitionService.class);
        when(connectors.get("conn-1")).thenReturn(live);
        when(connectors.countIndexFree("conn-1")).thenReturn(1);
        FetchSupport fetch = mock(FetchSupport.class);
        when(fetch.resolvePasswordOrRefuse(live)).thenReturn("pw");
        monitor.setProfileService(profiles);
        monitor.setConnectorService(connectors);
        monitor.setFetchSupport(fetch);

        ImapIdleMonitor.LiveLoad load = monitor.loadLiveConfig(
                PROF, "bedroom", "conn-1", "INBOX", false,
                ImapIdleMonitor.connectionIdentity(started, "pw"));

        assertTrue(load.refusal() != null && load.refusal().contains("connection changed"),
                "a newline-crossing identity was still admitted: " + load.refusal());
    }

    @Test
    @DisplayName("a null tenantId and a blank tenantId are not the same connection")
    void aNullAndBlankTenantIdAreNotTheSameConnection() {
        ConnectorDefinition started = new ConnectorDefinition();
        started.setTenantId(null);
        ConnectorDefinition live = new ConnectorDefinition();
        live.setTenantId("");

        assertNotEquals(ImapIdleMonitor.connectionIdentity(started, "pw"),
                ImapIdleMonitor.connectionIdentity(live, "pw"),
                "null and blank tenantId were treated as the same IMAP socket");
    }

    @Test
    @DisplayName("a connector row whose body names another id is not admitted")
    void aConnectorBodyThatNamesAnotherIdIsNotAdmitted() {
        ImapIdleMonitor monitor = new ImapIdleMonitor();
        ImportProfileDefinition liveProfile = new ImportProfileDefinition();
        liveProfile.setProfileId(PROF);
        liveProfile.setRepositoryId("bedroom");
        liveProfile.setDefaultConnectorId("conn-a");
        ConnectorDefinition bodyB = new ConnectorDefinition();
        bodyB.setConnectorId("conn-b");
        bodyB.setSourceSystem("imap");
        ImportProfileDefinitionService profiles = mock(ImportProfileDefinitionService.class);
        when(profiles.getOwnedRowIndexFree(PROF)).thenReturn(liveProfile);
        ConnectorDefinitionService connectors = mock(ConnectorDefinitionService.class);
        when(connectors.get("conn-a")).thenReturn(bodyB);
        when(connectors.countIndexFree("conn-b")).thenReturn(1);
        monitor.setProfileService(profiles);
        monitor.setConnectorService(connectors);

        ImapIdleMonitor.LiveLoad load = monitor.loadLiveConfig(
                PROF, "bedroom", "conn-a", "INBOX");

        assertTrue(load.refusal() != null && load.refusal().contains("could not be read as that connector"),
                "a row of A whose body named B was still admitted: " + load.refusal());
    }

    @Test
    @DisplayName("a selector miss of a profile that is truly gone is still not found")
    void aSelectorMissOfAGoneProfileIsStillNotFound() {
        ImapIdleMonitor monitor = new ImapIdleMonitor();
        ImportProfileDefinitionService profiles = mock(ImportProfileDefinitionService.class);
        when(profiles.get(PROF)).thenReturn(null);
        when(profiles.getOwnedRowIndexFree(PROF)).thenReturn(null);
        monitor.setProfileService(profiles);

        String refusal = monitor.startIdle(PROF);

        assertTrue(refusal != null && refusal.contains("not found"),
                "a profile no index-free walk can find was not reported absent: " + refusal);
        assertEquals(List.of(), monitor.getIdleProfiles());
    }

    @Test
    @DisplayName("armIdle still admits a session that has not been stopped")
    void armIdleStillAdmitsAFreshAdapter() {
        ImapConnectorAdapter adapter = new ImapConnectorAdapter(
                mock(ConnectorDefinition.class), "pw");
        assertTrue(adapter.armIdle(), "a fresh adapter was refused a start");
        assertTrue(adapter.isIdleRunning());
    }

    @Test
    @DisplayName("a disabled profile is refused at IDLE start, not only on a later re-read")
    void aDisabledProfileIsRefusedAtIdleStart() {
        ImapIdleMonitor monitor = new ImapIdleMonitor();
        ImportProfileDefinition disabled = new ImportProfileDefinition();
        disabled.setProfileId(PROF);
        disabled.setEnabled(false);
        disabled.setRepositoryId("bedroom");
        disabled.setDefaultConnectorId("conn-1");
        ImportProfileDefinitionService profiles = mock(ImportProfileDefinitionService.class);
        when(profiles.getOwnedRowIndexFree(PROF)).thenReturn(disabled);
        monitor.setProfileService(profiles);

        String refusal = monitor.startIdle(PROF);

        assertTrue(refusal != null && refusal.contains("disabled"),
                "IDLE started for a disabled profile: " + refusal);
        assertEquals(List.of(), monitor.getIdleProfiles());
    }

    @Test
    @DisplayName("a later connector disable is read from the live row")
    void aLaterConnectorDisableStopsLiveAdmission() {
        ImapIdleMonitor monitor = new ImapIdleMonitor();
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId(PROF);
        profile.setRepositoryId("bedroom");
        profile.setDefaultConnectorId("conn-1");
        ConnectorDefinition disabled = new ConnectorDefinition();
        disabled.setConnectorId("conn-1");
        disabled.setSourceSystem("imap");
        disabled.setEnabled(false);
        ImportProfileDefinitionService profiles = mock(ImportProfileDefinitionService.class);
        when(profiles.getOwnedRowIndexFree(PROF)).thenReturn(profile);
        ConnectorDefinitionService connectors = mock(ConnectorDefinitionService.class);
        when(connectors.get("conn-1")).thenReturn(disabled);
        when(connectors.countIndexFree("conn-1")).thenReturn(1);
        monitor.setProfileService(profiles);
        monitor.setConnectorService(connectors);

        ImapIdleMonitor.LiveLoad load = monitor.loadLiveConfig(PROF, "bedroom", "conn-1", "INBOX");

        assertTrue(load.refusal() != null && load.refusal().contains("disabled"),
                "a disabled connector was still admitted: " + load.refusal());
    }

    @Test
    @DisplayName("a selector leftover connector is retry, not the start-time socket")
    void aConnectorSelectorHitWithWalkMissIsRetry() {
        ImapIdleMonitor monitor = new ImapIdleMonitor();
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId(PROF);
        profile.setRepositoryId("bedroom");
        profile.setDefaultConnectorId("conn-1");
        ConnectorDefinition leftover = new ConnectorDefinition();
        leftover.setConnectorId("conn-1");
        leftover.setSourceSystem("imap");
        leftover.setEnabled(true);
        ImportProfileDefinitionService profiles = mock(ImportProfileDefinitionService.class);
        when(profiles.getOwnedRowIndexFree(PROF)).thenReturn(profile);
        ConnectorDefinitionService connectors = mock(ConnectorDefinitionService.class);
        when(connectors.get("conn-1")).thenReturn(leftover);
        when(connectors.countIndexFree("conn-1")).thenReturn(0);
        monitor.setProfileService(profiles);
        monitor.setConnectorService(connectors);

        ImapIdleMonitor.LiveLoad load = monitor.loadLiveConfig(PROF, null, null, null);

        assertTrue(load.refusal() != null && load.refusal().contains("retry shortly"),
                "a selector leftover connector was started: " + load.refusal());
    }

    @Test
    @DisplayName("a connector the profile does not allow is refused at IDLE start")
    void aDisallowedConnectorIsRefusedAtIdleStart() {
        ImapIdleMonitor monitor = new ImapIdleMonitor();
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId(PROF);
        profile.setRepositoryId("bedroom");
        profile.setDefaultConnectorId("conn-1");
        profile.setAllowedConnectorIds(List.of("other-only"));
        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("conn-1");
        connector.setSourceSystem("imap");
        ImportProfileDefinitionService profiles = mock(ImportProfileDefinitionService.class);
        when(profiles.getOwnedRowIndexFree(PROF)).thenReturn(profile);
        ConnectorDefinitionService connectors = mock(ConnectorDefinitionService.class);
        when(connectors.get("conn-1")).thenReturn(connector);
        when(connectors.countIndexFree("conn-1")).thenReturn(1);
        monitor.setProfileService(profiles);
        monitor.setConnectorService(connectors);

        String refusal = monitor.startIdle(PROF);

        assertTrue(refusal != null && refusal.contains("not allowed"),
                "a disallowed connector was started: " + refusal);
        assertEquals(List.of(), monitor.getIdleProfiles());
    }
}
