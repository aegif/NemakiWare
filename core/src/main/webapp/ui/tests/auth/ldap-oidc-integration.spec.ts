/**
 * LDAP User + Keycloak OIDC Authentication Integration Tests
 *
 * Test suite for authenticating LDAP-synced users via Keycloak OIDC:
 * - LDAP users can authenticate through Keycloak
 * - User information is correctly passed from LDAP → Keycloak → NemakiWare
 * - Group memberships are synchronized
 *
 * Prerequisites:
 * - OpenLDAP server running with test users
 * - Keycloak server running with LDAP User Federation configured
 * - NemakiWare server running with OIDC authentication enabled
 *
 * Start test environment:
 *   cd docker && docker compose -f docker-compose-ldap-keycloak-test.yml up -d
 *
 * Test Users (from LDAP):
 * - ldapuser1 / ldappass1 - Regular user
 * - ldapuser2 / ldappass2 - Regular user
 * - ldapadmin / ldapadminpass - Administrator
 *
 * NOTE: These tests are automatically skipped when Keycloak is not running.
 * Use the standard test environment (docker-compose-simple.yml) for basic tests.
 */

import { test, expect, Page } from '@playwright/test';
import { isKeycloakAvailable, KEYCLOAK_SKIP_MESSAGE } from '../utils/test-state';

// Skip entire file if Keycloak is not available
test.beforeEach(async ({}, testInfo) => {
  if (!isKeycloakAvailable()) {
    testInfo.skip(true, KEYCLOAK_SKIP_MESSAGE);
  }
});

const KEYCLOAK_URL = process.env.KEYCLOAK_URL || 'http://localhost:8088';
const NEMAKIWARE_URL = process.env.NEMAKIWARE_URL || 'http://localhost:8080';

// Test users from LDAP
const LDAP_USERS = {
  user1: { username: 'ldapuser1', password: 'ldappass1', email: 'ldapuser1@nemakiware.example.com' },
  user2: { username: 'ldapuser2', password: 'ldappass2', email: 'ldapuser2@nemakiware.example.com' },
  admin: { username: 'ldapadmin', password: 'ldapadminpass', email: 'ldapadmin@nemakiware.example.com' },
};

