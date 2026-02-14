package jp.aegif.nemaki.mcp;

import java.io.File;
import java.math.BigInteger;

import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.server.CallContext;

/**
 * Simple CallContext implementation for MCP (Model Context Protocol) operations.
 *
 * This provides a minimal implementation that satisfies the CallContext interface
 * for permission checking operations without requiring a full HTTP request context.
 *
 * Only the essential fields (repositoryId, username) are populated as they're
 * the only values typically used in permission checks.
 */
public class McpCallContext implements CallContext {

    private final String repositoryId;
    private final String username;

    /**
     * Create a new MCP call context.
     *
     * @param repositoryId The repository ID
     * @param username The username performing the operation
     */
    public McpCallContext(String repositoryId, String username) {
        this.repositoryId = repositoryId;
        this.username = username;
    }

    @Override
    public String getBinding() {
        // MCP uses a custom binding type to distinguish from standard CMIS bindings
        return "mcp";
    }

    @Override
    public boolean isObjectInfoRequired() {
        return false;
    }

    @Override
    public Object get(String key) {
        // Return values for standard keys
        if (REPOSITORY_ID.equals(key)) {
            return repositoryId;
        }
        if (USERNAME.equals(key)) {
            return username;
        }
        if (CMIS_VERSION.equals(key)) {
            return CmisVersion.CMIS_1_1;
        }
        return null;
    }

    @Override
    public CmisVersion getCmisVersion() {
        return CmisVersion.CMIS_1_1;
    }

    @Override
    public String getRepositoryId() {
        return repositoryId;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public String getPassword() {
        // Password is not stored in MCP context for security
        return null;
    }

    @Override
    public String getLocale() {
        return null;
    }

    @Override
    public BigInteger getOffset() {
        return null;
    }

    @Override
    public BigInteger getLength() {
        return null;
    }

    @Override
    public File getTempDirectory() {
        return null;
    }

    @Override
    public boolean encryptTempFiles() {
        return false;
    }

    @Override
    public int getMemoryThreshold() {
        return 0;
    }

    @Override
    public long getMaxContentSize() {
        return 0;
    }
}
