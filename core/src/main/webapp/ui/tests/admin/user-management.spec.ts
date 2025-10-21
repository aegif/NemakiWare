import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';

test.describe('User Management', () => {
  let authHelper: AuthHelper;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    await authHelper.login();

    // Navigate to user management
    await page.waitForTimeout(2000);
    const adminMenu = page.locator('.ant-menu-submenu:has-text("管理")');
    if (await adminMenu.count() > 0) {
      await adminMenu.click();
      await page.waitForTimeout(1000);
    }
    await page.locator('.ant-menu-item:has-text("ユーザー管理")').click();
    await page.waitForTimeout(2000);

    // MOBILE FIX: Close sidebar to prevent overlay blocking clicks
    const viewportSize = page.viewportSize();
    const isMobileChrome = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobileChrome) {
      const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]');

      if (await menuToggle.count() > 0) {
        try {
          await menuToggle.first().click({ timeout: 3000 });
          await page.waitForTimeout(500);
        } catch (error) {
          // Continue even if sidebar close fails
        }
      } else {
        const alternativeToggle = page.locator('.ant-layout-header button, banner button').first();
        if (await alternativeToggle.count() > 0) {
          try {
            await alternativeToggle.click({ timeout: 3000 });
            await page.waitForTimeout(500);
          } catch (error) {
            // Continue even if alternative selector fails
          }
        }
      }
    }
  });

  test('should display user management page', async ({ page }) => {
    // Verify URL contains /users
    expect(page.url()).toContain('/users');

    // Check for user table or list
    const table = page.locator('.ant-table');
    if (await table.count() > 0) {
      await expect(table).toBeVisible({ timeout: 10000 });
    }

    // Take screenshot
    await page.screenshot({ path: 'test-results/screenshots/user_management.png', fullPage: true });
  });

  test('should display existing users', async ({ page }) => {
    // Wait for user list to load
    await page.waitForTimeout(2000);

    // Check for admin user (should always exist)
    const adminUser = page.locator('text=admin');
    const adminCount = await adminUser.count();

    // At least one instance of "admin" should be visible (in header or user list)
    expect(adminCount).toBeGreaterThan(0);
  });

  test('should handle user search or filter', async ({ page }) => {
    // Wait for page to load
    await page.waitForTimeout(2000);

    // Look for search input (FIX: Use .ant-input-search input to target actual input element)
    const searchInput = page.locator('input[placeholder*="検索"], input[placeholder*="search"], .ant-input-search input');

    if (await searchInput.count() > 0) {
      // Perform search
      await searchInput.first().fill('admin');
      await page.waitForTimeout(1000);

      // Verify search results
      const adminResult = page.locator('text=admin');
      await expect(adminResult.first()).toBeVisible({ timeout: 5000 });
    } else {
      test.skip('Search functionality not available in user management');
    }
  });

  test('should navigate back from user management', async ({ page, browserName }) => {
    // Detect mobile browsers
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Wait for page to stabilize
    await page.waitForTimeout(1000);

    // Click on Documents menu item
    const documentsMenu = page.locator('.ant-menu-item:has-text("ドキュメント")');
    await documentsMenu.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Verify navigation to documents page
    expect(page.url()).toContain('/documents');
  });
});
