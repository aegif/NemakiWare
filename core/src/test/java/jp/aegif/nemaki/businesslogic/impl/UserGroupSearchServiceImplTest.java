package jp.aegif.nemaki.businesslogic.impl;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService;
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService.SolrQueryResult;
import jp.aegif.nemaki.businesslogic.UserGroupSearchService.SearchResult;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;

/**
 * Tests for UserGroupSearchServiceImpl.
 *
 * Uses hand-written stubs instead of Mockito to avoid JVM attach dependency.
 */
public class UserGroupSearchServiceImplTest {

	private UserGroupSearchServiceImpl service;
	private StubSolrIndexMaintenanceService stubSolr;
	private StubPropertyManager stubPm;

	@BeforeEach
	public void setUp() {
		service = new UserGroupSearchServiceImpl();
		stubSolr = new StubSolrIndexMaintenanceService();
		stubPm = new StubPropertyManager();
		service.setSolrIndexMaintenanceService(stubSolr);
		service.setPropertyManager(stubPm);
	}

	// ==========================================
	// isSolrSearchEnabled tests
	// ==========================================

	@Test
	public void isSolrSearchEnabled_returnsFalseByDefault() {
		stubPm.setBoolValue(false);
		assertFalse(service.isSolrSearchEnabled());
	}

	@Test
	public void isSolrSearchEnabled_returnsTrueWhenEnabled() {
		stubPm.setBoolValue(true);
		assertTrue(service.isSolrSearchEnabled());
	}

	// ==========================================
	// isSolrSearchEffective tests (P2)
	// ==========================================

	@Test
	public void isSolrSearchEffective_flagDisabled() {
		stubPm.setBoolValue(false);
		assertFalse(service.isSolrUserSearchEffective("bedroom"));
		// No Solr call should be made when flag is off
		assertEquals(0, stubSolr.callCount);
	}

	@Test
	public void isSolrSearchEffective_fieldsExist() {
		stubPm.setBoolValue(true);
		// Probe returns numFound > 0
		stubSolr.setResult(makeDocs(1), 1);
		assertTrue(service.isSolrUserSearchEffective("bedroom"));
		assertEquals(1, stubSolr.callCount);
	}

	@Test
	public void isSolrSearchEffective_fieldsMissing() {
		stubPm.setBoolValue(true);
		// Probe returns numFound = 0
		stubSolr.setResult(new ArrayList<>(), 0);
		assertFalse(service.isSolrUserSearchEffective("bedroom"));
		assertEquals(1, stubSolr.callCount);
	}

	@Test
	public void isSolrSearchEffective_probeError() {
		stubPm.setBoolValue(true);
		stubSolr.setErrorResult("Connection refused");
		assertFalse(service.isSolrUserSearchEffective("bedroom"));
	}

	@Test
	public void isSolrSearchEffective_probeException() {
		stubPm.setBoolValue(true);
		stubSolr.throwException = true;
		assertFalse(service.isSolrUserSearchEffective("bedroom"));
	}

	@Test
	public void isSolrSearchEffective_cachedAfterProbe() {
		stubPm.setBoolValue(true);
		stubSolr.setResult(makeDocs(1), 1);

		assertTrue(service.isSolrUserSearchEffective("bedroom"));
		assertTrue(service.isSolrUserSearchEffective("bedroom"));
		// Solr should only be called once (probe is cached)
		assertEquals(1, stubSolr.callCount);
	}

	@Test
	public void isSolrSearchEffective_cachedMissingAfterProbe() {
		stubPm.setBoolValue(true);
		stubSolr.setResult(new ArrayList<>(), 0);

		assertFalse(service.isSolrUserSearchEffective("bedroom"));
		// Even on second call, result should stay false and no additional Solr call
		assertFalse(service.isSolrUserSearchEffective("bedroom"));
		assertEquals(1, stubSolr.callCount);
	}

	@Test
	public void isSolrSearchEffective_resetProbeAllowsRetry() {
		stubPm.setBoolValue(true);
		stubSolr.setResult(new ArrayList<>(), 0);
		assertFalse(service.isSolrUserSearchEffective("bedroom"));

		service.resetProbeState();
		stubSolr.setResult(makeDocs(1), 5);
		assertTrue(service.isSolrUserSearchEffective("bedroom"));
		assertEquals(2, stubSolr.callCount);
	}

