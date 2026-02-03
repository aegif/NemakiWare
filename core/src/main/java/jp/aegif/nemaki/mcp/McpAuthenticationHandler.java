package jp.aegif.nemaki.mcp;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.cmis.factory.auth.ApiKeyService;
import org.springframework.lang.Nullable;
import jp.aegif.nemaki.model.User;
import jp.aegif.nemaki.util.AuthenticationUtil;

/**
 * Handles authentication for MCP (Model Context Protocol) requests.
 *
 * Supports multiple authentication methods:
 * 1. HTTP Basic Authentication (via Authorization header)
 * 2. HTTP Bearer Token (via Authorization header)
 * 3. MCP Session Token (via X-MCP-Session-Token header)
 * 4. API Key (via X-API-Key header) - for cloud-only users
 *
 * Authentication priority order:
 * 1. MCP Session Token (highest priority - end-user identity)
 * 2. API Key
 * 3. Bearer Token
 * 4. Basic Authentication (transport-level)
 */
@Component
public class McpAuthenticationHandler {

    private static final Logger log = LoggerFactory.getLogger(McpAuthenticationHandler.class);

    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String HEADER_MCP_SESSION_TOKEN = "X-MCP-Session-Token";
    private static final String HEADER_API_KEY = "X-API-Key";
    private static final String AUTH_BASIC = "Basic ";
    private static final String AUTH_BEARER = "Bearer ";

