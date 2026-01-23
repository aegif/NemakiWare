package jp.aegif.nemaki.mcp;

/**
 * Definition of an MCP tool with its schema.
 */
public class McpToolDefinition {

    private final String name;
    private final String description;
    private final String inputSchema;

    public McpToolDefinition(String name, String description, String inputSchema) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getInputSchema() {
        return inputSchema;
    }
}
