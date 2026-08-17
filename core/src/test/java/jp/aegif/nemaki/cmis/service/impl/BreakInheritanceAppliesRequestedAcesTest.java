/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.cmis.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.CmisExtensionElement;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlEntryImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlPrincipalDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.CmisExtensionElementImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.CompileService;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.model.Ace;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.util.cache.CacheService;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.lock.ThreadLockService;

/**
 * What breaking inheritance stores, for each shape of request the product actually sends.
 *
 * <h2>The two failures this sits between</h2>
 *
 * <p>The branch originally ignored {@code acl.getAces()} completely and copied the object's
 * current effective ACL into the new local list. A caller that said "detach, and here is the ACL I
 * want" got "detach, and keep what you had", with a success response — a removal that was not
 * performed and not reported.
 *
 * <p>The first attempt at fixing that filtered the request to {@code direct=true} entries. That
 * broke the product's own Break Inheritance button, which sends the effective ACL back UNCHANGED —
 * inherited entries included, flagged {@code direct=false} — so detaching a folder would have
 * silently dropped every inherited grant. One silent over-permission traded for one silent
 * lockout.
 *
 * <p>The rule that satisfies both: on a break, every requested entry is stored, whatever its
 * direct flag says. After the break there is no inheritance, so the flag describes where an entry
 * came FROM, not where it belongs. Without a break the direct-only filter stays, because inherited
 * entries are not this object's to store while it still inherits.
 *
 * <p>These tests drive the real {@code applyAcl} and read what it hands to
 * {@code contentService.updateInternal} — the ACL that will actually be persisted.
 */
class BreakInheritanceAppliesRequestedAcesTest {

    private AclServiceImpl service;
    private ContentService contentService;
    private Content content;

    private static final String OBJECT_ID = "folder-1";

    @BeforeEach
    void setUp() {
        service = new AclServiceImpl();
        contentService = mock(ContentService.class);
        ExceptionService exceptions = mock(ExceptionService.class);
        CompileService compile = mock(CompileService.class);
        TypeManager typeManager = mock(TypeManager.class);
        ThreadLockService locks = mock(ThreadLockService.class);
        NemakiCachePool pool = mock(NemakiCachePool.class);
        CacheService cache = mock(CacheService.class);

        content = mock(Content.class);
        lenient().when(content.getId()).thenReturn(OBJECT_ID);
        lenient().when(content.getObjectType()).thenReturn("cmis:folder");
        lenient().when(content.isFolder()).thenReturn(true);

        lenient().when(contentService.getContent(anyString(), eq(OBJECT_ID))).thenReturn(content);
        lenient().when(contentService.getAclInheritedWithDefault(anyString(), any()))
                .thenReturn(true);
        // The object currently inherits read for "inherited-user" and has a local ACE for
        // "local-user": the effective ACL the UI would have loaded and sent back.
        jp.aegif.nemaki.model.Acl current = new jp.aegif.nemaki.model.Acl();
        current.getLocalAces().add(new Ace("local-user", List.of("cmis:read"), true));
        current.getInheritedAces().add(new Ace("inherited-user", List.of("cmis:read"), false));
        lenient().when(contentService.calculateAcl(anyString(), any())).thenReturn(current);

        TypeDefinition td = mock(TypeDefinition.class);
        lenient().when(td.isControllableAcl()).thenReturn(true);
        lenient().when(typeManager.getTypeDefinition(anyString(), any(Content.class)))
                .thenReturn(td);

        lenient().when(locks.getWriteLock(anyString(), anyString()))
                .thenAnswer(inv -> new ReentrantReadWriteLock().writeLock());
        lenient().when(locks.getReadLock(anyString(), anyString()))
                .thenAnswer(inv -> new ReentrantReadWriteLock().readLock());
        lenient().when(pool.get(anyString())).thenReturn(cache);

        service.setContentService(contentService);
        service.setExceptionService(exceptions);
        service.setCompileService(compile);
        service.setTypeManager(typeManager);
        service.setThreadLockService(locks);
        service.setNemakiCachePool(pool);
        // convertSystemPrinciaplId resolves GROUP_EVERYONE / anonymous through this map.
        jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap repoMap =
                mock(jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap.class);
        jp.aegif.nemaki.cmis.factory.info.RepositoryInfo info =
                mock(jp.aegif.nemaki.cmis.factory.info.RepositoryInfo.class);
        lenient().when(info.getPrincipalIdAnyone()).thenReturn("GROUP_EVERYONE");
        lenient().when(info.getPrincipalIdAnonymous()).thenReturn("anonymous");
        lenient().when(repoMap.get(anyString())).thenReturn(info);
        service.setRepositoryInfoMap(repoMap);
    }

    /** An ACL carrying the given (principal, direct) entries, plus the break extension. */
    private static Acl requestWithBreak(boolean breaking, String[][] entries) {
        List<org.apache.chemistry.opencmis.commons.data.Ace> aces = new ArrayList<>();
        for (String[] e : entries) {
            AccessControlEntryImpl ace = new AccessControlEntryImpl(
                    new AccessControlPrincipalDataImpl(e[0]), List.of("cmis:read"));
            ace.setDirect(Boolean.parseBoolean(e[1]));
            aces.add(ace);
        }
        AccessControlListImpl acl = new AccessControlListImpl(aces);
        if (breaking) {
            List<CmisExtensionElement> ext = new ArrayList<>();
            ext.add(new CmisExtensionElementImpl(null, "inherited", null, "false"));
            acl.setExtensions(ext);
        }
        return acl;
    }

