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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;

import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.GroupItem;

/**
 * The group lists read the documents the view emits, not a second copy (3.4.1).
 *
 * <p>{@code groupItemsById} is {@code emit(doc.groupId, doc)}. Both group-list reads asked for
 * {@code include_docs=true} on top, so CouchDB looked every group up again by id: 3.0 s for
 * 3,041 groups against 0.1 s for the values (nb33, 2026-10-12). The user list reads all groups
 * on every request to show memberships, so it took 3.3 s for a page of twenty — and the group
 * search and the permission screen paid the same. The fail-closed answers stay: a row that
 * carries no document, or will not decode, refuses.
 *
 * <p>The items are NOT the same as before in one respect, on purpose: they now carry the
 * document's {@code _id} and {@code _rev}. include_docs read the SDK Document's properties, which
 * hold neither (measured on core's classpath), so every caller that updated a listed group was
 * refused for want of a revision — on 3.4.0 the Jersey user API answered 500 for {@code groups},
 * and the LDAP sync wrote no group update (3.4.1 batch 7, review round 1).
 *
 * <p>The fixture is a {@code groupItemsById} row value as nb33 emitted it.
 */
class GroupListsReadTheEmittedDocumentsTest {

    private static final String REPO = "bedroom";
    private static final String EMITTED = "{\"_id\":\"b2bddf46cd4a3d09ae678809f78bc404\","
            + "\"_rev\":\"2-1271aa5fc9afc5e48e71d198c7e72ebb\","
            + "\"content_incarnation\":\"6dd28912-f233-4f26-b16c-828b26bfef62\",\"creator\":\"admin\","
            + "\"created\":1786253346377,\"document\":false,\"groupId\":\"c17082x55g21\",\"modifier\":\"admin\","
            + "\"aspects\":[],\"acl\":{\"entries\":[]},\"secondaryIds\":[],\"type\":\"cmis:item\",\"content\":false,"
            + "\"parentId\":\"78a28100-44d1-4318-8194-54aabba74c0e\",\"objectType\":\"nemaki:group\",\"folder\":false,"
            + "\"subTypeProperties\":[{\"value\":[\"alice\"],\"key\":\"nemaki:users\"},"
            + "{\"value\":[\"c17082x55g22\"],\"key\":\"nemaki:groups\"}],"
            + "\"attachment\":false,\"name\":\"c55-21\",\"modified\":1786253501112,\"relationship\":false,\"policy\":false}";

    private CloudantClientWrapper client;
    private UserGroupDaoDelegate delegate;

    @BeforeEach
    void setUp() {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        client = mock(CloudantClientWrapper.class);
        when(pool.getClient(REPO)).thenReturn(client);
        delegate = new UserGroupDaoDelegate(pool, new DaoHelper());
    }

    /** Parsed the way production gets it: by the Cloudant SDK's own Gson, not by Jackson. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> emitted() {
        return com.ibm.cloud.sdk.core.util.GsonSingleton.getGson().fromJson(EMITTED, Map.class);
    }

    private static ViewResultRow rowWithValue(Object value) {
        ViewResultRow row = mock(ViewResultRow.class);
        when(row.getValue()).thenReturn(value);
        return row;
    }

    private ArgumentCaptor<Map<String, Object>> answer(ViewResultRow... rows) {
        ViewResult result = mock(ViewResult.class);
        when(result.getRows()).thenReturn(new ArrayList<>(List.of(rows)));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        when(client.queryView(eq("_repo"), eq("groupItemsById"), anyMap())).thenReturn(result);
        return params;
    }

    private void assertTheEmittedGroup(GroupItem group) {
        assertEquals("b2bddf46cd4a3d09ae678809f78bc404", group.getId());
        // The revision too: callers update the groups they listed (the Jersey user API's
        // memberships, the LDAP directory sync), and the DAO refuses an update without one.
        assertEquals("2-1271aa5fc9afc5e48e71d198c7e72ebb", group.getRevision());
        assertEquals("c17082x55g21", group.getGroupId());
        assertEquals("c55-21", group.getName());
        assertEquals(List.of("alice"), group.getUsers());
        assertEquals(List.of("c17082x55g22"), group.getGroups());
    }

    @Test
    @DisplayName("all groups are read from the emitted documents, without asking for a second copy")
    void allGroupsAreReadFromTheEmittedDocuments() {
        ViewResultRow row = rowWithValue(emitted());
        ArgumentCaptor<Map<String, Object>> params = answer(row);

        List<GroupItem> groups = delegate.getGroupItems(REPO);

        verify(client).queryView(eq("_repo"), eq("groupItemsById"), params.capture());
        assertEquals(Boolean.FALSE, params.getValue().get("include_docs"),
                "the all-groups read asks CouchDB to look every group up again: " + params.getValue());
        verify(row, never()).getDoc();
        assertEquals(1, groups.size());
        assertTheEmittedGroup(groups.get(0));
    }

    @Test
    @DisplayName("a page of groups is read the same way, with its skip and limit")
    void aPageOfGroupsIsReadFromTheEmittedDocuments() {
        ViewResultRow row = rowWithValue(emitted());
        ArgumentCaptor<Map<String, Object>> params = answer(row);

        List<GroupItem> groups = delegate.getGroupItems(REPO, 40, 20);

        verify(client).queryView(eq("_repo"), eq("groupItemsById"), params.capture());
        assertEquals(Boolean.FALSE, params.getValue().get("include_docs"),
                "the paged groups read asks CouchDB to look every group up again: " + params.getValue());
        assertEquals(40, params.getValue().get("skip"));
        assertEquals(20, params.getValue().get("limit"));
        verify(row, never()).getDoc();
        assertTheEmittedGroup(groups.get(0));
    }

    @Test
    @DisplayName("a row that carries no document refuses, in both reads")
    void aRowWithoutADocumentRefuses() {
        answer(rowWithValue(emitted()), rowWithValue(null));
        // Said as what it is — a group item that exists and carries nothing to read — not as a
        // decoding failure.
        for (Executable read : List.<Executable>of(() -> delegate.getGroupItems(REPO),
                () -> delegate.getGroupItems(REPO, 0, 20))) {
            IllegalStateException refused = assertThrows(IllegalStateException.class, read);
            assertTrue(String.valueOf(refused.getCause()).contains("carries no document"),
                    "refused as something else: " + refused + " / " + refused.getCause());
        }

        answer(rowWithValue("not a document"));
        assertThrows(IllegalStateException.class, () -> delegate.getGroupItems(REPO));
        assertThrows(IllegalStateException.class, () -> delegate.getGroupItems(REPO, 0, 20));
    }

    @Test
    @DisplayName("a document that will not decode refuses, in both reads")
    void anUndecodableDocumentRefuses() {
        Map<String, Object> broken = emitted();
        broken.put("_id", Map.of("an", "object where the id is a string"));
        answer(rowWithValue(emitted()), rowWithValue(broken));
        assertThrows(IllegalStateException.class, () -> delegate.getGroupItems(REPO));
        assertThrows(IllegalStateException.class, () -> delegate.getGroupItems(REPO, 0, 20));
    }

    @Test
    @DisplayName("a view that answers without rows refuses, in both reads")
    void anAnswerWithoutRowsRefuses() {
        ViewResult result = mock(ViewResult.class);
        when(result.getRows()).thenReturn(null);
        when(client.queryView(eq("_repo"), eq("groupItemsById"), anyMap())).thenReturn(result);
        assertThrows(IllegalStateException.class, () -> delegate.getGroupItems(REPO));
        assertThrows(IllegalStateException.class, () -> delegate.getGroupItems(REPO, 0, 20));
    }
}
