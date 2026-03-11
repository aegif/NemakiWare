/**
 * SAML Authentication E2E Tests
 *
 * Test suite for NemakiWare SAML 2.0 authentication functionality:
 * - SAML login button visibility and interaction
 * - SAML redirect to Keycloak identity provider
 * - SAML callback handling and token conversion
 * - SAML session management
 *
 * Prerequisites:
 * - Keycloak server running at http://localhost:8180
 * - Keycloak realm 'nemakiware' configured with SAML client 'nemakiware-sp'
 * - Test user 'testuser' with password 'password' in Keycloak
 *
 * Environment Variables:
 * - KEYCLOAK_URL: Keycloak server URL (default: http://localhost:8180)
 * - SAML_ENTITY_ID: SAML entity ID (default: nemakiware-saml-client)
 *
 * NOTE: These tests are automatically skipped when Keycloak is not running.
 */

import { test, expect } from '@playwright/test';
import { isKeycloakAvailable, KEYCLOAK_SKIP_MESSAGE } from '../utils/test-state';

const KEYCLOAK_URL = process.env.KEYCLOAK_URL || 'http://localhost:8088';
const SAML_ENTITY_ID = process.env.SAML_ENTITY_ID || 'nemakiware-sp';

test.describe('NemakiWare SAML Authentication', () => {
  // Serial mode: SAML tests interact with shared Keycloak session state
  test.describe.configure({ mode: 'serial' });

  test.beforeEach(async ({ page }, testInfo) => {
    if (!isKeycloakAvailable()) {
      testInfo.skip(true, KEYCLOAK_SKIP_MESSAGE);
    }
    await page.context().clearCookies();
    await page.context().clearPermissions();
  });

  test('should display SAML login button on login page', async ({ page }) => {
    await page.goto('/core/ui/');
    
    await page.waitForFunction(
      () => {
        const root = document.getElementById('root');
        return root && root.children.length > 0;
      },
      { timeout: 30000 }
    );

    await page.waitForTimeout(1000);

    const samlButton = page.locator('button:has-text("SAML"), button:has-text("SSO")').first();
    await expect(samlButton).toBeVisible({ timeout: 10000 });
  });

  test('should redirect to Keycloak when SAML button is clicked', async ({ page }) => {
    await page.goto('/core/ui/');

    await page.waitForFunction(
      () => {
        const root = document.getElementById('root');
        return root && root.children.length > 0;
      },
      { timeout: 30000 }
    );

    await page.waitForTimeout(1000);

    const samlButton = page.locator('button:has-text("SAML"), button:has-text("SSO")').first();
    await samlButton.click();

    // Extract port from KEYCLOAK_URL for dynamic matching
    const keycloakPort = new URL(KEYCLOAK_URL).port || '8088';
    const urlPattern = new RegExp(`localhost:${keycloakPort}|keycloak`, 'i');
    await page.waitForURL(urlPattern, { timeout: 15000 });

    expect(page.url()).toContain(keycloakPort);
  });

  test('should complete SAML login flow with Keycloak', async ({ page }) => {
    await page.goto('/core/ui/');

    await page.waitForFunction(
      () => {
        const root = document.getElementById('root');
        return root && root.children.length > 0;
      },
      { timeout: 30000 }
    );

    await page.waitForTimeout(1000);

    const samlButton = page.locator('button:has-text("SAML"), button:has-text("SSO")').first();
    await samlButton.click();

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

  test('should reject SAML token conversion (signature verification not implemented)', async ({ request }) => {
    const samlResponse = Buffer.from(
      '<samlp:Response xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol" xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion">' +
      '<saml:Assertion><saml:NameID>testuser</saml:NameID></saml:Assertion>' +
      '</samlp:Response>'
    ).toString('base64');

    const response = await request.post('/core/rest/repo/bedroom/authtoken/saml/convert', {
      headers: {
        'Content-Type': 'application/json',
      },
      data: {
        saml_response: samlResponse,
        relay_state: 'repositoryId=bedroom'
      }
    });

    expect(response.ok()).toBeTruthy();

    // SAML token conversion is disabled for security (no signature verification)
    const result = await response.json();
    expect(result.status).toBe('failure');
    expect(result.error).toBeDefined();
  });

  test('should reject SAML token conversion without saml_response', async ({ request }) => {
    const response = await request.post('/core/rest/repo/bedroom/authtoken/saml/convert', {
      headers: {
        'Content-Type': 'application/json',
      },
      data: {}
    });

    const result = await response.json();
    expect(result.status).toBe('failure');
    expect(result.error).toBeDefined();
  });

  test('should reject SAML auto-provisioning (endpoint disabled)', async ({ request }) => {
    // SAML token conversion is disabled - signature verification not implemented
    const samlResponse = Buffer.from(
      '<samlp:Response xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol" xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion">' +
      '<saml:Assertion>' +
      '<saml:AttributeStatement>' +
      '<saml:Attribute Name="email">' +
      '<saml:AttributeValue>nonexistent@example.com</saml:AttributeValue>' +
      '</saml:Attribute>' +
      '</saml:AttributeStatement>' +
      '</saml:Assertion>' +
      '</samlp:Response>'
    ).toString('base64');

    const response = await request.post('/core/rest/repo/bedroom/authtoken/saml/convert', {
      headers: {
        'Content-Type': 'application/json',
      },
      data: {
        saml_response: samlResponse
      }
    });

    const result = await response.json();
    expect(result.status).toBe('failure');
    expect(result.error).toBeDefined();
  });

  test('should reject SAML token with NameID (endpoint disabled)', async ({ request }) => {
    // SAML token conversion is disabled - signature verification not implemented
    const samlResponse = Buffer.from(
      '<samlp:Response xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol" xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion">' +
      '<saml:Assertion>' +
      '<saml:NameID>testuser</saml:NameID>' +
      '<saml:AttributeStatement>' +
      '<saml:Attribute Name="email">' +
      '<saml:AttributeValue>testuser@example.com</saml:AttributeValue>' +
      '</saml:Attribute>' +
      '</saml:AttributeStatement>' +
      '</saml:Assertion>' +
      '</samlp:Response>'
    ).toString('base64');

    const response = await request.post('/core/rest/repo/bedroom/authtoken/saml/convert', {
      headers: {
        'Content-Type': 'application/json',
      },
      data: {
        saml_response: samlResponse
      }
    });

    expect(response.ok()).toBeTruthy();

    const result = await response.json();
    expect(result.status).toBe('failure');
    expect(result.error).toBeDefined();
  });
});
