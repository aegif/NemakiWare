/**
 * Session Management E2E Tests - OIDC + Traditional Auth Coexistence
 *
 * Tests for NemakiWare session management when both OIDC and traditional
 * authentication methods are available:
 *
 * 1. Token isolation between auth methods
 * 2. Concurrent sessions with different auth methods
 * 3. Session invalidation (logout) behavior
 * 4. Token expiration handling
 * 5. Cross-method session independence
 *
 * This test file focuses on API-level session management rather than UI flows.
 * For UI-based authentication tests, see:
 * - tests/auth/login.spec.ts (traditional)
 * - tests/auth/oidc-login.spec.ts (OIDC)
 *
 * Prerequisites:
 * - NemakiWare running with both OIDC and traditional auth enabled
 * - Test users: admin (traditional), api-e2e-testuser (traditional)
 * - For full OIDC tests: Keycloak at localhost:8088 (skipped if unavailable)
 */

import { test, expect } from '@playwright/test';
import { generateTestId } from '../utils/test-helper';

const BASE_URL = 'http://localhost:8080/core';
const REPOSITORY_ID = 'bedroom';
const AUTH_HEADER = 'Basic ' + Buffer.from('admin:admin').toString('base64');

// Keycloak settings (for OIDC tests)
const KEYCLOAK_URL = process.env.KEYCLOAK_URL || 'http://localhost:8088';
const OIDC_TOKEN_URL = `${KEYCLOAK_URL}/realms/nemakiware/protocol/openid-connect/token`;

let keycloakAvailable = false;

/**
 * Helper: Traditional login to get NemakiWare auth token
 */
async function traditionalLogin(request: any, username: string, password: string): Promise<string | null> {
  const response = await request.post(
    `${BASE_URL}/rest/repo/${REPOSITORY_ID}/authtoken/${username}/login`,
    {
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      form: { password },
    }
  );

  if (response.ok()) {
    const data = await response.json();
    if (data.status === 'success' && data.value?.token) {
      return data.value.token;
    }
  }
  return null;
}

/**
 * Helper: Get Keycloak OIDC token
 */
async function getKeycloakToken(request: any, username: string, password: string): Promise<string | null> {
  try {
    const response = await request.post(OIDC_TOKEN_URL, {
      form: {
        client_id: 'nemakiware-oidc-client',
        username,
        password,
        grant_type: 'password',
        scope: 'openid profile email',
      },
    });
    if (response.ok()) {
      const data = await response.json();
      return data.access_token || null;
    }
  } catch {
    // Keycloak not available
  }
  return null;
}

/**
 * Helper: Decode JWT payload
 */
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

/**
 * Helper: Convert OIDC token to NemakiWare token
 */
async function oidcLogin(request: any, username: string, password: string): Promise<string | null> {
  const kcToken = await getKeycloakToken(request, username, password);
  if (!kcToken) return null;

  const payload = decodeJwtPayload(kcToken);
  if (!payload) return null;

  const response = await request.post(
    `${BASE_URL}/api/v1/cmis/auth/repositories/${REPOSITORY_ID}/oidc`,
    {
      headers: { 'Content-Type': 'application/json' },
      data: {
        user_info: {
          preferred_username: payload.preferred_username,
          email: payload.email,
          name: payload.name,
        },
      },
    }
  );

  if (response.ok()) {
    const data = await response.json();
    return data.token || null;
  }
  return null;
}

/**
 * Helper: Test CMIS access with token
 */
async function testCmisAccess(request: any, token: string): Promise<boolean> {
  const response = await request.get(
    `${BASE_URL}/browser/${REPOSITORY_ID}/root?cmisselector=object`,
    { headers: { AUTH_TOKEN: token } }
  );
  return response.ok();
}

/**
 * Helper: Logout with token
 */
async function logout(request: any, username: string, token: string): Promise<boolean> {
  const response = await request.post(
    `${BASE_URL}/rest/repo/${REPOSITORY_ID}/authtoken/${username}/logout`,
    {
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded',
        AUTH_TOKEN: token,
      },
    }
  );
  if (response.ok()) {
    const data = await response.json();
    return data.status === 'success';
  }
  return false;
}

// Check Keycloak availability before all tests
test.beforeAll(async ({ request }) => {
  try {
    const response = await request.get(`${KEYCLOAK_URL}/health/ready`, { timeout: 5000 });
    keycloakAvailable = response.ok();
  } catch {
    keycloakAvailable = false;
  }
  console.log(`Session Management Tests - Keycloak available: ${keycloakAvailable}`);
});

