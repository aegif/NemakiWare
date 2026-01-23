package jp.aegif.nemaki.mcp;

/**
 * Result of MCP login attempt.
 */
public class McpLoginResult {

    private final boolean success;
    private final String sessionToken;
    private final String userId;
    private final String repositoryId;
    private final String errorMessage;

    private McpLoginResult(boolean success, String sessionToken, String userId, String repositoryId, String errorMessage) {
        this.success = success;
        this.sessionToken = sessionToken;
        this.userId = userId;
        this.repositoryId = repositoryId;
        this.errorMessage = errorMessage;
    }

    public static McpLoginResult success(String sessionToken, String userId, String repositoryId) {
        return new McpLoginResult(true, sessionToken, userId, repositoryId, null);
    }

    public static McpLoginResult failure(String errorMessage) {
        return new McpLoginResult(false, null, null, null, errorMessage);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getSessionToken() {
        return sessionToken;
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
