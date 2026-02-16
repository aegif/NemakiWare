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
 *     aegif - RSS token CouchDB model
 ******************************************************************************/
package jp.aegif.nemaki.model.couch;

import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import jp.aegif.nemaki.rss.RssToken;

/**
 * CouchDB model for RssToken.
 *
 * Document type: "rssToken"
 * Views: rssTokensByToken, rssTokensByUserId, rssTokensByExpiresAt
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CouchRssToken extends CouchNodeBase {

    private static final String TYPE = "rssToken";

    @JsonProperty("token")
    private String token;

    @JsonProperty("repositoryId")
    private String repositoryId;

    @JsonProperty("userId")
    private String userId;

    @JsonProperty("name")
    private String name;

    @JsonProperty("folderIds")
    private List<String> folderIds;

    @JsonProperty("documentIds")
    private List<String> documentIds;

    @JsonProperty("events")
    private List<String> events;

    @JsonProperty("createdAt")
    private Long createdAt;

    @JsonProperty("expiresAt")
    private Long expiresAt;

    @JsonProperty("enabled")
    private boolean enabled;

    public CouchRssToken() {
        setType(TYPE);
    }

    @JsonCreator
    @SuppressWarnings("unchecked")
    public CouchRssToken(Map<String, Object> properties) {
        super(properties);
        setType(TYPE);

        if (properties != null) {
            if (properties.containsKey("token")) {
                this.token = (String) properties.get("token");
            }
            if (properties.containsKey("repositoryId")) {
                this.repositoryId = (String) properties.get("repositoryId");
            }
            if (properties.containsKey("userId")) {
                this.userId = (String) properties.get("userId");
            }
            if (properties.containsKey("name")) {
                this.name = (String) properties.get("name");
            }
            if (properties.containsKey("folderIds")) {
                Object fi = properties.get("folderIds");
                if (fi instanceof List) {
                    this.folderIds = (List<String>) fi;
                }
            }
            if (properties.containsKey("documentIds")) {
                Object di = properties.get("documentIds");
                if (di instanceof List) {
                    this.documentIds = (List<String>) di;
                }
            }
            if (properties.containsKey("events")) {
                Object ev = properties.get("events");
                if (ev instanceof List) {
                    this.events = (List<String>) ev;
                }
            }
            if (properties.containsKey("createdAt")) {
                Object ca = properties.get("createdAt");
                if (ca instanceof Number) {
                    this.createdAt = ((Number) ca).longValue();
                }
            }
            if (properties.containsKey("expiresAt")) {
                Object ea = properties.get("expiresAt");
                if (ea instanceof Number) {
                    this.expiresAt = ((Number) ea).longValue();
                }
            }
            if (properties.containsKey("enabled")) {
                Object en = properties.get("enabled");
                if (en instanceof Boolean) {
                    this.enabled = (Boolean) en;
                }
            }
        }
    }

    public CouchRssToken(RssToken rssToken) {
        setType(TYPE);
        if (rssToken.getId() != null) {
            setId(rssToken.getId());
        }
        this.token = rssToken.getToken();
        this.repositoryId = rssToken.getRepositoryId();
        this.userId = rssToken.getUserId();
        this.name = rssToken.getName();
        this.folderIds = rssToken.getFolderIds();
        this.documentIds = rssToken.getDocumentIds();
        this.enabled = rssToken.isEnabled();

        if (rssToken.getEvents() != null) {
            this.events = new ArrayList<>(rssToken.getEvents());
        }
        if (rssToken.getCreatedAt() != null) {
            this.createdAt = rssToken.getCreatedAt().getTimeInMillis();
        }
        if (rssToken.getExpiresAt() != null) {
            this.expiresAt = rssToken.getExpiresAt().getTimeInMillis();
        }
    }

    public RssToken convertToRssToken() {
        RssToken rssToken = new RssToken();
        rssToken.setId(getId());
        rssToken.setToken(token);
        rssToken.setRepositoryId(repositoryId);
        rssToken.setUserId(userId);
        rssToken.setName(name);
        rssToken.setFolderIds(folderIds);
        rssToken.setDocumentIds(documentIds);
        rssToken.setEnabled(enabled);

        if (events != null) {
            rssToken.setEvents(new HashSet<>(events));
        }
        if (createdAt != null) {
            GregorianCalendar cal = new GregorianCalendar();
            cal.setTimeInMillis(createdAt);
            rssToken.setCreatedAt(cal);
        }
        if (expiresAt != null) {
            GregorianCalendar cal = new GregorianCalendar();
            cal.setTimeInMillis(expiresAt);
            rssToken.setExpiresAt(cal);
        }

        return rssToken;
    }

    // Getters and Setters

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

    public List<String> getEvents() {
        return events;
    }

    public void setEvents(List<String> events) {
        this.events = events;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Long expiresAt) {
        this.expiresAt = expiresAt;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
