import { SAMLConfig } from '../services/saml';

/**
 * SAML Configuration for NemakiWare React UI
 *
 * For local development with Keycloak:
 *   Start Keycloak: docker compose -f docker/docker-compose-auth-test.yml up -d
 *   Keycloak Admin: http://localhost:8180/admin (admin/admin)
 *
 * Environment Variables (optional):
 *   VITE_SAML_SSO_URL - SAML SSO URL
 *   VITE_SAML_ENTITY_ID - Service Provider Entity ID
 *   VITE_SAML_ENABLED - Enable/disable SAML (true/false)
 */

// Default Keycloak local development configuration
const KEYCLOAK_LOCAL_SSO_URL = 'http://localhost:8180/realms/nemakiware/protocol/saml';
const KEYCLOAK_LOCAL_ENTITY_ID = 'nemakiware-sp';
const KEYCLOAK_LOCAL_LOGOUT_URL = 'http://localhost:8180/realms/nemakiware/protocol/saml';

/**
 * Detect if local Keycloak is likely available based on current URL
 * Returns true if running on localhost
 */
const isLocalDevelopment = (): boolean => {
  return window.location.hostname === 'localhost' ||
         window.location.hostname === '127.0.0.1';
};

/**
 * Get SAML SSO URL
 * Priority: Environment variable > Local Keycloak (if localhost) > Legacy endpoint
 */
const getSsoUrl = (): string => {
  const envSsoUrl = import.meta.env.VITE_SAML_SSO_URL;
  if (envSsoUrl) {
    return envSsoUrl;
  }
  if (isLocalDevelopment()) {
    return KEYCLOAK_LOCAL_SSO_URL;
  }
  // Fallback to legacy NemakiWare SAML endpoint
  return '/core/samlLogin';
};

/**
 * Get SAML Entity ID
 * Priority: Environment variable > Local Keycloak config > Default
 */
const getEntityId = (): string => {
  const envEntityId = import.meta.env.VITE_SAML_ENTITY_ID;
  if (envEntityId) {
    return envEntityId;
  }
  if (isLocalDevelopment()) {
    return KEYCLOAK_LOCAL_ENTITY_ID;
  }
  return 'nemakiware-spa';
};

/**
 * Get SAML Logout URL
 * Priority: Environment variable > Local Keycloak config > Default
 */
const getLogoutUrl = (): string => {
  const envLogoutUrl = import.meta.env.VITE_SAML_LOGOUT_URL;
  if (envLogoutUrl) {
    return envLogoutUrl;
  }
  if (isLocalDevelopment()) {
    return KEYCLOAK_LOCAL_LOGOUT_URL;
  }
  return `${window.location.origin}/core/ui/`;
};

export const defaultSAMLConfig: SAMLConfig = {
  sso_url: getSsoUrl(),
  entity_id: getEntityId(),
  callback_url: `${window.location.origin}/core/ui/saml-callback`,
  logout_url: getLogoutUrl()
};

export const getSAMLConfig = (): SAMLConfig => {
  return {
    sso_url: getSsoUrl(),
    entity_id: getEntityId(),
    callback_url: `${window.location.origin}/core/ui/saml-callback`,
    logout_url: getLogoutUrl()
  };
};

export const isSAMLEnabled = (): boolean => {
  const envEnabled = import.meta.env.VITE_SAML_ENABLED;
  if (envEnabled !== undefined) {
    return envEnabled === 'true';
  }
  // Enable by default for local development
  return true;
};
