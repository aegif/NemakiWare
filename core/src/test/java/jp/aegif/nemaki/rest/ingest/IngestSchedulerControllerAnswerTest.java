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
                "import profile p1 has more than one owned definition row in repository bedroom"));
    }

    @Test
    @DisplayName("an absence the read ESTABLISHED is a 404, and a genuinely bad setting is "
            + "still a 400 — the over-throw control")
    void absenceIsA404AndABadSettingIsStillA400() {
        assertEquals(HttpStatus.NOT_FOUND, startIdleAnswering("Profile not found: p1"),
                "an absence the index-free read established was answered as a bad request");
        assertEquals(HttpStatus.BAD_REQUEST, startIdleAnswering("Import profile is disabled: p1"),
                "a genuinely wrong setting stopped being a 400 — the split went too far");
        assertEquals(HttpStatus.BAD_REQUEST,
                startIdleAnswering("Profile is not an IMAP profile: p1"));
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
}
