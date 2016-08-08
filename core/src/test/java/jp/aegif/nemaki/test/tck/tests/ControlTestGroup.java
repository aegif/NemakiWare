package jp.aegif.nemaki.test.tck.tests;

import org.apache.chemistry.opencmis.tck.tests.control.ACLSmokeTest;
import org.junit.Test;

import jp.aegif.nemaki.test.tck.TckSuite;

public class ControlTestGroup extends TckSuite{
	@Test
	public void aclSmokeTest() throws Exception{
		ACLSmokeTest test = new ACLSmokeTest();
		run(test);
	}
}
