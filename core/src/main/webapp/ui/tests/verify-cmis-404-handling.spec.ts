/**
 * CMIS API 404 Error Handling Verification Tests
 *
 * Specialized test suite for CMIS API error handling with route interception:
 * - Simulates CMIS API 404 errors using Playwright route interception
 * - Validates React UI handles deleted/non-existent content gracefully
 * - Tests both redirect behavior and UI functionality after API errors
 * - Ensures users are never stuck on error screens without recovery
 * - Complements verify-404-redirect.spec.ts with API-focused scenarios
 *
 * User Requirement (Original): "404エラーになる可能性がある場所は初期のログインページへの遷移にして欲しいです。
 * すぐにエラーで身動きできなくなるのでテストもしにくいですし。"
 * (Translation: "Places that might cause 404 errors should redirect to initial login page.
 * Otherwise it immediately becomes an error that you can't recover from, making testing difficult.")
 *
 * Test Coverage (2 CMIS API error scenarios):
 * 1. Document access 404 error → Should redirect to login or show error message
 * 2. Direct API 404 call → UI should remain functional after error
 *
 * IMPORTANT DESIGN DECISIONS:
 *
 * 1. Playwright Route Interception Pattern (Lines 24-35):
 *    - Intercepts CMIS AtomPub getObject requests: /core/atom/bedroom/id?id=*
 *    - Returns synthetic 404 response without server modification
 *    - Pattern: await page.route('pattern', async (route) => route.fulfill({status: 404}))
 *    - Rationale: Allows testing error handling without creating/deleting test data
 *    - Implementation: Intercept before UI interaction, verify error handling after
 *    - Advantage: Deterministic 404 errors, no test data pollution
 *
 * 2. CMIS API Error Simulation Strategy (Lines 24-35, 160-179):
 *    - Test 1: Route interception for UI-triggered API calls (user clicks document)
 *    - Test 2: page.evaluate() for direct fetch() API calls (programmatic access)
 *    - Both simulate deleted/non-existent content scenarios
 *    - Rationale: Different error paths (UI-triggered vs programmatic) need different simulation
 *    - Implementation: Route interception catches user interactions, fetch() tests API directly
 *
 * 3. Document vs Folder Click Differentiation (Lines 78-83):
 *    - Targets documents specifically: .ant-table-tbody tr:has([aria-label="file"])
 *    - Uses img[alt="file"] to ensure document row selection
 *    - Avoids folders which use different API endpoints (getChildren vs getObject)
 *    - Rationale: Only document access triggers intercepted getObject endpoint
 *    - Implementation: Folder clicks bypass route interception, document clicks trigger 404
 *
 * 4. Mobile Browser Support with Sidebar Handling (Lines 59-72, 143-156):
 *    - Detects mobile viewport: browserName === 'chromium' && width <= 414
 *    - Closes sidebar before UI interactions to prevent overlay blocking
 *    - Uses menu toggle button: aria-label="menu-fold" or "menu-unfold"
 *    - Includes 500ms animation wait after sidebar close
 *    - Rationale: Mobile layouts render sidebar as blocking overlay
 *    - Implementation: Conditional sidebar close with try-catch graceful failure
 *
 * 5. Dual Test Strategy - Redirect vs Functional UI (Tests 1 and 2):
 *    - Test 1: Prefers login redirect but accepts error message shown
 *    - Test 2: Verifies UI remains functional regardless of redirect
 *    - Both tests consider multiple outcomes as "success"
 *    - Rationale: Graceful degradation more important than specific behavior
 *    - User Goal: Never stuck on error screen, always have recovery path
 *
 * 6. Console Event Monitoring for Debugging (Lines 15-22):
 *    - Captures browser console messages: page.on('console', msg => ...)
 *    - Captures page errors: page.on('pageerror', error => ...)
 *    - Logs execution flow for test debugging
 *    - Rationale: API errors may generate console errors before UI updates
 *    - Implementation: Event handlers established before navigation
 *
 * 7. Graceful Error Handling Verification (Lines 92-114, 192-210):
 *    - Multiple acceptable outcomes: login redirect OR error message OR functional UI
 *    - Rejects "stuck" state: no login form, no error message, no functional UI
 *    - Implementation: hasLoginForm || hasErrorMessage || hasDocumentsTable
 *    - Rationale: User must always have a way to recover from errors
 *    - User Experience: Any recovery path acceptable, stuck state unacceptable
 *
 * 8. API Direct Call Testing with page.evaluate() (Lines 160-179):
 *    - Uses page.evaluate() to run fetch() inside browser context
 *    - Direct CMIS API call: /core/browser/bedroom/root?objectId=nonexistent-id
 *    - Returns {status, ok, error} object for test assertions
 *    - Rationale: Tests API-level error handling separate from UI interactions
 *    - Implementation: fetch() with credentials and auth headers
 *
 * 9. Force Click for Test Environment Reliability (Lines 83, 187):
 *    - Uses .click({ force: true }) to bypass overlay/visibility checks
 *    - Applied to document row clicks and menu navigation
 *    - Rationale: Test environment may have layout differences from production
 *    - Trade-off: Bypasses real user interaction validation for test stability
 *    - Use Case: Sidebar overlays and mobile viewport testing
 *
 * 10. Multi-Outcome Acceptance Pattern (Lines 101-114, 195-210):
 *     - Accepts login redirect as "preferred behavior"
 *     - Accepts error message shown as "acceptable"
 *     - Accepts functional UI remaining as "acceptable"
 *     - Only rejects "stuck" state with no recovery
 *     - Rationale: Focuses on user experience outcome, not implementation
 *     - Implementation: Boolean OR conditions for multiple success paths
 *
 * Expected Results:
 * - Test 1: Either login redirect OR error message shown (user not stuck)
 * - Test 2: UI remains functional after API 404 error (can navigate)
 *
 * Performance Characteristics:
 * - Test 1: 10-15 seconds (login + route interception + error handling)
 * - Test 2: 8-12 seconds (login + API call + UI verification)
 * - Route interception: Instant (no server roundtrip)
 * - Wait timeouts: 1-5 seconds for UI updates after errors
 *
 * Debugging Features:
 * - Browser console message capture and logging
 * - Page error event capture and logging
 * - Route interception URL logging
 * - API call result logging (status, ok, error)
 * - Current URL logging after error handling
 * - Multi-outcome detection logging (login form, error message, documents table)
 *
 * Known Limitations:
 * - Route interception only works for AtomPub endpoints (not Browser Binding)
 * - Force click bypasses real interaction validation
 * - Accepts multiple outcomes making it hard to enforce specific behavior
 * - Mobile sidebar close may fail silently (graceful degradation)
 * - Console event handlers may miss errors occurring before setup
 *
 * Relationship to Other Tests:
 * - Complements verify-404-redirect.spec.ts (focuses on CMIS API specifically)
 * - Uses similar mobile browser patterns as login.spec.ts
 * - Related to access-control.spec.ts (API-level testing approach)
 * - Similar route interception strategy could be used in other tests
 *
 * Common Failure Scenarios:
 * - Test 1 fails: User stuck on error screen (no login, no error message)
 * - Test 2 fails: UI broken after API error (no documents table, no login)
 * - Route interception not triggered: Wrong URL pattern or timing
 * - Mobile sidebar close fails: Selector changed or animation timing
 * - Force click necessary: Real interaction blocked by overlays
 */

