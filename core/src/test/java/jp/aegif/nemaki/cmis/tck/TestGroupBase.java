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
		System.err.println("[TCK STATIC INIT] Starting static initialization for TestGroupBase");
		System.err.println("[TCK STATIC INIT] Thread: " + Thread.currentThread().getName());
		try {
			System.err.println("[TCK STATIC INIT] Loading parameter file resource");
			java.net.URL paramUrl = TestGroupBase.class.getClassLoader().getResource(PARAMETERS_FILE_NAME);
			if (paramUrl == null) {
				System.err.println("[TCK] Could not find resource: " + PARAMETERS_FILE_NAME);
				parametersFile = new File("core/src/test/resources/" + PARAMETERS_FILE_NAME);
				if (parametersFile.exists()) {
				} else {
					System.err.println("[TCK] Parameters file does not exist at: " + parametersFile.getAbsolutePath());
				}
			} else {
				parametersFile = new File(paramUrl.getFile());
			}

			java.net.URL filterUrl = TestGroupBase.class.getClassLoader().getResource(FILTERS_FILE_NAME);
			if (filterUrl == null) {
				System.err.println("[TCK] Could not find resource: " + FILTERS_FILE_NAME);
				filters = new Properties();
			} else {
				filters = PropertyUtil.build(new File(filterUrl.getFile()));
			}

			// CRITICAL FIX: Load parameters once in static initializer
			// This avoids the hang issue with multiple loadParameters calls
			if (parametersFile != null && parametersFile.exists()) {
				try {
					System.err.println("[TCK STATIC INIT] Creating JUnitRunner for parameter preloading");
					JUnitRunner tempRunner = new JUnitRunner();
					System.err.println("[TCK STATIC INIT] Calling loadParameters() - THIS MAY HANG");
					tempRunner.loadParameters(parametersFile);
					System.err.println("[TCK STATIC INIT] loadParameters() completed successfully");
					loadedParameters = tempRunner.getParameters();
					parametersLoaded = true;
					System.err.println("[TCK STATIC INIT] Parameters preloaded successfully, count: " + (loadedParameters != null ? loadedParameters.size() : 0));
				} catch (Exception loadEx) {
					System.err.println("[TCK] Failed to preload parameters: " + loadEx);
					loadEx.printStackTrace();
					// Continue without parameters - tests may fail but won't hang
				}
			}
			System.err.println("[TCK STATIC INIT] Static initialization completed");
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

		// CRITICAL FIX: Temporarily skip filter checks to isolate timeout problem
		if (false) {
			filterClass(this.getClass().getSimpleName());
			filterMethod(testName.getMethodName());
		}

	}

	private void filterClass(String simpleClassName) {
		Properties filterProps = getFilters();
		String filterValue = filterProps.getProperty(simpleClassName, "true"); // Default to true if not found
		Assume.assumeTrue(Boolean.valueOf(filterValue));
	}

	private void filterMethod(String methodName) {
		Properties filterProps = getFilters();
		String filterValue = filterProps.getProperty(methodName, "true"); // Default to true if not found
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
				properties.load(new FileInputStream(file));
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
		run(new SimpleCmisWrapperTestGroup(test));
		TckSuite.addToGroup(this.getClass(), test);
	}

	public void run(CmisTestGroup group) throws Exception {
		JUnitRunner runner = new JUnitRunner();

		// CRITICAL FIX: Use preloaded parameters instead of loading again
		// This avoids the hang issue with multiple loadParameters calls
		if (parametersLoaded && loadedParameters != null) {
			runner.setParameters(loadedParameters);
		} else {
			if (parametersFile == null || !parametersFile.exists()) {
				throw new IllegalStateException("Failed to load TCK parameters file");
			}
			runner.loadParameters(parametersFile);
		}

		runner.addGroup(group);

		runner.run(new JUnitProgressMonitor());

		// CRITICAL FIX: Clean up TCK test artifacts after each test group
		// TEMPORARILY DISABLED: Testing if cleanup is causing timeout
		// cleanupTckTestArtifacts(runner);

		checkForFailures(runner);
	}

	private static void checkForFailures(JUnitRunner runner) {
		for (CmisTestGroup group : runner.getGroups()) {
			for (CmisTest test : group.getTests()) {
				boolean hasFailures = false;
				StringBuilder failureDetails = new StringBuilder();
				int resultIndex = 0;

				for (CmisTestResult result : test.getResults()) {
					if (result.getStatus() == CmisTestResultStatus.FAILURE ||
						result.getStatus() == CmisTestResultStatus.UNEXPECTED_EXCEPTION) {
						hasFailures = true;

						// Collect detailed failure information
						failureDetails.append("\n  Result #").append(resultIndex)
							.append(": ").append(result.getStatus())
							.append(" - ").append(result.getMessage());

						if (result.getException() != null) {
							failureDetails.append("\n    Exception: ")
								.append(result.getException().getClass().getSimpleName())
								.append(": ").append(result.getException().getMessage());
						}

						StackTraceElement[] stackTrace = result.getStackTrace();
						if (stackTrace != null && stackTrace.length > 0) {
							failureDetails.append("\n    Stack: ")
								.append(stackTrace[0].toString());
						}
					}
					resultIndex++;
				}

				if (hasFailures) {
					String errorMsg = "TCK FAILURE detected in test: " + test.getName() + failureDetails.toString();
					System.err.println(errorMsg);
					Assert.fail(errorMsg);
				}
			}
		}
	}
	
	/**
	 * Clean up TCK test artifacts to prevent test contamination
	 * @param runner the test runner that executed the tests
	 */
	private static void cleanupTckTestArtifacts(JUnitRunner runner) {
		org.apache.chemistry.opencmis.client.api.Session session = null;
		try {
			// Create a new session using the test parameters
			Map<String, String> parameters = runner.getParameters();
			if (parameters == null || parameters.isEmpty()) {
				System.err.println("TCK CLEANUP: No parameters available for cleanup");
				return;
			}

			// Create session factory and session
			org.apache.chemistry.opencmis.client.api.SessionFactory factory =
				org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl.newInstance();

			// Build session parameters from TCK parameters
			Map<String, String> sessionParams = new java.util.HashMap<>();

			// Copy all parameters that start with standard CMIS session parameter prefixes
			for (Map.Entry<String, String> entry : parameters.entrySet()) {
				String key = entry.getKey();
				if (key.startsWith("org.apache.chemistry.opencmis.") ||
				    key.equals("test.binding") ||
				    key.equals("test.username") ||
				    key.equals("test.password") ||
				    key.equals("test.repositoryid")) {
					sessionParams.put(key, entry.getValue());
				}
			}

			// Map TCK parameter names to session parameter names
			String bindingType = parameters.get("org.apache.chemistry.opencmis.binding.spi.type");
			if (bindingType != null) {
				sessionParams.put(org.apache.chemistry.opencmis.commons.SessionParameter.BINDING_TYPE, bindingType);
			}

			// AtomPub URL
			String atompubUrl = parameters.get("org.apache.chemistry.opencmis.binding.atompub.url");
			if (atompubUrl != null) {
				sessionParams.put(org.apache.chemistry.opencmis.commons.SessionParameter.ATOMPUB_URL, atompubUrl);
			}

			// Browser URL
			String browserUrl = parameters.get("org.apache.chemistry.opencmis.binding.browser.url");
			if (browserUrl != null) {
				sessionParams.put(org.apache.chemistry.opencmis.commons.SessionParameter.BROWSER_URL, browserUrl);
			}

			// WebServices URLs
			String wsRepositoryUrl = parameters.get("org.apache.chemistry.opencmis.binding.webservices.RepositoryService");
			if (wsRepositoryUrl != null) {
				sessionParams.put(org.apache.chemistry.opencmis.commons.SessionParameter.WEBSERVICES_REPOSITORY_SERVICE, wsRepositoryUrl);
			}

			String username = parameters.get("org.apache.chemistry.opencmis.user");
			if (username != null) {
				sessionParams.put(org.apache.chemistry.opencmis.commons.SessionParameter.USER, username);
			}

			String password = parameters.get("org.apache.chemistry.opencmis.password");
			if (password != null) {
				sessionParams.put(org.apache.chemistry.opencmis.commons.SessionParameter.PASSWORD, password);
			}

			String repositoryId = parameters.get("org.apache.chemistry.opencmis.session.repository.id");
			if (repositoryId != null) {
				sessionParams.put(org.apache.chemistry.opencmis.commons.SessionParameter.REPOSITORY_ID, repositoryId);
			}

			// Create session
			session = factory.createSession(sessionParams);

			if (session != null) {
				// Get root folder
				org.apache.chemistry.opencmis.client.api.Folder rootFolder = session.getRootFolder();

				// Find and delete all cmistck objects
				org.apache.chemistry.opencmis.client.api.ItemIterable<org.apache.chemistry.opencmis.client.api.CmisObject> children =
					rootFolder.getChildren();

				int deletedCount = 0;
				for (org.apache.chemistry.opencmis.client.api.CmisObject child : children) {
					String name = child.getName();
					if (name != null && name.startsWith("cmistck")) {
						try {
							// Delete with all versions if it's a document
							if (child instanceof org.apache.chemistry.opencmis.client.api.Document) {
								((org.apache.chemistry.opencmis.client.api.Document) child).deleteAllVersions();
							} else if (child instanceof org.apache.chemistry.opencmis.client.api.Folder) {
								// Delete folder recursively
								((org.apache.chemistry.opencmis.client.api.Folder) child).deleteTree(true, null, true);
							} else {
								child.delete(true);
							}
							deletedCount++;

						} catch (Exception deleteEx) {
							System.err.println("TCK CLEANUP: Failed to delete " + name + ": " + deleteEx.getMessage());
							// Continue with other deletions
						}
					}
				}

				if (deletedCount > 0) {
					System.out.println("TCK CLEANUP: Deleted " + deletedCount + " test artifacts");
					// Brief wait for deletions to propagate
					Thread.sleep(500);
				}

			} else {
				System.err.println("TCK CLEANUP: Failed to create session for cleanup");
			}

		} catch (Exception cleanupEx) {
			System.err.println("TCK CLEANUP: Cleanup failed: " + cleanupEx.getMessage());
			cleanupEx.printStackTrace();
			// Don't fail the test due to cleanup errors
		} finally {
			// Clear session if created
			if (session != null) {
				try {
					session.clear();
				} catch (Exception e) {
					// Ignore
				}
			}
		}
	}

	private static class JUnitRunner extends AbstractRunner {
		public JUnitRunner() {
		}

		@Override
		public void loadParameters(File file) throws IOException {
			super.loadParameters(file);
		}

		@Override
		public void addGroup(CmisTestGroup group) throws Exception {
			super.addGroup(group);
		}

		@Override
		public void run(CmisTestProgressMonitor monitor) throws Exception {
			super.run(monitor);
		}
	}

	private static class JUnitProgressMonitor implements CmisTestProgressMonitor {

		@SuppressWarnings("PMD.SystemPrintln")
		public void startGroup(CmisTestGroup group) {
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
