package jp.aegif.nemaki.mcp;

/**
 * Result from executing an MCP tool.
 *
 * Use McpToolResultFactory to create instances with proper JSON escaping.
 */
public class McpToolResult {

    private final boolean success;
    private final String content;
    private final boolean isError;

    /**
     * Package-private constructor. Use McpToolResultFactory to create instances.
     */
    McpToolResult(boolean success, String content, boolean isError) {
        this.success = success;
        this.content = content;
        this.isError = isError;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getContent() {
        return content;
    }

    public boolean isError() {
        return isError;
    }
}
