package jp.aegif.nemaki.rest.ingest;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.util.constant.CallContextKey;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RC5.3 W1: GET /v1/admin/import-profiles?autoDisabledSince=ISO-8601
 * — server-side filter for V6's "auto-disabled in last N days" UI.
 *
 * <p>Pins:
 * <ul>
 *   <li>Param absent → behaves exactly like before W1 (pass-through)</li>
 *   <li>Param present with valid ISO-8601 → only profiles with a
 *       `lastAutoDisabledAt` &gt;= cutoff returned</li>
 *   <li>Profiles without a marker excluded when the param is active</li>
 *   <li>Malformed cutoff → fail-safe: pass-through + WARN (not 400)</li>
 *   <li>Profile-side malformed marker → defensively excluded</li>
 *   <li>Empty string cutoff → pass-through (treat as absent)</li>
 * </ul>
 */
class ImportProfileSinceFilterTest {

    private ImportProfileDefinitionController controller;
    private ImportProfileDefinitionService profileService;
    private IngestAuthorizationService authService;
    private HttpServletRequest httpRequest;

    @BeforeEach
    void setUp() throws Exception {
        controller = new ImportProfileDefinitionController();
        profileService = mock(ImportProfileDefinitionService.class);
        authService = mock(IngestAuthorizationService.class);
        httpRequest = mock(HttpServletRequest.class);
        inject("importProfileDefinitionService", profileService);
        inject("ingestAuthorizationService", authService);
        inject("httpRequest", httpRequest);

        // Admin context so the W1 filter runs against the un-permission-filtered set
        CallContext admin = mock(CallContext.class);
        lenient().when(admin.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(httpRequest.getAttribute("CallContext")).thenReturn(admin);
        when(authService.isAdmin(admin)).thenReturn(true);
    }

    private void inject(String fieldName, Object value) throws Exception {
        Field f = ImportProfileDefinitionController.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(controller, value);
    }

    private ImportProfileDefinition profile(String id, String lastAutoDisabledAt) {
        ImportProfileDefinition p = new ImportProfileDefinition();
        p.setProfileId(id);
        p.setRepositoryId("bedroom");
        p.setEnabled(lastAutoDisabledAt == null);   // mirror real auto-disable state
        p.setLastAutoDisabledAt(lastAutoDisabledAt);
        return p;
    }

    @Test
    void noParam_returnsAllProfiles_unchanged() {
        // V5.2 backward-compat: behaviour with no autoDisabledSince must
        // be identical to before W1.
        when(profileService.list()).thenReturn(List.of(
                profile("p-active", null),
                profile("p-old", "2026-01-01T00:00:00Z"),
                profile("p-recent", Instant.now().toString())
        ));

        ResponseEntity<List<ImportProfileDefinition>> resp = controller.list(null, null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(3, resp.getBody().size(),
                "no-param call must return everything (backward compat)");
    }

    @Test
    void emptyStringParam_treatedAsAbsent_passThrough() {
        when(profileService.list()).thenReturn(List.of(
                profile("p-active", null),
                profile("p-recent", Instant.now().toString())
        ));

        ResponseEntity<List<ImportProfileDefinition>> resp = controller.list(null, "");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(2, resp.getBody().size(),
                "empty string must behave like null (pass-through)");
    }

    @Test
    void validCutoff_returnsOnlyMarkedProfilesAtOrAfterCutoff() {
        // cutoff = 7 days ago
        Instant now = Instant.now();
        Instant sevenDaysAgo = now.minusSeconds(7 * 24 * 60 * 60);
        Instant tenDaysAgo = now.minusSeconds(10 * 24 * 60 * 60);

        when(profileService.list()).thenReturn(List.of(
                profile("p-active", null),                            // no marker → excluded
                profile("p-recent", now.toString()),                  // within window → kept
                profile("p-just-old", tenDaysAgo.toString()),         // before cutoff → excluded
                profile("p-edge", sevenDaysAgo.toString())            // == cutoff → kept (>=)
        ));

        ResponseEntity<List<ImportProfileDefinition>> resp = controller.list(null, sevenDaysAgo.toString());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        List<String> ids = resp.getBody().stream()
                .map(ImportProfileDefinition::getProfileId).toList();
        assertEquals(2, ids.size(), "expected exactly 2 in-window profiles, got " + ids);
        assertTrue(ids.contains("p-recent"));
        assertTrue(ids.contains("p-edge"));
    }

    @Test
    void malformedCutoff_returns400_R4Strictness() {
        // R4 (RC5.4): malformed cutoff is now strictly rejected with
        // 400, replacing the RC5.3 fail-safe pass-through. The
        // closure review flagged the pass-through as risky because a
        // typo could silently return the full list and let an
        // operator misread "no recent shutdowns". The RC5.3 UI only
        // ever ships Date.toISOString(), so this strictness affects
        // only CLI / scripting callers with malformed input — for
        // them, an explicit 400 is more useful than a silent
        // pass-through.
        when(profileService.list()).thenReturn(List.of(
                profile("p-active", null),
                profile("p-recent", Instant.now().toString())
        ));

        ResponseEntity<List<ImportProfileDefinition>> resp = controller.list(null, "not-a-timestamp");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "malformed cutoff must be 400 (R4 strict, not RC5.3 pass-through)");
    }

    @Test
    void emptyStringCutoff_stillTreatedAsAbsent_passThrough_evenWithR4() {
        // R4 strictness applies only to non-empty malformed input.
        // Empty / null still pass through (treat as "no filter requested").
        when(profileService.list()).thenReturn(List.of(
                profile("p-recent", Instant.now().toString())
        ));

        ResponseEntity<List<ImportProfileDefinition>> respEmpty = controller.list(null, "");
        ResponseEntity<List<ImportProfileDefinition>> respNull = controller.list(null, null);

        assertEquals(HttpStatus.OK, respEmpty.getStatusCode(),
                "empty string remains pass-through (R4 only catches malformed non-empty)");
        assertEquals(HttpStatus.OK, respNull.getStatusCode(),
                "null remains pass-through");
    }

    @Test
    void profileWithMalformedMarker_isExcluded_defensively() {
        // A profile in the database with a corrupted lastAutoDisabledAt
        // must NOT be falsely classified as "recent" by the filter. The
        // alternative (include on ambiguity) would mis-surface stale
        // shutdowns as fresh during incident triage.
        when(profileService.list()).thenReturn(List.of(
                profile("p-bad-marker", "this-is-not-iso"),
                profile("p-recent", Instant.now().toString())
        ));

        ResponseEntity<List<ImportProfileDefinition>> resp =
                controller.list(null, Instant.now().minusSeconds(60).toString());

        assertEquals(1, resp.getBody().size());
        assertEquals("p-recent", resp.getBody().get(0).getProfileId());
    }

    @Test
    void cutoffInFuture_returnsZeroProfiles() {
        // No profile should match a "since tomorrow" filter — the
        // window has no past to look at. Confirms strict >= semantics.
        when(profileService.list()).thenReturn(List.of(
                profile("p-recent", Instant.now().toString())
        ));

        ResponseEntity<List<ImportProfileDefinition>> resp = controller.list(
                null, Instant.now().plusSeconds(60).toString());

        assertEquals(0, resp.getBody().size());
    }

    @Test
    void epochOverflowCutoff_returns400_C1_RC5_5() {
        // C1 (RC5.5): a cutoff that parses as a valid Instant but
        // overflows Long range on toEpochMilli() — for example
        // "+999999999-12-31T23:59:59Z" — used to leak HTTP 500
        // through the controller because applyAutoDisabledSinceFilter
        // only caught DateTimeParseException. External review caught
        // it as a contract gap with R4's "strict 400 on malformed".
        // RC5.5 closes the gap by also catching ArithmeticException.
        when(profileService.list()).thenReturn(List.of(
                profile("p-recent", Instant.now().toString())
        ));

        ResponseEntity<List<ImportProfileDefinition>> resp =
                controller.list(null, "+999999999-12-31T23:59:59Z");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "epoch-overflow cutoff must return 400, not 500 (C1 closes the leak)");
    }

