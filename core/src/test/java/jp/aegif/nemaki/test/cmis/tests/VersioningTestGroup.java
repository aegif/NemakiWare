package jp.aegif.nemaki.test.cmis.tests;

import org.apache.chemistry.opencmis.tck.tests.versioning.CheckedOutTest;
import org.apache.chemistry.opencmis.tck.tests.versioning.VersionDeleteTest;
import org.apache.chemistry.opencmis.tck.tests.versioning.VersioningSmokeTest;
import org.apache.chemistry.opencmis.tck.tests.versioning.VersioningStateCreateTest;
import org.junit.Test;

import jp.aegif.nemaki.test.cmis.TestGroupBase;
import jp.aegif.nemaki.test.cmis.TestHelper;

public class VersioningTestGroup extends TestGroupBase{
	@Test
	public void versioningSmokeTest() throws Exception{
		VersioningSmokeTest test = new VersioningSmokeTest();
		TestHelper.run(test);
	}
	
	@Test
	public void versionDeleteTest() throws Exception{
		VersionDeleteTest test = new VersionDeleteTest();
		TestHelper.run(test);
	}
	
	@Test
	public void versioningStateCreateTest() throws Exception{
		VersioningStateCreateTest test = new VersioningStateCreateTest();
		TestHelper.run(test);
	}
	
	@Test
	public void checkedOutTest() throws Exception{
		CheckedOutTest test = new CheckedOutTest();
		TestHelper.run(test);
	}
}
