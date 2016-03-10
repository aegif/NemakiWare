package jp.aegif.nemaki.test.cmis;

import java.io.File;
import java.io.PrintWriter;
import java.util.Map;

import org.apache.chemistry.opencmis.tck.CmisTest;
import org.apache.chemistry.opencmis.tck.CmisTestGroup;
import org.apache.chemistry.opencmis.tck.CmisTestProgressMonitor;
import org.apache.chemistry.opencmis.tck.CmisTestReport;
import org.apache.chemistry.opencmis.tck.CmisTestResult;
import org.apache.chemistry.opencmis.tck.CmisTestResultStatus;
import org.apache.chemistry.opencmis.tck.impl.AbstractCmisTestGroup;
import org.apache.chemistry.opencmis.tck.report.TextReport;
import org.apache.chemistry.opencmis.tck.runner.AbstractRunner;
import org.junit.Assert;

import jp.aegif.nemaki.test.cmis.tests.BasicTestGroup;

public class TestHelper {
	private static final String PARAMETERS_FILE_NAME = "cmis-tck-parameters.properties";
	
    private TestHelper() {
    }

    public static void run(CmisTest test) throws Exception {
        run(new SimpleCmisWrapperTestGroup(test));
    }

    public static void run(CmisTestGroup group) throws Exception {
        JUnitRunner runner = new JUnitRunner();

        File parametersFile = new File(BasicTestGroup.class.getClassLoader().getResource(PARAMETERS_FILE_NAME).getFile());
        runner.loadParameters(parametersFile);
        runner.addGroup(group);
        runner.run(new JUnitProgressMonitor());

        //CmisTestReport report = new TextReport();
        //report.createReport(runner.getParameters(), runner.getGroups(), new PrintWriter(System.out));

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
            System.out.println(group.getName() + " (" + group.getTests().size() + " tests)");
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
     * @author linzhixing
     *
     */
    private static class SimpleCmisWrapperTestGroup extends AbstractCmisTestGroup{
    	
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
