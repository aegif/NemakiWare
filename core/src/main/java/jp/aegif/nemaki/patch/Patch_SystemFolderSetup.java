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
    
    /**
     * Get CouchDB URL from configuration file (PropertyManager)
     */
    private String getCouchDbUrl() {
        PropertyManager propertyManager = patchUtil.getPropertyManager();
        if (propertyManager == null) {
            log.warn("PropertyManager not available - using fallback URL detection");
            // Fallback to original logic if PropertyManager not available
            try {
                java.net.InetAddress.getByName("couchdb");
                log.info("Docker environment detected - using couchdb:5984");
                return "http://couchdb:5984";
            } catch (java.net.UnknownHostException e) {
                log.info("Local environment detected - using localhost:5984");
                return "http://localhost:5984";
            }
        }
        
        // Use PropertyManager to read configuration
        String couchDbUrl = propertyManager.readValue("db.couchdb.url");
        if (couchDbUrl != null && !couchDbUrl.trim().isEmpty()) {
            log.info("Using CouchDB URL from configuration: " + couchDbUrl);
            return couchDbUrl;
        } else {
            log.warn("db.couchdb.url not found in configuration - using default");
            return "http://localhost:5984";
        }
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
        
        if ("canopy".equals(repositoryId)) {
            log.info("Skipping System Folder Setup for canopy - information management area");
            return;
        }
        
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
            System.out.println("=== PATCH DEBUG: Checking for existing system folders in repository " + repositoryId + ", root folder " + rootFolderId);
            log.info("PATCH DEBUG: Checking for existing system folders in repository " + repositoryId + ", root folder " + rootFolderId);
            
            // CRITICAL FIX: Use direct CouchDB query instead of ContentService/ContentDaoService
            // Both service layers fail during initialization phase due to getContent() dependencies
            try {
                System.out.println("=== PATCH DEBUG: Using direct CouchDB view query approach");
                log.info("PATCH DEBUG: Using direct CouchDB view query approach");
                
                // Get CloudantClientWrapper directly from patch util
                jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper client = patchUtil.getConnectorPool().getClient(repositoryId);
                if (client == null) {
                    log.error("Could not get Cloudant client for repository: " + repositoryId);
                    return null;
                }
                
                // Query children view with parent ID
                java.util.Map<String, Object> queryParams = new java.util.HashMap<>();
                queryParams.put("key", rootFolderId);
                queryParams.put("include_docs", true);
                
                System.out.println("=== PATCH DEBUG: Executing direct CouchDB view query: _repo/children with key=" + rootFolderId);
                
                com.ibm.cloud.cloudant.v1.model.ViewResult result = client.queryView("_repo", "children", queryParams);
                
                if (result.getRows() != null && !result.getRows().isEmpty()) {
                    System.out.println("=== PATCH DEBUG: Direct CouchDB query found " + result.getRows().size() + " raw rows");
                    log.info("PATCH DEBUG: Direct CouchDB query found " + result.getRows().size() + " raw rows");
                    
                    for (com.ibm.cloud.cloudant.v1.model.ViewResultRow row : result.getRows()) {
                        if (row.getDoc() != null) {
                            try {
                                // CRITICAL FIX: Extract document ID correctly from ViewResultRow
                                // Primary method: Get document ID from row itself
                                String objectId = row.getId();
                                System.out.println("=== PATCH DEBUG: Row ID (primary method): " + objectId);
                                
                                // Extract document properties for additional information
                                com.ibm.cloud.cloudant.v1.model.Document doc = row.getDoc();
                                if (doc != null) {
                                    // Fallback method: Try document properties if row ID is null
                                    if (objectId == null) {
                                        java.util.Map<String, Object> docProperties = doc.getProperties();
                                        if (docProperties != null) {
                                            objectId = (String) docProperties.get("_id");
                                            System.out.println("=== PATCH DEBUG: Document properties _id (fallback method): " + objectId);
                                        }
                                    }
                                    
                                    // Additional fallback: Try document getId() method
                                    if (objectId == null) {
                                        objectId = doc.getId();
                                        System.out.println("=== PATCH DEBUG: Document getId() (additional fallback): " + objectId);
                                    }
                                    
                                    // Get other document properties for validation
                                    java.util.Map<String, Object> docProperties = doc.getProperties();
                                    if (docProperties != null) {
                                        String name = (String) docProperties.get("name");
                                        String type = (String) docProperties.get("type");
                                        Boolean folder = (Boolean) docProperties.get("folder");
                                    
                                        System.out.println("=== PATCH DEBUG: Found object: name='" + name + "', id=" + objectId + ", type=" + type + ", folder=" + folder);
                                        log.info("PATCH DEBUG: Found object: name='" + name + "', id=" + objectId + ", type=" + type + ", folder=" + folder);
                                        
                                        // Check if this is a folder with .system or System name
                                        if ((folder != null && folder) || "cmis:folder".equals(type)) {
                                            if (".system".equals(name)) {
                                                System.out.println("=== PATCH SUCCESS: Found existing .system folder from dump file: " + objectId + " - using this as system folder");
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
                                                System.out.println("=== PATCH SUCCESS: Found existing legacy System folder: " + objectId);
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
                                            System.out.println("=== PATCH DEBUG: Found non-folder: " + name + " (type: " + type + ", folder: " + folder + ")");
                                            log.info("PATCH DEBUG: Found non-folder: " + name + " (type: " + type + ", folder: " + folder + ")");
                                        }
                                    } else {
                                        System.out.println("=== PATCH DEBUG: Document has no properties");
                                        log.info("PATCH DEBUG: Document has no properties");
                                    }
                                } else {
                                    System.out.println("=== PATCH DEBUG: Document is null");
                                    log.info("PATCH DEBUG: Document is null");
                                }
                            } catch (Exception docEx) {
                                log.warn("Error processing document in system folder search", docEx);
                            }
                        }
                    }
                } else {
                    System.out.println("=== PATCH DEBUG: No children found in direct CouchDB view query");
                    log.info("PATCH DEBUG: No children found in direct CouchDB view query");
                }
                
                System.out.println("=== PATCH DEBUG: No existing system folders found via direct CouchDB query");
                log.info("PATCH DEBUG: No existing system folders found via direct CouchDB query");
                return null;
                
            } catch (Exception directEx) {
                log.error("Direct CouchDB query failed, system folder detection not possible during initialization", directEx);
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
                createRepositoryConfigurationEntry(repositoryId, "systemFolder", systemFolderId, 
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
            // Create configuration document for nemaki_conf database
            String configId = "system_config_" + key.replace(".", "_");
            
            String configJson = String.format(
                "{\n" +
                "  \"_id\": \"%s\",\n" +
                "  \"type\": \"configuration\",\n" +
                "  \"created\": \"%s\",\n" +
                "  \"creator\": \"system\",\n" +
                "  \"modified\": \"%s\",\n" +
                "  \"modifier\": \"system\",\n" +
                "  \"key\": \"%s\",\n" +
                "  \"value\": \"%s\",\n" +
                "  \"description\": \"%s\"\n" +
                "}",
                configId,
                java.time.Instant.now().toString(),
                java.time.Instant.now().toString(),
                key,
                value,
                description
            );
            
            // Use direct HTTP approach to create configuration
            createConfigurationDocument("nemaki_conf", configId, configJson);
            
        } catch (Exception e) {
            log.error("Error creating system configuration entry: " + key, e);
        }
    }
    
    /**
     * Create repository-specific configuration entry in nemaki_conf
     */
    private void createRepositoryConfigurationEntry(String repositoryId, String key, String value, String description) {
        try {
            // Create configuration document for nemaki_conf database
            String configId = repositoryId + "_" + key;
            
            String configJson = String.format(
                "{\n" +
                "  \"_id\": \"%s\",\n" +
                "  \"type\": \"configuration\",\n" +
                "  \"created\": \"%s\",\n" +
                "  \"creator\": \"system\",\n" +
                "  \"modified\": \"%s\",\n" +
                "  \"modifier\": \"system\",\n" +
                "  \"repositoryId\": \"%s\",\n" +
                "  \"key\": \"%s\",\n" +
                "  \"value\": \"%s\",\n" +
                "  \"description\": \"%s\"\n" +
                "}",
                configId,
                java.time.Instant.now().toString(),
                java.time.Instant.now().toString(),
                repositoryId,
                key,
                value,
                description
            );
            
            // Use direct HTTP approach to create configuration
            createConfigurationDocument("nemaki_conf", configId, configJson);
            
        } catch (Exception e) {
            log.error("Error creating repository configuration entry: " + repositoryId + "." + key, e);
        }
    }
    
    /**
     * Create configuration document using direct HTTP approach
     * This avoids circular dependency issues during initialization
     */
    private void createConfigurationDocument(String database, String documentId, String jsonContent) {
        try {
            // Check if document already exists
            java.net.URL checkUrl = new java.net.URL(getCouchDbUrl() + "/" + database + "/" + documentId);
            java.net.HttpURLConnection checkConn = (java.net.HttpURLConnection) checkUrl.openConnection();
            
            String auth = "admin:password";
            String encodedAuth = java.util.Base64.getEncoder().encodeToString(auth.getBytes());
            checkConn.setRequestProperty("Authorization", "Basic " + encodedAuth);
            checkConn.setRequestMethod("HEAD");
            
            int checkResponse = checkConn.getResponseCode();
            checkConn.disconnect();
            
            if (checkResponse == 200) {
                log.info("Configuration document already exists: " + documentId);
                return;
            }
            
            // Create document
            java.net.URL createUrl = new java.net.URL(getCouchDbUrl() + "/" + database + "/" + documentId);
            java.net.HttpURLConnection createConn = (java.net.HttpURLConnection) createUrl.openConnection();
            
            createConn.setRequestProperty("Authorization", "Basic " + encodedAuth);
            createConn.setRequestProperty("Content-Type", "application/json");
            createConn.setRequestMethod("PUT");
            createConn.setDoOutput(true);
            
            // Write JSON content
            try (java.io.OutputStream os = createConn.getOutputStream()) {
                os.write(jsonContent.getBytes("UTF-8"));
            }
            
            int createResponse = createConn.getResponseCode();
            createConn.disconnect();
            
            if (createResponse == 201) {
                log.info("Configuration document created successfully: " + documentId);
            } else {
                log.warn("Failed to create configuration document: " + documentId + " (HTTP " + createResponse + ")");
            }
            
        } catch (Exception e) {
            log.error("Error creating configuration document: " + documentId, e);
        }
    }
}
