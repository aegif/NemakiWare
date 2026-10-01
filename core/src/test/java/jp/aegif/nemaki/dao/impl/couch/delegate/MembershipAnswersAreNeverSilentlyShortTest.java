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
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;

/**
 * A user's group membership and a group's reverse lookup are never answered SHORT.
 *
 * <h2>Why "safe in direction" is still wrong</h2>
 *
 * <p>{@code getJoinedGroupByUserId} decides what a user may see. Every arm that turned a failure
 * into a smaller answer — a view that answered without rows, a row without a value, a row that
 * would not decode, any exception at all — shrank the user's permissions silently: no error, no
 * log the user sees, just documents that vanish from their listings. The direction is safe
 * (nobody GAINS access), but the statement is false, and the review that found it put the twin
 * cases plainly: the DIRECT half had been argued about for three rounds while the NESTED half
 * ({@code checkIndirectGroup}) kept every one of the same arms open.
 *
 * <p>{@code parentGroupIdsFrom} feeds deletion: the callers strip a deleted principal from every
 * group that references it. Its own javadoc argued that a partial list means "a delete that
 * reports success and leaves a dangling reference" — and then the null-result arm returned
 * empty anyway. The javadoc was right; the code now follows it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MembershipAnswersAreNeverSilentlyShortTest {

    private static final String REPO = "bedroom";

    @Mock
    private CloudantClientPool connectorPool;
    @Mock
    private DaoHelper daoHelper;
    @Mock
    private CloudantClientWrapper client;

    private UserGroupDaoDelegate delegate;

    @BeforeEach
    void setUp() {
        delegate = new UserGroupDaoDelegate(connectorPool, daoHelper);
        when(connectorPool.getClient(REPO)).thenReturn(client);
    }

    private static ViewResult resultWithGroupIds(String... groupIds) {
        ViewResult result = mock(ViewResult.class);
        List<ViewResultRow> rows = new ArrayList<>();
        for (String groupId : groupIds) {
            ViewResultRow row = mock(ViewResultRow.class);
            Map<String, Object> doc = new HashMap<>();
            doc.put("groupId", groupId);
            when(row.getValue()).thenReturn(doc);
            rows.add(row);
        }
        when(result.getRows()).thenReturn(rows);
        return result;
    }

    private static ViewResult resultWithNullRows() {
        ViewResult result = mock(ViewResult.class);
        when(result.getRows()).thenReturn(null);
        return result;
    }

    private static ViewResult resultWithValues(Object... values) {
        ViewResult result = mock(ViewResult.class);
        List<ViewResultRow> rows = new ArrayList<>();
        for (Object value : values) {
            ViewResultRow row = mock(ViewResultRow.class);
            when(row.getValue()).thenReturn(value);
            rows.add(row);
        }
        when(result.getRows()).thenReturn(rows);
        return result;
    }

    // ---- getJoinedGroupByUserId: the DIRECT half ----

    @Test
    @DisplayName("joined groups: a view that answers without rows refuses, not 'no groups'")
    void joinedGroupsNullRowsRefuse() {
        // Built BEFORE when(): evaluating it inside thenReturn() opens a nested stubbing.
        ViewResult unanswered = resultWithNullRows();
        when(client.queryView(eq("_repo"), eq("joinedDirectGroupsByUserId"), anyMap()))
                .thenReturn(unanswered);

        assertThrows(IllegalStateException.class,
                () -> delegate.getJoinedGroupByUserId(REPO, "miyata"),
                "an unanswered membership view was served as 'belongs to nothing', and the "
                        + "user silently lost every permission their groups granted");
    }

    @Test
    @DisplayName("joined groups: a row without a value refuses the whole answer")
    void joinedGroupsNullValueRefuses() {
        ViewResult valuelessRow = resultWithValues((Object) null);
        when(client.queryView(eq("_repo"), eq("joinedDirectGroupsByUserId"), anyMap()))
                .thenReturn(valuelessRow);

        assertThrows(IllegalStateException.class,
                () -> delegate.getJoinedGroupByUserId(REPO, "miyata"),
                "a membership row with no value was skipped, shrinking the membership");
    }

    @Test
    @DisplayName("joined groups: a row that will not decode refuses, not warn-and-skip")
    void joinedGroupsUndecodableRowRefuses() {
        // Not a Map — the cast inside the loop throws, which the old code warned and swallowed.
        ViewResult undecodableRow = resultWithValues("not-a-map");
        when(client.queryView(eq("_repo"), eq("joinedDirectGroupsByUserId"), anyMap()))
                .thenReturn(undecodableRow);

        assertThrows(IllegalStateException.class,
                () -> delegate.getJoinedGroupByUserId(REPO, "miyata"),
                "an unreadable membership row was warn-skipped, shrinking the membership");
    }

    @Test
    @DisplayName("joined groups: an infrastructure failure refuses, not an empty list")
    void joinedGroupsOuterFailureRefuses() {
        when(client.queryView(eq("_repo"), eq("joinedDirectGroupsByUserId"), anyMap()))
                .thenThrow(new RuntimeException("connection reset"));

        assertThrows(IllegalStateException.class,
                () -> delegate.getJoinedGroupByUserId(REPO, "miyata"),
                "a failed membership resolution returned an empty list — 'could not ask' "
                        + "constructed as the same value as 'asked, belongs to nothing'");
    }

    @Test
    @DisplayName("joined groups: a row that decodes but has no groupId refuses")
    void joinedGroupsRowWithoutGroupIdRefuses() {
        // The arm the round-32 review found open after the other four were closed: a Map row
        // with no usable groupId was skipped by `if (groupId != null)` — a membership row the
        // answer silently lost, same as a decode failure.
        ViewResult noIdRow = resultWithValues(new HashMap<String, Object>());
        when(client.queryView(eq("_repo"), eq("joinedDirectGroupsByUserId"), anyMap()))
                .thenReturn(noIdRow);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> delegate.getJoinedGroupByUserId(REPO, "miyata"),
                "a membership row without a groupId was skipped, shrinking the membership");
        assertTrue(e.getMessage().contains("usable groupId"),
                "the specific arm's message was re-wrapped into the generic 'could not be "
                        + "read', burying what actually happened: " + e.getMessage());
    }

    // ---- getJoinedGroupByUserId: the NESTED half (checkIndirectGroup) ----

    @Test
    @DisplayName("nested expansion: a hierarchy row without a value refuses — the twin of HK")
    void nestedExpansionNullValueRefuses() {
        // Found by the round-32 discrimination review: the arm EXISTED but nothing measured
        // it — reverting it to a skip left all twelve tests green.
        ViewResult direct = resultWithGroupIds("sec");
        ViewResult valuelessRow = resultWithValues((Object) null);
        when(client.queryView(eq("_repo"), eq("joinedDirectGroupsByUserId"), anyMap()))
                .thenReturn(direct);
        when(client.queryView(eq("_repo"), eq("joinedDirectGroupsByGroupId"), anyMap()))
                .thenReturn(valuelessRow);

        assertThrows(IllegalStateException.class,
                () -> delegate.getJoinedGroupByUserId(REPO, "miyata"),
                "a valueless nesting row was skipped in the nested half only");
    }

    @Test
    @DisplayName("nested expansion: a hierarchy row without a groupId refuses")
    void nestedExpansionRowWithoutGroupIdRefuses() {
        ViewResult direct = resultWithGroupIds("sec");
        ViewResult noIdRow = resultWithValues(new HashMap<String, Object>());
        when(client.queryView(eq("_repo"), eq("joinedDirectGroupsByUserId"), anyMap()))
                .thenReturn(direct);
        when(client.queryView(eq("_repo"), eq("joinedDirectGroupsByGroupId"), anyMap()))
                .thenReturn(noIdRow);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> delegate.getJoinedGroupByUserId(REPO, "miyata"),
                "a nesting row without a groupId was skipped, losing nested permissions");
        assertTrue(e.getMessage().contains("usable groupId"),
                "the nested arm's message was re-wrapped: " + e.getMessage());
    }


    @Test
    @DisplayName("nested expansion: a hierarchy view that answers without rows refuses")
    void nestedExpansionNullRowsRefuse() {
        ViewResult direct = resultWithGroupIds("sec");
        ViewResult unanswered = resultWithNullRows();
        when(client.queryView(eq("_repo"), eq("joinedDirectGroupsByUserId"), anyMap()))
                .thenReturn(direct);
        when(client.queryView(eq("_repo"), eq("joinedDirectGroupsByGroupId"), anyMap()))
                .thenReturn(unanswered);

        assertThrows(IllegalStateException.class,
                () -> delegate.getJoinedGroupByUserId(REPO, "miyata"),
                "the direct half refuses this arm but the nested half passed it as 'no "
                        + "parents' — permissions granted through nesting silently vanish");
    }

    @Test
    @DisplayName("nested expansion: an unreadable hierarchy row refuses")
    void nestedExpansionUndecodableRowRefuses() {
        ViewResult direct = resultWithGroupIds("sec");
        ViewResult undecodableRow = resultWithValues("not-a-map");
        when(client.queryView(eq("_repo"), eq("joinedDirectGroupsByUserId"), anyMap()))
                .thenReturn(direct);
        when(client.queryView(eq("_repo"), eq("joinedDirectGroupsByGroupId"), anyMap()))
                .thenReturn(undecodableRow);

        assertThrows(IllegalStateException.class,
                () -> delegate.getJoinedGroupByUserId(REPO, "miyata"),
                "an unreadable nesting row was warn-skipped in the nested half only");
    }

    @Test
    @DisplayName("nested expansion: a hierarchy view failure refuses, not a partial answer")
    void nestedExpansionFailureRefuses() {
        ViewResult direct = resultWithGroupIds("sec");
        when(client.queryView(eq("_repo"), eq("joinedDirectGroupsByUserId"), anyMap()))
                .thenReturn(direct);
        when(client.queryView(eq("_repo"), eq("joinedDirectGroupsByGroupId"), anyMap()))
                .thenThrow(new RuntimeException("connection reset"));

        assertThrows(IllegalStateException.class,
                () -> delegate.getJoinedGroupByUserId(REPO, "miyata"),
                "a failed nesting expansion was warn-skipped per group, so the membership "
                        + "kept the direct groups and silently lost the nested ones");
    }

    @Test
    @DisplayName("joined groups: the ordinary resolution still works — the control")
    void ordinaryResolutionStillWorks() {
        ViewResult direct = resultWithGroupIds("sec");
        ViewResult parents = resultWithGroupIds("div");
        ViewResult empty = resultWithGroupIds();
        when(client.queryView(eq("_repo"), eq("joinedDirectGroupsByUserId"), anyMap()))
                .thenReturn(direct);
        when(client.queryView(eq("_repo"), eq("joinedDirectGroupsByGroupId"), anyMap()))
                .thenAnswer(invocation -> {
                    Map<String, Object> params = invocation.getArgument(2);
                    String groupId = String.valueOf(((List<?>) params.get("startkey")).get(0));
                    return "sec".equals(groupId) ? parents : empty;
                });

        List<String> joined = delegate.getJoinedGroupByUserId(REPO, "miyata");

        assertTrue(joined.contains("sec") && joined.contains("div"),
                "the refusal arms broke the ordinary resolution: " + joined);
    }

    // ---- getGroupItemById: the re-fetch the principal delete and membership updates use ----

    @Test
    @DisplayName("a group whose direct read fails refuses — not 'no such group'")
    void aFailedGroupRefetchRefuses() {
        // The internal read's catch answered null for any failure, and the callers treat
        // null as "the group does not exist": the membership-update retry loop skipped the
        // change and reported success. (The principal delete now aborts on null — safe —
        // but the STATEMENT was still false.)
        ViewResultRow row = mock(ViewResultRow.class);
        when(row.getId()).thenReturn("doc-g1");
        ViewResult lookup = mock(ViewResult.class);
        when(lookup.getRows()).thenReturn(List.of(row));
        when(client.queryView(eq("_repo"), eq("groupItemsById"), eq("group-1"),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(lookup);
        when(client.get("doc-g1")).thenThrow(new RuntimeException("connection reset"));

        assertThrows(IllegalStateException.class,
                () -> delegate.getGroupItemById(REPO, "group-1"));
    }

    @Test
    @DisplayName("a group that exists but is missing required fields refuses")
    void anUnusableExistingGroupRefuses() {
        // The fresh read RETURNED the document — the group exists — but required fields
        // were missing and the old arm said "no such group".
        ViewResultRow row = mock(ViewResultRow.class);
        when(row.getId()).thenReturn("doc-g2");
        ViewResult lookup = mock(ViewResult.class);
        when(lookup.getRows()).thenReturn(List.of(row));
        when(client.queryView(eq("_repo"), eq("groupItemsById"), eq("group-2"),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(lookup);
        com.ibm.cloud.cloudant.v1.model.Document bare =
                mock(com.ibm.cloud.cloudant.v1.model.Document.class);
        when(bare.getId()).thenReturn("doc-g2");
        when(bare.getRev()).thenReturn(null);
        java.util.Map<String, Object> props = new HashMap<>();
        props.put("groupId", "group-2");
        when(bare.getProperties()).thenReturn(props);
        when(client.get("doc-g2")).thenReturn(bare);

        assertThrows(IllegalStateException.class,
                () -> delegate.getGroupItemById(REPO, "group-2"),
                "an existing-but-unusable group was answered as 'does not exist'");
    }

    // ---- parentGroupIdsFrom via its public callers: the DELETE-time reverse lookup ----

    @Test
    @DisplayName("reverse lookup (group): an unanswered view refuses, not 'nothing references it'")
    void reverseLookupGroupNullResultRefuses() {
        when(client.queryView(eq("_repo"), eq("joinedDirectGroupsByGroupId"), anyMap()))
                .thenReturn(null);

        assertThrows(CmisRuntimeException.class,
                () -> delegate.getGroupIdsDirectlyContainingGroup(REPO, "sec"),
                "the javadoc's own argument — a partial list means a delete that reports "
                        + "success and leaves a dangling nested reference — and this arm "
                        + "returned empty anyway");
    }

    @Test
    @DisplayName("reverse lookup (group): a view that answers without rows refuses")
    void reverseLookupGroupNullRowsRefuse() {
        ViewResult unanswered = resultWithNullRows();
        when(client.queryView(eq("_repo"), eq("joinedDirectGroupsByGroupId"), anyMap()))
                .thenReturn(unanswered);

        assertThrows(CmisRuntimeException.class,
                () -> delegate.getGroupIdsDirectlyContainingGroup(REPO, "sec"));
    }

    @Test
    @DisplayName("reverse lookup (user): the user-side twin refuses the same arm")
    void reverseLookupUserNullRowsRefuse() {
        ViewResult unanswered = resultWithNullRows();
        when(client.queryView(eq("_repo"), eq("joinedDirectGroupsByUserId"), anyMap()))
                .thenReturn(unanswered);

        assertThrows(CmisRuntimeException.class,
                () -> delegate.getGroupIdsDirectlyContainingUser(REPO, "miyata"),
                "the group twin was fixed and the user twin kept the defect — the batch's "
                        + "recurring shape");
    }

    @Test
    @DisplayName("reverse lookup: ordinary rows still resolve — the control")
    void reverseLookupStillResolves() {
        ViewResult parentRows = resultWithGroupIds("div");
        when(client.queryView(eq("_repo"), eq("joinedDirectGroupsByGroupId"), anyMap()))
                .thenReturn(parentRows);

        List<String> parents = delegate.getGroupIdsDirectlyContainingGroup(REPO, "sec");

        assertEquals(List.of("div"), parents,
                "the refusal arms broke the ordinary reverse lookup");
    }
}
