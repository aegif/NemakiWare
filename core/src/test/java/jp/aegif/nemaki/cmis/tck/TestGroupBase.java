package jp.aegif.nemaki.cmis.tck;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.chemistry.opencmis.tck.CmisTest;
import org.apache.chemistry.opencmis.tck.CmisTestGroup;
import org.apache.chemistry.opencmis.tck.CmisTestProgressMonitor;
import org.apache.chemistry.opencmis.tck.CmisTestResult;
import org.apache.chemistry.opencmis.tck.CmisTestResultStatus;
import org.apache.chemistry.opencmis.tck.impl.AbstractCmisTestGroup;
import org.apache.chemistry.opencmis.tck.runner.AbstractRunner;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestName;

public class TestGroupBase extends AbstractRunner {

	static final String PARAMETERS_FILE_NAME = "cmis-tck-parameters.properties";
	static final String FILTERS_FILE_NAME = "cmis-tck-filters.properties";
	protected static File parametersFile = new File(
			TestGroupBase.class.getClassLoader().getResource(PARAMETERS_FILE_NAME).getFile());
	protected static Properties filters = PropertyUtil
			.build(new File(TestGroupBase.class.getClassLoader().getResource("cmis-tck-filters.properties").getFile()));

	static Map<String, AbstractCmisTestGroup> testGroupMap = new HashMap<>();

	@Rule
	public TestName testName = new TestName();

	@Before
	public void beforeMethod() throws Exception {
		filterClass(this.getClass().getSimpleName());
		filterMethod(testName.getMethodName());
	}

	private void filterClass(String simpleClassName) {
		Assume.assumeTrue(Boolean.valueOf(filters.getProperty(simpleClassName)));
	}

	private void filterMethod(String methodName) {
		Assume.assumeTrue(Boolean.valueOf(filters.getProperty(methodName)));
	}

	private static class PropertyUtil {
		public static Properties build(File file) {
			Properties properties = new Properties();
			try {
				properties.load(new FileInputStream(file));
			} catch (FileNotFoundException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch blocknet
				e.printStackTrace();
			}
			return properties;
		}
	}

	public void run(CmisTest test) throws Exception {
		run(new SimpleCmisWrapperTestGroup(test));
		TckSuite.addToGroup(this.getClass(), test);
	}
	
	public void run(CmisTestGroup group) throws Exception {
		JUnitRunner runner = new JUnitRunner();

		runner.loadParameters(parametersFile);
		runner.addGroup(group);
		runner.run(new JUnitProgressMonitor());

		// CRITICAL FIX: Clean up TCK test artifacts after each test group
		cleanupTckTestArtifacts(runner);

		checkForFailures(runner);
	}

	private static void checkForFailures(JUnitRunner runner) {
		for (CmisTestGroup group : runner.getGroups()) {
			for (CmisTest test : group.getTests()) {
				// CRITICAL DEBUG: Print ALL results, not just failures
				System.err.println("=== TCK TEST RESULT ANALYSIS: " + test.getName() + " ===");
				System.err.println("Total results count: " + test.getResults().size());
				
				for (int i = 0; i < test.getResults().size(); i++) {
					CmisTestResult result = test.getResults().get(i);
					System.err.println("\n--- Result #" + i + " ---");
					System.err.println("  Status: " + result.getStatus() + " (level=" + result.getStatus().getLevel() + ")");
					System.err.println("  Message: '" + result.getMessage() + "'");
					System.err.println("  URL: " + result.getUrl());
					System.err.println("  Request: " + result.getRequest());
					System.err.println("  Response: " + result.getResponse());
					System.err.println("  Children count: " + (result.getChildren() != null ? result.getChildren().size() : "null"));
					
					// Print child results if any
					if (result.getChildren() != null && !result.getChildren().isEmpty()) {
						for (int j = 0; j < result.getChildren().size(); j++) {
							CmisTestResult child = result.getChildren().get(j);
							System.err.println("    Child #" + j + ": " + child.getStatus() + " - " + child.getMessage());
						}
					}
					
					if (result.getException() != null) {
						System.err.println("  Exception: " + result.getException().getMessage());
						result.getException().printStackTrace(System.err);
					}
					
					// Check if this is a failure result
					if (result.getStatus().getLevel() >= CmisTestResultStatus.FAILURE.getLevel()) {
						System.err.println("*** FAILURE DETECTED ***");
						Assert.fail("TCK FAILURE - " + result.getMessage());
					}
				}
				System.err.println("=== END TCK TEST RESULT ANALYSIS ===\n");
			}
		}
	}
	
