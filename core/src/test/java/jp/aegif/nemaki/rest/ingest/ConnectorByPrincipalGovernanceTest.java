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

    // ── RC6 B3-2: /by-group/{groupId} ───────────────────────────────

    private jp.aegif.nemaki.model.Group makeGroup(String id, List<String> users) {
        jp.aegif.nemaki.model.Group g = new jp.aegif.nemaki.model.Group();
        g.setGroupId(id);
        g.setName(id + " display");
        g.setUsers(users);
        return g;
    }

    @Test
    void byGroup_nonAdmin_returns403() {
        asNonAdmin();
        ResponseEntity<?> resp = controller.listByGroup(GROUP, REPO, true, 200);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    void byGroup_missingGroupId_returns400() {
        asAdmin();
        ResponseEntity<?> resp = controller.listByGroup("  ", REPO, true, 200);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void byGroup_missingRepoId_returns400() {
        asAdmin();
        ResponseEntity<?> resp = controller.listByGroup(GROUP, "", true, 200);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void byGroup_unknownPrincipal_returnsEmptyMembers_butStillSurfacesDirectGrants() {
        asAdmin();
        // PrincipalService returns null for getGroupById → groupType=UNKNOWN
        when(principalService.getGroupById(REPO, GROUP)).thenReturn(null);
        // Even so, a connector that lists "engineers" in allowedPrincipalIds
        // should still appear in directGrants (governance is best-effort
        // — operators can probe arbitrary IDs).
        when(connectorService.list()).thenReturn(List.of(
                conn("c1", List.of(GROUP))
        ));
        when(authService.expandPrincipals(any(), any())).thenReturn(Set.of());

        ResponseEntity<?> resp = controller.listByGroup(GROUP, REPO, true, 200);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("UNKNOWN", body.get("groupType"));
        assertEquals(0, body.get("memberCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> direct = (List<Map<String, Object>>) body.get("directGrants");
        assertEquals(1, direct.size());
        assertEquals("c1", direct.get(0).get("connectorId"));
        // RC6 review L: perMemberImpact + companion flags are ALWAYS
        // present in the response shape. With no members, the impact
        // array is empty and the truncation flag is false.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> impact =
                (List<Map<String, Object>>) body.get("perMemberImpact");
        assertNotNull(impact, "perMemberImpact must always be present");
        assertEquals(0, impact.size());
        assertEquals(Boolean.FALSE, body.get("perMemberImpactTruncated"));
        assertEquals(Boolean.FALSE, body.get("memberUserIdsTruncated"));
        assertEquals(200, body.get("memberLimit"));
    }

    @Test
    void byGroup_resolvedGroupWithMembers_returnsMemberListAndDirectGrants() {
        asAdmin();
        when(principalService.getGroupById(REPO, GROUP))
                .thenReturn(makeGroup(GROUP, List.of("alice", "bob", "carol")));
        when(connectorService.list()).thenReturn(List.of(
                conn("c1", List.of(GROUP)),
                conn("c2", List.of("other-group"))
        ));
        // Each member's expansion includes the queried group + nothing else
        when(authService.expandPrincipals(eq(REPO), eq("alice")))
                .thenReturn(Set.of(GROUP));
        when(authService.expandPrincipals(eq(REPO), eq("bob")))
                .thenReturn(Set.of(GROUP));
        when(authService.expandPrincipals(eq(REPO), eq("carol")))
                .thenReturn(Set.of(GROUP));

        ResponseEntity<?> resp = controller.listByGroup(GROUP, REPO, true, 200);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("GROUP", body.get("groupType"));
        assertEquals(3, body.get("memberCount"));
        @SuppressWarnings("unchecked")
        List<String> members = (List<String>) body.get("memberUserIds");
        assertEquals(List.of("alice", "bob", "carol"), members);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> direct = (List<Map<String, Object>>) body.get("directGrants");
        assertEquals(1, direct.size());
        assertEquals("c1", direct.get(0).get("connectorId"));
    }

    @Test
    void byGroup_perMemberImpact_detectsSoleRouteVsAlternateRoute() {
        asAdmin();
        when(principalService.getGroupById(REPO, GROUP))
                .thenReturn(makeGroup(GROUP, List.of("alice", "bob")));
        // c1 is reachable via the queried GROUP only
        // c2 is reachable via "other-group" (NOT via GROUP)
        // c3 is reachable both via GROUP and direct user grant
        when(connectorService.list()).thenReturn(List.of(
                conn("c1", List.of(GROUP)),
                conn("c2", List.of("other-group")),
                conn("c3", List.of(GROUP, "alice"))
        ));
        // alice belongs to both GROUP and other-group + has a direct grant
        when(authService.expandPrincipals(eq(REPO), eq("alice")))
                .thenReturn(Set.of(GROUP, "other-group"));
        // bob belongs to GROUP only
        when(authService.expandPrincipals(eq(REPO), eq("bob")))
                .thenReturn(Set.of(GROUP));

        ResponseEntity<?> resp = controller.listByGroup(GROUP, REPO, true, 200);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> impact =
                (List<Map<String, Object>>) body.get("perMemberImpact");
        assertNotNull(impact);
        assertEquals(2, impact.size());

        // alice: only c1 is sole-route via GROUP (c2 via other-group, c3 via direct user grant)
        Map<String, Object> aliceEntry = impact.get(0);
        assertEquals("alice", aliceEntry.get("userId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> aliceLost =
                (List<Map<String, Object>>) aliceEntry.get("lostIfGroupRemoved");
        assertEquals(1, aliceLost.size());
        assertEquals("c1", aliceLost.get(0).get("connectorId"));

        // bob: c1 lost (GROUP-only), c3 also lost (only matched principal is GROUP for bob)
        Map<String, Object> bobEntry = impact.get(1);
        assertEquals("bob", bobEntry.get("userId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bobLost =
                (List<Map<String, Object>>) bobEntry.get("lostIfGroupRemoved");
        assertEquals(2, bobLost.size());
        assertEquals(Set.of("c1", "c3"),
                Set.of((String) bobLost.get(0).get("connectorId"),
                       (String) bobLost.get(1).get("connectorId")));
    }

    @Test
    void byGroup_memberLimitTruncatesBothMemberListAndImpact() {
        // RC6 review M: memberUserIds itself must be capped by
        // memberLimit (not just perMemberImpact) so the response stays
        // bounded for very large groups. memberCount preserves the
        // untruncated size; memberUserIdsTruncated signals the cap.
        asAdmin();
        List<String> bigMembers = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) bigMembers.add("user-" + i);
        when(principalService.getGroupById(REPO, GROUP))
                .thenReturn(makeGroup(GROUP, bigMembers));
        when(connectorService.list()).thenReturn(List.of(conn("c1", List.of(GROUP))));
        when(authService.expandPrincipals(any(), any())).thenReturn(Set.of(GROUP));

        ResponseEntity<?> resp = controller.listByGroup(GROUP, REPO, true, 10);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        @SuppressWarnings("unchecked")
        List<String> members = (List<String>) body.get("memberUserIds");
        assertEquals(10, members.size(), "memberUserIds capped at memberLimit");
        assertEquals(50, body.get("memberCount"),
                "memberCount preserves untruncated size");
        assertEquals(Boolean.TRUE, body.get("memberUserIdsTruncated"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> impact =
                (List<Map<String, Object>>) body.get("perMemberImpact");
        assertEquals(10, impact.size(), "perMemberImpact capped in lock-step");
        assertEquals(Boolean.TRUE, body.get("perMemberImpactTruncated"));
        assertEquals(10, body.get("memberLimit"));
    }

    @Test
    void byGroup_memberLimitClampedToServerMax_RC6_reviewM() {
        // RC6 review M: an admin can't request memberLimit > MAX_MEMBER_LIMIT
        // and force the controller to allocate a huge per-member list.
        // The clamp is in the controller, not exposed via property —
        // verified here by sending 1_000_000 and observing the response.
        asAdmin();
        // Use a tiny group so the actual truncation isn't what we're
        // measuring — only the echo of the clamped memberLimit value.
        when(principalService.getGroupById(REPO, GROUP))
                .thenReturn(makeGroup(GROUP, List.of("alice")));
        when(connectorService.list()).thenReturn(List.of());
        when(authService.expandPrincipals(any(), any())).thenReturn(Set.of());

        ResponseEntity<?> resp = controller.listByGroup(GROUP, REPO, true, 1_000_000);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        // 1000 is the MAX_MEMBER_LIMIT constant declared in the controller
        assertEquals(1000, body.get("memberLimit"),
                "memberLimit echoed back must be clamped to MAX_MEMBER_LIMIT");
    }

    @Test
    void byGroup_includeMembersFalse_returnsStableShapeWithEmptyImpact_reviewL() {
        // RC6 review L: includeMembers=false is the documented fast path
        // (no per-member expansion). The response shape must still be
        // stable for the UI — perMemberImpact is an empty array, and
        // perMemberImpactTruncated is false (we didn't attempt expansion;
        // that's different from "we truncated it").
        asAdmin();
        when(principalService.getGroupById(REPO, GROUP))
                .thenReturn(makeGroup(GROUP, List.of("alice", "bob")));
        when(connectorService.list()).thenReturn(List.of(conn("c1", List.of(GROUP))));

        ResponseEntity<?> resp = controller.listByGroup(GROUP, REPO, false, 200);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertEquals(2, body.get("memberCount"));
        // perMemberImpact is present (review L) and empty
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> impact =
                (List<Map<String, Object>>) body.get("perMemberImpact");
        assertNotNull(impact, "perMemberImpact must always be present");
        assertEquals(0, impact.size(), "fast path skips per-member expansion");
        assertEquals(Boolean.FALSE, body.get("perMemberImpactTruncated"),
                "false (not truncated) — we just didn't attempt expansion");
        assertEquals(200, body.get("memberLimit"));
        // expandPrincipals must NOT have been called per-member
        org.mockito.Mockito.verify(authService, org.mockito.Mockito.never())
                .expandPrincipals(any(), any());
    }

    @Test
    void byGroup_perRequestConnectorListIsFetchedExactlyOnce_M3() {
        // RC6 M3: listByGroup with includeMembers=true previously called
        // connectorDefinitionService.list() once per member + once for
        // directGrants — up to 201 times for memberLimit=200. The M3
        // refactor caches the connector list at controller-method scope
        // and passes it through the buildMatches overload. This test
        // pins the invariant: regardless of member count, list() runs
        // exactly once per request.
        asAdmin();
        // 25 members — enough to make the prior O(members + 1) cost
        // obvious if the cache regresses, but not so many that the
        // test wastes wall time.
        java.util.List<String> manyMembers = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) manyMembers.add("user-" + i);
        when(principalService.getGroupById(REPO, GROUP))
                .thenReturn(makeGroup(GROUP, manyMembers));
        when(connectorService.list()).thenReturn(List.of(
                conn("c1", List.of(GROUP)),
                conn("c2", List.of("other-group"))
        ));
        when(authService.expandPrincipals(any(), any())).thenReturn(Set.of(GROUP));

        ResponseEntity<?> resp = controller.listByGroup(GROUP, REPO, true, 200);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        // Pre-M3 baseline would have been 26 (25 members + 1 directGrants);
        // M3 must collapse it to exactly 1.
        org.mockito.Mockito.verify(connectorService, org.mockito.Mockito.times(1)).list();
    }

    @Test
    void byPrincipal_singleListCallPerRequest_M3_regressionGuard() {
        // Companion to the M3 test above: confirms the single-call path
        // is unchanged. listByPrincipal still uses the no-arg
        // buildMatches overload, which lists() once.
        asAdmin();
        when(connectorService.list()).thenReturn(List.of(conn("c1", List.of(USER))));
        when(authService.expandPrincipals(any(), any())).thenReturn(Set.of(USER));

        controller.listByPrincipal(USER, REPO, true);

        org.mockito.Mockito.verify(connectorService, org.mockito.Mockito.times(1)).list();
    }

    @Test
    void byPrincipal_connectorListReturnsNull_returnsEmptyMatches_L2() {
        // RC6 L2: ConnectorDefinitionService.list() returning null
        // must not NPE the governance endpoint. Treated as
        // "no connectors known" → matches[] is empty, response
        // shape stays stable.
        asAdmin();
        when(connectorService.list()).thenReturn(null);
        when(authService.expandPrincipals(any(), any())).thenReturn(Set.of());

        ResponseEntity<?> resp = controller.listByPrincipal(USER, REPO, false);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) body.get("matches");
        assertNotNull(matches);
        assertEquals(0, matches.size());
    }

    @Test
    void byGroup_connectorListReturnsNull_returnsEmptyMatches_L2() {
        // Same defence on the by-group path. Both the directGrants
        // call and the perMemberImpact loop share the same cached
        // allConnectors list, so a single null surfaces as zero
        // matches across the whole response without NPE.
        asAdmin();
        when(principalService.getGroupById(REPO, GROUP))
                .thenReturn(makeGroup(GROUP, List.of("alice")));
        when(connectorService.list()).thenReturn(null);
        when(authService.expandPrincipals(any(), any())).thenReturn(Set.of(GROUP));

        ResponseEntity<?> resp = controller.listByGroup(GROUP, REPO, true, 200);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> direct = (List<Map<String, Object>>) body.get("directGrants");
        assertEquals(0, direct.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> impact = (List<Map<String, Object>>) body.get("perMemberImpact");
        assertEquals(1, impact.size());
        @SuppressWarnings("unchecked")
        List<?> aliceLost = (List<?>) impact.get(0).get("lostIfGroupRemoved");
        assertEquals(0, aliceLost.size());
    }

    @Test
    void byGroup_principalServiceThrows_fallsBackToUnknown() {
        asAdmin();
        when(principalService.getGroupById(REPO, GROUP))
                .thenThrow(new RuntimeException("backend down"));
        when(connectorService.list()).thenReturn(List.of(conn("c1", List.of(GROUP))));

        ResponseEntity<?> resp = controller.listByGroup(GROUP, REPO, true, 200);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("UNKNOWN", body.get("groupType"));
        assertEquals(0, body.get("memberCount"));
        // directGrants still works — that path doesn't depend on PrincipalService
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> direct = (List<Map<String, Object>>) body.get("directGrants");
        assertEquals(1, direct.size());
    }
}
