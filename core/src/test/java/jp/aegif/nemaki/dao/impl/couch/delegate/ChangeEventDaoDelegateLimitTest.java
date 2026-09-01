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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;

/**
 * A "give me everything" change request is clamped to what CouchDB accepts.
 *
 * <h2>The empty feed this replaces</h2>
 *
 * <p>CouchDB rejects a view limit above 2^28 with {@code query_parse_error}, and the old
 * fail-open catch turned that 400 into an empty list — so a client passing
 * {@code Integer.MAX_VALUE} (the TCK's content-changes smoke test does exactly this) read an
 * EMPTY change feed and believed the repository had no changes. The fail-closed rework made the
 * refusal visible, and the first live TCK run against it surfaced this: the request was never
 * servable, only silently unanswered. A first clamp to CouchDB's own wire cap (2^28) traded the
 * 400 for a heap OOM — the whole change log arrived as one response. The cap is therefore an
 * application page size; compileChangeDataList's per-event token means a clamped page never
 * strands the client.
 */
class ChangeEventDaoDelegateLimitTest {

    private static final String REPO = "bedroom";
    private static final int MAX_CHANGE_PAGE = 10_000;

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedParamsFor(int maxItems) {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(pool.getClient(REPO)).thenReturn(client);
        ViewResult empty = mock(ViewResult.class);
        when(empty.getRows()).thenReturn(new ArrayList<ViewResultRow>());
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        when(client.queryView(eq("_repo"), eq("changesByToken"), anyMap())).thenReturn(empty);

        new ChangeEventDaoDelegate(pool, mock(DaoHelper.class))
                .getLatestChanges(REPO, null, maxItems);

        org.mockito.Mockito.verify(client).queryView(eq("_repo"), eq("changesByToken"),
                params.capture());
        return params.getValue();
    }

    @Test
    @DisplayName("Integer.MAX_VALUE is clamped to one page instead of a 400 or a heap OOM")
    void aHugeLimitIsClamped() {
        // Both live failure shapes: above 2^28 CouchDB refuses with a 400 (the old fail-open
        // catch served that as an EMPTY feed); anything below the wire cap but above the
        // change log's size loads every row in one response — measured as a JVM heap OOM on
        // a repository with a few hundred thousand change rows. A page plus hasMoreItems
        // serves the same request without either.
        assertEquals(MAX_CHANGE_PAGE, capturedParamsFor(Integer.MAX_VALUE).get("limit"),
                "the view receives a limit that either CouchDB rejects or the heap cannot "
                        + "hold, instead of a servable page");
    }

    @Test
    @DisplayName("a non-positive limit is one page, not 'no limit'")
    void aNonPositiveLimitIsStillBounded() {
        // maxItems <= 0 used to mean NO limit param — an unbounded include_docs query over
        // the whole change log, reachable from /api/v1 (?maxItems=0), from OData, and from
        // any BigInteger that truncates to zero or negative.
        assertEquals(MAX_CHANGE_PAGE, capturedParamsFor(0).get("limit"),
                "a zero ask flowed through as an unbounded query");
        assertEquals(MAX_CHANGE_PAGE, capturedParamsFor(-5).get("limit"));
    }

    @Test
    @DisplayName("the singular latest-change lookup refuses on failure — not 'no changes'")
    void aFailedLatestChangeLookupRefuses() {
        // The empty-repository nulls live above the catch (startup-missing view, zero
        // rows); a FAILURE returned null too, and the FULL sync seeded an EMPTY
        // checkpoint over it and reported COMPLETED.
        CloudantClientPool pool = mock(CloudantClientPool.class);
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(pool.getClient(REPO)).thenReturn(client);
        when(client.queryView(eq("_repo"), eq("changesByToken"), anyMap()))
                .thenThrow(new RuntimeException("connection reset"));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new ChangeEventDaoDelegate(pool, mock(DaoHelper.class))
                        .getLatestChange(REPO),
                "a failed read of the latest change was served as 'there are no changes'");
    }

    @Test
    @DisplayName("an ordinary limit passes through untouched — the control")
    void anOrdinaryLimitPassesThrough() {
        assertEquals(100, capturedParamsFor(100).get("limit"));
    }
}
