package jp.aegif.nemaki.test.cmis.tests;

import org.apache.chemistry.opencmis.tck.tests.versioning.CheckedOutTest;
import org.apache.chemistry.opencmis.tck.tests.versioning.VersionDeleteTest;
import org.apache.chemistry.opencmis.tck.tests.versioning.VersioningSmokeTest;
import org.apache.chemistry.opencmis.tck.tests.versioning.VersioningStateCreateTest;
import org.junit.Test;

import jp.aegif.nemaki.test.cmis.TckSuite;

public class VersioningTestGroup extends TckSuite{
	@Test
	public void versioningSmokeTest() throws Exception{
		VersioningSmokeTest test = new VersioningSmokeTest();
		run(test);
	}
	
	@Test
	public void versionDeleteTest() throws Exception{
		VersionDeleteTest test = new VersionDeleteTest();
		run(test);
	}
	
	@Test
	public void versioningStateCreateTest() throws Exception{
		VersioningStateCreateTest test = new VersioningStateCreateTest();
		run(test);
	}
	
	@Test
	public void checkedOutTest() throws Exception{
		CheckedOutTest test = new CheckedOutTest();
		run(test);
	}
}
