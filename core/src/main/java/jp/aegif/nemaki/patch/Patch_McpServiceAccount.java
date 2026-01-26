package jp.aegif.nemaki.patch;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.common.NemakiObjectType;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.spring.SpringContext;

/**
 * MCP Service Account Setup Patch
 *
 * Creates a dedicated service account for MCP (Model Context Protocol) transport-level
 * authentication. This account has NO admin privileges - it is only used for accessing
 * the MCP endpoint. Actual user permissions are controlled via sessionToken obtained
 * from the nemakiware_login tool.
 *
 * Security benefits:
 * - Claude Desktop config files don't contain admin credentials
 * - If config is leaked, attacker only gets MCP endpoint access, not admin access
 * - User permissions are still enforced via sessionToken authentication
 *
 * Password configuration (in order of priority):
 * 1. Environment variable: MCP_SERVICE_PASSWORD
 * 2. System property: mcp.service.password
 * 3. Auto-generated secure random password (logged on first startup)
 *
 * This patch is idempotent - it will not create duplicate accounts on restart.
 */
public class Patch_McpServiceAccount extends AbstractNemakiPatch {

    private static final Log log = LogFactory.getLog(Patch_McpServiceAccount.class);

    private static final String PATCH_NAME = "mcp-service-account-20260124";
    private static final String MCP_SERVICE_USER_ID = "mcp-service";
    private static final String MCP_SERVICE_USER_NAME = "MCP Service Account";

    // Environment variable and system property names for password configuration
    private static final String ENV_MCP_SERVICE_PASSWORD = "MCP_SERVICE_PASSWORD";
    private static final String PROP_MCP_SERVICE_PASSWORD = "mcp.service.password";

    // Characters allowed in auto-generated passwords
    private static final String PASSWORD_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*-_=+";
    private static final int GENERATED_PASSWORD_LENGTH = 32;

    @Override
    public String getName() {
        return PATCH_NAME;
    }

    @Override
    protected void applySystemPatch() {
        // Use ERROR level for visibility (consistent with other patches)
        log.error("=== MCP SERVICE ACCOUNT PATCH: No system-wide configuration needed ===");
    }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        // Use ERROR level for visibility (consistent with other patches in this codebase)
        log.error("=== MCP SERVICE ACCOUNT PATCH STARTED for repository: " + repositoryId + " ===");

        // Skip archive repositories
        if (repositoryId.endsWith("_closet")) {
            log.error("Skipping MCP service account for archive repository: " + repositoryId);
            return;
        }

        // Skip canopy (management repository)
        if ("canopy".equals(repositoryId)) {
            log.error("Skipping MCP service account for canopy repository");
            return;
        }

