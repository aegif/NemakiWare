package jp.aegif.nemaki.mcp;

/**
 * Result of MCP authentication attempt.
 */
public class McpAuthResult {

    private final boolean success;
    private final String userId;
    private final String repositoryId;
    private final String errorMessage;

    private McpAuthResult(boolean success, String userId, String repositoryId, String errorMessage) {
        this.success = success;
        this.userId = userId;
        this.repositoryId = repositoryId;
        this.errorMessage = errorMessage;
    }

    public static McpAuthResult success(String userId, String repositoryId) {
        return new McpAuthResult(true, userId, repositoryId, null);
    }

    public static McpAuthResult failure(String errorMessage) {
        return new McpAuthResult(false, null, null, errorMessage);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getUserId() {
        return userId;
    }

    public String getRepositoryId() {
        return repositoryId;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