    @Test
    void profileWithEpochOverflowMarker_isExcluded_listStillReturns200_C1_RC5_5() {
        // C1 (RC5.5): a single corrupted profile with an
        // overflow-prone lastAutoDisabledAt (parses as valid Instant
        // but ArithmeticException on toEpochMilli) used to 500 the
        // entire list response. Defensive exclude now covers
        // ArithmeticException the same way it covers
        // DateTimeParseException — the corrupted profile is silently
        // dropped, the rest of the list returns normally.
        when(profileService.list()).thenReturn(List.of(
                profile("p-overflow-marker", "+999999999-12-31T23:59:59Z"),
                profile("p-recent", Instant.now().toString())
        ));

        ResponseEntity<List<ImportProfileDefinition>> resp = controller.list(
                null, Instant.now().minusSeconds(60).toString());

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "overflow-prone marker on one profile must not 500 the list");
        assertEquals(1, resp.getBody().size(),
                "expected only the recent profile; overflow profile must be excluded");
        assertEquals("p-recent", resp.getBody().get(0).getProfileId());
    }

    @Test
    void repositoryIdAndSince_compose_correctly() {
        // When both params present, repository scope wins for the
        // initial fetch, then since applies to that subset.
        when(profileService.listByRepository(eq("bedroom"))).thenReturn(List.of(
                profile("p-bedroom-recent", Instant.now().toString()),
                profile("p-bedroom-old", "2020-01-01T00:00:00Z")
        ));

        ResponseEntity<List<ImportProfileDefinition>> resp = controller.list(
                "bedroom", Instant.now().minusSeconds(60).toString());

        assertEquals(1, resp.getBody().size());
        assertEquals("p-bedroom-recent", resp.getBody().get(0).getProfileId());
    }
}
