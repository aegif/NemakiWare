package jp.aegif.nemaki.cmis.tck.tests;

import org.apache.chemistry.opencmis.tck.tests.filing.MultifilingTest;
import org.apache.chemistry.opencmis.tck.tests.filing.UnfilingTest;
import org.junit.Test;

import jp.aegif.nemaki.cmis.tck.TckSuite;

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
