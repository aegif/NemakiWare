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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.auth.Token;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;

/**
 * A session token is minted for an account DOCUMENT, with the generation it was minted at (R170).
 *
 * <p>The mint side of the rule. {@code TokenMap.set} is where the ten callers of
 * {@code setToken} converge, which is why the binding is written there and none of them changes —
 * and why this lock drives {@code setToken} rather than the callers.
 *
 * <p>The compare side (a token of a recreated account, a token from before a password change) runs
 * through {@code AuthenticationServiceImpl.login} and is measured in
 * {@code CredentialsEndWithTheAccountTest}, whose fixture mints bound tokens for that reason.
 */
class ASessionTokenEndsWithItsAccountTest {

    private static final String REPO = "bedroom";
    private static final String USER = "alice";
    private static final String ACCOUNT_DOC = "alice-doc";

    private TokenServiceImpl service;
    private ContentService contentService;

    @BeforeEach
    void setUp() {
        service = new TokenServiceImpl();
        contentService = mock(ContentService.class);
        service.setContentService(contentService);
        PropertyManager properties = mock(PropertyManager.class);
        when(properties.readValue(PropertyKey.AUTH_TOKEN_EXPIRATION)).thenReturn("60000");
        service.setPropertyManager(properties);
    }

    /** An account document; {@code generation} null means the property is absent. */
    private UserItem account(String documentId, Object generation) {
        UserItem user = new UserItem(documentId, "nemaki:user", USER, USER, "hash", false, null);
        List<Property> properties = new ArrayList<>();
        if (generation != null) {
            properties.add(new Property("nemaki:credentialGeneration", generation));
        }
        user.setSubTypeProperties(properties);
        return user;
    }

    @Test
    @DisplayName("a token minted now records the account document and the generation it was minted at")
    void aTokenRecordsTheAccountItWasMintedFor() {
        when(contentService.getUserItemById(REPO, USER)).thenReturn(account(ACCOUNT_DOC, 4));

        Token minted = service.setToken("", REPO, USER);

        assertNotNull(minted, "nothing was minted");
        assertEquals(ACCOUNT_DOC, minted.getAccountId(),
                "the token records no account document, so it would keep working for whichever"
                        + " account this user id names later — R170 itself");
        assertEquals(Long.valueOf(4L), minted.getCredentialGeneration(),
                "the token records no generation, so a password change could not end the sessions"
                        + " it opened");
    }

    @Test
    @DisplayName("an account with no generation property mints at 0 — upgrading does not stop anyone")
    void anAccountWithNoGenerationMintsAtZero() {
        when(contentService.getUserItemById(REPO, USER)).thenReturn(account(ACCOUNT_DOC, null));

        assertEquals(Long.valueOf(0L), service.setToken("", REPO, USER).getCredentialGeneration(),
                "a token for an account that has never changed its password was minted with no"
                        + " generation, and the login refuses exactly that — every user of an"
                        + " upgraded repository would be locked out");
    }

    @Test
    @DisplayName("nothing is minted for a name that resolves to no account document")
    void nothingIsMintedWithoutAnAccount() {
        when(contentService.getUserItemById(REPO, USER)).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> service.setToken("", REPO, USER),
                "a token was handed back that could never authenticate, and the caller was told"
                        + " the login had succeeded");
        assertNull(service.getToken("", REPO, USER), "the unusable token was kept in the map");
    }

    @Test
    @DisplayName("nothing is minted when the account's own generation cannot be read")
    void nothingIsMintedWhenTheGenerationIsUnreadable() {
        // Unknown is not zero: minting at 0 would invent a value, and the token would then compare
        // equal to an account that has never changed its password.
        when(contentService.getUserItemById(REPO, USER)).thenReturn(account(ACCOUNT_DOC, "nonsense"));

        assertThrows(IllegalStateException.class, () -> service.setToken("", REPO, USER));
        assertNull(service.getToken("", REPO, USER));
    }

    @Test
    @DisplayName("a token minted after a password change carries the new generation")
    void aTokenMintedAfterAChangeCarriesTheNewGeneration() {
        when(contentService.getUserItemById(REPO, USER)).thenReturn(account(ACCOUNT_DOC, 1));
        Token before = service.setToken("", REPO, USER);

        when(contentService.getUserItemById(REPO, USER)).thenReturn(account(ACCOUNT_DOC, 2));
        Token after = service.setToken("", REPO, USER);

        assertEquals(Long.valueOf(1L), before.getCredentialGeneration());
        assertEquals(Long.valueOf(2L), after.getCredentialGeneration(),
                "the new token was minted at the old generation, so the login would refuse the"
                        + " session the user just opened by changing their password");
    }
}
