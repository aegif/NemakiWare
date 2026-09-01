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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.dao.impl.couch.delegate.DaoHelper;
import jp.aegif.nemaki.dao.impl.couch.delegate.UserGroupDaoDelegate;

/**
 * A failed identity or policy lookup is not "there is no such user / group / policy".
 *
 * <h2>Why these five were left for last</h2>
 *
 * <p>The sweep that found them judged them lower risk than the destructive consumers, because
 * their callers terminate at a 404 or a 401 rather than deleting anything. That is true of the
 * consequence and false of the statement: a CouchDB hiccup was presented to the client as a
 * definite "this user does not exist", and the directory sync reads the same answer when it
 * decides whether an account is still there. Refusing costs a 500 the caller can retry.
 */
class IdentityAndPolicyLookupsRefuseFailuresTest {

    private static final String REPO = "bedroom";

    private static ContentDaoServiceImpl couchWith(CloudantClientWrapper client) {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        when(pool.getClient(REPO)).thenReturn(client);
        ContentDaoServiceImpl service = new ContentDaoServiceImpl();
        service.setConnectorPool(pool);
        return service;
    }

    private static UserGroupDaoDelegate delegateWith(CloudantClientWrapper client) {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        when(pool.getClient(REPO)).thenReturn(client);
        return new UserGroupDaoDelegate(pool, mock(DaoHelper.class));
    }

    @Test
    @DisplayName("a failed policy lookup refuses instead of answering 404")
    void aFailedPolicyLookupRefuses() {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.get(jp.aegif.nemaki.model.couch.CouchPolicy.class, "pol-1"))
                .thenThrow(new RuntimeException("connection reset"));

        assertThrows(IllegalStateException.class,
                () -> couchWith(client).getPolicy(REPO, "pol-1"));
    }

    @Test
    @DisplayName("a failed item lookup refuses")
    void aFailedItemLookupRefuses() {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.get(jp.aegif.nemaki.model.couch.CouchItem.class, "item-1"))
                .thenThrow(new RuntimeException("connection reset"));

        assertThrows(IllegalStateException.class,
                () -> couchWith(client).getItem(REPO, "item-1"));
    }

    @Test
    @DisplayName("a failed user lookup by object id refuses")
    void aFailedUserItemLookupRefuses() {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.get(jp.aegif.nemaki.model.couch.CouchUserItem.class, "user-doc-1"))
                .thenThrow(new RuntimeException("connection reset"));

        assertThrows(IllegalStateException.class,
                () -> delegateWith(client).getUserItem(REPO, "user-doc-1"));
    }

    @Test
    @DisplayName("a failed user lookup by userId refuses — the authentication input")
    void aFailedUserByIdLookupRefuses() {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.queryView(eq("_repo"), eq("userItemsById"), eq("miyata")))
                .thenThrow(new RuntimeException("connection reset"));

        assertThrows(IllegalStateException.class,
                () -> delegateWith(client).getUserItemById(REPO, "miyata"),
                "a CouchDB hiccup was served as 'that user does not exist'");
    }

    @Test
    @DisplayName("a failed group lookup by object id refuses")
    void aFailedGroupItemLookupRefuses() {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.get(jp.aegif.nemaki.model.couch.CouchGroupItem.class, "group-doc-1"))
                .thenThrow(new RuntimeException("connection reset"));

        assertThrows(IllegalStateException.class,
                () -> delegateWith(client).getGroupItem(REPO, "group-doc-1"));
    }

    @Test
    @DisplayName("a genuine absence is still null — the control")
    void aGenuineAbsenceIsStillNull() {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.get(jp.aegif.nemaki.model.couch.CouchPolicy.class, "pol-2"))
                .thenReturn(null);

        assertNull(couchWith(client).getPolicy(REPO, "pol-2"));
    }
}
