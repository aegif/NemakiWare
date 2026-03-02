package jp.aegif.nemaki.cmis.tck.tests;

import org.apache.chemistry.opencmis.tck.tests.control.ACLSmokeTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

import jp.aegif.nemaki.cmis.tck.TckSuite;

// @Disabled("TCK tests temporarily disabled due to data visibility issues - see CLAUDE.md") - ENABLED for investigation
public class ControlTestGroup extends TckSuite{
	@Test
	public void aclSmokeTest() throws Exception{
		ACLSmokeTest test = new ACLSmokeTest();
		run(test);
	}
}
