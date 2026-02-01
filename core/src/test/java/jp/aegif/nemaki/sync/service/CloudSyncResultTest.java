package jp.aegif.nemaki.sync.service;

import org.junit.Test;
import static org.junit.Assert.*;

public class CloudSyncResultTest {

	@Test
	public void testDefaultConstructor() {
		CloudSyncResult result = new CloudSyncResult();
		assertNotNull(result.getSyncId());
		assertEquals(CloudSyncResult.Status.IDLE, result.getStatus());
		assertNull(result.getProvider());
		assertNull(result.getRepositoryId());
		assertNull(result.getSyncMode());
		assertEquals(0, result.getUsersCreated());
		assertEquals(0, result.getGroupsCreated());
		assertEquals(0, result.getCurrentPage());
		assertTrue(result.getErrors().isEmpty());
		assertTrue(result.getWarnings().isEmpty());
	}

	@Test
	public void testParameterizedConstructor() {
		CloudSyncResult result = new CloudSyncResult("bedroom", "google", CloudSyncResult.SyncMode.DELTA);
		assertNotNull(result.getSyncId());
		assertEquals("bedroom", result.getRepositoryId());
		assertEquals("google", result.getProvider());
		assertEquals(CloudSyncResult.SyncMode.DELTA, result.getSyncMode());
		assertEquals(CloudSyncResult.Status.IDLE, result.getStatus());
	}

	@Test
	public void testUniqueSyncIds() {
		CloudSyncResult r1 = new CloudSyncResult();
		CloudSyncResult r2 = new CloudSyncResult();
		assertNotEquals(r1.getSyncId(), r2.getSyncId());
	}

	@Test
	public void testIncrementUsersCreated() {
		CloudSyncResult result = new CloudSyncResult();
		assertEquals(0, result.getUsersCreated());
		result.incrementUsersCreated();
		result.incrementUsersCreated();
		result.incrementUsersCreated();
		assertEquals(3, result.getUsersCreated());
	}

	@Test
	public void testIncrementUsersUpdated() {
		CloudSyncResult result = new CloudSyncResult();
		result.incrementUsersUpdated();
		assertEquals(1, result.getUsersUpdated());
	}

	@Test
	public void testIncrementUsersDeleted() {
		CloudSyncResult result = new CloudSyncResult();
		result.incrementUsersDeleted();
		result.incrementUsersDeleted();
		assertEquals(2, result.getUsersDeleted());
	}

	@Test
	public void testIncrementUsersSkipped() {
		CloudSyncResult result = new CloudSyncResult();
		result.incrementUsersSkipped();
		assertEquals(1, result.getUsersSkipped());
	}

	@Test
	public void testIncrementGroupCounters() {
		CloudSyncResult result = new CloudSyncResult();
		result.incrementGroupsCreated();
		result.incrementGroupsUpdated();
		result.incrementGroupsDeleted();
		result.incrementGroupsSkipped();
		assertEquals(1, result.getGroupsCreated());
		assertEquals(1, result.getGroupsUpdated());
		assertEquals(1, result.getGroupsDeleted());
		assertEquals(1, result.getGroupsSkipped());
	}

	@Test
	public void testAddErrors() {
		CloudSyncResult result = new CloudSyncResult();
		result.addError("Error 1");
		result.addError("Error 2");
		assertEquals(2, result.getErrors().size());
		assertEquals("Error 1", result.getErrors().get(0));
	}

	@Test
	public void testAddWarnings() {
		CloudSyncResult result = new CloudSyncResult();
		result.addWarning("Warning 1");
		assertEquals(1, result.getWarnings().size());
		assertEquals("Warning 1", result.getWarnings().get(0));
	}

	@Test
	public void testErrorsListIsDefensiveCopy() {
		CloudSyncResult result = new CloudSyncResult();
		result.addError("Error");
		result.getErrors().clear();
		assertEquals(1, result.getErrors().size());
	}

	@Test
	public void testWarningsListIsDefensiveCopy() {
		CloudSyncResult result = new CloudSyncResult();
		result.addWarning("Warning");
		result.getWarnings().clear();
		assertEquals(1, result.getWarnings().size());
	}

	@Test
	public void testSetStatus() {
		CloudSyncResult result = new CloudSyncResult();
		result.setStatus(CloudSyncResult.Status.RUNNING);
		assertEquals(CloudSyncResult.Status.RUNNING, result.getStatus());
		result.setStatus(CloudSyncResult.Status.COMPLETED);
		assertEquals(CloudSyncResult.Status.COMPLETED, result.getStatus());
	}

	@Test
	public void testStatusEnumValues() {
		CloudSyncResult.Status[] values = CloudSyncResult.Status.values();
		assertEquals(5, values.length);
		assertNotNull(CloudSyncResult.Status.valueOf("IDLE"));
		assertNotNull(CloudSyncResult.Status.valueOf("RUNNING"));
		assertNotNull(CloudSyncResult.Status.valueOf("COMPLETED"));
		assertNotNull(CloudSyncResult.Status.valueOf("ERROR"));
		assertNotNull(CloudSyncResult.Status.valueOf("CANCELLED"));
	}

	@Test
	public void testSyncModeEnumValues() {
		CloudSyncResult.SyncMode[] values = CloudSyncResult.SyncMode.values();
		assertEquals(2, values.length);
		assertNotNull(CloudSyncResult.SyncMode.valueOf("DELTA"));
		assertNotNull(CloudSyncResult.SyncMode.valueOf("FULL"));
	}

	@Test
	public void testPagingProgress() {
		CloudSyncResult result = new CloudSyncResult();
		result.setCurrentPage(5);
		result.setTotalPages(10);
		assertEquals(5, result.getCurrentPage());
		assertEquals(10, result.getTotalPages());
	}

	@Test
	public void testTimeFields() {
		CloudSyncResult result = new CloudSyncResult();
		assertNull(result.getStartTime());
		assertNull(result.getEndTime());
		result.setStartTime("2026-01-01T00:00:00Z");
		result.setEndTime("2026-01-01T01:00:00Z");
		assertEquals("2026-01-01T00:00:00Z", result.getStartTime());
		assertEquals("2026-01-01T01:00:00Z", result.getEndTime());
	}
}
