/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     aegif - password policy enforcement regression tests
 ******************************************************************************/
package jp.aegif.nemaki.rest.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import jakarta.servlet.http.HttpServletRequest;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.PasswordPolicyService;
import jp.aegif.nemaki.util.constant.CallContextKey;

/**
 * Regression tests ensuring {@code UserController} enforces the password policy
 * on BOTH create and update. The Spring MVC user path previously hashed the
 * password directly without calling {@link PasswordPolicyService#validate}, so a
 * weak password could bypass the admin-configured policy. These tests fail if
 * that validation call is ever removed again, because a weak password would no
 * longer be rejected and persistence would proceed.
 */
public class UserControllerPasswordPolicyTest {

    private static final String REPO = "bedroom";

    private UserController controller;
    private ContentService contentService;
    private PasswordPolicyService passwordPolicyService;
    private HttpServletRequest httpRequest;
    private CallContext callContext;

    @BeforeEach
    public void setUp() throws Exception {
        controller = new UserController();
        contentService = mock(ContentService.class);
        passwordPolicyService = mock(PasswordPolicyService.class);
        httpRequest = mock(HttpServletRequest.class);
        callContext = mock(CallContext.class);

        when(httpRequest.getAttribute("CallContext")).thenReturn(callContext);
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(true);
        when(callContext.getUsername()).thenReturn("admin");

        setField("httpRequest", httpRequest);
        setField("contentService", contentService);
        setField("passwordPolicyService", passwordPolicyService);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = UserController.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(controller, value);
    }

    @Test
    public void createUserRejectsPasswordViolatingPolicy() {
        when(contentService.getUserItemById(REPO, "bob")).thenReturn(null); // not existing
        when(passwordPolicyService.validate(eq("weak"), eq(REPO)))
                .thenReturn(PasswordPolicyService.PasswordPolicyResult.error("Password too short"));

        ResponseEntity<Map<String, Object>> response =
                controller.createUser(REPO, "bob", "Bob", "weak", null, null, null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                "weak password must be rejected with 400");
        assertTrue(response.getBody().toString().contains("Password too short"),
                "policy error must be surfaced");
        verify(contentService, never()).createUserItem(any(), any(), any());
    }

    @Test
    public void updateUserRejectsPasswordViolatingPolicy() {
        UserItem existing = mock(UserItem.class);
        when(existing.getSubTypeProperties()).thenReturn(new ArrayList<>());
        when(contentService.getUserItemById(REPO, "bob")).thenReturn(existing);
        when(passwordPolicyService.validate(eq("weak"), eq(REPO)))
                .thenReturn(PasswordPolicyService.PasswordPolicyResult.error("Password too short"));

        ResponseEntity<Map<String, Object>> response =
                controller.updateUser(REPO, "bob", null, null, null, null, "weak");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                "weak password on update must be rejected with 400");
        verify(existing, never()).setPassowrd(any());
        verify(contentService, never()).update(any(), any(), any());
    }

    @Test
    public void createUserAcceptsPasswordSatisfyingPolicy() {
        // A compliant password must NOT be rejected at the policy gate. We stop
        // before folder/persistence by asserting validate() was consulted and the
        // request did not 400 at the policy check (it may fail later on unmocked
        // folder lookup, which is out of scope — we assert the policy verdict only).
        when(contentService.getUserItemById(REPO, "carol")).thenReturn(null);
        when(passwordPolicyService.validate(eq("Str0ng!Pass"), eq(REPO)))
                .thenReturn(PasswordPolicyService.PasswordPolicyResult.ok());

        ResponseEntity<Map<String, Object>> response =
                controller.createUser(REPO, "carol", "Carol", "Str0ng!Pass", null, null, null);

        // Policy passed → not a validation 400. (System-folder lookup is mocked to
        // null, so creation surfaces a 500, proving we got past the policy gate.)
        assertNotEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                "a compliant password must pass the policy gate");
        verify(passwordPolicyService).validate(eq("Str0ng!Pass"), eq(REPO));
    }
}
