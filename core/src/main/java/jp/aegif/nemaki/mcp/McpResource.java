package jp.aegif.nemaki.mcp;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * JAX-RS Resource for MCP (Model Context Protocol) endpoints.
 *
 * Provides HTTP transport for MCP protocol:
 * - POST /mcp/message - Handle MCP JSON-RPC messages
 * - GET /mcp/info - Get server information
 *
 * Authentication is handled via:
 * 1. Authorization header (Basic or Bearer)
 * 2. X-MCP-Session-Token header
 * 3. nemakiware_login tool (returns session token)
 *
 * Claude Desktop configuration example:
 * <pre>
 * {
 *   "mcpServers": {
 *     "nemakiware": {
 *       "url": "http://localhost:8080/core/mcp/message",
 *       "headers": {
 *         "Authorization": "Basic YWRtaW46YWRtaW4="
 *       }
 *     }
 *   }
 * }
 * </pre>
 */
@Component
@Path("/")
public class McpResource {

    private static final Logger log = LoggerFactory.getLogger(McpResource.class);

    private final NemakiwareMcpServer mcpServer;

    @Autowired
    public McpResource(NemakiwareMcpServer mcpServer) {
        this.mcpServer = mcpServer;
    }

    /**
     * Handle MCP JSON-RPC messages.
     *
     * Supports all MCP methods:
     * - initialize: Initialize the connection
     * - tools/list: List available tools
     * - tools/call: Execute a tool
     * - ping: Health check
     */
    @POST
    @Path("/message")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response handleMessage(
            Map<String, Object> request,
            @HeaderParam("Authorization") String authorization,
            @HeaderParam("X-MCP-Session-Token") String sessionToken) {

        log.debug("MCP message received: {}", request);

        // Build headers map for authentication
        Map<String, String> headers = new HashMap<>();
        if (authorization != null) {
            headers.put("Authorization", authorization);
        }
        if (sessionToken != null) {
            headers.put("X-MCP-Session-Token", sessionToken);
        }

        // Handle the MCP request
        Map<String, Object> response = mcpServer.handleRequest(request, headers);

        return Response.ok(response).build();
    }

    /**
     * Get MCP server information.
     *
     * Returns server info, capabilities, and available tools.
     */
    @GET
    @Path("/info")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getServerInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("serverInfo", mcpServer.getServerInfo());
        info.put("capabilities", mcpServer.getCapabilities());
        info.put("tools", mcpServer.listTools());

        return Response.ok(info).build();
    }

    /**
     * Health check endpoint.
     */
    @GET
    @Path("/health")
    @Produces(MediaType.APPLICATION_JSON)
    public Response healthCheck() {
        return Response.ok(Map.of(
            "status", "healthy",
            "service", "nemakiware-mcp"
        )).build();
    }
}
