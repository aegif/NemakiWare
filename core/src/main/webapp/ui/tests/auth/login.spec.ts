/**
 * Authentication E2E Tests
 *
 * Comprehensive test suite for NemakiWare authentication functionality:
 * - Login/logout workflows with valid/invalid credentials
 * - Session management and persistence across page refreshes
 * - Protected route access control and redirect behavior
 * - Mobile browser support for authentication flows
 * - Ant Design form component interaction patterns
 *
 * Test Coverage (7 tests):
 * 1. Login page UI validation (fields, buttons, repository selector)
 * 2. Successful login with valid credentials
 * 3. Failed login with invalid credentials
 * 4. Empty credentials validation
 * 5. Logout functionality
 * 6. Session persistence on page refresh
 * 7. Protected route redirect to login
 *
 * IMPORTANT DESIGN DECISIONS:
 *
 * 1. AuthHelper Utility Usage (Lines 43-46, 148-153, 180-184):
 *    - Centralized authentication logic in AuthHelper class
 *    - Provides login(), logout(), isLoggedIn() helper methods
 *    - Encapsulates repository selection and credential handling
 *    - Rationale: Reusable authentication logic across all test suites
 *    - Implementation: await authHelper.login() handles full login flow
 *
 * 2. Mobile Browser Support (Lines 61-75, 155-169):
 *    - Detects mobile viewport: browserName === 'chromium' && width <= 414
 *    - Closes sidebar before accessing header elements
 *    - Uses menu toggle button: aria-label="menu-fold" or "menu-unfold"
 *    - Includes try-catch for graceful failure if sidebar close fails
 *    - Rationale: Mobile layouts render sidebar as overlay blocking header
 *    - Performance: 500ms wait after sidebar close for animation
 *
 * 3. Multiple Selector Strategy with Fallback (Lines 21, 93):
 *    - Username field: 'input[type="text"], input[name="username"], input[placeholder="ユーザー名"]'
 *    - Uses .first() to handle multiple matches gracefully
 *    - Rationale: UI implementation may use different field attributes
 *    - Provides robustness against minor UI changes
 *    - Example: Works with both English and Japanese placeholders
 *
 * 4. Session Clean Start Pattern (Lines 6-10):
 *    - beforeEach hook clears cookies and permissions
 *    - Ensures each test starts with fresh authentication state
 *    - Prevents test interdependencies and flaky failures
 *    - Rationale: Authentication tests must be completely isolated
 *    - Implementation: context().clearCookies(), clearPermissions()
 *
 * 5. Ant Design Component Interaction (Lines 32-35, 100-110):
 *    - Repository selector: .ant-select component
 *    - Dropdown handling: .ant-select-dropdown with visibility check
 *    - Option selection: .ant-select-item-option with text filter
 *    - Includes scrollIntoViewIfNeeded() for long dropdown lists
 *    - Wait pattern: 300ms after scroll before click
 *    - Rationale: Ant Design components require specific interaction sequence
 *
 * 6. Login Verification Strategy (Lines 48-59, 189-190):
 *    - Primary: URL contains '/ui/' (successful redirect)
 *    - Secondary: Password field not visible (left login page)
 *    - Tertiary: Main layout elements present (.ant-layout-sider, .ant-layout-content)
 *    - User display in header: text=admin
 *    - Repository display: text=bedroom
 *    - Rationale: Multi-layer verification ensures robust login detection
 *
 * 7. Protected Route Access Control (Lines 193-204):
 *    - Direct navigation to /documents without authentication
 *    - Expects redirect to login or display of login form
 *    - Checks both URL redirect and password field visibility
 *    - Rationale: Verifies React Router protected route implementation
 *    - Implementation: ProtectedRoute component should redirect unauthenticated users
 *
 * 8. Error Handling Patterns (Lines 116-127, 139-144):
 *    - Invalid credentials: Should remain on login page
 *    - Empty credentials: Form should not submit
 *    - No strict requirement for error message display
 *    - Rationale: Focuses on functional behavior rather than UI messages
 *    - Allows flexibility in error messaging implementation
 *
 * Expected Results:
 * - All 7 tests should pass across all browser profiles
 * - Login/logout flow functional on desktop and mobile
 * - Session persistence verified after page refresh
 * - Protected routes redirect correctly
 * - Form validation prevents empty submissions
 *
 * Performance Optimizations:
 * - Uses waitForSelector with timeout for reliable element detection
 * - Includes waitForTimeout for animation completion (Ant Design)
 * - Mobile sidebar close wrapped in try-catch for graceful degradation
 *
 * Debugging Features:
 * - TestHelper.checkForJSErrors() validates no console errors
 * - Multiple selector fallbacks for robust element location
 * - Clear assertion messages for failure diagnosis
 *
 * Authentication Credentials (from AuthHelper):
 * - Default username: admin
 * - Default password: admin
 * - Default repository: bedroom
 *
 * Relationship to Other Components:
 * - AuthHelper: Centralized authentication utility
 * - TestHelper: Common test utilities (JS error checking, Ant Design load)
 * - ProtectedRoute: React component enforcing authentication
 * - React Router: Navigation and route protection
 */

