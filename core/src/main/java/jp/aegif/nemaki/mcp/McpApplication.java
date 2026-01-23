package jp.aegif.nemaki.mcp;

import java.util.logging.Logger;

import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.message.filtering.EntityFilteringFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.spring.SpringLifecycleListener;
import org.glassfish.jersey.server.spring.SpringComponentProvider;

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

    private static final Logger logger = Logger.getLogger(McpApplication.class.getName());

    public McpApplication() {
        logger.info("=== Initializing NemakiWare MCP Application ===");

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

        logger.info("=== NemakiWare MCP Application initialized successfully ===");
        logger.info("MCP endpoint available at: /core/mcp/message");
    }
}
