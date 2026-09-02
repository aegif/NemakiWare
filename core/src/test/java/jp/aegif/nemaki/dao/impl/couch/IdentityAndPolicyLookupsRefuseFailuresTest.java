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
    @DisplayName("a user who is not there reads as absent — this test used to assert the "
            + "opposite, and that broke login")
    void anAbsentUserIsNotARefusal() {
        // WITHDRAWN CONTRACT. This method used to assert that a null view result REFUSES,
        // on the reading that null meant "the view did not answer". It does not: the keyed
        // overload of queryView returns `rows == 0 ? null : result`, so null is "no row
        // carries this userId" — the ordinary answer for a user who does not exist.
        //
        // With the refusal in place, a login with an unknown username was a 500 instead of a
        // 401 (and never reached the throttle), and `validateNewGroup`'s "does this already
        // exist?" pre-check made creating any group impossible. A review found it; this test
        // had been holding the defect in place.
        //
        // The property it was reaching for is real and still holds — it just lives one layer
        // down: a design document that is not deployed makes the WRAPPER throw, outside the
        // startup window (CloudantClientWrapper), so the DAO never sees that case as null.
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.queryView(eq("_repo"), eq("userItemsById"), eq("miyata")))
                .thenReturn(null);

        // assertDoesNotThrow, so that a restored refusal fails on THIS assertion rather
        // than escaping as an exception the runner scores as harness breakage.
        var found = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> delegateWith(client).getUserItemById(REPO, "miyata"),
                "a user that genuinely is not there was reported as a failed read, so no "
                        + "login can fail cleanly and no group can be created");
        org.junit.jupiter.api.Assertions.assertNull(found);
    }

    @Test
    @DisplayName("a user row with an unreadable shape refuses instead of being skipped")
    void anUnreadableUserRowRefuses() {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        com.ibm.cloud.cloudant.v1.model.ViewResultRow row =
                mock(com.ibm.cloud.cloudant.v1.model.ViewResultRow.class);
        when(row.getValue()).thenReturn("not-a-map");
        com.ibm.cloud.cloudant.v1.model.ViewResult result =
                mock(com.ibm.cloud.cloudant.v1.model.ViewResult.class);
        when(result.getRows()).thenReturn(java.util.List.of(row));
        when(client.queryView(eq("_repo"), eq("userItemsById"), eq("miyata")))
                .thenReturn(result);

        assertThrows(IllegalStateException.class,
                () -> delegateWith(client).getUserItemById(REPO, "miyata"),
                "the row the answer hinges on was skipped, narrowing the search to the "
                        + "rows that happened to decode");
    }

    @Test
    @DisplayName("a group that is not there reads as absent too — the twin of the same "
            + "withdrawn contract")
    void anAbsentGroupIsNotARefusal() {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.queryView(eq("_repo"), eq("groupItemsById"), eq("sec"),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(null);

        var found = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> delegateWith(client).getGroupItemById(REPO, "sec"),
                "validateNewGroup asks this before creating a group; refusing here makes "
                        + "creating any group impossible");
        org.junit.jupiter.api.Assertions.assertNull(found);
    }

    @Test
    @DisplayName("an existing but unusable user document refuses — not 'no such user'")
    void anUnusableExistingUserRefuses() {
        // The document IS there (the view returned it, it is the right user), but it cannot
        // be made into a UserItem. Answering null says the user does not exist, which
        // authentication and the directory sync act on. The runner found this arm
        // unmeasured: the existing test drives the CATCH, not this branch.
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        java.util.Map<String, Object> bare = new java.util.HashMap<>();
        bare.put("objectType", "nemaki:user");
        bare.put("userId", "miyata");   // right user, but no id / type
        com.ibm.cloud.cloudant.v1.model.ViewResultRow row =
                mock(com.ibm.cloud.cloudant.v1.model.ViewResultRow.class);
        when(row.getValue()).thenReturn(bare);
        com.ibm.cloud.cloudant.v1.model.ViewResult result =
                mock(com.ibm.cloud.cloudant.v1.model.ViewResult.class);
        when(result.getRows()).thenReturn(java.util.List.of(row));
        when(client.queryView(eq("_repo"), eq("userItemsById"), eq("miyata")))
                .thenReturn(result);

        assertThrows(IllegalStateException.class,
                () -> delegateWith(client).getUserItemById(REPO, "miyata"),
                "an existing-but-unusable user document was answered as 'does not exist'");
    }

    @Test
    @DisplayName("a view that ANSWERED with no rows is still 'not there' — the control")
    void anEmptyAnswerIsStillAbsence() {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        // NULL, not an empty-rows ViewResult. The keyed overload of queryView returns
        // `rows == 0 ? null : result` (CloudantClientWrapper), so an empty-rows result is a
        // value production NEVER produces, and a test that stubs one is measuring a state
        // that cannot occur. Two sibling tests were rewritten for exactly this after the
        // shape hid a login-breaking refusal for a round; this third one, in the same
        // package, was missed by that sweep and found by a later audit of the fixtures.
        when(client.queryView(eq("_repo"), eq("userItemsById"), eq("ghost")))
                .thenReturn(null);

        assertNull(delegateWith(client).getUserItemById(REPO, "ghost"),
                "the refusal arms broke the ordinary 'that user is not there' answer — an "
                        + "unknown username can then only fail as a 500, and nothing that "
                        + "asks 'does this already exist?' can create anything");
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
