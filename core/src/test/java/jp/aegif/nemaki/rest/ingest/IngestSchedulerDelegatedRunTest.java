package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.util.PropertyManager;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RC5 (v2 §12.1): with the operator opt-in property ON, the scheduler
 * MUST run delegated profiles under the creator's synthesised CallContext
 * AND re-evaluate cmis:all per tick. With the property OFF (default),
 * RC4 behaviour holds (skip + WARN-once).
 *
 * <p>{@link IngestSchedulerDelegationSkipTest} pins the legacy default
 * path; this test pins the new opt-in path's gates.
 */
class IngestSchedulerDelegatedRunTest {

    private static final String REPO = "bedroom";
    private static final String PROFILE = "delg-1";
    private static final String CREATOR = "alice";
    private static final String FOLDER = "folder-abc";

    private IngestSchedulerService scheduler;
    private ImportProfileDefinitionService profileService;
    private ConnectorDefinitionService connectorService;
    private CanonicalImportService canonicalImportService;
    private RepositoryInfoMap repositoryInfoMap;
    private IngestAuthorizationService authService;
    private DelegatedCallContextFactory ctxFactory;
    private PropertyManager properties;

    @BeforeEach
    void setUp() {
        scheduler = new IngestSchedulerService();
        profileService = mock(ImportProfileDefinitionService.class);
        connectorService = mock(ConnectorDefinitionService.class);
        canonicalImportService = mock(CanonicalImportService.class);
        repositoryInfoMap = mock(RepositoryInfoMap.class);
        authService = mock(IngestAuthorizationService.class);
        ctxFactory = mock(DelegatedCallContextFactory.class);
        properties = mock(PropertyManager.class);

        scheduler.setProfileService(profileService);
        scheduler.setConnectorService(connectorService);
        scheduler.setCanonicalImportService(canonicalImportService);
        scheduler.setRepositoryInfoMap(repositoryInfoMap);
        scheduler.setIngestAuthorizationService(authService);
        scheduler.setDelegatedCallContextFactory(ctxFactory);
        scheduler.setPropertyManager(properties);

        when(repositoryInfoMap.keys()).thenReturn(Set.of(REPO));
        // Default: opt-in ON
        lenient().when(properties.readValue(
                eq("nemakiware.ingest.delegated.schedulerEnabled"))).thenReturn("true");
        lenient().when(properties.readValue(
                eq("nemakiware.ingest.delegated.autoDisableInactiveOwners"))).thenReturn("false");
    }

    private ImportProfileDefinition delegatedProfile() {
        ImportProfileDefinition p = new ImportProfileDefinition();
        p.setProfileId(PROFILE);
        p.setRepositoryId(REPO);
        p.setEnabled(true);
        p.setSchedulerEnabled(true);
        p.setDelegated(true);
        p.setCreatedByUserId(CREATOR);
        p.setTargetFolderId(FOLDER);
        p.setDefaultConnectorId("c1");
        p.setAllowedConnectorIds(List.of("c1"));
        return p;
    }

    @Test
    void optInOff_keepsLegacySkipBehaviour() {
        // Override the BeforeEach default
        when(properties.readValue(eq("nemakiware.ingest.delegated.schedulerEnabled")))
                .thenReturn("false");
        when(profileService.listByRepository(REPO))
                .thenReturn(List.of(delegatedProfile()));

        scheduler.pollScheduledProfiles();

        verify(connectorService, never()).get(any());
        verify(canonicalImportService, never()).execute(any(), any());
    }

    @Test
    void optInOn_butCreatorInactive_skipsAndDoesNotFetch() {
        when(profileService.listByRepository(REPO))
                .thenReturn(List.of(delegatedProfile()));
        // CREATOR_USER_INACTIVE path
        when(ctxFactory.buildOrNull(REPO, CREATOR)).thenReturn(null);

        scheduler.pollScheduledProfiles();

        verify(ctxFactory, times(1)).buildOrNull(REPO, CREATOR);
        verify(connectorService, never()).get(any());
        verify(canonicalImportService, never()).execute(any(), any());
    }

