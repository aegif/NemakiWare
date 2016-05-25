package jp.aegif.nemaki.test.tck.tests;

import org.apache.chemistry.opencmis.tck.tests.basics.RepositoryInfoTest;
import org.apache.chemistry.opencmis.tck.tests.basics.RootFolderTest;
import org.apache.chemistry.opencmis.tck.tests.basics.SecurityTest;
import org.junit.Test;

import jp.aegif.nemaki.test.tck.TckSuite;

public class BasicsTestGroup extends TckSuite{
	
	@Test
	public void securityTest() throws Exception{
		SecurityTest test = new SecurityTest();
		run(test);
	}
	
	@Test
	public void repositoryInfoTest() throws Exception{
		RepositoryInfoTest test = new RepositoryInfoTest();
		run(test);
	}
	
	@Test
	public void rootFolderTest() throws Exception{
		RootFolderTest test = new RootFolderTest();
		run(test);
	}
}
