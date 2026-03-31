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
import jp.aegif.nemaki.rest.purview.CatalogBackendKind;
import jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver;
import jp.aegif.nemaki.rest.purview.PurviewConfig;
import jp.aegif.nemaki.rest.purview.journal.AtlasConfig;

public class PurviewSyncSchedulerTest {

	private MetadataCatalogConnectionResolver connectionResolver;
	private PurviewConfig purviewConfig;
	private AtlasConfig atlasConfig;
	private PurviewIncrementalSyncService syncService;
	private PurviewDeleteResolutionService deleteResolutionService;
	private RepositoryInfoMap repoInfoMap;

	private PurviewSyncScheduler createScheduler() {
		return new PurviewSyncScheduler(connectionResolver, purviewConfig, atlasConfig,
				syncService, deleteResolutionService, repoInfoMap);
	}

	private void setupMocks() {
		connectionResolver = mock(MetadataCatalogConnectionResolver.class);
		purviewConfig = mock(PurviewConfig.class);
		atlasConfig = mock(AtlasConfig.class);
		syncService = mock(PurviewIncrementalSyncService.class);
		deleteResolutionService = mock(PurviewDeleteResolutionService.class);
		repoInfoMap = mock(RepositoryInfoMap.class);
	}

	@Test
	public void testInitDisabledWhenNeitherBackendEnabled() {
		setupMocks();
		when(connectionResolver.isAnyEnabled()).thenReturn(false);
		when(connectionResolver.activeBackend()).thenReturn(CatalogBackendKind.NONE);

		PurviewSyncScheduler scheduler = createScheduler();
		scheduler.init();

		assertTrue(scheduler.isSchedulerActive());
		assertNull(scheduler.getActiveCron());
		scheduler.destroy();
	}

	@Test
	public void testInitDisabledWhenPurviewCronEmpty() {
		setupMocks();
		when(connectionResolver.isAnyEnabled()).thenReturn(true);
		when(connectionResolver.activeBackend()).thenReturn(CatalogBackendKind.PURVIEW);
		when(purviewConfig.getSyncCron()).thenReturn("");

		PurviewSyncScheduler scheduler = createScheduler();
		scheduler.init();

		assertTrue(scheduler.isSchedulerActive());
		assertNull(scheduler.getActiveCron());
		scheduler.destroy();
	}

	@Test
	public void testInitDisabledWhenCronInvalid() {
		setupMocks();
		when(connectionResolver.isAnyEnabled()).thenReturn(true);
		when(connectionResolver.activeBackend()).thenReturn(CatalogBackendKind.PURVIEW);
		when(purviewConfig.getSyncCron()).thenReturn("not-a-cron");

		PurviewSyncScheduler scheduler = createScheduler();
		scheduler.init();

		assertTrue(scheduler.isSchedulerActive());
		assertNull(scheduler.getActiveCron());
		scheduler.destroy();
	}

	@Test
	public void testInitStartsSchedulerWhenPurviewEnabledAndCronValid() {
		setupMocks();
		when(connectionResolver.isAnyEnabled()).thenReturn(true);
		when(connectionResolver.activeBackend()).thenReturn(CatalogBackendKind.PURVIEW);
		when(purviewConfig.getSyncCron()).thenReturn("0 0 3 * * ?");

		PurviewSyncScheduler scheduler = createScheduler();
		scheduler.init();

		assertTrue(scheduler.isSchedulerActive());
		assertEquals("0 0 3 * * ?", scheduler.getActiveCron());
		scheduler.destroy();
	}

	@Test
	public void testInitStartsSchedulerWhenAtlasEnabledAndCronValid() {
		setupMocks();
		when(connectionResolver.isAnyEnabled()).thenReturn(true);
		when(connectionResolver.activeBackend()).thenReturn(CatalogBackendKind.ATLAS);
		when(atlasConfig.getSyncCron()).thenReturn("0 30 2 * * ?");

		PurviewSyncScheduler scheduler = createScheduler();
		scheduler.init();

		assertTrue(scheduler.isSchedulerActive());
		assertEquals("0 30 2 * * ?", scheduler.getActiveCron());
		scheduler.destroy();
	}

