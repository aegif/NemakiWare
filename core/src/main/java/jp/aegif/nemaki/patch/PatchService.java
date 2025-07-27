package jp.aegif.nemaki.patch;

import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;

public class PatchService {
	private static final Log log = LogFactory.getLog(PatchService.class);
	private RepositoryInfoMap repositoryInfoMap;
	private CloudantClientPool connectorPool;
	
	// Configuration properties for database initialization
	private String couchdbUrl = "http://localhost:5984";
	private String couchdbUsername = "admin";
	private String couchdbPassword = "password";

	private List<AbstractNemakiPatch> patchList;
	
	public PatchService() {
		// The patch application is now triggered explicitly by Spring configuration via init-method="applyPatchesOnStartup"
		// This ensures compatibility and prevents circular dependency issues during Spring context initialization
		System.out.println("=== PATCH DEBUG: PatchService constructor called ===");
		log.info("=== PATCH DEBUG: PatchService constructor called ===");
	}

	public void applyPatchesOnStartup() {
		log.info("=== PHASE 2: PatchService.applyPatchesOnStartup() EXECUTING ===");
		try {
			log.info("Starting CMIS patch application (Phase 2)");
			
			// Note: All database initialization (Phase 1) is handled by DatabasePreInitializer
			// This method focuses on CMIS-aware operations that require fully initialized services
			
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

	public void apply(){
		createPathView();
		for(AbstractNemakiPatch patch : patchList){
			patch.apply();
		}
	}

	private void createPathView(){
		System.out.println("=== PATCH DEBUG: createPathView() CALLED (temporarily disabled) ===");
		log.warn("Patch view creation temporarily disabled during Cloudant migration");
		// TODO: Implement view creation with Cloudant SDK when needed
	}

	public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
		System.out.println("=== PATCH DEBUG: setRepositoryInfoMap called with " + (repositoryInfoMap != null ? repositoryInfoMap.getClass().getName() : "null"));
		this.repositoryInfoMap = repositoryInfoMap;
	}

	public void setConnectorPool(CloudantClientPool connectorPool) {
		System.out.println("=== PATCH DEBUG: setConnectorPool called with " + (connectorPool != null ? connectorPool.getClass().getName() : "null"));
		this.connectorPool = connectorPool;
	}

	public void setPatchList(List<AbstractNemakiPatch> patchList) {
		System.out.println("=== PATCH DEBUG: setPatchList called with " + (patchList != null ? "size=" + patchList.size() : "null"));
		if (patchList != null) {
			System.out.println("=== PATCH DEBUG: patchList contents:");
			for (int i = 0; i < patchList.size(); i++) {
				AbstractNemakiPatch patch = patchList.get(i);
				System.out.println("=== PATCH DEBUG: [" + i + "] = " + (patch != null ? patch.getClass().getName() : "null"));
			}
		}
		this.patchList = patchList;
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
	 * 
	 * Phase 1 (database layer) is handled by DatabasePreInitializer using
	 * pure HTTP operations without dependency on CMIS services.
	 */

}
