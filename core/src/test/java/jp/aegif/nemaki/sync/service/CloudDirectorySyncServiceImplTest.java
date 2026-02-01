package jp.aegif.nemaki.sync.service;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.MockitoJUnitRunner;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.*;

import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(MockitoJUnitRunner.class)
public class CloudDirectorySyncServiceImplTest {

	private static final String TEST_REPO = "bedroom";

	@Mock
	private PrincipalService principalService;

	@Mock
	private PropertyManager propertyManager;

	@InjectMocks
	private CloudDirectorySyncServiceImpl service;

	@Test
	public void testGetSyncStatus_NoExistingSync_ReturnsIdle() {
		CloudSyncResult result = service.getSyncStatus(TEST_REPO, "google");
		assertEquals(CloudSyncResult.Status.IDLE, result.getStatus());
		assertEquals(TEST_REPO, result.getRepositoryId());
		assertEquals("google", result.getProvider());
	}

	@Test
	public void testStartDeltaSync_ReturnsRunningResult() {
		lenient().when(propertyManager.readValue(anyString())).thenReturn(null);
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("true");
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_PROVIDERS)).thenReturn("google");

		CloudSyncResult result = service.startDeltaSync(TEST_REPO, "google");
		assertNotNull(result);
		assertEquals(CloudSyncResult.Status.RUNNING, result.getStatus());
		assertEquals(CloudSyncResult.SyncMode.DELTA, result.getSyncMode());
		assertEquals("google", result.getProvider());
	}

	@Test
	public void testStartFullReconciliation_ReturnsRunningResult() {
		lenient().when(propertyManager.readValue(anyString())).thenReturn(null);
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("true");
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_PROVIDERS)).thenReturn("microsoft");

		CloudSyncResult result = service.startFullReconciliation(TEST_REPO, "microsoft");
		assertNotNull(result);
		assertEquals(CloudSyncResult.Status.RUNNING, result.getStatus());
		assertEquals(CloudSyncResult.SyncMode.FULL, result.getSyncMode());
	}

	@Test
	public void testGetSyncStatus_AfterStart_ReturnsRunningResult() {
		lenient().when(propertyManager.readValue(anyString())).thenReturn(null);
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("true");
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_PROVIDERS)).thenReturn("google");

		service.startDeltaSync(TEST_REPO, "google");
		CloudSyncResult status = service.getSyncStatus(TEST_REPO, "google");
		assertEquals(CloudSyncResult.Status.RUNNING, status.getStatus());
	}

	@Test
	public void testCancelSync_SetsCancelFlag() {
		service.cancelSync(TEST_REPO, "google");
		// Verify no exception, cancel flag is set internally
	}

	@Test
	public void testCancelSync_NoRunningSync_Noop() {
		// Should not throw even when no sync is running
		service.cancelSync(TEST_REPO, "nonexistent");
	}

	@Test
	public void testGetWindowSize_Default() throws Exception {
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_WINDOW_SIZE)).thenReturn(null);

		Method method = CloudDirectorySyncServiceImpl.class.getDeclaredMethod("getWindowSize");
		method.setAccessible(true);
		int size = (int) method.invoke(service);
		assertEquals(100, size);
	}

	@Test
	public void testGetWindowSize_CustomValue() throws Exception {
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_WINDOW_SIZE)).thenReturn("50");

		Method method = CloudDirectorySyncServiceImpl.class.getDeclaredMethod("getWindowSize");
		method.setAccessible(true);
		int size = (int) method.invoke(service);
		assertEquals(50, size);
	}

	@Test
	public void testGetWindowSize_InvalidValue_ReturnsDefault() throws Exception {
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_WINDOW_SIZE)).thenReturn("abc");

		Method method = CloudDirectorySyncServiceImpl.class.getDeclaredMethod("getWindowSize");
		method.setAccessible(true);
		int size = (int) method.invoke(service);
		assertEquals(100, size);
	}

	@Test
	public void testIsProviderEnabled_GoogleEnabled() throws Exception {
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("true");
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_PROVIDERS)).thenReturn("google,microsoft");

		Method method = CloudDirectorySyncServiceImpl.class.getDeclaredMethod("isProviderEnabled", String.class);
		method.setAccessible(true);
		assertTrue((boolean) method.invoke(service, "google"));
		assertTrue((boolean) method.invoke(service, "microsoft"));
	}

	@Test
	public void testIsProviderEnabled_Disabled() throws Exception {
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("false");

		Method method = CloudDirectorySyncServiceImpl.class.getDeclaredMethod("isProviderEnabled", String.class);
		method.setAccessible(true);
		assertFalse((boolean) method.invoke(service, "google"));
	}

	@Test
	public void testIsProviderEnabled_NotInList() throws Exception {
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("true");
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_PROVIDERS)).thenReturn("microsoft");

		Method method = CloudDirectorySyncServiceImpl.class.getDeclaredMethod("isProviderEnabled", String.class);
		method.setAccessible(true);
		assertFalse((boolean) method.invoke(service, "google"));
	}

	@Test
	public void testSyncKey_CombinesRepoAndProvider() throws Exception {
		Method method = CloudDirectorySyncServiceImpl.class.getDeclaredMethod("syncKey", String.class, String.class);
		method.setAccessible(true);
		String key = (String) method.invoke(service, "bedroom", "google");
		assertEquals("bedroom:google", key);
	}

	@Test
	public void testIsCancelled_DefaultFalse() throws Exception {
		Method isCancelled = CloudDirectorySyncServiceImpl.class.getDeclaredMethod("isCancelled", String.class);
		isCancelled.setAccessible(true);
		Method syncKeyMethod = CloudDirectorySyncServiceImpl.class.getDeclaredMethod("syncKey", String.class, String.class);
		syncKeyMethod.setAccessible(true);
		String key = (String) syncKeyMethod.invoke(service, TEST_REPO, "google");
		boolean cancelled = (boolean) isCancelled.invoke(service, key);
		assertFalse(cancelled);
	}

	@Test
	public void testIsCancelled_AfterCancel() throws Exception {
		service.cancelSync(TEST_REPO, "google");

		Method isCancelled = CloudDirectorySyncServiceImpl.class.getDeclaredMethod("isCancelled", String.class);
		isCancelled.setAccessible(true);
		Method syncKeyMethod = CloudDirectorySyncServiceImpl.class.getDeclaredMethod("syncKey", String.class, String.class);
		syncKeyMethod.setAccessible(true);
		String key = (String) syncKeyMethod.invoke(service, TEST_REPO, "google");
		boolean cancelled = (boolean) isCancelled.invoke(service, key);
		assertTrue(cancelled);
	}

	@Test
	public void testStartSync_DuplicateCall_ReusesRunningResult() {
		lenient().when(propertyManager.readValue(anyString())).thenReturn(null);
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("true");
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_PROVIDERS)).thenReturn("google");

		CloudSyncResult first = service.startDeltaSync(TEST_REPO, "google");
		CloudSyncResult second = service.startDeltaSync(TEST_REPO, "google");

		// Second call should return the same RUNNING result (not create a new one)
		assertSame("Duplicate startSync should return the existing RUNNING result", first, second);
		assertEquals(CloudSyncResult.Status.RUNNING, second.getStatus());
	}

	@Test
	public void testStartSync_DifferentKeys_RunInParallel() {
		lenient().when(propertyManager.readValue(anyString())).thenReturn(null);
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("true");
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_PROVIDERS)).thenReturn("google,microsoft");

		CloudSyncResult google = service.startDeltaSync(TEST_REPO, "google");
		CloudSyncResult microsoft = service.startDeltaSync(TEST_REPO, "microsoft");

		// Different keys should produce independent results
		assertNotSame("Different keys should have independent results", google, microsoft);
		assertEquals(CloudSyncResult.Status.RUNNING, google.getStatus());
		assertEquals(CloudSyncResult.Status.RUNNING, microsoft.getStatus());
		assertEquals("google", google.getProvider());
		assertEquals("microsoft", microsoft.getProvider());
	}

	@Test
	public void testStartSync_ConcurrentSameKey_OnlyOneRuns() throws Exception {
		lenient().when(propertyManager.readValue(anyString())).thenReturn(null);
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("true");
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_PROVIDERS)).thenReturn("google");

		int threadCount = 4;
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(threadCount);
		AtomicInteger distinctResults = new AtomicInteger(0);
		CloudSyncResult[] results = new CloudSyncResult[threadCount];

		for (int i = 0; i < threadCount; i++) {
			final int idx = i;
			new Thread(() -> {
				try {
					startLatch.await();
					results[idx] = service.startDeltaSync(TEST_REPO, "google");
				} catch (Exception e) {
					// ignore
				} finally {
					doneLatch.countDown();
				}
			}).start();
		}

		startLatch.countDown(); // release all threads
		assertTrue("Threads should complete within 5s", doneLatch.await(5, TimeUnit.SECONDS));

		// All threads should get the same result object
		for (int i = 1; i < threadCount; i++) {
			assertSame("All concurrent callers should get the same result", results[0], results[i]);
		}
	}
}
