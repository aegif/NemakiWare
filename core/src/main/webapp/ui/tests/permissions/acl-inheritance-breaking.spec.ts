import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';
import { getAuthHeader } from '../utils/auth-header';
import { getAclInheritedViaRest } from '../utils/acl';

/**
 * ACL Inheritance Breaking E2E Tests
 *
 * Tests the ACL inheritance breaking functionality in NemakiWare CMIS repository:
 * - Break inheritance button visibility when permissions are inherited
 * - Break inheritance confirmation dialog
 * - Successful inheritance breaking operation
 * - Inherited permissions converted to direct permissions after breaking
 * - Break inheritance button hidden after inheritance is broken
 *
 * IMPORTANT DESIGN DECISIONS:
 * 1. CMIS API-First Testing Strategy:
 *    - Uses CMIS Browser Binding API directly for folder creation and ACL operations
 *    - Bypasses UI interactions for setup reliability and speed
 *    - Enables precise ACL verification via dedicated ACL endpoint
 *    - UI testing focuses on the break inheritance button and dialog
 *
 * 2. Unique Test Data per Instance:
 *    - Uses Date.now() timestamps + random value for unique folder names
 *    - Format: acl-inherit-test-${timestamp}-${random}
 *    - Prevents conflicts between parallel test executions (multiple workers)
 *
 * 3. Comprehensive Cleanup Strategy:
 *    - afterEach hook queries for all test folders matching pattern
 *    - Deletes folders via CMIS API
 *    - Prevents test data accumulation across test runs
 *
 * 4. Robust UI Element Detection:
 *    - Uses polling with retries for folder row appearance (handles indexing delays)
 *    - Multiple selector fallbacks for permissions button
 *    - Scroll-into-view before clicking to ensure visibility
 *
 * Test Execution Order:
 * - Test 1: Verify break inheritance button appears for inherited permissions
 * - Test 2: Verify break inheritance confirmation dialog
 * - Test 3: Verify successful inheritance breaking operation
 * - Test 4: Verify inherited permissions become direct after breaking
 */

/**
 * Poll for folder row to appear in main table with retries
 * Handles Solr indexing delays by retrying with page reloads
 *
 * REWRITTEN (2025-12-14): Removed incorrect selectRootFolder that used non-existent treeitem.
 * The documents page shows root folder contents directly without tree navigation.
 */
