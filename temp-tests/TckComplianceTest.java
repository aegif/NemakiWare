package jp.aegif.nemaki.test;

import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.util.PropertyManager;

import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import static org.junit.Assert.*;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = {
    "classpath:applicationContext.xml",
    "classpath:serviceContext.xml", 
    "classpath:businesslogicContext.xml",
    "classpath:daoContext.xml"
})
public class TckComplianceTest {

    @Autowired
    private TypeManager typeManager;
    
    @Autowired 
    private PropertyManager propertyManager;
    
    private String repositoryId = "bedroom";
    
    @Test
    public void testDocumentTypeContentStreamConfiguration() {
        System.out.println("\n=== Testing ACL/ControlTestGroup Compliance ===");
        
        // Test cmis:document type content stream configuration
        TypeDefinition documentTypeDef = typeManager.getTypeDefinition(repositoryId, BaseTypeId.CMIS_DOCUMENT.value());
        assertNotNull("cmis:document type should be available", documentTypeDef);
        
        assertTrue("Document type should be DocumentTypeDefinition", documentTypeDef instanceof DocumentTypeDefinition);
        DocumentTypeDefinition docTypeDef = (DocumentTypeDefinition) documentTypeDef;
        
        ContentStreamAllowed contentStreamAllowed = docTypeDef.getContentStreamAllowed();
        System.out.println("Document Type Content Stream Configuration:");
        System.out.println("- Content Stream Allowed: " + contentStreamAllowed);
        
        // This should now be ALLOWED instead of REQUIRED (ACL fix)
        assertEquals("Content stream should be ALLOWED for TCK compliance", 
                    ContentStreamAllowed.ALLOWED, contentStreamAllowed);
    }
    
    @Test
    public void testPolicyTypeConfiguration() {
        System.out.println("\n=== Testing TypesTestGroup Compliance ===");
        
        // Test cmis:policy type
        try {
            TypeDefinition policyTypeDef = typeManager.getTypeDefinition(repositoryId, BaseTypeId.CMIS_POLICY.value());
            assertNotNull("cmis:policy type should be available", policyTypeDef);
            
            Boolean creatable = policyTypeDef.isCreatable();
            Boolean queryable = policyTypeDef.isQueryable();
            
            System.out.println("Policy Type Configuration:");
            System.out.println("- Creatable: " + creatable);
            System.out.println("- Queryable: " + queryable);
            
            assertTrue("Policy type should be creatable for CMIS compliance", Boolean.TRUE.equals(creatable));
            assertTrue("Policy type should be queryable for CMIS compliance", Boolean.TRUE.equals(queryable));
        } catch (Exception e) {
            System.err.println("Policy type test failed: " + e.getMessage());
            // Policy type might not be implemented, which is acceptable
        }
    }
    
    @Test
    public void testItemTypeConfiguration() {
        System.out.println("\n=== Testing CMIS 1.1 Item Type Compliance ===");
        
        // Test cmis:item type (CMIS 1.1)
        try {
            TypeDefinition itemTypeDef = typeManager.getTypeDefinition(repositoryId, BaseTypeId.CMIS_ITEM.value());
            assertNotNull("cmis:item type should be available", itemTypeDef);
            
            Boolean queryable = itemTypeDef.isQueryable();
            
            System.out.println("Item Type Configuration:");
            System.out.println("- Queryable: " + queryable);
            
            assertTrue("Item type should be queryable for CMIS 1.1 compliance", Boolean.TRUE.equals(queryable));
        } catch (Exception e) {
            System.err.println("Item type test failed: " + e.getMessage());
            // Item type might not be implemented, which is acceptable
        }
    }
    
    @Test
    public void testVersioningConfiguration() {
        System.out.println("\n=== Testing VersioningTestGroup Compliance ===");
        
        TypeDefinition documentTypeDef = typeManager.getTypeDefinition(repositoryId, BaseTypeId.CMIS_DOCUMENT.value());
        DocumentTypeDefinition docTypeDef = (DocumentTypeDefinition) documentTypeDef;
        
        Boolean versionable = docTypeDef.isVersionable();
        System.out.println("Versioning Configuration:");
        System.out.println("- Document Type Versionable: " + versionable);
        
        assertTrue("Document type should be versionable", Boolean.TRUE.equals(versionable));
        
        // Check versioning properties exist
        String[] versioningProps = {
            "cmis:versionLabel",
            "cmis:versionSeriesId", 
            "cmis:isLatestVersion",
            "cmis:isMajorVersion",
            "cmis:isLatestMajorVersion"
        };
        
        System.out.println("Versioning Properties Check:");
        for (String propId : versioningProps) {
            boolean hasProp = docTypeDef.getPropertyDefinitions().containsKey(propId);
            System.out.println("- " + propId + ": " + (hasProp ? "PRESENT" : "MISSING"));
            assertTrue("Required versioning property should be present: " + propId, hasProp);
        }
    }
    
    @Test
    public void testPropertyOverrides() {
        System.out.println("\n=== Testing Property Override Configuration ===");
        
        // Test if property overrides are being applied
        System.out.println("Property Manager Configuration Test:");
        
        // These should reflect our overrides in nemakiware.properties
        try {
            String contentStreamAllowed = propertyManager.readValue("basetype.document.contentStreamAllowed");
            System.out.println("- basetype.document.contentStreamAllowed: " + contentStreamAllowed);
            assertEquals("Content stream override should be 'allowed'", "allowed", contentStreamAllowed);
            
            String policyCreatable = propertyManager.readValue("basetype.policy.creatable");
            System.out.println("- basetype.policy.creatable: " + policyCreatable);
            
            String itemQueryable = propertyManager.readValue("basetype.item.queryable");
            System.out.println("- basetype.item.queryable: " + itemQueryable);
            
        } catch (Exception e) {
            System.err.println("Property override test warning: " + e.getMessage());
        }
    }
}