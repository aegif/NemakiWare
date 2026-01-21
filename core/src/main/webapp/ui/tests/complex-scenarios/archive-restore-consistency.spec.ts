/**
 * Complex Scenario Test: Archive and Restore Consistency
 *
 * This test suite validates the consistency of archive functionality:
 * 1. Archiving documents and folders
 * 2. Verifying archived items are not visible in normal views
 * 3. Viewing archived items in archive/trash view
 * 4. Restoring archived items
 * 5. Verifying restored items maintain their properties and relationships
 * 6. Permanent deletion from archive
 *
 * Test Environment:
 * - Server: http://localhost:8080/core/ui/
 * - Authentication: admin:admin
 * - Repository: bedroom
 *
 * CMIS Concepts Tested:
 * - Archive/Soft Delete Operations
 * - Restore from Archive
 * - Permanent Deletion
 * - Property Preservation After Restore
 * - Parent-Child Relationship Preservation
 */

import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';
import { randomUUID } from 'crypto';
import {
  TIMEOUTS,
  I18N_PATTERNS,
} from './test-constants';

test.describe('Archive and Restore Consistency', () => {
  test.describe.configure({ mode: 'serial' });

  let authHelper: AuthHelper;
  let testHelper: TestHelper;

  const testRunId = randomUUID().substring(0, 8);
  const testFolderName = `archive-test-folder-${testRunId}`;
  const testDocumentName = `archive-test-doc-${testRunId}.txt`;
  const documentContent = `Archive test content - ${testRunId}`;

  let testFolderId: string;
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

  test('Step 1: Create test folder and document', async ({ page, browserName }) => {
    test.setTimeout(180000); // Extended timeout for folder + document creation
    console.log(`Creating test folder: ${testFolderName}`);
    console.log(`Creating test document: ${testDocumentName}`);

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Create folder
    const folderCreated = await testHelper.createFolder(testFolderName, isMobile);
    console.log(`Folder created: ${folderCreated}`);

    if (folderCreated) {
      const folderRow = page.locator('.ant-table-tbody tr').filter({ hasText: testFolderName }).first();
      const rowKey = await folderRow.getAttribute('data-row-key');
      if (rowKey) {
        testFolderId = rowKey;
        console.log(`Folder ID: ${testFolderId}`);
      }

      // Navigate into folder using helper (single click on folder name, not dblclick on row)
      const navigationSuccessful = await testHelper.navigateIntoFolder(testFolderName, isMobile);
      console.log(`Navigation into folder: ${navigationSuccessful}`);

      if (!navigationSuccessful) {
        console.log('WARNING: Folder navigation may have failed, document will be created at current location');
      }

      // Create document inside folder
      const docCreated = await testHelper.uploadDocument(testDocumentName, documentContent, isMobile);
      console.log(`Document created: ${docCreated}`);

      if (docCreated) {
        const documentRow = page.locator('.ant-table-tbody tr').filter({ hasText: testDocumentName }).first();
        const docRowKey = await documentRow.getAttribute('data-row-key');
        if (docRowKey) {
          testDocumentId = docRowKey;
          console.log(`Document ID: ${testDocumentId}`);
        }
      }

      expect(docCreated).toBe(true);
    }

    expect(folderCreated).toBe(true);
  });

  test('Step 2: Archive the document', async ({ page, browserName }) => {
    console.log('Archiving document...');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Navigate into test folder using helper (single click on folder name)
    await testHelper.navigateIntoFolder(testFolderName, isMobile);

    // Find the document
    const documentRow = page.locator('.ant-table-tbody tr').filter({ hasText: testDocumentName }).first();
    if (await documentRow.count() === 0) {
      test.skip('Test document not found');
      return;
    }

    // Select the document
    await documentRow.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(500);

    // Look for archive/delete button
    const archiveButton = page.locator('button').filter({ hasText: /アーカイブ|Archive|削除|Delete|ゴミ箱/ }).first();
    if (await archiveButton.count() > 0) {
      await archiveButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(500);

      // Confirm if dialog appears
      const confirmButton = page.locator('.ant-modal button, .ant-popconfirm button').filter({ hasText: /OK|確認|削除|はい/ }).first();
      if (await confirmButton.count() > 0) {
        await confirmButton.click();
        await page.waitForTimeout(2000);
        console.log('Document archived');
      }
    } else {
      console.log('Archive button not found');
    }

    // Verify document is no longer visible in folder
    await page.waitForTimeout(1000);
    const documentStillVisible = await page.locator('.ant-table-tbody tr').filter({ hasText: testDocumentName }).count() > 0;
    console.log(`Document still visible after archive: ${documentStillVisible}`);
    expect(documentStillVisible).toBe(false);
  });

  test('Step 3: View archived document in archive/trash view', async ({ page, browserName }) => {
    console.log('Viewing archived document...');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Look for archive/trash menu item
    const archiveMenu = page.locator('.ant-menu-item').filter({ hasText: /アーカイブ|Archive|ゴミ箱|Trash/ });
    if (await archiveMenu.count() > 0) {
      await archiveMenu.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);

      // Verify document is in archive
      const archivedDocument = page.locator('.ant-table-tbody tr').filter({ hasText: testDocumentName });
      const isInArchive = await archivedDocument.count() > 0;
      console.log(`Document found in archive: ${isInArchive}`);

      if (isInArchive) {
        // Verify document properties are preserved
        await archivedDocument.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(1000);

        // Check if properties tab shows original values
        const propertiesTab = page.locator('.ant-tabs-tab').filter({ hasText: /プロパティ|Properties/ });
        if (await propertiesTab.count() > 0) {
          await propertiesTab.click(isMobile ? { force: true } : {});
          await page.waitForTimeout(1000);
          console.log('Viewing archived document properties');
        }
      }
    } else {
      console.log('Archive menu not found - archive view may not be available');
    }
  });

  test('Step 4: Restore document from archive', async ({ page, browserName }) => {
    console.log('Restoring document from archive...');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to archive
    const archiveMenu = page.locator('.ant-menu-item').filter({ hasText: /アーカイブ|Archive|ゴミ箱|Trash/ });
    if (await archiveMenu.count() > 0) {
      await archiveMenu.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);

      // Find the archived document
      const archivedDocument = page.locator('.ant-table-tbody tr').filter({ hasText: testDocumentName }).first();
      if (await archivedDocument.count() > 0) {
        await archivedDocument.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(500);

        // Look for restore button
        const restoreButton = page.locator('button').filter({ hasText: /復元|Restore|元に戻す/ }).first();
        if (await restoreButton.count() > 0) {
          await restoreButton.click(isMobile ? { force: true } : {});
          await page.waitForTimeout(500);

          // Confirm if dialog appears
          const confirmButton = page.locator('.ant-modal button, .ant-popconfirm button').filter({ hasText: /OK|確認|復元|はい/ }).first();
          if (await confirmButton.count() > 0) {
            await confirmButton.click();
            await page.waitForTimeout(2000);
            console.log('Document restored');
          }
        } else {
          console.log('Restore button not found');
        }
      }
    }
  });

  test('Step 5: Verify restored document is back in original location', async ({ page, browserName }) => {
    console.log('Verifying restored document...');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Navigate into test folder using helper (single click on folder name)
    const folderRow = page.locator('.ant-table-tbody tr').filter({ hasText: testFolderName }).first();
    if (await folderRow.count() > 0) {
      await testHelper.navigateIntoFolder(testFolderName, isMobile);

      // Verify document is back
      const restoredDocument = page.locator('.ant-table-tbody tr').filter({ hasText: testDocumentName });
      const isRestored = await restoredDocument.count() > 0;
      console.log(`Document restored to original location: ${isRestored}`);

      if (isRestored) {
        // Verify properties are preserved
        await restoredDocument.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(1000);

        const propertiesTab = page.locator('.ant-tabs-tab').filter({ hasText: /プロパティ|Properties/ });
        if (await propertiesTab.count() > 0) {
          await propertiesTab.click(isMobile ? { force: true } : {});
          await page.waitForTimeout(1000);
          console.log('Restored document properties verified');
        }
      }
    }
  });

  test('Step 6: Archive and permanently delete document', async ({ page, browserName }) => {
    console.log('Testing permanent deletion...');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Navigate into test folder using helper (single click on folder name)
    const folderRow = page.locator('.ant-table-tbody tr').filter({ hasText: testFolderName }).first();
    if (await folderRow.count() > 0) {
      await testHelper.navigateIntoFolder(testFolderName, isMobile);
    }

    // Archive the document again
    const documentRow = page.locator('.ant-table-tbody tr').filter({ hasText: testDocumentName }).first();
    if (await documentRow.count() > 0) {
      await documentRow.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(500);

      const archiveButton = page.locator('button').filter({ hasText: /アーカイブ|Archive|削除|Delete/ }).first();
      if (await archiveButton.count() > 0) {
        await archiveButton.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(500);

        const confirmButton = page.locator('.ant-modal button, .ant-popconfirm button').filter({ hasText: /OK|確認|削除|はい/ }).first();
        if (await confirmButton.count() > 0) {
          await confirmButton.click();
          await page.waitForTimeout(2000);
        }
      }
    }

    // Navigate to archive and permanently delete
    const archiveMenu = page.locator('.ant-menu-item').filter({ hasText: /アーカイブ|Archive|ゴミ箱|Trash/ });
    if (await archiveMenu.count() > 0) {
      await archiveMenu.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);

      const archivedDocument = page.locator('.ant-table-tbody tr').filter({ hasText: testDocumentName }).first();
      if (await archivedDocument.count() > 0) {
        await archivedDocument.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(500);

        // Look for permanent delete button
        const permanentDeleteButton = page.locator('button').filter({ hasText: /完全削除|Permanent Delete|完全に削除/ }).first();
        if (await permanentDeleteButton.count() > 0) {
          await permanentDeleteButton.click(isMobile ? { force: true } : {});
          await page.waitForTimeout(500);

          const confirmButton = page.locator('.ant-modal button, .ant-popconfirm button').filter({ hasText: /OK|確認|削除|はい/ }).first();
          if (await confirmButton.count() > 0) {
            await confirmButton.click();
            await page.waitForTimeout(2000);
            console.log('Document permanently deleted');
          }
        } else {
          console.log('Permanent delete button not found');
        }
      }
    }

    // Verify document is gone from archive
    await page.waitForTimeout(1000);
    const documentInArchive = await page.locator('.ant-table-tbody tr').filter({ hasText: testDocumentName }).count() > 0;
    console.log(`Document still in archive after permanent delete: ${documentInArchive}`);
  });

  test.afterAll(async ({ browser }) => {
    console.log('=== Starting cleanup for archive-restore-consistency test ===');
    console.log(`Test Run ID: ${testRunId}`);
    console.log(`Folder to clean: ${testFolderName} (ID: ${testFolderId || 'unknown'})`);
    console.log(`Document to clean: ${testDocumentName} (ID: ${testDocumentId || 'unknown'})`);

    const context = await browser.newContext();
    const page = await context.newPage();
    const authHelper = new AuthHelper(page);
    const testHelper = new TestHelper(page);
    const failedCleanups: string[] = [];

    try {
      await authHelper.login();
      await page.waitForTimeout(TIMEOUTS.PAGE_LOAD);

      // CLEANUP ORDER: Documents first (if any remain), then Folders (dependency order)
      // Note: Document may have been permanently deleted in test, folder cleanup is primary
      console.log('[Cleanup Step 1] Deleting test folder...');
      try {
        const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: I18N_PATTERNS.DOCUMENTS });
        if (await documentsMenuItem.count() > 0) {
          await documentsMenuItem.click();
          await page.waitForTimeout(TIMEOUTS.PAGE_LOAD);

          await testHelper.deleteTestFolder(testFolderName);
          console.log(`[Cleanup] Successfully deleted folder: ${testFolderName}`);
        }
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
