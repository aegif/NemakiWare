package jp.aegif.nemaki.sync.service;

import org.junit.Test;
import static org.junit.Assert.*;

public class CloudSyncStateTest {

	@Test
	public void testDefaultConstructor() {
		CloudSyncState state = new CloudSyncState();
		assertNull(state.getRepositoryId());
		assertNull(state.getProvider());
		assertNull(state.getLastDeltaToken());
		assertNull(state.getLastSyncTimestamp());
		assertNull(state.getLastFullReconciliationTimestamp());
	}

	@Test
	public void testParameterizedConstructor() {
		CloudSyncState state = new CloudSyncState("bedroom", "microsoft");
		assertEquals("bedroom", state.getRepositoryId());
		assertEquals("microsoft", state.getProvider());
		assertNull(state.getLastDeltaToken());
	}

	@Test
	public void testSetRepositoryId() {
		CloudSyncState state = new CloudSyncState();
		state.setRepositoryId("canopy");
		assertEquals("canopy", state.getRepositoryId());
	}

	@Test
	public void testSetProvider() {
		CloudSyncState state = new CloudSyncState();
		state.setProvider("google");
		assertEquals("google", state.getProvider());
	}

	@Test
	public void testSetLastDeltaToken() {
		CloudSyncState state = new CloudSyncState();
		String deltaLink = "https://graph.microsoft.com/v1.0/users/delta?$deltatoken=abc123";
		state.setLastDeltaToken(deltaLink);
		assertEquals(deltaLink, state.getLastDeltaToken());
	}

	@Test
	public void testSetLastSyncTimestamp() {
		CloudSyncState state = new CloudSyncState();
		state.setLastSyncTimestamp("2026-01-15T10:30:00Z");
		assertEquals("2026-01-15T10:30:00Z", state.getLastSyncTimestamp());
	}

	@Test
	public void testSetLastFullReconciliationTimestamp() {
		CloudSyncState state = new CloudSyncState();
		state.setLastFullReconciliationTimestamp("2026-01-01T00:00:00Z");
		assertEquals("2026-01-01T00:00:00Z", state.getLastFullReconciliationTimestamp());
	}
}