	@Test
	public void isSolrSearchEffective_perRepositoryCache_independentResults() {
		stubPm.setBoolValue(true);
		// bedroom: fields exist
		stubSolr.setPerRepoProbeResult("bedroom", makeDocs(1), 3);
		// canopy: fields missing (not yet indexed)
		stubSolr.setPerRepoProbeResult("canopy", new ArrayList<>(), 0);

		assertTrue(service.isSolrUserSearchEffective("bedroom"));
		assertFalse(service.isSolrUserSearchEffective("canopy"));
		assertEquals(2, stubSolr.callCount);
	}

	@Test
	public void isSolrSearchEffective_perRepositoryCache_cachedIndependently() {
		stubPm.setBoolValue(true);
		stubSolr.setPerRepoProbeResult("bedroom", makeDocs(1), 3);
		stubSolr.setPerRepoProbeResult("canopy", new ArrayList<>(), 0);

		// First calls: probe both repos
		assertTrue(service.isSolrUserSearchEffective("bedroom"));
		assertFalse(service.isSolrUserSearchEffective("canopy"));
		assertEquals(2, stubSolr.callCount);

		// Second calls: cached, no additional Solr calls
		assertTrue(service.isSolrUserSearchEffective("bedroom"));
		assertFalse(service.isSolrUserSearchEffective("canopy"));
		assertEquals(2, stubSolr.callCount);
	}

	@Test
	public void isSolrSearchEffective_resetClearsAllRepositories() {
		stubPm.setBoolValue(true);
		stubSolr.setPerRepoProbeResult("bedroom", makeDocs(1), 3);
		stubSolr.setPerRepoProbeResult("canopy", new ArrayList<>(), 0);

		assertTrue(service.isSolrUserSearchEffective("bedroom"));
		assertFalse(service.isSolrUserSearchEffective("canopy"));
		assertEquals(2, stubSolr.callCount);

		// Reset clears all
		service.resetProbeState();
		// Now canopy also has data
		stubSolr.setPerRepoProbeResult("canopy", makeDocs(1), 5);

		assertTrue(service.isSolrUserSearchEffective("bedroom"));
		assertTrue(service.isSolrUserSearchEffective("canopy"));
		assertEquals(4, stubSolr.callCount);
	}

	// ==========================================
	// searchUsers (case-insensitive) tests
	// ==========================================

	@Test
	public void searchUsers_returnsObjectIdsFromSolr() {
		List<Map<String, Object>> docs = new ArrayList<>();
		docs.add(docWithObjectId("id1"));
		docs.add(docWithObjectId("id2"));
		stubSolr.setResult(docs, 2);

		SearchResult result = service.searchUsers("bedroom", "admin", 0, 20);

		assertFalse(result.hasError());
		assertEquals(2, result.getObjectIds().size());
		assertEquals("id1", result.getObjectIds().get(0));
		assertEquals("id2", result.getObjectIds().get(1));
		assertEquals(2, result.getTotalCount());
	}

	@Test
	public void searchUsers_caseInsensitive_lowercasesQuery() {
		stubSolr.setResult(new ArrayList<>(), 0);
		service.searchUsers("bedroom", "Admin", 0, 20);

		// The query should contain lowercase "admin"
		String capturedQuery = stubSolr.lastQuery;
		assertNotNull(capturedQuery);
		assertTrue(capturedQuery.contains("admin"), "Query should contain lowercased term");
		assertFalse(capturedQuery.contains("Admin"), "Query should NOT contain original case term in id_lc field search");
	}

	@Test
	public void searchUsers_passesOffsetAndLimit() {
		stubSolr.setResult(new ArrayList<>(), 0);
		service.searchUsers("bedroom", "test", 10, 25);

		assertEquals(10, stubSolr.lastStart);
		assertEquals(25, stubSolr.lastRows);
	}

	@Test
	public void searchUsers_escapesSpecialChars() {
		stubSolr.setResult(new ArrayList<>(), 0);
		service.searchUsers("bedroom", "test+user", 0, 20);

		String capturedQuery = stubSolr.lastQuery;
		assertNotNull(capturedQuery);
		// + should be escaped by ClientUtils.escapeQueryChars
		assertTrue(capturedQuery.contains("test\\+user"), "Query should contain escaped special chars");
	}

