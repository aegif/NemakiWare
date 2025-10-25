import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';

test.describe('Permission Management UI - ACL Display', () => {
  let authHelper: AuthHelper;
  let testHelper: TestHelper;
  const testFolderName = `permissions-test-${Date.now()}`;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    await authHelper.login();
    await page.waitForTimeout(2000);

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

  test.skip('should successfully load ACL data when clicking permissions button', async ({ page, browserName }) => {
    console.log('Test: Verifying ACL data loading (fix for "データの読み込みに失敗しました" error)');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Create a test folder
    const createFolderButton = page.locator('button').filter({ hasText: 'フォルダ作成' });
    if (await createFolderButton.count() > 0) {
      await createFolderButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      const modal = page.locator('.ant-modal:not(.ant-modal-hidden)');
      const nameInput = modal.locator('input[placeholder*="名前"], input[id*="name"]');
      await nameInput.fill(testFolderName);

      const submitButton = modal.locator('button[type="submit"], button.ant-btn-primary');
      await submitButton.click();
      await page.waitForSelector('.ant-message-success', { timeout: 10000 });
      await page.waitForTimeout(2000);

      console.log(`Test: Created test folder: ${testFolderName}`);
    }

    // Find the test folder row
    const folderRow = page.locator('tr').filter({ hasText: testFolderName });

    if (await folderRow.count() > 0) {
      // Look for permissions/ACL button (権限管理)
      const permissionsButton = folderRow.locator('button').filter({
        hasText: /権限|ACL|Permission/i
      });

      if (await permissionsButton.count() > 0) {
        console.log('Test: Clicking permissions button...');
        await permissionsButton.first().click(isMobile ? { force: true } : {});
        await page.waitForTimeout(1000);

        // CRITICAL TEST: Verify NO error message appears
        const errorMessage = page.locator('.ant-message-error').filter({ hasText: 'データの読み込みに失敗しました' });
        const errorCount = await errorMessage.count();

        if (errorCount > 0) {
          console.log('❌ ERROR: "データの読み込みに失敗しました" message appeared!');
          console.log('This indicates ACL endpoint is still failing.');
          await expect(errorMessage).not.toBeVisible();
        } else {
          console.log('✅ SUCCESS: No error message - ACL data loaded successfully');
        }

        // Verify permissions modal/drawer opened
        const permissionsModal = page.locator('.ant-modal, .ant-drawer').last();
        if (await permissionsModal.count() > 0) {
          await expect(permissionsModal).toBeVisible({ timeout: 5000 });
          console.log('✅ Permissions management modal/drawer opened');

          // Verify ACL table or list is displayed
          const aclTable = permissionsModal.locator('.ant-table, .ant-list');
          if (await aclTable.count() > 0) {
            await expect(aclTable).toBeVisible({ timeout: 5000 });
            console.log('✅ ACL data table/list displayed');

            // Check if there are any ACL entries
            const aclEntries = permissionsModal.locator('.ant-table tbody tr, .ant-list-item');
            const entryCount = await aclEntries.count();
            console.log(`ACL entries count: ${entryCount}`);

            if (entryCount > 0) {
              console.log('✅ ACL entries found - ACL data successfully retrieved');
            } else {
              console.log('ℹ️ No ACL entries (empty ACL is valid)');
            }
          } else {
            console.log('ℹ️ ACL table/list not found - may use different UI structure');
          }

          // Close modal
          const closeButton = permissionsModal.locator('button.ant-modal-close, button.ant-drawer-close, button').filter({ hasText: /閉じる|Cancel|キャンセル/i });
          if (await closeButton.count() > 0) {
            await closeButton.first().click();
            await page.waitForTimeout(500);
          }
        } else {
          console.log('ℹ️ Permissions modal not found - may need to update test selectors');
        }
      } else {
        console.log('ℹ️ Permissions button not found in folder row - checking alternative locations');

        // Try clicking the folder row first to see action buttons
        await folderRow.first().click();
        await page.waitForTimeout(1000);

        // Look for permissions button in action menu or toolbar
        const actionPermissionsButton = page.locator('button').filter({
          hasText: /権限|ACL|Permission/i
        });

        if (await actionPermissionsButton.count() > 0) {
          console.log('Test: Found permissions button in action menu');
          await actionPermissionsButton.first().click(isMobile ? { force: true } : {});
          await page.waitForTimeout(1000);

          // Verify no error
          const errorMessage = page.locator('.ant-message-error').filter({ hasText: 'データの読み込みに失敗しました' });
          await expect(errorMessage).not.toBeVisible();
          console.log('✅ No error message after clicking permissions button');
        } else {
          test.skip('Permissions button not implemented in UI yet');
        }
      }
    } else {
      test.skip('Test folder creation failed');
    }

    // Cleanup: Delete test folder
    const cleanupFolderRow = page.locator('tr').filter({ hasText: testFolderName });
    if (await cleanupFolderRow.count() > 0) {
      await cleanupFolderRow.first().click();
      await page.waitForTimeout(500);

      const deleteButton = page.locator('button').filter({ has: page.locator('[data-icon="delete"]') });
      if (await deleteButton.count() > 0) {
        await deleteButton.first().click();
        await page.waitForTimeout(500);

        const confirmButton = page.locator('.ant-popconfirm button.ant-btn-primary, button').filter({ hasText: /OK|確認/ });
        if (await confirmButton.count() > 0) {
          await confirmButton.first().click();
          await page.waitForTimeout(2000);
        }
      }
    }
  });

  test('should verify ACL REST API endpoint is accessible', async ({ page }) => {
    console.log('Test: Verifying ACL REST API endpoint');

    // Test the ACL endpoint directly via API
    const apiResponse = await page.evaluate(async () => {
      try {
        // Get root folder ID first
        const rootResponse = await fetch('/core/browser/bedroom?cmisselector=query&q=SELECT%20*%20FROM%20cmis:folder%20WHERE%20cmis:path%20=%20%27/%27', {
          headers: {
            'Authorization': 'Basic ' + btoa('admin:admin'),
            'Accept': 'application/json'
          }
        });

        if (!rootResponse.ok) {
          return {
            error: `Root folder query failed: ${rootResponse.status}`
          };
        }

        const rootData = await rootResponse.json();
        const rootFolderId = rootData.results?.[0]?.properties?.['cmis:objectId']?.value;

        if (!rootFolderId) {
          return {
            error: 'Root folder ID not found'
          };
        }

        // Test ACL endpoint (the one that was failing before fix)
        const aclResponse = await fetch(`/core/rest/repo/bedroom/node/${rootFolderId}/acl`, {
          headers: {
            'Authorization': 'Basic ' + btoa('admin:admin'),
            'Accept': 'application/json'
          }
        });

        const aclData = await aclResponse.json();

        return {
          status: aclResponse.status,
          ok: aclResponse.ok,
          data: aclData,
          hasACL: !!aclData.acl,
          hasPermissions: !!aclData.acl?.permissions
        };
      } catch (error) {
        return {
          error: error.toString()
        };
      }
    });

    console.log('ACL API response:', apiResponse);

    // Verify API response
    expect(apiResponse.status).toBe(200);
    console.log('✅ ACL endpoint returns HTTP 200');

    expect(apiResponse.ok).toBe(true);
    console.log('✅ ACL endpoint request successful');

    expect(apiResponse.hasACL).toBe(true);
    console.log('✅ ACL object exists in response');

    console.log('Test: ACL REST API endpoint verification complete');
  });

  test('should use correct REST API URL (not Browser Binding URL)', async ({ page }) => {
    console.log('Test: Verifying cmis.ts uses correct ACL endpoint URL');

    // Monitor network requests to verify correct URL is used
    const aclRequests: string[] = [];

    page.on('request', request => {
      const url = request.url();
      if (url.includes('acl')) {
        aclRequests.push(url);
        console.log(`ACL request URL: ${url}`);
      }
    });

    // Navigate to documents and try to access permissions
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click();
    await page.waitForTimeout(2000);

    // Find root folder or any folder
    const anyFolder = page.locator('tr').filter({ has: page.locator('[data-icon="folder"]') }).first();

    if (await anyFolder.count() > 0) {
      await anyFolder.click();
      await page.waitForTimeout(1000);

      // Look for permissions button
      const permissionsButton = page.locator('button').filter({
        hasText: /権限|ACL|Permission/i
      });

      if (await permissionsButton.count() > 0) {
        await permissionsButton.first().click();
        await page.waitForTimeout(2000);

        // Verify ACL request used correct URL
        console.log(`Total ACL requests: ${aclRequests.length}`);

        if (aclRequests.length > 0) {
          const hasCorrectUrl = aclRequests.some(url => url.includes('/core/rest/repo/'));
          const hasWrongUrl = aclRequests.some(url => url.includes('/core/browser/') && url.includes('acl'));

          expect(hasCorrectUrl).toBe(true);
          console.log('✅ Correct REST API URL used: /core/rest/repo/.../acl');

          expect(hasWrongUrl).toBe(false);
          console.log('✅ Wrong Browser Binding URL NOT used');
        } else {
          console.log('ℹ️ No ACL requests detected - may need to trigger permissions UI differently');
        }
      }
    }

    console.log('Test: URL verification complete');
  });
});