	@Test
	public void testExecuteSyncCallsIncrementalSyncForEachRepository() throws Exception {
		setupMocks();
		when(connectionResolver.isAnyEnabled()).thenReturn(true);
		when(connectionResolver.activeBackend()).thenReturn(CatalogBackendKind.PURVIEW);
		when(repoInfoMap.getMainRepositoryKeys()).thenReturn(Arrays.asList("bedroom", "canopy"));

		PurviewSyncScheduler scheduler = createScheduler();

		Method executeSyncMethod = PurviewSyncScheduler.class.getDeclaredMethod("executeSync");
		executeSyncMethod.setAccessible(true);
		executeSyncMethod.invoke(scheduler);

		verify(syncService).startIncrementalSync("bedroom", "scheduled");
		verify(syncService).startIncrementalSync("canopy", "scheduled");
		verify(deleteResolutionService).startDeleteResolution("bedroom", "scheduled");
		verify(deleteResolutionService).startDeleteResolution("canopy", "scheduled");
	}

	@Test
	public void testExecuteSyncSkipsWhenNeitherBackendEnabled() throws Exception {
		setupMocks();
		when(connectionResolver.isAnyEnabled()).thenReturn(false);

		PurviewSyncScheduler scheduler = createScheduler();

		Method executeSyncMethod = PurviewSyncScheduler.class.getDeclaredMethod("executeSync");
		executeSyncMethod.setAccessible(true);
		executeSyncMethod.invoke(scheduler);

		verify(syncService, never()).startIncrementalSync(anyString(), anyString());
		verify(deleteResolutionService, never()).startDeleteResolution(anyString(), anyString());
	}

	@Test
	public void testDestroyStopsScheduler() {
		setupMocks();
		when(connectionResolver.isAnyEnabled()).thenReturn(true);
		when(connectionResolver.activeBackend()).thenReturn(CatalogBackendKind.PURVIEW);
		when(purviewConfig.getSyncCron()).thenReturn("0 0 3 * * ?");

		PurviewSyncScheduler scheduler = createScheduler();
		scheduler.init();
		assertTrue(scheduler.isSchedulerActive());

		scheduler.destroy();
		assertFalse(scheduler.isSchedulerActive());
	}

	@Test
	public void testReconcileDetectsCronChange() {
		setupMocks();
		when(connectionResolver.isAnyEnabled()).thenReturn(true);
		when(connectionResolver.activeBackend()).thenReturn(CatalogBackendKind.PURVIEW);
		when(purviewConfig.getSyncCron()).thenReturn("0 0 3 * * ?");

		PurviewSyncScheduler scheduler = createScheduler();
		scheduler.init();
		assertEquals("0 0 3 * * ?", scheduler.getActiveCron());

		when(purviewConfig.getSyncCron()).thenReturn("0 30 2 * * ?");
		scheduler.reconcileSchedule();

		assertEquals("0 30 2 * * ?", scheduler.getActiveCron());
		scheduler.destroy();
	}

	@Test
	public void testReconcileClearedCronStops() {
		setupMocks();
		when(connectionResolver.isAnyEnabled()).thenReturn(true);
		when(connectionResolver.activeBackend()).thenReturn(CatalogBackendKind.PURVIEW);
		when(purviewConfig.getSyncCron()).thenReturn("0 0 3 * * ?");

		PurviewSyncScheduler scheduler = createScheduler();
		scheduler.init();
		assertEquals("0 0 3 * * ?", scheduler.getActiveCron());

		when(purviewConfig.getSyncCron()).thenReturn("");
		scheduler.reconcileSchedule();

		assertNull(scheduler.getActiveCron());
		assertTrue(scheduler.isSchedulerActive());
		scheduler.destroy();
	}
}
