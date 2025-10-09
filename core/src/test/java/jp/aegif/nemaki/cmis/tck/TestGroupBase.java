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

	protected static File parametersFile;
	protected static Properties filters;
	private static boolean parametersLoaded = false;
	private static Map<String, String> loadedParameters;

	// CRITICAL FIX: Load parameters once in static initializer to avoid hang
	// This resolves the timeout issue where loadParameters hangs on 2nd+ calls
	static {
		System.out.println("[TCK] Static initialization starting");
		try {
			java.net.URL paramUrl = TestGroupBase.class.getClassLoader().getResource(PARAMETERS_FILE_NAME);
			if (paramUrl == null) {
				System.err.println("[TCK] Could not find resource: " + PARAMETERS_FILE_NAME);
				parametersFile = new File("core/src/test/resources/" + PARAMETERS_FILE_NAME);
				if (parametersFile.exists()) {
					System.out.println("[TCK] Found parameters file at: " + parametersFile.getAbsolutePath());
				} else {
					System.err.println("[TCK] Parameters file does not exist at: " + parametersFile.getAbsolutePath());
				}
			} else {
				parametersFile = new File(paramUrl.getFile());
				System.out.println("[TCK] Parameters file loaded from classpath: " + parametersFile);
			}

			java.net.URL filterUrl = TestGroupBase.class.getClassLoader().getResource(FILTERS_FILE_NAME);
			if (filterUrl == null) {
				System.err.println("[TCK] Could not find resource: " + FILTERS_FILE_NAME);
				filters = new Properties();
			} else {
				filters = PropertyUtil.build(new File(filterUrl.getFile()));
				System.out.println("[TCK] Filters loaded with " + filters.size() + " properties");
			}

			// CRITICAL FIX: Load parameters once in static initializer
			// This avoids the hang issue with multiple loadParameters calls
			if (parametersFile != null && parametersFile.exists()) {
				System.out.println("[TCK] Preloading parameters in static initializer");
				try {
					JUnitRunner tempRunner = new JUnitRunner();
					tempRunner.loadParameters(parametersFile);
					loadedParameters = tempRunner.getParameters();
					parametersLoaded = true;
					System.out.println("[TCK] Parameters preloaded successfully (count: " + (loadedParameters != null ? loadedParameters.size() : 0) + ")");
				} catch (Exception loadEx) {
					System.err.println("[TCK] Failed to preload parameters: " + loadEx);
					// Continue without parameters - tests may fail but won't hang
				}
			}
		} catch (Exception e) {
			System.err.println("[TCK] Error in static initialization: " + e);
			e.printStackTrace();
			// Initialize with empty properties to prevent NPE
			if (filters == null) {
				filters = new Properties();
			}
			if (parametersFile == null) {
				parametersFile = new File("core/src/test/resources/" + PARAMETERS_FILE_NAME);
			}
		}
		System.out.println("[TCK] Static initialization completed");
	}

	static Map<String, AbstractCmisTestGroup> testGroupMap = new HashMap<>();

	// Accessor methods for backward compatibility
	protected static File getParametersFile() {
		return parametersFile;
	}

	private static Properties getFilters() {
		return filters;
	}

	@Rule
	public TestName testName = new TestName();

	@Before
	public void beforeMethod() throws Exception {
		System.out.println("[TestGroupBase] @Before method called");
		System.out.println("[TestGroupBase] Test class: " + this.getClass().getSimpleName());
		System.out.println("[TestGroupBase] Test method: " + testName.getMethodName());

		// CRITICAL FIX: Temporarily skip filter checks to isolate timeout problem
		if (false) {
			filterClass(this.getClass().getSimpleName());
			filterMethod(testName.getMethodName());
		}

		System.out.println("[TestGroupBase] @Before method completed");
	}

	private void filterClass(String simpleClassName) {
		Properties filterProps = getFilters();
		String filterValue = filterProps.getProperty(simpleClassName, "true"); // Default to true if not found
		System.out.println("[TCK DEBUG] Filter for class " + simpleClassName + ": " + filterValue);
		Assume.assumeTrue(Boolean.valueOf(filterValue));
	}

	private void filterMethod(String methodName) {
		Properties filterProps = getFilters();
		String filterValue = filterProps.getProperty(methodName, "true"); // Default to true if not found
		System.out.println("[TCK DEBUG] Filter for method " + methodName + ": " + filterValue);
		Assume.assumeTrue(Boolean.valueOf(filterValue));
	}

	private static class PropertyUtil {
		public static Properties build(File file) {
			Properties properties = new Properties();
			if (file == null) {
				System.err.println("[TCK ERROR] PropertyUtil.build: file is null");
				return properties;
			}
			if (!file.exists()) {
				System.err.println("[TCK ERROR] PropertyUtil.build: file does not exist: " + file.getAbsolutePath());
				return properties;
			}
			try {
				System.out.println("[TCK DEBUG] PropertyUtil.build: Loading properties from " + file.getAbsolutePath());
				properties.load(new FileInputStream(file));
				System.out.println("[TCK DEBUG] PropertyUtil.build: Loaded " + properties.size() + " properties");
			} catch (FileNotFoundException e) {
				System.err.println("[TCK ERROR] PropertyUtil.build: File not found: " + e.getMessage());
				e.printStackTrace();
			} catch (IOException e) {
				System.err.println("[TCK ERROR] PropertyUtil.build: IO error: " + e.getMessage());
				e.printStackTrace();
			}
			return properties;
		}
	}

	public void run(CmisTest test) throws Exception {
		System.out.println("[TestGroupBase] run(CmisTest) called for: " + test.getClass().getName());
		run(new SimpleCmisWrapperTestGroup(test));
		System.out.println("[TestGroupBase] Adding test to TckSuite group...");
		TckSuite.addToGroup(this.getClass(), test);
		System.out.println("[TestGroupBase] run(CmisTest) completed");
	}

	public void run(CmisTestGroup group) throws Exception {
		System.out.println("[TestGroupBase] run(CmisTestGroup) called");
		JUnitRunner runner = new JUnitRunner();
		System.out.println("[TestGroupBase] JUnitRunner created");

		// CRITICAL FIX: Use preloaded parameters instead of loading again
		// This avoids the hang issue with multiple loadParameters calls
		if (parametersLoaded && loadedParameters != null) {
			System.out.println("[TestGroupBase] Using preloaded parameters (count: " + loadedParameters.size() + ")");
			runner.setParameters(loadedParameters);
		} else {
			System.out.println("[TestGroupBase] Loading parameters from file (fallback)");
			if (parametersFile == null || !parametersFile.exists()) {
				throw new IllegalStateException("Failed to load TCK parameters file");
			}
			runner.loadParameters(parametersFile);
		}
		System.out.println("[TestGroupBase] Parameters ready");

		System.out.println("[TestGroupBase] Adding group...");
		runner.addGroup(group);
		System.out.println("[TestGroupBase] Group added");

		System.out.println("[TestGroupBase] Running tests...");
		runner.run(new JUnitProgressMonitor());
		System.out.println("[TestGroupBase] Tests completed");

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
		public JUnitRunner() {
			System.out.println("[JUnitRunner] Constructor called");
		}

		@Override
		public void loadParameters(File file) throws IOException {
			System.out.println("[JUnitRunner] loadParameters(File) called: " + file);
			super.loadParameters(file);
			System.out.println("[JUnitRunner] loadParameters(File) completed");
		}

		@Override
		public void addGroup(CmisTestGroup group) throws Exception {
			System.out.println("[JUnitRunner] addGroup() called: " + group);
			super.addGroup(group);
			System.out.println("[JUnitRunner] addGroup() completed");
		}

		@Override
		public void run(CmisTestProgressMonitor monitor) throws Exception {
			System.out.println("[JUnitRunner] run() called");
			super.run(monitor);
			System.out.println("[JUnitRunner] run() completed");
		}
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
			System.out.println("[SimpleCmisWrapperTestGroup] Constructor called for test: " + test);
			if (test == null) {
				throw new IllegalArgumentException("Test is null!");
			}

			this.test = test;
			System.out.println("[SimpleCmisWrapperTestGroup] Test set: " + test.getClass().getName());
		}

		@Override
		public void init(Map<String, String> parameters) throws Exception {
			System.out.println("[SimpleCmisWrapperTestGroup] init() called");
			super.init(parameters);
			System.out.println("[SimpleCmisWrapperTestGroup] super.init() completed");
			addTest(test);
			System.out.println("[SimpleCmisWrapperTestGroup] addTest() completed");
			setName(test.getName());
			System.out.println("[SimpleCmisWrapperTestGroup] setName() completed: " + test.getName());
		}

	}
}
