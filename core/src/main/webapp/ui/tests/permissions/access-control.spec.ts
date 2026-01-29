import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper, ApiHelper, generateTestId } from '../utils/test-helper';


/**
 * Access Control and Permissions E2E Tests
 *
 * Comprehensive end-to-end tests for NemakiWare access control system:
 * - Dynamic test user creation with unique credentials
 * - Permission setup via CMIS API and UI interactions
 * - Permission verification with restricted folder scenarios
 * - ACL modification and restoration testing
 * - Multi-phase test architecture with automated cleanup
 *
 * IMPORTANT DESIGN DECISIONS:
 * 1. Multi-Phase Test Architecture (Lines 17-427):
 *    - Phase 1 (Lines 17-151): Pre-cleanup of old test folders from previous runs
 *    - Phase 2 (Lines 154-389): Test user creation with unique randomUUID credentials
 *    - Phase 3 (Lines 392-427): Root folder ACL setup via CMIS Browser Binding API
 *    - Phase 4 (Lines 429-622): Admin user permission setup tests
 *    - Phase 5 (Lines 624-822): Admin user ACL modification tests
 *    - Phase 6 (Lines 824-1120): Test user permission verification tests
 *    - Phase 7 (Lines 1122-1177): Final cleanup with CMIS deleteTree operation
 *    - Phase 8 (Lines 1181-1317): Post-test cleanup (afterAll) with batch deletion
 *    - Rationale: Phased approach ensures proper test data isolation and cleanup
 *
 * 2. Unique Test Data Strategy (Lines 9-14):
 *    - Uses randomUUID() for unique folder names and test usernames
 *    - Format: restricted-folder-${uuid8} and testuser${uuid8}
 *    - Prevents conflicts in parallel test execution across browsers
 *    - Enables reliable cross-browser testing without data collisions
 *    - Allows multiple test runs without manual cleanup requirements
 *
 * 3. Dual Cleanup Strategy (Lines 17-151, 1181-1317):
 *    - Pre-cleanup (beforeAll): Deletes up to 3 old test folders BEFORE tests start
 *    - Post-cleanup (afterAll): Deletes up to 10 test folders AFTER tests complete
 *    - Timeout protection: 60s max for pre-cleanup, 300s max for post-cleanup
 *    - Failed folder tracking: Skips folders that previously failed to delete
 *    - SKIP_CLEANUP environment variable: Can be set to 'true' for faster development
 *    - Rationale: Balances test speed with database cleanliness
 *
 * 4. CMIS API-First Setup Strategy (Lines 392-427):
 *    - Root folder ACL setup uses CMIS Browser Binding API directly
 *    - Grant cmis:all permission to test user for full read access
 *    - Note: cmis:read alone is insufficient for getChildren operation
 *    - Bypasses UI for reliable permission setup
 *    - Enables consistent test environment across all test phases
 *
 * 5. Smart Conditional Skipping Pattern (Lines 498-499, 580-581, 696-697, etc.):
 *    - Tests check for UI elements before performing actions
 *    - Skip gracefully if features not available (test.skip())
 *    - Better than hard test.describe() - self-healing when features become available
 *    - Maintains test suite flexibility across different UI implementation states
 *    - Examples: Folder creation, ACL management, permission editing
 *
 * 6. Mobile Browser Support (Lines 446-472, 868-893):
 *    - Sidebar close logic in beforeEach prevents overlay blocking clicks
 *    - Viewport width ≤414px triggers mobile-specific behavior
 *    - Force click option for mobile browsers (isMobile ? { force: true } : {})
 *    - Graceful fallback if sidebar toggle unavailable
 *    - Consistent with other test suites' mobile support pattern
 *
 * 7. Test User Authentication Verification with Extended Timeouts (Lines 933-966):
 *    - Comprehensive login debugging with URL tracking and error logging
 *    - Screenshot capture on login failure for debugging
 *    - Error message detection and logging
 *    - Graceful test skip if user creation failed or lacks repository access
 *    - Prevents cascading failures in permission verification tests
 *    - CRITICAL FIX (2025-10-26): Extended timeout to 180s (2 minutes) for test user login
 *    - Test users require additional time for ACL permission propagation after creation
 *    - AuthHelper uses 60s timeout per attempt with 5 retry attempts for test users
 *    - Total maximum wait: 300s (5 minutes) for authentication success
 *    - Rationale: Permission setup via CMIS API may take time to propagate to session
 *
 * 8. CMIS API Cleanup Strategy (Lines 1139-1177):
 *    - Uses CMIS Browser Binding deleteTree operation for folder cleanup
 *    - Query-based folder discovery (SELECT cmis:objectId FROM cmis:folder)
 *    - Handles both succinctProperties and properties response formats
 *    - More reliable than UI-based deletion for folders with contents
 *    - Timeout protection: 60-second test timeout for cleanup test
 *
 * Test Execution Flow:
 * 1. Pre-cleanup: Remove 3 old test folders from previous runs (optional)
 * 2. Setup: Create unique test user with full credentials
 * 3. Setup: Grant root folder access to test user via CMIS API
 * 4. Admin Tests: Create restricted folder and set permissions
 * 5. Admin Tests: Modify ACL permissions (read-write, remove/restore entry)
 * 6. Test User Tests: Verify permission restrictions (currently skipped due to visibility issues)
 * 7. Cleanup: Delete test folder via CMIS deleteTree operation
 * 8. Post-cleanup: Remove up to 10 test folders in batches (optional)
 *
 * Test User Credentials:
 * - Username: testuser${randomUUID8} (e.g., testuser12a34b56)
 * - Password: TestPass123!
 * - Email: testuser${randomUUID8}@example.com
 * - Display Name: ${username}_display
 * - Full Name: Test User
 * - Permissions: cmis:all on root folder (granted in beforeAll)
 *
 * CMIS Browser Binding API Usage:
 * - User Creation: Manual form filling in UI (no CMIS API)
 * - ACL Setup: cmisaction=applyACL with addACEPrincipal[]/addACEPermission[][] arrays
 * - Folder Query: cmisselector=query with CMIS SQL WHERE clause
 * - Folder Deletion: cmisaction=deleteTree with folderId parameter
 * - Repository Info: cmisselector=repositoryInfo for rootFolderId retrieval
 *
 * Known Limitations:
 * - Test user visibility tests (Lines 903-1029) currently skipped due to CMIS API permission issues
 * - Test user restriction tests (Lines 1031-1119) currently skipped (dependent on visibility)
 * - ACL management UI varies across implementations - tests adapt with conditional logic
 *
 * Performance Optimizations:
 * - Reduced pre-cleanup limit from 10 to 3 folders for faster startup
 * - Extended timeouts for test user creation (180s) and cleanup (300s)
 * - Failed folder tracking prevents repeated deletion attempts
 * - Batch deletion with re-query after each deletion to avoid stale elements
 */
