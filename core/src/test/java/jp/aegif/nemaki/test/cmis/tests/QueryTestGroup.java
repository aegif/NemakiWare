package jp.aegif.nemaki.test.cmis.tests;

import org.apache.chemistry.opencmis.tck.tests.query.ContentChangesSmokeTest;
import org.apache.chemistry.opencmis.tck.tests.query.QueryForObject;
import org.apache.chemistry.opencmis.tck.tests.query.QueryInFolderTest;
import org.apache.chemistry.opencmis.tck.tests.query.QueryLikeTest;
import org.apache.chemistry.opencmis.tck.tests.query.QueryRootFolderTest;
import org.apache.chemistry.opencmis.tck.tests.query.QuerySmokeTest;
import org.junit.Test;

import jp.aegif.nemaki.test.cmis.TestGroupBase;
import jp.aegif.nemaki.test.cmis.TestHelper;

public class QueryTestGroup extends TestGroupBase{
	@Test
	public void querySmokeTest() throws Exception{
		QuerySmokeTest test = new QuerySmokeTest();
		TestHelper.run(test);
	}
	
	@Test
	public void queryRootFolderTest() throws Exception{
		QueryRootFolderTest test = new QueryRootFolderTest();
		TestHelper.run(test);
	}
	
	@Test
	public void queryForObject() throws Exception{
		QueryForObject test = new QueryForObject();
		TestHelper.run(test);
	}
	
	@Test
	public void queryLikeTest() throws Exception{
		QueryLikeTest test = new QueryLikeTest();
		TestHelper.run(test);
	}
	
	@Test
	public void queryInFolderTest() throws Exception{
		QueryInFolderTest test = new QueryInFolderTest();
		TestHelper.run(test);
	}
	
	@Test
	public void contentChangesSmokeTest() throws Exception{
		ContentChangesSmokeTest test = new ContentChangesSmokeTest();
		TestHelper.run(test);
	}
}
