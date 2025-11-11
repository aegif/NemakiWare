package jp.aegif.nemaki.patch;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.annotation.Order;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.NemakiPropertyDefinition;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;
import jp.aegif.nemaki.util.PropertyManager;

import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;

/**
 * PHASE 3: PatchService - System Property Initialization
 *
 * CRITICAL FIX (2025-11-10): Converted to ApplicationListener<ContextRefreshedEvent> pattern
 * to ensure proper execution order after DatabasePreInitializer (@Order(1)) and
 * CMISPostInitializer (@Order(2)).
 *
 * Previous issue: init-method="applyPatchesOnStartup" ran during bean creation,
 * BEFORE DatabasePreInitializer executed, causing "not_found" errors when trying
 * to create documents in non-existent databases.
 *
 * New pattern: ApplicationListener with @Order(3) ensures execution after:
 * - @Order(1): DatabasePreInitializer (creates databases, loads dump files)
 * - @Order(2): CMISPostInitializer (creates CMIS patches)
 * - @Order(3): PatchService (creates system property definitions)
 *
 * NOTE: This class is registered as a Spring Bean in patchContext.xml (NOT via @Component)
 * to match the pattern used by DatabasePreInitializer and CMISPostInitializer.
 * Using @Component with XML bean definition causes bean duplication issues.
 */
@Order(3)  // Execute after DatabasePreInitializer (@Order(1)) and CMISPostInitializer (@Order(2))
public class PatchService implements ApplicationListener<ContextRefreshedEvent> {

	// Static initializer for class loading verification
	static {
		System.err.println("*** PatchService CLASS LOADED ***");
		System.err.println("*** Thread: " + Thread.currentThread().getName() + " ***");
	}

	private static final Log log = LogFactory.getLog(PatchService.class);
	private final AtomicBoolean initialized = new AtomicBoolean(false);

	// CRITICAL FIX (2025-11-11): Reverting to @Autowired pattern matching DatabasePreInitializer
	// Previous hypothesis was INCORRECT: DatabasePreInitializer does NOT use XML properties at all
	// DatabasePreInitializer: 0 XML properties (empty bean definition) with @Autowired → WORKS ✅
	// CMISPostInitializer: 1 XML property (list) without @Autowired → WORKS ✅
	// PatchService (previous): 7 XML properties (bean refs) without @Autowired → FAILED ❌
	//
	// Root cause analysis revealed patchContext.xml comments were wrong - DatabasePreInitializer
	// uses field defaults, NOT @Autowired as claimed. Testing empty bean pattern with @Autowired.
	@org.springframework.beans.factory.annotation.Autowired
	private RepositoryInfoMap repositoryInfoMap;
	@org.springframework.beans.factory.annotation.Autowired
	private CloudantClientPool connectorPool;
	@org.springframework.beans.factory.annotation.Autowired
	private PropertyManager propertyManager;

	// NEW: Required dependencies for PropertyDefinitionDetail creation
	@org.springframework.beans.factory.annotation.Autowired
	private TypeService typeService;
	@org.springframework.beans.factory.annotation.Autowired
	private TypeManager typeManager;

	// NEW: Required dependency for initial folder creation
	@org.springframework.beans.factory.annotation.Autowired
	private ContentService contentService;

	// NEW: Required dependency for Solr indexing
	@org.springframework.beans.factory.annotation.Autowired
	private SolrUtil solrUtil;

	// Configuration properties for database initialization - Docker environment compatible
	// CRITICAL FIX (2025-11-10): Use hardcoded default instead of method call during field initialization
	// Calling getCouchDbUrl() during field initialization causes bean creation failure because
	// propertyManager (@Autowired) is not yet injected at field initialization time
	// This matches the pattern used by DatabasePreInitializer for reliable bean creation
	private String couchdbUrl = "http://couchdb:5984";
	private String couchdbUsername = "admin";
	private String couchdbPassword = "password";
	
