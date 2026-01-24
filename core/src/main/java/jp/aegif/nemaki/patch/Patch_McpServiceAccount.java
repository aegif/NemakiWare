package jp.aegif.nemaki.patch;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.mindrot.jbcrypt.BCrypt;

import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.model.User;
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
 * This patch is idempotent - it will not create duplicate accounts on restart.
 */
public class Patch_McpServiceAccount extends AbstractNemakiPatch {

    private static final Log log = LogFactory.getLog(Patch_McpServiceAccount.class);

    private static final String PATCH_NAME = "mcp-service-account-20260124";
    private static final String MCP_SERVICE_USER_ID = "mcp-service";
    private static final String MCP_SERVICE_USER_NAME = "MCP Service Account";
    // Default password - should be changed in production via setup script
    private static final String MCP_SERVICE_DEFAULT_PASSWORD = "mcp-secure-token-2026";

    @Override
    public String getName() {
        return PATCH_NAME;
    }

    @Override
    protected void applySystemPatch() {
        log.info("No system-wide configuration needed for MCP service account");
    }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        log.info("Creating MCP service account for repository: " + repositoryId);

        // Skip archive repositories
        if (repositoryId.endsWith("_closet")) {
            log.info("Skipping MCP service account for archive repository: " + repositoryId);
            return;
        }

        // Skip canopy (management repository)
        if ("canopy".equals(repositoryId)) {
            log.info("Skipping MCP service account for canopy repository");
            return;
        }

        try {
            PrincipalService principalService = SpringContext.getApplicationContext()
                    .getBean("PrincipalService", PrincipalService.class);
            if (principalService == null) {
                log.error("PrincipalService not available, cannot create MCP service account");
                return;
            }

            // Check if user already exists
            User existingUser = principalService.getUserById(repositoryId, MCP_SERVICE_USER_ID);
            if (existingUser != null) {
                log.info("MCP service account already exists in repository: " + repositoryId);
                return;
            }

            // Create new MCP service user
            User mcpServiceUser = new User();
            mcpServiceUser.setUserId(MCP_SERVICE_USER_ID);
            mcpServiceUser.setName(MCP_SERVICE_USER_NAME);
            mcpServiceUser.setFirstName("MCP");
            mcpServiceUser.setLastName("Service");
            mcpServiceUser.setEmail("mcp-service@localhost");
            mcpServiceUser.setAdmin(false);  // NOT an admin

            // Generate bcrypt password hash
            String passwordHash = BCrypt.hashpw(MCP_SERVICE_DEFAULT_PASSWORD, BCrypt.gensalt(10));
            mcpServiceUser.setPasswordHash(passwordHash);

            // Create the user
            principalService.createUser(repositoryId, mcpServiceUser);

            log.info("✅ MCP service account created successfully for repository: " + repositoryId);
            log.info("   User ID: " + MCP_SERVICE_USER_ID);
            log.info("   Admin: false (non-privileged)");

        } catch (Exception e) {
            log.error("Failed to create MCP service account for repository: " + repositoryId, e);
            // Don't throw - patch failures should not prevent application startup
        }
    }
}
