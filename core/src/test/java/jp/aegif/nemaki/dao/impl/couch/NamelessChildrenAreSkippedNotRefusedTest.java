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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;

/**
 * A child with no name is skipped, and that is not a short answer.
 *
 * <p>This class was called {@code ChildrenNamesAreNeverSilentlyShortTest} while it asserted
 * exactly the opposite — a three-row view yielding a two-element list, deliberately. The
 * refusal it was written for was withdrawn (below) and the name was not followed, which is
 * the same defect class the batch had just corrected elsewhere. A review caught it.
 *
 * <h2>A refusal that was wrong, kept as a lock so it is not re-added</h2>
 *
 * <p>{@code getChildrenNames} feeds the CMIS name-uniqueness check, and the empty case is
 * closed — {@code childrenNamesViewIsAlive} tells a genuinely empty folder from a view that
 * is not answering. A sibling sweep proposed closing the per-row case too, on the reasoning
 * that a dropped row makes the list SHORT rather than empty, so the alive-probe never fires
 * and the uniqueness check concludes "no conflict".
 *
 * <p>That reasoning does not hold for this view. It is
 * {@code emit(doc.parentId, doc.name)} — the value IS the name, with no decoding in between
 * — so a null value means the document HAS NO NAME. That is a fact about the child, and
 * nameless objects exist in this system (the filesystem exporter skips them by the same
 * rule). A nameless child cannot collide with a name, so dropping it does not weaken the
 * check; refusing would have made every create in a folder containing one impossible.
 *
 * <p>The refusal was written, then withdrawn before it shipped. This test pins the corrected
 * behaviour so the next sweep does not re-derive the same wrong conclusion.
 */
class NamelessChildrenAreSkippedNotRefusedTest {

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
    @DisplayName("a child with no name is skipped, not refused — the over-correction that "
            + "was caught before it shipped")
    void aNamelessChildIsSkippedNotRefused() {
        // This was briefly a refusal. The view is `emit(doc.parentId, doc.name)`: the value
        // IS the name, with no decode step, so a null value means the CHILD HAS NO NAME —
        // a fact about the child, not a read that failed. Nameless objects exist here and
        // are tolerated elsewhere. Refusing would have made every create in a folder that
        // contains one impossible, and it would not have protected the uniqueness check,
        // because a nameless child cannot collide with a name.
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        ViewResult mixed = rowsWithValues("report.pdf", null, "notes.txt");
        when(client.queryView(eq("_repo"), eq("childrenNames"), eq(PARENT))).thenReturn(mixed);
        ContentDaoServiceImpl dao = daoOver(client);

        assertEquals(List.of("report.pdf", "notes.txt"), dao.getChildrenNames(REPO, PARENT),
                "a nameless child either refused the listing or was counted as a name");
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
                "the skip dropped a named child as well as the nameless one");
    }
}