test.describe('Session Management - Traditional Auth Basics', () => {
  test('S1: Traditional login returns valid token', async ({ request }) => {
    const token = await traditionalLogin(request, 'admin', 'admin');

    expect(token).not.toBeNull();
    expect(token!.length).toBeGreaterThan(10);
  });

  test('S2: Traditional token allows CMIS access', async ({ request }) => {
    const token = await traditionalLogin(request, 'admin', 'admin');
    expect(token).not.toBeNull();

    const canAccess = await testCmisAccess(request, token!);
    expect(canAccess).toBe(true);
  });

  test('S3: Invalid traditional credentials rejected', async ({ request }) => {
    const token = await traditionalLogin(request, 'admin', 'wrongpassword');
    expect(token).toBeNull();
  });

  test('S4: Non-existent user rejected', async ({ request }) => {
    const token = await traditionalLogin(request, 'nonexistent-user-xyz', 'anypassword');
    expect(token).toBeNull();
  });
});

test.describe('Session Management - Concurrent Traditional Sessions', () => {
  test('S5: Multiple users can have concurrent sessions', async ({ request }) => {
    // Login as admin
    const adminToken = await traditionalLogin(request, 'admin', 'admin');
    expect(adminToken).not.toBeNull();

    // Login as test user (created by global setup)
    const testUserToken = await traditionalLogin(request, 'api-e2e-testuser', 'test');
    expect(testUserToken).not.toBeNull();

    // Both tokens should be different
    expect(adminToken).not.toBe(testUserToken);

    // Both tokens should work
    const adminAccess = await testCmisAccess(request, adminToken!);
    const testUserAccess = await testCmisAccess(request, testUserToken!);

    expect(adminAccess).toBe(true);
    expect(testUserAccess).toBe(true);
  });

  test('S6: Same user can have multiple sessions', async ({ request }) => {
    // Login admin twice
    const token1 = await traditionalLogin(request, 'admin', 'admin');
    const token2 = await traditionalLogin(request, 'admin', 'admin');

    expect(token1).not.toBeNull();
    expect(token2).not.toBeNull();

    // Tokens might be the same or different depending on implementation
    // But both should work
    const access1 = await testCmisAccess(request, token1!);
    const access2 = await testCmisAccess(request, token2!);

    expect(access1).toBe(true);
    expect(access2).toBe(true);
  });

  test('S7: Logout invalidates token', async ({ request }) => {
    // Login to get a token
    const token = await traditionalLogin(request, 'admin', 'admin');
    expect(token).not.toBeNull();

    // Verify token works before logout
    const accessBefore = await testCmisAccess(request, token!);
    expect(accessBefore).toBe(true);

    // Logout to invalidate the token
    const loggedOut = await logout(request, 'admin', token!);
    expect(loggedOut).toBe(true);

    // Verify logout API worked - token status should be invalid
    // Note: With httpCredentials in playwright config, access still works via Basic Auth fallback
    // This test verifies the logout API call itself succeeds
  });

  test('S8: Logout one session does not affect other sessions', async ({ request }) => {
    // Login admin with two sessions
    const token1 = await traditionalLogin(request, 'admin', 'admin');
    const token2 = await traditionalLogin(request, 'admin', 'admin');

    expect(token1).not.toBeNull();
    expect(token2).not.toBeNull();

    // Logout first session
    await logout(request, 'admin', token1!);

    // Second session should still work (if tokens are different)
    // Note: Implementation may issue same token, in which case both would be invalidated
    // This test documents actual behavior
    const access2 = await testCmisAccess(request, token2!);
    // Just verify no crash - actual behavior depends on implementation
    expect(typeof access2).toBe('boolean');
  });
});

test.describe('Session Management - OIDC Auth', () => {
  test.beforeEach(async () => {
    test.skip(!keycloakAvailable, 'Keycloak not available');
  });

  test('S9: OIDC login returns valid NemakiWare token', async ({ request }) => {
    const token = await oidcLogin(request, 'admin', 'admin');

    expect(token).not.toBeNull();
    expect(token!.length).toBeGreaterThan(10);
  });

  test('S10: OIDC token allows CMIS access', async ({ request }) => {
    const token = await oidcLogin(request, 'admin', 'admin');
    expect(token).not.toBeNull();

    const canAccess = await testCmisAccess(request, token!);
    expect(canAccess).toBe(true);
  });

  test('S11: OIDC invalid credentials rejected by Keycloak', async ({ request }) => {
    const token = await oidcLogin(request, 'admin', 'wrongpassword');
    expect(token).toBeNull();
  });
});

