package jp.aegif.nemaki.patch;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.util.PropertyManager;

import java.util.List;

import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.chemistry.opencmis.commons.PropertyIds;

/**
 * System Folder Setup Patch
 * 
 * Creates the essential System folder for each repository and ensures proper 
 * configuration in nemaki_conf database. This patch is critical for proper
 * REST API functionality, particularly Group and User management endpoints.
 * 
 * The System folder serves as a container for:
 * - User management objects
 * - Group management objects  
 * - System configuration data
 * - Internal application structures
 * 
 * This patch is idempotent - it will not create duplicate folders on restart.
 */
public class Patch_SystemFolderSetup extends AbstractNemakiPatch {
    
    private static final Log log = LogFactory.getLog(Patch_SystemFolderSetup.class);
    
    // Patch configuration
    private static final String PATCH_NAME = "system-folder-setup-20250805";
    private static final String SYSTEM_FOLDER_NAME = ".system"; // SECURITY: Use .system with system-only access
    
    @Override
    public String getName() {
        return PATCH_NAME;
    }

    @Override
    protected void applySystemPatch() {
        log.info("Creating system configuration entries in nemaki_conf database");
        
        try {
            // Create system version configuration if it doesn't exist
            createSystemConfigurationEntry("system.version", "2025.08.05", 
                "NemakiWare system version identifier");
                
        } catch (Exception e) {
            log.error("Error creating system configuration entries", e);
        }
    }
    
    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        log.info("Starting System Folder Setup Patch for repository: " + repositoryId);
        
