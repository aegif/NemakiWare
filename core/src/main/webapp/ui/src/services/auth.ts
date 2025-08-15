
import { REST_BASE } from '../config';

export interface AuthToken {
  token: string;
  repositoryId: string;
  username: string;
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
        this.currentAuth = JSON.parse(authData);
        console.log('AuthService constructor loaded auth:', this.currentAuth);
      } catch (e) {
        console.error('AuthService constructor failed to parse auth data:', e);
        localStorage.removeItem('nemakiware_auth');
      }
    } else {
      console.log('AuthService constructor: no auth data in localStorage');
    }
    
    if (typeof window !== 'undefined') {
      (window as any).authService = this;
      console.log('AuthService constructor: exposed to window');
    }
  }

  async login(username: string, password: string, repositoryId: string): Promise<AuthToken> {
    const formData = new URLSearchParams();
    formData.append('password', password);
    
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${REST_BASE}/repo/${repositoryId}/authtoken/${username}/login`, true);
      xhr.setRequestHeader('Accept', 'application/json');
      xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
      
      const credentials = btoa(`${username}:${password}`);
      xhr.setRequestHeader('Authorization', `Basic ${credentials}`);
      
      xhr.onreadystatechange = () => {
        if (xhr.readyState === 4) {
          console.log('AUTH DEBUG: Status:', xhr.status, 'Response:', xhr.responseText);
          if (xhr.status === 200) {
            try {
              const response = JSON.parse(xhr.responseText);
              console.log('AUTH DEBUG: Parsed response:', response);
              if (response.status === 'success') {
                const token = response.value.token;
                this.currentAuth = { token, repositoryId, username };
                localStorage.setItem('nemakiware_auth', JSON.stringify(this.currentAuth));
                window.dispatchEvent(new CustomEvent('authStateChanged'));
                console.log('AUTH DEBUG: Login successful, token:', token);
                resolve(this.currentAuth);
              } else {
                console.log('AUTH DEBUG: Login failed - invalid status:', response.status);
                reject(new Error('Authentication failed'));
              }
            } catch (e) {
              console.log('AUTH DEBUG: JSON parse error:', e);
              reject(new Error('Invalid response format'));
            }
          } else {
            console.log('AUTH DEBUG: HTTP error:', xhr.status, xhr.statusText);
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
      const xhr = new XMLHttpRequest();
      xhr.open('GET', `${REST_BASE}/repo/${this.currentAuth.repositoryId}/authtoken/${this.currentAuth.username}/unregister`, true);
      const headers = this.getAuthHeaders();
      Object.entries(headers).forEach(([key, value]) => {
        xhr.setRequestHeader(key, value);
      });
      xhr.send();
    }
    this.currentAuth = null;
    localStorage.removeItem('nemakiware_auth');
  }

  getAuthToken(): string | null {
    return this.currentAuth?.token || null;
  }

  getCurrentAuth(): AuthToken | null {
    return this.currentAuth;
  }

  getAuthHeaders(): Record<string, string> {
    const token = this.getAuthToken();
    if (token) {
      return { 
        'AUTH_TOKEN': token
      };
    }
    return {};
  }

  isAuthenticated(): boolean {
    return !!this.currentAuth;
  }
}