        try {
            log.error("Getting ContentService from Spring context...");
            ContentService contentService = SpringContext.getApplicationContext()
                    .getBean("ContentService", ContentService.class);
            if (contentService == null) {
                log.error("ContentService not available, cannot create MCP service account");
                return;
            }
            log.error("ContentService obtained successfully");

            // Check if user already exists
            log.error("Checking if mcp-service user already exists...");
            List<UserItem> existingUsers = contentService.getUserItems(repositoryId);
            for (UserItem user : existingUsers) {
                if (MCP_SERVICE_USER_ID.equals(user.getUserId())) {
                    log.error("MCP service account already exists in repository: " + repositoryId);
                    return;
                }
            }
            log.error("User does not exist, creating new mcp-service account...");

            // Get or generate password
            String password = getMcpServicePassword();
            boolean wasGenerated = (System.getenv(ENV_MCP_SERVICE_PASSWORD) == null
                    && System.getProperty(PROP_MCP_SERVICE_PASSWORD) == null);

            // Create new MCP service user using UserItem (same as Patch_TestUserInitialization)
            // Constructor: (id, type, userId, name, password, isAdmin, description)
            UserItem mcpServiceUser = new UserItem(
                null,                          // id - will be auto-generated
                NemakiObjectType.nemakiUser,   // type
                MCP_SERVICE_USER_ID,           // userId
                MCP_SERVICE_USER_NAME,         // name
                password,                      // password (plain text - will be hashed internally)
                false,                         // isAdmin - NOT an admin
                null                           // description
            );
            mcpServiceUser.setDescription("MCP transport-level service account (non-admin)");

            // Set additional properties (firstName, lastName, email)
            Map<String, Object> propsMap = new HashMap<>();
            propsMap.put("nemaki:firstName", "MCP");
            propsMap.put("nemaki:lastName", "Service");
            propsMap.put("nemaki:email", "mcp-service@localhost");

            List<Property> properties = new ArrayList<>();
            for (String key : propsMap.keySet()) {
                properties.add(new Property(key, propsMap.get(key)));
            }

            if (!properties.isEmpty()) {
                mcpServiceUser.setSubTypeProperties(properties);
            }

            // Create the user using ContentService
            log.error("Calling contentService.createUserItem()...");
            UserItem createdUser = contentService.createUserItem(
                new SystemCallContext(repositoryId),
                repositoryId,
                mcpServiceUser
            );

            log.error("=== MCP SERVICE ACCOUNT PATCH COMPLETED SUCCESSFULLY ===");
            log.error("   Repository: " + repositoryId);
            log.error("   User ID: " + MCP_SERVICE_USER_ID);
            log.error("   Internal ID: " + createdUser.getId());
            log.error("   Admin: false (non-privileged)");

            // Only log the password if it was auto-generated (so admin can capture it)
            if (wasGenerated) {
                log.error("   ===============================================");
                log.error("   IMPORTANT: MCP Service Password (auto-generated)");
                log.error("   Password: " + password);
                log.error("   ===============================================");
                log.error("   Please save this password - it will not be shown again!");
                log.error("   To avoid auto-generation, set environment variable:");
                log.error("      MCP_SERVICE_PASSWORD=<your-password>");
                log.error("   Or system property:");
                log.error("      -Dmcp.service.password=<your-password>");
                log.error("   ===============================================");
            } else {
                log.error("   Password: (configured via environment variable or system property)");
            }

        } catch (Exception e) {
            log.error("Failed to create MCP service account for repository: " + repositoryId, e);
            // Don't throw - patch failures should not prevent application startup
        }
    }

    /**
     * Get the MCP service account password from configuration or generate a secure random one.
     *
     * Priority order:
     * 1. Environment variable: MCP_SERVICE_PASSWORD
     * 2. System property: mcp.service.password
     * 3. Auto-generated secure random password
     *
     * @return The password to use for the MCP service account
     */
    private String getMcpServicePassword() {
        // 1. Check environment variable
        String envPassword = System.getenv(ENV_MCP_SERVICE_PASSWORD);
        if (envPassword != null && !envPassword.trim().isEmpty()) {
            log.error("MCP service password: using environment variable " + ENV_MCP_SERVICE_PASSWORD);
            return envPassword.trim();
        }

        // 2. Check system property
        String propPassword = System.getProperty(PROP_MCP_SERVICE_PASSWORD);
        if (propPassword != null && !propPassword.trim().isEmpty()) {
            log.error("MCP service password: using system property " + PROP_MCP_SERVICE_PASSWORD);
            return propPassword.trim();
        }

        // 3. Generate secure random password
        log.error("MCP service password: generating secure random password");
        return generateSecurePassword();
    }

    /**
     * Generate a secure random password.
     *
     * @return A random password of GENERATED_PASSWORD_LENGTH characters
     */
    private String generateSecurePassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(GENERATED_PASSWORD_LENGTH);

        for (int i = 0; i < GENERATED_PASSWORD_LENGTH; i++) {
            int index = random.nextInt(PASSWORD_CHARS.length());
            sb.append(PASSWORD_CHARS.charAt(index));
        }

        return sb.toString();
    }
}
