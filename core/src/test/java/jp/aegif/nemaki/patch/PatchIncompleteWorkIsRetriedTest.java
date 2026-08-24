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

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A patch that returns WITHOUT doing its work must be retried, not recorded as applied.
 *
 * <h2>The hole</h2>
 *
 * <p>{@code apply()} writes the patch-history row the moment {@code applyPerRepositoryPatch}
 * returns without throwing, and never calls it again. Most patches in this package catch their
 * own failures and log them — so a patch that failed entirely was recorded as applied and never
 * ran again: the thing it was supposed to do never happened, silently, for the life of the
 * deployment (roadmap §2-2).
 *
 * <p>Throwing from every catch was why this was held as a breaking change: some of those catches
 * exist because the failure genuinely is tolerable, and turning them all into startup errors
 * would stop deployments that have always come up. {@code reportIncomplete} is the third answer
 * — the history row is withheld so the patch retries, the run reports itself unsuccessful, and
 * startup continues.
 */
class PatchIncompleteWorkIsRetriedTest {

    /** A patch whose body does whatever the test tells it to. */
    private static final class ScriptedPatch extends AbstractNemakiPatch {
        Runnable body = () -> { };
        int invocations;

        @Override public String getName() { return "scripted-patch"; }
        @Override protected void applySystemPatch() { }

        @Override
        protected void applyPerRepositoryPatch(String repositoryId) {
            invocations++;
            body.run();
        }

        void reportFromBody(String reason) {
            reportIncomplete(reason);
        }
    }

    private static ScriptedPatch patchOver(PatchUtil util) throws Exception {
        ScriptedPatch patch = new ScriptedPatch();
        Field f = AbstractNemakiPatch.class.getDeclaredField("patchUtil");
        f.setAccessible(true);
        f.set(patch, util);
        return patch;
    }

    private static PatchUtil utilFor(String repositoryId) {
        PatchUtil util = mock(PatchUtil.class);
        RepositoryInfoMap infoMap = mock(RepositoryInfoMap.class);
        when(infoMap.keys()).thenReturn(new java.util.LinkedHashSet<>(java.util.List.of(repositoryId)));
        when(infoMap.isArchiveRepository(repositoryId)).thenReturn(false);
        when(util.getRepositoryInfoMap()).thenReturn(infoMap);
        when(util.cmisViewsAreAnswering(anyString())).thenReturn(true);
        when(util.isApplied(anyString(), anyString())).thenReturn(false);
        return util;
    }

    @Test
    @DisplayName("a patch that reports incomplete work is NOT recorded as applied")
    void incompleteWorkWithholdsTheHistoryRow() throws Exception {
        PatchUtil util = utilFor("bedroom");
        ScriptedPatch patch = patchOver(util);
        patch.body = () -> patch.reportFromBody("TypeService was not available");

        boolean succeeded = patch.apply();

        assertFalse(succeeded, "a run that did not do its work reported success");
        verify(util, never()).createPathHistory(anyString(), anyString());
    }

    @Test
    @DisplayName("a patch that does its work IS recorded — the control")
    void completedWorkWritesTheHistoryRow() throws Exception {
        // Without this, an apply() that never wrote history would pass the test above.
        PatchUtil util = utilFor("bedroom");
        ScriptedPatch patch = patchOver(util);

        assertTrue(patch.apply());

        verify(util, times(1)).createPathHistory("bedroom", "scripted-patch");
    }

    @Test
    @DisplayName("the report does not leak into the NEXT repository in the same run")
    void theReportIsScopedToOneRepository() throws Exception {
        // The flag is set and cleared around each repository. If it leaked, one repository's
        // failure would withhold every later repository's history row too — turning a local
        // failure into a deployment-wide permanent retry.
        PatchUtil util = mock(PatchUtil.class);
        RepositoryInfoMap infoMap = mock(RepositoryInfoMap.class);
        when(infoMap.keys()).thenReturn(
                new java.util.LinkedHashSet<>(java.util.List.of("bedroom", "canopy")));
        when(infoMap.isArchiveRepository(anyString())).thenReturn(false);
        when(util.getRepositoryInfoMap()).thenReturn(infoMap);
        when(util.cmisViewsAreAnswering(anyString())).thenReturn(true);
        when(util.isApplied(anyString(), anyString())).thenReturn(false);

        ScriptedPatch patch = patchOver(util);
        patch.body = () -> {
            if (patch.invocations == 1) {
                patch.reportFromBody("first repository could not be patched");
            }
        };

        assertFalse(patch.apply(), "the run must still report itself unsuccessful");

        verify(util, never()).createPathHistory("bedroom", "scripted-patch");
        verify(util, times(1)).createPathHistory("canopy", "scripted-patch");
    }

    @Test
    @DisplayName("a patch that throws still fails, and still writes no history")
    void throwingStillFails() throws Exception {
        PatchUtil util = utilFor("bedroom");
        ScriptedPatch patch = patchOver(util);
        patch.body = () -> { throw new IllegalStateException("boom"); };

        assertFalse(patch.apply());

        verify(util, never()).createPathHistory(anyString(), anyString());
    }
}
