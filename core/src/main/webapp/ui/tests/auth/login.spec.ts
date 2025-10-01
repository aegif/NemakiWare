import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';

test.describe('NemakiWare Authentication', () => {
  test.beforeEach(async ({ page }) => {
    // Start with a clean session
    await page.context().clearCookies();
    await page.context().clearPermissions();
  });

  test('should display login page correctly', async ({ page }) => {
    const testHelper = new TestHelper(page);

    await page.goto('/core/ui/dist/');

    // Check page title
    await expect(page).toHaveTitle(/NemakiWare|CMIS/);

    // Verify login form elements are present
    await expect(page.locator('input[placeholder*="admin"], input[name="username"]')).toBeVisible();
    await expect(page.locator('input[type="password"]')).toBeVisible();
    await expect(page.getByRole('button', { name: /login|ログイン/i })).toBeVisible();

    // Check for repository selection if available
    const repositorySelect = page.locator('select, .ant-select');
    if (await repositorySelect.count() > 0) {
      await expect(repositorySelect).toBeVisible();
    }

    // Verify no JavaScript errors
    const jsErrors = await testHelper.checkForJSErrors();
    expect(jsErrors).toHaveLength(0);
  });

  test('should successfully login with valid credentials', async ({ page }) => {
    const authHelper = new AuthHelper(page);
    const testHelper = new TestHelper(page);

    await authHelper.login();

    // Verify successful login by checking URL
    expect(page.url()).toContain('/ui/dist/');

    // Verify we're no longer on login page
    await expect(page.locator('input[type="password"]')).not.toBeVisible();

    // Wait for main UI to load
    await testHelper.waitForAntdLoad();

    // Look for main application elements
    const mainElements = [
      '.ant-layout-sider', // Sidebar
      '.ant-layout-content', // Main content area
      '.document-list, .folder-tree', // Document management elements
    ];

    let foundMainElement = false;
    for (const selector of mainElements) {
      if (await page.locator(selector).count() > 0) {
        foundMainElement = true;
        break;
      }
    }

    expect(foundMainElement).toBe(true);

    // Verify no network errors
    await testHelper.verifyNoNetworkErrors();
  });

  test('should fail login with invalid credentials', async ({ page }) => {
    const authHelper = new AuthHelper(page);

    await page.goto('/core/ui/dist/');

    // Try to login with invalid credentials
    await page.locator('input[placeholder*="admin"], input[name="username"]').fill('invalid');
    await page.locator('input[type="password"]').fill('invalid');

    // Select repository if dropdown exists
    const repositorySelect = page.locator('select, .ant-select');
    if (await repositorySelect.count() > 0) {
      await repositorySelect.click();
      await page.getByText('bedroom').click();
    }

    await page.getByRole('button', { name: /login|ログイン/i }).click();

    // Should remain on login page or show error
    await page.waitForTimeout(2000); // Wait for potential error message

    // Verify we're still on login page
    await expect(page.locator('input[type="password"]')).toBeVisible();

    // Look for error message (common selectors)
    const errorSelectors = [
      '.ant-message-error',
      '.error-message',
      '.alert-error',
      '[role="alert"]',
    ];

    let errorFound = false;
    for (const selector of errorSelectors) {
      if (await page.locator(selector).count() > 0) {
        errorFound = true;
        break;
      }
    }

    // Either error message should be shown or we should still be on login page
    expect(errorFound || await page.locator('input[type="password"]').isVisible()).toBe(true);
  });

  test('should handle empty credentials', async ({ page }) => {
    await page.goto('/core/ui/dist/');

    // Try to login with empty credentials
    await page.getByRole('button', { name: /login|ログイン/i }).click();

    // Should remain on login page
    await expect(page.locator('input[type="password"]')).toBeVisible();

    // Form validation might show error messages
    const validationErrors = await page.locator('.ant-form-item-explain-error, .error').count();
    // We don't strictly require validation errors, but form should not submit
  });

  test('should logout successfully', async ({ page }) => {
    const authHelper = new AuthHelper(page);

    // First login
    await authHelper.login();

    // Verify logged in
    expect(await authHelper.isLoggedIn()).toBe(true);

    // Perform logout
    await authHelper.logout();

    // Verify logged out
    await expect(page.locator('input[type="password"]')).toBeVisible();
    expect(await authHelper.isLoggedIn()).toBe(false);
  });

  test('should maintain session on page refresh', async ({ page }) => {
    const authHelper = new AuthHelper(page);

    // Login
    await authHelper.login();

    // Refresh page
    await page.reload();

    // Should still be logged in
    expect(await authHelper.isLoggedIn()).toBe(true);
    await expect(page.locator('input[type="password"]')).not.toBeVisible();
  });

  test('should redirect to login when accessing protected routes without authentication', async ({ page }) => {
    // Try to access a protected route directly
    await page.goto('/core/ui/dist/documents');

    // Should redirect to login or show login form
    await page.waitForTimeout(2000);

    const onLoginPage = await page.locator('input[type="password"]').isVisible();
    const redirectedToLogin = page.url().includes('login') || page.url().endsWith('/ui/dist/');

    expect(onLoginPage || redirectedToLogin).toBe(true);
  });
});