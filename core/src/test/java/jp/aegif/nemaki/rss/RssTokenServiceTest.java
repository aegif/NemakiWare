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

public class RssTokenServiceTest {
    
    private RssTokenService tokenService;
    
    @BeforeEach
    public void setUp() {
        tokenService = new RssTokenService();
        tokenService.setDefaultExpiryDays(30);
        tokenService.setMaxExpiryDays(365);
        // A STORE, because there is no longer a process-local token cache to answer from.
        // These tests used to run the service unwired and read their answers out of that
        // cache — the very arrangement that let a token revoked on one replica keep working
        // on every other one. Validation now reads through, so the tests wire the store
        // production always has.
        tokenService.setRssTokenDaoService(new InMemoryRssTokenDao());
    }

    /** A store, not a cache: every read goes to it, which is the point of the change. */
    private static final class InMemoryRssTokenDao implements jp.aegif.nemaki.rss.RssTokenDaoService {
        private final java.util.Map<String, RssToken> byId = new java.util.LinkedHashMap<>();

        @Override
        public RssToken create(String repositoryId, RssToken token) {
            byId.put(token.getId(), token);
            return token;
        }

        @Override
        public RssToken getById(String repositoryId, String tokenId) {
            return byId.get(tokenId);
        }

        @Override
        public RssToken getByToken(String repositoryId, String tokenValue) {
            for (RssToken token : byId.values()) {
                if (token.getToken() != null && token.getToken().equals(tokenValue)) {
                    return token;
                }
            }
            return null;
        }

        @Override
        public List<RssToken> getByUserId(String repositoryId, String userId) {
            List<RssToken> found = new java.util.ArrayList<>();
            for (RssToken token : byId.values()) {
                if (userId != null && userId.equals(token.getUserId())) {
                    found.add(token);
                }
            }
            return found;
        }

        @Override
        public RssToken update(String repositoryId, RssToken token) {
            byId.put(token.getId(), token);
            return token;
        }

        @Override
        public void delete(String repositoryId, String tokenId) {
            byId.remove(tokenId);
        }

        @Override
        public int deleteExpired(String repositoryId) {
            int before = byId.size();
            byId.values().removeIf(RssToken::isExpired);
            return before - byId.size();
        }
    }
    
    @Test
    public void testGenerateToken_Basic() {
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
    public void testGenerateToken_WithCustomExpiry() {
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
    public void testGenerateToken_ExpiryExceedsMax() {
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
    public void testValidateToken_Valid() {
        RssToken generatedToken = tokenService.generateToken(
            "repo1", "user1", "Test Token", null, null, null, null);
        
        RssToken validatedToken = tokenService.validateToken("repo1", generatedToken.getToken());

        assertNotNull(validatedToken);
        assertEquals(generatedToken.getId(), validatedToken.getId());
        assertEquals(generatedToken.getToken(), validatedToken.getToken());
    }

    @Test
    public void testValidateToken_NullToken() {
        RssToken validatedToken = tokenService.validateToken("repo1", null);

        assertNull(validatedToken);
    }

    @Test
    public void testValidateToken_EmptyToken() {
        RssToken validatedToken = tokenService.validateToken("repo1", "");

        assertNull(validatedToken);
    }

    @Test
    public void testValidateToken_InvalidToken() {
        RssToken validatedToken = tokenService.validateToken("repo1", "invalid-token-value");
        
        assertNull(validatedToken);
    }
    
    @Test
    public void testDisableToken() {
        RssToken generatedToken = tokenService.generateToken(
            "repo1", "user1", "Test Token", null, null, null, null);
        
        RssToken validatedBefore = tokenService.validateToken("repo1", generatedToken.getToken());
        assertNotNull(validatedBefore);

        boolean disabled = tokenService.disableToken("repo1", generatedToken.getId());
        assertTrue(disabled);

        RssToken validatedAfter = tokenService.validateToken("repo1", generatedToken.getToken());
        assertNull(validatedAfter);
    }
    
    @Test
    public void testDisableToken_NotFound() {
        boolean disabled = tokenService.disableToken("repo1", "non-existent-token-id");
        assertFalse(disabled);
    }
    
    @Test
    public void testDeleteToken() {
        RssToken generatedToken = tokenService.generateToken(
            "repo1", "user1", "Test Token", null, null, null, null);
        
        RssToken validatedBefore = tokenService.validateToken("repo1", generatedToken.getToken());
        assertNotNull(validatedBefore);

        boolean deleted = tokenService.deleteToken("repo1", generatedToken.getId());
        assertTrue(deleted);

        RssToken validatedAfter = tokenService.validateToken("repo1", generatedToken.getToken());
        assertNull(validatedAfter);
    }
    
    @Test
    public void testDeleteToken_NotFound() {
        boolean deleted = tokenService.deleteToken("repo1", "non-existent-token-id");
        assertFalse(deleted);
    }
    
    @Test
    public void testRefreshToken() {
        RssToken generatedToken = tokenService.generateToken(
            "repo1", "user1", "Test Token", null, null, null, 7);
        
        Calendar originalExpiry = generatedToken.getExpiresAt();
        
        RssToken refreshedToken = tokenService.refreshToken("repo1", generatedToken.getId(), 30);
        
        assertNotNull(refreshedToken);
        assertTrue(refreshedToken.getExpiresAt().after(originalExpiry));
    }
    
    @Test
    public void testRefreshToken_NotFound() {
        RssToken refreshedToken = tokenService.refreshToken("repo1", "non-existent-token-id", 30);
        assertNull(refreshedToken);
    }
    
    @Test
    public void testTokenUniqueness() {
        RssToken token1 = tokenService.generateToken(
            "repo1", "user1", "Token 1", null, null, null, null);
        RssToken token2 = tokenService.generateToken(
            "repo1", "user1", "Token 2", null, null, null, null);
        
        assertNotEquals(token1.getId(), token2.getId());
        assertNotEquals(token1.getToken(), token2.getToken());
    }
    
    @Test
    public void validationReadsThroughSoARevokeElsewhereTakesEffect() {
        // The process-local cache is gone. A token revoked by another replica (modelled here
        // as a change made straight in the store, without going through this service) must
        // stop validating immediately — with the cache it kept working until this JVM
        // restarted, because nothing invalidated it across replicas.
        RssToken token = tokenService.generateToken(
            "repo1", "user1", "Token", null, null, null, null);
        assertNotNull(tokenService.validateToken("repo1", token.getToken()));

        RssToken storedElsewhere = tokenService.getTokenById("repo1", token.getId());
        storedElsewhere.setEnabled(false);

        assertNull(tokenService.validateToken("repo1", token.getToken()),
            "a token revoked outside this service still validated — the answer came from a "
            + "process-local cache that no other replica can invalidate");
    }

    @Test
    public void anUnwiredStoreRefusesInsteadOfAnsweringInvalid() {
        RssTokenService unwired = new RssTokenService();
        unwired.setDefaultExpiryDays(30);

        assertThrows(IllegalStateException.class,
            () -> unwired.validateToken("repo1", "some-token"),
            "a service that cannot look a token up answered 'invalid token'");
    }
}
