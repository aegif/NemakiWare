/**
 * CmisAuthHeaderProvider - Authentication header provider for CMIS operations
 *
 * Authentication is handled entirely by HttpOnly cookies (nemaki_auth_token).
 * The browser automatically sends the cookie for same-origin requests.
 * No explicit auth headers are needed.
 *
 * This module also provides helper functions to check auth state from localStorage
 * (non-sensitive data: username, repositoryId, authMethod).
 */

const AUTH_STORAGE_KEY = 'nemakiware_auth';

/**
 * Auth data structure stored in localStorage (non-sensitive data only)
 */
interface StoredAuthData {
  username?: string;
  repositoryId?: string;
  authMethod?: string;
}

/**
 * Get authentication headers for CMIS API requests.
 * Authentication is handled by HttpOnly cookie, returns empty headers.
 */
export function getCmisAuthHeaders(): Record<string, string> {
  return {};
}

/**
 * Check if user is currently authenticated (has valid auth data in localStorage)
 */
export function hasCmisAuth(): boolean {
  try {
    const authData = localStorage.getItem(AUTH_STORAGE_KEY);
    if (authData) {
      const auth: StoredAuthData = JSON.parse(authData);
      return !!(auth.username && auth.repositoryId);
    }
  } catch (e) {
    // localStorage access failed or JSON parse failed
  }
  return false;
}

/**
 * Get the current username from auth data
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
