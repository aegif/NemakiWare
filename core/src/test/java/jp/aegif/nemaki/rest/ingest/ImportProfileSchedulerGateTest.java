package jp.aegif.nemaki.rest.ingest;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.audit.AuditLogger;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.CallContextKey;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RC5 (v2 §12.1): the {@code SCHEDULER_REQUIRES_ADMIN} gate on the
 * profile controller is now property-controlled. Default (property unset
 * / false) preserves RC4 behaviour — non-admin scheduled profiles are
 * refused outright. With {@code nemakiware.ingest.delegated.schedulerEnabled=true}
 * the gate lets {@code schedulerEnabled=true} through; the runtime
 * scheduler is then responsible for the per-tick ACL re-eval.
 */
class ImportProfileSchedulerGateTest {

    private static final String REPO = "bedroom";
    private static final String FOLDER = "F-1";
    private static final String CONN = "c-1";
    private static final String USER = "alice";

    private ImportProfileDefinitionController controller;
    private ImportProfileDefinitionService profileService;
    private ConnectorDefinitionService connectorService;
    private IngestAuthorizationService authService;
    private AuditLogger auditLogger;
    private HttpServletRequest httpRequest;
    private PropertyManager properties;

    @BeforeEach
    void setUp() throws Exception {
        controller = new ImportProfileDefinitionController();
        profileService = mock(ImportProfileDefinitionService.class);
        connectorService = mock(ConnectorDefinitionService.class);
        authService = mock(IngestAuthorizationService.class);
        auditLogger = mock(AuditLogger.class);
        httpRequest = mock(HttpServletRequest.class);
        properties = mock(PropertyManager.class);

        inject("importProfileDefinitionService", profileService);
        inject("connectorDefinitionService", connectorService);
        inject("ingestAuthorizationService", authService);
        inject("auditLogger", auditLogger);
        inject("httpRequest", httpRequest);
        inject("propertyManager", properties);
    }

    private void inject(String fieldName, Object value) throws Exception {
        Field f = ImportProfileDefinitionController.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(controller, value);
    }

