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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;

/**
 * A patch that could not run must not be recorded as having run.
 *
 * <h2>What was wrong</h2>
 *
 * <p>{@code AbstractNemakiPatch.apply()} writes the patch history the instant
 * {@code applyPerRepositoryPatch} returns normally, and {@code isApplied} then skips the patch on
 * every later startup. {@code Patch_SystemFolderSetup} used to {@code return} — not throw — on
 * every "the repository is not initialized yet" branch, and to swallow its own outer exception with
 * the comment "Don't throw - patch failures should not prevent application startup".
 *
 * <p>So a repository that was merely not ready when the patch ran got its history written anyway,
 * and the system folder was never created — permanently, with one {@code warn} line as the only
 * trace. That is the neighbourhood PX1 (two {@code .system} folders in bedroom, which breaks CMIS
 * path resolution) came out of.
 *
 * <p>The premise behind the old comment is false: {@code apply()} already catches
 * ({@code AbstractNemakiPatch.java:63-67}), logs, marks the run unsuccessful and moves to the next
 * repository. Throwing costs nothing at startup and buys a retry, because the history is only
 * written on the success path.
 *
 * <h2>What this test pins</h2>
 *
 * <p>Not the exception type — the consequence. {@code createPathHistory} must not be called, so the
 * next startup tries again. Asserting on the throw alone would still pass if someone later caught
 * it one frame up and recorded the patch anyway.
 *
 * <p>Hand-written stubs rather than Mockito, matching {@link AbstractNemakiPatchTest} in this
 * package: the inline mock maker needs {@code -XX:+EnableDynamicAgentLoading}.
 */
class PatchSystemFolderSetupNotReadyTest {

	private static final String REPO = "bedroom";
	private static final String PATCH_NAME = "system-folder-setup-20250805";

	private static class StubRepositoryInfoMap extends RepositoryInfoMap {
		@Override
		public Set<String> keys() {
			return new LinkedHashSet<>(List.of(REPO));
		}
	}

	private static class StubPatchUtil extends PatchUtil {
		private final List<String> createPathHistoryCalls = new ArrayList<>();
		private final ContentService contentService;

		StubPatchUtil(ContentService contentService) {
			this.contentService = contentService;
		}

		@Override
		public RepositoryInfoMap getRepositoryInfoMap() {
			return new StubRepositoryInfoMap();
		}

		@Override
		public ContentService getContentService() {
			return contentService;
		}

		/** Not what this test is about; the canary has its own file. */
		@Override
		public boolean cmisViewsAreAnswering(String repositoryId) {
			return true;
		}

		@Override
		protected boolean isApplied(String repositoryId, String name) {
			return false;
		}

		@Override
		protected void createPathHistory(String repositoryId, String name) {
			createPathHistoryCalls.add(repositoryId + ":" + name);
		}

		boolean recordedAnything() {
			return !createPathHistoryCalls.isEmpty();
		}
	}

	/**
	 * The branch that fires when the patch runs before the repository is usable.
	 *
	 * <p>{@code getContentService()} answering null is the earliest of several "not ready yet"
	 * checks; they all used to {@code return}, and they all now throw for the same reason.
	 */
	@Test
	void aRepositoryThatIsNotReadyIsNotRecordedAsPatched() {
		StubPatchUtil patchUtil = new StubPatchUtil(null);
		Patch_SystemFolderSetup patch = new Patch_SystemFolderSetup();
		patch.setPatchUtil(patchUtil);

		boolean succeeded = patch.apply();

		assertFalse(succeeded,
				"the system folder was not created, so the run did not succeed");
		assertFalse(patchUtil.recordedAnything(),
				"the history was written for a patch that did nothing — isApplied will skip it on "
						+ "every later startup, so the system folder is never created and the only "
						+ "trace is one log line");
	}

	/**
	 * The other half: without it, "never record anything" would pass the test above and stop every
	 * patch from ever being marked applied, so each startup would redo work that was already done.
	 *
	 * <p>Uses a patch that simply succeeds, because constructing a fully initialized repository for
	 * {@code Patch_SystemFolderSetup} here would be testing CouchDB, not this rule.
	 */
	@Test
	void aPatchThatSucceedsIsStillRecorded() {
		StubPatchUtil patchUtil = new StubPatchUtil(null);
		AbstractNemakiPatch succeeding = new AbstractNemakiPatch() {
			@Override protected void applySystemPatch() { }
			@Override protected void applyPerRepositoryPatch(String repositoryId) { }
			@Override public String getName() { return PATCH_NAME; }
		};
		succeeding.setPatchUtil(patchUtil);

		assertTrue(succeeding.apply());
		assertTrue(patchUtil.recordedAnything(),
				"a patch that did its work must be recorded, or it runs again every startup");
	}
}
