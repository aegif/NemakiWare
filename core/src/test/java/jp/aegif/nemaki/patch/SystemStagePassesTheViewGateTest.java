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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;

/**
 * The repository-independent stage of a patch is gated too.
 *
 * <h2>What "the system stage does no repository work" turned out to mean</h2>
 *
 * <p>{@code applySystemPatch()} ran before any gating, and that was recorded as a known hole
 * on the belief that the stage only logs. Counting them says otherwise: of 45 implementations,
 * eight touch a store. Seven create under stable ids — design documents, Mango index names,
 * {@code system_config_*}, migrated {@code docId}s — where CouchDB refuses a duplicate. The
 * eighth, {@code Patch_DefaultCloudDriveConnectorProfile}, asks
 * {@code exists("google-drive-default")}, which is answered by a MANGO SELECTOR, and then
 * saves under a GENERATED id. An index being rebuilt answers "no such connector", and a
 * second {@code google-drive-default} appears with nothing to reject it — the same shape as
 * the two {@code .system} folders, in the one stage the gate did not cover.
 *
 * <p>The stage belongs to no repository, so it is gated on all of them.
 */
class SystemStagePassesTheViewGateTest {

    private static class Repos extends RepositoryInfoMap {
        private final Set<String> repos;

        Repos(String... ids) {
            this.repos = new LinkedHashSet<>(java.util.List.of(ids));
        }

        @Override
        public Set<String> keys() {
            return repos;
        }
    }

    /** Answers the canary per repository, and counts how often it was asked. */
    private static class GatedPatchUtil extends PatchUtil {
        private final RepositoryInfoMap repos;
        private final Set<String> answering;

        GatedPatchUtil(RepositoryInfoMap repos, Set<String> answering) {
            this.repos = repos;
            this.answering = answering;
        }

        @Override
        public RepositoryInfoMap getRepositoryInfoMap() {
            return repos;
        }

        @Override
        public boolean cmisViewsAreAnswering(String repositoryId) {
            return answering.contains(repositoryId);
        }

        @Override
        protected boolean isApplied(String repositoryId, String name) {
            return false;
        }

        @Override
        protected void createPathHistory(String repositoryId, String name) {
            // no history store in this test
        }
    }

    private static class CountingPatch extends AbstractNemakiPatch {
        int systemStageRuns = 0;
        int perRepositoryRuns = 0;

        @Override
        protected void applySystemPatch() {
            systemStageRuns++;
        }

        @Override
        protected void applyPerRepositoryPatch(String repositoryId) {
            perRepositoryRuns++;
        }

        @Override
        public String getName() {
            return "test_system_stage_gate";
        }
    }

    @Test
    @DisplayName("one repository with silent views stops the system stage")
    void aSilentRepositoryStopsTheSystemStage() {
        Repos repos = new Repos("bedroom", "canopy");
        CountingPatch patch = new CountingPatch();
        patch.setPatchUtil(new GatedPatchUtil(repos, Set.of("bedroom")));

        boolean succeeded = patch.apply();

        assertEquals(0, patch.systemStageRuns,
                "the system stage ran while a repository's views were not answering — "
                        + "an existence check by Mango selector answers 'absent' there, and "
                        + "the create that follows has a generated id nothing can reject");
        assertFalse(succeeded, "the run reported success while a stage was skipped");
    }

    @Test
    @DisplayName("all repositories answering lets the system stage run — the control")
    void aHealthyDeploymentStillRunsTheSystemStage() {
        Repos repos = new Repos("bedroom", "canopy");
        CountingPatch patch = new CountingPatch();
        patch.setPatchUtil(new GatedPatchUtil(repos, Set.of("bedroom", "canopy")));

        boolean succeeded = patch.apply();

        assertEquals(1, patch.systemStageRuns,
                "the gate stopped a healthy deployment from patching at all");
        assertEquals(2, patch.perRepositoryRuns);
        assertTrue(succeeded);
    }

    @Test
    @DisplayName("the always-run override is gated too — behaviourally, not by spelling")
    void theAlwaysRunOverrideIsGated() {
        // Patch_WebAuthnCredentialViews re-implements apply() to run on every startup. The
        // view work in it really is idempotent; isApplied() and createPathHistory() are not,
        // and they were reached with no gate at all.
        //
        // This was a source lock first, and an audit showed a PARTIAL revert that kept it
        // green: leave the `if (!cmisViewsAreAnswering(...))` in place and drop only the
        // `continue;`. The strings are all still there and the history is still written.
        // Counting the writes cannot be fooled that way.
        Repos repos = new Repos("bedroom", "canopy");
        RecordingPatchUtil util = new RecordingPatchUtil(repos, Set.of("bedroom"));
        // applySystemPatch is counted too. The override gained a system-stage gate after a
        // review found it calling the stage before its own per-repository gate — and nothing
        // measured that half, because this patch's real applySystemPatch is a log line. A
        // later review named the gap; a counting subclass closes it.
        int[] systemStageRuns = new int[1];
        Patch_WebAuthnCredentialViews patch = new Patch_WebAuthnCredentialViews() {
            @Override
            protected void applySystemPatch() {
                systemStageRuns[0]++;
            }

            @Override
            protected void applyPerRepositoryPatch(String repositoryId) {
                // The view work itself needs a CouchDB client; this test is about the gate.
            }
        };
        patch.setPatchUtil(util);

        patch.apply();

        assertEquals(0, systemStageRuns[0],
                "the always-run override ran its SYSTEM stage while a repository's views "
                        + "were not answering — the half of its gate that nothing measured");
        assertEquals(List.of("bedroom"), util.historyWrites,
                "the always-run override wrote patch history for a repository whose views "
                        + "are not answering — isApplied() is a view-based existence check "
                        + "and createPathHistory() writes under a generated id, which is how "
                        + "one patch name ended up with two history rows");
    }

    /** Records which repositories got a history row. */
    private static class RecordingPatchUtil extends PatchUtil {
        private final RepositoryInfoMap repos;
        private final Set<String> answering;
        final List<String> historyWrites = new java.util.ArrayList<>();

        RecordingPatchUtil(RepositoryInfoMap repos, Set<String> answering) {
            this.repos = repos;
            this.answering = answering;
        }

        @Override
        public RepositoryInfoMap getRepositoryInfoMap() {
            return repos;
        }

        @Override
        public boolean cmisViewsAreAnswering(String repositoryId) {
            return answering.contains(repositoryId);
        }

        @Override
        protected boolean isApplied(String repositoryId, String name) {
            return false;
        }

        @Override
        protected void createPathHistory(String repositoryId, String name) {
            historyWrites.add(repositoryId);
        }
    }
}
