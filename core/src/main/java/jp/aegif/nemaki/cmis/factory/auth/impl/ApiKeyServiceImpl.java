/*******************************************************************************
 * Copyright (c) 2024 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package jp.aegif.nemaki.cmis.factory.auth.impl;

import jp.aegif.nemaki.cmis.factory.auth.ApiKeyService;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.ApiKey;
import jp.aegif.nemaki.util.constant.NodeType;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.mindrot.jbcrypt.BCrypt;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of ApiKeyService for managing API keys.
 */
public class ApiKeyServiceImpl implements ApiKeyService {

    private static final Log log = LogFactory.getLog(ApiKeyServiceImpl.class);

    /** Prefix for API keys */
    private static final String API_KEY_PREFIX = "nw_";

    /** Length of the random part of the API key */
    private static final int API_KEY_RANDOM_LENGTH = 32;

    /**
     * Maximum number of API keys allowed per user.
     * SECURITY: This prevents abuse where a user creates unlimited keys.
     */
    private static final int MAX_KEYS_PER_USER = 10;

    /** Interval for flushing lastUsed updates to DB (in minutes) */
    private static final int LAST_USED_FLUSH_INTERVAL_MINUTES = 5;

    /** Cache: repositoryId -> (keyId -> ApiKey) */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ApiKey>> keyCache = new ConcurrentHashMap<>();

    /** Secondary index: repositoryId -> (keyPrefix -> List<ApiKey>) for O(1) prefix lookup */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, List<ApiKey>>> prefixIndex = new ConcurrentHashMap<>();

    /** Pending lastUsed updates: repositoryId -> (keyId -> timestamp) */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, GregorianCalendar>> pendingLastUsedUpdates = new ConcurrentHashMap<>();

    /** Scheduler for periodic lastUsed flush */
    private final ScheduledExecutorService lastUsedFlushScheduler;

    private ContentDaoService contentDaoService;

    private SecureRandom secureRandom = new SecureRandom();

