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
}
