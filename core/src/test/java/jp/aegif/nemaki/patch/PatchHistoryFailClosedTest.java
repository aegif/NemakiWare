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
package jp.aegif.nemaki.patch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.PatchHistory;

/**
 * "I could not check whether this patch ran" must not be answered as "it did not run".
 *
 * <h2>What went wrong</h2>
 *
 * <p>{@code isApplied} used to return false from its catch block, with the comment "in case of
 * error, assume patch is not applied to allow re-execution". Re-execution is precisely the harm:
 * the patches are not all idempotent. bedroom ended up with TWO {@code .system} folders — the
 * original from the initial data and one created 2026-08-13 — and TWO history records for
 * {@code system-folder-setup-20250805}. Two objects answering to the path {@code /.system} breaks
 * CMIS path resolution; the TCK's rootFolderTest fails with "object fetched by id and object
 * fetched by path don't match".
 *
 * <p>The lookup underneath had the same shape: it returned {@code null} both for "no such record"
 * and for "the query failed", so the caller could not tell them apart. It now throws on failure,
 * and this class pins what {@code isApplied} does with that.
 *
 * <h2>Why skipping is the cheaper mistake</h2>
 *
 * <p>A patch that really has not run yet is applied on the next startup, once whatever made the
 * history unreadable — a design document mid-rebuild is the likely cause, and
 * {@code Patch_JoinedGroupsSingleEmit} rewrites exactly that document during a v3.3.0 upgrade —
 * has settled. Running a non-idempotent patch a second time cannot be undone by waiting.
 */
class PatchHistoryFailClosedTest {

    private static final String REPO = "bedroom";
    private static final String PATCH = "system-folder-setup-20250805";

    private PatchUtil utilWith(ContentDaoService dao) {
        PatchUtil util = new PatchUtil();
        util.setContentDaoService(dao);
        return util;
    }

    /** The fix: an unreadable history reports APPLIED, so the patch is skipped, not repeated. */
    @Test
    void anUnreadableHistoryIsNotReportedAsNotApplied() {
        ContentDaoService dao = mock(ContentDaoService.class);
        when(dao.getPatchHistoryByName(anyString(), anyString()))
                .thenThrow(new org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException(
                        "the patch view is rebuilding"));

        assertTrue(utilWith(dao).isApplied(REPO, PATCH),
                "answering 'not applied' when the history could not be read is what let "
                        + "system-folder-setup run twice and create a second .system folder");
    }

    /**
     * The other half. Without it, "always say applied" would pass the test above and silently
     * stop every patch from ever running.
     */
    @Test
    void anAbsentHistoryStillMeansNotApplied() {
        ContentDaoService dao = mock(ContentDaoService.class);
        when(dao.getPatchHistoryByName(anyString(), anyString())).thenReturn(null);

        assertFalse(utilWith(dao).isApplied(REPO, PATCH),
                "a genuinely missing record must still let the patch run — otherwise nothing "
                        + "is ever applied on a fresh repository");
    }

    /** A record that says the patch has not been applied is honoured as such. */
    @Test
    void aRecordThatSaysNotAppliedIsHonoured() {
        PatchHistory history = new PatchHistory();
        history.setIsApplied(false);
        ContentDaoService dao = mock(ContentDaoService.class);
        when(dao.getPatchHistoryByName(anyString(), anyString())).thenReturn(history);

        assertFalse(utilWith(dao).isApplied(REPO, PATCH));
    }

    /** And one that says it has been applied. */
    @Test
    void aRecordThatSaysAppliedIsHonoured() {
        PatchHistory history = new PatchHistory();
        history.setIsApplied(true);
        ContentDaoService dao = mock(ContentDaoService.class);
        when(dao.getPatchHistoryByName(anyString(), anyString())).thenReturn(history);

        assertTrue(utilWith(dao).isApplied(REPO, PATCH));
    }
}
