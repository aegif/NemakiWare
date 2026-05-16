package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.model.Ace;
import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.CallContextKey;
import jp.aegif.nemaki.util.constant.CmisPermission;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IngestAuthorizationServiceTest {

    private static final String REPO = "bedroom";
    private static final String FOLDER = "folder-1";
    private static final String PARENT = "folder-parent";
    private static final String GRANDPARENT = "folder-grandparent";
    private static final String USER = "alice";
    private static final String GROUP = "group:editors";
    private static final String ANYONE = "Anyone";

    private ContentService contentService;
    private PrincipalService principalService;
    private IngestAuthorizationService svc;

    @BeforeEach
    void setUp() {
        contentService = mock(ContentService.class);
        principalService = mock(PrincipalService.class);
        svc = new IngestAuthorizationService();
        svc.setContentService(contentService);
        svc.setPrincipalService(principalService);
        when(principalService.getAnyone(REPO)).thenReturn(ANYONE);
        when(principalService.getGroupIdsContainingUser(REPO, USER)).thenReturn(Set.of(GROUP));
    }

    // ────────────────────────────────────────────────────────────────────
    // isAdmin
    // ────────────────────────────────────────────────────────────────────

    @Test
    void isAdmin_trueWhenCallContextFlagTrue() {
        CallContext ctx = mock(CallContext.class);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        assertTrue(svc.isAdmin(ctx));
    }

    @Test
    void isAdmin_falseWhenCallContextNull() {
        assertFalse(svc.isAdmin(null));
    }

    @Test
    void isAdmin_fallbackToUserItemWhenFlagAbsent() {
        CallContext ctx = mock(CallContext.class);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(null);
        when(ctx.getUsername()).thenReturn(USER);
        when(ctx.getRepositoryId()).thenReturn(REPO);
        UserItem u = mock(UserItem.class);
        when(u.isAdmin()).thenReturn(true);
        when(contentService.getUserItemById(REPO, USER)).thenReturn(u);
        assertTrue(svc.isAdmin(ctx));
    }

    @Test
    void isAdmin_failClosedWhenLookupThrows() {
        CallContext ctx = mock(CallContext.class);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(null);
        when(ctx.getUsername()).thenReturn(USER);
        when(ctx.getRepositoryId()).thenReturn(REPO);
        when(contentService.getUserItemById(REPO, USER)).thenThrow(new RuntimeException("db down"));
        assertFalse(svc.isAdmin(ctx));
    }

    // ────────────────────────────────────────────────────────────────────
    // canManageProfileForFolder
    // ────────────────────────────────────────────────────────────────────

    @Test
    void canManage_adminAlwaysTrue() {
        CallContext ctx = adminCtx();
        assertTrue(svc.canManageProfileForFolder(ctx, REPO, FOLDER));
        verify(contentService, never()).getFolder(any(), any());
    }

    @Test
    void canManage_falseWhenFolderNotFound() {
        CallContext ctx = userCtx();
        when(contentService.getFolder(REPO, FOLDER)).thenReturn(null);
        assertFalse(svc.canManageProfileForFolder(ctx, REPO, FOLDER));
    }

    @Test
    void canManage_trueWhenUserHasDirectCmisAll() {
        CallContext ctx = userCtx();
        Folder f = mockFolder(FOLDER);
        when(contentService.getFolder(REPO, FOLDER)).thenReturn(f);
        doReturn(aclWith(ace(USER, CmisPermission.ALL))).when(contentService).calculateAcl(REPO, f);
        assertTrue(svc.canManageProfileForFolder(ctx, REPO, FOLDER));
    }

    @Test
    void canManage_trueWhenUserHasCmisAllViaGroup() {
        CallContext ctx = userCtx();
        Folder f = mockFolder(FOLDER);
        when(contentService.getFolder(REPO, FOLDER)).thenReturn(f);
        doReturn(aclWith(ace(GROUP, CmisPermission.ALL))).when(contentService).calculateAcl(REPO, f);
        assertTrue(svc.canManageProfileForFolder(ctx, REPO, FOLDER));
    }

    @Test
    void canManage_trueWhenAnyoneHasCmisAll() {
        CallContext ctx = userCtx();
        Folder f = mockFolder(FOLDER);
        when(contentService.getFolder(REPO, FOLDER)).thenReturn(f);
        doReturn(aclWith(ace(ANYONE, CmisPermission.ALL))).when(contentService).calculateAcl(REPO, f);
        assertTrue(svc.canManageProfileForFolder(ctx, REPO, FOLDER));
    }

    @Test
    void canManage_falseWhenUserHasOnlyRead() {
        CallContext ctx = userCtx();
        Folder f = mockFolder(FOLDER);
        when(contentService.getFolder(REPO, FOLDER)).thenReturn(f);
        doReturn(aclWith(ace(USER, CmisPermission.READ))).when(contentService).calculateAcl(REPO, f);
        assertFalse(svc.canManageProfileForFolder(ctx, REPO, FOLDER));
    }

    @Test
    void canManage_falseWhenAclNull() {
        CallContext ctx = userCtx();
        Folder f = mockFolder(FOLDER);
        when(contentService.getFolder(REPO, FOLDER)).thenReturn(f);
        when(contentService.calculateAcl(REPO, f)).thenReturn(null);
        assertFalse(svc.canManageProfileForFolder(ctx, REPO, FOLDER));
    }

    @Test
    void canManage_failClosedWhenCalculateAclThrows() {
        CallContext ctx = userCtx();
        Folder f = mockFolder(FOLDER);
        when(contentService.getFolder(REPO, FOLDER)).thenReturn(f);
        when(contentService.calculateAcl(REPO, f)).thenThrow(new RuntimeException("DAO error"));
        assertFalse(svc.canManageProfileForFolder(ctx, REPO, FOLDER));
    }

    @Test
    void canManage_failClosedWhenGroupExpansionThrows() {
        CallContext ctx = userCtx();
        Folder f = mockFolder(FOLDER);
        when(contentService.getFolder(REPO, FOLDER)).thenReturn(f);
        // Group ACE present, but group expansion blows up — must not match group ACE
        when(principalService.getGroupIdsContainingUser(REPO, USER))
                .thenThrow(new RuntimeException("ldap down"));
        doReturn(aclWith(ace(GROUP, CmisPermission.ALL))).when(contentService).calculateAcl(REPO, f);
        assertFalse(svc.canManageProfileForFolder(ctx, REPO, FOLDER));
    }

    @Test
    void canManage_falseForNullArgs() {
        assertFalse(svc.canManageProfileForFolder(null, REPO, FOLDER));
        assertFalse(svc.canManageProfileForFolder(userCtx(), null, FOLDER));
        assertFalse(svc.canManageProfileForFolder(userCtx(), REPO, null));
        assertFalse(svc.canManageProfileForFolder(userCtx(), REPO, "  "));
    }

    // ────────────────────────────────────────────────────────────────────
    // canUseConnectorForDelegatedProfile
    // ────────────────────────────────────────────────────────────────────

    @Test
    void canUseConnector_falseWhenConnectorNull() {
        assertFalse(svc.canUseConnectorForDelegatedProfile(userCtx(), REPO, null, FOLDER));
    }

    @Test
    void canUseConnector_falseWhenConnectorDisabled() {
        ConnectorDefinition c = delegatedConnector();
        c.setEnabled(false);
        assertFalse(svc.canUseConnectorForDelegatedProfile(userCtx(), REPO, c, FOLDER));
    }

    @Test
    void canUseConnector_falseWhenNotDelegated() {
        ConnectorDefinition c = delegatedConnector();
        c.setDelegated(false);
        assertFalse(svc.canUseConnectorForDelegatedProfile(userCtx(), REPO, c, FOLDER));
    }

    @Test
    void canUseConnector_falseWhenDelegatedButNoFolderScope() {
        // delegated=true but allowedFolderIds empty AND delegateAllFolders=false → no delegation
        ConnectorDefinition c = new ConnectorDefinition();
        c.setEnabled(true);
        c.setDelegated(true);
        c.setDelegateAllFolders(false);
        c.setAllowedFolderIds(null);
        assertFalse(svc.canUseConnectorForDelegatedProfile(userCtx(), REPO, c, FOLDER));
    }

    @Test
    void canUseConnector_trueWhenDelegateAllFoldersAndNoPrincipalRestriction() {
        ConnectorDefinition c = new ConnectorDefinition();
        c.setEnabled(true);
        c.setDelegated(true);
        c.setDelegateAllFolders(true);
        // No folder lookup needed — delegateAllFolders short-circuits
        assertTrue(svc.canUseConnectorForDelegatedProfile(userCtx(), REPO, c, FOLDER));
        verify(contentService, never()).getParent(any(), any());
    }

    @Test
    void canUseConnector_trueWhenTargetFolderInAllowedList() {
        ConnectorDefinition c = delegatedConnector();
        c.setAllowedFolderIds(List.of(FOLDER));
        // Self-match — no parent traversal needed
        assertTrue(svc.canUseConnectorForDelegatedProfile(userCtx(), REPO, c, FOLDER));
    }

    @Test
    void canUseConnector_trueWhenTargetIsDescendantOfAllowedFolder() {
        ConnectorDefinition c = delegatedConnector();
        c.setAllowedFolderIds(List.of(GRANDPARENT));
        // FOLDER → PARENT → GRANDPARENT chain
        Folder parent = mockFolder(PARENT);
        Folder grand = mockFolder(GRANDPARENT);
        when(contentService.getParent(REPO, FOLDER)).thenReturn(parent);
        when(contentService.getParent(REPO, PARENT)).thenReturn(grand);
        assertTrue(svc.canUseConnectorForDelegatedProfile(userCtx(), REPO, c, FOLDER));
    }

    @Test
    void canUseConnector_falseWhenTargetIsSibling_pathPrefixWouldFalseMatch() {
        // Path-prefix matching would falsely match "/A" against "/AB". This
        // test confirms we use ID-based ancestor traversal: GRANDPARENT
        // is NOT in the chain, so the connector must reject.
        ConnectorDefinition c = delegatedConnector();
        c.setAllowedFolderIds(List.of(GRANDPARENT));
        Folder unrelated = mockFolder("unrelated-root");
        when(contentService.getParent(REPO, FOLDER)).thenReturn(unrelated);
        assertFalse(svc.canUseConnectorForDelegatedProfile(userCtx(), REPO, c, FOLDER));
    }

    @Test
    void canUseConnector_failClosedWhenParentLookupThrows() {
        ConnectorDefinition c = delegatedConnector();
        c.setAllowedFolderIds(List.of(GRANDPARENT));
        when(contentService.getParent(REPO, FOLDER)).thenThrow(new RuntimeException("DAO failure"));
        assertFalse(svc.canUseConnectorForDelegatedProfile(userCtx(), REPO, c, FOLDER));
    }

    @Test
    void canUseConnector_terminatesOnAncestorCycle() {
        // Pathological: FOLDER → FOLDER (self-loop). Must not infinite-loop.
        ConnectorDefinition c = delegatedConnector();
        c.setAllowedFolderIds(List.of("never-matched"));
        Folder selfLoop = mockFolder(FOLDER);
        when(contentService.getParent(REPO, FOLDER)).thenReturn(selfLoop);
        assertFalse(svc.canUseConnectorForDelegatedProfile(userCtx(), REPO, c, FOLDER));
    }

    @Test
    void canUseConnector_trueWhenPrincipalRestrictionMatchesUser() {
        ConnectorDefinition c = delegatedConnector();
        c.setAllowedFolderIds(List.of(FOLDER));
        c.setAllowedPrincipalIds(List.of(USER));
        assertTrue(svc.canUseConnectorForDelegatedProfile(userCtx(), REPO, c, FOLDER));
    }

    @Test
    void canUseConnector_trueWhenPrincipalRestrictionMatchesGroup() {
        ConnectorDefinition c = delegatedConnector();
        c.setAllowedFolderIds(List.of(FOLDER));
        c.setAllowedPrincipalIds(List.of(GROUP));
        assertTrue(svc.canUseConnectorForDelegatedProfile(userCtx(), REPO, c, FOLDER));
    }

    @Test
    void canUseConnector_falseWhenPrincipalRestrictionExcludesUser() {
        ConnectorDefinition c = delegatedConnector();
        c.setAllowedFolderIds(List.of(FOLDER));
        c.setAllowedPrincipalIds(List.of("bob", "group:other"));
        assertFalse(svc.canUseConnectorForDelegatedProfile(userCtx(), REPO, c, FOLDER));
    }

    @Test
    void canUseConnector_failClosedOnGroupExpansionFailureWhenGroupRequired() {
        ConnectorDefinition c = delegatedConnector();
        c.setAllowedFolderIds(List.of(FOLDER));
        c.setAllowedPrincipalIds(List.of(GROUP));
        when(principalService.getGroupIdsContainingUser(REPO, USER))
                .thenThrow(new RuntimeException("ldap down"));
        assertFalse(svc.canUseConnectorForDelegatedProfile(userCtx(), REPO, c, FOLDER));
    }

    // ────────────────────────────────────────────────────────────────────
    // maxAncestorHops — configurable cap
    // ────────────────────────────────────────────────────────────────────

    @Test
    void maxAncestorHops_defaultsTo128_whenNoPropertyManager() {
        assertEquals(IngestAuthorizationService.DEFAULT_MAX_ANCESTOR_HOPS, svc.maxAncestorHops());
    }

    @Test
    void maxAncestorHops_usesPropertyValue_whenValid() {
        PropertyManager pm = mock(PropertyManager.class);
        when(pm.readValue(IngestAuthorizationService.MAX_HOPS_PROPERTY)).thenReturn("50");
        svc.setPropertyManager(pm);
        assertEquals(50, svc.maxAncestorHops());
    }

    @Test
    void maxAncestorHops_fallsBackToDefault_onInvalidProperty() {
        PropertyManager pm = mock(PropertyManager.class);
        when(pm.readValue(IngestAuthorizationService.MAX_HOPS_PROPERTY)).thenReturn("not-a-number");
        svc.setPropertyManager(pm);
        assertEquals(IngestAuthorizationService.DEFAULT_MAX_ANCESTOR_HOPS, svc.maxAncestorHops());
    }

    @Test
    void maxAncestorHops_fallsBackToDefault_onNonPositive() {
        PropertyManager pm = mock(PropertyManager.class);
        when(pm.readValue(IngestAuthorizationService.MAX_HOPS_PROPERTY)).thenReturn("0");
        svc.setPropertyManager(pm);
        assertEquals(IngestAuthorizationService.DEFAULT_MAX_ANCESTOR_HOPS, svc.maxAncestorHops());
    }

    @Test
    void maxAncestorHops_isCached_propertyReadOnce() {
        PropertyManager pm = mock(PropertyManager.class);
        when(pm.readValue(IngestAuthorizationService.MAX_HOPS_PROPERTY)).thenReturn("42");
        svc.setPropertyManager(pm);
        svc.maxAncestorHops();
        svc.maxAncestorHops();
        svc.maxAncestorHops();
        verify(pm, times(1)).readValue(IngestAuthorizationService.MAX_HOPS_PROPERTY);
    }

    @Test
    void isFolderInAllowedScope_capExceeded_returnsFalseAndLogsWarn() {
        // Build a long chain F0 -> F1 -> ... -> F49, with cap=5 and allowed=F30.
        // Walk should give up at hop 5 without false-positive.
        PropertyManager pm = mock(PropertyManager.class);
        when(pm.readValue(IngestAuthorizationService.MAX_HOPS_PROPERTY)).thenReturn("5");
        svc.setPropertyManager(pm);

        for (int i = 0; i < 50; i++) {
            Folder f = mockFolder("F" + (i + 1));
            when(contentService.getParent(REPO, "F" + i)).thenReturn(f);
        }
        boolean result = svc.isFolderInAllowedScope(REPO, "F0", List.of("F30"));
        assertFalse(result, "ancestor walk should fail-closed when cap is reached without finding allowed ancestor");
    }

    // ────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────

    private CallContext adminCtx() {
        CallContext ctx = mock(CallContext.class);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(ctx.getUsername()).thenReturn("admin");
        when(ctx.getRepositoryId()).thenReturn(REPO);
        return ctx;
    }

    private CallContext userCtx() {
        CallContext ctx = mock(CallContext.class);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.FALSE);
        when(ctx.getUsername()).thenReturn(USER);
        when(ctx.getRepositoryId()).thenReturn(REPO);
        return ctx;
    }

    private Folder mockFolder(String id) {
        Folder f = mock(Folder.class);
        when(f.getId()).thenReturn(id);
        return f;
    }

    private Acl aclWith(Ace... aces) {
        Acl acl = mock(Acl.class);
        List<Ace> list = new ArrayList<>(List.of(aces));
        when(acl.getAllAces()).thenReturn(list);
        return acl;
    }

    private Ace ace(String principal, String permission) {
        Ace a = mock(Ace.class);
        when(a.getPrincipalId()).thenReturn(principal);
        when(a.getPermissions()).thenReturn(List.of(permission));
        return a;
    }

    private ConnectorDefinition delegatedConnector() {
        ConnectorDefinition c = new ConnectorDefinition();
        c.setConnectorId("conn-1");
        c.setEnabled(true);
        c.setDelegated(true);
        c.setDelegateAllFolders(false);
        c.setAllowedFolderIds(List.of(FOLDER));
        return c;
    }
}