	@Test
	public void searchUsers_containsObjectTypeFilter() {
		stubSolr.setResult(new ArrayList<>(), 0);
		service.searchUsers("bedroom", "test", 0, 20);

		String capturedQuery = stubSolr.lastQuery;
		assertNotNull(capturedQuery);
		assertTrue(capturedQuery.contains("objecttype:nemaki\\:user"), "Query should filter by nemaki:user objecttype");
	}

	@Test
	public void searchUsers_requestsOnlyObjectIdField() {
		stubSolr.setResult(new ArrayList<>(), 0);
		service.searchUsers("bedroom", "test", 0, 20);

		assertEquals("object_id", stubSolr.lastFields);
	}

	// ==========================================
	// searchUsersCaseSensitive tests
	// ==========================================

	@Test
	public void searchUsersCaseSensitive_preservesCase() {
		stubSolr.setResult(new ArrayList<>(), 0);
		service.searchUsersCaseSensitive("bedroom", "Admin", 50);

		String capturedQuery = stubSolr.lastQuery;
		assertNotNull(capturedQuery);
		// Should use user_id (not user_id_lc) and name (not name_lc)
		assertTrue(capturedQuery.contains("dynamic.usersearch.user_id:"), "Query should use case-sensitive field user_id");
		assertTrue(capturedQuery.contains("name:"), "Query should use case-sensitive field name");
		assertTrue(capturedQuery.contains("Admin"), "Query should contain original case term");
	}

	// ==========================================
	// searchGroups (case-insensitive) tests
	// ==========================================

	@Test
	public void searchGroups_containsGroupObjectTypeFilter() {
		stubSolr.setResult(new ArrayList<>(), 0);
		service.searchGroups("bedroom", "test", 0, 20);

		String capturedQuery = stubSolr.lastQuery;
		assertNotNull(capturedQuery);
		assertTrue(capturedQuery.contains("objecttype:nemaki\\:group"), "Query should filter by nemaki:group objecttype");
	}

	@Test
	public void searchGroups_usesGroupIdField() {
		stubSolr.setResult(new ArrayList<>(), 0);
		service.searchGroups("bedroom", "test", 0, 20);

		String capturedQuery = stubSolr.lastQuery;
		assertNotNull(capturedQuery);
		assertTrue(capturedQuery.contains("dynamic.usersearch.group_id_lc:"), "Query should use group_id_lc field");
	}

	// ==========================================
	// Error handling tests
	// ==========================================

	@Test
	public void searchUsers_returnsErrorWhenSolrFails() {
		stubSolr.setErrorResult("Connection refused");

		SearchResult result = service.searchUsers("bedroom", "admin", 0, 20);

		assertTrue(result.hasError());
		assertEquals("Connection refused", result.getErrorMessage());
	}

	@Test
	public void searchUsers_returnsErrorOnException() {
		stubSolr.throwException = true;

		SearchResult result = service.searchUsers("bedroom", "admin", 0, 20);

		assertTrue(result.hasError());
		assertNotNull(result.getErrorMessage());
	}

	@Test
	public void searchGroups_returnsErrorWhenSolrFails() {
		stubSolr.setErrorResult("Solr down");

		SearchResult result = service.searchGroups("bedroom", "admin", 0, 20);

		assertTrue(result.hasError());
	}

	// ==========================================
	// Limit enforcement tests
	// ==========================================

	@Test
	public void searchUsers_enforcesMaxLimit() {
		stubSolr.setResult(new ArrayList<>(), 0);
		service.searchUsers("bedroom", "test", 0, 5000);

		// Should be capped to DEFAULT_MAX_ROWS (1000)
		assertEquals(1000, stubSolr.lastRows);
	}

	@Test
	public void searchUsers_zeroLimitUsesBatchLoop() {
		// limit=0 means non-paginated: batch loop with DEFAULT_MAX_ROWS per batch
		// 500 docs < 1000 batch size → single batch, loop exits
		stubSolr.setBatchResults(makeDocs(500), 500);
		SearchResult result = service.searchUsers("bedroom", "test", 0, 0);

		assertFalse(result.hasError());
		assertEquals(500, result.getObjectIds().size());
		assertEquals(500, result.getTotalCount());
		// Batch loop should have called Solr once with start=0, rows=1000
		assertEquals(1, stubSolr.callCount);
		assertEquals(0, stubSolr.lastStart);
		assertEquals(1000, stubSolr.lastRows);
	}

