package jp.aegif.nemaki.mcp;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jp.aegif.nemaki.rag.search.VectorSearchResult;
import jp.aegif.nemaki.rag.search.VectorSearchService;

/**
 * Provides MCP tools for NemakiWare integration.
 *
 * Available tools:
 * - nemakiware_login: Login to NemakiWare and get session token
 * - nemakiware_logout: Logout and invalidate session token
 * - nemakiware_rag_search: Semantic search using RAG
 * - nemakiware_similar_documents: Find similar documents
 */
@Component
public class McpToolsProvider {

    private static final Logger log = LoggerFactory.getLogger(McpToolsProvider.class);

    private static final int DEFAULT_TOP_K = 5;
    private static final float DEFAULT_MIN_SCORE = 0.5f;

    private final McpAuthenticationHandler authHandler;
    private final VectorSearchService vectorSearchService;
    private final McpToolResultFactory resultFactory;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String defaultRepository;

    @Autowired
    public McpToolsProvider(
            McpAuthenticationHandler authHandler,
            VectorSearchService vectorSearchService,
            McpToolResultFactory resultFactory,
            ObjectMapper objectMapper,
            @Value("${nemakiware.baseUrl:http://localhost:8080/core}") String baseUrl,
            @Value("${cmis.server.default.repository:bedroom}") String defaultRepository) {
        this.authHandler = authHandler;
        this.vectorSearchService = vectorSearchService;
        this.resultFactory = resultFactory;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.defaultRepository = defaultRepository;
    }

    /**
     * Get all available tool definitions.
     */
    public List<McpToolDefinition> getToolDefinitions() {
        return Arrays.asList(
            createLoginToolDefinition(),
            createLogoutToolDefinition(),
            createRagSearchToolDefinition(),
            createSimilarDocumentsToolDefinition()
        );
    }

    // ========== Tool Definitions ==========

    private McpToolDefinition createLoginToolDefinition() {
        try {
            // Use ObjectMapper to safely build JSON schema (prevents injection if defaultRepository contains special chars)
            Map<String, Object> schemaObj = new LinkedHashMap<>();
            schemaObj.put("type", "object");

            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("username", Map.of("type", "string", "description", "ユーザー名"));
            properties.put("password", Map.of("type", "string", "description", "パスワード"));

            Map<String, Object> repoIdProp = new LinkedHashMap<>();
            repoIdProp.put("type", "string");
            repoIdProp.put("description", "リポジトリID（デフォルト: " + defaultRepository + "）");
            repoIdProp.put("default", defaultRepository);
            properties.put("repositoryId", repoIdProp);

            schemaObj.put("properties", properties);
            schemaObj.put("required", Arrays.asList("username", "password"));

            String schema = objectMapper.writeValueAsString(schemaObj);
            return new McpToolDefinition(
                "nemakiware_login",
                "NemakiWareにログインしてセッショントークンを取得します",
                schema
            );
        } catch (JsonProcessingException e) {
            log.error("Failed to generate login tool schema", e);
            // Fallback: use defaultRepository if it's safe (no quotes), otherwise use "bedroom"
            String safeDefaultRepo = defaultRepository != null && !defaultRepository.contains("\"")
                ? defaultRepository
                : "bedroom";
            String safeSchema = String.format(
                "{\"type\":\"object\",\"properties\":{\"username\":{\"type\":\"string\",\"description\":\"ユーザー名\"},\"password\":{\"type\":\"string\",\"description\":\"パスワード\"},\"repositoryId\":{\"type\":\"string\",\"description\":\"リポジトリID\",\"default\":\"%s\"}},\"required\":[\"username\",\"password\"]}",
                safeDefaultRepo);
            return new McpToolDefinition(
                "nemakiware_login",
                "NemakiWareにログインしてセッショントークンを取得します",
                safeSchema
            );
        }
    }

    private McpToolDefinition createLogoutToolDefinition() {
        String schema = """
            {
              "type": "object",
              "properties": {
                "sessionToken": {
                  "type": "string",
                  "description": "ログアウトするセッショントークン"
                }
              },
              "required": ["sessionToken"]
            }
            """;
        return new McpToolDefinition(
            "nemakiware_logout",
            "NemakiWareからログアウトしてセッションを無効化します",
            schema
        );
    }

