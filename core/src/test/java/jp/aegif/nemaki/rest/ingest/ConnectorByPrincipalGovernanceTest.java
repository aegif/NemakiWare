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
 * RC5 (v2 §12.3): {@code GET /v1/admin/connectors/by-principal/{id}}.
 * Admin-only governance view. Pins:
 * <ul>
 *   <li>Admin gate (non-admin → 403)</li>
 *   <li>repositoryId required</li>
 *   <li>{@code expand=false} → only direct allowedPrincipalIds hits</li>
 *   <li>{@code expand=true} → includes group-derived hits + records
 *       which principal IDs matched</li>
 *   <li>{@code matchType} resolves to {@code direct},
 *       {@code group}, or {@code direct+group}</li>
 *   <li>Connectors with an empty allowedPrincipalIds are skipped</li>
 * </ul>
 */
class ConnectorByPrincipalGovernanceTest {

    private static final String REPO = "bedroom";
    private static final String USER = "alice";
    private static final String GROUP = "engineers";

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

    @Test
    void nonAdmin_returns403() {
        asNonAdmin();
        ResponseEntity<?> resp = controller.listByPrincipal(USER, REPO, false);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    void missingRepositoryId_returns400() {
        asAdmin();
        ResponseEntity<?> resp = controller.listByPrincipal(USER, "", false);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void blankPrincipalId_returns400() {
        asAdmin();
        ResponseEntity<?> resp = controller.listByPrincipal("   ", REPO, false);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void noExpansion_returnsOnlyDirectMatches() {
        asAdmin();
        when(connectorService.list()).thenReturn(List.of(
                conn("c-direct", List.of(USER)),
                conn("c-group", List.of(GROUP)),
                conn("c-other", List.of("bob"))
        ));

        ResponseEntity<?> resp = controller.listByPrincipal(USER, REPO, false);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertNotNull(body);
        List<Map<String, Object>> matches = (List<Map<String, Object>>) body.get("matches");
        assertEquals(1, matches.size(), "expected only the direct match");
        assertEquals("c-direct", matches.get(0).get("connectorId"));
        assertEquals("direct", matches.get(0).get("matchType"));
        assertEquals(List.of(USER), matches.get(0).get("matchedPrincipalIds"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void expansion_includesGroupDerivedMatches() {
        asAdmin();
        when(authService.expandPrincipals(REPO, USER))
                .thenReturn(Set.of(USER, GROUP, "sf-office"));
        when(connectorService.list()).thenReturn(List.of(
                conn("c-direct", List.of(USER)),
                conn("c-group", List.of(GROUP)),
                conn("c-office", List.of("sf-office")),
                conn("c-other", List.of("bob"))
        ));

        ResponseEntity<?> resp = controller.listByPrincipal(USER, REPO, true);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        List<Map<String, Object>> matches = (List<Map<String, Object>>) body.get("matches");
        assertEquals(3, matches.size(),
                "expected direct + 2 group-derived matches");
        Map<String, String> typesByConnector = new java.util.HashMap<>();
        matches.forEach(m -> typesByConnector.put(
                (String) m.get("connectorId"), (String) m.get("matchType")));
        assertEquals("direct", typesByConnector.get("c-direct"));
        assertEquals("group", typesByConnector.get("c-group"));
        assertEquals("group", typesByConnector.get("c-office"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void connectorWithBothDirectAndGroupMatch_reportsAsDirectPlusGroup() {
        // A connector that allows both the user directly AND a group they
        // belong to should be flagged "direct+group" so the operator can
        // see the redundancy.
        asAdmin();
        when(authService.expandPrincipals(REPO, USER))
                .thenReturn(Set.of(USER, GROUP));
        when(connectorService.list()).thenReturn(List.of(
                conn("c-both", List.of(USER, GROUP))
        ));

        ResponseEntity<?> resp = controller.listByPrincipal(USER, REPO, true);
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        List<Map<String, Object>> matches = (List<Map<String, Object>>) body.get("matches");
        assertEquals(1, matches.size());
        assertEquals("direct+group", matches.get(0).get("matchType"));
        List<String> matchedIds = (List<String>) matches.get(0).get("matchedPrincipalIds");
        assertTrue(matchedIds.contains(USER));
        assertTrue(matchedIds.contains(GROUP));
    }

    @Test
    @SuppressWarnings("unchecked")
    void connectorWithEmptyAllowedPrincipals_isSkipped() {
        asAdmin();
        when(connectorService.list()).thenReturn(List.of(
                conn("c-open", List.of()),
                conn("c-null", null)
        ));

        ResponseEntity<?> resp = controller.listByPrincipal(USER, REPO, false);
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        List<Map<String, Object>> matches = (List<Map<String, Object>>) body.get("matches");
        assertEquals(0, matches.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void responseBody_includesEchoFieldsForCaller() {
        // The body must echo back principalId / repositoryId / expand so the
        // caller can render the result against the input they sent.
        asAdmin();
        when(connectorService.list()).thenReturn(List.of());

        ResponseEntity<?> resp = controller.listByPrincipal(USER, REPO, true);
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertEquals(USER, body.get("principalId"));
        assertEquals(REPO, body.get("repositoryId"));
        assertEquals(Boolean.TRUE, body.get("expand"));
        assertNotNull(body.get("expandedPrincipals"));
    }

    // ── V2 (RC5 ext): principalType resolution ─────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void principalType_USER_whenPrincipalServiceResolvesUser() {
        asAdmin();
        jp.aegif.nemaki.model.User u = mock(jp.aegif.nemaki.model.User.class);
        when(principalService.getUserById(REPO, USER)).thenReturn(u);
        when(connectorService.list()).thenReturn(List.of());

        ResponseEntity<?> resp = controller.listByPrincipal(USER, REPO, false);
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertEquals("USER", body.get("principalType"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void principalType_GROUP_whenPrincipalServiceResolvesGroup() {
        asAdmin();
        jp.aegif.nemaki.model.Group g = mock(jp.aegif.nemaki.model.Group.class);
        when(principalService.getUserById(REPO, GROUP)).thenReturn(null);
        when(principalService.getGroupById(REPO, GROUP)).thenReturn(g);
        when(connectorService.list()).thenReturn(List.of());

        ResponseEntity<?> resp = controller.listByPrincipal(GROUP, REPO, false);
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertEquals("GROUP", body.get("principalType"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void principalType_UNKNOWN_whenNeitherUserNorGroupResolves() {
        // Pseudo-principal (e.g. "anyone"), or a typo — must not break
        // the lookup. Match list is still computed against the raw
        // principalId so direct allowedPrincipalIds hits still surface.
        asAdmin();
        when(principalService.getUserById(REPO, "pseudo-anyone")).thenReturn(null);
        when(principalService.getGroupById(REPO, "pseudo-anyone")).thenReturn(null);
        when(connectorService.list()).thenReturn(List.of());

        ResponseEntity<?> resp = controller.listByPrincipal("pseudo-anyone", REPO, true);
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertEquals("UNKNOWN", body.get("principalType"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void principalType_UNKNOWN_whenPrincipalServiceNotWired() throws Exception {
        // Defence: ConnectorDefinitionController.principalService is
        // declared @Autowired(required=false) so the rest of the
        // controller still works when the bean is missing. Reset to
        // null to simulate that.
        java.lang.reflect.Field f = ConnectorDefinitionController.class.getDeclaredField("principalService");
        f.setAccessible(true);
        f.set(controller, null);

        asAdmin();
        when(connectorService.list()).thenReturn(List.of());

        ResponseEntity<?> resp = controller.listByPrincipal(USER, REPO, true);
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertEquals("UNKNOWN", body.get("principalType"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void expand_isSkippedForGROUPPrincipal() {
        // When the input is a group, expansion is a conceptual no-op
        // (NemakiWare groups don't nest). The endpoint must not call
        // expandPrincipals to avoid PrincipalService impl-dependent
        // surprises when fed a non-user ID.
        asAdmin();
        jp.aegif.nemaki.model.Group g = mock(jp.aegif.nemaki.model.Group.class);
        when(principalService.getUserById(REPO, GROUP)).thenReturn(null);
        when(principalService.getGroupById(REPO, GROUP)).thenReturn(g);
        when(connectorService.list()).thenReturn(List.of());

        controller.listByPrincipal(GROUP, REPO, true);

        // expandPrincipals must NOT be called for GROUP principals
        org.mockito.Mockito.verify(authService, org.mockito.Mockito.never())
                .expandPrincipals(org.mockito.ArgumentMatchers.eq(REPO),
                        org.mockito.ArgumentMatchers.eq(GROUP));
    }
}
