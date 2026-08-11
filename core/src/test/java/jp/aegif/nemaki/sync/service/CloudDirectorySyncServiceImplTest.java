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

	/**
	 * Concurrent callers for one repository+provider must share the running sync.
	 *
	 * <p>The property under test is conditional: {@code startSync} returns the existing result
	 * only while its status is still RUNNING, and the async body flips that to COMPLETED as soon
	 * as it finishes. With a mocked provider the body finishes in microseconds, so a caller that
	 * arrives late legitimately starts a NEW sync and legitimately gets a different object.
	 *
	 * <p>Releasing four threads from a latch does not make them overlap — it makes them likely to
	 * overlap on an idle machine. This test asserted the overlap without arranging it, and duly
	 * failed once under load in a full-suite run (a probe was running on the same host) while
	 * passing in isolation. Retrying it would have hidden a test defect behind "flaky".
	 *
	 * <p>So the overlap is now arranged rather than hoped for. The first caller's sync body is
	 * HELD at its first property read until the later callers have been served, which makes
	 * "the first sync is still running when they arrive" a fact of the test. Note that racing
	 * four threads was not merely unreliable, it was not even reaching the code it meant to
	 * exercise: instrumenting the body showed it had not started at all within the original
	 * test's window, so the assertion was passing because the executor happened to be slow.
	 */
	@Test
	public void testStartSync_ConcurrentSameKey_OnlyOneRuns() throws Exception {
		CountDownLatch bodyEntered = new CountDownLatch(1);
		CountDownLatch releaseBody = new CountDownLatch(1);
		AtomicInteger syncBodiesEntered = new AtomicInteger(0);

		lenient().when(propertyManager.readValue(anyString())).thenReturn(null);
		lenient().when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_PROVIDERS))
				.thenReturn("google");
		// isProviderEnabled reads this first, and only the async body calls it — so blocking
		// here pins the sync in RUNNING without delaying any caller. The waits are bounded, so
		// a wrong assumption about who reads what fails this test rather than hanging the suite.
		when(propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED))
				.thenAnswer(invocation -> {
					syncBodiesEntered.incrementAndGet();
					bodyEntered.countDown();
					releaseBody.await(10, TimeUnit.SECONDS);
					return "true";
				});

		CloudSyncResult first = service.startDeltaSync(TEST_REPO, "google");
		assertNotNull(first);
		assertEquals(CloudSyncResult.Status.RUNNING, first.getStatus());
		assertTrue(bodyEntered.await(10, TimeUnit.SECONDS),
				"the sync body must have started, or nothing is being held and the callers below"
						+ " would be racing a completed sync — exactly the defect this replaced");

		// Now the sync is provably in flight. Every further caller for the same key must be
		// handed that same running sync rather than starting another.
		int laterCallers = 3;
		CountDownLatch doneLatch = new CountDownLatch(laterCallers);
		CloudSyncResult[] results = new CloudSyncResult[laterCallers];
		for (int i = 0; i < laterCallers; i++) {
			final int idx = i;
			new Thread(() -> {
				try {
					results[idx] = service.startDeltaSync(TEST_REPO, "google");
				} finally {
					doneLatch.countDown();
				}
			}).start();
		}
		assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "callers should not block on a sync");

		try {
			for (int i = 0; i < laterCallers; i++) {
				assertSame(first, results[i],
						"a caller arriving while a sync is running must share it, not start another");
			}
			assertEquals(1, syncBodiesEntered.get(),
					"exactly one sync body may run for a repository+provider; sharing the result"
							+ " object is how that is observed, but the count is the property");
		} finally {
			releaseBody.countDown();
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
