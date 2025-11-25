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
 * - Test Data: Uses existing "CMIS 1.1 Specification Resources.pdf" document
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
    await page.goto('http://localhost:8080/core/ui/dist/#/documents');
    await page.waitForTimeout(2000);
  });

  test('should display search-specific columns in search mode', async ({ page, browserName }) => {
    // Mobile detection
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Find search input
    const searchInput = page.locator('input[placeholder*="検索"]').first();
    await expect(searchInput).toBeVisible({ timeout: 10000 });

    // Enter search query for CMIS specification document
    await searchInput.fill('CMIS');

    // Find and click search button
    // Use className instead of text because Ant Design adds spacing: "検 索"
    const searchButton = page.locator('button.search-button').first();
    await expect(searchButton).toBeVisible({ timeout: 5000 });
    await searchButton.click(isMobile ? { force: true } : {});

    // Wait for search results
    await page.waitForTimeout(3000);

    // Verify "クリア" button appears (indicates search mode)
    const clearButton = page.locator('button:has-text("クリア")').first();
    await expect(clearButton).toBeVisible({ timeout: 5000 });

    // Verify search-specific column headers are present
    const table = page.locator('.ant-table').first();
    await expect(table).toBeVisible({ timeout: 5000 });

    // Check for search-mode column headers
    const objectTypeHeader = table.locator('th:has-text("オブジェクトタイプ")');
    const pathHeader = table.locator('th:has-text("パス")');
    const createdByHeader = table.locator('th:has-text("作成者")');
    const creationDateHeader = table.locator('th:has-text("作成日時")');

    await expect(objectTypeHeader).toBeVisible({ timeout: 5000 });
    await expect(pathHeader).toBeVisible({ timeout: 5000 });
    await expect(createdByHeader).toBeVisible({ timeout: 5000 });
    await expect(creationDateHeader).toBeVisible({ timeout: 5000 });

    // Verify browse-mode columns are NOT present
    const sizeHeader = table.locator('th:has-text("サイズ")');
    const modifiedByHeader = table.locator('th:has-text("更新者")');

    expect(await sizeHeader.count()).toBe(0);
    expect(await modifiedByHeader.count()).toBe(0);
  });

  test('should display path information in search results', async ({ page, browserName }) => {
    // Mobile detection
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Perform search
    const searchInput = page.locator('input[placeholder*="検索"]').first();
    await searchInput.fill('CMIS');

    const searchButton = page.locator('button.search-button').first();
    await searchButton.click(isMobile ? { force: true } : {});

    // Wait for search results
    await page.waitForTimeout(3000);

    // Find table rows with results
    const table = page.locator('.ant-table').first();
    const rows = table.locator('tbody tr');
    const rowCount = await rows.count();

    expect(rowCount).toBeGreaterThan(0);

    // Verify path column contains valid paths (not "（計算中...）")
    const firstRow = rows.first();
    const pathCell = firstRow.locator('td').nth(3); // Path is 4th column (0-indexed: type, name, objectType, path)

    // Wait for path calculation to complete
    await page.waitForTimeout(2000);

    const pathText = await pathCell.textContent();
    expect(pathText).toBeTruthy();
    expect(pathText).not.toBe('（計算中...）');

    // Path should start with / for absolute paths
    if (pathText && pathText.trim() !== '-') {
      expect(pathText.startsWith('/')).toBeTruthy();
    }
  });

  test('should display objectType in search results', async ({ page, browserName }) => {
    // Mobile detection
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Perform search
    const searchInput = page.locator('input[placeholder*="検索"]').first();
    await searchInput.fill('CMIS');

    const searchButton = page.locator('button.search-button').first();
    await searchButton.click(isMobile ? { force: true } : {});

    // Wait for search results
    await page.waitForTimeout(3000);

    // Find table rows
    const table = page.locator('.ant-table').first();
    const rows = table.locator('tbody tr');
    const rowCount = await rows.count();

    expect(rowCount).toBeGreaterThan(0);

    // Verify objectType column shows CMIS type
    const firstRow = rows.first();
    const objectTypeCell = firstRow.locator('td').nth(2); // ObjectType is 3rd column (0-indexed)

    const objectTypeText = await objectTypeCell.textContent();
    expect(objectTypeText).toBeTruthy();

    // ObjectType should be a valid CMIS type (cmis:document, cmis:folder, etc.)
    expect(objectTypeText?.startsWith('cmis:')).toBeTruthy();
  });

  test('should display createdBy and creationDate in search results', async ({ page, browserName }) => {
    // Mobile detection
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Perform search
    const searchInput = page.locator('input[placeholder*="検索"]').first();
    await searchInput.fill('CMIS');

    const searchButton = page.locator('button.search-button').first();
    await searchButton.click(isMobile ? { force: true } : {});

    // Wait for search results
    await page.waitForTimeout(3000);

    // Find table rows
    const table = page.locator('.ant-table').first();
    const rows = table.locator('tbody tr');
    const rowCount = await rows.count();

    expect(rowCount).toBeGreaterThan(0);

    const firstRow = rows.first();

    // Verify createdBy column (5th column: type, name, objectType, path, createdBy)
    const createdByCell = firstRow.locator('td').nth(4);
    const createdByText = await createdByCell.textContent();
    expect(createdByText).toBeTruthy();
    expect(createdByText).not.toBe('-');

    // Verify creationDate column (6th column)
    const creationDateCell = firstRow.locator('td').nth(5);
    const creationDateText = await creationDateCell.textContent();
    expect(creationDateText).toBeTruthy();
    expect(creationDateText).not.toBe('-');

    // Creation date should be formatted as Japanese locale datetime
    expect(creationDateText).toMatch(/\d{4}\/\d{1,2}\/\d{1,2}/); // YYYY/MM/DD format
  });

  test('should switch to browse-mode columns when clearing search', async ({ page, browserName }) => {
    // Mobile detection
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Perform search first
    const searchInput = page.locator('input[placeholder*="検索"]').first();
    await searchInput.fill('CMIS');

    const searchButton = page.locator('button.search-button').first();
    await searchButton.click(isMobile ? { force: true } : {});

    // Wait for search results
    await page.waitForTimeout(3000);

    // Verify search-mode columns exist
    const table = page.locator('.ant-table').first();
    let pathHeader = table.locator('th:has-text("パス")');
    await expect(pathHeader).toBeVisible({ timeout: 5000 });

    // Click clear button
    const clearButton = page.locator('button:has-text("クリア")').first();
    await clearButton.click(isMobile ? { force: true } : {});

    // Wait for mode switch
    await page.waitForTimeout(2000);

    // Verify browse-mode columns now appear
    const sizeHeader = table.locator('th:has-text("サイズ")');
    const modifiedByHeader = table.locator('th:has-text("更新者")');
    const modifiedDateHeader = table.locator('th:has-text("更新日時")');

    await expect(sizeHeader).toBeVisible({ timeout: 5000 });
    await expect(modifiedByHeader).toBeVisible({ timeout: 5000 });
    await expect(modifiedDateHeader).toBeVisible({ timeout: 5000 });

    // Verify search-mode columns are gone
    pathHeader = table.locator('th:has-text("パス")');
    const objectTypeHeader = table.locator('th:has-text("オブジェクトタイプ")');
    const createdByHeader = table.locator('th:has-text("作成者")');
    const creationDateHeader = table.locator('th:has-text("作成日時")');

    expect(await pathHeader.count()).toBe(0);
    expect(await objectTypeHeader.count()).toBe(0);
    expect(await createdByHeader.count()).toBe(0);
    expect(await creationDateHeader.count()).toBe(0);

    // Verify clear button disappears
    expect(await clearButton.count()).toBe(0);
  });

  test('should display all search result metadata correctly', async ({ page, browserName }) => {
    // Mobile detection
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Perform search
    const searchInput = page.locator('input[placeholder*="検索"]').first();
    await searchInput.fill('CMIS');

    const searchButton = page.locator('button.search-button').first();
    await searchButton.click(isMobile ? { force: true } : {});

    // Wait for search results and path calculation
    await page.waitForTimeout(4000);

    // Verify comprehensive metadata
    const table = page.locator('.ant-table').first();
    const rows = table.locator('tbody tr');
    const rowCount = await rows.count();

    expect(rowCount).toBeGreaterThan(0);

    const firstRow = rows.first();

    // Column indices (0-indexed):
    // 0: type icon, 1: name, 2: objectType, 3: path, 4: createdBy, 5: creationDate, 6: actions

    // Verify all cells have content
    const nameCell = firstRow.locator('td').nth(1);
    const objectTypeCell = firstRow.locator('td').nth(2);
    const pathCell = firstRow.locator('td').nth(3);
    const createdByCell = firstRow.locator('td').nth(4);
    const creationDateCell = firstRow.locator('td').nth(5);

    // Name
    const nameText = await nameCell.textContent();
    expect(nameText).toContain('CMIS'); // Search query should match

    // ObjectType
    const objectTypeText = await objectTypeCell.textContent();
    expect(objectTypeText).toMatch(/^cmis:/);

    // Path
    const pathText = await pathCell.textContent();
    expect(pathText).toBeTruthy();
    expect(pathText).not.toBe('（計算中...）');
    if (pathText && pathText !== '-') {
      expect(pathText.startsWith('/')).toBeTruthy();
    }

    // CreatedBy
    const createdByText = await createdByCell.textContent();
    expect(createdByText).toBeTruthy();

    // CreationDate
    const creationDateText = await creationDateCell.textContent();
    expect(creationDateText).toMatch(/\d{4}\/\d{1,2}\/\d{1,2}/);
  });
});
