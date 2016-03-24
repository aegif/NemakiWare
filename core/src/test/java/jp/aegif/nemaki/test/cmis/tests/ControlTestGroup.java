package jp.aegif.nemaki.test.cmis.tests;

import org.apache.chemistry.opencmis.tck.tests.control.ACLSmokeTest;
import org.junit.Test;

import jp.aegif.nemaki.test.cmis.TckSuite;

public class ControlTestGroup extends TckSuite{
	@Test
	public void aclSmokeTest() throws Exception{
		ACLSmokeTest test = new ACLSmokeTest();
		run(test);
	}
}
