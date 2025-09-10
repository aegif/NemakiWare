package jp.aegif.nemaki.cmis.tck.tests;

import org.apache.chemistry.opencmis.tck.tests.filing.MultifilingTest;
import org.apache.chemistry.opencmis.tck.tests.filing.UnfilingTest;
import org.junit.Test;
import org.junit.Ignore;

import jp.aegif.nemaki.cmis.tck.TckSuite;

/**
 * FilingTestGroup - Tests for CMIS Filing features
 * 
 * NOTE: NemakiWare does NOT support Multifiling and Unfiling features.
 * These are optional CMIS capabilities that NemakiWare has chosen not to implement.
 * The tests are disabled via cmis-tck-filters.properties (FilingTestGroup=false).
 * 
 * This is a product specification limitation, not a bug.
 */
@Ignore("NemakiWare does not support Multifiling/Unfiling - product specification")
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
