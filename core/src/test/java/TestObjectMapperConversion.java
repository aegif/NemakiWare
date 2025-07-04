import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.couch.CouchFolder;
import java.util.Map;
import java.util.Arrays;

public class TestObjectMapperConversion {
    public static void main(String[] args) throws Exception {
        // Create a test Folder object
        Folder folder = new Folder();
        folder.setId("test-folder-id");
        folder.setName("Test Folder");
        folder.setObjectType("cmis:folder");
        folder.setType("Folder");
        folder.setParentId("parent-id");
        folder.setDescription("Test Description");
        
        // Create CouchFolder from Folder
        CouchFolder couchFolder = new CouchFolder(folder);
        
        System.out.println("=== Original CouchFolder Properties ===");
        System.out.println("ID: " + couchFolder.getId());
        System.out.println("Name: " + couchFolder.getName());
        System.out.println("ObjectType: " + couchFolder.getObjectType());
        System.out.println("Type: " + couchFolder.getType());
        System.out.println("ParentId: " + couchFolder.getParentId());
        System.out.println("Description: " + couchFolder.getDescription());
        
        // Test ObjectMapper conversion
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        
        @SuppressWarnings("unchecked")
        Map<String, Object> documentMap = mapper.convertValue(couchFolder, Map.class);
        
        System.out.println("\n=== After ObjectMapper.convertValue ===");
        System.out.println("Map size: " + documentMap.size());
        System.out.println("Map keys: " + documentMap.keySet());
        
        // Print all key-value pairs
        for (Map.Entry<String, Object> entry : documentMap.entrySet()) {
            System.out.println(entry.getKey() + " = " + entry.getValue());
        }
        
        // Check specific properties
        System.out.println("\n=== Checking Specific Properties ===");
        System.out.println("objectType from map: " + documentMap.get("objectType"));
        System.out.println("name from map: " + documentMap.get("name"));
        System.out.println("type from map: " + documentMap.get("type"));
        System.out.println("_id from map: " + documentMap.get("_id"));
        System.out.println("id from map: " + documentMap.get("id"));
        
        // Test JSON string conversion
        String jsonString = mapper.writeValueAsString(documentMap);
        System.out.println("\n=== JSON String ===");
        System.out.println(jsonString);
    }
}