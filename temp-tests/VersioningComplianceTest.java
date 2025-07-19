package jp.aegif.nemaki.test;

import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = {
    "classpath:applicationContext.xml",
    "classpath:serviceContext.xml",
    "classpath:businesslogicContext.xml",
    "classpath:daoContext.xml"
})
public class VersioningComplianceTest {

    @Autowired
    private TypeManager typeManager;

    @Test
    public void testDocumentVersioningCompliance() {
        String repositoryId = "bedroom";
        
        // Test cmis:document type for versioning support
        TypeDefinition documentTypeDef = typeManager.getTypeDefinition(repositoryId, BaseTypeId.CMIS_DOCUMENT.value());
        assertNotNull("cmis:document type should be available", documentTypeDef);
        
        assertTrue("Document type should be DocumentTypeDefinition", documentTypeDef instanceof DocumentTypeDefinition);
        DocumentTypeDefinition docTypeDef = (DocumentTypeDefinition) documentTypeDef;
        
        // Check if versioning is enabled
        Boolean versionable = docTypeDef.isVersionable();
        assertNotNull("Versionable property should not be null", versionable);
        assertTrue("Document type should be versionable for CMIS compliance", versionable);
        
        System.out.println("Document Type Versioning Status:");
        System.out.println("- Versionable: " + versionable);
        System.out.println("- Content Stream Allowed: " + docTypeDef.getContentStreamAllowed());
        
        // Check property definitions contain versioning properties
        boolean hasVersionLabel = docTypeDef.getPropertyDefinitions().containsKey("cmis:versionLabel");
        boolean hasVersionSeriesId = docTypeDef.getPropertyDefinitions().containsKey("cmis:versionSeriesId");
        boolean hasIsLatestVersion = docTypeDef.getPropertyDefinitions().containsKey("cmis:isLatestVersion");
        boolean hasIsMajorVersion = docTypeDef.getPropertyDefinitions().containsKey("cmis:isMajorVersion");
        boolean hasIsLatestMajorVersion = docTypeDef.getPropertyDefinitions().containsKey("cmis:isLatestMajorVersion");
        
        System.out.println("Versioning Property Definitions:");
        System.out.println("- cmis:versionLabel: " + hasVersionLabel);
        System.out.println("- cmis:versionSeriesId: " + hasVersionSeriesId);
        System.out.println("- cmis:isLatestVersion: " + hasIsLatestVersion);
        System.out.println("- cmis:isMajorVersion: " + hasIsMajorVersion);
        System.out.println("- cmis:isLatestMajorVersion: " + hasIsLatestMajorVersion);
        
        assertTrue("Document type should have cmis:versionLabel property", hasVersionLabel);
        assertTrue("Document type should have cmis:versionSeriesId property", hasVersionSeriesId);
        assertTrue("Document type should have cmis:isLatestVersion property", hasIsLatestVersion);
        assertTrue("Document type should have cmis:isMajorVersion property", hasIsMajorVersion);
        assertTrue("Document type should have cmis:isLatestMajorVersion property", hasIsLatestMajorVersion);
    }
}