    /** The principals of the ACL that applyAcl actually persisted. */
    private List<String> storedPrincipals(Acl request) {
        service.applyAcl(mock(CallContext.class), "bedroom", OBJECT_ID, request,
                AclPropagation.PROPAGATE);
        ArgumentCaptor<jp.aegif.nemaki.model.Acl> captor =
                ArgumentCaptor.forClass(jp.aegif.nemaki.model.Acl.class);
        org.mockito.Mockito.verify(content).setAcl(captor.capture());
        List<String> out = new ArrayList<>();
        for (Ace a : captor.getValue().getLocalAces()) {
            out.add(a.getPrincipalId());
        }
        return out;
    }

    @Test
    @DisplayName("UI の継承ブレーク (実効 ACL をそのまま送る) は継承分も残す")
    void theUiBreakKeepsInheritedEntries() {
        // Exactly what PermissionManagement.handleBreakInheritance sends: the loaded ACL,
        // unchanged, inherited entries still flagged direct=false. Dropping those would turn
        // "detach this folder" into "revoke everyone who had access through the parent".
        List<String> stored = storedPrincipals(requestWithBreak(true, new String[][]{
                {"local-user", "true"}, {"inherited-user", "false"}}));

        assertTrue(stored.contains("inherited-user"),
                "an inherited entry sent back with the break must survive as a local one:"
                        + " after the break there is no ancestor to inherit it from. Stored: "
                        + stored);
        assertTrue(stored.contains("local-user"));
        assertEquals(2, stored.size(), "and nothing else: " + stored);
    }

    @Test
    @DisplayName("継承ブレークで要求された ACE を捨てない (剥奪が効く)")
    void theBreakAppliesTheRequestedList() {
        // The caller detaches AND removes inherited-user in one call. The original code answered
        // success and kept inherited-user — the silent over-permission.
        List<String> stored = storedPrincipals(requestWithBreak(true, new String[][]{
                {"local-user", "true"}}));

        assertEquals(List.of("local-user"), stored,
                "the requested list IS the resulting ACL; keeping the removed principal would be"
                        + " a revocation that was reported as done and was not");
    }

    @Test
    @DisplayName("空リスト + ブレークは空 ACL として扱う (剥奪を取り消さない)")
    void anEmptyListWithABreakIsHonoured() {
        // Reachable for real: the add/remove overload computes the resulting list, so removing an
        // object's last ACE while detaching it arrives here empty. Reading that as "keep current"
        // would restore the very entry being removed.
        List<String> stored = storedPrincipals(requestWithBreak(true, new String[][]{}));

        assertEquals(List.of(), stored,
                "an empty request is a statement, not an absence — undoing it would be the same"
                        + " silent over-permission in a different shape");
    }

    @Test
    @DisplayName("実効 ACL は principal ごとに 1 件 (= ブレークで重複を保存しえない)")
    void theEffectiveAclHasOneEntryPerPrincipal() {
        // "Store every requested entry on a break" is only safe because the list the UI sends
        // back cannot contain a principal twice. That is not applyAcl's doing — it is
        // Acl.getMergedAces, which keys by principal and lets the local entry win. If that ever
        // stops holding, a principal granted locally AND inherited would arrive as two entries
        // and be stored as two local ACEs with whatever permissions each carried, and the read
        // path (also keyed by principal) would silently keep only one of them.
        jp.aegif.nemaki.model.Acl acl = new jp.aegif.nemaki.model.Acl();
        acl.getLocalAces().add(new Ace("dup-user", List.of("cmis:read", "cmis:write"), true));
        acl.getInheritedAces().add(new Ace("dup-user", List.of("cmis:read"), false));
        acl.getInheritedAces().add(new Ace("other-user", List.of("cmis:read"), false));

        List<String> principals = new ArrayList<>();
        List<String> dupPermissions = new ArrayList<>();
        for (Ace a : acl.getMergedAces()) {
            principals.add(a.getPrincipalId());
            if ("dup-user".equals(a.getPrincipalId())) {
                dupPermissions.addAll(a.getPermissions());
            }
        }

        assertEquals(1, java.util.Collections.frequency(principals, "dup-user"),
                "one entry per principal, or the break would store duplicates: " + principals);
        assertTrue(dupPermissions.contains("cmis:write"),
                "and it must be the LOCAL entry that survives — keeping the inherited one would"
                        + " downgrade a locally granted permission on every break");
    }

    @Test
    @DisplayName("ブレーク無しでは継承フラグ付き ACE を保存しない")
    void withoutABreakInheritedEntriesAreNotStored() {
        // Still inheriting, so an inherited entry is not this object's to store: writing it
        // locally would freeze a copy that stops tracking the ancestor.
        List<String> stored = storedPrincipals(requestWithBreak(false, new String[][]{
                {"local-user", "true"}, {"inherited-user", "false"}}));

        assertEquals(List.of("local-user"), stored,
                "only direct entries are stored while inheritance is in effect: " + stored);
    }
}
