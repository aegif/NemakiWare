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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation of ApiKeyService for managing API keys.
 */
public class ApiKeyServiceImpl implements ApiKeyService {

    private static final Log log = LogFactory.getLog(ApiKeyServiceImpl.class);

    /** Prefix for API keys */
    private static final String API_KEY_PREFIX = "nw_";

    /** Length of the random part of the API key */
    private static final int API_KEY_RANDOM_LENGTH = 32;

    /** Cache of API keys by repository, keyed by keyPrefix for quick lookup */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ApiKey>> keyCache = new ConcurrentHashMap<>();

    private ContentDaoService contentDaoService;

    private SecureRandom secureRandom = new SecureRandom();

    @Override
    public ApiKeyCreationResult createApiKey(String repositoryId, String userId, String name, String description) {
        return createApiKey(repositoryId, userId, name, description, null);
    }

    @Override
    public ApiKeyCreationResult createApiKey(String repositoryId, String userId, String name, String description, GregorianCalendar expiresAt) {
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

        log.info("listApiKeys: Cache has " + allKeys.size() + " total keys for repository " + repositoryId);
        for (ApiKey key : allKeys) {
            log.info("listApiKeys: Key id=" + key.getId() + " userId=" + key.getUserId() + " active=" + key.isActive() + " name=" + key.getName());
            if (userId.equals(key.getUserId()) && key.isActive()) {
                result.add(key);
            }
        }
        log.info("listApiKeys: Returning " + result.size() + " keys for user " + userId);

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
            return null;
        }

        // Extract the key prefix for faster lookup
        String keyPrefix = apiKey.length() >= 11 ? apiKey.substring(0, 11) : apiKey;

        List<ApiKey> allKeys = getAllApiKeysFromCache(repositoryId);

        for (ApiKey key : allKeys) {
            if (!key.isActive()) {
                continue;
            }

            // Check if key has expired
            if (key.isExpired()) {
                log.debug("API key '" + key.getName() + "' has expired");
                continue;
            }

            // Quick check on prefix first
            if (!keyPrefix.equals(key.getKeyPrefix())) {
                continue;
            }

            // Verify the full key
            try {
                if (BCrypt.checkpw(apiKey, key.getKeyHash())) {
                    log.debug("API key validated for user: " + key.getUserId());
                    return key.getUserId();
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
            key.setLastUsed(new GregorianCalendar());
            contentDaoService.update(repositoryId, key);
            invalidateCache(repositoryId);
        }
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
     * Load all API keys from the database into the cache.
     */
    private void loadApiKeysFromDatabase(String repositoryId, ConcurrentHashMap<String, ApiKey> cache) {
        try {
            // Query all documents of type 'apiKey'
            List<ApiKey> keys = contentDaoService.getApiKeys(repositoryId);
            if (keys != null) {
                for (ApiKey key : keys) {
                    cache.put(key.getId(), key);
                }
            }
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
    }

    // Setters for Spring injection

    public void setContentDaoService(ContentDaoService contentDaoService) {
        this.contentDaoService = contentDaoService;
    }
}
