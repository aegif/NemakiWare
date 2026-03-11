import { test, expect } from '@playwright/test';
import { isKeycloakAvailable, getKeycloakUrl } from '../utils/test-state';

/**
 * Keycloak OIDC Authentication E2E Tests
 *
 * Tests the full authentication flow:
 *   OpenLDAP → Keycloak (OIDC token) → NemakiWare (auth token)
 *
 * Requires:
 * - OpenLDAP container with test users (openldap:389)
 * - Keycloak container with nemakiware realm
 * - NemakiWare with LDAP-synced users
 *
 * Endpoints:
 * - Keycloak: /realms/nemakiware/protocol/openid-connect/token
 * - NemakiWare: /core/api/v1/cmis/auth/repositories/{repoId}/oidc
 * - NemakiWare: /core/rest/auth/config
 */

const BASE_URL = 'http://localhost:8080';
const KEYCLOAK_URL = process.env.KEYCLOAK_URL || getKeycloakUrl();
const REPO_ID = 'bedroom';
const OIDC_TOKEN_URL = `${KEYCLOAK_URL}/realms/nemakiware/protocol/openid-connect/token`;
const NEMAKI_OIDC_URL = `${BASE_URL}/core/api/v1/cmis/auth/repositories/${REPO_ID}/oidc`;
// NemakiWare backend runs in Docker and accesses Keycloak via container name
const USERINFO_ENDPOINT = 'http://keycloak:8080/realms/nemakiware/protocol/openid-connect/userinfo';

let keycloakReachable = false;
let keycloakClientConfigured = false;

/** Get OIDC access token from Keycloak using Resource Owner Password Credentials grant */
async function getKeycloakToken(request: any, username: string, password: string): Promise<string | null> {
  try {
    const response = await request.post(OIDC_TOKEN_URL, {
      form: {
        client_id: 'nemakiware-oidc-client',
        username,
        password,
        grant_type: 'password',
        scope: 'openid profile email'
      }
    });
    if (response.ok()) {
      const data = await response.json();
      return data.access_token || null;
    }
    return null;
  } catch {
    return null;
  }
}

/** Decode JWT payload (base64url) */
function decodeJwtPayload(token: string): Record<string, any> | null {
  try {
    const payload = token.split('.')[1];
    const padded = payload + '='.repeat((4 - payload.length % 4) % 4);
    const decoded = Buffer.from(padded, 'base64url').toString('utf-8');
    return JSON.parse(decoded);
  } catch {
    return null;
  }
}

test.beforeAll(async ({ request }) => {
  // Step 1: Check if Keycloak server is reachable
  keycloakReachable = isKeycloakAvailable();
  if (!keycloakReachable) {
    try {
      const response = await request.get(
        `${KEYCLOAK_URL}/realms/nemakiware/.well-known/openid-configuration`,
        { timeout: 5000 }
      );
      keycloakReachable = response.ok();
    } catch { /* server not reachable */ }
  }
  // Step 2: If server is reachable, verify OIDC client configuration separately
  if (keycloakReachable) {
    try {
      const tokenResponse = await request.post(OIDC_TOKEN_URL, {
        form: {
          client_id: 'nemakiware-oidc-client',
          username: 'admin',
          password: 'admin',
          grant_type: 'password',
          scope: 'openid'
        },
        timeout: 5000
      });
      keycloakClientConfigured = tokenResponse.ok();
      if (!keycloakClientConfigured) {
        console.log(`Keycloak OIDC client not configured (token grant returned ${tokenResponse.status()}) — connectivity tests will still run`);
      }
    } catch {
      console.log('Keycloak OIDC client check failed — connectivity tests will still run');
      keycloakClientConfigured = false;
    }
  }
  console.log(`Keycloak reachable: ${keycloakReachable}, client configured: ${keycloakClientConfigured} (URL: ${KEYCLOAK_URL})`);
});

