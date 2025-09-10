package jp.aegif.nemaki.cmis.tck;

import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import java.util.HashMap;
import java.util.Map;

/**
 * Simple test to reproduce TreeSet.m access issue
 * This replicates the exact code path that fails in RootFolderTest
 */
public class TestOpenCMISClient {
    
    public static void main(String[] args) {
        System.out.println("=== OpenCMIS Client TreeSet Test ===");
        
        try {
            // Test against Docker WAR environment (port 8080)
            testEnvironment("Docker WAR", "8080");
            
            // Test against Jetty environment (port 8081) 
            testEnvironment("Jetty Maven", "8081");
            
        } catch (Exception e) {
            System.err.println("Test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void testEnvironment(String envName, String port) {
        System.out.println("\n--- Testing " + envName + " Environment (port " + port + ") ---");
        
        try {
            // Create session parameters (same as TCK)
            Map<String, String> parameters = new HashMap<>();
            parameters.put(SessionParameter.BINDING_TYPE, BindingType.ATOMPUB.value());
            parameters.put(SessionParameter.ATOMPUB_URL, "http://localhost:" + port + "/core/atom/bedroom");
            parameters.put(SessionParameter.USER, "admin");
            parameters.put(SessionParameter.PASSWORD, "admin");
            parameters.put(SessionParameter.REPOSITORY_ID, "bedroom");
            
            // Create session (this triggers XML parsing)
            SessionFactory factory = SessionFactoryImpl.newInstance();
            Session session = factory.createSession(parameters);
            
            System.out.println("✓ Session created successfully");
            
            // This is the exact line that fails in RootFolderTest.java:60
            System.out.print("Attempting getRootFolder()...");
            session.getRootFolder();
            System.out.println(" ✓ SUCCESS");
            
        } catch (Exception e) {
            System.out.println(" ✗ FAILED");
            System.out.println("Error: " + e.getMessage());
            if (e.getMessage().contains("TreeSet.m accessible")) {
                System.out.println(">>> This is the UNNAMEDモジュール問題 <<<");
            }
        }
    }
}