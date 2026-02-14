package jp.aegif.nemaki.sync.scheduler;

import jp.aegif.nemaki.sync.service.CloudDirectorySyncService;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CloudDirectorySyncScheduler.
 * Tests init/destroy lifecycle, cron validation, and scheduling behavior.
 */
@RunWith(MockitoJUnitRunner.class)
public class CloudDirectorySyncSchedulerTest {

	@Mock
	private CloudDirectorySyncService cloudDirectorySyncService;

	@Mock
	private PropertyManager propertyManager;

	private CloudDirectorySyncScheduler scheduler;

	@Before
	public void setup() {
		scheduler = new CloudDirectorySyncScheduler();
		scheduler.setCloudDirectorySyncService(cloudDirectorySyncService);
		scheduler.setPropertyManager(propertyManager);
	}

	@After
	public void tearDown() {
		scheduler.destroy();
	}

	@Test
	public void testInit_Disabled_DoesNotStartScheduler() {
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("false");

		scheduler.init();

		assertFalse("Scheduler should not be active when disabled", scheduler.isSchedulerActive());
	}

	@Test
	public void testInit_EnabledButNoCron_DoesNotStartScheduler() {
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("true");
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_CRON)).thenReturn(null);

		scheduler.init();

		assertFalse("Scheduler should not be active without cron expression", scheduler.isSchedulerActive());
	}

	@Test
	public void testInit_EnabledEmptyCron_DoesNotStartScheduler() {
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("true");
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_CRON)).thenReturn("   ");

		scheduler.init();

		assertFalse("Scheduler should not be active with empty cron", scheduler.isSchedulerActive());
	}

	@Test
	public void testInit_InvalidCron_DoesNotStartScheduler() {
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("true");
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_CRON)).thenReturn("invalid-cron");

		scheduler.init();

		assertFalse("Scheduler should not be active with invalid cron", scheduler.isSchedulerActive());
	}

	@Test
	public void testInit_ValidCron_StartsScheduler() {
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("true");
		// Every day at 2am
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_CRON)).thenReturn("0 0 2 * * *");

		scheduler.init();

		assertTrue("Scheduler should be active with valid cron", scheduler.isSchedulerActive());
	}

	@Test
	public void testInit_DoubleInit_Idempotent() {
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("true");
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_CRON)).thenReturn("0 0 2 * * *");

		scheduler.init();
		scheduler.init(); // second call should be no-op

		assertTrue("Scheduler should still be active after double init", scheduler.isSchedulerActive());
	}

	@Test
	public void testDestroy_StopsScheduler() {
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("true");
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_CRON)).thenReturn("0 0 2 * * *");

		scheduler.init();
		assertTrue(scheduler.isSchedulerActive());

		scheduler.destroy();
		assertFalse("Scheduler should not be active after destroy", scheduler.isSchedulerActive());
	}

	@Test
	public void testDestroy_NoInit_NoException() {
		// destroy without init should not throw
		scheduler.destroy();
		assertFalse(scheduler.isSchedulerActive());
	}

	@Test
	public void testIsSchedulerActive_BeforeInit_ReturnsFalse() {
		assertFalse(scheduler.isSchedulerActive());
	}
}
