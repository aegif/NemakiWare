package jp.aegif.nemaki.patch;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;

import java.util.HashMap;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlEntryImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlPrincipalDataImpl;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Initial Content Setup Patch
 *
 * Creates initial folders and sample documents for new NemakiWare installations:
 * - Sites folder (root level)
 * - Technical Documents folder (root level)
 * - CMIS specification PDF (in Technical Documents)
 *
 * This patch is idempotent - it will not create duplicate folders/documents on restart.
 * If folders already exist from previous versions, they will be preserved.
 *
 * CRITICAL: This patch must execute AFTER Patch_SystemFolderSetup to ensure proper
 * initialization order.
 */
public class Patch_InitialContentSetup extends AbstractNemakiPatch {

    private static final Log log = LogFactory.getLog(Patch_InitialContentSetup.class);

    // Patch configuration
    private static final String PATCH_NAME = "initial-content-setup-20251005";
    private static final String SITES_FOLDER_NAME = "Sites";
    private static final String TECHNICAL_DOCS_FOLDER_NAME = "Technical Documents";

    @Override
    public String getName() {
        return PATCH_NAME;
    }

    @Override
    protected void applySystemPatch() {
        log.info("No system-wide configuration needed for initial content setup");
    }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        log.error("=== INITIAL CONTENT SETUP PATCH STARTED for repository: " + repositoryId + " ===");
        log.info("Starting Initial Content Setup Patch for repository: " + repositoryId);

        if ("canopy".equals(repositoryId)) {
            log.info("Skipping Initial Content Setup for canopy - information management area");
            return;
        }

        if ("bedroom_closet".equals(repositoryId) || "canopy_closet".equals(repositoryId)) {
            log.info("Skipping Initial Content Setup for archive repositories");
            return;
        }

