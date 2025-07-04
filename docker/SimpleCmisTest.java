import java.util.*;
import org.apache.chemistry.opencmis.client.api.*;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.enums.BindingType;

/**
 * Simple CMIS functionality test for TCK validation
 */
public class SimpleCmisTest {
    
    public static void main(String[] args) {
        System.out.println("=== NemakiWare CMIS Basic Functionality Test ===");
        
        try {
            // Create session parameters
            Map<String, String> parameters = new HashMap<>();
            parameters.put(SessionParameter.BINDING_TYPE, BindingType.ATOMPUB.value());
            parameters.put(SessionParameter.ATOMPUB_URL, "http://localhost:8080/core/atom/bedroom");
            parameters.put(SessionParameter.USER, "admin");
            parameters.put(SessionParameter.PASSWORD, "admin");
            parameters.put(SessionParameter.REPOSITORY_ID, "bedroom");
            
            // Create session factory
            SessionFactory sessionFactory = SessionFactoryImpl.newInstance();
            
            System.out.println("Attempting to connect to CMIS repository...");
            System.out.println("URL: " + parameters.get(SessionParameter.ATOMPUB_URL));
            
            // Create session
            Session session = sessionFactory.createSession(parameters);
            
            System.out.println("✅ CMIS Session created successfully\!");
            
            // Test 1: Repository Information
            System.out.println("\n--- Test 1: Repository Information ---");
            RepositoryInfo repoInfo = session.getRepositoryInfo();
            System.out.println("Repository ID: " + repoInfo.getId());
            System.out.println("Repository Name: " + repoInfo.getName());
            System.out.println("CMIS Version: " + repoInfo.getCmisVersionSupported());
            System.out.println("Product Name: " + repoInfo.getProductName());
            System.out.println("Product Version: " + repoInfo.getProductVersion());
            System.out.println("✅ Repository information retrieved successfully\!");
            
            // Test 2: Root Folder Access
            System.out.println("\n--- Test 2: Root Folder Access ---");
            Folder rootFolder = session.getRootFolder();
            System.out.println("Root Folder ID: " + rootFolder.getId());
            System.out.println("Root Folder Name: " + rootFolder.getName());
            System.out.println("Root Folder Type: " + rootFolder.getBaseTypeId());
            System.out.println("✅ Root folder access successful\!");
            
            System.out.println("\n=== CMIS Basic Functionality Test COMPLETED ===");
            System.out.println("Core connection established - proceeding to full TCK");
            
        } catch (Exception e) {
            System.err.println("❌ CMIS Test failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
EOF < /dev/null