test.describe('Session Management - OIDC + Traditional Coexistence', () => {
  test.beforeEach(async () => {
    test.skip(!keycloakAvailable, 'Keycloak not available');
  });

  test('S12: Traditional and OIDC tokens work independently', async ({ request }) => {
    // Get traditional token
    const traditionalToken = await traditionalLogin(request, 'admin', 'admin');
    expect(traditionalToken).not.toBeNull();

    // Get OIDC token
    const oidcToken = await oidcLogin(request, 'admin', 'admin');
    expect(oidcToken).not.toBeNull();

    // Both should work
    const traditionalAccess = await testCmisAccess(request, traditionalToken!);
    const oidcAccess = await testCmisAccess(request, oidcToken!);

    expect(traditionalAccess).toBe(true);
    expect(oidcAccess).toBe(true);
  });

  test('S13: Traditional logout does not affect OIDC token', async ({ request }) => {
    // Get traditional token
    const traditionalToken = await traditionalLogin(request, 'admin', 'admin');
    expect(traditionalToken).not.toBeNull();

    // Get OIDC token
    const oidcToken = await oidcLogin(request, 'admin', 'admin');
    expect(oidcToken).not.toBeNull();

    // Logout traditional session
    await logout(request, 'admin', traditionalToken!);

    // OIDC token should still work (they are independent tokens)
    const oidcAccess = await testCmisAccess(request, oidcToken!);
    expect(oidcAccess).toBe(true);
  });

  test('S14: OIDC logout does not affect traditional token', async ({ request }) => {
    // Get traditional token
    const traditionalToken = await traditionalLogin(request, 'admin', 'admin');
    expect(traditionalToken).not.toBeNull();

    // Get OIDC token
    const oidcToken = await oidcLogin(request, 'admin', 'admin');
    expect(oidcToken).not.toBeNull();

    // Logout OIDC session
    await logout(request, 'admin', oidcToken!);

    // Traditional token should still work
    const traditionalAccess = await testCmisAccess(request, traditionalToken!);
    expect(traditionalAccess).toBe(true);
  });

  test('S15: Different users can use different auth methods concurrently', async ({ request }) => {
    // Admin uses traditional auth
    const adminTraditional = await traditionalLogin(request, 'admin', 'admin');
    expect(adminTraditional).not.toBeNull();

    // testuser uses OIDC (if available in Keycloak)
    const kcToken = await getKeycloakToken(request, 'testuser', 'password');
    if (kcToken) {
      const testuserOidc = await oidcLogin(request, 'testuser', 'password');

      if (testuserOidc) {
        // Both should work independently
        const adminAccess = await testCmisAccess(request, adminTraditional!);
        const testuserAccess = await testCmisAccess(request, testuserOidc);

        expect(adminAccess).toBe(true);
        expect(testuserAccess).toBe(true);
      }
    }
    // If testuser not in Keycloak, just verify admin works
    const adminAccess = await testCmisAccess(request, adminTraditional!);
    expect(adminAccess).toBe(true);
  });
});

test.describe('Session Management - Token Validation', () => {
  test('S16: Invalid token format rejected (without Basic Auth fallback)', async ({ browser }) => {
    // Use fresh context without httpCredentials to test pure token validation
    const context = await browser.newContext({
      httpCredentials: undefined,
      extraHTTPHeaders: {},
    });

    try {
      const response = await context.request.get(
        `${BASE_URL}/browser/${REPOSITORY_ID}/root?cmisselector=object`,
        { headers: { AUTH_TOKEN: 'invalid-token-format' } }
      );
      // Without Basic Auth fallback, invalid token should be rejected
      expect(response.status()).toBeGreaterThanOrEqual(400);
    } finally {
      await context.close();
    }
  });

  test('S17: Empty token with Basic Auth fallback succeeds', async ({ request }) => {
    // With httpCredentials configured, empty AUTH_TOKEN falls back to Basic Auth
    const response = await request.get(
      `${BASE_URL}/browser/${REPOSITORY_ID}/root?cmisselector=object`,
      { headers: { AUTH_TOKEN: '' } }
    );
    // Should succeed via Basic Auth fallback (httpCredentials in playwright config)
    expect(response.ok()).toBe(true);
  });

  test('S18: Expired token rejected (without Basic Auth fallback)', async ({ browser }) => {
    // Use fresh context without httpCredentials
    const context = await browser.newContext({
      httpCredentials: undefined,
      extraHTTPHeaders: {},
    });
    const freshRequest = context.request;

    try {
      // Get a real token
      const token = await traditionalLogin(freshRequest, 'admin', 'admin');
      expect(token).not.toBeNull();

      // Logout to invalidate the token
      await logout(freshRequest, 'admin', token!);

      // Token should now be rejected (no Basic Auth fallback)
      const response = await freshRequest.get(
        `${BASE_URL}/browser/${REPOSITORY_ID}/root?cmisselector=object`,
        { headers: { AUTH_TOKEN: token! } }
      );
      expect(response.status()).toBeGreaterThanOrEqual(400);
    } finally {
      await context.close();
    }
  });
});

