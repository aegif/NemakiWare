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
package jp.aegif.nemaki.cmis.factory.auth.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.server.impl.CallContextImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.auth.ApiKeyService;
import jp.aegif.nemaki.cmis.factory.auth.Token;
import jp.aegif.nemaki.cmis.factory.auth.TokenService;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.CallContextKey;

/**
 * A session token and an API key end with the account they were issued to (3.4.1, zero-trust P0): deleting the account,
 * or setting its {@code nemaki:allowedAuthMethods} to {@code disabled}, stops them on the next request. Both were checked
 * against the token store alone — a session token kept working for its 24 hours, an API key without end — through the
 * one login every REST, API v1 and CMIS request goes through.
 */
class CredentialsEndWithTheAccountTest {

    private AuthenticationServiceImpl service;
    private ContentService contentService;

    @BeforeEach
    void setUp() {
        service = new AuthenticationServiceImpl();
        contentService = mock(ContentService.class);
        service.setContentService(contentService);
        PropertyManager properties = mock(PropertyManager.class);
        service.setPropertyManager(properties);
        RepositoryInfoMap repositories = mock(RepositoryInfoMap.class);
        jp.aegif.nemaki.cmis.factory.info.RepositoryInfo superUsers = mock(jp.aegif.nemaki.cmis.factory.info.RepositoryInfo.class);
        when(superUsers.getId()).thenReturn("nemaki_conf");
        when(repositories.getSuperUsers()).thenReturn(superUsers);
        when(repositories.contains("bedroom")).thenReturn(true);
        service.setRepositoryInfoMap(repositories);
        TokenService tokens = mock(TokenService.class);
        when(tokens.getToken("", "bedroom", "alice")).thenReturn(new Token("alice", "session-1", System.currentTimeMillis() + 60_000));
        when(tokens.validateToken("", "bedroom", "session-1")).thenReturn("alice");
        service.setTokenService(tokens);
        ApiKeyService keys = mock(ApiKeyService.class);
        when(keys.validateApiKey("bedroom", "key-1")).thenReturn("alice");
        service.setApiKeyService(keys);
        // A refused token falls through to the password login, which looks for the anonymous id.
        jp.aegif.nemaki.businesslogic.PrincipalService principals = mock(jp.aegif.nemaki.businesslogic.PrincipalService.class);
        when(principals.getAnonymous("bedroom")).thenReturn("anonymous");
        service.setPrincipalService(principals);
    }

    private static UserItem account(String allowedAuthMethods) {
        UserItem user = mock(UserItem.class);
        when(user.getUserId()).thenReturn("alice");
        when(user.getSubTypeProperties()).thenReturn(allowedAuthMethods == null ? List.of()
                : List.of(new Property("nemaki:allowedAuthMethods", allowedAuthMethods)));
        return user;
    }

    private boolean withSession() {
        CallContextImpl context = new CallContextImpl(null, CmisVersion.CMIS_1_1, "bedroom", null, null, null, null, null);
        context.put(CallContextKey.AUTH_TOKEN, "session-1");
        return service.login(context);
    }

    private boolean withApiKey() {
        CallContextImpl context = new CallContextImpl(null, CmisVersion.CMIS_1_1, "bedroom", null, null, null, null, null);
        context.put(CallContextKey.API_KEY, "key-1");
        return service.login(context);
    }

    @Test
    @DisplayName("a session token of an account that is there and not disabled logs in — as do its API keys")
    void theAccountsCredentialsWorkWhileItIsThere() {
        UserItem a1 = account(null);
        when(contentService.getUserItemById("bedroom", "alice")).thenReturn(a1);
        assertTrue(withSession());
        assertTrue(withApiKey());
        UserItem a2 = account("password,cloud");
        when(contentService.getUserItemById("bedroom", "alice")).thenReturn(a2);
        assertTrue(withSession());
        assertTrue(withApiKey());
    }

    @Test
    @DisplayName("a session token stops when its account is deleted — it kept working for its 24 hours")
    void aSessionTokenStopsWhenTheAccountIsDeleted() {
        when(contentService.getUserItemById("bedroom", "alice")).thenReturn(null);
        assertFalse(assertDoesNotThrow(this::withSession), "a deleted account's session token logged in");
    }

    @Test
    @DisplayName("a session token stops when its account is disabled")
    void aSessionTokenStopsWhenTheAccountIsDisabled() {
        UserItem a3 = account("disabled");
        when(contentService.getUserItemById("bedroom", "alice")).thenReturn(a3);
        assertFalse(withSession());
    }

    @Test
    @DisplayName("an API key stops when its account is deleted — it authenticated a deleted account without end")
    void anApiKeyStopsWhenTheAccountIsDeleted() {
        when(contentService.getUserItemById("bedroom", "alice")).thenReturn(null);
        assertFalse(assertDoesNotThrow(this::withApiKey), "a deleted account's API key logged in");
    }

    @Test
    @DisplayName("an API key stops when its account is disabled")
    void anApiKeyStopsWhenTheAccountIsDisabled() {
        UserItem a4 = account(" Disabled ");
        when(contentService.getUserItemById("bedroom", "alice")).thenReturn(a4);
        assertFalse(withApiKey());
    }
}