    private CallContext nonAdminCtx() {
        CallContext ctx = mock(CallContext.class);
        when(ctx.getUsername()).thenReturn(USER);
        // Cross-repository confinement (3.2.1): the controller now requires the
        // operation's repository to match the authenticated one.
        lenient().when(ctx.getRepositoryId()).thenReturn(REPO);
        lenient().when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.FALSE);
        when(httpRequest.getAttribute("CallContext")).thenReturn(ctx);
        when(authService.isAdmin(ctx)).thenReturn(false);
        // Folder + connector pass-through
        lenient().when(authService.resolveFolderId(eq(REPO), any(), any())).thenReturn(FOLDER);
        lenient().when(authService.canManageProfileForFolder(any(), eq(REPO), eq(FOLDER)))
                .thenReturn(true);
        lenient().when(authService.canUseConnectorForDelegatedProfile(
                any(), eq(REPO), any(), eq(FOLDER))).thenReturn(true);
        ConnectorDefinition conn = new ConnectorDefinition();
        conn.setConnectorId(CONN);
        conn.setDelegated(true);
        conn.setAllowedFolderIds(List.of(FOLDER));
        conn.setAllowedPrincipalIds(List.of(USER));
        lenient().when(connectorService.get(CONN)).thenReturn(conn);
        return ctx;
    }

    private ImportProfileDefinition scheduledDelegatedDraft() {
        ImportProfileDefinition def = new ImportProfileDefinition();
        def.setRepositoryId(REPO);
        def.setTargetFolderId(FOLDER);
        def.setDefaultConnectorId(CONN);
        def.setAllowedConnectorIds(List.of(CONN));
        def.setSchedulerEnabled(true);   // ← what we want gated
        def.setDelegated(true);
        return def;
    }

    @Test
    void propertyOff_refusesNonAdminScheduledCreate_withSchedulerRequiresAdmin() {
        nonAdminCtx();
        when(properties.readValue("nemakiware.ingest.delegated.schedulerEnabled"))
                .thenReturn(null);  // simulates property absent → default false

        ResponseEntity<Map<String, Object>> resp = controller.create(scheduledDelegatedDraft());

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(DenialReason.SCHEDULER_REQUIRES_ADMIN.name(),
                resp.getBody().get("denialReason"));
    }

    @Test
    void propertyExplicitlyFalse_alsoRefuses() {
        nonAdminCtx();
        when(properties.readValue("nemakiware.ingest.delegated.schedulerEnabled"))
                .thenReturn("false");

        ResponseEntity<Map<String, Object>> resp = controller.create(scheduledDelegatedDraft());

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertEquals(DenialReason.SCHEDULER_REQUIRES_ADMIN.name(),
                resp.getBody().get("denialReason"));
    }

    @Test
    void propertyOn_acceptsNonAdminScheduledCreate_andPreservesSchedulerEnabledFlag() {
        nonAdminCtx();
        when(properties.readValue("nemakiware.ingest.delegated.schedulerEnabled"))
                .thenReturn("true");

        ImportProfileDefinition draft = scheduledDelegatedDraft();
        when(profileService.create(any())).thenAnswer(inv -> {
            ImportProfileDefinition saved = inv.getArgument(0);
            saved.setProfileId("new-id");
            return saved;
        });

        ResponseEntity<Map<String, Object>> resp = controller.create(draft);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertEquals("new-id", resp.getBody().get("profileId"));
        // The whole point of the property: schedulerEnabled is preserved
        // rather than coerced to false.
        assertTrue(draft.isSchedulerEnabled(),
                "with property=true the non-admin's schedulerEnabled choice must be preserved");
        // Delegation flags still stamped
        assertTrue(draft.isDelegated());
        assertEquals(USER, draft.getCreatedByUserId());
        // defaultProfile is still admin-only — unrelated gate
        assertFalse(draft.isDefaultProfile());
    }

    @Test
    void propertyOn_butSchedulerEnabledFalse_isStillAccepted() {
        nonAdminCtx();
        when(properties.readValue("nemakiware.ingest.delegated.schedulerEnabled"))
                .thenReturn("true");

        ImportProfileDefinition draft = scheduledDelegatedDraft();
        draft.setSchedulerEnabled(false);
        when(profileService.create(any())).thenAnswer(inv -> {
            ImportProfileDefinition saved = inv.getArgument(0);
            saved.setProfileId("new-id");
            return saved;
        });

        ResponseEntity<Map<String, Object>> resp = controller.create(draft);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertFalse(draft.isSchedulerEnabled());
    }

    @Test
    void propertyOn_doesNotBypassDefaultProfileGate() {
        // defaultProfile=true is a DIFFERENT non-admin restriction, with its
        // own DenialReason. The new opt-in must not accidentally relax it.
        nonAdminCtx();
        when(properties.readValue("nemakiware.ingest.delegated.schedulerEnabled"))
                .thenReturn("true");

        ImportProfileDefinition draft = scheduledDelegatedDraft();
        draft.setDefaultProfile(true);

        ResponseEntity<Map<String, Object>> resp = controller.create(draft);

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertEquals(DenialReason.DEFAULT_PROFILE_REQUIRES_ADMIN.name(),
                resp.getBody().get("denialReason"));
    }

    // ── P1-1(e) §1.3 / AC15: scheduleConfiguredBy* is SERVER-owned ────

    @Test
    void update_stampsTheOperator_onScheduleRelevantDiff_andDiscardsClientStamp() {
        // AC15 had no test at all (adversarial review, finding 4): deleting the whole
        // scheduleChanged block left the suite green. The discriminating shape is a forged
        // client stamp riding a schedule-relevant PUT — the server must overwrite BOTH fields
        // with its own answer.
        CallContext admin = mock(CallContext.class);
        when(admin.getUsername()).thenReturn("admin");
        lenient().when(admin.getRepositoryId()).thenReturn(REPO);
        lenient().when(admin.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(httpRequest.getAttribute("CallContext")).thenReturn(admin);
        when(authService.isAdmin(admin)).thenReturn(true);

        ImportProfileDefinition existing = scheduledDelegatedDraft();
        existing.setProfileId("p-stamp");
        existing.setEnabled(false);
        existing.setScheduleConfiguredByUserId("previous-operator");
        existing.setScheduleConfiguredAtMs(1L);
        when(profileService.get("p-stamp")).thenReturn(existing);

        ImportProfileDefinition update = scheduledDelegatedDraft();
        update.setProfileId("p-stamp");
        update.setEnabled(true);                             // schedule-relevant diff
        update.setScheduleConfiguredByUserId("mallory");     // forged client stamp
        update.setScheduleConfiguredAtMs(999L);

        controller.update("p-stamp", update);

        org.junit.jupiter.api.Assertions.assertEquals("admin",
                update.getScheduleConfiguredByUserId(),
                "a schedule-relevant PUT must stamp the AUTHENTICATED operator — the "
                        + "client-supplied name survived");
        org.junit.jupiter.api.Assertions.assertNotEquals(Long.valueOf(999L),
                update.getScheduleConfiguredAtMs(),
                "the client-supplied timestamp survived");
        verify(profileService).update(update);
    }

    @Test
    void update_stampsTheOperator_onTargetFolderChange() {
        // The folder is where every autonomous run LANDS. The first handshake diffed only
        // enabled/scheduler/delegated/connector/params, so repointing the target folder kept
        // the previous operator on record for a schedule someone else just redirected —
        // H3's defect in one more field (external review).
        CallContext admin = mock(CallContext.class);
        when(admin.getUsername()).thenReturn("admin");
        lenient().when(admin.getRepositoryId()).thenReturn(REPO);
        lenient().when(admin.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(httpRequest.getAttribute("CallContext")).thenReturn(admin);
        when(authService.isAdmin(admin)).thenReturn(true);

        ImportProfileDefinition existing = scheduledDelegatedDraft();
        existing.setProfileId("p-folder");
        existing.setScheduleConfiguredByUserId("previous-operator");
        existing.setScheduleConfiguredAtMs(1L);
        when(profileService.get("p-folder")).thenReturn(existing);

        ImportProfileDefinition update = scheduledDelegatedDraft();
        update.setProfileId("p-folder");
        update.setTargetFolderId("F-ELSEWHERE");

        controller.update("p-folder", update);

        org.junit.jupiter.api.Assertions.assertEquals("admin",
                update.getScheduleConfiguredByUserId(),
                "repointing the landing folder left the previous operator on record");
    }

    @Test
    void update_preservesThePriorStamp_whenNothingScheduleRelevantChanged() {
        CallContext admin = mock(CallContext.class);
        when(admin.getUsername()).thenReturn("admin");
        lenient().when(admin.getRepositoryId()).thenReturn(REPO);
        lenient().when(admin.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(httpRequest.getAttribute("CallContext")).thenReturn(admin);
        when(authService.isAdmin(admin)).thenReturn(true);

        ImportProfileDefinition existing = scheduledDelegatedDraft();
        existing.setProfileId("p-keep");
        existing.setScheduleConfiguredByUserId("previous-operator");
        existing.setScheduleConfiguredAtMs(1L);
        when(profileService.get("p-keep")).thenReturn(existing);

        ImportProfileDefinition update = scheduledDelegatedDraft();
        update.setProfileId("p-keep");
        update.setDefaultObjectTypeId("nemaki:custom");     // NOT schedule-relevant
        update.setScheduleConfiguredByUserId(null);          // omitted by the client

        controller.update("p-keep", update);

        org.junit.jupiter.api.Assertions.assertEquals("previous-operator",
                update.getScheduleConfiguredByUserId(),
                "an unrelated edit erased the schedule-configuration record");
        org.junit.jupiter.api.Assertions.assertEquals(Long.valueOf(1L),
                update.getScheduleConfiguredAtMs());
    }

    // ── V1 (RC5 ext): auto-disable marker handshake ───────────────────

    @Test
    void update_clearsAutoDisableMarker_whenAdminReEnablesDisabledProfile() {
        // Admin path. Existing profile was auto-disabled by the scheduler
        // (enabled=false + lastAutoDisabledAt set). Admin re-enables
        // (enabled=true). Marker must be cleared so a second auto-disable
        // doesn't carry stale audit context forward.
        CallContext admin = mock(CallContext.class);
        when(admin.getUsername()).thenReturn("admin");
        lenient().when(admin.getRepositoryId()).thenReturn(REPO);
        lenient().when(admin.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(httpRequest.getAttribute("CallContext")).thenReturn(admin);
        when(authService.isAdmin(admin)).thenReturn(true);

        ImportProfileDefinition existing = scheduledDelegatedDraft();
        existing.setProfileId("p-1");
        existing.setEnabled(false);
        existing.setLastAutoDisabledAt("2026-05-19T15:55:03.701Z");
        existing.setLastAutoDisabledReason("CREATOR_USER_INACTIVE: creator 'alice' inactive for 3 ticks");
        when(profileService.get("p-1")).thenReturn(existing);

        ImportProfileDefinition update = scheduledDelegatedDraft();
        update.setProfileId("p-1");
        update.setEnabled(true);
        // Marker fields not sent in payload — common case

        controller.update("p-1", update);

        // After admin re-enable, marker cleared on the payload object
        // before being persisted.
        org.junit.jupiter.api.Assertions.assertNull(update.getLastAutoDisabledAt(),
                "lastAutoDisabledAt must be cleared on re-enable");
        org.junit.jupiter.api.Assertions.assertNull(update.getLastAutoDisabledReason(),
                "lastAutoDisabledReason must be cleared on re-enable");
        verify(profileService).update(update);
    }

    @Test
    void update_preservesAutoDisableMarker_whenUnrelatedEditAndPayloadOmitsIt() {
        // A scripted PUT that flips an unrelated field (e.g. rateLimitRpm)
        // must NOT accidentally erase the audit-trail marker from a
        // previously auto-disabled profile.
        CallContext admin = mock(CallContext.class);
        when(admin.getUsername()).thenReturn("admin");
        lenient().when(admin.getRepositoryId()).thenReturn(REPO);
        lenient().when(admin.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(httpRequest.getAttribute("CallContext")).thenReturn(admin);
        when(authService.isAdmin(admin)).thenReturn(true);

        ImportProfileDefinition existing = scheduledDelegatedDraft();
        existing.setProfileId("p-2");
        existing.setEnabled(false);
        existing.setLastAutoDisabledAt("2026-05-19T15:55:03.701Z");
        existing.setLastAutoDisabledReason("CREATOR_USER_INACTIVE: ...");
        when(profileService.get("p-2")).thenReturn(existing);

        ImportProfileDefinition update = scheduledDelegatedDraft();
        update.setProfileId("p-2");
        update.setEnabled(false);   // still disabled — admin is just editing other fields
        update.setLastAutoDisabledAt(null);
        update.setLastAutoDisabledReason(null);

        controller.update("p-2", update);

        org.junit.jupiter.api.Assertions.assertEquals("2026-05-19T15:55:03.701Z",
                update.getLastAutoDisabledAt(),
                "marker must be preserved from existing record");
        org.junit.jupiter.api.Assertions.assertNotNull(update.getLastAutoDisabledReason());
        verify(profileService).update(update);
    }

    // ── F1 (RC5 ext): non-admin payload cannot spoof markers ─────────

    @Test
    void update_nonAdmin_payloadMarker_isStripped() {
        // A non-admin (folder owner) PUT must NOT be able to set marker
        // fields via the payload — those are scheduler-controlled. Even
        // if the payload includes them, the controller strips them
        // before the handshake runs.
        nonAdminCtx();
        when(properties.readValue("nemakiware.ingest.delegated.schedulerEnabled"))
                .thenReturn("true");

        ImportProfileDefinition existing = scheduledDelegatedDraft();
        existing.setProfileId("p-spoof");
        existing.setEnabled(true);
        existing.setLastAutoDisabledAt(null);    // never auto-disabled
        existing.setLastAutoDisabledReason(null);
        when(profileService.get("p-spoof")).thenReturn(existing);

        ImportProfileDefinition update = scheduledDelegatedDraft();
        update.setProfileId("p-spoof");
        update.setEnabled(true);
        // Spoof attempt: payload sets marker fields
        update.setLastAutoDisabledAt("2099-12-31T00:00:00Z");
        update.setLastAutoDisabledReason("FAKE: I disabled myself yesterday");

        controller.update("p-spoof", update);

        // Marker fields must be null — payload values discarded
        org.junit.jupiter.api.Assertions.assertNull(update.getLastAutoDisabledAt(),
                "non-admin payload marker must be stripped (F1)");
        org.junit.jupiter.api.Assertions.assertNull(update.getLastAutoDisabledReason(),
                "non-admin payload marker must be stripped (F1)");
    }

    @Test
    void create_nonAdmin_payloadMarker_isStripped() {
        // Same F1 invariant on create path. A non-admin creating a
        // delegated profile cannot ship it with a pre-set marker.
        nonAdminCtx();
        when(properties.readValue("nemakiware.ingest.delegated.schedulerEnabled"))
                .thenReturn("true");

        ImportProfileDefinition draft = scheduledDelegatedDraft();
        draft.setLastAutoDisabledAt("2099-01-01T00:00:00Z");
        draft.setLastAutoDisabledReason("FAKE marker from creation payload");
        when(profileService.create(any())).thenAnswer(inv -> {
            ImportProfileDefinition saved = inv.getArgument(0);
            saved.setProfileId("new-id");
            return saved;
        });

        controller.create(draft);

        org.junit.jupiter.api.Assertions.assertNull(draft.getLastAutoDisabledAt(),
                "non-admin create payload marker must be stripped (F1)");
        org.junit.jupiter.api.Assertions.assertNull(draft.getLastAutoDisabledReason(),
                "non-admin create payload marker must be stripped (F1)");
    }

    @Test
    void update_admin_canStillWriteMarker_forDataRepair() {
        // Admin path is trusted for the marker field — admins may need
        // to fix corrupted records manually. The strip only fires for
        // non-admin.
        CallContext admin = mock(CallContext.class);
        when(admin.getUsername()).thenReturn("admin");
        lenient().when(admin.getRepositoryId()).thenReturn(REPO);
        lenient().when(admin.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(httpRequest.getAttribute("CallContext")).thenReturn(admin);
        when(authService.isAdmin(admin)).thenReturn(true);

        ImportProfileDefinition existing = scheduledDelegatedDraft();
        existing.setProfileId("p-admin-repair");
        existing.setEnabled(false);                   // currently disabled, no marker
        existing.setLastAutoDisabledAt(null);
        when(profileService.get("p-admin-repair")).thenReturn(existing);

        ImportProfileDefinition update = scheduledDelegatedDraft();
        update.setProfileId("p-admin-repair");
        update.setEnabled(false);                     // stay disabled (not a re-enable)
        update.setLastAutoDisabledAt("2026-05-19T20:00:00Z");
        update.setLastAutoDisabledReason("Manually annotated by admin");

        controller.update("p-admin-repair", update);

        // Admin write of marker honoured
        org.junit.jupiter.api.Assertions.assertEquals("2026-05-19T20:00:00Z",
                update.getLastAutoDisabledAt());
        org.junit.jupiter.api.Assertions.assertEquals("Manually annotated by admin",
                update.getLastAutoDisabledReason());
    }
}
