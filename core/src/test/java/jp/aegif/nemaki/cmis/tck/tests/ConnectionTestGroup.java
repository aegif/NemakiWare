package jp.aegif.nemaki.cmis.tck.tests;

import org.junit.Test;
import org.junit.Ignore;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import java.util.HashMap;
import java.util.Map;

import jp.aegif.nemaki.cmis.tck.TckSuite;

/**
 * Simple connection test to isolate TCK hanging issues
 * This test attempts basic CMIS session creation without full TCK framework
 */
public class ConnectionTestGroup extends TckSuite {

    @Test
    public void basicConnectionTest() throws Exception {
        System.out.println("=== BASIC CONNECTION TEST START ===");
        
        try {
            Map<String, String> parameters = new HashMap<>();
            parameters.put(SessionParameter.BINDING_TYPE, BindingType.BROWSER.value());
            parameters.put(SessionParameter.BROWSER_URL, "http://localhost:8080/core/browser/bedroom");
            parameters.put(SessionParameter.USER, "admin");
            parameters.put(SessionParameter.PASSWORD, "admin");
            parameters.put(SessionParameter.REPOSITORY_ID, "bedroom");
            
            // Add timeout parameters
            parameters.put(SessionParameter.CONNECT_TIMEOUT, "30000");
            parameters.put(SessionParameter.READ_TIMEOUT, "120000");
            
            System.out.println("Creating session factory...");
            SessionFactory factory = SessionFactoryImpl.newInstance();
            
            System.out.println("Getting repositories...");
            var repositories = factory.getRepositories(parameters);
            System.out.println("Found repositories: " + repositories.size());
            
            System.out.println("Creating session...");
            Session session = factory.createSession(parameters);
            System.out.println("Session created: " + session.getRepositoryInfo().getName());
            
            System.out.println("Getting root folder...");
            var rootFolder = session.getRootFolder();
            System.out.println("Root folder: " + rootFolder.getName() + " (ID: " + rootFolder.getId() + ")");
            
            System.out.println("=== BASIC CONNECTION TEST SUCCESS ===");
            
        } catch (Exception e) {
            System.err.println("=== BASIC CONNECTION TEST FAILED ===");
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }
    
    @Test
    public void repositoryInfoOnlyTest() throws Exception {
        System.out.println("=== REPOSITORY INFO ONLY TEST START ===");
        
        try {
            Map<String, String> parameters = new HashMap<>();
            parameters.put(SessionParameter.BINDING_TYPE, BindingType.BROWSER.value());
            parameters.put(SessionParameter.BROWSER_URL, "http://localhost:8080/core/browser/bedroom");
            parameters.put(SessionParameter.USER, "admin");
            parameters.put(SessionParameter.PASSWORD, "admin");
            
            // Add timeout parameters
            parameters.put(SessionParameter.CONNECT_TIMEOUT, "30000");
            parameters.put(SessionParameter.READ_TIMEOUT, "60000");
            
            System.out.println("Creating session factory...");
            SessionFactory factory = SessionFactoryImpl.newInstance();
            
            System.out.println("Getting repositories (this should be quick)...");
            var repositories = factory.getRepositories(parameters);
            System.out.println("Found repositories: " + repositories.size());
            
            if (!repositories.isEmpty()) {
                var repo = repositories.get(0);
                System.out.println("Repository: " + repo.getName() + " (ID: " + repo.getId() + ")");
                System.out.println("Vendor: " + repo.getVendorName());
                System.out.println("Product: " + repo.getProductName() + " " + repo.getProductVersion());
            }
            
            System.out.println("=== REPOSITORY INFO ONLY TEST SUCCESS ===");
            
        } catch (Exception e) {
            System.err.println("=== REPOSITORY INFO ONLY TEST FAILED ===");
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }
}