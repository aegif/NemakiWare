package jp.aegif.nemaki.sync.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import static org.junit.jupiter.api.Assertions.*;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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
		assertSame(first, second, "Duplicate startSync should return the existing RUNNING result");
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
		assertNotSame(google, microsoft, "Different keys should have independent results");
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
		assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "Threads should complete within 5s");

		// All threads should get the same result object
		for (int i = 1; i < threadCount; i++) {
			assertSame(results[0], results[i], "All concurrent callers should get the same result");
		}
	}

	// ---- syncUserGroups ----

	@Test
	public void testSyncUserGroups_UnknownProvider_ReturnsEmptyList() {
		lenient().when(propertyManager.readValue(anyString())).thenReturn(null);
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("true");
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_PROVIDERS)).thenReturn("google,microsoft");

		java.util.List<String> groups = service.syncUserGroups(TEST_REPO, "dropbox", "ext-user", "user@example.com");
		assertNotNull(groups);
		assertTrue(groups.isEmpty(), "Unknown provider should return empty list");
	}

	@Test
	public void testSyncUserGroups_ProviderDisabled_ReturnsEmptyList() {
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("false");

		java.util.List<String> groups = service.syncUserGroups(TEST_REPO, "google", "ext-user", "user@example.com");
		assertNotNull(groups);
		assertTrue(groups.isEmpty(), "Disabled provider should return empty list");
	}

	@Test
	public void testSyncUserGroups_ProviderNotInList_ReturnsEmptyList() {
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("true");
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_PROVIDERS)).thenReturn("microsoft");

		java.util.List<String> groups = service.syncUserGroups(TEST_REPO, "google", "ext-user", "user@example.com");
		assertNotNull(groups);
		assertTrue(groups.isEmpty(), "Provider not in list should return empty list");
	}

	// ---- testConnection ----

	@Test
	public void testTestConnection_UnknownProvider_ReturnsFalse() {
		boolean connected = service.testConnection("dropbox");
		assertFalse(connected, "Unknown provider should return false");
	}

	@Test
	public void testTestConnection_Google_NoCreds_ReturnsFalse() {
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_GOOGLE_SERVICE_ACCOUNT_KEY))
				.thenReturn(null);
		boolean connected = service.testConnection("google");
		assertFalse(connected, "Google without creds should return false");
	}

	@Test
	public void testTestConnection_Microsoft_NoCreds_ReturnsFalse() {
		lenient().when(propertyManager.readValue(anyString())).thenReturn(null);
		boolean connected = service.testConnection("microsoft");
		assertFalse(connected, "Microsoft without creds should return false");
	}

	// ---- startSync state transition: COMPLETED → new start ----

	@Test
	public void testStartSync_AfterCompletion_CreatesNewResult() throws Exception {
		lenient().when(propertyManager.readValue(anyString())).thenReturn(null);
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("true");
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_PROVIDERS)).thenReturn("google");

		// First start
		CloudSyncResult first = service.startDeltaSync(TEST_REPO, "google");
		assertEquals(CloudSyncResult.Status.RUNNING, first.getStatus());

		// Simulate completion (executor runs async, but we can force status)
		first.setStatus(CloudSyncResult.Status.COMPLETED);

		// Second start after completion should create a new result
		CloudSyncResult second = service.startDeltaSync(TEST_REPO, "google");
		assertNotSame(first, second, "After completion, a new result should be created");
		assertEquals(CloudSyncResult.Status.RUNNING, second.getStatus());
	}

	@Test
	public void testStartSync_AfterError_CreatesNewResult() throws Exception {
		lenient().when(propertyManager.readValue(anyString())).thenReturn(null);
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED)).thenReturn("true");
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_PROVIDERS)).thenReturn("google");

		CloudSyncResult first = service.startDeltaSync(TEST_REPO, "google");
		first.setStatus(CloudSyncResult.Status.ERROR);

		CloudSyncResult second = service.startDeltaSync(TEST_REPO, "google");
		assertNotSame(first, second, "After error, a new result should be created");
		assertEquals(CloudSyncResult.Status.RUNNING, second.getStatus());
	}
}