	@Test
	public void searchUsers_negativeLimitUsesBatchLoop() {
		stubSolr.setBatchResults(makeDocs(500), 500);
		SearchResult result = service.searchUsers("bedroom", "test", 0, -1);

		assertFalse(result.hasError());
		assertEquals(500, result.getObjectIds().size());
		assertEquals(1000, stubSolr.lastRows);
	}

	// ==========================================
	// P1: Non-paginated batch loop tests
	// ==========================================

	@Test
	public void searchUsers_nonPaginated_underBatchSize() {
		// 500 docs in single batch (under 1000 threshold)
		stubSolr.setBatchResults(makeDocs(500), 500);

		SearchResult result = service.searchUsers("bedroom", "test", 0, 0);

		assertFalse(result.hasError());
		assertEquals(500, result.getObjectIds().size());
		assertEquals(500, result.getTotalCount());
		assertEquals(1, stubSolr.callCount);
	}

	@Test
	public void searchUsers_nonPaginated_multipleBatches() {
		// 2500 docs total → 3 batches: 1000 + 1000 + 500
		stubSolr.setMultiBatchResults(2500);

		SearchResult result = service.searchUsers("bedroom", "test", 0, 0);

		assertFalse(result.hasError());
		assertEquals(2500, result.getObjectIds().size());
		assertEquals(2500, result.getTotalCount());
		assertEquals(3, stubSolr.callCount);
		// Verify batch offsets were correct
		assertEquals(0, stubSolr.batchStarts.get(0).intValue());
		assertEquals(1000, stubSolr.batchStarts.get(1).intValue());
		assertEquals(2000, stubSolr.batchStarts.get(2).intValue());
	}

	@Test
	public void searchUsers_nonPaginated_exactBatchBoundary() {
		// Exactly 2000 docs → 2 full batches + 1 empty batch
		stubSolr.setMultiBatchResults(2000);

		SearchResult result = service.searchUsers("bedroom", "test", 0, 0);

		assertFalse(result.hasError());
		assertEquals(2000, result.getObjectIds().size());
		assertEquals(2000, result.getTotalCount());
		// 2 full batches of 1000, then 3rd batch returns 0 docs (< 1000) → exit
		assertEquals(3, stubSolr.callCount);
	}

	@Test
	public void searchUsers_nonPaginated_errorMidBatch() {
		// First batch succeeds, second batch returns error
		stubSolr.setMultiBatchWithErrorAt(2, 2500);

		SearchResult result = service.searchUsers("bedroom", "test", 0, 0);

		assertTrue(result.hasError());
		assertNotNull(result.getErrorMessage());
		assertEquals(2, stubSolr.callCount);
	}

	// ==========================================
	// Paginated batch loop for limit > 1000
	// ==========================================

	@Test
	public void searchUsers_paginated_limitOver1000_fetchesAll() {
		// limit=2000 with 2000 available → 2 batches of 1000
		stubSolr.setMultiBatchResults(5000);

		SearchResult result = service.searchUsers("bedroom", "test", 0, 2000);

		assertFalse(result.hasError());
		assertEquals(2000, result.getObjectIds().size());
		assertEquals(5000, result.getTotalCount());
		assertEquals(2, stubSolr.callCount);
		assertEquals(0, stubSolr.batchStarts.get(0).intValue());
		assertEquals(1000, stubSolr.batchStarts.get(1).intValue());
	}

	@Test
	public void searchUsers_paginated_limitOver1000_withOffset() {
		// offset=500, limit=1500 → batches: start=500/rows=1000, start=1500/rows=500
		stubSolr.setMultiBatchResults(5000);

		SearchResult result = service.searchUsers("bedroom", "test", 500, 1500);

		assertFalse(result.hasError());
		assertEquals(1500, result.getObjectIds().size());
		assertEquals(2, stubSolr.callCount);
		assertEquals(500, stubSolr.batchStarts.get(0).intValue());
		assertEquals(1500, stubSolr.batchStarts.get(1).intValue());
		// Second batch should request only remaining 500
		assertEquals(500, stubSolr.lastRows);
	}

	@Test
	public void searchUsers_paginated_limitUnder1000_singleCall() {
		// limit=500 → single call, no batching needed
		stubSolr.setMultiBatchResults(5000);

		SearchResult result = service.searchUsers("bedroom", "test", 0, 500);

		assertFalse(result.hasError());
		assertEquals(500, result.getObjectIds().size());
		assertEquals(1, stubSolr.callCount);
		assertEquals(500, stubSolr.lastRows);
	}

