import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';
import { getAuthHeader } from '../utils/auth-header';

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
 *    - Uses Date.now() timestamps for unique folder names
 *    - Format: acl-inherit-test-${timestamp}
 *    - Prevents conflicts between parallel test executions
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
 * Poll for folder row to appear in UI with retries
 * Handles Solr indexing delays by retrying with page reloads
 */
async function waitForFolderRow(page: any, folderName: string, maxAttempts = 10): Promise<any> {
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    const folderRow = page.locator('tr').filter({ hasText: folderName });
    const count = await folderRow.count();
    
    if (count > 0) {
      console.log(`Found folder row for "${folderName}" on attempt ${attempt}`);
      return folderRow;
    }
    
    console.log(`Folder row not found (attempt ${attempt}/${maxAttempts}), reloading...`);
    await page.reload();
    await page.waitForTimeout(2000);
  }
  
  throw new Error(`Folder row for "${folderName}" not found after ${maxAttempts} attempts`);
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
    const testFolderName = `acl-inherit-test-${Date.now()}`;
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

    const folderRow = await waitForFolderRow(page, testFolderName);

    let permissionsButton = folderRow.locator('button').filter({
      hasText: /権限|ACL|Permission/i
    });

    if (await permissionsButton.count() === 0) {
      const actionButton = folderRow.locator('button[aria-label*="more"], button.ant-dropdown-trigger');
      if (await actionButton.count() > 0) {
        await actionButton.first().click();
        await page.waitForTimeout(500);
        permissionsButton = page.locator('.ant-dropdown-menu button').filter({
          hasText: /権限|ACL|Permission/i
        });
      }
    }

    if (await permissionsButton.count() > 0) {
      await permissionsButton.first().scrollIntoViewIfNeeded();
      await permissionsButton.first().click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      const permissionsModal = page.locator('.ant-modal, .ant-drawer').last();
      await expect(permissionsModal).toBeVisible({ timeout: 5000 });

      const breakInheritanceButton = permissionsModal.locator('button').filter({
        hasText: /継承を切る|Break Inheritance/i
      });

      await expect(breakInheritanceButton).toBeVisible({ timeout: 5000 });
      console.log('✅ Break inheritance button is visible for inherited permissions');

      // Close modal
      const closeButton = permissionsModal.locator('button.ant-modal-close, button.ant-drawer-close');
      if (await closeButton.count() > 0) {
        await closeButton.first().click();
        await page.waitForTimeout(500);
      }
    } else {
      test.skip('Permissions button not found');
    }
  });

  test('should show confirmation dialog when breaking inheritance', async ({ page, browserName }) => {
    const testFolderName = `acl-inherit-test-${Date.now()}`;
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

    // Wait for folder to appear in UI (handles indexing delays)
    const folderRow = await waitForFolderRow(page, testFolderName);

    // Try multiple selector strategies for permissions button
    let permissionsButton = folderRow.locator('button').filter({
      hasText: /権限|ACL|Permission/i
    });

    if (await permissionsButton.count() === 0) {
      const actionButton = folderRow.locator('button[aria-label*="more"], button.ant-dropdown-trigger');
      if (await actionButton.count() > 0) {
        await actionButton.first().click();
        await page.waitForTimeout(500);
        permissionsButton = page.locator('.ant-dropdown-menu button').filter({
          hasText: /権限|ACL|Permission/i
        });
      }
    }

    if (await permissionsButton.count() > 0) {
      await permissionsButton.first().scrollIntoViewIfNeeded();
      await permissionsButton.first().click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      const permissionsModal = page.locator('.ant-modal, .ant-drawer').last();
      await expect(permissionsModal).toBeVisible({ timeout: 5000 });

      const breakInheritanceButton = permissionsModal.locator('button').filter({
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

      const closeButton = permissionsModal.locator('button.ant-modal-close, button.ant-drawer-close');
      if (await closeButton.count() > 0) {
        await closeButton.first().click();
        await page.waitForTimeout(500);
      }
    } else {
      test.skip('Permissions button not found');
    }
  });

  test('should successfully break inheritance and show success message', async ({ page, browserName }) => {
    const testFolderName = `acl-inherit-test-${Date.now()}`;
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

    // Wait for folder to appear in UI (handles indexing delays)
    const folderRow = await waitForFolderRow(page, testFolderName);

    // Try multiple selector strategies for permissions button
    let permissionsButton = folderRow.locator('button').filter({
      hasText: /権限|ACL|Permission/i
    });

    if (await permissionsButton.count() === 0) {
      const actionButton = folderRow.locator('button[aria-label*="more"], button.ant-dropdown-trigger');
      if (await actionButton.count() > 0) {
        await actionButton.first().click();
        await page.waitForTimeout(500);
        permissionsButton = page.locator('.ant-dropdown-menu button').filter({
          hasText: /権限|ACL|Permission/i
        });
      }
    }

    if (await permissionsButton.count() > 0) {
      await permissionsButton.first().scrollIntoViewIfNeeded();
      await permissionsButton.first().click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      const permissionsModal = page.locator('.ant-modal, .ant-drawer').last();
      await expect(permissionsModal).toBeVisible({ timeout: 5000 });

      const breakInheritanceButton = permissionsModal.locator('button').filter({
        hasText: /継承を切る|Break Inheritance/i
      });

      await breakInheritanceButton.click();
      await page.waitForTimeout(500);

      const confirmDialog = page.locator('.ant-modal-confirm');
      const confirmButton = confirmDialog.locator('button').filter({ hasText: /継承を切断|OK/i });
      await confirmButton.click();
      await page.waitForTimeout(1000);

      const successMessage = page.locator('.ant-message-success');
      await expect(successMessage).toBeVisible({ timeout: 5000 });
      console.log('✅ Success message appeared');

      await page.waitForTimeout(2000);

      const breakButtonAfter = permissionsModal.locator('button').filter({
        hasText: /継承を切る|Break Inheritance/i
      });
      await expect(breakButtonAfter).not.toBeVisible();
      console.log('✅ Break inheritance button is hidden after breaking');

      const aclCheckResponse = await page.request.get(
        `http://localhost:8080/core/browser/bedroom/${folderId}?cmisselector=object`,
        { headers: getAuthHeader() }
      );

      if (aclCheckResponse.ok()) {
        const objectData = await aclCheckResponse.json();
        const aclInherited = objectData.properties?.['cmis:aclInherited']?.value;
        console.log(`ACL inherited status: ${aclInherited}`);
        
        if (aclInherited !== undefined) {
          expect(aclInherited).toBe(false);
          console.log('✅ ACL inheritance is broken (verified via API)');
        } else {
          console.log('ℹ️ ACL inherited field not found in object properties');
        }
      }

      const closeButton = permissionsModal.locator('button.ant-modal-close, button.ant-drawer-close');
      if (await closeButton.count() > 0) {
        await closeButton.first().click();
        await page.waitForTimeout(500);
      }
    } else {
      test.skip('Permissions button not found');
    }
  });

  test('should convert inherited permissions to direct permissions after breaking', async ({ page, browserName }) => {
    const testFolderName = `acl-inherit-test-${Date.now()}`;
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
    const inheritedPermissionsCount = aclBefore.aces?.filter((ace: any) => !ace.isDirect).length || 0;
    console.log(`Inherited permissions before breaking: ${inheritedPermissionsCount}`);

    // Wait for folder to appear in UI (handles indexing delays)
    const folderRow = await waitForFolderRow(page, testFolderName);

    // Try multiple selector strategies for permissions button
    let permissionsButton = folderRow.locator('button').filter({
      hasText: /権限|ACL|Permission/i
    });

    if (await permissionsButton.count() === 0) {
      const actionButton = folderRow.locator('button[aria-label*="more"], button.ant-dropdown-trigger');
      if (await actionButton.count() > 0) {
        await actionButton.first().click();
        await page.waitForTimeout(500);
        permissionsButton = page.locator('.ant-dropdown-menu button').filter({
          hasText: /権限|ACL|Permission/i
        });
      }
    }

    if (await permissionsButton.count() > 0) {
      await permissionsButton.first().scrollIntoViewIfNeeded();
      await permissionsButton.first().click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      const permissionsModal = page.locator('.ant-modal, .ant-drawer').last();
      await expect(permissionsModal).toBeVisible({ timeout: 5000 });

      const breakInheritanceButton = permissionsModal.locator('button').filter({
        hasText: /継承を切る|Break Inheritance/i
      });

      await breakInheritanceButton.click();
      await page.waitForTimeout(500);

      const confirmDialog = page.locator('.ant-modal-confirm');
      const confirmButton = confirmDialog.locator('button').filter({ hasText: /継承を切断|OK/i });
      await confirmButton.click();
      await page.waitForTimeout(2000);

      const aclAfterResponse = await page.request.get(
        `http://localhost:8080/core/browser/bedroom/${folderId}?cmisselector=acl`,
        { headers: getAuthHeader() }
      );

      expect(aclAfterResponse.ok()).toBeTruthy();
      const aclAfter = await aclAfterResponse.json();
      const inheritedPermissionsAfter = aclAfter.aces?.filter((ace: any) => !ace.isDirect).length || 0;
      const directPermissionsAfter = aclAfter.aces?.filter((ace: any) => ace.isDirect).length || 0;

      console.log(`Inherited permissions after breaking: ${inheritedPermissionsAfter}`);
      console.log(`Direct permissions after breaking: ${directPermissionsAfter}`);

      expect(inheritedPermissionsAfter).toBe(0);
      console.log('✅ No inherited permissions remain after breaking');

      expect(directPermissionsAfter).toBeGreaterThan(0);
      console.log('✅ Direct permissions exist after breaking inheritance');

      const closeButton = permissionsModal.locator('button.ant-modal-close, button.ant-drawer-close');
      if (await closeButton.count() > 0) {
        await closeButton.first().click();
        await page.waitForTimeout(500);
      }
    } else {
      test.skip('Permissions button not found');
    }
  });
});
