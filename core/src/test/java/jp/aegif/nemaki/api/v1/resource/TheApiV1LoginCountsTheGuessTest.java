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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Response;
import jp.aegif.nemaki.api.v1.exception.ApiException;
import jp.aegif.nemaki.api.v1.model.request.LoginRequest;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.auth.AuthenticationService;
import jp.aegif.nemaki.cmis.factory.auth.Token;
import jp.aegif.nemaki.cmis.factory.auth.TokenService;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.model.UserItem;

/**
 * The API v1 password login counts the guesses, on the same counter as every other password door
 * (3.4.1, round-5 review).
 *
 * <p>{@code POST /api/v1/cmis/auth/repositories/{repo}/login} is a public endpoint that verified the
 * password itself — {@code passwordMatches} and then a token — instead of going through
 * {@code AuthenticationService.login}. Nothing counted the attempts: the guessing that locks out
 * after 15 failures over REST, the CMIS bindings and MCP was unlimited here, and because this door
 * never asked, it kept answering while the account was locked out everywhere else. S6 was recorded
 * as "MCP was the one door with no throttle"; there were three, and this was the easiest.
 *
 * <p>Driven as a request reaches the resource, with the throttle as a collaborator: what the counter
 * itself does is {@code LoginThrottle}'s own tests, and the key's construction is locked in
 * {@code GuessingIsCountedPerCallerAndSecretsStayOutOfTheLogTest}. What is measured here is that this
 * door asks, counts, and — the arm that is easy to get wrong — does NOT clear the count for a correct
 * password the account's policy refuses.
 */
class TheApiV1LoginCountsTheGuessTest {

    private static final String REPO = "bedroom";
    private static final String USER = "alice";
    private static final String PASSWORD = "the-real-one";

    private AuthResource resource;
    private ContentService contentService;
    private TokenService tokenService;
    private AuthenticationService authenticationService;
    private HttpServletRequest httpRequest;

    @BeforeEach
    void setUp() throws Exception {
        contentService = mock(ContentService.class);
        tokenService = mock(TokenService.class);
        authenticationService = mock(AuthenticationService.class);
        httpRequest = mock(HttpServletRequest.class);

        resource = new AuthResource();
        set(resource, "contentService", contentService);
        set(resource, "tokenService", tokenService);
        set(resource, "authenticationService", authenticationService);
        set(resource, "httpRequest", httpRequest);

        Token token = mock(Token.class);
        when(token.getToken()).thenReturn("a-token");
        when(tokenService.setToken(anyString(), eq(REPO), eq(USER))).thenReturn(token);
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

    /** An account with the given {@code nemaki:allowedAuthMethods} (null = unset). */
    private void accountExists(String allowedAuthMethods) {
        UserItem user = new UserItem(USER + "-doc", "nemaki:user", USER, USER,
                BCrypt.hashpw(PASSWORD, BCrypt.gensalt(4)), false, null);
        if (allowedAuthMethods != null) {
            user.setSubTypeProperties(
                    List.of(new Property("nemaki:allowedAuthMethods", allowedAuthMethods)));
        }
        when(contentService.getUserItemById(REPO, USER)).thenReturn(user);
        when(contentService.getGroupIdsDirectlyContainingUser(REPO, USER)).thenReturn(List.of());
        when(contentService.getGroupItems(REPO)).thenReturn(List.of());
    }

    private LoginRequest credentials(String password) {
        LoginRequest request = new LoginRequest();
        request.setUserId(USER);
        request.setPassword(password);
        return request;
    }

    @Test
    @DisplayName("during a lockout the door refuses without even looking the account up")
    void duringALockoutTheDoorRefusesBeforeReadingTheAccount() {
        when(authenticationService.isPasswordGuessingBlocked(REPO, USER, httpRequest)).thenReturn(true);
        accountExists(null);

        assertThrows(ApiException.class, () -> resource.login(REPO, credentials(PASSWORD)),
                "the door answered while this account was locked out, so a lockout elsewhere means"
                        + " nothing: guessing simply moves here");
        verify(contentService, never()).getUserItemById(REPO, USER);
    }

    @Test
    @DisplayName("a wrong password is counted")
    void aWrongPasswordIsCounted() {
        accountExists(null);

        assertThrows(ApiException.class, () -> resource.login(REPO, credentials("not-it")));

        verify(authenticationService).recordPasswordFailure(REPO, USER, httpRequest);
        verify(authenticationService, never()).recordPasswordSuccess(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a login that succeeds clears the count")
    void aLoginThatSucceedsClearsTheCount() {
        accountExists(null);

        Response answer = assertDoesNotThrow(() -> resource.login(REPO, credentials(PASSWORD)),
                "the login threw for a correct password");

        assertEquals(200, answer.getStatus());
        verify(authenticationService).recordPasswordSuccess(REPO, USER, httpRequest);
        verify(authenticationService, never()).recordPasswordFailure(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a correct password the account may not use does NOT clear the count")
    void aCorrectPasswordTheAccountMayNotUseDoesNotClearTheCount() {
        // The arm that is easy to get wrong, and that the MCP door had wrong until this review:
        // clearing the counter as soon as the password matches lets a caller who knows a password
        // that can never log in (a disabled or SSO-only account) reset the counter forever.
        accountExists("cloud");

        assertThrows(ApiException.class, () -> resource.login(REPO, credentials(PASSWORD)),
                "a cloud-only account logged in with a password");

        verify(authenticationService, never()).recordPasswordSuccess(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a name with no account is neither counted nor cleared")
    void anUnknownNameIsNeitherCountedNorCleared() {
        when(contentService.getUserItemById(REPO, USER)).thenReturn(null);

        assertThrows(ApiException.class, () -> resource.login(REPO, credentials(PASSWORD)));

        // Counting it would let anyone lock out a name BEFORE it exists; clearing it would be a
        // way to reset the counter without knowing anything.
        verify(authenticationService, never()).recordPasswordFailure(anyString(), anyString(), any());
        verify(authenticationService, never()).recordPasswordSuccess(anyString(), anyString(), any());
    }
}
