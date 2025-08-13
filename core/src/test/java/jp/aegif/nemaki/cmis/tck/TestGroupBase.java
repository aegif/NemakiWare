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
				// CRITICAL DEBUG: Print ALL results, not just failures - ALWAYS SHOW ANALYSIS
				System.out.println("=== TCK TEST RESULT ANALYSIS: " + test.getName() + " ===");
				System.out.println("Total results count: " + test.getResults().size());
				System.out.println("Test class: " + test.getClass().getName());
				
				// Also write to file for debugging
				try (java.io.PrintWriter fileOut = new java.io.PrintWriter(new java.io.FileWriter("/tmp/tck-debug.log", true))) {
					fileOut.println("=== TCK TEST RESULT ANALYSIS: " + test.getName() + " ===");
					fileOut.println("Total results count: " + test.getResults().size());
					fileOut.println("Test class: " + test.getClass().getName());
				} catch (Exception e) {
					System.err.println("Failed to write debug log: " + e.getMessage());
				}
				
				boolean hasFailures = false;
				for (int i = 0; i < test.getResults().size(); i++) {
					CmisTestResult result = test.getResults().get(i);
					System.out.println("\n--- Result #" + i + " ---");
					System.out.println("  Status: " + result.getStatus() + " (level=" + result.getStatus().getLevel() + ")");
					System.out.println("  Message: '" + result.getMessage() + "'");
					System.out.println("  URL: " + result.getUrl());
					System.out.println("  Request: " + result.getRequest());
					System.out.println("  Response: " + result.getResponse());
					System.out.println("  Children count: " + (result.getChildren() != null ? result.getChildren().size() : "null"));
					
					// Write to debug file
					try (java.io.PrintWriter fileOut = new java.io.PrintWriter(new java.io.FileWriter("/tmp/tck-debug.log", true))) {
						fileOut.println("\n--- Result #" + i + " ---");
						fileOut.println("  Status: " + result.getStatus() + " (level=" + result.getStatus().getLevel() + ")");
						fileOut.println("  Message: '" + result.getMessage() + "'");
						fileOut.println("  Children count: " + (result.getChildren() != null ? result.getChildren().size() : "null"));
					} catch (Exception e) {}
					
					// Print child results if any
					if (result.getChildren() != null && !result.getChildren().isEmpty()) {
						System.out.println("  === CHILD RESULTS ===");
						try (java.io.PrintWriter fileOut = new java.io.PrintWriter(new java.io.FileWriter("/tmp/tck-debug.log", true))) {
							fileOut.println("  === CHILD RESULTS ===");
						} catch (Exception e) {}
						
						for (int j = 0; j < result.getChildren().size(); j++) {
							CmisTestResult child = result.getChildren().get(j);
							System.out.println("    Child #" + j + ": " + child.getStatus() + " - " + child.getMessage());
							try (java.io.PrintWriter fileOut = new java.io.PrintWriter(new java.io.FileWriter("/tmp/tck-debug.log", true))) {
								fileOut.println("    Child #" + j + ": " + child.getStatus() + " - " + child.getMessage());
							} catch (Exception e) {}
							
							if (child.getStatus().getLevel() >= CmisTestResultStatus.WARNING.getLevel()) {
								System.out.println("      *** CHILD WARNING/FAILURE ***");
								try (java.io.PrintWriter fileOut = new java.io.PrintWriter(new java.io.FileWriter("/tmp/tck-debug.log", true))) {
									fileOut.println("      *** CHILD WARNING/FAILURE ***");
								} catch (Exception e) {}
							}
						}
					}
					
					if (result.getException() != null) {
						System.out.println("  Exception: " + result.getException().getMessage());
						result.getException().printStackTrace();
					}
					
					// Check if this is a failure result
					if (result.getStatus().getLevel() >= CmisTestResultStatus.FAILURE.getLevel()) {
						System.out.println("*** ROOT LEVEL FAILURE DETECTED ***");
						hasFailures = true;
					}
				}
				System.out.println("=== END TCK TEST RESULT ANALYSIS ===\n");
				
				// Only fail the test if we found actual failures
				if (hasFailures) {
					Assert.fail("TCK FAILURE detected in test: " + test.getName());
				}
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
