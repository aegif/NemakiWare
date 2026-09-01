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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;

/**
 * The set of names in a folder is complete, or it is refused.
 *
 * <h2>Why a SHORT list is worse here than an empty one</h2>
 *
 * <p>{@code getChildrenNames} feeds the CMIS name-uniqueness check. The empty case was closed
 * — {@code childrenNamesViewIsAlive} tells a genuinely empty folder from a view that is not
 * answering — and the per-row case was not: a row that IS there and carries no value was
 * dropped, so the list came back SHORT. Short is not empty, so the alive-probe never fires,
 * and the uniqueness check concludes "no conflict" for a name that is already taken. A
 * duplicate sibling name is the one outcome this delegate's own comments say nothing repairs.
 *
 * <p>Found by a sibling sweep: three readers of this same view fail closed and this was the
 * fourth.
 */
class ChildrenNamesAreNeverSilentlyShortTest {

    private static final String REPO = "bedroom";
    private static final String PARENT = "folder-1";

    private static ViewResult rowsWithValues(Object... values) {
        ViewResult result = mock(ViewResult.class);
        List<ViewResultRow> rows = new ArrayList<>();
        for (Object v : values) {
            ViewResultRow row = mock(ViewResultRow.class);
            when(row.getValue()).thenReturn(v);
            rows.add(row);
        }
        when(result.getRows()).thenReturn(rows);
        return result;
    }

    private static ContentDaoServiceImpl daoOver(CloudantClientWrapper client) {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        when(pool.getClient(anyString())).thenReturn(client);
        ContentDaoServiceImpl dao = new ContentDaoServiceImpl();
        dao.setConnectorPool(pool);
        return dao;
    }

    @Test
    @DisplayName("a row that carries no name refuses the listing")
    void aNamelessRowRefusesTheListing() {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        ViewResult mixed = rowsWithValues("report.pdf", null, "notes.txt");
        when(client.queryView(eq("_repo"), eq("childrenNames"), eq(PARENT))).thenReturn(mixed);
        ContentDaoServiceImpl dao = daoOver(client);

        CmisRuntimeException refused = assertThrows(CmisRuntimeException.class,
                () -> dao.getChildrenNames(REPO, PARENT),
                "a nameless row was dropped, so the name list came back SHORT — which the "
                        + "alive-probe cannot see and the uniqueness check reads as 'no "
                        + "conflict'");
        assertTrue(refused.getMessage().contains("carry no name"),
                "refused by the alive-probe rather than by the short-list guard: "
                        + refused.getMessage());
    }

    @Test
    @DisplayName("a complete listing still answers — the control")
    void aCompleteListingStillAnswers() {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        ViewResult complete = rowsWithValues("report.pdf", "notes.txt");
        when(client.queryView(eq("_repo"), eq("childrenNames"), eq(PARENT)))
                .thenReturn(complete);
        ContentDaoServiceImpl dao = daoOver(client);

        assertEquals(List.of("report.pdf", "notes.txt"), dao.getChildrenNames(REPO, PARENT),
                "the refusal arm broke the ordinary listing");
    }
}
