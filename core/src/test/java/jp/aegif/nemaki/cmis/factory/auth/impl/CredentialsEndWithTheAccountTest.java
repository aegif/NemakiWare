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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import jp.aegif.nemaki.util.test.HarnessBroken;

/**
 * A session token and an API key end with the account they were issued to (3.4.1, zero-trust P0): deleting the account,
 * or setting its {@code nemaki:allowedAuthMethods} to {@code disabled}, stops them on the next request. Both were checked
 * against the token store alone — a session token kept working for its 24 hours, an API key without end — through the
 * one login every REST, API v1 and CMIS request goes through.
 */
class CredentialsEndWithTheAccountTest {

    /** The id of the account DOCUMENT the fixture's credentials are issued for (R170). */
    private static final String ACCOUNT_DOCUMENT = "alice-doc";

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
        // The super-users repository is a REPOSITORY — 「super.users: bedroom」 in
        // repositories-default.yml — and this fixture used to name it "nemaki_conf", which is
        // the system database the conf door is addressed BY (batch-20 review, subagent P3-6).
        // Inert for these locks (it only feeds the IS_SU flag), and wrong where a reader looks.
        when(superUsers.getId()).thenReturn("bedroom");
        when(repositories.getSuperUsers()).thenReturn(superUsers);
        when(repositories.contains("bedroom")).thenReturn(true);
        service.setRepositoryInfoMap(repositories);
        TokenService tokens = mock(TokenService.class);
        // Bound to the account document and to its generation, because the login compares both
        // since R170 (3.4.1 batch 19). A fixture minting an unbound token would make every test
        // here pass or fail on THAT refusal instead of on what this class is about.
        Token session = new Token("alice", "session-1", System.currentTimeMillis() + 60_000);
        session.setAccountId(ACCOUNT_DOCUMENT);
        session.setCredentialGeneration(0L);
        when(tokens.getToken("", "bedroom", "alice")).thenReturn(session);
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
        return account(allowedAuthMethods, ACCOUNT_DOCUMENT, null);
    }

    /**
     * The account document the login reads for the name the credential names: its id, and its
     * {@code nemaki:credentialGeneration} as stored (null = the property is absent).
     *
     * <p>Build it into a local BEFORE the {@code when(...)} that returns it: stubbing a new mock
     * inside {@code thenReturn}'s argument is an UnfinishedStubbing, which fails the test with no
     * assertion of its own (measured here).
     */
    private static UserItem account(String allowedAuthMethods, String documentId, String generation) {
        UserItem user = mock(UserItem.class);
        when(user.getUserId()).thenReturn("alice");
        when(user.getId()).thenReturn(documentId);
        List<Property> properties = new java.util.ArrayList<>();
        if (allowedAuthMethods != null) {
            properties.add(new Property("nemaki:allowedAuthMethods", allowedAuthMethods));
        }
        if (generation != null) {
            properties.add(new Property(
                    jp.aegif.nemaki.util.AuthenticationUtil.CREDENTIAL_GENERATION_KEY, generation));
        }
        when(user.getSubTypeProperties()).thenReturn(properties);
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
    @DisplayName("a session token of a RECREATED account is refused, through the login itself")
    void aSessionTokenOfARecreatedAccountIsRefusedThroughTheLogin() {
        // The name resolves, the token is the registered one, the account is neither deleted nor
        // disabled — and it is a DIFFERENT document than the one the token was issued for. The
        // arms above are all satisfied by mayStillAct, so without this one the login's binding
        // check was measured only where another branch gives the same answer (R170's confirmation
        // round, Codex P2-3). [[a-sibling-arm-of-the-same-check-satisfies-the-lock]]
        UserItem recreated = account(null, "alice-doc-recreated", null);
        when(contentService.getUserItemById("bedroom", "alice")).thenReturn(recreated);
        assertFalse(assertDoesNotThrow(this::withSession),
                "the session token of the DELETED alice logged in as the recreated one");
    }

    @Test
    @DisplayName("the registered token is read ONCE, so a token registered in between is not the one checked")
    void theRegisteredTokenIsReadOnce() {
        // The window R194 is about: the login compared the presented token with the registered one,
        // and then read the registered token AGAIN to ask whether it is bound to this account. If the
        // same user id was deleted and recreated in between — which is what R170 is about — the second
        // read answered with the NEW token, whose binding is to the NEW account, so the OLD token the
        // caller presented logged in as the recreated user.
        Token recreated = new Token("alice", "session-2", System.currentTimeMillis() + 60_000);
        recreated.setAccountId("alice-doc-recreated");
        recreated.setCredentialGeneration(0L);
        TokenService races = mock(TokenService.class);
        // The FIRST answer is the token the caller presented; the second is the one registered after.
        Token presented = new Token("alice", "session-1", System.currentTimeMillis() + 60_000);
        presented.setAccountId(ACCOUNT_DOCUMENT);
        presented.setCredentialGeneration(0L);
        when(races.getToken("", "bedroom", "alice")).thenReturn(presented, recreated);
        when(races.validateToken("", "bedroom", "session-1")).thenReturn("alice");
        service.setTokenService(races);
        UserItem recreatedAccount = account(null, "alice-doc-recreated", null);
        when(contentService.getUserItemById("bedroom", "alice")).thenReturn(recreatedAccount);

        assertFalse(assertDoesNotThrow(this::withSession),
                "the token of the DELETED alice logged in as the recreated one: the binding was"
                        + " checked against a token registered after the comparison");
        // The mechanism, not just the outcome: one read is what makes the two questions about the
        // same token.
        verify(races, times(1)).getToken("", "bedroom", "alice");
    }

    @Test
    @DisplayName("a session token from before a password change is refused, through the login itself")
    void aSessionTokenFromBeforeAPasswordChangeIsRefusedThroughTheLogin() {
        // Same document, same name, able to act: only the generation differs. This is the arm that
        // stays green when the login stops comparing generations at all (the call passes
        // generationApplies = true; nothing else here would notice it becoming false).
        UserItem changed = account(null, ACCOUNT_DOCUMENT, "1");
        when(contentService.getUserItemById("bedroom", "alice")).thenReturn(changed);
        assertFalse(assertDoesNotThrow(this::withSession),
                "a session opened before the password change still logged in");
    }

    @Test
    @DisplayName("a session token is refused when the account's own generation cannot be read")
    void aSessionTokenIsRefusedWhenTheAccountsGenerationCannotBeRead() {
        // "Could not read" is not "generation 0": guessing would let a credential the password
        // change invalidated through.
        UserItem unreadable = account(null, ACCOUNT_DOCUMENT, "nonsense");
        when(contentService.getUserItemById("bedroom", "alice")).thenReturn(unreadable);
        assertFalse(assertDoesNotThrow(this::withSession),
                "the login read an unreadable generation as 0 and let the session through");
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

    private static UserItem passwordAccount(String allowedAuthMethods, String hash) {
        UserItem user = account(allowedAuthMethods);
        when(user.getPassowrd()).thenReturn(hash);
        return user;
    }

    private boolean withPassword(String password) {
        CallContextImpl context = new CallContextImpl(null, CmisVersion.CMIS_1_1, "bedroom", null, null, null, null, null);
        context.put(org.apache.chemistry.opencmis.commons.server.CallContext.USERNAME, "alice");
        context.put(org.apache.chemistry.opencmis.commons.server.CallContext.PASSWORD, password);
        return service.login(context);
    }

    @Test
    @DisplayName("\"disabled\" anywhere in the list disables the account — its session token, its API key and its password (batch 17 review round 1: Codex P1)")
    void aCompoundDisabledValueDisablesTheAccount() {
        String hash = org.mindrot.jbcrypt.BCrypt.hashpw("pw-1", org.mindrot.jbcrypt.BCrypt.gensalt(4));
        // The password logs in where the account allows it: a refusal below is the value's, not the harness's.
        UserItem allowed = passwordAccount("password", hash);
        when(contentService.getUserItemById("bedroom", "alice")).thenReturn(allowed);
        HarnessBroken.unless(withPassword("pw-1"), "the password did not log in where the account allows it — the lock would measure nothing");
        for (String compound : List.of("password,disabled", "cloud, Disabled ")) {
            UserItem held = passwordAccount(compound, hash);
            when(contentService.getUserItemById("bedroom", "alice")).thenReturn(held);
            assertFalse(withSession(), compound + ": the session token still logged in");
            assertFalse(withApiKey(), compound + ": the API key still logged in");
            assertFalse(withPassword("pw-1"), compound + ": the password still logged in");
        }
    }

    @Test
    @DisplayName("an account limited to one method keeps its session token and its API key — only \"disabled\" ends them (batch 17 review round 1: subagent P2-2)")
    void anAccountLimitedToOneMethodKeepsItsCredentials() {
        for (String only : List.of("cloud", "password")) {
            UserItem limited = account(only);
            when(contentService.getUserItemById("bedroom", "alice")).thenReturn(limited);
            assertTrue(withSession(), only + ": the session token of an account limited to it was refused");
            assertTrue(withApiKey(), only + ": the API key of an account limited to it was refused");
        }
    }
}
