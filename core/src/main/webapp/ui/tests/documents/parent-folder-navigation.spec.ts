/**
 * Parent Folder Navigation Tests for NemakiWare React UI
 *
 * Tests for navigating up to parent folders from current folder:
 * - Up button visibility when not in root folder
 * - Up button hidden when in root folder
 * - Navigate to parent folder via Up button
 * - Navigate to root via Up button from nested folder
 * - Up button disabled in root folder
 * - URL updates correctly after navigating up
 * - Breadcrumb and Up button stay synchronized
 *
 * Related User Report (2025-11-26):
 * "カレントフォルダの考え方が導入されたことは良いのですが、
 *  親フォルダへのナビゲーションがないと元にもどっていけません。"
 *
 * Implementation Requirement:
 * Add "Up to Parent Folder" button next to breadcrumb navigation
 * to allow users to easily navigate back up the folder hierarchy.
 */

import { test, expect, Page } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';

test.describe('Parent Folder Navigation', () => {
  // CRITICAL FIX (2025-11-26): Set timeout at describe level to ensure it applies
  // Individual test.setTimeout() calls were being ignored - using describe.configure instead
  test.describe.configure({ timeout: 90000 }); // 90 seconds for all tests in this suite

  let authHelper: AuthHelper;
  let testHelper: TestHelper;
  let page: Page;

  test.beforeEach(async ({ page: testPage, browserName }) => {
    page = testPage;
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    // Capture browser console output for debugging
    // ENHANCED (2025-11-26): Also capture parent navigation errors and general errors
    page.on('console', msg => {
      const text = msg.text();
      if (text.includes('[CMIS DEBUG]') || text.includes('[BREADCRUMB DEBUG]') ||
          text.includes('[PARENT NAV DEBUG]') || text.includes('Navigate to parent') ||
          text.includes('error') || msg.type() === 'error') {
        console.log(`BROWSER CONSOLE [${msg.type()}]:`, text);
      }
    });

    // Login and navigate to document management
    await authHelper.login();
    await page.waitForTimeout(2000);
    await testHelper.waitForAntdLoad();

    // Close sidebar if mobile browser
    // CRITICAL FIX (2025-11-26): Handle all mobile browsers (Mobile Chrome, Mobile Safari, Tablet)
    // Previous logic only handled chromium with width <= 414, leaving Mobile Safari and Tablet sidebars open
    const viewportSize = page.viewportSize();
    const isMobile = viewportSize && (
      (browserName === 'chromium' && viewportSize.width <= 414) ||  // Mobile Chrome
      (browserName === 'webkit' && viewportSize.width <= 414) ||    // Mobile Safari
      (viewportSize.width <= 1024 && viewportSize.width > 414)      // Tablet
    );

    if (isMobile) {
      const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]').first();
      if (await menuToggle.count() > 0) {
        await menuToggle.click({ timeout: 3000 });
        await page.waitForTimeout(500);
      }
    }

    // Navigate to documents page
    const documentsLink = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    if (await documentsLink.count() > 0) {
      await documentsLink.click();
      await page.waitForTimeout(2000);

      // CRITICAL FIX (2025-11-26): Wait for UI to stabilize after navigation
      // Ensures header and toolbar are fully rendered before test operations
      await page.waitForTimeout(1000);
      await testHelper.waitForAntdLoad();
    }

    // CRITICAL FIX (2025-11-26): Clean up any leftover TestParent folder from previous failed test runs
    // Without this cleanup, the "navigate through multiple folder levels" test fails at folder creation
    // because TestParent already exists. The test's cleanup code only runs on success, creating a cycle.
    //
    // APPROACH: Use CMIS API directly instead of UI-based deletion
    // UI-based deletion via Popconfirm was unreliable - confirm button click didn't trigger delete
    // Direct API call is more reliable for test cleanup purposes

    console.log('[CLEANUP DEBUG] Starting beforeEach cleanup check...');

    // Wait for table to be fully loaded before checking for TestParent
    console.log('[CLEANUP DEBUG] Waiting for table to load...');
    await page.waitForSelector('.ant-table-tbody', { timeout: 10000 });
    await page.waitForTimeout(1000); // Additional wait for table content to render
    console.log('[CLEANUP DEBUG] Table loaded, checking for TestParent...');

    // Find TestParent row and get its objectId from data-row-key attribute
    // DocumentList.tsx uses rowKey="id" so each row has data-row-key="{objectId}"
    const existingTestParent = page.locator('.ant-table-row').filter({ hasText: 'TestParent' });
    const testParentCount = await existingTestParent.count();
    console.log('[CLEANUP DEBUG] TestParent count:', testParentCount);

    if (testParentCount > 0) {
      console.log('[CLEANUP DEBUG] TestParent found, getting objectId from data-row-key...');

      // Get objectId from data-row-key attribute
      const objectId = await existingTestParent.first().getAttribute('data-row-key');
      console.log('[CLEANUP DEBUG] TestParent objectId:', objectId);

      if (objectId) {
        console.log('[CLEANUP DEBUG] Using CMIS API to delete TestParent directly...');

        // Use CMIS Browser Binding deleteTree API directly
        // This bypasses the unreliable Popconfirm UI interaction
        // CRITICAL FIX (2025-11-26): deleteTree uses 'folderId' parameter, NOT 'objectId'
        try {
          const response = await page.request.post('http://localhost:8080/core/browser/bedroom', {
            form: {
              cmisaction: 'deleteTree',
              folderId: objectId,  // Parameter name is 'folderId' for deleteTree
              allVersions: 'true',
              continueOnFailure: 'true'
            },
            headers: {
              'Authorization': 'Basic ' + Buffer.from('admin:admin').toString('base64')
            }
          });

          const status = response.status();
          console.log('[CLEANUP DEBUG] CMIS deleteTree response status:', status);

          if (status === 200 || status === 204) {
            console.log('[CLEANUP DEBUG] CMIS deleteTree succeeded');

            // Reload page to refresh the table
            console.log('[CLEANUP DEBUG] Reloading page to refresh table...');
            await page.reload();
            await page.waitForTimeout(2000);
            await testHelper.waitForAntdLoad();

            // Verify TestParent is gone
            await page.waitForSelector('.ant-table-tbody', { timeout: 10000 });
            const remainingTestParent = page.locator('.ant-table-row').filter({ hasText: 'TestParent' });
            const remainingCount = await remainingTestParent.count();
            console.log('[CLEANUP DEBUG] TestParent remaining after deletion:', remainingCount);

            if (remainingCount === 0) {
              console.log('[CLEANUP DEBUG] Cleanup completed successfully via CMIS API');
            } else {
              console.log('[CLEANUP DEBUG] WARNING: TestParent still exists after CMIS delete');
            }
          } else {
            const body = await response.text();
            console.log('[CLEANUP DEBUG] CMIS deleteTree failed:', status, body);
          }
        } catch (error) {
          console.log('[CLEANUP DEBUG] CMIS API error:', error);
        }
      } else {
        console.log('[CLEANUP DEBUG] Could not get objectId from data-row-key attribute');
      }
    } else {
      console.log('[CLEANUP DEBUG] No TestParent found - cleanup not needed');
    }
  });

  test('should show Up button when in subfolder', async () => {
    // Find any existing folder to navigate into (not hardcoded to 'Sites')
    // Note: FolderOutlined renders as .anticon-folder
    const folderIcon = page.locator('.ant-table-tbody tr .anticon-folder').first();
    const isFolderVisible = await folderIcon.isVisible().catch(() => false);

    if (!isFolderVisible) {
      console.log('No folder found in repository - test skipped');
      test.skip();
      return;
    }

    // Get the folder row and click to enter
    // Note: Folder names are rendered as <Button type="link"> not <a> tags
    const folderRow = page.locator('.ant-table-tbody tr').filter({ has: page.locator('.anticon-folder') }).first();
    const folderButton = folderRow.locator('button.ant-btn-link').first();
    const folderName = await folderButton.textContent();
    console.log(`Found folder: ${folderName}`);

    // Click the folder button to enter
    await folderButton.click();
    await page.waitForTimeout(2000);

    // Verify we're in a subfolder by checking URL has folderId
    expect(page.url()).toContain('folderId=');

    // Verify Up button is visible (上へ)
    const upButton = page.locator('button').filter({ hasText: /上へ/ });
    await expect(upButton).toBeVisible({ timeout: 10000 });
    await expect(upButton).not.toBeDisabled();
  });

  test('should hide or disable Up button when in root folder', async () => {
    // Verify we're in root folder (breadcrumb shows only home icon)
    const breadcrumb = page.locator('.ant-breadcrumb');
    await expect(breadcrumb).toBeVisible();

    // Up button should be disabled or hidden in root
    const upButton = page.locator('button').filter({ hasText: /親フォルダへ|上へ|Up/ });

    // Check if button exists
    const buttonCount = await upButton.count();
    if (buttonCount > 0) {
      // If button exists, it should be disabled
      await expect(upButton.first()).toBeDisabled();
    }
    // Otherwise, button is hidden, which is acceptable
  });

  test('should navigate to parent folder when Up button clicked', async () => {
    // Find any existing folder to navigate into
    const folderIcon = page.locator('.ant-table-tbody tr .anticon-folder').first();
    const isFolderVisible = await folderIcon.isVisible().catch(() => false);

    if (!isFolderVisible) {
      console.log('No folder found in repository - test skipped');
      test.skip();
      return;
    }

    // Get the folder row and enter it
    // Note: Folder names are rendered as <Button type="link"> not <a> tags
    const folderRow = page.locator('.ant-table-tbody tr').filter({ has: page.locator('.anticon-folder') }).first();
    const folderButton = folderRow.locator('button.ant-btn-link').first();
    const folderName = await folderButton.textContent();

    await folderButton.click();
    await page.waitForTimeout(2000);

    // Verify we're in subfolder
    const subfolderUrl = page.url();
    expect(subfolderUrl).toContain('folderId=');

    // Click Up button (上へ)
    const upButton = page.locator('button').filter({ hasText: /上へ/ });
    await expect(upButton).toBeVisible({ timeout: 10000 });
    await upButton.click();
    await page.waitForTimeout(2000);

    // Verify we navigated back
    const parentUrl = page.url();
    expect(parentUrl).not.toEqual(subfolderUrl);

    // Verify the folder we entered is visible again
    const originalFolder = page.locator('.ant-table-tbody tr').filter({ hasText: folderName || '' });
    await expect(originalFolder).toBeVisible({ timeout: 10000 });
  });

  test('should navigate through multiple folder levels with Up button', async ({ browserName }) => {
    // Increase timeout for this complex test (creates 2 folders, navigates, tests breadcrumbs, cleanup)
    test.setTimeout(90000); // 90 seconds

    // Create test folder structure: Root -> TestParent -> TestChild
    const createFolderButton = page.locator('button').filter({ hasText: /フォルダ作成|新しいフォルダ/ });

    // Detect mobile browsers for force click
    const viewportSize = page.viewportSize();
    const isMobile = viewportSize && (
      (browserName === 'chromium' && viewportSize.width <= 414) ||  // Mobile Chrome
      (browserName === 'webkit' && viewportSize.width <= 414) ||    // Mobile Safari
      (viewportSize.width <= 1024 && viewportSize.width > 414)      // Tablet
    );

    // Create TestParent folder in root
    if (await createFolderButton.count() > 0) {
      // CRITICAL FIX (2025-11-26): Wait for button to be clickable and any animations to complete
      // This prevents "subtree intercepts pointer events" errors from header overlays
      await page.waitForTimeout(500);
      const button = createFolderButton.first();
      await button.scrollIntoViewIfNeeded();
      await page.waitForTimeout(300);

      // CRITICAL FIX (2025-11-26): Use force click for mobile browsers to bypass overlay issues
      await button.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      // Use working pattern from acl-management.spec.ts
      const folderModal = page.locator('.ant-modal:not(.ant-modal-hidden)');
      const nameInput = folderModal.locator('input[placeholder*="フォルダ名"]');
      await nameInput.fill('TestParent');

      const submitButton = folderModal.locator('button[type="submit"], button.ant-btn-primary');
      await submitButton.click();
      // CRITICAL FIX (2025-11-26): Extended timeout from 10s → 30s → 60s for slow folder creation operations
      // Root cause: Folder creation in CI/Docker environment can exceed 30 seconds
      await page.waitForSelector('.ant-message-success', { timeout: 60000 });
      await page.waitForTimeout(2000);
    }

    // Navigate into TestParent
    const testParentRow = page.locator('.ant-table-row').filter({ hasText: 'TestParent' });
    if (await testParentRow.count() > 0) {
      const testParentLink = page.locator('button').filter({ hasText: 'TestParent' });
      await testParentLink.click();
      await page.waitForTimeout(2000);

      // Create TestChild folder inside TestParent
      if (await createFolderButton.count() > 0) {
        // CRITICAL FIX (2025-11-26): Wait for button to be clickable and any animations to complete
        await page.waitForTimeout(500);
        const button = createFolderButton.first();
        await button.scrollIntoViewIfNeeded();
        await page.waitForTimeout(300);

        // CRITICAL FIX (2025-11-26): Use force click for mobile browsers to bypass overlay issues
        await button.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(1000);

        // Use working pattern from acl-management.spec.ts
        const folderModal = page.locator('.ant-modal:not(.ant-modal-hidden)');
        const nameInput = folderModal.locator('input[placeholder*="フォルダ名"]');
        await nameInput.fill('TestChild');

        const submitButton = folderModal.locator('button[type="submit"], button.ant-btn-primary');
        await submitButton.click();
        // CRITICAL FIX (2025-11-26): Extended timeout from 10s → 30s → 60s for slow folder creation operations
        // Root cause: Folder creation in CI/Docker environment can exceed 30 seconds
        await page.waitForSelector('.ant-message-success', { timeout: 60000 });
        await page.waitForTimeout(2000);

        // Navigate into TestChild
        const testChildRow = page.locator('.ant-table-row').filter({ hasText: 'TestChild' });
        if (await testChildRow.count() > 0) {
          const testChildLink = page.locator('button').filter({ hasText: 'TestChild' });
          await testChildLink.click();
          await page.waitForTimeout(2000);

          // Verify breadcrumb shows: Home > TestParent > TestChild
          const breadcrumb = page.locator('.ant-breadcrumb');
          await expect(breadcrumb).toContainText('TestParent');
          await expect(breadcrumb).toContainText('TestChild');

          // Navigate up once (to TestParent)
          const upButton = page.locator('button').filter({ hasText: /親フォルダへ|上へ|Up/ });
          await upButton.click();
          await page.waitForTimeout(2000);

          // Verify we're in TestParent
          await expect(breadcrumb).toContainText('TestParent');
          await expect(breadcrumb).not.toContainText('TestChild');

          // Navigate up again (to root)
          await upButton.click();
          await page.waitForTimeout(2000);

          // Verify we're in root
          await expect(breadcrumb).not.toContainText('TestParent');

          // Cleanup: Delete TestParent folder (will delete TestChild too)
          const cleanupRow = page.locator('.ant-table-row').filter({ hasText: 'TestParent' });
          if (await cleanupRow.count() > 0) {
            await cleanupRow.click();
            const deleteButton = page.locator('button').filter({ hasText: /削除/ });
            if (await deleteButton.count() > 0) {
              await deleteButton.first().click();
              await page.waitForTimeout(500);

              const confirmButton = page.locator('button').filter({ hasText: /OK|はい/ }).last();
              if (await confirmButton.count() > 0) {
                await confirmButton.click();
                await page.waitForTimeout(2000);
              }
            }
          }
        }
      }
    }
  });

  test('should synchronize Up button with breadcrumb navigation', async () => {
    // Find any existing folder to navigate into (not hardcoded to 'Sites')
    const folderIcon = page.locator('.ant-table-tbody tr .anticon-folder').first();
    const isFolderVisible = await folderIcon.isVisible().catch(() => false);

    if (!isFolderVisible) {
      console.log('No folder found in repository - test skipped');
      test.skip();
      return;
    }

    // Get the folder row and navigate into it
    // Note: Folder names are rendered as <Button type="link"> not <a> tags
    const folderRow = page.locator('.ant-table-tbody tr').filter({ has: page.locator('.anticon-folder') }).first();
    const folderButton = folderRow.locator('button.ant-btn-link').first();
    const folderName = await folderButton.textContent();
    console.log(`Found folder: ${folderName}`);

    // Click the folder button to enter
    await folderButton.click();
    await page.waitForTimeout(2000);

    // Verify breadcrumb shows the folder name
    const breadcrumb = page.locator('.ant-breadcrumb');
    if (folderName) {
      await expect(breadcrumb).toContainText(folderName);
    }

    // Navigate back using breadcrumb Home icon
    const homeLink = breadcrumb.locator('.ant-breadcrumb-link').first();
    await homeLink.click();
    await page.waitForTimeout(2000);

    // Verify we're back in root - the folder should be visible again
    const originalFolder = page.locator('.ant-table-tbody tr').filter({ hasText: folderName || '' });
    await expect(originalFolder).toBeVisible({ timeout: 10000 });

    // Verify Up button is disabled/hidden in root
    const upButton = page.locator('button').filter({ hasText: /親フォルダへ|上へ|Up/ });
    const buttonCount = await upButton.count();
    if (buttonCount > 0) {
      await expect(upButton.first()).toBeDisabled();
    }
  });

  test('should update URL parameter when navigating up', async () => {
    // Find any existing folder to navigate into (not hardcoded to 'Sites')
    const folderIcon = page.locator('.ant-table-tbody tr .anticon-folder').first();
    const isFolderVisible = await folderIcon.isVisible().catch(() => false);

    if (!isFolderVisible) {
      console.log('No folder found in repository - test skipped');
      test.skip();
      return;
    }

    // Get the folder row and navigate into it
    // Note: Folder names are rendered as <Button type="link"> not <a> tags
    const folderRow = page.locator('.ant-table-tbody tr').filter({ has: page.locator('.anticon-folder') }).first();
    const folderButton = folderRow.locator('button.ant-btn-link').first();
    const folderName = await folderButton.textContent();
    console.log(`Found folder: ${folderName}`);

    // Click the folder button to enter
    await folderButton.click();
    await page.waitForTimeout(2000);

    // Get folder ID from URL after entering subfolder
    const subfolderUrl = page.url();
    const subfolderMatch = subfolderUrl.match(/folderId=([^&]+)/);
    expect(subfolderMatch).toBeTruthy();
    const subfolderFolderId = subfolderMatch![1];

    // Navigate up
    const upButton = page.locator('button').filter({ hasText: /親フォルダへ|上へ|Up/ });
    await upButton.click();
    await page.waitForTimeout(2000);

    // Get root folder ID from URL
    const rootUrl = page.url();
    const rootMatch = rootUrl.match(/folderId=([^&]+)/);
    expect(rootMatch).toBeTruthy();
    const rootFolderId = rootMatch![1];

    // Folder IDs should be different
    expect(rootFolderId).not.toEqual(subfolderFolderId);

    // Root folder ID should be the expected value (bedroom repository root)
    expect(rootFolderId).toBe('e02f784f8360a02cc14d1314c10038ff');
  });
});
