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
package jp.aegif.nemaki.api.v1.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jp.aegif.nemaki.api.v1.model.request.UserRequest;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.constant.CallContextKey;

/**
 * The user API stamps each group whose membership it rewrites (3.4.1, R148 (d)).
 *
 * <p>Its membership writes used the plain update, which leaves a group's modifier and modified
 * as they were; the change log takes its time from {@code modified}, so a membership change was
 * dated to the group's previous edit. Until 3.4.1 these writes never reached the store — the
 * listed groups carried no revision — and 3.4.1's list read made them work.
 */
class TheUserApiStampsTheGroupsItRewritesTest {

    private static final String REPO = "bedroom";

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("a PUT with groups stamps every group it adds the user to or removes the user from")
    void theRewrittenGroupsAreStamped() throws Exception {
        ContentService contentService = mock(ContentService.class);
        UserItem alice = mock(UserItem.class);
        when(alice.getSubTypeProperties()).thenReturn(new ArrayList<>());
        when(contentService.getUserItemById(REPO, "alice")).thenReturn(alice);
        GroupItem eng = new GroupItem("doc-eng", "nemaki:group", "eng", "Engineering",
                new ArrayList<>(), new ArrayList<>());
        GroupItem ops = new GroupItem("doc-ops", "nemaki:group", "ops", "Operations",
                new ArrayList<>(List.of("alice")), new ArrayList<>());
        GroupItem untouched = new GroupItem("doc-qa", "nemaki:group", "qa", "QA",
                new ArrayList<>(), new ArrayList<>());
        when(contentService.getGroupItems(REPO)).thenReturn(new ArrayList<>(List.of(eng, ops, untouched)));

        UserResource resource = new UserResource();
        set(resource, "contentService", contentService);
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext ctx = mock(CallContext.class);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(request.getAttribute("CallContext")).thenReturn(ctx);
        set(resource, "httpRequest", request);

        UserRequest put = new UserRequest();
        put.setUserName("alice");
        put.setGroups(List.of("eng"));
        resource.updateUser(REPO, "alice", put);

        ArgumentCaptor<GroupItem> stamped = ArgumentCaptor.forClass(GroupItem.class);
        verify(contentService, times(2)).applyGroupUpdate(eq(REPO), stamped.capture(), anyString());
        assertEquals(Set.of("eng", "ops"),
                stamped.getAllValues().stream().map(GroupItem::getGroupId).collect(Collectors.toSet()));
        verify(contentService, never()).update(any(), eq(REPO), any(GroupItem.class));
    }
}
