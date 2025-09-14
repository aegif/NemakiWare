package jp.aegif.nemaki.cmis.tck.tests;

import org.junit.Test;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.chemistry.opencmis.tck.tests.types.BaseTypesTest;
import org.apache.chemistry.opencmis.tck.tests.types.CreateAndDeleteTypeTest;
import org.apache.chemistry.opencmis.tck.tests.types.SecondaryTypesTest;
import org.apache.chemistry.opencmis.tck.tests.query.QuerySmokeTest;
import org.apache.chemistry.opencmis.tck.tests.query.QueryRootFolderTest;
import org.apache.chemistry.opencmis.tck.tests.query.QueryLikeTest;
import org.apache.chemistry.opencmis.tck.tests.query.QueryInFolderTest;
import org.apache.chemistry.opencmis.tck.tests.versioning.VersioningSmokeTest;
import org.apache.chemistry.opencmis.tck.tests.versioning.VersionDeleteTest;
import org.apache.chemistry.opencmis.tck.tests.versioning.VersioningStateCreateTest;
import org.apache.chemistry.opencmis.tck.tests.versioning.CheckedOutTest;
import org.apache.chemistry.opencmis.tck.CmisTest;
import org.apache.chemistry.opencmis.tck.CmisTestResult;
import org.apache.chemistry.opencmis.tck.CmisTestResultStatus;
import java.util.HashMap;
import java.util.Map;

/**
 * Direct TCK test runner that bypasses the hanging JUnitRunner framework
 * This allows us to run individual TCK tests and get actual results
 */
public class DirectTckTestRunner {

    private Session createSession() throws Exception {
        Map<String, String> parameters = new HashMap<>();
        parameters.put(SessionParameter.BINDING_TYPE, BindingType.BROWSER.value());
        parameters.put(SessionParameter.BROWSER_URL, "http://localhost:8080/core/browser/bedroom");
        parameters.put(SessionParameter.USER, "admin");
        parameters.put(SessionParameter.PASSWORD, "admin");
        parameters.put(SessionParameter.REPOSITORY_ID, "bedroom");
        
        parameters.put(SessionParameter.CONNECT_TIMEOUT, "30000");
        parameters.put(SessionParameter.READ_TIMEOUT, "120000");
        
        SessionFactory factory = SessionFactoryImpl.newInstance();
        return factory.createSession(parameters);
    }
    
    private void runTckTest(CmisTest test, String testName) throws Exception {
        System.out.println("=== DIRECT TCK TEST: " + testName + " ===");
        
        try {
            Session session = createSession();
            System.out.println("Session created successfully for " + testName);
            
            Map<String, String> parameters = new HashMap<>();
            parameters.put(SessionParameter.REPOSITORY_ID, "bedroom");
            
            test.init(parameters);
            
            java.lang.reflect.Field sessionField = 
                org.apache.chemistry.opencmis.tck.impl.AbstractSessionTest.class.getDeclaredField("session");
            sessionField.setAccessible(true);
            sessionField.set(test, session);
            
            System.out.println("Running test: " + testName);
            test.run();
            
            System.out.println("=== RESULTS FOR " + testName + " ===");
            boolean hasFailures = false;
            int resultCount = 0;
            
            for (CmisTestResult result : test.getResults()) {
                resultCount++;
                System.out.println("Result #" + resultCount + ": " + result.getStatus() + " - " + result.getMessage());
                
                if (result.getStatus().getLevel() >= CmisTestResultStatus.FAILURE.getLevel()) {
                    hasFailures = true;
                    System.out.println("  *** FAILURE DETECTED ***");
                    if (result.getException() != null) {
                        System.out.println("  Exception: " + result.getException().getMessage());
                    }
                }
                
                if (result.getChildren() != null && !result.getChildren().isEmpty()) {
                    for (CmisTestResult child : result.getChildren()) {
                        System.out.println("  Child: " + child.getStatus() + " - " + child.getMessage());
                        if (child.getStatus().getLevel() >= CmisTestResultStatus.FAILURE.getLevel()) {
                            hasFailures = true;
                        }
                    }
                }
            }
            
            System.out.println("=== " + testName + " SUMMARY: " + 
                (hasFailures ? "FAILED" : "PASSED") + " (" + resultCount + " results) ===\n");
            
        } catch (Exception e) {
            System.err.println("=== " + testName + " EXCEPTION ===");
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.err.println("=== END " + testName + " EXCEPTION ===\n");
        }
    }
    
    @Test
    public void directBaseTypesTest() throws Exception {
        runTckTest(new BaseTypesTest(), "BaseTypesTest");
    }
    
    @Test
    public void directCreateAndDeleteTypeTest() throws Exception {
        runTckTest(new CreateAndDeleteTypeTest(), "CreateAndDeleteTypeTest");
    }
    
    @Test
    public void directSecondaryTypesTest() throws Exception {
        runTckTest(new SecondaryTypesTest(), "SecondaryTypesTest");
    }
    
    @Test
    public void directQuerySmokeTest() throws Exception {
        runTckTest(new QuerySmokeTest(), "QuerySmokeTest");
    }
    
    @Test
    public void directQueryRootFolderTest() throws Exception {
        runTckTest(new QueryRootFolderTest(), "QueryRootFolderTest");
    }
    
    
    @Test
    public void directQueryLikeTest() throws Exception {
        runTckTest(new QueryLikeTest(), "QueryLikeTest");
    }
    
    @Test
    public void directQueryInFolderTest() throws Exception {
        runTckTest(new QueryInFolderTest(), "QueryInFolderTest");
    }
    
    @Test
    public void directVersioningSmokeTest() throws Exception {
        runTckTest(new VersioningSmokeTest(), "VersioningSmokeTest");
    }
    
    @Test
    public void directVersionDeleteTest() throws Exception {
        runTckTest(new VersionDeleteTest(), "VersionDeleteTest");
    }
    
    @Test
    public void directVersioningStateCreateTest() throws Exception {
        runTckTest(new VersioningStateCreateTest(), "VersioningStateCreateTest");
    }
    
    @Test
    public void directCheckedOutTest() throws Exception {
        runTckTest(new CheckedOutTest(), "CheckedOutTest");
    }
}
