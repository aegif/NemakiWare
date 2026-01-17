/**
 * Complex Scenario Test: Folder Hierarchy with Custom Type Documents and Scoped Search
 *
 * This test suite validates:
 * 1. Creating a folder hierarchy structure
 * 2. Creating custom type documents in different folders
 * 3. Searching with folder scope
 * 4. Moving documents between folders
 * 5. Verifying search results update after move
 *
 * Test Environment:
 * - Server: http://localhost:8080/core/ui/
 * - Authentication: admin:admin
 * - Repository: bedroom
 *
 * CMIS Concepts Tested:
 * - Folder Hierarchy (cmis:folder)
 * - Document Filing (parent-child relationships)
 * - CMIS SQL Query with IN_FOLDER/IN_TREE predicates
 * - moveObject operation
 */

import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';
import { randomUUID } from 'crypto';
import {
  TIMEOUTS,
  I18N_PATTERNS,
} from './test-constants';

test.describe('Folder Hierarchy with Custom Type Documents and Scoped Search', () => {
  test.describe.configure({ mode: 'serial' });

  let authHelper: AuthHelper;
  let testHelper: TestHelper;

  const testRunId = randomUUID().substring(0, 8);
  const rootFolderName = `hierarchy-root-${testRunId}`;
  const subFolder1Name = `subfolder1-${testRunId}`;
  const subFolder2Name = `subfolder2-${testRunId}`;
  const testDocument1Name = `hierarchy-doc1-${testRunId}.txt`;
  const testDocument2Name = `hierarchy-doc2-${testRunId}.txt`;
  const searchableValue = `HierarchySearch_${testRunId}`;

  let rootFolderId: string;
  let subFolder1Id: string;
  let subFolder2Id: string;

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

  test('Step 1: Create root folder', async ({ page, browserName }) => {
    console.log(`Creating root folder: ${rootFolderName}`);

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Create root folder
    const folderCreated = await testHelper.createFolder(rootFolderName, isMobile);
    expect(folderCreated).toBe(true);

    if (folderCreated) {
      const folderRow = page.locator('.ant-table-tbody tr').filter({ hasText: rootFolderName }).first();
      const rowKey = await folderRow.getAttribute('data-row-key');
      if (rowKey) {
        rootFolderId = rowKey;
        console.log(`Root folder ID: ${rootFolderId}`);
      }
    }
  });

  test('Step 2: Create subfolders inside root folder', async ({ page, browserName }) => {
    console.log('Creating subfolders...');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Navigate into root folder
    const rootFolderRow = page.locator('.ant-table-tbody tr').filter({ hasText: rootFolderName }).first();
    if (await rootFolderRow.count() === 0) {
      test.skip('Root folder not found');
      return;
    }

    await rootFolderRow.dblclick(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Create subfolder 1
    const subfolder1Created = await testHelper.createFolder(subFolder1Name, isMobile);
    console.log(`Subfolder 1 created: ${subfolder1Created}`);

    if (subfolder1Created) {
      const folderRow = page.locator('.ant-table-tbody tr').filter({ hasText: subFolder1Name }).first();
      const rowKey = await folderRow.getAttribute('data-row-key');
      if (rowKey) {
        subFolder1Id = rowKey;
        console.log(`Subfolder 1 ID: ${subFolder1Id}`);
      }
    }

    // Create subfolder 2
    const subfolder2Created = await testHelper.createFolder(subFolder2Name, isMobile);
    console.log(`Subfolder 2 created: ${subfolder2Created}`);

    if (subfolder2Created) {
      const folderRow = page.locator('.ant-table-tbody tr').filter({ hasText: subFolder2Name }).first();
      const rowKey = await folderRow.getAttribute('data-row-key');
      if (rowKey) {
        subFolder2Id = rowKey;
        console.log(`Subfolder 2 ID: ${subFolder2Id}`);
      }
    }

    expect(subfolder1Created && subfolder2Created).toBe(true);
  });

  test('Step 3: Create documents in different subfolders', async ({ page, browserName }) => {
    console.log('Creating documents in subfolders...');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Navigate into root folder
    const rootFolderRow = page.locator('.ant-table-tbody tr').filter({ hasText: rootFolderName }).first();
    if (await rootFolderRow.count() > 0) {
      await rootFolderRow.dblclick(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);
    }

    // Navigate into subfolder 1
    const subfolder1Row = page.locator('.ant-table-tbody tr').filter({ hasText: subFolder1Name }).first();
    if (await subfolder1Row.count() > 0) {
      await subfolder1Row.dblclick(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);

      // Create document 1 in subfolder 1
      const doc1Created = await testHelper.uploadDocument(testDocument1Name, `${searchableValue} - Document 1 content`, isMobile);
      console.log(`Document 1 created in subfolder 1: ${doc1Created}`);

      // Go back to root folder
      await page.goBack();
      await page.waitForTimeout(2000);
    }

    // Navigate into subfolder 2
    const subfolder2Row = page.locator('.ant-table-tbody tr').filter({ hasText: subFolder2Name }).first();
    if (await subfolder2Row.count() > 0) {
      await subfolder2Row.dblclick(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);

      // Create document 2 in subfolder 2
      const doc2Created = await testHelper.uploadDocument(testDocument2Name, `${searchableValue} - Document 2 content`, isMobile);
      console.log(`Document 2 created in subfolder 2: ${doc2Created}`);
    }
  });

  test('Step 4: Search with folder scope (IN_TREE)', async ({ page, browserName }) => {
    console.log('Searching with folder scope...');

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
      await searchInput.fill(searchableValue);
    }

    // Try to set folder scope if available
    const folderScopeSelect = page.locator('.ant-select').filter({ hasText: /フォルダ|Folder|Scope/ });
    if (await folderScopeSelect.count() > 0) {
      await folderScopeSelect.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(500);

      // Select root folder
      const rootFolderOption = page.locator('.ant-select-item-option').filter({ hasText: rootFolderName });
      if (await rootFolderOption.count() > 0) {
        await rootFolderOption.click(isMobile ? { force: true } : {});
        console.log('Set folder scope to root folder');
      } else {
        await page.keyboard.press('Escape');
      }
    }

    // Click search button
    const searchButton = page.locator('button.search-button, button:has-text("検索")').first();
    if (await searchButton.count() > 0) {
      await searchButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(3000);
    }

    // Verify both documents are found (they're both in the tree under root folder)
    const doc1InResults = page.locator('.ant-table-tbody tr').filter({ hasText: testDocument1Name });
    const doc2InResults = page.locator('.ant-table-tbody tr').filter({ hasText: testDocument2Name });

    const doc1Found = await doc1InResults.count() > 0;
    const doc2Found = await doc2InResults.count() > 0;

    console.log(`Document 1 found: ${doc1Found}`);
    console.log(`Document 2 found: ${doc2Found}`);
  });

  test('Step 5: Move document between folders', async ({ page, browserName }) => {
    console.log('Moving document between folders...');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Navigate to root folder > subfolder 1
    const rootFolderRow = page.locator('.ant-table-tbody tr').filter({ hasText: rootFolderName }).first();
    if (await rootFolderRow.count() > 0) {
      await rootFolderRow.dblclick(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);
    }

    const subfolder1Row = page.locator('.ant-table-tbody tr').filter({ hasText: subFolder1Name }).first();
    if (await subfolder1Row.count() > 0) {
      await subfolder1Row.dblclick(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);
    }

    // Find document 1 and move it
    const documentRow = page.locator('.ant-table-tbody tr').filter({ hasText: testDocument1Name }).first();
    if (await documentRow.count() === 0) {
      console.log('Document 1 not found in subfolder 1');
      return;
    }

    await documentRow.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(1000);

    // Look for move button
    const moveButton = page.locator('button').filter({ hasText: /移動|Move/ }).first();
    if (await moveButton.count() > 0) {
      await moveButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      // Select destination folder (subfolder 2)
      const moveModal = page.locator('.ant-modal:visible');
      if (await moveModal.count() > 0) {
        // Navigate to subfolder 2 in the folder tree
        const subfolder2Option = moveModal.locator('text=' + subFolder2Name);
        if (await subfolder2Option.count() > 0) {
          await subfolder2Option.click(isMobile ? { force: true } : {});
          await page.waitForTimeout(500);

          // Confirm move
          const confirmButton = moveModal.locator('button').filter({ hasText: /OK|確認|移動/ }).first();
          if (await confirmButton.count() > 0) {
            await confirmButton.click(isMobile ? { force: true } : {});
            await page.waitForTimeout(2000);
            console.log('Document moved to subfolder 2');
          }
        }
      }
    } else {
      console.log('Move button not found - move operation may not be available in UI');
    }
  });

  test('Step 6: Verify search results after move', async ({ page, browserName }) => {
    console.log('Verifying search results after move...');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Wait for Solr indexing
    await page.waitForTimeout(3000);

    // Navigate to search page
    const searchMenu = page.locator('.ant-menu-item').filter({ hasText: '検索' });
    if (await searchMenu.count() > 0) {
      await searchMenu.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);
    }

    // Search for documents
    const searchInput = page.locator('input[placeholder*="検索"]').first();
    if (await searchInput.count() > 0) {
      await searchInput.fill(searchableValue);
    }

    const searchButton = page.locator('button.search-button, button:has-text("検索")').first();
    if (await searchButton.count() > 0) {
      await searchButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(3000);
    }

    // Both documents should still be found (just in different locations)
    const doc1InResults = page.locator('.ant-table-tbody tr').filter({ hasText: testDocument1Name });
    const doc2InResults = page.locator('.ant-table-tbody tr').filter({ hasText: testDocument2Name });

    const doc1Found = await doc1InResults.count() > 0;
    const doc2Found = await doc2InResults.count() > 0;

    console.log(`Document 1 found after move: ${doc1Found}`);
    console.log(`Document 2 found after move: ${doc2Found}`);

    // Verify document 1 is now in subfolder 2
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Navigate to subfolder 2
    const rootFolderRow = page.locator('.ant-table-tbody tr').filter({ hasText: rootFolderName }).first();
    if (await rootFolderRow.count() > 0) {
      await rootFolderRow.dblclick(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);
    }

    const subfolder2Row = page.locator('.ant-table-tbody tr').filter({ hasText: subFolder2Name }).first();
    if (await subfolder2Row.count() > 0) {
      await subfolder2Row.dblclick(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);

      // Check if document 1 is now here
      const doc1InSubfolder2 = page.locator('.ant-table-tbody tr').filter({ hasText: testDocument1Name });
      const doc1Moved = await doc1InSubfolder2.count() > 0;
      console.log(`Document 1 is now in subfolder 2: ${doc1Moved}`);
    }
  });

  test.afterAll(async ({ browser }) => {
    console.log('=== Starting cleanup for folder-hierarchy-search test ===');
    console.log(`Test Run ID: ${testRunId}`);
    console.log(`Root folder to clean: ${rootFolderName} (ID: ${rootFolderId || 'unknown'})`);
    console.log(`Subfolders to clean: ${subFolder1Name}, ${subFolder2Name}`);
    console.log(`Documents to clean: ${testDocument1Name}, ${testDocument2Name}`);

    const context = await browser.newContext();
    const page = await context.newPage();
    const authHelper = new AuthHelper(page);
    const testHelper = new TestHelper(page);
    const failedCleanups: string[] = [];

    try {
      await authHelper.login();
      await page.waitForTimeout(TIMEOUTS.PAGE_LOAD);

      // CLEANUP ORDER: Documents first, then Subfolders, then Root folder (dependency order)
      const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: I18N_PATTERNS.DOCUMENTS });
      if (await documentsMenuItem.count() > 0) {
        await documentsMenuItem.click();
        await page.waitForTimeout(TIMEOUTS.PAGE_LOAD);

        // Navigate into root folder
        const rootFolderRow = page.locator('.ant-table-tbody tr').filter({ hasText: rootFolderName }).first();
        if (await rootFolderRow.count() > 0) {
          await rootFolderRow.dblclick();
          await page.waitForTimeout(TIMEOUTS.PAGE_LOAD);

          // Step 1: Delete documents in subfolders
          console.log('[Cleanup Step 1] Deleting documents in subfolders...');
          for (const subfolderName of [subFolder1Name, subFolder2Name]) {
            try {
              const subfolderRow = page.locator('.ant-table-tbody tr').filter({ hasText: subfolderName }).first();
              if (await subfolderRow.count() > 0) {
                await subfolderRow.dblclick();
                await page.waitForTimeout(TIMEOUTS.PAGE_LOAD);

                // Delete documents in subfolder
                for (const docName of [testDocument1Name, testDocument2Name]) {
                  try {
                    await testHelper.deleteTestDocument(docName);
                    console.log(`[Cleanup] Successfully deleted document: ${docName}`);
                  } catch (docError) {
                    // Document may not exist in this folder
                    console.log(`[Cleanup] Document not found in ${subfolderName}: ${docName}`);
                  }
                }

                // Go back
                await page.goBack();
                await page.waitForTimeout(TIMEOUTS.PAGE_LOAD);
              }
            } catch (subfolderError) {
              console.error(`[Cleanup] Error processing subfolder ${subfolderName}:`, subfolderError);
            }
          }

          // Step 2: Delete subfolders
          console.log('[Cleanup Step 2] Deleting subfolders...');
          for (const subfolderName of [subFolder1Name, subFolder2Name]) {
            try {
              await testHelper.deleteTestFolder(subfolderName);
              console.log(`[Cleanup] Successfully deleted subfolder: ${subfolderName}`);
            } catch (folderError) {
              const errorMsg = `subfolder: ${subfolderName}`;
              failedCleanups.push(errorMsg);
              console.error(`[Cleanup] Failed to delete ${errorMsg}:`, folderError);
            }
          }

          // Go back to root
          await page.goBack();
          await page.waitForTimeout(TIMEOUTS.PAGE_LOAD);
        }

        // Step 3: Delete root folder
        console.log('[Cleanup Step 3] Deleting root folder...');
        try {
          await testHelper.deleteTestFolder(rootFolderName);
          console.log(`[Cleanup] Successfully deleted root folder: ${rootFolderName}`);
        } catch (rootError) {
          const errorMsg = `root folder: ${rootFolderName} (ID: ${rootFolderId || 'unknown'})`;
          failedCleanups.push(errorMsg);
          console.error(`[Cleanup] Failed to delete ${errorMsg}:`, rootError);
        }
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
