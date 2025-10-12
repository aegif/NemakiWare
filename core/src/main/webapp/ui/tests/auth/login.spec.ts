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

    await page.goto('/core/ui/dist/index.html');

    // Check page title
    await expect(page).toHaveTitle(/NemakiWare|CMIS/);

    // Verify username field is present (try multiple selectors)
    const usernameField = page.locator('input[type="text"], input[name="username"], input[placeholder="ユーザー名"]').first();
    await expect(usernameField).toBeVisible();

    // Verify password field is present
    await expect(page.locator('input[type="password"]')).toBeVisible();

    // Verify login button is present
    const loginButton = page.locator('button:has-text("ログイン")').first();
    await expect(loginButton).toBeVisible();

    // Check for repository selection if available
    const repositorySelect = page.locator('.ant-select');
    if (await repositorySelect.count() > 0) {
      await expect(repositorySelect.first()).toBeVisible();
    }

    // Verify no JavaScript errors
    const jsErrors = await testHelper.checkForJSErrors();
    expect(jsErrors).toHaveLength(0);
  });

  test('should successfully login with valid credentials', async ({ page, browserName }) => {
    const authHelper = new AuthHelper(page);
    const testHelper = new TestHelper(page);

    await authHelper.login();

    // Verify successful login by checking URL
    expect(page.url()).toContain('/ui/dist/');

    // Verify we're no longer on login page
    await expect(page.locator('input[type="password"]')).not.toBeVisible();

    // Wait for main UI to load
    await testHelper.waitForAntdLoad();

    // Verify main application layout is present
    await expect(page.locator('.ant-layout-sider')).toBeVisible({ timeout: 10000 });
    await expect(page.locator('.ant-layout-content')).toBeVisible({ timeout: 10000 });

    // MOBILE FIX: Close sidebar on mobile to access header elements
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobile) {
      const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]');
      if (await menuToggle.count() > 0) {
        try {
          await menuToggle.first().click({ timeout: 3000 });
          await page.waitForTimeout(500);
        } catch (error) {
          // Continue even if sidebar close fails
        }
      }
    }

    // Verify user is shown in header (admin)
    const userDisplay = page.locator('.ant-layout-header').locator('text=admin');
    await expect(userDisplay).toBeVisible({ timeout: 5000 });

    // Verify repository is shown
    const repoDisplay = page.locator('.ant-layout-header').locator('text=bedroom');
    await expect(repoDisplay).toBeVisible({ timeout: 5000 });
  });

  test('should fail login with invalid credentials', async ({ page }) => {
    await page.goto('/core/ui/dist/index.html');

    // Wait for login form
    await page.waitForSelector('input[type="password"]', { timeout: 10000 });

    // Fill username field
    const usernameField = page.locator('input[type="text"], input[name="username"]').first();
    await usernameField.fill('invalid');

    // Fill password field
    await page.locator('input[type="password"]').fill('invalid');

    // Select repository if dropdown exists
    const repositorySelect = page.locator('.ant-select').first();
    if (await repositorySelect.count() > 0) {
      await repositorySelect.click();
      await page.waitForSelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)', { timeout: 5000 });

      const option = page.locator('.ant-select-dropdown .ant-select-item-option').filter({ hasText: 'bedroom' }).first();
      await option.waitFor({ state: 'attached', timeout: 3000 });
      await option.scrollIntoViewIfNeeded();
      await page.waitForTimeout(300);
      await option.click();
    }

    // Click login button
    const loginButton = page.locator('button:has-text("ログイン")').first();
    await loginButton.click();

    // Wait for potential error message
    await page.waitForTimeout(3000);

    // Verify we're still on login page
    await expect(page.locator('input[type="password"]')).toBeVisible();

    // Look for error message using Ant Design alert
    const stillOnLoginPage = await page.locator('input[type="password"]').isVisible();

    // Either error message should be shown or we should still be on login page
    expect(stillOnLoginPage).toBe(true);
  });

  test('should handle empty credentials', async ({ page }) => {
    await page.goto('/core/ui/dist/index.html');

    // Wait for form to load
    await page.waitForSelector('input[type="password"]', { timeout: 10000 });

    // Try to login with empty credentials
    const loginButton = page.locator('button:has-text("ログイン")').first();
    await loginButton.click();

    // Should remain on login page
    await expect(page.locator('input[type="password"]')).toBeVisible();

    // Form validation might show error messages (Ant Design validation)
    // We don't strictly require validation errors, but form should not submit
  });

  test('should logout successfully', async ({ page, browserName }) => {
    const authHelper = new AuthHelper(page);

    // First login
    await authHelper.login();

    // Verify logged in
    expect(await authHelper.isLoggedIn()).toBe(true);

    // MOBILE FIX: Close sidebar before logout to access header menu
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobile) {
      const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]');
      if (await menuToggle.count() > 0) {
        try {
          await menuToggle.first().click({ timeout: 3000 });
          await page.waitForTimeout(500);
        } catch (error) {
          // Continue even if sidebar close fails
        }
      }
    }

    // Perform logout
    await authHelper.logout();

    // Verify logged out
    await expect(page.locator('input[type="password"]')).toBeVisible();
    expect(await authHelper.isLoggedIn()).toBe(false);
  });

  test('should maintain session on page refresh', async ({ page, browserName }) => {
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
    await page.goto('/core/ui/dist/index.html#/documents');

    // Should redirect to login or show login form
    await page.waitForTimeout(2000);

    const onLoginPage = await page.locator('input[type="password"]').isVisible();
    const redirectedToLogin = page.url().includes('login') || page.url().endsWith('/ui/dist/');

    expect(onLoginPage || redirectedToLogin).toBe(true);
  });
});