import { AuthToken } from './auth';

export interface SAMLConfig {
  sso_url: string;
  entity_id: string;
  certificate?: string;
  callback_url: string;
  logout_url?: string;
}

export interface SAMLResponse {
  saml_response: string;
  relay_state?: string;
  user_attributes: Record<string, any>;
}

export class SAMLService {
  private config: SAMLConfig;

  constructor(config: SAMLConfig) {
    this.config = config;
  }

  initiateLogin(repositoryId?: string): void {
    const relayState = repositoryId ? `repositoryId=${repositoryId}` : '';
    const params = new URLSearchParams({
      SAMLRequest: this.generateSAMLRequest(),
      RelayState: relayState
    });
    
    window.location.href = `${this.config.sso_url}?${params.toString()}`;
  }

  private generateSAMLRequest(): string {
    const request = {
      issuer: this.config.entity_id,
      callback: this.config.callback_url,
      timestamp: new Date().toISOString()
    };
    
    return btoa(JSON.stringify(request));
  }

  async handleSAMLResponse(samlResponse: string, relayState?: string): Promise<AuthToken> {
    const repositoryId = this.extractRepositoryIdFromRelayState(relayState) || 'bedroom';
    
    const response = await fetch(`/core/rest/repo/${repositoryId}/authtoken/saml/convert`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        saml_response: samlResponse,
        relay_state: relayState
      })
    });

    if (!response.ok) {
      throw new Error('Failed to convert SAML response');
    }

    const result = await response.json();
    return {
      token: result.value.token,
      repositoryId: repositoryId,
      username: result.value.userName
    };
  }

  private extractRepositoryIdFromRelayState(relayState?: string): string | null {
    if (!relayState) return null;
    
    const params = new URLSearchParams(relayState);
    return params.get('repositoryId');
  }

  async convertSAMLResponse(samlResponseData: SAMLResponse, repositoryId: string): Promise<AuthToken> {
    const response = await fetch(`/core/rest/repo/${repositoryId}/authtoken/saml/convert`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        saml_response: samlResponseData.saml_response,
        relay_state: samlResponseData.relay_state,
        user_attributes: samlResponseData.user_attributes
      })
    });

    if (!response.ok) {
      throw new Error('Failed to convert SAML response');
    }

    const result = await response.json();
    return {
      token: result.value.token,
      repositoryId: repositoryId,
      username: result.value.userName
    };
  }

  initiateLogout(): void {
    if (this.config.logout_url) {
      window.location.href = this.config.logout_url;
    }
  }
}