async function waitForTableRow(page: any, folderName: string, maxAttempts = 10): Promise<any> {
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    // Wait for table to be visible and have data
    try {
      await page.waitForSelector('.ant-table-tbody tr', { timeout: 5000 });
    } catch {
      console.log(`Table not visible yet (attempt ${attempt}/${maxAttempts})`);
      await page.reload();
      await page.waitForTimeout(2000);
      continue;
    }

    const table = page.locator('.ant-table');
    const folderRow = table.locator('tbody tr').filter({ hasText: folderName });
    const count = await folderRow.count();

    if (count > 0) {
      console.log(`Found folder row in table for "${folderName}" on attempt ${attempt}`);
      // Fix strict mode violation: return only the first matching row
      // This prevents errors when duplicate folders exist in the table
      return folderRow.first();
    }

    console.log(`Folder row not found in table (attempt ${attempt}/${maxAttempts}), reloading...`);
    await page.reload();
    await page.waitForTimeout(2000);
  }

  throw new Error(`Folder row for "${folderName}" not found in table after ${maxAttempts} attempts`);
}
test.describe('ACL Inheritance Breaking', () => {
  let authHelper: AuthHelper;
  let testHelper: TestHelper;
  let rootFolderId: string;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    await authHelper.login();
    await page.waitForTimeout(2000);

    const repoInfoResponse = await page.request.get(
      'http://localhost:8080/core/browser/bedroom?cmisselector=repositoryInfo',
      { headers: getAuthHeader() }
    );
    const repoInfo = await repoInfoResponse.json();
    rootFolderId = repoInfo.bedroom.rootFolderId;

    // MOBILE FIX: Close sidebar
    const viewportSize = page.viewportSize();
    const isMobileChrome = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobileChrome) {
      const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]');
      if (await menuToggle.count() > 0) {
        await menuToggle.first().click({ timeout: 3000 });
        await page.waitForTimeout(500);
      }
    }

    await testHelper.waitForAntdLoad();
  });

  test.afterEach(async ({ page }) => {
    // Cleanup: Delete any test folders via CMIS API
    console.log('afterEach: Cleaning up test folders');

    try {
      const queryResponse = await page.request.get(
        `http://localhost:8080/core/browser/bedroom?cmisselector=query&q=SELECT%20cmis:objectId%20FROM%20cmis:folder%20WHERE%20cmis:name%20LIKE%20'acl-inherit-test-%25'`,
        { headers: getAuthHeader() }
      );

      if (queryResponse.ok()) {
        const queryResult = await queryResponse.json();
        const folders = queryResult.results || [];

        for (const folder of folders) {
          const folderId = folder.properties?.['cmis:objectId']?.value;
          if (folderId) {
            await page.request.post('http://localhost:8080/core/browser/bedroom', {
              headers: getAuthHeader(),
              form: {
                'cmisaction': 'delete',
                'objectId': folderId
              }
            });
            console.log(`afterEach: Deleted folder ${folderId}`);
          }
        }
      }
    } catch (error) {
      console.log('afterEach: Cleanup failed (non-critical):', error);
    }
  });

  test('should show break inheritance button when permissions are inherited', async ({ page, browserName }) => {
    const testFolderName = `acl-inherit-test-${Date.now()}-${Math.random().toString(36).substring(2, 8)}`;
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    console.log('Test: Verifying break inheritance button visibility');

    // Navigate to documents
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    const createResponse = await page.request.post('http://localhost:8080/core/browser/bedroom', {
      headers: getAuthHeader(),
      form: {
        'cmisaction': 'createFolder',
        'objectId': rootFolderId,
        'propertyId[0]': 'cmis:name',
        'propertyValue[0]': testFolderName,
        'propertyId[1]': 'cmis:objectTypeId',
        'propertyValue[1]': 'cmis:folder'
      }
    });

    expect(createResponse.ok()).toBeTruthy();
    console.log(`Test: Created test folder: ${testFolderName}`);

    const folderRow = await waitForTableRow(page, testFolderName);

    const permissionsButton = folderRow.locator('button').filter({ hasText: /権限管理|Permission/i }).first();
    await expect(permissionsButton).toBeVisible({ timeout: 5000 });
    await permissionsButton.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(1000);

    // Wait for permissions page to load (not a modal, but a full page)
    const permissionsHeading = page.locator('h2').filter({ hasText: /権限管理|Permission/i });
    await expect(permissionsHeading).toBeVisible({ timeout: 5000 });

    const breakInheritanceButton = page.locator('button').filter({
      hasText: /継承を切る|Break Inheritance/i
    });

    await expect(breakInheritanceButton).toBeVisible({ timeout: 5000 });
    console.log('✅ Break inheritance button is visible for inherited permissions');

    // Navigate back to documents
    const backButton = page.locator('button').filter({ hasText: /戻る|Back/i });
    if (await backButton.count() > 0) {
      await backButton.first().click();
      await page.waitForTimeout(500);
    }
  });

  test('should show confirmation dialog when breaking inheritance', async ({ page, browserName }) => {
    const testFolderName = `acl-inherit-test-${Date.now()}-${Math.random().toString(36).substring(2, 8)}`;
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    console.log('Test: Verifying break inheritance confirmation dialog');

    // Navigate to documents
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    const createResponse = await page.request.post('http://localhost:8080/core/browser/bedroom', {
      headers: getAuthHeader(),
      form: {
        'cmisaction': 'createFolder',
        'objectId': rootFolderId,
        'propertyId[0]': 'cmis:name',
        'propertyValue[0]': testFolderName,
        'propertyId[1]': 'cmis:objectTypeId',
        'propertyValue[1]': 'cmis:folder'
      }
    });

    expect(createResponse.ok()).toBeTruthy();

    const folderRow = await waitForTableRow(page, testFolderName);

    const permissionsButton = folderRow.locator('button').filter({ hasText: /権限管理|Permission/i }).first();
    await expect(permissionsButton).toBeVisible({ timeout: 5000 });
    await permissionsButton.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(1000);

    // Wait for permissions page to load (not a modal, but a full page)
    const permissionsHeading = page.locator('h2').filter({ hasText: /権限管理|Permission/i });
    await expect(permissionsHeading).toBeVisible({ timeout: 5000 });

    const breakInheritanceButton = page.locator('button').filter({
      hasText: /継承を切る|Break Inheritance/i
    });

    await breakInheritanceButton.click();
    await page.waitForTimeout(500);

    const confirmDialog = page.locator('.ant-modal-confirm');
    await expect(confirmDialog).toBeVisible({ timeout: 5000 });
    console.log('✅ Confirmation dialog appeared');

    const dialogTitle = confirmDialog.locator('.ant-modal-confirm-title');
    await expect(dialogTitle).toContainText(/ACL継承を切断しますか/i);
    console.log('✅ Dialog title is correct');

    const dialogContent = confirmDialog.locator('.ant-modal-confirm-content');
    await expect(dialogContent).toContainText(/親フォルダからの権限継承を解除します/i);
    console.log('✅ Dialog content is correct');

    const cancelButton = confirmDialog.locator('button').filter({ hasText: /キャンセル|Cancel/i });
    if (await cancelButton.count() > 0) {
      await cancelButton.click();
      await page.waitForTimeout(500);
    }

    // Navigate back to documents
    const backButton = page.locator('button').filter({ hasText: /戻る|Back/i });
    if (await backButton.count() > 0) {
      await backButton.first().click();
      await page.waitForTimeout(500);
    }
  });

  test('should successfully break inheritance and show success message', async ({ page, browserName }) => {
    const testFolderName = `acl-inherit-test-${Date.now()}-${Math.random().toString(36).substring(2, 8)}`;
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    console.log('Test: Verifying successful inheritance breaking');

    // Navigate to documents
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    const createResponse = await page.request.post('http://localhost:8080/core/browser/bedroom', {
      headers: getAuthHeader(),
      form: {
        'cmisaction': 'createFolder',
        'objectId': rootFolderId,
        'propertyId[0]': 'cmis:name',
        'propertyValue[0]': testFolderName,
        'propertyId[1]': 'cmis:objectTypeId',
        'propertyValue[1]': 'cmis:folder'
      }
    });

    expect(createResponse.ok()).toBeTruthy();

    const createResult = await createResponse.json();
    const folderId = createResult.succinctProperties?.['cmis:objectId'];
    expect(folderId).toBeTruthy();
    console.log(`Test: Folder ID: ${folderId}`);

    const folderRow = await waitForTableRow(page, testFolderName);

    const permissionsButton = folderRow.locator('button').filter({ hasText: /権限管理|Permission/i }).first();
    await expect(permissionsButton).toBeVisible({ timeout: 5000 });
    await permissionsButton.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(1000);

    // Wait for permissions page to load (not a modal, but a full page)
    const permissionsHeading = page.locator('h2').filter({ hasText: /権限管理|Permission/i });
    await expect(permissionsHeading).toBeVisible({ timeout: 5000 });

    const breakInheritanceButton = page.locator('button').filter({
      hasText: /継承を切る|Break Inheritance/i
    });

    await breakInheritanceButton.click();
    await page.waitForTimeout(500);

    const confirmDialog = page.locator('.ant-modal-confirm');
    const confirmButton = confirmDialog.locator('button.ant-btn-primary').first();
    await expect(confirmButton).toBeVisible({ timeout: 3000 });
    await confirmButton.click();

    // Wait for success message to confirm operation completed
    const successMessage = page.locator('.ant-message-success');
    await expect(successMessage).toBeVisible({ timeout: 10000 });
    console.log('✅ Success message appeared');

    // Wait for the page to reload and update the button visibility
    await page.waitForTimeout(3000);

    const breakButtonAfter = page.locator('button').filter({
      hasText: /継承を切る|Break Inheritance/i
    });
    await expect(breakButtonAfter).not.toBeVisible({ timeout: 10000 });
    console.log('✅ Break inheritance button is hidden after breaking');

    const aclInherited = await getAclInheritedViaRest(page, 'bedroom', folderId);
    expect(aclInherited).toBe(false);
    console.log('✅ ACL inheritance is broken (verified via REST API)');

    // Navigate back to documents
    const backButton = page.locator('button').filter({ hasText: /戻る|Back/i });
    if (await backButton.count() > 0) {
      await backButton.first().click();
      await page.waitForTimeout(500);
    }
  });

  test('should convert inherited permissions to direct permissions after breaking', async ({ page, browserName }) => {
    const testFolderName = `acl-inherit-test-${Date.now()}-${Math.random().toString(36).substring(2, 8)}`;
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    console.log('Test: Verifying inherited permissions become direct after breaking');

    // Navigate to documents
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    const createResponse = await page.request.post('http://localhost:8080/core/browser/bedroom', {
      headers: getAuthHeader(),
      form: {
        'cmisaction': 'createFolder',
        'objectId': rootFolderId,
        'propertyId[0]': 'cmis:name',
        'propertyValue[0]': testFolderName,
        'propertyId[1]': 'cmis:objectTypeId',
        'propertyValue[1]': 'cmis:folder'
      }
    });

    expect(createResponse.ok()).toBeTruthy();

    const createResult = await createResponse.json();
    const folderId = createResult.succinctProperties?.['cmis:objectId'];
    expect(folderId).toBeTruthy();

    const aclBeforeResponse = await page.request.get(
      `http://localhost:8080/core/browser/bedroom/${folderId}?cmisselector=acl`,
      { headers: getAuthHeader() }
    );

    expect(aclBeforeResponse.ok()).toBeTruthy();
    const aclBefore = await aclBeforeResponse.json();
    const inheritedPermissionsCount = aclBefore.aces?.filter((ace: any) => !ace.direct).length || 0;
    console.log(`Inherited permissions before breaking: ${inheritedPermissionsCount}`);

    const folderRow = await waitForTableRow(page, testFolderName);

    const permissionsButton = folderRow.locator('button').filter({ hasText: /権限管理|Permission/i }).first();
    await expect(permissionsButton).toBeVisible({ timeout: 5000 });
    await permissionsButton.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(1000);

    // Wait for permissions page to load (not a modal, but a full page)
    const permissionsHeading = page.locator('h2').filter({ hasText: /権限管理|Permission/i });
    await expect(permissionsHeading).toBeVisible({ timeout: 5000 });

    const breakInheritanceButton = page.locator('button').filter({
      hasText: /継承を切る|Break Inheritance/i
    });

    await breakInheritanceButton.click();
    await page.waitForTimeout(500);

    const confirmDialog = page.locator('.ant-modal-confirm');
    const confirmButton = confirmDialog.locator('button.ant-btn-primary').first();
    await expect(confirmButton).toBeVisible({ timeout: 3000 });
    await confirmButton.click();

    // Wait for success message to ensure operation completed before checking ACL
    const successMessage = page.locator('.ant-message-success');
    await expect(successMessage).toBeVisible({ timeout: 10000 });

    // Additional wait to ensure backend operation completes (success message appears quickly but backend may still be processing)
    await page.waitForTimeout(2000);

    const aclAfterResponse = await page.request.get(
      `http://localhost:8080/core/browser/bedroom/${folderId}?cmisselector=acl`,
      { headers: getAuthHeader() }
    );

    expect(aclAfterResponse.ok()).toBeTruthy();
    const aclAfter = await aclAfterResponse.json();
    const inheritedPermissionsAfter = aclAfter.aces?.filter((ace: any) => !ace.direct).length || 0;
    const directPermissionsAfter = aclAfter.aces?.filter((ace: any) => ace.direct).length || 0;

    console.log(`Inherited permissions after breaking: ${inheritedPermissionsAfter}`);
    console.log(`Direct permissions after breaking: ${directPermissionsAfter}`);

    expect(inheritedPermissionsAfter).toBe(0);
    console.log('✅ No inherited permissions remain after breaking');

    expect(directPermissionsAfter).toBeGreaterThan(0);
    console.log('✅ Direct permissions exist after breaking inheritance');

    // Navigate back to documents
    const backButton = page.locator('button').filter({ hasText: /戻る|Back/i });
    if (await backButton.count() > 0) {
      await backButton.first().click();
      await page.waitForTimeout(500);
    }
  });
});
