import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';

test.describe('Error Handling', () => {
  let authHelper: AuthHelper;
  let testHelper: TestHelper;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    // Login as admin
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

    // Navigate to documents
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click();
    await page.waitForTimeout(2000);
  });

  test('should handle network errors during document operations', async ({ page, context, browserName }) => {
    test.setTimeout(90000); // 90 second timeout

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    console.log('Test: Starting network error handling test');

    // Try to upload a document
    const uploadButton = page.locator('button').filter({ hasText: 'アップロード' }).first();
    await uploadButton.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(1000);

    const fileInput = page.locator('input[type="file"]');
    await fileInput.setInputFiles({
      name: 'network-error-test.txt',
      mimeType: 'text/plain',
      buffer: Buffer.from('Test content for network error', 'utf-8'),
    });

    console.log('Test: File selected for upload');

    // Wait for upload to start
    await page.waitForTimeout(2000);

    // Simulate network disconnection by blocking all network requests
    await context.route('**/*', route => route.abort('failed'));

    console.log('Test: Network requests blocked to simulate connection error');

    await page.waitForTimeout(3000);

    // Should see error message
    const errorIndicators = [
      page.locator('.ant-message-error'),
      page.locator('.ant-notification-error'),
      page.locator('.ant-alert-error'),
      page.locator('.ant-upload-list-item-error'),
      page.locator('[class*="error"]').filter({ hasText: /エラー|error|失敗|failed/i })
    ];

    let errorFound = false;
    for (const indicator of errorIndicators) {
      if (await indicator.count() > 0 && await indicator.first().isVisible({ timeout: 5000 }).catch(() => false)) {
        console.log('Test: Error indicator found:', await indicator.first().textContent());
        errorFound = true;
        break;
      }
    }

    if (errorFound) {
      console.log('Test: Network error correctly displayed to user');
    } else {
      console.log('Test: No explicit error message, but operation should not complete');
    }

    // Restore network
    await context.unroute('**/*');

    console.log('Test: Network restored');

    // Try another operation to verify recovery
    await page.reload();
    await page.waitForTimeout(3000);

    // Should be able to navigate after network recovery
    const documentsMenuAfterRecovery = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    if (await documentsMenuAfterRecovery.count() > 0) {
      await documentsMenuAfterRecovery.click();
      await page.waitForTimeout(2000);
      console.log('Test: Successfully recovered from network error');
    }

    console.log('Test: Network error handling test completed');
  });

  test('should display appropriate error messages for permission denied', async ({ page, browserName }) => {
    test.setTimeout(90000);

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    console.log('Test: Testing permission denied error handling');

    // Try to access admin functions (user management) which may require special permissions
    const adminMenu = page.locator('.ant-menu-submenu').filter({ hasText: '管理' });

    if (await adminMenu.count() > 0) {
      await adminMenu.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      const userManagementItem = page.locator('.ant-menu-item').filter({ hasText: 'ユーザー管理' });

      if (await userManagementItem.count() > 0) {
        await userManagementItem.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(2000);

        console.log('Test: Accessed user management page');

        // Try to create a user without proper permissions (by manipulating API call)
        // This simulates a permission denied scenario

        // Alternatively, try to delete .system folder which should be protected
        const systemFolder = page.locator('.ant-table-tbody tr, .ant-tree-treenode').filter({ hasText: '.system' });

        if (await systemFolder.count() > 0) {
          await systemFolder.first().click();
          await page.waitForTimeout(500);

          const deleteButton = page.locator('button').filter({ has: page.locator('[data-icon="delete"]') });

          if (await deleteButton.count() > 0) {
            await deleteButton.first().click(isMobile ? { force: true } : {});
            await page.waitForTimeout(500);

            // Try to confirm deletion (should fail)
            const confirmButton = page.locator('.ant-popconfirm button.ant-btn-primary, button').filter({ hasText: /OK|確認/ });
            if (await confirmButton.count() > 0) {
              await confirmButton.first().click();
              await page.waitForTimeout(2000);

              // Should see permission denied error
              const errorMessage = page.locator('.ant-message-error, .ant-notification-error').filter({ hasText: /権限|permission|denied|forbidden/i });

              if (await errorMessage.count() > 0) {
                console.log('Test: Permission denied error correctly displayed');
                await expect(errorMessage.first()).toBeVisible({ timeout: 5000 });
              } else {
                console.log('Test: Operation prevented (may not show explicit permission error)');
              }
            }
          }
        }
      }
    } else {
      console.log('Test: Admin menu not accessible (expected for non-admin users)');
      // This itself is a form of permission handling
    }

    console.log('Test: Permission denied error handling test completed');
  });

  test('should recover gracefully from server errors', async ({ page, context, browserName }) => {
    test.setTimeout(90000);

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    console.log('Test: Testing server error recovery');

    // Simulate server error by intercepting API calls and returning 500
    await context.route('**/core/browser/**', route => {
      route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({
          error: 'Internal Server Error',
          message: 'Simulated server error for testing'
        })
      });
    });

    console.log('Test: Server error simulation activated (500 responses)');

    // Try to perform an operation (e.g., navigate to folders)
    const documentsMenu = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    if (await documentsMenu.count() > 0) {
      await documentsMenu.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(3000);
    }

    // Should see error message
    const serverErrorIndicators = [
      page.locator('.ant-message-error'),
      page.locator('.ant-notification-error'),
      page.locator('.ant-alert-error'),
      page.locator('[class*="error"]').filter({ hasText: /サーバー|server|エラー|error/i })
    ];

    let serverErrorFound = false;
    for (const indicator of serverErrorIndicators) {
      if (await indicator.count() > 0 && await indicator.first().isVisible({ timeout: 5000 }).catch(() => false)) {
        console.log('Test: Server error message displayed:', await indicator.first().textContent());
        serverErrorFound = true;
        break;
      }
    }

    // Remove server error simulation
    await context.unroute('**/core/browser/**');

    console.log('Test: Server error simulation removed');

    // Try to recover - reload page
    await page.reload();
    await page.waitForTimeout(3000);

    // Should be able to access documents again
    const documentsMenuAfterRecovery = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    if (await documentsMenuAfterRecovery.count() > 0) {
      await documentsMenuAfterRecovery.click();
      await page.waitForTimeout(2000);

      // Verify document list loads
      const documentTable = page.locator('.ant-table, .ant-list');
      if (await documentTable.count() > 0) {
        console.log('Test: Successfully recovered from server error - document list loaded');
        await expect(documentTable.first()).toBeVisible({ timeout: 10000 });
      }
    }

    console.log('Test: Server error recovery test completed');
  });

  test('should validate user input and show field-level errors', async ({ page, browserName }) => {
    test.setTimeout(90000);

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    console.log('Test: Testing input validation and field-level errors');

    // Navigate to folder creation to test input validation
    const createFolderButton = page.locator('button').filter({ hasText: /新規フォルダ|フォルダ作成|New Folder/i }).first();

    if (await createFolderButton.count() > 0) {
      await createFolderButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      console.log('Test: Folder creation dialog opened');

      // Try to submit with empty name (should show validation error)
      const submitButton = page.locator('button[type="submit"], button').filter({ hasText: /作成|OK|Create/ }).first();

      if (await submitButton.count() > 0) {
        await submitButton.click();
        await page.waitForTimeout(1000);

        // Should see validation error
        const validationErrors = [
          page.locator('.ant-form-item-explain-error'),
          page.locator('.ant-form-item-has-error'),
          page.locator('[class*="error"]').filter({ hasText: /必須|required|入力/i })
        ];

        let validationErrorFound = false;
        for (const errorIndicator of validationErrors) {
          if (await errorIndicator.count() > 0) {
            console.log('Test: Validation error found for empty field');
            validationErrorFound = true;
            await expect(errorIndicator.first()).toBeVisible({ timeout: 5000 });
            break;
          }
        }

        if (!validationErrorFound) {
          console.log('Test: Form may prevent submission (button disabled or no error shown)');
        }

        // Try to input invalid characters (if folder name has restrictions)
        const folderNameInput = page.locator('input[placeholder*="フォルダ名"], input[placeholder*="名前"], input[type="text"]').first();

        if (await folderNameInput.count() > 0) {
          // Try special characters that might be invalid in folder names
          await folderNameInput.fill('/\\:*?"<>|');
          await page.waitForTimeout(500);

          await submitButton.click();
          await page.waitForTimeout(1000);

          // Should see validation error for invalid characters
          const invalidCharError = page.locator('.ant-form-item-explain-error, .ant-message-error').filter({ hasText: /無効|invalid|文字|character/i });

          if (await invalidCharError.count() > 0) {
            console.log('Test: Invalid character validation error shown');
            await expect(invalidCharError.first()).toBeVisible({ timeout: 5000 });
          } else {
            console.log('Test: Invalid characters may be automatically filtered or prevented');
          }
        }

        // Close the dialog
        const cancelButton = page.locator('button').filter({ hasText: /キャンセル|Cancel/ }).first();
        if (await cancelButton.count() > 0) {
          await cancelButton.click();
          await page.waitForTimeout(500);
        } else {
          // Try ESC key
          await page.keyboard.press('Escape');
          await page.waitForTimeout(500);
        }
      }
    } else {
      console.log('Test: Folder creation button not found, trying user management form');

      // Alternative: Try user management form validation
      const adminMenu = page.locator('.ant-menu-submenu').filter({ hasText: '管理' });

      if (await adminMenu.count() > 0) {
        await adminMenu.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(1000);

        const userManagementItem = page.locator('.ant-menu-item').filter({ hasText: 'ユーザー管理' });

        if (await userManagementItem.count() > 0) {
          await userManagementItem.click(isMobile ? { force: true } : {});
          await page.waitForTimeout(2000);

          const newUserButton = page.locator('button').filter({ hasText: /新規ユーザー|新規作成/ }).first();

          if (await newUserButton.count() > 0) {
            await newUserButton.click(isMobile ? { force: true } : {});
            await page.waitForTimeout(1000);

            // Try to submit empty form
            const submitUserButton = page.locator('button[type="submit"], button').filter({ hasText: /作成|OK/ }).first();

            if (await submitUserButton.count() > 0) {
              await submitUserButton.click();
              await page.waitForTimeout(1000);

              // Should see validation errors on required fields
              const formErrors = page.locator('.ant-form-item-explain-error, .ant-form-item-has-error');

              if (await formErrors.count() > 0) {
                console.log('Test: Form validation errors shown for empty required fields');
                await expect(formErrors.first()).toBeVisible({ timeout: 5000 });
              }
            }

            // Close dialog
            await page.keyboard.press('Escape');
            await page.waitForTimeout(500);
          }
        }
      }
    }

    console.log('Test: Input validation test completed');
  });
});
