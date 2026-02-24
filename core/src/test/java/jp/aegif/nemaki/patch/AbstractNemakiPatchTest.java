package jp.aegif.nemaki.patch;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;

/**
 * Tests for AbstractNemakiPatch.apply() failure propagation.
 *
 * Verifies:
 * 1. apply() returns false when applyPerRepositoryPatch throws
 * 2. createPathHistory is NOT called for failed repositories
 * 3. apply() returns true when all repositories succeed
 * 4. Already-applied patches are skipped without affecting success status
 *
 * Uses hand-written stubs instead of Mockito to avoid JVM attach dependency
 * (Byte Buddy inline mock maker requires -XX:+EnableDynamicAgentLoading).
 */
public class AbstractNemakiPatchTest {

    // ========================================================================
    // Stub: RepositoryInfoMap that returns a configurable set of repository IDs
    // ========================================================================
    private static class StubRepositoryInfoMap extends RepositoryInfoMap {
        private final Set<String> repos;

        StubRepositoryInfoMap(Set<String> repos) {
            this.repos = repos;
        }

        @Override
        public Set<String> keys() {
            return repos;
        }
    }

    // ========================================================================
    // Stub: PatchUtil that records isApplied/createPathHistory calls
    // ========================================================================
    private static class StubPatchUtil extends PatchUtil {
        private final Set<String> appliedPatches; // "repoId:patchName" entries
        private final List<String> createPathHistoryCalls = new ArrayList<>();
        private final RepositoryInfoMap repoInfoMap;

        StubPatchUtil(Set<String> appliedPatches, RepositoryInfoMap repoInfoMap) {
            this.appliedPatches = appliedPatches;
            this.repoInfoMap = repoInfoMap;
        }

        @Override
        public RepositoryInfoMap getRepositoryInfoMap() {
            return repoInfoMap;
        }

        @Override
        protected boolean isApplied(String repositoryId, String name) {
            return appliedPatches.contains(repositoryId + ":" + name);
        }

        @Override
        protected void createPathHistory(String repositoryId, String name) {
            createPathHistoryCalls.add(repositoryId + ":" + name);
        }

        boolean wasCreatePathHistoryCalled(String repositoryId, String name) {
            return createPathHistoryCalls.contains(repositoryId + ":" + name);
        }

        int getCreatePathHistoryCallCount() {
            return createPathHistoryCalls.size();
        }
    }

    // ========================================================================
    // Patch subclasses for testing
    // ========================================================================

    private static class SuccessPatch extends AbstractNemakiPatch {
        @Override protected void applySystemPatch() {}
        @Override protected void applyPerRepositoryPatch(String repositoryId) {
            // Success — no exception
        }
        @Override public String getName() { return "test_success"; }
    }

    private static class FailingPatch extends AbstractNemakiPatch {
        @Override protected void applySystemPatch() {}
        @Override protected void applyPerRepositoryPatch(String repositoryId) {
            throw new RuntimeException("Simulated patch failure for " + repositoryId);
        }
        @Override public String getName() { return "test_failing"; }
    }

    private static class PartialFailPatch extends AbstractNemakiPatch {
        private final String failRepo;
        PartialFailPatch(String failRepo) { this.failRepo = failRepo; }
        @Override protected void applySystemPatch() {}
        @Override protected void applyPerRepositoryPatch(String repositoryId) {
            if (failRepo.equals(repositoryId)) {
                throw new RuntimeException("Simulated failure for " + repositoryId);
            }
        }
        @Override public String getName() { return "test_partial_fail"; }
    }

    // ========================================================================
    // Tests
    // ========================================================================

    @Test
    public void testApplyReturnsTrueWhenAllSucceed() {
        Set<String> repos = new LinkedHashSet<>();
        repos.add("bedroom");

        StubPatchUtil stubPatchUtil = new StubPatchUtil(
                new LinkedHashSet<>(), // no patches applied yet
                new StubRepositoryInfoMap(repos));

        SuccessPatch patch = new SuccessPatch();
        patch.patchUtil = stubPatchUtil;

        boolean result = patch.apply();

        assertTrue("apply() should return true when all repositories succeed", result);
        assertTrue("createPathHistory should be called for bedroom",
                stubPatchUtil.wasCreatePathHistoryCalled("bedroom", "test_success"));
    }

    @Test
    public void testApplyReturnsFalseWhenPatchThrows() {
        Set<String> repos = new LinkedHashSet<>();
        repos.add("bedroom");

        StubPatchUtil stubPatchUtil = new StubPatchUtil(
                new LinkedHashSet<>(),
                new StubRepositoryInfoMap(repos));

        FailingPatch patch = new FailingPatch();
        patch.patchUtil = stubPatchUtil;

        boolean result = patch.apply();

        assertFalse("apply() should return false when applyPerRepositoryPatch throws", result);
        assertFalse("createPathHistory should NOT be called for failed repository",
                stubPatchUtil.wasCreatePathHistoryCalled("bedroom", "test_failing"));
    }

    @Test
    public void testCreatePathHistoryNotCalledForFailedRepo() {
        Set<String> repos = new LinkedHashSet<>();
        repos.add("bedroom");
        repos.add("canopy");

        StubPatchUtil stubPatchUtil = new StubPatchUtil(
                new LinkedHashSet<>(),
                new StubRepositoryInfoMap(repos));

        PartialFailPatch patch = new PartialFailPatch("canopy");
        patch.patchUtil = stubPatchUtil;

        boolean result = patch.apply();

        assertFalse("apply() should return false when any repository fails", result);
        // bedroom succeeded → history created
        assertTrue("createPathHistory should be called for bedroom (succeeded)",
                stubPatchUtil.wasCreatePathHistoryCalled("bedroom", "test_partial_fail"));
        // canopy failed → history NOT created
        assertFalse("createPathHistory should NOT be called for canopy (failed)",
                stubPatchUtil.wasCreatePathHistoryCalled("canopy", "test_partial_fail"));
    }

    @Test
    public void testAlreadyAppliedSkippedWithoutFailure() {
        Set<String> repos = new LinkedHashSet<>();
        repos.add("bedroom");

        Set<String> alreadyApplied = new LinkedHashSet<>();
        alreadyApplied.add("bedroom:test_success");

        StubPatchUtil stubPatchUtil = new StubPatchUtil(
                alreadyApplied,
                new StubRepositoryInfoMap(repos));

        SuccessPatch patch = new SuccessPatch();
        patch.patchUtil = stubPatchUtil;

        boolean result = patch.apply();

        assertTrue("apply() should return true when patch is already applied (skipped)", result);
        assertEquals("createPathHistory should NOT be called when patch already applied",
                0, stubPatchUtil.getCreatePathHistoryCallCount());
    }
}
