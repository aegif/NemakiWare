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

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public class RssTokenTest {
    
    @Test
    public void testTokenBuilder() {
        Calendar now = Calendar.getInstance();
        Calendar expires = Calendar.getInstance();
        expires.add(Calendar.DAY_OF_MONTH, 30);
        
        List<String> folderIds = Arrays.asList("folder1", "folder2");
        List<String> documentIds = Arrays.asList("doc1", "doc2");
        Set<String> events = new HashSet<>(Arrays.asList("CREATED", "UPDATED"));
        
        RssToken token = new RssToken.Builder()
            .id("token-id-123")
            .token("secure-token-value")
            .repositoryId("repo1")
            .userId("user1")
            .name("My RSS Token")
            .folderIds(folderIds)
            .documentIds(documentIds)
            .events(events)
            .createdAt(now)
            .expiresAt(expires)
            .enabled(true)
            .build();
        
        assertEquals("token-id-123", token.getId());
        assertEquals("secure-token-value", token.getToken());
        assertEquals("repo1", token.getRepositoryId());
        assertEquals("user1", token.getUserId());
        assertEquals("My RSS Token", token.getName());
        assertEquals(folderIds, token.getFolderIds());
        assertEquals(documentIds, token.getDocumentIds());
        assertEquals(events, token.getEvents());
        assertEquals(now, token.getCreatedAt());
        assertEquals(expires, token.getExpiresAt());
        assertTrue(token.isEnabled());
    }
    
    @Test
    public void testIsExpired_NotExpired() {
        Calendar expires = Calendar.getInstance();
        expires.add(Calendar.DAY_OF_MONTH, 30);
        
        RssToken token = new RssToken.Builder()
            .expiresAt(expires)
            .build();
        
        assertFalse(token.isExpired());
    }
    
    @Test
    public void testIsExpired_Expired() {
        Calendar expires = Calendar.getInstance();
        expires.add(Calendar.DAY_OF_MONTH, -1);
        
        RssToken token = new RssToken.Builder()
            .expiresAt(expires)
            .build();
        
        assertTrue(token.isExpired());
    }
    
    @Test
    public void testIsExpired_NullExpiry() {
        RssToken token = new RssToken.Builder()
            .build();
        
        assertFalse(token.isExpired());
    }
    
    @Test
    public void testIsValid_ValidToken() {
        Calendar expires = Calendar.getInstance();
        expires.add(Calendar.DAY_OF_MONTH, 30);
        
        RssToken token = new RssToken.Builder()
            .expiresAt(expires)
            .enabled(true)
            .build();
        
        assertTrue(token.isValid());
    }
    
    @Test
    public void testIsValid_DisabledToken() {
        Calendar expires = Calendar.getInstance();
        expires.add(Calendar.DAY_OF_MONTH, 30);
        
        RssToken token = new RssToken.Builder()
            .expiresAt(expires)
            .enabled(false)
            .build();
        
        assertFalse(token.isValid());
    }
    
    @Test
    public void testIsValid_ExpiredToken() {
        Calendar expires = Calendar.getInstance();
        expires.add(Calendar.DAY_OF_MONTH, -1);
        
        RssToken token = new RssToken.Builder()
            .expiresAt(expires)
            .enabled(true)
            .build();
        
        assertFalse(token.isValid());
    }
    
    @Test
    public void testHasAccessToFolder() {
        List<String> folderIds = Arrays.asList("folder1", "folder2", "folder3");
        
        RssToken token = new RssToken.Builder()
            .folderIds(folderIds)
            .build();
        
        assertTrue(token.hasAccessToFolder("folder1"));
        assertTrue(token.hasAccessToFolder("folder2"));
        assertTrue(token.hasAccessToFolder("folder3"));
        assertFalse(token.hasAccessToFolder("folder4"));
    }
    
    @Test
    public void testHasAccessToFolder_NullFolderIds() {
        RssToken token = new RssToken.Builder()
            .build();
        
        assertFalse(token.hasAccessToFolder("folder1"));
    }
    
    @Test
    public void testHasAccessToDocument() {
        List<String> documentIds = Arrays.asList("doc1", "doc2");
        
        RssToken token = new RssToken.Builder()
            .documentIds(documentIds)
            .build();
        
        assertTrue(token.hasAccessToDocument("doc1"));
        assertTrue(token.hasAccessToDocument("doc2"));
        assertFalse(token.hasAccessToDocument("doc3"));
    }
    
    @Test
    public void testHasAccessToDocument_NullDocumentIds() {
        RssToken token = new RssToken.Builder()
            .build();
        
        assertFalse(token.hasAccessToDocument("doc1"));
    }
    
    @Test
    public void testIncludesEvent() {
        Set<String> events = new HashSet<>(Arrays.asList("CREATED", "UPDATED"));
        
        RssToken token = new RssToken.Builder()
            .events(events)
            .build();
        
        assertTrue(token.includesEvent("CREATED"));
        assertTrue(token.includesEvent("UPDATED"));
        assertFalse(token.includesEvent("DELETED"));
    }
    
    @Test
    public void testIncludesEvent_NullEvents() {
        RssToken token = new RssToken.Builder()
            .build();
        
        assertTrue(token.includesEvent("CREATED"));
        assertTrue(token.includesEvent("UPDATED"));
        assertTrue(token.includesEvent("DELETED"));
    }
    
    @Test
    public void testIncludesEvent_EmptyEvents() {
        RssToken token = new RssToken.Builder()
            .events(new HashSet<>())
            .build();
        
        assertTrue(token.includesEvent("CREATED"));
    }
}
