package jp.aegif.nemaki.cmis.tck;

import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.chemistry.opencmis.tck.CmisTest;
import org.apache.chemistry.opencmis.tck.CmisTestGroup;
import org.apache.chemistry.opencmis.tck.CmisTestProgressMonitor;
import org.apache.chemistry.opencmis.tck.CmisTestReport;
import org.apache.chemistry.opencmis.tck.CmisTestResult;
import org.apache.chemistry.opencmis.tck.report.TextReport;
import org.apache.chemistry.opencmis.tck.report.XmlReport;
import org.apache.chemistry.opencmis.tck.runner.AbstractRunner;

public class AclTckRunner extends AbstractRunner {
    
    private static final String PARAMETERS_FILE = "cmis-tck-parameters-docker.properties";
    
    public AclTckRunner() throws Exception {
        Properties props = new Properties();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(PARAMETERS_FILE)) {
            if (is == null) {
                throw new RuntimeException("Cannot find " + PARAMETERS_FILE + " in classpath");
            }
            props.load(is);
        }
        
        Map<String, String> parameters = new HashMap<>();
        for (String key : props.stringPropertyNames()) {
            parameters.put(key, props.getProperty(key));
        }
        
        parameters.put("org.apache.chemistry.opencmis.binding.connecttimeout", "30000");
        parameters.put("org.apache.chemistry.opencmis.binding.readtimeout", "45000");
        parameters.put("org.apache.chemistry.opencmis.binding.httpinvoker.classname", "org.apache.chemistry.opencmis.client.bindings.spi.http.DefaultHttpInvoker");
        
        setParameters(parameters);
    }
    
    public static void main(String[] args) throws Exception {
        System.out.println("Starting NemakiWare ACL TCK Test Execution...");
        
        AclTckRunner runner = new AclTckRunner();
        
        System.out.println("Loaded parameters:");
        Map<String, String> params = runner.getParameters();
        System.out.println("- Binding: " + params.get("org.apache.chemistry.opencmis.binding.spi.type"));
        System.out.println("- URL: " + params.get("org.apache.chemistry.opencmis.binding.atompub.url"));
        System.out.println("- User: " + params.get("org.apache.chemistry.opencmis.user"));
        System.out.println("- Repository: " + params.get("org.apache.chemistry.opencmis.session.repository.id"));
        
        List<CmisTestGroup> groups = new ArrayList<>();
        
        String[] groupClasses = {
            "org.apache.chemistry.opencmis.tck.tests.control.ControlTestGroup"
        };
        
        for (String className : groupClasses) {
            try {
                Class<?> clazz = Class.forName(className);
                CmisTestGroup group = (CmisTestGroup) clazz.newInstance();
                group.init(runner.getParameters());
                groups.add(group);
                runner.addGroup(group);
                System.out.println("Added test group: " + className);
            } catch (Exception e) {
                System.out.println("Warning: Could not load test group " + className + ": " + e.getMessage());
            }
        }
        
        System.out.println("Running ACL TCK tests with " + groups.size() + " test group...");
        
        CmisTestProgressMonitor monitor = new CmisTestProgressMonitor() {
            @Override
            public void startGroup(CmisTestGroup group) {
                System.out.println("\nStarting test group: " + group.getName());
            }
            
            @Override
            public void endGroup(CmisTestGroup group) {
                System.out.println("Completed test group: " + group.getName());
            }
            
            @Override
            public void startTest(CmisTest test) {
                System.out.print("  Running: " + test.getName() + " ... ");
            }
            
            @Override
            public void endTest(CmisTest test) {
                System.out.println("done");
            }
            
            @Override
            public void message(String msg) {
                System.out.println("    " + msg);
            }
        };
        
        runner.run(monitor);
        
        File reportsDir = new File(System.getProperty("user.home") + "/tck-reports");
        reportsDir.mkdirs();
        
        System.out.println("\nGenerating ACL test reports...");
        CmisTestReport textReport = new TextReport();
        try (PrintWriter textWriter = new PrintWriter(new FileWriter(new File(reportsDir, "tck-acl-report.txt")))) {
            textReport.createReport(runner.getParameters(), groups, textWriter);
        }
        
        System.out.println("Generating XML report...");
        CmisTestReport xmlReport = new XmlReport();
        try (PrintWriter xmlWriter = new PrintWriter(new FileWriter(new File(reportsDir, "tck-acl-report.xml")))) {
            xmlReport.createReport(runner.getParameters(), groups, xmlWriter);
        }
        
        System.out.println("\n=========== ACL TCK Test Summary ===========");
        int totalTests = 0;
        int totalFailures = 0;
        int totalWarnings = 0;
        
        for (CmisTestGroup group : groups) {
            for (CmisTest test : group.getTests()) {
                for (CmisTestResult result : test.getResults()) {
                    totalTests++;
                    switch (result.getStatus()) {
                        case FAILURE:
                        case UNEXPECTED_EXCEPTION:
                            totalFailures++;
                            break;
                        case WARNING:
                            totalWarnings++;
                            break;
                    }
                }
            }
        }
        
        System.out.println("Total tests: " + totalTests);
        System.out.println("Passed: " + (totalTests - totalFailures - totalWarnings));
        System.out.println("Warnings: " + totalWarnings);
        System.out.println("Failures: " + totalFailures);
        System.out.println("===============================================");
        
        System.out.println("\nACL TCK test execution completed!");
        System.out.println("Reports generated in: " + reportsDir.getAbsolutePath());
        
        System.exit(totalFailures > 0 ? 1 : 0);
    }
}
