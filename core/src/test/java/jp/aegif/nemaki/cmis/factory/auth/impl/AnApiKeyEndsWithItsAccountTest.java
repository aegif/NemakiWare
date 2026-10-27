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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.cmis.factory.auth.ApiKeyService.ApiKeyCreationResult;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.ApiKey;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.test.HarnessBroken;

/**
 * An API key belongs to the account DOCUMENT it was issued for, not to a user name (3.4.1, R170).
 *
 * <p>Until this release an {@code ApiKey} recorded only {@code userId}. Deleting a user and creating
 * the same user id again handed the new account every key the old one had — the API key of a
 * departed employee authenticating as their successor, with no expiry to run out.
 *
 * <p>Driven through {@code ApiKeyServiceImpl} itself, which is where the owner decided the judgement
 * belongs (2026-10-16): {@code validateApiKey} answers with a user NAME, so no caller can tell which
 * account document that name resolves to now. The rule's own arms are measured in
 * {@code ACredentialEndsWithItsAccountTest}; what is measured here is that this store WRITES the
 * binding when a key is issued, ASKS it when a key is presented, and that the one axis API keys must
 * NOT be subject to — the password generation — still lets an automation through (owner's answer 3).
 */
class AnApiKeyEndsWithItsAccountTest {

    private static final String REPO = "bedroom";
    private static final String USER = "alice";
    private static final String ACCOUNT_DOC = "user-doc-1";

    private ApiKeyServiceImpl service;
    private ContentDaoService dao;
    private List<ApiKey> stored;

    @BeforeEach
    void setUp() {
        dao = mock(ContentDaoService.class);
        stored = new ArrayList<>();
        service = new ApiKeyServiceImpl();
        service.setContentDaoService(dao);

        // The store, as the service sees it: create() appends, getApiKeys() reads back, delete()
        // removes by id. Nothing here decides anything the locks below measure.
        when(dao.create(eq(REPO), any(ApiKey.class))).thenAnswer(call -> {
            ApiKey key = call.getArgument(1);
            stored.add(key);
            return key;
        });
        when(dao.getApiKeys(REPO)).thenAnswer(call -> new ArrayList<>(stored));
        org.mockito.Mockito.doAnswer(call -> {
            String id = call.getArgument(1);
            stored.removeIf(key -> id.equals(key.getId()));
            return null;
        }).when(dao).delete(eq(REPO), anyString());
    }

    /** An account document; {@code generation} null means the property is absent. */
    private UserItem account(String documentId, String allowedAuthMethods, Object generation) {
        UserItem user = new UserItem(documentId, "nemaki:user", USER, USER, "hash", false, null);
        List<Property> properties = new ArrayList<>();
        if (allowedAuthMethods != null) {
            properties.add(new Property("nemaki:allowedAuthMethods", allowedAuthMethods));
        }
        if (generation != null) {
            properties.add(new Property("nemaki:credentialGeneration", generation));
        }
        user.setSubTypeProperties(properties);
        return user;
    }

    private void theNameResolvesTo(UserItem user) {
        when(dao.getUserItemById(REPO, USER)).thenReturn(user);
    }

    @Test
    @DisplayName("a key issued now records the account document and the generation it was issued at")
    void aKeyIssuedNowRecordsTheAccount() {
        theNameResolvesTo(account(ACCOUNT_DOC, null, 3));

        ApiKeyCreationResult created = service.createApiKey(REPO, USER, "automation", null);

        assertEquals(ACCOUNT_DOC, created.getApiKey().getAccountId(),
                "the key records no account document, so R170 is open for every key issued by this"
                        + " version");
        assertEquals("3", created.getApiKey().getCredentialGeneration(),
                "the generation was not written, so an administrator revoking every credential of"
                        + " this person has nothing to compare");
    }

    @Test
    @DisplayName("a key cannot be issued for a name that resolves to no account document")
    void aKeyIsNotIssuedWithoutAnAccount() {
        theNameResolvesTo(null);

        assertThrows(IllegalStateException.class,
                () -> service.createApiKey(REPO, USER, "automation", null),
                "a key was issued with nothing to bind it to; it could never authenticate, and the"
                        + " caller was told it had one");
        verify(dao, never()).create(eq(REPO), any(ApiKey.class));
    }

