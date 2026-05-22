package jp.aegif.nemaki.rest.ingest;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.util.constant.CallContextKey;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RC5.3 W2: POST /v1/admin/connectors/by-principal/{id}/simulate-remove.
 *
 * <p>Pins:
 * <ul>
 *   <li>Admin gate (non-admin → 403)</li>
 *   <li>Required body fields (repositoryId, removePrincipalIds)</li>
 *   <li>Empty removePrincipalIds → 400</li>
 *   <li>Sole-route detection: connectors matched only via the removed
 *       principals → `lost`, others → `kept`</li>
 *   <li>Cascade: removing multiple principals together makes a
 *       connector lost that would survive each removal individually</li>
 *   <li>Response includes the same expanded principal info as V3's
 *       listByPrincipal so both endpoints align</li>
 *   <li>GROUP queried principal skips expand even when expand=true
 *       (matches V3 / V8 semantics)</li>
 * </ul>
 */
class ConnectorSimulateRemoveTest {

    private static final String REPO = "bedroom";
    private static final String USER = "alice";
    private static final String GROUP_A = "engineers";
    private static final String GROUP_B = "sf-office";

    private ConnectorDefinitionController controller;
    private ConnectorDefinitionService connectorService;
    private IngestAuthorizationService authService;
    private HttpServletRequest httpRequest;
    private jp.aegif.nemaki.businesslogic.PrincipalService principalService;

    @BeforeEach
    void setUp() throws Exception {
        controller = new ConnectorDefinitionController();
        connectorService = mock(ConnectorDefinitionService.class);
        authService = mock(IngestAuthorizationService.class);
        httpRequest = mock(HttpServletRequest.class);
        principalService = mock(jp.aegif.nemaki.businesslogic.PrincipalService.class);

        inject("connectorDefinitionService", connectorService);
        inject("ingestAuthorizationService", authService);
        inject("httpRequest", httpRequest);
        inject("principalService", principalService);
    }

    private void inject(String fieldName, Object value) throws Exception {
        Field f = ConnectorDefinitionController.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(controller, value);
    }

    private void asAdmin() {
        CallContext ctx = mock(CallContext.class);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        lenient().when(ctx.getUsername()).thenReturn("admin");
        when(httpRequest.getAttribute("CallContext")).thenReturn(ctx);
    }

