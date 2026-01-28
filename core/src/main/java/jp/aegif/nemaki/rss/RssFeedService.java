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

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.chemistry.opencmis.commons.enums.ChangeType;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Change;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;

/**
 * Service for generating RSS/Atom feeds from repository changes.
 * 
 * This service retrieves change events from the repository and converts them
 * into RSS feed items for consumption by RSS readers.
 */
public class RssFeedService {
    
    private static final Log log = LogFactory.getLog(RssFeedService.class);
    
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_MAX_DEPTH = 5;
    
    private ContentService contentService;
    private ContentDaoService contentDaoService;
    private RssFeedGenerator feedGenerator;
    private String baseUrl;
    
    private int defaultLimit = DEFAULT_LIMIT;
    private int maxLimit = MAX_LIMIT;
    private int defaultMaxDepth = DEFAULT_MAX_DEPTH;
    
    public RssFeedService() {
        this.feedGenerator = new RssFeedGenerator();
        log.debug("RssFeedService initialized");
    }
    
    public void setContentService(ContentService contentService) {
        this.contentService = contentService;
    }
    
    public void setContentDaoService(ContentDaoService contentDaoService) {
        this.contentDaoService = contentDaoService;
    }
    
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
        if (feedGenerator != null) {
            feedGenerator.setBaseUrl(baseUrl);
        }
    }
    
    public void setDefaultLimit(int defaultLimit) {
        this.defaultLimit = defaultLimit;
    }
    
    public void setMaxLimit(int maxLimit) {
        this.maxLimit = maxLimit;
    }
    
    public void setDefaultMaxDepth(int defaultMaxDepth) {
        this.defaultMaxDepth = defaultMaxDepth;
    }
    
    /**
     * Generate an RSS 2.0 feed for a folder.
     * 
     * @param repositoryId The repository ID
     * @param folderId The folder ID
     * @param includeChildren Whether to include changes in child folders
     * @param maxDepth Maximum depth for child folder traversal
     * @param limit Maximum number of items to include
     * @param events Set of event types to include (null for all)
     * @param token The RSS token for authentication
     * @return RSS 2.0 XML string
     */
    public String generateFolderRssFeed(String repositoryId, String folderId,
                                         boolean includeChildren, Integer maxDepth,
                                         Integer limit, Set<String> events, RssToken token) {
        
        List<RssFeedItem> items = getFolderFeedItems(repositoryId, folderId, 
            includeChildren, maxDepth, limit, events, token);
        
        Content folder = contentService != null ? contentService.getContent(repositoryId, folderId) : null;
        String folderName = folder != null ? folder.getName() : folderId;
        
        String title = "NemakiWare - " + folderName;
        String description = "Changes in folder: " + folderName;
        String link = buildFolderLink(repositoryId, folderId);
        
        return feedGenerator.generateRss(title, description, link, items);
    }
    
    /**
     * Generate an Atom 1.0 feed for a folder.
     * 
     * @param repositoryId The repository ID
     * @param folderId The folder ID
     * @param includeChildren Whether to include changes in child folders
     * @param maxDepth Maximum depth for child folder traversal
     * @param limit Maximum number of items to include
     * @param events Set of event types to include (null for all)
     * @param token The RSS token for authentication
     * @return Atom 1.0 XML string
     */
    public String generateFolderAtomFeed(String repositoryId, String folderId,
                                          boolean includeChildren, Integer maxDepth,
                                          Integer limit, Set<String> events, RssToken token) {
        
        List<RssFeedItem> items = getFolderFeedItems(repositoryId, folderId,
            includeChildren, maxDepth, limit, events, token);
        
        Content folder = contentService != null ? contentService.getContent(repositoryId, folderId) : null;
        String folderName = folder != null ? folder.getName() : folderId;
        
        String title = "NemakiWare - " + folderName;
        String subtitle = "Changes in folder: " + folderName;
        String feedId = buildFolderFeedId(repositoryId, folderId);
        
        return feedGenerator.generateAtom(title, subtitle, feedId, items);
    }
    
    /**
     * Generate an RSS 2.0 feed for a document.
     * 
     * @param repositoryId The repository ID
     * @param documentId The document ID
     * @param limit Maximum number of items to include
     * @param events Set of event types to include (null for all)
     * @param token The RSS token for authentication
     * @return RSS 2.0 XML string
     */
    public String generateDocumentRssFeed(String repositoryId, String documentId,
                                           Integer limit, Set<String> events, RssToken token) {
        
        List<RssFeedItem> items = getDocumentFeedItems(repositoryId, documentId, limit, events, token);
        
        Content document = contentService != null ? contentService.getContent(repositoryId, documentId) : null;
        String documentName = document != null ? document.getName() : documentId;
        
        String title = "NemakiWare - " + documentName;
        String description = "Changes to document: " + documentName;
        String link = buildDocumentLink(repositoryId, documentId);
        
        return feedGenerator.generateRss(title, description, link, items);
    }
    
    /**
     * Generate an Atom 1.0 feed for a document.
     * 
     * @param repositoryId The repository ID
     * @param documentId The document ID
     * @param limit Maximum number of items to include
     * @param events Set of event types to include (null for all)
     * @param token The RSS token for authentication
     * @return Atom 1.0 XML string
     */
    public String generateDocumentAtomFeed(String repositoryId, String documentId,
                                            Integer limit, Set<String> events, RssToken token) {
        
        List<RssFeedItem> items = getDocumentFeedItems(repositoryId, documentId, limit, events, token);
        
        Content document = contentService != null ? contentService.getContent(repositoryId, documentId) : null;
        String documentName = document != null ? document.getName() : documentId;
        
        String title = "NemakiWare - " + documentName;
        String subtitle = "Changes to document: " + documentName;
        String feedId = buildDocumentFeedId(repositoryId, documentId);
        
        return feedGenerator.generateAtom(title, subtitle, feedId, items);
    }
    
    /**
     * Get feed items for a folder.
     */
    private List<RssFeedItem> getFolderFeedItems(String repositoryId, String folderId,
                                                   boolean includeChildren, Integer maxDepth,
                                                   Integer limit, Set<String> events, RssToken token) {
        
        int effectiveLimit = limit != null ? Math.min(limit, maxLimit) : defaultLimit;
        int effectiveMaxDepth = maxDepth != null ? maxDepth : defaultMaxDepth;
        
        Set<String> effectiveEvents = events;
        if (effectiveEvents == null && token != null && token.getEvents() != null) {
            effectiveEvents = token.getEvents();
        }
        
        List<Change> changes = getChangesForFolder(repositoryId, folderId, 
            includeChildren, effectiveMaxDepth, effectiveLimit, effectiveEvents);
        
        return convertChangesToFeedItems(repositoryId, changes);
    }
    
    /**
     * Get feed items for a document.
     */
    private List<RssFeedItem> getDocumentFeedItems(String repositoryId, String documentId,
                                                     Integer limit, Set<String> events, RssToken token) {
        
        int effectiveLimit = limit != null ? Math.min(limit, maxLimit) : defaultLimit;
        
        Set<String> effectiveEvents = events;
        if (effectiveEvents == null && token != null && token.getEvents() != null) {
            effectiveEvents = token.getEvents();
        }
        
        List<Change> changes = getChangesForDocument(repositoryId, documentId, effectiveLimit, effectiveEvents);
        
        return convertChangesToFeedItems(repositoryId, changes);
    }
    
    /**
     * Get changes for a folder from the change log.
     */
    private List<Change> getChangesForFolder(String repositoryId, String folderId,
                                               boolean includeChildren, int maxDepth,
                                               int limit, Set<String> events) {
        
        if (contentDaoService == null) {
            log.warn("ContentDaoService not available, returning empty changes");
            return new ArrayList<>();
        }
        
        Set<String> folderIds = new HashSet<>();
        folderIds.add(folderId);
        
        if (includeChildren) {
            collectChildFolderIds(repositoryId, folderId, folderIds, maxDepth, 0);
        }
        
        List<Change> allChanges = contentDaoService.getLatestChanges(repositoryId, null, limit * 2);
        
        List<Change> filteredChanges = new ArrayList<>();
        for (Change change : allChanges) {
            if (filteredChanges.size() >= limit) {
                break;
            }
            
            String objectId = change.getObjectId();
            Content content = contentService != null ? contentService.getContent(repositoryId, objectId) : null;
            
            if (content != null) {
                String parentId = content.getParentId();
                if (folderIds.contains(objectId) || folderIds.contains(parentId)) {
                    if (matchesEventFilter(change, events)) {
                        filteredChanges.add(change);
                    }
                }
            }
        }
        
        return filteredChanges;
    }
    
    /**
     * Get changes for a document from the change log.
     */
    private List<Change> getChangesForDocument(String repositoryId, String documentId,
                                                 int limit, Set<String> events) {
        
        if (contentDaoService == null) {
            log.warn("ContentDaoService not available, returning empty changes");
            return new ArrayList<>();
        }
        
        List<Change> allChanges = contentDaoService.getLatestChanges(repositoryId, null, limit * 2);
        
        List<Change> filteredChanges = new ArrayList<>();
        for (Change change : allChanges) {
            if (filteredChanges.size() >= limit) {
                break;
            }
            
            if (documentId.equals(change.getObjectId())) {
                if (matchesEventFilter(change, events)) {
                    filteredChanges.add(change);
                }
            }
        }
        
        return filteredChanges;
    }
    
    /**
     * Recursively collect child folder IDs up to maxDepth.
     */
    private void collectChildFolderIds(String repositoryId, String folderId,
                                         Set<String> folderIds, int maxDepth, int currentDepth) {
        
        if (currentDepth >= maxDepth) {
            return;
        }
        
        if (contentService == null) {
            return;
        }
        
        Content folder = contentService.getContent(repositoryId, folderId);
        if (!(folder instanceof Folder)) {
            return;
        }
        
        List<Content> children = contentService.getChildren(repositoryId, folderId);
        if (children == null) {
            return;
        }
        
        for (Content child : children) {
            if (child instanceof Folder) {
                folderIds.add(child.getId());
                collectChildFolderIds(repositoryId, child.getId(), folderIds, maxDepth, currentDepth + 1);
            }
        }
    }
    
    /**
     * Check if a change matches the event filter.
     */
    private boolean matchesEventFilter(Change change, Set<String> events) {
        if (events == null || events.isEmpty()) {
            return true;
        }
        
        String eventType = convertChangeTypeToEventType(change.getChangeType());
        return events.contains(eventType);
    }
    
    /**
     * Convert Change objects to RssFeedItem objects.
     */
    private List<RssFeedItem> convertChangesToFeedItems(String repositoryId, List<Change> changes) {
        List<RssFeedItem> items = new ArrayList<>();
        
        for (Change change : changes) {
            RssFeedItem item = convertChangeToFeedItem(repositoryId, change);
            if (item != null) {
                items.add(item);
            }
        }
        
        return items;
    }
    
    /**
     * Convert a single Change to an RssFeedItem.
     */
    private RssFeedItem convertChangeToFeedItem(String repositoryId, Change change) {
        String objectId = change.getObjectId();
        String eventType = convertChangeTypeToEventType(change.getChangeType());
        
        Content content = null;
        if (contentService != null) {
            content = contentService.getContent(repositoryId, objectId);
        }
        
        String objectName = content != null ? content.getName() : objectId;
        String objectType = content != null ? content.getObjectType() : "unknown";
        String parentId = content != null ? content.getParentId() : null;
        
        String title = buildItemTitle(eventType, objectName);
        String description = buildItemDescription(eventType, objectName, objectType);
        String link = buildObjectLink(repositoryId, objectId);
        
        Calendar pubDate = change.getTime();
        String author = change.getCreator();
        
        return new RssFeedItem.Builder()
            .id(change.getId())
            .title(title)
            .description(description)
            .link(link)
            .author(author)
            .pubDate(pubDate)
            .eventType(eventType)
            .objectId(objectId)
            .objectName(objectName)
            .objectType(objectType)
            .parentId(parentId)
            .build();
    }
    
    /**
     * Convert CMIS ChangeType to event type string.
     */
    private String convertChangeTypeToEventType(ChangeType changeType) {
        if (changeType == null) {
            return "UNKNOWN";
        }
        
        switch (changeType) {
            case CREATED:
                return "CREATED";
            case UPDATED:
                return "UPDATED";
            case DELETED:
                return "DELETED";
            case SECURITY:
                return "SECURITY";
            default:
                return "UNKNOWN";
        }
    }
    
    /**
     * Build item title based on event type and object name.
     */
    private String buildItemTitle(String eventType, String objectName) {
        switch (eventType) {
            case "CREATED":
                return "Created: " + objectName;
            case "UPDATED":
                return "Updated: " + objectName;
            case "DELETED":
                return "Deleted: " + objectName;
            case "SECURITY":
                return "Security changed: " + objectName;
            default:
                return eventType + ": " + objectName;
        }
    }
    
    /**
     * Build item description based on event type, object name, and type.
     */
    private String buildItemDescription(String eventType, String objectName, String objectType) {
        String typeLabel = objectType.contains("folder") ? "Folder" : "Document";
        
        switch (eventType) {
            case "CREATED":
                return typeLabel + " '" + objectName + "' was created.";
            case "UPDATED":
                return typeLabel + " '" + objectName + "' was updated.";
            case "DELETED":
                return typeLabel + " '" + objectName + "' was deleted.";
            case "SECURITY":
                return "Security settings for " + typeLabel.toLowerCase() + " '" + objectName + "' were changed.";
            default:
                return typeLabel + " '" + objectName + "' - " + eventType;
        }
    }
    
    /**
     * Build link to folder.
     */
    private String buildFolderLink(String repositoryId, String folderId) {
        if (baseUrl == null) {
            return "#";
        }
        return baseUrl + "/ui/#/repository/" + repositoryId + "/folder/" + folderId;
    }
    
    /**
     * Build link to document.
     */
    private String buildDocumentLink(String repositoryId, String documentId) {
        if (baseUrl == null) {
            return "#";
        }
        return baseUrl + "/ui/#/repository/" + repositoryId + "/document/" + documentId;
    }
    
    /**
     * Build link to object (folder or document).
     */
    private String buildObjectLink(String repositoryId, String objectId) {
        if (baseUrl == null) {
            return "#";
        }
        return baseUrl + "/ui/#/repository/" + repositoryId + "/object/" + objectId;
    }
    
    /**
     * Build feed ID for folder.
     */
    private String buildFolderFeedId(String repositoryId, String folderId) {
        return "urn:nemakiware:feed:folder:" + repositoryId + ":" + folderId;
    }
    
    /**
     * Build feed ID for document.
     */
    private String buildDocumentFeedId(String repositoryId, String documentId) {
        return "urn:nemakiware:feed:document:" + repositoryId + ":" + documentId;
    }
}
