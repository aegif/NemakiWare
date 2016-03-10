package jp.aegif.nemaki.test.cmis.tests;

import org.apache.chemistry.opencmis.tck.tests.types.BaseTypesTest;
import org.apache.chemistry.opencmis.tck.tests.types.CreateAndDeleteTypeTest;
import org.apache.chemistry.opencmis.tck.tests.types.SecondaryTypesTest;
import org.junit.Test;

import jp.aegif.nemaki.test.cmis.TestGroupBase;
import jp.aegif.nemaki.test.cmis.TestHelper;

public class TypesTestGroup extends TestGroupBase{
	@Test
	public void baseTypesTest() throws Exception{
		BaseTypesTest test = new BaseTypesTest();
		TestHelper.run(test);
	}
	
	@Test
	public void createAndDeleteTypeTest() throws Exception{
		CreateAndDeleteTypeTest test = new CreateAndDeleteTypeTest();
		TestHelper.run(test);
	}
	
	@Test
	public void secondaryTypesTest() throws Exception{
		SecondaryTypesTest test = new SecondaryTypesTest();
		TestHelper.run(test);
	}
}
