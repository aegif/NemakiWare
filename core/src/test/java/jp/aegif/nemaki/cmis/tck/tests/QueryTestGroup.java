package jp.aegif.nemaki.cmis.tck.tests;

import org.apache.chemistry.opencmis.tck.tests.query.ContentChangesSmokeTest;
import jp.aegif.nemaki.cmis.tck.tests.settling.SettlingQueryForObject;
import jp.aegif.nemaki.cmis.tck.tests.settling.SettlingQueryInFolder;
import jp.aegif.nemaki.cmis.tck.tests.settling.SettlingQueryLike;
import org.apache.chemistry.opencmis.tck.tests.query.QueryRootFolderTest;
import org.apache.chemistry.opencmis.tck.tests.query.QuerySmokeTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

import jp.aegif.nemaki.cmis.tck.TckSuite;

public class QueryTestGroup extends TckSuite{

	/**
	 * Override binding to Browser for Query tests.
	 * AtomPub binding has a known issue with CMIS query session creation
	 * (CmisInvalidArgumentException: null on AtomPub session). Browser binding
	 * is the primary CMIS 1.1 binding and works correctly for queries.
	 * Other test groups continue to use AtomPub (the default) for regression coverage.
	 *
	 * Only the binding type is overridden; the browser URL is read from
	 * the parameters file so that CI/alternate-port environments work correctly.
	 */
	@Override
	protected java.util.Map<String, String> getParameterOverrides() {
		java.util.Map<String, String> overrides = new java.util.HashMap<>();
		overrides.put("org.apache.chemistry.opencmis.binding.spi.type", "browser");
		return overrides;
	}

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
		// Waits for the index; see SearchIndexSettle.
		SettlingQueryForObject test = new SettlingQueryForObject();
		run(test);
	}
	
	@Test
	public void queryLikeTest() throws Exception{
		// Waits for the index; see SearchIndexSettle.
		SettlingQueryLike test = new SettlingQueryLike();
		run(test);
	}
	
	@Test
	public void queryInFolderTest() throws Exception{
		// Waits for the index; see SearchIndexSettle.
		SettlingQueryInFolder test = new SettlingQueryInFolder();
		run(test);
	}
	
	@Test
	public void contentChangesSmokeTest() throws Exception{
		ContentChangesSmokeTest test = new ContentChangesSmokeTest();
		run(test);
	}
}
