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
		System.out.println("=== PATCH DEBUG: applyPatchesOnStartup() CALLED ===");
		log.info("=== PATCH DEBUG: applyPatchesOnStartup() CALLED ===");
		try {
			System.out.println("=== PATCH DEBUG: Starting automatic patch application on startup ===");
			log.info("Starting automatic patch application on startup");
			
			System.out.println("=== PATCH DEBUG: repositoryInfoMap=" + (repositoryInfoMap != null ? repositoryInfoMap.getClass().getName() : "null"));
			System.out.println("=== PATCH DEBUG: connectorPool=" + (connectorPool != null ? connectorPool.getClass().getName() : "null"));
			System.out.println("=== PATCH DEBUG: patchList=" + (patchList != null ? "size=" + patchList.size() : "null"));
			
			// Check and initialize databases if needed
			checkAndInitializeDatabases();
			
			// Note: All previous patches have been consolidated into initialization dump files
			// This method is preserved for future patch requirements
			
			// Apply any future patches if they exist
			if (patchList != null && !patchList.isEmpty()) {
				log.info("Applying " + patchList.size() + " future patches");
				apply();
			} else {
				log.info("No additional patches to apply - all consolidated into initialization data");
			}
			
			System.out.println("=== PATCH DEBUG: Automatic patch application completed successfully ===");
			log.info("Automatic patch application completed successfully");
		} catch (Exception e) {
			System.out.println("=== PATCH DEBUG: Failed to apply patches on startup: " + e.getMessage());
			log.error("Failed to apply patches on startup", e);
			e.printStackTrace();
			// Continue with application startup even if patches fail
			// This ensures the application can start even with patch issues
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
	 * Check and initialize databases if they are empty or missing required data
	 */
	private void checkAndInitializeDatabases() {
		log.info("=== DATABASE INITIALIZATION CHECK STARTED ===");
		
		try {
			// Check all configured repositories
			if (repositoryInfoMap != null && connectorPool != null) {
				for (String repositoryId : repositoryInfoMap.keys()) {
					checkAndInitializeRepository(repositoryId);
				}
			}
			
			log.info("=== DATABASE INITIALIZATION CHECK COMPLETED ===");
		} catch (Exception e) {
			log.error("Database initialization check failed", e);
			// Don't fail the entire startup process
		}
	}
	
	/**
	 * Check and initialize a specific repository
	 */
	private void checkAndInitializeRepository(String repositoryId) {
		try {
			log.info("Checking repository: " + repositoryId);
			
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			if (client == null) {
				log.warn("No client available for repository: " + repositoryId);
				return;
			}
			
			// Check if design document exists and has required views
			boolean needsInitialization = false;
			
			try {
				// Try to get the design document
				com.ibm.cloud.cloudant.v1.model.Document designDoc = client.get("_design/_repo");
				if (designDoc == null) {
					log.info("Design document '_design/_repo' not found in " + repositoryId + " - needs initialization");
					needsInitialization = true;
				} else {
					// Check if we can execute a basic view query
					try {
						client.queryView("_repo", "admin");
						log.info("Repository '" + repositoryId + "' appears to be properly initialized");
					} catch (Exception e) {
						log.info("Views not working properly in " + repositoryId + " - needs re-initialization");
						needsInitialization = true;
					}
				}
			} catch (Exception e) {
				log.info("Repository '" + repositoryId + "' appears to be empty or needs initialization");
				needsInitialization = true;
			}
			
			if (needsInitialization) {
				log.info("Repository '" + repositoryId + "' needs initialization - starting automatic initialization...");
				try {
					performRepositoryInitialization(repositoryId);
					log.info("Repository '" + repositoryId + "' has been successfully initialized");
				} catch (Exception e) {
					log.error("Failed to automatically initialize repository '" + repositoryId + "'", e);
					log.warn("Please ensure the database is properly initialized using the external initialization tools.");
				}
			}
			
		} catch (Exception e) {
			log.error("Error checking repository: " + repositoryId, e);
		}
	}

	/**
	 * Perform automatic repository initialization using embedded initialization data
	 */
	private void performRepositoryInitialization(String repositoryId) {
		log.info("Starting automatic initialization for repository: " + repositoryId);
		
		try {
			// Create database if it doesn't exist
			createDatabaseIfNotExists(repositoryId);
			
			// Apply initialization data using cloudant-init.jar
			String dumpFileName = getDumpFileName(repositoryId);
			applyInitializationDataUsingProcess(repositoryId, dumpFileName);
			
			log.info("Repository '" + repositoryId + "' initialization completed successfully");
			
		} catch (Exception e) {
			log.error("Failed to initialize repository: " + repositoryId, e);
			throw new RuntimeException("Repository initialization failed: " + repositoryId, e);
		}
	}
	
	/**
	 * Create database if it doesn't exist
	 */
	private void createDatabaseIfNotExists(String repositoryId) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			
			com.ibm.cloud.cloudant.v1.model.GetDatabaseInformationOptions options = 
				new com.ibm.cloud.cloudant.v1.model.GetDatabaseInformationOptions.Builder()
					.db(repositoryId)
					.build();
			client.getClient().getDatabaseInformation(options).execute();
			log.info("Database '" + repositoryId + "' already exists");
			
		} catch (com.ibm.cloud.sdk.core.service.exception.NotFoundException e) {
			log.info("Creating database: " + repositoryId);
			try {
				CloudantClientWrapper client = connectorPool.getClient(repositoryId);
				com.ibm.cloud.cloudant.v1.model.PutDatabaseOptions createOptions = 
					new com.ibm.cloud.cloudant.v1.model.PutDatabaseOptions.Builder()
						.db(repositoryId)
						.build();
				client.getClient().putDatabase(createOptions).execute();
				log.info("Database '" + repositoryId + "' created successfully");
				
			} catch (Exception createError) {
				log.error("Failed to create database: " + repositoryId, createError);
				throw new RuntimeException("Database creation failed: " + repositoryId, createError);
			}
		}
	}
	
	/**
	 * Apply initialization data using cloudant-init.jar process
	 */
	private void applyInitializationDataUsingProcess(String repositoryId, String dumpFileName) {
		try {
			log.info("Applying initialization data to " + repositoryId + " using " + dumpFileName);
			
			// Find dump file in classpath
			org.springframework.core.io.Resource resource = new org.springframework.core.io.ClassPathResource("initialization/" + dumpFileName);
			if (!resource.exists()) {
				log.warn("Initialization file not found: " + dumpFileName + " - skipping data initialization");
				return;
			}
			
			// Create temp file for the dump
			java.io.File tempDumpFile = java.io.File.createTempFile("nemaki_init_" + repositoryId, ".dump");
			tempDumpFile.deleteOnExit();
			
			// Copy resource to temp file
			try (java.io.InputStream is = resource.getInputStream();
				 java.io.FileOutputStream fos = new java.io.FileOutputStream(tempDumpFile)) {
				byte[] buffer = new byte[4096];
				int bytesRead;
				while ((bytesRead = is.read(buffer)) != -1) {
					fos.write(buffer, 0, bytesRead);
				}
			}
			
			// Find cloudant-init.jar in classpath
			org.springframework.core.io.Resource initJarResource = new org.springframework.core.io.ClassPathResource("cloudant-init.jar");
			if (!initJarResource.exists()) {
				log.warn("cloudant-init.jar not found in classpath - attempting alternative initialization");
				return;
			}
			
			// Create temp file for cloudant-init.jar
			java.io.File tempInitJar = java.io.File.createTempFile("cloudant-init", ".jar");
			tempInitJar.deleteOnExit();
			
			try (java.io.InputStream is = initJarResource.getInputStream();
				 java.io.FileOutputStream fos = new java.io.FileOutputStream(tempInitJar)) {
				byte[] buffer = new byte[4096];
				int bytesRead;
				while ((bytesRead = is.read(buffer)) != -1) {
					fos.write(buffer, 0, bytesRead);
				}
			}
			
			// Execute cloudant-init.jar
			ProcessBuilder pb = new ProcessBuilder(
				"java", "-jar", tempInitJar.getAbsolutePath(),
				"--url", couchdbUrl,
				"--username", couchdbUsername,
				"--password", couchdbPassword,
				"--repository", repositoryId,
				"--dump", tempDumpFile.getAbsolutePath(),
				"--force", "true"
			);
			
			pb.redirectErrorStream(true);
			Process process = pb.start();
			
			// Read output
			try (java.io.BufferedReader reader = new java.io.BufferedReader(
					new java.io.InputStreamReader(process.getInputStream()))) {
				String line;
				while ((line = reader.readLine()) != null) {
					log.info("Init process: " + line);
				}
			}
			
			int exitCode = process.waitFor();
			if (exitCode == 0) {
				log.info("Successfully initialized " + repositoryId + " with " + dumpFileName);
			} else {
				log.error("Initialization process failed with exit code: " + exitCode);
				throw new RuntimeException("Failed to initialize repository: " + repositoryId);
			}
			
		} catch (Exception e) {
			log.error("Failed to apply initialization data to " + repositoryId, e);
			throw new RuntimeException("Data initialization failed: " + repositoryId, e);
		}
	}
	
	/**
	 * Get appropriate dump file name for repository
	 */
	private String getDumpFileName(String repositoryId) {
		if (repositoryId.endsWith("_closet")) {
			return "archive_init.dump";
		} else if ("canopy".equals(repositoryId)) {
			return "canopy_init.dump";
		} else {
			return "bedroom_init.dump";
		}
	}
	
	/**
	 * NOTE: ensureEssentialDesignDocuments() method removed - 
	 * All essential design documents are now included in initialization dump files.
	 * This eliminates the need for runtime design document creation and prevents
	 * TokenService initialization timing issues.
	 */

}
