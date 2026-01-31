/*******************************************************************************
 * Copyright (c) 2013 aegif.
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
 *
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.rss;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Service for managing RSS feed access tokens.
 * 
 * This service handles token generation, validation, and lifecycle management
 * for RSS feed authentication.
 */
public class RssTokenService {
    
    private static final Log log = LogFactory.getLog(RssTokenService.class);
    
    private static final int TOKEN_LENGTH = 32;
    private static final int DEFAULT_EXPIRY_DAYS = 30;
    private static final int MAX_EXPIRY_DAYS = 365;
    
    private final SecureRandom secureRandom = new SecureRandom();
    
    private final Map<String, RssToken> tokenCache = new ConcurrentHashMap<>();
    
    private RssTokenDaoService rssTokenDaoService;
    
    private int defaultExpiryDays = DEFAULT_EXPIRY_DAYS;
    private int maxExpiryDays = MAX_EXPIRY_DAYS;
    
    public RssTokenService() {
        log.debug("RssTokenService initialized");
    }
    
    public void setRssTokenDaoService(RssTokenDaoService rssTokenDaoService) {
        this.rssTokenDaoService = rssTokenDaoService;
    }
    
    public void setDefaultExpiryDays(int defaultExpiryDays) {
        this.defaultExpiryDays = defaultExpiryDays;
    }
    
    public void setMaxExpiryDays(int maxExpiryDays) {
        this.maxExpiryDays = maxExpiryDays;
    }
    
    /**
     * Generate a new RSS token for a user.
     * 
     * @param repositoryId The repository ID
     * @param userId The user ID
     * @param name A descriptive name for the token
     * @param folderIds List of folder IDs to grant access to
     * @param documentIds List of document IDs to grant access to
     * @param events Set of event types to include in the feed
     * @param expiryDays Number of days until the token expires (null for default)
     * @return The generated RssToken
     */
    public RssToken generateToken(String repositoryId, String userId, String name,
                                   List<String> folderIds, List<String> documentIds,
                                   Set<String> events, Integer expiryDays) {
        
        String tokenId = UUID.randomUUID().toString();
        String tokenValue = generateSecureToken();
        
        int days = expiryDays != null ? Math.min(expiryDays, maxExpiryDays) : defaultExpiryDays;
        
        Calendar createdAt = Calendar.getInstance();
        Calendar expiresAt = Calendar.getInstance();
        expiresAt.add(Calendar.DAY_OF_MONTH, days);
        
        RssToken token = new RssToken.Builder()
            .id(tokenId)
            .token(tokenValue)
            .repositoryId(repositoryId)
            .userId(userId)
            .name(name)
            .folderIds(folderIds)
            .documentIds(documentIds)
            .events(events)
            .createdAt(createdAt)
            .expiresAt(expiresAt)
            .enabled(true)
            .build();
        
        if (rssTokenDaoService != null) {
            rssTokenDaoService.create(repositoryId, token);
        }
        
        tokenCache.put(tokenValue, token);
        
        log.info("Generated RSS token for user: " + userId + ", name: " + name + 
                 ", expires: " + expiresAt.getTime());
        
        return token;
    }
    
    /**
     * Validate an RSS token and return the associated token object.
     * 
     * @param tokenValue The token value to validate
     * @return The RssToken if valid, null otherwise
     */
    public RssToken validateToken(String tokenValue) {
        if (tokenValue == null || tokenValue.isEmpty()) {
            log.debug("validateToken: token is null or empty");
            return null;
        }
        
        RssToken token = tokenCache.get(tokenValue);
        
        if (token == null && rssTokenDaoService != null) {
            token = rssTokenDaoService.getByToken(tokenValue);
            if (token != null) {
                tokenCache.put(tokenValue, token);
            }
        }
        
        if (token == null) {
            log.debug("validateToken: token not found");
            return null;
        }
        
        if (!token.isValid()) {
            log.debug("validateToken: token is invalid or expired");
            tokenCache.remove(tokenValue);
            return null;
        }
        
        return token;
    }
    
