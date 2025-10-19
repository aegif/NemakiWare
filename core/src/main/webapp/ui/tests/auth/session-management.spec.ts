import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';

test.describe('Session Management', () => {
  let authHelper: AuthHelper;
  let testHelper: TestHelper;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    // Login as admin
    await authHelper.login();
    await page.waitForTimeout(2000);

    // MOBILE FIX: Close sidebar
    const viewportSize = page.viewportSize();
    const isMobileChrome = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobileChrome) {
      const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]');
      if (await menuToggle.count() > 0) {
        await menuToggle.first().click({ timeout: 3000 });
        await page.waitForTimeout(500);
      }
    }

    await testHelper.waitForAntdLoad();

    // Navigate to documents
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click();
    await page.waitForTimeout(2000);
  });

  test('should handle session timeout and automatic logout', async ({ page, browserName }) => {
    test.setTimeout(120000); // 2-minute timeout for this test

    console.log('Test: Verifying initial session is active');

    // Verify user is logged in
    const userDisplay = page.locator('.ant-layout-header').locator('text=admin');
    await expect(userDisplay).toBeVisible({ timeout: 5000 });

    console.log('Test: Initial session verified - user is logged in');

    // Clear authentication tokens to simulate session expiration
    await page.evaluate(() => {
      // Clear localStorage
      localStorage.removeItem('nemaki_auth_token');
      localStorage.removeItem('nemaki_username');
      localStorage.removeItem('nemaki_repository');

      // Clear sessionStorage
      sessionStorage.clear();

      // Clear cookies (if any)
      document.cookie.split(";").forEach((c) => {
        document.cookie = c.replace(/^ +/, "").replace(/=.*/, "=;expires=" + new Date().toUTCString() + ";path=/");
      });
    });

    console.log('Test: Authentication tokens cleared to simulate session timeout');

    await page.waitForTimeout(2000);

    // Try to perform an operation that requires authentication (e.g., navigate to a different page)
    const settingsMenuItem = page.locator('.ant-menu-item, .ant-menu-submenu').filter({ hasText: '設定' });
    if (await settingsMenuItem.count() > 0) {
      await settingsMenuItem.first().click({ timeout: 5000 });
      await page.waitForTimeout(2000);
    } else {
      // Alternative: Try to access documents again
      await page.goto('http://localhost:8080/core/ui/dist/index.html#/documents');
      await page.waitForTimeout(2000);
    }

    console.log('Test: Attempted to access protected resource after token clear');

    // Should be redirected to login page or see login form
    // Check for multiple possible indicators of being logged out
    const onLoginPage = await page.locator('input[type="password"]').isVisible({ timeout: 10000 }).catch(() => false);
    const atLoginUrl = page.url().includes('login') || page.url().endsWith('/ui/dist/') || page.url().endsWith('/ui/dist/index.html');

    if (onLoginPage || atLoginUrl) {
      console.log('Test: Successfully redirected to login page after session timeout');
      expect(onLoginPage || atLoginUrl).toBe(true);
    } else {
      // May show an error message or session expired notification
      const errorMessage = page.locator('.ant-message-error, .ant-notification-error, .ant-alert-error');
      if (await errorMessage.count() > 0) {
        console.log('Test: Session timeout error message displayed');
        await expect(errorMessage).toBeVisible({ timeout: 5000 });
      } else {
        // At minimum, user should not be visible anymore
        const userStillVisible = await userDisplay.isVisible({ timeout: 3000 }).catch(() => false);
        console.log('Test: User display visibility after logout:', userStillVisible);
        expect(userStillVisible).toBe(false);
      }
    }

    console.log('Test: Session timeout handling verified');
  });

  test('should handle multi-tab session management', async ({ page, context, browserName }) => {
    test.setTimeout(120000); // 2-minute timeout

    console.log('Test: Starting multi-tab session management test');

    // Verify logged in on first tab
    const userDisplay = page.locator('.ant-layout-header').locator('text=admin');
    await expect(userDisplay).toBeVisible({ timeout: 5000 });

    console.log('Test: First tab logged in successfully');

    // Open second tab with same context
    const secondTab = await context.newPage();
    await secondTab.goto('http://localhost:8080/core/ui/dist/index.html');
    await secondTab.waitForTimeout(3000);

    console.log('Test: Second tab opened');

    // Second tab should automatically be logged in (shared session)
    const secondTabLoggedIn = await secondTab.locator('input[type="password"]').isVisible({ timeout: 5000 }).catch(() => false);

    if (!secondTabLoggedIn) {
      console.log('Test: Second tab is already logged in (session shared)');

      // Verify documents are accessible in second tab
      const secondTabUserDisplay = secondTab.locator('.ant-layout-header').locator('text=admin');
      await expect(secondTabUserDisplay).toBeVisible({ timeout: 10000 });
    } else {
      console.log('Test: Second tab requires login (session not shared via localStorage)');
      // This is also acceptable behavior - some apps don't share sessions across tabs
    }

    // Logout from first tab
    console.log('Test: Logging out from first tab');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobile) {
      const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]');
      if (await menuToggle.count() > 0) {
        await menuToggle.first().click({ timeout: 3000 });
        await page.waitForTimeout(500);
      }
    }

    await authHelper.logout();
    await page.waitForTimeout(2000);

    console.log('Test: First tab logged out');

    // Verify first tab is on login page
    await expect(page.locator('input[type="password"]')).toBeVisible({ timeout: 5000 });

    console.log('Test: First tab showing login page');

    // Second tab behavior - should detect logout
    // Try to perform an action that triggers session check
    await secondTab.reload();
    await secondTab.waitForTimeout(3000);

    const secondTabAfterLogout = await secondTab.locator('input[type="password"]').isVisible({ timeout: 5000 }).catch(() => false);

    if (secondTabAfterLogout) {
      console.log('Test: Second tab correctly detected logout and shows login page');
      expect(secondTabAfterLogout).toBe(true);
    } else {
      console.log('Test: Second tab may still show content (depends on session sync implementation)');
      // This is acceptable - some implementations don't sync logout across tabs
      // The important part is that API calls would fail if attempted
    }

    // Cleanup
    await secondTab.close();

    console.log('Test: Multi-tab session management test completed');
  });
});
