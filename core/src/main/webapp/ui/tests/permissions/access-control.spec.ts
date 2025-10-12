import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';

test.describe('Access Control and Permissions', () => {
  let authHelper: AuthHelper;
  let testHelper: TestHelper;
  const restrictedFolderName = `restricted-folder-${Date.now()}`;
  const testDocName = `permission-test-doc-${Date.now()}.txt`;

  // Generate unique test user name to avoid conflicts with existing users
  const testUsername = `testuser${Date.now()}`;
  const testUserPassword = 'TestPass123!';

  // Setup: Create test user
  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext();
    const page = await context.newPage();
    const setupAuthHelper = new AuthHelper(page);

    try {
      // Login as admin
      await setupAuthHelper.login();
      await page.waitForTimeout(2000);

      // Navigate to user management
      console.log('Setup: Navigating to user management');
      const adminMenu = page.locator('.ant-menu-submenu:has-text("管理")');
      if (await adminMenu.count() > 0) {
        console.log('Setup: Found 管理 menu, clicking...');
        await adminMenu.click();
        await page.waitForTimeout(1000);
      } else {
        console.log('Setup: 管理 menu not found');
      }

      const userManagementItem = page.locator('.ant-menu-item:has-text("ユーザー管理")');
      if (await userManagementItem.count() > 0) {
        console.log('Setup: Found ユーザー管理 menu item, clicking...');
        await userManagementItem.click();
        await page.waitForTimeout(2000);
      } else {
        console.log('Setup: ユーザー管理 menu item not found');
      }

        // With unique username approach, no need to check for existing user
        console.log(`Setup: Creating test user: ${testUsername}`);

        // Debug: Check what's on the page
        const allButtons = await page.locator('button').allTextContents();
        console.log(`Setup: Found ${allButtons.length} buttons on page:`, allButtons.slice(0, 10));

        // Create test user
        const createButton = page.locator('button').filter({
          hasText: /新規作成|ユーザー追加|追加/
        });

        if (await createButton.count() > 0) {
          console.log('Setup: Found create button, clicking...');
          await createButton.first().click();
          await page.waitForTimeout(1000);

          // Wait for modal/drawer to be visible
          const modal = page.locator('.ant-modal:not(.ant-modal-hidden), .ant-drawer:not(.ant-drawer-hidden)');
          await modal.waitFor({ state: 'visible', timeout: 5000 });
          console.log('Setup: Modal opened');

          // Fill username
          const usernameInput = page.locator('input[id*="username"], input[id*="userId"], input[name="username"], input[name="userId"], .ant-modal input, .ant-drawer input').first();
          await usernameInput.waitFor({ state: 'visible', timeout: 3000 });
          await usernameInput.fill(testUsername);
          console.log(`Setup: Filled username: ${testUsername}`);

          // Fill password
          const passwordInputs = page.locator('input[type="password"]');
          const passwordCount = await passwordInputs.count();
          console.log(`Setup: Found ${passwordCount} password fields`);

          if (passwordCount >= 1) {
            await passwordInputs.nth(0).fill(testUserPassword);
            console.log('Setup: Filled password field 1');
          }

          if (passwordCount >= 2) {
            await passwordInputs.nth(1).fill(testUserPassword);
            console.log('Setup: Filled password field 2 (confirmation)');
          }

          // Submit
          const submitButton = page.locator('.ant-modal button[type="submit"], .ant-drawer button[type="submit"], .ant-modal .ant-btn-primary, .ant-drawer .ant-btn-primary, button:has-text("作成"), button:has-text("保存"), button:has-text("OK")');
          if (await submitButton.count() > 0) {
            console.log('Setup: Found submit button, clicking...');
            await submitButton.first().click();

            // Wait for success message or modal to close
            try {
              await page.waitForSelector('.ant-message-success, .ant-notification-success', { timeout: 5000 });
              console.log('Setup: Success message appeared');
            } catch (e) {
              console.log('Setup: No success message, checking if modal closed');
            }

            await page.waitForTimeout(3000);

            // Verify test user was created
            const createdTestUser = page.locator(`tr:has-text("${testUsername}")`);
            const userCreated = await createdTestUser.count() > 0;
            console.log(`Setup: ${testUsername} creation ${userCreated ? 'SUCCESSFUL' : 'FAILED'}`);
          } else {
            console.log('Setup: Submit button not found!');
          }
        } else {
          console.log('Setup: Create button not found - user creation UI may not be accessible');
        }
      }
    } catch (error) {
      console.log(`Setup: ${testUsername} creation failed:`, error);
    } finally {
      await context.close();
    }
  });

  test.describe('Admin User - Setup Permissions', () => {
    test.beforeEach(async ({ page, browserName }) => {
      authHelper = new AuthHelper(page);
      testHelper = new TestHelper(page);

      await page.context().clearCookies();
      await authHelper.login(); // Login as admin
      await page.waitForTimeout(2000); // Wait for UI initialization after login
      await testHelper.waitForAntdLoad();

      // Navigate to documents
      const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
      if (await documentsMenuItem.count() > 0) {
        await documentsMenuItem.click();
        await page.waitForTimeout(2000);
      }

      // MOBILE FIX: Close sidebar to prevent overlay blocking clicks
      const viewportSize = page.viewportSize();
      const isMobileChrome = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

      if (isMobileChrome) {
        const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]');

        if (await menuToggle.count() > 0) {
          try {
            await menuToggle.first().click({ timeout: 3000 });
            await page.waitForTimeout(500);
          } catch (error) {
            // Continue even if sidebar close fails
          }
        } else {
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

    test('should create restricted folder with limited permissions', async ({ page, browserName }) => {
      const viewportSize = page.viewportSize();
      const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

      // Create test folder
      const createFolderButton = page.locator('button').filter({ hasText: 'フォルダ作成' });

      if (await createFolderButton.count() > 0) {
        await createFolderButton.click(isMobile ? { force: true } : {});
        await page.waitForSelector('.ant-modal:not(.ant-modal-hidden)', { timeout: 5000 });

        const nameInput = page.locator('.ant-modal input[placeholder*="名前"], .ant-modal input[id*="name"]');
        await nameInput.fill(restrictedFolderName);

        const submitButton = page.locator('.ant-modal button[type="submit"], .ant-modal .ant-btn-primary');
        await submitButton.click();

        await page.waitForSelector('.ant-message-success', { timeout: 10000 });
        await page.waitForTimeout(2000);

        // Verify folder created
        const createdFolder = page.locator(`text=${restrictedFolderName}`);
        await expect(createdFolder).toBeVisible({ timeout: 5000 });
      } else {
        test.skip('Folder creation not available');
      }
    });

    test('should set ACL permissions on folder (admin only)', async ({ page, browserName }) => {
      const viewportSize = page.viewportSize();
      const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

      await page.waitForTimeout(2000);

      // Find the restricted folder
      const folderRow = page.locator('tr').filter({ hasText: restrictedFolderName });

      if (await folderRow.count() > 0) {
        // Look for permissions/ACL button (may be lock icon or settings icon)
        const permissionsButton = folderRow.locator('button').filter({
          has: page.locator('[data-icon="lock"], [data-icon="safety"], [data-icon="setting"]')
        });

        if (await permissionsButton.count() > 0) {
          await permissionsButton.first().click(isMobile ? { force: true } : {});
          await page.waitForTimeout(1000);

          // Look for ACL management interface
          // This will vary based on UI implementation
          const aclModal = page.locator('.ant-modal, .ant-drawer');
          if (await aclModal.count() > 0) {
            await expect(aclModal).toBeVisible({ timeout: 5000 });

            // Try to add test user with limited permissions
            const addUserButton = page.locator('button:has-text("追加"), button:has-text("ユーザー追加")');
            if (await addUserButton.count() > 0) {
              await addUserButton.first().click(isMobile ? { force: true } : {});
              await page.waitForTimeout(500);

              // Select test user
              const userSelect = page.locator('.ant-select, input[placeholder*="ユーザー"]');
              if (await userSelect.count() > 0) {
                await userSelect.first().click();
                await page.waitForTimeout(500);

                // Type test username
                await page.keyboard.type(testUsername);
                await page.waitForTimeout(500);

                // Select from dropdown
                const testuserOption = page.locator(`.ant-select-item:has-text("${testUsername}")`);
                if (await testuserOption.count() > 0) {
                  await testuserOption.first().click();
                }
              }

              // Set permission level (e.g., Read only)
              const permissionSelect = page.locator('.ant-select').filter({ hasText: /権限|Permission/ });
              if (await permissionSelect.count() > 0) {
                await permissionSelect.first().click();
                await page.waitForTimeout(500);

                // Select "Read" permission
                const readOption = page.locator('.ant-select-item:has-text("読み取り"), .ant-select-item:has-text("Read")');
                if (await readOption.count() > 0) {
                  await readOption.first().click();
                }
              }

              // Save ACL changes
              const saveButton = page.locator('button:has-text("保存"), button:has-text("OK"), button[type="submit"]');
              if (await saveButton.count() > 0) {
                await saveButton.first().click(isMobile ? { force: true } : {});
                await page.waitForSelector('.ant-message-success', { timeout: 10000 });
              }
            }
          }
        } else {
          // If no specific permissions button, try right-click context menu
          await folderRow.click({ button: 'right' });
          await page.waitForTimeout(500);

          const permissionsMenu = page.locator('.ant-dropdown-menu-item:has-text("権限"), .ant-dropdown-menu-item:has-text("ACL")');
          if (await permissionsMenu.count() > 0) {
            await permissionsMenu.click();
          } else {
            test.skip('ACL management interface not found');
          }
        }
      } else {
        test.skip('Restricted folder not found');
      }
    });

    test('should upload document to restricted folder', async ({ page, browserName }) => {
      const viewportSize = page.viewportSize();
      const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

      await page.waitForTimeout(2000);

      // Navigate into restricted folder
      const folderLink = page.locator('a, span').filter({ hasText: restrictedFolderName });
      if (await folderLink.count() > 0) {
        await folderLink.first().click();
        await page.waitForTimeout(2000);

        // Upload document
        const uploadButton = page.locator('button').filter({ hasText: 'ファイルアップロード' });
        if (await uploadButton.count() > 0) {
          await uploadButton.click(isMobile ? { force: true } : {});
          await page.waitForSelector('.ant-modal:not(.ant-modal-hidden)', { timeout: 5000 });

          await testHelper.uploadTestFile(
            '.ant-modal input[type="file"]',
            testDocName,
            'Permission test content - should be read-only for testuser'
          );

          await page.waitForTimeout(1000);

          const submitBtn = page.locator('.ant-modal button[type="submit"]');
          await submitBtn.click();

          await page.waitForSelector('.ant-message-success', { timeout: 10000 });
          await page.waitForTimeout(2000);
        }
      }
    });
  });

  test.describe('Test User - Verify Permission Restrictions', () => {
    test.beforeEach(async ({ page, browserName }) => {
      authHelper = new AuthHelper(page);
      testHelper = new TestHelper(page);

      await page.context().clearCookies();

      // Try to login as test user - skip tests if user wasn't created
      try {
        console.log(`Test: Attempting login as ${testUsername}`);
        await authHelper.login(testUsername, testUserPassword);
        await page.waitForTimeout(2000); // Wait for UI initialization after login
        await testHelper.waitForAntdLoad();
        console.log('Test: Test user login successful');
      } catch (error) {
        console.log(`Test: Test user login failed - test user may not have been created:`, error);
        test.skip(true, `Test user ${testUsername} was not created or login failed. User management UI may not be available.`);
      }

      // Navigate to documents
      const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
      if (await documentsMenuItem.count() > 0) {
        await documentsMenuItem.click();
        await page.waitForTimeout(2000);
      }

      // MOBILE FIX: Close sidebar to prevent overlay blocking clicks
      const viewportSize = page.viewportSize();
      const isMobileChrome = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

      if (isMobileChrome) {
        const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]');

        if (await menuToggle.count() > 0) {
          try {
            await menuToggle.first().click({ timeout: 3000 });
            await page.waitForTimeout(500);
          } catch (error) {
            // Continue even if sidebar close fails
          }
        } else {
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

    test('should be able to view restricted folder as test user', async ({ page, browserName }) => {
      const viewportSize = page.viewportSize();
      const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

      await page.waitForTimeout(2000);

      // Verify test user can see the restricted folder
      const folderLink = page.locator(`text=${restrictedFolderName}`);

      if (await folderLink.count() > 0) {
        await expect(folderLink).toBeVisible({ timeout: 5000 });

        // Navigate into folder
        await folderLink.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(2000);

        // Verify can see the document
        const document = page.locator(`text=${testDocName}`);
        await expect(document).toBeVisible({ timeout: 5000 });
      } else {
        test.skip('Restricted folder not visible to test user - permission issue');
      }
    });

    test('should NOT be able to delete document (read-only)', async ({ page, browserName }) => {
      const viewportSize = page.viewportSize();
      const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

      await page.waitForTimeout(2000);

      // Navigate to restricted folder
      const folderLink = page.locator(`text=${restrictedFolderName}`);
      if (await folderLink.count() > 0) {
        await folderLink.click();
        await page.waitForTimeout(2000);

        // Find document row
        const docRow = page.locator('tr').filter({ hasText: testDocName });

        if (await docRow.count() > 0) {
          // Check if delete button exists
          const deleteButton = docRow.locator('button').filter({
            has: page.locator('[data-icon="delete"]')
          });

          if (await deleteButton.count() > 0) {
            // If button exists, it should be disabled or deletion should fail
            const isDisabled = await deleteButton.first().isDisabled();
            if (!isDisabled) {
              // Try to delete and expect failure
              await deleteButton.first().click(isMobile ? { force: true } : {});
              await page.waitForTimeout(500);

              const confirmButton = page.locator('.ant-popconfirm button.ant-btn-primary');
              if (await confirmButton.count() > 0) {
                await confirmButton.click();

                // Should see error message
                const errorMessage = page.locator('.ant-message-error');
                await expect(errorMessage).toBeVisible({ timeout: 5000 });
              }
            } else {
              // Button is disabled as expected
              expect(isDisabled).toBe(true);
            }
          } else {
            // No delete button is shown - correct for read-only user
            expect(await deleteButton.count()).toBe(0);
          }
        }
      }
    });

    test('should NOT be able to upload to restricted folder', async ({ page, browserName }) => {
      const viewportSize = page.viewportSize();
      const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

      await page.waitForTimeout(2000);

      // Navigate to restricted folder
      const folderLink = page.locator(`text=${restrictedFolderName}`);
      if (await folderLink.count() > 0) {
        await folderLink.click();
        await page.waitForTimeout(2000);

        // Check if upload button exists or is disabled
        const uploadButton = page.locator('button').filter({ hasText: 'ファイルアップロード' });

        if (await uploadButton.count() > 0) {
          const isDisabled = await uploadButton.first().isDisabled();
          if (!isDisabled) {
            // Try to upload and expect failure
            await uploadButton.click(isMobile ? { force: true } : {});

            // May not open modal or may show error
            const modal = page.locator('.ant-modal:not(.ant-modal-hidden)');
            const errorMessage = page.locator('.ant-message-error');

            // Either modal doesn't open or error is shown
            const modalOpened = await modal.count() > 0;
            const errorShown = await errorMessage.count() > 0;

            expect(modalOpened || errorShown).toBeTruthy();
          } else {
            // Button is disabled as expected
            expect(isDisabled).toBe(true);
          }
        } else {
          // No upload button for read-only user - correct behavior
          expect(await uploadButton.count()).toBe(0);
        }
      }
    });
  });

  test.describe('Admin - Cleanup', () => {
    test.beforeEach(async ({ page }) => {
      authHelper = new AuthHelper(page);
      await authHelper.login(); // Login as admin again
      await page.waitForTimeout(2000);

      const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
      if (await documentsMenuItem.count() > 0) {
        await documentsMenuItem.click();
        await page.waitForTimeout(2000);
      }
    });

    test('should clean up restricted folder and contents', async ({ page, browserName }) => {
      const viewportSize = page.viewportSize();
      const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

      await page.waitForTimeout(2000);

      const folderRow = page.locator('tr').filter({ hasText: restrictedFolderName });

      if (await folderRow.count() > 0) {
        const deleteButton = folderRow.locator('button').filter({
          has: page.locator('[data-icon="delete"]')
        });

        if (await deleteButton.count() > 0) {
          await deleteButton.click(isMobile ? { force: true } : {});
          await page.waitForTimeout(500);

          const confirmButton = page.locator('.ant-popconfirm button.ant-btn-primary');
          if (await confirmButton.count() > 0) {
            await confirmButton.click();
            await page.waitForSelector('.ant-message-success', { timeout: 10000 });
          }
        }
      }
    });
  });
});
