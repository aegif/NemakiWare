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
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.lock.ThreadLockService;

import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
 * <p>It took the generic delete, so an administrator deleted the built-in {@code admin} with one
 * browser-binding request (measured on a 3.4.0 build, 2026-10-09) — the guard the three user
 * endpoints were given did not cover it — and the deleted user's id stayed in every group that
 * listed it. Driven through {@code ObjectServiceInternalImpl.deleteObjectInternal}, the method
 * {@code deleteObject} hands every binding's request to, over the real {@code ContentServiceImpl}.
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
}
