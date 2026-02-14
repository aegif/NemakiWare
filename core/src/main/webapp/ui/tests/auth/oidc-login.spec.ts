/**
 * OIDC Authentication E2E Tests
 *
 * Test suite for NemakiWare OIDC (OpenID Connect) authentication functionality:
 * - OIDC login button visibility and interaction
 * - OIDC redirect to Keycloak identity provider
 * - OIDC callback handling and token conversion
 * - OIDC session management
 *
 * Prerequisites:
 * - Keycloak server running at http://localhost:8180
 * - Keycloak realm 'nemakiware' configured with OIDC client 'nemakiware-ui'
 * - Test user 'testuser' with password 'password' in Keycloak
 *
 * Environment Variables:
 * - KEYCLOAK_URL: Keycloak server URL (default: http://localhost:8180)
 * - OIDC_CLIENT_ID: OIDC client ID (default: nemakiware-ui)
 *
 * NOTE: These tests are automatically skipped when Keycloak is not running.
 */

import { test, expect } from '@playwright/test';
import { isKeycloakAvailable, KEYCLOAK_SKIP_MESSAGE } from '../utils/test-state';

const KEYCLOAK_URL = process.env.KEYCLOAK_URL || 'http://localhost:8180';
const OIDC_CLIENT_ID = process.env.OIDC_CLIENT_ID || 'nemakiware-ui';

test.describe('NemakiWare OIDC Authentication', () => {
  // Serial mode: OIDC tests interact with shared Keycloak session state
  test.describe.configure({ mode: 'serial' });

  test.beforeEach(async ({ page }, testInfo) => {
    if (!isKeycloakAvailable()) {
      testInfo.skip(true, KEYCLOAK_SKIP_MESSAGE);
    }
    await page.context().clearCookies();
    await page.context().clearPermissions();
  });

  test('should display OIDC login button on login page', async ({ page }) => {
    await page.goto('/core/ui/');
    
    await page.waitForFunction(
      () => {
        const root = document.getElementById('root');
        return root && root.children.length > 0;
      },
      { timeout: 30000 }
    );

    await page.waitForTimeout(1000);

    const oidcButton = page.locator('button:has-text("OIDC"), button:has-text("OpenID")').first();
    await expect(oidcButton).toBeVisible({ timeout: 10000 });
  });

  test('should redirect to Keycloak when OIDC button is clicked', async ({ page }) => {
    await page.goto('/core/ui/');

    await page.waitForFunction(
      () => {
        const root = document.getElementById('root');
        return root && root.children.length > 0;
      },
      { timeout: 30000 }
    );

    await page.waitForTimeout(1000);

    const oidcButton = page.locator('button:has-text("OIDC"), button:has-text("OpenID")').first();
    await oidcButton.click();

    // Extract port from KEYCLOAK_URL for dynamic matching
    const keycloakPort = new URL(KEYCLOAK_URL).port || '8088';
    const urlPattern = new RegExp(`localhost:${keycloakPort}|keycloak`, 'i');
    await page.waitForURL(urlPattern, { timeout: 15000 });

    expect(page.url()).toContain(keycloakPort);
  });

  test('should complete OIDC login flow with Keycloak', async ({ page }) => {
    await page.goto('/core/ui/');

    await page.waitForFunction(
      () => {
        const root = document.getElementById('root');
        return root && root.children.length > 0;
      },
      { timeout: 30000 }
    );

    await page.waitForTimeout(1000);

    const oidcButton = page.locator('button:has-text("OIDC"), button:has-text("OpenID")').first();
    await oidcButton.click();

    // Extract port from KEYCLOAK_URL for dynamic matching
    const keycloakPort = new URL(KEYCLOAK_URL).port || '8088';
    const urlPattern = new RegExp(`localhost:${keycloakPort}|keycloak`, 'i');
    await page.waitForURL(urlPattern, { timeout: 15000 });

    const usernameField = page.locator('input[name="username"], #username').first();
    await usernameField.waitFor({ state: 'visible', timeout: 10000 });
    await usernameField.fill('testuser');

    const passwordField = page.locator('input[name="password"], #password').first();
    await passwordField.fill('password');

    const loginButton = page.locator('input[type="submit"], button[type="submit"], #kc-login').first();
    await loginButton.click();

    await page.waitForURL(/localhost:8080|core\/ui/i, { timeout: 30000 });

    expect(page.url()).toContain('8080');
  });

  test('should handle OIDC token conversion endpoint', async ({ page, request }) => {
    // Get a real access token from Keycloak using password grant
    const keycloakPort = new URL(KEYCLOAK_URL).port || '8180';
    const tokenEndpoint = `http://localhost:${keycloakPort}/realms/nemakiware/protocol/openid-connect/token`;
    // NemakiWare backend runs in Docker and accesses Keycloak via container name
    const userinfoEndpoint = 'http://keycloak:8080/realms/nemakiware/protocol/openid-connect/userinfo';

    const tokenResponse = await request.post(tokenEndpoint, {
      form: {
        grant_type: 'password',
        client_id: OIDC_CLIENT_ID,
        username: 'testuser',
        password: 'password',
        scope: 'openid profile email'
      }
    });

    expect(tokenResponse.ok()).toBeTruthy();
    const tokenResult = await tokenResponse.json();
    expect(tokenResult.access_token).toBeDefined();

    // Use the real access token to call NemakiWare's OIDC convert endpoint
    const response = await request.post('/core/rest/repo/bedroom/authtoken/oidc/convert', {
      headers: {
        'Content-Type': 'application/json',
      },
      data: {
        access_token: tokenResult.access_token,
        userinfo_endpoint: userinfoEndpoint
      }
    });

    expect(response.ok()).toBeTruthy();

    const result = await response.json();
    expect(result.status).toBe('success');
    expect(result.value).toBeDefined();
    expect(result.value.userName).toBe('testuser');
    expect(result.value.token).toBeDefined();
    expect(result.value.repositoryId).toBe('bedroom');
  });

  test('should reject OIDC token conversion without user_info', async ({ request }) => {
    const response = await request.post('/core/rest/repo/bedroom/authtoken/oidc/convert', {
      headers: {
        'Content-Type': 'application/json',
      },
      data: {}
    });

    const result = await response.json();
    expect(result.status).toBe('failure');
    expect(result.error).toBeDefined();
  });
});
