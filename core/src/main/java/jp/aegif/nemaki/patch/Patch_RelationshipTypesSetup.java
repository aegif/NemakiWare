package jp.aegif.nemaki.patch;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;

/**
 * Relationship Types Setup Patch
 * 
 * This patch registers standard relationship types using CMIS REST API to ensure:
 * 1. Type registration functionality is working correctly
 * 2. Standard relationship types are available for users
 * 3. System setup health check through actual CMIS API usage
 * 
 * Registered relationship types:
 * - nemaki:parentChildRelationship - For parent-child relationships
 * - nemaki:bidirectionalRelationship - For bidirectional relationships
 * 
 * These are based on existing user environment definitions and provide
 * essential relationship modeling capabilities for ECM systems.
 */
public class Patch_RelationshipTypesSetup extends AbstractNemakiPatch {
    
    private static final Log log = LogFactory.getLog(Patch_RelationshipTypesSetup.class);
    
    // Patch configuration
    private static final String PATCH_NAME = "relationship-types-setup-20250725";
    
    // CMIS API configuration
    private static final String CORE_BASE_URL = "http://localhost:8080/core";
    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "admin";
    
    // Type registration XML templates
    private static final String PARENT_CHILD_RELATIONSHIP_XML = 
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<types>\n" +
        "  <type name=\"nemaki:parentChildRelationship\">\n" +
        "    <title>Parent-Child Relationship</title>\n" +
        "    <parent>cmis:relationship</parent>\n" +
        "    <properties>\n" +
        "    </properties>\n" +
        "  </type>\n" +
        "</types>";
    
    private static final String BIDIRECTIONAL_RELATIONSHIP_XML = 
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<types>\n" +
        "  <type name=\"nemaki:bidirectionalRelationship\">\n" +
        "    <title>Bidirectional Relationship</title>\n" +
        "    <parent>cmis:relationship</parent>\n" +
        "    <properties>\n" +
        "    </properties>\n" +
        "  </type>\n" +
        "</types>";
    
    public Patch_RelationshipTypesSetup() {
        System.out.println("=== PATCH DEBUG: Patch_RelationshipTypesSetup constructor called ===");
        log.info("=== PATCH DEBUG: Patch_RelationshipTypesSetup constructor called ===");
    }
    
    @Override
    public String getName() {
        return PATCH_NAME;
    }
    
    @Override
    protected void applySystemPatch() {
        // No system-wide patches needed
        log.info("No system-wide patches needed for Relationship Types Setup");
    }
    
    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        log.info("=== PATCH DEBUG: Starting Relationship Types Setup Patch for repository: " + repositoryId + " ===");
        System.out.println("=== PATCH DEBUG: Starting Relationship Types Setup Patch for repository: " + repositoryId + " ===");
        
        if ("canopy".equals(repositoryId)) {
            log.info("=== PATCH DEBUG: Skipping Relationship Types Setup for canopy - type registration applies to content repositories only ===");
            System.out.println("=== PATCH DEBUG: Skipping Relationship Types Setup for canopy - type registration applies to content repositories only ===");
            return;
        }
        
        // CRITICAL FIX: Skip this patch during initialization to prevent deadlock
        // The patch tries to access HTTP endpoints while they're still being initialized
        log.warn("=== PATCH DEBUG: TEMPORARILY SKIPPING Relationship Types Setup to prevent initialization deadlock ===");
        System.out.println("=== PATCH DEBUG: TEMPORARILY SKIPPING Relationship Types Setup to prevent initialization deadlock ===");
        return;
        
