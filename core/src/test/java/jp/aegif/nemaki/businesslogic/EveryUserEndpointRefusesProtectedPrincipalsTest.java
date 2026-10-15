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
package jp.aegif.nemaki.businesslogic;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Response;

import jp.aegif.nemaki.api.v1.exception.ApiException;
import jp.aegif.nemaki.api.v1.resource.UserResource;
import jp.aegif.nemaki.businesslogic.impl.ContentServiceImpl;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.rest.UserItemResource;
import jp.aegif.nemaki.rest.controller.UserController;
import jp.aegif.nemaki.util.constant.CallContextKey;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * None of the three user endpoints deletes or demotes a protected user (R115).
 *
 * <p>The bulk batch refused the built-in {@code admin}, the service accounts and the acting
 * user; legacy {@code /rest}, Spring MVC and Jersey did not, so {@code admin} could be deleted
 * one request at a time. Each endpoint is driven as a request reaches it, by an administrator
 * ("bob"), over the real {@code ContentServiceImpl} — the guard is the canonical method's, and
 * what is measured is that each endpoint reaches it with the acting user and answers a refusal
 * as one.
 */
class EveryUserEndpointRefusesProtectedPrincipalsTest {

    private static final String REPO = "bedroom";
    private static final String ACTOR = "bob";

    private ContentServiceImpl service;
    private HttpServletRequest request;
    private final Map<String, UserItem> users = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = spy(new ContentServiceImpl());
        doNothing().when(service).delete(any(), any(), any(), any());
        doReturn(null).when(service).update(any(), any(), any());
        for (String id : List.of("admin", "solr", "mcp-service", "alice", ACTOR)) {
            // Real items: the legacy endpoint flips the flag on the object and reads it back.
            UserItem user = new UserItem(id + "-doc", "nemaki:user", id, id, null, !"mcp-service".equals(id), null);
            users.put(id, user);
            doReturn(user).when(service).getUserItemById(REPO, id);
            doReturn(List.of()).when(service).getGroupIdsDirectlyContainingUser(REPO, id);
        }

        CallContext callContext = mock(CallContext.class);
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(true);
        when(callContext.getUsername()).thenReturn(ACTOR);
        when(callContext.getRepositoryId()).thenReturn(REPO);
        request = mock(HttpServletRequest.class);
        when(request.getAttribute("CallContext")).thenReturn(callContext);
        when(request.getHeader("X-Requested-With")).thenReturn("XMLHttpRequest");
    }

    private static void set(Object target, String name, Object value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException next) {
                // a superclass may declare it
            }
        }
        throw new NoSuchFieldException(name);
    }

    private void neverDeleted(String id) {
        verify(service, never()).delete(any(), eq(REPO), eq(id + "-doc"), any());
    }

    // ---- Jersey /api/v1/cmis ----

    private UserResource jersey() throws Exception {
        UserResource resource = new UserResource();
        set(resource, "contentService", service);
        set(resource, "httpRequest", request);
        return resource;
    }

    @Test
    @DisplayName("Jersey refuses to delete the built-in accounts and the acting user, and deletes another")
    void jerseyRefusesProtectedUsers() throws Exception {
        for (String id : List.of("admin", "solr", "mcp-service", ACTOR)) {
            ApiException refused = assertThrows(ApiException.class, () -> jersey().deleteUser(REPO, id),
                    "Jersey deleted " + id);
            assertEquals(403, refused.getStatus(), id + ": " + refused.getMessage());
            neverDeleted(id);
        }
        assertEquals(204, jersey().deleteUser(REPO, "alice").getStatus());
        verify(service).delete(any(), eq(REPO), eq("alice-doc"), eq(false));
    }

    // ---- Spring MVC /api/v1/repo ----

    private UserController spring() throws Exception {
        UserController controller = new UserController();
        set(controller, "contentService", service);
        set(controller, "httpRequest", request);
        return controller;
    }

    @Test
    @DisplayName("Spring MVC refuses to delete the built-in accounts and the acting user, and deletes another")
    void springRefusesProtectedUsers() throws Exception {
        for (String id : List.of("admin", "solr", "mcp-service", ACTOR)) {
            ResponseEntity<Map<String, Object>> response = spring().deleteUser(REPO, id);
            assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(), id + ": " + response.getBody());
            neverDeleted(id);
        }
        assertEquals(HttpStatus.OK, spring().deleteUser(REPO, "alice").getStatusCode());
        verify(service).delete(any(), eq(REPO), eq("alice-doc"), eq(false));
    }

    // ---- legacy /rest ----

    private UserItemResource legacy() throws Exception {
        UserItemResource resource = new UserItemResource();
        set(resource, "contentService", service);
        return resource;
    }

    @Test
    @DisplayName("legacy /rest refuses to delete the built-in accounts and the acting user, and deletes another")
    void legacyRefusesToDeleteProtectedUsers() throws Exception {
        for (String id : List.of("admin", "solr", "mcp-service", ACTOR)) {
            String answer = legacy().delete(REPO, id, request);
            assertTrue(answer.contains("\"failure\"") && answer.contains("is never deleted"), id + ": " + answer);
            neverDeleted(id);
        }
        String answer = legacy().delete(REPO, "alice", request);
        assertTrue(answer.contains("\"success\""), answer);
        verify(service).delete(any(), eq(REPO), eq("alice-doc"), eq(false));
    }

    @Test
    @DisplayName("legacy /rest refuses to remove the admin flag of the built-in accounts and of the acting user")
    void legacyRefusesToDemoteProtectedUsers() throws Exception {
        for (String id : List.of("admin", "solr", ACTOR)) {
            String answer = legacy().update(REPO, id, null, null, null, null, null, null, null, null, null, null,
                    "false", request);
            assertTrue(answer.contains("\"failure\"") && answer.contains("is never demoted"), id + ": " + answer);
            assertTrue(users.get(id).isAdmin(), id + " lost its admin flag");
            verify(service, never()).update(any(), eq(REPO), eq(users.get(id)));
        }
        String answer = legacy().update(REPO, "alice", null, null, null, null, null, null, null, null, null, null,
                "false", request);
        assertTrue(answer.contains("\"success\""), answer);
        assertFalse(users.get("alice").isAdmin(), "alice kept the admin flag the request removed");
    }
}
