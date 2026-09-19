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
package jp.aegif.nemaki.dao.impl.couch;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.couch.CouchRssToken;

/**
 * The RSS token store never answers SHORT or turns a failure into "not found".
 *
 * <h2>The token that outlives its revocation</h2>
 *
 * <p>{@code getByUserId} is the listing an administrator REVOKES from — a silently short
 * answer (one row lost mid-stream, the old catch returned the partial list) means a token that
 * keeps its feed access after the admin believes every token is revoked. {@code getById}'s old
 * catch returned null for any failure, and the disable/delete paths report null as "Token not
 * found" — telling the admin a token that still works is already gone. Found by the round-32
 * sibling sweep; same fail-closed rule as the user/group listings.
 */
class RssTokenListingsAreNeverSilentlyShortTest {

    private static final String REPO = "bedroom";

    private CloudantClientWrapper client;
    private RssTokenDaoServiceImpl dao;

    @BeforeEach
    void setUp() {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        client = mock(CloudantClientWrapper.class);
        when(pool.getClient(REPO)).thenReturn(client);
        dao = new RssTokenDaoServiceImpl();
        dao.setConnectorPool(pool);
    }

    @Test
    @DisplayName("a token listing whose view answers without rows refuses")
    void aTokenListingWithoutRowsRefuses() {
        ViewResult unanswered = mock(ViewResult.class);
        when(unanswered.getRows()).thenReturn(null);
        when(client.queryView(eq("_repo"), eq("rssTokensByUserId"), anyMap()))
                .thenReturn(unanswered);

        assertThrows(IllegalStateException.class,
                () -> dao.getByUserId(REPO, "user-x"),
                "an unanswered view was served as 'no tokens', and the admin revoked "
                        + "everything they could see");
    }

    @Test
    @DisplayName("a token row without a document refuses the whole listing")
    void aDocumentlessTokenRowRefuses() {
        ViewResultRow row = mock(ViewResultRow.class);
        when(row.getDoc()).thenReturn(null);
        ViewResult result = mock(ViewResult.class);
        when(result.getRows()).thenReturn(List.of(row));
        when(client.queryView(eq("_repo"), eq("rssTokensByUserId"), anyMap()))
                .thenReturn(result);

        assertThrows(IllegalStateException.class,
                () -> dao.getByUserId(REPO, "user-x"),
                "a token the listing dropped keeps its access invisibly");
    }

    @Test
    @DisplayName("a failed listing refuses — the old catch returned the PARTIAL list")
    void aFailedListingRefuses() {
        when(client.queryView(eq("_repo"), eq("rssTokensByUserId"), anyMap()))
                .thenThrow(new RuntimeException("connection reset"));

        assertThrows(RuntimeException.class,
                () -> dao.getByUserId(REPO, "user-x"),
                "the rows read before the failure were served as the complete token list");
    }

    @Test
    @DisplayName("a failed delete refuses — 'Token deleted' must not be a lie")
    void aFailedDeleteRefuses() {
        // The write-side twin the round-33 review found after getById was fixed: the
        // swallow let the resource report "Token deleted" (and audit a success) while the
        // document survived — the revoked token reloaded on the next cache miss and kept
        // its feed access.
        jp.aegif.nemaki.model.couch.CouchRssToken existing =
                mock(jp.aegif.nemaki.model.couch.CouchRssToken.class);
        when(existing.getRevision()).thenReturn("1-abc");
        when(client.get(CouchRssToken.class, "token-9")).thenReturn(existing);
        org.mockito.Mockito.doThrow(new RuntimeException("connection reset"))
                .when(client).delete("token-9", "1-abc");

        assertThrows(IllegalStateException.class,
                () -> dao.delete(REPO, "token-9"));
    }

    @Test
    @DisplayName("a failed token VALIDATION lookup refuses — not 'invalid token'")
    void aFailedValidationLookupRefuses() {
        // getByToken feeds validateToken: null becomes 401 "Invalid or expired token".
        // Deny is the safe DIRECTION for an access check, but the statement is false —
        // the token may be valid while CouchDB times out. A 500 tells the subscriber to
        // retry instead of telling them their token is dead.
        when(client.queryView(eq("_repo"), eq("rssTokensByToken"), anyMap()))
                .thenThrow(new RuntimeException("connection reset"));

        assertThrows(IllegalStateException.class,
                () -> dao.getByToken(REPO, "feed-token-value"));
    }

    @Test
    @DisplayName("a failed getById refuses — null means 'no such token' to disable/delete")
    void aFailedGetByIdRefuses() {
        when(client.get(CouchRssToken.class, "token-1"))
                .thenThrow(new RuntimeException("connection reset"));

        assertThrows(IllegalStateException.class,
                () -> dao.getById(REPO, "token-1"),
                "a failed lookup reported 'Token not found', telling the admin a live "
                        + "token is already gone");
    }
}
