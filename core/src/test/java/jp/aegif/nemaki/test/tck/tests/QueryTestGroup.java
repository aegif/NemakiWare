package jp.aegif.nemaki.test.tck.tests;

import org.apache.chemistry.opencmis.tck.tests.query.ContentChangesSmokeTest;
import org.apache.chemistry.opencmis.tck.tests.query.QueryForObject;
import org.apache.chemistry.opencmis.tck.tests.query.QueryInFolderTest;
import org.apache.chemistry.opencmis.tck.tests.query.QueryLikeTest;
import org.apache.chemistry.opencmis.tck.tests.query.QueryRootFolderTest;
import org.apache.chemistry.opencmis.tck.tests.query.QuerySmokeTest;
import org.junit.Test;

import jp.aegif.nemaki.test.tck.TckSuite;

public class QueryTestGroup extends TckSuite{
	@Test
	public void querySmokeTest() throws Exception{
		QuerySmokeTest test = new QuerySmokeTest();
		run(test);
	}
	
	@Test
	public void queryRootFolderTest() throws Exception{
		QueryRootFolderTest test = new QueryRootFolderTest();
		run(test);
	}
	
	@Test
	public void queryForObject() throws Exception{
		QueryForObject test = new QueryForObject();
		run(test);
	}
	
	@Test
	public void queryLikeTest() throws Exception{
		QueryLikeTest test = new QueryLikeTest();
		run(test);
	}
	
	@Test
	public void queryInFolderTest() throws Exception{
		QueryInFolderTest test = new QueryInFolderTest();
		run(test);
	}
	
	@Test
	public void contentChangesSmokeTest() throws Exception{
		ContentChangesSmokeTest test = new ContentChangesSmokeTest();
		run(test);
	}
}
