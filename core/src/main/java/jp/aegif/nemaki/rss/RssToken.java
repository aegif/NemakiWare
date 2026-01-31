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

import java.util.Calendar;
import java.util.List;
import java.util.Set;

/**
 * Model class representing an RSS feed access token.
 * 
 * RSS tokens provide authenticated access to RSS feeds without requiring
 * session-based authentication, making them suitable for RSS readers.
 */
public class RssToken {
    
    private String id;
    private String token;
    private String repositoryId;
    private String userId;
    private String name;
    private List<String> folderIds;
    private List<String> documentIds;
    private Set<String> events;
    private Calendar createdAt;
    private Calendar expiresAt;
    private boolean enabled;
    
    public RssToken() {
        this.enabled = true;
    }
    
    public String getId() {
        return id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    public String getToken() {
        return token;
    }
    
    public void setToken(String token) {
        this.token = token;
    }
    
    public String getRepositoryId() {
        return repositoryId;
    }
    
    public void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
    }
    
    public String getUserId() {
        return userId;
    }
    
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public List<String> getFolderIds() {
        return folderIds;
    }
    
    public void setFolderIds(List<String> folderIds) {
        this.folderIds = folderIds;
    }
    
    public List<String> getDocumentIds() {
        return documentIds;
    }
    
    public void setDocumentIds(List<String> documentIds) {
        this.documentIds = documentIds;
    }
    
    public Set<String> getEvents() {
        return events;
    }
    
    public void setEvents(Set<String> events) {
        this.events = events;
    }
    
    public Calendar getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(Calendar createdAt) {
        this.createdAt = createdAt;
    }
    
    public Calendar getExpiresAt() {
        return expiresAt;
    }
    
    public void setExpiresAt(Calendar expiresAt) {
        this.expiresAt = expiresAt;
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    /**
     * Check if this token has expired.
     * 
     * @return true if the token has expired, false otherwise
     */
    public boolean isExpired() {
        if (expiresAt == null) {
            return false;
        }
        return Calendar.getInstance().after(expiresAt);
    }
    
    /**
     * Check if this token is valid (enabled and not expired).
     * 
     * @return true if the token is valid, false otherwise
     */
    public boolean isValid() {
        return enabled && !isExpired();
    }
    
    /**
     * Check if this token grants access to the specified folder.
     * 
     * @param folderId The folder ID to check
     * @return true if access is granted, false otherwise
     */
    public boolean hasAccessToFolder(String folderId) {
        if (folderIds == null || folderIds.isEmpty()) {
            return false;
        }
        return folderIds.contains(folderId);
    }
    
    /**
     * Check if this token grants access to the specified document.
     * 
     * @param documentId The document ID to check
     * @return true if access is granted, false otherwise
     */
    public boolean hasAccessToDocument(String documentId) {
        if (documentIds == null || documentIds.isEmpty()) {
            return false;
        }
        return documentIds.contains(documentId);
    }
    
    /**
     * Check if this token includes the specified event type.
     * 
     * @param eventType The event type to check
     * @return true if the event type is included, false otherwise
     */
    public boolean includesEvent(String eventType) {
        if (events == null || events.isEmpty()) {
            return true;
        }
        return events.contains(eventType);
    }
    
    /**
     * Builder class for RssToken.
     */
    public static class Builder {
        private final RssToken token;
        
        public Builder() {
            this.token = new RssToken();
        }
        
        public Builder id(String id) {
            token.setId(id);
            return this;
        }
        
        public Builder token(String tokenValue) {
            token.setToken(tokenValue);
            return this;
        }
        
        public Builder repositoryId(String repositoryId) {
            token.setRepositoryId(repositoryId);
            return this;
        }
        
        public Builder userId(String userId) {
            token.setUserId(userId);
            return this;
        }
        
        public Builder name(String name) {
            token.setName(name);
            return this;
        }
        
        public Builder folderIds(List<String> folderIds) {
            token.setFolderIds(folderIds);
            return this;
        }
        
        public Builder documentIds(List<String> documentIds) {
            token.setDocumentIds(documentIds);
            return this;
        }
        
        public Builder events(Set<String> events) {
            token.setEvents(events);
            return this;
        }
        
        public Builder createdAt(Calendar createdAt) {
            token.setCreatedAt(createdAt);
            return this;
        }
        
        public Builder expiresAt(Calendar expiresAt) {
            token.setExpiresAt(expiresAt);
            return this;
        }
        
        public Builder enabled(boolean enabled) {
            token.setEnabled(enabled);
            return this;
        }
        
        public RssToken build() {
            return token;
        }
    }
}
