package jp.aegif.nemaki.cmis.tck.tests;

import org.apache.chemistry.opencmis.tck.tests.filing.MultifilingTest;
import org.apache.chemistry.opencmis.tck.tests.filing.UnfilingTest;
import org.junit.Test;
import org.junit.Ignore;

import jp.aegif.nemaki.cmis.tck.TckSuite;

/**
 * FilingTestGroup - Tests for CMIS Filing features (Multi-filing and Unfiling)
 *
 * ========================================================================
 * PRODUCT SPECIFICATION: NemakiWare does NOT support Multi-filing/Unfiling
 * ========================================================================
 *
 * Multi-filing allows a single object to exist in multiple parent folders.
 * Unfiling allows objects to exist without any parent folder.
 *
 * These are OPTIONAL CMIS 1.1 capabilities that NemakiWare has chosen not to
 * implement as a product design decision. This is the ONLY TCK test group
 * that is intentionally skipped.
 *
 * CRITICAL: This @Ignore annotation must NOT be removed without explicit
 * user authorization. See CLAUDE.md "TCK IMPLEMENTATION POLICY" section.
 *
 * Policy Reference: CLAUDE.md line 39-98 (TCK IMPLEMENTATION POLICY)
 * Established: 2025-10-21
 *
 * This is a product specification limitation, not a bug.
 */
@Ignore("NemakiWare does not support Multifiling/Unfiling - PRODUCT SPECIFICATION (see CLAUDE.md TCK IMPLEMENTATION POLICY)")
public class FilingTestGroup extends TckSuite{
	@Test
	public void multifilingTest() throws Exception{
		MultifilingTest test = new MultifilingTest();
		run(test);
	}
	
	@Test
	public void unfilingTest() throws Exception{
		UnfilingTest test = new UnfilingTest();
		run(test);
	}
}
