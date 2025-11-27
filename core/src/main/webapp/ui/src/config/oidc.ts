import { OIDCConfig } from '../services/oidc';

/**
 * OIDC Configuration for NemakiWare React UI
 *
 * For local development with Keycloak:
 *   Start Keycloak: docker compose -f docker/docker-compose-auth-test.yml up -d
 *   Keycloak Admin: http://localhost:8180/admin (admin/admin)
 *
 * Environment Variables (optional):
 *   VITE_OIDC_AUTHORITY - OIDC provider URL (e.g., http://localhost:8180/realms/nemakiware)
 *   VITE_OIDC_CLIENT_ID - Client ID (e.g., nemakiware-ui)
 *   VITE_OIDC_ENABLED - Enable/disable OIDC (true/false)
 */

// Default Keycloak local development configuration
const KEYCLOAK_LOCAL_AUTHORITY = 'http://localhost:8180/realms/nemakiware';
const KEYCLOAK_LOCAL_CLIENT_ID = 'nemakiware-ui';

// Demo provider configuration (for testing without Keycloak)
const DEMO_AUTHORITY = 'https://demo.duendesoftware.com';
const DEMO_CLIENT_ID = 'interactive.public';

/**
 * Detect if local Keycloak is likely available based on current URL
 * Returns true if running on localhost
 */
const isLocalDevelopment = (): boolean => {
  return window.location.hostname === 'localhost' ||
         window.location.hostname === '127.0.0.1';
};

/**
 * Get OIDC authority URL
 * Priority: Environment variable > Local Keycloak (if localhost) > Demo provider
 */
const getAuthority = (): string => {
  // Check environment variable first
  const envAuthority = import.meta.env.VITE_OIDC_AUTHORITY;
  if (envAuthority) {
    return envAuthority;
  }
  // Use local Keycloak for localhost development
  if (isLocalDevelopment()) {
    return KEYCLOAK_LOCAL_AUTHORITY;
  }
  // Fallback to demo provider
  return DEMO_AUTHORITY;
};

/**
 * Get OIDC client ID
 * Priority: Environment variable > Local Keycloak (if localhost) > Demo client
 */
const getClientId = (): string => {
  const envClientId = import.meta.env.VITE_OIDC_CLIENT_ID;
  if (envClientId) {
    return envClientId;
  }
  if (isLocalDevelopment()) {
    return KEYCLOAK_LOCAL_CLIENT_ID;
  }
  return DEMO_CLIENT_ID;
};

export const defaultOIDCConfig: OIDCConfig = {
  authority: getAuthority(),
  client_id: getClientId(),
  redirect_uri: `${window.location.origin}/core/ui/oidc-callback`,
  post_logout_redirect_uri: `${window.location.origin}/core/ui/`,
  response_type: 'code',
  scope: 'openid profile email'
};

export const getOIDCConfig = (): OIDCConfig => {
  return {
    ...defaultOIDCConfig,
    authority: getAuthority(),
    client_id: getClientId()
  };
};

export const isOIDCEnabled = (): boolean => {
  const envEnabled = import.meta.env.VITE_OIDC_ENABLED;
  if (envEnabled !== undefined) {
    return envEnabled === 'true';
  }
  // Enable by default for local development
  return true;
};
