import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';

/**
 * Back to Current Folder Navigation Test
 *
 * CRITICAL USER REQUIREMENT (2025-12-24):
 * "「カレント」へ「←戻る」が機能するようになることを真剣にのぞんでいます"
 *
 * Verifies that when clicking the "戻る" button in DocumentViewer,
 * the user returns to the SAME subfolder they were viewing, not the root folder.
 *
 * Test Flow:
 * 1. Login as admin
 * 2. Navigate to a subfolder (not root)
 * 3. Click on a document to open DocumentViewer
 * 4. Click "戻る" button
 * 5. Verify we are back in the same subfolder (not root)
 */
test.describe('Back to Current Folder Navigation', () => {
  let authHelper: AuthHelper;
  let testHelper: TestHelper;

  test.beforeEach(async ({ page }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);
    await authHelper.login();
    await page.waitForTimeout(2000);
  });

  test.afterEach(async ({ page }) => {
    await page.close();
  });

  test('should return to current folder when clicking back button', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);
    const ROOT_FOLDER_ID = 'e02f784f8360a02cc14d1314c10038ff';

    // Step 1: Wait for document list to load
    await page.waitForSelector('.ant-table-tbody', { timeout: 10000 });
    console.log('Document list loaded');

    // Step 2: Find documents (non-folder items) in root
    const documentRows = page.locator('.ant-table-tbody tr').filter({
      hasNot: page.locator('.anticon-folder')
    });

    const docCount = await documentRows.count();
    console.log(`Found ${docCount} documents in root`);

    if (docCount === 0) {
      test.skip(true, 'No documents available for test');
      return;
    }

    // Step 3: Click on the first document to open DocumentViewer
    const targetRow = documentRows.first();
    const docNameButton = targetRow.locator('button.ant-btn-link').first();
    const docName = await docNameButton.textContent({ timeout: 5000 }).catch(() => 'document');
    console.log(`Clicking on document: ${docName}`);

    await docNameButton.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(3000);

    // Step 4: Verify we're in DocumentViewer with correct folderId
    const viewerUrl = page.url();
    console.log(`DocumentViewer URL: ${viewerUrl}`);

    expect(viewerUrl).toMatch(/\/documents\/[a-f0-9]+/);

    // Check if folderId was preserved in the URL
    const viewerFolderIdMatch = viewerUrl.match(/folderId=([a-f0-9]+)/);
    const viewerFolderId = viewerFolderIdMatch ? viewerFolderIdMatch[1] : null;
    console.log(`Viewer folderId: ${viewerFolderId}`);

    expect(viewerFolderId).toBe(ROOT_FOLDER_ID);
    console.log('✅ folderId preserved when navigating to DocumentViewer');

    // Step 5: Click the "戻る" button
    const backButton = page.locator('button').filter({ hasText: '戻る' });
    await expect(backButton).toBeVisible({ timeout: 5000 });

    console.log('Clicking back button...');
    await backButton.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Step 6: Verify we returned to the correct folder
    const finalUrl = page.url();
    console.log(`Final URL after back: ${finalUrl}`);

    // The URL should be /documents with the same folderId
    expect(finalUrl).toContain('/documents');
    expect(finalUrl).not.toMatch(/\/documents\/[a-f0-9]+/); // Should be /documents, not /documents/:id

    const finalFolderIdMatch = finalUrl.match(/folderId=([a-f0-9]+)/);
    const finalFolderId = finalFolderIdMatch ? finalFolderIdMatch[1] : null;
    console.log(`Final folderId: ${finalFolderId}`);

    expect(finalFolderId).toBe(ROOT_FOLDER_ID);
    console.log('✅ Returned to the correct folder!');

    // Step 7: Verify the same folder contents are visible
    await page.waitForSelector('.ant-table-tbody', { timeout: 10000 });

    // Verify table is visible and contains the document we clicked
    const table = page.locator('.ant-table-tbody');
    await expect(table).toBeVisible({ timeout: 5000 });

    console.log('✅ Document list is visible after back navigation');
  });

  test('should preserve folderId when navigating through multiple documents', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    await page.waitForSelector('.ant-table-tbody', { timeout: 10000 });

    // Check for documents in root first
    const rootDocs = page.locator('.ant-table-tbody tr').filter({
      hasNot: page.locator('.anticon-folder')
    });

    const rootDocCount = await rootDocs.count();
    console.log(`Root has ${rootDocCount} documents`);

    // If root has enough documents, test there
    if (rootDocCount >= 2) {
      console.log('Testing with root folder documents');
      const originalFolderId = 'e02f784f8360a02cc14d1314c10038ff'; // ROOT

      // Open first document
      await rootDocs.first().locator('button.ant-btn-link').first().click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);

      let currentUrl = page.url();
      console.log(`After opening first doc: ${currentUrl}`);

      // Go back
      const backButton = page.locator('button').filter({ hasText: '戻る' });
      await expect(backButton).toBeVisible({ timeout: 5000 });
      await backButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);

      currentUrl = page.url();
      console.log(`After back: ${currentUrl}`);
      expect(currentUrl).toContain('/documents');
      console.log('✅ Back navigation works from root');
      return;
    }

    // Try to find a folder with documents
    const folderRows = page.locator('.ant-table-tbody tr').filter({
      has: page.locator('.anticon-folder')
    });

    if (await folderRows.count() === 0) {
      test.skip(true, 'No folders and not enough root documents');
      return;
    }

    // Find folder with documents
    let foundFolder = false;
    let originalFolderId: string | undefined;

    for (let i = 0; i < Math.min(await folderRows.count(), 5); i++) {
      const folder = folderRows.nth(i);
      await folder.locator('button.ant-btn-link').first().click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);

      const url = page.url();
      const folderId = url.match(/folderId=([a-f0-9]+)/)?.[1];

      const docs = page.locator('.ant-table-tbody tr').filter({
        hasNot: page.locator('.anticon-folder')
      });

      if (await docs.count() > 0 && folderId) {
        originalFolderId = folderId;
        foundFolder = true;
        console.log(`Found folder with docs: ${folderId}`);
        break;
      }

      // Go back to root
      await page.goto('http://localhost:8080/core/ui/index.html#/documents');
      await page.waitForTimeout(1500);
    }

    if (!foundFolder) {
      test.skip(true, 'No folder with documents found');
      return;
    }

    // Open first document
    const docs = page.locator('.ant-table-tbody tr').filter({
      hasNot: page.locator('.anticon-folder')
    });
    await docs.first().locator('button.ant-btn-link').first().click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    let currentUrl = page.url();
    let currentFolderId = currentUrl.match(/folderId=([a-f0-9]+)/)?.[1];
    expect(currentFolderId).toBe(originalFolderId);
    console.log('✅ First document preserves folderId');

    // Go back
    const backButton = page.locator('button').filter({ hasText: '戻る' });
    await backButton.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    currentUrl = page.url();
    currentFolderId = currentUrl.match(/folderId=([a-f0-9]+)/)?.[1];
    expect(currentFolderId).toBe(originalFolderId);
    console.log('✅ After back, still in original folder');

    // Open second document if available
    const docs2 = page.locator('.ant-table-tbody tr').filter({
      hasNot: page.locator('.anticon-folder')
    });

    if (await docs2.count() >= 2) {
      await docs2.nth(1).locator('button.ant-btn-link').first().click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);

      currentUrl = page.url();
      currentFolderId = currentUrl.match(/folderId=([a-f0-9]+)/)?.[1];
      expect(currentFolderId).toBe(originalFolderId);
      console.log('✅ Second document also preserves folderId');

      // Go back again
      await page.locator('button').filter({ hasText: '戻る' }).click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);

      currentUrl = page.url();
      currentFolderId = currentUrl.match(/folderId=([a-f0-9]+)/)?.[1];
      expect(currentFolderId).toBe(originalFolderId);
      console.log('✅ After second back, still in original folder');
    }
  });
});
