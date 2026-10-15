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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.server.impl.CallContextImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Response;
import jp.aegif.nemaki.api.v1.exception.ApiException;
import jp.aegif.nemaki.api.v1.model.response.AuthResponse;
import jp.aegif.nemaki.api.v1.model.response.UserResponse;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.auth.Token;
import jp.aegif.nemaki.cmis.factory.auth.TokenService;
import jp.aegif.nemaki.model.UserItem;

/**
 * API v1 acts as the identity the filter authenticated, not as the one the request asserts (3.4.1, zero-trust P0).
 *
 * <p>The authentication filter — {@code rest.AuthenticationFilter}, which {@code web.xml} maps over
 * {@code /api/v1/cmis/*} — authenticates a request by its {@code AUTH_TOKEN} or API key <em>before</em> it
 * looks at {@code Authorization}, and records who that is in the {@code CallContext}. The resource used to re-derive the
 * name from the raw {@code Authorization} header, taking it out of a {@code Basic} value without checking the password:
 * a caller holding any valid token of their own could add {@code Authorization: Basic admin:anything} and have
 * {@code /token/refresh} mint a token for {@code admin}. These locks drive the production entry points.
 */
class TheAssertedNameIsNotTheAuthenticatedOneTest {

    private static final String REPO = "bedroom";
    /** What an attacker asserts: the admin's name with a password that is not the admin's. */
    private static final String ASSERTS_ADMIN =
            "Basic " + Base64.getEncoder().encodeToString("admin:not-the-password".getBytes(StandardCharsets.UTF_8));

    private AuthResource resource;
    private HttpServletRequest httpRequest;
    private ContentService contentService;
    private TokenService tokenService;

    @BeforeEach
    void setUp() throws Exception {
        resource = new AuthResource();
        httpRequest = mock(HttpServletRequest.class);
        contentService = mock(ContentService.class);
        tokenService = mock(TokenService.class);
        inject("httpRequest", httpRequest);
        inject("contentService", contentService);
        inject("tokenService", tokenService);
        for (String name : new String[] {"bob", "admin"}) {
            UserItem user = mock(UserItem.class);
            when(user.getUserId()).thenReturn(name);
            when(contentService.getUserItemById(REPO, name)).thenReturn(user);
            when(tokenService.setToken("", REPO, name)).thenReturn(new Token(name, "token-for-" + name, Long.MAX_VALUE));
        }
    }

    private void inject(String field, Object value) throws Exception {
        Field f = AuthResource.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(resource, value);
    }

    /** As the filter leaves it: authenticated as {@code username}, with whatever Authorization the caller sent. */
    private void authenticatedAs(String username, String authorizationHeader) {
        CallContextImpl context = new CallContextImpl(null, CmisVersion.CMIS_1_1, REPO, null, null, null, null, null);
        context.put(CallContext.USERNAME, username);
        when(httpRequest.getAttribute("CallContext")).thenReturn(context);
        when(httpRequest.getHeader("Authorization")).thenReturn(authorizationHeader);
        when(httpRequest.getUserPrincipal()).thenReturn(null);
    }

    private static String tokenOf(Response response) {
        assertEquals(200, response.getStatus());
        AuthResponse body = (AuthResponse) response.getEntity();
        assertNotNull(body);
        return body.getToken();
    }

    @Test
    @DisplayName("the refresh mints a token for the account the request authenticated as, not for a name its Authorization header asserts")
    void theRefreshMintsATokenForTheAuthenticatedAccount() {
        authenticatedAs("bob", ASSERTS_ADMIN);

        // assertDoesNotThrow so that losing the authenticated identity fails THIS assertion, not the harness.
        Response response = assertDoesNotThrow(() -> resource.refreshToken(REPO),
                "the refresh refused the account the request authenticated as");
        assertEquals("token-for-bob", tokenOf(response),
                "the refresh minted a token for the name the request asserted");

        verify(tokenService, never()).setToken("", REPO, "admin");
    }

    @Test
    @DisplayName("the refresh still works for an admin who authenticated as the admin")
    void theRefreshWorksForAnAccountThatAuthenticatedAsItself() {
        authenticatedAs("admin", ASSERTS_ADMIN);

        Response response = assertDoesNotThrow(() -> resource.refreshToken(REPO));
        assertEquals("token-for-admin", tokenOf(response));
    }

    @Test
    @DisplayName("a blank authenticated username is not an identity — nothing is minted for the empty account")
    void aBlankAuthenticatedUsernameIsNotAnIdentity() {
        authenticatedAs("", ASSERTS_ADMIN);

        assertThrows(ApiException.class, () -> resource.refreshToken(REPO),
                "a blank authenticated username was taken for an identity");

        // Discriminating: a blank name must be refused AT the identity step. Letting it through and relying on the
        // account lookup to come back empty is the same outcome by a sibling arm, and would leave this lock green.
        verify(contentService, never()).getUserItemById(REPO, "");
        verify(tokenService, never()).setToken(anyString(), anyString(), eq(""));
        verify(tokenService, never()).setToken("", REPO, "admin");
    }

    @Test
    @DisplayName("the logout and the current-user answer are about the account the request authenticated as, not a name its header asserts")
    void logoutAndCurrentUserFollowTheAuthenticatedAccount() {
        authenticatedAs("bob", ASSERTS_ADMIN);

        Response me = assertDoesNotThrow(() -> resource.getCurrentUser(REPO),
                "the current-user answer refused the account the request authenticated as");
        assertEquals(200, me.getStatus());
        assertEquals("bob", ((UserResponse) me.getEntity()).getUserId(),
                "the current-user answer described the name the request asserted");

        assertDoesNotThrow(() -> resource.logout(REPO), "the logout refused the authenticated account");
        // The admin's token is not the one a logout by bob may remove.
        verify(tokenService, never()).removeToken(any(), eq(REPO), eq("admin"));
    }

    @Test
    @DisplayName("nothing is minted where the filter left no authenticated identity — an asserted name is not one")
    void nothingIsMintedWithoutAnAuthenticatedIdentity() {
        when(httpRequest.getAttribute("CallContext")).thenReturn(null);
        when(httpRequest.getHeader("Authorization")).thenReturn(ASSERTS_ADMIN);
        when(httpRequest.getUserPrincipal()).thenReturn(null);

        assertThrows(ApiException.class, () -> resource.refreshToken(REPO),
                "a request with no authenticated identity minted a token from the name it asserted");

        verify(tokenService, never()).setToken("", REPO, "admin");
    }
}
