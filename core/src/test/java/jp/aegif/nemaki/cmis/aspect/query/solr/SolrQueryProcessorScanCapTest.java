package jp.aegif.nemaki.cmis.aspect.query.solr;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
