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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;
import jp.aegif.nemaki.util.spring.SpringContext;

/**
 * The QA fixture account must not be seeded into a deployment that did not ask for it.
 *
 * <h2>What was wrong</h2>
 *
 * <p>{@code Patch_TestUserInitialization} is registered in the production
 * {@code patchContext.xml} and named in {@code NemakiPatchInitializationListener}'s ordered seed
 * list. There was no flag: every deployment got {@code testuser} and {@code testgroup} in every
 * repository. The password it stores ({@code "test"}) is not a BCrypt hash and therefore never
 * authenticated, so this was never a working credential — it was QA data in production, and an
 * account an administrator could later hand a real password to.
 *
 * <h2>What this test pins</h2>
 *
 * <p>The CONSEQUENCE: with no configuration, the patch reads nothing and writes nothing. Asserting
 * on {@code isEnabled()} alone would not be enough — it would stay green if someone deleted the
 * check from {@code applyPerRepositoryPatch} — so both tests drive the real patch method and watch
 * the {@link ContentService} it would have used.
 */
public class TestUserPatchIsOptInTest {

	private static final String REPO = "bedroom";

	@AfterEach
	void clearSpringContext() {
		new SpringContext().setApplicationContext(null);
	}

	private static ContentService wireContext() {
		ContentService contentService = mock(ContentService.class);
		when(contentService.getGroupItems(anyString())).thenReturn(List.<GroupItem>of());
		when(contentService.getUserItems(anyString())).thenReturn(List.<UserItem>of());

		// The patch now THROWS on any failure instead of logging past it (3.3.1 #1), so the
		// happy path needs real-looking returns — a null from these mocks is a failure now.
		GroupItem createdGroup = mock(GroupItem.class);
		when(createdGroup.getId()).thenReturn("group-1");
		when(contentService.createGroupItem(any(), anyString(), any(GroupItem.class)))
				.thenReturn(createdGroup);
		jp.aegif.nemaki.model.Folder usersFolder = mock(jp.aegif.nemaki.model.Folder.class);
		when(usersFolder.getId()).thenReturn("users-folder");
		when(contentService.getOrCreateSystemSubFolder(anyString(), anyString()))
				.thenReturn(usersFolder);
		UserItem createdUser = mock(UserItem.class);
		when(createdUser.getId()).thenReturn("user-1");
		when(contentService.createUserItem(any(), anyString(), any(UserItem.class)))
				.thenReturn(createdUser);

		ApplicationContext ctx = mock(ApplicationContext.class);
		when(ctx.getBean("PrincipalService", PrincipalService.class))
				.thenReturn(mock(PrincipalService.class));
		when(ctx.getBean("ContentService", ContentService.class)).thenReturn(contentService);
		new SpringContext().setApplicationContext(ctx);
		return contentService;
	}

	private static Patch_TestUserInitialization patchConfiguredWith(String flagValue) {
		PropertyManager propertyManager = mock(PropertyManager.class);
		when(propertyManager.readBoolean(PropertyKey.PATCH_TESTUSER_ENABLED))
				.thenReturn(Boolean.parseBoolean(flagValue));

		PatchUtil patchUtil = mock(PatchUtil.class);
		when(patchUtil.getPropertyManager()).thenReturn(propertyManager);

		Patch_TestUserInitialization patch = new Patch_TestUserInitialization();
		patch.setPatchUtil(patchUtil);
		return patch;
	}

	/** The shipped default. "Not configured" is not consent. */
	@Test
	public void anUnconfiguredDeploymentIsNotSeeded() {
		ContentService contentService = wireContext();
		Patch_TestUserInitialization patch = patchConfiguredWith("false");

		assertFalse(patch.isEnabled());
		patch.applyPerRepositoryPatch(REPO);

		verify(contentService, never()).getGroupItems(anyString());
		verify(contentService, never()).createGroupItem(any(), anyString(), any());
		verify(contentService, never()).createUserItem(any(), anyString(), any(UserItem.class));
	}

