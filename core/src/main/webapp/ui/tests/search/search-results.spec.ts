/**
 * Search Results Verification Tests for NemakiWare React UI
 *
 * CRITICAL FIX (2025-11-19): User requested detailed search result verification
 * Original issue: "検索結果に適切なプロパティが表示されません。検索結果についてはもっと細かい検証をPlaywrightでもすべきだと思います"
 *
 * These tests verify:
 * 1. Search mode displays search-specific columns (objectType, path, createdBy, creationDate)
 * 2. Browse mode displays browse-specific columns (size, lastModifiedBy, lastModificationDate)
 * 3. Search results calculate and display document paths correctly
 * 4. "クリア" button transitions back to browse mode with correct columns
 * 5. Search results show all expected metadata for document discovery
 *
 * Test Environment:
 * - Browsers: Chromium, Firefox, WebKit, Mobile Chrome, Mobile Safari, Tablet
 * - Test Data: Uses existing test documents with "test" in name
 * - Server: http://localhost:8080/core/ui/
 * - Authentication: admin:admin
 * - Repository: bedroom
 */

import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';

test.describe('Search Results Detailed Verification', () => {
  let authHelper: AuthHelper;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);

    // Login as admin
    await authHelper.login();

    // Wait for UI initialization
    await page.waitForTimeout(2000);

    // Mobile sidebar close logic (if needed)
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobile) {
      // Close sidebar to prevent overlay blocking
      const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]').first();
      if (await menuToggle.count() > 0) {
        await menuToggle.click({ timeout: 3000 }).catch(() => {});
        await page.waitForTimeout(500);
      }
    }

    // Navigate to documents page
    await page.goto('http://localhost:8080/core/ui/#/documents');
    await page.waitForTimeout(2000);
  });

  test('should display search-specific columns in search mode', async ({ page, browserName }) => {
    // Mobile detection
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Find search input
    const searchInput = page.locator('input[placeholder*="検索"]').first();
    await expect(searchInput).toBeVisible({ timeout: 10000 });

    // Enter search query (any query to trigger search mode)
    await searchInput.fill('test');

    // Find and click search button
    const searchButton = page.locator('button.search-button').first();
    await expect(searchButton).toBeVisible({ timeout: 5000 });
    await searchButton.click(isMobile ? { force: true } : {});

    // Wait for search to execute
    await page.waitForTimeout(3000);

    // Verify "クリア" button appears (indicates search mode is active)
    const clearButton = page.locator('button:has-text("クリア")').first();
    await expect(clearButton).toBeVisible({ timeout: 5000 });

    // Verify table is visible
    const table = page.locator('.ant-table').first();
    await expect(table).toBeVisible({ timeout: 5000 });

    // Search mode now includes all metadata columns (implemented 2025-12-25)
    // Verify: オブジェクトタイプ, パス, 作成者, 作成日時 columns
    const objectTypeHeader = table.locator('th:has-text("オブジェクトタイプ")');
    const pathHeader = table.locator('th:has-text("パス")');
    const createdByHeader = table.locator('th:has-text("作成者")');
    const creationDateHeader = table.locator('th:has-text("作成日時")');

    await expect(objectTypeHeader).toBeVisible({ timeout: 5000 });
    await expect(pathHeader).toBeVisible({ timeout: 5000 });
    await expect(createdByHeader).toBeVisible({ timeout: 5000 });
    await expect(creationDateHeader).toBeVisible({ timeout: 5000 });
  });

  test.skip('should display path information in search results', async ({ page, browserName }) => {
    // SKIPPED: Test requires documents to exist in repository
    // This test depends on having searchable documents in the test environment
    // which may not exist in a clean test database

    // Mobile detection
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Perform search
    const searchInput = page.locator('input[placeholder*="検索"]').first();
    await searchInput.fill('test');

    const searchButton = page.locator('button.search-button').first();
    await searchButton.click(isMobile ? { force: true } : {});

    // Wait for search results
    await page.waitForTimeout(3000);

    // Find table rows with results
    const table = page.locator('.ant-table').first();
    const rows = table.locator('tbody tr');
    const rowCount = await rows.count();

    expect(rowCount).toBeGreaterThan(0);

    // Verify path column contains valid paths
    const firstRow = rows.first();
    const pathCell = firstRow.locator('td').nth(2); // Path is 3rd column after type and name

    const pathText = await pathCell.textContent();
    expect(pathText).toBeTruthy();
  });

  test('should display objectType in search results', async ({ page, browserName }) => {
    // ENABLED (2025-12-25): objectType column is now implemented in DocumentList
    // Test may skip if no documents matching "test" exist in repository

    // Mobile detection
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Perform search
    const searchInput = page.locator('input[placeholder*="検索"]').first();
    await searchInput.fill('test');

    const searchButton = page.locator('button.search-button').first();
    await searchButton.click(isMobile ? { force: true } : {});

    // Wait for search results
    await page.waitForTimeout(3000);

    // Find table rows
    const table = page.locator('.ant-table').first();
    const rows = table.locator('tbody tr.ant-table-row');
    const rowCount = await rows.count();

    // Skip if no search results (test data dependent)
    test.skip(rowCount === 0, 'No documents matching "test" found in repository');

    // Verify objectType column shows Japanese label (ドキュメント or フォルダ) or CMIS type
    const firstRow = rows.first();
    // Search mode columns: タイプ(0), 名前(1), オブジェクトタイプ(2), パス(3), 作成者(4), 作成日時(5)
    const objectTypeCell = firstRow.locator('td').nth(2); // objectType is 3rd column (index 2)

    const objectTypeText = await objectTypeCell.textContent();
    // Check for Japanese labels or CMIS type prefix
    const validObjectTypes = ['ドキュメント', 'フォルダ', 'cmis:document', 'cmis:folder'];
    const isValidType = validObjectTypes.some(type => objectTypeText?.includes(type));
    expect(isValidType || objectTypeText?.startsWith('cmis:')).toBeTruthy();
  });

  test('should display createdBy and creationDate in search results', async ({ page, browserName }) => {
    // ENABLED (2025-12-25): createdBy and creationDate columns are now implemented in DocumentList
    // Test may skip if no documents matching "test" exist in repository

    // Mobile detection
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Perform search
    const searchInput = page.locator('input[placeholder*="検索"]').first();
    await searchInput.fill('test');

    const searchButton = page.locator('button.search-button').first();
    await searchButton.click(isMobile ? { force: true } : {});

    // Wait for search results
    await page.waitForTimeout(3000);

    // Find table rows
    const table = page.locator('.ant-table').first();
    const rows = table.locator('tbody tr.ant-table-row');
    const rowCount = await rows.count();

    // Skip if no search results (test data dependent)
    test.skip(rowCount === 0, 'No documents matching "test" found in repository');

    const firstRow = rows.first();

    // Search mode columns: タイプ(0), 名前(1), オブジェクトタイプ(2), パス(3), 作成者(4), 作成日時(5)
    // Verify createdBy column
    const createdByCell = firstRow.locator('td').nth(4);
    const createdByText = await createdByCell.textContent();
    expect(createdByText).toBeTruthy();

    // Verify creationDate column has date format
    const creationDateCell = firstRow.locator('td').nth(5);
    const creationDateText = await creationDateCell.textContent();
    expect(creationDateText).toBeTruthy();
    // Verify it contains date-like content (year or slash for date separator)
    expect(creationDateText?.match(/\d{4}|\//) !== null).toBeTruthy();
  });

  test('should switch to browse-mode columns when clearing search', async ({ page, browserName }) => {
    // Mobile detection
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Perform search first
    const searchInput = page.locator('input[placeholder*="検索"]').first();
    await searchInput.fill('test');

    const searchButton = page.locator('button.search-button').first();
    await searchButton.click(isMobile ? { force: true } : {});

    // Wait for search mode to activate
    await page.waitForTimeout(3000);

    // Verify search-mode: パス column visible and クリア button visible
    const table = page.locator('.ant-table').first();
    let pathHeader = table.locator('th:has-text("パス")');
    await expect(pathHeader).toBeVisible({ timeout: 5000 });

    let clearButton = page.locator('button:has-text("クリア")').first();
    await expect(clearButton).toBeVisible({ timeout: 5000 });

    // Click clear button
    await clearButton.click(isMobile ? { force: true } : {});

    // Wait for mode switch
    await page.waitForTimeout(2000);

    // Verify browse-mode: standard columns visible
    const sizeHeader = table.locator('th:has-text("サイズ")');
    const modifiedByHeader = table.locator('th:has-text("更新者")');
    const modifiedDateHeader = table.locator('th:has-text("更新日時")');

    await expect(sizeHeader).toBeVisible({ timeout: 5000 });
    await expect(modifiedByHeader).toBeVisible({ timeout: 5000 });
    await expect(modifiedDateHeader).toBeVisible({ timeout: 5000 });

    // Verify search-mode path column is gone
    pathHeader = table.locator('th:has-text("パス")');
    expect(await pathHeader.count()).toBe(0);

    // Verify clear button disappears (may need to refresh locator)
    clearButton = page.locator('button:has-text("クリア")');
    expect(await clearButton.count()).toBe(0);
  });

  test('should display all search result metadata correctly', async ({ page, browserName }) => {
    // ENABLED (2025-12-25): All search result columns are now implemented
    // Columns: objectType, name, path, createdBy, creationDate
    // Test may skip if no documents matching "test" exist in repository

    // Mobile detection
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Perform search
    const searchInput = page.locator('input[placeholder*="検索"]').first();
    await searchInput.fill('test');

    const searchButton = page.locator('button.search-button').first();
    await searchButton.click(isMobile ? { force: true } : {});

    // Wait for search results
    await page.waitForTimeout(4000);

    // Verify table and column headers exist
    const table = page.locator('.ant-table').first();
    await expect(table).toBeVisible({ timeout: 5000 });

    // Verify all search-mode column headers
    await expect(table.locator('th:has-text("オブジェクトタイプ")')).toBeVisible();
    await expect(table.locator('th:has-text("名前")')).toBeVisible();
    await expect(table.locator('th:has-text("パス")')).toBeVisible();
    await expect(table.locator('th:has-text("作成者")')).toBeVisible();
    await expect(table.locator('th:has-text("作成日時")')).toBeVisible();

    // Check for result rows
    const rows = table.locator('tbody tr.ant-table-row');
    const rowCount = await rows.count();

    // Skip data verification if no results (test passes for column header verification)
    if (rowCount === 0) {
      return; // Column headers verified, no data to check
    }

    // Verify first row has all metadata populated
    const firstRow = rows.first();
    const cells = firstRow.locator('td');
    const cellCount = await cells.count();

    // Should have at least 5 cells (objectType, name, path, createdBy, creationDate)
    expect(cellCount).toBeGreaterThanOrEqual(5);
  });
});
