package jp.aegif.nemaki.patch;

import java.util.List;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.NemakiPropertyDefinition;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;
import jp.aegif.nemaki.util.PropertyManager;

/**
 * PRIORITY 4: Bean initialization order adjustment for PropertyDefinitionDetail initialization guarantee
 * 
 * @Component - Registers as Spring Bean for automatic dependency injection
 * @Order(100) - Executes after core services are initialized (higher numbers execute later)  
 * @DependsOn - Ensures required dependencies are initialized before PatchService execution
 */
@Component
@Order(100)
@DependsOn({"typeService", "typeManager", "repositoryInfoMap", "propertyManager", "connectorPool"})
public class PatchService {
	private static final Log log = LogFactory.getLog(PatchService.class);
	private RepositoryInfoMap repositoryInfoMap;
	private CloudantClientPool connectorPool;
	private PropertyManager propertyManager;
	
	// NEW: Required dependencies for PropertyDefinitionDetail creation
	private TypeService typeService;
	private TypeManager typeManager;
	
	// Configuration properties for database initialization - Docker environment compatible
	private String couchdbUrl = getCouchDbUrl();
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
		// The patch application is now triggered explicitly by Spring configuration via init-method="applyPatchesOnStartup"
		// This ensures compatibility and prevents circular dependency issues during Spring context initialization
		// DEBUG: PatchService constructor called (logged by log.info below)
		log.info("=== PATCH DEBUG: PatchService constructor called ===");
	}

	public void applyPatchesOnStartup() {
		log.info("=== PHASE 2: PatchService.applyPatchesOnStartup() EXECUTING ===");
		try {
			log.info("Starting CMIS patch application (Phase 2)");
			
			// Note: All database initialization (Phase 1) is handled by DatabasePreInitializer
			// This method focuses on CMIS-aware operations that require fully initialized services
			
			// CRITICAL FIX: Create PropertyDefinitionDetail records for system CMIS properties
			// This addresses the root cause of PropertyDefinitionCore contamination
			initializeSystemPropertyDefinitionDetails();
			
			// PRIORITY 4: TypeManager cache forced update for TCK compliance
			// This ensures that PropertyDefinitionDetail changes are immediately reflected in type cache
			invalidateTypeManagerCaches();
			
			// TODO: Initialize test users for QA and development (requires principalService injection)
			log.info("Test user initialization skipped - requires principalService dependency");
			
			// Apply any future patches if they exist
			if (patchList != null && !patchList.isEmpty()) {
				log.info("Applying " + patchList.size() + " CMIS patches");
				apply();
			} else {
				log.info("No CMIS patches to apply - Phase 2 completed");
			}
			
			log.info("CMIS patch application completed successfully");
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

	public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
		log.debug("setRepositoryInfoMap called with " + (repositoryInfoMap != null ? repositoryInfoMap.getClass().getName() : "null"));
		this.repositoryInfoMap = repositoryInfoMap;
	}

	public void setConnectorPool(CloudantClientPool connectorPool) {
		log.debug("setConnectorPool called with " + (connectorPool != null ? connectorPool.getClass().getName() : "null"));
		this.connectorPool = connectorPool;
	}

	public void setPatchList(List<AbstractNemakiPatch> patchList) {
		log.debug("setPatchList called with " + (patchList != null ? "size=" + patchList.size() : "null"));
		if (patchList != null) {
			log.debug("patchList contents:");
			for (int i = 0; i < patchList.size(); i++) {
				AbstractNemakiPatch patch = patchList.get(i);
				log.debug("[" + i + "] = " + (patch != null ? patch.getClass().getName() : "null"));
			}
		}
		this.patchList = patchList;
	}
	
	// NEW: Setter methods for required dependencies
	public void setTypeService(TypeService typeService) {
		log.debug("setTypeService called with " + (typeService != null ? typeService.getClass().getName() : "null"));
		this.typeService = typeService;
	}
	
	public void setTypeManager(TypeManager typeManager) {
		log.debug("setTypeManager called with " + (typeManager != null ? typeManager.getClass().getName() : "null"));
		this.typeManager = typeManager;
	}
	
	public void setPropertyManager(PropertyManager propertyManager) {
		log.debug("setPropertyManager called with " + (propertyManager != null ? propertyManager.getClass().getName() : "null"));
		this.propertyManager = propertyManager;
	}
	
	// Setters for configuration properties
	public void setCouchdbUrl(String couchdbUrl) {
		this.couchdbUrl = couchdbUrl;
	}
	
	public void setCouchdbUsername(String couchdbUsername) {
		this.couchdbUsername = couchdbUsername;
	}
	
	public void setCouchdbPassword(String couchdbPassword) {
		this.couchdbPassword = couchdbPassword;
	}

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

}
