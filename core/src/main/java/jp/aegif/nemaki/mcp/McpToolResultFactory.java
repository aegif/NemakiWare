package jp.aegif.nemaki.mcp;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Factory for creating MCP tool results.
 *
 * Uses Spring-managed ObjectMapper for consistent JSON serialization
 * across the application.
 */
@Component
public class McpToolResultFactory {

    private static final Logger log = LoggerFactory.getLogger(McpToolResultFactory.class);

    private final ObjectMapper objectMapper;

    @Autowired
    public McpToolResultFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Create a successful result with content.
     */
    public McpToolResult success(String content) {
        return new McpToolResult(true, content, false);
    }

    /**
     * Create an error result with proper JSON escaping.
     */
    public McpToolResult error(String errorMessage) {
        try {
            Map<String, String> errorObj = Map.of("error", errorMessage != null ? errorMessage : "Unknown error");
            String json = objectMapper.writeValueAsString(errorObj);
            return new McpToolResult(false, json, true);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize error message, using fallback: {}", e.getMessage());
            return new McpToolResult(false, "{\"error\": \"Internal error\"}", true);
        }
    }
}
