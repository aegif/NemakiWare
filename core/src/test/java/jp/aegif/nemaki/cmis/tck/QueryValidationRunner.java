package jp.aegif.nemaki.cmis.tck;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.chemistry.opencmis.client.api.ItemIterable;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.client.api.QueryResult;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.enums.BindingType;

/**
 * Validates CMIS query functionality with detailed result analysis
 */
public class QueryValidationRunner {
    
    private static final String PARAMETERS_FILE = "/tmp/cmis-tck-parameters-docker.properties";
    private Session session;
    
    public QueryValidationRunner() throws Exception {
        initializeSession();
    }
    
    private void initializeSession() throws Exception {
        // Load properties
        Properties props = new Properties();
        try (java.io.FileInputStream is = new java.io.FileInputStream(PARAMETERS_FILE)) {
            props.load(is);
        }
        
        // Create session parameters
        Map<String, String> sessionParams = new HashMap<>();
        sessionParams.put(SessionParameter.BINDING_TYPE, BindingType.ATOMPUB.value());
        sessionParams.put(SessionParameter.ATOMPUB_URL, props.getProperty("org.apache.chemistry.opencmis.binding.atompub.url"));
        sessionParams.put(SessionParameter.USER, props.getProperty("org.apache.chemistry.opencmis.user"));
        sessionParams.put(SessionParameter.PASSWORD, props.getProperty("org.apache.chemistry.opencmis.password"));
        sessionParams.put(SessionParameter.REPOSITORY_ID, props.getProperty("org.apache.chemistry.opencmis.session.repository.id"));
        
        // Create session
        SessionFactory factory = SessionFactoryImpl.newInstance();
        session = factory.createSession(sessionParams);
        
        System.out.println("✓ Connected to repository: " + session.getRepositoryInfo().getName());
    }
    
