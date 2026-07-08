package jp.aegif.nemaki.mcp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    // JSON-RPC error codes
    private static final int ERROR_PARSE = -32700;
    private static final int ERROR_INVALID_REQUEST = -32600;
    private static final int ERROR_METHOD_NOT_FOUND = -32601;
    private static final int ERROR_INVALID_PARAMS = -32602;
    private static final int ERROR_INTERNAL = -32603;

    private final McpToolsProvider toolsProvider;
    private final McpAuthenticationHandler authHandler;
    private final McpToolResultFactory resultFactory;
    private final ObjectMapper objectMapper;
    private final String defaultRepository;
    private final jp.aegif.nemaki.util.PropertyManager propertyManager;

    @Autowired
    public NemakiwareMcpServer(
            McpToolsProvider toolsProvider,
            McpAuthenticationHandler authHandler,
            McpToolResultFactory resultFactory,
            ObjectMapper objectMapper,
            @Value("${cmis.server.default.repository:bedroom}") String defaultRepository,
            jp.aegif.nemaki.util.PropertyManager propertyManager) {
        this.toolsProvider = toolsProvider;
        this.authHandler = authHandler;
        this.resultFactory = resultFactory;
        this.objectMapper = objectMapper;
        this.defaultRepository = defaultRepository;
        this.propertyManager = propertyManager;
    }

    /** Whether tools/list is publicly accessible — reads live from PropertyManager
     *  so admin UI changes take effect immediately without restart. */
    private boolean isToolsListPublic() {
        String value = propertyManager.readValue("mcp.tools.list.public");
        return value == null || value.isBlank() || "true".equalsIgnoreCase(value.trim());
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
        // Never log full arguments — login / apikey_login / cloud_login carry
        // password / apiKey / sessionToken in plaintext, and any future tool
        // could add a credential-shaped argument. Log only the tool name and
        // the (sanitised) set of argument keys so a debug session retains
        // tracing value without leaking secrets to log aggregators.
        if (log.isDebugEnabled()) {
            log.debug("Executing MCP tool: {} with arguments: {}", toolName, redactArguments(arguments));
        }

        switch (toolName) {
            case "nemakiware_login":
                return toolsProvider.executeLoginTool(arguments);

            case "nemakiware_apikey_login":
                return toolsProvider.executeApiKeyLoginTool(arguments);

            case "nemakiware_cloud_login":
                return toolsProvider.executeCloudLoginTool(arguments);

            case "nemakiware_cloud_login_status":
                return toolsProvider.executeCloudLoginStatusTool(arguments);

            case "nemakiware_logout":
                return toolsProvider.executeLogoutTool(arguments);

            case "nemakiware_search":
            case "nemakiware_rag_search":
            case "nemakiware_similar_documents":
            case "nemakiware_get_document_content":
                return executeAuthenticatedTool(toolName, arguments, headers);

            default:
                log.warn("Unknown tool requested: {}", toolName);
                return resultFactory.error("Unknown tool: " + toolName);
        }
    }

    /**
     * Execute a tool that requires authentication.
     *
     * Authentication priority:
     * 1. sessionToken in tool arguments (from nemakiware_login)
     * 2. HTTP headers (X-MCP-Session-Token, Bearer, or Basic auth)
     */
    private McpToolResult executeAuthenticatedTool(String toolName, Map<String, Object> arguments, Map<String, String> headers) {
        // Get repository ID from arguments or use configured default
        String repositoryId = getStringArg(arguments, "repositoryId", defaultRepository);

        // Check for session token in arguments first (from nemakiware_login)
        String sessionToken = getStringArg(arguments, "sessionToken", null);
        McpAuthResult authResult;

        if (sessionToken != null) {
            // Use session token from arguments
            authResult = authHandler.authenticateSessionToken(repositoryId, sessionToken);
            if (!authResult.isSuccess()) {
                log.warn("Session token authentication failed for tool {}: {}", toolName, authResult.getErrorMessage());
                return resultFactory.error("Invalid or expired session token: " + authResult.getErrorMessage());
            }
        } else {
            // Fall back to HTTP header authentication
            authResult = authHandler.authenticate(repositoryId, headers);
            if (!authResult.isSuccess()) {
                log.warn("Authentication failed for tool {}: {}", toolName, authResult.getErrorMessage());
                return resultFactory.error("Authentication required: " + authResult.getErrorMessage());
            }
        }

        String userId = authResult.getUserId();
        log.debug("Authenticated user {} for tool {}", userId, toolName);

        // Execute the tool
        switch (toolName) {
            case "nemakiware_search":
                return toolsProvider.executeSearchTool(arguments, repositoryId, userId);

            case "nemakiware_rag_search":
                return toolsProvider.executeRagSearchTool(arguments, repositoryId, userId);

            case "nemakiware_similar_documents":
                return toolsProvider.executeSimilarDocumentsTool(arguments, repositoryId, userId);

            case "nemakiware_get_document_content":
                return toolsProvider.executeGetDocumentContentTool(arguments, repositoryId, userId);

            default:
                return resultFactory.error("Unknown tool: " + toolName);
        }
    }

    /**
     * Handle MCP JSON-RPC request.
     *
     * @param request The JSON-RPC request
     * @param headers Request headers
     * @return JSON-RPC response, or null for notifications
     */
    public Map<String, Object> handleRequest(Map<String, Object> request, Map<String, String> headers) {
        String method = (String) request.get("method");
        Object id = request.get("id");
        // JSON-RPC "params" is optional and, per spec, a structured value (object/array).
        // A client may send a non-object (e.g. a bare string); tolerate it as empty params
        // instead of letting an unchecked (Map) cast throw a ClassCastException here —
        // outside the try/catch below it would surface as a raw HTTP 500 and break the
        // JSON-RPC contract.
        Object rawParams = request.getOrDefault("params", new HashMap<>());
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (rawParams instanceof Map)
                ? (Map<String, Object>) rawParams
                : new HashMap<>();

        log.debug("MCP request: method={}, id={}", method, id);

        // Handle notifications (no id field) - they don't expect a response
        if (id == null && method != null && method.startsWith("notifications/")) {
            log.debug("Received MCP notification: {}", method);
            // Return empty success response for notifications
            return createResponse(null, Map.of(), null);
        }

        try {
            Object result = handleMethod(method, params, headers);
            return createResponse(id, result, null);
        } catch (IllegalArgumentException e) {
            // Expected error: unknown method or invalid parameters
            log.warn("Invalid MCP request: {}", e.getMessage());
            return createErrorResponse(id, ERROR_METHOD_NOT_FOUND, e.getMessage(), null);
        } catch (SecurityException e) {
            // Authentication required
            log.info("MCP authentication required: {}", e.getMessage());
            return createErrorResponse(id, ERROR_INVALID_REQUEST, e.getMessage(), null);
        } catch (ClassCastException e) {
            // Invalid request structure
            log.warn("Invalid request structure: {}", e.getMessage());
            return createErrorResponse(id, ERROR_INVALID_REQUEST, "Invalid request structure", e.getMessage());
        } catch (Exception e) {
            // Unexpected error - log with full stack trace
            log.error("Unexpected error handling MCP request", e);
            return createErrorResponse(id, ERROR_INTERNAL, "Internal error", e.getMessage());
        }
    }

    private Object handleMethod(String method, Map<String, Object> params, Map<String, String> headers) {
        if (method == null) {
            throw new IllegalArgumentException("Method is required");
        }

        switch (method) {
            case "initialize":
                return handleInitialize(params);

            case "tools/list":
                if (!isToolsListPublic()) {
                    // When mcp.tools.list.public=false, require authentication
                    var authResult = authHandler.authenticate(defaultRepository, headers);
                    if (!authResult.isSuccess()) {
                        throw new SecurityException("Authentication required to list tools (mcp.tools.list.public=false)");
                    }
                }
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
        if (toolName == null) {
            throw new IllegalArgumentException("Tool name is required");
        }

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

    /**
     * Create a JSON-RPC error response with optional data field.
     */
    private Map<String, Object> createErrorResponse(Object id, int code, String message, String data) {
        Map<String, Object> error = new HashMap<>();
        error.put("code", code);
        error.put("message", message);
        if (data != null) {
            error.put("data", data);
        }
        return createResponse(id, null, error);
    }

    private String getStringArg(Map<String, Object> args, String key, String defaultValue) {
        Object value = args.get(key);
        if (value == null) return defaultValue;
        return value.toString();
    }

    /**
     * Argument keys whose values are credential-shaped and must never be
     * logged. Compared lower-case. The set is intentionally generous —
     * anything that walks like a secret is masked, even if a particular
     * tool uses it for something else (e.g., a "secret" used as a label).
     */
    private static final java.util.Set<String> SENSITIVE_ARG_KEYS = java.util.Set.of(
        "password",
        "passwd",
        "pwd",
        "apikey",
        "api_key",
        "secret",
        "token",
        "sessiontoken",
        "session_token",
        "accesstoken",
        "access_token",
        "refreshtoken",
        "refresh_token",
        "credential",
        "credentials",
        "authorization",
        "clientsecret",
        "client_secret",
        "privatekey",
        "private_key"
    );

    /**
     * Build a map suitable for logging from the raw MCP tool arguments:
     * sensitive keys are replaced with the literal "[REDACTED]"; long
     * values are abbreviated. Returning a Map (rather than a single
     * String) keeps the log output structured for downstream parsing.
     */
    static Map<String, Object> redactArguments(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return java.util.Collections.emptyMap();
        }
        Map<String, Object> redacted = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : args.entrySet()) {
            String key = e.getKey();
            if (key != null && SENSITIVE_ARG_KEYS.contains(key.toLowerCase(java.util.Locale.ROOT))) {
                redacted.put(key, "[REDACTED]");
            } else {
                Object v = e.getValue();
                if (v == null) {
                    redacted.put(key, null);
                } else if (v instanceof Map<?, ?> nested) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> nestedMap = (Map<String, Object>) nested;
                    redacted.put(key, redactArguments(nestedMap));
                } else {
                    String s = v.toString();
                    redacted.put(key, s.length() > 200 ? s.substring(0, 200) + "..." : s);
                }
            }
        }
        return redacted;
    }
}
