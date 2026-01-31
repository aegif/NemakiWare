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

import java.util.List;

/**
 * DAO service interface for RSS token persistence.
 */
public interface RssTokenDaoService {
    
    /**
     * Create a new RSS token.
     * 
     * @param repositoryId The repository ID
     * @param token The token to create
     * @return The created token with ID
     */
    RssToken create(String repositoryId, RssToken token);
    
    /**
     * Get a token by its ID.
     * 
     * @param repositoryId The repository ID
     * @param tokenId The token ID
     * @return The token if found, null otherwise
     */
    RssToken getById(String repositoryId, String tokenId);
    
    /**
     * Get a token by its token value.
     * 
     * @param tokenValue The token value
     * @return The token if found, null otherwise
     */
    RssToken getByToken(String tokenValue);
    
    /**
     * Get all tokens for a user.
     * 
     * @param repositoryId The repository ID
     * @param userId The user ID
     * @return List of tokens for the user
     */
    List<RssToken> getByUserId(String repositoryId, String userId);
    
    /**
     * Update an existing token.
     * 
     * @param repositoryId The repository ID
     * @param token The token to update
     * @return The updated token
     */
    RssToken update(String repositoryId, RssToken token);
    
    /**
     * Delete a token.
     * 
     * @param repositoryId The repository ID
     * @param tokenId The token ID
     */
    void delete(String repositoryId, String tokenId);
    
    /**
     * Delete all expired tokens.
     * 
     * @param repositoryId The repository ID
     * @return Number of tokens deleted
     */
    int deleteExpired(String repositoryId);
}
