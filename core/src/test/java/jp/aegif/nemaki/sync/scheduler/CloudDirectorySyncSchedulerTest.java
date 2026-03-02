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
	public void testInit_Disabled_DoesNotStartScheduler() {
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("false");

		scheduler.init();

		assertFalse(scheduler.isSchedulerActive(), "Scheduler should not be active when disabled");
	}

	@Test
	public void testInit_EnabledButNoCron_DoesNotStartScheduler() {
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("true");
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_CRON)).thenReturn(null);

		scheduler.init();

		assertFalse(scheduler.isSchedulerActive(), "Scheduler should not be active without cron expression");
	}

	@Test
	public void testInit_EnabledEmptyCron_DoesNotStartScheduler() {
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("true");
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_CRON)).thenReturn("   ");

		scheduler.init();

		assertFalse(scheduler.isSchedulerActive(), "Scheduler should not be active with empty cron");
	}

	@Test
	public void testInit_InvalidCron_DoesNotStartScheduler() {
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("true");
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_CRON)).thenReturn("invalid-cron");

		scheduler.init();

		assertFalse(scheduler.isSchedulerActive(), "Scheduler should not be active with invalid cron");
	}

	@Test
	public void testInit_ValidCron_StartsScheduler() {
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("true");
		// Every day at 2am
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_CRON)).thenReturn("0 0 2 * * *");

		scheduler.init();

		assertTrue(scheduler.isSchedulerActive(), "Scheduler should be active with valid cron");
	}

	@Test
	public void testInit_DoubleInit_Idempotent() {
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("true");
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_CRON)).thenReturn("0 0 2 * * *");

		scheduler.init();
		scheduler.init(); // second call should be no-op

		assertTrue(scheduler.isSchedulerActive(), "Scheduler should still be active after double init");
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
