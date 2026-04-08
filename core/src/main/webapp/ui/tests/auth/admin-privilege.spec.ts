/**
 * Admin Privilege Management E2E Tests
 *
 * Tests for:
 * 1. /me endpoint returning isAdmin flag
 * 2. Admin badge display in user management table
 * 3. Granting admin privilege to another user
 * 4. Revoking admin privilege from another user
 * 5. Cannot revoke own admin privilege
 * 6. Granted user sees admin menu
 *
 * Prerequisites:
 * - NemakiWare core running
 * - admin:admin account
 * - api-e2e-admintest account with password 'testtest' (created by global-setup.ts)
 */

import { test, expect } from '@playwright/test';

test.describe.configure({ mode: 'serial' });

const BASE_URL = 'http://localhost:8080';
const UI_URL = `${BASE_URL}/core/ui`;
const REST_BASE = `${BASE_URL}/core/rest/repo/bedroom`;
const ADMIN_AUTH = 'Basic ' + Buffer.from('admin:admin').toString('base64');
const REST_CSRF = { 'X-Requested-With': 'XMLHttpRequest' as const };
const TEST_USER_ID = 'api-e2e-admintest';
const TEST_USER_AUTH = 'Basic ' + Buffer.from(`${TEST_USER_ID}:testtest`).toString('base64');

