package jp.aegif.nemaki.test.cmis.tests;

import org.apache.chemistry.opencmis.tck.tests.control.ACLSmokeTest;
import org.junit.Test;

import jp.aegif.nemaki.test.cmis.TestGroupBase;
import jp.aegif.nemaki.test.cmis.TestHelper;

public class ControlTestGroup extends TestGroupBase{
	@Test
	public void aclSmokeTest() throws Exception{
		ACLSmokeTest test = new ACLSmokeTest();
		TestHelper.run(test);
	}
}
