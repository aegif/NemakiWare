package jp.aegif.nemaki.rest.ingest;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.util.constant.CallContextKey;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What this controller ANSWERS for a refusal, and what it says it did.
 *
 * <p>Every refusal here used to be a 400 — including the ones that end in "retry shortly",
 * the ones that report a standing twin pair, and the ones that report an established absence.
 * A review found the endpoint had been left out when the rest of the batch split those; the
 * next one found the split still binary. There was no test class for this controller at all,
 * so none of it was measured. The reset half is here for the same reason: the controller and
 * the manager disagreed about what a blank {@code scope} means.
 */
class IngestSchedulerControllerAnswerTest {

    private IngestSchedulerController controller;
    private IngestSchedulerService schedulerService;
    private HttpServletRequest httpRequest;

    @BeforeEach
    void setUp() throws Exception {
        controller = new IngestSchedulerController();
        schedulerService = mock(IngestSchedulerService.class);
        httpRequest = mock(HttpServletRequest.class);
        inject("schedulerService", schedulerService);
        inject("httpRequest", httpRequest);

        CallContext ctx = mock(CallContext.class);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(httpRequest.getAttribute("CallContext")).thenReturn(ctx);
    }

    private void inject(String fieldName, Object value) throws Exception {
        Field f = IngestSchedulerController.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(controller, value);
    }

    private HttpStatus startIdleAnswering(String refusal) {
        when(schedulerService.startIdle("p1")).thenReturn(refusal);
        return (HttpStatus) controller.startIdle("p1").getStatusCode();
    }

