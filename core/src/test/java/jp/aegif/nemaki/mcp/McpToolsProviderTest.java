package jp.aegif.nemaki.mcp;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mindrot.jbcrypt.BCrypt;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.fasterxml.jackson.databind.ObjectMapper;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.businesslogic.TextExtractionService;
import jp.aegif.nemaki.cmis.aspect.PermissionService;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.cmis.service.DiscoveryService;
import jp.aegif.nemaki.model.User;
import jp.aegif.nemaki.rag.search.VectorSearchResult;
import jp.aegif.nemaki.rag.search.VectorSearchService;

/**
 * TDD Tests for MCP Tools Provider.
 *
 * Tests the MCP tools:
 * 1. nemakiware_login - Login and get session token
 * 2. nemakiware_logout - Logout and invalidate session
 * 3. nemakiware_rag_search - RAG semantic search
 * 4. nemakiware_similar_documents - Find similar documents
 */
public class McpToolsProviderTest {

    @Mock
    private PrincipalService principalService;

    @Mock
    private VectorSearchService vectorSearchService;

    @Mock
    private ContentService contentService;

    @Mock
    private TextExtractionService textExtractionService;

    @Mock
    private PermissionService permissionService;

    @Mock
    private TypeManager typeManager;

    @Mock
    private DiscoveryService discoveryService;

    private McpToolsProvider toolsProvider;
    private McpAuthenticationHandler authHandler;
    private McpToolResultFactory resultFactory;

