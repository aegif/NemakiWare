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
    const authData = localStorage.getItem('nemakiware_auth');
    if (authData) {
      try {
        this.currentAuth = JSON.parse(authData);
      } catch (e) {
        localStorage.removeItem('nemakiware_auth');
      }
    }
  }

  async login(username: string, password: string, repositoryId: string): Promise<AuthToken> {
    const formData = new URLSearchParams();
    formData.append('password', password);
    
    const response = await axios.post(
      `/core/rest/repo/${repositoryId}/authtoken/${username}/login`,
      formData,
      { 
        headers: { 
          'Accept': 'application/json',
          'Content-Type': 'application/x-www-form-urlencoded'
        }
      }
    );
    
    if (response.data.status === 'success') {
      const token = response.data.value.token;
      this.currentAuth = { token, repositoryId, username };
      localStorage.setItem('nemakiware_auth', JSON.stringify(this.currentAuth));
      return this.currentAuth;
    }
    throw new Error('Authentication failed');
  }

  logout(): void {
    if (this.currentAuth) {
      axios.get(`/core/rest/repo/${this.currentAuth.repositoryId}/authtoken/${this.currentAuth.username}/unregister`, {
        headers: this.getAuthHeaders()
      }).catch(() => {});
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
    const auth = this.getCurrentAuth();
    if (token && auth) {
      return { 
        'AUTH_TOKEN': token,
        'Authorization': `Basic ${btoa(`${auth.username}:${token}`)}`
      };
    }
    return {};
  }

  isAuthenticated(): boolean {
    return !!this.currentAuth;
  }
}
