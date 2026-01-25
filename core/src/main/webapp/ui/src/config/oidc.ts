import { OIDCConfig } from '../services/oidc';
import { getAuthConfig } from '../services/authConfig';

export const defaultOIDCConfig: OIDCConfig = {
  authority: 'http://localhost:8088/realms/nemakiware',
  client_id: 'nemakiware-oidc-client',
  redirect_uri: `${window.location.origin}/core/ui/oidc-callback.html`,
  post_logout_redirect_uri: `${window.location.origin}/core/ui/`,
  response_type: 'code',
  scope: 'openid profile email'
};

export const getOIDCConfig = (): OIDCConfig => {
  return defaultOIDCConfig;
};

/**
 * Check if OIDC login is enabled.
 * Fetches configuration from backend API.
 * Returns false (safe default) if backend is unavailable.
 */
export const isOIDCEnabled = async (): Promise<boolean> => {
  try {
    const config = await getAuthConfig();
    return config.oidcEnabled;
  } catch {
    return false;
  }
};
