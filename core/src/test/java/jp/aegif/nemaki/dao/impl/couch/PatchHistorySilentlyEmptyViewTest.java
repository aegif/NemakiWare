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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.PatchHistory;

/**
 * A view that answers "nothing" without failing must not be read as "this patch never ran".
 *
 * <h2>Why the exception tests were not enough</h2>
 *
 * <p>The first fix made {@code getPatchHistoryByName} throw when the query FAILS, so a failure
 * could no longer masquerade as absence. But the failure that actually happened does not throw:
 * a CouchDB design document being rebuilt answers <b>HTTP 200 with zero rows</b>. That was
 * measured directly against the server while investigating the reindex wipe — a view whose map
 * function throws returns {@code {"total_rows":0,"rows":[]}} and status 200.
 *
 * <p>So the silent-empty path stayed open: the view says nothing, the DAO returns null,
 * {@code PatchUtil.isApplied} reads null as "not applied", and a non-idempotent patch runs a
 * second time. That is how bedroom ended up with two {@code .system} folders and two history
 * records for the same patch name. Fixing {@code .system} specifically (by reading the recorded
 * id from {@code nemaki_conf}) protects that one patch; every other non-idempotent patch was
 * still exposed.
 *
 * <p>The DAO now asks a second time through Mango ({@code _find}), which does not read
 * {@code _design/_repo} and therefore cannot be emptied by the same rebuild. Only when BOTH say
 * nothing is the history reported absent.
 */
class PatchHistorySilentlyEmptyViewTest {

    private static final String REPO = "bedroom";
    private static final String PATCH = "system-folder-setup-20250805";

    private CloudantClientWrapper client;

    private ContentDaoServiceImpl daoWithView(ViewResult viewResult) {
        client = mock(CloudantClientWrapper.class);
        when(client.queryView(anyString(), anyString(), any(Map.class))).thenReturn(viewResult);
        CloudantClientPool pool = mock(CloudantClientPool.class);
        when(pool.getClient(anyString())).thenReturn(client);
        ContentDaoServiceImpl dao = new ContentDaoServiceImpl();
        dao.setConnectorPool(pool);
        return dao;
    }

    private static ViewResult emptyView() {
        ViewResult result = mock(ViewResult.class);
        when(result.getRows()).thenReturn(List.<ViewResultRow>of());
        return result;
    }

    private static Map<String, Object> historyDoc() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("_id", "4383c1a96093a7526774f8d2db000c71");
        doc.put("type", "patch");
        doc.put("name", PATCH);
        doc.put("applied", Boolean.TRUE);
        return doc;
    }

    /**
     * The gap this closes: the view is silently empty, but the record exists.
     */
    @Test
    void aSilentlyEmptyViewDoesNotReportTheHistoryAsAbsent() {
        ContentDaoServiceImpl dao = daoWithView(emptyView());
        when(client.findRawBySelector(any(Map.class), anyInt())).thenReturn(List.of(historyDoc()));

        PatchHistory history = dao.getPatchHistoryByName(REPO, PATCH);

        assertNotNull(history,
                "the record exists and only the view could not see it — returning null here is "
                        + "what makes PatchUtil.isApplied re-run a non-idempotent patch");
        assertTrue(history.isApplied());
        assertEquals(PATCH, history.getName());
    }

    /**
     * The other half: on a genuinely fresh repository both lookups find nothing, and the patch
     * must be allowed to run. Without this, "never report absent" would pass the test above and
     * stop every patch from ever being applied.
     */
    @Test
    void aGenuinelyAbsentHistoryIsStillReportedAsAbsent() {
        ContentDaoServiceImpl dao = daoWithView(emptyView());
        when(client.findRawBySelector(any(Map.class), anyInt())).thenReturn(List.of());

        assertNull(dao.getPatchHistoryByName(REPO, PATCH),
                "a fresh repository has no history and the patch has to be allowed to run");
    }

    /**
     * The second opinion costs nothing on the normal path: when the view answers, Mango is never
     * asked. A fix that queried twice on every startup for every patch would be its own problem.
     */
    @Test
    void theSecondLookupIsNotUsedWhenTheViewAnswers() {
        ViewResultRow row = mock(ViewResultRow.class);
        com.ibm.cloud.cloudant.v1.model.Document doc = new com.ibm.cloud.cloudant.v1.model.Document();
        doc.setId("4383c1a96093a7526774f8d2db000c71");
        doc.put("type", "patch");
        doc.put("name", PATCH);
        doc.put("applied", Boolean.TRUE);
        when(row.getDoc()).thenReturn(doc);
        ViewResult result = mock(ViewResult.class);
        when(result.getRows()).thenReturn(List.of(row));

        ContentDaoServiceImpl dao = daoWithView(result);
        dao.getPatchHistoryByName(REPO, PATCH);

        verify(client, never()).findRawBySelector(any(Map.class), anyInt());
    }

    /**
     * If the second opinion cannot be obtained either, that is "I do not know" — and the caller
     * must not be handed a null it would read as "not applied".
     */
    @Test
    void aFailingSecondLookupIsReportedRatherThanTreatedAsAbsent() {
        ContentDaoServiceImpl dao = daoWithView(emptyView());
        when(client.findRawBySelector(any(Map.class), anyInt()))
                .thenThrow(new RuntimeException("Mango is unavailable"));

        assertThrows(org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException.class,
                () -> dao.getPatchHistoryByName(REPO, PATCH));
    }
}