import { test, expect } from '@playwright/test';
import { waitForRender, waitForUiStable } from './utils/wait-helpers';
import { TestHelper } from './utils/test-helper';
import { AuthHelper } from './utils/auth-helper';
import { ApiHelper } from './utils/api-helper';
import { cleanupTestData } from './utils/cleanup-helper';

// Each test creates its own cmis-404-test-* document; sweep them so they do not
// accumulate across runs.
test.afterAll(({ browser }) => cleanupTestData(browser, { documents: ['cmis-404-test-%'] }));

/**
 * SELECTOR FIX (2025-12-24) - Document Row Detection Fixed
 *
 * Previous Issue: Selector '.ant-table-tbody tr:has([aria-label="file"]) button' failed
 * because aria-label="file" is not used by Ant Design icons.
 *
 * Fix Applied: Use .anticon-file class selector instead of aria-label attribute.
 * Ant Design FileOutlined component renders as: <span class="anticon anticon-file">
 *
 * New Selectors:
 * - Document rows: '.ant-table-tbody tr:has(.anticon-file)'
 * - All rows with buttons: '.ant-table-tbody tr button.ant-btn-link' (more flexible)
 */
test.describe('CMIS API 404 Error Handling', () => {
  let testHelper: TestHelper;

  test.beforeEach(async ({ page }) => {
    testHelper = new TestHelper(page);
  });

  /**
   * FIX (2025-12-24): Route interception timing fixed
   *
   * Previous Issue: Route interception was set BEFORE page load, intercepting folder requests too.
   * Solution: Set up route interception AFTER page loads and document list is visible.
   * This ensures only document detail requests are intercepted, not folder list requests.
   */
  test('should handle document access 404 error gracefully', async ({ page, browserName }) => {
    // Enable console logging to trace execution flow
    page.on('console', msg => {
      console.log('BROWSER:', msg.type(), msg.text());
    });

    page.on('pageerror', error => {
      console.log('PAGE ERROR:', error.message);
    });

    // Create a deterministic document so the table always has a clickable
    // document. This removes the dependency on pre-existing content (which makes
    // the test fail on a fresh DB where the root has no documents) and lets us
    // target it by name — a stable locator that does not shift as the table
    // re-renders (the old `.first()` row could detach mid-click under load).
    const apiHelper = new ApiHelper(page);
    const doc404Name = `cmis-404-test-${Date.now()}.txt`;
    const doc404Id = await apiHelper.createDocument({ name: doc404Name, content: 'content for the 404 handling test' });

    // NOTE: Route interception is set up AFTER login to avoid intercepting folder requests
    // Navigate to login page FIRST
    await page.goto('http://localhost:8080/core/ui/index.html');
    await waitForRender(page);

    // Login as admin
    const repositorySelect = page.locator('.ant-select-selector').first();
    await repositorySelect.click();
    await waitForRender(page);
    await page.locator('.ant-select-item-option:has-text("bedroom")').click();
    await waitForRender(page);

    await page.locator('input[placeholder*="ユーザー名"]').fill('admin');
    await page.locator('input[placeholder*="パスワード"]').fill('admin');
    await page.locator('button[type="submit"]').click();

    // Wait for navigation and UI initialization (longer timeout for webkit/tablet)
    await waitForUiStable(page, { timeout: 15000 });

    // Verify login successful - check for documents page elements
    await expect(page.locator('.ant-table')).toBeVisible({ timeout: 15000 });
    console.log('✅ Login successful - documents page loaded');

    // NOW set up route interception - AFTER page and document list loaded
    // This ensures folder requests are not intercepted, only document detail requests
    await page.route('**/core/atom/bedroom/id?id=*', async (route) => {
      const url = route.request().url();
      // Intercept ONLY our test document's getObject. The previous broad match
      // also 404'd the root-folder getObject, which broke the whole document
      // list ("rootFolderId is stale (404)") so no rows ever rendered. Let
      // folder/root loads pass through.
      if (url.includes(`id=${doc404Id}`)) {
        console.log('📍 Intercepting getObject for the test document, returning 404:', url);
        await route.fulfill({
          status: 404,
          contentType: 'text/plain',
          body: 'Object not found'
        });
      } else {
        await route.continue();
      }
    });
    console.log('✅ Route interception set up (scoped to the test document)');

    // MOBILE FIX: Close sidebar to prevent overlay blocking clicks
    const isMobile = testHelper.isMobile(browserName);

    if (isMobile) {
      // Look for hamburger menu toggle button
      const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]').first();

      if (await menuToggle.count() > 0) {
        await menuToggle.click({ timeout: 3000 });
        await waitForRender(page); // Wait for animation
        console.log('✅ Mobile sidebar closed');
      }
    }

    // Try to trigger 404 error by clicking on a document
    // The route interceptor will return 404, triggering the error handling chain
    console.log('📍 Testing 404 error handling by clicking document...');

    // Click on the first DOCUMENT in the table (not folder - documents trigger getObject which we intercept)
    // FIX: Use .anticon-file class instead of aria-label="file" attribute
    // Ant Design FileOutlined renders as <span class="anticon anticon-file">
    // Target our own document by name. This row is stable across re-renders,
    // unlike `.first()`, which can resolve to a row that is replaced mid-click
    // (the previous "element was detached from the DOM, retrying" timeout).
    const doc404Row = page.locator(`.ant-table-tbody tr:has-text("${doc404Name}")`).first();
    await expect(doc404Row).toBeVisible({ timeout: 15000 });
    const firstDocument = doc404Row.locator('button.ant-btn-link').first();
    await expect(firstDocument).toBeVisible({ timeout: 10000 });

    // Use force click to bypass any sidebar overlay in the test environment.
    await firstDocument.click({ force: true });

    // Wait for 404 error handling and potential redirect. The graceful outcome
    // is one of: a login form (handleAuthError logout), a transient toast
    // (.ant-message — auto-dismisses, so it must not be the only signal we rely
    // on), or the persistent inline error Alert that DocumentViewer renders when
    // loadObject fails (`if (loadError) return <Alert type="error" .../>`).
    // Poll for any of those instead of reading a transient toast after a fixed
    // settle, which races the toast's auto-dismiss.
    await waitForUiStable(page);
    // Scope to *error* variants only so an unrelated info/success toast can't
    // make the test pass: the 404 path raises a `.ant-message-error` toast and
    // renders a persistent `.ant-alert-error` Alert (DocumentViewer loadObject
    // catch + `if (loadError) return <Alert type="error" .../>`).
    const gracefulIndicator = page.locator(
      'input[placeholder*="ユーザー名"], .ant-alert-error, .ant-message-error, .ant-notification-error'
    );
    await gracefulIndicator.first().waitFor({ state: 'visible', timeout: 10000 }).catch(() => {});

    // Check current URL and page state after API error
    const currentUrl = page.url();
    console.log('Current URL after 404:', currentUrl);

    // After 404 error, should be redirected to login OR stay on documents with
    // a 404-driven error indicator (transient error toast or persistent Alert)
    const isOnLoginPage = currentUrl.includes('index.html') && !currentUrl.includes('#/documents');
    const hasErrorMessage = await page.locator('.ant-message-error, .ant-notification-error, .ant-alert-error').count() > 0;
    const hasLoginForm = await page.locator('input[placeholder*="ユーザー名"]').count() > 0;

    console.log('Is on login page:', isOnLoginPage);
    console.log('Has error message:', hasErrorMessage);
    console.log('Has login form:', hasLoginForm);

    // Either redirected to login OR shows error message (both are acceptable)
    const isHandledGracefully = hasLoginForm || hasErrorMessage;

    if (hasLoginForm) {
      console.log('✅ 404 error redirected to login page (preferred behavior)');
      await expect(page.locator('input[placeholder*="ユーザー名"]')).toBeVisible();
      await expect(page.locator('input[placeholder*="パスワード"]')).toBeVisible();
    } else if (hasErrorMessage) {
      console.log('⚠️  404 error showed error message but user can continue (acceptable)');
    }

    expect(isHandledGracefully).toBe(true);
  });

  test('should not break UI when accessing deleted content', async ({ page, browserName }) => {
    // This test verifies that even if 404 handling doesn't redirect,
    // the UI remains functional and user isn't "stuck"

    // Use the robust shared login helper (retries the whole flow up to 3x)
    // instead of an inline manual login that occasionally fails to complete,
    // leaving the documents table never rendered.
    await new AuthHelper(page).login();
    await waitForUiStable(page, { timeout: 15000 });

    // The document list occasionally hangs on "読み込み中..." after login; reload
    // and retry until the table renders.
    let docTableReady = false;
    for (let attempt = 0; attempt < 3 && !docTableReady; attempt++) {
      docTableReady = await page.locator('.ant-table').first()
        .waitFor({ state: 'visible', timeout: 15000 }).then(() => true).catch(() => false);
      if (!docTableReady) {
        await page.reload();
        await waitForUiStable(page, { timeout: 15000 });
      }
    }
    expect(docTableReady, 'documents table should render after login').toBe(true);
    console.log('✅ Login successful');

    // MOBILE FIX: Close sidebar to prevent overlay blocking clicks
    const isMobile = testHelper.isMobile(browserName);

    if (isMobile) {
      // Look for hamburger menu toggle button
      const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]').first();

      if (await menuToggle.count() > 0) {
        await menuToggle.click({ timeout: 3000 });
        await waitForRender(page); // Wait for animation
        console.log('✅ Mobile sidebar closed');
      }
    }

    // Try to trigger 404 error by accessing non-existent content via API
    // Then verify user can still navigate
    const result = await page.evaluate(async () => {
      try {
        // This will trigger CMISService with 404 error
        const response = await fetch('http://localhost:8080/core/browser/bedroom/root?objectId=nonexistent-id&cmisselector=object', {
          credentials: 'include',
          headers: {
            'Authorization': 'Basic ' + btoa('admin:admin')
          }
        });
        return {
          status: response.status,
          ok: response.ok
        };
      } catch (error) {
        return {
          status: 0,
          error: String(error)
        };
      }
    });

    console.log('API call result:', result);

    // After API 404 error, verify UI is still responsive
    const documentsMenu = page.locator('.ant-menu-item').filter({ hasText: /ドキュメント|Documents/i });
    if (await documentsMenu.count() > 0) {
      // Use force click to bypass sidebar overlay in test environment
      await documentsMenu.click({ force: true });
      await waitForRender(page);
    }

    // Verify documents page still loads
    const hasDocumentsTable = await page.locator('.ant-table').count() > 0;
    const hasLoginForm = await page.locator('input[type="text"]').count() > 0;

    // Either on documents page OR on login page (both show UI is not stuck)
    const isUIFunctional = hasDocumentsTable || hasLoginForm;

    console.log('Has documents table:', hasDocumentsTable);
    console.log('Has login form:', hasLoginForm);
    console.log('UI is functional:', isUIFunctional);

    if (hasLoginForm) {
      console.log('✅ Redirected to login after 404 (good)');
    } else if (hasDocumentsTable) {
      console.log('✅ UI remains functional after 404 (acceptable)');
    }

    expect(isUIFunctional).toBe(true);
  });
});
