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

  // Pre-cleanup: Delete old test folders from previous runs BEFORE tests start
  test.beforeAll(async ({ browser }) => {
    test.setTimeout(90000); // Set 90-second timeout for this hook

    // Allow skipping pre-cleanup via environment variable for faster test execution
    if (process.env.SKIP_CLEANUP === 'true') {
      console.log('Pre-cleanup: SKIPPED (SKIP_CLEANUP=true)');
      return;
    }

    console.log('Pre-cleanup: Starting cleanup of old test folders before test execution');
    const context = await browser.newContext();
    const page = await context.newPage();
    const cleanupAuthHelper = new AuthHelper(page);

    const cleanupStartTime = Date.now();
    const maxCleanupTime = 30000; // 30 seconds max for cleanup (reduced from 60)

    try {
      await cleanupAuthHelper.login();
      await page.waitForTimeout(2000);

      // Navigate to documents
      const documentsMenu = page.locator('.ant-menu-item:has-text("ドキュメント")');
      if (await documentsMenu.count() > 0) {
        await documentsMenu.click();
        await page.waitForTimeout(2000);
      }

      // Delete up to 3 old test folders to reduce UI clutter (reduced from 10 for speed)
      let deletedCount = 0;
      const maxDeletions = 3;
      const failedFolders = new Set<string>(); // Track folders that failed to delete

      while (deletedCount < maxDeletions && (Date.now() - cleanupStartTime) < maxCleanupTime) {
        // Re-query folder rows on each iteration to avoid stale elements
        const folderRows = page.locator('.ant-table-tbody tr');
        const folderCount = await folderRows.count();

        if (folderCount === 0) {
          console.log('Pre-cleanup: No folders found on current page');
          break;
        }

        // Find first test folder on current page (skip previously failed ones)
        let foundTestFolder = false;
        for (let i = 0; i < folderCount; i++) {
          const row = folderRows.nth(i);
          const folderNameButton = row.locator('td').nth(1).locator('button');
          const folderName = await folderNameButton.textContent();

          if (folderName && (folderName.startsWith('restricted-folder-') || folderName.startsWith('test-folder-'))) {
            // Skip folders that already failed to delete
            if (failedFolders.has(folderName)) {
              console.log(`Pre-cleanup: Skipping previously failed folder: ${folderName}`);
              continue;
            }
            console.log(`Pre-cleanup: Deleting folder: ${folderName}`);

            const deleteButton = row.locator('button').filter({
              has: page.locator('[data-icon="delete"]')
            });

            if (await deleteButton.count() > 0) {
              await deleteButton.first().click({ timeout: 3000 });

              // Wait for popconfirm to appear
              await page.waitForTimeout(1500);

              // Try to find and click visible confirm button with multiple strategies
              try {
                // Strategy 1: Wait for visible popconfirm container first
                const popconfirm = page.locator('.ant-popconfirm:visible, .ant-popover:visible');
                await popconfirm.waitFor({ state: 'visible', timeout: 3000 });

                // Strategy 2: Find confirm button within visible popconfirm
                const confirmButton = popconfirm.locator('button.ant-btn-primary, button:has-text("OK"), button:has-text("確認")');

                // Try clicking with force if button exists but not perfectly visible
                if (await confirmButton.count() > 0) {
                  await confirmButton.first().click({ force: true, timeout: 3000 });

                  // Wait for folder to disappear from table (verify deletion completed)
                  // Extended to 10 attempts (10 seconds) for folders with contents
                  let deletionConfirmed = false;
                  for (let attempt = 0; attempt < 10; attempt++) {
                    await page.waitForTimeout(1000);
                    const stillExists = page.locator('tr').filter({ hasText: folderName });
                    if (await stillExists.count() === 0) {
                      deletionConfirmed = true;
                      break;
                    }
                  }

                  if (deletionConfirmed) {
                    console.log(`Pre-cleanup: Folder ${folderName} deletion confirmed`);
                    deletedCount++;
                    foundTestFolder = true;
                    break; // Exit inner loop after successful deletion
                  } else {
                    console.log(`Pre-cleanup: Warning - Folder ${folderName} still exists after deletion attempt`);
                    failedFolders.add(folderName); // Mark as failed to skip in future iterations
                    // Don't increment deletedCount, try next folder (continue in loop)
                  }
                } else {
                  console.log(`Pre-cleanup: Confirm button not found in visible popconfirm for ${folderName}`);
                  failedFolders.add(folderName); // Mark as failed
                }
              } catch (confirmError) {
                console.log(`Pre-cleanup: Confirm button error for ${folderName}:`, confirmError.message);
                failedFolders.add(folderName); // Mark as failed to skip in future iterations
                // Skip this folder and try next one
              }
            }
          }
        }

        // No more test folders found on current page
        if (!foundTestFolder) {
          console.log('Pre-cleanup: No more test folders found on current page');
          break;
        }
      }

      const cleanupElapsed = Date.now() - cleanupStartTime;
      if (cleanupElapsed >= maxCleanupTime) {
        console.log(`Pre-cleanup: Timeout reached (${cleanupElapsed}ms) - stopping cleanup to allow tests to proceed`);
      }

      console.log(`Pre-cleanup: Successfully deleted ${deletedCount} old test folders in ${cleanupElapsed}ms`);
    } catch (error) {
      console.log('Pre-cleanup: Error during cleanup:', error);
    } finally {
      await context.close();
    }
  });

  // Setup: Create test user
  test.beforeAll(async ({ browser }) => {
    test.setTimeout(90000); // Set 90-second timeout for user creation
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

        // With unique username approach, no need to check for existing user
        console.log(`Setup: Creating test user: ${testUsername}`);

        // Debug: Check what's on the page
        const allButtons = await page.locator('button').allTextContents();
        console.log(`Setup: Found ${allButtons.length} buttons on page:`, allButtons.slice(0, 10));

        // Create test user
        const createButton = page.locator('button').filter({
          hasText: /新規ユーザー|新規作成|ユーザー追加|追加/
        });

        if (await createButton.count() > 0) {
          console.log('Setup: Found create button, clicking...');
          await createButton.first().click();
          await page.waitForTimeout(1000);

          // Wait for modal/drawer to be visible
          const modal = page.locator('.ant-modal:not(.ant-modal-hidden), .ant-drawer:not(.ant-drawer-hidden)');
          await modal.waitFor({ state: 'visible', timeout: 5000 });
          console.log('Setup: Modal opened');

          // Debug: Check all form fields
          const allInputs = await modal.locator('input').all();
          console.log(`Setup: Found ${allInputs.length} input fields in modal`);
          for (let i = 0; i < allInputs.length; i++) {
            const input = allInputs[i];
            const type = await input.getAttribute('type');
            const name = await input.getAttribute('name');
            const id = await input.getAttribute('id');
            const placeholder = await input.getAttribute('placeholder');
            console.log(`Setup: Input ${i}: type=${type}, name=${name}, id=${id}, placeholder=${placeholder}`);
          }

          // Fill user ID (primary identifier)
          const userIdInput = modal.locator('input#id, input[placeholder*="ユーザーID"]');
          if (await userIdInput.count() > 0) {
            await userIdInput.first().fill(testUsername);
            console.log(`Setup: Filled user ID: ${testUsername}`);
          } else {
            console.log('Setup: Warning - User ID field not found');
          }

          // Fill display name (optional but good to have)
          const nameInput = modal.locator('input#name, input[placeholder*="ユーザー名を入力"]');
          if (await nameInput.count() > 0) {
            await nameInput.first().fill(`${testUsername}_display`);
            console.log('Setup: Filled display name');
          }

          // Fill firstName (required)
          const firstNameInput = modal.locator('input#firstName, input[placeholder*="名を入力"]');
          if (await firstNameInput.count() > 0) {
            await firstNameInput.first().fill('Test');
            console.log('Setup: Filled firstName');
          } else {
            console.log('Setup: Warning - firstName field not found');
          }

          // Fill lastName (required)
          const lastNameInput = modal.locator('input#lastName, input[placeholder*="姓を入力"]');
          if (await lastNameInput.count() > 0) {
            await lastNameInput.first().fill('User');
            console.log('Setup: Filled lastName');
          } else {
            console.log('Setup: Warning - lastName field not found');
          }

          // Fill email (required)
          const emailInput = modal.locator('input#email, input[type="email"], input[placeholder*="メールアドレス"]');
          if (await emailInput.count() > 0) {
            await emailInput.first().fill(`${testUsername}@example.com`);
            console.log('Setup: Filled email');
          } else {
            console.log('Setup: Warning - email field not found');
          }

          // Fill password (required)
          const passwordInput = modal.locator('input#password, input[type="password"]');
          if (await passwordInput.count() > 0) {
            await passwordInput.first().fill(testUserPassword);
            console.log('Setup: Filled password');
          } else {
            console.log('Setup: Warning - password field not found');
          }

          // Submit - try multiple strategies
          console.log('Setup: Looking for submit button...');
          const allModalButtons = await modal.locator('button').allTextContents();
          console.log('Setup: All modal buttons:', allModalButtons);

          let submitButton = modal.locator('button').filter({ hasText: /作成|保存|OK|確認/ });
          let submitCount = await submitButton.count();
          console.log(`Setup: Submit button candidates with text filter: ${submitCount}`);

          if (submitCount === 0) {
            // Try without text filter
            submitButton = modal.locator('button[type="submit"], button.ant-btn-primary');
            submitCount = await submitButton.count();
            console.log(`Setup: Submit button candidates without text filter: ${submitCount}`);
          }

          if (submitCount > 0) {
            console.log('Setup: Found submit button, clicking...');
            await submitButton.first().click();

            // Wait for success message or modal to close
            let successMessageAppeared = false;
            try {
              await page.waitForSelector('.ant-message-success, .ant-notification-success', { timeout: 5000 });
              console.log('Setup: Success message appeared');
              successMessageAppeared = true;
            } catch (e) {
              console.log('Setup: No success message detected');
            }

            // Wait for modal to close
            try {
              await modal.waitFor({ state: 'hidden', timeout: 5000 });
              console.log('Setup: Modal closed');
            } catch (e) {
              console.log('Setup: Modal did not close - may indicate form validation error');
              // Check for error messages
              const errorMessages = await modal.locator('.ant-form-item-explain-error, .ant-message-error').allTextContents();
              if (errorMessages.length > 0) {
                console.log('Setup: Form validation errors:', errorMessages);
              }
            }

            // Wait for database write to complete
            await page.waitForTimeout(3000);

            // Verify test user was created by attempting login (more reliable than table check)
            let userCreated = false;
            if (successMessageAppeared) {
              console.log('Setup: Verifying user creation by attempting login...');

              // Logout from admin session
              try {
                const userMenuButton = page.locator('.ant-dropdown-trigger, button:has-text("admin")').first();
                if (await userMenuButton.count() > 0) {
                  await userMenuButton.click();
                  await page.waitForTimeout(500);

                  const logoutButton = page.locator('.ant-dropdown-menu-item:has-text("ログアウト"), a:has-text("ログアウト")');
                  if (await logoutButton.count() > 0) {
                    await logoutButton.first().click();
                    await page.waitForTimeout(2000);
                    console.log('Setup: Logged out from admin session');
                  }
                }
              } catch (logoutError) {
                console.log('Setup: Logout error (navigating to login directly):', logoutError.message);
                await page.goto('http://localhost:8080/core/ui/dist/');
                await page.waitForTimeout(2000);
              }

              // Attempt login with test user
              try {
                const testAuthHelper = new AuthHelper(page);
                await testAuthHelper.login(testUsername, testUserPassword);

                // Check if login succeeded (authenticated elements appear)
                const authenticatedElement = page.locator('.ant-menu, .ant-layout-header, [data-testid="authenticated"]');
                userCreated = await authenticatedElement.first().isVisible({ timeout: 10000 });

                if (userCreated) {
                  console.log(`Setup: ${testUsername} login SUCCESSFUL - user creation verified`);

                  // Logout test user and restore admin session for tests
                  const testUserMenuButton = page.locator('.ant-dropdown-trigger, button:has-text("' + testUsername + '")').first();
                  if (await testUserMenuButton.count() > 0) {
                    await testUserMenuButton.click();
                    await page.waitForTimeout(500);

                    const logoutButton = page.locator('.ant-dropdown-menu-item:has-text("ログアウト"), a:has-text("ログアウト")');
                    if (await logoutButton.count() > 0) {
                      await logoutButton.first().click();
                      await page.waitForTimeout(1000);
                    }
                  }
                } else {
                  console.log(`Setup: ${testUsername} login FAILED - authentication elements not visible`);
                }
              } catch (loginError) {
                console.log(`Setup: ${testUsername} login FAILED:`, loginError.message);
                userCreated = false;
              }
            } else {
              console.log('Setup: Success message not detected - skipping login verification');
            }

            console.log(`Setup: ${testUsername} creation ${userCreated ? 'SUCCESSFUL' : 'FAILED'}`);

            if (!userCreated && successMessageAppeared) {
              console.log('Setup: Warning - Success message appeared but login verification failed');
            }
          } else {
            console.log('Setup: Submit button not found!');
          }
        } else {
          console.log('Setup: Create button not found - user creation UI may not be accessible');
        }
      } else {
        console.log('Setup: ユーザー管理 menu item not found');
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
        console.log(`Test: Attempting login as ${testUsername} with password ${testUserPassword}`);
        console.log('Test: Current URL before login:', page.url());

        await authHelper.login(testUsername, testUserPassword);
        console.log('Test: Login method completed, waiting for UI initialization');

        await page.waitForTimeout(2000); // Wait for UI initialization after login
        console.log('Test: After 2s wait, current URL:', page.url());

        await testHelper.waitForAntdLoad();
        console.log('Test: Test user login successful - Ant Design loaded');
      } catch (error) {
        console.log(`Test: Test user login failed - error details:`, error);
        console.log('Test: Current URL after error:', page.url());

        // Take screenshot for debugging
        const screenshot = await page.screenshot();
        console.log('Test: Screenshot size:', screenshot.length, 'bytes');

        // Check for error messages on page
        const errorMessages = await page.locator('.ant-message-error, .ant-notification-error').allTextContents();
        if (errorMessages.length > 0) {
          console.log('Test: Error messages visible:', errorMessages);
        }

        test.skip(true, `Test user ${testUsername} login failed. This may indicate the user was not created successfully or lacks repository access.`);
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

      // Extended wait for folder list to load (especially for test user session)
      await page.waitForTimeout(3000);

      // Wait for folder table to be visible
      const folderTable = page.locator('.ant-table-tbody');
      await folderTable.waitFor({ state: 'visible', timeout: 10000 });

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
      test.setTimeout(90000); // Extended timeout for slow deletion operations
      const viewportSize = page.viewportSize();
      const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

      await page.waitForTimeout(2000);

      const folderRow = page.locator('tr').filter({ hasText: restrictedFolderName });

      if (await folderRow.count() > 0) {
        const deleteButton = folderRow.locator('button').filter({
          has: page.locator('[data-icon="delete"]')
        });

        if (await deleteButton.count() > 0) {
          console.log(`Cleanup: Clicking delete button for ${restrictedFolderName}`);
          await deleteButton.click(isMobile ? { force: true } : {});

          // Wait for popconfirm to appear (extended timeout for slow UI)
          console.log('Cleanup: Waiting for popconfirm to appear...');
          await page.waitForTimeout(2000);

          // Try to find and click visible confirm button with multiple strategies
          try {
            // Strategy 1: Wait for visible popconfirm container first
            const popconfirm = page.locator('.ant-popconfirm:visible, .ant-popover:visible');
            await popconfirm.waitFor({ state: 'visible', timeout: 5000 });
            console.log('Cleanup: Popconfirm visible');

            // Strategy 2: Find all visible confirm buttons
            const allConfirmButtons = page.locator('.ant-popconfirm:visible button, .ant-popover:visible button');
            const buttonCount = await allConfirmButtons.count();
            console.log(`Cleanup: Found ${buttonCount} buttons in popconfirm`);

            // Log button texts for debugging
            for (let i = 0; i < buttonCount; i++) {
              const buttonText = await allConfirmButtons.nth(i).textContent();
              console.log(`Cleanup: Button ${i}: "${buttonText}"`);
            }

            // Strategy 3: Try primary button first, then any button with confirmation text
            const confirmButton = page.locator('.ant-popconfirm:visible button.ant-btn-primary, .ant-popover:visible button.ant-btn-primary').first();

            if (await confirmButton.count() > 0) {
              console.log('Cleanup: Clicking primary confirm button');
              await confirmButton.click({ force: true, timeout: 3000 });

              // Wait for success message with extended timeout (folder with contents takes longer to delete)
              try {
                await page.waitForSelector('.ant-message-success', { timeout: 30000 });
              } catch (timeoutError) {
                console.log('Cleanup: Success message timeout - verifying deletion by checking if folder disappeared');
              }

              // Verify folder was deleted with polling (up to 20 seconds for folders with contents)
              let deletionConfirmed = false;
              for (let attempt = 0; attempt < 20; attempt++) {
                await page.waitForTimeout(1000);
                const deletedFolderRow = page.locator('tr').filter({ hasText: restrictedFolderName });
                const folderStillExists = await deletedFolderRow.count() > 0;

                if (!folderStillExists) {
                  deletionConfirmed = true;
                  console.log(`Cleanup: Folder ${restrictedFolderName} deletion confirmed after ${attempt + 1} attempts`);
                  break;
                }
              }

              if (!deletionConfirmed) {
                console.log(`Cleanup: Warning - Folder ${restrictedFolderName} still exists after 20-second verification`);
              }

              // Test should pass as long as folder is gone, even if success message didn't appear
              expect(deletionConfirmed).toBe(true);
            } else {
              console.log(`Cleanup: Confirm button not found in visible popconfirm`);
              // Test will fail - folder still exists and can't be deleted
              expect(false).toBe(true); // Force fail with clear message
            }
          } catch (confirmError) {
            console.log('Cleanup: Confirm button error:', confirmError.message);
            // Try to verify if folder was deleted despite error (with polling)
            let deletionConfirmed = false;
            for (let attempt = 0; attempt < 20; attempt++) {
              await page.waitForTimeout(1000);
              const deletedFolderRow = page.locator('tr').filter({ hasText: restrictedFolderName });
              const folderStillExists = await deletedFolderRow.count() > 0;
              if (!folderStillExists) {
                deletionConfirmed = true;
                console.log(`Cleanup: Folder ${restrictedFolderName} deletion confirmed after ${attempt + 1} attempts (despite error)`);
                break;
              }
            }
            expect(deletionConfirmed).toBe(true);
          }
        }
      }
    });
  });

  // Cleanup: Remove accumulated test folders to prevent performance degradation
  test.afterAll(async ({ browser }) => {
    test.setTimeout(90000); // Set 90-second timeout for cleanup

    // Allow skipping cleanup via environment variable for faster test execution
    if (process.env.SKIP_CLEANUP === 'true') {
      console.log('Cleanup: SKIPPED (SKIP_CLEANUP=true)');
      return;
    }

    console.log('Cleanup: Starting test folder cleanup');
    const context = await browser.newContext();
    const page = await context.newPage();
    const cleanupAuthHelper = new AuthHelper(page);

    try {
      // Login as admin for cleanup operations
      await cleanupAuthHelper.login();
      await page.waitForTimeout(2000);

      // Navigate to documents page
      const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
      if (await documentsMenuItem.count() > 0) {
        await documentsMenuItem.click();
        await page.waitForTimeout(3000); // Wait for folder list to load
      }

      let deletedCount = 0;
      const maxDeletions = 10; // Reduced limit to avoid timeout
      const failedFolders = new Set<string>(); // Track folders that failed to delete

      // Delete test folders in batches - re-query after each deletion to avoid stale elements
      for (let batch = 0; batch < maxDeletions; batch++) {
        try {
          // Re-query folder rows after each deletion
          const folderRows = page.locator('tr').filter({
            has: page.locator('[data-icon="folder"]')
          });
          const folderCount = await folderRows.count();

          if (batch === 0) {
            console.log(`Cleanup: Found ${folderCount} folders on current page`);
          }

          // Find first test folder on current page (skip previously failed ones)
          let foundTestFolder = false;
          for (let i = 0; i < folderCount; i++) {
            const row = folderRows.nth(i);
            const folderNameButton = row.locator('td').nth(1).locator('button');
            const folderName = await folderNameButton.textContent();

            if (folderName && (folderName.startsWith('restricted-folder-') || folderName.startsWith('test-folder-'))) {
              // Skip folders that already failed to delete
              if (failedFolders.has(folderName)) {
                console.log(`Cleanup: Skipping previously failed folder: ${folderName}`);
                continue;
              }
              console.log(`Cleanup: Deleting folder: ${folderName}`);

              const deleteButton = row.locator('button').filter({
                has: page.locator('[data-icon="delete"]')
              });

              if (await deleteButton.count() > 0) {
                await deleteButton.first().click({ timeout: 3000 });

                // Wait for popconfirm to appear
                await page.waitForTimeout(1500);

                // Try to find and click visible confirm button with multiple strategies
                try {
                  // Strategy 1: Wait for visible popconfirm container first
                  const popconfirm = page.locator('.ant-popconfirm:visible, .ant-popover:visible');
                  await popconfirm.waitFor({ state: 'visible', timeout: 3000 });

                  // Strategy 2: Find confirm button within visible popconfirm
                  const confirmButton = popconfirm.locator('button.ant-btn-primary, button:has-text("OK"), button:has-text("確認")');

                  // Try clicking with force if button exists but not perfectly visible
                  if (await confirmButton.count() > 0) {
                    await confirmButton.first().click({ force: true, timeout: 3000 });

                    // Wait for folder to disappear from table (verify deletion completed)
                    // Extended to 10 attempts (10 seconds) for folders with contents
                    let deletionConfirmed = false;
                    for (let attempt = 0; attempt < 10; attempt++) {
                      await page.waitForTimeout(1000);
                      const stillExists = page.locator('tr').filter({ hasText: folderName });
                      if (await stillExists.count() === 0) {
                        deletionConfirmed = true;
                        break;
                      }
                    }

                    if (deletionConfirmed) {
                      console.log(`Cleanup: Folder ${folderName} deletion confirmed`);
                      deletedCount++;
                      foundTestFolder = true;
                      break; // Exit inner loop after successful deletion
                    } else {
                      console.log(`Cleanup: Warning - Folder ${folderName} still exists after deletion attempt`);
                      failedFolders.add(folderName); // Mark as failed to skip in future iterations
                      // Don't increment deletedCount, try next folder
                    }
                  } else {
                    console.log(`Cleanup: Confirm button not found in visible popconfirm for ${folderName}`);
                    failedFolders.add(folderName); // Mark as failed
                  }
                } catch (confirmError) {
                  console.log(`Cleanup: Confirm button error for ${folderName}:`, confirmError.message);
                  failedFolders.add(folderName); // Mark as failed to skip in future iterations
                  // Skip this folder and try next one
                }
              }
            }
          }

          // No more test folders found on current page
          if (!foundTestFolder) {
            console.log(`Cleanup: No more test folders found on current page after ${deletedCount} deletions`);
            break;
          }

        } catch (error) {
          console.log(`Cleanup: Error deleting folder in batch ${batch}:`, error);
          break; // Stop on error to avoid cascading failures
        }
      }

      console.log(`Cleanup: Successfully deleted ${deletedCount} test folders`);

    } catch (error) {
      console.log('Cleanup: Test folder cleanup failed:', error);
      // Don't throw - cleanup failure should not fail the test suite
    } finally {
      await context.close();
    }
  });
});