test.describe('Keycloak OIDC - Keycloak Connectivity', () => {

  test.beforeEach(async () => {
    test.skip(!keycloakReachable, 'Keycloak server not reachable');
  });

  test('Keycloak health endpoint returns ready', async ({ request }) => {
    // Try /health/ready first, fall back to OIDC discovery (health endpoint may not be exposed)
    const healthResponse = await request.get(`${KEYCLOAK_URL}/health/ready`).catch(() => null);
    if (healthResponse && healthResponse.ok()) {
      expect(healthResponse.status()).toBe(200);
      const data = await healthResponse.json();
      expect(data.status).toBe('UP');
    } else {
      // Fallback: verify OIDC discovery endpoint works (Keycloak is running but health endpoint not exposed)
      const discoveryResponse = await request.get(
        `${KEYCLOAK_URL}/realms/nemakiware/.well-known/openid-configuration`
      );
      expect(discoveryResponse.status()).toBe(200);
      const data = await discoveryResponse.json();
      expect(data.issuer).toContain('nemakiware');
      console.log('Keycloak health endpoint not available, but OIDC discovery works');
    }
  });

  test('nemakiware realm OIDC discovery is accessible', async ({ request }) => {
    const response = await request.get(
      `${KEYCLOAK_URL}/realms/nemakiware/.well-known/openid-configuration`
    );
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.issuer).toContain('nemakiware');
    expect(data.token_endpoint).toBeDefined();
    expect(data.authorization_endpoint).toBeDefined();
    expect(data.userinfo_endpoint).toBeDefined();
  });

  test('nemakiware-oidc-client is configured', async ({ request }) => {
    test.skip(!keycloakClientConfigured, 'Keycloak OIDC client not configured');
    // Verify client exists by attempting token grant
    const response = await request.post(OIDC_TOKEN_URL, {
      form: {
        client_id: 'nemakiware-oidc-client',
        username: 'admin',
        password: 'admin',
        grant_type: 'password',
        scope: 'openid'
      }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.access_token).toBeDefined();
    expect(data.token_type).toBe('Bearer');
  });
});

test.describe('Keycloak OIDC - LDAP User Authentication via Keycloak', () => {

  test.beforeEach(async () => {
    test.skip(!keycloakClientConfigured, 'Keycloak OIDC client not configured');
  });

  test('LDAP user ldapuser1 can authenticate via Keycloak', async ({ request }) => {
    const token = await getKeycloakToken(request, 'ldapuser1', 'ldappass1');
    expect(token).not.toBeNull();

    const payload = decodeJwtPayload(token!);
    expect(payload).not.toBeNull();
    expect(payload!.preferred_username).toBe('ldapuser1');
    expect(payload!.email).toBe('ldapuser1@nemakiware.example.com');
  });

  test('LDAP user yamada can authenticate via Keycloak', async ({ request }) => {
    const token = await getKeycloakToken(request, 'yamada', 'yamadapass');
    expect(token).not.toBeNull();

    const payload = decodeJwtPayload(token!);
    expect(payload).not.toBeNull();
    expect(payload!.preferred_username).toBe('yamada');
  });

  test('Keycloak realm user admin can authenticate', async ({ request }) => {
    const token = await getKeycloakToken(request, 'admin', 'admin');
    expect(token).not.toBeNull();
  });

  test('Invalid LDAP credentials are rejected by Keycloak', async ({ request }) => {
    const response = await request.post(OIDC_TOKEN_URL, {
      form: {
        client_id: 'nemakiware-oidc-client',
        username: 'ldapuser1',
        password: 'wrongpassword',
        grant_type: 'password',
        scope: 'openid'
      }
    });
    expect(response.status()).toBe(401);
    const data = await response.json();
    expect(data.error).toBe('invalid_grant');
  });

  test('Non-existent user is rejected by Keycloak', async ({ request }) => {
    const response = await request.post(OIDC_TOKEN_URL, {
      form: {
        client_id: 'nemakiware-oidc-client',
        username: 'nonexistent-user',
        password: 'somepass',
        grant_type: 'password',
        scope: 'openid'
      }
    });
    expect(response.status()).toBe(401);
  });

  test('JWT token contains expected claims', async ({ request }) => {
    const token = await getKeycloakToken(request, 'ldapuser1', 'ldappass1');
    expect(token).not.toBeNull();

    const payload = decodeJwtPayload(token!);
    expect(payload).not.toBeNull();
    expect(payload!.iss).toContain('nemakiware');
    expect(payload!.azp).toBe('nemakiware-oidc-client');
    expect(payload!.typ).toBe('Bearer');
    expect(payload!.exp).toBeGreaterThan(Math.floor(Date.now() / 1000));
    expect(payload!.given_name).toBeDefined();
    expect(payload!.family_name).toBeDefined();
  });
});

