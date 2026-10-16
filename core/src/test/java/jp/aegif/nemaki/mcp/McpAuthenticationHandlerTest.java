package jp.aegif.nemaki.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.cmis.factory.auth.ApiKeyService;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.model.User;
import jp.aegif.nemaki.model.UserItem;

/**
 * TDD Tests for MCP Authentication Handler.
 *
 * Tests authentication via:
 * 1. HTTP Basic Authentication header
 * 2. HTTP Bearer Token header
 * 3. MCP Session Token (from login tool)
 * 4. API Key (for cloud-only users)
 */
public class McpAuthenticationHandlerTest {

    @Mock
    private PrincipalService principalService;

    @Mock
    private ApiKeyService apiKeyService;

    @Mock
    private ContentService contentService;

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

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        authHandler = new McpAuthenticationHandler(principalService, apiKeyService, TEST_SESSION_TTL_SECONDS);
        // As in production: the account a session or key acts for is read (3.4.1 — it must still be there, not disabled).
        authHandler.setContentService(contentService);
        // Built BEFORE the stubbing: boundAccount() stubs a mock of its own, and nesting that inside
        // when(...) is an unfinished stubbing that fails every test in the class (Mockito).
        jp.aegif.nemaki.model.UserItem anyAccount = boundAccount();
        when(contentService.getUserItemById(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(anyAccount);
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
    @org.junit.jupiter.api.DisplayName("an MCP session ends when its account is disabled — and is dropped, so re-enabling the account does not revive it (3.4.1, zero-trust P0)")
    public void anMcpSessionEndsWhenItsAccountIsDisabled() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-MCP-Session-Token", VALID_TOKEN_4);
        authHandler.registerSessionToken(VALID_TOKEN_4, "testuser", "bedroom");
        jp.aegif.nemaki.model.UserItem disabled = boundAccount();
        when(disabled.getSubTypeProperties()).thenReturn(java.util.List.of(
                new jp.aegif.nemaki.model.Property("nemaki:allowedAuthMethods", "disabled")));
        when(contentService.getUserItemById("bedroom", "testuser")).thenReturn(disabled);

        assertFalse(authHandler.authenticate("bedroom", headers).isSuccess(), "the session of a disabled account still acted");

        jp.aegif.nemaki.model.UserItem enabled = boundAccount();
        when(contentService.getUserItemById("bedroom", "testuser")).thenReturn(enabled);
        assertFalse(authHandler.authenticate("bedroom", headers).isSuccess(), "the dropped session came back with the account");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("an MCP API key stops when its account is deleted — a key never expires (3.4.1, zero-trust P0)")
    public void anMcpApiKeyStopsWhenItsAccountIsDeleted() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-API-Key", "nk_key-1");
        when(apiKeyService.validateApiKey("bedroom", "nk_key-1")).thenReturn("gone");
        when(contentService.getUserItemById("bedroom", "gone")).thenReturn(null);

        assertFalse(authHandler.authenticate("bedroom", headers).isSuccess(), "the key of a deleted account still acted");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("where the account's state cannot be read, neither an MCP session nor an API key acts for it (3.4.1, zero-trust P0)")
    public void nothingActsForAnAccountWhoseStateCannotBeRead() {
        McpAuthenticationHandler unwired = new McpAuthenticationHandler(principalService, apiKeyService, TEST_SESSION_TTL_SECONDS);
        // Since R170 (3.4.1 batch 19) a session is bound to the account DOCUMENT, so where the
        // account cannot be read no session is OPENED — which is stronger than refusing it on use,
        // and is why the session arm of this lock now measures the opening. "A session that exists
        // while the account cannot be read" is no longer a state this handler can be in: every way
        // of opening one reads the account first.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> unwired.registerSessionToken(VALID_TOKEN_4, "testuser", "bedroom"),
                "a session was opened for an account no one could read");
        when(apiKeyService.validateApiKey("bedroom", "nk_key-2")).thenReturn("testuser");
        Map<String, String> session = new HashMap<>();
        session.put("X-MCP-Session-Token", VALID_TOKEN_4);
        Map<String, String> key = new HashMap<>();
        key.put("X-API-Key", "nk_key-2");

        assertFalse(unwired.authenticate("bedroom", session).isSuccess(), "a session acted for an account no one could read");
        assertFalse(unwired.authenticate("bedroom", key).isSuccess(), "a key acted for an account no one could read");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("guessing an MCP password is counted and then blocked — it went through no throttle at all (3.4.1, external review S6)")
    public void guessingAnMcpPasswordIsEventuallyBlocked() {
        String repositoryId = "bedroom";
        String username = "admin";
        User mockUser = mock(User.class);
        when(mockUser.getUserId()).thenReturn(username);
        when(mockUser.getPasswordHash()).thenReturn(ADMIN_PASSWORD_HASH);
        when(principalService.getUserById(repositoryId, username)).thenReturn(mockUser);

        // The configured allowance is 15 failures. Guess past it.
        for (int attempt = 0; attempt < 15; attempt++) {
            assertFalse(authHandler.login(repositoryId, username, "not-the-password").isSuccess(),
                    "a wrong password succeeded on attempt " + attempt);
        }

        // Now even the RIGHT password is refused: the throttle is what answers, not the password check.
        // Without a throttle this login succeeds, which is the defect — guessing over MCP was unlimited.
        assertFalse(authHandler.login(repositoryId, username, "admin").isSuccess(),
                "the correct password still logged in after 15 failures, so nothing is counting them");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("a correct password the account may not use does not reset the counter (3.4.1, round-5 review)")
    public void aCorrectPasswordTheAccountMayNotUseDoesNotResetTheCounter() {
        // recordSuccess used to run as soon as the password matched, BEFORE the allowedAuthMethods
        // check refused it. So a caller who knows the password of a disabled or cloud-only account
        // could clear the counter at will and guess forever in batches of fourteen.
        String repositoryId = "bedroom";
        String username = "alice";
        User alice = mock(User.class);
        when(alice.getUserId()).thenReturn(username);
        when(alice.getPasswordHash()).thenReturn(ADMIN_PASSWORD_HASH);
        when(principalService.getUserById(repositoryId, username)).thenReturn(alice);

        jp.aegif.nemaki.model.UserItem cloudOnly = boundAccount();
        when(cloudOnly.getSubTypeProperties()).thenReturn(java.util.List.of(
                new jp.aegif.nemaki.model.Property("nemaki:allowedAuthMethods", "cloud")));
        when(contentService.getUserItemById(repositoryId, username)).thenReturn(cloudOnly);

        for (int attempt = 0; attempt < 14; attempt++) {
            assertFalse(authHandler.login(repositoryId, username, "not-the-password").isSuccess());
        }
        // The password is right; the policy refuses it. This must NOT clear the fourteen.
        assertFalse(authHandler.login(repositoryId, username, "admin").isSuccess(),
                "a cloud-only account logged in with a password over MCP");

        // The fifteenth failure completes the lockout.
        assertFalse(authHandler.login(repositoryId, username, "not-the-password").isSuccess());

        // The administrator now allows passwords again. The lockout must still hold: if the refused
        // login above cleared the counter, this one succeeds and nothing was counted.
        jp.aegif.nemaki.model.UserItem allowed = boundAccount();
        when(allowed.getSubTypeProperties()).thenReturn(java.util.List.of(
                new jp.aegif.nemaki.model.Property("nemaki:allowedAuthMethods", "password")));
        when(contentService.getUserItemById(repositoryId, username)).thenReturn(allowed);

        assertFalse(authHandler.login(repositoryId, username, "admin").isSuccess(),
                "the correct password logged in, so the refused login reset the counter and guessing"
                        + " over MCP is unlimited in batches of fourteen");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("an MCP login with the API key of a deleted or disabled account opens no session (3.4.1, zero-trust P0)")
    public void anApiKeyLoginOpensNoSessionForAnAccountThatCannotAct() {
        when(apiKeyService.validateApiKey("bedroom", "nk_key-3")).thenReturn("testuser");
        jp.aegif.nemaki.model.UserItem disabled = boundAccount();
        when(disabled.getSubTypeProperties()).thenReturn(java.util.List.of(
                new jp.aegif.nemaki.model.Property("nemaki:allowedAuthMethods", "disabled")));
        when(contentService.getUserItemById("bedroom", "testuser")).thenReturn(disabled);
        assertFalse(authHandler.loginWithApiKey("bedroom", "nk_key-3").isSuccess(),
                "the API key of a disabled account opened an MCP session");

        when(apiKeyService.validateApiKey("bedroom", "nk_key-4")).thenReturn("gone");
        when(contentService.getUserItemById("bedroom", "gone")).thenReturn(null);
        assertFalse(authHandler.loginWithApiKey("bedroom", "nk_key-4").isSuccess(),
                "the API key of a deleted account opened an MCP session");

        jp.aegif.nemaki.model.UserItem readable = boundAccount();
        when(contentService.getUserItemById("bedroom", "testuser")).thenReturn(readable);
        assertTrue(authHandler.loginWithApiKey("bedroom", "nk_key-3").isSuccess(), "the key of an account that can act was refused");
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

    // ========== allowedAuthMethods policy gate (security audit follow-up, 3.2.1) ==========

    /**
     * An account mock that names its own DOCUMENT, which every credential is bound to since R170.
     *
     * <p>A bare {@code mock(UserItem.class)} answers null for {@code getId()}, and a session or key
     * bound to null is refused — so a fixture that used one would make every test here fail on THAT
     * refusal instead of on what it is about (3.4.1 batch 19).
     */
    private static jp.aegif.nemaki.model.UserItem boundAccount() {
        jp.aegif.nemaki.model.UserItem account = mock(jp.aegif.nemaki.model.UserItem.class);
        when(account.getId()).thenReturn("account-document");
        return account;
    }

    private static UserItem userItemWithPolicy(String userId, String allowedAuthMethods) {
        UserItem u = new UserItem();
        u.setId(userId + "-doc");
        u.setUserId(userId);
        if (allowedAuthMethods != null) {
            u.setSubTypeProperties(List.of(new Property("nemaki:allowedAuthMethods", allowedAuthMethods)));
        }
        return u;
    }

    /** A correct password must NOT authenticate a "disabled" account over MCP Basic auth. */
    @Test
    public void testBasicAuthDeniedForDisabledAccount() {
        String repositoryId = "bedroom";
        String username = "admin";
        String password = "admin"; // correct password
        String basicAuth = "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes());

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", basicAuth);

        User mockUser = mock(User.class);
        when(mockUser.getUserId()).thenReturn(username);
        when(mockUser.getPasswordHash()).thenReturn(ADMIN_PASSWORD_HASH);
        when(principalService.getUserById(repositoryId, username)).thenReturn(mockUser);

        authHandler.setContentService(contentService);
        when(contentService.getUserItemById(repositoryId, username))
                .thenReturn(userItemWithPolicy(username, "disabled"));

        McpAuthResult result = authHandler.authenticate(repositoryId, headers);

        // Same generic message as a wrong password: no "account disabled" oracle.
        assertFalse(result.isSuccess());
        assertEquals("Invalid credentials", result.getErrorMessage());
    }

    /** A correct password must NOT create a session for a "cloud"-only account via MCP login. */
    @Test
    public void testLoginDeniedForCloudOnlyAccount() {
        String repositoryId = "bedroom";
        String username = "admin";
        String password = "admin"; // correct password

        User mockUser = mock(User.class);
        when(mockUser.getUserId()).thenReturn(username);
        when(mockUser.getPasswordHash()).thenReturn(ADMIN_PASSWORD_HASH);
        when(principalService.getUserById(repositoryId, username)).thenReturn(mockUser);

        authHandler.setContentService(contentService);
        when(contentService.getUserItemById(repositoryId, username))
                .thenReturn(userItemWithPolicy(username, "cloud"));

        McpLoginResult result = authHandler.login(repositoryId, username, password);

        assertFalse(result.isSuccess());
        assertNull(result.getSessionToken());
        assertEquals("Invalid credentials", result.getErrorMessage());
    }

    /** Control: a "password"-policy account still authenticates (no over-block). */
    @Test
    public void testBasicAuthAllowedForPasswordPolicyAccount() {
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

        authHandler.setContentService(contentService);
        when(contentService.getUserItemById(repositoryId, username))
                .thenReturn(userItemWithPolicy(username, "password"));

        McpAuthResult result = authHandler.authenticate(repositoryId, headers);

        assertTrue(result.isSuccess());
        assertEquals(username, result.getUserId());
    }
}
