/**
 * API Key Service for NemakiWare React UI
 *
 * Provides programmatic access to API key management.
 * API keys enable persistent authentication for MCP clients and
 * cloud-only users who cannot use password authentication.
 *
 * Usage Examples:
 * ```typescript
 * // List API keys
 * const keys = await apiKeyService.listApiKeys('bedroom', 'admin');
 *
 * // Create a new API key
 * const result = await apiKeyService.createApiKey('bedroom', 'admin', 'My MCP Key', 'For Claude Desktop');
 * console.log('Save this key:', result.apiKey); // Only shown once
 *
 * // Revoke an API key
 * await apiKeyService.revokeApiKey('bedroom', 'key-id-123');
 * ```
 */

import { AuthService } from './auth';

export interface ApiKey {
  id: string;
  userId: string;
  name: string;
  description?: string;
  keyPrefix: string;
  active: boolean;
  expired: boolean;
  created?: number;
  lastUsed?: number;
  expiresAt?: number; // epoch milliseconds, null means never expires
}

export interface ApiKeyCreationResult {
  id: string;
  userId: string;
  name: string;
  description?: string;
  keyPrefix: string;
  active: boolean;
  expired: boolean;
  created?: number;
  expiresAt?: number;
  apiKey: string; // The plain text key - only returned on creation
  message: string;
}

export interface ApiKeyListResponse {
  apiKeys: ApiKey[];
}

const getApiBaseUrl = (repositoryId: string) =>
  `/core/api/v1/cmis/repositories/${repositoryId}/apikeys`;

/**
 * List all API keys for a user.
 */
export async function listApiKeys(repositoryId: string, userId: string): Promise<ApiKey[]> {
  const authService = AuthService.getInstance();
  const headers = authService.getAuthHeaders();

  const response = await fetch(`${getApiBaseUrl(repositoryId)}/user/${userId}`, {
    method: 'GET',
    headers: {
      'Accept': 'application/json',
      ...headers
    }
  });

  if (!response.ok) {
    const errorData = await response.json().catch(() => ({}));
    throw new Error(errorData.error || `Failed to list API keys: ${response.status}`);
  }

  const data: ApiKeyListResponse = await response.json();
  return data.apiKeys || [];
}

/**
 * Create a new API key.
 * IMPORTANT: The apiKey field in the response is only returned once.
 * Store it securely as it cannot be retrieved again.
 *
 * @param expiresAt Optional expiration date/time as epoch milliseconds. Null means never expires.
 */
export async function createApiKey(
  repositoryId: string,
  userId: string,
  name: string,
  description?: string,
  expiresAt?: number
): Promise<ApiKeyCreationResult> {
  const authService = AuthService.getInstance();
  const headers = authService.getAuthHeaders();

  const body: Record<string, string> = { name };
  if (description) {
    body.description = description;
  }
  if (expiresAt !== undefined && expiresAt !== null) {
    body.expiresAt = String(expiresAt);
  }

  const response = await fetch(`${getApiBaseUrl(repositoryId)}/user/${userId}`, {
    method: 'POST',
    headers: {
      'Accept': 'application/json',
      'Content-Type': 'application/json',
      ...headers
    },
    body: JSON.stringify(body)
  });

  if (!response.ok) {
    const errorData = await response.json().catch(() => ({}));
    throw new Error(errorData.error || `Failed to create API key: ${response.status}`);
  }

  return await response.json();
}

/**
 * Revoke (delete) an API key.
 * This action cannot be undone.
 */
export async function revokeApiKey(repositoryId: string, keyId: string): Promise<void> {
  const authService = AuthService.getInstance();
  const headers = authService.getAuthHeaders();

  const response = await fetch(`${getApiBaseUrl(repositoryId)}/${keyId}`, {
    method: 'DELETE',
    headers: {
      'Accept': 'application/json',
      ...headers
    }
  });

  if (!response.ok) {
    const errorData = await response.json().catch(() => ({}));
    throw new Error(errorData.error || `Failed to revoke API key: ${response.status}`);
  }
}

export const apiKeyService = {
  listApiKeys,
  createApiKey,
  revokeApiKey
};

export default apiKeyService;