    private McpToolDefinition createRagSearchToolDefinition() {
        String schema = """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "検索クエリ（自然言語で質問や検索内容を入力）"
                },
                "sessionToken": {
                  "type": "string",
                  "description": "ログイン時に取得したセッショントークン（ユーザー権限で検索する場合は必須）"
                },
                "topK": {
                  "type": "integer",
                  "description": "取得する最大件数（デフォルト: 5）",
                  "default": 5
                },
                "minScore": {
                  "type": "number",
                  "description": "最小類似度スコア（0.0-1.0、デフォルト: 0.5）",
                  "default": 0.5
                }
              },
              "required": ["query"]
            }
            """;
        return new McpToolDefinition(
            "nemakiware_rag_search",
            "NemakiWareリポジトリから質問に関連する文書を意味的に検索します。ログインしたユーザーの権限で検索するには、nemakiware_loginで取得したsessionTokenを渡してください。",
            schema
        );
    }

    private McpToolDefinition createSimilarDocumentsToolDefinition() {
        String schema = """
            {
              "type": "object",
              "properties": {
                "documentId": {
                  "type": "string",
                  "description": "類似文書を検索する対象の文書ID"
                },
                "sessionToken": {
                  "type": "string",
                  "description": "ログイン時に取得したセッショントークン（ユーザー権限で検索する場合は必須）"
                },
                "topK": {
                  "type": "integer",
                  "description": "取得する最大件数（デフォルト: 5）",
                  "default": 5
                },
                "minScore": {
                  "type": "number",
                  "description": "最小類似度スコア（0.0-1.0、デフォルト: 0.5）",
                  "default": 0.5
                }
              },
              "required": ["documentId"]
            }
            """;
        return new McpToolDefinition(
            "nemakiware_similar_documents",
            "指定した文書と意味的に類似した文書を検索します。ログインしたユーザーの権限で検索するには、nemakiware_loginで取得したsessionTokenを渡してください。",
            schema
        );
    }

    // ========== Tool Execution ==========

    /**
     * Execute the login tool.
     */
    public McpToolResult executeLoginTool(Map<String, Object> arguments) {
        String repositoryId = getStringArg(arguments, "repositoryId", defaultRepository);
        String username = getStringArg(arguments, "username", null);
        String password = getStringArg(arguments, "password", null);

        if (username == null || password == null) {
            return resultFactory.error("username and password are required");
        }

        McpLoginResult loginResult = authHandler.login(repositoryId, username, password);

        if (loginResult.isSuccess()) {
            try {
                // Use ObjectMapper for safe JSON serialization (prevents injection attacks)
                Map<String, Object> responseObj = new LinkedHashMap<>();
                responseObj.put("success", true);
                responseObj.put("session_token", loginResult.getSessionToken());
                responseObj.put("repository_id", loginResult.getRepositoryId());
                responseObj.put("user_id", loginResult.getUserId());
                String response = objectMapper.writeValueAsString(responseObj);
                // Note: Login success is already logged by McpAuthenticationHandler
                return resultFactory.success(response);
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize login response", e);
                return resultFactory.error("Internal error");
            }
        } else {
            log.warn("MCP login failed for user '{}': {}", username, loginResult.getErrorMessage());
            return resultFactory.error(loginResult.getErrorMessage());
        }
    }

    /**
     * Execute the logout tool.
     */
    public McpToolResult executeLogoutTool(Map<String, Object> arguments) {
        String sessionToken = getStringArg(arguments, "sessionToken", null);

        if (sessionToken == null) {
            return resultFactory.error("sessionToken is required");
        }

        authHandler.logout(sessionToken);
        log.info("MCP logout for session token");
        return resultFactory.success("Logged out successfully");
    }

    /**
     * Execute the RAG search tool.
     */
    public McpToolResult executeRagSearchTool(Map<String, Object> arguments, String repositoryId, String userId) {
        String query = getStringArg(arguments, "query", null);
        int topK = getIntArg(arguments, "topK", DEFAULT_TOP_K);
        float minScore = getFloatArg(arguments, "minScore", DEFAULT_MIN_SCORE);

        if (query == null) {
            return resultFactory.error("query is required");
        }

        try {
            List<VectorSearchResult> results = vectorSearchService.search(
                repositoryId, userId, query, topK, minScore
            );

            return resultFactory.success(formatSearchResults(query, results, repositoryId));

        } catch (Exception e) {
            log.error("RAG search failed: {}", e.getMessage(), e);
            // Return generic error message to avoid exposing internal details
            return resultFactory.error("Search failed. Please try again or contact support.");
        }
    }

