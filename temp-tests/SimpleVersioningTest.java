package jp.aegif.nemaki.test;

import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.model.*;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.*;
import org.apache.chemistry.opencmis.commons.server.CallContext;

import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.util.*;

import static org.junit.Assert.*;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = {
    "classpath:applicationContext.xml",
    "classpath:serviceContext.xml",
    "classpath:businesslogicContext.xml",
    "classpath:daoContext.xml"
})
public class SimpleVersioningTest {

    @Autowired
    private TypeManager typeManager;
    
    @Autowired
    private ContentService contentService;
    
    @Autowired
    private PrincipalService principalService;
    
    @Autowired
    private PropertyManager propertyManager;
    
    private String repositoryId = "bedroom";
    private String userId = "admin";
    
    @Before
    public void setUp() {
        // Ensure admin user exists
        try {
            User user = principalService.getUserById(repositoryId, userId);
            if (user == null) {
                System.err.println("Admin user not found in repository");
            }
        } catch (Exception e) {
            System.err.println("Error checking user: " + e.getMessage());
        }
    }
    
    @Test
    public void testVersioningTypeDefinition() {
        System.out.println("\n=== Testing Versioning Type Definition ===");
        
        // Test cmis:document type
        TypeDefinition documentTypeDef = typeManager.getTypeDefinition(repositoryId, BaseTypeId.CMIS_DOCUMENT.value());
        assertNotNull("cmis:document type should be available", documentTypeDef);
        
        assertTrue("Document type should be DocumentTypeDefinition", documentTypeDef instanceof DocumentTypeDefinition);
        DocumentTypeDefinition docTypeDef = (DocumentTypeDefinition) documentTypeDef;
        
        // Check if versioning is enabled
        Boolean versionable = docTypeDef.isVersionable();
        System.out.println("Document Type Versioning Configuration:");
        System.out.println("- Type ID: " + docTypeDef.getId());
        System.out.println("- Versionable: " + versionable);
        System.out.println("- Content Stream Allowed: " + docTypeDef.getContentStreamAllowed());
        
        assertTrue("Document type should be versionable", Boolean.TRUE.equals(versionable));
        
        // Check property definitions
        System.out.println("\nVersioning Properties Available:");
        String[] versioningProps = {
            PropertyIds.VERSION_LABEL,
            PropertyIds.VERSION_SERIES_ID,
            PropertyIds.IS_LATEST_VERSION,
            PropertyIds.IS_MAJOR_VERSION,
            PropertyIds.IS_LATEST_MAJOR_VERSION,
            PropertyIds.VERSION_SERIES_CHECKED_OUT_BY,
            PropertyIds.VERSION_SERIES_CHECKED_OUT_ID,
            PropertyIds.IS_VERSION_SERIES_CHECKED_OUT,
            PropertyIds.CHECKIN_COMMENT
        };
        
        for (String propId : versioningProps) {
            boolean hasProp = docTypeDef.getPropertyDefinitions().containsKey(propId);
            System.out.println("- " + propId + ": " + (hasProp ? "YES" : "NO"));
            if (!hasProp) {
                System.err.println("  WARNING: Missing required versioning property!");
            }
        }
    }
    
    @Test 
    public void testVersioningOperations() {
        System.out.println("\n=== Testing Versioning Operations ===");
        
        try {
            // Get root folder
            String rootFolderId = propertyManager.readValue(PropertyKey.CAPABILITY_EXTENDED_USER_ITEM_FOLDER);
            if (rootFolderId == null) {
                rootFolderId = "e02f784f8360a02cc14d1314c10038ff"; // default
            }
            
            Folder rootFolder = contentService.getFolder(repositoryId, rootFolderId);
            assertNotNull("Root folder should exist", rootFolder);
            System.out.println("Using root folder: " + rootFolder.getId());
            
            // Create a versionable document
            Properties properties = createDocumentProperties("VersionTest.txt", BaseTypeId.CMIS_DOCUMENT.value());
            
            Document doc = contentService.createDocument(
                repositoryId,
                properties,
                rootFolder.getId(),
                null, // no content stream initially
                VersioningState.MAJOR,
                null, // no policies
                null, // no addAces
                null  // no removeAces
            );
            
            assertNotNull("Document should be created", doc);
            System.out.println("Created document: " + doc.getId());
            System.out.println("- Version Label: " + doc.getVersionLabel());
            System.out.println("- Version Series ID: " + doc.getVersionSeriesId());
            System.out.println("- Is Latest Version: " + doc.isLatestVersion());
            System.out.println("- Is Major Version: " + doc.isMajorVersion());
            
            // Test check out
            System.out.println("\nTesting check out...");
            Document pwc = contentService.checkOut(repositoryId, doc.getId(), null);
            assertNotNull("PWC should be created", pwc);
            System.out.println("Checked out document: " + pwc.getId());
            System.out.println("- Is Private Working Copy: " + pwc.isPrivateWorkingCopy());
            
            // Refresh original document to check its state
            doc = contentService.getDocument(repositoryId, doc.getId());
            System.out.println("Original document after check out:");
            System.out.println("- Is Version Series Checked Out: " + doc.isVersionSeriesCheckedOut());
            System.out.println("- Version Series Checked Out By: " + doc.getVersionSeriesCheckedOutBy());
            System.out.println("- Version Series Checked Out ID: " + doc.getVersionSeriesCheckedOutId());
            
            // Test cancel check out
            System.out.println("\nTesting cancel check out...");
            contentService.cancelCheckOut(repositoryId, doc.getId(), null);
            
            doc = contentService.getDocument(repositoryId, doc.getId());
            System.out.println("Document after cancel check out:");
            System.out.println("- Is Version Series Checked Out: " + doc.isVersionSeriesCheckedOut());
            
            // Clean up
            contentService.deleteDocument(repositoryId, doc.getId(), true);
            System.out.println("\nTest document deleted successfully");
            
        } catch (Exception e) {
            System.err.println("Error during versioning operations: " + e.getMessage());
            e.printStackTrace();
            fail("Versioning operations failed: " + e.getMessage());
        }
    }
    
    private Properties createDocumentProperties(String name, String typeId) {
        List<PropertyData<?>> propertyList = new ArrayList<>();
        
        // Name
        propertyList.add(new PropertyStringImpl(PropertyIds.NAME, name));
        
        // Object Type ID
        propertyList.add(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, typeId));
        
        PropertiesImpl properties = new PropertiesImpl();
        properties.setProperties(propertyList);
        
        return properties;
    }
}