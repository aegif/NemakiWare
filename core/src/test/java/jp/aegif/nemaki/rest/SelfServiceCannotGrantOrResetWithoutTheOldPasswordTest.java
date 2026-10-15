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
package jp.aegif.nemaki.rest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.businesslogic.impl.ContentServiceImpl;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.AuthenticationUtil;
import jp.aegif.nemaki.util.constant.CallContextKey;

/**
 * The legacy {@code /rest} user update does not let an account grant itself anything, and does not let a
 * password be replaced without the current one (3.4.1, external review S1 and S4).
 *
 * <p>{@code checkAuthorityForUser} lets a non-administrator through for their OWN id, and three fields sat
 * inside that gate: {@code groups} — so a user could add themselves to any group and take its ACL rights —
 * {@code allowedAuthMethods} — so a user could undo an administrator's "SSO only" or "disabled" — and
 * {@code isAdmin}, which was already guarded. Separately, {@code update} and {@code update-json} set a new
 * password without asking for the current one, while {@code changePassword} next to them refused exactly
 * that: a stolen session token, good for 24 hours, could be turned into a permanent takeover.
 *
 * <p>Driven as a request reaches the resource. The acting user is {@code alice}, a non-administrator editing
 * her own account — the case the authority gate allows and these locks are about.
 */
class SelfServiceCannotGrantOrResetWithoutTheOldPasswordTest {

    private static final String REPO = "bedroom";
    private static final String SELF = "alice";
    private static final String CURRENT_PASSWORD = "current-pw";

    private ContentServiceImpl service;
    private UserItemResource resource;
    private HttpServletRequest selfRequest;
    private UserItem self;

    @BeforeEach
    void setUp() throws Exception {
        service = spy(new ContentServiceImpl());
        doNothing().when(service).delete(any(), any(), any(), any());
        doReturn(null).when(service).update(any(), any(), any());
        doNothing().when(service).applyUserUpdate(any(), any(), any());
        doReturn("hashed-new").when(service).hashPassword(any());

        self = new UserItem(SELF + "-doc", "nemaki:user", SELF, SELF,
                org.mindrot.jbcrypt.BCrypt.hashpw(CURRENT_PASSWORD, org.mindrot.jbcrypt.BCrypt.gensalt(4)), false, null);
        doReturn(self).when(service).getUserItemById(REPO, SELF);
        doReturn(List.of()).when(service).getGroupIdsDirectlyContainingUser(REPO, SELF);
        doReturn(List.of()).when(service).getGroupItems(REPO);

        resource = new UserItemResource();
        set(resource, "contentService", service);
        // The resource reads configuration on the way in (CSRF); without it the endpoint throws
        // before reaching the checks these locks are about.
        set(resource, "propertyManager", mock(jp.aegif.nemaki.util.PropertyManager.class));

        selfRequest = requestAs(SELF, false);
    }

    /** A request as {@code user}, an administrator or not, with CSRF satisfied as the filter sees it. */
    private HttpServletRequest requestAs(String user, boolean admin) {
        CallContext callContext = mock(CallContext.class);
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(admin);
        when(callContext.getUsername()).thenReturn(user);
        when(callContext.getRepositoryId()).thenReturn(REPO);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute("CallContext")).thenReturn(callContext);
        when(request.getHeader("X-Requested-With")).thenReturn("XMLHttpRequest");
        return request;
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

    /** {@code PUT /user/update/{id}}: only the arguments each lock is about are non-null. */
    private String update(HttpServletRequest request, String id, String password, String oldPassword,
            String groupsJson, String allowedAuthMethods) {
        return resource.update(REPO, id, null, null, null, null, null, null,
                password, oldPassword, groupsJson, allowedAuthMethods, null, request);
    }