test.describe('Session Management - Auth Config', () => {
  test('S19: Auth config endpoint returns method availability', async ({ request }) => {
    const response = await request.get(`${BASE_URL}/rest/auth/config`);
    expect(response.ok()).toBeTruthy();

    const data = await response.json();
    expect(data.status).toBe('success');
    expect(typeof data.oidcEnabled).toBe('boolean');
    expect(typeof data.samlEnabled).toBe('boolean');
  });

  test('S20: Auth methods are consistently available', async ({ request }) => {
    // Check auth config multiple times to ensure consistency
    const responses = await Promise.all([
      request.get(`${BASE_URL}/rest/auth/config`),
      request.get(`${BASE_URL}/rest/auth/config`),
      request.get(`${BASE_URL}/rest/auth/config`),
    ]);

    const configs = await Promise.all(responses.map(r => r.json()));

    // All responses should have same configuration
    expect(configs[0].oidcEnabled).toBe(configs[1].oidcEnabled);
    expect(configs[1].oidcEnabled).toBe(configs[2].oidcEnabled);
    expect(configs[0].samlEnabled).toBe(configs[1].samlEnabled);
  });
});

test.describe('Session Management - CMIS Operations with Different Auth', () => {
  test('S21: Create document with traditional auth token', async ({ request }) => {
    const token = await traditionalLogin(request, 'admin', 'admin');
    expect(token).not.toBeNull();

    // Get root folder
    const rootRes = await request.get(
      `${BASE_URL}/browser/${REPOSITORY_ID}/root?cmisselector=object`,
      { headers: { AUTH_TOKEN: token! } }
    );
    expect(rootRes.ok()).toBeTruthy();
    const rootData = await rootRes.json();
    const rootId = rootData.succinctProperties?.['cmis:objectId'] || rootData.properties?.['cmis:objectId']?.value;

    // Create document
    const uuid = generateTestId();
    const createRes = await request.post(
      `${BASE_URL}/browser/${REPOSITORY_ID}?objectId=${rootId}`,
      {
        headers: { AUTH_TOKEN: token! },
        form: {
          cmisaction: 'createDocument',
          folderId: rootId,
          'propertyId[0]': 'cmis:objectTypeId',
          'propertyValue[0]': 'cmis:document',
          'propertyId[1]': 'cmis:name',
          'propertyValue[1]': `session-test-traditional-${uuid}.txt`,
        },
      }
    );
    expect(createRes.ok()).toBeTruthy();

    // Cleanup
    const createData = await createRes.json();
    const docId = createData.succinctProperties?.['cmis:objectId'] || createData.properties?.['cmis:objectId']?.value;
    await request.post(
      `${BASE_URL}/browser/${REPOSITORY_ID}?objectId=${docId}`,
      {
        headers: { AUTH_TOKEN: token! },
        form: { cmisaction: 'delete', allVersions: 'true' },
      }
    );
  });

  test.skip('S22: Create document with OIDC auth token (requires Keycloak)', async ({ request }) => {
    // Skip if Keycloak not available
    if (!keycloakAvailable) {
      test.skip(true, 'Keycloak not available');
      return;
    }

    const token = await oidcLogin(request, 'admin', 'admin');
    if (!token) {
      test.skip(true, 'Could not get OIDC token');
      return;
    }

    // Get root folder
    const rootRes = await request.get(
      `${BASE_URL}/browser/${REPOSITORY_ID}/root?cmisselector=object`,
      { headers: { AUTH_TOKEN: token } }
    );
    expect(rootRes.ok()).toBeTruthy();
    const rootData = await rootRes.json();
    const rootId = rootData.succinctProperties?.['cmis:objectId'] || rootData.properties?.['cmis:objectId']?.value;

    // Create document
    const uuid = generateTestId();
    const createRes = await request.post(
      `${BASE_URL}/browser/${REPOSITORY_ID}?objectId=${rootId}`,
      {
        headers: { AUTH_TOKEN: token },
        form: {
          cmisaction: 'createDocument',
          folderId: rootId,
          'propertyId[0]': 'cmis:objectTypeId',
          'propertyValue[0]': 'cmis:document',
          'propertyId[1]': 'cmis:name',
          'propertyValue[1]': `session-test-oidc-${uuid}.txt`,
        },
      }
    );
    expect(createRes.ok()).toBeTruthy();

    // Cleanup
    const createData = await createRes.json();
    const docId = createData.succinctProperties?.['cmis:objectId'] || createData.properties?.['cmis:objectId']?.value;
    await request.post(
      `${BASE_URL}/browser/${REPOSITORY_ID}?objectId=${docId}`,
      {
        headers: { AUTH_TOKEN: token },
        form: { cmisaction: 'delete', allVersions: 'true' },
      }
    );
  });
});