    /**
     * Execute the similar documents tool.
     */
    public McpToolResult executeSimilarDocumentsTool(Map<String, Object> arguments, String repositoryId, String userId) {
        String documentId = getStringArg(arguments, "documentId", null);
        int topK = getIntArg(arguments, "topK", DEFAULT_TOP_K);
        float minScore = getFloatArg(arguments, "minScore", DEFAULT_MIN_SCORE);

        if (documentId == null) {
            return resultFactory.error("documentId is required");
        }

        try {
            List<VectorSearchResult> results = vectorSearchService.findSimilarDocuments(
                repositoryId, userId, documentId, topK, minScore
            );

            return resultFactory.success(formatSimilarDocumentsResults(documentId, results, repositoryId));

        } catch (Exception e) {
            log.error("Similar documents search failed: {}", e.getMessage(), e);
            // Return generic error message to avoid exposing internal details
            return resultFactory.error("Search failed. Please try again or contact support.");
        }
    }

    // ========== Result Formatting ==========

    private String formatSearchResults(String query, List<VectorSearchResult> results, String repositoryId) {
        if (results.isEmpty()) {
            return "No documents found matching the query: " + escapeMarkdown(query);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 検索結果: \"").append(escapeMarkdown(query)).append("\"\n\n");
        sb.append("**").append(results.size()).append("件の関連文書が見つかりました:**\n\n");

        for (int i = 0; i < results.size(); i++) {
            VectorSearchResult result = results.get(i);
            String docUrl = formatDocumentUrl(repositoryId, result.getDocumentId());
            int scorePercent = Math.round(result.getScore() * 100);

            // Escape document name to prevent Markdown injection
            sb.append(String.format("%d. [%s](%s) (類似度: %d%%)\n",
                i + 1,
                escapeMarkdown(result.getDocumentName()),
                docUrl,
                scorePercent
            ));

            // Add excerpt (also escaped)
            String excerpt = truncateText(result.getChunkText(), 200);
            sb.append("   > ").append(escapeMarkdown(excerpt)).append("\n\n");
        }

        return sb.toString();
    }

    private String formatSimilarDocumentsResults(String sourceDocId, List<VectorSearchResult> results, String repositoryId) {
        if (results.isEmpty()) {
            return "No similar documents found for document: " + escapeMarkdown(sourceDocId);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 類似ドキュメント\n\n");
        sb.append("**").append(results.size()).append("件の類似文書が見つかりました:**\n\n");

        for (int i = 0; i < results.size(); i++) {
            VectorSearchResult result = results.get(i);
            String docUrl = formatDocumentUrl(repositoryId, result.getDocumentId());
            int scorePercent = Math.round(result.getScore() * 100);

            // Escape document name to prevent Markdown injection
            sb.append(String.format("%d. [%s](%s) (類似度: %d%%)\n",
                i + 1,
                escapeMarkdown(result.getDocumentName()),
                docUrl,
                scorePercent
            ));

            if (result.getChunkText() != null && !result.getChunkText().isEmpty()) {
                String excerpt = truncateText(result.getChunkText(), 150);
                sb.append("   > ").append(escapeMarkdown(excerpt)).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private String formatDocumentUrl(String repositoryId, String documentId) {
        // URL encode to handle special characters safely
        // URLEncoder uses + for spaces, but URL fragments should use %20
        String encodedRepoId = URLEncoder.encode(repositoryId, StandardCharsets.UTF_8)
            .replace("+", "%20");
        String encodedDocId = URLEncoder.encode(documentId, StandardCharsets.UTF_8)
            .replace("+", "%20");
        return baseUrl + "/ui/#/browse/" + encodedRepoId + "/" + encodedDocId;
    }

    /**
     * Escape special Markdown characters to prevent injection.
     */
    private String escapeMarkdown(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("`", "\\`")
                   .replace("*", "\\*")
                   .replace("_", "\\_")
                   .replace("{", "\\{")
                   .replace("}", "\\}")
                   .replace("[", "\\[")
                   .replace("]", "\\]")
                   .replace("(", "\\(")
                   .replace(")", "\\)")
                   .replace("#", "\\#")
                   .replace("+", "\\+")
                   .replace("-", "\\-")
                   .replace(".", "\\.")
                   .replace("!", "\\!")
                   .replace("|", "\\|")   // Table delimiter
                   .replace("~", "\\~");  // Strikethrough
    }

    private String truncateText(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength - 3) + "...";
    }

    // ========== Argument Helpers ==========

    private String getStringArg(Map<String, Object> args, String key, String defaultValue) {
        Object value = args.get(key);
        if (value == null) return defaultValue;
        return value.toString();
    }

    private int getIntArg(Map<String, Object> args, String key, int defaultValue) {
        Object value = args.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private float getFloatArg(Map<String, Object> args, String key, float defaultValue) {
        Object value = args.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) {
            return ((Number) value).floatValue();
        }
        try {
            return Float.parseFloat(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