    public void validateQueries(PrintWriter writer) {
        writer.println("=================================================================");
        writer.println("DETAILED QUERY VALIDATION REPORT");
        writer.println("=================================================================");
        writer.println("Generated: " + new java.util.Date());
        writer.println("Repository: " + session.getRepositoryInfo().getId());
        writer.println("Root Folder ID: " + session.getRootFolder().getId());
        writer.println("");
        
        // Define test queries with expected behavior
        String[][] testQueries = {
            {"SELECT * FROM cmis:document", "All documents in repository"},
            {"SELECT * FROM cmis:folder", "All folders in repository"},
            {"SELECT * FROM cmis:item", "All items (users/groups) in repository"},
            {"SELECT cmis:objectId, cmis:name FROM cmis:document", "Document IDs and names only"},
            {"SELECT cmis:objectId, cmis:name FROM cmis:folder", "Folder IDs and names only"},
            {"SELECT * FROM cmis:folder WHERE cmis:name = '.system'", "System folder by exact name"},
            {"SELECT * FROM cmis:document WHERE cmis:name LIKE '%.txt'", "Text files by pattern"},
            {"SELECT * FROM cmis:folder WHERE IN_FOLDER('" + session.getRootFolder().getId() + "')", "Direct children of root folder"},
            {"SELECT * FROM cmis:document WHERE IN_TREE('" + session.getRootFolder().getId() + "')", "All documents in repository tree"},
            {"SELECT * FROM cmis:folder ORDER BY cmis:name", "Folders sorted by name"}
        };
        
        writer.println("QUERY VALIDATION RESULTS:");
        writer.println("-----------------------------------------------------------------");
        
        int totalQueries = 0;
        int successfulQueries = 0;
        int queryWithResults = 0;
        
        for (String[] queryInfo : testQueries) {
            String query = queryInfo[0];
            String description = queryInfo[1];
            totalQueries++;
            
            writer.println("");
            writer.println((totalQueries) + ". " + description);
            writer.println("   Query: " + query);
            
            try {
                long startTime = System.currentTimeMillis();
                ItemIterable<QueryResult> results = session.query(query, false);
                long executionTime = System.currentTimeMillis() - startTime;
                
                int resultCount = 0;
                boolean hasValidData = false;
                
                // Count results and validate data
                for (QueryResult result : results) {
                    resultCount++;
                    if (resultCount <= 3) { // Show first 3 results for verification
                        writer.println("   Result " + resultCount + ":");
                        for (PropertyData<?> prop : result.getProperties()) {
                            Object value = prop.getFirstValue();
                            String displayValue = (value != null) ? value.toString() : "null";
                            if (displayValue.length() > 50) {
                                displayValue = displayValue.substring(0, 47) + "...";
                            }
                            writer.println("     " + prop.getId() + " = " + displayValue);
                            hasValidData = true;
                        }
                    }
                    if (resultCount >= 100) break; // Limit counting for performance
                }
                
                writer.println("   ✓ EXECUTED (" + executionTime + "ms)");
                writer.println("   ✓ RESULT COUNT: " + resultCount + " items");
                
                if (resultCount > 0) {
                    queryWithResults++;
                    writer.println("   ✓ STATUS: SUCCESS with data");
                } else {
                    writer.println("   ⚠ STATUS: SUCCESS but no results (may be expected)");
                }
                
                successfulQueries++;
                
            } catch (Exception e) {
                writer.println("   ✗ ERROR: " + e.getMessage());
                writer.println("   ✗ STATUS: FAILED");
                e.printStackTrace(writer);
            }
        }
        
        writer.println("");
        writer.println("=================================================================");
        writer.println("QUERY VALIDATION SUMMARY");
        writer.println("=================================================================");
        writer.println("Total Queries Tested: " + totalQueries);
        writer.println("Successfully Executed: " + successfulQueries + " (" + (successfulQueries * 100 / totalQueries) + "%)");
        writer.println("Queries with Results: " + queryWithResults + " (" + (queryWithResults * 100 / totalQueries) + "%)");
        writer.println("Queries with No Results: " + (successfulQueries - queryWithResults));
        writer.println("");
        
        if (successfulQueries == totalQueries) {
            writer.println("✅ OVERALL STATUS: ALL QUERIES EXECUTED SUCCESSFULLY");
        } else {
            writer.println("❌ OVERALL STATUS: " + (totalQueries - successfulQueries) + " QUERIES FAILED");
        }
        
        // Additional repository content analysis
        writer.println("");
        writer.println("=================================================================");
        writer.println("REPOSITORY CONTENT ANALYSIS");
        writer.println("=================================================================");
        
        try {
            // Count different object types
            int documentCount = countObjects("SELECT * FROM cmis:document");
            int folderCount = countObjects("SELECT * FROM cmis:folder");  
            int itemCount = countObjects("SELECT * FROM cmis:item");
            
            writer.println("Repository Content Summary:");
            writer.println("- Documents: " + documentCount);
            writer.println("- Folders: " + folderCount);  
            writer.println("- Items (users/groups): " + itemCount);
            writer.println("- Total Objects: " + (documentCount + folderCount + itemCount));
            
            if (documentCount == 0 && folderCount <= 1 && itemCount == 0) {
                writer.println("");
                writer.println("⚠️  WARNING: Repository appears to be mostly empty!");
                writer.println("   This may explain why some queries return 0 results.");
                writer.println("   Consider initializing test data for more comprehensive testing.");
            }
            
        } catch (Exception e) {
            writer.println("Error analyzing repository content: " + e.getMessage());
        }
        
        writer.println("");
    }
    
    private int countObjects(String query) {
        try {
            ItemIterable<QueryResult> results = session.query(query, false);
            int count = 0;
            for (QueryResult result : results) {
                count++;
                if (count >= 1000) break; // Prevent excessive counting
            }
            return count;
        } catch (Exception e) {
            return -1; // Error
        }
    }
    
    public static void main(String[] args) throws Exception {
        System.out.println("Starting CMIS Query Validation...");
        
        QueryValidationRunner validator = new QueryValidationRunner();
        
        // Create reports directory
        String reportPath = "/tmp/tck-reports";
        File reportsDir = new File(reportPath);
        reportsDir.mkdirs();
        
        // Generate detailed query validation report
        try (PrintWriter writer = new PrintWriter(new FileWriter(new File(reportsDir, "query-validation-report.txt")))) {
            validator.validateQueries(writer);
        }
        
        System.out.println("Query validation completed!");
        System.out.println("Report available at: " + reportPath + "/query-validation-report.txt");
    }
}