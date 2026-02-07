/**
 * Authentication Service for NemakiWare React UI
 *
 * Singleton service managing authentication state.
 * Authentication tokens are stored as HttpOnly cookies by the server,
 * NOT in JavaScript-accessible storage (XSS protection).
 * localStorage stores only non-sensitive UI state (username, repositoryId, authMethod).
 *
 * Key design:
 * - login() sends credentials via XHR; server sets HttpOnly cookie in response
 * - logout() calls server to clear the cookie; clears local UI state
 * - getAuthHeaders() returns empty headers; cookie is sent automatically by browser
 * - saveAuth() stores non-sensitive data for UI state restoration after page refresh
 * - 'authStateChanged' custom event bridges service state to React AuthContext
 */

export interface AuthToken {
  token?: string;
  repositoryId: string;
  username: string;
  /** Authentication method used */
  authMethod?: 'basic' | 'oidc' | 'saml' | 'google' | 'microsoft';
}

export class AuthService {
  private static instance: AuthService;
  private currentAuth: AuthToken | null = null;

  static getInstance(): AuthService {
    if (!AuthService.instance) {
      AuthService.instance = new AuthService();
    }
    return AuthService.instance;
  }

  constructor() {
    const authData = localStorage.getItem('nemakiware_auth');
    if (authData) {
      try {
        const parsed = JSON.parse(authData);
        // Restore non-sensitive auth state only (token is NOT stored in localStorage)
        // The HttpOnly cookie handles actual authentication
        this.currentAuth = {
          repositoryId: parsed.repositoryId,
          username: parsed.username,
          authMethod: parsed.authMethod,
        };
      } catch (e) {
        // Failed to parse auth data - remove invalid data
        localStorage.removeItem('nemakiware_auth');
      }
    }

    // Only expose authService globally in development mode for debugging
    // SECURITY: This is disabled in production to prevent token exposure via browser console
    if (typeof window !== 'undefined' && import.meta.env.DEV) {
      (window as any).authService = this;
    }
  }

  async login(username: string, password: string, repositoryId: string): Promise<AuthToken> {
    const formData = new URLSearchParams();
    formData.append('password', password);
    
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `/core/rest/repo/${repositoryId}/authtoken/${username}/login`, true);
      xhr.setRequestHeader('Accept', 'application/json');
      xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
      xhr.withCredentials = true; // Receive and store HttpOnly cookie from server
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              if (response.status === 'success') {
                // Store non-sensitive auth state in memory and localStorage
                // The actual token is stored as an HttpOnly cookie by the server
                this.currentAuth = { repositoryId, username, authMethod: 'basic' };
                localStorage.setItem('nemakiware_auth', JSON.stringify(this.currentAuth));

                // Trigger custom event to notify AuthContext immediately
                window.dispatchEvent(new CustomEvent('authStateChanged'));

                resolve(this.currentAuth);
              } else {
                reject(new Error('Authentication failed'));
              }
            } catch (e) {
              reject(new Error('Invalid response format'));
            }
          } else {
            reject(new Error('Authentication failed'));
          }
        }
      };
      
      xhr.onerror = () => reject(new Error('Network error'));
      xhr.send(formData.toString());
    });
  }

  logout(): void {
    if (this.currentAuth) {
      // Call server-side logout endpoint to clear HttpOnly cookie
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `/core/rest/repo/${this.currentAuth.repositoryId}/authtoken/${this.currentAuth.username}/logout`, true);
      xhr.withCredentials = true;
      xhr.onerror = () => {
        console.error('Logout request failed (network error)');
      };
      xhr.onload = () => {
        if (xhr.status >= 400) {
          console.error(`Logout request failed: ${xhr.status} ${xhr.statusText}`);
        }
      };
      xhr.send();
    }
    this.currentAuth = null;
    localStorage.removeItem('nemakiware_auth');

    // Clear OIDC session data from localStorage
    const oidcKeys = Object.keys(localStorage).filter(key => key.startsWith('oidc.'));
    oidcKeys.forEach(key => localStorage.removeItem(key));

    // Clear SAML session data from localStorage
    const samlKeys = Object.keys(localStorage).filter(key =>
      key.startsWith('saml.') || key.includes('saml') || key.includes('SAML')
    );
    samlKeys.forEach(key => localStorage.removeItem(key));
  }

  /** @deprecated Token is no longer accessible to JavaScript. Use HttpOnly cookie instead. */
  getAuthToken(): string | null {
    return null;
  }

  getCurrentAuth(): AuthToken | null {
    return this.currentAuth;
  }

  /**
   * Get authentication headers for API requests.
   * 
   * Returns Bearer token format (standard OAuth2/JWT format).
   * Note: With HttpOnly cookies enabled, the browser will automatically
   * send the auth cookie, so headers may not be strictly necessary for
   * same-origin requests. However, we still provide Bearer headers for:
   * - Backward compatibility
   * - Cross-origin requests where cookies may not be sent
   * - Non-browser clients
   */
  /** Returns auth headers. HttpOnly cookie handles authentication automatically. */
  getAuthHeaders(): Record<string, string> {
    return {};
  }

  isAuthenticated(): boolean {
    return !!this.currentAuth;
  }

  /**
   * Save authentication token from external sources (OIDC, SAML).
   * Used when authentication is performed outside of the normal login flow.
   * Saves to localStorage and dispatches authStateChanged event.
   */
  saveAuth(auth: AuthToken): void {
    // Store non-sensitive auth state only; actual token is in HttpOnly cookie
    this.currentAuth = {
      repositoryId: auth.repositoryId,
      username: auth.username,
      authMethod: auth.authMethod,
    };
    localStorage.setItem('nemakiware_auth', JSON.stringify(this.currentAuth));

    // Trigger custom event to notify AuthContext immediately
    window.dispatchEvent(new CustomEvent('authStateChanged'));
  }
}
