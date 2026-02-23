package jp.aegif.nemaki.patch;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.Before;
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
 */
public class AbstractNemakiPatchTest {

    private PatchUtil mockPatchUtil;
    private RepositoryInfoMap mockRepoInfoMap;

    @Before
    public void setUp() {
        mockPatchUtil = mock(PatchUtil.class);
        mockRepoInfoMap = mock(RepositoryInfoMap.class);
        when(mockPatchUtil.getRepositoryInfoMap()).thenReturn(mockRepoInfoMap);
    }

    /**
     * A test subclass that always succeeds.
     */
    private static class SuccessPatch extends AbstractNemakiPatch {
        @Override protected void applySystemPatch() {}
        @Override protected void applyPerRepositoryPatch(String repositoryId) {
            // Success — no exception
        }
        @Override public String getName() { return "test_success"; }
    }

    /**
     * A test subclass that always throws.
     */
    private static class FailingPatch extends AbstractNemakiPatch {
        @Override protected void applySystemPatch() {}
        @Override protected void applyPerRepositoryPatch(String repositoryId) {
            throw new RuntimeException("Simulated patch failure for " + repositoryId);
        }
        @Override public String getName() { return "test_failing"; }
    }

    /**
     * A test subclass that fails for a specific repository.
     */
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

    @Test
    public void testApplyReturnsTrueWhenAllSucceed() {
        Set<String> repos = new LinkedHashSet<>();
        repos.add("bedroom");
        when(mockRepoInfoMap.keys()).thenReturn(repos);
        when(mockPatchUtil.isApplied("bedroom", "test_success")).thenReturn(false);

        SuccessPatch patch = new SuccessPatch();
        patch.patchUtil = mockPatchUtil;

        boolean result = patch.apply();

        assertTrue("apply() should return true when all repositories succeed", result);
        verify(mockPatchUtil).createPathHistory("bedroom", "test_success");
    }

    @Test
    public void testApplyReturnsFalseWhenPatchThrows() {
        Set<String> repos = new LinkedHashSet<>();
        repos.add("bedroom");
        when(mockRepoInfoMap.keys()).thenReturn(repos);
        when(mockPatchUtil.isApplied("bedroom", "test_failing")).thenReturn(false);

        FailingPatch patch = new FailingPatch();
        patch.patchUtil = mockPatchUtil;

        boolean result = patch.apply();

        assertFalse("apply() should return false when applyPerRepositoryPatch throws", result);
        verify(mockPatchUtil, never()).createPathHistory(eq("bedroom"), anyString());
    }

    @Test
    public void testCreatePathHistoryNotCalledForFailedRepo() {
        Set<String> repos = new LinkedHashSet<>();
        repos.add("bedroom");
        repos.add("canopy");
        when(mockRepoInfoMap.keys()).thenReturn(repos);
        when(mockPatchUtil.isApplied(anyString(), eq("test_partial_fail"))).thenReturn(false);

        PartialFailPatch patch = new PartialFailPatch("canopy");
        patch.patchUtil = mockPatchUtil;

        boolean result = patch.apply();

        assertFalse("apply() should return false when any repository fails", result);
        // bedroom succeeded → history created
        verify(mockPatchUtil).createPathHistory("bedroom", "test_partial_fail");
        // canopy failed → history NOT created
        verify(mockPatchUtil, never()).createPathHistory("canopy", "test_partial_fail");
    }

    @Test
    public void testAlreadyAppliedSkippedWithoutFailure() {
        Set<String> repos = new LinkedHashSet<>();
        repos.add("bedroom");
        when(mockRepoInfoMap.keys()).thenReturn(repos);
        when(mockPatchUtil.isApplied("bedroom", "test_success")).thenReturn(true);

        SuccessPatch patch = new SuccessPatch();
        patch.patchUtil = mockPatchUtil;

        boolean result = patch.apply();

        assertTrue("apply() should return true when patch is already applied (skipped)", result);
        verify(mockPatchUtil, never()).createPathHistory(anyString(), anyString());
    }
}