    @Test
    @DisplayName("a key cannot be issued when the account's own generation cannot be read")
    void aKeyIsNotIssuedWhenTheGenerationIsUnreadable() {
        // Unknown is not zero: writing 0 here would be a value this version invented, and the key
        // would then compare equal to an account that has never changed its password.
        theNameResolvesTo(account(ACCOUNT_DOC, null, "nonsense"));

        assertThrows(IllegalStateException.class,
                () -> service.createApiKey(REPO, USER, "automation", null));
        verify(dao, never()).create(eq(REPO), any(ApiKey.class));
    }

    @Test
    @DisplayName("the key of this account authenticates")
    void theKeyOfThisAccountAuthenticates() {
        theNameResolvesTo(account(ACCOUNT_DOC, null, 0));
        ApiKeyCreationResult created = service.createApiKey(REPO, USER, "automation", null);

        assertEquals(USER, service.validateApiKey(REPO, created.getPlainTextKey()),
                "a key issued moments ago against this very account was refused, which would stop"
                        + " every automation this release upgrades");
    }

    @Test
    @DisplayName("the key of the PREVIOUS account with the same user id is refused — R170 itself")
    void theKeyOfARecreatedAccountIsRefused() {
        theNameResolvesTo(account("the-deleted-document", null, 0));
        ApiKeyCreationResult created = service.createApiKey(REPO, USER, "automation", null);

        // The user is deleted and created again: same name, new document.
        theNameResolvesTo(account(ACCOUNT_DOC, null, 0));

        assertNull(service.validateApiKey(REPO, created.getPlainTextKey()),
                "the API key of the previous holder of this user id authenticated as the new"
                        + " account — the defect R170 names");
    }

    @Test
    @DisplayName("a key issued before this release records no account and is refused")
    void aKeyWithoutAnAccountIsRefused() {
        // The owner's decision (2026-10-16), knowing it invalidates existing keys: accepting by
        // name leaves R170 open. Such a key cannot be issued any more (see above), so it is built
        // here the way 3.4.0 wrote it.
        theNameResolvesTo(account(ACCOUNT_DOC, null, 0));
        ApiKeyCreationResult created = service.createApiKey(REPO, USER, "automation", null);
        created.getApiKey().setAccountId(null);
        created.getApiKey().setCredentialGeneration(null);

        assertNull(service.validateApiKey(REPO, created.getPlainTextKey()),
                "a key from before this release was accepted by name, which is exactly what the"
                        + " owner chose to refuse");
    }

    @Test
    @DisplayName("a password change does NOT stop an API key — the generation is not compared here")
    void aPasswordChangeDoesNotStopAnApiKey() {
        theNameResolvesTo(account(ACCOUNT_DOC, null, 0));
        ApiKeyCreationResult created = service.createApiKey(REPO, USER, "automation", null);

        // The account's generation moves on; the key still carries 0.
        theNameResolvesTo(account(ACCOUNT_DOC, null, 5));

        assertEquals(USER, service.validateApiKey(REPO, created.getPlainTextKey()),
                "a human changing their password silently stopped an automation, which the owner"
                        + " decided against (answer 3): API keys are revoked explicitly, not by a"
                        + " password change");
    }

    @Test
    @DisplayName("an account that may not act refuses its keys — batch 17's rule still runs first")
    void anAccountThatMayNotActRefusesItsKeys() {
        theNameResolvesTo(account(ACCOUNT_DOC, null, 0));
        ApiKeyCreationResult created = service.createApiKey(REPO, USER, "automation", null);

        theNameResolvesTo(account(ACCOUNT_DOC, "password,disabled", 0));

        assertNull(service.validateApiKey(REPO, created.getPlainTextKey()),
                "a disabled account's API key authenticated");
    }

    @Test
    @DisplayName("revoking every key of a user takes the ones with no accountId too, and they stop working")
    void revokingEveryKeyOfAUserTakesTheOldOnesToo() {
        theNameResolvesTo(account(ACCOUNT_DOC, null, 0));
        ApiKeyCreationResult bound = service.createApiKey(REPO, USER, "automation", null);
        ApiKeyCreationResult old = service.createApiKey(REPO, USER, "legacy", null);
        old.getApiKey().setAccountId(null);

        assertEquals(2, service.revokeApiKeysOf(REPO, USER),
                "the count of revoked keys is what the delete logs and what an operator reads");
        assertTrue(stored.isEmpty(),
                "a key of a deleted user was left in the store; looking them up by accountId is"
                        + " what would leave exactly the pre-3.4.1 ones behind");
        assertNull(service.validateApiKey(REPO, bound.getPlainTextKey()));
        HarnessBroken.unless((old.getApiKey().getKeyHash()) != null, "the test's own fixture lost its key");
    }
}
