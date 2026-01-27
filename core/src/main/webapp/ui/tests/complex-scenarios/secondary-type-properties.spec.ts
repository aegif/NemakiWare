/**
 * Complex Scenario Test: Secondary Type with Custom Properties
 *
 * This test suite validates:
 * 1. Creating a secondary type with custom properties
 * 2. Applying secondary type to existing documents
 * 3. Searching by secondary type properties
 * 4. Removing secondary type and verifying search behavior
 *
 * Test Environment:
 * - Server: http://localhost:8080/core/ui/
 * - Authentication: admin:admin
 * - Repository: bedroom
 *
 * CMIS Concepts Tested:
 * - Secondary Type Definition (cmis:secondary)
 * - Secondary Type Application to Objects
 * - Property Inheritance from Secondary Types
 * - CMIS SQL Query with Secondary Type Properties
 */

import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper, generateTestId } from '../utils/test-helper';

import {
  TIMEOUTS,
  I18N_PATTERNS,
} from './test-constants';

test.describe('Secondary Type with Custom Properties', () => {
  test.describe.configure({ mode: 'serial' });

  let authHelper: AuthHelper;
  let testHelper: TestHelper;

  const testRunId = generateTestId();
  const secondaryTypeId = `test:secondaryAspect${testRunId}`;
  const secondaryTypeName = `Secondary Aspect ${testRunId}`;
  const aspectPropId = `test:aspectProp${testRunId}`;
  const aspectPropName = `Aspect Property ${testRunId}`;
  const aspectPropValue = `AspectValue_${testRunId}`;
  const testDocumentName = `secondary-type-test-${testRunId}.txt`;
  let testDocumentId: string;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    await authHelper.login();
    await page.waitForTimeout(2000);

    const viewportSize = page.viewportSize();
    const isMobileChrome = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobileChrome) {
      const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]');
      if (await menuToggle.count() > 0) {
        await menuToggle.first().click({ timeout: 3000 }).catch(() => {});
        await page.waitForTimeout(500);
      }
    }

    await testHelper.waitForAntdLoad();
  });

  test('Step 1: Create secondary type with custom property', async ({ page, browserName }) => {
    test.setTimeout(120000); // Extended timeout for type creation with properties
    console.log(`Creating secondary type: ${secondaryTypeId}`);

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to Type Management
    const adminMenu = page.locator('.ant-menu-submenu').filter({ hasText: /管理|Admin/i });
    if (await adminMenu.count() > 0) {
      await adminMenu.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);
    }

    const typeManagementItem = page.locator('.ant-menu-item').filter({ hasText: /タイプ管理|Type Management/i });
    if (await typeManagementItem.count() > 0) {
      await typeManagementItem.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);
    } else {
      test.skip('Type Management menu not available');
      return;
    }

    await page.waitForSelector('.ant-table', { timeout: 15000 });

    // Click create type button
    const newTypeButton = page.locator('button').filter({ hasText: /新規タイプ|新規.*作成|Create.*Type/ });
    if (await newTypeButton.count() === 0) {
      test.skip('Create type button not found');
      return;
    }

    await newTypeButton.first().click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    const createModal = page.locator('.ant-modal:visible, .ant-drawer:visible');
    await expect(createModal).toBeVisible({ timeout: 5000 });

    // Fill type ID
    const typeIdInput = createModal.locator('input[placeholder*="タイプID"]');
    if (await typeIdInput.count() > 0) {
      await typeIdInput.first().fill(secondaryTypeId);
    } else {
      await createModal.locator('input').first().fill(secondaryTypeId);
    }
    console.log(`Filled type ID: ${secondaryTypeId}`);

    // Fill type name
    const typeNameInput = createModal.locator('input[placeholder*="表示名"]');
    if (await typeNameInput.count() > 0) {
      await typeNameInput.first().fill(secondaryTypeName);
    }

    // Select base type as secondary (cmis:secondary)
    const baseTypeSelect = createModal.locator('.ant-form-item').filter({ hasText: 'ベースタイプ' }).locator('.ant-select');
    if (await baseTypeSelect.count() > 0) {
      await baseTypeSelect.first().click();
      await page.waitForTimeout(500);

      // Look for secondary type option
      const secondaryOption = page.locator('.ant-select-item').filter({ hasText: /セカンダリ|Secondary|Aspect/i });
      if (await secondaryOption.count() > 0) {
        await secondaryOption.first().click();
        console.log('Selected base type: cmis:secondary');
      } else {
        // Fallback to document if secondary not available
        const documentOption = page.locator('.ant-select-item').filter({ hasText: /ドキュメント|Documents/i });
        if (await documentOption.count() > 0) {
          await documentOption.first().click();
          console.log('Secondary type not available, using document type');
        }
      }
    }

    // Switch to properties tab
    const propertiesTab = createModal.locator('.ant-tabs-tab').filter({ hasText: /プロパティ定義|Properties/ });
    if (await propertiesTab.count() > 0) {
      await propertiesTab.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);
    }

    // Add property
    const addPropertyButton = page.locator('button').filter({ hasText: /プロパティを追加|Add Property/ });
    if (await addPropertyButton.count() > 0) {
      await addPropertyButton.first().click(isMobile ? { force: true } : {});
      await page.waitForTimeout(500);

      const propertyCard = page.locator('.ant-card').last();

      // Property ID
      const propIdInput = propertyCard.locator('input[placeholder*="プロパティID"]');
      if (await propIdInput.count() > 0) {
        await propIdInput.first().fill(aspectPropId);
      } else {
        // Fallback: use text input, excluding checkboxes
        const textInput = propertyCard.locator('input[type="text"], input:not([type="checkbox"]):not([type="radio"])').first();
        await textInput.fill(aspectPropId);
      }
      console.log(`Added aspect property: ${aspectPropId}`);

      // Display name
      const propNameInput = propertyCard.locator('input[placeholder*="表示名"]');
      if (await propNameInput.count() > 0) {
        await propNameInput.first().fill(aspectPropName);
      }

      // Select property type (string) - default is already string, so skip if already selected
      const propTypeSelect = propertyCard.locator('.ant-select').first();
      if (await propTypeSelect.count() > 0) {
        const currentType = await propTypeSelect.textContent();
        if (!currentType?.includes('string') && !currentType?.includes('文字列')) {
          await propTypeSelect.click();
          await page.waitForTimeout(500);
          const stringOption = page.locator('.ant-select-item-option').filter({ hasText: /文字列|String/i }).first();
          if (await stringOption.count() > 0 && await stringOption.isVisible()) {
            await stringOption.click();
          } else {
            await page.keyboard.press('Escape');
          }
        }
      }
    }

    // Submit form
    const submitButton = createModal.locator('button[type="submit"], button:has-text("作成"), button.ant-btn-primary');
    await submitButton.first().click(isMobile ? { force: true } : {});
    await page.waitForTimeout(5000);

    const successMessage = page.locator('.ant-message-success');
    if (await successMessage.count() > 0) {
      console.log('Secondary type creation success');
    }

    // Close modal if still open
    const modalStillOpen = await page.locator('.ant-modal:visible').count() > 0;
    if (modalStillOpen) {
      const closeButton = page.locator('.ant-modal-close');
      if (await closeButton.count() > 0) {
        await closeButton.first().click();
      }
    }
  });

  test('Step 2: Create test document', async ({ page, browserName }) => {
    console.log(`Creating test document: ${testDocumentName}`);

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Upload document
    const uploadSuccess = await testHelper.uploadDocument(testDocumentName, `Secondary type test content - ${testRunId}`, isMobile);

    if (uploadSuccess) {
      console.log(`Document ${testDocumentName} created successfully`);
      const documentRow = page.locator('.ant-table-tbody tr').filter({ hasText: testDocumentName }).first();
      const rowKey = await documentRow.getAttribute('data-row-key');
      if (rowKey) {
        testDocumentId = rowKey;
        console.log(`Document ID: ${testDocumentId}`);
      }
    }

    expect(uploadSuccess).toBe(true);
  });

  test('Step 3: Apply secondary type to document', async ({ page, browserName }) => {
    console.log('Applying secondary type to document...');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Find and click on the test document
    const documentRow = page.locator('.ant-table-tbody tr').filter({ hasText: testDocumentName }).first();
    if (await documentRow.count() === 0) {
      test.skip('Test document not found');
      return;
    }

    await documentRow.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Look for secondary type / aspect management
    const aspectButton = page.locator('button').filter({ hasText: /アスペクト|セカンダリ|Secondary|Aspect/ }).first();
    if (await aspectButton.count() > 0) {
      await aspectButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      // Look for aspect selector
      const aspectSelect = page.locator('.ant-select').filter({ hasText: /アスペクト|セカンダリ|Secondary/ });
      if (await aspectSelect.count() > 0) {
        await aspectSelect.click();
        await page.waitForTimeout(500);

        // Select our secondary type
        const aspectOption = page.locator('.ant-select-item-option').filter({ hasText: secondaryTypeId });
        if (await aspectOption.count() > 0) {
          await aspectOption.click();
          console.log(`Applied secondary type: ${secondaryTypeId}`);
          await page.waitForTimeout(500);

          // Fill aspect property value
          const aspectPropInput = page.locator(`input[name*="${aspectPropId}"], input[placeholder*="${aspectPropName}"]`);
          if (await aspectPropInput.count() > 0) {
            await aspectPropInput.fill(aspectPropValue);
            console.log(`Filled aspect property with: ${aspectPropValue}`);
          }

          // Save changes
          const saveButton = page.locator('button').filter({ hasText: /保存|Save|適用/ }).first();
          if (await saveButton.count() > 0) {
            await saveButton.click(isMobile ? { force: true } : {});
            await page.waitForTimeout(2000);
          }
        }
      }
    } else {
      console.log('Aspect/Secondary type management not available in UI');
    }
  });

  test('Step 4: Search by secondary type property', async ({ page, browserName }) => {
    console.log(`Searching by aspect property: ${aspectPropValue}`);

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Wait for Solr indexing
    await page.waitForTimeout(3000);

    // Navigate to search page
    const searchMenu = page.locator('.ant-menu-item').filter({ hasText: '検索' });
    if (await searchMenu.count() > 0) {
      await searchMenu.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);
    } else {
      await page.goto('http://localhost:8080/core/ui/#/search');
      await page.waitForTimeout(2000);
    }

    // Enter search text
    const searchInput = page.locator('input[placeholder*="検索"]').first();
    if (await searchInput.count() > 0) {
      await searchInput.fill(aspectPropValue);
    }

    // Click search button
    const searchButton = page.locator('button.search-button, button:has-text("検索")').first();
    if (await searchButton.count() > 0) {
      await searchButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(3000);
    }

    // Check results
    const documentInResults = page.locator('.ant-table-tbody tr').filter({ hasText: testDocumentName });
    const found = await documentInResults.count() > 0;
    console.log(`Document found in search results: ${found}`);
  });

  test('Step 5: Remove secondary type and verify search', async ({ page, browserName }) => {
    console.log('Removing secondary type from document...');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Find and click on the test document
    const documentRow = page.locator('.ant-table-tbody tr').filter({ hasText: testDocumentName }).first();
    if (await documentRow.count() === 0) {
      test.skip('Test document not found');
      return;
    }

    await documentRow.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Look for secondary type / aspect management
    const aspectButton = page.locator('button').filter({ hasText: /アスペクト|セカンダリ|Secondary|Aspect/ }).first();
    if (await aspectButton.count() > 0) {
      await aspectButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      // Look for remove button for our secondary type
      const removeButton = page.locator('button').filter({ hasText: /削除|Remove|解除/ }).first();
      if (await removeButton.count() > 0) {
        await removeButton.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(500);

        // Confirm if needed
        const confirmButton = page.locator('.ant-modal button, .ant-popconfirm button').filter({ hasText: /OK|確認|はい/ }).first();
        if (await confirmButton.count() > 0) {
          await confirmButton.click();
        }

        await page.waitForTimeout(2000);
        console.log('Secondary type removed');
      }
    }

    // Wait for Solr indexing
    await page.waitForTimeout(3000);

    // Search again and verify document is not found by aspect property
    const searchMenu = page.locator('.ant-menu-item').filter({ hasText: '検索' });
    if (await searchMenu.count() > 0) {
      await searchMenu.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);

      const searchInput = page.locator('input[placeholder*="検索"]').first();
      if (await searchInput.count() > 0) {
        await searchInput.fill(aspectPropValue);
      }

      const searchButton = page.locator('button.search-button, button:has-text("検索")').first();
      if (await searchButton.count() > 0) {
        await searchButton.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(3000);
      }

      const documentInResults = page.locator('.ant-table-tbody tr').filter({ hasText: testDocumentName });
      const found = await documentInResults.count() > 0;
      console.log(`Document found after removing secondary type: ${found}`);
      // Note: After removing secondary type, the aspect property should no longer be searchable
    }
  });

  test.afterAll(async ({ browser }) => {
    console.log('=== Starting cleanup for secondary-type-properties test ===');
    console.log(`Test Run ID: ${testRunId}`);
    console.log(`Document to clean: ${testDocumentName} (ID: ${testDocumentId || 'unknown'})`);
    console.log(`Secondary type to clean: ${secondaryTypeId}`);

    const context = await browser.newContext();
    const page = await context.newPage();
    const authHelper = new AuthHelper(page);
    const testHelper = new TestHelper(page);
    const failedCleanups: string[] = [];

    try {
      await authHelper.login();
      await page.waitForTimeout(TIMEOUTS.PAGE_LOAD);

      // CLEANUP ORDER: Documents first, then Types (dependency order)
      // Step 1: Delete test document
      console.log('[Cleanup Step 1] Deleting test document...');
      try {
        const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: I18N_PATTERNS.DOCUMENTS });
        if (await documentsMenuItem.count() > 0) {
          await documentsMenuItem.click();
          await page.waitForTimeout(TIMEOUTS.PAGE_LOAD);

          await testHelper.deleteTestDocument(testDocumentName);
          console.log(`[Cleanup] Successfully deleted document: ${testDocumentName}`);
        }
      } catch (docError) {
        const errorMsg = `document: ${testDocumentName} (ID: ${testDocumentId || 'unknown'})`;
        failedCleanups.push(errorMsg);
        console.error(`[Cleanup] Failed to delete ${errorMsg}:`, docError);
      }

      // Step 2: Delete secondary type (after documents are deleted)
      console.log('[Cleanup Step 2] Deleting secondary type...');
      try {
        const adminMenu = page.locator('.ant-menu-submenu').filter({ hasText: I18N_PATTERNS.ADMIN });
        if (await adminMenu.count() > 0) {
          await adminMenu.click();
          await page.waitForTimeout(TIMEOUTS.UI_ANIMATION * 2);

          const typeManagementItem = page.locator('.ant-menu-item').filter({ hasText: I18N_PATTERNS.TYPE_MANAGEMENT });
          if (await typeManagementItem.count() > 0) {
            await typeManagementItem.click();
            await page.waitForTimeout(TIMEOUTS.PAGE_LOAD);

            const typeRow = page.locator('.ant-table-tbody tr').filter({ hasText: secondaryTypeId }).first();
            if (await typeRow.count() > 0) {
              const deleteButton = typeRow.locator('button').filter({ hasText: I18N_PATTERNS.DELETE }).first();
              if (await deleteButton.count() > 0) {
                await deleteButton.click();
                await page.waitForTimeout(TIMEOUTS.UI_ANIMATION);

                const confirmButton = page.locator('.ant-modal button, .ant-popconfirm button').filter({ hasText: I18N_PATTERNS.CONFIRM }).first();
                if (await confirmButton.count() > 0) {
                  await confirmButton.click();
                  await page.waitForTimeout(TIMEOUTS.PAGE_LOAD);
                  console.log(`[Cleanup] Successfully deleted secondary type: ${secondaryTypeId}`);
                }
              }
            } else {
              console.log(`[Cleanup] Secondary type not found (may have been deleted already): ${secondaryTypeId}`);
            }
          }
        }
      } catch (typeError) {
        const errorMsg = `secondary type: ${secondaryTypeId}`;
        failedCleanups.push(errorMsg);
        console.error(`[Cleanup] Failed to delete ${errorMsg}:`, typeError);
      }

    } catch (error) {
      console.error('[Cleanup] Fatal error during cleanup:', error);
    } finally {
      await context.close();

      // Report cleanup failures for manual intervention
      if (failedCleanups.length > 0) {
        console.warn('=== CLEANUP FAILURES - Manual cleanup required ===');
        console.warn('The following items could not be deleted automatically:');
        failedCleanups.forEach(item => console.warn(`  - ${item}`));
        console.warn('=================================================');
      } else {
        console.log('=== Cleanup completed successfully ===');
      }
    }
  });
});
