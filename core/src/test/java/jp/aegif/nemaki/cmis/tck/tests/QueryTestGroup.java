package jp.aegif.nemaki.cmis.tck.tests;

import org.apache.chemistry.opencmis.tck.tests.query.ContentChangesSmokeTest;
import org.apache.chemistry.opencmis.tck.tests.query.QueryForObject;
import org.apache.chemistry.opencmis.tck.tests.query.QueryInFolderTest;
import org.apache.chemistry.opencmis.tck.tests.query.QueryLikeTest;
import org.apache.chemistry.opencmis.tck.tests.query.QueryRootFolderTest;
import org.apache.chemistry.opencmis.tck.tests.query.QuerySmokeTest;
import org.junit.Test;
import org.junit.Ignore;

import jp.aegif.nemaki.cmis.tck.TckSuite;

// @Ignore("TCK tests temporarily disabled due to server connectivity issues - requires running CMIS server on localhost:8080") - ENABLED for investigation
public class QueryTestGroup extends TckSuite{

	// Static initialization and constructor - no debug logging needed

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