	/**
	 * And it still works when asked for — otherwise the test above would pass against a patch that
	 * had simply been broken.
	 */
	@Test
	public void anOperatorWhoAsksForItStillGetsIt() {
		ContentService contentService = wireContext();
		Patch_TestUserInitialization patch = patchConfiguredWith("true");

		assertTrue(patch.isEnabled());
		patch.applyPerRepositoryPatch(REPO);

		verify(contentService).getGroupItems(REPO);
	}

	/** No configuration reachable at all must not be read as "yes". */
	@Test
	public void noPropertyManagerMeansNo() {
		Patch_TestUserInitialization patch = new Patch_TestUserInitialization();
		assertFalse(patch.isEnabled(), "a patch with nothing wired must not seed anything");
	}

	/**
	 * 3.3.1 #1: an ENABLED seed that fails must not bake in either.
	 *
	 * <p>The four catches used to swallow and return normally, so the base class recorded
	 * {@code PatchHistory} over a failed seed — an enabled deployment whose first boot hit a
	 * transient error never got the fixture, forever. Now the failure propagates: the base
	 * per-repository catch logs it, {@code apply()} reports {@code false}, and no history is
	 * written, so the next boot retries. Restoring any swallowing catch fails this test.
	 */
	@Test
	public void aFailedSeedRecordsNoHistoryAndRetriesNextBoot() {
		ContentService contentService = wireContext();
		when(contentService.getGroupItems(anyString()))
				.thenThrow(new RuntimeException("transient CouchDB failure"));

		PropertyManager propertyManager = mock(PropertyManager.class);
		when(propertyManager.readBoolean(PropertyKey.PATCH_TESTUSER_ENABLED)).thenReturn(true);
		PatchUtil patchUtil = mock(PatchUtil.class);
		when(patchUtil.getPropertyManager()).thenReturn(propertyManager);
		jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap repos =
				mock(jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap.class);
		when(repos.keys()).thenReturn(java.util.Set.of(REPO));
		when(repos.isArchiveRepository(anyString())).thenReturn(false);
		when(patchUtil.getRepositoryInfoMap()).thenReturn(repos);
		when(patchUtil.cmisViewsAreAnswering(anyString())).thenReturn(true);
		when(patchUtil.isApplied(anyString(), anyString())).thenReturn(false);

		Patch_TestUserInitialization patch = new Patch_TestUserInitialization();
		patch.setPatchUtil(patchUtil);

		assertFalse(patch.apply(), "a failed seed must be reported as an unsuccessful run");
		verify(patchUtil, never()).createPathHistory(anyString(), anyString());
	}

	/**
	 * A disabled boot must not bake the decision in.
	 *
	 * <p>The base {@code apply()} records {@code PatchHistory} after each repository, and
	 * {@code isApplied} short-circuits every later run. So if the flag only gated the inner
	 * method, one boot with the default (off) would permanently disable the fixture — turning it
	 * on later would do nothing. This pins the consequence: a disabled run records NOTHING.
	 * Reverting the {@code apply()} override (keeping only the inner gate) fails this test.
	 */
	@Test
	public void aDisabledBootRecordsNoHistory() {
		PropertyManager propertyManager = mock(PropertyManager.class);
		when(propertyManager.readBoolean(PropertyKey.PATCH_TESTUSER_ENABLED)).thenReturn(false);
		PatchUtil patchUtil = mock(PatchUtil.class);
		when(patchUtil.getPropertyManager()).thenReturn(propertyManager);

		Patch_TestUserInitialization patch = new Patch_TestUserInitialization();
		patch.setPatchUtil(patchUtil);

		assertTrue(patch.apply(), "declining to seed is a success, not a failure");

		verify(patchUtil, never()).createPathHistory(anyString(), anyString());
		verify(patchUtil, never()).getRepositoryInfoMap();
	}
}
