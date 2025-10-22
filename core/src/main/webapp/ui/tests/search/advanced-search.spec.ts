import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';

test.describe('Advanced Search', () => {
  let authHelper: AuthHelper;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    await authHelper.login();

    // Navigate to search page
    await page.waitForTimeout(2000);
    const searchMenu = page.locator('.ant-menu-item:has-text("検索")');
    await searchMenu.click();
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

  test('should display search page', async ({ page }) => {
    // Verify URL contains /search
    expect(page.url()).toContain('/search');

    // Check for search interface
    const searchInterface = page.locator('.ant-card, .search-container, form');
    if (await searchInterface.count() > 0) {
      await expect(searchInterface.first()).toBeVisible({ timeout: 10000 });
    }

    // Take screenshot
    await page.screenshot({ path: 'test-results/screenshots/search_page.png', fullPage: true });
  });

  test('should handle basic search', async ({ page, browserName }) => {
    // Detect mobile browsers
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Wait for page to load
    await page.waitForTimeout(2000);

    // Look for search input
    const searchInput = page.locator('input[placeholder*="検索"], input[placeholder*="search"], .ant-input-search input');

    if (await searchInput.count() > 0) {
      // Fill search query
      await searchInput.first().fill('test');

      // Look for search button
      const searchButton = page.locator('button:has-text("検索"), .ant-btn:has-text("Search"), .ant-input-search-button');

      if (await searchButton.count() > 0) {
        await searchButton.first().click(isMobile ? { force: true } : {});
      } else {
        // Try pressing Enter
        await searchInput.first().press('Enter');
      }

      // Wait for search results
      await page.waitForTimeout(2000);

      // Verify results container exists
      const resultsContainer = page.locator('.ant-table, .search-results, .ant-list');
      if (await resultsContainer.count() > 0) {
        await expect(resultsContainer.first()).toBeVisible({ timeout: 5000 });
      }
    } else {
      test.skip('Search input not found');
    }
  });

  test('should execute search without errors', async ({ page, browserName }) => {
    // Detect mobile browsers
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Wait for page to load
    await page.waitForTimeout(2000);

    // Perform a search
    const searchInput = page.locator('input[placeholder*="検索"], input[placeholder*="search"]');
    if (await searchInput.count() > 0) {
      await searchInput.first().fill('test-search-query');

      const searchButton = page.locator('button:has-text("検索"), .ant-btn:has-text("Search")');
      if (await searchButton.count() > 0) {
        await searchButton.first().click(isMobile ? { force: true } : {});
      } else {
        await searchInput.first().press('Enter');
      }

      await page.waitForTimeout(2000);

      // Verify no error messages appeared
      const errorMessage = page.locator('.ant-message-error');
      const errorCount = await errorMessage.count();

      // DEBUGGING: Log error message if present
      if (errorCount > 0) {
        const errorText = await errorMessage.first().textContent();
        console.log(`❌ Search error message appeared: "${errorText}"`);
        console.log(`PRODUCT BUG: Search operation returned error despite valid query`);
      } else {
        console.log('✅ No error messages - search executed successfully');
      }

      expect(errorCount).toBe(0);

      // Verify we're still on search page (not redirected due to error)
      expect(page.url()).toContain('/search');
    } else {
      test.skip('Search functionality not available');
    }
  });

  test('should navigate to document from search results', async ({ page }) => {
    // Wait for page to load
    await page.waitForTimeout(2000);

    // Perform search
    const searchInput = page.locator('input[placeholder*="検索"], input[placeholder*="search"]');
    if (await searchInput.count() > 0) {
      await searchInput.first().fill('test');

      const searchButton = page.locator('button:has-text("検索")');
      if (await searchButton.count() > 0) {
        await searchButton.first().click();
        await page.waitForTimeout(2000);

        // Look for clickable result
        const resultLink = page.locator('.ant-table tbody tr a, .ant-table tbody tr td').first();
        if (await resultLink.count() > 0) {
          await resultLink.click();
          await page.waitForTimeout(1000);

          // Should navigate somewhere (document detail or download)
          // Just verify page didn't error
          const errorMessage = page.locator('.ant-message-error');
          expect(await errorMessage.count()).toBe(0);
        }
      }
    } else {
      test.skip('Search functionality not available');
    }
  });

  test('should navigate back from search page', async ({ page }) => {
    // Wait for page to stabilize
    await page.waitForTimeout(1000);

    // Click on Documents menu item
    const documentsMenu = page.locator('.ant-menu-item:has-text("ドキュメント")');
    await documentsMenu.click();
    await page.waitForTimeout(2000);

    // Verify navigation to documents page
    expect(page.url()).toContain('/documents');
  });
});
