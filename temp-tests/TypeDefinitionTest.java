package jp.aegif.nemaki.test;

import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = {
    "classpath:applicationContext.xml",
    "classpath:serviceContext.xml",
    "classpath:businesslogicContext.xml",
    "classpath:daoContext.xml"
})
public class TypeDefinitionTest {

    @Autowired
    private TypeManager typeManager;

    @Test
    public void testBasicTypeDefinitions() {
        String repositoryId = "bedroom";
        
        // Test cmis:document type
        TypeDefinition documentType = typeManager.getTypeDefinition(repositoryId, BaseTypeId.CMIS_DOCUMENT.value());
        assertNotNull("cmis:document type should be available", documentType);
        assertEquals("Document base type should be CMIS_DOCUMENT", BaseTypeId.CMIS_DOCUMENT, documentType.getBaseTypeId());
        System.out.println("Document Type: " + documentType.getId() + " - " + documentType.getDisplayName());
        
        // Test cmis:folder type
        TypeDefinition folderType = typeManager.getTypeDefinition(repositoryId, BaseTypeId.CMIS_FOLDER.value());
        assertNotNull("cmis:folder type should be available", folderType);
        assertEquals("Folder base type should be CMIS_FOLDER", BaseTypeId.CMIS_FOLDER, folderType.getBaseTypeId());
        System.out.println("Folder Type: " + folderType.getId() + " - " + folderType.getDisplayName());
        
        // Test cmis:relationship type
        try {
            TypeDefinition relationshipType = typeManager.getTypeDefinition(repositoryId, BaseTypeId.CMIS_RELATIONSHIP.value());
            if (relationshipType != null) {
                assertEquals("Relationship base type should be CMIS_RELATIONSHIP", BaseTypeId.CMIS_RELATIONSHIP, relationshipType.getBaseTypeId());
                System.out.println("Relationship Type: " + relationshipType.getId() + " - " + relationshipType.getDisplayName());
            }
        } catch (Exception e) {
            System.out.println("Relationship type not available: " + e.getMessage());
        }
        
        // Test cmis:policy type
        try {
            TypeDefinition policyType = typeManager.getTypeDefinition(repositoryId, BaseTypeId.CMIS_POLICY.value());
            if (policyType != null) {
                assertEquals("Policy base type should be CMIS_POLICY", BaseTypeId.CMIS_POLICY, policyType.getBaseTypeId());
                System.out.println("Policy Type: " + policyType.getId() + " - " + policyType.getDisplayName());
            }
        } catch (Exception e) {
            System.out.println("Policy type not available: " + e.getMessage());
        }
        
        // Test cmis:item type (CMIS 1.1)
        try {
            TypeDefinition itemType = typeManager.getTypeDefinition(repositoryId, BaseTypeId.CMIS_ITEM.value());
            if (itemType != null) {
                assertEquals("Item base type should be CMIS_ITEM", BaseTypeId.CMIS_ITEM, itemType.getBaseTypeId());
                System.out.println("Item Type: " + itemType.getId() + " - " + itemType.getDisplayName());
            }
        } catch (Exception e) {
            System.out.println("Item type not available: " + e.getMessage());
        }
        
        // Test cmis:secondary type (CMIS 1.1)
        try {
            TypeDefinition secondaryType = typeManager.getTypeDefinition(repositoryId, BaseTypeId.CMIS_SECONDARY.value());
            if (secondaryType != null) {
                assertEquals("Secondary base type should be CMIS_SECONDARY", BaseTypeId.CMIS_SECONDARY, secondaryType.getBaseTypeId());
                System.out.println("Secondary Type: " + secondaryType.getId() + " - " + secondaryType.getDisplayName());
            }
        } catch (Exception e) {
            System.out.println("Secondary type not available: " + e.getMessage());
        }
    }
}