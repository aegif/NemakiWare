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

    // Valid UUID-UUID format tokens for testing (format validation requires this)
    private static final String VALID_TOKEN_1 = "11111111-1111-1111-1111-111111111111-22222222-2222-2222-2222-222222222222";
    private static final String VALID_TOKEN_2 = "33333333-3333-3333-3333-333333333333-44444444-4444-4444-4444-444444444444";
    private static final String VALID_TOKEN_3 = "55555555-5555-5555-5555-555555555555-66666666-6666-6666-6666-666666666666";
    private static final String VALID_TOKEN_4 = "77777777-7777-7777-7777-777777777777-88888888-8888-8888-8888-888888888888";

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

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + VALID_TOKEN_1);

        // Setup: Register a valid session token first
        authHandler.registerSessionToken(VALID_TOKEN_1, username, repositoryId);

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

        Map<String, String> headers = new HashMap<>();
        headers.put("X-MCP-Session-Token", VALID_TOKEN_2);

        // Setup: Register a valid session token
        authHandler.registerSessionToken(VALID_TOKEN_2, username, repositoryId);

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

        Map<String, String> headers = new HashMap<>();
        headers.put("X-MCP-Session-Token", VALID_TOKEN_3);

        // Setup: Register then expire the token
        authHandler.registerSessionToken(VALID_TOKEN_3, username, repositoryId);
        authHandler.expireSessionToken(VALID_TOKEN_3);

        // When
        McpAuthResult result = authHandler.authenticate(repositoryId, headers);

        // Then
        assertFalse(result.isSuccess());
        assertEquals("Invalid or expired token", result.getErrorMessage());
    }

    // ========== Authentication Priority Tests ==========

    @Test
    public void testSessionTokenTakesPriorityOverBasicAuth() {
        // Given: Both Basic Auth and Session Token provided
        // Session Token should take priority (represents end-user identity)
        String repositoryId = "bedroom";
        String basicUsername = "admin";
        String basicPassword = "admin";
        String sessionUsername = "sessionuser";

        String basicAuth = "Basic " + Base64.getEncoder().encodeToString((basicUsername + ":" + basicPassword).getBytes());

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", basicAuth);
        headers.put("X-MCP-Session-Token", VALID_TOKEN_4);

        User mockUser = mock(User.class);
        when(mockUser.getUserId()).thenReturn(basicUsername);
        when(mockUser.getPasswordHash()).thenReturn(ADMIN_PASSWORD_HASH);
        when(principalService.getUserById(repositoryId, basicUsername)).thenReturn(mockUser);

        authHandler.registerSessionToken(VALID_TOKEN_4, sessionUsername, repositoryId);

        // When
        McpAuthResult result = authHandler.authenticate(repositoryId, headers);

        // Then: Session Token should be used (takes priority over Basic Auth)
        assertTrue(result.isSuccess());
        assertEquals(sessionUsername, result.getUserId());
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

    // ========== Session Token Format Validation Tests ==========

    @Test
    public void testSessionTokenValidationRejectsNullToken() {
        // Given
        String repositoryId = "bedroom";

        // When
        McpAuthResult result = authHandler.authenticateSessionToken(repositoryId, null);

        // Then
        assertFalse(result.isSuccess());
        assertEquals("Invalid or expired token", result.getErrorMessage());
    }

    @Test
    public void testSessionTokenValidationRejectsEmptyToken() {
        // Given
        String repositoryId = "bedroom";

        // When
        McpAuthResult result = authHandler.authenticateSessionToken(repositoryId, "");

        // Then
        assertFalse(result.isSuccess());
        assertEquals("Invalid or expired token", result.getErrorMessage());
    }

    @Test
    public void testSessionTokenValidationRejectsTooLongToken() {
        // Given
        String repositoryId = "bedroom";
        // Token longer than MAX_SESSION_TOKEN_LENGTH (73)
        String tooLongToken = "a".repeat(100);

        // When
        McpAuthResult result = authHandler.authenticateSessionToken(repositoryId, tooLongToken);

        // Then
        assertFalse(result.isSuccess());
        assertEquals("Invalid or expired token", result.getErrorMessage());
    }

    @Test
    public void testSessionTokenValidationRejectsInvalidFormat() {
        // Given
        String repositoryId = "bedroom";
        // Token with invalid format (not UUID-UUID)
        String invalidFormatToken = "invalid-token-format-12345";

        // When
        McpAuthResult result = authHandler.authenticateSessionToken(repositoryId, invalidFormatToken);

        // Then
        assertFalse(result.isSuccess());
        assertEquals("Invalid or expired token", result.getErrorMessage());
    }

    @Test
    public void testSessionTokenValidationRejectsSingleUuid() {
        // Given
        String repositoryId = "bedroom";
        // Token with only single UUID (not UUID-UUID format)
        String singleUuid = "550e8400-e29b-41d4-a716-446655440000";

        // When
        McpAuthResult result = authHandler.authenticateSessionToken(repositoryId, singleUuid);

        // Then
        assertFalse(result.isSuccess());
        assertEquals("Invalid or expired token", result.getErrorMessage());
    }

    @Test
    public void testSessionTokenValidationAcceptsValidFormat() {
        // Given
        String repositoryId = "bedroom";
        String username = "testuser";
        // Valid UUID-UUID format token
        String validToken = "550e8400-e29b-41d4-a716-446655440000-550e8400-e29b-41d4-a716-446655440001";

        // Register the token first
        authHandler.registerSessionToken(validToken, username, repositoryId);

        // When
        McpAuthResult result = authHandler.authenticateSessionToken(repositoryId, validToken);

        // Then
        assertTrue(result.isSuccess());
        assertEquals(username, result.getUserId());
        assertEquals(repositoryId, result.getRepositoryId());
    }

    @Test
    public void testSessionTokenValidationAcceptsUppercaseUuid() {
        // Given
        String repositoryId = "bedroom";
        String username = "testuser";
        // Valid UUID-UUID format token with uppercase (should be case insensitive)
        String upperCaseToken = "550E8400-E29B-41D4-A716-446655440000-550E8400-E29B-41D4-A716-446655440001";

        // Register the token first
        authHandler.registerSessionToken(upperCaseToken, username, repositoryId);

        // When
        McpAuthResult result = authHandler.authenticateSessionToken(repositoryId, upperCaseToken);

        // Then
        assertTrue(result.isSuccess());
    }

    @Test
    public void testSessionTokenValidationRejectsRepositoryMismatch() {
        // Given
        String originalRepositoryId = "bedroom";
        String differentRepositoryId = "livingroom";
        String username = "testuser";
        String validToken = "550e8400-e29b-41d4-a716-446655440000-550e8400-e29b-41d4-a716-446655440001";

        // Register the token for one repository
        authHandler.registerSessionToken(validToken, username, originalRepositoryId);

        // When: Try to use it for a different repository
        McpAuthResult result = authHandler.authenticateSessionToken(differentRepositoryId, validToken);

        // Then
        assertFalse(result.isSuccess());
        assertEquals("Invalid or expired token", result.getErrorMessage());
    }
}
