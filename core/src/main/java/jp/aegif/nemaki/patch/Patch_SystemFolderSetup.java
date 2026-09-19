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
        
        // NOT-READY MUST THROW, NEVER RETURN.
        //
        // AbstractNemakiPatch.apply() records this patch as applied the instant this method
        // returns normally (AbstractNemakiPatch.java:57-59), and isApplied then skips it on every
        // later startup. So returning early because the repository is not initialized yet does not
        // mean "try again next time" — it means "never again", with a single warn line as the only
        // trace. That is the shape PX1 came out of.
        //
        // Throwing is the safe direction and does NOT risk startup: apply() already catches
        // (AbstractNemakiPatch.java:63-67), logs, marks the run unsuccessful and moves to the next
        // repository — and crucially does not write the history, so this patch runs again next
        // startup. The old comment below ("Don't throw - patch failures should not prevent
        // application startup") was written against a premise that is not true of apply().
        try {
            ContentService contentService = patchUtil.getContentService();
            if (contentService == null) {
                throw new IllegalStateException("ContentService not available for repository "
                        + repositoryId + " — the system folder was not set up. Retrying next startup.");
            }

            if (patchUtil.getRepositoryInfoMap() == null) {
                throw new IllegalStateException("RepositoryInfoMap not available yet for repository "
                        + repositoryId + " — the system folder was not set up. Retrying next startup.");
            }

            if (patchUtil.getRepositoryInfoMap().get(repositoryId) == null) {
                throw new IllegalStateException("Repository info not available for " + repositoryId
                        + " — the system folder was not set up. Retrying next startup.");
            }

            String rootFolderId = patchUtil.getRepositoryInfoMap().get(repositoryId).getRootFolderId();
            if (rootFolderId == null) {
                throw new IllegalStateException("Root folder id not available for repository "
                        + repositoryId + " — the system folder was not set up. Retrying next startup.");
            }

            log.info("Using root folder ID: " + rootFolderId + " for repository: " + repositoryId);

            // Verify root folder exists
            try {
                Folder rootFolder = (Folder) contentService.getContent(repositoryId, rootFolderId);
                if (rootFolder == null) {
                    throw new IllegalStateException("Root folder " + rootFolderId + " not found for "
                            + repositoryId + "; the repository is not fully initialized yet — the "
                            + "system folder was not set up. Retrying next startup.");
                }

                log.info("Root folder verified for repository: " + repositoryId + ", proceeding with System folder setup");
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Cannot access root folder " + rootFolderId + " for "
                        + repositoryId + "; the repository is not fully initialized yet — the system "
                        + "folder was not set up. Retrying next startup: " + e.getMessage(), e);
            }
            
            // Create SystemCallContext for operations
            SystemCallContext callContext = new SystemCallContext(repositoryId);
            
            // Check if System folder already exists.
            //
            // FIRST from the configuration, which records the id this patch assigned last time and
            // is read by a direct document lookup. findExistingSystemFolder below asks the
            // `children` view — and a view whose design document is being rebuilt answers with
            // ZERO ROWS and HTTP 200, so "no system folder here" is exactly what a healthy-looking
            // failure says. This patch then creates a second one. That happened: bedroom held two
            // `.system` folders, the second created 2026-08-13, which breaks CMIS path resolution
            // because two objects answer to /.system.
            //
            // The configuration cannot be silenced the same way: it is a document read by id, not
            // a view query. If it names a folder that still exists, there is nothing to do.
            Folder existingSystemFolder = findConfiguredSystemFolder(contentService, repositoryId);
            if (existingSystemFolder != null) {
                log.info("System folder already recorded in configuration with ID: "
                        + existingSystemFolder.getId() + " — nothing to create");
                setSystemFolderConfiguration(repositoryId, existingSystemFolder.getId());
                log.info("System Folder Setup Patch completed successfully for repository: " + repositoryId);
                return;
            }
            existingSystemFolder = findExistingSystemFolder(contentService, repositoryId, rootFolderId);
            
            if (existingSystemFolder == null) {
                log.info("Creating System folder for repository: " + repositoryId);
                String systemFolderId = createSystemFolder(contentService, callContext, repositoryId, rootFolderId);
                
                if (systemFolderId != null) {
                    log.info("System folder created with ID: " + systemFolderId);
                    
                    // Set systemFolder configuration in nemaki_conf
                    setSystemFolderConfiguration(repositoryId, systemFolderId);
                    
                } else {
                    // Creating it is this patch's entire job. Warning and returning would record
                    // the patch as applied with no system folder created and no retry — the same
                    // "never again" as the not-ready branches above.
                    throw new IllegalStateException("Failed to create the system folder for "
                            + repositoryId + " (createSystemFolder returned no id). Retrying next "
                            + "startup.");
                }
            } else {
                log.info("System folder already exists with ID: " + existingSystemFolder.getId());
                
                // Ensure configuration is set even if folder exists
                setSystemFolderConfiguration(repositoryId, existingSystemFolder.getId());
            }
            
            log.info("System Folder Setup Patch completed successfully for repository: " + repositoryId);
            
        } catch (Exception e) {
            // Rethrow. Swallowing here is what turns "this failed" into "this is done": apply()
            // writes the history as soon as this method returns normally, so a swallowed failure is
            // permanent and invisible. apply() catches this, logs it, and leaves the patch
            // un-recorded so it runs again next startup — startup is not affected.
            log.error("Error during System Folder Setup Patch for repository: " + repositoryId, e);
            throw (e instanceof RuntimeException) ? (RuntimeException) e
                    : new IllegalStateException("System Folder Setup failed for repository "
                            + repositoryId + ": " + e.getMessage(), e);
        }
    }
    
    /**
     * The system folder id recorded in the configuration, or null if none is recorded.
     *
     * <p>Reads {@code nemaki_conf} through {@code _all_docs} — not a view, and not even the same
     * database as the one whose design document gets rewritten during an upgrade. That is the
     * whole point: this lookup cannot be turned into a silent empty answer by a rebuild.
     */
    private String readConfiguredSystemFolderId(String repositoryId) {
        jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper confClient =
            patchUtil.getConnectorPool().getClient("nemaki_conf");
        if (confClient == null) {
            return null;
        }
        java.util.List<com.ibm.cloud.cloudant.v1.model.Document> holders =
            findConfigurationDocumentsByKey(confClient, "system.folder", repositoryId);
        for (com.ibm.cloud.cloudant.v1.model.Document doc : holders) {
            java.util.Map<String, Object> props = doc.getProperties();
            if (props == null) {
                continue;
            }
            Object value = props.get("value");
            if (value != null && !value.toString().trim().isEmpty()) {
                return value.toString();
            }
        }
        return null;
    }

    /**
     * The system folder this patch recorded last time, if it is still there.
     *
     * <p>Deliberately does NOT go through a view. The configuration entry holds the id, and an id
     * is read straight from the document store — which is the one lookup a design-document rebuild
     * cannot turn into a silent empty answer. Returns null when nothing is recorded (a genuinely
     * first run) or when the recorded folder has since been deleted, and in both cases the caller
     * falls back to the view-based search.
     */
    private Folder findConfiguredSystemFolder(ContentService contentService, String repositoryId) {
        try {
            String recordedId = readConfiguredSystemFolderId(repositoryId);
            if (recordedId == null || recordedId.trim().isEmpty()) {
                return null;
            }
            Folder folder = contentService.getFolder(repositoryId, recordedId);
            if (folder == null) {
                log.warn("Configuration names system folder " + recordedId + " for repository "
                        + repositoryId + " but it no longer exists — falling back to a search");
                return null;
            }
            return folder;
        } catch (Exception e) {
            // Unknown, not absent. Fall back to the search rather than creating a duplicate on a
            // guess; the search has its own (weaker) protection.
            log.warn("Could not read the configured system folder id for repository "
                    + repositoryId + ": " + e.getMessage());
            return null;
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
                
                int unreadableRows = 0;
                if (result.getRows() != null && !result.getRows().isEmpty()) {
                    if (log.isDebugEnabled()) {
                        log.debug("Direct CouchDB query found " + result.getRows().size() + " raw rows");
                    }
                    
                    for (com.ibm.cloud.cloudant.v1.model.ViewResultRow row : result.getRows()) {
                        if (row.getDoc() == null) {
                            // Counted, not skipped: the row that will not decode may BE the
                            // .system folder, and walking past it lets the caller create a
                            // second one — the exact healthy-looking-absence failure this
                            // patch documents for the view-rebuild case. The views-answering
                            // gate cannot see this: the view IS answering, per-row.
                            unreadableRows++;
                            continue;
                        }
                        {
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
                                unreadableRows++;
                                log.warn("Error processing document in system folder search"
                                        + " — counted as unreadable, not as absent", docEx);
                            }
                        }
                    }
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("No children found in direct CouchDB view query");
                    }
                }
                
                if (unreadableRows > 0) {
                    // No .system among the READABLE rows, and rows exist that could not be
                    // read. "Not found" is not established — throwing lands in
                    // AbstractNemakiPatch's catch, the history row is withheld, and the next
                    // start retries against a repaired listing instead of creating a
                    // duplicate .system folder nothing can merge afterwards.
                    throw new IllegalStateException(unreadableRows + " child row(s) of the root"
                            + " folder could not be decoded, so whether a .system folder"
                            + " already exists is unknown; refusing to create one");
                }
                if (log.isDebugEnabled()) {
                    log.debug("No existing system folders found via direct CouchDB query");
                }
                return null;
                
            } catch (Exception directEx) {
                log.error("Direct CouchDB query failed, falling back to ContentService.getChildren()", directEx);

                // Fallback: use ContentService.getChildren() which works even without views
                // (the cached DAO falls back to nonCachedContentDaoService when tree cache is empty)
                int fallbackUnreadable = 0;
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
                    // The quiet twin of the catch below: rows the store cannot DECODE are
                    // absent from `children` without any exception, and one of them may be the
                    // .system folder itself. The direct CouchDB path above refuses for this
                    // (its refusal lands HERE, as directEx) — so a fallback that does not
                    // apply the same rule would undo it one branch later.
                    //
                    // Read inside the try, thrown AFTER the catch: thrown here it would be
                    // caught by the very catch below and re-worded as "could not be
                    // enumerated" — refused either way, but for a reason that sends the
                    // operator to the wrong place.
                    fallbackUnreadable = contentService.lastUnreadableChildCount();
                } catch (Exception fallbackEx) {
                    // "We could not look" must not become "there is none" HERE of all places:
                    // the caller's response to null is to CREATE a .system folder, and this
                    // class's own comment records what that cost last time — two .system
                    // folders in bedroom and a broken /.system path resolution.
                    //
                    // It became easier to reach on 2026-08-28, when getChildren was changed to
                    // refuse an unanswered view rather than report an empty folder. That
                    // correction is right, and it makes this branch throw wherever the
                    // enumeration genuinely fails — a design document being rebuilt, a
                    // repository not seeded from a dump.
                    //
                    // NOT on every fresh install, which is what the first version of this
                    // comment said. DatabasePreInitializer is @Order(1) and the patch runner is
                    // @Order(3), and both shipped dumps already contain the `children` view —
                    // so on the standard path the view is there before this runs. Checked
                    // rather than reasoned from the patch order, after asserting the opposite.
                    //
                    // So: no answer, no creation. The throw below is caught by
                    // AbstractNemakiPatch, which logs it and does NOT reach createPathHistory,
                    // so the history row is withheld and the patch is retried on the next start
                    // — by which time the view is there — and startup is not stopped. (Named
                    // reportIncomplete here once; this class never calls it. Same outcome, but
                    // a reader following the name found nothing.)
                    log.warn("The root folder of " + repositoryId + " could not be enumerated ("
                            + fallbackEx.getMessage() + "), so whether a .system folder already "
                            + "exists is UNKNOWN. Not creating one: this patch is retried on the "
                            + "next start rather than risking a second .system folder.",
                            fallbackEx);
                    throw new IllegalStateException("the root folder of " + repositoryId
                            + " could not be enumerated, so it is unknown whether a .system "
                            + "folder already exists", fallbackEx);
                }
                if (fallbackUnreadable > 0) {
                    throw new IllegalStateException(fallbackUnreadable + " child row(s) of the"
                            + " root folder could not be decoded, so whether a .system folder"
                            + " already exists is unknown; refusing to create one");
                }

                return null;
            }
            
        } catch (Exception e) {
            // "Could not check" is not "there is none", and this method's answer is read by a
            // caller whose response to null is to CREATE a second .system folder. The refusal
            // added to the fallback below was landing HERE and being turned straight back into
            // an absence — an outer catch swallowing a guard added to an inner one, which is
            // the shape this project keeps finding and had already found twice this week.
            if (e instanceof IllegalStateException) {
                log.error("Refusing to report the .system folder as absent for " + repositoryId
                        + ": " + e.getMessage(), e);
                throw (IllegalStateException) e;
            }
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
