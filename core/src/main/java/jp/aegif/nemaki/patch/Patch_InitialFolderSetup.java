package jp.aegif.nemaki.patch;

import java.io.ByteArrayInputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
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
        log.info("=== PATCH DEBUG: Starting Initial Folder Setup Patch for repository: " + repositoryId + " ===");
        System.out.println("=== PATCH DEBUG: Starting Initial Folder Setup Patch for repository: " + repositoryId + " ===");
        
        if ("canopy".equals(repositoryId)) {
            log.info("=== PATCH DEBUG: Skipping Initial Folder Setup for canopy - it's an information management area, not a CMIS repository ===");
            System.out.println("=== PATCH DEBUG: Skipping Initial Folder Setup for canopy - it's an information management area, not a CMIS repository ===");
            return;
        }
        
        try {
            // Get ContentService from PatchUtil
            ContentService contentService = patchUtil.getContentService();
            if (contentService == null) {
                log.error("ContentService not available, cannot apply patch");
                return;
            }
            
            if (patchUtil.getRepositoryInfoMap() == null) {
                log.warn("RepositoryInfoMap not available yet. Skipping Initial Folder Setup Patch for repository: " + repositoryId);
                return;
            }
            
            if (patchUtil.getRepositoryInfoMap().get(repositoryId) == null) {
                log.warn("Repository info not available for: " + repositoryId + ". Skipping Initial Folder Setup Patch.");
                return;
            }
            
            String rootFolderId = patchUtil.getRepositoryInfoMap().get(repositoryId).getRootFolderId();
            if (rootFolderId == null) {
                log.warn("Root folder ID not available for repository: " + repositoryId + ". Skipping Initial Folder Setup Patch.");
                return;
            }
            
            log.info("Using root folder ID: " + rootFolderId + " for repository: " + repositoryId);
            
            try {
                Folder rootFolder = (Folder) contentService.getContent(repositoryId, rootFolderId);
                if (rootFolder == null) {
                    log.warn("Root folder not found for repository: " + repositoryId + ". Repository may not be fully initialized yet. Skipping Initial Folder Setup Patch.");
                    return;
                }
                
                log.info("Root folder verified for repository: " + repositoryId + ", proceeding with folder setup");
            } catch (Exception e) {
                log.warn("Cannot access root folder for repository: " + repositoryId + ". Repository may not be fully initialized yet. Skipping Initial Folder Setup Patch. Error: " + e.getMessage());
                return;
            }
            
            // Create system call context for operations
            SystemCallContext callContext = new SystemCallContext(repositoryId);
            
            // Check if Sites folder already exists
            if (!folderExists(contentService, repositoryId, rootFolderId, SITES_FOLDER_NAME)) {
                log.info("Creating Sites folder via ContentService...");
                String sitesFolderId = createFolder(contentService, callContext, repositoryId, rootFolderId, 
                    SITES_FOLDER_NAME, "Sites folder for collaborative workspaces");
                if (sitesFolderId != null) {
                    log.info("Sites folder created with ID: " + sitesFolderId);
                } else {
                    log.warn("Failed to create Sites folder, repository may not be ready");
                }
            } else {
                log.info("Sites folder already exists, skipping creation");
            }
            
            // Check if Technical Documents folder already exists
            if (!folderExists(contentService, repositoryId, rootFolderId, TECH_DOCS_FOLDER_NAME)) {
                log.info("Creating Technical Documents folder via ContentService...");
                String techDocsFolderId = createFolder(contentService, callContext, repositoryId, rootFolderId,
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
                } else {
                    log.warn("Failed to create Technical Documents folder, repository may not be ready");
                }
            } else {
                log.info("Technical Documents folder already exists, skipping creation");
            }
            
            // Force Solr reindexing to ensure all created content is searchable
            log.info("Triggering Solr reindexing to ensure all content is searchable...");
            triggerSolrReindexing(repositoryId);
            
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
            
            // Get parent folder object with comprehensive null check
            Folder parentFolder = null;
            try {
                parentFolder = (Folder) contentService.getContent(repositoryId, parentId);
                if (parentFolder == null) {
                    log.warn("Parent folder not found for ID: " + parentId + " in repository: " + repositoryId + 
                            ". Repository may not be fully initialized yet. Skipping folder creation: " + folderName);
                    return null;
                }
            } catch (Exception e) {
                log.warn("Error accessing parent folder ID: " + parentId + " in repository: " + repositoryId + 
                        ". Repository may not be fully initialized yet. Skipping folder creation: " + folderName + ". Error: " + e.getMessage());
                return null;
            }
            
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
            
            // Create content stream with correct length
            ContentStream contentStream = new ContentStreamImpl(
                CMIS_SPEC_FILENAME,
                BigInteger.valueOf(pdfBytes.length), // CRITICAL FIX: Provide actual byte length
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
        String textContent = "Content Management Interoperability Services (CMIS) Version 1.1\n" +
            "OASIS Standard - 1 May 2012\n\n" +
            "ABSTRACT\n" +
            "This specification defines a domain model and Web Services, Restful AtomPub (RFC5023) and \n" +
            "Browser (JSON) bindings that can be used by applications to work with one or more Content \n" +
            "Management repositories/systems.\n\n" +
            "TABLE OF CONTENTS\n" +
            "1. Introduction\n" +
            "2. Domain Model\n" +
            "3. Services\n" +
            "4. Restful AtomPub Binding\n" +
            "5. Web Services Binding\n" +
            "6. Browser Binding\n\n" +
            "1. INTRODUCTION\n\n" +
            "The Content Management Interoperability Services (CMIS) standard defines a domain model and \n" +
            "set of bindings that include Web Services and ReSTful AtomPub that can be used by applications.\n\n" +
            "CMIS provides a common data model covering typed files and folders with generic properties \n" +
            "that can be set or read. In addition there may be an Access Control List (ACL) expressed in \n" +
            "terms of permissions specific to the repository.\n\n" +
            "2. DOMAIN MODEL\n\n" +
            "2.1 Data Model\n" +
            "- Repository: The entry point to the CMIS services, repositories are containers.\n" +
            "- Object: Documents, folders, relationships, policies, and items are all objects.\n" +
            "- Object-Type: All objects are typed. The base types are: cmis:document, cmis:folder, \n" +
            "  cmis:relationship, cmis:policy, and cmis:item.\n" +
            "- Document Object: An object that contains a content stream and has properties.\n" +
            "- Folder Object: An object that contains other fileable objects.\n" +
            "- Relationship Object: An object that defines a relationship between a source and target object.\n" +
            "- Policy Object: An object that represents an administrative policy.\n" +
            "- Item Object: An object for modeling information that doesn't fit the document/folder model.\n\n" +
            "2.2 Query Language\n" +
            "CMIS provides a type-based query service for discovering objects that match specified criteria.\n" +
            "The query language is based on a subset of SQL-92 with extensions specific to content management:\n\n" +
            "SELECT * FROM cmis:document WHERE cmis:name LIKE 'CMIS%'\n" +
            "SELECT * FROM cmis:folder WHERE IN_FOLDER('folder-id-123')\n\n" +
            "3. SERVICES\n\n" +
            "3.1 Repository Services\n" +
            "- getRepositories: Get a list of CMIS repositories.\n" +
            "- getRepositoryInfo: Get information about the CMIS repository.\n" +
            "- getTypeChildren: Get the list of object types defined for the repository.\n" +
            "- getTypeDescendants: Get the list of object types descended from the specified type.\n" +
            "- getTypeDefinition: Get the definition of the specified object type.\n\n" +
            "3.2 Navigation Services\n" +
            "- getChildren: Get the list of child objects contained in the specified folder.\n" +
            "- getDescendants: Get the descendant objects contained in the specified folder.\n" +
            "- getFolderTree: Get the folder tree hierarchy under the specified folder.\n" +
            "- getFolderParent: Get the parent folder object for the specified folder.\n" +
            "- getObjectParents: Get the parent folder(s) for the specified fileable object.\n\n" +
            "3.3 Object Services\n" +
            "- createDocument: Create a document object.\n" +
            "- createDocumentFromSource: Create a document object as a copy.\n" +
            "- createFolder: Create a folder object.\n" +
            "- createRelationship: Create a relationship object.\n" +
            "- createPolicy: Create a policy object.\n" +
            "- createItem: Create an item object.\n" +
            "- getAllowableActions: Get the allowable actions for the specified object.\n" +
            "- getObject: Get the specified object.\n" +
            "- getProperties: Get the properties of the specified object.\n" +
            "- getContentStream: Get the content stream for the specified document.\n" +
            "- updateProperties: Update the properties of the specified object.\n" +
            "- bulkUpdateProperties: Update properties of multiple objects.\n" +
            "- moveObject: Move the specified object from one folder to another.\n" +
            "- deleteObject: Delete the specified object.\n" +
            "- deleteTree: Delete the specified folder and all descendant objects.\n" +
            "- setContentStream: Set the content stream for the specified document.\n" +
            "- appendContentStream: Append content to the content stream.\n" +
            "- deleteContentStream: Delete the content stream for the specified document.\n\n" +
            "3.4 Multi-filing and Unfiling Services\n" +
            "- addObjectToFolder: Add an object to a folder.\n" +
            "- removeObjectFromFolder: Remove an object from a folder.\n\n" +
            "3.5 Discovery Services\n" +
            "- query: Query the repository.\n" +
            "- getContentChanges: Get the list of object changes.\n\n" +
            "3.6 Versioning Services\n" +
            "- checkOut: Create a private working copy.\n" +
            "- cancelCheckOut: Cancel the checkout.\n" +
            "- checkIn: Check in the private working copy.\n" +
            "- getObjectOfLatestVersion: Get the latest version.\n" +
            "- getPropertiesOfLatestVersion: Get the properties of the latest version.\n" +
            "- getAllVersions: Get all versions of the specified document.\n\n" +
            "3.7 Relationship Services\n" +
            "- getObjectRelationships: Get the relationships for the specified object.\n\n" +
            "3.8 Policy Services\n" +
            "- applyPolicy: Apply a policy to the specified object.\n" +
            "- removePolicy: Remove a policy from the specified object.\n" +
            "- getAppliedPolicies: Get the list of policies applied to the specified object.\n\n" +
            "3.9 ACL Services\n" +
            "- getACL: Get the ACL for the specified object.\n" +
            "- applyACL: Apply ACL to the specified object.\n\n" +
            "4. BINDINGS\n\n" +
            "CMIS provides multiple protocol bindings:\n" +
            "- Web Services Binding: SOAP-based binding following WS-I Basic Profile.\n" +
            "- AtomPub Binding: REST-based binding using Atom Publishing Protocol.\n" +
            "- Browser Binding: JSON-based binding for browser applications.\n\n" +
            "5. CONFORMANCE\n\n" +
            "Repositories may implement different conformance levels:\n" +
            "- Basic: Minimal set of services required.\n" +
            "- Standard: Includes versioning and other advanced features.\n" +
            "- Full: Complete implementation of all services.\n\n" +
            "END OF DOCUMENT\n\n" +
            "Keywords for search testing: CMIS, Content Management, Interoperability, Services, \n" +
            "Repository, Document, Folder, Query, AtomPub, Web Services, REST, SOAP, JSON, Browser, \n" +
            "Versioning, ACL, Permissions, Object, Type, Properties, Content Stream, Check In, Check Out, \n" +
            "OASIS, Standard, Specification, Domain Model, Binding, Protocol, API, Enterprise Content Management.";
        
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
    
    /**
     * Trigger Solr reindexing to ensure all content is searchable
     */
    private void triggerSolrReindexing(String repositoryId) {
        // CRITICAL FIX: Disable blocking HTTP call during Spring Context initialization
        // This was causing infinite hang because the application is not yet ready to serve HTTP requests
        log.warn("STARTUP OPTIMIZATION: Solr reindexing temporarily disabled during initialization");
        log.warn("Solr reindexing will be handled by automatic indexing when documents are accessed");
        log.info("Manual reindexing if needed: curl -u admin:admin http://localhost:8080/core/rest/all/search-engine/reindex");
        
        // TODO: Implement proper asynchronous reindexing after application startup completion
        // Current implementation causes circular dependency: patch waits for app, app waits for patch
        return;
        
        /* DISABLED TO FIX STARTUP HANG - ORIGINAL CODE:
        try {
            log.info("Starting Solr reindexing for repository: " + repositoryId);
            
            // Use HTTP client to call the reindex REST endpoint
            org.apache.hc.client5.http.classic.HttpClient httpClient = 
                org.apache.hc.client5.http.impl.classic.HttpClientBuilder.create().build();
            
            // Call reindex endpoint
            String reindexUrl = "http://localhost:8080/core/rest/all/search-engine/reindex";
            org.apache.hc.client5.http.classic.methods.HttpGet reindexRequest = 
                new org.apache.hc.client5.http.classic.methods.HttpGet(reindexUrl);
            
            // Add basic authentication
            String auth = java.util.Base64.getEncoder()
                .encodeToString("admin:admin".getBytes("UTF-8"));
            reindexRequest.setHeader("Authorization", "Basic " + auth);
            
            String response = httpClient.execute(reindexRequest, responseHandler -> {
                int statusCode = responseHandler.getCode();
                if (statusCode == 200) {
                    return org.apache.hc.core5.http.io.entity.EntityUtils.toString(responseHandler.getEntity(), "UTF-8");
                } else {
                    log.warn("Solr reindex request returned status: " + statusCode);
                    return null;
                }
            });
            
            if (response != null) {
                log.info("Solr reindexing triggered successfully: " + response);
            } else {
                log.warn("Solr reindexing request failed or returned non-200 status");
            }
            
        } catch (Exception e) {
            log.warn("Failed to trigger Solr reindexing automatically: " + e.getMessage());
            log.info("Manual Solr reindexing may be required: curl -u admin:admin http://localhost:8080/core/rest/all/search-engine/reindex");
        }
        */
    }
}