        /*
        try {
            // Wait for core application to be fully ready
            if (!waitForCoreApplication()) {
                log.warn("Core application not ready, skipping Relationship Types Setup for repository: " + repositoryId);
                return;
            }
            
            // Register parent-child relationship type
            boolean parentChildSuccess = registerRelationshipType(repositoryId, 
                "nemaki:parentChildRelationship", PARENT_CHILD_RELATIONSHIP_XML);
            
            // Register bidirectional relationship type  
            boolean bidirectionalSuccess = registerRelationshipType(repositoryId,
                "nemaki:bidirectional…", BIDIRECTIONAL_RELATIONSHIP_XML);
            
            // Verify types are available through CMIS endpoints
            if (parentChildSuccess && bidirectionalSuccess) {
                verifyTypesAvailability(repositoryId);
            }
            
            // Refresh type manager to ensure types are loaded
            refreshTypeManager();
            
            log.info("Relationship Types Setup Patch completed for repository: " + repositoryId);
            log.info("Standard relationship types are now available for modeling relationships between objects");
            
        } catch (Exception e) {
            log.error("Error during Relationship Types Setup Patch for repository: " + repositoryId, e);
            // Don't throw - patch failures should not prevent application startup
        }
        */
    }
    
    /**
     * Wait for core application to be ready for API calls
     */
    private boolean waitForCoreApplication() {
        log.info("Checking if core application is ready for CMIS API calls...");
        
        for (int i = 0; i < 30; i++) { // Wait up to 30 seconds
            try {
                HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
                
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(CORE_BASE_URL + "/atom/bedroom"))
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (ADMIN_USERNAME + ":" + ADMIN_PASSWORD).getBytes()))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
                
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    log.info("Core application is ready for CMIS API calls");
                    return true;
                }
                
                log.debug("Core application not ready yet (status: " + response.statusCode() + "), waiting...");
                Thread.sleep(1000);
                
            } catch (Exception e) {
                log.debug("Core application not ready yet (" + e.getMessage() + "), waiting...");
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        
        log.warn("Core application did not become ready within timeout period");
        return false;
    }
    
    /**
     * Register a relationship type using CMIS REST API
     */
    private boolean registerRelationshipType(String repositoryId, String typeId, String xmlDefinition) {
        log.info("Registering relationship type: " + typeId + " for repository: " + repositoryId);
        
        try {
            // Check if type already exists
            if (checkTypeExists(repositoryId, typeId)) {
                log.info("Relationship type " + typeId + " already exists, skipping registration");
                return true;
            }
            
            // Register type using CMIS REST API
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
            
            String registerUrl = CORE_BASE_URL + "/rest/repo/" + repositoryId + "/type/register-simple";
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(registerUrl))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (ADMIN_USERNAME + ":" + ADMIN_PASSWORD).getBytes()))
                .header("Content-Type", "application/xml")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(xmlDefinition))
                .build();
            
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            log.info("Type registration response for " + typeId + ": HTTP " + response.statusCode());
            log.debug("Response body: " + response.body());
            
            if (response.statusCode() == 200) {
                // Parse JSON response to check success
                if (response.body().contains("\"success\":true")) {
                    log.info("Successfully registered relationship type: " + typeId);
                    return true;
                } else {
                    log.warn("Type registration reported failure for " + typeId + ": " + response.body());
                    return false;
                }
            } else {
                log.warn("Type registration failed for " + typeId + " with HTTP " + response.statusCode() + 
                        ": " + response.body());
                return false;
            }
            
        } catch (Exception e) {
            log.error("Error registering relationship type: " + typeId, e);
            return false;
        }
    }
    
    /**
     * Check if a type already exists
     */
    private boolean checkTypeExists(String repositoryId, String typeId) {
        try {
            // Use TypeService to check if type exists
            TypeService typeService = patchUtil.getTypeService();
            if (typeService != null) {
                return typeService.getTypeDefinition(repositoryId, typeId) != null;
            }
            
            // Fallback: check via CMIS REST API
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
            
            String typeUrl = CORE_BASE_URL + "/atom/" + repositoryId + "/types/" + typeId;
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(typeUrl))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (ADMIN_USERNAME + ":" + ADMIN_PASSWORD).getBytes()))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
            
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            return response.statusCode() == 200;
            
        } catch (Exception e) {
            log.debug("Error checking if type exists: " + typeId + " - " + e.getMessage());
            return false; // Assume it doesn't exist if we can't check
        }
    }
    
    /**
     * Verify that registered types are available through CMIS endpoints
     */
    private void verifyTypesAvailability(String repositoryId) {
        log.info("Verifying relationship types are available through CMIS endpoints...");
        
        String[] typesToVerify = {
            "nemaki:parentChildRelationship",
            "nemaki:bidirectionalRelationship"
        };
        
        for (String typeId : typesToVerify) {
            try {
                if (verifyTypeAvailability(repositoryId, typeId)) {
                    log.info("✓ Relationship type " + typeId + " is available through CMIS");
                } else {
                    log.warn("✗ Relationship type " + typeId + " is not accessible through CMIS");
                }
            } catch (Exception e) {
                log.warn("Error verifying type availability: " + typeId, e);
            }
        }
    }
    
    /**
     * Verify a specific type is available through CMIS
     */
    private boolean verifyTypeAvailability(String repositoryId, String typeId) {
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
            
            String typeUrl = CORE_BASE_URL + "/atom/" + repositoryId + "/types/" + typeId;
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(typeUrl))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (ADMIN_USERNAME + ":" + ADMIN_PASSWORD).getBytes()))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
            
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            boolean available = response.statusCode() == 200 && 
                              response.body().contains(typeId);
            
            if (available) {
                log.debug("Type " + typeId + " verified available with response: " + 
                         response.body().substring(0, Math.min(200, response.body().length())));
            }
            
            return available;
            
        } catch (Exception e) {
            log.debug("Error verifying type availability: " + typeId + " - " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Refresh type manager to ensure new types are loaded
     */
    private void refreshTypeManager() {
        try {
            TypeManager typeManager = patchUtil.getTypeManager();
            if (typeManager != null) {
                log.info("Refreshing type manager to load newly registered types...");
                typeManager.refreshTypes();
                log.info("Type manager refreshed successfully");
            } else {
                log.warn("TypeManager not available for refresh");
            }
        } catch (Exception e) {
            log.warn("Error refreshing type manager", e);
        }
    }
}