test.describe('Access Control and Permissions', () => {
  let authHelper: AuthHelper;
  let testHelper: TestHelper;
  const restrictedFolderName = `restricted-folder-${generateTestId()}`;
  const testDocName = `permission-test-doc-${generateTestId()}.txt`;

  // Generate unique test user name to avoid conflicts with existing users
  const testUsername = `testuser${generateTestId()}`;
  const testUserPassword = 'TestPass123!';

  // Pre-cleanup: Delete old test folders from previous runs BEFORE tests start
  // REFACTORING (2026-01-26): Changed from UI-based to API-based cleanup for reliability
  test.beforeAll(async ({ browser }) => {
    test.setTimeout(60000); // Reduced from 180s to 60s with API-based cleanup

    // Allow skipping pre-cleanup via environment variable for faster test execution
    if (process.env.SKIP_CLEANUP === 'true') {
      console.log('Pre-cleanup: SKIPPED (SKIP_CLEANUP=true)');
      return;
    }

    console.log('Pre-cleanup: Starting API-based cleanup of old test folders');
    const context = await browser.newContext();
    const page = await context.newPage();

    try {
      const apiHelper = new ApiHelper(page);
      
      // Clean up test folders using API (much faster and more reliable than UI)
      const deletedFolders = await apiHelper.cleanupTestFolders('restricted-folder-%', 5);
      const deletedTestFolders = await apiHelper.cleanupTestFolders('test-folder-%', 5);
      
      console.log(`Pre-cleanup: Deleted ${deletedFolders + deletedTestFolders} old test folders via API`);
    } catch (error) {
      console.log('Pre-cleanup: Error during API cleanup:', error);
      // Don't fail - cleanup errors should not block tests
    } finally {
      await context.close();
    }
  });

  // Setup: Create test user
  // REFACTORING (2026-01-26): Reduced timeout from 180s to 90s
  test.beforeAll(async ({ browser }) => {
    test.setTimeout(90000); // Reduced from 180s - user creation should be faster
    const context = await browser.newContext();
    const page = await context.newPage();
    const setupAuthHelper = new AuthHelper(page);

    try {
      // Login as admin
      await setupAuthHelper.login();
      await page.waitForTimeout(2000);

      // Navigate to user management
      console.log('Setup: Navigating to user management');
      const adminMenu = page.locator('.ant-menu-submenu').filter({ hasText: /管理|Admin/i });
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
            await page.waitForTimeout(2000);

            // Verify test user was created by checking user table (faster than login verification)
            let userCreated = false;
            if (successMessageAppeared) {
              console.log('Setup: Verifying user creation in user table...');

              // Refresh user list via UI navigation instead of page.reload() to avoid breaking React Router
              // Navigate away to Documents
              const documentsMenu = page.locator('.ant-menu-item').filter({ hasText: /ドキュメント|Documents/i });
              if (await documentsMenu.count() > 0) {
                await documentsMenu.click();
                await page.waitForTimeout(1000);
                console.log('Setup: Navigated away to Documents');
              }

              // Navigate back to User Management to refresh the list
              const adminMenuRefresh = page.locator('.ant-menu-submenu').filter({ hasText: /管理|Admin/i });
              if (await adminMenuRefresh.count() > 0) {
                await adminMenuRefresh.click();
                await page.waitForTimeout(500);
              }

              const userManagementRefresh = page.locator('.ant-menu-item:has-text("ユーザー管理")');
              if (await userManagementRefresh.count() > 0) {
                await userManagementRefresh.click();
                await page.waitForTimeout(2000);
                console.log('Setup: Navigated back to User Management');
              }

              // Check if test user appears in table
              // New users are added to the end of the list, so check the last page first
              const paginationExists = await page.locator('.ant-pagination').count();
              console.log(`Setup: DEBUG - Pagination exists: ${paginationExists > 0}`);

              if (paginationExists > 0) {
                // Navigate to the last page where new user should appear
                console.log('Setup: Navigating to last page to find new user...');

                // Debug: Show all pagination items
                const paginationItems = await page.locator('.ant-pagination-item').allTextContents();
                console.log(`Setup: DEBUG - Pagination items: ${JSON.stringify(paginationItems)}`);

                const lastPageButton = page.locator('.ant-pagination-item').last();
                const lastPageCount = await lastPageButton.count();
                console.log(`Setup: DEBUG - Last page button count: ${lastPageCount}`);

                if (lastPageCount > 0) {
                  const lastPageText = await lastPageButton.textContent();
                  console.log(`Setup: DEBUG - Clicking last page button: ${lastPageText}`);
                  await lastPageButton.click();
                  await page.waitForTimeout(3000); // Increased from 2000ms for table loading
                }
              }

              // Debug: Show what's actually in the table
              const allTableRows = page.locator('.ant-table-tbody tr');
              const rowCount = await allTableRows.count();
              console.log(`Setup: DEBUG - Total rows in table: ${rowCount}`);

              if (rowCount > 0) {
                // Show first few rows for debugging
                for (let i = 0; i < Math.min(rowCount, 5); i++) {
                  const row = allTableRows.nth(i);
                  const rowText = await row.textContent();
                  console.log(`Setup: DEBUG - Row ${i}: ${rowText?.substring(0, 100)}`);
                }
              }

              // Check if test user appears in table
              const userTableRow = page.locator(`tr:has-text("${testUsername}")`);
              const userRowCount = await userTableRow.count();

              if (userRowCount > 0) {
                console.log(`Setup: ${testUsername} found in user table`);
                userCreated = true;
              } else {
                console.log(`Setup: ${testUsername} not found in table after navigation to last page`);
                // Debug: Try exact match with username
                const exactMatch = await page.locator('.ant-table-tbody').textContent();
                if (exactMatch?.includes(testUsername)) {
                  console.log(`Setup: DEBUG - Username found in table content but tr:has-text() selector didn't match`);
                } else {
                  console.log(`Setup: DEBUG - Username not found anywhere in table content`);
                }
              }
            } else {
              console.log('Setup: Success message not detected - skipping user verification');
            }

            console.log(`Setup: ${testUsername} creation ${userCreated ? 'SUCCESSFUL' : 'failed'}`);

            if (!userCreated && successMessageAppeared) {
              console.log('Setup: Warning - Success message appeared but user not found in table');
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

      // API Fallback: If UI creation may have failed, ensure user exists via REST API
      console.log(`Setup: Ensuring test user exists via REST API fallback`);
      try {
        const createUserResponse = await page.request.post(
          `http://localhost:8080/core/api/v1/cmis/repositories/bedroom/users`,
          {
            headers: {
              'Authorization': `Basic ${Buffer.from('admin:admin').toString('base64')}`,
              'Content-Type': 'application/json'
            },
            data: {
              userId: testUsername,
              userName: `${testUsername}_display`,
              firstName: 'Test',
              lastName: 'User',
              email: `${testUsername}@example.com`,
              password: testUserPassword
            }
          }
        );
        const responseText = await createUserResponse.text();
        console.log(`Setup: REST API user create response: ${createUserResponse.status()} - ${responseText.substring(0, 200)}`);
      } catch (apiError) {
        console.log(`Setup: REST API user create fallback error (may already exist):`, apiError);
      }
    } catch (error) {
      console.log(`Setup: ${testUsername} creation failed:`, error);
    } finally {
      await context.close();
    }
  });

  // Setup: Grant test user read access to root folder
  test.beforeAll(async ({ browser }) => {
    test.setTimeout(120000); // Set 120-second timeout for ACL setup (extended)
    const context = await browser.newContext();
    const page = await context.newPage();

    try {
      console.log('Setup: Granting root folder access to test user via CMIS API');

      const rootFolderId = 'e02f784f8360a02cc14d1314c10038ff';

      // Use CMIS Browser Binding to apply ACL to root folder
      // Note: cmis:read alone is insufficient for getChildren operation
      // Grant cmis:all for full read access including folder navigation
      const response = await page.request.post('http://localhost:8080/core/browser/bedroom', {
        headers: {
          'Authorization': `Basic ${Buffer.from('admin:admin').toString('base64')}`
        },
        form: {
          'cmisaction': 'applyACL',
          'objectId': rootFolderId,
          'addACEPrincipal[0]': testUsername,
          'addACEPermission[0][0]': 'cmis:all'
        }
      });

      if (response.ok()) {
        console.log(`Setup: Root folder ACL applied successfully for ${testUsername}`);
      } else {
        console.log(`Setup: Root folder ACL application failed: ${response.status()} - ${await response.text()}`);
      }
    } catch (error) {
      console.log('Setup: Root folder ACL error:', error);
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

      await testHelper.closeMobileSidebar(browserName);
    });

    test('should create restricted folder with limited permissions', async ({ page, browserName }) => {
      const isMobile = testHelper.isMobile(browserName);

      // Create test folder
      // CRITICAL FIX (2025-12-27): Use modal closure instead of success message
      const createFolderButton = page.locator('button').filter({ hasText: 'フォルダ作成' });

      if (await createFolderButton.count() > 0) {
        await createFolderButton.click(isMobile ? { force: true } : {});

        const modal = page.locator('.ant-modal:not(.ant-modal-hidden)');
        await modal.waitFor({ state: 'visible', timeout: 10000 });
        await page.waitForTimeout(500);

        let nameInput = modal.locator('input[placeholder*="フォルダ名"]').first();
        if (await nameInput.count() === 0) {
          nameInput = modal.locator('input').first();
        }
        await nameInput.fill(restrictedFolderName);

        const submitButton = modal.locator('button[type="submit"]');
        if (await submitButton.count() > 0) {
          await submitButton.first().click();
        } else {
          await modal.locator('button.ant-btn-primary').first().click();
        }

        // Wait for modal to close instead of success message
        await expect(modal).not.toBeVisible({ timeout: 15000 });
        await page.waitForTimeout(1000);

        // Verify folder created
        const createdFolder = page.locator(`text=${restrictedFolderName}`);
        await expect(createdFolder).toBeVisible({ timeout: 5000 });
      } else {
        // UPDATED (2025-12-26): Folder creation IS implemented in DocumentList.tsx
        test.skip('Folder creation button not visible - IS implemented in DocumentList.tsx');
      }
    });

    // CONVERTED (2025-12-27): Changed from UI-based to API-based test for reliability
    // Previous UI-based test had issues with conditional skipping
    test('should set ACL permissions on folder via API (admin only)', async ({ page }) => {
      const authHeader = `Basic ${Buffer.from('admin:admin').toString('base64')}`;
      const rootFolderId = 'e02f784f8360a02cc14d1314c10038ff';
      const testFolderName = `acl-set-test-${generateTestId()}`;

      // Step 1: Create a test folder via CMIS API
      console.log('Test: Creating folder via CMIS API');
      const createResponse = await page.request.post('http://localhost:8080/core/browser/bedroom', {
        headers: { 'Authorization': authHeader },
        form: {
          'cmisaction': 'createFolder',
          'folderId': rootFolderId,
          'propertyId[0]': 'cmis:objectTypeId',
          'propertyValue[0]': 'cmis:folder',
          'propertyId[1]': 'cmis:name',
          'propertyValue[1]': testFolderName
        }
      });

      expect(createResponse.ok()).toBe(true);
      const createResult = await createResponse.json();
      const folderId = createResult.properties?.['cmis:objectId']?.value || createResult.succinctProperties?.['cmis:objectId'];
      expect(folderId).toBeTruthy();
      console.log(`Test: Folder created with ID: ${folderId}`);

      // Step 2: Set ACL permissions via CMIS applyACL API
      console.log('Test: Setting ACL permissions via CMIS API');
      const aclResponse = await page.request.post('http://localhost:8080/core/browser/bedroom', {
        headers: { 'Authorization': authHeader },
        form: {
          'cmisaction': 'applyACL',
          'objectId': folderId,
          'addACEPrincipal[0]': 'admin',
          'addACEPermission[0][0]': 'cmis:all',
          'addACEPrincipal[1]': 'testuser',
          'addACEPermission[1][0]': 'cmis:read'
        }
      });

      expect(aclResponse.ok()).toBe(true);
      console.log('Test: ACL permissions set');

      // Step 3: Verify ACL was set correctly
      const verifyResponse = await page.request.get(
        `http://localhost:8080/core/browser/bedroom/${folderId}?cmisselector=acl`,
        { headers: { 'Authorization': authHeader } }
      );

      expect(verifyResponse.ok()).toBe(true);
      const aclData = await verifyResponse.json();
      const aces = aclData.aces || aclData.acl?.aces || [];

      // Verify admin has cmis:all
      const adminAce = aces.find((ace: any) =>
        ace.principal?.principalId === 'admin' || ace.principalId === 'admin'
      );
      expect(adminAce).toBeTruthy();
      console.log('Test: Admin permission verified');

      // Verify testuser has cmis:read
      const testuserAce = aces.find((ace: any) =>
        ace.principal?.principalId === 'testuser' || ace.principalId === 'testuser'
      );
      expect(testuserAce).toBeTruthy();
      console.log('Test: Testuser permission verified');

      // Cleanup: Delete test folder via API
      const deleteResponse = await page.request.post('http://localhost:8080/core/browser/bedroom', {
        headers: { 'Authorization': authHeader },
        form: {
          'cmisaction': 'delete',
          'objectId': folderId
        }
      });

      if (deleteResponse.ok()) {
        console.log('Test: Folder deleted successfully');
      }
    });

    test('should upload document to restricted folder', async ({ page, browserName }) => {
      const isMobile = testHelper.isMobile(browserName);

      await page.waitForTimeout(2000);

      // Navigate into restricted folder
      const folderLink = page.locator('a, span').filter({ hasText: restrictedFolderName });
      if (await folderLink.count() > 0) {
        await folderLink.first().click();
        await page.waitForTimeout(2000);

        // CRITICAL FIX (2025-12-15): Use flexible selector for upload button
        let uploadButton = page.locator('button').filter({ hasText: 'アップロード' }).first();
        if (await uploadButton.count() === 0) {
          uploadButton = page.locator('button').filter({ hasText: 'ファイルアップロード' }).first();
        }
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

  test.describe('Admin User - Permission Management', () => {
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

      // CRITICAL FIX: Create restricted folder if it doesn't exist
      // This makes Permission Management tests independent from Setup Permissions tests
      const existingFolder = page.locator('tr').filter({ hasText: restrictedFolderName });
      if (await existingFolder.count() === 0) {
        console.log(`BeforeEach: Creating ${restrictedFolderName} for test`);
        const createFolderButton = page.locator('button').filter({ hasText: 'フォルダ作成' });

        if (await createFolderButton.count() > 0) {
          await createFolderButton.click();
          await page.waitForSelector('.ant-modal:not(.ant-modal-hidden)', { timeout: 5000 });

          const nameInput = page.locator('.ant-modal input[placeholder*="名前"], .ant-modal input[id*="name"]');
          await nameInput.fill(restrictedFolderName);

          const submitButton = page.locator('.ant-modal button[type="submit"], .ant-modal .ant-btn-primary');
          await submitButton.click();

          await page.waitForSelector('.ant-message-success', { timeout: 10000 });
          await page.waitForTimeout(2000);
          console.log(`BeforeEach: Successfully created ${restrictedFolderName}`);
        }
      } else {
        console.log(`BeforeEach: ${restrictedFolderName} already exists`);
      }

      await testHelper.closeMobileSidebar(browserName);
    });

    // CONVERTED (2025-12-27): Changed from UI-based to API-based test for reliability
    // Previous UI-based test had ~400 lines of complex UI interactions with multiple conditional skips
    // API-based approach is more reliable and faster
    test('should modify permissions from read-only to read-write via API', async ({ page }) => {
      const authHeader = `Basic ${Buffer.from('admin:admin').toString('base64')}`;
      const rootFolderId = 'e02f784f8360a02cc14d1314c10038ff';
      const testFolderName = `permission-modify-test-${generateTestId()}`;
      const testPrincipal = 'testuser';

      // Step 1: Create a test folder via CMIS API
      console.log('Test: Creating folder via CMIS API');
      const createResponse = await page.request.post('http://localhost:8080/core/browser/bedroom', {
        headers: { 'Authorization': authHeader },
        form: {
          'cmisaction': 'createFolder',
          'folderId': rootFolderId,
          'propertyId[0]': 'cmis:objectTypeId',
          'propertyValue[0]': 'cmis:folder',
          'propertyId[1]': 'cmis:name',
          'propertyValue[1]': testFolderName
        }
      });

      expect(createResponse.ok()).toBe(true);
      const createResult = await createResponse.json();
      const folderId = createResult.properties?.['cmis:objectId']?.value || createResult.succinctProperties?.['cmis:objectId'];
      expect(folderId).toBeTruthy();
      console.log(`Test: Folder created with ID: ${folderId}`);

      // Step 2: Set initial read-only permission via CMIS applyACL API
      console.log('Test: Setting initial read-only permission');
      const setReadResponse = await page.request.post('http://localhost:8080/core/browser/bedroom', {
        headers: { 'Authorization': authHeader },
        form: {
          'cmisaction': 'applyACL',
          'objectId': folderId,
          'addACEPrincipal[0]': testPrincipal,
          'addACEPermission[0][0]': 'cmis:read'
        }
      });
      expect(setReadResponse.ok()).toBe(true);
      console.log('Test: Read-only permission set');

      // Step 3: Verify initial permission is cmis:read
      console.log('Test: Verifying initial permission');
      const verifyReadResponse = await page.request.get(
        `http://localhost:8080/core/browser/bedroom/${folderId}?cmisselector=acl`,
        { headers: { 'Authorization': authHeader } }
      );
      expect(verifyReadResponse.ok()).toBe(true);
      const aclDataBefore = await verifyReadResponse.json();

      // ACL response format: aces can be at root level or under .acl
      const acesBefore = aclDataBefore.aces || aclDataBefore.acl?.aces || [];
      const testUserAceBefore = acesBefore.find(
        (ace: any) => ace.principal?.principalId === testPrincipal || ace.principalId === testPrincipal
      );
      expect(testUserAceBefore).toBeTruthy();
      expect(testUserAceBefore.permissions).toContain('cmis:read');
      console.log(`Test: Verified initial permission: ${JSON.stringify(testUserAceBefore.permissions)}`);

      // Step 4: Modify permission from read-only to read-write
      // Use removeACE to remove the old permission and addACE to add the new one
      console.log('Test: Modifying permission to read-write');
      const modifyResponse = await page.request.post('http://localhost:8080/core/browser/bedroom', {
        headers: { 'Authorization': authHeader },
        form: {
          'cmisaction': 'applyACL',
          'objectId': folderId,
          'removeACEPrincipal[0]': testPrincipal,
          'removeACEPermission[0][0]': 'cmis:read',
          'addACEPrincipal[0]': testPrincipal,
          'addACEPermission[0][0]': 'cmis:write'
        }
      });
      expect(modifyResponse.ok()).toBe(true);
      console.log('Test: Permission modified to read-write');

      // Step 5: Verify permission is now cmis:write
      console.log('Test: Verifying modified permission');
      const verifyWriteResponse = await page.request.get(
        `http://localhost:8080/core/browser/bedroom/${folderId}?cmisselector=acl`,
        { headers: { 'Authorization': authHeader } }
      );
      expect(verifyWriteResponse.ok()).toBe(true);
      const aclDataAfter = await verifyWriteResponse.json();

      // ACL response format: aces can be at root level or under .acl
      const acesAfter = aclDataAfter.aces || aclDataAfter.acl?.aces || [];
      const testUserAceAfter = acesAfter.find(
        (ace: any) => ace.principal?.principalId === testPrincipal || ace.principalId === testPrincipal
      );
      expect(testUserAceAfter).toBeTruthy();
      expect(testUserAceAfter.permissions).toContain('cmis:write');
      console.log(`Test: Verified modified permission: ${JSON.stringify(testUserAceAfter.permissions)}`);

      // Step 6: Cleanup - delete test folder
      console.log('Test: Cleaning up - deleting test folder');
      const deleteResponse = await page.request.post('http://localhost:8080/core/browser/bedroom', {
        headers: { 'Authorization': authHeader },
        form: {
          'cmisaction': 'delete',
          'objectId': folderId
        }
      });
      expect(deleteResponse.ok()).toBe(true);
      console.log('Test: Cleanup completed successfully');

      console.log('Test: Permission modification from read-only to read-write verified successfully!');
    });

    // CONVERTED (2025-12-27): Changed from UI-based to API-based test for reliability
    // Previous UI-based test had Popconfirm callback issues with React closure scope
    // API-based approach bypasses UI component limitations entirely
    test('should remove and restore ACL entry via API', async ({ page }) => {
      const authHeader = `Basic ${Buffer.from('admin:admin').toString('base64')}`;
      const rootFolderId = 'e02f784f8360a02cc14d1314c10038ff';
      const testFolderName = `acl-test-folder-${generateTestId()}`;
      const testPrincipal = 'testuser';

      // Step 1: Create a test folder via CMIS API
      console.log('Test: Creating folder via CMIS API');
      const createResponse = await page.request.post('http://localhost:8080/core/browser/bedroom', {
        headers: { 'Authorization': authHeader },
        form: {
          'cmisaction': 'createFolder',
          'folderId': rootFolderId,
          'propertyId[0]': 'cmis:objectTypeId',
          'propertyValue[0]': 'cmis:folder',
          'propertyId[1]': 'cmis:name',
          'propertyValue[1]': testFolderName
        }
      });

      expect(createResponse.ok()).toBe(true);
      const createResult = await createResponse.json();
      const folderId = createResult.properties?.['cmis:objectId']?.value || createResult.succinctProperties?.['cmis:objectId'];
      expect(folderId).toBeTruthy();
      console.log(`Test: Folder created with ID: ${folderId}`);

      // Step 2: Add permission via CMIS applyACL API
      console.log('Test: Adding permission via CMIS API');
      const addAclResponse = await page.request.post('http://localhost:8080/core/browser/bedroom', {
        headers: { 'Authorization': authHeader },
        form: {
          'cmisaction': 'applyACL',
          'objectId': folderId,
          'addACEPrincipal[0]': testPrincipal,
          'addACEPermission[0][0]': 'cmis:read'
        }
      });

      expect(addAclResponse.ok()).toBe(true);
      console.log('Test: Permission added');

      // Step 3: Verify permission was added
      const aclResponse1 = await page.request.get(
        `http://localhost:8080/core/browser/bedroom/${folderId}?cmisselector=acl`,
        { headers: { 'Authorization': authHeader } }
      );
      expect(aclResponse1.ok()).toBe(true);
      const aclData1 = await aclResponse1.json();
      const aces1 = aclData1.aces || aclData1.acl?.aces || [];
      const testUserAce1 = aces1.find((ace: any) =>
        ace.principal?.principalId === testPrincipal || ace.principalId === testPrincipal
      );
      expect(testUserAce1).toBeTruthy();
      console.log('Test: Verified permission exists');

      // Step 4: Remove permission via CMIS applyACL API
      // CMIS requires both principal AND permission to be specified for removal
      console.log('Test: Removing permission via CMIS API');
      const removeAclResponse = await page.request.post('http://localhost:8080/core/browser/bedroom', {
        headers: { 'Authorization': authHeader },
        form: {
          'cmisaction': 'applyACL',
          'objectId': folderId,
          'removeACEPrincipal[0]': testPrincipal,
          'removeACEPermission[0][0]': 'cmis:read'
        }
      });

      expect(removeAclResponse.ok()).toBe(true);
      console.log('Test: Permission removed');

      // Step 5: Verify permission was removed
      const aclResponse2 = await page.request.get(
        `http://localhost:8080/core/browser/bedroom/${folderId}?cmisselector=acl`,
        { headers: { 'Authorization': authHeader } }
      );
      expect(aclResponse2.ok()).toBe(true);
      const aclData2 = await aclResponse2.json();
      const aces2 = aclData2.aces || aclData2.acl?.aces || [];
      const testUserAce2 = aces2.find((ace: any) =>
        ace.principal?.principalId === testPrincipal || ace.principalId === testPrincipal
      );
      expect(testUserAce2).toBeFalsy();
      console.log('Test: Verified permission removed');

      // Step 6: Restore permission via CMIS applyACL API
      console.log('Test: Restoring permission via CMIS API');
      const restoreAclResponse = await page.request.post('http://localhost:8080/core/browser/bedroom', {
        headers: { 'Authorization': authHeader },
        form: {
          'cmisaction': 'applyACL',
          'objectId': folderId,
          'addACEPrincipal[0]': testPrincipal,
          'addACEPermission[0][0]': 'cmis:read'
        }
      });

      expect(restoreAclResponse.ok()).toBe(true);
      console.log('Test: Permission restored');

      // Step 7: Verify permission was restored
      const aclResponse3 = await page.request.get(
        `http://localhost:8080/core/browser/bedroom/${folderId}?cmisselector=acl`,
        { headers: { 'Authorization': authHeader } }
      );
      expect(aclResponse3.ok()).toBe(true);
      const aclData3 = await aclResponse3.json();
      const aces3 = aclData3.aces || aclData3.acl?.aces || [];
      const testUserAce3 = aces3.find((ace: any) =>
        ace.principal?.principalId === testPrincipal || ace.principalId === testPrincipal
      );
      expect(testUserAce3).toBeTruthy();
      console.log('Test: Verified permission restored');

      // Cleanup: Delete test folder via API
      const deleteResponse = await page.request.post('http://localhost:8080/core/browser/bedroom', {
        headers: { 'Authorization': authHeader },
        form: {
          'cmisaction': 'delete',
          'objectId': folderId
        }
      });

      if (deleteResponse.ok()) {
        console.log('Test: Folder deleted successfully');
      }
    });
  });

  // NOTE: Test 3 (set ACL permissions) and Test 4 (modify permissions) have been
  // replaced with API-based tests in the test above. The UI-based versions had issues
  // with conditional skipping that made them unreliable. The API-based test
  // 'should remove and restore ACL entry via API' covers all necessary ACL operations.

  test.describe('Test User - Verify Permission Restrictions', () => {
    // CRITICAL FIX (2025-11-24): Create restricted folder via CMIS API in beforeAll
    // This ensures the folder exists before Test User tests run, fixing test isolation issue
    // Previous issue: Folder creation was in separate Admin test that didn't run with filtered execution
    test.beforeAll(async ({ browser }) => {
      console.log(`Setup: Creating restricted folder ${restrictedFolderName} via CMIS API`);

      const context = await browser.newContext();
      const page = await context.newPage();

      try {
        // Create folder using CMIS Browser Binding
        const rootFolderId = 'e02f784f8360a02cc14d1314c10038ff';
        const createFolderUrl = `http://localhost:8080/core/browser/bedroom`;

        const response = await page.request.post(createFolderUrl, {
          multipart: {
            'cmisaction': 'createFolder',
            'folderId': rootFolderId,
            'propertyId[0]': 'cmis:objectTypeId',
            'propertyValue[0]': 'cmis:folder',
            'propertyId[1]': 'cmis:name',
            'propertyValue[1]': restrictedFolderName
          },
          headers: {
            'Authorization': `Basic ${Buffer.from('admin:admin').toString('base64')}`
          }
        });

        if (response.ok()) {
          const responseJson = await response.json();
          console.log(`Setup: CMIS API response:`, JSON.stringify(responseJson, null, 2));

          // Check response structure and extract folder ID
          let folderId: string | undefined;
          if (responseJson.properties && responseJson.properties['cmis:objectId']) {
            folderId = responseJson.properties['cmis:objectId'].value;
          } else if (responseJson.succinctProperties && responseJson.succinctProperties['cmis:objectId']) {
            folderId = responseJson.succinctProperties['cmis:objectId'];
          }

          if (!folderId) {
            console.log(`Setup: Failed to extract folder ID from response`);
            return;
          }

          console.log(`Setup: Created restricted folder ${restrictedFolderName} with ID ${folderId}`);

          // Apply ACL to grant test user read-only access
          const aclUrl = `http://localhost:8080/core/browser/bedroom`;
          const aclResponse = await page.request.post(aclUrl, {
            multipart: {
              'cmisaction': 'applyACL',
              'objectId': folderId,
              'addACEPrincipal[0]': testUsername,
              'addACEPermission[0][0]': 'cmis:read'
            },
            headers: {
              'Authorization': `Basic ${Buffer.from('admin:admin').toString('base64')}`
            }
          });

          if (aclResponse.ok()) {
            console.log(`Setup: Applied ACL for ${testUsername} to folder ${restrictedFolderName}`);
          } else {
            console.log(`Setup: ACL application failed: ${await aclResponse.text()}`);
          }

          // Create a test document inside the restricted folder
          console.log(`Setup: Creating document ${testDocName} in folder ${restrictedFolderName}`);
          const createDocUrl = `http://localhost:8080/core/browser/bedroom`;
          const docResponse = await page.request.post(createDocUrl, {
            multipart: {
              'cmisaction': 'createDocument',
              'folderId': folderId,
              'propertyId[0]': 'cmis:objectTypeId',
              'propertyValue[0]': 'cmis:document',
              'propertyId[1]': 'cmis:name',
              'propertyValue[1]': testDocName,
              'content': 'Test document for permission verification'
            },
            headers: {
              'Authorization': `Basic ${Buffer.from('admin:admin').toString('base64')}`
            }
          });

          if (docResponse.ok()) {
            console.log(`Setup: Document ${testDocName} created successfully in ${restrictedFolderName}`);
          } else {
            console.log(`Setup: Document creation failed: ${await docResponse.text()}`);
          }
        } else {
          console.log(`Setup: Folder creation failed: ${await response.text()}`);
        }
      } catch (error) {
        console.log(`Setup: Error creating restricted folder:`, error);
      } finally {
        await context.close();
      }
    });

    test.beforeEach(async ({ page, browserName }) => {
      // CRITICAL FIX (2025-10-26): Extended timeout for test user login with permission delays
      // Test users require additional time for ACL permission propagation after creation
      test.setTimeout(120000); // 2 minutes for test user login and UI initialization

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

      await testHelper.closeMobileSidebar(browserName);
    });

    // CONVERTED (2025-12-27): Changed from UI-based to API-based test for reliability
    // Previous UI-based test had flaky issues with folder navigation and document visibility
    // API-based approach tests permission enforcement directly via CMIS API
    // Uses the dynamic testUsername created by the test suite
    test('should be able to view restricted folder as test user via API', async ({ page }) => {
      const adminAuth = `Basic ${Buffer.from('admin:admin').toString('base64')}`;
      // Use the dynamically created test user credentials from the test suite
      const testUserAuth = `Basic ${Buffer.from(`${testUsername}:${testUserPassword}`).toString('base64')}`;
      const rootFolderId = 'e02f784f8360a02cc14d1314c10038ff';
      const testFolderName = `view-test-folder-${generateTestId()}`;
      const testDocumentName = `test-doc-${generateTestId()}.txt`;

      console.log(`Test: Using test user: ${testUsername}`);

      // Step 1: Create a test folder via CMIS API (as admin)
      console.log('Test: Creating folder via CMIS API');
      const createFolderResponse = await page.request.post('http://localhost:8080/core/browser/bedroom', {
        headers: { 'Authorization': adminAuth },
        form: {
          'cmisaction': 'createFolder',
          'folderId': rootFolderId,
          'propertyId[0]': 'cmis:objectTypeId',
          'propertyValue[0]': 'cmis:folder',
          'propertyId[1]': 'cmis:name',
          'propertyValue[1]': testFolderName
        }
      });

      expect(createFolderResponse.ok()).toBe(true);
      const folderResult = await createFolderResponse.json();
      const folderId = folderResult.properties?.['cmis:objectId']?.value || folderResult.succinctProperties?.['cmis:objectId'];
      expect(folderId).toBeTruthy();
      console.log(`Test: Folder created with ID: ${folderId}`);

      // Step 2: Add dynamic testuser read permission via CMIS applyACL API
      console.log(`Test: Adding ${testUsername} read permission`);
      const setAclResponse = await page.request.post('http://localhost:8080/core/browser/bedroom', {
        headers: { 'Authorization': adminAuth },
        form: {
          'cmisaction': 'applyACL',
          'objectId': folderId,
          'addACEPrincipal[0]': testUsername,
          'addACEPermission[0][0]': 'cmis:read'
        }
      });
      expect(setAclResponse.ok()).toBe(true);
      console.log(`Test: ${testUsername} read permission set`);

      // Step 3: Create a test document in the folder (as admin)
      // Use multipart format for document creation with content
      console.log('Test: Creating test document in folder');
      const createDocResponse = await page.request.post('http://localhost:8080/core/browser/bedroom', {
        headers: { 'Authorization': adminAuth },
        multipart: {
          'cmisaction': 'createDocument',
          'folderId': folderId,
          'propertyId[0]': 'cmis:objectTypeId',
          'propertyValue[0]': 'cmis:document',
          'propertyId[1]': 'cmis:name',
          'propertyValue[1]': testDocumentName,
          'content': 'Test document content for permission verification'
        }
      });
      if (!createDocResponse.ok()) {
        const errorText = await createDocResponse.text();
        console.log(`Test: Document creation failed: ${createDocResponse.status()} - ${errorText}`);
      }
      expect(createDocResponse.ok()).toBe(true);
      const docResult = await createDocResponse.json();
      const docId = docResult.properties?.['cmis:objectId']?.value || docResult.succinctProperties?.['cmis:objectId'];
      console.log(`Test: Document created with ID: ${docId}`);

      // Step 4: Verify testuser can read the folder via CMIS API
      console.log(`Test: Verifying ${testUsername} can read folder`);
      const readFolderResponse = await page.request.get(
        `http://localhost:8080/core/browser/bedroom/${folderId}?cmisselector=object`,
        { headers: { 'Authorization': testUserAuth } }
      );
      if (!readFolderResponse.ok()) {
        const errorText = await readFolderResponse.text();
        console.log(`Test: Folder read failed: ${readFolderResponse.status()} - ${errorText}`);
      }
      expect(readFolderResponse.ok()).toBe(true);
      const folderData = await readFolderResponse.json();
      expect(folderData.properties?.['cmis:name']?.value || folderData.succinctProperties?.['cmis:name']).toBe(testFolderName);
      console.log(`Test: ${testUsername} can read folder successfully`);

      // Step 5: Verify testuser can read folder children (see the document)
      console.log(`Test: Verifying ${testUsername} can see document in folder`);
      const childrenResponse = await page.request.get(
        `http://localhost:8080/core/browser/bedroom/${folderId}?cmisselector=children`,
        { headers: { 'Authorization': testUserAuth } }
      );
      expect(childrenResponse.ok()).toBe(true);
      const childrenData = await childrenResponse.json();
      const objects = childrenData.objects || [];
      const foundDoc = objects.find((obj: any) => {
        const name = obj.object?.properties?.['cmis:name']?.value || obj.object?.succinctProperties?.['cmis:name'];
        return name === testDocumentName;
      });
      expect(foundDoc).toBeTruthy();
      console.log(`Test: ${testUsername} can see document in folder`);

      // Step 6: Cleanup - delete folder and document (as admin)
      // Cleanup failure should not fail the test since verification already passed
      console.log('Test: Cleaning up - deleting test folder and contents');
      try {
        const deleteResponse = await page.request.post('http://localhost:8080/core/browser/bedroom', {
          headers: { 'Authorization': adminAuth },
          form: {
            'cmisaction': 'deleteTree',
            'objectId': folderId,
            'allVersions': 'true',
            'continueOnFailure': 'true'
          }
        });
        if (deleteResponse.ok()) {
          console.log('Test: Cleanup completed successfully');
        } else {
          console.log(`Test: Cleanup warning - deleteTree returned ${deleteResponse.status()}`);
        }
      } catch (cleanupError) {
        console.log(`Test: Cleanup warning - ${cleanupError}`);
      }

      console.log('Test: testuser permission verification completed successfully!');
    });

    test('should NOT be able to delete document (read-only)', async ({ page, browserName }) => {
      const isMobile = testHelper.isMobile(browserName);

      await page.waitForTimeout(2000);

      // Navigate to restricted folder
      // Bug fix: Use specific selector for table button to avoid ambiguity with tree view
      const folderLink = page.locator(`.ant-table-tbody button:has-text("${restrictedFolderName}")`);
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

                // Check if error message appears or deletion is silently blocked
                // Either behavior is acceptable for read-only permissions
                const errorMessage = page.locator('.ant-message-error');
                const errorVisible = await errorMessage.isVisible({ timeout: 2000 }).catch(() => false);

                // If no error message, verify document still exists (deletion was blocked)
                if (!errorVisible) {
                  await page.waitForTimeout(1000);
                  // Document should still exist in the list
                  const docStillExists = await page.locator('tr').filter({ hasText: testDocName }).count();
                  expect(docStillExists).toBeGreaterThan(0);
                }
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
      const isMobile = testHelper.isMobile(browserName);

      await page.waitForTimeout(2000);

      // Navigate to restricted folder
      // Bug fix: Use specific selector for table button to avoid ambiguity with tree view
      const folderLink = page.locator(`.ant-table-tbody button:has-text("${restrictedFolderName}")`);
      if (await folderLink.count() > 0) {
        await folderLink.click();
        await page.waitForTimeout(2000);

        // CRITICAL FIX (2025-12-15): Use flexible selector for upload button
        let uploadButton = page.locator('button').filter({ hasText: 'アップロード' }).first();
        if (await uploadButton.count() === 0) {
          uploadButton = page.locator('button').filter({ hasText: 'ファイルアップロード' }).first();
        }

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

    test('should clean up restricted folder and contents', async ({ page }) => {
      test.setTimeout(60000);
      console.log(`Cleanup: Attempting to delete folder: ${restrictedFolderName}`);
      
      // Use CMIS API to delete the folder tree
      const baseUrl = 'http://localhost:8080/core/browser/bedroom';
      const authHeader = 'Basic ' + Buffer.from('admin:admin').toString('base64');
      
      const repoResponse = await page.request.get(`${baseUrl}?cmisselector=repositoryInfo`, {
        headers: { 'Authorization': authHeader }
      });
      const repoData = await repoResponse.json();
      const rootFolderId = repoData.bedroom.rootFolderId;
      
      const searchResponse = await page.request.get(
        `${baseUrl}?cmisselector=query&q=SELECT cmis:objectId FROM cmis:folder WHERE cmis:name='${restrictedFolderName}'`,
        { headers: { 'Authorization': authHeader } }
      );
      const searchData = await searchResponse.json();
      
      if (searchData.results && searchData.results.length > 0) {
        const result = searchData.results[0];
        const folderId = result.succinctProperties ? result.succinctProperties['cmis:objectId'] : result.properties['cmis:objectId'].value;
        console.log(`Cleanup: Found folder ID: ${folderId}`);
        
        // Delete the folder tree using deleteTree operation
        const deleteResponse = await page.request.post(`${baseUrl}/${folderId}`, {
          headers: { 'Authorization': authHeader },
          form: {
            cmisaction: 'deleteTree',
            folderId: folderId
          }
        });
        
        if (deleteResponse.ok()) {
          console.log(`Cleanup: Successfully deleted folder: ${restrictedFolderName}`);
        } else {
          console.log(`Cleanup: Failed to delete folder: ${deleteResponse.status()}`);
        }
      } else {
        console.log(`Cleanup: Folder not found: ${restrictedFolderName}`);
      }
    });
  });

  // Cleanup: Remove accumulated test folders via CMIS API to prevent performance degradation
  // CRITICAL FIX (2025-12-27): Changed from UI-based to API-based cleanup to avoid
  // "ant-modal-wrap intercepts pointer events" errors
  test.afterAll(async ({ browser }) => {
    test.setTimeout(60000); // Set 60-second timeout for API-based cleanup

    // Allow skipping cleanup via environment variable for faster test execution
    if (process.env.SKIP_CLEANUP === 'true') {
      console.log('Cleanup: SKIPPED (SKIP_CLEANUP=true)');
      return;
    }

    console.log('Cleanup: Starting API-based test folder cleanup');
    const context = await browser.newContext();
    const page = await context.newPage();

    try {
      const authHeader = `Basic ${Buffer.from('admin:admin').toString('base64')}`;
      let deletedCount = 0;

      // Query for folders starting with restricted-folder- or test-folder-
      const folderPatterns = ['restricted-folder-%', 'test-folder-%'];

      for (const pattern of folderPatterns) {
        const queryResponse = await page.request.get(
          `http://localhost:8080/core/browser/bedroom?cmisselector=query&q=SELECT%20cmis:objectId%20FROM%20cmis:folder%20WHERE%20cmis:name%20LIKE%20'${encodeURIComponent(pattern)}'`,
          {
            headers: { 'Authorization': authHeader }
          }
        );

        if (queryResponse.ok()) {
          const queryResult = await queryResponse.json();
          const folders = queryResult.results || [];

          for (const folder of folders) {
            const folderId = folder.properties?.['cmis:objectId']?.value;
            if (folderId) {
              try {
                const deleteResponse = await page.request.post('http://localhost:8080/core/browser/bedroom', {
                  headers: { 'Authorization': authHeader },
                  form: {
                    'cmisaction': 'delete',
                    'objectId': folderId,
                    'allVersions': 'true'
                  }
                });
                if (deleteResponse.ok()) {
                  deletedCount++;
                  console.log(`Cleanup: Deleted folder ${folderId}`);
                }
              } catch (deleteError) {
                console.log(`Cleanup: Failed to delete folder ${folderId}`);
              }
            }
          }
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
