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
package jp.aegif.nemaki.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jp.aegif.nemaki.util.constant.NodeType;

import java.util.GregorianCalendar;

/**
 * API Key model for persistent API authentication.
 *
 * API keys provide a way for users to authenticate without passwords,
 * especially useful for:
 * - MCP (Model Context Protocol) clients
 * - Cloud-only users who cannot use password authentication
 * - CI/CD pipelines and automated tools
 *
 * The actual key value is only returned once upon creation and is stored
 * as a BCrypt hash in the database for security.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiKey extends NodeBase {

    private static final long serialVersionUID = 1L;

    /** The user ID this API key belongs to */
    private String userId;

    /** The repository ID this API key is valid for */
    private String repositoryId;

    /** BCrypt hash of the API key (never store plain text) */
    private String keyHash;

    /** Key prefix for identification (first 8 chars, e.g., "nw_a1b2") */
    private String keyPrefix;

    /** User-defined name for the API key */
    private String name;

    /** Optional description */
    private String description;

    /** Last time this key was used for authentication */
    private GregorianCalendar lastUsed;

    /** Expiration date/time (null means never expires) */
    private GregorianCalendar expiresAt;

    /** Whether this key is active (can be used for authentication) */
    private boolean active;

    public ApiKey() {
        setType(NodeType.API_KEY.value());
        this.active = true;
    }

    public ApiKey(String userId, String repositoryId, String name) {
        this();
        this.userId = userId;
        this.repositoryId = repositoryId;
        this.name = name;
    }

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

    /**
     * Check if this API key has expired.
     * @return true if the key has expired, false otherwise (including if no expiration is set)
     */
    public boolean isExpired() {
        if (expiresAt == null) {
            return false; // No expiration set
        }
        return new GregorianCalendar().after(expiresAt);
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
