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
    void malformedCutoff_failsSafe_returnsAllProfilesWithoutFilter() {
        // UI bug ships garbage → filter must drop silently rather than
        // 400 (preserves admin tab usability while a malformed value
        // gets WARN-logged for investigation).
        when(profileService.list()).thenReturn(List.of(
                profile("p-active", null),
                profile("p-recent", Instant.now().toString())
        ));

        ResponseEntity<List<ImportProfileDefinition>> resp = controller.list(null, "not-a-timestamp");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(2, resp.getBody().size(),
                "malformed cutoff must pass through, not 400");
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