    @Test
    @DisplayName("a refusal that says the read could not answer is a 503, not a 400")
    void aReadThatCouldNotAnswerIsA503() {
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, startIdleAnswering(
                "connector c-1 exists but could not be read; retry shortly"),
                "a body that says 'retry shortly' came back inside a status that says the "
                        + "request is wrong");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, startIdleAnswering(
                "whether import profile p1 exists could not be established; IDLE not started: x"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, startIdleAnswering(
                "import profile p1 could not be looked up: no profile service is wired on this"
                        + " node; IDLE not started"),
                "an unwired node blamed the request");
    }

    @Test
    @DisplayName("a standing twin pair is a 409 here, as it is on every other entry point")
    void aStandingPairIsA409() {
        assertEquals(HttpStatus.CONFLICT, startIdleAnswering(
                "connector c-1 has more than one definition row"),
                "no retry makes a standing pair go away, and 400 says the request is wrong");
        assertEquals(HttpStatus.CONFLICT, startIdleAnswering(
                "import profile p1 has more than one owned definition row; resolve the pair"
                        + " before starting a capture that is keyed by profileId alone"));
        assertEquals(HttpStatus.CONFLICT, startIdleAnswering(
                "IDLE already running for profile: p1"),
                "a session that is already there is a standing conflict, not a bad request");
    }

    @Test
    @DisplayName("an absence the read ESTABLISHED is a 404, and a genuinely bad setting is "
            + "still a 400 — the over-throw control")
    void absenceIsA404AndABadSettingIsStillA400() {
        assertEquals(HttpStatus.NOT_FOUND, startIdleAnswering("Profile not found: p1"),
                "an absence the index-free read established was answered as a bad request");
        assertEquals(HttpStatus.BAD_REQUEST, startIdleAnswering("Import profile is disabled: p1"),
                "a genuinely wrong setting stopped being a 400 — the split went too far");
        // The product's own wording. An invented one measures a message that cannot occur;
        // a review found two of those in this class.
        assertEquals(HttpStatus.BAD_REQUEST, startIdleAnswering(
                "IDLE is only supported for IMAP connectors (system=box)"));
        assertEquals(HttpStatus.BAD_REQUEST,
                startIdleAnswering("No password for IMAP connector"));
    }

    @Test
    @DisplayName("a blank scope resets everything, so the answer must not name one scope")
    void aBlankScopeIsNotOneNamedScope() {
        // Spring binds `?scope=` to "", not null. The manager reads that as "reset
        // everything" (it tests isBlank) while this controller read it as "one named scope"
        // (it tested != null), so the request cleared every static checkpoint and the answer
        // named a single one — and the incomplete-reset warning could not be reached at all.
        when(schedulerService.resetCheckpoint(eq("p1"), any()))
                .thenReturn(new CheckpointManager.ResetSummary(3, false));

        ResponseEntity<Map<String, Object>> res = controller.resetCheckpoints("p1", "");

        Map<String, Object> body = res.getBody();
        assertNotNull(body);
        assertFalse(String.valueOf(body.get("message")).contains("p1/"),
                "a reset of everything answered as one named scope: " + body);
        assertNotNull(body.get("warning"),
                "the incomplete-reset warning was unreachable for a blank scope: " + body);
    }

    @Test
    @DisplayName("a reset that could not name the scoped keys warns; one that could does not "
            + "— the pair")
    void theResetWarningTracksWhetherTheRowWasRead() {
        when(schedulerService.resetCheckpoint(eq("p1"), any()))
                .thenReturn(new CheckpointManager.ResetSummary(5, true));
        Map<String, Object> complete = controller.resetCheckpoints("p1", null).getBody();
        assertNotNull(complete);
        assertNull(complete.get("warning"), "a complete reset carried the warning: " + complete);
        assertTrue(String.valueOf(complete.get("message")).contains("All checkpoints reset"),
                "a complete reset stopped saying so: " + complete);

        when(schedulerService.resetCheckpoint(eq("p1"), any()))
                .thenReturn(new CheckpointManager.ResetSummary(2, false));
        Map<String, Object> partial = controller.resetCheckpoints("p1", null).getBody();
        assertNotNull(partial);
        assertNotNull(partial.get("warning"), "an incomplete reset reported none: " + partial);
        assertFalse(String.valueOf(partial.get("message")).contains("All checkpoints reset"),
                "an incomplete reset still said 'All': " + partial);
    }

    @Test
    @DisplayName("a typed refusal from the listing is a 503, not Spring's 500")
    void aTypedRefusalIsA503() {
        ResponseEntity<Map<String, Object>> res = controller.definitionRowsCouldNotBeRead(
                new ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException(
                        "the listing could not be completed"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode(),
                "the typed 'retry' refusal reached the caller as 'our bug'");
        assertNotNull(res.getBody());
        assertEquals("the listing could not be completed", res.getBody().get("message"),
                "an admin endpoint dropped the reason");
    }

    @Test
    @DisplayName("the enumeration warning reaches the GET too")
    void theGetCarriesTheSameWarning() {
        when(schedulerService.enumerateCheckpoints("p1")).thenReturn(
                new CheckpointManager.Enumeration(Map.of("gmail", "x"), false));

        Map<String, Object> body = controller.getCheckpoints("p1").getBody();

        assertNotNull(body);
        assertNotNull(body.get("warning"),
                "a list that could not name the scoped keys looked complete: " + body);
        verify(schedulerService).enumerateCheckpoints("p1");
    }

    @Test
    @DisplayName("the STOP verb classifies the same way — it had been left on a fixed 400")
    void stopClassifiesLikeStart() {
        when(schedulerService.stopIdle("p1")).thenReturn(
                "the IMAP IDLE monitor is not wired on this node, so IDLE could not be"
                        + " stopped; retry shortly against a node that runs it");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                controller.stopIdle("p1").getStatusCode(),
                "the stop endpoint still answers a fixed 400 — the notes promise 開始・停止");

        when(schedulerService.stopIdle("p1"))
                .thenReturn("No IDLE session running for profile: p1");
        assertEquals(HttpStatus.BAD_REQUEST, controller.stopIdle("p1").getStatusCode(),
                "an ordinary 'nothing to stop' stopped being a 400");
    }

    @Test
    @DisplayName("an unwired node says so, in the words of the verb that was asked")
    void anUnwiredNodeSaysSoPerVerb() {
        // The service, not a mock of it: the message under measurement is the one the service
        // builds when no monitor is wired. It used to be "ImapIdleMonitor not available",
        // which carries no marker, so the endpoint answered 400 — the deployment blamed on
        // the request. A review found neither layer locked it, and that one wording was
        // reused for both verbs, so a stop answered "could not be started".
        IngestSchedulerService real = new IngestSchedulerService();

        String start = real.startIdle("p1");
        String stop = real.stopIdle("p1");

        assertTrue(start.contains("could not be started"), "the start's wording: " + start);
        assertTrue(stop.contains("could not be stopped"),
                "the stop answered in the start's words: " + stop);
        assertTrue(start.contains("not wired on this node") && start.contains("retry shortly"),
                "an unwired node did not say so: " + start);

        when(schedulerService.startIdle("p1")).thenReturn(start);
        when(schedulerService.stopIdle("p1")).thenReturn(stop);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                controller.startIdle("p1").getStatusCode(),
                "an unwired node answered 400 on the start");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                controller.stopIdle("p1").getStatusCode(),
                "an unwired node answered 400 on the stop");
    }

    @Test
    @DisplayName("an unwired scheduled listing refuses rather than answering 'nothing is "
            + "scheduled'")
    void anUnwiredScheduledListingRefuses() {
        // The poll's own comment says the empty list is GENUINELY empty because a read that
        // could not be answered throws — and one arm did not: with no repository map or no
        // profile service the method returned List.of(), so every scheduled capture was
        // skipped in silence and GET /status answered count 0. A review found the arm the
        // sentence does not cover.
        IngestSchedulerService real = new IngestSchedulerService();

        ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException refused = assertThrows(
                ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class,
                real::getScheduledProfiles,
                "an unwired node answered 'no profile is scheduled'");
        assertTrue(refused.getMessage().contains("not wired on this node"),
                "the refusal does not say what is wrong: " + refused.getMessage());
    }

    @Test
    @DisplayName("the delegated-wiring refusal is a 503, and an authorisation denial is a 403 "
            + "— neither is a malformed request")
    void wiringIsA503AndADenialIsA403() {
        // The 503 half of the previous round's IDLE fix had no lock at all: the message was
        // rewritten to carry the retry marker and nothing measured it, while the ledger
        // presented the item as measured. The 403 is the next review's: an authorisation
        // outcome was answered as a bad request.
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, startIdleAnswering(
                "delegated IMAP IDLE could not be authorised: the scheduler is not wired on"
                        + " this node; retry shortly against a node that runs it"),
                "an unwired scheduler was answered as a malformed request");
        assertEquals(HttpStatus.FORBIDDEN, startIdleAnswering(
                "Delegated authorization denied for profile p1 (CREATOR_USER_INACTIVE)"),
                "an authorisation denial was answered as a malformed request");
    }

    @Test
    @DisplayName("a profile that lost its row is a 404, and a hostile profileId cannot buy a "
            + "different status")
    void absenceAfterTheFactIsA404AndTheIdCannotSteerTheStatus() {
        assertEquals(HttpStatus.NOT_FOUND, startIdleAnswering(
                "import profile p1 no longer has a row in repository bedroom;"
                        + " IDLE not started"),
                "an absence established after registration was answered as a bad request");
        // Every message here embeds the caller's own profileId, so the caller can write the
        // markers this classifier reads. Anchoring the arms at the start was the first
        // answer and a review showed it was not enough, in BOTH directions. The id is now
        // taken out of the text before the settled-answer arms are tested, and the
        // could-not-ask arm is tested against the text with and without it.
        assertEquals(HttpStatus.NOT_FOUND, statusFor("foo retry shortly",
                "Profile not found: foo retry shortly"),
                "an id carrying a retry marker took a settled 404 away");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, statusFor(
                " no longer has a row in repository ",
                "import profile  no longer has a row in repository  could not be looked up:"
                        + " no profile service is wired on this node; IDLE not started"),
                "an id shaped like the 404 arm turned an unwired node into 'it is not there'");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, statusFor("could not be read",
                "connector c-1 exists but could not be read; retry shortly"),
                "an id that spells a marker took the 503 away");
    }

    /** The refusal a monitor would build for {@code profileId}, through the real endpoint. */
    private HttpStatus statusFor(String profileId, String refusal) {
        when(schedulerService.startIdle(profileId)).thenReturn(refusal);
        return (HttpStatus) controller.startIdle(profileId).getStatusCode();
    }

    @Test
    @DisplayName("an unwired node cannot list IDLE sessions either — the verbs beside it "
            + "already say so")
    void anUnwiredNodeCannotListSessions() {
        IngestSchedulerService real = new IngestSchedulerService();

        ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException refused = assertThrows(
                ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class,
                real::getIdleProfiles,
                "a node that cannot run IDLE answered 'no session is running'");
        assertTrue(refused.getMessage().contains("not wired on this node"),
                "the refusal does not say what is wrong: " + refused.getMessage());
    }

    @Test
    @DisplayName("GET /status keeps the part it established when the idle listing refuses")
    void theStatusEndpointDoesNotLoseWhatItAlreadyHas() {
        // Making the idle listing refuse turned this whole endpoint into a 503, discarding a
        // scheduled list that HAD been read. Over-throwing, which this batch counts the same
        // as a fail-open. A review found it in the round that introduced it.
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        profile.setRepositoryId("bedroom");
        when(schedulerService.scheduledProfilesWithUnreadable()).thenReturn(
                new ImportProfileDefinitionService.OwnedProfiles(
                        java.util.List.of(profile), java.util.List.of()));
        when(schedulerService.resolveConnectorFor(profile)).thenReturn(
                new IngestSchedulerService.ConnectorForProfile(
                        null, IngestSchedulerService.Unresolved.NOT_USABLE));
        when(schedulerService.getIdleProfiles()).thenThrow(
                new ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException(
                        "the IMAP IDLE monitor is not wired on this node"));

        // assertDoesNotThrow: the regression under measurement IS the exception leaving the
        // endpoint, and a lock that dies on it reads as "harness broken" to the control
        // runner rather than as a firing. The runner said exactly that.
        ResponseEntity<Map<String, Object>> res = assertDoesNotThrow(controller::getStatus,
                "the idle listing's refusal took the whole endpoint down with it");

        assertEquals(HttpStatus.OK, res.getStatusCode(),
                "a scheduled list that was read was thrown away with the part that was not");
        Map<String, Object> body = res.getBody();
        assertNotNull(body);
        assertEquals(1, body.get("count"), "the part that WAS established went missing");
        assertNull(body.get("idleProfiles"), "a listing that refused answered a list anyway");
        assertNotNull(body.get("idleProfilesUnavailable"),
                "the part that could not be answered said nothing: " + body);
    }

    private ImportProfileDefinition scheduledProfile() {
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        profile.setRepositoryId("bedroom");
        profile.setEnabled(true);
        profile.setSchedulerEnabled(true);
        profile.setDefaultConnectorId("c1");
        return profile;
    }

    @Test
    @DisplayName("the resolution says WHY, and three of the five reasons are not facts about "
            + "the connector")
    void theResolutionSaysWhichReasonItIs() {
        // One null answered all five, and five callers turned it into a statement: ready:false
        // on the dashboard, two 400s, an empty folder listing, and a silently skipped capture.
        IngestSchedulerService service = new IngestSchedulerService();
        assertEquals(IngestSchedulerService.Unresolved.NOT_WIRED,
                service.resolveConnectorFor(scheduledProfile()).why(),
                "an unwired node reported something about the connector");

        ConnectorDefinitionService connectors = mock(ConnectorDefinitionService.class);
        service.setConnectorService(connectors);

        // doThrow/doReturn, not when(...): re-stubbing a call that already throws would
        // invoke it during the stubbing and raise right there.
        org.mockito.Mockito.doThrow(
                new ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException("nope"))
                .when(connectors).get("c1");
        IngestSchedulerService.ConnectorForProfile notRead =
                assertDoesNotThrow(() -> service.resolveConnectorFor(scheduledProfile()),
                        "a refused read took the resolution down instead of being reported");
        assertEquals(IngestSchedulerService.Unresolved.NOT_READ, notRead.why());
        assertFalse(notRead.answered(), "a refused read was reported as an answer");

        org.mockito.Mockito.doReturn(null).when(connectors).get("c1");
        IngestSchedulerService.ConnectorForProfile hidden =
                service.resolveConnectorFor(scheduledProfile());
        assertEquals(IngestSchedulerService.Unresolved.ABSENT_OR_HIDDEN, hidden.why());
        assertFalse(hidden.answered(),
                "a null from a read that cannot tell absence from a hidden row was reported "
                        + "as an answer");

        ConnectorDefinition disabled = new ConnectorDefinition();
        disabled.setConnectorId("c1");
        disabled.setEnabled(false);
        org.mockito.Mockito.doReturn(disabled).when(connectors).get("c1");
        IngestSchedulerService.ConnectorForProfile unusable =
                service.resolveConnectorFor(scheduledProfile());
        assertEquals(IngestSchedulerService.Unresolved.NOT_USABLE, unusable.why());
        assertTrue(unusable.answered(),
                "a row that WAS read and is disabled stopped being an answer — the split went "
                        + "too far");
    }

    @Test
    @DisplayName("the trigger endpoint answers 503 / 404 / 400 by the reason, not 400 for all")
    void theTriggerEndpointSplitsByReason() {
        ImportProfileDefinition profile = scheduledProfile();
        when(schedulerService.scheduledProfilesWithUnreadable()).thenReturn(
                new ImportProfileDefinitionService.OwnedProfiles(
                        java.util.List.of(profile), java.util.List.of()));

        when(schedulerService.resolveConnectorFor(profile)).thenReturn(
                new IngestSchedulerService.ConnectorForProfile(
                        null, IngestSchedulerService.Unresolved.NOT_READ));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                controller.triggerIngest("p1").getStatusCode(),
                "a connector that could not be read was reported as a bad request");

        ConnectorDefinitionService connectors = mock(ConnectorDefinitionService.class);
        try {
            inject("connectorDefinitionService", connectors);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        when(schedulerService.resolveConnectorFor(profile)).thenReturn(
                new IngestSchedulerService.ConnectorForProfile(
                        null, IngestSchedulerService.Unresolved.ABSENT_OR_HIDDEN));
        when(connectors.existsIndexFree("c1")).thenReturn(true);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                controller.triggerIngest("p1").getStatusCode(),
                "a row the index cannot show was reported as absent");
        when(connectors.existsIndexFree("c1")).thenReturn(false);
        assertEquals(HttpStatus.NOT_FOUND,
                controller.triggerIngest("p1").getStatusCode(),
                "an absence the walk established was not reported as one");

        when(schedulerService.resolveConnectorFor(profile)).thenReturn(
                new IngestSchedulerService.ConnectorForProfile(
                        null, IngestSchedulerService.Unresolved.NOT_USABLE));
        assertEquals(HttpStatus.BAD_REQUEST,
                controller.triggerIngest("p1").getStatusCode(),
                "a connector that WAS read and is unusable stopped being a 400");
    }

    @Test
    @DisplayName("the dashboard says why a profile is not ready, and whether that is an answer")
    void theDashboardSaysWhyNotReady() {
        ImportProfileDefinition profile = scheduledProfile();
        when(schedulerService.scheduledProfilesWithUnreadable()).thenReturn(
                new ImportProfileDefinitionService.OwnedProfiles(
                        java.util.List.of(profile), java.util.List.of()));
        when(schedulerService.getIdleProfiles()).thenReturn(java.util.List.of());
        when(schedulerService.resolveConnectorFor(profile)).thenReturn(
                new IngestSchedulerService.ConnectorForProfile(
                        null, IngestSchedulerService.Unresolved.NOT_READ));

        Map<String, Object> body = controller.getStatus().getBody();

        assertNotNull(body);
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> entries =
                (java.util.List<Map<String, Object>>) body.get("scheduledProfiles");
        assertEquals(1, entries.size());
        assertEquals(Boolean.FALSE, entries.get(0).get("ready"));
        assertEquals("NOT_READ", entries.get(0).get("notReadyReason"),
                "'ready: false' alone reads as 'the connector is missing or disabled'");
        assertEquals(Boolean.FALSE, entries.get(0).get("notReadyIsAnAnswer"));
    }

    @Test
    @DisplayName("a profile that is NOT on a schedule keeps the reason its named default gave")
    void aNonSchedulerProfileKeepsTheReason() {
        // Both reasons were returned only behind isSchedulerEnabled(); every other profile
        // fell through to NO_CANDIDATE, which answered() calls a fact. The folder verbs serve
        // exactly the profiles that are not on a schedule, so the round before reached none
        // of them. Two reviewers found it independently.
        IngestSchedulerService service = new IngestSchedulerService();
        ConnectorDefinitionService connectors = mock(ConnectorDefinitionService.class);
        service.setConnectorService(connectors);
        ImportProfileDefinition manual = scheduledProfile();
        manual.setSchedulerEnabled(false);
        org.mockito.Mockito.doReturn(null).when(connectors).get("c1");

        IngestSchedulerService.ConnectorForProfile resolution =
                service.resolveConnectorFor(manual);

        assertEquals(IngestSchedulerService.Unresolved.ABSENT_OR_HIDDEN, resolution.why(),
                "a manual profile's unreadable connector was reported as 'no candidate'");
        assertFalse(resolution.answered(),
                "a row that was never read was reported as a fact about the connector");
    }

    @Test
    @DisplayName("an unwired walk service refuses instead of answering 'does not exist'")
    void anUnwiredWalkServiceDoesNotFabricateAbsence() {
        ImportProfileDefinition profile = scheduledProfile();
        when(schedulerService.getScheduledProfiles()).thenReturn(java.util.List.of(profile));
        when(schedulerService.scheduledProfilesWithUnreadable()).thenReturn(
                new ImportProfileDefinitionService.OwnedProfiles(
                        java.util.List.of(profile), java.util.List.of()));
        when(schedulerService.resolveConnectorFor(profile)).thenReturn(
                new IngestSchedulerService.ConnectorForProfile(
                        null, IngestSchedulerService.Unresolved.ABSENT_OR_HIDDEN));
        // connectorDefinitionService deliberately left unwired.

        ResponseEntity<Map<String, Object>> res = controller.triggerIngest("p1");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode(),
                "a node with nothing to ask answered 'connector does not exist'");
        // On the MESSAGE: without the guard the next line dereferences the null service, the
        // NullPointerException lands on the catch below it, and the caller still gets a 503 —
        // with a reason that names a failed walk instead of the wiring. The control measured
        // that and did not fire until this assertion was added. Second time this exact shape
        // has been caught, after the connector-service arm in ExternalIngestController.
        assertNotNull(res.getBody());
        assertTrue(String.valueOf(res.getBody().get("message")).contains("not wired on"),
                "an unwired node was reported as a failed walk: " + res.getBody());
    }

    @Test
    @DisplayName("a scheduled row the walk could not read is 503, not 'not found or not "
            + "scheduler-enabled'")
    void anUnreadableScheduledRowIsNotReportedAsAbsent() {
        // The listing behind this endpoint dropped the rows it could not read, because it was
        // written for the poll, which has no caller to answer. The endpoint then made two
        // statements about such a row, neither established.
        jp.aegif.nemaki.rest.ingest.ImportProfileDefinitionService.UninterpretableRow row =
                new jp.aegif.nemaki.rest.ingest.ImportProfileDefinitionService
                        .UninterpretableRow("doc-1", "p1", null, java.util.List.of(),
                        java.util.List.of(), false, "the row could not be read");
        when(schedulerService.scheduledProfilesWithUnreadable()).thenReturn(
                new ImportProfileDefinitionService.OwnedProfiles(
                        java.util.List.of(), java.util.List.of(row)));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                controller.triggerIngest("p1").getStatusCode(),
                "a row that could not be read was answered as absent or unscheduled");

        Map<String, Object> status = controller.getStatus().getBody();
        assertNotNull(status);
        assertNotNull(status.get("profilesUnreadable"),
                "the dashboard's count was short with nothing saying so: " + status);
    }

    @Test
    @DisplayName("a delegated denial carries the reason the audit recorded, not a fixed one")
    void aDelegatedDenialCarriesItsOwnReason() {
        // authorizeDelegatedFetch answered CREATOR_CMIS_ALL_LOST for all seven of the
        // preparation's denials — so an unwired node, and a creator lookup that FAILED (the
        // reason CREATOR_LOOKUP_FAILED exists to prevent exactly this substitution), were
        // both reported to an administrator as a revoked cmis:all. The IDLE endpoint's 403
        // and the webhook receiver's WARN both state it.
        IngestSchedulerService service = new IngestSchedulerService();
        ImportProfileDefinition delegated = scheduledProfile();
        delegated.setDelegated(true);
        delegated.setCreatedByUserId("alice");
        // delegatedCallContextFactory / ingestAuthorizationService deliberately unwired, and
        // the opt-in property defaults to false, so the FIRST arm answers.
        IngestSchedulerService.DelegatedAuthorization denial =
                service.authorizeDelegatedFetch(delegated, null);

        assertFalse(denial.isAllowed());
        assertEquals(DenialReason.DELEGATED_SCHEDULING_DISABLED, denial.getDenialReason(),
                "the denial reported a revoked cmis:all for a profile the operator never "
                        + "opted in for");
    }

    @Test
    @DisplayName("the endpoints' listing refuses when unwired, as the poll's does")
    void theEndpointListingRefusesWhenUnwired() {
        // Two methods carry this guard: the poll's list-only walk and the endpoints' walk
        // that also carries the unreadable rows. A control on one of them left the other
        // unmeasured — the lock drove the poll's method while the control sabotaged the
        // endpoints'. The runner said DID NOT FIRE, which is how the pair was found.
        IngestSchedulerService real = new IngestSchedulerService();

        ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException refused = assertThrows(
                ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class,
                real::scheduledProfilesWithUnreadable,
                "an unwired node answered 'no profile is scheduled' to the endpoints");
        assertTrue(refused.getMessage().contains("not wired on this node"),
                "the refusal does not say what is wrong: " + refused.getMessage());
    }

    @org.junit.jupiter.api.Test
    void aRowTheMapperRefusedIsAStandingConflict_notARetry() {
        // "could not be read as a profile" is a CORRUPT STORED ROW: standing, not transient.
        // It was matching the "could not be read" token that means "this node could not ask",
        // so the endpoint told the operator to retry something only they can repair. A review
        // found the standing case wearing the transient answer.
        assertEquals(HttpStatus.CONFLICT, startIdleAnswering(
                "import profile p1 could not be started: row ingest_profile:p1 could not be"
                        + " read as a profile (Unrecognized field \"foo\")"),
                "a corrupt stored row was answered as a retry");
    }

    @org.junit.jupiter.api.Test
    void aCheckpointReadThatCouldNotAnswerIs503_notOurBug() {
        // The round that gave CheckpointManager a refusing settings read added a throw to a
        // path this controller's handler javadoc had just finished EXCLUDING, and nothing
        // caught it: GlobalExceptionHandler is scoped to rest.controller and does not cover
        // this package, so the operator got a Spring 500 — "our bug" — for the one condition
        // this whole batch has been converting to 503. A review found it.
        when(schedulerService.enumerateCheckpoints("p1")).thenThrow(
                new jp.aegif.nemaki.rest.controller.IntegrationSettingsService
                        .SettingUnreadableException("the stored value of"
                                + " 'ingest.checkpoint.p1.gmail' could not be read: the"
                                + " configuration database did not answer; retry shortly"));

        jp.aegif.nemaki.rest.controller.IntegrationSettingsService.SettingUnreadableException
                escaped = assertThrows(
                        jp.aegif.nemaki.rest.controller.IntegrationSettingsService
                                .SettingUnreadableException.class,
                        () -> controller.getCheckpoints("p1"),
                        "the endpoint answered a checkpoint read that FAILED as 'this profile"
                                + " has never polled'");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                controller.definitionRowsCouldNotBeRead(escaped).getStatusCode(),
                "the handler does not answer a retry for a read that could not be made");
        // The two assertions above are about the HANDLER. The WIRING is a separate fact and
        // the control proved it: removing the type from @ExceptionHandler left both of them
        // green, because this test calls the handler method directly while Spring dispatches
        // by the annotation. So assert the annotation itself — that is what a reader of
        // "answers 503" is relying on.
        // Scanned rather than looked up by name, and with no exception path: an AssertionError
        // raised from a reflection failure is harness BREAKAGE, which the control runner scores
        // as a firing — this repo locks against that shape in HarnessBreakageIsNotAFiringTest,
        // and the first version of these lines tripped it.
        java.util.List<Class<?>> handled = new java.util.ArrayList<>();
        for (java.lang.reflect.Method m : IngestSchedulerController.class.getDeclaredMethods()) {
            org.springframework.web.bind.annotation.ExceptionHandler a =
                    m.getAnnotation(org.springframework.web.bind.annotation.ExceptionHandler.class);
            if (a != null) handled.addAll(java.util.Arrays.asList(a.value()));
        }
        assertTrue(handled.contains(jp.aegif.nemaki.rest.controller.IntegrationSettingsService
                        .SettingUnreadableException.class),
                "Spring dispatches by @ExceptionHandler, and the settings refusal is not in any"
                        + " of this controller's — so the endpoint answers a Spring 500, whatever"
                        + " the handler body would have said: " + handled);
    }

    @org.junit.jupiter.api.Test
    void theIdleStatusReportsMessagesThatWereNeitherCapturedNorRecorded() {
        // The decision to keep an IDLE session running instead of stopping it when a miss
        // cannot be dead-lettered rests on the miss being VISIBLE. It was not: the counter had
        // no reader anywhere, while its javadoc said it was surfaced here. Two reviewers found
        // the claim in the same round.
        when(schedulerService.getIdleProfiles()).thenReturn(java.util.List.of("p1"));
        when(schedulerService.idleUndurableMisses()).thenReturn(java.util.Map.of("p1", 3));

        Map<String, Object> body = controller.getIdleStatus().getBody();

        assertEquals(java.util.Map.of("p1", 3), body.get("undurableMisses"),
                "a session that missed messages it could not record looked healthy: " + body);
        assertTrue(String.valueOf(body.get("undurableMissNote")).contains("re-fetch"),
                "the answer does not say how to recover: " + body);
    }
}