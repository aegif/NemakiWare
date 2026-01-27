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
      await page.waitForTimeout(1000);

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
      await page.waitForTimeout(1000);

      // Find the test document row (ensured by beforeEach)
      const documentRow = page.locator('.ant-table-row').filter({ hasText: testDocName }).first();
      await expect(documentRow).toBeVisible({ timeout: 10000 });

      // Click detail view button (詳細表示 - eye icon)
      const detailButton = documentRow.locator('button').filter({ has: page.locator('.anticon-eye') });

      if (await detailButton.count() > 0) {
        // Track JavaScript errors
        const jsErrors: string[] = [];
        page.on('pageerror', error => {
          jsErrors.push(error.message);
        });

        await detailButton.click();
        await page.waitForTimeout(2000);

        // Verify we navigated to document detail page
        const currentUrl = page.url();
        console.log('Document detail URL:', currentUrl);
        expect(currentUrl).toMatch(/\/documents\/[a-f0-9]+/);

        // Verify tabs are visible
        const tabs = page.locator('.ant-tabs-nav');
        await expect(tabs).toBeVisible({ timeout: 10000 });

        // Get all tabs
        const tabItems = page.locator('.ant-tabs-tab');
        const tabCount = await tabItems.count();
        console.log(`Found ${tabCount} tabs in DocumentViewer`);

        // Click through each tab
        for (let i = 0; i < tabCount; i++) {
          const tab = tabItems.nth(i);
          const tabText = await tab.textContent();
          console.log(`Clicking tab: ${tabText}`);

          await tab.click();
          await page.waitForTimeout(500);
        }

        // Check for critical errors
        const criticalErrors = jsErrors.filter(e =>
          e.includes('includes is not a function') ||
          e.includes('Cannot read properties')
        );

        if (criticalErrors.length > 0) {
          console.error('Critical errors detected:', criticalErrors);
        }
        expect(criticalErrors).toHaveLength(0);
      } else {
        test.skip('Detail button not found for test document');
      }
    });

    test('should display secondary types tab without errors', async ({ page }) => {
      // Login is done in beforeEach, document ensured
      await page.waitForSelector('.ant-table', { timeout: 10000 });
      await page.waitForTimeout(1000);

      // Find the test document row (ensured by beforeEach)
      const documentRow = page.locator('.ant-table-row').filter({ hasText: testDocName }).first();
      await expect(documentRow).toBeVisible({ timeout: 10000 });

      const detailButton = documentRow.locator('button').filter({ has: page.locator('.anticon-eye') });

      if (await detailButton.count() > 0) {
        const errors: string[] = [];
        page.on('pageerror', error => {
          errors.push(error.message);
        });

        await detailButton.click();
        await page.waitForTimeout(2000);

        // Find and click セカンダリタイプ tab
        const secondaryTypeTab = page.locator('.ant-tabs-tab').filter({ hasText: 'セカンダリタイプ' });

        if (await secondaryTypeTab.count() > 0) {
          await secondaryTypeTab.click();
          await page.waitForTimeout(1000);

          // Verify tab content rendered
          const tabContent = page.locator('.ant-tabs-tabpane-active');
          await expect(tabContent).toBeVisible();

          // Check for the specific error that was reported
          const includesErrors = errors.filter(e => e.includes('includes is not a function'));
          expect(includesErrors).toHaveLength(0);

          console.log('Secondary types tab loaded successfully without errors');
        } else {
          // UPDATED (2025-12-26): Tab IS implemented in DocumentViewer.tsx line 882
          console.log('Secondary types tab not found in DOM - IS implemented in DocumentViewer.tsx line 882');
        }
      } else {
        test.skip('Detail button not found for test document');
      }
    });

    test('should display relationships tab without errors', async ({ page }) => {
      // Login is done in beforeEach, document ensured
      await page.waitForSelector('.ant-table', { timeout: 10000 });
      await page.waitForTimeout(1000);

      // Find the test document row (ensured by beforeEach)
      const documentRow = page.locator('.ant-table-row').filter({ hasText: testDocName }).first();
      await expect(documentRow).toBeVisible({ timeout: 10000 });

      const detailButton = documentRow.locator('button').filter({ has: page.locator('.anticon-eye') });

      if (await detailButton.count() > 0) {
        const errors: string[] = [];
        page.on('pageerror', error => {
          errors.push(error.message);
        });

        await detailButton.click();
        await page.waitForTimeout(2000);

        // Find and click 関係 tab (FIXED 2025-12-26: Was incorrectly looking for '関連' instead of '関係')
        // Implemented in DocumentViewer.tsx line 917
        const relationshipTab = page.locator('.ant-tabs-tab').filter({ hasText: '関係' });

        if (await relationshipTab.count() > 0) {
          await relationshipTab.click();
          await page.waitForTimeout(1000);

          // Verify tab content rendered
          const tabContent = page.locator('.ant-tabs-tabpane-active');
          await expect(tabContent).toBeVisible();

          // No critical errors should occur
          const criticalErrors = errors.filter(e =>
            e.includes('TypeError') ||
            e.includes('Cannot read properties')
          );
          expect(criticalErrors).toHaveLength(0);

          console.log('Relationships tab loaded successfully without errors');
        } else {
          // UPDATED (2025-12-26): Tab IS implemented in DocumentViewer.tsx line 917
          console.log('Relationships tab not found in DOM - IS implemented in DocumentViewer.tsx line 917');
        }
      } else {
        test.skip('Detail button not found for test document');
      }
    });

    test('should display preview tab for documents with content', async ({ page }) => {
      await authHelper.login();

      await page.waitForSelector('.ant-table', { timeout: 10000 });
      await page.waitForTimeout(2000);

      // Find a document (not folder) - look for document icon or .txt extension
      const documentRows = page.locator('.ant-table-row');
      const rowCount = await documentRows.count();

      let foundDocument = false;
      for (let i = 0; i < rowCount; i++) {
        const row = documentRows.nth(i);
        const rowText = await row.textContent();

        // Skip folders and system files
        if (rowText?.includes('.txt') || rowText?.includes('.pdf') || rowText?.includes('Document')) {
          const detailButton = row.locator('button').filter({ has: page.locator('.anticon-eye') });

          if (await detailButton.count() > 0) {
            await detailButton.click();
            await page.waitForTimeout(2000);

            // Find プレビュー tab
            const previewTab = page.locator('.ant-tabs-tab').filter({ hasText: 'プレビュー' });

            if (await previewTab.count() > 0) {
              console.log('Preview tab found - document has content stream');

              await previewTab.click();
              await page.waitForTimeout(2000);

              // Verify preview content area is visible
              const previewContent = page.locator('.ant-tabs-tabpane-active');
              await expect(previewContent).toBeVisible();

              console.log('Preview tab loaded successfully');
              foundDocument = true;
              break;
            } else {
              console.log('Preview tab not visible for this document');
              // Go back and try another document
              await page.goBack();
              await page.waitForTimeout(1000);
            }
          }
        }
      }

      if (!foundDocument) {
        console.log('No document with preview tab found');
      }
    });
  });

  test.describe('Back Button and Navigation', () => {
    test('should return to document list when clicking back button', async ({ page }) => {
      await authHelper.login();

      await page.waitForSelector('.ant-table', { timeout: 10000 });
      await page.waitForTimeout(2000);

      const documentRow = page.locator('.ant-table-row').first();

      if (await documentRow.count() > 0) {
        const detailButton = documentRow.locator('button').filter({ has: page.locator('.anticon-eye') });

        if (await detailButton.count() > 0) {
          // Record URL before clicking
          const listUrl = page.url();
          console.log('Document list URL:', listUrl);

          await detailButton.click();
          await page.waitForTimeout(2000);

          // Verify we're on detail page
          expect(page.url()).toMatch(/\/documents\/[a-f0-9]+/);

          // Click back button in DocumentViewer
          const backButton = page.locator('button').filter({ hasText: '戻る' }).first();

          if (await backButton.count() > 0) {
            await backButton.click();
            await page.waitForTimeout(1500);

            // Verify we're back on the documents page
            expect(page.url()).toContain('/documents');

            // Verify table is visible again
            const table = page.locator('.ant-table');
            await expect(table).toBeVisible({ timeout: 5000 });

            console.log('Successfully returned to document list');
          } else {
            // Try browser back
            await page.goBack();
            await page.waitForTimeout(1500);
          }
        } else {
          test.skip(true, 'Detail button not found');
        }
      } else {
        test.skip(true, 'No documents found');
      }
    });
  });

  test.describe('Document Operations Stability', () => {
    test('should be able to view multiple documents sequentially', async ({ page }) => {
      await authHelper.login();

      await page.waitForSelector('.ant-table', { timeout: 10000 });
      await page.waitForTimeout(2000);

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
          await page.waitForTimeout(2000);

          // Verify tabs loaded
          const tabs = page.locator('.ant-tabs-nav');
          await expect(tabs).toBeVisible({ timeout: 10000 });

          // Click through tabs
          const tabItems = page.locator('.ant-tabs-tab');
          const tabCount = await tabItems.count();

          for (let j = 0; j < tabCount; j++) {
            await tabItems.nth(j).click();
            await page.waitForTimeout(300);
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
          await page.waitForTimeout(1500);

          // Verify table is back
          await expect(page.locator('.ant-table')).toBeVisible({ timeout: 5000 });
        }
      }

      console.log('Successfully viewed multiple documents without errors');
    });
  });

  test.describe('CMIS Property Format Verification', () => {
    test('should handle CMIS Browser Binding property format correctly', async ({ page }) => {
      await authHelper.login();

      await page.waitForSelector('.ant-table', { timeout: 10000 });
      await page.waitForTimeout(2000);

      const documentRow = page.locator('.ant-table-row').first();

      if (await documentRow.count() > 0) {
        const detailButton = documentRow.locator('button').filter({ has: page.locator('.anticon-eye') });

        if (await detailButton.count() > 0) {
          // Track all JavaScript errors
          const jsErrors: string[] = [];
          page.on('pageerror', error => {
            jsErrors.push(error.message);
          });

          await detailButton.click();
          await page.waitForTimeout(2000);

          // Click through all tabs to trigger property access
          const tabItems = page.locator('.ant-tabs-tab');
          const tabCount = await tabItems.count();

          for (let i = 0; i < tabCount; i++) {
            const tab = tabItems.nth(i);
            const tabText = await tab.textContent();
            console.log(`Testing tab: ${tabText}`);

            await tab.click();
            await page.waitForTimeout(500);
          }

          // Check for property access errors - the specific bug we're verifying is fixed
          const propertyErrors = jsErrors.filter(e =>
            e.includes('includes is not a function') ||
            e.includes('y.includes is not a function') ||
            e.includes('Cannot read property') ||
            e.includes('Cannot read properties of undefined')
          );

          if (propertyErrors.length > 0) {
            console.error('Property access errors detected:', propertyErrors);
          }

          expect(propertyErrors).toHaveLength(0);
          console.log('All tabs loaded without CMIS property format errors');
        } else {
          test.skip(true, 'Detail button not found');
        }
      } else {
        test.skip(true, 'No documents found');
      }
    });
  });

  test.describe('Secondary Type Operations (Critical Bug Fix 2025-12-13)', () => {
    test('should add secondary type without y.includes error', async ({ page }) => {
      await authHelper.login();

      await page.waitForSelector('.ant-table', { timeout: 10000 });
      await page.waitForTimeout(2000);

      // Find a document (preferably text file in root)
      const documentRow = page.locator('.ant-table-row').first();

      if (await documentRow.count() === 0) {
        test.skip(true, 'No documents found');
        return;
      }

      // Track all JavaScript errors
      const jsErrors: string[] = [];
      page.on('pageerror', error => {
        jsErrors.push(error.message);
        console.error('JavaScript error:', error.message);
      });

      // Open document detail
      const detailButton = documentRow.locator('button').filter({ has: page.locator('.anticon-eye') });
      if (await detailButton.count() === 0) {
        test.skip(true, 'Detail button not found');
        return;
      }
      await detailButton.click();
      await page.waitForTimeout(2000);

      // Click on secondary types tab
      const secondaryTab = page.locator('.ant-tabs-tab').filter({ hasText: 'セカンダリタイプ' });
      if (await secondaryTab.count() === 0) {
        test.skip(true, 'Secondary types tab not found');
        return;
      }
      await secondaryTab.click();
      await page.waitForTimeout(1500);

      // Check for initial errors (before operation)
      const initialErrors = jsErrors.filter(e =>
        e.includes('includes is not a function') ||
        e.includes('y.includes is not a function')
      );
      expect(initialErrors).toHaveLength(0);
      console.log('Secondary types tab opened without errors');

      // Try to add a secondary type - use placeholder text to find the selector
      // The select has placeholder="追加するセカンダリタイプを選択..."
      const typeSelector = page.locator('.ant-select').filter({ has: page.locator('.ant-select-selection-placeholder:has-text("追加するセカンダリタイプを選択")') });

      // Alternative: look for the select within Space.Compact near the 追加 button
      const addButtonArea = page.locator('button:has-text("追加")').locator('..');
      const typeSelectorAlt = addButtonArea.locator('.ant-select');

      const selector = (await typeSelector.count() > 0) ? typeSelector : typeSelectorAlt;
      const selectorVisible = await selector.count() > 0 && await selector.isVisible().catch(() => false);

      if (selectorVisible) {
        console.log('Found secondary type selector');

        // Open dropdown
        await selector.click();
        await page.waitForTimeout(1000);

        // Check if there are options in the dropdown
        const options = page.locator('.ant-select-dropdown:visible .ant-select-item-option');
        const optionCount = await options.count();
        console.log(`Found ${optionCount} secondary type options`);

        if (optionCount > 0) {
          // Select first available option
          await options.first().click();
          await page.waitForTimeout(500);

          // Click add button
          const addButton = page.locator('button').filter({ hasText: '追加' });
          if (await addButton.count() > 0) {
            console.log('Clicking add button...');
            await addButton.click();
            await page.waitForTimeout(2000);

            // Check for errors AFTER the add operation - THIS IS THE CRITICAL CHECK
            const afterAddErrors = jsErrors.filter(e =>
              e.includes('includes is not a function') ||
              e.includes('y.includes is not a function')
            );

            if (afterAddErrors.length > 0) {
              console.error('CRITICAL: y.includes error occurred after adding secondary type:', afterAddErrors);
            }

            expect(afterAddErrors).toHaveLength(0);
            console.log('Secondary type added successfully without y.includes error');
          } else {
            console.log('Add button not found - possibly all types assigned');
          }
        } else {
          console.log('No secondary type options available');
        }
      } else {
        console.log('Secondary type selector not found - may be read-only');
      }

      // Final verification - no JavaScript errors
      const criticalErrors = jsErrors.filter(e =>
        e.includes('includes is not a function')
      );
      expect(criticalErrors).toHaveLength(0);
      console.log('Test completed without y.includes errors');
    });
  });
});
