import { SAMLConfig } from '../services/saml';
import { getAuthConfig } from '../services/authConfig';

/** Cached SAML config loaded from backend */
let cachedSAMLConfig: SAMLConfig | null = null;

/**
 * Load SAML configuration from backend AuthConfigResource API.
 * Replaces previous hardcoded localhost:8088 values.
 */
export const loadSAMLConfig = async (repositoryId?: string): Promise<SAMLConfig> => {
  try {
    const authConfig = await getAuthConfig(repositoryId);
    const config: SAMLConfig = {
      sso_url: authConfig.samlSsoUrl || '',
      entity_id: authConfig.samlSpEntityId || 'nemakiware-sp',
      callback_url: `${window.location.origin}/core/saml/acs`,
      logout_url: authConfig.samlSloUrl || `${window.location.origin}/core/ui/`,
    };
    cachedSAMLConfig = config;
    return config;
  } catch {
    // Return empty config on failure (safe fail: SAML login will not redirect)
    return {
      sso_url: '',
      entity_id: 'nemakiware-sp',
      callback_url: `${window.location.origin}/core/saml/acs`,
      logout_url: `${window.location.origin}/core/ui/`,
    };
  }
};

/**
 * Get cached SAML config synchronously.
 * Returns empty config if not yet loaded.
 */
export const getSAMLConfig = (): SAMLConfig => {
  if (cachedSAMLConfig) {
    return cachedSAMLConfig;
  }
  return {
    sso_url: '',
    entity_id: 'nemakiware-sp',
    callback_url: `${window.location.origin}/core/saml/acs`,
    logout_url: `${window.location.origin}/core/ui/`,
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
