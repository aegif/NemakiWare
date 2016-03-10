package jp.aegif.nemaki.test.cmis.tests;

import org.apache.chemistry.opencmis.tck.tests.filing.MultifilingTest;
import org.apache.chemistry.opencmis.tck.tests.filing.UnfilingTest;
import org.junit.Test;

import jp.aegif.nemaki.test.cmis.TestGroupBase;
import jp.aegif.nemaki.test.cmis.TestHelper;

public class FilingTestGroup extends TestGroupBase{
	@Test
	public void multifilingTest() throws Exception{
		MultifilingTest test = new MultifilingTest();
		TestHelper.run(test);
	}
	
	@Test
	public void unfilingTest() throws Exception{
		UnfilingTest test = new UnfilingTest();
		TestHelper.run(test);
	}
}