    private void asNonAdmin() {
        CallContext ctx = mock(CallContext.class);
        lenient().when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.FALSE);
        when(httpRequest.getAttribute("CallContext")).thenReturn(ctx);
    }

    private ConnectorDefinition conn(String id, List<String> allowed) {
        ConnectorDefinition c = new ConnectorDefinition();
        c.setConnectorId(id);
        c.setDisplayName(id + "-display");
        c.setSourceArchetype(SourceArchetype.FILE_SHARE);
        c.setSourceSystem("box");
        c.setAdapterKind("native");
        c.setDelegated(true);
        c.setEnabled(true);
        c.setAllowedPrincipalIds(allowed);
        return c;
    }

    private Map<String, Object> body(String repo, boolean expand, Object removePrincipalIds) {
        return Map.of(
                "repositoryId", repo,
                "expand", expand,
                "removePrincipalIds", removePrincipalIds
        );
    }

    @Test
    void nonAdmin_returns403() {
        asNonAdmin();
        ResponseEntity<?> resp = controller.simulateRemove(USER, body(REPO, true, List.of(GROUP_A)));
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    void missingRepositoryId_returns400() {
        asAdmin();
        ResponseEntity<?> resp = controller.simulateRemove(USER,
                Map.of("expand", true, "removePrincipalIds", List.of(GROUP_A)));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void missingRemovePrincipalIds_returns400() {
        asAdmin();
        ResponseEntity<?> resp = controller.simulateRemove(USER,
                Map.of("repositoryId", REPO, "expand", true));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void emptyRemovePrincipalIds_returns400() {
        asAdmin();
        ResponseEntity<?> resp = controller.simulateRemove(USER,
                body(REPO, true, List.of()));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void blankPrincipalId_returns400() {
        asAdmin();
        ResponseEntity<?> resp = controller.simulateRemove("   ", body(REPO, true, List.of(GROUP_A)));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void soleRouteDetection_partitionsLostFromKept() {
        // user in group A and group B. Two connectors:
        // - c-only-A: matched only via group A → lost when A removed
        // - c-both: matched via A AND B (allowedPrincipalIds=[A,B]) →
        //   kept when only A is removed (B still grants access)
        asAdmin();
        jp.aegif.nemaki.model.User u = mock(jp.aegif.nemaki.model.User.class);
        when(principalService.getUserById(REPO, USER)).thenReturn(u);
        when(authService.expandPrincipals(REPO, USER)).thenReturn(Set.of(USER, GROUP_A, GROUP_B));
        when(connectorService.list()).thenReturn(List.of(
                conn("c-only-A", List.of(GROUP_A)),
                conn("c-both", List.of(GROUP_A, GROUP_B))
        ));

        ResponseEntity<?> resp = controller.simulateRemove(USER, body(REPO, true, List.of(GROUP_A)));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> b = (Map<String, Object>) resp.getBody();
        assertNotNull(b);
        List<Map<String, Object>> lost = (List<Map<String, Object>>) b.get("lost");
        List<Map<String, Object>> kept = (List<Map<String, Object>>) b.get("kept");
        assertEquals(1, lost.size(), "expected 1 lost when removing only A");
        assertEquals("c-only-A", lost.get(0).get("connectorId"));
        assertEquals(1, kept.size(), "expected 1 kept (c-both survives via B)");
        assertEquals("c-both", kept.get(0).get("connectorId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void multiPrincipalRemoval_cascades_correctly() {
        // Removing BOTH A and B → c-both also lost (no alternate route)
        asAdmin();
        jp.aegif.nemaki.model.User u = mock(jp.aegif.nemaki.model.User.class);
        when(principalService.getUserById(REPO, USER)).thenReturn(u);
        when(authService.expandPrincipals(REPO, USER)).thenReturn(Set.of(USER, GROUP_A, GROUP_B));
        when(connectorService.list()).thenReturn(List.of(
                conn("c-only-A", List.of(GROUP_A)),
                conn("c-only-B", List.of(GROUP_B)),
                conn("c-both", List.of(GROUP_A, GROUP_B))
        ));

        ResponseEntity<?> resp = controller.simulateRemove(USER,
                body(REPO, true, List.of(GROUP_A, GROUP_B)));

        Map<String, Object> b = (Map<String, Object>) resp.getBody();
        List<Map<String, Object>> lost = (List<Map<String, Object>>) b.get("lost");
        List<Map<String, Object>> kept = (List<Map<String, Object>>) b.get("kept");
        assertEquals(3, lost.size(), "expected all 3 connectors lost when both groups removed");
        assertEquals(0, kept.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void responseBody_includesEchoFields_matchingV3Shape() {
        // The simulate-remove response shape mirrors V3 listByPrincipal
        // (principalId / principalType / repositoryId / expand /
        // expandedPrincipals) plus the W2-specific lost+kept+removePrincipalIds.
        asAdmin();
        jp.aegif.nemaki.model.User u = mock(jp.aegif.nemaki.model.User.class);
        when(principalService.getUserById(REPO, USER)).thenReturn(u);
        when(authService.expandPrincipals(REPO, USER)).thenReturn(Set.of(USER, GROUP_A));
        when(connectorService.list()).thenReturn(List.of());

        ResponseEntity<?> resp = controller.simulateRemove(USER, body(REPO, true, List.of(GROUP_A)));

        Map<String, Object> b = (Map<String, Object>) resp.getBody();
        assertEquals(USER, b.get("principalId"));
        assertEquals("USER", b.get("principalType"));
        assertEquals(REPO, b.get("repositoryId"));
        assertEquals(Boolean.TRUE, b.get("expand"));
        assertNotNull(b.get("expandedPrincipals"));
        assertEquals(List.of(GROUP_A), b.get("removePrincipalIds"));
        assertNotNull(b.get("lost"));
        assertNotNull(b.get("kept"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void groupPrincipal_expandSkipped_matchesV3Semantics() {
        // For GROUP queried principal, expansion is skipped even with
        // expand=true (NemakiWare groups don't nest). Match logic must
        // align with V3's listByPrincipal behaviour.
        asAdmin();
        jp.aegif.nemaki.model.Group g = mock(jp.aegif.nemaki.model.Group.class);
        when(principalService.getUserById(REPO, GROUP_A)).thenReturn(null);
        when(principalService.getGroupById(REPO, GROUP_A)).thenReturn(g);
        when(connectorService.list()).thenReturn(List.of(
                conn("c-via-A", List.of(GROUP_A))
        ));

        controller.simulateRemove(GROUP_A, body(REPO, true, List.of(GROUP_A)));

        // expandPrincipals must NOT be called for GROUP principals
        org.mockito.Mockito.verify(authService, org.mockito.Mockito.never())
                .expandPrincipals(eq(REPO), eq(GROUP_A));
    }

    @Test
    @SuppressWarnings("unchecked")
    void connectorWithEmptyAllowedPrincipals_skipped_likeV3() {
        // V3 governance view skips connectors with empty allowedPrincipalIds.
        // Simulate-remove must do the same so the two endpoints stay aligned.
        asAdmin();
        jp.aegif.nemaki.model.User u = mock(jp.aegif.nemaki.model.User.class);
        when(principalService.getUserById(REPO, USER)).thenReturn(u);
        when(authService.expandPrincipals(REPO, USER)).thenReturn(Set.of(USER, GROUP_A));
        when(connectorService.list()).thenReturn(List.of(
                conn("c-open", List.of()),
                conn("c-null", null),
                conn("c-real", List.of(GROUP_A))
        ));

        ResponseEntity<?> resp = controller.simulateRemove(USER, body(REPO, true, List.of(GROUP_A)));

        Map<String, Object> b = (Map<String, Object>) resp.getBody();
        List<Map<String, Object>> lost = (List<Map<String, Object>>) b.get("lost");
        List<Map<String, Object>> kept = (List<Map<String, Object>>) b.get("kept");
        // Only c-real contributes; c-open and c-null are skipped at match build
        assertEquals(1, lost.size());
        assertEquals(0, kept.size());
        assertEquals("c-real", lost.get(0).get("connectorId"));
    }

    @Test
    void removePrincipalIdsExceedingMaxCount_returns400_M2() {
        // RC6 M2: the server caps the inbound array at
        // MAX_REMOVE_PRINCIPAL_IDS (500). 501 entries → 400. The cap
        // gate must fire BEFORE the per-entry loop allocates — if the
        // loop ran first, a million-entry payload would still bloat
        // the LinkedHashSet allocation.
        asAdmin();
        java.util.ArrayList<String> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < 501; i++) tooMany.add("group-" + i);
        ResponseEntity<?> resp = controller.simulateRemove(USER, body(REPO, true, tooMany));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> b = (Map<String, Object>) resp.getBody();
        assertTrue(((String) b.get("message")).contains("removePrincipalIds exceeds maximum size"),
                "message must name the limit so the caller can tune: " + b.get("message"));
        // The buildMatches loop must NOT have run — verify connector
        // listing was never touched.
        org.mockito.Mockito.verify(connectorService, org.mockito.Mockito.never()).list();
    }

    @Test
    void removePrincipalIdsAtMaxCount_returns200_M2() {
        // RC6 M2 boundary: exactly MAX_REMOVE_PRINCIPAL_IDS (500)
        // entries → still accepted. Wired with a minimal connector list
        // so the test verifies the gate boundary, not the matching
        // semantics (those are covered elsewhere).
        asAdmin();
        jp.aegif.nemaki.model.User u = mock(jp.aegif.nemaki.model.User.class);
        when(principalService.getUserById(REPO, USER)).thenReturn(u);
        when(authService.expandPrincipals(REPO, USER)).thenReturn(Set.of(USER));
        when(connectorService.list()).thenReturn(List.of());
        java.util.ArrayList<String> exactlyMax = new java.util.ArrayList<>();
        for (int i = 0; i < 500; i++) exactlyMax.add("group-" + i);
        ResponseEntity<?> resp = controller.simulateRemove(USER, body(REPO, true, exactlyMax));
        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    @Test
    void principalIdEntryExceedingMaxLength_returns400_M2() {
        // RC6 M2: an individual principal ID >MAX_PRINCIPAL_ID_LENGTH
        // (512 chars) rejects the whole request. We don't silently
        // drop the offender because the caller would otherwise get a
        // 200 with an empty removePrincipalIds (after filtering) and
        // be confused about what was sent vs. accepted.
        asAdmin();
        // 513-char ASCII string — over the cap
        String tooLong = "a".repeat(513);
        ResponseEntity<?> resp = controller.simulateRemove(USER,
                body(REPO, true, List.of("group-a", tooLong)));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> b = (Map<String, Object>) resp.getBody();
        assertTrue(((String) b.get("message")).contains("removePrincipalIds entry exceeds maximum length"),
                "message must name the limit so the caller can tune: " + b.get("message"));
    }

    @Test
    void principalIdEntryAtMaxLength_returns200_M2() {
        // RC6 M2 boundary: exactly MAX_PRINCIPAL_ID_LENGTH (512) chars
        // — accepted. Combined with a normal entry to exercise the
        // mixed-length code path.
        asAdmin();
        jp.aegif.nemaki.model.User u = mock(jp.aegif.nemaki.model.User.class);
        when(principalService.getUserById(REPO, USER)).thenReturn(u);
        when(authService.expandPrincipals(REPO, USER)).thenReturn(Set.of(USER));
        when(connectorService.list()).thenReturn(List.of());
        String maxLen = "a".repeat(512);
        ResponseEntity<?> resp = controller.simulateRemove(USER,
                body(REPO, true, List.of("group-a", maxLen)));
        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void blankStringEntriesInRemovalSet_filteredOut() {
        // Defensive: client ships ["GROUP_A", "", "  "] → only GROUP_A
        // ends up in the removal set. Empty-after-filter would 400.
        asAdmin();
        jp.aegif.nemaki.model.User u = mock(jp.aegif.nemaki.model.User.class);
        when(principalService.getUserById(REPO, USER)).thenReturn(u);
        when(authService.expandPrincipals(REPO, USER)).thenReturn(Set.of(USER, GROUP_A));
        when(connectorService.list()).thenReturn(List.of(conn("c-via-A", List.of(GROUP_A))));

        java.util.ArrayList<String> mixed = new java.util.ArrayList<>();
        mixed.add(GROUP_A);
        mixed.add("");
        mixed.add("  ");

        ResponseEntity<?> resp = controller.simulateRemove(USER, body(REPO, true, mixed));

        Map<String, Object> b = (Map<String, Object>) resp.getBody();
        assertEquals(List.of(GROUP_A), b.get("removePrincipalIds"));
        assertEquals(1, ((List<?>) b.get("lost")).size());
    }
}
