package jp.aegif.nemaki.cmis.tck.tests;

import org.junit.Test;
import org.junit.Assert;
import org.junit.Ignore;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.chemistry.opencmis.tck.tests.types.BaseTypesTest;
import org.apache.chemistry.opencmis.tck.CmisTest;
import org.apache.chemistry.opencmis.tck.CmisTestResult;
import org.apache.chemistry.opencmis.tck.CmisTestResultStatus;
import java.util.HashMap;
import java.util.Map;

/**
 * DEPRECATED: This class was created as a diagnostic tool for JUnitRunner hang issues.
 * The hang issue has been resolved by static initialization fix in TestGroupBase.
 * Use standard test groups instead - this class is kept for reference only.
 *
 * Detailed version of DirectTckTestRunner with comprehensive logging
 * to verify what is actually being tested
 */
@Ignore("DEPRECATED: Use standard test groups - hang issue resolved by TestGroupBase static initialization fix")
public class DirectTckTestRunnerDetailed {

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
        System.out.println("\n" + "=".repeat(70));
        System.out.println("=== DETAILED TCK TEST: " + testName + " ===");
        System.out.println("=".repeat(70));

        try {
            Session session = createSession();
            System.out.println("✓ Session created successfully");
            System.out.println("  Repository: " + session.getRepositoryInfo().getId());
            System.out.println("  CMIS Version: " + session.getRepositoryInfo().getCmisVersion());

            Map<String, String> parameters = new HashMap<>();
            parameters.put(SessionParameter.REPOSITORY_ID, "bedroom");
            // Add all required parameters for TCK framework
            parameters.put(SessionParameter.BINDING_TYPE, BindingType.BROWSER.value());
            parameters.put(SessionParameter.BROWSER_URL, "http://localhost:8080/core/browser/bedroom");
            parameters.put(SessionParameter.USER, "admin");
            parameters.put(SessionParameter.PASSWORD, "admin");

            System.out.println("\n1. Initializing test with parameters...");
            test.init(parameters);
            System.out.println("✓ Test initialized");

            System.out.println("\n2. Injecting session using reflection...");
            java.lang.reflect.Field sessionField = null;
            Class<?> clazz = test.getClass();
            while (clazz != null && sessionField == null) {
                try {
                    sessionField = clazz.getDeclaredField("session");
                    System.out.println("  Found session field in: " + clazz.getName());
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }

            if (sessionField != null) {
                sessionField.setAccessible(true);
                sessionField.set(test, session);
                System.out.println("✓ Session injected successfully");
            } else {
                System.out.println("⚠️ WARNING: Could not find session field");
            }

            System.out.println("\n3. Running test.run()...");
            test.run();
            System.out.println("✓ Test execution completed");

            System.out.println("\n4. Analyzing results...");
            System.out.println("Total results: " + test.getResults().size());

            boolean hasFailures = false;
            int resultCount = 0;
            int okCount = 0, infoCount = 0, warningCount = 0, failureCount = 0, skippedCount = 0;

            for (CmisTestResult result : test.getResults()) {
                resultCount++;
                CmisTestResultStatus status = result.getStatus();
                String message = result.getMessage();

                // Count by type
                switch (status) {
                    case OK: okCount++; break;
                    case INFO: infoCount++; break;
                    case WARNING: warningCount++; break;
                    case FAILURE: failureCount++; hasFailures = true; break;
                    case UNEXPECTED_EXCEPTION: failureCount++; hasFailures = true; break;
                    case SKIPPED: skippedCount++; break;
                }

                // Print details for non-OK results
                if (status != CmisTestResultStatus.OK && status != CmisTestResultStatus.INFO) {
                    System.out.println("\nResult #" + resultCount + ":");
                    System.out.println("  Status: " + status + " (level=" + status.getLevel() + ")");
                    System.out.println("  Message: " + message);

                    if (result.getException() != null) {
                        System.out.println("  Exception: " + result.getException().getMessage());
                    }
                }

                // Check child results
                if (result.getChildren() != null && !result.getChildren().isEmpty()) {
                    for (CmisTestResult child : result.getChildren()) {
                        if (child.getStatus().getLevel() >= CmisTestResultStatus.FAILURE.getLevel()) {
                            hasFailures = true;
                            failureCount++;
                            System.out.println("    CHILD FAILURE: " + child.getMessage());
                        }
                    }
                }
            }

            System.out.println("\n" + "=".repeat(70));
            System.out.println("=== " + testName + " RESULT SUMMARY ===");
            System.out.println("OK: " + okCount);
            System.out.println("INFO: " + infoCount);
            System.out.println("WARNING: " + warningCount);
            System.out.println("FAILURE: " + failureCount);
            System.out.println("SKIPPED: " + skippedCount);
            System.out.println("TOTAL: " + test.getResults().size());
            System.out.println("FINAL STATUS: " + (hasFailures ? "❌ FAILED" : "✅ PASSED"));
            System.out.println("=".repeat(70) + "\n");

            // CRITICAL: Assert.fail if there are failures
            // This is missing in the original DirectTckTestRunner!
            if (hasFailures) {
                Assert.fail("TCK test " + testName + " has " + failureCount + " failures");
            }

        } catch (Exception e) {
            System.err.println("\n" + "=".repeat(70));
            System.err.println("=== " + testName + " EXCEPTION ===");
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.err.println("=".repeat(70) + "\n");
            throw e; // Re-throw to fail the test
        }
    }

    @Test
    public void detailedBaseTypesTest() throws Exception {
        System.out.println("\n\n" + "#".repeat(80));
        System.out.println("### STARTING DETAILED BASE TYPES TEST ###");
        System.out.println("#".repeat(80));
        runTckTest(new BaseTypesTest(), "BaseTypesTest");
    }
}