        try {
            ContentService contentService = patchUtil.getContentService();
            if (contentService == null) {
                log.error("ContentService not available, cannot apply Initial Content Setup patch");
                return;
            }

            if (patchUtil.getRepositoryInfoMap() == null) {
                log.warn("RepositoryInfoMap not available yet. Skipping Initial Content Setup for: " + repositoryId);
                return;
            }

            if (patchUtil.getRepositoryInfoMap().get(repositoryId) == null) {
                log.warn("Repository info not available for: " + repositoryId + ". Skipping Initial Content Setup.");
                return;
            }

            String rootFolderId = patchUtil.getRepositoryInfoMap().get(repositoryId).getRootFolderId();
            if (rootFolderId == null) {
                log.warn("Root folder ID not available for repository: " + repositoryId + ". Skipping Initial Content Setup.");
                return;
            }

            log.info("Using root folder ID: " + rootFolderId + " for repository: " + repositoryId);

            // Verify root folder exists
            try {
                Folder rootFolder = (Folder) contentService.getContent(repositoryId, rootFolderId);
                if (rootFolder == null) {
                    log.warn("Root folder not found for repository: " + repositoryId + ". Repository may not be fully initialized yet.");
                    return;
                }

                log.info("Root folder verified for repository: " + repositoryId + ", proceeding with initial content setup");
            } catch (Exception e) {
                log.warn("Cannot access root folder for repository: " + repositoryId + ". Repository may not be fully initialized yet. Error: " + e.getMessage());
                return;
            }

            // Create SystemCallContext for operations
            SystemCallContext callContext = new SystemCallContext(repositoryId);

            // Create Sites folder if it doesn't exist
            log.error("=== CREATING SITES FOLDER ===");
            String sitesFolderId = createFolderIfNotExists(contentService, callContext, repositoryId, rootFolderId, SITES_FOLDER_NAME);
            log.error("=== SITES FOLDER RESULT: " + (sitesFolderId != null ? "SUCCESS (ID: " + sitesFolderId + ")" : "FAILED") + " ===");

            // Create Technical Documents folder if it doesn't exist
            log.error("=== CREATING TECHNICAL DOCUMENTS FOLDER ===");
            String technicalDocsFolderId = createFolderIfNotExists(contentService, callContext, repositoryId, rootFolderId, TECHNICAL_DOCS_FOLDER_NAME);
            log.error("=== TECHNICAL DOCUMENTS FOLDER RESULT: " + (technicalDocsFolderId != null ? "SUCCESS (ID: " + technicalDocsFolderId + ")" : "FAILED") + " ===");

            // Register CMIS specification PDF if Technical Documents folder was created
            if (technicalDocsFolderId != null) {
                registerCMISSpecificationPDF(contentService, callContext, repositoryId, technicalDocsFolderId);
            }

            log.error("=== INITIAL CONTENT SETUP PATCH COMPLETED SUCCESSFULLY for repository: " + repositoryId + " ===");

        } catch (Exception e) {
            log.error("=== ERROR DURING INITIAL CONTENT SETUP PATCH for repository: " + repositoryId + " ===", e);
            // Don't throw - patch failures should not prevent application startup
        }
    }

    /**
     * Create folder if it doesn't already exist
     *
     * @return Folder ID if created or already exists, null if creation failed
     */
    private String createFolderIfNotExists(ContentService contentService, SystemCallContext callContext,
                                           String repositoryId, String parentFolderId, String folderName) {
        try {
            log.error("Checking if folder '" + folderName + "' already exists...");
            // Check if folder already exists
            Folder existingFolder = findExistingFolderByName(repositoryId, parentFolderId, folderName);

            if (existingFolder != null) {
                log.error("Folder '" + folderName + "' already exists with ID: " + existingFolder.getId() + " (preserving from previous version)");
                return existingFolder.getId();
            }

            // Folder doesn't exist, create it
            log.error("Folder '" + folderName + "' does not exist, creating new folder in repository: " + repositoryId);

            // Prepare CMIS properties
            PropertiesImpl properties = new PropertiesImpl();

            // cmis:objectTypeId = cmis:folder
            PropertyIdImpl objectTypeId = new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, "cmis:folder");
            properties.addProperty(objectTypeId);

            // cmis:name = folder name
            PropertyStringImpl name = new PropertyStringImpl(PropertyIds.NAME, folderName);
            properties.addProperty(name);

            // Get parent folder
            Folder parentFolder = (Folder) contentService.getContent(repositoryId, parentFolderId);
            if (parentFolder == null) {
                log.error("Parent folder not found with ID: " + parentFolderId);
                return null;
            }

            // Create ACL for new folder (grant admin:all and GROUP_EVERYONE:read)
            org.apache.chemistry.opencmis.commons.data.Acl acl = createDefaultFolderAcl();

            // Create folder through ContentService
            log.error("Calling contentService.createFolder() for: " + folderName);
            Folder created = contentService.createFolder(callContext, repositoryId, properties,
                                                        parentFolder, null, acl, null, null);

            log.error("SUCCESS: Folder '" + folderName + "' created successfully with ID: " + created.getId());
            return created.getId();

        } catch (Exception e) {
            log.error("FAILED to create folder: " + folderName, e);
            return null;
        }
    }

    /**
     * Find existing folder by name using direct CouchDB query
     */
    private Folder findExistingFolderByName(String repositoryId, String parentFolderId, String folderName) {
        try {
            // Get CloudantClientWrapper directly from patch util
            jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper client = patchUtil.getConnectorPool().getClient(repositoryId);
            if (client == null) {
                log.error("Could not get Cloudant client for repository: " + repositoryId);
                return null;
            }

            // Query children view with parent ID
            Map<String, Object> queryParams = new HashMap<>();
            queryParams.put("key", parentFolderId);
            queryParams.put("include_docs", true);

            com.ibm.cloud.cloudant.v1.model.ViewResult result = client.queryView("_repo", "children", queryParams);

            if (result.getRows() != null && !result.getRows().isEmpty()) {
                for (com.ibm.cloud.cloudant.v1.model.ViewResultRow row : result.getRows()) {
                    if (row.getDoc() != null) {
                        com.ibm.cloud.cloudant.v1.model.Document doc = row.getDoc();
                        Map<String, Object> docProperties = doc.getProperties();

                        if (docProperties != null) {
                            String name = (String) docProperties.get("name");
                            String type = (String) docProperties.get("type");

                            // Check if this is a folder with the target name
                            if ("cmis:folder".equals(type) && folderName.equals(name)) {
                                log.info("Found existing folder: " + folderName + " with ID: " + row.getId());

                                // Convert to Folder object
                                ContentService contentService = patchUtil.getContentService();
                                Content content = contentService.getContent(repositoryId, row.getId());

                                if (content instanceof Folder) {
                                    return (Folder) content;
                                }
                            }
                        }
                    }
                }
            }

            return null;

        } catch (Exception e) {
            log.warn("Error checking for existing folder: " + folderName, e);
            return null;
        }
    }

    /**
     * Create default ACL for folders (admin:all and GROUP_EVERYONE:read)
     * This matches the ACL structure of the repository root folder
     */
    private org.apache.chemistry.opencmis.commons.data.Acl createDefaultFolderAcl() {
        AccessControlListImpl acl = new AccessControlListImpl();
        java.util.List<org.apache.chemistry.opencmis.commons.data.Ace> aces = new ArrayList<>();

        // Add admin principal with cmis:all permission
        AccessControlPrincipalDataImpl adminPrincipal = new AccessControlPrincipalDataImpl("admin");
        AccessControlEntryImpl adminAce = new AccessControlEntryImpl(adminPrincipal, Arrays.asList("cmis:all"));
        aces.add(adminAce);

        // Add GROUP_EVERYONE principal with cmis:read permission
        AccessControlPrincipalDataImpl everyonePrincipal = new AccessControlPrincipalDataImpl("GROUP_EVERYONE");
        AccessControlEntryImpl everyoneAce = new AccessControlEntryImpl(everyonePrincipal, Arrays.asList("cmis:read"));
        aces.add(everyoneAce);

        acl.setAces(aces);
        return acl;
    }

    /**
     * Register welcome document in Technical Documents folder
     * Creates a simple welcome text document for new installations
     */
    private void registerCMISSpecificationPDF(ContentService contentService, SystemCallContext callContext,
                                             String repositoryId, String parentFolderId) {
        try {
            final String documentName = "Welcome to NemakiWare.txt";

            log.info("Checking for welcome document in Technical Documents folder: " + repositoryId);

            // Check if document already exists
            Document existingDoc = findExistingDocumentByName(repositoryId, parentFolderId, documentName);
            if (existingDoc != null) {
                log.info("Welcome document already exists, skipping registration");
                return;
            }

            // Create welcome document with content
            String welcomeContent = "Welcome to NemakiWare - Open Source CMIS Repository\n" +
                    "================================================\n\n" +
                    "NemakiWare is a CMIS 1.1 compliant content management system.\n\n" +
                    "Features:\n" +
                    "- CMIS 1.1 full compliance (AtomPub, Browser Binding, Web Services)\n" +
                    "- Document versioning and workflow\n" +
                    "- Full-text search with Apache Solr\n" +
                    "- Access control and permissions management\n" +
                    "- React-based modern UI\n\n" +
                    "For more information, visit: https://github.com/aegif/NemakiWare\n\n" +
                    "This document was automatically created during system initialization.\n";

            createDocumentWithContent(contentService, callContext, repositoryId, parentFolderId,
                                    documentName, "text/plain", welcomeContent);

            log.info("✅ Welcome document created successfully in Technical Documents folder");

        } catch (Exception e) {
            log.error("Error registering welcome document", e);
        }
    }

    /**
     * Find existing document by name using CouchDB children view
     */
    private Document findExistingDocumentByName(String repositoryId, String parentFolderId, String documentName) {
        try {
            CloudantClientWrapper client = patchUtil.getConnectorPool().getClient(repositoryId);
            if (client == null) {
                log.error("Could not get Cloudant client for repository: " + repositoryId);
                return null;
            }

            Map<String, Object> queryParams = new HashMap<>();
            queryParams.put("key", parentFolderId);
            queryParams.put("include_docs", true);

            com.ibm.cloud.cloudant.v1.model.ViewResult result = client.queryView("_repo", "children", queryParams);

            if (result.getRows() != null) {
                for (com.ibm.cloud.cloudant.v1.model.ViewResultRow row : result.getRows()) {
                    if (row.getDoc() != null) {
                        com.ibm.cloud.cloudant.v1.model.Document doc = row.getDoc();
                        Map<String, Object> docProperties = doc.getProperties();

                        if (docProperties != null) {
                            String name = (String) docProperties.get("name");
                            String type = (String) docProperties.get("type");

                            if ("cmis:document".equals(type) && documentName.equals(name)) {
                                log.info("Found existing document: " + documentName + " with ID: " + row.getId());
                                ContentService contentService = patchUtil.getContentService();
                                Content content = contentService.getContent(repositoryId, row.getId());
                                if (content instanceof Document) {
                                    return (Document) content;
                                }
                            }
                        }
                    }
                }
            }

            return null;

        } catch (Exception e) {
            log.warn("Error checking for existing document: " + documentName, e);
            return null;
        }
    }

    /**
     * Create document with text content
     */
    private void createDocumentWithContent(ContentService contentService, SystemCallContext callContext,
                                          String repositoryId, String parentFolderId,
                                          String documentName, String mimeType, String textContent) {
        try {
            log.info("Creating document: " + documentName + " in folder: " + parentFolderId);

            // Prepare CMIS properties
            PropertiesImpl properties = new PropertiesImpl();
            PropertyIdImpl objectTypeId = new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, "cmis:document");
            properties.addProperty(objectTypeId);
            PropertyStringImpl name = new PropertyStringImpl(PropertyIds.NAME, documentName);
            properties.addProperty(name);

            // Get parent folder
            Folder parentFolder = (Folder) contentService.getContent(repositoryId, parentFolderId);
            if (parentFolder == null) {
                log.error("Parent folder not found with ID: " + parentFolderId);
                return;
            }

            // Create content stream from text
            byte[] contentBytes = textContent.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.io.ByteArrayInputStream contentStream = new java.io.ByteArrayInputStream(contentBytes);

            org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl cmisContentStream =
                new org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl(
                    documentName,
                    java.math.BigInteger.valueOf(contentBytes.length),
                    mimeType,
                    contentStream
                );

            // Create ACL for document (same as folders: admin:all, GROUP_EVERYONE:read, system:all)
            org.apache.chemistry.opencmis.commons.data.Acl acl = createDefaultFolderAcl();

            // Create document with content
            // Parameters: callContext, repositoryId, properties, parentFolder, contentStream,
            //             versioningState, policies, addAces, removeAces
            Document created = contentService.createDocument(callContext, repositoryId, properties,
                                                           parentFolder, cmisContentStream,
                                                           null,  // versioningState
                                                           null,  // policies
                                                           acl,   // addAces
                                                           null); // removeAces

            log.info("✅ Document '" + documentName + "' created successfully with ID: " + created.getId());

        } catch (Exception e) {
            log.error("Failed to create document: " + documentName, e);
        }
    }
}