    public ApiKeyServiceImpl() {
        // Schedule periodic flush of lastUsed updates to database
        lastUsedFlushScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "api-key-lastused-flush");
            t.setDaemon(true);
            return t;
        });
        lastUsedFlushScheduler.scheduleAtFixedRate(
            this::flushLastUsedUpdates,
            LAST_USED_FLUSH_INTERVAL_MINUTES,
            LAST_USED_FLUSH_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        );
    }

    @Override
    public ApiKeyCreationResult createApiKey(String repositoryId, String userId, String name, String description) {
        return createApiKey(repositoryId, userId, name, description, null);
    }

    @Override
    public ApiKeyCreationResult createApiKey(String repositoryId, String userId, String name, String description, GregorianCalendar expiresAt) {
        // SECURITY: Enforce per-user API key quota
        List<ApiKey> existingKeys = listApiKeys(repositoryId, userId);
        if (existingKeys.size() >= MAX_KEYS_PER_USER) {
            log.warn("API key quota exceeded for user " + userId + " in repository " + repositoryId +
                    " (limit: " + MAX_KEYS_PER_USER + ")");
            throw new IllegalStateException("API key quota exceeded. Maximum " + MAX_KEYS_PER_USER + " keys allowed per user.");
        }

        // Generate a cryptographically secure random API key
        byte[] randomBytes = new byte[API_KEY_RANDOM_LENGTH];
        secureRandom.nextBytes(randomBytes);
        String randomPart = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        String plainTextKey = API_KEY_PREFIX + randomPart;

        // Create the key prefix for identification (first 8 chars after prefix)
        String keyPrefix = API_KEY_PREFIX + randomPart.substring(0, 8);

        // Hash the key for storage
        String keyHash = BCrypt.hashpw(plainTextKey, BCrypt.gensalt());

        // Create the ApiKey object
        ApiKey apiKey = new ApiKey(userId, repositoryId, name);
        apiKey.setId(UUID.randomUUID().toString());
        apiKey.setKeyHash(keyHash);
        apiKey.setKeyPrefix(keyPrefix);
        apiKey.setDescription(description);
        apiKey.setExpiresAt(expiresAt);
        apiKey.setActive(true);
        apiKey.setCreated(new GregorianCalendar());
        apiKey.setCreator(userId);

        // Save to database
        contentDaoService.create(repositoryId, apiKey);

        // Update cache
        invalidateCache(repositoryId);

        String expiresInfo = expiresAt != null ? " (expires: " + expiresAt.getTime() + ")" : " (never expires)";
        log.info("Created API key '" + name + "' for user " + userId + " in repository " + repositoryId + expiresInfo);

        return new ApiKeyCreationResult(apiKey, plainTextKey);
    }

    @Override
    public List<ApiKey> listApiKeys(String repositoryId, String userId) {
        List<ApiKey> result = new ArrayList<>();
        List<ApiKey> allKeys = getAllApiKeysFromCache(repositoryId);

        log.debug("listApiKeys: Cache has " + allKeys.size() + " total keys for repository " + repositoryId);
        for (ApiKey key : allKeys) {
            log.debug("listApiKeys: Key id=" + key.getId() + " userId=" + key.getUserId() + " active=" + key.isActive() + " name=" + key.getName());
            if (userId.equals(key.getUserId()) && key.isActive()) {
                result.add(key);
            }
        }
        log.debug("listApiKeys: Returning " + result.size() + " keys for user " + userId);

        return result;
    }

    @Override
    public ApiKey getApiKey(String repositoryId, String keyId) {
        List<ApiKey> allKeys = getAllApiKeysFromCache(repositoryId);
        for (ApiKey key : allKeys) {
            if (keyId.equals(key.getId())) {
                return key;
            }
        }
        return null;
    }

    @Override
    public boolean revokeApiKey(String repositoryId, String keyId) {
        ApiKey key = getApiKey(repositoryId, keyId);
        if (key == null) {
            return false;
        }

        // Delete the key from database by ID
        contentDaoService.delete(repositoryId, key.getId());

        // Invalidate cache
        invalidateCache(repositoryId);

        log.info("Revoked API key '" + key.getName() + "' (ID: " + keyId + ") for user " + key.getUserId());

        return true;
    }

    @Override
    public String validateApiKey(String repositoryId, String apiKey) {
        if (apiKey == null || !apiKey.startsWith(API_KEY_PREFIX)) {
            // SECURITY: Log invalid format attempts for audit
            if (apiKey != null) {
                log.warn("API key validation failed: invalid format (repository: " + repositoryId + ")");
            }
            return null;
        }

        // Extract the key prefix for faster lookup
        // SECURITY: Only log the prefix, never the full key
        String keyPrefix = apiKey.length() >= 11 ? apiKey.substring(0, 11) : apiKey;

        // Use prefix index for O(1) lookup instead of scanning all keys
        List<ApiKey> candidates = getKeysByPrefix(repositoryId, keyPrefix);

        if (candidates == null || candidates.isEmpty()) {
            log.warn("API key validation failed: unknown key prefix " + keyPrefix +
                " (repository: " + repositoryId + ")");
            return null;
        }

        for (ApiKey key : candidates) {
            if (!key.isActive()) {
                continue;
            }

            // Check if key has expired
            if (key.isExpired()) {
                log.warn("API key validation failed: key '" + key.getName() +
                    "' for user '" + key.getUserId() + "' has expired (repository: " + repositoryId + ")");
                continue;
            }

            // Verify the full key with BCrypt
            try {
                if (BCrypt.checkpw(apiKey, key.getKeyHash())) {
                    log.debug("API key validated for user: " + key.getUserId());
                    // Update lastUsed timestamp asynchronously (in-memory + periodic DB flush)
                    updateLastUsedAsync(repositoryId, key);
                    return key.getUserId();
                } else {
                    // SECURITY: Log hash mismatch (possible key tampering or collision)
                    log.warn("API key validation failed: hash mismatch for prefix " + keyPrefix +
                        " (repository: " + repositoryId + ")");
                }
            } catch (Exception e) {
                log.warn("Error validating API key: " + e.getMessage());
            }
        }

        return null;
    }

    @Override
    public void updateLastUsed(String repositoryId, String keyId) {
        ApiKey key = getApiKey(repositoryId, keyId);
        if (key != null) {
            updateLastUsedAsync(repositoryId, key);
        }
    }

    /**
     * Update lastUsed in-memory immediately. DB write is deferred to periodic flush.
     */
    private void updateLastUsedAsync(String repositoryId, ApiKey key) {
        GregorianCalendar now = new GregorianCalendar();
        key.setLastUsed(now);
        // Queue for periodic DB flush
        pendingLastUsedUpdates
            .computeIfAbsent(repositoryId, k -> new ConcurrentHashMap<>())
            .put(key.getId(), now);
    }

    /**
     * Flush pending lastUsed updates to the database.
     * Called periodically by the scheduler.
     */
    private void flushLastUsedUpdates() {
        for (Map.Entry<String, ConcurrentHashMap<String, GregorianCalendar>> repoEntry : pendingLastUsedUpdates.entrySet()) {
            String repositoryId = repoEntry.getKey();
            ConcurrentHashMap<String, GregorianCalendar> pending = repoEntry.getValue();

            if (pending.isEmpty()) {
                continue;
            }

            // Atomically drain pending updates
            ConcurrentHashMap<String, GregorianCalendar> toFlush = new ConcurrentHashMap<>(pending);
            pending.clear();

            for (Map.Entry<String, GregorianCalendar> entry : toFlush.entrySet()) {
                try {
                    ApiKey key = getApiKey(repositoryId, entry.getKey());
                    if (key != null) {
                        key.setLastUsed(entry.getValue());
                        contentDaoService.update(repositoryId, key);
                    }
                } catch (Exception e) {
                    log.warn("Failed to flush lastUsed for key " + entry.getKey() + ": " + e.getMessage());
                }
            }
            log.debug("Flushed " + toFlush.size() + " lastUsed updates for repository " + repositoryId);
        }
    }

    /**
     * Get API keys matching the given prefix using the secondary index.
     */
    private List<ApiKey> getKeysByPrefix(String repositoryId, String prefix) {
        ensureCacheLoaded(repositoryId);
        ConcurrentHashMap<String, List<ApiKey>> repoIndex = prefixIndex.get(repositoryId);
        if (repoIndex != null) {
            return repoIndex.get(prefix);
        }
        return null;
    }

    /**
     * Ensure the cache and prefix index are loaded for the given repository.
     */
    private void ensureCacheLoaded(String repositoryId) {
        keyCache.computeIfAbsent(repositoryId, k -> {
            ConcurrentHashMap<String, ApiKey> cache = new ConcurrentHashMap<>();
            loadApiKeysFromDatabase(repositoryId, cache);
            return cache;
        });
    }

    /**
     * Get all API keys from cache, loading from database if necessary.
     */
    private List<ApiKey> getAllApiKeysFromCache(String repositoryId) {
        ConcurrentHashMap<String, ApiKey> repoCache = keyCache.computeIfAbsent(repositoryId, k -> {
            ConcurrentHashMap<String, ApiKey> cache = new ConcurrentHashMap<>();
            loadApiKeysFromDatabase(repositoryId, cache);
            return cache;
        });

        return new ArrayList<>(repoCache.values());
    }

    /**
     * Load all API keys from the database into the cache and build prefix index.
     */
    private void loadApiKeysFromDatabase(String repositoryId, ConcurrentHashMap<String, ApiKey> cache) {
        try {
            // Query all documents of type 'apiKey'
            List<ApiKey> keys = contentDaoService.getApiKeys(repositoryId);
            ConcurrentHashMap<String, List<ApiKey>> repoIndex = new ConcurrentHashMap<>();

            if (keys != null) {
                for (ApiKey key : keys) {
                    cache.put(key.getId(), key);
                    // Build prefix index
                    if (key.getKeyPrefix() != null) {
                        repoIndex.computeIfAbsent(key.getKeyPrefix(), p -> new ArrayList<>()).add(key);
                    }
                }
            }
            prefixIndex.put(repositoryId, repoIndex);
            log.debug("Loaded " + cache.size() + " API keys for repository " + repositoryId);
        } catch (Exception e) {
            log.error("Error loading API keys from database: " + e.getMessage(), e);
        }
    }

    /**
     * Invalidate the cache for a repository.
     */
    private void invalidateCache(String repositoryId) {
        keyCache.remove(repositoryId);
        prefixIndex.remove(repositoryId);
    }

    // Setters for Spring injection

    public void setContentDaoService(ContentDaoService contentDaoService) {
        this.contentDaoService = contentDaoService;
    }
}
