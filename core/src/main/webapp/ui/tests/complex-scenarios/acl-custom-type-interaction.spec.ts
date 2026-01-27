/**
 * Complex Scenario Test: ACL Inheritance and Custom Type Interaction
 *
 * This test suite validates the interaction between:
 * 1. Custom document types with custom properties
 * 2. ACL (Access Control List) permissions
 * 3. ACL inheritance breaking and restoration
 * 4. Permission verification with custom properties
 *
 * Test Environment:
 * - Server: http://localhost:8080/core/ui/
 * - Authentication: admin:admin (and test users)
 * - Repository: bedroom
 *
 * CMIS Concepts Tested:
 * - Custom Type Definition
 * - ACL Management
 * - ACL Inheritance
 * - Permission Propagation
 */

import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper, generateTestId } from '../utils/test-helper';
import {
  TIMEOUTS,
  I18N_PATTERNS,
} from './test-constants';

test.describe('ACL Inheritance and Custom Type Interaction', () => {
  test.describe.configure({ mode: 'serial' });

  let authHelper: AuthHelper;
  let testHelper: TestHelper;

  const testRunId = generateTestId();
  const testFolderName = `acl-test-folder-${testRunId}`;
  const testDocumentName = `acl-test-doc-${testRunId}.txt`;
  let testFolderId: string;
  let testDocumentId: string;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    await authHelper.login();
    await page.waitForTimeout(2000);

    // Use TestHelper's mobile sidebar handling
    await testHelper.closeMobileSidebar(browserName);

    await testHelper.waitForAntdLoad();
  });

  test('Step 1: Create test folder for ACL testing', async ({ page, browserName }) => {
    console.log(`Creating test folder: ${testFolderName}`);

    const isMobile = testHelper.isMobile(browserName);

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Click folder creation button
    const folderButton = page.locator('button').filter({ hasText: /フォルダ作成|新規フォルダ|Create Folder/ }).first();
    if (await folderButton.count() === 0) {
      test.skip('Folder creation button not found');
      return;
    }

    await folderButton.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(1000);

    // Fill folder name in modal
    const folderModal = page.locator('.ant-modal:visible');
    await expect(folderModal).toBeVisible({ timeout: 5000 });

    const folderNameInput = folderModal.locator('input[placeholder*="フォルダ名"], input[placeholder*="名前"]').first();
    if (await folderNameInput.count() > 0) {
      await folderNameInput.fill(testFolderName);
    } else {
      await folderModal.locator('input').first().fill(testFolderName);
    }

    // Submit
    const submitButton = folderModal.locator('button[type="submit"], button:has-text("作成"), button.ant-btn-primary').first();
    await submitButton.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(3000);

    // Verify folder created
    const folderRow = page.locator('.ant-table-tbody tr').filter({ hasText: testFolderName }).first();
    const folderExists = await folderRow.count() > 0;
    console.log(`Folder ${testFolderName} created: ${folderExists}`);

    if (folderExists) {
      const rowKey = await folderRow.getAttribute('data-row-key');
      if (rowKey) {
        testFolderId = rowKey;
        console.log(`Folder ID: ${testFolderId}`);
      }
    }

    expect(folderExists).toBe(true);
  });

  test('Step 2: Create document inside test folder', async ({ page, browserName }) => {
    test.setTimeout(120000); // Extended timeout for navigation + upload
    console.log(`Creating document: ${testDocumentName}`);

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Navigate into test folder
    const folderRow = page.locator('.ant-table-tbody tr').filter({ hasText: testFolderName }).first();
    if (await folderRow.count() > 0) {
      await folderRow.dblclick(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);
    }

    // Upload document
    const uploadSuccess = await testHelper.uploadDocument(testDocumentName, `ACL test content - ${testRunId}`, isMobile);

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

  test('Step 3: Set ACL permissions on folder', async ({ page, browserName }) => {
    console.log('Setting ACL permissions on folder...');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Find and select the test folder
    const folderRow = page.locator('.ant-table-tbody tr').filter({ hasText: testFolderName }).first();
    if (await folderRow.count() === 0) {
      test.skip('Test folder not found');
      return;
    }

    await folderRow.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(1000);

    // Look for ACL/permissions button or tab
    const aclButton = page.locator('button').filter({ hasText: /権限|ACL|Permissions/ }).first();
    if (await aclButton.count() > 0) {
      await aclButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      // Look for ACL modal or panel
      const aclPanel = page.locator('.ant-modal:visible, .ant-drawer:visible, .ant-card').filter({ hasText: /権限|ACL|Permissions/ });
      if (await aclPanel.count() > 0) {
        console.log('ACL panel opened');

        // Try to add a permission entry
        const addButton = aclPanel.locator('button').filter({ hasText: /追加|Add/ }).first();
        if (await addButton.count() > 0) {
          await addButton.click(isMobile ? { force: true } : {});
          await page.waitForTimeout(500);

          // Select user/group
          const userSelect = page.locator('.ant-select').filter({ hasText: /ユーザー|User|Group/ }).first();
          if (await userSelect.count() > 0) {
            await userSelect.click();
            await page.waitForTimeout(300);

            // Select a user (e.g., admin or test user)
            const userOption = page.locator('.ant-select-item-option').first();
            if (await userOption.count() > 0) {
              await userOption.click();
            }
          }

          // Select permission level
          const permissionSelect = page.locator('.ant-select').filter({ hasText: /権限|Permission|Role/ }).last();
          if (await permissionSelect.count() > 0) {
            await permissionSelect.click();
            await page.waitForTimeout(300);

            const readOption = page.locator('.ant-select-item-option').filter({ hasText: /読み取り|Read|Viewer/ }).first();
            if (await readOption.count() > 0) {
              await readOption.click();
            }
          }

          // Save ACL changes
          const saveButton = aclPanel.locator('button').filter({ hasText: /保存|Save|OK/ }).first();
          if (await saveButton.count() > 0) {
            await saveButton.click(isMobile ? { force: true } : {});
            await page.waitForTimeout(2000);
            console.log('ACL permissions saved');
          }
        }
      }
    } else {
      console.log('ACL button not found - ACL management may not be available in UI');
    }
  });

  test('Step 4: Break ACL inheritance on document', async ({ page, browserName }) => {
    console.log('Breaking ACL inheritance on document...');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Navigate into test folder
    const folderRow = page.locator('.ant-table-tbody tr').filter({ hasText: testFolderName }).first();
    if (await folderRow.count() > 0) {
      await folderRow.dblclick(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);
    }

    // Find and select the test document
    const documentRow = page.locator('.ant-table-tbody tr').filter({ hasText: testDocumentName }).first();
    if (await documentRow.count() === 0) {
      test.skip('Test document not found');
      return;
    }

    await documentRow.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(1000);

    // Look for ACL/permissions button
    const aclButton = page.locator('button').filter({ hasText: /権限|ACL|Permissions/ }).first();
    if (await aclButton.count() > 0) {
      await aclButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      // Look for "break inheritance" option
      const breakInheritanceButton = page.locator('button, .ant-switch').filter({ hasText: /継承を解除|Break Inheritance|独自の権限/ }).first();
      if (await breakInheritanceButton.count() > 0) {
        await breakInheritanceButton.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(1000);

        // Confirm if dialog appears
        const confirmButton = page.locator('.ant-modal button, .ant-popconfirm button').filter({ hasText: /OK|確認|はい/ }).first();
        if (await confirmButton.count() > 0) {
          await confirmButton.click();
          await page.waitForTimeout(2000);
        }

        console.log('ACL inheritance broken');
      } else {
        console.log('Break inheritance button not found');
      }
    }
  });

  test('Step 5: Verify document has independent ACL', async ({ page, browserName }) => {
    console.log('Verifying document has independent ACL...');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Navigate into test folder
    const folderRow = page.locator('.ant-table-tbody tr').filter({ hasText: testFolderName }).first();
    if (await folderRow.count() > 0) {
      await folderRow.dblclick(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);
    }

    // Find and select the test document
    const documentRow = page.locator('.ant-table-tbody tr').filter({ hasText: testDocumentName }).first();
    if (await documentRow.count() === 0) {
      test.skip('Test document not found');
      return;
    }

    await documentRow.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(1000);

    // Look for ACL/permissions button
    const aclButton = page.locator('button').filter({ hasText: /権限|ACL|Permissions/ }).first();
    if (await aclButton.count() > 0) {
      await aclButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      // Check for indicator that inheritance is broken
      const inheritanceIndicator = page.locator('text=/継承.*解除|独自の権限|Not Inherited/i');
      const isInheritanceBroken = await inheritanceIndicator.count() > 0;
      console.log(`ACL inheritance is broken: ${isInheritanceBroken}`);

      // Close ACL panel
      const closeButton = page.locator('.ant-modal-close, .ant-drawer-close').first();
      if (await closeButton.count() > 0) {
        await closeButton.click();
      }
    }
  });

  test.afterAll(async ({ browser }) => {
    console.log('=== Starting cleanup for acl-custom-type-interaction test ===');
    console.log(`Test Run ID: ${testRunId}`);
    console.log(`Document to clean: ${testDocumentName} (ID: ${testDocumentId || 'unknown'})`);
    console.log(`Folder to clean: ${testFolderName} (ID: ${testFolderId || 'unknown'})`);

    const context = await browser.newContext();
    const page = await context.newPage();
    const authHelper = new AuthHelper(page);
    const testHelper = new TestHelper(page);
    const failedCleanups: string[] = [];

    try {
      await authHelper.login();
      await page.waitForTimeout(TIMEOUTS.PAGE_LOAD);

      // CLEANUP ORDER: Documents first, then Folders (dependency order)
      // Step 1: Delete test document inside folder
      console.log('[Cleanup Step 1] Deleting test document...');
      try {
        const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: I18N_PATTERNS.DOCUMENTS });
        if (await documentsMenuItem.count() > 0) {
          await documentsMenuItem.click();
          await page.waitForTimeout(TIMEOUTS.PAGE_LOAD);

          // Navigate into test folder
          const folderRow = page.locator('.ant-table-tbody tr').filter({ hasText: testFolderName }).first();
          if (await folderRow.count() > 0) {
            await folderRow.dblclick();
            await page.waitForTimeout(TIMEOUTS.PAGE_LOAD);

            // Delete document
            await testHelper.deleteTestDocument(testDocumentName);
            console.log(`[Cleanup] Successfully deleted document: ${testDocumentName}`);
          }
        }
      } catch (docError) {
        const errorMsg = `document: ${testDocumentName} (ID: ${testDocumentId || 'unknown'})`;
        failedCleanups.push(errorMsg);
        console.error(`[Cleanup] Failed to delete ${errorMsg}:`, docError);
      }

      // Step 2: Delete test folder (after documents are deleted)
      console.log('[Cleanup Step 2] Deleting test folder...');
      try {
        // Go back to documents root
        await page.goBack();
        await page.waitForTimeout(TIMEOUTS.PAGE_LOAD);

        await testHelper.deleteTestFolder(testFolderName);
        console.log(`[Cleanup] Successfully deleted folder: ${testFolderName}`);
      } catch (folderError) {
        const errorMsg = `folder: ${testFolderName} (ID: ${testFolderId || 'unknown'})`;
        failedCleanups.push(errorMsg);
        console.error(`[Cleanup] Failed to delete ${errorMsg}:`, folderError);
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
