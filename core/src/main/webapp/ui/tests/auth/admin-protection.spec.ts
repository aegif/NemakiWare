/**
 * Admin Route Protection Tests
 *
 * These tests verify that:
 * 1. Non-admin users cannot see the admin menu
 * 2. Non-admin users are redirected when accessing admin routes directly
 * 3. Admin users can access all admin features
 *
 * Prerequisites:
 * - Keycloak running with testuser (password: 'password', role: 'user')
 * - NemakiWare core running
 */

import { test, expect } from '@playwright/test';

// CRITICAL: Run tests serially to avoid parallel login conflicts
test.describe.configure({ mode: 'serial' });

const BASE_URL = 'http://localhost:8080/core/ui';
const ADMIN_ROUTES = ['/users', '/groups', '/types', '/archive'];

/**
 * Helper to login via basic auth with improved reliability
 */
async function loginAsUser(page: any, username: string, password: string) {
  // Navigate to login page
  await page.goto(`${BASE_URL}/`);

  // Wait for login form with retry
  await page.waitForSelector('input[placeholder*="ユーザー"]', { timeout: 15000 });

  // Clear any existing values and fill login form
  await page.fill('input[placeholder*="ユーザー"]', '');
  await page.fill('input[placeholder*="ユーザー"]', username);
  await page.fill('input[type="password"]', '');
  await page.fill('input[type="password"]', password);

  // Click login button
  await page.click('button:has-text("ログイン")');

  // Wait for navigation to documents page with increased timeout
  await page.waitForURL(/\/#\/documents/, { timeout: 45000 });
}

test.describe('Admin Route Protection', () => {
  test.describe('Non-admin user (testuser)', () => {
    test.beforeEach(async ({ page }) => {
      // Login as non-admin user
      await loginAsUser(page, 'testuser', 'test');
    });

    test('should not see admin menu in sidebar', async ({ page }) => {
      // Wait for sidebar to load
      await page.waitForSelector('[class*="ant-menu"]', { timeout: 10000 });

      // Check that admin menu is NOT visible
      const adminMenu = page.locator('span:has-text("管理")');
      await expect(adminMenu).not.toBeVisible();

      // Verify user can see regular menus
      const documentsMenu = page.locator('span:has-text("ドキュメント")');
      await expect(documentsMenu).toBeVisible();

      const searchMenu = page.locator('span:has-text("検索")');
      await expect(searchMenu).toBeVisible();
    });

    test('should be redirected when accessing /users directly', async ({ page }) => {
      // Try to navigate directly to admin route
      await page.goto(`${BASE_URL}/#/users`);

      // Should be redirected to documents
      await page.waitForURL(/\/#\/documents/, { timeout: 10000 });
      expect(page.url()).toContain('/documents');
    });

    test('should be redirected when accessing /groups directly', async ({ page }) => {
      await page.goto(`${BASE_URL}/#/groups`);
      await page.waitForURL(/\/#\/documents/, { timeout: 10000 });
      expect(page.url()).toContain('/documents');
    });

    test('should be redirected when accessing /types directly', async ({ page }) => {
      await page.goto(`${BASE_URL}/#/types`);
      await page.waitForURL(/\/#\/documents/, { timeout: 10000 });
      expect(page.url()).toContain('/documents');
    });

    test('should be redirected when accessing /archive directly', async ({ page }) => {
      await page.goto(`${BASE_URL}/#/archive`);
      await page.waitForURL(/\/#\/documents/, { timeout: 10000 });
      expect(page.url()).toContain('/documents');
    });
  });

  test.describe('Admin user', () => {
    test.beforeEach(async ({ page }) => {
      // Login as admin user
      await loginAsUser(page, 'admin', 'admin');
    });

    test('should see admin menu in sidebar', async ({ page }) => {
      // Wait for sidebar to load
      await page.waitForSelector('[class*="ant-menu"]', { timeout: 10000 });

      // Check that admin menu IS visible
      const adminMenu = page.locator('span:has-text("管理")');
      await expect(adminMenu).toBeVisible();
    });

    test('should be able to access /users', async ({ page }) => {
      await page.goto(`${BASE_URL}/#/users`);

      // Should stay on users page (not redirected)
      await page.waitForTimeout(2000);
      expect(page.url()).toContain('/users');

      // Wait for page to load (table or loading indicator)
      await page.waitForSelector('[class*="ant-spin"], [class*="ant-table"]', { timeout: 15000 });
    });

    test('should be able to access /groups', async ({ page }) => {
      await page.goto(`${BASE_URL}/#/groups`);
      await page.waitForTimeout(2000);
      expect(page.url()).toContain('/groups');
    });

    test('should be able to access /types', async ({ page }) => {
      await page.goto(`${BASE_URL}/#/types`);
      await page.waitForTimeout(2000);
      expect(page.url()).toContain('/types');
    });

    test('should be able to access /archive', async ({ page }) => {
      await page.goto(`${BASE_URL}/#/archive`);
      await page.waitForTimeout(2000);
      expect(page.url()).toContain('/archive');
    });

    test('should see all admin submenu items when clicking admin menu', async ({ page }) => {
      // Click on admin menu to expand
      await page.click('span:has-text("管理")');

      // Wait for submenu to expand
      await page.waitForTimeout(500);

      // Check all submenu items are visible
      await expect(page.locator('span:has-text("ユーザー管理")')).toBeVisible();
      await expect(page.locator('span:has-text("グループ管理")')).toBeVisible();
      await expect(page.locator('span:has-text("タイプ管理")')).toBeVisible();
      await expect(page.locator('span:has-text("アーカイブ")')).toBeVisible();
    });
  });
});

test.describe('Permission Management Access', () => {
  // Permission management should be accessible to both admin and non-admin
  // because users need to manage permissions on their own documents

  test.skip('non-admin user can access permission page for their document', async ({ page }) => {
    // SKIPPED: This test requires a valid document objectId that testuser has access to.
    // The permission management page redirects to documents if the objectId doesn't exist.
    // To properly test this, we would need to:
    // 1. Create a document as admin
    // 2. Grant testuser permissions
    // 3. Login as testuser and access the permissions page

    await loginAsUser(page, 'testuser', 'test');
    await page.goto(`${BASE_URL}/#/permissions/valid-object-id`);
    await page.waitForTimeout(2000);
    expect(page.url()).toContain('/permissions');
  });
});
