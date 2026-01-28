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

/**
 * Model class representing a single item in an RSS/Atom feed.
 * 
 * Each item corresponds to a change event in the repository.
 */
public class RssFeedItem {
    
    private String id;
    private String title;
    private String description;
    private String link;
    private String author;
    private Calendar pubDate;
    private String eventType;
    private String objectId;
    private String objectName;
    private String objectType;
    private String parentId;
    private String parentPath;
    
    public RssFeedItem() {
    }
    
    public String getId() {
        return id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    public String getTitle() {
        return title;
    }
    
    public void setTitle(String title) {
        this.title = title;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    public String getLink() {
        return link;
    }
    
    public void setLink(String link) {
        this.link = link;
    }
    
    public String getAuthor() {
        return author;
    }
    
    public void setAuthor(String author) {
        this.author = author;
    }
    
    public Calendar getPubDate() {
        return pubDate;
    }
    
    public void setPubDate(Calendar pubDate) {
        this.pubDate = pubDate;
    }
    
    public String getEventType() {
        return eventType;
    }
    
    public void setEventType(String eventType) {
        this.eventType = eventType;
    }
    
    public String getObjectId() {
        return objectId;
    }
    
    public void setObjectId(String objectId) {
        this.objectId = objectId;
    }
    
    public String getObjectName() {
        return objectName;
    }
    
    public void setObjectName(String objectName) {
        this.objectName = objectName;
    }
    
    public String getObjectType() {
        return objectType;
    }
    
    public void setObjectType(String objectType) {
        this.objectType = objectType;
    }
    
    public String getParentId() {
        return parentId;
    }
    
    public void setParentId(String parentId) {
        this.parentId = parentId;
    }
    
    public String getParentPath() {
        return parentPath;
    }
    
    public void setParentPath(String parentPath) {
        this.parentPath = parentPath;
    }
    
    /**
     * Builder class for RssFeedItem.
     */
    public static class Builder {
        private final RssFeedItem item;
        
        public Builder() {
            this.item = new RssFeedItem();
        }
        
        public Builder id(String id) {
            item.setId(id);
            return this;
        }
        
        public Builder title(String title) {
            item.setTitle(title);
            return this;
        }
        
        public Builder description(String description) {
            item.setDescription(description);
            return this;
        }
        
        public Builder link(String link) {
            item.setLink(link);
            return this;
        }
        
        public Builder author(String author) {
            item.setAuthor(author);
            return this;
        }
        
        public Builder pubDate(Calendar pubDate) {
            item.setPubDate(pubDate);
            return this;
        }
        
        public Builder eventType(String eventType) {
            item.setEventType(eventType);
            return this;
        }
        
        public Builder objectId(String objectId) {
            item.setObjectId(objectId);
            return this;
        }
        
        public Builder objectName(String objectName) {
            item.setObjectName(objectName);
            return this;
        }
        
        public Builder objectType(String objectType) {
            item.setObjectType(objectType);
            return this;
        }
        
        public Builder parentId(String parentId) {
            item.setParentId(parentId);
            return this;
        }
        
        public Builder parentPath(String parentPath) {
            item.setParentPath(parentPath);
            return this;
        }
        
        public RssFeedItem build() {
            return item;
        }
    }
}