    @Test
    @DisplayName("a non-administrator cannot add themselves to a group through their own update (external review S1)")
    void aNonAdministratorCannotJoinAGroup() {
        String answer = assertDoesNotThrow(
                () -> update(selfRequest, SELF, null, null, "[\"admins\"]", null),
                "the update threw instead of refusing");

        assertTrue(answer.contains("\"failure\""), answer);
        // The refusal must be the authority's, and nothing may have been written on the way to it.
        verify(service, never()).applyGroupUpdate(any(), any(), any());
        verify(service, never()).applyUserUpdate(any(), any(), any());
    }

    @Test
    @DisplayName("a non-administrator cannot rewrite their own allowedAuthMethods — an administrator's \"SSO only\" stands (external review S1)")
    void aNonAdministratorCannotRewriteItsOwnAuthMethods() {
        String answer = assertDoesNotThrow(
                () -> update(selfRequest, SELF, null, null, null, "password"),
                "the update threw instead of refusing");

        assertTrue(answer.contains("\"failure\""), answer);
        verify(service, never()).applyUserUpdate(any(), any(), any());
        assertTrue(self.getSubTypeProperties() == null || self.getSubTypeProperties().stream()
                .noneMatch(p -> "nemaki:allowedAuthMethods".equals(p.getKey())),
                "the account's authentication methods were rewritten by its own holder");
    }

    @Test
    @DisplayName("an administrator can still set both on another account — the gate is on who asks, not on the field")
    void anAdministratorCanStillSetThem() {
        String answer = assertDoesNotThrow(
                () -> update(requestAs("bob", true), SELF, null, null, "[]", "password,cloud"),
                "the update threw for an administrator");

        assertTrue(answer.contains("\"success\""), answer);
        verify(service).applyUserUpdate(any(), any(), any());
    }

    @Test
    @DisplayName("a self password change through update needs the current password (external review S4)")
    void aSelfPasswordChangeNeedsTheCurrentPassword() {
        String refused = assertDoesNotThrow(
                () -> update(selfRequest, SELF, "new-pw", "not-the-current-one", null, null),
                "the update threw instead of refusing");
        assertTrue(refused.contains("\"failure\""), refused);
        verify(service, never()).applyUserUpdate(any(), any(), any());

        // Discriminating: with the CURRENT password it goes through, so the refusal above is the
        // old-password check and not some other arm of this endpoint refusing everything.
        String accepted = assertDoesNotThrow(
                () -> update(selfRequest, SELF, "new-pw", CURRENT_PASSWORD, null, null),
                "the update threw for a self change that presented the current password");
        assertTrue(accepted.contains("\"success\""), accepted);
        verify(service).applyUserUpdate(any(), any(), any());
    }

    @Test
    @DisplayName("a self password change through update-json needs the current password too (external review S4)")
    void aSelfPasswordChangeThroughJsonNeedsTheCurrentPassword() {
        String refused = assertDoesNotThrow(() -> resource.updateJson(REPO, SELF,
                "{\"password\":\"new-pw\",\"oldPassword\":\"not-the-current-one\"}", selfRequest),
                "update-json threw instead of refusing");
        assertTrue(refused.contains("\"failure\""), refused);
        verify(service, never()).applyUserUpdate(any(), any(), any());

        String accepted = assertDoesNotThrow(() -> resource.updateJson(REPO, SELF,
                "{\"password\":\"new-pw\",\"oldPassword\":\"" + CURRENT_PASSWORD + "\"}", selfRequest),
                "update-json threw for a self change that presented the current password");
        assertTrue(accepted.contains("\"success\""), accepted);
        verify(service).applyUserUpdate(any(), any(), any());
    }

    @Test
    @DisplayName("an administrator resetting ANOTHER account's password still needs no current password")
    void anAdministratorResetNeedsNoCurrentPassword() {
        String answer = assertDoesNotThrow(
                () -> update(requestAs("bob", true), SELF, "reset-pw", null, null, null),
                "the update threw for an administrator's reset");

        assertEquals(true, answer.contains("\"success\""), answer);
        verify(service).applyUserUpdate(any(), any(), any());
    }
}
