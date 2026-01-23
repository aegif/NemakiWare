package jp.aegif.nemaki.mcp;

/**
 * Result from executing an MCP tool.
 */
public class McpToolResult {

    private final boolean success;
    private final String content;
    private final boolean isError;

    private McpToolResult(boolean success, String content, boolean isError) {
        this.success = success;
        this.content = content;
        this.isError = isError;
    }

    public static McpToolResult success(String content) {
        return new McpToolResult(true, content, false);
    }

    public static McpToolResult error(String errorMessage) {
        return new McpToolResult(false, "{\"error\": \"" + escapeJson(errorMessage) + "\"}", true);
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

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
