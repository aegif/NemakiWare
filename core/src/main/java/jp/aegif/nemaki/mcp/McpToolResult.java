package jp.aegif.nemaki.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Result from executing an MCP tool.
 */
public class McpToolResult {

    // Thread-safe singleton ObjectMapper for JSON serialization
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
        String safeMessage;
        try {
            // Use ObjectMapper for proper JSON escaping (handles all control chars and Unicode)
            safeMessage = OBJECT_MAPPER.writeValueAsString(errorMessage);
            // Remove surrounding quotes added by writeValueAsString
            safeMessage = safeMessage.substring(1, safeMessage.length() - 1);
        } catch (JsonProcessingException e) {
            // Fallback to simple replacement if ObjectMapper fails
            safeMessage = errorMessage != null ? errorMessage.replaceAll("[\"\\\\]", "") : "";
        }
        return new McpToolResult(false, "{\"error\": \"" + safeMessage + "\"}", true);
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
