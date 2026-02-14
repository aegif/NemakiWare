/*******************************************************************************
 * Copyright (c) 2024 aegif.
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
 ******************************************************************************/
package jp.aegif.nemaki.model.couch;

import java.util.GregorianCalendar;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import jp.aegif.nemaki.model.ApiKey;

/**
 * CouchDB wrapper class for ApiKey model.
 * Handles JSON serialization/deserialization for CouchDB storage.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CouchApiKey extends CouchNodeBase {

    private static final long serialVersionUID = 1L;

    @JsonProperty("userId")
    private String userId;

    @JsonProperty("repositoryId")
    private String repositoryId;

    @JsonProperty("keyHash")
    private String keyHash;

    @JsonProperty("keyPrefix")
    private String keyPrefix;

    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    private String description;

    @JsonProperty("lastUsed")
    private GregorianCalendar lastUsed;

    @JsonProperty("expiresAt")
    private GregorianCalendar expiresAt;

    @JsonProperty("active")
    private boolean active;

    public CouchApiKey() {
        super();
    }

    /**
     * Map-based constructor for Cloudant SDK Document conversion.
     */
    @JsonCreator
    public CouchApiKey(Map<String, Object> properties) {
        super(properties);
        if (properties != null) {
            this.userId = (String) properties.get("userId");
            this.repositoryId = (String) properties.get("repositoryId");
            this.keyHash = (String) properties.get("keyHash");
            this.keyPrefix = (String) properties.get("keyPrefix");
            this.name = (String) properties.get("name");
            this.description = (String) properties.get("description");

            // Handle lastUsed date field
            if (properties.containsKey("lastUsed")) {
                this.lastUsed = parseDateTime(properties.get("lastUsed"));
            }

            // Handle expiresAt date field
            if (properties.containsKey("expiresAt")) {
                this.expiresAt = parseDateTime(properties.get("expiresAt"));
            }

            // Handle active boolean field
            Object activeValue = properties.get("active");
            if (activeValue instanceof Boolean) {
                this.active = (Boolean) activeValue;
            } else if (activeValue != null) {
                this.active = Boolean.parseBoolean(activeValue.toString());
            }
        }
    }

    /**
     * Constructor from ApiKey domain model.
     */
    public CouchApiKey(ApiKey apiKey) {
        super(apiKey);
        setUserId(apiKey.getUserId());
        setRepositoryId(apiKey.getRepositoryId());
        setKeyHash(apiKey.getKeyHash());
        setKeyPrefix(apiKey.getKeyPrefix());
        setName(apiKey.getName());
        setDescription(apiKey.getDescription());
        setLastUsed(apiKey.getLastUsed());
        setExpiresAt(apiKey.getExpiresAt());
        setActive(apiKey.isActive());
    }

    /**
     * Convert to ApiKey domain model.
     */
    public ApiKey convert() {
        ApiKey apiKey = new ApiKey();

        // Copy base fields
        apiKey.setId(getId());
        apiKey.setType(getType());
        apiKey.setCreated(getCreated());
        apiKey.setCreator(getCreator());
        apiKey.setModified(getModified());
        apiKey.setModifier(getModifier());
        apiKey.setRevision(getRevision());

        // Copy ApiKey-specific fields
        apiKey.setUserId(getUserId());
        apiKey.setRepositoryId(getRepositoryId());
        apiKey.setKeyHash(getKeyHash());
        apiKey.setKeyPrefix(getKeyPrefix());
        apiKey.setName(getName());
        apiKey.setDescription(getDescription());
        apiKey.setLastUsed(getLastUsed());
        apiKey.setExpiresAt(getExpiresAt());
        apiKey.setActive(isActive());

        return apiKey;
    }

    // Getters and setters

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public void setKeyHash(String keyHash) {
        this.keyHash = keyHash;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public GregorianCalendar getLastUsed() {
        return lastUsed;
    }

    public void setLastUsed(GregorianCalendar lastUsed) {
        this.lastUsed = lastUsed;
    }

    public GregorianCalendar getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(GregorianCalendar expiresAt) {
        this.expiresAt = expiresAt;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