test.describe('Keycloak OIDC - NemakiWare OIDC Auth Flow', () => {

  test.beforeEach(async () => {
    test.skip(!keycloakClientConfigured, 'Keycloak OIDC client not configured');
  });

  test('Full OIDC flow: Keycloak token → NemakiWare auth token (ldapuser1)', async ({ request }) => {
    // Step 1: Get Keycloak OIDC access token
    const kcToken = await getKeycloakToken(request, 'ldapuser1', 'ldappass1');
    expect(kcToken).not.toBeNull();

    // Step 2: Call NemakiWare OIDC endpoint with access_token + userinfo_endpoint
    const response = await request.post(NEMAKI_OIDC_URL, {
      headers: { 'Content-Type': 'application/json' },
      data: {
        access_token: kcToken,
        userinfo_endpoint: USERINFO_ENDPOINT
      }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.token).toBeDefined();
    expect(data.repositoryId).toBe(REPO_ID);
    expect(data.user).toBeDefined();
    expect(data.user.userId).toBe('ldapuser1');
    expect(data.expiresAt).toBeGreaterThan(Date.now());
  });

  test('Full OIDC flow: Keycloak token → NemakiWare auth token (yamada)', async ({ request }) => {
    const kcToken = await getKeycloakToken(request, 'yamada', 'yamadapass');
    expect(kcToken).not.toBeNull();

    const response = await request.post(NEMAKI_OIDC_URL, {
      headers: { 'Content-Type': 'application/json' },
      data: {
        access_token: kcToken,
        userinfo_endpoint: USERINFO_ENDPOINT
      }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.token).toBeDefined();
    expect(data.user.userId).toBe('yamada');
  });

  test('NemakiWare auth token from OIDC flow is usable for CMIS operations', async ({ request }) => {
    // Get NemakiWare token via OIDC flow
    const kcToken = await getKeycloakToken(request, 'ldapuser1', 'ldappass1');
    expect(kcToken).not.toBeNull();

    const authResponse = await request.post(NEMAKI_OIDC_URL, {
      headers: { 'Content-Type': 'application/json' },
      data: {
        access_token: kcToken,
        userinfo_endpoint: USERINFO_ENDPOINT
      }
    });
    const authData = await authResponse.json();
    const nemakiToken = authData.token;

    // Use NemakiWare token to access CMIS
    const cmisResponse = await request.get(
      `${BASE_URL}/core/browser/${REPO_ID}/root?cmisselector=object`, {
        headers: { 'AUTH_TOKEN': nemakiToken }
      }
    );
    expect(cmisResponse.status()).toBe(200);
    const cmisData = await cmisResponse.json();
    const name = cmisData.properties?.['cmis:name']?.value || cmisData.succinctProperties?.['cmis:name'];
    expect(name).toBeDefined();
  });

  test('OIDC endpoint rejects missing access_token', async ({ request }) => {
    const response = await request.post(NEMAKI_OIDC_URL, {
      headers: { 'Content-Type': 'application/json' },
      data: {}
    });
    expect(response.status()).toBe(400);
  });

  test('OIDC endpoint rejects invalid access_token', async ({ request }) => {
    const response = await request.post(NEMAKI_OIDC_URL, {
      headers: { 'Content-Type': 'application/json' },
      data: {
        access_token: 'invalid-token-value',
        userinfo_endpoint: USERINFO_ENDPOINT
      }
    });
    // Should fail with 401 (access token validation failed)
    expect(response.status()).toBeGreaterThanOrEqual(400);
  });

  test('OIDC endpoint auto-creates user for new Keycloak user', async ({ request }) => {
    // Use testuser (Keycloak realm user, not from LDAP sync)
    const kcToken = await getKeycloakToken(request, 'testuser', 'password');
    expect(kcToken).not.toBeNull();

    const response = await request.post(NEMAKI_OIDC_URL, {
      headers: { 'Content-Type': 'application/json' },
      data: {
        access_token: kcToken,
        userinfo_endpoint: USERINFO_ENDPOINT
      }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.user.userId).toBe('testuser');
  });
});

test.describe('Keycloak OIDC - Auth Config Endpoint', () => {

  test('GET /rest/auth/config returns SSO configuration', async ({ request }) => {
    const response = await request.get(`${BASE_URL}/core/rest/auth/config`);
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(typeof data.oidcEnabled).toBe('boolean');
    expect(typeof data.samlEnabled).toBe('boolean');
    expect(data.status).toBe('success');
  });
});