    // Session token format: UUID-UUID (e.g., xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx-xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx)
    // Validates token format before lookup to prevent processing of malformed tokens
    private static final Pattern SESSION_TOKEN_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE
    );
    private static final int MAX_SESSION_TOKEN_LENGTH = 73; // UUID-UUID = 36 + 1 + 36 = 73

    private final PrincipalService principalService;
    private final ApiKeyService apiKeyService;
    private final Map<String, McpSession> sessionTokens = new ConcurrentHashMap<>();
    private final Map<String, CloudLoginRequest> pendingCloudLogins = new ConcurrentHashMap<>();
    private final long sessionTtlSeconds;
    private final long cleanupIntervalMinutes;
    private ScheduledExecutorService cleanupExecutor;

    @Autowired
    public McpAuthenticationHandler(
            PrincipalService principalService,
            @Nullable @Autowired(required = false) ApiKeyService apiKeyService,
            @Value("${mcp.session.ttl.seconds:86400}") long sessionTtlSeconds,
            @Value("${mcp.session.cleanup.interval.minutes:15}") long cleanupIntervalMinutes) {
        this.principalService = principalService;
        this.apiKeyService = apiKeyService;
        this.sessionTtlSeconds = sessionTtlSeconds;
        this.cleanupIntervalMinutes = cleanupIntervalMinutes;
        if (apiKeyService == null) {
            log.warn("ApiKeyService is not available - API key authentication will be disabled");
        }
    }

    /**
     * Package-private constructor for unit testing only.
     * Uses default cleanup interval of 15 minutes.
     *
     * @param principalService The principal service for user authentication
     * @param apiKeyService The API key service for API key authentication
     * @param sessionTtlSeconds Session TTL in seconds
     */
    McpAuthenticationHandler(PrincipalService principalService, ApiKeyService apiKeyService, long sessionTtlSeconds) {
        this(principalService, apiKeyService, sessionTtlSeconds, 15);
    }

    /**
     * Initialize cleanup scheduler on startup.
     */
    @PostConstruct
    public void init() {
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mcp-session-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupExecutor.scheduleAtFixedRate(
            this::cleanupExpiredSessions,
            cleanupIntervalMinutes,
            cleanupIntervalMinutes,
            TimeUnit.MINUTES
        );
        log.info("MCP session cleanup scheduler started (interval: {} minutes, TTL: {} seconds)",
                cleanupIntervalMinutes, sessionTtlSeconds);
    }

    /**
     * Shutdown cleanup scheduler on destroy.
     */
    @PreDestroy
    public void destroy() {
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdown();
            try {
                if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    cleanupExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                cleanupExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("MCP session cleanup scheduler stopped");
        }
    }

    /**
     * Remove expired sessions and pending cloud logins from the cache.
     */
    private void cleanupExpiredSessions() {
        int removedSessions = 0;
        Iterator<Map.Entry<String, McpSession>> sessionIterator = sessionTokens.entrySet().iterator();
        while (sessionIterator.hasNext()) {
            Map.Entry<String, McpSession> entry = sessionIterator.next();
            if (entry.getValue().isExpired()) {
                sessionIterator.remove();
                removedSessions++;
            }
        }
        if (removedSessions > 0) {
            log.debug("Cleaned up {} expired MCP sessions", removedSessions);
        }

        // Also clean up expired pending cloud logins
        int removedLogins = 0;
        Iterator<Map.Entry<String, CloudLoginRequest>> loginIterator = pendingCloudLogins.entrySet().iterator();
        while (loginIterator.hasNext()) {
            Map.Entry<String, CloudLoginRequest> entry = loginIterator.next();
            if (entry.getValue().isExpired()) {
                loginIterator.remove();
                removedLogins++;
            }
        }
        if (removedLogins > 0) {
            log.debug("Cleaned up {} expired pending cloud logins", removedLogins);
        }
    }

    /**
     * Authenticate a request using the provided headers.
     *
     * Priority order (highest to lowest):
     * 1. MCP Session Token - from nemakiware_login tool, represents end-user identity
     * 2. API Key - for cloud-only users and programmatic access
     * 3. Bearer Token - API token authentication
     * 4. Basic Authentication - transport-level auth (used by MCP bridge)
     *
     * This priority ensures that when a user logs in via the MCP login tool,
     * their session token takes precedence over other authentication methods.
     *
     * @param repositoryId The target repository ID
     * @param headers Request headers map
     * @return Authentication result
     */
    public McpAuthResult authenticate(String repositoryId, Map<String, String> headers) {
        // Priority 1: MCP Session Token (end-user identity from nemakiware_login)
        String sessionToken = headers.get(HEADER_MCP_SESSION_TOKEN);
        if (sessionToken != null) {
            return authenticateSessionToken(repositoryId, sessionToken);
        }

        // Priority 2: API Key (for cloud-only users)
        String apiKey = headers.get(HEADER_API_KEY);
        if (apiKey == null) {
            // Also check lowercase header name
            apiKey = headers.get("x-api-key");
        }
        if (apiKey != null && !apiKey.isEmpty()) {
            return authenticateApiKey(repositoryId, apiKey);
        }

        // Priority 3: Bearer Token
        String authHeader = headers.get(HEADER_AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith(AUTH_BEARER)) {
            return authenticateBearer(repositoryId, authHeader);
        }

        // Priority 4: Basic Authentication (transport-level, e.g., MCP bridge)
        if (authHeader != null && authHeader.startsWith(AUTH_BASIC)) {
            return authenticateBasic(repositoryId, authHeader);
        }

        return McpAuthResult.failure("Authentication required");
    }

    /**
     * Authenticate using API Key.
     *
     * @param repositoryId The target repository ID
     * @param apiKey The API key
     * @return Authentication result
     */
    private McpAuthResult authenticateApiKey(String repositoryId, String apiKey) {
        if (apiKeyService == null) {
            log.warn("API key authentication attempted but ApiKeyService is not available");
            return McpAuthResult.failure("API key authentication not available");
        }

        String userId = apiKeyService.validateApiKey(repositoryId, apiKey);
        if (userId != null) {
            log.debug("API key authenticated for user: {}", userId);
            return McpAuthResult.success(userId, repositoryId);
        }

        return McpAuthResult.failure("Invalid API key");
    }

    /**
     * Authenticate using HTTP Basic Authentication.
     */
    private McpAuthResult authenticateBasic(String repositoryId, String authHeader) {
        try {
            String base64Credentials = authHeader.substring(AUTH_BASIC.length());
            byte[] decodedBytes = Base64.getDecoder().decode(base64Credentials);
            String credentials = new String(decodedBytes, StandardCharsets.UTF_8);

            int colonIndex = credentials.indexOf(':');
            if (colonIndex < 0) {
                return McpAuthResult.failure("Malformed Basic authentication header");
            }

            String username = credentials.substring(0, colonIndex);
            String password = credentials.substring(colonIndex + 1);

            return validateCredentials(repositoryId, username, password);

        } catch (IllegalArgumentException e) {
            log.warn("Failed to decode Basic authentication header: {}", e.getMessage());
            return McpAuthResult.failure("Malformed Basic authentication header");
        }
    }

    /**
     * Authenticate using Bearer Token (session token in Authorization header).
     */
    private McpAuthResult authenticateBearer(String repositoryId, String authHeader) {
        String token = authHeader.substring(AUTH_BEARER.length());
        return authenticateSessionToken(repositoryId, token);
    }

    /**
     * Authenticate using MCP Session Token.
     *
     * @param repositoryId The target repository ID
     * @param token The session token from nemakiware_login
     * @return Authentication result
     */
    public McpAuthResult authenticateSessionToken(String repositoryId, String token) {
        // Input validation: check token is not null/empty and has valid format
        if (token == null || token.isEmpty()) {
            return McpAuthResult.failure("Invalid or expired token");
        }

        // Length check to prevent processing very long strings
        if (token.length() > MAX_SESSION_TOKEN_LENGTH) {
            log.debug("Session token rejected: exceeds maximum length");
            return McpAuthResult.failure("Invalid or expired token");
        }

        // Format validation: ensure token matches expected UUID-UUID pattern
        // This prevents processing of malformed or potentially malicious tokens
        if (!SESSION_TOKEN_PATTERN.matcher(token).matches()) {
            log.debug("Session token rejected: invalid format");
            return McpAuthResult.failure("Invalid or expired token");
        }

        McpSession session = sessionTokens.get(token);

        if (session == null) {
            return McpAuthResult.failure("Invalid or expired token");
        }

        if (session.isExpired()) {
            sessionTokens.remove(token);
            return McpAuthResult.failure("Invalid or expired token");
        }

        // Validate repository ID matches (security: potential token reuse attempt across repositories)
        if (!repositoryId.equals(session.getRepositoryId())) {
            log.error("Session token repository mismatch (potential security issue): expected {}, got {}",
                    repositoryId, session.getRepositoryId());
            return McpAuthResult.failure("Invalid or expired token");
        }

        return McpAuthResult.success(session.getUserId(), session.getRepositoryId());
    }

    /**
     * Validate username and password against the repository.
     * Note: Returns same error message for both "user not found" and "wrong password"
     * to prevent user enumeration attacks.
     */
    private McpAuthResult validateCredentials(String repositoryId, String username, String password) {
        User user = principalService.getUserById(repositoryId, username);

        if (user == null) {
            // Don't reveal whether user exists - use same message as wrong password
            return McpAuthResult.failure("Invalid credentials");
        }

        if (!AuthenticationUtil.passwordMatches(password, user.getPasswordHash())) {
            return McpAuthResult.failure("Invalid credentials");
        }

        return McpAuthResult.success(username, repositoryId);
    }

    /**
     * Login and create a new session token.
     *
     * @param repositoryId The repository to login to
     * @param username User's username
     * @param password User's password
     * @return Login result containing the session token if successful
     */
    public McpLoginResult login(String repositoryId, String username, String password) {
        McpAuthResult authResult = validateCredentials(repositoryId, username, password);

        if (!authResult.isSuccess()) {
            return McpLoginResult.failure(authResult.getErrorMessage());
        }

        // Generate a secure session token
        String sessionToken = generateSessionToken();
        McpSession session = new McpSession(username, repositoryId, Instant.now().plusSeconds(sessionTtlSeconds));
        sessionTokens.put(sessionToken, session);

        log.info("MCP login successful for user '{}' in repository '{}'", username, repositoryId);

        return McpLoginResult.success(sessionToken, username, repositoryId);
    }

    /**
     * Logout and invalidate the session token.
     *
     * @param sessionToken The session token to invalidate
     */
    public void logout(String sessionToken) {
        McpSession session = sessionTokens.remove(sessionToken);
        if (session != null) {
            log.info("MCP logout for user '{}'", session.getUserId());
        }
    }

    /**
     * Register a session token.
     * Package-private: intended for testing only.
     */
    void registerSessionToken(String token, String userId, String repositoryId) {
        McpSession session = new McpSession(userId, repositoryId, Instant.now().plusSeconds(sessionTtlSeconds));
        sessionTokens.put(token, session);
    }

    /**
     * Expire a session token immediately.
     * Package-private: intended for testing only.
     */
    void expireSessionToken(String token) {
        McpSession session = sessionTokens.get(token);
        if (session != null) {
            // Replace with an expired session
            sessionTokens.put(token, new McpSession(session.getUserId(), session.getRepositoryId(), Instant.now().minusSeconds(1)));
        }
    }

    /**
     * Generate a secure random session token.
     */
    private String generateSessionToken() {
        return UUID.randomUUID().toString() + "-" + UUID.randomUUID().toString();
    }

    /**
     * Internal session data.
     */
    private static class McpSession {
        private final String userId;
        private final String repositoryId;
        private final Instant expiresAt;

        McpSession(String userId, String repositoryId, Instant expiresAt) {
            this.userId = userId;
            this.repositoryId = repositoryId;
            this.expiresAt = expiresAt;
        }

        String getUserId() {
            return userId;
        }

        String getRepositoryId() {
            return repositoryId;
        }

        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    /**
     * Login using an API key and create a new session token.
     * This is useful for cloud-only users who have generated an API key.
     *
     * @param repositoryId The repository to login to
     * @param apiKey The API key
     * @return Login result containing the session token if successful
     */
    public McpLoginResult loginWithApiKey(String repositoryId, String apiKey) {
        if (apiKeyService == null) {
            return McpLoginResult.failure("API key authentication not available");
        }

        String userId = apiKeyService.validateApiKey(repositoryId, apiKey);
        if (userId == null) {
            return McpLoginResult.failure("Invalid API key");
        }

        // Generate a secure session token
        String sessionToken = generateSessionToken();
        McpSession session = new McpSession(userId, repositoryId, Instant.now().plusSeconds(sessionTtlSeconds));
        sessionTokens.put(sessionToken, session);

        log.info("MCP API key login successful for user '{}' in repository '{}'", userId, repositoryId);

        return McpLoginResult.success(sessionToken, userId, repositoryId);
    }

    /**
     * Initiate a cloud login request.
     * Returns a login code that the user needs to enter in the browser.
     *
     * @param repositoryId The repository to login to
     * @return Cloud login initiation result with login URL and code
     */
    public CloudLoginInitResult initiateCloudLogin(String repositoryId) {
        String loginCode = generateLoginCode();
        String requestId = UUID.randomUUID().toString();

        CloudLoginRequest request = new CloudLoginRequest(
            requestId,
            repositoryId,
            loginCode,
            Instant.now().plusSeconds(300) // 5 minutes to complete login
        );
        pendingCloudLogins.put(requestId, request);

        // SECURITY: Don't log the login code - it's a shared secret
        log.info("Cloud login initiated: requestId={}", requestId);

        return new CloudLoginInitResult(requestId, loginCode);
    }

    /**
     * Check the status of a cloud login request.
     *
     * @param requestId The request ID from initiateCloudLogin
     * @return Login result if authentication completed, null if still pending
     */
    public McpLoginResult checkCloudLoginStatus(String requestId) {
        CloudLoginRequest request = pendingCloudLogins.get(requestId);

        if (request == null) {
            return McpLoginResult.failure("Invalid or expired login request");
        }

        if (request.isExpired()) {
            pendingCloudLogins.remove(requestId);
            return McpLoginResult.failure("Login request expired");
        }

        if (!request.isCompleted()) {
            // Still waiting for user to complete browser authentication
            return null;
        }

        // Login completed - create session and return
        // Note: We don't remove the request here anymore to allow re-login with different account
        // The request will be removed when it expires (cleanup) or when a new request is initiated

        String sessionToken = generateSessionToken();
        McpSession session = new McpSession(
            request.getUserId(),
            request.getRepositoryId(),
            Instant.now().plusSeconds(sessionTtlSeconds)
        );
        sessionTokens.put(sessionToken, session);

        // Reset the completed flag so user can re-login with a different account
        // The user ID will be updated when completeCloudLogin is called again
        request.resetForRelogin();

        log.info("MCP cloud login successful for user '{}' in repository '{}'",
            request.getUserId(), request.getRepositoryId());

        return McpLoginResult.success(sessionToken, request.getUserId(), request.getRepositoryId());
    }

    /**
     * Complete a cloud login request after browser authentication.
     * Called by the callback endpoint when OAuth completes.
     *
     * SECURITY:
     * - Requires both requestId and loginCode to prevent brute-force attacks
     * - Uses constant-time comparison for login code to prevent timing attacks
     * - Limits failed attempts per request to prevent enumeration
     *
     * @param requestId The request ID from the URL
     * @param loginCode The login code displayed to the user
     * @param userId The authenticated user ID
     * @return true if the login was completed, false if the code was invalid
     */
    public boolean completeCloudLogin(String requestId, String loginCode, String userId) {
        // SECURITY: Require requestId to narrow down the search and prevent brute-force
        if (requestId == null || requestId.isEmpty()) {
            log.warn("Cloud login completion failed: requestId is required");
            return false;
        }

        CloudLoginRequest request = pendingCloudLogins.get(requestId);
        if (request == null) {
            log.warn("Cloud login completion failed: invalid requestId");
            return false;
        }

        if (request.isExpired()) {
            pendingCloudLogins.remove(requestId);
            log.warn("Cloud login completion failed: request expired");
            return false;
        }

        // SECURITY: Check failure limit before attempting validation
        if (request.isFailedTooManyTimes()) {
            pendingCloudLogins.remove(requestId);
            log.warn("Cloud login completion failed: too many failed attempts for requestId={}", requestId);
            return false;
        }

        // Use constant-time comparison to prevent timing attacks
        if (java.security.MessageDigest.isEqual(
                request.getLoginCode().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                loginCode.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            request.complete(userId);
            log.info("Cloud login completed for requestId={}, user={}", requestId, userId);
            return true;
        }

        // SECURITY: Increment failure count on invalid code
        request.incrementFailedAttempts();
        log.warn("Cloud login completion failed: invalid code for requestId={} (attempts: {})",
                requestId, request.getFailedAttempts());
        return false;
    }

    /**
     * Legacy method for backward compatibility.
     * @deprecated Use {@link #completeCloudLogin(String, String, String)} with requestId instead.
     */
    @Deprecated
    public boolean completeCloudLogin(String loginCode, String userId) {
        // SECURITY: This method is deprecated and will be removed.
        // For now, search all pending requests (less secure)
        for (Map.Entry<String, CloudLoginRequest> entry : pendingCloudLogins.entrySet()) {
            CloudLoginRequest request = entry.getValue();
            if (!request.isExpired() && !request.isFailedTooManyTimes() &&
                    java.security.MessageDigest.isEqual(
                        request.getLoginCode().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        loginCode.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                request.complete(userId);
                log.info("Cloud login completed for requestId={}, user={}", entry.getKey(), userId);
                return true;
            }
        }
        log.warn("Invalid or expired cloud login attempt");
        return false;
    }

    /**
     * Generate a secure login code.
     *
     * SECURITY: Uses 128 bits of entropy encoded as Base64URL (22 characters).
     * This provides 2^128 combinations, making brute-force attacks computationally
     * infeasible even without rate limiting.
     *
     * Combined with requestId requirement, an attacker would need to guess both:
     * - The UUID requestId (122 bits of entropy)
     * - The login code (128 bits of entropy)
     */
    private String generateLoginCode() {
        // Generate 128 bits (16 bytes) of cryptographically secure random data
        byte[] randomBytes = new byte[16];
        new java.security.SecureRandom().nextBytes(randomBytes);
        // Encode as Base64URL without padding (22 characters)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    /**
     * Result of cloud login initiation.
     */
    public static class CloudLoginInitResult {
        private final String requestId;
        private final String loginCode;

        CloudLoginInitResult(String requestId, String loginCode) {
            this.requestId = requestId;
            this.loginCode = loginCode;
        }

        public String getRequestId() {
            return requestId;
        }

        public String getLoginCode() {
            return loginCode;
        }
    }

    /**
     * Internal cloud login request data.
     */
    private static class CloudLoginRequest {
        /** Maximum number of failed attempts before the request is invalidated */
        private static final int MAX_FAILED_ATTEMPTS = 5;

        private final String requestId;
        private final String repositoryId;
        private final String loginCode;
        private final Instant expiresAt;
        private volatile boolean completed = false;
        private volatile String userId;
        private volatile int failedAttempts = 0;

        CloudLoginRequest(String requestId, String repositoryId, String loginCode, Instant expiresAt) {
            this.requestId = requestId;
            this.repositoryId = repositoryId;
            this.loginCode = loginCode;
            this.expiresAt = expiresAt;
        }

        String getRequestId() {
            return requestId;
        }

        String getRepositoryId() {
            return repositoryId;
        }

        String getLoginCode() {
            return loginCode;
        }

        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }

        boolean isCompleted() {
            return completed;
        }

        String getUserId() {
            return userId;
        }

        void complete(String userId) {
            this.userId = userId;
            this.completed = true;
        }

        /**
         * Reset the completed state to allow re-login with a different account.
         * Called after the session token is returned to MCP client.
         */
        void resetForRelogin() {
            this.completed = false;
            // Keep userId for logging purposes, it will be overwritten on next complete()
        }

        /**
         * Increment the failed attempts counter.
         * SECURITY: Limits brute-force attempts against the login code.
         */
        synchronized void incrementFailedAttempts() {
            this.failedAttempts++;
        }

        /**
         * Get the current number of failed attempts.
         */
        int getFailedAttempts() {
            return failedAttempts;
        }

        /**
         * Check if too many failed attempts have occurred.
         * SECURITY: After MAX_FAILED_ATTEMPTS, the request should be invalidated.
         */
        boolean isFailedTooManyTimes() {
            return failedAttempts >= MAX_FAILED_ATTEMPTS;
        }
    }
}