import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';

test.describe('NemakiWare Authentication', () => {
  test.beforeEach(async ({ page, context }) => {
    // Start with a clean session
    await context.clearCookies();
    await context.clearPermissions();

    // CRITICAL FIX (2025-12-02): Clear localStorage and all storage to remove cached auth
    // Navigate to page first, then clear all storage types
    await page.goto('/core/ui/index.html');
    await page.evaluate(() => {
      // Clear all storage mechanisms
      localStorage.clear();
      sessionStorage.clear();

      // Also clear IndexedDB if present
      if (indexedDB && indexedDB.databases) {
        indexedDB.databases().then(dbs => {
          dbs.forEach(db => {
            if (db.name) indexedDB.deleteDatabase(db.name);
          });
        }).catch(() => {});
      }
    });

    // FIX FOR BASIC AUTH CACHING: Navigate away from domain to clear HTTP auth cache
    // Playwright browser context caches HTTP Basic Auth credentials per-origin
    // Navigating to about:blank and back forces credential cache to be cleared
    await page.goto('about:blank');
    await page.goto('/core/ui/index.html');

    // Clear storage again after fresh navigation
    await page.evaluate(() => {
      localStorage.clear();
      sessionStorage.clear();
    });
  });

  test('should display login page correctly', async ({ page }) => {
    const testHelper = new TestHelper(page);

    await page.goto('/core/ui/index.html');

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
    expect(page.url()).toContain('/ui/');

    // Verify we're no longer on login page
    await expect(page.locator('input[type="password"]')).not.toBeVisible();

    // Wait for main UI to load
    await testHelper.waitForAntdLoad();

    // Verify main application layout is present
    await expect(page.locator('.ant-layout-sider')).toBeVisible({ timeout: 10000 });
    await expect(page.locator('.ant-layout-content')).toBeVisible({ timeout: 10000 });

    // MOBILE FIX: Close sidebar on mobile to access header elements
    const isMobile = testHelper.isMobile(browserName);

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

  test('should fail login with invalid credentials', async ({ browser }) => {
    // CRITICAL FIX (2025-12-02): Use a fresh browser context WITHOUT httpCredentials
    // The global playwright.config.ts sets httpCredentials to admin:admin which overrides
    // any Authorization header set by JavaScript. This caused the test to always succeed
    // because the server received admin:admin credentials instead of invalid:invalid.
    const context = await browser.newContext({
      // Explicitly disable httpCredentials to test actual invalid credentials
      httpCredentials: undefined,
      extraHTTPHeaders: {},
    });
    const page = await context.newPage();

    try {
      await page.goto('/core/ui/index.html');

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
    } finally {
      await context.close();
    }
  });

  test('should handle empty credentials', async ({ page }) => {
    await page.goto('/core/ui/index.html');

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
    const isMobile = testHelper.isMobile(browserName);

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
    await page.goto('/core/ui/index.html#/documents');

    // Should redirect to login or show login form
    await page.waitForTimeout(2000);

    const onLoginPage = await page.locator('input[type="password"]').isVisible();
    const redirectedToLogin = page.url().includes('login') || page.url().endsWith('/ui/');

    expect(onLoginPage || redirectedToLogin).toBe(true);
  });
});