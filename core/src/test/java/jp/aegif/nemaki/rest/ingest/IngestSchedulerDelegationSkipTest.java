package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JVM-level defence-in-depth test: the scheduler must NOT call
 * {@code executeFetch()} for a profile whose {@code delegated=true}, even
 * if some upstream layer (direct CouchDB write, hand-edited record, future
 * migration bug) somehow let {@code schedulerEnabled=true} through.
 *
 * <p>The controller already refuses to set {@code schedulerEnabled=true}
 * on a delegated profile (covered by the API E2E spec) — this test pins
 * the second line of defence inside {@link IngestSchedulerService}.
 *
 * <p>Also verifies that the "skipping delegated profile" WARN is emitted
 * exactly once per profileId per JVM lifetime, regardless of how many
 * poll cycles see the same broken record.
 */
class IngestSchedulerDelegationSkipTest {

    private static final String REPO = "bedroom";
    private static final String DELEGATED_PROFILE = "delg-prof-1";
    private static final String ADMIN_PROFILE = "admin-prof-1";

    private IngestSchedulerService scheduler;
    private ImportProfileDefinitionService profileService;
    private ConnectorDefinitionService connectorService;
    private CanonicalImportService canonicalImportService;
    private RepositoryInfoMap repositoryInfoMap;

    @BeforeEach
    void setUp() {
        scheduler = new IngestSchedulerService();
        profileService = mock(ImportProfileDefinitionService.class);
        connectorService = mock(ConnectorDefinitionService.class);
        canonicalImportService = mock(CanonicalImportService.class);
        repositoryInfoMap = mock(RepositoryInfoMap.class);

        scheduler.setProfileService(profileService);
        scheduler.setConnectorService(connectorService);
        scheduler.setCanonicalImportService(canonicalImportService);
        scheduler.setRepositoryInfoMap(repositoryInfoMap);

        when(repositoryInfoMap.keys()).thenReturn(Set.of(REPO));
    }

    private ImportProfileDefinition delegatedSchedulerProfile() {
        ImportProfileDefinition p = new ImportProfileDefinition();
        p.setProfileId(DELEGATED_PROFILE);
        p.setRepositoryId(REPO);
        p.setEnabled(true);
        p.setSchedulerEnabled(true);     // simulate hand-edited / corrupt record
        p.setDelegated(true);             // ← the bypass condition we defend against
        p.setDefaultConnectorId("c");
        p.setAllowedConnectorIds(List.of("c"));
        return p;
    }

    private ImportProfileDefinition adminSchedulerProfile() {
        ImportProfileDefinition p = new ImportProfileDefinition();
        p.setProfileId(ADMIN_PROFILE);
        p.setRepositoryId(REPO);
        p.setEnabled(true);
        p.setSchedulerEnabled(true);
        p.setDelegated(false);
        p.setDefaultConnectorId("c-admin");
        p.setAllowedConnectorIds(List.of("c-admin"));
        return p;
    }

    @Test
    void delegatedProfile_isSkipped_connectorLookupNeverHappens() {
        when(profileService.listByRepository(REPO)).thenReturn(List.of(delegatedSchedulerProfile()));

        scheduler.pollScheduledProfiles();

        // The first defence is "skip before resolveConnectorForProfile" —
        // verify connector lookup was never attempted, which means
        // executeFetch couldn't possibly have run either.
        verify(connectorService, never()).get(any());
        verify(canonicalImportService, never()).execute(any(), any());
    }

    @Test
    void delegatedSkip_warnsOnce_evenAcrossMultiplePolls() {
        // Same broken record returned on every poll. Without the
        // warnedDelegatedSchedulerProfiles set, every poll cycle would
        // emit a fresh WARN — polluting the log indefinitely.
        when(profileService.listByRepository(REPO)).thenReturn(List.of(delegatedSchedulerProfile()));

        // Use a logback appender to count WARN lines for the scheduler class.
        ch.qos.logback.classic.Logger lc =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(IngestSchedulerService.class);
        @SuppressWarnings("unchecked")
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        lc.addAppender(appender);
        try {
            for (int i = 0; i < 5; i++) {
                scheduler.pollScheduledProfiles();
            }
            long warnCount = appender.list.stream()
                    .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .filter(e -> e.getFormattedMessage().contains("Skipping delegated profile"))
                    .filter(e -> e.getFormattedMessage().contains(DELEGATED_PROFILE))
                    .count();
            assertTrue(warnCount == 1,
                    "expected exactly one WARN per profile across multiple polls, got " + warnCount
                            + " messages: " + appender.list);
        } finally {
            lc.detachAppender(appender);
        }
    }

    @Test
    void adminProfileWithSchedulerEnabled_isNotSkipped_progressesToConnectorLookup() {
        when(profileService.listByRepository(REPO)).thenReturn(List.of(adminSchedulerProfile()));
        // resolveConnectorForProfile would try to fetch the connector. Returning null
        // shortcircuits before executeFetch but proves the skip path didn't fire.
        lenient().when(connectorService.get(any())).thenReturn(null);

        scheduler.pollScheduledProfiles();

        // Admin profile passes the delegated check and reaches resolveConnectorForProfile,
        // which consults connectorService.
        verify(connectorService, times(1)).get(eq("c-admin"));
    }

    @Test
    void mixedList_skipsOnlyDelegated_continuesToProcessOthers() {
        when(profileService.listByRepository(REPO)).thenReturn(List.of(
                delegatedSchedulerProfile(),
                adminSchedulerProfile()));
        lenient().when(connectorService.get(any())).thenReturn(null);

        scheduler.pollScheduledProfiles();

        // delegated → no connector lookup; admin → exactly one
        verify(connectorService, never()).get(eq("c"));
        verify(connectorService, times(1)).get(eq("c-admin"));
    }

    @Test
    void disabledProfile_isSkippedBeforeDelegationCheck_noWarnEmitted() {
        // Pre-condition for the delegation gate: profile must be enabled and
        // schedulerEnabled=true. A disabled profile should be skipped at the
        // earlier filter, NOT generate a delegation WARN.
        ImportProfileDefinition disabled = delegatedSchedulerProfile();
        disabled.setEnabled(false);
        when(profileService.listByRepository(REPO)).thenReturn(List.of(disabled));

        ch.qos.logback.classic.Logger lc =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(IngestSchedulerService.class);
        @SuppressWarnings("unchecked")
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        lc.addAppender(appender);
        try {
            scheduler.pollScheduledProfiles();
            boolean warned = appender.list.stream()
                    .anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
                            && e.getFormattedMessage().contains("Skipping delegated profile"));
            assertFalse(warned, "disabled profile should not trigger the delegation WARN");
        } finally {
            lc.detachAppender(appender);
        }
        verify(connectorService, never()).get(any());
    }
}
