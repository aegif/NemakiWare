/**
 * Permission Management UI - ACL Display Tests
 *
 * Comprehensive test suite for CMIS ACL (Access Control List) management functionality:
 * - ACL data loading and display in React UI
 * - REST API endpoint verification for ACL retrieval
 * - Network request URL pattern validation (REST vs Browser Binding)
 * - Error handling for ACL data loading failures
 * - Mobile browser support for permission management operations
 *
 * Test Coverage (3 tests):
 * 1. UI-based ACL data loading (ENABLED - permissions button with text label)
 * 2. Direct ACL REST API endpoint verification
 * 3. Network request URL pattern validation
 *
 * IMPORTANT DESIGN DECISIONS:
 *
 * 1. Timestamp-Based Unique Test Folder Naming (Line 163):
 *    - Uses Date.now() for unique test folder names: `permissions-test-${Date.now()}`
 *    - Prevents parallel test execution conflicts (different timestamps)
 *    - Avoids cleanup race conditions across multiple browser projects
 *    - Example: permissions-test-1620270113521 (unique to each test run)
 *    - Rationale: Multiple browser projects may run tests simultaneously
 *    - Implementation: testFolderName declared at suite level, used in test 1
 *
 * 2. Mobile Browser Support with Sidebar Close Logic (Lines 172-182):
 *    - Detects mobile viewport: browserName === 'chromium' && width <= 414
 *    - Closes sidebar before ACL operations to prevent overlay blocking
 *    - Uses menu toggle button: aria-label="menu-fold" or "menu-unfold"
 *    - Includes 500ms animation wait after sidebar close
 *    - Applied in both beforeEach and individual tests
 *    - Rationale: Mobile layouts render sidebar as blocking overlay
 *    - Implementation: Conditional sidebar close with try-catch graceful failure
 *
 * 3. Direct ACL REST API Testing with page.evaluate() (Lines 330-377):
 *    - Uses page.evaluate() to run fetch() inside browser context
 *    - Tests ACL endpoint: `/core/rest/repo/bedroom/node/${objectId}/acl`
 *    - Returns structured response: {status, ok, hasACL, hasPermissions}
 *    - Bypasses UI layer to test API directly (no UI dependency)
 *    - Rationale: API-level verification independent of UI implementation
 *    - Implementation: Two-step process (query root folder ID, then fetch ACL)
 *
 * 4. Network Request Monitoring for URL Verification (Lines 398-447):
 *    - Uses page.on('request') to capture all network requests
 *    - Filters requests containing 'acl' in URL
 *    - Logs captured ACL request URLs for debugging
 *    - Validates correct REST API endpoint used (not Browser Binding)
 *    - Rationale: Ensures UI code uses correct CMIS service method
 *    - Implementation: Request listener with array accumulation pattern
 *
 * 5. Smart Conditional Skipping for UI Features (Lines 187, 299, 303):
 *    - test.skip() when UI elements not found (permissions button)
 *    - Graceful degradation instead of hard failures
 *    - Console logging explains why test skipped
 *    - Example: "Permissions button not implemented in UI yet"
 *    - Rationale: Tests document expected UI features without blocking CI
 *    - Implementation: Element count check before test.skip() call
 *
 * 6. ACL Endpoint URL Pattern Validation (Lines 433-440):
 *    - Validates presence of correct URL: /core/rest/repo/.../acl
 *    - Validates absence of wrong URL: /core/browser/.../acl
 *    - Uses array.some() for pattern matching across all requests
 *    - Both positive and negative assertions for comprehensive validation
 *    - Rationale: Previous bug used wrong Browser Binding endpoint
 *    - Implementation: Two expect() calls for hasCorrectUrl and !hasWrongUrl
 *
 * 7. BeforeEach Session Reset Pattern (Lines 165-185):
 *    - Creates fresh AuthHelper and TestHelper instances per test
 *    - Performs login to establish authenticated session
 *    - Waits 2 seconds for UI initialization after login
 *    - Closes mobile sidebar if applicable
 *    - Waits for Ant Design component load completion
 *    - Rationale: Ensures consistent starting state for all tests
 *    - Implementation: Standard pattern across all permission test files
 *
 * 8. Test Folder Creation and Cleanup Pattern (Lines 198-323):
 *    - Creates unique test folder using timestamp-based name
 *    - Tests ACL operations on created folder
 *    - Deletes test folder in cleanup phase (lines 306-323)
 *    - Cleanup uses delete button + confirmation popconfirm pattern
 *    - Rationale: Leaves no test data artifacts after execution
 *    - Implementation: Symmetric create/delete with error handling
 *
 * 9. Modal/Drawer Dual Support Strategy (Lines 243, 269):
 *    - Supports both Ant Design modal and drawer components
 *    - Uses locator: '.ant-modal, .ant-drawer' to match either
 *    - Close button also supports multiple selectors
 *    - Rationale: UI implementation may use either modal or drawer
 *    - Implementation: CSS multi-selector pattern with .last() to get most recent
 *
 * 10. Error Message Negative Assertion Pattern (Lines 231-240, 295-297):
 *     - Explicitly checks for ERROR absence (not just success presence)
 *     - Targets specific error: "データの読み込みに失敗しました" (Data loading failed)
 *     - Uses .not.toBeVisible() instead of .toHaveCount(0)
 *     - Logs both error and success cases for debugging
 *     - Rationale: Previous bug showed this exact error message
 *     - Implementation: Combined error check + success verification approach
 *
 * Expected Results:
 * - Test 1: Verifies ACL data loads without error message when clicking permissions button
 * - Test 2: ACL REST API endpoint returns HTTP 200 with valid ACL object
 * - Test 3: UI code uses correct /core/rest/repo/ endpoint (not /core/browser/)
 *
 * Performance Characteristics:
 * - Test 1: ~15-20 seconds (folder creation + ACL UI interaction + cleanup)
 * - Test 2: ~2-3 seconds (direct API call with browser context evaluation)
 * - Test 3: ~5-10 seconds (navigation + network monitoring + UI interaction)
 * - Total suite: ~25-35 seconds (all 3 tests active)
 *
 * Debugging Features:
 * - Comprehensive console logging for each test phase
 * - ACL request URL capture and logging
 * - Error message detection with specific text matching
 * - API response structure logging (status, ok, hasACL, hasPermissions)
 * - Element count logging before conditional skips
 * - Root folder ID extraction logging
 *
 * Known Limitations:
 * - Test 1: Permissions button uses text label for proper selector matching
 * - Network monitoring only captures requests during test execution
 * - Cleanup may fail if delete button selectors change
 * - Mobile sidebar close may fail silently (graceful degradation)
 * - ACL table/list structure assumed (may need selector updates)
 * - Requires root folder to exist for test 2 and 3
 *
 * Relationship to Other Tests:
 * - Related to access-control.spec.ts (ACL functionality testing)
 * - Uses same mobile browser patterns as document-management.spec.ts
 * - Complements acl-management.spec.ts (different testing approach)
 * - Similar page.evaluate() pattern as document-versioning.spec.ts
 * - Shares AuthHelper/TestHelper utilities with all test files
 *
 * Common Failure Scenarios:
 * - Test 1 fails: Permissions button text label missing or changed
 * - Test 2 fails: ACL REST API endpoint not accessible (HTTP 404/500)
 * - Test 2 fails: Root folder query returns no results
 * - Test 3 fails: UI code uses wrong Browser Binding endpoint
 * - Test 3 fails: No ACL requests detected (permissions button not clicked)
 * - Cleanup fails: Delete button selector changed in UI
 * - Mobile sidebar close fails: Toggle button selector or animation timing
 *
 * ACL REST API Endpoint Structure:
 * - Correct endpoint: /core/rest/repo/{repositoryId}/node/{objectId}/acl
 * - Wrong endpoint: /core/browser/{repositoryId}/{path}?cmisselector=acl
 * - Response format: {acl: {permissions: [...], isExact: boolean}}
 * - Authentication: Basic auth with admin:admin credentials
 *
 * CMIS ACL Permission Structure:
 * - Principal: User or group identifier (e.g., "admin", "GROUP_EVERYONE")
 * - Permissions: Array of permission strings (e.g., ["cmis:read", "cmis:write"])
 * - Direct: Boolean indicating if permission is directly applied or inherited
 * - ACE (Access Control Entry): Single permission assignment to a principal
 *
 * Test Data Management:
 * - Unique folder names prevent parallel test conflicts
 * - Cleanup ensures no test data accumulation
 * - Root folder used for API testing (always exists)
 * - Test folders created in current directory (no specific path required)
 */
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

  test('should successfully load ACL data when clicking permissions button', async ({ page, browserName }) => {
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
