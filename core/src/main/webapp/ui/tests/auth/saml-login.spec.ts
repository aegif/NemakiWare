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
 * - Keycloak server running at http://localhost:8088
 * - Keycloak realm 'nemakiware' configured with SAML client 'nemakiware-saml-client'
 * - Test user 'testuser' with password 'password' in Keycloak
 *
 * Environment Variables:
 * - KEYCLOAK_URL: Keycloak server URL (default: http://localhost:8088)
 * - SAML_ENTITY_ID: SAML entity ID (default: nemakiware-saml-client)
 */

import { test, expect } from '@playwright/test';

const KEYCLOAK_URL = process.env.KEYCLOAK_URL || 'http://localhost:8088';
const SAML_ENTITY_ID = process.env.SAML_ENTITY_ID || 'nemakiware-saml-client';

test.describe('NemakiWare SAML Authentication', () => {
  // Serial mode: SAML tests interact with shared Keycloak session state
  test.describe.configure({ mode: 'serial' });

  test.beforeEach(async ({ page }) => {
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

    await page.waitForURL(/localhost:8088|keycloak/i, { timeout: 15000 });
    
    expect(page.url()).toContain('8088');
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

    await page.waitForURL(/localhost:8088|keycloak/i, { timeout: 15000 });

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

  test('should handle SAML token conversion endpoint with valid response', async ({ request }) => {
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
    
    const result = await response.json();
    expect(result.status).toBe('success');
    expect(result.value).toBeDefined();
    expect(result.value.userName).toBe('testuser');
    expect(result.value.token).toBeDefined();
    expect(result.value.repositoryId).toBe('bedroom');
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

  test('should reject SAML response with email attribute for non-existent user', async ({ request }) => {
    // Note: This test validates current behavior where SSO users must pre-exist in NemakiWare.
    // Auto-provisioning of SSO users is not yet implemented.
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

    // User does not exist in NemakiWare, so conversion should fail
    const result = await response.json();
    expect(result.status).toBe('failure');
    expect(result.error).toBeDefined();
  });

  test('should extract username from SAML response with email attribute for existing user', async ({ request }) => {
    // This test uses 'testuser' which exists in both Keycloak and NemakiWare (created via previous tests)
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
    expect(result.status).toBe('success');
    // NameID takes precedence over email attribute
    expect(result.value.userName).toBe('testuser');
  });
});