test.describe('LDAP User Authentication via Keycloak OIDC', () => {
  test.describe.configure({ mode: 'serial' });

  test.beforeEach(async ({ page }) => {
    // Clear cookies and storage to ensure clean state
    await page.context().clearCookies();
    await page.context().clearPermissions();
  });

  test('LDAP user should be able to login via Keycloak OIDC', async ({ page }) => {
    // Navigate to NemakiWare
    await page.goto(`${NEMAKIWARE_URL}/core/ui/`);

    // Wait for the app to load
    await page.waitForFunction(
      () => {
        const root = document.getElementById('root');
        return root && root.children.length > 0;
      },
      { timeout: 30000 }
    );

    // Find and click OIDC login button
    const oidcButton = page.locator('button:has-text("OIDC"), button:has-text("OpenID")').first();
    await expect(oidcButton).toBeVisible({ timeout: 10000 });
    await oidcButton.click();

    // Wait for redirect to Keycloak
    await page.waitForURL(/localhost:8088|keycloak/i, { timeout: 15000 });
    expect(page.url()).toContain('8088');

    // Login with LDAP user credentials
    const usernameField = page.locator('input[name="username"], #username').first();
    await usernameField.waitFor({ state: 'visible', timeout: 10000 });
    await usernameField.fill(LDAP_USERS.user1.username);

    const passwordField = page.locator('input[name="password"], #password').first();
    await passwordField.fill(LDAP_USERS.user1.password);

    const loginButton = page.locator('input[type="submit"], button[type="submit"], #kc-login').first();
    await loginButton.click();

    // Wait for redirect back to NemakiWare
    await page.waitForURL(/localhost:8080|core\/ui/i, { timeout: 30000 });
    expect(page.url()).toContain('8080');

    // Verify user is logged in (should see username in header)
    await page.waitForTimeout(2000);
    // The UI shows username text in header after login
    const usernameDisplay = page.locator(`text=${LDAP_USERS.user1.username}`).first();
    await expect(usernameDisplay).toBeVisible({ timeout: 10000 });
  });

  test('LDAP admin user should be able to login via Keycloak OIDC', async ({ page }) => {
    await page.goto(`${NEMAKIWARE_URL}/core/ui/`);

    await page.waitForFunction(
      () => {
        const root = document.getElementById('root');
        return root && root.children.length > 0;
      },
      { timeout: 30000 }
    );

    const oidcButton = page.locator('button:has-text("OIDC"), button:has-text("OpenID")').first();
    await oidcButton.click();

    await page.waitForURL(/localhost:8088|keycloak/i, { timeout: 15000 });

    const usernameField = page.locator('input[name="username"], #username').first();
    await usernameField.waitFor({ state: 'visible', timeout: 10000 });
    await usernameField.fill(LDAP_USERS.admin.username);

    const passwordField = page.locator('input[name="password"], #password').first();
    await passwordField.fill(LDAP_USERS.admin.password);

    const loginButton = page.locator('input[type="submit"], button[type="submit"], #kc-login').first();
    await loginButton.click();

    await page.waitForURL(/localhost:8080|core\/ui/i, { timeout: 30000 });
    expect(page.url()).toContain('8080');

    // Verify admin user is logged in
    await page.waitForTimeout(2000);
    const usernameDisplay = page.locator(`text=${LDAP_USERS.admin.username}`).first();
    await expect(usernameDisplay).toBeVisible({ timeout: 10000 });
  });

  test('Invalid LDAP credentials should fail authentication', async ({ page }) => {
    await page.goto(`${NEMAKIWARE_URL}/core/ui/`);

    await page.waitForFunction(
      () => {
        const root = document.getElementById('root');
        return root && root.children.length > 0;
      },
      { timeout: 30000 }
    );

    const oidcButton = page.locator('button:has-text("OIDC"), button:has-text("OpenID")').first();
    await oidcButton.click();

    await page.waitForURL(/localhost:8088|keycloak/i, { timeout: 15000 });

    const usernameField = page.locator('input[name="username"], #username').first();
    await usernameField.waitFor({ state: 'visible', timeout: 10000 });
    await usernameField.fill(LDAP_USERS.user1.username);

    const passwordField = page.locator('input[name="password"], #password').first();
    await passwordField.fill('wrongpassword');

    const loginButton = page.locator('input[type="submit"], button[type="submit"], #kc-login').first();
    await loginButton.click();

    // Should stay on Keycloak with error message
    await page.waitForTimeout(2000);
    const errorMessage = page.locator('.alert-error, .kc-feedback-text, [class*="error"]').first();
    await expect(errorMessage).toBeVisible({ timeout: 5000 });

    // Should still be on Keycloak login page
    expect(page.url()).toContain('8088');
  });
});

test.describe('LDAP User Sync + OIDC Token Conversion', () => {
  test('OIDC token conversion should create NemakiWare session for LDAP user', async ({ request }) => {
    // Simulate OIDC callback with LDAP user info
    const response = await request.post(`${NEMAKIWARE_URL}/core/rest/repo/bedroom/authtoken/oidc/convert`, {
      headers: {
        'Content-Type': 'application/json',
      },
      data: {
        user_info: {
          preferred_username: LDAP_USERS.user1.username,
          email: LDAP_USERS.user1.email,
          sub: `ldap-${LDAP_USERS.user1.username}`,
          given_name: 'LDAP User',
          family_name: 'One'
        }
      }
    });

    expect(response.ok()).toBeTruthy();

    const result = await response.json();
    expect(result.status).toBe('success');
    expect(result.value).toBeDefined();
    expect(result.value.userName).toBe(LDAP_USERS.user1.username);
    expect(result.value.token).toBeDefined();
    expect(result.value.repositoryId).toBe('bedroom');
  });

  test('OIDC token conversion should work for LDAP admin user', async ({ request }) => {
    const response = await request.post(`${NEMAKIWARE_URL}/core/rest/repo/bedroom/authtoken/oidc/convert`, {
      headers: {
        'Content-Type': 'application/json',
      },
      data: {
        user_info: {
          preferred_username: LDAP_USERS.admin.username,
          email: LDAP_USERS.admin.email,
          sub: `ldap-${LDAP_USERS.admin.username}`,
          given_name: 'LDAP',
          family_name: 'Administrator'
        }
      }
    });

    expect(response.ok()).toBeTruthy();

    const result = await response.json();
    expect(result.status).toBe('success');
    expect(result.value.userName).toBe(LDAP_USERS.admin.username);
  });
});