	@Test
	public void searchUsers_paginated_limitOver1000_dataExhausted() {
		// limit=3000 but only 1500 docs → 2 batches: 1000 + 500
		stubSolr.setMultiBatchResults(1500);

		SearchResult result = service.searchUsers("bedroom", "test", 0, 3000);

		assertFalse(result.hasError());
		assertEquals(1500, result.getObjectIds().size());
		assertEquals(1500, result.getTotalCount());
		assertEquals(2, stubSolr.callCount);
	}

	// ==========================================
	// Stable sort verification
	// ==========================================

	@Test
	public void searchUsers_paginated_usesStableSort() {
		stubSolr.setResult(new ArrayList<>(), 0);
		service.searchUsers("bedroom", "test", 0, 20);

		assertEquals("object_id asc", stubSolr.lastSort);
	}

	@Test
	public void searchUsers_nonPaginated_usesStableSort() {
		stubSolr.setBatchResults(makeDocs(500), 500);
		service.searchUsers("bedroom", "test", 0, 0);

		assertEquals("object_id asc", stubSolr.lastSort);
	}

	// ==========================================
	// MISSING probe TTL re-evaluation
	// ==========================================

	@Test
	public void isSolrSearchEffective_missingExpires_retriesAfterTTL() {
		stubPm.setBoolValue(true);

		// Inject an expired MISSING state (timestamp = 6 minutes ago)
		long sixMinutesAgo = System.currentTimeMillis()
				- UserGroupSearchServiceImpl.MISSING_RETRY_INTERVAL_MS - 60_000;
		service.setProbeState("bedroom:user",
				new UserGroupSearchServiceImpl.ProbeState(false, sixMinutesAgo));

		// Now Solr has data → re-probe should succeed
		stubSolr.setResult(makeDocs(1), 5);

		assertTrue(service.isSolrUserSearchEffective("bedroom"));
		assertEquals(1, stubSolr.callCount); // re-probed
	}

	@Test
	public void isSolrSearchEffective_missingNotExpired_noRetry() {
		stubPm.setBoolValue(true);

		// Inject a fresh MISSING state (timestamp = just now)
		service.setProbeState("bedroom:user",
				new UserGroupSearchServiceImpl.ProbeState(false, System.currentTimeMillis()));

		// Even though Solr now has data, cached MISSING should be used
		stubSolr.setResult(makeDocs(1), 5);

		assertFalse(service.isSolrUserSearchEffective("bedroom"));
		assertEquals(0, stubSolr.callCount); // no re-probe
	}

	@Test
	public void isSolrSearchEffective_readyNotExpired_noRetry() {
		stubPm.setBoolValue(true);

		// Inject a fresh READY state (timestamp = 10 minutes ago, within 30-min TTL)
		long tenMinutesAgo = System.currentTimeMillis() - 10 * 60 * 1000L;
		service.setProbeState("bedroom:user",
				new UserGroupSearchServiceImpl.ProbeState(true, tenMinutesAgo));

		assertTrue(service.isSolrUserSearchEffective("bedroom"));
		assertEquals(0, stubSolr.callCount); // cached, no re-probe
	}

	@Test
	public void isSolrSearchEffective_readyExpires_retriesAfterTTL() {
		stubPm.setBoolValue(true);

		// Inject an expired READY state (timestamp = 31 minutes ago, beyond 30-min TTL)
		long thirtyOneMinutesAgo = System.currentTimeMillis()
				- UserGroupSearchServiceImpl.READY_RETRY_INTERVAL_MS - 60_000;
		service.setProbeState("bedroom:user",
				new UserGroupSearchServiceImpl.ProbeState(true, thirtyOneMinutesAgo));

		// Re-probe: Solr still has data → stays true
		stubSolr.setResult(makeDocs(1), 5);

		assertTrue(service.isSolrUserSearchEffective("bedroom"));
		assertEquals(1, stubSolr.callCount); // re-probed
	}

	@Test
	public void isSolrSearchEffective_readyExpires_solrCleared_becomesFalse() {
		stubPm.setBoolValue(true);

		// Inject an expired READY state
		long thirtyOneMinutesAgo = System.currentTimeMillis()
				- UserGroupSearchServiceImpl.READY_RETRY_INTERVAL_MS - 60_000;
		service.setProbeState("bedroom:user",
				new UserGroupSearchServiceImpl.ProbeState(true, thirtyOneMinutesAgo));

		// Re-probe: Solr has been cleared → numFound=0
		stubSolr.setResult(new ArrayList<>(), 0);

		assertFalse(service.isSolrUserSearchEffective("bedroom"));
		assertEquals(1, stubSolr.callCount); // re-probed and now false
	}

