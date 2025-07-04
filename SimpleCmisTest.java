import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;

public class SimpleCmisTest {
    public static void main(String[] args) {
        System.out.println("=== NemakiWare CMIS Functionality Test ===");
        
        try {
            // Test 1: Repository Info
            testEndpoint("Repository Info (AtomPub)", 
                        "http://localhost:8080/core/atom/bedroom", 
                        "admin", "admin");
            
            // Test 2: Browse Binding
            testEndpoint("Browser Binding", 
                        "http://localhost:8080/core/browser/bedroom", 
                        "admin", "admin");
            
            // Test 3: Web Services
            testEndpoint("Web Services", 
                        "http://localhost:8080/core/services", 
                        null, null);
            
            // Test 4: CMIS Query via POST (this tests the fixed query functionality)
            testCmisQuery("CMIS Query Test", 
                         "http://localhost:8080/core/atom/bedroom/query", 
                         "admin", "admin",
                         "SELECT * FROM cmis:folder WHERE cmis:name = 'bedroom'");
            
            System.out.println("\n=== All Tests Completed ===");
            
        } catch (Exception e) {
            System.err.println("Test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void testEndpoint(String testName, String urlString, String username, String password) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            
            if (username != null && password != null) {
                String auth = username + ":" + password;
                String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());
                conn.setRequestProperty("Authorization", "Basic " + encodedAuth);
            }
            
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            
            int responseCode = conn.getResponseCode();
            System.out.println(testName + ": HTTP " + responseCode + 
                             (responseCode == 200 ? " - SUCCESS" : " - CHECK"));
            
            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String line = reader.readLine();
                if (line != null && line.length() > 50) {
                    System.out.println("  Response preview: " + line.substring(0, 50) + "...");
                } else if (line != null) {
                    System.out.println("  Response: " + line);
                }
                reader.close();
            }
            
        } catch (Exception e) {
            System.out.println(testName + ": ERROR - " + e.getMessage());
        }
    }
    
    private static void testCmisQuery(String testName, String urlString, String username, String password, String query) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            
            String auth = username + ":" + password;
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());
            conn.setRequestProperty("Authorization", "Basic " + encodedAuth);
            
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setDoOutput(true);
            
            String postData = "q=" + java.net.URLEncoder.encode(query, "UTF-8");
            conn.getOutputStream().write(postData.getBytes());
            conn.getOutputStream().close();
            
            int responseCode = conn.getResponseCode();
            System.out.println(testName + ": HTTP " + responseCode + 
                             (responseCode == 200 ? " - SUCCESS" : " - CHECK"));
            
            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String line = reader.readLine();
                if (line != null && line.length() > 50) {
                    System.out.println("  Query result preview: " + line.substring(0, 50) + "...");
                }
                reader.close();
            }
            
        } catch (Exception e) {
            System.out.println(testName + ": ERROR - " + e.getMessage());
        }
    }
}