package jp.aegif.nemaki.sync.scheduler;

import jp.aegif.nemaki.sync.service.CloudDirectorySyncService;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CloudDirectorySyncScheduler.
 * Tests init/destroy lifecycle, cron validation, and scheduling behavior.
 *
 * In the current design, init() always creates the ScheduledExecutorService
 * (for config polling), so isSchedulerActive() is always true after init().
 * The activeCron field indicates whether an actual sync schedule is armed.
 */
@ExtendWith(MockitoExtension.class)
public class CloudDirectorySyncSchedulerTest {

	@Mock
	private CloudDirectorySyncService cloudDirectorySyncService;

	@Mock
	private PropertyManager propertyManager;

	private CloudDirectorySyncScheduler scheduler;

	@BeforeEach
	public void setup() {
		scheduler = new CloudDirectorySyncScheduler();
		scheduler.setCloudDirectorySyncService(cloudDirectorySyncService);
		scheduler.setPropertyManager(propertyManager);
	}

	@AfterEach
	public void tearDown() {
		scheduler.destroy();
	}

	@Test
	public void testInit_Disabled_NoCronArmed() {
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("false");

		scheduler.init();

		assertTrue(scheduler.isSchedulerActive(), "Scheduler executor should be active (for config polling)");
		assertNull(scheduler.getActiveCron(), "No cron should be armed when disabled");
	}

	@Test
	public void testInit_EnabledButNoCron_NoCronArmed() {
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("true");
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_CRON)).thenReturn(null);

		scheduler.init();

		assertTrue(scheduler.isSchedulerActive());
		assertNull(scheduler.getActiveCron(), "No cron should be armed without cron expression");
	}

	@Test
	public void testInit_EnabledEmptyCron_NoCronArmed() {
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("true");
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_CRON)).thenReturn("   ");

		scheduler.init();

		assertTrue(scheduler.isSchedulerActive());
		assertNull(scheduler.getActiveCron(), "No cron should be armed with empty cron");
	}

	@Test
	public void testInit_InvalidCron_NoCronArmed() {
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("true");
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_CRON)).thenReturn("invalid-cron");

		scheduler.init();

		assertTrue(scheduler.isSchedulerActive());
		assertNull(scheduler.getActiveCron(), "No cron should be armed with invalid cron");
	}

	@Test
	public void testInit_ValidCron_CronArmed() {
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("true");
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_CRON)).thenReturn("0 0 2 * * *");

		scheduler.init();

		assertTrue(scheduler.isSchedulerActive());
		assertEquals("0 0 2 * * *", scheduler.getActiveCron());
	}

	@Test
	public void testReconcile_DetectsCronChange() {
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("true");
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_CRON)).thenReturn("0 0 2 * * *");

		scheduler.init();
		assertEquals("0 0 2 * * *", scheduler.getActiveCron());

		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_CRON)).thenReturn("0 0 4 * * *");
		scheduler.reconcileSchedule();

		assertEquals("0 0 4 * * *", scheduler.getActiveCron());
	}

	@Test
	public void testReconcile_ClearedCronStops() {
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("true");
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_CRON)).thenReturn("0 0 2 * * *");

		scheduler.init();
		assertNotNull(scheduler.getActiveCron());

		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("false");
		scheduler.reconcileSchedule();

		assertNull(scheduler.getActiveCron());
	}

	@Test
	public void testDestroy_StopsScheduler() {
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("true");
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_CRON)).thenReturn("0 0 2 * * *");

		scheduler.init();
		assertTrue(scheduler.isSchedulerActive());

		scheduler.destroy();
		assertFalse(scheduler.isSchedulerActive(), "Scheduler should not be active after destroy");
	}

	@Test
	public void testIsSchedulerActive_BeforeInit_ReturnsFalse() {
		assertFalse(scheduler.isSchedulerActive());
	}
}
