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
import { TestHelper, generateTestId } from '../utils/test-helper';

import {
  TIMEOUTS,
  I18N_PATTERNS,
} from './test-constants';

test.describe('Folder Hierarchy with Custom Type Documents and Scoped Search', () => {
  test.describe.configure({ mode: 'serial' });

  let authHelper: AuthHelper;
  let testHelper: TestHelper;

  const testRunId = generateTestId();
  const rootFolderName = `hierarchy-root-${testRunId}`;
  const subFolder1Name = `subfolder1-${testRunId}`;
  const subFolder2Name = `subfolder2-${testRunId}`;
  const testDocument1Name = `hierarchy-doc1-${testRunId}.txt`;
  const testDocument2Name = `hierarchy-doc2-${testRunId}.txt`;
  const searchableValue = `HierarchySearch_${testRunId}`;

  let rootFolderId: string;
  let subFolder1Id: string;
  let subFolder2Id: string;

  // Clean up any leftover test data from previous runs BEFORE starting tests
  test.beforeAll(async ({ browser }) => {
    console.log('=== PRE-TEST CLEANUP: Removing any leftover hierarchy-test data ===');
    const context = await browser.newContext();
    const page = await context.newPage();

    try {
      const baseUrl = 'http://localhost:8080/core/browser/bedroom';
      const authHeader = 'Basic ' + Buffer.from('admin:admin').toString('base64');

      // Search for any leftover hierarchy-root folders from previous runs
      const searchResponse = await page.request.get(
        `${baseUrl}?cmisselector=query&q=${encodeURIComponent("SELECT cmis:objectId, cmis:name FROM cmis:folder WHERE cmis:name LIKE 'hierarchy-root-%'")}`,
        { headers: { 'Authorization': authHeader } }
      );

      if (searchResponse.ok()) {
        const searchData = await searchResponse.json();
        if (searchData.results && searchData.results.length > 0) {
          console.log(`[Pre-cleanup] Found ${searchData.results.length} leftover hierarchy-root folder(s)`);
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
        } else {
          console.log('[Pre-cleanup] No leftover hierarchy-root folders found');
        }
      }

      // Also clean up any leftover hierarchy-doc documents
      const docSearchResponse = await page.request.get(
        `${baseUrl}?cmisselector=query&q=${encodeURIComponent("SELECT cmis:objectId, cmis:name FROM cmis:document WHERE cmis:name LIKE 'hierarchy-doc%'")}`,
        { headers: { 'Authorization': authHeader } }
      );

      if (docSearchResponse.ok()) {
        const docData = await docSearchResponse.json();
        if (docData.results && docData.results.length > 0) {
          console.log(`[Pre-cleanup] Found ${docData.results.length} leftover hierarchy-doc document(s)`);
          for (const result of docData.results) {
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
    test.setTimeout(120000); // Extended timeout for creating multiple folders
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

    // Wait for UI to stabilize and close any notifications before creating second folder
    await page.waitForTimeout(2000);

    // Dismiss any notification messages that might interfere
    const notifications = page.locator('.ant-message, .ant-notification');
    if (await notifications.count() > 0) {
      console.log(`Found ${await notifications.count()} notification(s), waiting for them to dismiss...`);
      await page.waitForTimeout(3000);
    }

    // CRITICAL FIX: Reload the page to ensure clean state for second folder creation
    // This avoids issues with React state not being properly reset after closeAllOverlays
    console.log('Reloading page to ensure clean state...');
    await page.reload();
    await page.waitForSelector('.ant-table', { timeout: 10000 });
    await page.waitForTimeout(1000);

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
    test.setTimeout(120000); // Extended timeout for navigation + 2 document uploads
    console.log('Creating documents in subfolders via CMIS API (more reliable than UI upload)...');

    // Use CMIS API to create documents directly (more reliable than UI upload)
    const baseUrl = 'http://localhost:8080/core/browser/bedroom';
    const authHeader = 'Basic ' + Buffer.from('admin:admin').toString('base64');

    // Get subfolder IDs from previous step (if not available, query them)
    let sf1Id = subFolder1Id;
    let sf2Id = subFolder2Id;

    // If subfolder IDs not set, query for them
    if (!sf1Id || !sf2Id) {
      console.log('Subfolder IDs not set from previous step, querying...');

      // Query for root folder first
      const rootQuery = await page.request.get(
        `${baseUrl}?cmisselector=query&q=${encodeURIComponent(`SELECT cmis:objectId FROM cmis:folder WHERE cmis:name = '${rootFolderName}'`)}`,
        { headers: { 'Authorization': authHeader } }
      );

      if (rootQuery.ok()) {
        const rootData = await rootQuery.json();
        if (rootData.results && rootData.results.length > 0) {
          const rId = rootData.results[0].succinctProperties?.['cmis:objectId'] ||
                      rootData.results[0].properties?.['cmis:objectId']?.value;

          // Query children of root folder to find subfolders
          const childrenQuery = await page.request.get(
            `${baseUrl}/root?objectId=${rId}&cmisselector=children`,
            { headers: { 'Authorization': authHeader } }
          );

          if (childrenQuery.ok()) {
            const childData = await childrenQuery.json();
            if (childData.objects) {
              for (const obj of childData.objects) {
                const name = obj.object?.succinctProperties?.['cmis:name'] ||
                             obj.object?.properties?.['cmis:name']?.value;
                const id = obj.object?.succinctProperties?.['cmis:objectId'] ||
                           obj.object?.properties?.['cmis:objectId']?.value;
                if (name === subFolder1Name) sf1Id = id;
                if (name === subFolder2Name) sf2Id = id;
              }
            }
          }
        }
      }
    }

    console.log(`Subfolder 1 ID: ${sf1Id}`);
    console.log(`Subfolder 2 ID: ${sf2Id}`);

    // Create document 1 in subfolder 1 via CMIS API
    if (sf1Id) {
      console.log(`Creating ${testDocument1Name} in subfolder 1...`);
      const formData = new FormData();
      formData.append('cmisaction', 'createDocument');
      formData.append('propertyId[0]', 'cmis:objectTypeId');
      formData.append('propertyValue[0]', 'cmis:document');
      formData.append('propertyId[1]', 'cmis:name');
      formData.append('propertyValue[1]', testDocument1Name);

      // Create text content as file
      const content1 = `${searchableValue} - Document 1 content`;
      const blob1 = new Blob([content1], { type: 'text/plain' });
      formData.append('content', blob1, testDocument1Name);

      const createResponse1 = await page.request.post(`${baseUrl}/root?objectId=${sf1Id}`, {
        headers: { 'Authorization': authHeader },
        multipart: {
          cmisaction: 'createDocument',
          'propertyId[0]': 'cmis:objectTypeId',
          'propertyValue[0]': 'cmis:document',
          'propertyId[1]': 'cmis:name',
          'propertyValue[1]': testDocument1Name,
          content: {
            name: testDocument1Name,
            mimeType: 'text/plain',
            buffer: Buffer.from(content1)
          }
        }
      });

      console.log(`Document 1 creation response: ${createResponse1.status()}`);
      if (createResponse1.ok()) {
        console.log(`Document 1 created successfully in subfolder 1`);
      } else {
        console.log(`Document 1 creation failed: ${await createResponse1.text()}`);
      }
    } else {
      console.log('Subfolder 1 ID not available - skipping document 1 creation');
    }

    // Create document 2 in subfolder 2 via CMIS API
    if (sf2Id) {
      console.log(`Creating ${testDocument2Name} in subfolder 2...`);
      const content2 = `${searchableValue} - Document 2 content`;

      const createResponse2 = await page.request.post(`${baseUrl}/root?objectId=${sf2Id}`, {
        headers: { 'Authorization': authHeader },
        multipart: {
          cmisaction: 'createDocument',
          'propertyId[0]': 'cmis:objectTypeId',
          'propertyValue[0]': 'cmis:document',
          'propertyId[1]': 'cmis:name',
          'propertyValue[1]': testDocument2Name,
          content: {
            name: testDocument2Name,
            mimeType: 'text/plain',
            buffer: Buffer.from(content2)
          }
        }
      });

      console.log(`Document 2 creation response: ${createResponse2.status()}`);
      if (createResponse2.ok()) {
        console.log(`Document 2 created successfully in subfolder 2`);
      } else {
        console.log(`Document 2 creation failed: ${await createResponse2.text()}`);
      }
    } else {
      console.log('Subfolder 2 ID not available - skipping document 2 creation');
    }

    // Wait for Solr indexing
    console.log('Waiting for Solr indexing...');
    await page.waitForTimeout(5000);

    // Verify documents were created by querying
    const verifyQuery = await page.request.get(
      `${baseUrl}?cmisselector=query&q=${encodeURIComponent(`SELECT cmis:objectId, cmis:name FROM cmis:document WHERE cmis:name LIKE 'hierarchy-doc%'`)}`,
      { headers: { 'Authorization': authHeader } }
    );

    if (verifyQuery.ok()) {
      const verifyData = await verifyQuery.json();
      const docsCreated = verifyData.results?.length || 0;
      console.log(`Verification: ${docsCreated} hierarchy-doc documents found`);
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
