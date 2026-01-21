package jp.aegif.nemaki.cmis.tck.tests;

import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.tck.tests.types.BaseTypesTest;
import org.apache.chemistry.opencmis.tck.tests.types.CreateAndDeleteTypeTest;
import org.apache.chemistry.opencmis.tck.tests.types.SecondaryTypesTest;
import org.apache.chemistry.opencmis.tck.impl.WrapperCmisTestGroup;
import org.junit.Test;
import org.junit.Ignore;

import jp.aegif.nemaki.cmis.tck.TckSuite;

// @Ignore("TCK tests temporarily disabled due to data visibility issues - see CLAUDE.md") - ENABLED: Data visibility issues resolved
public class TypesTestGroup extends TckSuite{
	@Test
	public void baseTypesTest() throws Exception{
		BaseTypesTest test = new BaseTypesTest();
		run(test);
	}
	
	@Test
	public void createAndDeleteTypeTest() throws Exception{
		// RESTORED: Standard OpenCMIS TCK implementation (abandoned wrapper approach removed)
		CreateAndDeleteTypeTest test = new CreateAndDeleteTypeTest();
		run(test);  // Use the standard TckSuite.run() method
	}
	
	@Test
	public void secondaryTypesTest() throws Exception{
		SecondaryTypesTest test = new SecondaryTypesTest();
		run(test);
	}

}
