package jp.aegif.nemaki.mcp;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
    private final String baseUrl;
    private final String defaultRepository;

    @Autowired
    public McpToolsProvider(
            McpAuthenticationHandler authHandler,
            VectorSearchService vectorSearchService,
            McpToolResultFactory resultFactory,
            @Value("${nemakiware.baseUrl:http://localhost:8080/core}") String baseUrl,
            @Value("${cmis.server.default.repository:bedroom}") String defaultRepository) {
        this.authHandler = authHandler;
        this.vectorSearchService = vectorSearchService;
        this.resultFactory = resultFactory;
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
        String schema = """
            {
              "type": "object",
              "properties": {
                "username": {
                  "type": "string",
                  "description": "ユーザー名"
                },
                "password": {
                  "type": "string",
                  "description": "パスワード"
                },
                "repositoryId": {
                  "type": "string",
                  "description": "リポジトリID（デフォルト: bedroom）",
                  "default": "bedroom"
                }
              },
              "required": ["username", "password"]
            }
            """;
        return new McpToolDefinition(
            "nemakiware_login",
            "NemakiWareにログインしてセッショントークンを取得します",
            schema
        );
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
            "NemakiWareリポジトリから質問に関連する文書を意味的に検索します。検索結果には文書名、関連箇所、類似度スコアが含まれます。",
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
            "指定した文書と意味的に類似した文書を検索します",
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
            String response = String.format(
                "{\"success\": true, \"session_token\": \"%s\", \"repository_id\": \"%s\", \"user_id\": \"%s\"}",
                loginResult.getSessionToken(),
                loginResult.getRepositoryId(),
                loginResult.getUserId()
            );
            log.info("MCP login successful for user '{}' in repository '{}'", username, repositoryId);
            return resultFactory.success(response);
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
            return resultFactory.error("Search failed: " + e.getMessage());
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
            return resultFactory.error("Search failed: " + e.getMessage());
        }
    }

    // ========== Result Formatting ==========

    private String formatSearchResults(String query, List<VectorSearchResult> results, String repositoryId) {
        if (results.isEmpty()) {
            return "No documents found matching the query: " + query;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 検索結果: \"").append(query).append("\"\n\n");
        sb.append("**").append(results.size()).append("件の関連文書が見つかりました:**\n\n");

        for (int i = 0; i < results.size(); i++) {
            VectorSearchResult result = results.get(i);
            String docUrl = formatDocumentUrl(repositoryId, result.getDocumentId());
            int scorePercent = Math.round(result.getScore() * 100);

            sb.append(String.format("%d. [%s](%s) (類似度: %d%%)\n",
                i + 1,
                result.getDocumentName(),
                docUrl,
                scorePercent
            ));

            // Add excerpt
            String excerpt = truncateText(result.getChunkText(), 200);
            sb.append("   > ").append(excerpt).append("\n\n");
        }

        return sb.toString();
    }

    private String formatSimilarDocumentsResults(String sourceDocId, List<VectorSearchResult> results, String repositoryId) {
        if (results.isEmpty()) {
            return "No similar documents found for document: " + sourceDocId;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 類似ドキュメント\n\n");
        sb.append("**").append(results.size()).append("件の類似文書が見つかりました:**\n\n");

        for (int i = 0; i < results.size(); i++) {
            VectorSearchResult result = results.get(i);
            String docUrl = formatDocumentUrl(repositoryId, result.getDocumentId());
            int scorePercent = Math.round(result.getScore() * 100);

            sb.append(String.format("%d. [%s](%s) (類似度: %d%%)\n",
                i + 1,
                result.getDocumentName(),
                docUrl,
                scorePercent
            ));

            if (result.getChunkText() != null && !result.getChunkText().isEmpty()) {
                String excerpt = truncateText(result.getChunkText(), 150);
                sb.append("   > ").append(excerpt).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private String formatDocumentUrl(String repositoryId, String documentId) {
        return baseUrl + "/ui/#/browse/" + repositoryId + "/" + documentId;
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
