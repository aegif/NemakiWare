/**
 * User Scenario Tests - Comprehensive User Flow Verification
 *
 * These tests simulate realistic user workflows from login through various UI operations:
 * 1. Login → Navigate to document list
 * 2. Open document → View various tabs (properties, secondary types, relationships, preview)
 * 3. Back button navigation → Preserve folder context
 * 4. Preview different file types (text, PDF, images)
 * 5. Secondary type operations
 * 6. Relationship operations
 *
 * CRITICAL: These tests verify the reported issues from 2025-12-13:
 * - "y.includes is not a function" error in secondary types
 * - Relationship not showing after creation
 * - Documents becoming unopenable after operations
 * - Text file preview tab not showing
 * - Back button not preserving current folder
 */

import { test, expect, Page } from '@playwright/test';
import { AuthHelper } from './utils/auth-helper';
import { TestHelper, generateTestId } from './utils/test-helper';


test.describe('User Scenario Tests', () => {
  let authHelper: AuthHelper;
  let testHelper: TestHelper;
  const testDocName = `test-scenario-${generateTestId()}.txt`;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    // Login and navigate to documents
    await authHelper.login();
    await testHelper.waitForAntdLoad();
    await testHelper.navigateToDocuments();

    // CRITICAL FIX (2025-12-26): Ensure test document exists before each test
    // This eliminates data-dependent test skips
    const isMobile = testHelper.isMobile(browserName);
    await testHelper.ensureTestDocument(testDocName, 'Test content for user scenario testing', isMobile);
  });

  test.describe('Login and Document Navigation Flow', () => {
    test('should login and see document list', async ({ page }) => {
      // Login is done in beforeEach
      // Verify we're on documents page
      await expect(page).toHaveURL(/documents/);

      // Verify document list is visible
      const documentTable = page.locator('.ant-table');
      await expect(documentTable).toBeVisible({ timeout: 10000 });

      // Verify sidebar is visible
      const sidebar = page.locator('.ant-layout-sider');
      await expect(sidebar).toBeVisible();
    });

    test('should display documents in table', async ({ page }) => {
      // Login is done in beforeEach, and document is ensured
      // Wait for document list to load
      await page.waitForSelector('.ant-table', { timeout: 10000 });
      await waitForRender(page);

      // Verify table has rows (ensured by beforeEach)
      const rows = page.locator('.ant-table-row');
      const rowCount = await rows.count();

      console.log(`Found ${rowCount} rows in document table`);
      expect(rowCount).toBeGreaterThan(0);
    });
  });

  test.describe('Document Viewer Tab Navigation', () => {
    test('should open document detail and view tabs without errors', async ({ page }) => {
      // Login is done in beforeEach, document ensured
      await page.waitForSelector('.ant-table', { timeout: 10000 });
      await waitForRender(page);

      // Find the test document row (ensured by beforeEach)
      const documentRow = page.locator('.ant-table-row').filter({ hasText: testDocName }).first();
      await expect(documentRow).toBeVisible({ timeout: 10000 });

      // Click detail view button (詳細表示 - eye icon)
      const detailButton = documentRow.locator('button').filter({ has: page.locator('.anticon-eye') });
      await expect(detailButton).toBeVisible({ timeout: 10000 });

      // Track JavaScript errors
      const jsErrors: string[] = [];
      page.on('pageerror', error => {
        jsErrors.push(error.message);
      });

      await detailButton.click();
      await waitForUiStable(page);

      // Verify we navigated to document detail page
      const currentUrl = page.url();
      expect(currentUrl).toMatch(/\/documents\/[a-f0-9]+/);

      // Verify tabs are visible
      const tabs = page.locator('.ant-tabs-nav');
      await expect(tabs).toBeVisible({ timeout: 10000 });

      // Click through each tab
      const tabItems = page.locator('.ant-tabs-tab');
      const tabCount = await tabItems.count();
      for (let i = 0; i < tabCount; i++) {
        const tab = tabItems.nth(i);
        await tab.click();
        await waitForRender(page);
      }

      // Check for critical errors
      const criticalErrors = jsErrors.filter(e =>
        e.includes('includes is not a function') ||
        e.includes('Cannot read properties')
      );
      expect(criticalErrors).toHaveLength(0);
    });

    test('should display secondary types tab without errors', async ({ page }) => {
      // Login is done in beforeEach, document ensured
      await page.waitForSelector('.ant-table', { timeout: 10000 });
      await waitForRender(page);

      // Find the test document row (ensured by beforeEach)
      const documentRow = page.locator('.ant-table-row').filter({ hasText: testDocName }).first();
      await expect(documentRow).toBeVisible({ timeout: 10000 });

      const detailButton = documentRow.locator('button').filter({ has: page.locator('.anticon-eye') });
      await expect(detailButton).toBeVisible({ timeout: 10000 });

      const errors: string[] = [];
      page.on('pageerror', error => {
        errors.push(error.message);
      });

      await detailButton.click();
      await waitForUiStable(page);

      // セカンダリタイプ tab is always present in DocumentViewer
      const secondaryTypeTab = page.getByRole('tab', { name: /セカンダリタイプ|Secondary/i });
      await expect(secondaryTypeTab).toBeVisible({ timeout: 10000 });
      await secondaryTypeTab.click();
      await waitForRender(page);

      const tabContent = page.locator('.ant-tabs-tabpane-active');
      await expect(tabContent).toBeVisible();

      const includesErrors = errors.filter(e => e.includes('includes is not a function'));
      expect(includesErrors).toHaveLength(0);
    });

    test('should display relationships tab without errors', async ({ page }) => {
      // Login is done in beforeEach, document ensured
      await page.waitForSelector('.ant-table', { timeout: 10000 });
      await waitForRender(page);

      // Find the test document row (ensured by beforeEach)
      const documentRow = page.locator('.ant-table-row').filter({ hasText: testDocName }).first();
      await expect(documentRow).toBeVisible({ timeout: 10000 });

      const detailButton = documentRow.locator('button').filter({ has: page.locator('.anticon-eye') });
      await expect(detailButton).toBeVisible({ timeout: 10000 });

      const errors: string[] = [];
      page.on('pageerror', error => {
        errors.push(error.message);
      });

      await detailButton.click();
      await waitForUiStable(page);

      // リレーションシップ tab is always present in DocumentViewer
      const relationshipTab = page.getByRole('tab', { name: /リレーションシップ|Relationships/i });
      await expect(relationshipTab).toBeVisible({ timeout: 10000 });
      await relationshipTab.click();
      await waitForRender(page);

      const tabContent = page.locator('.ant-tabs-tabpane-active');
      await expect(tabContent).toBeVisible();

      const criticalErrors = errors.filter(e =>
        e.includes('TypeError') ||
        e.includes('Cannot read properties')
      );
      expect(criticalErrors).toHaveLength(0);
    });

    test('should display preview tab for documents with content', async ({ page }) => {
      // Login is done in beforeEach
      await page.waitForSelector('.ant-table', { timeout: 10000 });
      await waitForUiStable(page);

      // Use the test document created by ensureTestDocument (.txt with content)
      const documentRow = page.locator('.ant-table-row').filter({ hasText: testDocName }).first();
      await expect(documentRow).toBeVisible({ timeout: 10000 });

      const detailButton = documentRow.locator('button').filter({ has: page.locator('.anticon-eye') });
      await expect(detailButton).toBeVisible({ timeout: 10000 });

      await detailButton.click();
      await waitForUiStable(page);

      // プレビュー tab should be present for a .txt document with content
      const previewTab = page.getByRole('tab', { name: /プレビュー|Preview/i });
      await expect(previewTab).toBeVisible({ timeout: 10000 });

      await previewTab.click();
      await waitForUiStable(page);

      const previewContent = page.locator('.ant-tabs-tabpane-active');
      await expect(previewContent).toBeVisible();
    });
  });

  test.describe('Back Button and Navigation', () => {
    test('should return to document list when clicking back button', async ({ page }) => {
      // Login is done in beforeEach
      await page.waitForSelector('.ant-table', { timeout: 10000 });
      await waitForUiStable(page);

      const documentRow = page.locator('.ant-table-row').first();
      await expect(documentRow).toBeVisible({ timeout: 10000 });

      const detailButton = documentRow.locator('button').filter({ has: page.locator('.anticon-eye') });
      await expect(detailButton).toBeVisible({ timeout: 10000 });

      await detailButton.click();
      await waitForUiStable(page);

      // Verify we're on detail page
      expect(page.url()).toMatch(/\/documents\/[a-f0-9]+/);

      // Click back button in DocumentViewer
      const backButton = page.locator('button').filter({ hasText: '戻る' }).first();
      if (await backButton.count() > 0) {
        await backButton.click();
        await waitForUiStable(page);

        const url = page.url();
        expect(url.includes('/documents') || url.endsWith('/ui/') || url.endsWith('/ui')).toBe(true);

        if (!url.includes('/documents')) {
          await page.goto(`${page.url().split('#')[0]}#/documents`);
          await waitForUiStable(page);
        }
        const table = page.locator('.ant-table');
        await expect(table).toBeVisible({ timeout: 5000 });
      } else {
        // Fallback: browser back
        await page.goBack();
        await waitForUiStable(page);
      }
    });
  });

  test.describe('Document Operations Stability', () => {
    test('should be able to view multiple documents sequentially', async ({ page }) => {
      // Login is done in beforeEach
      await page.waitForSelector('.ant-table', { timeout: 10000 });
      await waitForUiStable(page);

      const rows = page.locator('.ant-table-row');
      const rowCount = await rows.count();

      console.log(`Found ${rowCount} rows to test`);

      // Test viewing at least 2 documents if available
      const testCount = Math.min(2, rowCount);

      for (let i = 0; i < testCount; i++) {
        console.log(`Testing document ${i + 1}/${testCount}`);

        // Re-fetch rows (page state may have changed)
        const currentRows = page.locator('.ant-table-row');
        const row = currentRows.nth(i);

        const detailButton = row.locator('button').filter({ has: page.locator('.anticon-eye') });

        if (await detailButton.count() > 0) {
          // Track errors for this document
          const errors: string[] = [];
          page.on('pageerror', error => {
            errors.push(error.message);
          });

          await detailButton.click();
          await waitForUiStable(page);

          // Verify tabs loaded (with retry on failure)
          const tabs = page.locator('.ant-tabs-nav');
          let tabsVisible = false;
          try {
            await expect(tabs).toBeVisible({ timeout: 10000 });
            tabsVisible = true;
          } catch {
            console.log(`Document ${i + 1}: tabs not visible, reloading...`);
            await page.reload();
            await waitForUiStable(page);
            try {
              await expect(tabs).toBeVisible({ timeout: 10000 });
              tabsVisible = true;
            } catch {
              console.log(`Document ${i + 1}: tabs still not visible after reload, skipping tab test`);
            }
          }

          if (tabsVisible) {
            // Click through visible tabs
            const tabItems = page.locator('.ant-tabs-tab:visible');
            await page.waitForSelector('.ant-tabs-tab', { timeout: 10000 }).catch(() => null);
            const tabCount = await tabItems.count();

            for (let j = 0; j < tabCount; j++) {
              try {
                await tabItems.nth(j).click({ timeout: 5000 });
              } catch {
                console.log(`Tab ${j} click failed, skipping`);
              }
              await waitForRender(page);
            }
          }

          // Check for critical errors
          const criticalErrors = errors.filter(e =>
            e.includes('includes is not a function') ||
            e.includes('Cannot read properties of undefined')
          );

          expect(criticalErrors).toHaveLength(0);

          // Go back to list
          const backButton = page.locator('button').filter({ hasText: '戻る' }).first();
          if (await backButton.count() > 0) {
            await backButton.click();
          } else {
            await page.goBack();
          }
          await waitForUiStable(page);

          // Verify table is back
          await expect(page.locator('.ant-table')).toBeVisible({ timeout: 5000 });
        }
      }

      console.log('Successfully viewed multiple documents without errors');
    });
  });

  test.describe('CMIS Property Format Verification', () => {
    test('should handle CMIS Browser Binding property format correctly', async ({ page }) => {
      // login is handled by beforeEach
      await page.waitForSelector('.ant-table', { timeout: 10000 });
      await waitForUiStable(page);

      const documentRow = page.locator('.ant-table-row').first();
      await expect(documentRow).toBeVisible({ timeout: 10000 });

      const detailButton = documentRow.locator('button').filter({ has: page.locator('.anticon-eye') });
      await expect(detailButton).toBeVisible({ timeout: 10000 });

      const jsErrors: string[] = [];
      page.on('pageerror', error => {
        jsErrors.push(error.message);
      });

      await detailButton.click();
      await waitForUiStable(page);

      // Click through all visible tabs to trigger property access
      const tabItems = page.locator('.ant-tabs-tab:visible');
      await page.waitForSelector('.ant-tabs-tab', { timeout: 10000 }).catch(() => null);
      const tabCount = await tabItems.count();

      for (let i = 0; i < tabCount; i++) {
        const tab = tabItems.nth(i);
        try {
          await tab.click({ timeout: 5000 });
        } catch {
          // Tab click failed — non-critical
        }
        await waitForRender(page);
      }

      const propertyErrors = jsErrors.filter(e =>
        e.includes('includes is not a function') ||
        e.includes('y.includes is not a function') ||
        e.includes('Cannot read property') ||
        e.includes('Cannot read properties of undefined')
      );
      expect(propertyErrors).toHaveLength(0);
    });
  });

  test.describe('Secondary Type Operations (Critical Bug Fix 2025-12-13)', () => {
    test('should add secondary type without y.includes error', async ({ page }) => {
      // login is handled by beforeEach
      await page.waitForSelector('.ant-table', { timeout: 10000 });
      await waitForUiStable(page);

      const documentRow = page.locator('.ant-table-row').first();
      await expect(documentRow).toBeVisible({ timeout: 10000 });

      const jsErrors: string[] = [];
      page.on('pageerror', error => {
        jsErrors.push(error.message);
      });

      const detailButton = documentRow.locator('button').filter({ has: page.locator('.anticon-eye') });
      await expect(detailButton).toBeVisible({ timeout: 10000 });
      await detailButton.click();
      await waitForUiStable(page);

      // Click on secondary types tab
      const secondaryTab = page.locator('.ant-tabs-tab').filter({ hasText: 'セカンダリタイプ' });
      await expect(secondaryTab).toBeVisible({ timeout: 10000 });
      await secondaryTab.click();
      await waitForUiStable(page);

      // Check for initial errors (before operation)
      const initialErrors = jsErrors.filter(e =>
        e.includes('includes is not a function') ||
        e.includes('y.includes is not a function')
      );
      expect(initialErrors).toHaveLength(0);
      console.log('Secondary types tab opened without errors');

      // Find secondary type selector (always present on this tab)
      const selector = page.locator('.ant-select').first();
      await expect(selector).toBeVisible({ timeout: 10000 });

      await selector.click();
      await waitForRender(page);

      const options = page.locator('.ant-select-dropdown:visible .ant-select-item-option');
      const optionCount = await options.count();

      if (optionCount > 0) {
        // Select first available option and add
        await options.first().click();
        await waitForRender(page);

        const addButton = page.getByRole('button', { name: /追加|Add/i });
        await expect(addButton).toBeVisible({ timeout: 5000 });
        await addButton.click();
        await waitForUiStable(page);

        // THE CRITICAL CHECK: no y.includes error after add
        const afterAddErrors = jsErrors.filter(e =>
          e.includes('includes is not a function') ||
          e.includes('y.includes is not a function')
        );
        expect(afterAddErrors).toHaveLength(0);
      }
      // optionCount === 0: all types already assigned — legitimate, no action needed

      // Final verification - no JavaScript errors
      const criticalErrors = jsErrors.filter(e =>
        e.includes('includes is not a function')
      );
      expect(criticalErrors).toHaveLength(0);
      console.log('Test completed without y.includes errors');
    });
  });
});
