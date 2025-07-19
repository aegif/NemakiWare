import { OIDCConfig } from '../services/oidc';

export const defaultOIDCConfig: OIDCConfig = {
  authority: 'https://demo.duendesoftware.com',
  client_id: 'interactive.public',
  redirect_uri: `${window.location.origin}/core/ui/oidc-callback`,
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
