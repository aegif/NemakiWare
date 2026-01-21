import { OIDCConfig } from '../services/oidc';

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

export const isOIDCEnabled = (): boolean => {
  return true;
};
