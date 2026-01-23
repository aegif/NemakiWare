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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.model.User;
import jp.aegif.nemaki.util.AuthenticationUtil;

/**
 * Handles authentication for MCP (Model Context Protocol) requests.
 *
 * Supports multiple authentication methods:
 * 1. HTTP Basic Authentication (via Authorization header)
 * 2. HTTP Bearer Token (via Authorization header)
 * 3. MCP Session Token (via X-MCP-Session-Token header)
 *
 * Authentication priority order:
 * 1. Basic Auth (highest priority)
 * 2. Bearer Token
 * 3. MCP Session Token
 */
@Component
public class McpAuthenticationHandler {

    private static final Logger log = LoggerFactory.getLogger(McpAuthenticationHandler.class);

    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String HEADER_MCP_SESSION_TOKEN = "X-MCP-Session-Token";
    private static final String AUTH_BASIC = "Basic ";
    private static final String AUTH_BEARER = "Bearer ";

    // Cleanup interval in minutes
    private static final long CLEANUP_INTERVAL_MINUTES = 15;

    private final PrincipalService principalService;
    private final Map<String, McpSession> sessionTokens = new ConcurrentHashMap<>();
    private final long sessionTtlSeconds;
    private ScheduledExecutorService cleanupExecutor;

    @Autowired
    public McpAuthenticationHandler(
            PrincipalService principalService,
            @Value("${mcp.session.ttl.seconds:86400}") long sessionTtlSeconds) {
        this.principalService = principalService;
        this.sessionTtlSeconds = sessionTtlSeconds;
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
            CLEANUP_INTERVAL_MINUTES,
            CLEANUP_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        );
        log.info("MCP session cleanup scheduler started (interval: {} minutes, TTL: {} seconds)",
                CLEANUP_INTERVAL_MINUTES, sessionTtlSeconds);
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
     * Remove expired sessions from the cache.
     */
    private void cleanupExpiredSessions() {
        int removedCount = 0;
        Iterator<Map.Entry<String, McpSession>> iterator = sessionTokens.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, McpSession> entry = iterator.next();
            if (entry.getValue().isExpired()) {
                iterator.remove();
                removedCount++;
            }
        }
        if (removedCount > 0) {
            log.debug("Cleaned up {} expired MCP sessions", removedCount);
        }
    }

    /**
     * Authenticate a request using the provided headers.
     *
     * @param repositoryId The target repository ID
     * @param headers Request headers map
     * @return Authentication result
     */
    public McpAuthResult authenticate(String repositoryId, Map<String, String> headers) {
        // Priority 1: Basic Authentication
        String authHeader = headers.get(HEADER_AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith(AUTH_BASIC)) {
            return authenticateBasic(repositoryId, authHeader);
        }

        // Priority 2: Bearer Token
        if (authHeader != null && authHeader.startsWith(AUTH_BEARER)) {
            return authenticateBearer(repositoryId, authHeader);
        }

        // Priority 3: MCP Session Token
        String sessionToken = headers.get(HEADER_MCP_SESSION_TOKEN);
        if (sessionToken != null) {
            return authenticateSessionToken(repositoryId, sessionToken);
        }

        return McpAuthResult.failure("Authentication required");
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
     */
    private McpAuthResult authenticateSessionToken(String repositoryId, String token) {
        McpSession session = sessionTokens.get(token);

        if (session == null) {
            return McpAuthResult.failure("Invalid or expired token");
        }

        if (session.isExpired()) {
            sessionTokens.remove(token);
            return McpAuthResult.failure("Invalid or expired token");
        }

        // Validate repository ID matches
        if (!repositoryId.equals(session.getRepositoryId())) {
            log.warn("Session token repository mismatch: expected {}, got {}", repositoryId, session.getRepositoryId());
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
}
