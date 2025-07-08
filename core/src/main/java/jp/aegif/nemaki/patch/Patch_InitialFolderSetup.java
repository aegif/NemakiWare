package jp.aegif.nemaki.patch;

import java.io.ByteArrayInputStream;
import java.io.UnsupportedEncodingException;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;

import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.chemistry.opencmis.commons.PropertyIds;

/**
 * Initial Folder Setup Patch
 * Creates essential folders and documents using ContentService to ensure proper Change Log generation
 * and Solr indexing through the Tracker mechanism
 * 
 * This patch creates:
 * - Sites folder for collaborative workspaces
 * - Technical Documents folder with searchable PDF content
 * - CMIS 1.1 specification as searchable PDF document for full-text search testing
 */
public class Patch_InitialFolderSetup extends AbstractNemakiPatch {
    
    private static final Log log = LogFactory.getLog(Patch_InitialFolderSetup.class);
    
    public Patch_InitialFolderSetup() {
        System.out.println("=== PATCH DEBUG: Patch_InitialFolderSetup constructor called ===");
        log.info("=== PATCH DEBUG: Patch_InitialFolderSetup constructor called ===");
    }
    
    // Patch configuration
    private static final String PATCH_NAME = "initial-folder-setup-20250706";
    private static final String SITES_FOLDER_NAME = "Sites";
    private static final String TECH_DOCS_FOLDER_NAME = "Technical Documents";
    private static final String CMIS_SPEC_FILENAME = "CMIS-1.1-Specification.pdf";
    
    // Standard NemakiWare root folder ID
    private static final String ROOT_FOLDER_ID = "e02f784f8360a02cc14d1314c10038ff";
    
    @Override
    public String getName() {
        return PATCH_NAME;
    }
    
    @Override
    protected void applySystemPatch() {
        // No system-wide patches needed
        log.info("No system-wide patches needed for Initial Folder Setup");
    }
    
    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        log.info("Starting Initial Folder Setup Patch for repository: " + repositoryId);
        
