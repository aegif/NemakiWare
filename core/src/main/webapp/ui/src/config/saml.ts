import { SAMLConfig } from '../services/saml';
import { getAuthConfig } from '../services/authConfig';

export const defaultSAMLConfig: SAMLConfig = {
  sso_url: 'https://demo.saml.provider.com/sso',
  entity_id: 'nemakiware-saml-client',
  callback_url: `${window.location.origin}/core/ui/saml-callback.html`,
  logout_url: `${window.location.origin}/core/ui/`
};

export const getSAMLConfig = (): SAMLConfig => {
  return {
    sso_url: 'http://localhost:8088/realms/nemakiware/protocol/saml',
    entity_id: 'nemakiware-saml-client',
    callback_url: `${window.location.origin}/core/ui/saml-callback.html`,
    logout_url: `${window.location.origin}/core/ui/`
  };
};

/**
 * Check if SAML login is enabled.
 * Fetches configuration from backend API.
 * Returns false (safe default) if backend is unavailable.
 */
export const isSAMLEnabled = async (): Promise<boolean> => {
  try {
    const config = await getAuthConfig();
    return config.samlEnabled;
  } catch {
    return false;
  }
};