    @Test
    void optInOn_creatorLostCmisAll_skipsAndDoesNotFetch() {
        CallContext synth = mock(CallContext.class);
        when(profileService.listByRepository(REPO))
                .thenReturn(List.of(delegatedProfile()));
        when(ctxFactory.buildOrNull(REPO, CREATOR)).thenReturn(synth);
        when(authService.resolveFolderId(eq(REPO), eq(FOLDER), any())).thenReturn(FOLDER);
        // CREATOR_CMIS_ALL_LOST path
        when(authService.canManageProfileForFolderAsUser(eq(CREATOR), eq(REPO), eq(FOLDER)))
                .thenReturn(false);

        scheduler.pollScheduledProfiles();

        verify(authService, times(1))
                .canManageProfileForFolderAsUser(eq(CREATOR), eq(REPO), eq(FOLDER));
        verify(connectorService, never()).get(any());
        verify(canonicalImportService, never()).execute(any(), any());
    }

    @Test
    void optInOn_profileMissingCreator_skipsAsInactive() {
        // No createdByUserId — legacy admin-created delegated record
        ImportProfileDefinition orphan = delegatedProfile();
        orphan.setCreatedByUserId(null);
        when(profileService.listByRepository(REPO)).thenReturn(List.of(orphan));

        scheduler.pollScheduledProfiles();

        // Factory should never even be consulted — no identity to ask about
        verify(ctxFactory, never()).buildOrNull(anyString(), anyString());
        verify(connectorService, never()).get(any());
    }

    @Test
    void optInOn_creatorActiveAndAuthorised_progressesPastGate() {
        // All gates pass → reaches connector lookup. Returning null for the
        // connector shortcircuits before executeFetch but proves the gate
        // didn't fire.
        CallContext synth = mock(CallContext.class);
        when(profileService.listByRepository(REPO))
                .thenReturn(List.of(delegatedProfile()));
        when(ctxFactory.buildOrNull(REPO, CREATOR)).thenReturn(synth);
        when(authService.resolveFolderId(eq(REPO), eq(FOLDER), any())).thenReturn(FOLDER);
        when(authService.canManageProfileForFolderAsUser(eq(CREATOR), eq(REPO), eq(FOLDER)))
                .thenReturn(true);
        lenient().when(connectorService.get(eq("c1"))).thenReturn(null);

        scheduler.pollScheduledProfiles();

        verify(connectorService, times(1)).get(eq("c1"));
    }

    @Test
    void optInOff_warnsOncePerProfile_evenAcrossMultiplePolls() {
        when(properties.readValue(eq("nemakiware.ingest.delegated.schedulerEnabled")))
                .thenReturn("false");
        when(profileService.listByRepository(REPO))
                .thenReturn(List.of(delegatedProfile()));

        ch.qos.logback.classic.Logger lc =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                        IngestSchedulerService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        lc.addAppender(appender);
        try {
            for (int i = 0; i < 4; i++) scheduler.pollScheduledProfiles();
            long warnCount = appender.list.stream()
                    .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .filter(e -> e.getFormattedMessage().contains("Skipping delegated profile"))
                    .filter(e -> e.getFormattedMessage().contains(PROFILE))
                    .count();
            // Note: warnedDelegatedSchedulerProfiles is a static-ish in-instance
            // Set, so a fresh IngestSchedulerService instance per test gets a
            // fresh counter. We expect exactly one across 4 polls.
            assertTrue(warnCount == 1,
                    "expected exactly one WARN per profile (opt-in OFF), got "
                            + warnCount + ": " + appender.list);
        } finally {
            lc.detachAppender(appender);
        }
    }

    @Test
    void optInOn_inactiveCreator_doesNotEmitLegacyOptOutWarn() {
        // When the property is ON, the inactive-creator branch should NOT
        // also fire the "scheduler property=false" WARN. We're using the
        // new opt-in path now.
        when(profileService.listByRepository(REPO))
                .thenReturn(List.of(delegatedProfile()));
        when(ctxFactory.buildOrNull(REPO, CREATOR)).thenReturn(null);

        ch.qos.logback.classic.Logger lc =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                        IngestSchedulerService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        lc.addAppender(appender);
        try {
            scheduler.pollScheduledProfiles();
            boolean optOutWarn = appender.list.stream()
                    .anyMatch(e -> e.getFormattedMessage()
                            .contains("nemakiware.ingest.delegated.schedulerEnabled=false"));
            assertFalse(optOutWarn,
                    "opt-in ON path must not emit the legacy property=false WARN");
        } finally {
            lc.detachAppender(appender);
        }
        verify(ctxFactory, atLeast(1)).buildOrNull(REPO, CREATOR);
    }
}
