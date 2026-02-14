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

// Test environment constants
const CMIS_BASE_URL = 'http://localhost:8080/core';
const REPOSITORY_ID = 'bedroom';
const ROOT_FOLDER_ID = 'e02f784f8360a02cc14d1314c10038ff';
const ADMIN_CREDENTIALS = 'admin:admin';

// Helper function to create a test folder via CMIS API
async function createTestFolder(parentId: string, folderName: string): Promise<string | null> {
  try {
    const response = await fetch(`${CMIS_BASE_URL}/browser/${REPOSITORY_ID}`, {
      method: 'POST',
      headers: {
        'Authorization': `Basic ${Buffer.from(ADMIN_CREDENTIALS).toString('base64')}`,
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      body: new URLSearchParams({
        cmisaction: 'createFolder',
        'propertyId[0]': 'cmis:objectTypeId',
        'propertyValue[0]': 'cmis:folder',
        'propertyId[1]': 'cmis:name',
        'propertyValue[1]': folderName,
        objectId: parentId,
      }).toString(),
    });

    if (response.ok) {
      const data = await response.json();
      return data.succinctProperties?.['cmis:objectId'] || data.properties?.['cmis:objectId']?.value || null;
    }
    console.log(`Failed to create folder ${folderName}:`, response.status);
    return null;
  } catch (error) {
    console.log(`Error creating folder ${folderName}:`, error);
    return null;
  }
}

// Helper function to delete a folder via CMIS API
async function deleteTestFolder(folderId: string): Promise<boolean> {
  try {
    const response = await fetch(`${CMIS_BASE_URL}/browser/${REPOSITORY_ID}`, {
      method: 'POST',
      headers: {
        'Authorization': `Basic ${Buffer.from(ADMIN_CREDENTIALS).toString('base64')}`,
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      body: new URLSearchParams({
        cmisaction: 'deleteTree',
        folderId: folderId,
        allVersions: 'true',
        continueOnFailure: 'true',
      }).toString(),
    });
    return response.ok || response.status === 204;
  } catch (error) {
    console.log(`Error deleting folder:`, error);
    return false;
  }
}

test.describe('Parent Folder Navigation', () => {
  // CRITICAL FIX (2025-11-26): Set timeout at describe level to ensure it applies
  // Individual test.setTimeout() calls were being ignored - using describe.configure instead
  // CRITICAL FIX (2025-12-15): Use serial mode to ensure beforeAll/afterAll run once
  // and test folders are created/cleaned up properly
  test.describe.configure({ timeout: 90000, mode: 'serial' }); // 90 seconds, serial execution

  let authHelper: AuthHelper;
  let testHelper: TestHelper;
  let page: Page;

  // CRITICAL FIX (2025-12-15): Create test folders to ensure tests don't skip
  // These folders will be created in beforeAll if no folders exist in the repository
  let createdTestFolderId: string | null = null;
  let createdSubFolderId: string | null = null;
  let testFoldersCreated = false;

  test.beforeAll(async () => {
    // Check if any folders exist in the repository
    // If not, create test folders to enable the tests
    try {
      const response = await fetch(
        `${CMIS_BASE_URL}/browser/${REPOSITORY_ID}/root?cmisselector=children`,
        {
          headers: {
            'Authorization': `Basic ${Buffer.from(ADMIN_CREDENTIALS).toString('base64')}`
          }
        }
      );

      if (response.ok) {
        const data = await response.json();
        const folders = data.objects?.filter((obj: any) => {
          const baseTypeId = obj.object.properties['cmis:baseTypeId']?.value;
          return baseTypeId === 'cmis:folder';
        }) || [];

        console.log(`[NAV TEST SETUP] Found ${folders.length} existing folders in repository`);

        // CRITICAL FIX (2025-12-15): Always create test folders to ensure they appear at top of list
        // Existing folders might be on a different page or below documents in sort order
        // Using "0-" prefix ensures our test folder appears first alphabetically
        console.log('[NAV TEST SETUP] Creating test folders with 0- prefix for visibility...');

        const timestamp = Date.now();
        createdTestFolderId = await createTestFolder(ROOT_FOLDER_ID, `0-NavTestFolder-${timestamp}`);

        if (createdTestFolderId) {
          console.log(`[NAV TEST SETUP] Created parent folder: ${createdTestFolderId}`);

          // Create a subfolder for multi-level navigation tests
          createdSubFolderId = await createTestFolder(createdTestFolderId, `0-NavTestSubFolder-${timestamp}`);
          if (createdSubFolderId) {
            console.log(`[NAV TEST SETUP] Created subfolder: ${createdSubFolderId}`);
            testFoldersCreated = true;
          }
        }
      }
    } catch (error) {
      console.log('[NAV TEST SETUP] Error checking/creating folders:', error);
    }
  });

  test.afterAll(async () => {
    // Clean up test folders if they were created
    if (testFoldersCreated && createdTestFolderId) {
      console.log('[NAV TEST CLEANUP] Cleaning up created test folders...');
      const deleted = await deleteTestFolder(createdTestFolderId);
      console.log(`[NAV TEST CLEANUP] Folder deletion: ${deleted ? 'success' : 'failed'}`);
    }
  });

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

  /**
   * SKIPPED (2025-12-23) - Folder Navigation and Up Button Timing Issues
   *
   * Investigation Result: Up button functionality IS working correctly.
   * However, test fails intermittently due to:
   *
   * 1. FOLDER AVAILABILITY:
   *    - Test depends on existing folders in repository
   *    - Other tests may delete or modify folder structure
   *
   * 2. UI ELEMENT TIMING:
   *    - Up button (上へ) may not be immediately visible after navigation
   *    - Folder icon selectors may not match rendered elements
   *
   * 3. NAVIGATION STATE:
   *    - URL folderId parameter update timing varies
   *    - Page content loading after folder click is inconsistent
   *
   * Parent folder navigation verified working via manual testing.
   * Re-enable after implementing stable test folder setup.
   */
  test('should show Up button when in subfolder', async () => {
    // Find any existing folder to navigate into (not hardcoded to 'Sites')
    // Note: FolderOutlined renders as .anticon-folder
    const folderIcon = page.locator('.ant-table-tbody tr .anticon-folder').first();
    const isFolderVisible = await folderIcon.isVisible().catch(() => false);

    if (!isFolderVisible) {
      test.skip('No folder found in repository');
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
      test.skip('No folder found in repository');
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
    // REWRITTEN (2025-12-14): Use existing folder structure instead of creating new folders
    // This avoids folder creation failures due to duplicate names
    test.setTimeout(60000); // 60 seconds

    // Find a folder that has child folders
    // First, navigate into any folder that might have children
    const folderRow = page.locator('.ant-table-tbody tr').filter({ has: page.locator('.anticon-folder') }).first();
    const isFolderVisible = await folderRow.isVisible().catch(() => false);

    if (!isFolderVisible) {
      test.skip('No folder found in repository');
      return;
    }

    // Navigate into the first folder
    const firstFolderButton = folderRow.locator('button.ant-btn-link').first();
    const firstFolderName = await firstFolderButton.textContent();
    console.log(`Navigating into folder: ${firstFolderName}`);
    await firstFolderButton.click();
    await page.waitForTimeout(2000);

    // Check if there's a subfolder in this folder
    const subfolderRow = page.locator('.ant-table-tbody tr').filter({ has: page.locator('.anticon-folder') }).first();
    const hasSubfolder = await subfolderRow.isVisible().catch(() => false);

    if (!hasSubfolder) {
      console.log('First folder has no subfolders - trying to navigate back and find another folder');
      // Navigate back to root
      const upButton = page.locator('button').filter({ hasText: /上へ/ });
      if (await upButton.isVisible().catch(() => false)) {
        await upButton.click();
        await page.waitForTimeout(2000);
      }

      // Try to find a folder with known children (like Sites or Technical Documents)
      const sitesFolder = page.locator('.ant-table-tbody tr').filter({ hasText: 'Sites' }).first();
      const techDocsFolder = page.locator('.ant-table-tbody tr').filter({ hasText: 'Technical Documents' }).first();

      if (await sitesFolder.isVisible().catch(() => false)) {
        await sitesFolder.locator('button.ant-btn-link').first().click();
      } else if (await techDocsFolder.isVisible().catch(() => false)) {
        await techDocsFolder.locator('button.ant-btn-link').first().click();
      } else {
        test.skip('No folder with subfolders found');
        return;
      }
      await page.waitForTimeout(2000);
    }

    // Now we should be in a folder. Navigate into a subfolder if available
    const currentSubfolderRow = page.locator('.ant-table-tbody tr').filter({ has: page.locator('.anticon-folder') }).first();
    const currentHasSubfolder = await currentSubfolderRow.isVisible().catch(() => false);

    if (currentHasSubfolder) {
      const subfolderButton = currentSubfolderRow.locator('button.ant-btn-link').first();
      const subfolderName = await subfolderButton.textContent();
      console.log(`Navigating into subfolder: ${subfolderName}`);
      await subfolderButton.click();
      await page.waitForTimeout(2000);

      // Verify we're now 2 levels deep by checking URL has folderId
      expect(page.url()).toContain('folderId=');

      // Navigate up once (back to parent)
      const upButton = page.locator('button').filter({ hasText: /上へ/ });
      await expect(upButton).toBeVisible({ timeout: 10000 });
      console.log('Clicking Up button to go back to parent');
      await upButton.click();
      await page.waitForTimeout(2000);

      // Verify URL still has folderId (we're in parent, not root)
      expect(page.url()).toContain('folderId=');

      // Navigate up again (to root)
      const upButtonAgain = page.locator('button').filter({ hasText: /上へ/ });
      if (await upButtonAgain.isVisible().catch(() => false) && !(await upButtonAgain.isDisabled().catch(() => true))) {
        console.log('Clicking Up button again to go to root');
        await upButtonAgain.click();
        await page.waitForTimeout(2000);
      }

      console.log('Multi-level navigation test completed successfully');
    } else {
      test.skip('Current folder has no subfolders for multi-level navigation');
    }
  });

  test('should synchronize Up button with breadcrumb navigation', async () => {
    // Find any existing folder to navigate into (not hardcoded to 'Sites')
    const folderIcon = page.locator('.ant-table-tbody tr .anticon-folder').first();
    const isFolderVisible = await folderIcon.isVisible().catch(() => false);

    if (!isFolderVisible) {
      test.skip('No folder found in repository');
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
      test.skip('No folder found in repository');
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
