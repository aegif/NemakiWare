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

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RssTokenServiceTest {
    
    private RssTokenService tokenService;
    
    @BeforeEach
    void setUp() {
        tokenService = new RssTokenService();
        tokenService.setDefaultExpiryDays(30);
        tokenService.setMaxExpiryDays(365);
    }
    
    @Test
    void testGenerateToken_Basic() {
        String repositoryId = "repo1";
        String userId = "user1";
        String name = "Test Token";
        List<String> folderIds = Arrays.asList("folder1", "folder2");
        List<String> documentIds = null;
        Set<String> events = new HashSet<>(Arrays.asList("CREATED", "UPDATED"));
        Integer expiryDays = null;
        
        RssToken token = tokenService.generateToken(
            repositoryId, userId, name, folderIds, documentIds, events, expiryDays);
        
        assertNotNull(token);
        assertNotNull(token.getId());
        assertNotNull(token.getToken());
        assertEquals(repositoryId, token.getRepositoryId());
        assertEquals(userId, token.getUserId());
        assertEquals(name, token.getName());
        assertEquals(folderIds, token.getFolderIds());
        assertNull(token.getDocumentIds());
        assertEquals(events, token.getEvents());
        assertNotNull(token.getCreatedAt());
        assertNotNull(token.getExpiresAt());
        assertTrue(token.isEnabled());
        assertTrue(token.isValid());
    }
    
    @Test
    void testGenerateToken_WithCustomExpiry() {
        String repositoryId = "repo1";
        String userId = "user1";
        String name = "Test Token";
        Integer expiryDays = 7;
        
        RssToken token = tokenService.generateToken(
            repositoryId, userId, name, null, null, null, expiryDays);
        
        assertNotNull(token);
        
        Calendar expectedExpiry = Calendar.getInstance();
        expectedExpiry.add(Calendar.DAY_OF_MONTH, 7);
        
        long diffMs = Math.abs(token.getExpiresAt().getTimeInMillis() - expectedExpiry.getTimeInMillis());
        assertTrue(diffMs < 1000);
    }
    
    @Test
    void testGenerateToken_ExpiryExceedsMax() {
        String repositoryId = "repo1";
        String userId = "user1";
        String name = "Test Token";
        Integer expiryDays = 500;
        
        RssToken token = tokenService.generateToken(
            repositoryId, userId, name, null, null, null, expiryDays);
        
        assertNotNull(token);
        
        Calendar expectedExpiry = Calendar.getInstance();
        expectedExpiry.add(Calendar.DAY_OF_MONTH, 365);
        
        long diffMs = Math.abs(token.getExpiresAt().getTimeInMillis() - expectedExpiry.getTimeInMillis());
        assertTrue(diffMs < 1000);
    }
    
    @Test
    void testValidateToken_Valid() {
        RssToken generatedToken = tokenService.generateToken(
            "repo1", "user1", "Test Token", null, null, null, null);
        
        RssToken validatedToken = tokenService.validateToken(generatedToken.getToken());
        
        assertNotNull(validatedToken);
        assertEquals(generatedToken.getId(), validatedToken.getId());
        assertEquals(generatedToken.getToken(), validatedToken.getToken());
    }
    
    @Test
    void testValidateToken_NullToken() {
        RssToken validatedToken = tokenService.validateToken(null);
        
        assertNull(validatedToken);
    }
    
    @Test
    void testValidateToken_EmptyToken() {
        RssToken validatedToken = tokenService.validateToken("");
        
        assertNull(validatedToken);
    }
    
    @Test
    void testValidateToken_InvalidToken() {
        RssToken validatedToken = tokenService.validateToken("invalid-token-value");
        
        assertNull(validatedToken);
    }
    
    @Test
    void testDisableToken() {
        RssToken generatedToken = tokenService.generateToken(
            "repo1", "user1", "Test Token", null, null, null, null);
        
        RssToken validatedBefore = tokenService.validateToken(generatedToken.getToken());
        assertNotNull(validatedBefore);
        
        boolean disabled = tokenService.disableToken("repo1", generatedToken.getId());
        assertTrue(disabled);
        
        RssToken validatedAfter = tokenService.validateToken(generatedToken.getToken());
        assertNull(validatedAfter);
    }
    
    @Test
    void testDisableToken_NotFound() {
        boolean disabled = tokenService.disableToken("repo1", "non-existent-token-id");
        assertFalse(disabled);
    }
    
    @Test
    void testDeleteToken() {
        RssToken generatedToken = tokenService.generateToken(
            "repo1", "user1", "Test Token", null, null, null, null);
        
        RssToken validatedBefore = tokenService.validateToken(generatedToken.getToken());
        assertNotNull(validatedBefore);
        
        boolean deleted = tokenService.deleteToken("repo1", generatedToken.getId());
        assertTrue(deleted);
        
        RssToken validatedAfter = tokenService.validateToken(generatedToken.getToken());
        assertNull(validatedAfter);
    }
    
    @Test
    void testDeleteToken_NotFound() {
        boolean deleted = tokenService.deleteToken("repo1", "non-existent-token-id");
        assertFalse(deleted);
    }
    
    @Test
    void testRefreshToken() {
        RssToken generatedToken = tokenService.generateToken(
            "repo1", "user1", "Test Token", null, null, null, 7);
        
        Calendar originalExpiry = generatedToken.getExpiresAt();
        
        RssToken refreshedToken = tokenService.refreshToken("repo1", generatedToken.getId(), 30);
        
        assertNotNull(refreshedToken);
        assertTrue(refreshedToken.getExpiresAt().after(originalExpiry));
    }
    
    @Test
    void testRefreshToken_NotFound() {
        RssToken refreshedToken = tokenService.refreshToken("repo1", "non-existent-token-id", 30);
        assertNull(refreshedToken);
    }
    
    @Test
    void testTokenUniqueness() {
        RssToken token1 = tokenService.generateToken(
            "repo1", "user1", "Token 1", null, null, null, null);
        RssToken token2 = tokenService.generateToken(
            "repo1", "user1", "Token 2", null, null, null, null);
        
        assertNotEquals(token1.getId(), token2.getId());
        assertNotEquals(token1.getToken(), token2.getToken());
    }
    
    @Test
    void testClearExpiredTokens() {
        tokenService.clearExpiredTokens();
    }
}
