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

import jp.aegif.nemaki.businesslogic.PrincipalService;
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

    private McpToolsProvider toolsProvider;
    private McpAuthenticationHandler authHandler;

    private static final String ADMIN_PASSWORD_HASH = BCrypt.hashpw("admin", BCrypt.gensalt());
    private static final long TEST_SESSION_TTL_SECONDS = 3600;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        authHandler = new McpAuthenticationHandler(principalService, TEST_SESSION_TTL_SECONDS);
        toolsProvider = new McpToolsProvider(authHandler, vectorSearchService, "http://localhost:8080/core");
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
        assertTrue(result.getContent().contains("Invoice_2024.pdf"));
        assertTrue(result.getContent().contains("Contract.pdf"));
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
        // Check markdown link format
        assertTrue(result.getContent().contains("[TestDoc.pdf](http://localhost:8080/core/ui/#/browse/bedroom/abc123)"));
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
        assertTrue(result.getContent().contains("Similar1.pdf"));
        assertTrue(result.getContent().contains("88%"));
    }

    // ========== Tool Definitions Tests ==========

    @Test
    public void testGetToolDefinitions() {
        // When
        List<McpToolDefinition> tools = toolsProvider.getToolDefinitions();

        // Then
        assertEquals(4, tools.size());

        // Verify tool names
        assertTrue(tools.stream().anyMatch(t -> t.getName().equals("nemakiware_login")));
        assertTrue(tools.stream().anyMatch(t -> t.getName().equals("nemakiware_logout")));
        assertTrue(tools.stream().anyMatch(t -> t.getName().equals("nemakiware_rag_search")));
        assertTrue(tools.stream().anyMatch(t -> t.getName().equals("nemakiware_similar_documents")));
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
