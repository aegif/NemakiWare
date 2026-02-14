package jp.aegif.nemaki.mcp;

import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.spring.SpringLifecycleListener;
import org.glassfish.jersey.server.spring.SpringComponentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.ws.rs.ApplicationPath;

/**
 * JAX-RS Application for MCP (Model Context Protocol) endpoints.
 *
 * Exposes NemakiWare functionality to LLM clients like Claude Desktop via MCP.
 *
 * Endpoints:
 * - POST /mcp/message - Handle MCP JSON-RPC messages
 * - GET /mcp/info - Get server information
 * - GET /mcp/health - Health check
 *
 * Configuration in Claude Desktop (claude_desktop_config.json):
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
@ApplicationPath("/mcp")
public class McpApplication extends ResourceConfig {

    private static final Logger log = LoggerFactory.getLogger(McpApplication.class);

    public McpApplication() {
        log.info("=== Initializing NemakiWare MCP Application ===");

        // Enable Jersey-Spring integration
        register(SpringLifecycleListener.class);
        register(SpringComponentProvider.class);

        // Enable JSON processing with Jackson
        register(JacksonFeature.class);

        // Enable Jersey-Spring bridge for automatic DI
        property("jersey.config.server.provider.classnames",
                "org.glassfish.jersey.server.spring.SpringLifecycleListener," +
                "org.glassfish.jersey.server.spring.scope.RequestContextFilter");

        // Register MCP resource package
        packages("jp.aegif.nemaki.mcp");

        log.info("=== NemakiWare MCP Application initialized successfully ===");
        log.info("MCP endpoint available at: /core/mcp/message");
    }
}
