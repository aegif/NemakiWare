import { UserManager, UserManagerSettings, User } from 'oidc-client-ts';
import { AuthToken } from './auth';
import { REST_BASE } from '../config';

export interface OIDCConfig {
  authority: string;
  client_id: string;
  redirect_uri: string;
  post_logout_redirect_uri: string;
  response_type: string;
  scope: string;
}

export class OIDCService {
  private userManager: UserManager;

  constructor(config: OIDCConfig) {
    const settings: UserManagerSettings = {
      authority: config.authority,
      client_id: config.client_id,
      redirect_uri: config.redirect_uri,
      post_logout_redirect_uri: config.post_logout_redirect_uri,
      response_type: config.response_type,
      scope: config.scope,
      automaticSilentRenew: true,
      silent_redirect_uri: `${window.location.origin}/core/ui/silent-callback.html`
    };

    this.userManager = new UserManager(settings);
  }

  async signinRedirect(): Promise<void> {
    return this.userManager.signinRedirect();
  }

  async signinRedirectCallback(): Promise<User> {
    return this.userManager.signinRedirectCallback();
  }

  async getUser(): Promise<User | null> {
    return this.userManager.getUser();
  }

  async convertOIDCToken(oidcUser: User, repositoryId: string): Promise<AuthToken> {
    const response = await fetch(`${REST_BASE}/repo/${repositoryId}/authtoken/oidc/convert`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${oidcUser.access_token}`
      },
      body: JSON.stringify({
        oidc_token: oidcUser.access_token,
        id_token: oidcUser.id_token,
        user_info: oidcUser.profile
      })
    });

    if (!response.ok) {
      throw new Error('Failed to convert OIDC token');
    }

    const result = await response.json();
    return {
      token: result.value.token,
      repositoryId: repositoryId,
      username: result.value.userName
    };
  }

  async signoutRedirect(): Promise<void> {
    return this.userManager.signoutRedirect();
  }
}
