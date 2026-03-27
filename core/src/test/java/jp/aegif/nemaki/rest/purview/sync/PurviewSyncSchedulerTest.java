package jp.aegif.nemaki.rest.purview.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.rest.purview.PurviewConfig;

public class PurviewSyncSchedulerTest {

	@Test
	public void testInitDisabledWhenPurviewNotEnabled() {
		PurviewConfig config = mock(PurviewConfig.class);
		when(config.isEnabled()).thenReturn(false);

		PurviewIncrementalSyncService syncService = mock(PurviewIncrementalSyncService.class);
		RepositoryInfoMap repoInfoMap = mock(RepositoryInfoMap.class);

		PurviewSyncScheduler scheduler = new PurviewSyncScheduler(config, syncService, repoInfoMap);
		scheduler.init();

		// Executor is always created, but activeCron should be null
		assertTrue(scheduler.isSchedulerActive());
		assertNull(scheduler.getActiveCron());
		scheduler.destroy();
	}

	@Test
	public void testInitDisabledWhenCronEmpty() {
		PurviewConfig config = mock(PurviewConfig.class);
		when(config.isEnabled()).thenReturn(true);
		when(config.getSyncCron()).thenReturn("");

		PurviewIncrementalSyncService syncService = mock(PurviewIncrementalSyncService.class);
		RepositoryInfoMap repoInfoMap = mock(RepositoryInfoMap.class);

		PurviewSyncScheduler scheduler = new PurviewSyncScheduler(config, syncService, repoInfoMap);
		scheduler.init();

		assertTrue(scheduler.isSchedulerActive());
		assertNull(scheduler.getActiveCron());
		scheduler.destroy();
	}

	@Test
	public void testInitDisabledWhenCronInvalid() {
		PurviewConfig config = mock(PurviewConfig.class);
		when(config.isEnabled()).thenReturn(true);
		when(config.getSyncCron()).thenReturn("not-a-cron");

		PurviewIncrementalSyncService syncService = mock(PurviewIncrementalSyncService.class);
		RepositoryInfoMap repoInfoMap = mock(RepositoryInfoMap.class);

		PurviewSyncScheduler scheduler = new PurviewSyncScheduler(config, syncService, repoInfoMap);
		scheduler.init();

		assertTrue(scheduler.isSchedulerActive());
		assertNull(scheduler.getActiveCron());
		scheduler.destroy();
	}

	@Test
	public void testInitStartsSchedulerWhenEnabledAndCronValid() {
		PurviewConfig config = mock(PurviewConfig.class);
		when(config.isEnabled()).thenReturn(true);
		when(config.getSyncCron()).thenReturn("0 0 3 * * ?");

		PurviewIncrementalSyncService syncService = mock(PurviewIncrementalSyncService.class);
		RepositoryInfoMap repoInfoMap = mock(RepositoryInfoMap.class);

		PurviewSyncScheduler scheduler = new PurviewSyncScheduler(config, syncService, repoInfoMap);
		scheduler.init();

		assertTrue(scheduler.isSchedulerActive());
		assertEquals("0 0 3 * * ?", scheduler.getActiveCron());
		scheduler.destroy();
	}

	@Test
	public void testExecuteSyncCallsIncrementalSyncForEachRepository() throws Exception {
		PurviewConfig config = mock(PurviewConfig.class);
		when(config.isEnabled()).thenReturn(true);

		PurviewIncrementalSyncService syncService = mock(PurviewIncrementalSyncService.class);
		RepositoryInfoMap repoInfoMap = mock(RepositoryInfoMap.class);
		when(repoInfoMap.getMainRepositoryKeys()).thenReturn(Arrays.asList("bedroom", "canopy"));

		PurviewSyncScheduler scheduler = new PurviewSyncScheduler(config, syncService, repoInfoMap);

		// Invoke executeSync via reflection
		Method executeSyncMethod = PurviewSyncScheduler.class.getDeclaredMethod("executeSync");
		executeSyncMethod.setAccessible(true);
		executeSyncMethod.invoke(scheduler);

		verify(syncService).startIncrementalSync("bedroom", "scheduled");
		verify(syncService).startIncrementalSync("canopy", "scheduled");
	}

	@Test
	public void testExecuteSyncSkipsWhenPurviewDisabledDynamically() throws Exception {
		PurviewConfig config = mock(PurviewConfig.class);
		when(config.isEnabled()).thenReturn(false);

		PurviewIncrementalSyncService syncService = mock(PurviewIncrementalSyncService.class);
		RepositoryInfoMap repoInfoMap = mock(RepositoryInfoMap.class);

		PurviewSyncScheduler scheduler = new PurviewSyncScheduler(config, syncService, repoInfoMap);

		// Invoke executeSync via reflection
		Method executeSyncMethod = PurviewSyncScheduler.class.getDeclaredMethod("executeSync");
		executeSyncMethod.setAccessible(true);
		executeSyncMethod.invoke(scheduler);

		verify(syncService, never()).startIncrementalSync(anyString(), anyString());
	}

	@Test
	public void testDestroyStopsScheduler() {
		PurviewConfig config = mock(PurviewConfig.class);
		when(config.isEnabled()).thenReturn(true);
		when(config.getSyncCron()).thenReturn("0 0 3 * * ?");

		PurviewIncrementalSyncService syncService = mock(PurviewIncrementalSyncService.class);
		RepositoryInfoMap repoInfoMap = mock(RepositoryInfoMap.class);

		PurviewSyncScheduler scheduler = new PurviewSyncScheduler(config, syncService, repoInfoMap);
		scheduler.init();
		assertTrue(scheduler.isSchedulerActive());

		scheduler.destroy();
		assertFalse(scheduler.isSchedulerActive());
	}

	@Test
	public void testReconcileDetectsCronChange() {
		PurviewConfig config = mock(PurviewConfig.class);
		when(config.isEnabled()).thenReturn(true);
		when(config.getSyncCron()).thenReturn("0 0 3 * * ?");

		PurviewIncrementalSyncService syncService = mock(PurviewIncrementalSyncService.class);
		RepositoryInfoMap repoInfoMap = mock(RepositoryInfoMap.class);

		PurviewSyncScheduler scheduler = new PurviewSyncScheduler(config, syncService, repoInfoMap);
		scheduler.init();
		assertEquals("0 0 3 * * ?", scheduler.getActiveCron());

		// Simulate cron change via admin UI
		when(config.getSyncCron()).thenReturn("0 30 2 * * ?");
		scheduler.reconcileSchedule();

		assertEquals("0 30 2 * * ?", scheduler.getActiveCron());
		scheduler.destroy();
	}

	@Test
	public void testReconcileClearedCronStops() {
		PurviewConfig config = mock(PurviewConfig.class);
		when(config.isEnabled()).thenReturn(true);
		when(config.getSyncCron()).thenReturn("0 0 3 * * ?");

		PurviewIncrementalSyncService syncService = mock(PurviewIncrementalSyncService.class);
		RepositoryInfoMap repoInfoMap = mock(RepositoryInfoMap.class);

		PurviewSyncScheduler scheduler = new PurviewSyncScheduler(config, syncService, repoInfoMap);
		scheduler.init();
		assertEquals("0 0 3 * * ?", scheduler.getActiveCron());

		// Simulate cron cleared via admin UI
		when(config.getSyncCron()).thenReturn("");
		scheduler.reconcileSchedule();

		assertNull(scheduler.getActiveCron());
		assertTrue(scheduler.isSchedulerActive()); // executor still alive for polling
		scheduler.destroy();
	}
}