    /**
     * Get all tokens for a user.
     * 
     * @param repositoryId The repository ID
     * @param userId The user ID
     * @return List of tokens for the user
     */
    public List<RssToken> getTokensForUser(String repositoryId, String userId) {
        if (rssTokenDaoService != null) {
            return rssTokenDaoService.getByUserId(repositoryId, userId);
        }
        return List.of();
    }
    
    /**
     * Get a token by its ID.
     * 
     * @param repositoryId The repository ID
     * @param tokenId The token ID
     * @return The RssToken if found, null otherwise
     */
    public RssToken getTokenById(String repositoryId, String tokenId) {
        if (rssTokenDaoService != null) {
            return rssTokenDaoService.getById(repositoryId, tokenId);
        }
        // Fallback to in-memory cache lookup by ID
        for (RssToken token : tokenCache.values()) {
            if (tokenId.equals(token.getId())) {
                return token;
            }
        }
        return null;
    }
    
    /**
     * Disable a token.
     * 
     * @param repositoryId The repository ID
     * @param tokenId The token ID
     * @return true if the token was disabled, false otherwise
     */
    public boolean disableToken(String repositoryId, String tokenId) {
        RssToken token = getTokenById(repositoryId, tokenId);
        if (token == null) {
            return false;
        }
        
        token.setEnabled(false);
        
        if (rssTokenDaoService != null) {
            rssTokenDaoService.update(repositoryId, token);
        }
        
        tokenCache.remove(token.getToken());
        
        log.info("Disabled RSS token: " + tokenId);
        return true;
    }
    
    /**
     * Delete a token.
     * 
     * @param repositoryId The repository ID
     * @param tokenId The token ID
     * @return true if the token was deleted, false otherwise
     */
    public boolean deleteToken(String repositoryId, String tokenId) {
        RssToken token = getTokenById(repositoryId, tokenId);
        if (token == null) {
            return false;
        }
        
        if (rssTokenDaoService != null) {
            rssTokenDaoService.delete(repositoryId, tokenId);
        }
        
        tokenCache.remove(token.getToken());
        
        log.info("Deleted RSS token: " + tokenId);
        return true;
    }
    
    /**
     * Refresh a token's expiry date.
     * 
     * @param repositoryId The repository ID
     * @param tokenId The token ID
     * @param expiryDays Number of days until the token expires (null for default)
     * @return The updated RssToken if found, null otherwise
     */
    public RssToken refreshToken(String repositoryId, String tokenId, Integer expiryDays) {
        RssToken token = getTokenById(repositoryId, tokenId);
        if (token == null) {
            return null;
        }
        
        int days = expiryDays != null ? Math.min(expiryDays, maxExpiryDays) : defaultExpiryDays;
        
        Calendar newExpiresAt = Calendar.getInstance();
        newExpiresAt.add(Calendar.DAY_OF_MONTH, days);
        token.setExpiresAt(newExpiresAt);
        
        if (rssTokenDaoService != null) {
            rssTokenDaoService.update(repositoryId, token);
        }
        
        tokenCache.put(token.getToken(), token);
        
        log.info("Refreshed RSS token: " + tokenId + ", new expiry: " + newExpiresAt.getTime());
        return token;
    }
    
    /**
     * Generate a cryptographically secure token string.
     * 
     * @return A secure random token string
     */
    private String generateSecureToken() {
        byte[] bytes = new byte[TOKEN_LENGTH];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    
    /**
     * Clear expired tokens from the cache.
     * This should be called periodically to prevent memory leaks.
     */
    public void clearExpiredTokens() {
        int removed = 0;
        for (Map.Entry<String, RssToken> entry : tokenCache.entrySet()) {
            if (entry.getValue().isExpired()) {
                tokenCache.remove(entry.getKey());
                removed++;
            }
        }
        if (removed > 0) {
            log.info("Cleared " + removed + " expired RSS tokens from cache");
        }
    }
}
