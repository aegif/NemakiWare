package jp.aegif.nemaki.mcp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * NemakiWare MCP Server implementation.
 *
 * Provides MCP tools for LLM integration via HTTP/SSE transport.
 * This server exposes tools for:
 * - User authentication (login/logout)
 * - RAG semantic search
 * - Similar documents search
 *
 * The server supports two authentication modes:
 * 1. Header-based: Authorization header with Basic/Bearer token
 * 2. Tool-based: Login tool to obtain session token
 */
@Component
public class NemakiwareMcpServer {

    private static final Logger log = LoggerFactory.getLogger(NemakiwareMcpServer.class);

    private final McpToolsProvider toolsProvider;
    private final McpAuthenticationHandler authHandler;
    private final ObjectMapper objectMapper;

    @Autowired
    public NemakiwareMcpServer(
            McpToolsProvider toolsProvider,
            McpAuthenticationHandler authHandler) {
        this.toolsProvider = toolsProvider;
        this.authHandler = authHandler;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Get server information for MCP initialization.
     */
    public Map<String, Object> getServerInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("name", "nemakiware-mcp");
        info.put("version", "1.0.0");
        info.put("protocolVersion", "2024-11-05");
        return info;
    }

    /**
     * Get server capabilities.
     */
    public Map<String, Object> getCapabilities() {
        Map<String, Object> capabilities = new HashMap<>();

        // Tools capability
        Map<String, Object> tools = new HashMap<>();
        tools.put("listChanged", false);
        capabilities.put("tools", tools);

        return capabilities;
    }

    /**
     * List available tools.
     */
    public List<Map<String, Object>> listTools() {
        List<McpToolDefinition> definitions = toolsProvider.getToolDefinitions();

        return definitions.stream().map(def -> {
            Map<String, Object> tool = new HashMap<>();
            tool.put("name", def.getName());
            tool.put("description", def.getDescription());
            try {
                tool.put("inputSchema", objectMapper.readValue(def.getInputSchema(), Map.class));
            } catch (JsonProcessingException e) {
                log.error("Failed to parse input schema for tool {}: {}", def.getName(), e.getMessage());
                tool.put("inputSchema", Map.of("type", "object"));
            }
            return tool;
        }).toList();
    }

    /**
     * Execute a tool by name.
     *
     * @param toolName The name of the tool to execute
     * @param arguments The tool arguments
     * @param headers Request headers for authentication
     * @return Tool execution result
     */
    public McpToolResult executeTool(String toolName, Map<String, Object> arguments, Map<String, String> headers) {
        log.debug("Executing MCP tool: {} with arguments: {}", toolName, arguments);

        switch (toolName) {
            case "nemakiware_login":
                return toolsProvider.executeLoginTool(arguments);

            case "nemakiware_logout":
                return toolsProvider.executeLogoutTool(arguments);

            case "nemakiware_rag_search":
            case "nemakiware_similar_documents":
                return executeAuthenticatedTool(toolName, arguments, headers);

            default:
                log.warn("Unknown tool requested: {}", toolName);
                return McpToolResult.error("Unknown tool: " + toolName);
        }
    }

    /**
     * Execute a tool that requires authentication.
     */
    private McpToolResult executeAuthenticatedTool(String toolName, Map<String, Object> arguments, Map<String, String> headers) {
        // Get repository ID from arguments or use default
        String repositoryId = getStringArg(arguments, "repositoryId", "bedroom");

        // Authenticate the request
        McpAuthResult authResult = authHandler.authenticate(repositoryId, headers);
        if (!authResult.isSuccess()) {
            log.warn("Authentication failed for tool {}: {}", toolName, authResult.getErrorMessage());
            return McpToolResult.error("Authentication required: " + authResult.getErrorMessage());
        }

        String userId = authResult.getUserId();
        log.debug("Authenticated user {} for tool {}", userId, toolName);

        // Execute the tool
        switch (toolName) {
            case "nemakiware_rag_search":
                return toolsProvider.executeRagSearchTool(arguments, repositoryId, userId);

            case "nemakiware_similar_documents":
                return toolsProvider.executeSimilarDocumentsTool(arguments, repositoryId, userId);

            default:
                return McpToolResult.error("Unknown tool: " + toolName);
        }
    }

    /**
     * Handle MCP JSON-RPC request.
     *
     * @param request The JSON-RPC request
     * @param headers Request headers
     * @return JSON-RPC response
     */
    public Map<String, Object> handleRequest(Map<String, Object> request, Map<String, String> headers) {
        String method = (String) request.get("method");
        Object id = request.get("id");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) request.getOrDefault("params", new HashMap<>());

        log.debug("MCP request: method={}, id={}", method, id);

        try {
            Object result = handleMethod(method, params, headers);
            return createResponse(id, result, null);
        } catch (Exception e) {
            log.error("Error handling MCP request: {}", e.getMessage(), e);
            return createResponse(id, null, Map.of(
                "code", -32603,
                "message", "Internal error: " + e.getMessage()
            ));
        }
    }

    private Object handleMethod(String method, Map<String, Object> params, Map<String, String> headers) {
        switch (method) {
            case "initialize":
                return handleInitialize(params);

            case "tools/list":
                return Map.of("tools", listTools());

            case "tools/call":
                return handleToolCall(params, headers);

            case "ping":
                return Map.of();

            default:
                throw new IllegalArgumentException("Unknown method: " + method);
        }
    }

    private Map<String, Object> handleInitialize(Map<String, Object> params) {
        Map<String, Object> result = new HashMap<>();
        result.put("protocolVersion", "2024-11-05");
        result.put("serverInfo", getServerInfo());
        result.put("capabilities", getCapabilities());
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleToolCall(Map<String, Object> params, Map<String, String> headers) {
        String toolName = (String) params.get("name");
        Map<String, Object> arguments = (Map<String, Object>) params.getOrDefault("arguments", new HashMap<>());

        McpToolResult result = executeTool(toolName, arguments, headers);

        Map<String, Object> response = new HashMap<>();
        response.put("content", List.of(Map.of(
            "type", "text",
            "text", result.getContent()
        )));
        response.put("isError", result.isError());

        return response;
    }

    private Map<String, Object> createResponse(Object id, Object result, Map<String, Object> error) {
        Map<String, Object> response = new HashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);

        if (error != null) {
            response.put("error", error);
        } else {
            response.put("result", result);
        }

        return response;
    }

    private String getStringArg(Map<String, Object> args, String key, String defaultValue) {
        Object value = args.get(key);
        if (value == null) return defaultValue;
        return value.toString();
    }
}
