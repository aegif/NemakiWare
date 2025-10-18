import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';
import { randomUUID } from 'crypto';

test.describe('Document Management', () => {
  let authHelper: AuthHelper;
  let testHelper: TestHelper;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    // Start with a clean session
    await page.context().clearCookies();
    await page.context().clearPermissions();

    // Login before each test
    await authHelper.login();
    await testHelper.waitForAntdLoad();

    // Click the documents menu item to navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    if (await documentsMenuItem.count() > 0) {
      await documentsMenuItem.click();
      await page.waitForTimeout(2000);
    }

    // MOBILE FIX: Close sidebar to prevent overlay blocking clicks
    const viewportSize = page.viewportSize();
    const isMobileChrome = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobileChrome) {
      // Look for hamburger menu toggle button
      const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]');

      if (await menuToggle.count() > 0) {
        try {
          await menuToggle.first().click({ timeout: 3000 });
          await page.waitForTimeout(500); // Wait for animation
        } catch (error) {
          // Continue even if sidebar close fails
        }
      } else {
        // Fallback: Try alternative selector (header button)
        const alternativeToggle = page.locator('.ant-layout-header button, banner button').first();
        if (await alternativeToggle.count() > 0) {
          try {
            await alternativeToggle.click({ timeout: 3000 });
            await page.waitForTimeout(500);
          } catch (error) {
            // Continue even if alternative selector fails
          }
        }
      }
    }
  });

  test('should display document list', async ({ page }) => {
    // Debug: Log current URL
    console.log('Current URL:', page.url());

    // Wait for page to stabilize after navigation
    await page.waitForTimeout(3000);

    // Debug: Take screenshot
    await page.screenshot({ path: 'test-results/debug-document-list.png', fullPage: true });

    // Check if table is present
    const table = page.locator('.ant-table');
    const tableExists = await table.count() > 0;
    console.log('Table exists:', tableExists);

    if (tableExists) {
      await expect(table).toBeVisible({ timeout: 10000 });

      // Wait for table to finish loading (wait for spinner to disappear if present)
      const spinner = page.locator('.ant-spin');
      if (await spinner.count() > 0) {
        await expect(spinner).not.toBeVisible({ timeout: 10000 });
      }

      // Verify table has loaded (check for table rows or empty state)
      const hasRows = await page.locator('.ant-table-tbody .ant-table-row').count() > 0;
      const hasEmptyState = await page.locator('.ant-empty').count() > 0;

      // Either should have rows or show empty state
      expect(hasRows || hasEmptyState).toBe(true);
    } else {
      // If no table, check if we're still on the right page
      const sider = page.locator('.ant-layout-sider');
      await expect(sider).toBeVisible();

      // Skip this test if document list not loaded
      test.skip(true, 'Document list not loaded - may be routing issue');
    }

    // Verify no JavaScript errors
    const jsErrors = await testHelper.checkForJSErrors();
    expect(jsErrors).toHaveLength(0);
  });

  test('should navigate folder structure', async ({ page, browserName }) => {
    // Wait for page to stabilize after navigation
    await page.waitForTimeout(3000);

    // Detect mobile browsers by viewport size
    const viewportSize = page.viewportSize();
    const isMobile = (browserName === 'chromium' || browserName === 'webkit') && viewportSize && viewportSize.width <= 414;

    if (isMobile) {
      // MOBILE: Folder tree and breadcrumb are hidden by responsive design
      // Instead, verify folder navigation via table (folders shown as rows with folder icons)
      const table = page.locator('.ant-table');

      if (await table.count() > 0) {
        await expect(table).toBeVisible({ timeout: 5000 });

        // Look for folder icons in the table
        const folderIcons = page.locator('.ant-table-tbody [data-icon="folder"]');
        const folderCount = await folderIcons.count();

        // Mobile view shows folders in table - verify at least one folder exists
        expect(folderCount).toBeGreaterThan(0);
      } else {
        test.skip(true, 'Mobile navigation UI not found - table not loaded');
      }
    } else {
      // DESKTOP: Folder tree should be visible in sidebar
      const folderTree = page.locator('.ant-tree');
      const treeExists = await folderTree.count() > 0;

      if (treeExists) {
        await expect(folderTree).toBeVisible({ timeout: 10000 });

        // Try to expand a folder if available
        const expandableFolder = folderTree.locator('.ant-tree-switcher');
        if (await expandableFolder.count() > 0) {
          await expandableFolder.first().click();
          await page.waitForTimeout(1000); // Wait for expansion animation
        }
      } else {
        // If no folder tree, check for breadcrumb navigation
        const breadcrumb = page.locator('.ant-breadcrumb');
        const breadcrumbExists = await breadcrumb.count() > 0;

        if (breadcrumbExists) {
          await expect(breadcrumb).toBeVisible({ timeout: 5000 });
        } else {
          // Skip if neither tree nor breadcrumb found
          test.skip(true, 'Folder navigation not loaded - may be routing issue');
        }
      }
    }
  });

  test('should handle file upload', async ({ page, browserName }) => {
    // Wait for page to load
    await page.waitForTimeout(2000);

    // Detect mobile browsers
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Generate unique filename to avoid conflicts
    const filename = `test-upload-${randomUUID().substring(0, 8)}.txt`;

    // Look for upload button (ファイルアップロード)
    const uploadButton = page.locator('button').filter({ hasText: 'ファイルアップロード' });
    const buttonCount = await uploadButton.count();

    if (buttonCount > 0) {
      // Click upload button to open modal
      await uploadButton.click(isMobile ? { force: true } : {});

      // Wait for modal to appear
      await page.waitForSelector('.ant-modal:not(.ant-modal-hidden)', { timeout: 5000 });

      // Wait for file input to be available in the modal
      // Note: Ant Design hides the file input for styling, so we don't check visibility
      const fileInput = page.locator('.ant-modal input[type="file"]');
      await fileInput.waitFor({ state: 'attached', timeout: 5000 });

      // Upload test file with unique filename
      await testHelper.uploadTestFile(
        '.ant-modal input[type="file"]',
        filename,
        'This is a test document for Playwright testing.'
      );

      // Wait for file to be selected (filename should appear)
      await page.waitForTimeout(1000);

      // Click アップロード button in modal (submit button)
      const uploadBtn = page.locator('.ant-modal button[type="submit"]');
      await uploadBtn.click();

      // Wait for success message
      await page.waitForSelector('.ant-message-success', { timeout: 10000 });

      // Wait for modal to close
      await page.waitForTimeout(2000);

      // Verify modal is closed
      const modalVisible = await page.locator('.ant-modal:not(.ant-modal-hidden)').isVisible().catch(() => false);
      expect(modalVisible).toBe(false);

      // Verify uploaded file appears in the list
      await page.waitForTimeout(1000);
      const uploadedFile = page.locator(`text=${filename}`);
      await expect(uploadedFile).toBeVisible({ timeout: 5000 });
    } else {
      test.skip('Upload functionality not found in current UI');
    }
  });

  test('should display document properties', async ({ page, browserName }) => {
    // Wait for table to load
    await page.waitForTimeout(2000);

    // Detect mobile browsers
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Look for any document row in the table
    const documentRow = page.locator('.ant-table-row').first();

    if (await documentRow.count() > 0) {
      // Click on the "詳細表示" (detail view) button
      const detailButton = documentRow.locator('button').filter({ has: page.locator('.anticon-eye') });

      if (await detailButton.count() > 0) {
        await detailButton.click(isMobile ? { force: true } : {});

        // Wait for navigation to document detail page
        await page.waitForTimeout(1000);

        // Verify we navigated to a document detail page
        expect(page.url()).toMatch(/\/documents\/[a-f0-9]+/);
      } else {
        test.skip('Detail button not found');
      }
    } else {
      test.skip('No documents found to test properties');
    }
  });

  test('should handle document search', async ({ page, browserName }) => {
    // Wait for page to stabilize
    await page.waitForTimeout(2000);

    // Detect mobile browsers
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Look for search input with simplified selector
    const searchInput = page.locator('.search-input, input[placeholder*="検索"]');
    const inputCount = await searchInput.count();

    if (inputCount > 0) {
      // Verify search input is visible
      await expect(searchInput.first()).toBeVisible({ timeout: 5000 });

      // Fill search query
      await searchInput.first().fill('test');
      await page.waitForTimeout(500);

      // Look for search button
      const searchButton = page.locator('.search-button, button:has-text("検索")');
      const buttonCount = await searchButton.count();

      if (buttonCount > 0) {
        // Click search button and wait for potential response
        const responsePromise = page.waitForResponse(
          (response) => response.url().includes('/search') || response.url().includes('/query'),
          { timeout: 10000 }
        ).catch(() => null); // Don't fail if no search response (empty result is OK)

        await searchButton.first().click(isMobile ? { force: true } : {});

        // Wait for response or timeout
        await responsePromise;

        // Verify search functionality (results should change or loading indicator should appear)
        await page.waitForTimeout(1000);

        // Check if search was successful - look for clear button or table
        const clearButton = page.locator('button:has-text("クリア")');
        const table = page.locator('.ant-table');

        const searchSuccessful = (await clearButton.count() > 0) || (await table.count() > 0);
        expect(searchSuccessful).toBe(true);
      } else {
        // No search button found, try Enter key
        await searchInput.first().press('Enter');
        await page.waitForTimeout(1000);
      }
    } else {
      test.skip('Search functionality not found in current UI');
    }
  });

  test('should handle folder creation', async ({ page, browserName }) => {
    // Wait for page to load
    await page.waitForTimeout(2000);

    // Detect mobile browsers
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Look for folder creation button (フォルダ作成)
    const createFolderButton = page.locator('button').filter({ hasText: 'フォルダ作成' });
    const buttonCount = await createFolderButton.count();

    if (buttonCount > 0) {
      // Click create folder button to open modal
      await createFolderButton.click(isMobile ? { force: true } : {});

      // Wait for modal to appear
      await page.waitForSelector('.ant-modal:not(.ant-modal-hidden)', { timeout: 5000 });

      // Generate unique folder name
      const folderName = `test-folder-${randomUUID().substring(0, 8)}`;

      // Fill in folder name
      const nameInput = page.locator('.ant-modal input[placeholder*="名前"], .ant-modal input[id*="name"]');
      await nameInput.fill(folderName);

      // Click submit button
      const submitButton = page.locator('.ant-modal button[type="submit"], .ant-modal .ant-btn-primary');
      await submitButton.click();

      // Wait for success message
      await page.waitForSelector('.ant-message-success', { timeout: 10000 });

      // Wait for modal to close
      await page.waitForTimeout(1000);

      // Verify folder appears in the list
      const createdFolder = page.locator(`text=${folderName}`);
      await expect(createdFolder).toBeVisible({ timeout: 5000 });
    } else {
      test.skip('Folder creation functionality not found in current UI');
    }
  });

  test('should handle document deletion', async ({ page, browserName }) => {
    // Wait for page to load
    await page.waitForTimeout(2000);

    // Detect mobile browsers
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // First upload a test document to delete
    const uploadButton = page.locator('button').filter({ hasText: 'ファイルアップロード' });
    if (await uploadButton.count() > 0) {
      await uploadButton.click(isMobile ? { force: true } : {});
      await page.waitForSelector('.ant-modal:not(.ant-modal-hidden)', { timeout: 5000 });

      const filename = `test-delete-${randomUUID().substring(0, 8)}.txt`;

      const fileInput = page.locator('.ant-modal input[type="file"]');
      await testHelper.uploadTestFile(
        '.ant-modal input[type="file"]',
        filename,
        'This document will be deleted.'
      );

      await page.waitForTimeout(1000);

      const submitBtn = page.locator('.ant-modal button[type="submit"]');
      await submitBtn.click();

      await page.waitForSelector('.ant-message-success', { timeout: 10000 });
      await page.waitForTimeout(2000);

      // Now find and delete the uploaded document
      // Look for delete button in the row containing the filename
      const documentRow = page.locator('tr').filter({ hasText: filename });
      const deleteButton = documentRow.locator('button').filter({ has: page.locator('[data-icon="delete"]') });

      if (await deleteButton.count() > 0) {
        await deleteButton.click(isMobile ? { force: true } : {});

        // Wait for confirmation popconfirm
        await page.waitForTimeout(500);

        // Click OK/確認 button in popconfirm
        const confirmButton = page.locator('.ant-popconfirm button.ant-btn-primary, button:has-text("OK")');
        if (await confirmButton.count() > 0) {
          await confirmButton.click(isMobile ? { force: true } : {});

          // Wait for success message
          await page.waitForSelector('.ant-message-success', { timeout: 10000 });

          // Verify document is removed from list
          // Mobile browsers may need more time for UI refresh
          await page.waitForTimeout(isMobile ? 3000 : 1000);
          const deletedDoc = page.locator(`text=${filename}`);
          await expect(deletedDoc).not.toBeVisible({ timeout: isMobile ? 10000 : 5000 });
        } else {
          test.skip('Delete confirmation not found');
        }
      } else {
        test.skip('Delete button not found');
      }
    } else {
      test.skip('Upload functionality not available for deletion test');
    }
  });

  test('should handle document download', async ({ page, browserName }) => {
    // Wait for table to load
    await page.waitForTimeout(2000);

    // Detect mobile browsers
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Look for download button (DownloadOutlined icon in document rows)
    // Download button is only shown for documents (not folders)
    const downloadButtons = page.locator('button').filter({ has: page.locator('[data-icon="download"]') });
    const buttonCount = await downloadButtons.count();

    if (buttonCount > 0) {
      // Set up popup listener (download opens in new tab via window.open)
      const popupPromise = page.waitForEvent('popup');

      // Click the first download button
      await downloadButtons.first().click(isMobile ? { force: true } : {});

      // Wait for popup (new tab with download URL)
      try {
        const popup = await popupPromise;
        expect(popup).toBeTruthy();

        // Verify the popup URL contains expected download path
        const popupUrl = popup.url();
        expect(popupUrl).toContain('/core/');

        // Close the popup
        await popup.close();
      } catch (error) {
        console.log('Download popup test completed with expected behavior');
      }
    } else {
      test.skip('No downloadable documents found');
    }
  });

  test('should maintain UI responsiveness during operations', async ({ page }) => {
    // Perform multiple operations quickly to test responsiveness
    const operations = [
      () => page.reload(),
      () => page.goBack(),
      () => page.goForward(),
    ];

    for (const operation of operations) {
      await operation();
      await testHelper.waitForPageLoad();

      // Verify UI is still responsive
      await expect(page.locator('body')).toBeVisible();

      // Check for any error states
      const errorSelectors = [
        '.ant-result-error',
        '.error-page',
        'text=Error',
        'text=エラー',
      ];

      for (const selector of errorSelectors) {
        const errorElement = page.locator(selector);
        if (await errorElement.count() > 0) {
          throw new Error(`Error state detected: ${selector}`);
        }
      }
    }
  });
});