/**
 * Setup Wizard API client.
 * Uses fetch directly (no axios) since Setup Wizard operates outside normal auth flow.
 * All requests include X-Setup-Token header for authentication.
 */

const BASE_URL = '/core/api/v1/setup';

export interface SetupState {
  setupRequired: boolean;
  state: string;
  serverVersion: string;
  completedSteps: string[];
}

export interface ConnectionResult {
  reachable: boolean;
  couchDbVersion?: string;
  errorMessage?: string;
  responseTimeMs?: number;
}

export interface RepositoryOverview {
  repositoryId: string;
  name: string;
  documentCount: number;
  viewCount: number;
  requiredViewCount: number;
  hasSystemFolder: boolean;
  mainRepository: boolean;
  judgment: 'current' | 'legacy' | 'empty';
  error?: string;
}

export interface AuthConfig {
  passwordEnabled: boolean;
  googleEnabled: boolean;
  microsoftEnabled: boolean;
  googleClientId?: string;
  microsoftClientId?: string;
  microsoftTenantId?: string;
}

export interface OidcTestResult {
  reachable: boolean;
  discoveryUrl?: string;
  error?: string;
  clientIdValid?: boolean;
  clientIdIndeterminate?: boolean;
  clientIdError?: string;
}

export interface VectorTestResult {
  reachable: boolean;
  error?: string;
  dimension?: number;
}

export interface ApplyRequest {
  couchdb: {
    url: string;
    username: string;
    password: string;
  };
  auth?: AuthConfig;
  vector?: {
    type: string;
    url?: string;
    region?: string;
    modelId?: string;
    accessKeyId?: string;
    secretAccessKey?: string;
  };
  adminPassword?: string;
}

export interface ApplyResult {
  success: boolean;
  setupComplete?: boolean;
  message?: string;
  error?: string;
  warnings?: string[];
}

class SetupApiClient {
  private token: string = '';

  setToken(token: string): void {
    this.token = token;
  }

  getToken(): string {
    return this.token;
  }

  private async request<T>(path: string, options: RequestInit = {}): Promise<T> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      ...(options.headers as Record<string, string> || {}),
    };
    if (this.token) {
      headers['X-Setup-Token'] = this.token;
    }

    const response = await fetch(`${BASE_URL}${path}`, {
      ...options,
      headers,
    });

    if (!response.ok) {
      const body = await response.text();
      let errorMessage: string;
      try {
        const json = JSON.parse(body);
        errorMessage = json.error || `HTTP ${response.status}`;
      } catch {
        errorMessage = `HTTP ${response.status}`;
      }
      throw new Error(errorMessage);
    }

    return response.json();
  }

  /** GET /state -- no token required */
  async getState(): Promise<SetupState> {
    const response = await fetch(`${BASE_URL}/state`);
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    return response.json();
  }

  /** POST /couchdb/test-connection */
  async testCouchDb(req: { url: string; username: string; password: string }): Promise<ConnectionResult> {
    return this.request<ConnectionResult>('/couchdb/test-connection', {
      method: 'POST',
      body: JSON.stringify(req),
    });
  }

  /** POST /couchdb/probe */
  async probe(req: { url: string; username: string; password: string }): Promise<RepositoryOverview[]> {
    return this.request<RepositoryOverview[]>('/couchdb/probe', {
      method: 'POST',
      body: JSON.stringify(req),
    });
  }

  /** GET /auth/state */
  async getAuthState(): Promise<AuthConfig> {
    return this.request<AuthConfig>('/auth/state');
  }

  /** POST /auth/test-oidc */
  async testOidc(req: { issuerUrl: string; clientId?: string }): Promise<OidcTestResult> {
    return this.request<OidcTestResult>('/auth/test-oidc', {
      method: 'POST',
      body: JSON.stringify(req),
    });
  }

  /** GET /vector/state */
  async getVectorState(): Promise<{ type: string; url: string; region?: string; modelId?: string }> {
    return this.request<{ type: string; url: string; region?: string; modelId?: string }>('/vector/state');
  }

  /** POST /vector/test-connection */
  async testVector(req: { type?: string; url?: string; region?: string; modelId?: string; accessKeyId?: string; secretAccessKey?: string }): Promise<VectorTestResult> {
    return this.request<VectorTestResult>('/vector/test-connection', {
      method: 'POST',
      body: JSON.stringify(req),
    });
  }

  /** POST /apply -- one-shot setup */
  async apply(req: ApplyRequest): Promise<ApplyResult> {
    return this.request<ApplyResult>('/apply', {
      method: 'POST',
      body: JSON.stringify(req),
    });
  }

  /** POST /apply/mark-complete -- explicitly mark setup complete (after warnings) */
  async markComplete(): Promise<{ success: boolean }> {
    return this.request<{ success: boolean }>('/apply/mark-complete', {
      method: 'POST',
    });
  }

  /** POST /admin/change-password */
  async changeAdminPassword(req: { newPassword: string }): Promise<{ success: boolean }> {
    return this.request<{ success: boolean }>('/admin/change-password', {
      method: 'POST',
      body: JSON.stringify(req),
    });
  }
}

export const setupApi = new SetupApiClient();
