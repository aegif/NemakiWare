/**
 * CmisAuthHeaderProvider - Authentication header provider for CMIS operations
 * 
 * This module provides authentication headers for CMIS API requests.
 * It reads auth state from localStorage and constructs the appropriate headers
 * for the NemakiWare CMIS backend.
 * 
 * Design Principles:
 * - Single responsibility: Only provides auth headers, doesn't manage auth state
 * - Standard format: Uses Bearer token (OAuth2/JWT standard)
 * - Fail-safe: Returns empty headers on any error (localStorage access, JSON parse, etc.)
 * - Decoupled: CMISService doesn't need to know about localStorage or header format
 * 
 * Header Format:
 * - 'Authorization': Bearer token header (standard OAuth2/JWT format)
 * 
 * Note: With HttpOnly cookies enabled on the server, the browser will also
 * automatically send the auth cookie for same-origin requests. The Bearer
 * header provides backward compatibility and support for cross-origin requests.
 * 
 * Usage:
 * ```typescript
 * import { getCmisAuthHeaders } from './auth/CmisAuthHeaderProvider';
 * 
 * const httpClient = new CmisHttpClient(getCmisAuthHeaders);
 * ```
 */

const AUTH_STORAGE_KEY = 'nemakiware_auth';

/**
 * Auth data structure stored in localStorage
 */
interface StoredAuthData {
  username?: string;
  token?: string;
  repositoryId?: string;
}

/**
 * Get authentication headers for CMIS API requests
 * 
 * Reads auth state from localStorage and constructs headers.
 * Returns Bearer token format (standard OAuth2/JWT format).
 * 
 * Note: With HttpOnly cookies enabled, the browser will automatically
 * send the auth cookie for same-origin requests. However, we still
 * provide Bearer headers for backward compatibility and cross-origin requests.
 * 
 * @returns Record of header name to header value
 */
export function getCmisAuthHeaders(): Record<string, string> {
  try {
    const authData = localStorage.getItem(AUTH_STORAGE_KEY);

    if (authData) {
      const auth: StoredAuthData = JSON.parse(authData);

      if (auth.token) {
        // Use Bearer token format (standard OAuth2/JWT format)
        return {
          'Authorization': `Bearer ${auth.token}`
        };
      }
    }
  } catch (e) {
    // localStorage access failed or JSON parse failed - return empty headers
  }

  return {};
}

/**
 * Check if user is currently authenticated (has valid auth data in localStorage)
 * 
 * @returns true if auth data exists with username and token
 */
export function hasCmisAuth(): boolean {
  try {
    const authData = localStorage.getItem(AUTH_STORAGE_KEY);
    if (authData) {
      const auth: StoredAuthData = JSON.parse(authData);
      return !!(auth.username && auth.token);
    }
  } catch (e) {
    // localStorage access failed or JSON parse failed
  }
  return false;
}

/**
 * Get the current username from auth data
 * 
 * @returns username or null if not authenticated
 */
export function getCmisAuthUsername(): string | null {
  try {
    const authData = localStorage.getItem(AUTH_STORAGE_KEY);
    if (authData) {
      const auth: StoredAuthData = JSON.parse(authData);
      return auth.username || null;
    }
  } catch (e) {
    // localStorage access failed or JSON parse failed
  }
  return null;
}

/**
 * Get the current repository ID from auth data
 * 
 * @returns repositoryId or null if not authenticated
 */
export function getCmisAuthRepositoryId(): string | null {
  try {
    const authData = localStorage.getItem(AUTH_STORAGE_KEY);
    if (authData) {
      const auth: StoredAuthData = JSON.parse(authData);
      return auth.repositoryId || null;
    }
  } catch (e) {
    // localStorage access failed or JSON parse failed
  }
  return null;
}
