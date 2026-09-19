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
package jp.aegif.nemaki.dao.impl.couch.delegate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.UserItem;

/**
 * A row whose id disagrees with the index is not evidence that the user does not exist.
 *
 * <h2>The answer that created accounts</h2>
 *
 * <p>The {@code userItemsById} view is keyed on {@code userId}. When it matched a
 * {@code nemaki:user} row whose own {@code userId} is a DIFFERENT string, the index and the
 * document disagree — and the old code returned null, which every caller reads as "there is no
 * such user". Auto-provisioning creates an account on that answer and the directory sync
 * deletes on it, so a disagreement the store could not explain became either a second account
 * or a removed one.
 *
 * <p>The security half of that door is kept and measured: the mismatched document is never
 * handed back as the requested user. What changed is the sentence said instead — a refusal
 * rather than an absence.
 */
class UserLookupRefusesIndexDisagreementTest {

    private static final String REPO = "bedroom";

    private CloudantClientWrapper client;
    private UserGroupDaoDelegate delegate;

    private void wire() {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        client = mock(CloudantClientWrapper.class);
        when(pool.getClient(REPO)).thenReturn(client);
        delegate = new UserGroupDaoDelegate(pool, mock(DaoHelper.class));
    }

    @SafeVarargs
    private static ViewResult resultWithValues(Map<String, Object>... values) {
        ViewResult result = mock(ViewResult.class);
        List<ViewResultRow> rows = new ArrayList<>();
        for (Map<String, Object> value : values) {
            ViewResultRow row = mock(ViewResultRow.class);
            when(row.getValue()).thenReturn(value);
            rows.add(row);
        }
        when(result.getRows()).thenReturn(rows);
        return result;
    }

    private static Map<String, Object> userDocument(String userId) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("objectType", "nemaki:user");
        doc.put("userId", userId);
        doc.put("_id", "user-node-" + userId);
        doc.put("type", "user");
        doc.put("name", userId);
        return doc;
    }

    @Test
    @DisplayName("a matched row whose userId differs refuses — it does not say 'no such user'")
    void aMismatchedRowRefuses() {
        wire();
        ViewResult disagreeing = resultWithValues(userDocument("someone-else"));
        when(client.queryView(eq("_repo"), eq("userItemsById"), eq("kubota")))
                .thenReturn(disagreeing);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> delegate.getUserItemById(REPO, "kubota"),
                "the index and the document disagreed and the store answered 'that user "
                        + "does not exist' — auto-provisioning creates a second account on "
                        + "that answer and the directory sync deletes on it");
        assertEquals(true, refused.getMessage().contains("someone-else"),
                "the refusal no longer names the document it actually found: "
                        + refused.getMessage());
    }

    @Test
    @DisplayName("the GROUP twin refuses too — it was left answering null for 280 lines")
    void aMismatchedGroupRowRefuses() {
        // getUserItemById's mismatch arm was changed to refuse and getGroupItemById's — the
        // same check on the same view in the same file — was not. A sibling sweep found it.
        // Null there means "no such group": the nested-membership walk then drops every
        // permission granted through it, and the directory sync creates a duplicate.
        wire();
        java.util.Map<String, Object> row = new HashMap<>();
        row.put("groupId", "kubota");
        ViewResult matched = resultWithValues(row);
        when(matched.getRows().get(0).getId()).thenReturn("group-node-1");
        when(client.queryView(eq("_repo"), eq("groupItemsById"), eq("engineering"),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(matched);
        com.ibm.cloud.cloudant.v1.model.Document doc =
                mock(com.ibm.cloud.cloudant.v1.model.Document.class);
        when(doc.getId()).thenReturn("group-node-1");
        when(doc.getRev()).thenReturn("1-abc");
        java.util.Map<String, Object> props = new HashMap<>();
        props.put("groupId", "someone-else");
        props.put("objectType", "nemaki:group");
        when(doc.getProperties()).thenReturn(props);
        when(client.get("group-node-1")).thenReturn(doc);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> delegate.getGroupItemById(REPO, "engineering"),
                "the index and the document disagreed about a group and the store answered "
                        + "'no such group'");
        assertEquals(true, refused.getMessage().contains("someone-else"),
                "the refusal does not name what it found: " + refused.getMessage());
    }

    @Test
    @DisplayName("a user who does not exist reads as absent — the shape the real wrapper "
            + "returns, which the first version of this test got wrong")
    void anAbsentUserReadsAsAbsent() {
        wire();
        // NULL, not an empty-rows ViewResult. The keyed overload of queryView returns
        // `rows == 0 ? null : result`, so an empty-rows result is a value the production
        // wrapper NEVER produces. Stubbing it made this test pass while the delegate refused
        // every lookup of a user that does not exist — a login with an unknown username was
        // a 500 instead of a 401, and creating any group was impossible, because both go
        // through this call. A review caught it; the fixture had to match the wrapper.
        when(client.queryView(eq("_repo"), eq("userItemsById"), anyString()))
                .thenReturn(null);

        assertNull(delegate.getUserItemById(REPO, "nobody"),
                "a user that genuinely is not there must still read as absent, or no login "
                        + "can fail cleanly and nothing can ever be created");
    }

    @Test
    @DisplayName("a group that does not exist reads as absent too")
    void anAbsentGroupIsStillAbsence() {
        wire();
        when(client.queryView(eq("_repo"), eq("groupItemsById"), anyString(),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(null);

        assertNull(delegate.getGroupItemById(REPO, "nobody"),
                "validateNewGroup asks this before creating a group; refusing here makes "
                        + "creating any group impossible");
    }

    @Test
    @DisplayName("other objectTypes on the same key are still skipped — the kept arm")
    void otherObjectTypesAreStillSkipped() {
        wire();
        Map<String, Object> webauthn = new HashMap<>();
        webauthn.put("objectType", "nemaki:webauthnCredential");
        webauthn.put("userId", "kubota");
        ViewResult mixed = resultWithValues(webauthn, userDocument("kubota"));
        when(client.queryView(eq("_repo"), eq("userItemsById"), eq("kubota")))
                .thenReturn(mixed);

        UserItem found = delegate.getUserItemById(REPO, "kubota");
        assertNotNull(found, "a WebAuthn credential sharing the key stopped the user from "
                + "being found");
        assertEquals("kubota", found.getUserId());
    }
}