	// ==========================================
	// Coverage threshold tests (P1: partial backfill)
	// ==========================================

	@Test
	public void isSolrSearchEffective_coverageBelowThreshold_notReady() {
		stubPm.setBoolValue(true);
		service.setContentService(createStubContentService(80, 20)); // CouchDB: 80 users

		// Solr has only 79 user docs indexed → 79/80 = 98.75% < 100% threshold
		stubSolr.setResult(makeDocs(79), 79);

		assertFalse(service.isSolrUserSearchEffective("bedroom"));
		assertEquals(1, stubSolr.callCount);
	}

	@Test
	public void isSolrSearchEffective_coverageAboveThreshold_ready() {
		stubPm.setBoolValue(true);
		service.setContentService(createStubContentService(80, 20)); // CouchDB: 80 users

		// Solr has 81 docs indexed → 81/80 = 101.25% ≥ 100% threshold (race: new user indexed before CouchDB view updated)
		stubSolr.setResult(makeDocs(81), 81);

		assertTrue(service.isSolrUserSearchEffective("bedroom"));
		assertEquals(1, stubSolr.callCount);
	}

	@Test
	public void isSolrSearchEffective_coverageExactThreshold_ready() {
		stubPm.setBoolValue(true);
		service.setContentService(createStubContentService(80, 20)); // CouchDB: 80 users

		// Solr has exactly 80 docs → 80/80 = 100% = 100% threshold (boundary: should be ready)
		stubSolr.setResult(makeDocs(80), 80);

		assertTrue(service.isSolrUserSearchEffective("bedroom"));
	}

	@Test
	public void isSolrSearchEffective_noContentService_skipsCheck() {
		stubPm.setBoolValue(true);
		// ContentService not injected (null) → coverage check skipped, numFound > 0 is enough
		stubSolr.setResult(makeDocs(1), 1);

		assertTrue(service.isSolrUserSearchEffective("bedroom"));
		assertEquals(1, stubSolr.callCount);
	}

	@Test
	public void isSolrSearchEffective_couchDbCountZero_skipsCheck() {
		stubPm.setBoolValue(true);
		service.setContentService(createStubContentService(0, 0)); // CouchDB empty

		// Solr has data but CouchDB reports 0 → skip coverage check, treat as ready
		stubSolr.setResult(makeDocs(5), 5);

		assertTrue(service.isSolrUserSearchEffective("bedroom"));
	}

	@Test
	public void isSolrSearchEffective_coverageCheckCached() {
		stubPm.setBoolValue(true);
		service.setContentService(createStubContentService(80, 20)); // CouchDB: 80 users

		stubSolr.setResult(makeDocs(80), 80);

		// First call: probes + coverage check → READY (80/80 = 100%)
		assertTrue(service.isSolrUserSearchEffective("bedroom"));
		// Second call: cached, no additional Solr call
		assertTrue(service.isSolrUserSearchEffective("bedroom"));
		assertEquals(1, stubSolr.callCount);
	}

	// ==========================================
	// Separate user/group probe independence tests
	// ==========================================

	@Test
	public void separateProbes_userReadyGroupNot() {
		stubPm.setBoolValue(true);
		// 100 users, 10 groups in CouchDB
		service.setContentService(createStubContentService(100, 10));

		// Solr: users fully indexed (100), groups not yet (0)
		stubSolr.setMultiQueryResults("dynamic.usersearch.user_id:*", makeDocs(100), 100);
		stubSolr.setMultiQueryResults("dynamic.usersearch.group_id:*", new ArrayList<>(), 0);

		assertTrue(service.isSolrUserSearchEffective("bedroom"), "User search should be ready (100/100)");
		assertFalse(service.isSolrGroupSearchEffective("bedroom"), "Group search should NOT be ready (0/10)");
	}

