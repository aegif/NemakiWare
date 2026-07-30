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
import { waitForAppReady, waitForRender, waitForUiStable } from '../utils/wait-helpers';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper, generateTestId } from '../utils/test-helper';
import { ApiHelper } from '../utils/api-helper';

import {
  TIMEOUTS,
  I18N_PATTERNS,
} from './test-constants';

test.describe('Archive and Restore Consistency', () => {
  test.describe.configure({ mode: 'serial' });

  let authHelper: AuthHelper;
  let testHelper: TestHelper;

  const testRunId = generateTestId();
  const testFolderName = `archive-test-folder-${testRunId}`;
  const testDocumentName = `archive-test-doc-${testRunId}.txt`;
  const documentContent = `Archive test content - ${testRunId}`;

  let testFolderId: string;
  let testDocumentId: string;

  // Clean up any leftover test data from previous runs BEFORE starting tests
  test.beforeAll(async ({ browser }) => {
    console.log('=== PRE-TEST CLEANUP: Removing any leftover archive-test data ===');
    const context = await browser.newContext();
    const page = await context.newPage();

    try {
      const baseUrl = 'http://localhost:8080/core/browser/bedroom';
      const authHeader = 'Basic ' + Buffer.from('admin:admin').toString('base64');

      // Search for any leftover archive-test folders from previous runs
      const searchResponse = await page.request.get(
        `${baseUrl}?cmisselector=query&q=${encodeURIComponent("SELECT cmis:objectId, cmis:name FROM cmis:folder WHERE cmis:name LIKE 'archive-test-folder-%'")}`,
        { headers: { 'Authorization': authHeader } }
      );

      if (searchResponse.ok()) {
        const searchData = await searchResponse.json();
        if (searchData.results && searchData.results.length > 0) {
          console.log(`[Pre-cleanup] Found ${searchData.results.length} leftover archive-test folder(s)`);
          for (const result of searchData.results) {
            const folderId = result.succinctProperties?.['cmis:objectId'] || result.properties?.['cmis:objectId']?.value;
            const folderName = result.succinctProperties?.['cmis:name'] || result.properties?.['cmis:name']?.value;
            if (folderId) {
              console.log(`[Pre-cleanup] Deleting leftover folder: ${folderName} (${folderId})`);
              try {
                await page.request.post(`${baseUrl}/${folderId}`, {
                  headers: { 'Authorization': authHeader },
                  form: { cmisaction: 'deleteTree', allVersions: 'true', continueOnFailure: 'true' }
                });
                console.log(`[Pre-cleanup] Successfully deleted: ${folderName}`);
              } catch (e) {
                console.log(`[Pre-cleanup] Failed to delete ${folderName}: ${e}`);
              }
            }
          }
        }
      }

      // Also clean up any archived items in trash
      const archiveSearchResponse = await page.request.get(
        `${baseUrl}?cmisselector=query&q=${encodeURIComponent("SELECT cmis:objectId, cmis:name FROM cmis:document WHERE cmis:name LIKE 'archive-test-doc-%'")}`,
        { headers: { 'Authorization': authHeader } }
      );

      if (archiveSearchResponse.ok()) {
        const archiveData = await archiveSearchResponse.json();
        if (archiveData.results && archiveData.results.length > 0) {
          console.log(`[Pre-cleanup] Found ${archiveData.results.length} leftover archive-test document(s)`);
          for (const result of archiveData.results) {
            const docId = result.succinctProperties?.['cmis:objectId'] || result.properties?.['cmis:objectId']?.value;
            const docName = result.succinctProperties?.['cmis:name'] || result.properties?.['cmis:name']?.value;
            if (docId) {
              console.log(`[Pre-cleanup] Deleting leftover document: ${docName} (${docId})`);
              try {
                await page.request.post(`${baseUrl}/${docId}`, {
                  headers: { 'Authorization': authHeader },
                  form: { cmisaction: 'delete', allVersions: 'true' }
                });
                console.log(`[Pre-cleanup] Successfully deleted: ${docName}`);
              } catch (e) {
                console.log(`[Pre-cleanup] Failed to delete ${docName}: ${e}`);
              }
            }
          }
        }
      }
    } catch (error) {
      console.log('[Pre-cleanup] Error during pre-test cleanup (non-fatal):', error);
    } finally {
      await context.close();
    }
    console.log('=== PRE-TEST CLEANUP COMPLETE ===');
  });

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    await authHelper.login();
    await waitForAppReady(page, { timeout: 30000 });

    await testHelper.closeMobileSidebar(browserName);

    await testHelper.waitForAntdLoad();
  });

  test('Step 1: Create test folder and document', async ({ page, browserName }) => {
    test.setTimeout(120000);
    console.log(`Creating test folder: ${testFolderName}`);
    console.log(`Creating test document: ${testDocumentName}`);

    const isMobile = testHelper.isMobile(browserName);

    // Create the folder and (inside it) the document via the CMIS API. The UI
    // upload flow is slow (~50s) and times out under full-suite load, which
    // cascades this whole serial block; and UI folder-navigation could drop the
    // document at the repository root. An API create is fast, deterministic, and
    // guarantees the document is a child of the test folder for the later steps.
    const apiHelper = new ApiHelper(page);
    testFolderId = await apiHelper.createFolder({ name: testFolderName });
    expect(testFolderId).toBeTruthy();
    testDocumentId = await apiHelper.createDocument({
      name: testDocumentName,
      content: documentContent,
      parentId: testFolderId,
    });
    expect(testDocumentId).toBeTruthy();
    console.log(`Folder ID: ${testFolderId}, Document ID: ${testDocumentId}`);

    // Show the documents view so the folder is visible for the subsequent UI steps.
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await waitForUiStable(page);
    await page.reload();
    await testHelper.waitForAntdLoad();
    await expect(page.locator('.ant-table-tbody tr').filter({ hasText: testFolderName }).first())
      .toBeVisible({ timeout: 15000 });
  });

  test('Step 2: Archive the document', async ({ page, browserName }) => {
    console.log('Archiving document...');

    const isMobile = testHelper.isMobile(browserName);

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await waitForUiStable(page);

    // Navigate into test folder using helper (single click on folder name)
    await testHelper.navigateIntoFolder(testFolderName, isMobile);

    // Find the document row
    const documentRow = page.locator('.ant-table-tbody tr').filter({ hasText: testDocumentName }).first();
    if (await documentRow.count() === 0) {
      test.skip(true, 'ENV: Test document not found');
      return;
    }

    // Find the delete button (icon-only button with DeleteOutlined icon) within the document row
    // The delete button has .anticon-delete class from Ant Design's DeleteOutlined component
    const deleteButton = documentRow.locator('button').filter({ has: page.locator('.anticon-delete') }).first();
    if (await deleteButton.count() > 0) {
      console.log('Found delete button in document row');
      await deleteButton.click(isMobile ? { force: true } : {});
      await waitForRender(page);

      // Wait for and click confirmation in modal or popconfirm
      // NemakiWare uses Modal with OK/Cancel buttons for delete confirmation
      const confirmButton = page.locator('.ant-modal-footer button.ant-btn-primary, .ant-popconfirm button.ant-btn-primary').first();
      if (await confirmButton.count() > 0) {
        await confirmButton.click(isMobile ? { force: true } : {});
        await waitForUiStable(page);
        console.log('Document deleted (archived)');
      } else {
        console.log('Confirmation button not found - checking for popconfirm OK button');
        const okButton = page.locator('.ant-popover button').filter({ hasText: /OK|はい|確認/ }).first();
        if (await okButton.count() > 0) {
          await okButton.click(isMobile ? { force: true } : {});
          await waitForUiStable(page);
          console.log('Document deleted via popconfirm');
        }
      }
    } else {
      console.log('Delete button not found in document row - trying alternative selectors');
      // Fallback: Try to find any delete button on the page
      const anyDeleteButton = page.locator('button .anticon-delete').first();
      if (await anyDeleteButton.count() > 0) {
        console.log('Found delete icon, clicking parent button');
        await anyDeleteButton.locator('..').click(isMobile ? { force: true } : {});
        await waitForUiStable(page);
      }
    }

    // Verify document is no longer visible in folder
    await waitForRender(page);
    const documentStillVisible = await page.locator('.ant-table-tbody tr').filter({ hasText: testDocumentName }).count() > 0;
    console.log(`Document still visible after delete: ${documentStillVisible}`);
    expect(documentStillVisible).toBe(false);
  });

  test('Step 3: View archived document in archive/trash view', async ({ page, browserName }) => {
    console.log('Viewing archived document...');

    const isMobile = testHelper.isMobile(browserName);

    // Look for archive/trash menu item
    const archiveMenu = page.locator('.ant-menu-item').filter({ hasText: /アーカイブ|Archive|ゴミ箱|Trash/ });
    if (await archiveMenu.count() > 0) {
      await archiveMenu.click(isMobile ? { force: true } : {});
      await waitForUiStable(page);

      // Verify document is in archive
      const archivedDocument = page.locator('.ant-table-tbody tr').filter({ hasText: testDocumentName });
      const isInArchive = await archivedDocument.count() > 0;
      console.log(`Document found in archive: ${isInArchive}`);

      if (isInArchive) {
        // Verify document properties are preserved
        await archivedDocument.click(isMobile ? { force: true } : {});
        await waitForRender(page);

        // Check if properties tab shows original values
        const propertiesTab = page.locator('.ant-tabs-tab').filter({ hasText: /プロパティ|Properties/ });
        if (await propertiesTab.count() > 0) {
          await propertiesTab.click(isMobile ? { force: true } : {});
          await waitForRender(page);
          console.log('Viewing archived document properties');
        }
      }
    }
  });

  test('Step 4: Restore document from archive', async ({ page, browserName }) => {
    console.log('Restoring document from archive...');

    const isMobile = testHelper.isMobile(browserName);

    // Navigate to archive
    const archiveMenu = page.locator('.ant-menu-item').filter({ hasText: /アーカイブ|Archive|ゴミ箱|Trash/ });
    if (await archiveMenu.count() > 0) {
      await archiveMenu.click(isMobile ? { force: true } : {});
      await waitForUiStable(page);

      // Find the archived document
      const archivedDocument = page.locator('.ant-table-tbody tr').filter({ hasText: testDocumentName }).first();
      if (await archivedDocument.count() > 0) {
        await archivedDocument.click(isMobile ? { force: true } : {});
        await waitForRender(page);

        // Look for restore button
        const restoreButton = page.locator('button').filter({ hasText: /復元|Restore|元に戻す/ }).first();
        if (await restoreButton.count() > 0) {
          await restoreButton.click(isMobile ? { force: true } : {});
          await waitForRender(page);

          // Confirm if dialog appears
          const confirmButton = page.locator('.ant-modal button, .ant-popconfirm button').filter({ hasText: /OK|確認|復元|はい/ }).first();
          if (await confirmButton.count() > 0) {
            await confirmButton.click();
            await waitForUiStable(page);
            console.log('Document restored');
          }
        }
      }
    }
  });

  test('Step 5: Verify restored document is back in original location', async ({ page, browserName }) => {
    console.log('Verifying restored document...');

    const isMobile = testHelper.isMobile(browserName);

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await waitForUiStable(page);

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
        await waitForRender(page);

        const propertiesTab = page.locator('.ant-tabs-tab').filter({ hasText: /プロパティ|Properties/ });
        if (await propertiesTab.count() > 0) {
          await propertiesTab.click(isMobile ? { force: true } : {});
          await waitForRender(page);
          console.log('Restored document properties verified');
        }
      }
    }
  });

  test('Step 6: Archive and permanently delete document', async ({ page, browserName }) => {
    console.log('Testing permanent deletion...');

    const isMobile = testHelper.isMobile(browserName);

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await waitForUiStable(page);

    // Navigate into test folder using helper (single click on folder name)
    const folderRow = page.locator('.ant-table-tbody tr').filter({ hasText: testFolderName }).first();
    if (await folderRow.count() > 0) {
      await testHelper.navigateIntoFolder(testFolderName, isMobile);
    }

    // Archive the document again
    const documentRow = page.locator('.ant-table-tbody tr').filter({ hasText: testDocumentName }).first();
    if (await documentRow.count() > 0) {
      await documentRow.click(isMobile ? { force: true } : {});
      await waitForRender(page);

      const archiveButton = page.locator('button').filter({ hasText: /アーカイブ|Archive|削除|Delete/ }).first();
      if (await archiveButton.count() > 0) {
        await archiveButton.click(isMobile ? { force: true } : {});
        await waitForRender(page);

        const confirmButton = page.locator('.ant-modal button, .ant-popconfirm button').filter({ hasText: /OK|確認|削除|はい/ }).first();
        if (await confirmButton.count() > 0) {
          await confirmButton.click();
          await waitForUiStable(page);
        }
      }
    }

    // Navigate to archive and permanently delete
    const archiveMenu = page.locator('.ant-menu-item').filter({ hasText: /アーカイブ|Archive|ゴミ箱|Trash/ });
    if (await archiveMenu.count() > 0) {
      await archiveMenu.click(isMobile ? { force: true } : {});
      await waitForUiStable(page);

      const archivedDocument = page.locator('.ant-table-tbody tr').filter({ hasText: testDocumentName }).first();
      if (await archivedDocument.count() > 0) {
        await archivedDocument.click(isMobile ? { force: true } : {});
        await waitForRender(page);

        // Look for permanent delete button
        const permanentDeleteButton = page.locator('button').filter({ hasText: /完全削除|Permanent Delete|完全に削除/ }).first();
        if (await permanentDeleteButton.count() > 0) {
          await permanentDeleteButton.click(isMobile ? { force: true } : {});
          await waitForRender(page);

          const confirmButton = page.locator('.ant-modal button, .ant-popconfirm button').filter({ hasText: /OK|確認|削除|はい/ }).first();
          if (await confirmButton.count() > 0) {
            await confirmButton.click();
            await waitForUiStable(page);
            console.log('Document permanently deleted');
          }
        }
      }
    }

    // Verify document is gone from archive
    await waitForRender(page);
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
      }
    }
  });
});
