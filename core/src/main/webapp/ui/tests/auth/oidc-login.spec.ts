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
 * - Keycloak server running at http://localhost:8088
 * - Keycloak realm 'nemakiware' configured with OIDC client 'nemakiware-oidc-client'
 * - Test user 'testuser' with password 'password' in Keycloak
 *
 * Environment Variables:
 * - KEYCLOAK_URL: Keycloak server URL (default: http://localhost:8088)
 * - OIDC_CLIENT_ID: OIDC client ID (default: nemakiware-oidc-client)
 */

import { test, expect } from '@playwright/test';

const KEYCLOAK_URL = process.env.KEYCLOAK_URL || 'http://localhost:8088';
const OIDC_CLIENT_ID = process.env.OIDC_CLIENT_ID || 'nemakiware-oidc-client';

test.describe('NemakiWare OIDC Authentication', () => {
  test.beforeEach(async ({ page }) => {
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

    await page.waitForURL(/localhost:8088|keycloak/i, { timeout: 15000 });
    
    expect(page.url()).toContain('8088');
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

  test('should handle OIDC token conversion endpoint', async ({ page, request }) => {
    const response = await request.post('/core/rest/repo/bedroom/authtoken/oidc/convert', {
      headers: {
        'Content-Type': 'application/json',
      },
      data: {
        user_info: {
          preferred_username: 'testuser',
          email: 'testuser@example.com',
          sub: 'test-subject-id'
        }
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