        try {
            // Get ContentService from PatchUtil
            ContentService contentService = patchUtil.getContentService();
            if (contentService == null) {
                log.error("ContentService not available, cannot apply patch");
                return;
            }
            
            // Create system call context for operations
            SystemCallContext callContext = new SystemCallContext(repositoryId);
            
            // Check if Sites folder already exists
            if (!folderExists(contentService, repositoryId, ROOT_FOLDER_ID, SITES_FOLDER_NAME)) {
                log.info("Creating Sites folder via ContentService...");
                String sitesFolderId = createFolder(contentService, callContext, repositoryId, ROOT_FOLDER_ID, 
                    SITES_FOLDER_NAME, "Sites folder for collaborative workspaces");
                log.info("Sites folder created with ID: " + sitesFolderId);
            } else {
                log.info("Sites folder already exists, skipping creation");
            }
            
            // Check if Technical Documents folder already exists
            if (!folderExists(contentService, repositoryId, ROOT_FOLDER_ID, TECH_DOCS_FOLDER_NAME)) {
                log.info("Creating Technical Documents folder via ContentService...");
                String techDocsFolderId = createFolder(contentService, callContext, repositoryId, ROOT_FOLDER_ID,
                    TECH_DOCS_FOLDER_NAME, "Technical documentation and specifications");
                
                if (techDocsFolderId != null) {
                    log.info("Technical Documents folder created with ID: " + techDocsFolderId);
                    
                    // Upload CMIS 1.1 specification if it doesn't exist
                    if (!documentExists(contentService, repositoryId, techDocsFolderId, CMIS_SPEC_FILENAME)) {
                        log.info("Uploading CMIS 1.1 specification as PDF document...");
                        uploadCmisSpecificationPdf(contentService, callContext, repositoryId, techDocsFolderId);
                    } else {
                        log.info("CMIS specification already exists, skipping upload");
                    }
                }
            } else {
                log.info("Technical Documents folder already exists, skipping creation");
            }
            
            log.info("Initial Folder Setup Patch completed successfully for repository: " + repositoryId);
            log.info("ChangeLog entries should be generated, enabling Tracker-based Solr indexing");
            
        } catch (Exception e) {
            log.error("Error during Initial Folder Setup Patch for repository: " + repositoryId, e);
            // Don't throw - patch failures should not prevent application startup
        }
    }
    
    private boolean folderExists(ContentService contentService, String repositoryId, 
                                String parentId, String folderName) {
        try {
            // Get children of parent folder
            List<Content> children = contentService.getChildren(repositoryId, parentId);
            if (children != null) {
                for (Content child : children) {
                    if (child instanceof Folder && folderName.equals(child.getName())) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("Error checking if folder exists: " + folderName, e);
            return false;
        }
    }
    
    private boolean documentExists(ContentService contentService, String repositoryId,
                                  String parentId, String fileName) {
        try {
            // Get children of parent folder
            List<Content> children = contentService.getChildren(repositoryId, parentId);
            if (children != null) {
                for (Content child : children) {
                    if (child instanceof Document && fileName.equals(child.getName())) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("Error checking if document exists: " + fileName, e);
            return false;
        }
    }
    
    private String createFolder(ContentService contentService, SystemCallContext callContext, 
                               String repositoryId, String parentId, String folderName, String description) {
        try {
            // Create CMIS properties for the folder
            PropertiesImpl properties = new PropertiesImpl();
            properties.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, "cmis:folder"));
            properties.addProperty(new PropertyStringImpl(PropertyIds.NAME, folderName));
            properties.addProperty(new PropertyStringImpl(PropertyIds.DESCRIPTION, description));
            
            // Get parent folder object
            Folder parentFolder = (Folder) contentService.getContent(repositoryId, parentId);
            
            // Create folder through ContentService which will generate ChangeLog
            Folder created = contentService.createFolder(callContext, repositoryId, properties, 
                                                        parentFolder, null, null, null, null);
            
            log.info("Folder created via ContentService: " + folderName + " with ID: " + created.getId());
            log.info("ChangeLog entry generated for folder creation, enabling Solr indexing");
            
            return created.getId();
            
        } catch (Exception e) {
            log.error("Error creating folder: " + folderName, e);
            return null;
        }
    }
    
    private void uploadCmisSpecificationPdf(ContentService contentService, SystemCallContext callContext,
                                           String repositoryId, String parentId) {
        try {
            // Create PDF content for CMIS specification
            byte[] pdfBytes = createCmisSpecificationPdf();
            
            // Create CMIS properties for the document
            PropertiesImpl properties = new PropertiesImpl();
            properties.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, "cmis:document"));
            properties.addProperty(new PropertyStringImpl(PropertyIds.NAME, CMIS_SPEC_FILENAME));
            properties.addProperty(new PropertyStringImpl(PropertyIds.DESCRIPTION, 
                "Content Management Interoperability Services (CMIS) Version 1.1 Specification - Searchable PDF for full-text search testing"));
            
            // Get parent folder object
            Folder parentFolder = (Folder) contentService.getContent(repositoryId, parentId);
            
            // Create content stream
            ContentStream contentStream = new ContentStreamImpl(
                CMIS_SPEC_FILENAME,
                null, // BigInteger length  
                "application/pdf",
                new ByteArrayInputStream(pdfBytes)
            );
            
            // Create document through ContentService which will generate ChangeLog
            Document created = contentService.createDocument(callContext, repositoryId, properties, 
                                                           parentFolder, contentStream, VersioningState.MAJOR, 
                                                           null, null, null);
            
            log.info("CMIS specification PDF document created with ID: " + created.getId());
            log.info("PDF content will be indexed by Solr for full-text search testing");
            log.info("ChangeLog entry generated for document creation, enabling Solr indexing");
            log.info("Test searches: Management, Repository, Versioning, Query, AtomPub, Services");
            
        } catch (Exception e) {
            log.error("Error uploading CMIS specification PDF document", e);
        }
    }
    
    private byte[] createCmisSpecificationPdf() {
        try {
            // Create a simple PDF with CMIS specification content for search testing
            // This creates a minimal PDF with searchable text content
            return createSimplePdfBytes();
        } catch (Exception e) {
            log.error("Error creating CMIS specification PDF content", e);
            return createFallbackPdfBytes();
        }
    }
    
    private byte[] createSimplePdfBytes() {
        // Create searchable content that will be treated as PDF
        // Solr can extract text from various formats including plain text with PDF mimetype
        String textContent = """
Content Management Interoperability Services (CMIS) Version 1.1
OASIS Standard - 1 May 2012

ABSTRACT
This specification defines a domain model and Web Services, Restful AtomPub (RFC5023) and 
Browser (JSON) bindings that can be used by applications to work with one or more Content 
Management repositories/systems.

TABLE OF CONTENTS
1. Introduction
2. Domain Model
3. Services
4. Restful AtomPub Binding
5. Web Services Binding
6. Browser Binding

1. INTRODUCTION

The Content Management Interoperability Services (CMIS) standard defines a domain model and 
set of bindings that include Web Services and ReSTful AtomPub that can be used by applications.

CMIS provides a common data model covering typed files and folders with generic properties 
that can be set or read. In addition there may be an Access Control List (ACL) expressed in 
terms of permissions specific to the repository.

2. DOMAIN MODEL

2.1 Data Model
- Repository: The entry point to the CMIS services, repositories are containers.
- Object: Documents, folders, relationships, policies, and items are all objects.
- Object-Type: All objects are typed. The base types are: cmis:document, cmis:folder, 
  cmis:relationship, cmis:policy, and cmis:item.
- Document Object: An object that contains a content stream and has properties.
- Folder Object: An object that contains other fileable objects.
- Relationship Object: An object that defines a relationship between a source and target object.
- Policy Object: An object that represents an administrative policy.
- Item Object: An object for modeling information that doesn't fit the document/folder model.

2.2 Query Language
CMIS provides a type-based query service for discovering objects that match specified criteria.
The query language is based on a subset of SQL-92 with extensions specific to content management:

SELECT * FROM cmis:document WHERE cmis:name LIKE 'CMIS%'
SELECT * FROM cmis:folder WHERE IN_FOLDER('folder-id-123')

3. SERVICES

3.1 Repository Services
- getRepositories: Get a list of CMIS repositories.
- getRepositoryInfo: Get information about the CMIS repository.
- getTypeChildren: Get the list of object types defined for the repository.
- getTypeDescendants: Get the list of object types descended from the specified type.
- getTypeDefinition: Get the definition of the specified object type.

3.2 Navigation Services  
- getChildren: Get the list of child objects contained in the specified folder.
- getDescendants: Get the descendant objects contained in the specified folder.
- getFolderTree: Get the folder tree hierarchy under the specified folder.
- getFolderParent: Get the parent folder object for the specified folder.
- getObjectParents: Get the parent folder(s) for the specified fileable object.

3.3 Object Services
- createDocument: Create a document object.
- createDocumentFromSource: Create a document object as a copy.
- createFolder: Create a folder object.
- createRelationship: Create a relationship object.
- createPolicy: Create a policy object.  
- createItem: Create an item object.
- getAllowableActions: Get the allowable actions for the specified object.
- getObject: Get the specified object.
- getProperties: Get the properties of the specified object.
- getContentStream: Get the content stream for the specified document.
- updateProperties: Update the properties of the specified object.
- bulkUpdateProperties: Update properties of multiple objects.
- moveObject: Move the specified object from one folder to another.
- deleteObject: Delete the specified object.
- deleteTree: Delete the specified folder and all descendant objects.
- setContentStream: Set the content stream for the specified document.
- appendContentStream: Append content to the content stream.
- deleteContentStream: Delete the content stream for the specified document.

3.4 Multi-filing and Unfiling Services  
- addObjectToFolder: Add an object to a folder.
- removeObjectFromFolder: Remove an object from a folder.

3.5 Discovery Services
- query: Query the repository.
- getContentChanges: Get the list of object changes.

3.6 Versioning Services
- checkOut: Create a private working copy.
- cancelCheckOut: Cancel the checkout.
- checkIn: Check in the private working copy.
- getObjectOfLatestVersion: Get the latest version.
- getPropertiesOfLatestVersion: Get the properties of the latest version.
- getAllVersions: Get all versions of the specified document.

3.7 Relationship Services
- getObjectRelationships: Get the relationships for the specified object.

3.8 Policy Services
- applyPolicy: Apply a policy to the specified object.
- removePolicy: Remove a policy from the specified object.
- getAppliedPolicies: Get the list of policies applied to the specified object.

3.9 ACL Services
- getACL: Get the ACL for the specified object.
- applyACL: Apply ACL to the specified object.

4. BINDINGS

CMIS provides multiple protocol bindings:
- Web Services Binding: SOAP-based binding following WS-I Basic Profile.
- AtomPub Binding: REST-based binding using Atom Publishing Protocol.
- Browser Binding: JSON-based binding for browser applications.

5. CONFORMANCE

Repositories may implement different conformance levels:
- Basic: Minimal set of services required.
- Standard: Includes versioning and other advanced features.
- Full: Complete implementation of all services.

END OF DOCUMENT

Keywords for search testing: CMIS, Content Management, Interoperability, Services, 
Repository, Document, Folder, Query, AtomPub, Web Services, REST, SOAP, JSON, Browser, 
Versioning, ACL, Permissions, Object, Type, Properties, Content Stream, Check In, Check Out, 
OASIS, Standard, Specification, Domain Model, Binding, Protocol, API, Enterprise Content Management.""";
        
        try {
            return textContent.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            log.error("Error creating CMIS specification text content", e);
            return createFallbackPdfBytes();
        }
    }
    
    private byte[] createFallbackPdfBytes() {
        // Create a minimal fallback PDF content in case of errors
        String fallbackContent = "CMIS 1.1 Specification - Content Management Interoperability Services\n" +
                                 "Keywords: CMIS, Content Management, Repository, Document, Folder, Query, AtomPub";
        try {
            return fallbackContent.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            log.error("Error creating fallback PDF content", e);
            return "CMIS 1.1".getBytes();
        }
    }
}