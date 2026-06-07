/**
 * Password Change E2E Tests
 *
 * Tests for:
 * 1. Admin can see password reset button in user edit modal
 * 2. Admin can reset another user's password (no old password required)
 * 3. User can access account settings page
 * 4. User can change own password (old password required)
 * 5. Wrong old password shows error
 * 6. Minimum length validation
 * 7. Changed password works for login
 *
 * Prerequisites:
 * - NemakiWare core running
 * - admin:admin account
 * - api-e2e-admintest account with password 'testtest' (created by global-setup.ts)
 */

import { test, expect } from '@playwright/test';

import { waitForRender, waitForUiStable } from '../utils/wait-helpers';
test.describe.configure({ mode: 'serial' });

const BASE_URL = 'http://localhost:8080';
const UI_URL = `${BASE_URL}/core/ui`;
const REST_BASE = `${BASE_URL}/core/rest/repo/bedroom`;
const ADMIN_AUTH = 'Basic ' + Buffer.from('admin:admin').toString('base64');
const REST_CSRF = { 'X-Requested-With': 'XMLHttpRequest' as const };
const TEST_USER_ID = 'api-e2e-admintest';

async function loginAsUser(page: any, username: string, password: string) {
  await page.goto(`${UI_URL}/`);
  await page.waitForSelector('input[placeholder*="ユーザー"], input[placeholder*="User"]', { timeout: 15000 });
  await page.fill('input[placeholder*="ユーザー"], input[placeholder*="User"]', '');
  await page.fill('input[placeholder*="ユーザー"], input[placeholder*="User"]', username);
  await page.fill('input[type="password"]', '');
  await page.fill('input[type="password"]', password);
  await page.click('button[type="submit"], button:has-text("ログイン"), button:has-text("Login")');
  await page.waitForURL(/\/#\/documents/, { timeout: 45000 });
}

test.describe('Password Change', () => {

  // Track the current password of the test user for sequential tests
  let currentTestUserPassword = 'testtest';

  test.beforeAll(async ({ request }) => {
    // Ensure test user exists (global-setup may have failed or Docker was reset)
    const checkRes = await request.get(`${REST_BASE}/user/show/${TEST_USER_ID}`, {
      headers: { 'Authorization': ADMIN_AUTH },
    });
    const checkData = await checkRes.json();
    if (checkData.status !== 'success' || !checkData.user) {
      const createRes = await request.post(`${REST_BASE}/user/create/${TEST_USER_ID}`, {
        headers: {
          'Authorization': ADMIN_AUTH,
          'Content-Type': 'application/x-www-form-urlencoded',
          ...REST_CSRF,
        },
        data: new URLSearchParams({ name: TEST_USER_ID, password: 'testtest' }).toString(),
      });
      expect(createRes.status()).toBe(200);
    }
  });

  test.afterAll(async ({ request }) => {
    // Reset test user password back to 'test' via admin
    const formData = new URLSearchParams();
    formData.append('oldPassword', ''); // Admin bypass
    formData.append('newPassword', 'testtest');
    await request.fetch(`${REST_BASE}/user/changePassword/${TEST_USER_ID}`, {
      method: 'PUT',
      headers: {
        'Authorization': ADMIN_AUTH,
        'Content-Type': 'application/x-www-form-urlencoded',
        ...REST_CSRF,
      },
      data: formData.toString(),
    });
  });

  test('admin sees password reset section in user edit modal', async ({ page }) => {
    await loginAsUser(page, 'admin', 'admin');
    await page.goto(`${UI_URL}/#/users`);
    // Wait for page to stabilize - re-navigate if redirected (session timing issue)
    await waitForUiStable(page);
    if (!page.url().includes('/users')) {
      await page.goto(`${UI_URL}/#/users`);
      await waitForUiStable(page);
    }
    await page.waitForSelector('.ant-table', { timeout: 15000 });

    // Search for test user to handle pagination
    const searchInput = page.locator('input[placeholder*="検索"], input[placeholder*="search"], input[placeholder*="Search"], .ant-input-search input');
    if (await searchInput.count() > 0) {
      await searchInput.first().fill(TEST_USER_ID);
      await searchInput.first().press('Enter');
      await waitForUiStable(page);
    }

    // Find test user row and click edit (retry search if CouchDB view not yet updated)
    const testUserRow = page.locator('.ant-table-row').filter({ hasText: TEST_USER_ID });
    for (let retry = 0; retry < 3; retry++) {
      if (await testUserRow.isVisible()) break;
      if (retry < 2) {
        await waitForUiStable(page);
        if (await searchInput.count() > 0) {
          await searchInput.first().clear();
          await searchInput.first().fill(TEST_USER_ID);
          await searchInput.first().press('Enter');
          await waitForUiStable(page);
        }
      }
    }
    await expect(testUserRow).toBeVisible({ timeout: 10000 });
    const editButton = testUserRow.locator('button').filter({ has: page.locator('.anticon-edit, [aria-label="edit"]') });
    if (await editButton.count() > 0) {
      await editButton.first().click();
    } else {
      await testUserRow.locator('button').filter({ hasText: /編集|Edit/i }).first().click();
    }

    // Wait for modal
    await page.waitForSelector('.ant-modal', { timeout: 5000 });

    // Should see password reset section
    const passwordSection = page.locator('.ant-modal').locator('text=/パスワードリセット|Password Reset/i');
    await expect(passwordSection).toBeVisible({ timeout: 5000 });

    // Close modal
    await page.locator('.ant-modal .ant-modal-close').click();
  });

  test('admin can reset user password without old password', async ({ request }) => {
    // Use the API directly: admin changes another user's password
    const formData = new URLSearchParams();
    formData.append('newPassword', 'newpass123');

    const response = await request.fetch(`${REST_BASE}/user/changePassword/${TEST_USER_ID}`, {
      method: 'PUT',
      headers: {
        'Authorization': ADMIN_AUTH,
        'Content-Type': 'application/x-www-form-urlencoded',
        ...REST_CSRF,
      },
      data: formData.toString(),
    });

    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.status).toBe('success');

    // Verify: login with new password
    const testAuth = 'Basic ' + Buffer.from(`${TEST_USER_ID}:newpass123`).toString('base64');
    const meResponse = await request.get(`${REST_BASE}/user/me`, {
      headers: { 'Authorization': testAuth },
    });
    expect(meResponse.status()).toBe(200);

    currentTestUserPassword = 'newpass123';
  });

  test('user can access account settings page', async ({ page }) => {
    await loginAsUser(page, TEST_USER_ID, currentTestUserPassword);

    // Wait for auth state to fully load before navigating
    await waitForUiStable(page);
    // Navigate to account settings
    await page.goto(`${UI_URL}/#/account`);
    // Retry navigation if redirected (auth state race condition)
    for (let retry = 0; retry < 3; retry++) {
      await waitForUiStable(page);
      if (page.url().includes('/account')) break;
      console.log(`password-change: Retrying navigation to /account (attempt ${retry + 2})`);
      await page.goto(`${UI_URL}/#/account`);
    }

    // Click on the password tab (default tab is 'profile')
    const passwordTab = page.locator('.ant-tabs-tab').filter({ hasText: /パスワード|Password/i });
    if (await passwordTab.count() > 0) {
      await passwordTab.first().click();
      await waitForRender(page);
    }

    // Should see the password change form (button or heading)
    const changeButton = page.getByRole('button', { name: /パスワードを変更|Change Password/i });
    const passwordLabel = page.locator('text=/現在のパスワード|Current Password/i');
    const isButtonVisible = await changeButton.isVisible().catch(() => false);
    const isLabelVisible = await passwordLabel.isVisible().catch(() => false);
    expect(isButtonVisible || isLabelVisible).toBe(true);
  });

  test('user can change own password with correct old password', async ({ request }) => {
    const testAuth = 'Basic ' + Buffer.from(`${TEST_USER_ID}:${currentTestUserPassword}`).toString('base64');

    const formData = new URLSearchParams();
    formData.append('oldPassword', currentTestUserPassword);
    formData.append('newPassword', 'changed456');

    const response = await request.fetch(`${REST_BASE}/user/changePassword/${TEST_USER_ID}`, {
      method: 'PUT',
      headers: {
        'Authorization': testAuth,
        'Content-Type': 'application/x-www-form-urlencoded',
        ...REST_CSRF,
      },
      data: formData.toString(),
    });

    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.status).toBe('success');

    currentTestUserPassword = 'changed456';
  });

  test('wrong old password returns error', async ({ request }) => {
    const testAuth = 'Basic ' + Buffer.from(`${TEST_USER_ID}:${currentTestUserPassword}`).toString('base64');

    const formData = new URLSearchParams();
    formData.append('oldPassword', 'wrongpassword');
    formData.append('newPassword', 'shouldnotwork');

    const response = await request.fetch(`${REST_BASE}/user/changePassword/${TEST_USER_ID}`, {
      method: 'PUT',
      headers: {
        'Authorization': testAuth,
        'Content-Type': 'application/x-www-form-urlencoded',
        ...REST_CSRF,
      },
      data: formData.toString(),
    });

    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.status).toBe('failure');
  });

  test('minimum password length validation', async ({ request }) => {
    // Set password policy to require minimum 8 characters
    const policyApiBase = `${BASE_URL}/core/api/v1/cmis/repositories/bedroom/config/password-policy`;
    const setPolicyRes = await request.fetch(policyApiBase, {
      method: 'PUT',
      headers: {
        'Authorization': ADMIN_AUTH,
        'Content-Type': 'application/json',
        ...REST_CSRF,
      },
      data: JSON.stringify({ minLength: 8 }),
    });
    expect(setPolicyRes.ok()).toBeTruthy();

    try {
      const testAuth = 'Basic ' + Buffer.from(`${TEST_USER_ID}:${currentTestUserPassword}`).toString('base64');

      const formData = new URLSearchParams();
      formData.append('oldPassword', currentTestUserPassword);
      formData.append('newPassword', 'short'); // Less than 8 characters

      const response = await request.fetch(`${REST_BASE}/user/changePassword/${TEST_USER_ID}`, {
        method: 'PUT',
        headers: {
          'Authorization': testAuth,
          'Content-Type': 'application/x-www-form-urlencoded',
          ...REST_CSRF,
        },
        data: formData.toString(),
      });

      expect(response.status()).toBe(200);
      const data = await response.json();
      expect(data.status).toBe('failure');
    } finally {
      // Reset policy to no constraint
      await request.fetch(policyApiBase, {
        method: 'PUT',
        headers: {
          'Authorization': ADMIN_AUTH,
          'Content-Type': 'application/json',
          ...REST_CSRF,
        },
        data: JSON.stringify({ minLength: 0 }),
      });
    }
  });

  test('changed password works for login', async ({ page }) => {
    await loginAsUser(page, TEST_USER_ID, currentTestUserPassword);
    // If we get here, login succeeded
    expect(page.url()).toContain('/documents');
  });
});
