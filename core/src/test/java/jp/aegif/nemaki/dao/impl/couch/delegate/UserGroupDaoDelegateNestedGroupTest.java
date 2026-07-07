package jp.aegif.nemaki.dao.impl.couch.delegate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
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
 * Regression tests for nested-group expansion in
 * {@link UserGroupDaoDelegate#getJoinedGroupByUserId}.
 *
 * Background: the joinedDirectGroupsByGroupId view emits composite array keys
 * [groupId, n]. The old code passed startkey/endkey as pre-serialized Strings
 * ("[\"id\",0]"), which the Cloudant SDK sends as JSON string keys — these
 * never match array keys, so ancestor-group expansion silently returned
 * nothing and members of nested subgroups lost permissions granted to parent
 * groups (observed live: member of a section group inside a division group
 * was denied on a folder whose ACL granted the division group).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class UserGroupDaoDelegateNestedGroupTest {

    private static final String REPO_ID = "test-repo";

    @Mock private CloudantClientPool connectorPool;
    @Mock private DaoHelper daoHelper;
    @Mock private CloudantClientWrapper client;

    private UserGroupDaoDelegate delegate;

    @BeforeEach
    public void setUp() {
        delegate = new UserGroupDaoDelegate(connectorPool, daoHelper);
        when(connectorPool.getClient(REPO_ID)).thenReturn(client);
    }

    private ViewResult viewResultWithGroupIds(String... groupIds) {
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

    @Test
    public void expandsAncestorGroupsTransitively() {
        // miyata is a direct member of "sec"; "sec" is nested in "div"; "div" in "all"
        // (all ViewResult mocks are built BEFORE when() to avoid nested stubbing)
        ViewResult directResult = viewResultWithGroupIds("sec");
        ViewResult divResult = viewResultWithGroupIds("div");
        ViewResult allResult = viewResultWithGroupIds("all");
        ViewResult emptyResult = viewResultWithGroupIds();

        when(client.queryView(eq("_repo"), eq("joinedDirectGroupsByUserId"), anyMap()))
                .thenReturn(directResult);
        when(client.queryView(eq("_repo"), eq("joinedDirectGroupsByGroupId"), anyMap()))
                .thenAnswer(invocation -> {
                    Map<String, Object> params = invocation.getArgument(2);
                    Object startKey = params.get("startkey");
                    Object endKey = params.get("endkey");
                    // The view emits array keys [groupId, n]: both bounds MUST be Lists
                    // so the SDK serializes them as JSON arrays (a String key never
                    // matches an array key — that was the bug)
                    assertTrue(startKey instanceof List,
                            "startkey must be a List (JSON array), got: " + startKey.getClass());
                    assertTrue(endKey instanceof List,
                            "endkey must be a List (JSON array), got: " + endKey.getClass());
                    String groupId = String.valueOf(((List<?>) startKey).get(0));
                    switch (groupId) {
                        case "sec": return divResult;
                        case "div": return allResult;
                        default: return emptyResult;
                    }
                });

        List<String> joined = delegate.getJoinedGroupByUserId(REPO_ID, "miyata");

        assertNotNull(joined);
        assertTrue(joined.contains("sec"), "direct group must be included");
        assertTrue(joined.contains("div"), "parent of nested group must be included");
        assertTrue(joined.contains("all"), "transitive ancestor must be included");
    }

    @Test
    public void toleratesCircularNesting() {
        ViewResult directResult = viewResultWithGroupIds("a");
        ViewResult bResult = viewResultWithGroupIds("b");
        ViewResult aResult = viewResultWithGroupIds("a"); // cycle: b nests back into a

        when(client.queryView(eq("_repo"), eq("joinedDirectGroupsByUserId"), anyMap()))
                .thenReturn(directResult);
        when(client.queryView(eq("_repo"), eq("joinedDirectGroupsByGroupId"), anyMap()))
                .thenAnswer(invocation -> {
                    Map<String, Object> params = invocation.getArgument(2);
                    String groupId = String.valueOf(((List<?>) params.get("startkey")).get(0));
                    return "a".equals(groupId) ? bResult : aResult;
                });

        List<String> joined = delegate.getJoinedGroupByUserId(REPO_ID, "user1");

        assertTrue(joined.containsAll(Arrays.asList("a", "b")));
        // visited tracking must terminate the walk; each group appears once
        assertEquals(2, joined.size());
    }
}
