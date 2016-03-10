package jp.aegif.nemaki.test.cmis.tests;

import org.apache.chemistry.opencmis.tck.tests.basics.RepositoryInfoTest;
import org.apache.chemistry.opencmis.tck.tests.basics.RootFolderTest;
import org.apache.chemistry.opencmis.tck.tests.basics.SecurityTest;
import org.junit.Test;

import jp.aegif.nemaki.test.cmis.TestGroupBase;
import jp.aegif.nemaki.test.cmis.TestHelper;

public class BasicTestGroup extends TestGroupBase{
	@Test
	public void securityTest() throws Exception{
		SecurityTest test = new SecurityTest();
		TestHelper.run(test);
	}
	
	@Test
	public void repositoryInfoTest() throws Exception{
		RepositoryInfoTest test = new RepositoryInfoTest();
		TestHelper.run(test);
	}
	
	@Test
	public void rootFolderTest() throws Exception{
		RootFolderTest test = new RootFolderTest();
		TestHelper.run(test);
	}
}
