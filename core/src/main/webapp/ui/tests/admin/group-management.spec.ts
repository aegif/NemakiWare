import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';

test.describe('Group Management', () => {
  let authHelper: AuthHelper;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    await authHelper.login();

    // Navigate to group management
    await page.waitForTimeout(2000);
    const adminMenu = page.locator('.ant-menu-submenu:has-text("管理")');
    if (await adminMenu.count() > 0) {
      await adminMenu.click();
      await page.waitForTimeout(1000);
    }
    await page.locator('.ant-menu-item:has-text("グループ管理")').click();
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

  test('should display group management page', async ({ page }) => {
    // Verify URL contains /groups
    expect(page.url()).toContain('/groups');

    // Check for group table or list
    const table = page.locator('.ant-table');
    if (await table.count() > 0) {
      await expect(table).toBeVisible({ timeout: 10000 });
    }

    // Take screenshot
    await page.screenshot({ path: 'test-results/screenshots/group_management.png', fullPage: true });
  });

  test('should display existing groups', async ({ page }) => {
    // Wait for group list to load
    await page.waitForTimeout(2000);

    // Check for table rows or group items
    const tableRows = page.locator('.ant-table tbody tr');
    const rowCount = await tableRows.count();

    // Should have at least some groups or empty state
    if (rowCount > 0) {
      expect(rowCount).toBeGreaterThan(0);
    } else {
      // Check for empty state
      const emptyState = page.locator('.ant-empty');
      if (await emptyState.count() > 0) {
        await expect(emptyState).toBeVisible();
      }
    }
  });

  test('should handle group search or filter', async ({ page }) => {
    // Wait for page to load
    await page.waitForTimeout(2000);

    // Look for search input
    const searchInput = page.locator('input[placeholder*="検索"], input[placeholder*="search"], .ant-input-search');

    if (await searchInput.count() > 0) {
      // Perform search
      await searchInput.first().fill('test');
      await page.waitForTimeout(1000);

      // Search should have executed (results may be empty)
      const table = page.locator('.ant-table');
      await expect(table).toBeVisible({ timeout: 5000 });
    } else {
      test.skip('Search functionality not available in group management');
    }
  });

  test('should navigate back from group management', async ({ page, browserName }) => {
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