	/**
	 * Clean up TCK test artifacts to prevent test contamination
	 * @param runner the test runner that executed the tests
	 */
	private static void cleanupTckTestArtifacts(JUnitRunner runner) {
		try {
			System.out.println("=== TCK CLEANUP: Starting test artifact cleanup ===");
			
			// TEMPORARILY DISABLED: JUnitRunner.getSession() method not available
			// TODO: Implement proper session access for cleanup functionality
			System.out.println("TCK CLEANUP: DISABLED - Session access needs refactoring");
			
			/*
			if (runner.getSession() != null) {
				String repositoryId = runner.getSession().getRepositoryInfo().getId();
				System.out.println("TCK CLEANUP: Repository ID = " + repositoryId);
				
				// Get root folder
				org.apache.chemistry.opencmis.client.api.Folder rootFolder = runner.getSession().getRootFolder();
				System.out.println("TCK CLEANUP: Root folder ID = " + rootFolder.getId());
				
				// Find and delete all cmistck objects
				org.apache.chemistry.opencmis.client.api.ItemIterable<org.apache.chemistry.opencmis.client.api.CmisObject> children = 
					rootFolder.getChildren();
				
				int deletedCount = 0;
				for (org.apache.chemistry.opencmis.client.api.CmisObject child : children) {
					String name = child.getName();
					if (name != null && name.startsWith("cmistck")) {
						try {
							System.out.println("TCK CLEANUP: Deleting test artifact: " + name + " (ID: " + child.getId() + ")");
							
							// Delete with all versions if it's a document
							if (child instanceof org.apache.chemistry.opencmis.client.api.Document) {
								((org.apache.chemistry.opencmis.client.api.Document) child).deleteAllVersions();
							} else {
								child.delete(true); // Delete with deleteAllVersions flag
							}
							deletedCount++;
							
							// Brief pause to allow deletion to complete
							Thread.sleep(10);
							
						} catch (Exception deleteEx) {
							System.err.println("TCK CLEANUP: Failed to delete " + name + ": " + deleteEx.getMessage());
							// Continue with other deletions
						}
					}
				}
				
				System.out.println("=== TCK CLEANUP: Deleted " + deletedCount + " test artifacts ===");
				
				if (deletedCount > 0) {
					// Brief wait for deletions to propagate
					Thread.sleep(500);
				}
				
			} else {
				System.err.println("TCK CLEANUP: No session available for cleanup");
			}
			*/
			
		} catch (Exception cleanupEx) {
			System.err.println("TCK CLEANUP: Cleanup failed: " + cleanupEx.getMessage());
			cleanupEx.printStackTrace();
			// Don't fail the test due to cleanup errors
		}
	}

	private static class JUnitRunner extends AbstractRunner {
	}

	private static class JUnitProgressMonitor implements CmisTestProgressMonitor {

		@SuppressWarnings("PMD.SystemPrintln")
		public void startGroup(CmisTestGroup group) {
			// System.out.println(group.getName() + " (" +
			// group.getTests().size() + " tests)");
		}

		public void endGroup(CmisTestGroup group) {
		}

		@SuppressWarnings("PMD.SystemPrintln")
		public void startTest(CmisTest test) {
			System.out.println("  " + test.getName());
		}

		public void endTest(CmisTest test) {
		}

		@SuppressWarnings("PMD.SystemPrintln")
		public void message(String msg) {
			System.out.println(msg);
		}
	}

	/**
	 * Minor version of CmisWrapperTestGroup
	 * 
	 * @author linzhixing
	 *
	 */
	private static class SimpleCmisWrapperTestGroup extends AbstractCmisTestGroup {

		private final CmisTest test;

		public SimpleCmisWrapperTestGroup(CmisTest test) {
			if (test == null) {
				throw new IllegalArgumentException("Test is null!");
			}

			this.test = test;
		}

		@Override
		public void init(Map<String, String> parameters) throws Exception {
			super.init(parameters);
			addTest(test);
			setName(test.getName());
		}

	}
}
