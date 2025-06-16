package jp.aegif.nemaki.cmis.tck;

import static org.junit.Assert.*;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.client.api.ItemIterable;
import org.apache.chemistry.opencmis.client.api.QueryResult;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.junit.Before;
import org.junit.Test;

public class QueryDiagnosticTest {
    
    private Session session;
    
    @Before
    public void setUp() {
        Map<String, String> parameters = new HashMap<String, String>();
        parameters.put(SessionParameter.BINDING_TYPE, BindingType.ATOMPUB.value());
        parameters.put(SessionParameter.ATOMPUB_URL, "http://localhost:8080/core/atom/bedroom");
        parameters.put(SessionParameter.USER, "admin");
        parameters.put(SessionParameter.PASSWORD, "admin");
        parameters.put(SessionParameter.REPOSITORY_ID, "bedroom");
        
        SessionFactory factory = SessionFactoryImpl.newInstance();
        session = factory.getRepositories(parameters).get(0).createSession();
    }
    
    @Test
    public void testBasicFolderQuery() {
        String query = "SELECT * FROM cmis:folder";
        ItemIterable<QueryResult> results = session.query(query, false);
        
        long count = results.getTotalNumItems();
        System.out.println("Basic folder query results: " + count);
        assertTrue("Should find at least one folder", count > 0);
    }
    
    @Test
    public void testRootFolderQuery() {
        String query = "SELECT cmis:name, cmis:objectId FROM cmis:folder WHERE cmis:objectId = 'e02f784f8360a02cc14d1314c10038ff'";
        ItemIterable<QueryResult> results = session.query(query, false);
        
        long count = results.getTotalNumItems();
        System.out.println("Root folder query results: " + count);
        assertEquals("Should find exactly one root folder", 1, count);
    }
    
    @Test
    public void testDocumentQuery() {
        String query = "SELECT * FROM cmis:document";
        ItemIterable<QueryResult> results = session.query(query, false);
        
        long count = results.getTotalNumItems();
        System.out.println("Document query results: " + count);
    }
}
