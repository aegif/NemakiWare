package jp.aegif.nemaki.test.cmis.tests;

import org.apache.chemistry.opencmis.tck.tests.types.BaseTypesTest;
import org.apache.chemistry.opencmis.tck.tests.types.CreateAndDeleteTypeTest;
import org.apache.chemistry.opencmis.tck.tests.types.SecondaryTypesTest;
import org.junit.Test;

import jp.aegif.nemaki.test.cmis.TckSuite;

public class TypesTestGroup extends TckSuite{
	@Test
	public void baseTypesTest() throws Exception{
		BaseTypesTest test = new BaseTypesTest();
		run(test);
	}
	
	@Test
	public void createAndDeleteTypeTest() throws Exception{
		CreateAndDeleteTypeTest test = new CreateAndDeleteTypeTest();
		run(test);
	}
	
	@Test
	public void secondaryTypesTest() throws Exception{
		SecondaryTypesTest test = new SecondaryTypesTest();
		run(test);
	}
}
