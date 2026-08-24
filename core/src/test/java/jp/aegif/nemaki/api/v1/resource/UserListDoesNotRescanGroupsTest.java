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

import jp.aegif.nemaki.api.v1.model.response.UserListResponse;
import jp.aegif.nemaki.api.v1.model.response.UserResponse;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.constant.CallContextKey;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Response;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Listing users reads the groups ONCE, not once per user.
 *
 * <h2>How this was found</h2>
 *
 * <p>Not by a unit test — by timing the running deployment after the Playwright suite reported
 * five reproducible failures that all needed the user list. With 117 users the endpoint took
 * about 4.6 seconds PER USER and never answered inside any client's timeout, so the listing, its
 * pagination, its HATEOAS links, group member management and the admin-privilege screens all
 * failed together and looked like five unrelated faults.
 *
 * <p>The shape is an ordinary N+1: membership was computed by fetching every group in the
 * repository once for each user. It is invisible on a development repository with three users
 * and fatal on one that has been running tests for a week — which is why the test asserts the
 * NUMBER OF READS rather than the elapsed time. A timing assertion would be flaky and would not
 * say what went wrong.
 */
class UserListDoesNotRescanGroupsTest {

    private static final String REPO = "bedroom";

    private static UserResource resourceWith(ContentService contentService) throws Exception {
        UserResource resource = new UserResource();
        set(resource, "contentService", contentService);

        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext ctx = mock(CallContext.class);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(request.getAttribute("CallContext")).thenReturn(ctx);
        set(resource, "httpRequest", request);
        return resource;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static UserItem user(String id) {
        UserItem item = new UserItem();
        item.setUserId(id);
        item.setName(id);
        item.setSubTypeProperties(new ArrayList<>());
        return item;
    }

    private static GroupItem group(String id, String... members) {
        GroupItem item = new GroupItem();
        item.setGroupId(id);
        item.setName(id);
        item.setUsers(List.of(members));
        return item;
    }

    @Test
    @DisplayName("the groups are fetched once for the whole page")
    void groupsAreFetchedOnce() throws Exception {
        List<UserItem> users = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            users.add(user("user" + i));
        }
        ContentService contentService = mock(ContentService.class);
        when(contentService.getUserItems(anyString())).thenReturn(users);
        when(contentService.getGroupItems(anyString()))
                .thenReturn(List.of(group("admin", "user0"), group("everyone", "user1")));

        resourceWith(contentService).listUsers(REPO, 100, 0);

        // Once, not twenty times. Every extra call is a full read of every group in the
        // repository, and the cost is the product of the two collections.
        verify(contentService, times(1)).getGroupItems(REPO);
    }

    @Test
    @DisplayName("the memberships are still right — the control")
    void membershipsAreUnchanged() throws Exception {
        // Without this, returning no groups at all would pass the test above while quietly
        // making every user look like a non-member of everything, including admin.
        ContentService contentService = mock(ContentService.class);
        when(contentService.getUserItems(anyString()))
                .thenReturn(List.of(user("alice"), user("bob"), user("carol")));
        when(contentService.getGroupItems(anyString())).thenReturn(List.of(
                group("admin", "alice"),
                group("editors", "alice", "bob")));

        Response response = resourceWith(contentService).listUsers(REPO, 100, 0);
        UserListResponse body = (UserListResponse) response.getEntity();

        UserResponse alice = find(body, "alice");
        UserResponse bob = find(body, "bob");
        UserResponse carol = find(body, "carol");

        assertEquals(List.of("admin", "editors"), alice.getGroups());
        assertEquals(List.of("editors"), bob.getGroups());
        assertTrue(carol.getGroups().isEmpty(), "a user in no group was given groups");
        // isAdmin is derived from the same memberships, so it has to survive the change too.
        assertEquals(Boolean.TRUE, alice.getIsAdmin());
        assertEquals(Boolean.FALSE, bob.getIsAdmin());
    }

    private static UserResponse find(UserListResponse body, String userId) {
        for (UserResponse user : body.getUsers()) {
            if (userId.equals(user.getUserId())) {
                return user;
            }
        }
        throw new AssertionError("no user '" + userId + "' in the response");
    }
}
