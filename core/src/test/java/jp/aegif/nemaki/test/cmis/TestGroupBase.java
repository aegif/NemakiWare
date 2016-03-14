package jp.aegif.nemaki.test.cmis;

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

		checkForFailures(runner);
	}

	private static void checkForFailures(JUnitRunner runner) {
		for (CmisTestGroup group : runner.getGroups()) {
			for (CmisTest test : group.getTests()) {
				for (CmisTestResult result : test.getResults()) {
					if (result.getStatus().getLevel() >= CmisTestResultStatus.FAILURE.getLevel()) {
						Assert.fail(result.getMessage() + "\n" + result.getStackTrace().toString());
					}
				}
			}
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
