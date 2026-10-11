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
package jp.aegif.nemaki.businesslogic.impl;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.ProtectedPrincipals;
import jp.aegif.nemaki.businesslogic.ProtectedPrincipals.Reason;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The canonical user writes refuse the built-in accounts and the acting user (R115).
 *
 * <p>{@code ContentService.deleteUser} is where every user deletion ends — the three user
 * endpoints, the bulk batch and directory sync; CMIS is decided by {@code checkUserItemDeletion},
 * whose decision is the only one {@code deleteUserItem} carries out — so the built-in administrator
 * and the service accounts are refused there whoever asks, and the acting user when a request
 * names one.
 * {@code applyUserUpdate} keeps the built-in administrator an administrator.
 *
 * <p>The service is real; only its reads and writes of the store are stubbed.
 */
class ProtectedPrincipalsAreNeverDeletedOrDemotedTest {

    private static final String REPO = "bedroom";

    private ContentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = spy(new ContentServiceImpl());
        doNothing().when(service).delete(any(), any(), any(), any());
        doReturn(null).when(service).update(any(), any(), any());
        for (String id : List.of("admin", "solr", "mcp-service", "indexer", "alice", "bob")) {
            UserItem user = mock(UserItem.class);
            when(user.getId()).thenReturn(id + "-doc");
            when(user.getUserId()).thenReturn(id);
            doReturn(user).when(service).getUserItemById(REPO, id);
            doReturn(List.of()).when(service).getGroupIdsDirectlyContainingUser(REPO, id);
        }
    }

    private void solrIs(String solrUserId) throws Exception {
        PropertyManager pm = mock(PropertyManager.class);
        when(pm.readValue(PropertyKey.SOLR_NEMAKI_USERID)).thenReturn(solrUserId);
        Field field = ContentServiceImpl.class.getDeclaredField("propertyManager");
        field.setAccessible(true);
        field.set(service, pm);
    }

    private Reason refusedDeleting(String userId, String actor) {
        ProtectedPrincipals.RefusedException refused = assertThrows(ProtectedPrincipals.RefusedException.class,
                () -> service.deleteUser(REPO, userId, actor), userId + " was deleted");
        verify(service, never()).delete(any(), eq(REPO), eq(userId + "-doc"), any());
        return refused.reason();
    }

    @Test
    @DisplayName("the built-in administrator and the service accounts are never deleted, whoever asks")
    void theBuiltInAccountsAreNeverDeleted() {
        assertEquals(Reason.BUILT_IN_ADMIN, refusedDeleting("admin", "bob"));
        assertEquals(Reason.BUILT_IN_SERVICE_ACCOUNT, refusedDeleting("solr", "bob"));
        assertEquals(Reason.BUILT_IN_SERVICE_ACCOUNT, refusedDeleting("mcp-service", "bob"));
        // And by a path that acts for nobody — directory sync.
        assertThrows(ProtectedPrincipals.RefusedException.class, () -> service.deleteUser(REPO, "admin"));
        verify(service, never()).delete(any(), eq(REPO), eq("admin-doc"), any());
    }

    @Test
    @DisplayName("Solr's account is the one this deployment configures")
    void solrsAccountIsTheConfiguredOne() throws Exception {
        solrIs("indexer");
        assertEquals(Reason.BUILT_IN_SERVICE_ACCOUNT, refusedDeleting("indexer", "bob"));
    }

    @Test
    @DisplayName("the acting user does not delete itself; another user is deleted as before")
    void theActorDoesNotDeleteItself() {
        assertEquals(Reason.ACTOR_ITSELF, refusedDeleting("alice", "alice"));
        assertTrue(service.deleteUser(REPO, "alice", "bob"));
        verify(service).delete(any(), eq(REPO), eq("alice-doc"), eq(false));
    }

    @Test
    @DisplayName("CMIS's decision refuses the built-in accounts and the acting user")
    void theUserItemDecisionRefusesProtectedUsers() {
        CallContext bob = mock(CallContext.class);
        when(bob.getUsername()).thenReturn("bob");
        for (String id : List.of("admin", "solr", "mcp-service", "bob")) {
            UserItem item = service.getUserItemById(REPO, id);
            assertThrows(ProtectedPrincipals.RefusedException.class,
                    () -> service.checkUserItemDeletion(bob, REPO, item), id + " may be deleted");
        }
    }

    @Test
    @DisplayName("CMIS's delete of a user item carries out only a decision this service made — skipping the decision deletes nothing")
    void aDeletionThisServiceDidNotDecideIsNotCarriedOut() {
        ContentService.UserItemDeletion madeUp = new ContentService.UserItemDeletion() {
        };
        assertThrows(IllegalArgumentException.class, () -> service.deleteUserItem(madeUp, false));
        assertThrows(IllegalArgumentException.class, () -> service.deleteUserItem(null, false));
        verify(service, never()).delete(any(), any(), any(), any());
    }

    @Test
    @DisplayName("the built-in administrator is never saved without its admin flag")
    void theBuiltInAdministratorStaysAnAdministrator() {
        UserItem admin = mock(UserItem.class);
        when(admin.getUserId()).thenReturn("admin");
        when(admin.isAdmin()).thenReturn(false);
        ProtectedPrincipals.RefusedException refused = assertThrows(ProtectedPrincipals.RefusedException.class,
                () -> service.applyUserUpdate(REPO, admin, "bob"), "the built-in administrator was demoted");
        assertEquals(Reason.BUILT_IN_ADMIN, refused.reason());
        verify(service, never()).update(any(), eq(REPO), eq(admin));

        // Its other updates — a password, a name — go through.
        when(admin.isAdmin()).thenReturn(true);
        service.applyUserUpdate(REPO, admin, "bob");
        verify(service).update(any(), eq(REPO), eq(admin));
    }
}