test.describe('LDAP Group Membership via Keycloak', () => {
  // Re-enabled (2026-01-26): LDAP sync has been run
  test('LDAP group membership should be reflected in NemakiWare (requires sync)', async ({ request }) => {
    // This test requires LDAP sync to have run first
    // Check if ldapuser1 is member of expected groups after sync

    const response = await request.get(`${NEMAKIWARE_URL}/core/rest/repo/bedroom/group/list`, {
      headers: {
        'Authorization': 'Basic ' + Buffer.from('admin:admin').toString('base64')
      }
    });

    expect(response.ok()).toBeTruthy();
    const result = await response.json();
    expect(result.status).toBe('success');
    expect(result.groups).toBeDefined();

    // Verify LDAP-synced groups exist
    const groupNames = result.groups.map((g: any) => g.groupId || g.groupName);
    expect(groupNames).toContain('ldap_nemaki-users');
  });
});

test.describe('Session Management', () => {
  test('Logout should clear NemakiWare session', async ({ page }) => {
    // First, login
    await page.goto(`${NEMAKIWARE_URL}/core/ui/`);

    await page.waitForFunction(
      () => {
        const root = document.getElementById('root');
        return root && root.children.length > 0;
      },
      { timeout: 30000 }
    );

    const oidcButton = page.locator('button:has-text("OIDC"), button:has-text("OpenID")').first();
    await oidcButton.click();

    await page.waitForURL(/localhost:8088|keycloak/i, { timeout: 15000 });

    const usernameField = page.locator('input[name="username"], #username').first();
    await usernameField.waitFor({ state: 'visible', timeout: 10000 });
    await usernameField.fill(LDAP_USERS.user1.username);

    const passwordField = page.locator('input[name="password"], #password').first();
    await passwordField.fill(LDAP_USERS.user1.password);

    const loginButton = page.locator('input[type="submit"], button[type="submit"], #kc-login').first();
    await loginButton.click();

    await page.waitForURL(/localhost:8080|core\/ui/i, { timeout: 30000 });

    // Verify login succeeded
    await page.waitForTimeout(2000);
    const usernameDisplay = page.locator(`text=${LDAP_USERS.user1.username}`).first();
    await expect(usernameDisplay).toBeVisible({ timeout: 10000 });

    // Click on username to open user menu
    await usernameDisplay.click();
    await page.waitForTimeout(500);

    // Click logout (look for Japanese or English)
    const logoutMenuItem = page.locator('text=ログアウト').or(page.locator('text=Logout')).first();
    await logoutMenuItem.click({ timeout: 5000 });

    // Wait for Keycloak logout confirmation page
    await page.waitForTimeout(1000);

    // If on Keycloak logout confirmation page, click the Logout button
    const keycloakLogoutButton = page.locator('button:has-text("Logout")');
    if (await keycloakLogoutButton.isVisible({ timeout: 3000 }).catch(() => false)) {
      await keycloakLogoutButton.click();
      await page.waitForTimeout(2000);
    }

    // Should show login form after logout (OIDC button or password input)
    // Navigate back to NemakiWare UI
    await page.goto(`${NEMAKIWARE_URL}/core/ui/`);
    await page.waitForTimeout(2000);

    const loginPage = page.locator('button:has-text("OIDC")').or(page.locator('input[type="password"]')).first();
    await expect(loginPage).toBeVisible({ timeout: 10000 });
  });
});
