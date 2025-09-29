package jp.aegif.nemaki.cmis.tck.tests;

import org.junit.Test;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
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
import org.apache.chemistry.opencmis.tck.impl.AbstractCmisTestGroup;
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
        Session session = factory.createSession(parameters);

        // DEBUG: Check inherited flags on base types
        System.out.println("=== INHERITED FLAG DEBUG ===");
        checkInheritedFlags(session);

        return session;
    }

    private void checkInheritedFlags(Session session) {
        try {
            TypeDefinition docType = session.getTypeDefinition("cmis:document");
            System.out.println("cmis:document properties:");

            String[] props = {"cmis:objectId", "cmis:baseTypeId", "cmis:name"};
            for (String propId : props) {
                PropertyDefinition<?> prop = docType.getPropertyDefinitions().get(propId);
                if (prop != null) {
                    System.out.println("  " + propId + ":");
                    System.out.println("    - id: " + prop.getId());
                    System.out.println("    - queryName: " + prop.getQueryName());
                    System.out.println("    - localName: " + prop.getLocalName());
                    System.out.println("    - displayName: " + prop.getDisplayName());
                    System.out.println("    - inherited: " + prop.isInherited());
                    System.out.println("    - required: " + prop.isRequired());
                    System.out.println("    - queryable: " + prop.isQueryable());
                    System.out.println("    - updatability: " + prop.getUpdatability());
                } else {
                    System.out.println("  " + propId + " - NOT FOUND!");
                }
            }
        } catch (Exception e) {
            System.out.println("Error checking inherited flags: " + e.getMessage());
        }
    }
    
    private void runTckTest(CmisTest test, String testName) throws Exception {
        System.out.println("=== DIRECT TCK TEST: " + testName + " ===");

        try {
            Session session = createSession();
            System.out.println("Session created successfully for " + testName);

            Map<String, String> parameters = new HashMap<>();
            parameters.put(SessionParameter.REPOSITORY_ID, "bedroom");
            // Add all required parameters for TCK framework
            parameters.put(SessionParameter.BINDING_TYPE, BindingType.BROWSER.value());
            parameters.put(SessionParameter.BROWSER_URL, "http://localhost:8080/core/browser/bedroom");
            parameters.put(SessionParameter.USER, "admin");
            parameters.put(SessionParameter.PASSWORD, "admin");

            test.init(parameters);

            // Create a minimal group to avoid NPE
            AbstractCmisTestGroup dummyGroup = new AbstractCmisTestGroup() {
                @Override
                public String getName() {
                    return "DirectTest";
                }

                @Override
                public void init(Map<String, String> parameters) {
                    // No-op
                }

                @Override
                public void run() throws Exception {
                    // No-op
                }
            };

            // Set the group using reflection
            java.lang.reflect.Field groupField = null;
            Class<?> groupClazz = test.getClass();
            while (groupClazz != null && groupField == null) {
                try {
                    groupField = groupClazz.getDeclaredField("group");
                } catch (NoSuchFieldException e) {
                    groupClazz = groupClazz.getSuperclass();
                }
            }

            if (groupField != null) {
                groupField.setAccessible(true);
                groupField.set(test, dummyGroup);
            }
            
            // Inject session using reflection (find field in class hierarchy)
            java.lang.reflect.Field sessionField = null;
            Class<?> clazz = test.getClass();
            while (clazz != null && sessionField == null) {
                try {
                    sessionField = clazz.getDeclaredField("session");
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }

            if (sessionField != null) {
                sessionField.setAccessible(true);
                sessionField.set(test, session);
            } else {
                System.out.println("Warning: Could not find session field in test class hierarchy");
            }
            
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

            // CRITICAL FIX: Fail the JUnit test if TCK test has failures
            if (hasFailures) {
                org.junit.Assert.fail("TCK test " + testName + " detected failures");
            }

        } catch (Exception e) {
            System.err.println("=== " + testName + " EXCEPTION ===");
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.err.println("=== END " + testName + " EXCEPTION ===\n");
            // CRITICAL FIX: Re-throw exception to fail the JUnit test
            throw e;
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
