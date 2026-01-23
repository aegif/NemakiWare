package jp.aegif.nemaki.mcp;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mindrot.jbcrypt.BCrypt;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.model.User;

/**
 * TDD Tests for MCP Authentication Handler.
 *
 * Tests authentication via:
 * 1. HTTP Basic Authentication header
 * 2. HTTP Bearer Token header
 * 3. MCP Session Token (from login tool)
 */
public class McpAuthenticationHandlerTest {

    @Mock
    private PrincipalService principalService;

    private McpAuthenticationHandler authHandler;

    // BCrypt hash for "admin" password
    private static final String ADMIN_PASSWORD_HASH = BCrypt.hashpw("admin", BCrypt.gensalt());

    // Default session TTL for tests (1 hour)
    private static final long TEST_SESSION_TTL_SECONDS = 3600;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        authHandler = new McpAuthenticationHandler(principalService, TEST_SESSION_TTL_SECONDS);
    }

    // ========== Basic Authentication Tests ==========

    @Test
    public void testBasicAuthenticationSuccess() {
        // Given
        String repositoryId = "bedroom";
        String username = "admin";
        String password = "admin";
        String basicAuth = "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes());

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", basicAuth);

        User mockUser = mock(User.class);
        when(mockUser.getUserId()).thenReturn(username);
        when(mockUser.getPasswordHash()).thenReturn(ADMIN_PASSWORD_HASH);
        when(principalService.getUserById(repositoryId, username)).thenReturn(mockUser);

        // When
        McpAuthResult result = authHandler.authenticate(repositoryId, headers);

        // Then
        assertTrue(result.isSuccess());
        assertEquals(username, result.getUserId());
        assertEquals(repositoryId, result.getRepositoryId());
    }

    @Test
    public void testBasicAuthenticationInvalidPassword() {
        // Given
        String repositoryId = "bedroom";
        String username = "admin";
        String password = "wrongpassword";
        String basicAuth = "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes());

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", basicAuth);

        User mockUser = mock(User.class);
        when(mockUser.getPasswordHash()).thenReturn(ADMIN_PASSWORD_HASH); // Hash for "admin", not "wrongpassword"
        when(principalService.getUserById(repositoryId, username)).thenReturn(mockUser);

        // When
        McpAuthResult result = authHandler.authenticate(repositoryId, headers);

        // Then
        assertFalse(result.isSuccess());
        assertEquals("Invalid credentials", result.getErrorMessage());
    }

    @Test
    public void testBasicAuthenticationUserNotFound() {
        // Given
        String repositoryId = "bedroom";
        String username = "nonexistent";
        String password = "password";
        String basicAuth = "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes());

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", basicAuth);

        when(principalService.getUserById(repositoryId, username)).thenReturn(null);

        // When
        McpAuthResult result = authHandler.authenticate(repositoryId, headers);

        // Then
        assertFalse(result.isSuccess());
        // Same message as invalid password to prevent user enumeration
        assertEquals("Invalid credentials", result.getErrorMessage());
    }

    @Test
    public void testBasicAuthenticationMalformedHeader() {
        // Given
        String repositoryId = "bedroom";
        String basicAuth = "Basic invalidbase64!!!";

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", basicAuth);

        // When
        McpAuthResult result = authHandler.authenticate(repositoryId, headers);

        // Then
        assertFalse(result.isSuccess());
        assertEquals("Malformed Basic authentication header", result.getErrorMessage());
    }

    // ========== Bearer Token Authentication Tests ==========

    @Test
    public void testBearerTokenAuthenticationSuccess() {
        // Given
        String repositoryId = "bedroom";
        String username = "admin";
        String token = "valid-auth-token-12345";

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + token);

        // Setup: Register a valid session token first
        authHandler.registerSessionToken(token, username, repositoryId);

        // When
        McpAuthResult result = authHandler.authenticate(repositoryId, headers);

        // Then
        assertTrue(result.isSuccess());
        assertEquals(username, result.getUserId());
        assertEquals(repositoryId, result.getRepositoryId());
    }

    @Test
    public void testBearerTokenAuthenticationInvalidToken() {
        // Given
        String repositoryId = "bedroom";
        String invalidToken = "invalid-token-xyz";

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + invalidToken);

        // When
        McpAuthResult result = authHandler.authenticate(repositoryId, headers);

        // Then
        assertFalse(result.isSuccess());
        assertEquals("Invalid or expired token", result.getErrorMessage());
    }

    // ========== MCP Session Token Tests ==========

    @Test
    public void testMcpSessionTokenAuthenticationSuccess() {
        // Given
        String repositoryId = "bedroom";
        String username = "testuser";
        String sessionToken = "mcp-session-token-abc123";

        Map<String, String> headers = new HashMap<>();
        headers.put("X-MCP-Session-Token", sessionToken);

        // Setup: Register a valid session token
        authHandler.registerSessionToken(sessionToken, username, repositoryId);

        // When
        McpAuthResult result = authHandler.authenticate(repositoryId, headers);

        // Then
        assertTrue(result.isSuccess());
        assertEquals(username, result.getUserId());
        assertEquals(repositoryId, result.getRepositoryId());
    }

    @Test
    public void testMcpSessionTokenExpired() {
        // Given
        String repositoryId = "bedroom";
        String username = "testuser";
        String sessionToken = "mcp-session-token-expired";

        Map<String, String> headers = new HashMap<>();
        headers.put("X-MCP-Session-Token", sessionToken);

        // Setup: Register then expire the token
        authHandler.registerSessionToken(sessionToken, username, repositoryId);
        authHandler.expireSessionToken(sessionToken);

        // When
        McpAuthResult result = authHandler.authenticate(repositoryId, headers);

        // Then
        assertFalse(result.isSuccess());
        assertEquals("Invalid or expired token", result.getErrorMessage());
    }

    // ========== Authentication Priority Tests ==========

    @Test
    public void testBasicAuthTakesPriorityOverSessionToken() {
        // Given: Both Basic Auth and Session Token provided
        String repositoryId = "bedroom";
        String basicUsername = "admin";
        String basicPassword = "admin";
        String sessionUsername = "sessionuser";
        String sessionToken = "mcp-session-token-xyz";

        String basicAuth = "Basic " + Base64.getEncoder().encodeToString((basicUsername + ":" + basicPassword).getBytes());

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", basicAuth);
        headers.put("X-MCP-Session-Token", sessionToken);

        User mockUser = mock(User.class);
        when(mockUser.getUserId()).thenReturn(basicUsername);
        when(mockUser.getPasswordHash()).thenReturn(ADMIN_PASSWORD_HASH);
        when(principalService.getUserById(repositoryId, basicUsername)).thenReturn(mockUser);

        authHandler.registerSessionToken(sessionToken, sessionUsername, repositoryId);

        // When
        McpAuthResult result = authHandler.authenticate(repositoryId, headers);

        // Then: Basic Auth should be used
        assertTrue(result.isSuccess());
        assertEquals(basicUsername, result.getUserId());
    }

    @Test
    public void testNoAuthenticationProvided() {
        // Given
        String repositoryId = "bedroom";
        Map<String, String> headers = new HashMap<>();

        // When
        McpAuthResult result = authHandler.authenticate(repositoryId, headers);

        // Then
        assertFalse(result.isSuccess());
        assertEquals("Authentication required", result.getErrorMessage());
    }

    // ========== Login Tool Integration Tests ==========

    @Test
    public void testLoginCreatesSessionToken() {
        // Given
        String repositoryId = "bedroom";
        String username = "admin";
        String password = "admin";

        User mockUser = mock(User.class);
        when(mockUser.getUserId()).thenReturn(username);
        when(mockUser.getPasswordHash()).thenReturn(ADMIN_PASSWORD_HASH);
        when(principalService.getUserById(repositoryId, username)).thenReturn(mockUser);

        // When
        McpLoginResult loginResult = authHandler.login(repositoryId, username, password);

        // Then
        assertTrue(loginResult.isSuccess());
        assertNotNull(loginResult.getSessionToken());
        assertTrue(loginResult.getSessionToken().length() > 20);

        // Verify token can be used for authentication
        Map<String, String> headers = new HashMap<>();
        headers.put("X-MCP-Session-Token", loginResult.getSessionToken());

        McpAuthResult authResult = authHandler.authenticate(repositoryId, headers);
        assertTrue(authResult.isSuccess());
        assertEquals(username, authResult.getUserId());
    }

    @Test
    public void testLoginFailsWithInvalidCredentials() {
        // Given
        String repositoryId = "bedroom";
        String username = "admin";
        String password = "wrongpassword";

        User mockUser = mock(User.class);
        when(mockUser.getPasswordHash()).thenReturn(ADMIN_PASSWORD_HASH); // Hash for "admin", not "wrongpassword"
        when(principalService.getUserById(repositoryId, username)).thenReturn(mockUser);

        // When
        McpLoginResult loginResult = authHandler.login(repositoryId, username, password);

        // Then
        assertFalse(loginResult.isSuccess());
        assertNull(loginResult.getSessionToken());
        assertEquals("Invalid credentials", loginResult.getErrorMessage());
    }

    @Test
    public void testLogoutInvalidatesSessionToken() {
        // Given
        String repositoryId = "bedroom";
        String username = "admin";
        String password = "admin";

        User mockUser = mock(User.class);
        when(mockUser.getUserId()).thenReturn(username);
        when(mockUser.getPasswordHash()).thenReturn(ADMIN_PASSWORD_HASH);
        when(principalService.getUserById(repositoryId, username)).thenReturn(mockUser);

        McpLoginResult loginResult = authHandler.login(repositoryId, username, password);
        String sessionToken = loginResult.getSessionToken();

        // When
        authHandler.logout(sessionToken);

        // Then: Token should no longer be valid
        Map<String, String> headers = new HashMap<>();
        headers.put("X-MCP-Session-Token", sessionToken);

        McpAuthResult authResult = authHandler.authenticate(repositoryId, headers);
        assertFalse(authResult.isSuccess());
    }
}