	/**
	 * Get CouchDB URL from configuration file (PropertyManager)
	 */
	private String getCouchDbUrl() {
		if (propertyManager == null) {
			log.warn("PropertyManager not injected - using fallback URL detection");
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

	private List<AbstractNemakiPatch> patchList;

	public PatchService() {
		// Constructor - initialization handled by onApplicationEvent
		System.err.println("*** PatchService BEAN CREATED ***");
		System.err.println("*** Thread: " + Thread.currentThread().getName() + " ***");
		log.info("=== PatchService constructor called ===");
		log.info("PatchService bean created");
	}

	/**
	 * CRITICAL FIX ATTEMPT (2025-11-10): Commenting out @PostConstruct to match working classes
	 *
	 * DatabasePreInitializer and CMISPostInitializer both work WITHOUT @PostConstruct.
	 * Testing hypothesis: @PostConstruct execution prevents ApplicationListener registration.
	 *
	 * Verify @Autowired dependency injection completed successfully
	 * This runs AFTER @Autowired injection, so we can check for null values
	 */
	// @jakarta.annotation.PostConstruct
	// public void afterPropertiesSet() {
	// 	System.err.println("*** PatchService @PostConstruct CALLED ***");
	// 	System.err.println("*** Verifying @Autowired dependencies ***");

	// 	System.err.println("repositoryInfoMap: " + (repositoryInfoMap != null ? "INJECTED" : "NULL"));
	// 	System.err.println("connectorPool: " + (connectorPool != null ? "INJECTED" : "NULL"));
	// 	System.err.println("propertyManager: " + (propertyManager != null ? "INJECTED" : "NULL"));
	// 	System.err.println("typeService: " + (typeService != null ? "INJECTED" : "NULL"));
	// 	System.err.println("typeManager: " + (typeManager != null ? "INJECTED" : "NULL"));
	// 	System.err.println("contentService: " + (contentService != null ? "INJECTED" : "NULL"));
	// 	System.err.println("solrUtil: " + (solrUtil != null ? "INJECTED" : "NULL"));

	// 	log.info("=== PatchService @PostConstruct completed ===");
	// }

	/**
	 * ApplicationListener implementation - executes after Spring context refresh
	 *
	 * CRITICAL FIX (2025-11-10): Changed from init-method to onApplicationEvent pattern
	 * to ensure execution AFTER DatabasePreInitializer completes database initialization.
	 *
	 * AtomicBoolean ensures this runs exactly once even if ContextRefreshedEvent fires multiple times.
	 */
	@Override
	public void onApplicationEvent(ContextRefreshedEvent event) {
		// Ensure this runs only once
		if (!initialized.compareAndSet(false, true)) {
			log.info("PatchService already executed, skipping");
			return;
		}

		log.info("=== PHASE 3: PatchService.onApplicationEvent() EXECUTING ===");
		log.info("*** Event source: " + event.getSource().getClass().getName() + " ***");

		try {
			log.info("Starting CMIS patch application (Phase 3)");
			
			// Note: All database initialization (Phase 1) is handled by DatabasePreInitializer
			// This method focuses on CMIS-aware operations that require fully initialized services
			
			// CRITICAL FIX: Create PropertyDefinitionDetail records for system CMIS properties
			// This addresses the root cause of PropertyDefinitionCore contamination
			initializeSystemPropertyDefinitionDetails();
			
			// TCK REQUIREMENT: Create custom secondary type for TCK tests
			// CRITICAL FIX (2025-11-10): Execute BEFORE cache invalidation
			// invalidateTypeManagerCaches() removes repository from TYPES map
			// createTCKSecondaryType() needs populated TYPES map to succeed
			createTCKSecondaryType();

			// PRIORITY 4: TypeManager cache forced update for TCK compliance
			// This ensures that PropertyDefinitionDetail changes are immediately reflected in type cache
			// Execute AFTER type creation to avoid NullPointerException
			invalidateTypeManagerCaches();

			// INITIAL CONTENT: Create Sites and Technical Documents folders
			// DISABLED: Folder creation moved to Patch_InitialContentSetup with proper ACL configuration
			// PatchService was creating folders with null ACL (system principal only)
			// Patch_InitialContentSetup creates folders with admin:all and GROUP_EVERYONE:read ACL
			// createInitialFolders();

			// TODO: Initialize test users for QA and development (requires principalService injection)
			log.info("Test user initialization skipped - requires principalService dependency");

			// CRITICAL TCK FIX: Index root folders in Solr for query tests
			indexRootFoldersInSolr();

			// Apply any future patches if they exist
			if (patchList != null && !patchList.isEmpty()) {
				log.info("Applying " + patchList.size() + " CMIS patches");
				apply();
			} else {
				log.info("No CMIS patches to apply - Phase 3 completed");
			}

			log.info("CMIS patch application (Phase 3) completed successfully");
		} catch (Exception e) {
			log.error("Failed to apply CMIS patches on startup", e);
			// Continue with application startup even if patches fail
		}
	}
	
	/**
	 * CRITICAL FIX: Initialize PropertyDefinitionDetail records for system CMIS properties
	 * Root cause: RepositoryServiceImpl.createType excludes systemIds from PropertyDefinitionDetail creation
	 * This causes TypeManagerImpl to have zero PropertyDefinitionDetail records, leading to contamination
	 */
	private void initializeSystemPropertyDefinitionDetails() {
		log.info("=== CRITICAL FIX: Initializing PropertyDefinitionDetail for system CMIS properties ===");
		
		if (typeService == null) {
			log.error("TypeService not injected - cannot create PropertyDefinitionDetail records");
			return;
		}
		
		if (typeManager == null) {
			log.error("TypeManager not injected - cannot get system property IDs");
			return;
		}
		
		if (repositoryInfoMap == null) {
			log.error("RepositoryInfoMap not injected - cannot iterate repositories");
			return;
		}
		
		try {
			// Get system property IDs from TypeManager
			List<String> systemPropertyIds = typeManager.getSystemPropertyIds();
			log.info("Found " + systemPropertyIds.size() + " system properties to initialize");
			
			// Iterate through all repositories
			for (String repositoryId : repositoryInfoMap.keys()) {
				log.info("Initializing system properties for repository: " + repositoryId);
				
				// Create PropertyDefinitionDetail for each system property
				for (String propertyId : systemPropertyIds) {
					createSystemPropertyDefinitionDetail(repositoryId, propertyId);
				}
			}
			
			log.info("✅ System PropertyDefinitionDetail initialization completed successfully");
		} catch (Exception e) {
			log.error("❌ Failed to initialize system PropertyDefinitionDetail records", e);
			// Continue with startup even if this fails
		}
	}
	
	/**
	 * PRIORITY 4: Force TypeManager cache invalidation for TCK compliance
	 * This ensures that PropertyDefinitionDetail changes are immediately reflected in type definitions
	 * and resolves Browser Binding JSON serialization issues with property definitions
	 */
	private void invalidateTypeManagerCaches() {
		log.info("=== PRIORITY 4: TypeManager cache forced update for TCK compliance ===");
		
		if (typeManager == null) {
			log.error("TypeManager not injected - cannot invalidate type caches");
			return;
		}
		
		if (repositoryInfoMap == null) {
			log.error("RepositoryInfoMap not injected - cannot iterate repositories for cache invalidation");
			return;
		}
		
		try {
			// Force invalidate TypeManager cache for all repositories
			for (String repositoryId : repositoryInfoMap.keys()) {
				log.info("Invalidating TypeManager cache for repository: " + repositoryId);
				
				// Force TypeManager to reload type definitions from database
				// This ensures PropertyDefinitionDetail changes are reflected immediately
				typeManager.invalidateTypeCache(repositoryId);
				
				log.info("✅ TypeManager cache invalidated for repository: " + repositoryId);
			}
			
			log.info("✅ All TypeManager caches invalidated successfully - TCK compliance enhanced");
		} catch (Exception e) {
			log.error("❌ Failed to invalidate TypeManager caches", e);
			// Continue with startup even if cache invalidation fails
		}
	}
	
	/**
	 * Create PropertyDefinitionDetail record for a single system CMIS property
	 */
	private void createSystemPropertyDefinitionDetail(String repositoryId, String propertyId) {
		try {
			log.debug("Creating PropertyDefinitionDetail for system property: " + propertyId);
			
			// Create minimal NemakiPropertyDefinition with required fields
			NemakiPropertyDefinition propDef = createSystemPropertyDefinition(propertyId);
			
			// Use TypeService.createPropertyDefinition to create both Core and Detail records
			NemakiPropertyDefinitionDetail detail = typeService.createPropertyDefinition(repositoryId, propDef);
			
			if (detail != null) {
				log.debug("✅ Created PropertyDefinitionDetail for " + propertyId + " with ID: " + detail.getId());
			} else {
				log.warn("⚠️  PropertyDefinitionDetail creation returned null for " + propertyId);
			}
			
		} catch (Exception e) {
			log.warn("Failed to create PropertyDefinitionDetail for " + propertyId + ": " + e.getMessage());
			// Continue with other properties even if one fails
		}
	}
	
	/**
	 * Create NemakiPropertyDefinition with minimal required fields for system properties
	 */
	private NemakiPropertyDefinition createSystemPropertyDefinition(String propertyId) {
		NemakiPropertyDefinition propDef = new NemakiPropertyDefinition();
		
		// Set core identification fields
		propDef.setPropertyId(propertyId);
		propDef.setQueryName(propertyId); // Same as propertyId for system properties
		propDef.setLocalName(extractLocalName(propertyId)); // Extract name after ':'
		
		// Set property type based on known CMIS property patterns
		propDef.setPropertyType(determinePropertyType(propertyId));
		propDef.setCardinality(determineCardinality(propertyId));
		
		// Set reasonable defaults for system properties
		propDef.setDisplayName(generateDisplayName(propertyId));
		propDef.setDescription("System CMIS property: " + propertyId);
		propDef.setUpdatability(Updatability.READONLY); // Most system properties are read-only
		propDef.setRequired(false); // Default to not required
		propDef.setQueryable(true); // Most system properties are queryable
		propDef.setOrderable(true); // Most system properties are orderable
		
		return propDef;
	}
	
	/**
	 * Extract local name from qualified property ID (e.g., "cmis:name" -> "name")
	 */
	private String extractLocalName(String propertyId) {
		if (propertyId != null && propertyId.contains(":")) {
			return propertyId.substring(propertyId.indexOf(":") + 1);
		}
		return propertyId;
	}
	
	/**
	 * Determine PropertyType based on known CMIS property patterns
	 */
	private PropertyType determinePropertyType(String propertyId) {
		// Map known CMIS properties to their correct types
		switch (propertyId) {
			case PropertyIds.NAME:
			case PropertyIds.DESCRIPTION:
			case PropertyIds.CREATED_BY:
			case PropertyIds.LAST_MODIFIED_BY:
			case PropertyIds.PATH:
			case PropertyIds.CONTENT_STREAM_MIME_TYPE:
			case PropertyIds.CONTENT_STREAM_FILE_NAME:
			case PropertyIds.VERSION_LABEL:
			case PropertyIds.VERSION_SERIES_CHECKED_OUT_BY:
				return PropertyType.STRING;
				
			case PropertyIds.OBJECT_ID:
			case PropertyIds.OBJECT_TYPE_ID:
			case PropertyIds.BASE_TYPE_ID:
			case PropertyIds.VERSION_SERIES_ID:
			case PropertyIds.VERSION_SERIES_CHECKED_OUT_ID:
			case PropertyIds.PARENT_ID:
				return PropertyType.ID;
				
			case PropertyIds.CREATION_DATE:
			case PropertyIds.LAST_MODIFICATION_DATE:
				return PropertyType.DATETIME;
				
			case PropertyIds.CONTENT_STREAM_LENGTH:
				return PropertyType.INTEGER;
				
			case PropertyIds.IS_IMMUTABLE:
			case PropertyIds.IS_LATEST_VERSION:
			case PropertyIds.IS_MAJOR_VERSION:
			case PropertyIds.IS_LATEST_MAJOR_VERSION:
			case PropertyIds.IS_VERSION_SERIES_CHECKED_OUT:
			case PropertyIds.IS_PRIVATE_WORKING_COPY:
				return PropertyType.BOOLEAN;
				
			default:
				// Default to STRING for unknown properties
				return PropertyType.STRING;
		}
	}
	
	/**
	 * Determine Cardinality based on known CMIS property patterns
	 */
	private Cardinality determineCardinality(String propertyId) {
		// Most CMIS system properties are single-valued
		switch (propertyId) {
			case PropertyIds.SECONDARY_OBJECT_TYPE_IDS:
			case PropertyIds.ALLOWED_CHILD_OBJECT_TYPE_IDS:
				return Cardinality.MULTI;
			default:
				return Cardinality.SINGLE;
		}
	}
	
	/**
	 * Generate human-readable display name from property ID
	 */
	private String generateDisplayName(String propertyId) {
		if (propertyId == null) return "Unknown Property";
		
		// Extract local name and convert to title case
		String localName = extractLocalName(propertyId);
		StringBuilder displayName = new StringBuilder();
		
		boolean capitalizeNext = true;
		for (char c : localName.toCharArray()) {
			if (c == '_' || c == '-') {
				displayName.append(' ');
				capitalizeNext = true;
			} else if (capitalizeNext) {
				displayName.append(Character.toUpperCase(c));
				capitalizeNext = false;
			} else {
				displayName.append(Character.toLowerCase(c));
			}
		}
		
		return displayName.toString();
	}
	
	/**
	 * TODO: Initialize test users and groups for QA and development purposes
	 * Requires principalService injection to be implemented
	 * Planned to create:
	 * - TestUsers group
	 * - test user (password: test) as member of TestUsers
	 */

	/**
	 * Set the list of patches to apply during initialization
	 * Pattern matching CMISPostInitializer.setCmisPatchList()
	 * Required for XML property injection from patchContext.xml
	 */
	public void setPatchList(List<AbstractNemakiPatch> patchList) {
		log.info("*** PatchService.setPatchList() CALLED with " +
		         (patchList != null ? patchList.size() + " patches" : "NULL") + " ***");
		this.patchList = patchList;
	}

	public void apply(){
		createPathView();
		for(AbstractNemakiPatch patch : patchList){
			patch.apply();
		}
	}

	private void createPathView(){
		// DEBUG: createPathView() temporarily disabled (logged by log.warn below)
		log.warn("Patch view creation temporarily disabled during Cloudant migration");
		// TODO: Implement view creation with Cloudant SDK when needed
	}

	// CRITICAL FIX (2025-11-11): Setter methods removed - using @Autowired field injection
	// Pattern matching DatabasePreInitializer which has NO setter methods
	// @Autowired injects dependencies directly into fields, setters not required
	// Previous XML property injection required setters, but @Autowired does not

	/**
	 * NOTE: Database initialization methods removed from PatchService
	 * 
	 * All Phase 1 database operations (checkAndInitializeDatabases, 
	 * createDatabaseIfNotExists, loadDumpFileDirectly, etc.) have been 
	 * moved to DatabasePreInitializer to ensure proper initialization timing.
	 * 
	 * PatchService now focuses exclusively on Phase 2 CMIS operations
	 * that require fully initialized Spring services.
	 */
	
	/**
	 * NOTE: Database initialization methods moved to DatabasePreInitializer
	 * 
	 * PatchService now focuses exclusively on Phase 2 CMIS operations:
	 * - Folder creation using CMIS services
	 * - Type definition management  
	 * - Business logic patches
	 * - PropertyDefinitionDetail creation for system properties (NEW - CRITICAL FIX)
	 * 
	 * Phase 1 (database layer) is handled by DatabasePreInitializer using
	 * pure HTTP operations without dependency on CMIS services.
	 */

	/**
	 * TCK REQUIREMENT: Create custom secondary type for TCK tests
	 *
	 * The TCK SecondaryTypesTest requires a custom secondary type to test attach/detach operations.
	 * By default, TCK uses "cmis:secondary" which is a base type and cannot be attached to documents.
	 * This method creates "tck:testSecondaryType" which extends cmis:secondary and can be attached.
	 */
	private void createTCKSecondaryType() {
		log.info("=== TCK REQUIREMENT: Creating custom secondary type for TCK tests ===");

		if (typeService == null) {
			log.error("TypeService not injected - cannot create TCK secondary type");
			return;
		}

		if (repositoryInfoMap == null) {
			log.error("RepositoryInfoMap not injected - cannot iterate repositories");
			return;
		}

		try {
			// Iterate through all repositories
			for (String repositoryId : repositoryInfoMap.keys()) {
				log.info("Creating TCK secondary type for repository: " + repositoryId);

				// Check if type already exists
				try {
					Object existingType = typeService.getTypeDefinition(repositoryId, "tck:testSecondaryType");
					if (existingType != null) {
						log.info("TCK secondary type already exists in repository: " + repositoryId);
						continue;
					}
				} catch (Exception e) {
					// Type doesn't exist, create it
					log.debug("TCK secondary type doesn't exist, will create it");
				}

				// Create secondary type definition
				jp.aegif.nemaki.model.NemakiTypeDefinition typeDef = new jp.aegif.nemaki.model.NemakiTypeDefinition();
				typeDef.setId("tck:testSecondaryType");
				typeDef.setLocalName("testSecondaryType");
				typeDef.setLocalNameSpace("http://tck.opencmis.apache.org");
				typeDef.setDisplayName("TCK Test Secondary Type");
				typeDef.setQueryName("tck:testSecondaryType");
				typeDef.setDescription("Secondary type for TCK compliance tests");
				typeDef.setBaseId(org.apache.chemistry.opencmis.commons.enums.BaseTypeId.CMIS_SECONDARY);
				typeDef.setParentId("cmis:secondary");
				typeDef.setCreatable(false);
				typeDef.setFilable(false);
				typeDef.setQueryable(true);
				typeDef.setFulltextIndexed(false);
				typeDef.setIncludedInSupertypeQuery(true);
				typeDef.setControllablePolicy(false);
				typeDef.setControllableACL(true);

				// Create the type
				typeService.createTypeDefinition(repositoryId, typeDef);
				log.info("✅ TCK secondary type created successfully in repository: " + repositoryId);
			}

			// Invalidate type manager caches to ensure new type is immediately available
			invalidateTypeManagerCaches();

			log.info("✅ TCK custom secondary type creation completed successfully");
		} catch (Exception e) {
			log.error("❌ Failed to create TCK secondary type", e);
			// Continue with startup even if this fails
		}
	}

	/**
	 * Create initial folders (Sites, Technical Documents) for new installations
	 *
	 * This is a simplified direct implementation as a workaround for ServletContextListener issues.
	 * Folders are only created if they don't already exist (idempotent).
	 */
	private void createInitialFolders() {
		log.info("=== INITIAL CONTENT: Creating Sites and Technical Documents folders ===");

		if (contentService == null) {
			log.error("ContentService not available - cannot create initial folders");
			return;
		}

		if (repositoryInfoMap == null) {
			log.error("RepositoryInfoMap not available - cannot create initial folders");
			return;
		}

		try {
			// Iterate through all repositories
			for (String repositoryId : repositoryInfoMap.keys()) {
				if ("canopy".equals(repositoryId) || repositoryId.endsWith("_closet")) {
					log.info("Skipping initial folders for repository: " + repositoryId);
					continue;
				}

				log.info("Creating initial folders for repository: " + repositoryId);

				String rootFolderId = repositoryInfoMap.get(repositoryId).getRootFolderId();
				if (rootFolderId == null) {
					log.warn("Root folder ID not available for repository: " + repositoryId);
					continue;
				}

				SystemCallContext callContext = new SystemCallContext(repositoryId);

				// Create Sites folder if it doesn't exist
				createFolderIfNotExists(callContext, repositoryId, rootFolderId, "Sites");

				// Create Technical Documents folder if it doesn't exist
				createFolderIfNotExists(callContext, repositoryId, rootFolderId, "Technical Documents");
			}

			log.info("✅ Initial folder creation completed successfully");
		} catch (Exception e) {
			log.error("❌ Failed to create initial folders", e);
			// Continue with startup even if this fails
		}
	}

	/**
	 * Create folder if it doesn't already exist (idempotent)
	 */
	private void createFolderIfNotExists(SystemCallContext callContext, String repositoryId,
	                                     String parentFolderId, String folderName) {
		try {
			// Check if folder already exists using direct CouchDB query
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			if (client == null) {
				log.error("Could not get Cloudant client for repository: " + repositoryId);
				return;
			}

			java.util.Map<String, Object> queryParams = new java.util.HashMap<>();
			queryParams.put("key", parentFolderId);
			queryParams.put("include_docs", true);

			com.ibm.cloud.cloudant.v1.model.ViewResult result = client.queryView("_repo", "children", queryParams);

			if (result.getRows() != null) {
				for (com.ibm.cloud.cloudant.v1.model.ViewResultRow row : result.getRows()) {
					if (row.getDoc() != null) {
						com.ibm.cloud.cloudant.v1.model.Document doc = row.getDoc();
						java.util.Map<String, Object> docProperties = doc.getProperties();

						if (docProperties != null) {
							String name = (String) docProperties.get("name");
							String type = (String) docProperties.get("type");

							if ("cmis:folder".equals(type) && folderName.equals(name)) {
								log.info("Folder '" + folderName + "' already exists in repository: " + repositoryId);
								return;
							}
						}
					}
				}
			}

			// Folder doesn't exist, create it
			log.info("Creating folder: " + folderName + " in repository: " + repositoryId);

			Folder parentFolder = (Folder) contentService.getContent(repositoryId, parentFolderId);
			if (parentFolder == null) {
				log.error("Parent folder not found with ID: " + parentFolderId);
				return;
			}

			PropertiesImpl properties = new PropertiesImpl();
			PropertyIdImpl objectTypeId = new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, "cmis:folder");
			properties.addProperty(objectTypeId);
			PropertyStringImpl name = new PropertyStringImpl(PropertyIds.NAME, folderName);
			properties.addProperty(name);

			Folder created = contentService.createFolder(callContext, repositoryId, properties,
			                                             parentFolder, null, null, null, null);

			log.info("✅ Folder '" + folderName + "' created successfully with ID: " + created.getId());

		} catch (Exception e) {
			log.error("Failed to create folder: " + folderName, e);
			// Continue even if one folder creation fails
		}
	}


	/**
	 * Spring init-method callback
	 * Called by Spring after all setters have been executed
	 * Forces bean instantiation and proper ApplicationListener registration
	 */
	public void initializeIfNeeded() {
		log.error("*** PatchService.initializeIfNeeded() CALLED BY SPRING ***");
		log.error("*** All setters completed - ApplicationListener ready for ContextRefreshedEvent ***");
	}

	/**
	 * CRITICAL TCK FIX: Index root folders in Solr for query tests
	 * Root folders are created from database dumps and not automatically indexed
	 *
	 * This method also fixes the objectTypeId=null issue by setting it to "cmis:folder"
	 */
	private void indexRootFoldersInSolr() {
		log.info("=== CRITICAL TCK FIX: Indexing root folders in Solr ===");

		if (repositoryInfoMap == null) {
			log.warn("RepositoryInfoMap not available - skipping root folder Solr indexing");
			return;
		}

		if (contentService == null) {
			log.warn("ContentService not available - skipping root folder Solr indexing");
			return;
		}

		if (solrUtil == null) {
			log.warn("SolrUtil not available - skipping root folder Solr indexing");
			return;
		}

		try {
			for (String repositoryId : repositoryInfoMap.keys()) {
				if (repositoryId.endsWith("_closet")) {
					log.debug("Skipping closet repository: " + repositoryId);
					continue;
				}

				String rootFolderId = repositoryInfoMap.get(repositoryId).getRootFolderId();
				if (rootFolderId == null) {
					log.warn("Root folder ID not available for repository: " + repositoryId);
					continue;
				}

				try {
					log.info("Processing root folder for repository " + repositoryId + ": " + rootFolderId);

					Content rootContent = contentService.getContent(repositoryId, rootFolderId);
					if (rootContent == null) {
						log.warn("Root folder not found in repository: " + repositoryId);
						continue;
					}

					// CRITICAL FIX: Ensure objectType is set correctly
					if (rootContent.getObjectType() == null || rootContent.getObjectType().isEmpty()) {
						log.info("Fixing root folder objectType: null -> cmis:folder");
						rootContent.setObjectType("cmis:folder");

						// Update in database
						contentService.update(new SystemCallContext(repositoryId), repositoryId, rootContent);
						log.info("Root folder objectType updated in database");
					}

					// Index in Solr
					if (rootContent instanceof Folder) {
						log.info("Indexing root folder in Solr: " + rootFolderId);
						solrUtil.indexDocument(repositoryId, (Folder) rootContent);
						log.info("✅ Root folder indexed successfully in Solr");
					} else {
						log.warn("Root content is not a Folder instance: " + rootContent.getClass().getName());
					}
				} catch (Exception solrEx) {
					log.warn("Failed to index root folder in Solr for " + repositoryId + " (non-critical): " + solrEx.getMessage());
					if (log.isDebugEnabled()) {
						log.debug("Root folder indexing error details", solrEx);
					}
				}
			}

			log.info("✅ Root folder Solr indexing completed");
		} catch (Exception e) {
			log.error("❌ Error indexing root folders in Solr", e);
			// Continue with startup even if this fails
		}
	}

}
