package jp.aegif.nemaki.cmis.tck.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
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

            SessionFactory factory = SessionFactoryImpl.newInstance();
            var repositories = factory.getRepositories(parameters);
            Session session = factory.createSession(parameters);
            var rootFolder = session.getRootFolder();

            // Verify basic connectivity
            assertFalse(repositories.isEmpty(), "No repositories found");
            assertNotNull(session.getRepositoryInfo().getName(), "Repository name is null");
            assertNotNull(rootFolder.getName(), "Root folder name is null");

        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }
    
    @Test
    public void repositoryInfoOnlyTest() throws Exception {
        try {
            Map<String, String> parameters = new HashMap<>();
            parameters.put(SessionParameter.BINDING_TYPE, BindingType.BROWSER.value());
            parameters.put(SessionParameter.BROWSER_URL, "http://localhost:8080/core/browser/bedroom");
            parameters.put(SessionParameter.USER, "admin");
            parameters.put(SessionParameter.PASSWORD, "admin");

            // Add timeout parameters
            parameters.put(SessionParameter.CONNECT_TIMEOUT, "30000");
            parameters.put(SessionParameter.READ_TIMEOUT, "60000");

            SessionFactory factory = SessionFactoryImpl.newInstance();
            var repositories = factory.getRepositories(parameters);

            // Verify repository information is accessible
            assertFalse(repositories.isEmpty(), "No repositories found");

            var repo = repositories.get(0);
            assertNotNull(repo.getName(), "Repository name is null");
            assertNotNull(repo.getId(), "Repository ID is null");
            assertNotNull(repo.getVendorName(), "Vendor name is null");
            assertNotNull(repo.getProductName(), "Product name is null");

        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }
}