        try {
            ContentService contentService = patchUtil.getContentService();
            if (contentService == null) {
                log.error("ContentService not available, cannot apply System Folder patch");
                return;
            }
            
            if (patchUtil.getRepositoryInfoMap() == null) {
                log.warn("RepositoryInfoMap not available yet. Skipping System Folder Setup for: " + repositoryId);
                return;
            }
            
            if (patchUtil.getRepositoryInfoMap().get(repositoryId) == null) {
                log.warn("Repository info not available for: " + repositoryId + ". Skipping System Folder Setup.");
                return;
            }
            
            String rootFolderId = patchUtil.getRepositoryInfoMap().get(repositoryId).getRootFolderId();
            if (rootFolderId == null) {
                log.warn("Root folder ID not available for repository: " + repositoryId + ". Skipping System Folder Setup.");
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
                
                log.info("Root folder verified for repository: " + repositoryId + ", proceeding with System folder setup");
            } catch (Exception e) {
                log.warn("Cannot access root folder for repository: " + repositoryId + ". Repository may not be fully initialized yet. Error: " + e.getMessage());
                return;
            }
            
            // Create SystemCallContext for operations
            SystemCallContext callContext = new SystemCallContext(repositoryId);
            
            // Check if System folder already exists
            Folder existingSystemFolder = findExistingSystemFolder(contentService, repositoryId, rootFolderId);
            
            if (existingSystemFolder == null) {
                log.info("Creating System folder for repository: " + repositoryId);
                String systemFolderId = createSystemFolder(contentService, callContext, repositoryId, rootFolderId);
                
                if (systemFolderId != null) {
                    log.info("System folder created with ID: " + systemFolderId);
                    
                    // Set systemFolder configuration in nemaki_conf
                    setSystemFolderConfiguration(repositoryId, systemFolderId);
                    
                } else {
                    log.warn("Failed to create System folder for repository: " + repositoryId);
                }
            } else {
                log.info("System folder already exists with ID: " + existingSystemFolder.getId());
                
                // Ensure configuration is set even if folder exists
                setSystemFolderConfiguration(repositoryId, existingSystemFolder.getId());
            }
            
            log.info("System Folder Setup Patch completed successfully for repository: " + repositoryId);
            
        } catch (Exception e) {
            log.error("Error during System Folder Setup Patch for repository: " + repositoryId, e);
            // Don't throw - patch failures should not prevent application startup
        }
    }
    
    /**
     * Find existing System folder in the root directory
     * This prevents duplicate creation and handles the case where multiple System folders exist.
     * 
     * CRITICAL: The .system folder is provided by bedroom_init.dump and should be recognized
     * to prevent duplicate "System" folder creation.
     */
    private Folder findExistingSystemFolder(ContentService contentService, String repositoryId, String rootFolderId) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("Checking for existing system folders in repository " + repositoryId + ", root folder " + rootFolderId);
            }
            
            // CRITICAL FIX: Use direct CouchDB query instead of ContentService/ContentDaoService
            // Both service layers fail during initialization phase due to getContent() dependencies
            try {
                if (log.isDebugEnabled()) {
                    log.debug("Using direct CouchDB view query approach");
                }
                
                // Get CloudantClientWrapper directly from patch util
                jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper client = patchUtil.getConnectorPool().getClient(repositoryId);
                if (client == null) {
                    log.error("Could not get Cloudant client for repository: " + repositoryId);
                    return null;
                }
                
                // Query children view with parent ID
                // CRITICAL: Must set reduce=false because children view has _count reduce
                java.util.Map<String, Object> queryParams = new java.util.HashMap<>();
                queryParams.put("key", rootFolderId);
                queryParams.put("include_docs", true);
                queryParams.put("reduce", false);
                
                if (log.isDebugEnabled()) {
                    log.debug("Executing direct CouchDB view query: _repo/children with key=" + rootFolderId);
                }
                
                com.ibm.cloud.cloudant.v1.model.ViewResult result = client.queryView("_repo", "children", queryParams);
                
                if (result.getRows() != null && !result.getRows().isEmpty()) {
                    if (log.isDebugEnabled()) {
                        log.debug("Direct CouchDB query found " + result.getRows().size() + " raw rows");
                    }
                    
                    for (com.ibm.cloud.cloudant.v1.model.ViewResultRow row : result.getRows()) {
                        if (row.getDoc() != null) {
                            try {
                                // CRITICAL FIX: Extract document ID correctly from ViewResultRow
                                // Primary method: Get document ID from row itself
                                String objectId = row.getId();
                                if (log.isDebugEnabled()) {
                                    log.debug("Row ID (primary method): " + objectId);
                                }
                                
                                // Extract document properties for additional information
                                com.ibm.cloud.cloudant.v1.model.Document doc = row.getDoc();
                                if (doc != null) {
                                    // Fallback method: Try document properties if row ID is null
                                    if (objectId == null) {
                                        java.util.Map<String, Object> docProperties = doc.getProperties();
                                        if (docProperties != null) {
                                            objectId = (String) docProperties.get("_id");
                                            if (log.isDebugEnabled()) {
                                                log.debug("Document properties _id (fallback method): " + objectId);
                                            }
                                        }
                                    }
                                    
                                    // Additional fallback: Try document getId() method
                                    if (objectId == null) {
                                        objectId = doc.getId();
                                        if (log.isDebugEnabled()) {
                                            log.debug("Document getId() (additional fallback): " + objectId);
                                        }
                                    }
                                    
                                    // Get other document properties for validation
                                    java.util.Map<String, Object> docProperties = doc.getProperties();
                                    if (docProperties != null) {
                                        String name = (String) docProperties.get("name");
                                        String type = (String) docProperties.get("type");
                                        Boolean folder = (Boolean) docProperties.get("folder");
                                    
                                        if (log.isDebugEnabled()) {
                                            log.debug("Found object: name='" + name + "', id=" + objectId + ", type=" + type + ", folder=" + folder);
                                        }
                                        
                                        // Check if this is a folder with .system or System name
                                        if ((folder != null && folder) || "cmis:folder".equals(type)) {
                                            if (".system".equals(name)) {
                                                if (log.isDebugEnabled()) {
                                                    log.debug("Found existing .system folder from dump file: " + objectId + " - using this as system folder");
                                                }
                                                log.info("Found existing .system folder from dump file: " + objectId + " - using this as system folder");
                                                
                                                // Validate that we have a non-null objectId before creating the folder object
                                                if (objectId != null && !objectId.trim().isEmpty()) {
                                                    // Create a minimal Folder object for return
                                                    jp.aegif.nemaki.model.Folder systemFolder = new jp.aegif.nemaki.model.Folder();
                                                    systemFolder.setId(objectId);
                                                    systemFolder.setName(name);
                                                    return systemFolder;
                                                } else {
                                                    log.error("Found .system folder but objectId is null or empty - cannot use this folder");
                                                }
                                            }
                                            if ("System".equals(name)) {
                                                if (log.isDebugEnabled()) {
                                                    log.debug("Found existing legacy System folder: " + objectId);
                                                }
                                                log.info("Found existing legacy System folder: " + objectId);
                                                
                                                // Validate that we have a non-null objectId before creating the folder object
                                                if (objectId != null && !objectId.trim().isEmpty()) {
                                                    // Create a minimal Folder object for return  
                                                    jp.aegif.nemaki.model.Folder systemFolder = new jp.aegif.nemaki.model.Folder();
                                                    systemFolder.setId(objectId);
                                                    systemFolder.setName(name);
                                                    return systemFolder;
                                                } else {
                                                    log.error("Found System folder but objectId is null or empty - cannot use this folder");
                                                }
                                            }
                                        } else {
                                            if (log.isDebugEnabled()) {
                                                log.debug("Found non-folder: " + name + " (type: " + type + ", folder: " + folder + ")");
                                            }
                                        }
                                    } else {
                                        if (log.isDebugEnabled()) {
                                            log.debug("Document has no properties");
                                        }
                                    }
                                } else {
                                    if (log.isDebugEnabled()) {
                                        log.debug("Document is null");
                                    }
                                }
                            } catch (Exception docEx) {
                                log.warn("Error processing document in system folder search", docEx);
                            }
                        }
                    }
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("No children found in direct CouchDB view query");
                    }
                }
                
                if (log.isDebugEnabled()) {
                    log.debug("No existing system folders found via direct CouchDB query");
                }
                return null;
                
            } catch (Exception directEx) {
                log.error("Direct CouchDB query failed, falling back to ContentService.getChildren()", directEx);

                // Fallback: use ContentService.getChildren() which works even without views
                // (the cached DAO falls back to nonCachedContentDaoService when tree cache is empty)
                try {
                    java.util.List<jp.aegif.nemaki.model.Content> children = contentService.getChildren(repositoryId, rootFolderId);
                    if (children != null) {
                        for (jp.aegif.nemaki.model.Content child : children) {
                            if (child != null && ".system".equals(child.getName()) && child.isFolder()) {
                                log.info("Found existing .system folder via ContentService fallback: " + child.getId());
                                jp.aegif.nemaki.model.Folder systemFolder = new jp.aegif.nemaki.model.Folder();
                                systemFolder.setId(child.getId());
                                systemFolder.setName(child.getName());
                                return systemFolder;
                            }
                            if (child != null && "System".equals(child.getName()) && child.isFolder()) {
                                log.info("Found existing legacy System folder via ContentService fallback: " + child.getId());
                                jp.aegif.nemaki.model.Folder systemFolder = new jp.aegif.nemaki.model.Folder();
                                systemFolder.setId(child.getId());
                                systemFolder.setName(child.getName());
                                return systemFolder;
                            }
                        }
                    }
                } catch (Exception fallbackEx) {
                    log.warn("ContentService fallback also failed: " + fallbackEx.getMessage());
                }

                return null;
            }
            
        } catch (Exception e) {
            log.warn("Error checking for existing System folder", e);
            return null;
        }
    }
    
    /**
     * Create System folder in the root directory
     */
    private String createSystemFolder(ContentService contentService, SystemCallContext callContext, 
                                     String repositoryId, String rootFolderId) {
        try {
            // Create CMIS properties for the System folder
            PropertiesImpl properties = new PropertiesImpl();
            properties.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, "cmis:folder"));
            properties.addProperty(new PropertyStringImpl(PropertyIds.NAME, SYSTEM_FOLDER_NAME));
            properties.addProperty(new PropertyStringImpl(PropertyIds.DESCRIPTION, 
                "System folder for internal application structures, user and group management"));
            
            // Get parent folder object
            Folder parentFolder = null;
            try {
                parentFolder = (Folder) contentService.getContent(repositoryId, rootFolderId);
                if (parentFolder == null) {
                    log.warn("Root folder not found for ID: " + rootFolderId + " in repository: " + repositoryId);
                    return null;
                }
            } catch (Exception e) {
                log.warn("Error accessing root folder ID: " + rootFolderId + " in repository: " + repositoryId + ". Error: " + e.getMessage());
                return null;
            }
            
            // Create System folder through ContentService
            Folder created = contentService.createFolder(callContext, repositoryId, properties, 
                                                        parentFolder, null, null, null, null);
            
            log.info("System folder created via ContentService: " + SYSTEM_FOLDER_NAME + " with ID: " + created.getId());
            log.info("ChangeLog entry generated for System folder creation");
            
            return created.getId();
            
        } catch (Exception e) {
            log.error("Error creating System folder", e);
            return null;
        }
    }
    
    /**
     * Set systemFolder configuration in nemaki_conf database
     * This is critical for PropertyManager.readValue() to work correctly
     */
    private void setSystemFolderConfiguration(String repositoryId, String systemFolderId) {
        try {
            log.info("Setting systemFolder configuration for repository: " + repositoryId + " = " + systemFolderId);
            
            PropertyManager propertyManager = patchUtil.getPropertyManager();
            if (propertyManager != null) {
                // Create configuration entry for systemFolder
                createRepositoryConfigurationEntry(repositoryId, "system.folder", systemFolderId,
                    "System folder ID for " + repositoryId + " repository");
                    
                log.info("SystemFolder configuration set successfully for repository: " + repositoryId);
            } else {
                log.warn("PropertyManager not available, cannot set systemFolder configuration");
            }
            
        } catch (Exception e) {
            log.error("Error setting systemFolder configuration for repository: " + repositoryId, e);
        }
    }
    
    /**
     * Create system-wide configuration entry in nemaki_conf
     */
    private void createSystemConfigurationEntry(String key, String value, String description) {
        try {
            String configId = "system_config_" + key.replace(".", "_");
            createConfigurationDocument(configId, key, value, description, null);
        } catch (Exception e) {
            log.error("Error creating system configuration entry: " + key, e);
        }
    }
    
    /**
     * Create repository-specific configuration entry in nemaki_conf
     */
    private void createRepositoryConfigurationEntry(String repositoryId, String key, String value, String description) {
        try {
            String configId = repositoryId + "_" + key;
            createConfigurationDocument(configId, key, value, description, repositoryId);
        } catch (Exception e) {
            log.error("Error creating repository configuration entry: " + repositoryId + "." + key, e);
        }
    }
    
    /**
     * Create configuration document using CloudantClientWrapper (authenticated via connectorPool)
     * @param documentId the CouchDB document ID
     * @param key the configuration key
     * @param value the configuration value
     * @param description a human-readable description
     * @param repositoryId if non-null, marks this as a repository-specific config entry
     */
    private void createConfigurationDocument(String documentId, String key, String value,
                                             String description, String repositoryId) {
        try {
            jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper confClient =
                patchUtil.getConnectorPool().getClient("nemaki_conf");
            if (confClient == null) {
                log.error("Cannot get nemaki_conf client from connectorPool");
                return;
            }

            // Existence is a property of the logical key, not of this method's id convention.
            // The initialization dump seeds some of these keys under fixed ids
            // (system_config_001 carries system.version), while this method derives its id
            // from the key — so an id-only check missed the seeded copy and created a second
            // document for the same key. The strict configuration reader refuses to choose
            // between duplicates, which turned the 4b cursor preflight red on every standard
            // install: the gate could never be satisfied, only argued with.
            java.util.List<com.ibm.cloud.cloudant.v1.model.Document> holders =
                findConfigurationDocumentsByKey(confClient, key, repositoryId);
            String duplicate = duplicateToDelete(documentId, holders);
            if (duplicate != null) {
                // Both this method's document and another holder exist — the state earlier
                // runs of this very patch created. Heal by removing OUR copy, never the other
                // one: the other id may be the dump's seed or an operator's own document, and
                // this patch has no authority over either.
                com.ibm.cloud.cloudant.v1.model.Document ours = holders.stream()
                    .filter(d -> duplicate.equals(d.getId())).findFirst().orElse(null);
                if (ours != null) {
                    confClient.delete(ours.getId(), ours.getRev());
                    log.info("Removed duplicate configuration document '" + duplicate
                        + "' for key '" + key + "' — another document already carries it");
                }
                return;
            }
            if (!holders.isEmpty()) {
                log.info("Configuration key already stored: " + key + " (document: "
                    + holders.get(0).getId() + ")");
                return;
            }
            if (confClient.exists(documentId)) {
                log.info("Configuration document already exists: " + documentId);
                return;
            }

            java.util.Map<String, Object> doc = new java.util.HashMap<>();
            doc.put("_id", documentId);
            doc.put("type", "configuration");
            doc.put("created", java.time.Instant.now().toString());
            doc.put("creator", "system");
            doc.put("modified", java.time.Instant.now().toString());
            doc.put("modifier", "system");
            doc.put("key", key);
            doc.put("value", value);
            doc.put("description", description);
            if (repositoryId != null) {
                doc.put("repositoryId", repositoryId);
            }

            com.ibm.cloud.cloudant.v1.model.DocumentResult result = confClient.create(documentId, doc);
            if (result != null) {
                log.info("Configuration document created successfully: " + documentId);
            } else {
                log.warn("Failed to create configuration document: " + documentId);
            }
        } catch (Exception e) {
            log.error("Error creating configuration document: " + documentId, e);
        }
    }

    /**
     * Every configuration document that carries this logical key in this scope, whatever its id.
     *
     * <p>Scope matters: a global entry (no {@code repositoryId}) and a repository entry with the
     * same key name are different settings, and treating one as a duplicate of the other would
     * delete a document that is not ours to judge.
     */
    private java.util.List<com.ibm.cloud.cloudant.v1.model.Document> findConfigurationDocumentsByKey(
            jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper confClient,
            String key, String repositoryId) {
        java.util.List<com.ibm.cloud.cloudant.v1.model.Document> holders =
            new java.util.ArrayList<>();
        com.ibm.cloud.cloudant.v1.model.AllDocsResult result = confClient.getClient()
            .postAllDocs(new com.ibm.cloud.cloudant.v1.model.PostAllDocsOptions.Builder()
                .db(confClient.getDatabaseName())
                .includeDocs(true)
                .build())
            .execute().getResult();
        for (com.ibm.cloud.cloudant.v1.model.DocsResultRow row : result.getRows()) {
            if (row.getValue() != null && Boolean.TRUE.equals(row.getValue().isDeleted())) {
                continue;
            }
            com.ibm.cloud.cloudant.v1.model.Document doc = row.getDoc();
            if (doc == null || doc.getId() == null || doc.getId().startsWith("_design/")) {
                continue;
            }
            java.util.Map<String, Object> props = doc.getProperties();
            if (props == null || !key.equals(props.get("key"))) {
                continue;
            }
            Object scope = props.get("repositoryId");
            boolean sameScope = repositoryId == null ? scope == null
                : repositoryId.equals(scope);
            if (sameScope) {
                holders.add(doc);
            }
        }
        return holders;
    }

    /**
     * Which document this patch may delete to end a duplication, or null.
     *
     * <p>Only its own — the one under the id this patch derives — and only while another
     * document also carries the key. A single holder is healthy whatever its id, and a
     * duplication among documents this patch never wrote is not its call to resolve: the
     * strict reader will keep refusing, which is the honest outcome.
     */
    static String duplicateToDelete(String derivedId,
            java.util.List<com.ibm.cloud.cloudant.v1.model.Document> holders) {
        if (derivedId == null || holders == null || holders.size() < 2) {
            return null;
        }
        return holders.stream().map(com.ibm.cloud.cloudant.v1.model.Document::getId)
            .anyMatch(derivedId::equals) ? derivedId : null;
    }
}