async function loginAsUser(page: any, username: string, password: string) {
  for (let attempt = 1; attempt <= 3; attempt++) {
    await page.goto(`${UI_URL}/`);
    await page.waitForSelector('input[placeholder*="ユーザー"], input[placeholder*="User"]', { timeout: 15000 });
    // Use keyboard input for reliable Ant Design form interaction
    const usernameInput = page.locator('input[placeholder*="ユーザー"], input[placeholder*="User"]');
    await usernameInput.click();
    await page.keyboard.press('Control+a');
    await page.keyboard.type(username);
    const passwordInput = page.locator('input[type="password"]');
    await passwordInput.click();
    await page.keyboard.press('Control+a');
    await page.keyboard.type(password);
    await page.keyboard.press('Enter');
    try {
      await page.waitForURL(/\/#\/documents/, { timeout: 45000 });
      return; // Login succeeded
    } catch (e) {
      if (attempt < 3) {
        console.log(`loginAsUser: Attempt ${attempt} failed for ${username}, retrying...`);
        continue;
      }
      throw e;
    }
  }
}

test.describe('Admin Privilege Management', () => {

  test.afterAll(async ({ request }) => {
    // Cleanup: ensure api-e2e-admintest is NOT admin after tests
    const formData = new URLSearchParams();
    formData.append('isAdmin', 'false');
    formData.append('name', TEST_USER_ID);
    await request.fetch(`${REST_BASE}/user/update/${TEST_USER_ID}`, {
      method: 'PUT',
      headers: {
        'Authorization': ADMIN_AUTH,
        'Content-Type': 'application/x-www-form-urlencoded',
        ...REST_CSRF,
      },
      data: formData.toString(),
    });
  });

  test('/me endpoint returns isAdmin=true for admin', async ({ request }) => {
    const response = await request.get(`${REST_BASE}/user/me`, {
      headers: { 'Authorization': ADMIN_AUTH },
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.status).toBe('success');
    expect(data.user).toBeDefined();
    expect(data.user.isAdmin).toBe(true);
    expect(data.user.userId).toBe('admin');
  });

  test('/me endpoint returns isAdmin=false for regular user', async ({ request }) => {
    const response = await request.get(`${REST_BASE}/user/me`, {
      headers: { 'Authorization': TEST_USER_AUTH },
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.status).toBe('success');
    expect(data.user).toBeDefined();
    expect(data.user.isAdmin).toBe(false);
    expect(data.user.userId).toBe(TEST_USER_ID);
  });

  test('admin sees admin column in user management table', async ({ page }) => {
    await loginAsUser(page, 'admin', 'admin');
    await page.goto(`${UI_URL}/#/users`);
    await page.waitForSelector('.ant-table', { timeout: 15000 });

    // Check that admin column exists in the table header
    const adminColumnHeader = page.locator('.ant-table-thead th').filter({ hasText: /管理者|Admin/i });
    await expect(adminColumnHeader).toBeVisible();
  });

  test('admin can grant admin privilege to another user', async ({ page, request }) => {
    test.setTimeout(90000);
    await loginAsUser(page, 'admin', 'admin');
    // Wait for auth state (isAdmin) to fully load before navigating to admin route
    await page.waitForTimeout(3000);
    await page.goto(`${UI_URL}/#/users`);
    // Retry navigation if redirected away (AdminRoute race condition with isAdmin loading)
    for (let retry = 0; retry < 3; retry++) {
      await page.waitForTimeout(2000);
      if (page.url().includes('/users')) break;
      console.log(`admin-privilege: Retrying navigation to /users (attempt ${retry + 2})`);
      await page.goto(`${UI_URL}/#/users`);
    }
    await page.waitForSelector('.ant-table', { timeout: 15000 });
    // Wait for table rows to be fully rendered and interactive
    await page.waitForTimeout(2000);

    // Search for test user to handle pagination
    const searchInput = page.locator('input[placeholder*="検索"], input[placeholder*="search"], .ant-input-search input');
    if (await searchInput.count() > 0) {
      await searchInput.first().fill(TEST_USER_ID);
      await page.waitForTimeout(1000);
    }

    // Find the test user row and click edit
    const testUserRow = page.locator('tr').filter({ hasText: TEST_USER_ID });
    await expect(testUserRow.first()).toBeVisible({ timeout: 10000 });
    const editButton = testUserRow.first().locator('button').filter({ has: page.locator('.anticon-edit, [aria-label="edit"]') });
    if (await editButton.count() > 0) {
      await editButton.first().click();
    } else {
      await testUserRow.first().locator('button').filter({ hasText: /編集|Edit/i }).first().click();
    }

    // Wait for modal to open
    await page.waitForSelector('.ant-modal', { timeout: 5000 });

    // Find and enable the admin switch
    const adminFormItem = page.locator('.ant-modal .ant-form-item').filter({ hasText: /管理者|Administrator/i });
    const switchInAdminItem = adminFormItem.locator('.ant-switch');
    await switchInAdminItem.click();

    // Submit the form and wait for API response
    const responsePromise = page.waitForResponse(resp =>
      resp.url().includes('/user/update/') && resp.status() === 200);
    await page.locator('.ant-modal').getByRole('button', { name: /^保\s*存$|^更\s*新$|^Save$|^Update$/i }).click();
    await responsePromise;

    // Wait for modal to close
    await page.waitForSelector('.ant-modal', { state: 'hidden', timeout: 10000 });

    // Verify via API that the user is now admin (use request fixture to avoid browser session cookies)
    const response = await request.get(`${REST_BASE}/user/me`, {
      headers: { 'Authorization': TEST_USER_AUTH },
    });
    const data = await response.json();
    expect(data.user.isAdmin).toBe(true);
  });

  test('admin can revoke admin privilege from another user', async ({ page, request }) => {
    await loginAsUser(page, 'admin', 'admin');
    // Wait for auth state (isAdmin) to fully load before navigating to admin route
    await page.waitForTimeout(3000);
    await page.goto(`${UI_URL}/#/users`);
    // Retry navigation if redirected away (AdminRoute race condition with isAdmin loading)
    for (let retry = 0; retry < 3; retry++) {
      await page.waitForTimeout(2000);
      if (page.url().includes('/users')) break;
      console.log(`admin-privilege: Retrying navigation to /users (attempt ${retry + 2})`);
      await page.goto(`${UI_URL}/#/users`);
    }
    await page.waitForSelector('.ant-table', { timeout: 15000 });

    // Search for test user to handle pagination
    const searchInput = page.locator('input[placeholder*="検索"], input[placeholder*="search"], .ant-input-search input');
    if (await searchInput.count() > 0) {
      await searchInput.first().fill(TEST_USER_ID);
      await page.waitForTimeout(1000);
    }

    // Find the test user row and click edit
    const testUserRow = page.locator('.ant-table-row').filter({ hasText: TEST_USER_ID });
    const editButton = testUserRow.locator('button').filter({ has: page.locator('.anticon-edit, [aria-label="edit"]') });
    if (await editButton.count() > 0) {
      await editButton.first().click();
    } else {
      await testUserRow.locator('button').filter({ hasText: /編集|Edit/i }).first().click();
    }

    // Wait for modal to open
    await page.waitForSelector('.ant-modal', { timeout: 5000 });

    // Find the admin switch and disable it
    const adminFormItem = page.locator('.ant-modal .ant-form-item').filter({ hasText: /管理者|Administrator/i });
    const switchInAdminItem = adminFormItem.locator('.ant-switch');
    await switchInAdminItem.click();

    // Submit the form and wait for API response
    const responsePromise = page.waitForResponse(resp =>
      resp.url().includes('/user/update/') && resp.status() === 200);
    await page.locator('.ant-modal').getByRole('button', { name: /^保\s*存$|^更\s*新$|^Save$|^Update$/i }).click();
    await responsePromise;

    // Wait for modal to close
    await page.waitForSelector('.ant-modal', { state: 'hidden', timeout: 10000 });

    // Verify via API (use request fixture to avoid browser session cookies)
    const response = await request.get(`${REST_BASE}/user/me`, {
      headers: { 'Authorization': TEST_USER_AUTH },
    });
    const data = await response.json();
    expect(data.user.isAdmin).toBe(false);
  });

  test('admin cannot revoke own admin privilege', async ({ page }) => {
    await loginAsUser(page, 'admin', 'admin');
    // Wait for auth state (isAdmin) to fully load before navigating to admin route
    await page.waitForTimeout(3000);
    await page.goto(`${UI_URL}/#/users`);
    // Retry navigation if redirected away (AdminRoute race condition with isAdmin loading)
    for (let retry = 0; retry < 3; retry++) {
      await page.waitForTimeout(2000);
      if (page.url().includes('/users')) break;
      console.log(`admin-privilege: Retrying navigation to /users (attempt ${retry + 2})`);
      await page.goto(`${UI_URL}/#/users`);
    }
    await page.waitForSelector('.ant-table', { timeout: 15000 });

    // Search for admin user to isolate the row
    const searchInput = page.locator('input[placeholder*="検索"], input[placeholder*="search"], .ant-input-search input');
    if (await searchInput.count() > 0) {
      await searchInput.first().fill('admin');
      await page.waitForTimeout(1000);
    }

    // Find the admin user row (first cell contains exactly "admin")
    const adminRow = page.locator('.ant-table-row').filter({ hasText: /^admin/ }).first();
    await expect(adminRow).toBeVisible({ timeout: 5000 });

    // Click edit button
    const editButton = adminRow.locator('button').filter({ has: page.locator('.anticon-edit, [aria-label="edit"]') });
    if (await editButton.count() > 0) {
      await editButton.first().click();
    } else {
      await adminRow.locator('button').filter({ hasText: /編集|Edit/i }).click();
    }

    // Wait for modal to open
    await page.waitForSelector('.ant-modal', { timeout: 5000 });

    // The admin switch should be disabled for self
    const adminFormItem = page.locator('.ant-modal .ant-form-item').filter({ hasText: /管理者|Administrator/i });
    const switchInAdminItem = adminFormItem.locator('.ant-switch');
    await expect(switchInAdminItem).toHaveClass(/ant-switch-disabled/);

    // Close modal
    await page.locator('.ant-modal .ant-modal-close').click();
  });

  test('granted admin user sees admin menu', async ({ page, request }) => {
    // First grant admin to test user via API
    const formData = new URLSearchParams();
    formData.append('isAdmin', 'true');
    formData.append('name', TEST_USER_ID);
    await request.fetch(`${REST_BASE}/user/update/${TEST_USER_ID}`, {
      method: 'PUT',
      headers: {
        'Authorization': ADMIN_AUTH,
        'Content-Type': 'application/x-www-form-urlencoded',
        ...REST_CSRF,
      },
      data: formData.toString(),
    });

    // Login as the test user
    await loginAsUser(page, TEST_USER_ID, 'testtest');

    // Wait for sidebar to load
    await page.waitForSelector('[class*="ant-menu"]', { timeout: 10000 });

    // Admin menu should be visible
    const menu = page.locator('[class*="ant-menu"]');
    const adminMenu = menu.getByText(/管理|Admin/, { exact: true });
    await expect(adminMenu).toBeVisible();
  });
});