    private static final String ADMIN_PASSWORD_HASH = BCrypt.hashpw("admin", BCrypt.gensalt());
    private static final long TEST_SESSION_TTL_SECONDS = 3600;
    private static final String DEFAULT_REPOSITORY = "bedroom";

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        ObjectMapper objectMapper = new ObjectMapper();
        authHandler = new McpAuthenticationHandler(principalService, TEST_SESSION_TTL_SECONDS);
        resultFactory = new McpToolResultFactory(objectMapper);
        toolsProvider = new McpToolsProvider(authHandler, vectorSearchService, contentService,
                textExtractionService, permissionService, typeManager, discoveryService,
                resultFactory, objectMapper, "http://localhost:8080/core", DEFAULT_REPOSITORY);
    }

    // ========== Login Tool Tests ==========

    @Test
    public void testLoginToolReturnsSessionToken() {
        // Given
        String repositoryId = "bedroom";
        String username = "admin";
        String password = "admin";

        User mockUser = mock(User.class);
        when(mockUser.getUserId()).thenReturn(username);
        when(mockUser.getPasswordHash()).thenReturn(ADMIN_PASSWORD_HASH);
        when(principalService.getUserById(repositoryId, username)).thenReturn(mockUser);

        Map<String, Object> arguments = Map.of(
            "repositoryId", repositoryId,
            "username", username,
            "password", password
        );

        // When
        McpToolResult result = toolsProvider.executeLoginTool(arguments);

        // Then
        assertTrue(result.isSuccess());
        assertNotNull(result.getContent());
        assertTrue(result.getContent().contains("session_token"));
    }

    @Test
    public void testLoginToolFailsWithInvalidCredentials() {
        // Given
        String repositoryId = "bedroom";
        String username = "admin";
        String password = "wrongpassword";

        User mockUser = mock(User.class);
        when(mockUser.getPasswordHash()).thenReturn(ADMIN_PASSWORD_HASH);
        when(principalService.getUserById(repositoryId, username)).thenReturn(mockUser);

        Map<String, Object> arguments = Map.of(
            "repositoryId", repositoryId,
            "username", username,
            "password", password
        );

        // When
        McpToolResult result = toolsProvider.executeLoginTool(arguments);

        // Then
        assertFalse(result.isSuccess());
        assertTrue(result.getContent().contains("error"));
    }

    @Test
    public void testLoginToolUsesDefaultRepository() {
        // Given: No repositoryId specified
        String username = "admin";
        String password = "admin";

        User mockUser = mock(User.class);
        when(mockUser.getUserId()).thenReturn(username);
        when(mockUser.getPasswordHash()).thenReturn(ADMIN_PASSWORD_HASH);
        // Default repository is "bedroom"
        when(principalService.getUserById("bedroom", username)).thenReturn(mockUser);

        Map<String, Object> arguments = Map.of(
            "username", username,
            "password", password
        );

        // When
        McpToolResult result = toolsProvider.executeLoginTool(arguments);

        // Then
        assertTrue(result.isSuccess());
    }

    // ========== Logout Tool Tests ==========

    @Test
    public void testLogoutToolInvalidatesToken() {
        // Given: Login first
        String repositoryId = "bedroom";
        String username = "admin";
        String password = "admin";

        User mockUser = mock(User.class);
        when(mockUser.getUserId()).thenReturn(username);
        when(mockUser.getPasswordHash()).thenReturn(ADMIN_PASSWORD_HASH);
        when(principalService.getUserById(repositoryId, username)).thenReturn(mockUser);

        Map<String, Object> loginArgs = Map.of(
            "repositoryId", repositoryId,
            "username", username,
            "password", password
        );
        McpToolResult loginResult = toolsProvider.executeLoginTool(loginArgs);
        assertTrue(loginResult.isSuccess());

        // Extract token from login result
        String sessionToken = extractTokenFromResult(loginResult.getContent());

        // When: Logout
        Map<String, Object> logoutArgs = Map.of("sessionToken", sessionToken);
        McpToolResult logoutResult = toolsProvider.executeLogoutTool(logoutArgs);

        // Then
        assertTrue(logoutResult.isSuccess());
        assertTrue(logoutResult.getContent().contains("Logged out successfully"));
    }

    // ========== RAG Search Tool Tests ==========

    @Test
    public void testRagSearchToolReturnsResults() throws Exception {
        // Given
        String repositoryId = "bedroom";
        String userId = "admin";
        String query = "invoice payment terms";

        // Setup auth
        authHandler.registerSessionToken("test-token", userId, repositoryId);

        // Setup mock search results
        List<VectorSearchResult> mockResults = Arrays.asList(
            createMockResult("doc1", "Invoice_2024.pdf", "Payment terms are 30 days", 0.95f),
            createMockResult("doc2", "Contract.pdf", "Payment due within 60 days", 0.82f)
        );
        when(vectorSearchService.search(eq(repositoryId), eq(userId), eq(query), anyInt(), anyFloat()))
            .thenReturn(mockResults);

        Map<String, Object> arguments = Map.of(
            "query", query,
            "topK", 5,
            "minScore", 0.5f
        );

        // When
        McpToolResult result = toolsProvider.executeRagSearchTool(arguments, repositoryId, userId);

        // Then
        assertTrue(result.isSuccess());
        // Document names are Markdown-escaped: _ becomes \_ and . becomes \.
        assertTrue(result.getContent().contains("Invoice\\_2024\\.pdf"));
        assertTrue(result.getContent().contains("Contract\\.pdf"));
        assertTrue(result.getContent().contains("95%")); // Score as percentage
    }

    @Test
    public void testRagSearchToolFormatsLinksCorrectly() throws Exception {
        // Given
        String repositoryId = "bedroom";
        String userId = "admin";
        String query = "test";

        authHandler.registerSessionToken("test-token", userId, repositoryId);

        List<VectorSearchResult> mockResults = Arrays.asList(
            createMockResult("abc123", "TestDoc.pdf", "Test content", 0.9f)
        );
        when(vectorSearchService.search(eq(repositoryId), eq(userId), eq(query), anyInt(), anyFloat()))
            .thenReturn(mockResults);

        Map<String, Object> arguments = Map.of("query", query);

        // When
        McpToolResult result = toolsProvider.executeRagSearchTool(arguments, repositoryId, userId);

        // Then
        assertTrue(result.isSuccess());
        // Check markdown link format (document name is escaped: . becomes \.)
        assertTrue(result.getContent().contains("[TestDoc\\.pdf](http://localhost:8080/core/ui/#/browse/bedroom/abc123)"));
    }

    @Test
    public void testRagSearchToolHandlesEmptyResults() throws Exception {
        // Given
        String repositoryId = "bedroom";
        String userId = "admin";
        String query = "nonexistent";

        authHandler.registerSessionToken("test-token", userId, repositoryId);

        when(vectorSearchService.search(eq(repositoryId), eq(userId), eq(query), anyInt(), anyFloat()))
            .thenReturn(Arrays.asList());

        Map<String, Object> arguments = Map.of("query", query);

        // When
        McpToolResult result = toolsProvider.executeRagSearchTool(arguments, repositoryId, userId);

        // Then
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("No documents found"));
    }

    @Test
    public void testRagSearchToolUsesDefaultParameters() throws Exception {
        // Given: No topK or minScore specified
        String repositoryId = "bedroom";
        String userId = "admin";
        String query = "test";

        authHandler.registerSessionToken("test-token", userId, repositoryId);

        when(vectorSearchService.search(eq(repositoryId), eq(userId), eq(query), eq(5), eq(0.5f)))
            .thenReturn(Arrays.asList());

        Map<String, Object> arguments = Map.of("query", query);

        // When
        toolsProvider.executeRagSearchTool(arguments, repositoryId, userId);

        // Then: Should use default topK=5, minScore=0.5f
        verify(vectorSearchService).search(repositoryId, userId, query, 5, 0.5f);
    }

    // ========== Similar Documents Tool Tests ==========

    @Test
    public void testSimilarDocumentsToolReturnsResults() throws Exception {
        // Given
        String repositoryId = "bedroom";
        String userId = "admin";
        String documentId = "doc123";

        authHandler.registerSessionToken("test-token", userId, repositoryId);

        List<VectorSearchResult> mockResults = Arrays.asList(
            createMockResult("similar1", "Similar1.pdf", "Similar content 1", 0.88f),
            createMockResult("similar2", "Similar2.pdf", "Similar content 2", 0.75f)
        );
        when(vectorSearchService.findSimilarDocuments(eq(repositoryId), eq(userId), eq(documentId), anyInt(), anyFloat()))
            .thenReturn(mockResults);

        Map<String, Object> arguments = Map.of(
            "documentId", documentId,
            "topK", 5
        );

        // When
        McpToolResult result = toolsProvider.executeSimilarDocumentsTool(arguments, repositoryId, userId);

        // Then
        assertTrue(result.isSuccess());
        // Document names are Markdown-escaped: . becomes \.
        assertTrue(result.getContent().contains("Similar1\\.pdf"));
        assertTrue(result.getContent().contains("88%"));
    }

    // ========== URL Encoding Tests ==========

    @Test
    public void testRagSearchToolEncodesSpacesInUrls() throws Exception {
        // Given: Document ID with spaces
        String repositoryId = "bedroom";
        String userId = "admin";
        String query = "test";

        authHandler.registerSessionToken("test-token", userId, repositoryId);

        List<VectorSearchResult> mockResults = Arrays.asList(
            createMockResult("doc with spaces", "Test Doc.pdf", "Test content", 0.9f)
        );
        when(vectorSearchService.search(eq(repositoryId), eq(userId), eq(query), anyInt(), anyFloat()))
            .thenReturn(mockResults);

        Map<String, Object> arguments = Map.of("query", query);

        // When
        McpToolResult result = toolsProvider.executeRagSearchTool(arguments, repositoryId, userId);

        // Then
        assertTrue(result.isSuccess());
        // Spaces should be encoded as %20, not +
        assertTrue(result.getContent().contains("/browse/bedroom/doc%20with%20spaces)"));
    }

    // ========== Markdown Escaping Tests ==========

    @Test
    public void testRagSearchToolEscapesMarkdownSpecialChars() throws Exception {
        // Given: Document with Markdown special characters
        String repositoryId = "bedroom";
        String userId = "admin";
        String query = "test [query]";

        authHandler.registerSessionToken("test-token", userId, repositoryId);

        List<VectorSearchResult> mockResults = Arrays.asList(
            createMockResult("doc1", "File|with~special*chars.pdf", "Content with *bold* and _italic_", 0.9f)
        );
        when(vectorSearchService.search(eq(repositoryId), eq(userId), eq(query), anyInt(), anyFloat()))
            .thenReturn(mockResults);

        Map<String, Object> arguments = Map.of("query", query);

        // When
        McpToolResult result = toolsProvider.executeRagSearchTool(arguments, repositoryId, userId);

        // Then
        assertTrue(result.isSuccess());
        // Query should be escaped
        assertTrue(result.getContent().contains("test \\[query\\]"));
        // Document name special chars should be escaped
        assertTrue(result.getContent().contains("File\\|with\\~special\\*chars\\.pdf"));
        // Content special chars should be escaped
        assertTrue(result.getContent().contains("\\*bold\\*"));
        assertTrue(result.getContent().contains("\\_italic\\_"));
    }

    // ========== Tool Definitions Tests ==========

    @Test
    public void testGetToolDefinitions() {
        // When
        List<McpToolDefinition> tools = toolsProvider.getToolDefinitions();

        // Then
        assertEquals(6, tools.size());

        // Verify tool names
        assertTrue(tools.stream().anyMatch(t -> t.getName().equals("nemakiware_login")));
        assertTrue(tools.stream().anyMatch(t -> t.getName().equals("nemakiware_logout")));
        assertTrue(tools.stream().anyMatch(t -> t.getName().equals("nemakiware_search")));
        assertTrue(tools.stream().anyMatch(t -> t.getName().equals("nemakiware_rag_search")));
        assertTrue(tools.stream().anyMatch(t -> t.getName().equals("nemakiware_similar_documents")));
        assertTrue(tools.stream().anyMatch(t -> t.getName().equals("nemakiware_get_document_content")));
    }

    @Test
    public void testLoginToolDefinitionHasCorrectSchema() {
        // When
        List<McpToolDefinition> tools = toolsProvider.getToolDefinitions();
        McpToolDefinition loginTool = tools.stream()
            .filter(t -> t.getName().equals("nemakiware_login"))
            .findFirst()
            .orElse(null);

        // Then
        assertNotNull(loginTool);
        assertNotNull(loginTool.getInputSchema());
        assertTrue(loginTool.getInputSchema().contains("username"));
        assertTrue(loginTool.getInputSchema().contains("password"));
        assertTrue(loginTool.getDescription().contains("ログイン") || loginTool.getDescription().contains("login"));
    }

    // ========== Input Validation Tests ==========

    @Test
    public void testRagSearchToolRejectsQueryTooLong() throws Exception {
        // Given: Query exceeding MAX_QUERY_LENGTH (10000)
        String repositoryId = "bedroom";
        String userId = "admin";
        String tooLongQuery = "x".repeat(10001);

        authHandler.registerSessionToken("test-token", userId, repositoryId);

        Map<String, Object> arguments = Map.of("query", tooLongQuery);

        // When
        McpToolResult result = toolsProvider.executeRagSearchTool(arguments, repositoryId, userId);

        // Then
        assertFalse(result.isSuccess());
        assertTrue(result.getContent().contains("query exceeds maximum length"));
    }

    @Test
    public void testRagSearchToolRejectsEmptyQuery() throws Exception {
        // Given
        String repositoryId = "bedroom";
        String userId = "admin";

        authHandler.registerSessionToken("test-token", userId, repositoryId);

        Map<String, Object> arguments = Map.of("query", "");

        // When
        McpToolResult result = toolsProvider.executeRagSearchTool(arguments, repositoryId, userId);

        // Then
        assertFalse(result.isSuccess());
        assertTrue(result.getContent().contains("query is required"));
    }

    @Test
    public void testRagSearchToolRejectsTopKTooSmall() throws Exception {
        // Given
        String repositoryId = "bedroom";
        String userId = "admin";

        authHandler.registerSessionToken("test-token", userId, repositoryId);

        Map<String, Object> arguments = Map.of(
            "query", "test",
            "topK", 0  // MIN_TOP_K is 1
        );

        // When
        McpToolResult result = toolsProvider.executeRagSearchTool(arguments, repositoryId, userId);

        // Then
        assertFalse(result.isSuccess());
        assertTrue(result.getContent().contains("topK must be between"));
    }

    @Test
    public void testRagSearchToolRejectsTopKTooLarge() throws Exception {
        // Given
        String repositoryId = "bedroom";
        String userId = "admin";

        authHandler.registerSessionToken("test-token", userId, repositoryId);

        Map<String, Object> arguments = Map.of(
            "query", "test",
            "topK", 101  // MAX_TOP_K is 100
        );

        // When
        McpToolResult result = toolsProvider.executeRagSearchTool(arguments, repositoryId, userId);

        // Then
        assertFalse(result.isSuccess());
        assertTrue(result.getContent().contains("topK must be between"));
    }

    @Test
    public void testRagSearchToolRejectsMinScoreOutOfRange() throws Exception {
        // Given
        String repositoryId = "bedroom";
        String userId = "admin";

        authHandler.registerSessionToken("test-token", userId, repositoryId);

        Map<String, Object> arguments = Map.of(
            "query", "test",
            "minScore", 1.5f  // minScore must be 0.0-1.0
        );

        // When
        McpToolResult result = toolsProvider.executeRagSearchTool(arguments, repositoryId, userId);

        // Then
        assertFalse(result.isSuccess());
        assertTrue(result.getContent().contains("minScore must be between"));
    }

    @Test
    public void testSimilarDocumentsToolRejectsDocumentIdTooLong() throws Exception {
        // Given
        String repositoryId = "bedroom";
        String userId = "admin";
        String tooLongDocumentId = "x".repeat(201);  // MAX_DOCUMENT_ID_LENGTH is 200

        authHandler.registerSessionToken("test-token", userId, repositoryId);

        Map<String, Object> arguments = Map.of("documentId", tooLongDocumentId);

        // When
        McpToolResult result = toolsProvider.executeSimilarDocumentsTool(arguments, repositoryId, userId);

        // Then
        assertFalse(result.isSuccess());
        assertTrue(result.getContent().contains("documentId exceeds maximum length"));
    }

    @Test
    public void testSimilarDocumentsToolRejectsEmptyDocumentId() throws Exception {
        // Given
        String repositoryId = "bedroom";
        String userId = "admin";

        authHandler.registerSessionToken("test-token", userId, repositoryId);

        Map<String, Object> arguments = Map.of("documentId", "");

        // When
        McpToolResult result = toolsProvider.executeSimilarDocumentsTool(arguments, repositoryId, userId);

        // Then
        assertFalse(result.isSuccess());
        assertTrue(result.getContent().contains("documentId is required"));
    }

    @Test
    public void testGetDocumentContentToolRejectsZeroMaxLength() throws Exception {
        // Given
        String repositoryId = "bedroom";
        String userId = "admin";

        authHandler.registerSessionToken("test-token", userId, repositoryId);

        Map<String, Object> arguments = Map.of(
            "documentId", "doc123",
            "maxLength", 0  // maxLength must be at least 1
        );

        // When
        McpToolResult result = toolsProvider.executeGetDocumentContentTool(arguments, repositoryId, userId);

        // Then
        assertFalse(result.isSuccess());
        assertTrue(result.getContent().contains("maxLength must be at least 1"));
    }

    @Test
    public void testGetDocumentContentToolRejectsNegativeMaxLength() throws Exception {
        // Given
        String repositoryId = "bedroom";
        String userId = "admin";

        authHandler.registerSessionToken("test-token", userId, repositoryId);

        Map<String, Object> arguments = Map.of(
            "documentId", "doc123",
            "maxLength", -1
        );

        // When
        McpToolResult result = toolsProvider.executeGetDocumentContentTool(arguments, repositoryId, userId);

        // Then
        assertFalse(result.isSuccess());
        assertTrue(result.getContent().contains("maxLength must be at least 1"));
    }

    // ========== Helper Methods ==========

    private VectorSearchResult createMockResult(String docId, String docName, String chunkText, float score) {
        VectorSearchResult result = new VectorSearchResult();
        result.setDocumentId(docId);
        result.setDocumentName(docName);
        result.setChunkText(chunkText);
        result.setScore(score);
        result.setObjectType("cmis:document");
        return result;
    }

    private String extractTokenFromResult(String content) {
        // Simple extraction - in real implementation would use JSON parsing
        int start = content.indexOf("session_token\":\"") + 16;
        int end = content.indexOf("\"", start);
        return content.substring(start, end);
    }
}
