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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
    private static final int DEFAULT_EXPIRY_DAYS = 365;
    private static final int MAX_EXPIRY_DAYS = 365;
    
    private final SecureRandom secureRandom = new SecureRandom();
    
    // NO local token cache. It was a plain per-process map with no expiry and no
    // cross-replica invalidation: a token revoked on one replica kept working on every
    // other one until that JVM restarted. A TTL would only bound the window; reading
    // through makes the stale answer unrepresentable, and a token check is one keyed view
    // query — the right price for an access decision.
    
    private RssTokenDaoService rssTokenDaoService;

    /**
     * Read-only access to the user documents, to bind a feed token to the ACCOUNT (R170, 3.4.1).
     *
     * <p>The DAO, not {@code ContentService}: the service layer calls into the revocation side of
     * this class when a user is deleted, so depending on it here would close a cycle (the same
     * reason the API key store reads users through the DAO).
     */
    private jp.aegif.nemaki.dao.ContentDaoService contentDaoService;

    public void setContentDaoService(jp.aegif.nemaki.dao.ContentDaoService contentDaoService) {
        this.contentDaoService = contentDaoService;
    }
    
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
        
        // R170: a feed token belongs to the account DOCUMENT it was issued for, not to the user
        // NAME. Deleting a user and creating the same id again would otherwise hand the new account
        // the old holder's feed. A token that records no account cannot be validated, so refuse to
        // issue one rather than hand back a URL that never works.
        if (contentDaoService == null) {
            throw new IllegalStateException(
                    "the user store is not wired; a feed token cannot be bound to an account");
        }
        jp.aegif.nemaki.model.UserItem account = contentDaoService.getUserItemById(repositoryId, userId);
        if (account == null || account.getId() == null) {
            throw new IllegalStateException("No account document for user '" + userId
                    + "', so a feed token issued now could never be validated");
        }
        Long accountGeneration = jp.aegif.nemaki.util.AuthenticationUtil.accountGeneration(account);
        if (accountGeneration == null) {
            throw new IllegalStateException("The credential generation of user '" + userId
                    + "' cannot be read, so a feed token issued now could not be compared with it");
        }

        RssToken token = new RssToken.Builder()
            .id(tokenId)
            .accountId(account.getId())
            .credentialGeneration(Long.toString(accountGeneration))
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
        
        log.info("Generated RSS token for user: " + userId + ", name: " + name + 
                 ", expires: " + expiresAt.getTime());
        
        return token;
    }
    
    /**
     * Validate an RSS token and return the associated token object.
     * 
     * @param repositoryId The repository ID to search in (may be null for global search)
     * @param tokenValue The token value to validate
     * @return The RssToken if valid, null otherwise
     */
    public RssToken validateToken(String repositoryId, String tokenValue) {
        if (tokenValue == null || tokenValue.isEmpty()) {
            log.debug("validateToken: token is null or empty");
            return null;
        }
        
        if (rssTokenDaoService == null) {
            // An access decision must not be made by a service that cannot look the token
            // up. Answering null here would read as "invalid token".
            throw new IllegalStateException(
                    "the RSS token store is not wired; a token cannot be validated");
        }
        RssToken token = rssTokenDaoService.getByToken(repositoryId, tokenValue);
        
        if (token == null) {
            log.debug("validateToken: token not found");
            return null;
        }
        
        if (!token.isValid()) {
            log.debug("validateToken: token is invalid or expired");
            return null;
        }

        // R170: and it must still be the same account DOCUMENT. The generation is NOT compared —
        // a feed token is not derived from the password (§3 of the batch-19 design). A token whose
        // account cannot be read is refused: unknown is not "no restriction".
        if (contentDaoService == null) {
            throw new IllegalStateException(
                    "the user store is not wired; a feed token cannot be checked against its account");
        }
        jp.aegif.nemaki.util.AuthenticationUtil.CredentialBinding binding =
                jp.aegif.nemaki.util.AuthenticationUtil.credentialBinding(token.getAccountId(),
                        token.getCredentialGeneration(),
                        contentDaoService.getUserItemById(repositoryId, token.getUserId()), false);
        if (binding != jp.aegif.nemaki.util.AuthenticationUtil.CredentialBinding.OK) {
            log.info("validateToken: refused — " + binding);
            return null;
        }

        return token;
    }
    
    /**
     * Revokes every feed token of a user and answers how many (R170, batch 19).
     *
     * <p>Housekeeping, not the boundary: what stops the token of a deleted account is the
     * comparison in {@link #validateToken}. This keeps rows nobody can use out of the store, and
     * gives the delete a number to log. Looked up by the user NAME, because a token issued before
     * 3.4.1 records no accountId and looking up by that would leave exactly those behind.
     */
    public int revokeTokensOf(String repositoryId, String userId) {
        if (userId == null || rssTokenDaoService == null) {
            return 0;
        }
        List<RssToken> tokens = rssTokenDaoService.getByUserId(repositoryId, userId);
        if (tokens == null) {
            return 0;
        }
        int revoked = 0;
        for (RssToken token : tokens) {
            rssTokenDaoService.delete(repositoryId, token.getId());
            revoked++;
        }
        return revoked;
    }

    /**
     * Get all tokens for a user.
     * 
     * @param repositoryId The repository ID
     * @param userId The user ID
     * @return List of tokens for the user
     */
    public List<RssToken> getTokensForUser(String repositoryId, String userId) {
        if (rssTokenDaoService == null) {
            // The administrator revokes from this list; a memory-only subset of it is a
            // shorter answer presented as the whole.
            throw new IllegalStateException(
                    "the RSS token store is not wired; the user's tokens cannot be listed");
        }
        return rssTokenDaoService.getByUserId(repositoryId, userId);
    }
    
    /**
     * Get a token by its ID.
     * 
     * @param repositoryId The repository ID
     * @param tokenId The token ID
     * @return The RssToken if found, null otherwise
     */
    public RssToken getTokenById(String repositoryId, String tokenId) {
        if (rssTokenDaoService == null) {
            throw new IllegalStateException(
                    "the RSS token store is not wired; the token cannot be looked up");
        }
        return rssTokenDaoService.getById(repositoryId, tokenId);
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

    
}
