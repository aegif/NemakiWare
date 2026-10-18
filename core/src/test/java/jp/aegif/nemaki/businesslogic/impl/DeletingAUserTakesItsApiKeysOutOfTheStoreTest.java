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
package jp.aegif.nemaki.businesslogic.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import jp.aegif.nemaki.businesslogic.ContentService.UserItemDeletion;
import jp.aegif.nemaki.cmis.factory.auth.ApiKeyService;
import jp.aegif.nemaki.model.UserItem;

/**
 * Deleting a user takes their API keys out of the store, on BOTH ways of deleting one (R170, 3.4.1).
 *
 * <p>Housekeeping, not the security boundary — and the distinction is the point of this lock. What
 * stops the API key of a deleted account is the comparison in {@code ApiKeyServiceImpl}
 * ({@code AnApiKeyEndsWithItsAccountTest}); if this revocation were the defence, then the CMIS way
 * of deleting a user — {@code deleteUserItem}, which does not go through {@code deleteUser} — would
 * leave a usable key behind. So this measures three things that are easy to get backwards:
 *
 * <ul>
 * <li>both entry points revoke, not only {@code deleteUser};</li>
 * <li>the revocation happens AFTER the delete. The other order loses the keys of a user who then
 *     turns out to be undeletable, and buys nothing;</li>
 * <li>a revocation that fails does not fail the delete, and does not leave the caller believing the
 *     user is still there.</li>
 * </ul>
 */
class DeletingAUserTakesItsApiKeysOutOfTheStoreTest {

    private static final String REPO = "bedroom";
    private static final String USER = "alice";
    private static final String ACTOR = "bob";

    private ContentServiceImpl service;
    private ApiKeyService apiKeys;
    private UserItem alice;
    private CallContext callContext;

    @BeforeEach
    void setUp() {
        service = spy(new ContentServiceImpl());
        apiKeys = mock(ApiKeyService.class);
        service.setApiKeyService(apiKeys);

        doNothing().when(service).delete(any(), any(), any(), any());
        // removeUserFromAllGroups is private, so it RUNS here. With no group containing this user
        // (stubbed below) it does nothing, which is what this lock needs: the order of the delete
        // and the revocation is what is measured, not the membership stripping.

        alice = new UserItem(USER + "-doc", "nemaki:user", USER, USER, null, true, null);
        doReturn(alice).when(service).getUserItemById(REPO, USER);
        doReturn(List.of()).when(service).getGroupIdsDirectlyContainingUser(REPO, USER);

        callContext = mock(CallContext.class);
        when(callContext.getUsername()).thenReturn(ACTOR);
    }

    @Test
    @DisplayName("deleteUser revokes the user's API keys, after the delete")
    void deleteUserRevokesAfterTheDelete() {
        assertTrue(service.deleteUser(REPO, USER, ACTOR));

        InOrder order = inOrder(service, apiKeys);
        order.verify(service).delete(any(), eq(REPO), eq(alice.getId()), eq(false));
        order.verify(apiKeys).revokeApiKeysOf(REPO, USER);
    }

    @Test
    @DisplayName("the CMIS way of deleting a user revokes them too — it does not go through deleteUser")
    void deletingTheUserItemThroughCmisRevokesToo() {
        UserItemDeletion decided = service.checkUserItemDeletion(callContext, REPO, alice);

        service.deleteUserItem(decided, false);

        InOrder order = inOrder(service, apiKeys);
        order.verify(service).delete(any(), eq(REPO), eq(alice.getId()), eq(false));
        order.verify(apiKeys).revokeApiKeysOf(REPO, USER);
    }

    @Test
    @DisplayName("a revocation that fails does not fail the delete")
    void aFailedRevocationDoesNotFailTheDelete() {
        doThrow(new RuntimeException("the key store is unreachable"))
                .when(apiKeys).revokeApiKeysOf(REPO, USER);

        assertTrue(assertDoesNotThrow(() -> service.deleteUser(REPO, USER, ACTOR),
                        "a key store that is momentarily unreachable turned a completed delete into"
                                + " an error, and the caller cannot tell that the user IS gone"),
                "the delete reported failure although the user was deleted");
        verify(service).delete(any(), eq(REPO), eq(alice.getId()), eq(false));
    }

    @Test
    @DisplayName("a context that does not wire the key service still deletes users")
    void withNoKeyServiceTheDeleteStillWorks() {
        service.setApiKeyService(null);

        assertTrue(assertDoesNotThrow(() -> service.deleteUser(REPO, USER, ACTOR)));
        verify(service).delete(any(), eq(REPO), eq(alice.getId()), eq(false));
    }

    @Test
    @DisplayName("deleting a user that is not there revokes nothing")
    void aMissingUserRevokesNothing() {
        doReturn(null).when(service).getUserItemById(REPO, USER);

        org.junit.jupiter.api.Assertions.assertFalse(service.deleteUser(REPO, USER, ACTOR));

        verify(apiKeys, never()).revokeApiKeysOf(anyString(), anyString());
    }
}
