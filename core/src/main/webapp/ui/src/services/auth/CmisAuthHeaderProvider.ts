/**
 * CmisAuthHeaderProvider - Authentication header provider for CMIS operations
 * 
 * This module provides authentication headers for CMIS API requests.
 * It reads auth state from localStorage and constructs the appropriate headers
 * for the NemakiWare CMIS backend.
 * 
 * Design Principles:
 * - Single responsibility: Only provides auth headers, doesn't manage auth state
 * - Backward compatible: Returns exact same headers as original CMISService.getAuthHeaders()
 * - Fail-safe: Returns empty headers on any error (localStorage access, JSON parse, etc.)
 * - Decoupled: CMISService doesn't need to know about localStorage or header format
 * 
 * Header Format:
 * - 'Authorization': Basic auth header with username (password is 'dummy' since token is used)
 * - 'nemaki_auth_token': The actual authentication token
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
 * Returns empty object if not authenticated or on any error.
 * 
 * @returns Record of header name to header value
 */
export function getCmisAuthHeaders(): Record<string, string> {
  try {
    const authData = localStorage.getItem(AUTH_STORAGE_KEY);

    if (authData) {
      const auth: StoredAuthData = JSON.parse(authData);

      if (auth.username && auth.token) {
        // Use Basic auth with username to provide username context
        // Password is 'dummy' since actual authentication is via token
        const credentials = btoa(`${auth.username}:dummy`);
        return {
          'Authorization': `Basic ${credentials}`,
          'nemaki_auth_token': String(auth.token)
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
