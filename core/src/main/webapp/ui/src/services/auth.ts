import axios from 'axios';

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
    const authData = localStorage.getItem('nemaki_auth');
    if (authData) {
      try {
        this.currentAuth = JSON.parse(authData);
      } catch (e) {
        localStorage.removeItem('nemaki_auth');
      }
    }
  }

  async login(username: string, password: string, repositoryId: string): Promise<AuthToken> {
    const response = await axios.get(
      `/rest/repo/${repositoryId}/authtoken/${username}/register`,
      { 
        auth: { username, password },
        headers: { 'Accept': 'application/json' }
      }
    );
    
    if (response.data.status === 'success') {
      const token = response.data.value.token;
      this.currentAuth = { token, repositoryId, username };
      localStorage.setItem('nemaki_auth', JSON.stringify(this.currentAuth));
      return this.currentAuth;
    }
    throw new Error('Authentication failed');
  }

  logout(): void {
    if (this.currentAuth) {
      axios.get(`/rest/repo/${this.currentAuth.repositoryId}/authtoken/${this.currentAuth.username}/unregister`, {
        headers: this.getAuthHeaders()
      }).catch(() => {});
    }
    this.currentAuth = null;
    localStorage.removeItem('nemaki_auth');
  }

  getAuthToken(): string | null {
    return this.currentAuth?.token || null;
  }

  getCurrentAuth(): AuthToken | null {
    return this.currentAuth;
  }

  getAuthHeaders(): Record<string, string> {
    const token = this.getAuthToken();
    return token ? { 'Authorization': `Bearer ${token}` } : {};
  }

  isAuthenticated(): boolean {
    return !!this.currentAuth;
  }
}
