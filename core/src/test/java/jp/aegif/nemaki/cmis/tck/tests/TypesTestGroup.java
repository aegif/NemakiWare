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
		System.err.println("=== TYPES TEST GROUP: Starting standard OpenCMIS createAndDeleteTypeTest ===");
		
		CreateAndDeleteTypeTest test = new CreateAndDeleteTypeTest();
		run(test);  // Use the standard TckSuite.run() method
		
		System.err.println("=== TYPES TEST GROUP: Standard createAndDeleteTypeTest completed ===");
	}
	
	@Test
	public void secondaryTypesTest() throws Exception{
		SecondaryTypesTest test = new SecondaryTypesTest();
		run(test);
	}
	
	/**
	 * JUnit用の進捗モニター
	 */
	private static class JUnitProgressMonitor implements org.apache.chemistry.opencmis.tck.CmisTestProgressMonitor {
		@Override
		public void startGroup(org.apache.chemistry.opencmis.tck.CmisTestGroup group) {
			System.out.println("Starting group: " + group.getName());
		}
		
		@Override
		public void endGroup(org.apache.chemistry.opencmis.tck.CmisTestGroup group) {
			System.out.println("Completed group: " + group.getName());
		}
		
		@Override
		public void startTest(org.apache.chemistry.opencmis.tck.CmisTest test) {
			System.out.println("  Starting test: " + test.getName());
		}
		
		@Override
		public void endTest(org.apache.chemistry.opencmis.tck.CmisTest test) {
			System.out.println("  Completed test: " + test.getName());
		}
		
		@Override
		public void message(String msg) {
			System.out.println("  " + msg);
		}
	}
	
}
