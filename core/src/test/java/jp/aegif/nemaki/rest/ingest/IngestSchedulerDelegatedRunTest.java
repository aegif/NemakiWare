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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

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
    private jp.aegif.nemaki.audit.AuditLogger auditLogger;

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
        auditLogger = mock(jp.aegif.nemaki.audit.AuditLogger.class);

        scheduler.setProfileService(profileService);
        scheduler.setConnectorService(connectorService);
        scheduler.setCanonicalImportService(canonicalImportService);
        scheduler.setRepositoryInfoMap(repositoryInfoMap);
        scheduler.setIngestAuthorizationService(authService);
        scheduler.setDelegatedCallContextFactory(ctxFactory);
        scheduler.setPropertyManager(properties);
        scheduler.setAuditLogger(auditLogger);

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
    void autoDisable_writesMarkerFields_whenInactiveCreatorStreakExceedsThreshold() {
        // V1 (RC5 ext): the auto-disable path must persist
        // lastAutoDisabledAt + lastAutoDisabledReason so the admin UI
        // can distinguish scheduler-disabled from manually-disabled.
        // threshold=2 (lower than default 3 to keep test fast) + opt-in ON.
        lenient().when(properties.readValue(
                eq("nemakiware.ingest.delegated.autoDisableInactiveOwners"))).thenReturn("true");
        lenient().when(properties.readValue(
                eq("nemakiware.ingest.delegated.inactiveOwnerFailureThreshold"))).thenReturn("2");

        ImportProfileDefinition p = delegatedProfile();
        when(profileService.listByRepository(REPO)).thenReturn(List.of(p));
        when(ctxFactory.buildOrNull(REPO, CREATOR)).thenReturn(null);

        // Tick 1: streak=1, below threshold → no auto-disable
        scheduler.pollScheduledProfiles();
        assertTrue(p.isEnabled(), "profile must remain enabled before threshold");
        org.junit.jupiter.api.Assertions.assertNull(p.getLastAutoDisabledAt());

        // Tick 2: streak=2, hits threshold → auto-disable + marker written
        scheduler.pollScheduledProfiles();
        assertFalse(p.isEnabled(), "profile must be auto-disabled at threshold");
        org.junit.jupiter.api.Assertions.assertNotNull(p.getLastAutoDisabledAt(),
                "lastAutoDisabledAt must be set");
        org.junit.jupiter.api.Assertions.assertNotNull(p.getLastAutoDisabledReason(),
                "lastAutoDisabledReason must be set");
        assertTrue(p.getLastAutoDisabledReason().contains("CREATOR_USER_INACTIVE"),
                "reason must reference the structured DenialReason");
        assertTrue(p.getLastAutoDisabledReason().contains(CREATOR),
                "reason must include the creator's user ID for operator review");
        // Persist call must have happened
        verify(profileService, atLeast(1)).update(p);
    }

    @Test
    void targetFolderDisappearsBetweenTicks_emitsTargetFolderUnresolvable_notConnectorNotDelegated() {
        // RC5.6 (R5): the audit denialReason for "resolveFolderId returned
        // null on the second call" must be TARGET_FOLDER_UNRESOLVABLE.
        // Before the fix, the second resolveFolderId(...) was inlined into
        // the connector check; a null result silently caused the check to
        // return false and the audit recorded CONNECTOR_NOT_DELEGATED,
        // misattributing folder-resolution races to connector denial.
        CallContext synth = mock(CallContext.class);
        when(synth.getUsername()).thenReturn(CREATOR);
        when(profileService.listByRepository(REPO))
                .thenReturn(List.of(delegatedProfile()));
        when(ctxFactory.buildOrNull(REPO, CREATOR)).thenReturn(synth);
        // First call (in prepareDelegatedTick step 5) returns FOLDER.
        // Second call (the new explicit resolve in pollScheduledProfiles)
        // returns null — folder deleted between ticks.
        when(authService.resolveFolderId(eq(REPO), eq(FOLDER), any()))
                .thenReturn(FOLDER, (String) null);
        when(authService.canManageProfileForFolderAsUser(eq(CREATOR), eq(REPO), eq(FOLDER)))
                .thenReturn(true);
        ConnectorDefinition delegatedConnector = new ConnectorDefinition();
        delegatedConnector.setConnectorId("c1");
        delegatedConnector.setEnabled(true);
        delegatedConnector.setDelegated(true);
        when(connectorService.get(eq("c1"))).thenReturn(delegatedConnector);

        scheduler.pollScheduledProfiles();

        // The connector delegation re-check must NOT run when the folder
        // can't be resolved — otherwise we'd be back to misattributing.
        verify(authService, never()).canUseConnectorForDelegatedProfileAsUser(
                anyString(), anyString(), any(), anyString());
        // canonicalImport must not have executed.
        verify(canonicalImportService, never()).execute(any(), any());

        // The audit emit must carry denialReason=TARGET_FOLDER_UNRESOLVABLE
        // in the details map (not CONNECTOR_NOT_DELEGATED).
        ArgumentCaptor<java.util.Map<String, ?>> detailsCap =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(auditLogger, atLeastOnce()).logOperation(
                eq(jp.aegif.nemaki.audit.AuditOperation.EXTERNAL_INGEST_FAILED),
                eq(REPO), eq(CREATOR), anyString(), anyBoolean(), anyString(),
                detailsCap.capture());

        boolean sawTargetFolderUnresolvable = detailsCap.getAllValues().stream()
                .anyMatch(d -> DenialReason.TARGET_FOLDER_UNRESOLVABLE.name()
                        .equals(d.get("denialReason")));
        boolean sawConnectorNotDelegated = detailsCap.getAllValues().stream()
                .anyMatch(d -> DenialReason.CONNECTOR_NOT_DELEGATED.name()
                        .equals(d.get("denialReason")));
        assertTrue(sawTargetFolderUnresolvable,
                "expected audit with denialReason=TARGET_FOLDER_UNRESOLVABLE, captured details: "
                        + detailsCap.getAllValues());
        assertFalse(sawConnectorNotDelegated,
                "must NOT emit CONNECTOR_NOT_DELEGATED when the root cause is folder resolution failure, captured details: "
                        + detailsCap.getAllValues());
    }

    @Test
    void targetFolderResolves_butConnectorNoLongerDelegated_stillEmitsConnectorNotDelegated() {
        // Companion to the R5 test above: when the folder DOES resolve on the
        // second call but the connector was revoked, the denial must still be
        // CONNECTOR_NOT_DELEGATED. This pins that the R5 fix didn't regress
        // the legitimate connector-denial path.
        CallContext synth = mock(CallContext.class);
        when(synth.getUsername()).thenReturn(CREATOR);
        when(profileService.listByRepository(REPO))
                .thenReturn(List.of(delegatedProfile()));
        when(ctxFactory.buildOrNull(REPO, CREATOR)).thenReturn(synth);
        when(authService.resolveFolderId(eq(REPO), eq(FOLDER), any()))
                .thenReturn(FOLDER);
        when(authService.canManageProfileForFolderAsUser(eq(CREATOR), eq(REPO), eq(FOLDER)))
                .thenReturn(true);
        ConnectorDefinition revokedConnector = new ConnectorDefinition();
        revokedConnector.setConnectorId("c1");
        revokedConnector.setEnabled(true);
        revokedConnector.setDelegated(true);
        when(connectorService.get(eq("c1"))).thenReturn(revokedConnector);
        // Connector check returns false → CONNECTOR_NOT_DELEGATED expected
        when(authService.canUseConnectorForDelegatedProfileAsUser(
                eq(CREATOR), eq(REPO), eq(revokedConnector), eq(FOLDER)))
                .thenReturn(false);

        scheduler.pollScheduledProfiles();

        verify(canonicalImportService, never()).execute(any(), any());

        ArgumentCaptor<java.util.Map<String, ?>> detailsCap =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(auditLogger, atLeastOnce()).logOperation(
                eq(jp.aegif.nemaki.audit.AuditOperation.EXTERNAL_INGEST_FAILED),
                eq(REPO), eq(CREATOR), anyString(), anyBoolean(), anyString(),
                detailsCap.capture());
        boolean sawConnectorNotDelegated = detailsCap.getAllValues().stream()
                .anyMatch(d -> DenialReason.CONNECTOR_NOT_DELEGATED.name()
                        .equals(d.get("denialReason")));
        assertTrue(sawConnectorNotDelegated,
                "expected CONNECTOR_NOT_DELEGATED when folder resolves but connector check fails, captured: "
                        + detailsCap.getAllValues());
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

    // ── authorizeDelegatedFetch: shared guard reused by the webhook path ──
    // Security audit follow-up: the webhook-triggered fetch path
    // (IngestWebhookController) previously ran delegated profiles under an
    // unscoped admin context with NO re-evaluation. These tests pin the
    // shared guard that closes that bypass.

    @Test
    void authorizeDelegatedFetch_adminProfile_allowedWithNullContext() {
        ImportProfileDefinition admin = delegatedProfile();
        admin.setDelegated(false);
        ConnectorDefinition c = new ConnectorDefinition();
        c.setConnectorId("c1");

        IngestSchedulerService.DelegatedAuthorization auth =
                scheduler.authorizeDelegatedFetch(admin, c);

        assertTrue(auth.isAllowed(), "admin profile must be allowed");
        assertEquals(null, auth.getCallContext(), "admin profile uses null (admin) context");
        // No delegation re-eval for admin profiles.
        verify(ctxFactory, never()).buildOrNull(any(), any());
    }

    @Test
    void authorizeDelegatedFetch_creatorInactive_denied() {
        ConnectorDefinition c = new ConnectorDefinition();
        c.setConnectorId("c1");
        when(ctxFactory.buildOrNull(REPO, CREATOR)).thenReturn(null); // inactive

        IngestSchedulerService.DelegatedAuthorization auth =
                scheduler.authorizeDelegatedFetch(delegatedProfile(), c);

        assertFalse(auth.isAllowed(), "inactive creator must be denied");
        verify(authService, never()).canUseConnectorForDelegatedProfileAsUser(
                anyString(), anyString(), any(), anyString());
    }

    @Test
    void authorizeDelegatedFetch_connectorRevoked_denied() {
        CallContext synth = mock(CallContext.class);
        lenient().when(synth.getUsername()).thenReturn(CREATOR);
        ConnectorDefinition c = new ConnectorDefinition();
        c.setConnectorId("c1");
        when(ctxFactory.buildOrNull(REPO, CREATOR)).thenReturn(synth);
        when(authService.resolveFolderId(eq(REPO), eq(FOLDER), any())).thenReturn(FOLDER);
        when(authService.canManageProfileForFolderAsUser(eq(CREATOR), eq(REPO), eq(FOLDER)))
                .thenReturn(true);
        // connector delegation revoked
        when(authService.canUseConnectorForDelegatedProfileAsUser(
                eq(CREATOR), eq(REPO), any(), eq(FOLDER))).thenReturn(false);

        IngestSchedulerService.DelegatedAuthorization auth =
                scheduler.authorizeDelegatedFetch(delegatedProfile(), c);

        assertFalse(auth.isAllowed(), "revoked connector delegation must be denied");
        assertEquals(DenialReason.CONNECTOR_NOT_DELEGATED, auth.getDenialReason());
    }

    @Test
    void authorizeDelegatedFetch_allValid_allowedWithSynthContext() {
        CallContext synth = mock(CallContext.class);
        lenient().when(synth.getUsername()).thenReturn(CREATOR);
        ConnectorDefinition c = new ConnectorDefinition();
        c.setConnectorId("c1");
        when(ctxFactory.buildOrNull(REPO, CREATOR)).thenReturn(synth);
        when(authService.resolveFolderId(eq(REPO), eq(FOLDER), any())).thenReturn(FOLDER);
        when(authService.canManageProfileForFolderAsUser(eq(CREATOR), eq(REPO), eq(FOLDER)))
                .thenReturn(true);
        when(authService.canUseConnectorForDelegatedProfileAsUser(
                eq(CREATOR), eq(REPO), any(), eq(FOLDER))).thenReturn(true);

        IngestSchedulerService.DelegatedAuthorization auth =
                scheduler.authorizeDelegatedFetch(delegatedProfile(), c);

        assertTrue(auth.isAllowed(), "fully-valid delegated fetch must be allowed");
        assertEquals(synth, auth.getCallContext(),
                "must return the synthesised creator context for executeFetch");
    }
}
