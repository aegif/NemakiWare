package jp.aegif.nemaki.mcp;

import java.io.InputStream;
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

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.TextExtractionService;
import jp.aegif.nemaki.cmis.aspect.PermissionService;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.cmis.service.DiscoveryService;
import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.rag.search.VectorSearchResult;
import jp.aegif.nemaki.rag.search.VectorSearchService;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.server.CallContext;

import java.math.BigInteger;

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

    // Input validation constraints
    private static final int MAX_QUERY_LENGTH = 10000;
    private static final int MAX_DOCUMENT_ID_LENGTH = 200;
    private static final int MIN_TOP_K = 1;
    private static final int MAX_TOP_K = 100;
    private static final int MAX_CONTENT_LENGTH = 500000; // 500KB max for document content

    private final McpAuthenticationHandler authHandler;
    private final VectorSearchService vectorSearchService;
    private final ContentService contentService;
    private final TextExtractionService textExtractionService;
    private final PermissionService permissionService;
    private final TypeManager typeManager;
    private final DiscoveryService discoveryService;
    private final McpToolResultFactory resultFactory;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String defaultRepository;

    @Autowired
    public McpToolsProvider(
            McpAuthenticationHandler authHandler,
            VectorSearchService vectorSearchService,
            ContentService contentService,
            TextExtractionService textExtractionService,
            PermissionService permissionService,
            TypeManager typeManager,
            DiscoveryService discoveryService,
            McpToolResultFactory resultFactory,
            ObjectMapper objectMapper,
            @Value("${nemakiware.baseUrl:http://localhost:8080/core}") String baseUrl,
            @Value("${cmis.server.default.repository:bedroom}") String defaultRepository) {
        this.authHandler = authHandler;
        this.vectorSearchService = vectorSearchService;
        this.contentService = contentService;
        this.textExtractionService = textExtractionService;
        this.permissionService = permissionService;
        this.typeManager = typeManager;
        this.discoveryService = discoveryService;
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
            createSearchToolDefinition(),
            createRagSearchToolDefinition(),
            createSimilarDocumentsToolDefinition(),
            createGetDocumentContentToolDefinition()
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

    private McpToolDefinition createSearchToolDefinition() {
        String schema = """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "CMISクエリ文（例: SELECT * FROM cmis:document WHERE cmis:name LIKE '%契約%'）"
                },
                "sessionToken": {
                  "type": "string",
                  "description": "【必須】nemakiware_loginで取得したセッショントークン。検索結果はこのトークンに紐づくユーザーの権限でフィルタリングされます。"
                },
                "maxItems": {
                  "type": "integer",
                  "description": "取得する最大件数（デフォルト: 20）",
                  "default": 20
                },
                "skipCount": {
                  "type": "integer",
                  "description": "スキップする件数（ページネーション用、デフォルト: 0）",
                  "default": 0
                }
              },
              "required": ["query", "sessionToken"]
            }
            """;
        return new McpToolDefinition(
            "nemakiware_search",
            "CMISクエリ言語を使用してNemakiWareリポジトリを検索します。キーワード検索やメタデータ検索に適しています。例: 'SELECT * FROM cmis:document WHERE cmis:name LIKE '%報告書%'' または 'SELECT * FROM test:contract WHERE test:partyName = '株式会社ABC''。【重要】まずnemakiware_loginでログインし、取得したsessionTokenを必ず渡してください。ACLフィルタリングは自動的に適用されます。",
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
                  "description": "【必須】nemakiware_loginで取得したセッショントークン。検索結果はこのトークンに紐づくユーザーの権限でフィルタリングされます。"
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
              "required": ["query", "sessionToken"]
            }
            """;
        return new McpToolDefinition(
            "nemakiware_rag_search",
            "NemakiWareリポジトリから質問に関連する文書を意味的に検索します。【重要】まずnemakiware_loginでログインし、取得したsessionTokenを必ず渡してください。検索結果はユーザーのアクセス権限に基づいてフィルタリングされます。",
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
                  "description": "【必須】nemakiware_loginで取得したセッショントークン。検索結果はこのトークンに紐づくユーザーの権限でフィルタリングされます。"
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
              "required": ["documentId", "sessionToken"]
            }
            """;
        return new McpToolDefinition(
            "nemakiware_similar_documents",
            "指定した文書と意味的に類似した文書を検索します。【重要】まずnemakiware_loginでログインし、取得したsessionTokenを必ず渡してください。検索結果はユーザーのアクセス権限に基づいてフィルタリングされます。",
            schema
        );
    }

    private McpToolDefinition createGetDocumentContentToolDefinition() {
        String schema = """
            {
              "type": "object",
              "properties": {
                "documentId": {
                  "type": "string",
                  "description": "コンテンツを取得する文書のID（検索結果から取得）"
                },
                "sessionToken": {
                  "type": "string",
                  "description": "【必須】nemakiware_loginで取得したセッショントークン。文書へのアクセス権限がチェックされます。"
                },
                "maxLength": {
                  "type": "integer",
                  "description": "取得する最大文字数（デフォルト: 50000）。大きなファイルの場合は切り詰められます。",
                  "default": 50000
                }
              },
              "required": ["documentId", "sessionToken"]
            }
            """;
        return new McpToolDefinition(
            "nemakiware_get_document_content",
            "文書の全テキストコンテンツを取得します。検索結果で見つけた文書の詳細内容を読み込んでLLMのコンテキストに取り込むために使用します。PDF、Word、Excel、PowerPoint等の形式に対応。【重要】まずnemakiware_loginでログインし、取得したsessionTokenを必ず渡してください。",
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
     * Execute the CMIS query search tool.
     * Uses Solr-backed CMIS Query Language for keyword and metadata search.
     * ACL filtering is automatically applied by the query processor.
     */
    public McpToolResult executeSearchTool(Map<String, Object> arguments, String repositoryId, String userId) {
        String query = getStringArg(arguments, "query", null);
        int maxItems = getIntArg(arguments, "maxItems", 20);
        int skipCount = getIntArg(arguments, "skipCount", 0);

        // Input validation
        if (query == null || query.trim().isEmpty()) {
            return resultFactory.error("query is required and cannot be empty");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            return resultFactory.error("query exceeds maximum length of " + MAX_QUERY_LENGTH + " characters");
        }
        if (maxItems < 1 || maxItems > MAX_TOP_K) {
            return resultFactory.error("maxItems must be between 1 and " + MAX_TOP_K);
        }
        if (skipCount < 0) {
            return resultFactory.error("skipCount must be non-negative");
        }

        try {
            // Create CallContext for the MCP user
            CallContext callContext = new McpCallContext(repositoryId, userId);

            // Execute CMIS query via DiscoveryService
            // ACL filtering is applied automatically by the query processor
            ObjectList results = discoveryService.query(
                callContext,
                repositoryId,
                query.trim(),
                false,  // searchAllVersions
                false,  // includeAllowableActions
                IncludeRelationships.NONE,
                null,   // renditionFilter
                BigInteger.valueOf(maxItems),
                BigInteger.valueOf(skipCount),
                null    // extension
            );

            return resultFactory.success(formatCmisQueryResults(query, results, repositoryId));

        } catch (Exception e) {
            log.error("CMIS query search failed: {}", e.getMessage(), e);
            // Check if it's a query syntax error
            String errorMessage = e.getMessage();
            if (errorMessage != null && (
                    errorMessage.contains("parse") ||
                    errorMessage.contains("syntax") ||
                    errorMessage.contains("Invalid") ||
                    errorMessage.contains("unexpected"))) {
                return resultFactory.error("Query syntax error. Please check your CMIS query syntax.");
            }
            // Return generic error message to avoid exposing internal details
            return resultFactory.error("Search failed. Please try again or contact support.");
        }
    }

    /**
     * Execute the RAG search tool.
     */
    public McpToolResult executeRagSearchTool(Map<String, Object> arguments, String repositoryId, String userId) {
        String query = getStringArg(arguments, "query", null);
        int topK = getIntArg(arguments, "topK", DEFAULT_TOP_K);
        float minScore = getFloatArg(arguments, "minScore", DEFAULT_MIN_SCORE);

        // Input validation
        if (query == null || query.trim().isEmpty()) {
            return resultFactory.error("query is required and cannot be empty");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            return resultFactory.error("query exceeds maximum length of " + MAX_QUERY_LENGTH + " characters");
        }
        if (topK < MIN_TOP_K || topK > MAX_TOP_K) {
            return resultFactory.error("topK must be between " + MIN_TOP_K + " and " + MAX_TOP_K);
        }
        if (minScore < 0.0f || minScore > 1.0f) {
            return resultFactory.error("minScore must be between 0.0 and 1.0");
        }

        try {
            List<VectorSearchResult> results = vectorSearchService.search(
                repositoryId, userId, query.trim(), topK, minScore
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

        // Input validation
        if (documentId == null || documentId.trim().isEmpty()) {
            return resultFactory.error("documentId is required and cannot be empty");
        }
        if (documentId.length() > MAX_DOCUMENT_ID_LENGTH) {
            return resultFactory.error("documentId exceeds maximum length of " + MAX_DOCUMENT_ID_LENGTH + " characters");
        }
        if (topK < MIN_TOP_K || topK > MAX_TOP_K) {
            return resultFactory.error("topK must be between " + MIN_TOP_K + " and " + MAX_TOP_K);
        }
        if (minScore < 0.0f || minScore > 1.0f) {
            return resultFactory.error("minScore must be between 0.0 and 1.0");
        }

        try {
            List<VectorSearchResult> results = vectorSearchService.findSimilarDocuments(
                repositoryId, userId, documentId.trim(), topK, minScore
            );

            return resultFactory.success(formatSimilarDocumentsResults(documentId, results, repositoryId));

        } catch (Exception e) {
            log.error("Similar documents search failed: {}", e.getMessage(), e);
            // Return generic error message to avoid exposing internal details
            return resultFactory.error("Search failed. Please try again or contact support.");
        }
    }

    /**
     * Execute the get document content tool.
     * Retrieves the full text content of a document for LLM context.
     */
    public McpToolResult executeGetDocumentContentTool(Map<String, Object> arguments, String repositoryId, String userId) {
        String documentId = getStringArg(arguments, "documentId", null);
        int maxLength = getIntArg(arguments, "maxLength", 50000);

        // Input validation
        if (documentId == null || documentId.trim().isEmpty()) {
            return resultFactory.error("documentId is required and cannot be empty");
        }
        if (documentId.length() > MAX_DOCUMENT_ID_LENGTH) {
            return resultFactory.error("documentId exceeds maximum length of " + MAX_DOCUMENT_ID_LENGTH + " characters");
        }
        if (maxLength < 1) {
            return resultFactory.error("maxLength must be at least 1");
        }
        if (maxLength > MAX_CONTENT_LENGTH) {
            maxLength = MAX_CONTENT_LENGTH; // Silently cap to maximum to prevent DoS
            log.debug("maxLength capped to {} to prevent excessive memory usage", MAX_CONTENT_LENGTH);
        }

        // Trim the documentId for safety
        documentId = documentId.trim();

        try {
            // 1. Get the document from CouchDB
            Document document = contentService.getDocument(repositoryId, documentId);
            if (document == null) {
                // Log details for debugging, but return generic message to prevent ID enumeration
                log.warn("Document not found: {} (requested by user: {})", documentId, userId);
                return resultFactory.error("Document not found or access denied");
            }

            // 2. Explicit ACL check - ContentService.getDocument does NOT validate access
            // SECURITY: We must verify the user has permission to read this document
            if (!hasReadPermission(repositoryId, userId, document)) {
                log.warn("User {} denied access to document {} in repository {}",
                        userId, documentId, repositoryId);
                return resultFactory.error("Access denied: You do not have permission to read this document");
            }

            // 3. Get the attachment (binary content)
            String attachmentNodeId = document.getAttachmentNodeId();
            if (attachmentNodeId == null) {
                // Log details for debugging, but return generic message
                log.warn("Document has no content: {} (id: {}, user: {})",
                        document.getName(), document.getId(), userId);
                return resultFactory.error("Document has no content");
            }

            AttachmentNode attachment = contentService.getAttachment(repositoryId, attachmentNodeId);
            if (attachment == null) {
                // Log details for debugging, but return generic message
                log.warn("Document content not found: {} (id: {}, user: {})",
                        document.getName(), document.getId(), userId);
                return resultFactory.error("Document content not found");
            }

            // 4. Extract text from the document
            String mimeType = attachment.getMimeType();
            String fileName = attachment.getName();

            // Check if the MIME type is supported for text extraction
            if (!textExtractionService.isSupported(mimeType)) {
                // Log details for debugging, but return generic message
                log.warn("Unsupported document type for text extraction: {} (id: {}, mimeType: {})",
                        document.getName(), document.getId(), mimeType);
                return resultFactory.error("Document type is not supported for text extraction");
            }

            String extractedText;
            try (InputStream inputStream = attachment.getInputStream()) {
                if (inputStream == null) {
                    // Log details for debugging, but return generic message
                    log.warn("Could not read document content: {} (id: {})",
                            document.getName(), document.getId());
                    return resultFactory.error("Could not read document content");
                }
                extractedText = textExtractionService.extractText(inputStream, mimeType, fileName, maxLength);
            }

            if (extractedText == null || extractedText.trim().isEmpty()) {
                // Log details for debugging, but return generic message
                log.warn("No text content could be extracted from document: {} (id: {})",
                        document.getName(), document.getId());
                return resultFactory.error("No text content could be extracted from document");
            }

            // 5. Format the result for LLM
            return resultFactory.success(formatDocumentContent(document, extractedText, mimeType, maxLength));

        } catch (Exception e) {
            log.error("Failed to get document content for {}: {}", documentId, e.getMessage(), e);
            // Return generic error message to avoid exposing internal details
            return resultFactory.error("Failed to retrieve document content. Please try again or contact support.");
        }
    }

    // ========== Result Formatting ==========

    private String formatCmisQueryResults(String query, ObjectList results, String repositoryId) {
        if (results == null || results.getObjects() == null || results.getObjects().isEmpty()) {
            return "## 検索結果\n\nクエリに一致する文書が見つかりませんでした。\n\n**クエリ:** `" + escapeMarkdown(query) + "`";
        }

        List<ObjectData> objects = results.getObjects();
        StringBuilder sb = new StringBuilder();
        sb.append("## 検索結果\n\n");
        sb.append("**クエリ:** `").append(escapeMarkdown(query)).append("`\n\n");

        long totalNumItems = results.getNumItems() != null ? results.getNumItems().longValue() : objects.size();
        sb.append("**").append(objects.size()).append("件を表示");
        if (results.hasMoreItems() != null && results.hasMoreItems()) {
            sb.append(" (総数: ").append(totalNumItems).append("件以上)");
        } else if (totalNumItems > objects.size()) {
            sb.append(" (総数: ").append(totalNumItems).append("件)");
        }
        sb.append("**\n\n");

        for (int i = 0; i < objects.size(); i++) {
            ObjectData obj = objects.get(i);
            Properties props = obj.getProperties();
            if (props == null) continue;

            // Extract common properties
            String objectId = getPropertyValue(props, "cmis:objectId");
            String name = getPropertyValue(props, "cmis:name");
            String objectType = getPropertyValue(props, "cmis:objectTypeId");
            String lastModified = getPropertyValue(props, "cmis:lastModificationDate");
            String createdBy = getPropertyValue(props, "cmis:createdBy");

            String docUrl = formatDocumentUrl(repositoryId, objectId);

            sb.append(String.format("%d. [%s](%s)%n",
                i + 1,
                escapeMarkdown(name != null ? name : objectId),
                docUrl
            ));

            // Add metadata
            if (objectType != null) {
                sb.append("   - **タイプ:** ").append(escapeMarkdown(objectType)).append("\n");
            }
            if (createdBy != null) {
                sb.append("   - **作成者:** ").append(escapeMarkdown(createdBy)).append("\n");
            }
            if (lastModified != null) {
                sb.append("   - **更新日:** ").append(escapeMarkdown(lastModified)).append("\n");
            }
            sb.append("\n");
        }

        if (results.hasMoreItems() != null && results.hasMoreItems()) {
            sb.append("\n*さらに結果があります。skipCountを増やして続きを取得できます。*\n");
        }

        return sb.toString();
    }

    /**
     * Extract a string property value from CMIS Properties.
     */
    private String getPropertyValue(Properties props, String propertyId) {
        if (props == null || props.getProperties() == null) return null;
        PropertyData<?> propData = props.getProperties().get(propertyId);
        if (propData == null || propData.getFirstValue() == null) return null;
        return propData.getFirstValue().toString();
    }

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
            sb.append(String.format("%d. [%s](%s) (類似度: %d%%)%n",
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
            sb.append(String.format("%d. [%s](%s) (類似度: %d%%)%n",
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

    private String formatDocumentContent(Document document, String extractedText, String mimeType, int maxLength) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 文書コンテンツ: ").append(escapeMarkdown(document.getName())).append("\n\n");
        sb.append("**文書ID:** ").append(escapeMarkdown(document.getId())).append("\n");
        sb.append("**MIME タイプ:** ").append(escapeMarkdown(mimeType)).append("\n");

        boolean truncated = extractedText.length() >= maxLength;
        sb.append("**文字数:** ").append(extractedText.length());
        if (truncated) {
            sb.append(" (最大 ").append(maxLength).append(" 文字で切り詰め)");
        }
        sb.append("\n\n");

        sb.append("---\n\n");
        sb.append(extractedText);

        if (truncated) {
            sb.append("\n\n---\n*[コンテンツは最大文字数に達したため切り詰められました]*");
        }

        return sb.toString();
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

    // ========== Permission Helpers ==========

    /**
     * Check if a user has read permission for a document.
     *
     * SECURITY: This is a critical check that prevents unauthorized access to document content.
     * ContentService.getDocument() does NOT perform ACL validation, so we must explicitly check.
     *
     * @param repositoryId Repository ID
     * @param userId User ID to check permission for
     * @param document The document to check access to
     * @return true if the user has read permission, false otherwise
     */
    private boolean hasReadPermission(String repositoryId, String userId, Document document) {
        try {
            // Get the type definition to determine base type
            TypeDefinition typeDef = typeManager.getTypeDefinition(repositoryId, document);
            if (typeDef == null || typeDef.getBaseTypeId() == null) {
                log.warn("Could not determine type for document {}", document.getId());
                return false;
            }
            String baseType = typeDef.getBaseTypeId().value();

            // Calculate the ACL for this document
            Acl acl = contentService.calculateAcl(repositoryId, (Content) document);
            if (acl == null) {
                log.warn("Could not calculate ACL for document {}", document.getId());
                return false;
            }

            // Get user's groups for permission check
            java.util.Set<String> groups = contentService.getGroupIdsContainingUser(repositoryId, userId);

            // Create a simple CallContext for MCP operations
            // This provides proper context instead of null for future extensibility
            // and better compatibility with permission checking code
            CallContext callContext = new McpCallContext(repositoryId, userId);

            // Check if user has the required permission (CAN_GET_PROPERTIES_OBJECT is the read permission)
            String permissionKey = PermissionMapping.CAN_GET_PROPERTIES_OBJECT;
            Boolean hasPermission = permissionService.checkPermissionWithGivenList(
                    callContext, repositoryId, permissionKey, acl, baseType, (Content) document, userId, groups);

            return hasPermission != null && hasPermission;

        } catch (Exception e) {
            log.error("Error checking permission for document {}: {}", document.getId(), e.getMessage(), e);
            // Fail closed: deny access on error
            return false;
        }
    }
}