	@Test
	public void separateProbes_groupReadyUserNot() {
		stubPm.setBoolValue(true);
		// 100 users, 10 groups in CouchDB
		service.setContentService(createStubContentService(100, 10));

		// Solr: groups fully indexed (10), users only partially (5/100)
		stubSolr.setMultiQueryResults("dynamic.usersearch.user_id:*", makeDocs(5), 5);
		stubSolr.setMultiQueryResults("dynamic.usersearch.group_id:*", makeDocs(10), 10);

		assertFalse(service.isSolrUserSearchEffective("bedroom"), "User search should NOT be ready (5/100)");
		assertTrue(service.isSolrGroupSearchEffective("bedroom"), "Group search should be ready (10/10)");
	}

	@Test
	public void separateProbes_bothReady() {
		stubPm.setBoolValue(true);
		service.setContentService(createStubContentService(100, 10));

		// Both fully indexed
		stubSolr.setMultiQueryResults("dynamic.usersearch.user_id:*", makeDocs(100), 100);
		stubSolr.setMultiQueryResults("dynamic.usersearch.group_id:*", makeDocs(10), 10);

		assertTrue(service.isSolrUserSearchEffective("bedroom"));
		assertTrue(service.isSolrGroupSearchEffective("bedroom"));
	}

	@Test
	public void separateProbes_cachedIndependently() {
		stubPm.setBoolValue(true);
		service.setContentService(createStubContentService(100, 10));

		stubSolr.setMultiQueryResults("dynamic.usersearch.user_id:*", makeDocs(100), 100);
		stubSolr.setMultiQueryResults("dynamic.usersearch.group_id:*", makeDocs(10), 10);

		// First calls
		assertTrue(service.isSolrUserSearchEffective("bedroom"));
		assertTrue(service.isSolrGroupSearchEffective("bedroom"));
		assertEquals(2, stubSolr.callCount);

		// Second calls: both cached
		assertTrue(service.isSolrUserSearchEffective("bedroom"));
		assertTrue(service.isSolrGroupSearchEffective("bedroom"));
		assertEquals(2, stubSolr.callCount); // no additional calls
	}

	// ==========================================
	// Helpers
	// ==========================================

	/**
	 * Create a minimal ContentService stub via dynamic proxy.
	 * Only getUserItemCount and getGroupItemCount are functional.
	 */
	private ContentService createStubContentService(int userCount, int groupCount) {
		return (ContentService) Proxy.newProxyInstance(
				ContentService.class.getClassLoader(),
				new Class[]{ContentService.class},
				(proxy, method, args) -> {
					if ("getUserItemCount".equals(method.getName())) return userCount;
					if ("getGroupItemCount".equals(method.getName())) return groupCount;
					throw new UnsupportedOperationException("Stub: " + method.getName());
				});
	}

	private Map<String, Object> docWithObjectId(String id) {
		Map<String, Object> doc = new HashMap<>();
		doc.put("object_id", id);
		return doc;
	}

