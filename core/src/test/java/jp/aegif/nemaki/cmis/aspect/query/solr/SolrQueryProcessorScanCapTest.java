package jp.aegif.nemaki.cmis.aspect.query.solr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.SolrParams;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the ACL-scan reachability fix. A CMIS query fetches at
 * most {@code aclScanMaxRows} Solr rows and then authorizes / sorts / pages them
 * in memory, so a pre-ACL match set larger than the cap cannot be paged past the
 * cap and must be rejected — NOT returned as a truncated page with a misleading
 * {@code hasMoreItems=true} (which looped a paging client forever). This pins the
 * boundary of {@link SolrQueryProcessor#exceedsScanCap(long, int)}: {@code cap}
 * is allowed, {@code cap + 1} is rejected.
 */
public class SolrQueryProcessorScanCapTest {

	@Test
	public void withinCapIsAllowed() {
		assertFalse(SolrQueryProcessor.exceedsScanCap(0, 10000), "0 results must not be rejected");
		assertFalse(SolrQueryProcessor.exceedsScanCap(1, 10000));
		assertFalse(SolrQueryProcessor.exceedsScanCap(9999, 10000));
	}

	@Test
	public void exactlyAtCapIsAllowed() {
		// The whole authorized set is materialized when numFound == cap, so the
		// result is still correct and complete — do not reject the boundary.
		assertFalse(SolrQueryProcessor.exceedsScanCap(10000, 10000),
				"numFound == cap must be allowed (the full set is still materialized)");
	}

	@Test
	public void oneOverCapIsRejected() {
		assertTrue(SolrQueryProcessor.exceedsScanCap(10001, 10000),
				"numFound == cap + 1 must be rejected (the extra row is unreachable)");
	}

	@Test
	public void farOverCapIsRejected() {
		assertTrue(SolrQueryProcessor.exceedsScanCap(1_000_000L, 10000));
	}

	@Test
	public void respectsACustomCap() {
		// The cap is configurable via -Dnemakiware.cmis.query.aclScanMaxRows.
		assertFalse(SolrQueryProcessor.exceedsScanCap(2, 2), "at the custom cap: allowed");
		assertTrue(SolrQueryProcessor.exceedsScanCap(3, 2), "one over the custom cap: rejected");
	}

	// ── Two-phase queryWithinScanCap behaviour (mock SolrClient) ──

	/** Records the ROWS value seen on each SolrClient.query call (the query is mutated in place). */
	private static SolrClient clientReturning(List<Integer> rowsSeen, long... numFoundPerCall) {
		SolrClient client = mock(SolrClient.class);
		try {
			when(client.query(any(SolrParams.class))).thenAnswer(inv -> {
				SolrParams p = inv.getArgument(0);
				int callIndex = rowsSeen.size();
				rowsSeen.add(p.getInt(CommonParams.ROWS, -1));
				long nf = numFoundPerCall[Math.min(callIndex, numFoundPerCall.length - 1)];
				QueryResponse qr = mock(QueryResponse.class);
				SolrDocumentList sdl = new SolrDocumentList();
				sdl.setNumFound(nf);
				when(qr.getResults()).thenReturn(sdl);
				return qr;
			});
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		return client;
	}

	@Test
	public void overCapProbeRejectsWithoutASecondQuery() throws Exception {
		List<Integer> rowsSeen = new ArrayList<>();
		// Phase 1 (rows=0) already reports > cap.
		SolrClient client = clientReturning(rowsSeen, 10001);
		SolrQuery q = new SolrQuery();

		CmisInvalidArgumentException ex = assertThrows(CmisInvalidArgumentException.class,
				() -> SolrQueryProcessor.queryWithinScanCap(client, q, 10000, () -> false));

		// Only ONE query was issued (no cap-sized body fetch on rejection)...
		verify(client, times(1)).query(any(SolrParams.class));
		// ...and it was the rows=0 probe.
		assertEquals(1, rowsSeen.size());
		assertEquals(0, rowsSeen.get(0), "phase 1 must query with rows=0 (no body transfer)");
		// The message must NOT leak the pre-ACL count.
		assertFalse(ex.getMessage().matches(".*\\b10001\\b.*"),
				"rejection message must not echo the pre-ACL numFound");
	}

	/**
	 * While a permission change is propagating the index still carries the revoked principal's
	 * tokens, so the pre-gate count is inflated by rows the in-memory gate is about to remove.
	 * Rejecting then makes a revocation look like a broken search. The degradation is gated on
	 * that signal alone — a query that is genuinely too broad is still refused.
	 */
	@Test
	public void overCapDegradesOnlyWhileAPermissionChangeIsPropagating() throws Exception {
		List<Integer> rowsSeen = new ArrayList<>();
		SolrClient client = clientReturning(rowsSeen, 10001, 10001);
		SolrQuery q = new SolrQuery();

		SolrQueryProcessor.CappedResult r =
				SolrQueryProcessor.queryWithinScanCap(client, q, 10000, () -> true);

		assertTrue(r.truncated, "the caller must be told the rows are a prefix");
		verify(client, times(2)).query(any(SolrParams.class));
		assertEquals(0, rowsSeen.get(0), "the cheap rows=0 probe still runs first");
		assertEquals(10000, rowsSeen.get(1), "the fetch stays bounded by the cap");
	}

	@Test
	public void overCapStillRejectsWhenNothingIsPropagating() throws Exception {
		List<Integer> rowsSeen = new ArrayList<>();
		SolrClient client = clientReturning(rowsSeen, 10001);
		SolrQuery q = new SolrQuery();

		assertThrows(CmisInvalidArgumentException.class,
				() -> SolrQueryProcessor.queryWithinScanCap(client, q, 10000, () -> false));
		verify(client, times(1)).query(any(SolrParams.class));
	}

	@Test
	public void withinCapIsNeverMarkedTruncated() throws Exception {
		List<Integer> rowsSeen = new ArrayList<>();
		SolrClient client = clientReturning(rowsSeen, 42, 42);
		SolrQuery q = new SolrQuery();

		SolrQueryProcessor.CappedResult r =
				SolrQueryProcessor.queryWithinScanCap(client, q, 10000, () -> true);

		assertFalse(r.truncated,
				"a query within the cap is complete; marking it truncated because something"
						+ " unrelated is propagating would make every client distrust every page");
	}

	@Test
	public void withinCapFetchesWithRowsCapAfterTheProbe() throws Exception {
		List<Integer> rowsSeen = new ArrayList<>();
		// Phase 1 (rows=0) -> 42 (<= cap); phase 2 (rows=cap) -> 42.
		SolrClient client = clientReturning(rowsSeen, 42, 42);
		SolrQuery q = new SolrQuery();

		QueryResponse resp = SolrQueryProcessor.queryWithinScanCap(client, q, 10000, () -> false).response;

		verify(client, times(2)).query(any(SolrParams.class));
		assertEquals(0, rowsSeen.get(0), "phase 1 is the rows=0 probe");
		assertEquals(10000, rowsSeen.get(1), "phase 2 fetches with rows=cap");
		assertEquals(42L, resp.getResults().getNumFound());
	}

	@Test
	public void growthBetweenProbeAndFetchIsRejectedOnRecheck() throws Exception {
		List<Integer> rowsSeen = new ArrayList<>();
		// Probe sees 9000 (<= cap 10000) but the fetch sees 10001 (docs added).
		SolrClient client = clientReturning(rowsSeen, 9000, 10001);
		SolrQuery q = new SolrQuery();

		assertThrows(CmisInvalidArgumentException.class,
				() -> SolrQueryProcessor.queryWithinScanCap(client, q, 10000, () -> false));

		// Both phases ran (probe passed, fetch happened), then the re-check rejected.
		verify(client, times(2)).query(any(SolrParams.class));
		assertEquals(10000, rowsSeen.get(1), "phase 2 still fetches with rows=cap before the re-check");
	}

	@Test
	public void exactlyAtCapProbeProceedsToFetch() throws Exception {
		List<Integer> rowsSeen = new ArrayList<>();
		SolrClient client = clientReturning(rowsSeen, 10000, 10000);
		SolrQuery q = new SolrQuery();

		QueryResponse resp = SolrQueryProcessor.queryWithinScanCap(client, q, 10000, () -> false).response;
		verify(client, times(2)).query(any(SolrParams.class));
		assertEquals(10000L, resp.getResults().getNumFound());
	}
}
