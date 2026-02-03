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
package jp.aegif.nemaki.cmis.factory.auth;

import jp.aegif.nemaki.model.ApiKey;

import java.util.GregorianCalendar;
import java.util.List;

/**
 * Service for managing API keys.
 *
 * API keys provide a way for users to authenticate without passwords,
 * especially useful for MCP clients, cloud-only users, and automation.
 */
public interface ApiKeyService {

    /**
     * Result of API key creation containing both the key object and the plain text key.
     * The plain text key is only available at creation time and is never stored.
     */
    class ApiKeyCreationResult {
        private final ApiKey apiKey;
        private final String plainTextKey;

        public ApiKeyCreationResult(ApiKey apiKey, String plainTextKey) {
            this.apiKey = apiKey;
            this.plainTextKey = plainTextKey;
        }

        public ApiKey getApiKey() {
            return apiKey;
        }

        /**
         * The plain text API key. Only available at creation time.
         * This value should be shown to the user once and never stored.
         */
        public String getPlainTextKey() {
            return plainTextKey;
        }
    }

    /**
     * Create a new API key for a user with no expiration.
     *
     * @param repositoryId The repository ID
     * @param userId The user ID
     * @param name A user-defined name for the key
     * @param description Optional description
     * @return The creation result containing the key object and plain text key
     */
    ApiKeyCreationResult createApiKey(String repositoryId, String userId, String name, String description);

    /**
     * Create a new API key for a user with optional expiration.
     *
     * @param repositoryId The repository ID
     * @param userId The user ID
     * @param name A user-defined name for the key
     * @param description Optional description
     * @param expiresAt Optional expiration date/time (null means never expires)
     * @return The creation result containing the key object and plain text key
     */
    ApiKeyCreationResult createApiKey(String repositoryId, String userId, String name, String description, GregorianCalendar expiresAt);

    /**
     * List all API keys for a user.
     *
     * @param repositoryId The repository ID
     * @param userId The user ID
     * @return List of API keys (without the actual key values)
     */
    List<ApiKey> listApiKeys(String repositoryId, String userId);

    /**
     * Get an API key by its ID.
     *
     * @param repositoryId The repository ID
     * @param keyId The API key ID
     * @return The API key, or null if not found
     */
    ApiKey getApiKey(String repositoryId, String keyId);

    /**
     * Revoke (delete) an API key.
     *
     * @param repositoryId The repository ID
     * @param keyId The API key ID
     * @return true if the key was revoked, false if not found
     */
    boolean revokeApiKey(String repositoryId, String keyId);

    /**
     * Validate an API key and return the user ID if valid.
     *
     * @param repositoryId The repository ID
     * @param apiKey The plain text API key
     * @return The user ID if the key is valid, null otherwise
     */
    String validateApiKey(String repositoryId, String apiKey);

    /**
     * Update the last used timestamp for an API key.
     *
     * @param repositoryId The repository ID
     * @param keyId The API key ID
     */
    void updateLastUsed(String repositoryId, String keyId);
}