	private List<Map<String, Object>> makeDocs(int count) {
		List<Map<String, Object>> docs = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			docs.add(docWithObjectId("id-" + i));
		}
		return docs;
	}

	// ==========================================
	// Stub: SolrIndexMaintenanceService
	// ==========================================

	static class StubSolrIndexMaintenanceService implements SolrIndexMaintenanceService {
		String lastQuery;
		int lastStart;
		int lastRows;
		String lastSort;
		String lastFields;
		int callCount = 0;
		List<Integer> batchStarts = new ArrayList<>();
		private SolrQueryResult result;
		boolean throwException = false;

		// Multi-batch support
		private int multiBatchTotalDocs = -1;
		private int errorAtBatch = -1;

		// Per-repository probe results
		private Map<String, SolrQueryResult> perRepoResults = new HashMap<>();

		// Per-query substring results (for split probe tests)
		private Map<String, SolrQueryResult> perQueryResults = new HashMap<>();

		void setResult(List<Map<String, Object>> docs, long numFound) {
			multiBatchTotalDocs = -1;
			errorAtBatch = -1;
			perRepoResults.clear();
			result = new SolrQueryResult();
			result.setDocs(docs);
			result.setNumFound(numFound);
		}

		void setBatchResults(List<Map<String, Object>> docs, long numFound) {
			setResult(docs, numFound);
		}

		void setErrorResult(String errorMessage) {
			multiBatchTotalDocs = -1;
			errorAtBatch = -1;
			perRepoResults.clear();
			result = new SolrQueryResult();
			result.setErrorMessage(errorMessage);
		}

		/** Configure per-repository probe results for testing multi-repo scenarios. */
		void setPerRepoProbeResult(String repositoryId, List<Map<String, Object>> docs, long numFound) {
			SolrQueryResult r = new SolrQueryResult();
			r.setDocs(docs);
			r.setNumFound(numFound);
			perRepoResults.put(repositoryId, r);
		}

		/** Configure multi-batch: totalDocs docs split across 1000-doc batches. */
		void setMultiBatchResults(int totalDocs) {
			this.multiBatchTotalDocs = totalDocs;
			this.errorAtBatch = -1;
			this.result = null;
			this.perRepoResults.clear();
		}

		/** Configure multi-batch with error on batch N (1-indexed). */
		void setMultiBatchWithErrorAt(int errorBatchNum, int totalDocs) {
			this.multiBatchTotalDocs = totalDocs;
			this.errorAtBatch = errorBatchNum;
			this.result = null;
			this.perRepoResults.clear();
		}

		/** Configure query-substring-based results (for split user/group probe tests). */
		void setMultiQueryResults(String querySubstring, List<Map<String, Object>> docs, long numFound) {
			SolrQueryResult r = new SolrQueryResult();
			r.setDocs(docs);
			r.setNumFound(numFound);
			perQueryResults.put(querySubstring, r);
		}

		@Override
		public SolrQueryResult executeSolrQuery(String repositoryId, String query, int start, int rows, String sort, String fields) {
			if (throwException) {
				throw new RuntimeException("Simulated Solr exception");
			}
			callCount++;
			this.lastQuery = query;
			this.lastStart = start;
			this.lastRows = rows;
			this.lastSort = sort;
			this.lastFields = fields;
			batchStarts.add(start);

			// Per-query-substring results take highest priority (for split probe tests)
			if (!perQueryResults.isEmpty()) {
				for (Map.Entry<String, SolrQueryResult> entry : perQueryResults.entrySet()) {
					if (query.contains(entry.getKey())) {
						return entry.getValue();
					}
				}
			}

			// Per-repository results (for multi-repo probe tests)
			if (!perRepoResults.isEmpty() && perRepoResults.containsKey(repositoryId)) {
				return perRepoResults.get(repositoryId);
			}

			if (multiBatchTotalDocs >= 0) {
				// Check if this batch should return error
				if (errorAtBatch > 0 && callCount == errorAtBatch) {
					SolrQueryResult r = new SolrQueryResult();
					r.setErrorMessage("Simulated batch error at batch " + callCount);
					return r;
				}
				// Compute docs for this batch
				int remaining = multiBatchTotalDocs - start;
				int batchSize = Math.min(remaining, rows);
				if (batchSize < 0) batchSize = 0;
				List<Map<String, Object>> docs = new ArrayList<>();
				for (int i = 0; i < batchSize; i++) {
					Map<String, Object> doc = new HashMap<>();
					doc.put("object_id", "id-" + (start + i));
					docs.add(doc);
				}
				SolrQueryResult r = new SolrQueryResult();
				r.setDocs(docs);
				r.setNumFound(multiBatchTotalDocs);
				return r;
			}

			return result;
		}

		// Unused methods - provide minimal implementations
		@Override public boolean startFullReindex(String repositoryId) { return false; }
		@Override public boolean startFolderReindex(String repositoryId, String folderId, boolean recursive) { return false; }
		@Override public ReindexStatus getReindexStatus(String repositoryId) { return null; }
		@Override public boolean cancelReindex(String repositoryId) { return false; }
		@Override public IndexHealthStatus checkIndexHealth(String repositoryId) { return null; }
		@Override public IndexDiscrepancyResult getIndexDiscrepancies(String repositoryId) { return null; }
		@Override public long purgeOrphanedIndexEntries(String repositoryId) { return 0; }
		@Override public boolean reindexDocument(String repositoryId, String objectId) { return false; }
		@Override public boolean deleteFromIndex(String repositoryId, String objectId) { return false; }
		@Override public boolean clearIndex(String repositoryId) { return false; }
		@Override public boolean optimizeIndex(String repositoryId) { return false; }
	}

	// ==========================================
	// Stub: PropertyManager
	// ==========================================

	static class StubPropertyManager extends PropertyManager {
		private boolean boolValue = false;

		StubPropertyManager() {
			super();
		}

		void setBoolValue(boolean value) {
			this.boolValue = value;
		}

		@Override
		public boolean readBoolean(String key) {
			if (PropertyKey.SEARCH_USERGROUP_SOLR_ENABLED.equals(key)) {
				return boolValue;
			}
			return false;
		}
	}
}
