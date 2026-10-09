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

import jp.aegif.nemaki.businesslogic.impl.ContentServiceImpl;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.lock.ThreadLockService;

import org.apache.chemistry.opencmis.commons.exceptions.CmisConstraintException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CMIS {@code deleteObject} on a user's item deletes the user as a user (R115).
 *
 * <p>It took the generic delete: an administrator deleted a user's item with one browser-binding
 * request on a 3.4.0 build (measured 2026-10-09, with a test user), nothing on that path singles
 * out the built-in administrator's item (read), and the deleted user's id stayed in every group
 * that listed it. Driven through the {@code Content}-taking {@code deleteObjectInternal}, over the
 * real {@code ContentServiceImpl}: the objectId overload every binding reaches reads the content
 * with {@code getContent} — which makes a {@code nemaki:user} document a {@code UserItem} — and
 * hands it to this one; that read is not driven here.
 */
class CmisDeleteObjectDeletesAUserAsAUserTest {

    private static final String REPO = "bedroom";
    private static final String ACTOR = "bob";

    private ContentServiceImpl service;
    private ObjectServiceInternalImpl objects;
    private CallContext callContext;
    private final Map<String, UserItem> items = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = spy(new ContentServiceImpl());
        doNothing().when(service).delete(any(), any(), any(), any());
        doNothing().when(service).deleteDocument(any(), any(), any(), any(), any());
        doReturn(null).when(service).update(any(), any(), any());
        doReturn(List.of()).when(service).getParentChildChildIds(eq(REPO), anyString());
        for (String id : List.of("admin", "solr", "mcp-service", "alice", ACTOR)) {
            UserItem item = new UserItem(id + "-doc", "nemaki:user", id, id, null, true, null);
            items.put(id, item);
            doReturn(item).when(service).getUserItemById(REPO, id);
            doReturn(List.of()).when(service).getGroupIdsDirectlyContainingUser(REPO, id);
        }

        objects = new ObjectServiceInternalImpl();
        objects.setContentService(service);
        objects.setExceptionService(mock(ExceptionService.class));
        ThreadLockService locks = mock(ThreadLockService.class);
        when(locks.getWriteLock(anyString(), anyString())).thenReturn(new ReentrantLock());
        objects.setThreadLockService(locks);
        objects.setNemakiCachePool(mock(NemakiCachePool.class, Answers.RETURNS_DEEP_STUBS));

        callContext = mock(CallContext.class);
        when(callContext.getUsername()).thenReturn(ACTOR);
    }

    /** A document {@code parentDocId} is the parentChild parent of. */
    private String childOf(String parentDocId) {
        String childId = "child-of-" + parentDocId;
        Document child = new Document();
        child.setId(childId);
        doReturn(List.of(childId)).when(service).getParentChildChildIds(REPO, parentDocId);
        doReturn(child).when(service).getContent(REPO, childId);
        return childId;
    }

    @Test
    @DisplayName("deleteObject on the built-in accounts' items and on the acting user's own is refused")
    void protectedUsersAreNotDeletedThroughCmis() {
        for (String id : List.of("admin", "solr", "mcp-service", ACTOR)) {
            assertThrows(CmisPermissionDeniedException.class,
                    () -> objects.deleteObjectInternal(callContext, REPO, items.get(id), false, false),
                    "CMIS deleteObject deleted " + id);
            verify(service, never()).delete(any(), eq(REPO), eq(id + "-doc"), any());
        }
    }

    @Test
    @DisplayName("deleteObject on another user's item deletes it as a user — memberships stripped first")
    void anotherUserIsDeletedAsAUser() {
        objects.deleteObjectInternal(callContext, REPO, items.get("alice"), false, false);

        // The canonical delete, not the generic one: it looks for the groups that list the user.
        verify(service).getGroupIdsDirectlyContainingUser(REPO, "alice");
        verify(service).delete(any(), eq(REPO), eq("alice-doc"), eq(false));
    }

    @Test
    @DisplayName("a refused user's parentChild children are not deleted — the refusal comes before the cascade")
    void aRefusedUserKeepsItsChildren() {
        String adminsChild = childOf("admin-doc");
        assertThrows(CmisPermissionDeniedException.class,
                () -> objects.deleteObjectInternal(callContext, REPO, items.get("admin"), false, false));
        verify(service, never()).deleteDocument(any(), eq(REPO), eq(adminsChild), any(), any());

        // The control: the same arrangement under a user that may be deleted does reach the child.
        String alicesChild = childOf("alice-doc");
        objects.deleteObjectInternal(callContext, REPO, items.get("alice"), false, false);
        verify(service).deleteDocument(any(), eq(REPO), eq(alicesChild), any(), any());
    }

    @Test
    @DisplayName("the user's deletion and its membership strip are written as the caller, not as the system")
    void aUserDeletedThroughCmisIsDeletedByTheCaller() {
        GroupItem group = new GroupItem("g1-doc", "nemaki:group", "g1", "g1",
                new ArrayList<>(List.of("alice", "carol")), new ArrayList<>());
        doReturn(List.of("g1")).when(service).getGroupIdsDirectlyContainingUser(REPO, "alice");
        doReturn(group).when(service).getGroupItemByIdFresh(REPO, "g1");

        objects.deleteObjectInternal(callContext, REPO, items.get("alice"), false, false);

        // The change events, the webhooks and the archive take the actor from this context; the
        // generic delete this replaces passed the caller's.
        verify(service).update(same(callContext), eq(REPO),
                argThat(written -> written instanceof GroupItem g && g.getUsers().equals(List.of("carol"))));
        verify(service).delete(same(callContext), eq(REPO), eq("alice-doc"), eq(false));
    }

    @Test
    @DisplayName("a second document for a user that resolves elsewhere is refused — no strip, no delete, no cascade")
    void aSecondDocumentForTheSameUserIsNotDeletedAsTheUser() {
        UserItem second = new UserItem("carol-second-doc", "nemaki:user", "carol", "carol", null, false, null);
        UserItem resolved = new UserItem("carol-doc", "nemaki:user", "carol", "carol", null, false, null);
        doReturn(resolved).when(service).getUserItemById(REPO, "carol");
        doReturn(List.of()).when(service).getGroupIdsDirectlyContainingUser(REPO, "carol");
        String child = childOf("carol-second-doc");

        assertThrows(CmisConstraintException.class,
                () -> objects.deleteObjectInternal(callContext, REPO, second, false, false));

        // Deleting it as the user would strip the memberships of the user who lives on.
        verify(service, never()).getGroupIdsDirectlyContainingUser(REPO, "carol");
        verify(service, never()).delete(any(), eq(REPO), eq("carol-second-doc"), any());
        verify(service, never()).delete(any(), eq(REPO), eq("carol-doc"), any());
        verify(service, never()).deleteDocument(any(), eq(REPO), eq(child), any(), any());
    }

    @Test
    @DisplayName("a user item whose user id resolves to no user is refused, not answered as deleted")
    void aUserItemThatResolvesToNoUserIsNotDeleted() {
        UserItem unresolved = new UserItem("dave-doc", "nemaki:user", "dave", "dave", null, false, null);
        doReturn(null).when(service).getUserItemById(REPO, "dave");
        doReturn(List.of()).when(service).getGroupIdsDirectlyContainingUser(REPO, "dave");

        assertThrows(CmisConstraintException.class,
                () -> objects.deleteObjectInternal(callContext, REPO, unresolved, false, false));

        verify(service, never()).getGroupIdsDirectlyContainingUser(REPO, "dave");
        verify(service, never()).delete(any(), eq(REPO), eq("dave-doc"), any());
    }
}
