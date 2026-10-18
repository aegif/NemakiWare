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
package jp.aegif.nemaki.rss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.model.UserItem;

/**
 * A feed token belongs to the account DOCUMENT it was issued for (R170, 3.4.1 batch 19).
 *
 * <p>The fifth store, in this batch by the owner's decision (2026-10-16, undecided 7). Deleting a
 * user and creating the same user id again handed the new account the old holder's feed URL — the
 * same shape as the API key, and the one the design had left as "decide later".
 *
 * <p>The hole is inactive today: R162 records that the feed URL sits behind the authentication
 * filter, so a token alone cannot read a feed. That is exactly why the comparison is measured HERE
 * and not through the delete: a lock that only checks "the recreated user cannot use the old feed"
 * is satisfied by the housekeeping that removed it, and would stay green with no comparison at all
 * (the round-11 review asked for this split).
 */
class AFeedTokenEndsWithItsAccountTest {

    private static final String REPO = "bedroom";
    private static final String USER = "alice";
    private static final String ACCOUNT_DOC = "alice-doc";

    private RssTokenService service;
    private RssTokenDaoService store;
    private ContentDaoService dao;

    @BeforeEach
    void setUp() {
        store = mock(RssTokenDaoService.class);
        dao = mock(ContentDaoService.class);
        service = new RssTokenService();
        service.setRssTokenDaoService(store);
        service.setContentDaoService(dao);
    }

    private UserItem account(String documentId, Object generation) {
        UserItem user = new UserItem(documentId, "nemaki:user", USER, USER, "hash", false, null);
        List<Property> properties = new ArrayList<>();
        if (generation != null) {
            properties.add(new Property("nemaki:credentialGeneration", generation));
        }
        user.setSubTypeProperties(properties);
        return user;
    }

    private RssToken issueFor(UserItem resolved) {
        when(dao.getUserItemById(REPO, USER)).thenReturn(resolved);
        return service.generateToken(REPO, USER, "my feed", List.of("folder-1"), List.of(),
                Set.of("created"), 30);
    }

    @Test
    @DisplayName("a token issued now records the account document and the generation")
    void aTokenRecordsTheAccount() {
        RssToken token = issueFor(account(ACCOUNT_DOC, 4));

        assertEquals(ACCOUNT_DOC, token.getAccountId(),
                "the feed token records no account document, so the user id alone decides whose"
                        + " feed it is — R170 itself");
        assertEquals("4", token.getCredentialGeneration());
    }

    @Test
    @DisplayName("no token is issued for a name that resolves to no account document")
    void noTokenWithoutAnAccount() {
        when(dao.getUserItemById(REPO, USER)).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> service.generateToken(REPO, USER, "my feed", List.of(), List.of(),
                        Set.of("created"), 30),
                "a feed URL was handed out that could never be validated");
        verify(store, never()).create(anyString(), any(RssToken.class));
    }

    @Test
    @DisplayName("the token of THIS account validates")
    void theTokenOfThisAccountValidates() {
        RssToken token = issueFor(account(ACCOUNT_DOC, 0));
        when(store.getByToken(REPO, token.getToken())).thenReturn(token);

        assertNotNull(service.validateToken(REPO, token.getToken()),
                "a token issued moments ago against this very account was refused, so every feed"
                        + " this release upgrades would stop");
    }

    @Test
    @DisplayName("the name resolving to ANOTHER document refuses the old token — with no cleanup involved")
    void theTokenOfARecreatedAccountIsRefused() {
        // The comparison on its own: the token is still in the store (no delete ran), and what
        // refuses it is that the user id now names a different document.
        RssToken token = issueFor(account("the-deleted-document", 0));
        when(store.getByToken(REPO, token.getToken())).thenReturn(token);
        when(dao.getUserItemById(REPO, USER)).thenReturn(account(ACCOUNT_DOC, 0));

        assertNull(service.validateToken(REPO, token.getToken()),
                "the feed token of the previous holder of this user id was accepted for the new"
                        + " account — the defect R170 names, and the one the delete-time cleanup"
                        + " cannot answer for");
    }

    @Test
    @DisplayName("a token issued before this release records no account and is refused")
    void aTokenWithoutAnAccountIsRefused() {
        RssToken token = issueFor(account(ACCOUNT_DOC, 0));
        token.setAccountId(null);
        token.setCredentialGeneration(null);
        when(store.getByToken(REPO, token.getToken())).thenReturn(token);

        assertNull(service.validateToken(REPO, token.getToken()),
                "a token from before this release was accepted by name, which is what the owner"
                        + " chose to refuse");
    }

    @Test
    @DisplayName("a password change does NOT stop a feed — the generation is not compared here")
    void aPasswordChangeDoesNotStopAFeed() {
        RssToken token = issueFor(account(ACCOUNT_DOC, 0));
        when(store.getByToken(REPO, token.getToken())).thenReturn(token);
        when(dao.getUserItemById(REPO, USER)).thenReturn(account(ACCOUNT_DOC, 5));

        assertNotNull(service.validateToken(REPO, token.getToken()),
                "changing a password stopped a feed the owner set up deliberately; a feed token is"
                        + " not derived from the password (§3 of the design)");
    }

    @Test
    @DisplayName("revoking a user's tokens takes the ones with no accountId too")
    void revokingTakesTheOldOnesToo() {
        RssToken bound = issueFor(account(ACCOUNT_DOC, 0));
        RssToken old = issueFor(account(ACCOUNT_DOC, 0));
        old.setAccountId(null);
        when(store.getByUserId(REPO, USER)).thenReturn(List.of(bound, old));

        assertEquals(2, service.revokeTokensOf(REPO, USER),
                "the count is what the delete logs and what an operator reads");
        verify(store).delete(eq(REPO), eq(bound.getId()));
        verify(store).delete(eq(REPO), eq(old.getId()));
    }
}
