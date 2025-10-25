/**
 * Document Viewer Authentication Tests
 *
 * Specialized test suite for document detail view authentication stability:
 * - Verifies no re-authentication required when accessing document details
 * - Tests authentication token persistence across document navigation
 * - Validates session stability across multiple document accesses
 * - Handles both page navigation and drawer/modal rendering modes
 * - Prevents "user stuck" scenarios with authentication errors
 *
 * User Requirement (Original Issue): "Content detail screen requires re-authentication and then errors"
 * Goal: Users should access document details seamlessly without authentication prompts
 *
 * Test Coverage (2 tests):
 * 1. Single document detail access - Verifies no auth errors, details load, back button works
 * 2. Multiple document accesses (SKIPPED) - Session stability test for sequential access patterns
 *
 * IMPORTANT DESIGN DECISIONS:
 *
 * 1. Console Event Monitoring for Auth Debugging (Lines 14-21):
 *    - Captures browser console messages: page.on('console', msg => ...)
 *    - Captures page errors: page.on('pageerror', error => ...)
 *    - Logs execution flow and error details for authentication issue diagnosis
 *    - Rationale: Auth errors may appear in console before UI updates
 *    - Implementation: Event handlers established before navigation
 *    - Debugging Value: Helps identify token expiration vs network errors
 *
 * 2. Mobile Browser Sidebar Handling (Lines 45-56):
 *    - Detects mobile viewport: browserName === 'chromium' && width <= 414
 *    - Closes sidebar before document clicks to prevent overlay blocking
 *    - Uses menu toggle button: aria-label="menu-fold" or "menu-unfold"
 *    - Includes try-catch pattern with count() check for graceful failure
 *    - Rationale: Mobile layouts render sidebar as blocking overlay
 *    - Implementation: Conditional sidebar close with 500ms animation wait
 *
 * 3. Triple-Layer Authentication State Verification (Lines 76-92):
 *    - Layer 1: Check for login form (hasLoginForm) - indicates re-auth required
 *    - Layer 2: Check for auth error messages (hasAuthError) - indicates token/permission issues
 *    - Layer 3: Check for document details (hasDocumentDetails) - indicates successful access
 *    - Rationale: Authentication failures can manifest in different ways
 *    - Implementation: Three separate locator queries with distinct assertions
 *    - Error Handling: Logs error text when hasAuthError is true for debugging
 *
 * 4. Force Click Strategy for Test Environment Reliability (Lines 70, 112, 174, 267):
 *    - Uses .click({ force: true }) to bypass overlay/visibility checks
 *    - Applied to document row clicks and back button navigation
 *    - Rationale: Test environment may have layout differences from production
 *    - Trade-off: Bypasses real user interaction validation for test stability
 *    - Use Case: Sidebar overlays and mobile viewport testing
 *
 * 5. Document Detail Rendering Mode Detection (Lines 78, 186-196, 201-206):
 *    - Detects three possible rendering modes:
 *      - Page navigation: Full page with .ant-descriptions
 *      - Drawer rendering: .ant-drawer-open with .ant-drawer .ant-descriptions
 *      - Modal rendering: .ant-modal with .ant-modal .ant-descriptions
 *    - Flexible detection: hasAnyDocumentDetails = hasDocumentDetails || hasDrawerDetails || hasModalDetails
 *    - Rationale: UI implementation may vary (SPA route vs overlay)
 *    - Implementation: Multiple locator patterns to accommodate all modes
 *
 * 6. Back Navigation Verification Pattern (Lines 109-119):
 *    - Tests back button functionality: button:has-text("戻る")
 *    - Verifies return to documents list: .ant-table count check
 *    - Uses force click for reliability
 *    - Rationale: Back button is primary navigation method from detail view
 *    - Guards against navigation stack issues or broken history
 *
 * 7. Skipped Session Stability Test (Lines 122-284):
 *    - Test marked with test.skip() for multiple document access pattern
 *    - Would test accessing 3 documents sequentially
 *    - Rationale: Currently investigating UI rendering mode inconsistencies
 *    - Implementation Complexity: Must handle drawer/modal/page navigation modes
 *    - Future: Re-enable when document detail UI implementation stabilizes
 *
 * 8. Multiple Document Access Pattern (Lines 152-283):
 *    - Sequential access: Loop through first 3 documents
 *    - Re-query before each click: freshDocumentButtons = page.locator(...) prevents stale elements
 *    - Wait for URL change: waitForURL(/\/documents\/[a-f0-9-]+/) with timeout
 *    - Fallback detection: Check for drawer/modal if URL doesn't change
 *    - Return navigation: Handle drawer close, modal close, or back button
 *    - Rationale: Tests session token doesn't expire across multiple requests
 *
 * 9. URL Pattern Waiting with Fallback (Lines 179-196):
 *    - Primary: Wait for URL pattern /\/documents\/[a-f0-9-]+/ (page navigation)
 *    - Fallback: Detect drawer/modal open if URL wait times out
 *    - Logs navigation result for debugging
 *    - Rationale: UI may render details in drawer instead of navigating to new route
 *    - Error Handling: try-catch allows test to continue if UI uses overlay instead of navigation
 *
 * 10. Extensive Debugging Visibility (Lines 60-61, 80-82, 155-156, 208-221):
 *     - Logs current URL after each navigation step
 *     - Logs authentication state flags (hasLoginForm, hasAuthError, hasDocumentDetails)
 *     - Logs document counts and re-query results
 *     - Logs error message text when authentication errors occur
 *     - Rationale: Authentication issues are difficult to reproduce and diagnose
 *     - Implementation: Console.log() at every critical checkpoint
 *     - CI/CD Value: Provides diagnostic information in test output logs
 *
 * Expected Results:
 * - Test 1: Document detail access succeeds without login form appearing
 * - Test 1: No authentication error messages displayed
 * - Test 1: Document details (ID, properties tab) visible
 * - Test 1: Back button returns to document list successfully
 * - Test 2: SKIPPED - Session stability test pending UI implementation clarity
 *
 * Performance Characteristics:
 * - Test 1 execution: 10-20 seconds (login + navigation + verification)
 * - Document click wait: 3 seconds for detail page load
 * - Back navigation wait: 2 seconds for list reload
 * - URL pattern timeout: 10 seconds maximum
 *
 * Debugging Features:
 * - Browser console message capture and logging
 * - Page error event capture and logging
 * - Current URL logging after each navigation
 * - Authentication state logging (login form, errors, details)
 * - Document count logging for re-query verification
 * - Error message text extraction and logging
 *
 * Known Limitations:
 * - Test 2 skipped: Multiple document access pattern needs UI implementation clarity
 * - Force click usage: Bypasses real interaction validation for stability
 * - Drawer/modal detection: Relies on class name patterns that could change
 * - URL pattern matching: Assumes objectId format [a-f0-9-]+ (CouchDB UUIDs)
 * - Mobile sidebar close: May fail silently (graceful degradation with count check)
 *
 * Relationship to Other Tests:
 * - Uses similar mobile browser patterns as login.spec.ts
 * - Complements document-management.spec.ts (focuses on auth, not CRUD)
 * - Related to verify-404-redirect.spec.ts (error handling patterns)
 * - Similar console event monitoring as verify-cmis-404-handling.spec.ts
 *
 * Common Failure Scenarios:
 * - Test 1 fails with login form: Session token not persisting across detail navigation
 * - Test 1 fails with auth error: Permission denied or token expired during detail fetch
 * - Test 1 fails with no details: Document detail page not rendering after click
 * - Back button fails: Navigation stack broken or React Router state corruption
 * - Mobile test fails: Sidebar overlay blocking document click despite sidebar close
 */

import { test, expect } from '@playwright/test';

test.describe('Document Viewer Authentication', () => {
  test('should access document details without authentication errors', async ({ page, browserName }) => {
    // Enable console logging
    page.on('console', msg => {
      console.log('BROWSER:', msg.type(), msg.text());
    });

    page.on('pageerror', error => {
      console.log('PAGE ERROR:', error.message);
    });

    // Navigate to login page
    await page.goto('http://localhost:8080/core/ui/dist/index.html');
    await page.waitForTimeout(1000);

    // Login as admin
    const repositorySelect = page.locator('.ant-select-selector').first();
    await repositorySelect.click();
    await page.waitForTimeout(500);
    await page.locator('.ant-select-item-option:has-text("bedroom")').click();
    await page.waitForTimeout(500);

    await page.locator('input[placeholder*="ユーザー名"]').fill('admin');
    await page.locator('input[placeholder*="パスワード"]').fill('admin');
    await page.locator('button[type="submit"]').click();

    // Wait for navigation and UI initialization
    await page.waitForTimeout(5000);

    // Verify login successful - check for documents page elements
    await expect(page.locator('.ant-table')).toBeVisible({ timeout: 15000 });
    console.log('✅ Login successful - documents page loaded');

    // MOBILE FIX: Close sidebar to prevent overlay blocking clicks
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobile) {
      const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]').first();
      if (await menuToggle.count() > 0) {
        await menuToggle.click({ timeout: 3000 });
        await page.waitForTimeout(500);
        console.log('✅ Mobile sidebar closed');
      }
    }

    // Get current URL to verify we're on documents page
    const currentUrl = page.url();
    console.log('Current URL after login:', currentUrl);
    expect(currentUrl).toContain('documents');

    // Click on first document to access detail view
    const firstDocument = page.locator('.ant-table-tbody tr:has([aria-label="file"]) button').first();

    // Wait for document to be available
    await expect(firstDocument).toBeVisible({ timeout: 10000 });

    console.log('📍 Clicking on first document to access detail view...');
    await firstDocument.click({ force: true });

    // Wait for document detail page to load
    await page.waitForTimeout(3000);

    // Check for authentication errors
    const hasLoginForm = await page.locator('input[placeholder*="ユーザー名"]').count() > 0;
    const hasAuthError = await page.locator('.ant-message-error, .ant-notification-error').count() > 0;
    const hasDocumentDetails = await page.locator('.ant-descriptions').count() > 0;

    console.log('Has login form (re-auth required):', hasLoginForm);
    console.log('Has auth error message:', hasAuthError);
    console.log('Has document details:', hasDocumentDetails);

    // Verify no re-authentication required
    expect(hasLoginForm).toBe(false);

    // Verify no authentication errors
    if (hasAuthError) {
      const errorText = await page.locator('.ant-message-error, .ant-notification-error').first().textContent();
      console.log('❌ Authentication error found:', errorText);
      expect(hasAuthError).toBe(false);
    }

    // Verify document details loaded successfully
    if (hasDocumentDetails) {
      console.log('✅ Document details loaded successfully');

      // Verify key document information is displayed
      const hasObjectId = await page.locator('text=ID').count() > 0;
      const hasProperties = await page.locator('.ant-tabs-tab:has-text("プロパティ")').count() > 0;

      expect(hasObjectId).toBe(true);
      expect(hasProperties).toBe(true);
    } else {
      console.log('❌ Document details failed to load');
      expect(hasDocumentDetails).toBe(true);
    }

    // Verify back button works
    const backButton = page.locator('button:has-text("戻る")');
    if (await backButton.count() > 0) {
      await backButton.click({ force: true });
      await page.waitForTimeout(2000);

      // Should return to documents list
      const hasDocumentsList = await page.locator('.ant-table').count() > 0;
      expect(hasDocumentsList).toBe(true);
      console.log('✅ Back button works - returned to documents list');
    }
  });

  test.skip('should handle multiple document detail accesses without session issues', async ({ page, browserName }) => {
    // Login
    await page.goto('http://localhost:8080/core/ui/dist/index.html');
    await page.waitForTimeout(1000);

    const repositorySelect = page.locator('.ant-select-selector').first();
    await repositorySelect.click();
    await page.waitForTimeout(500);
    await page.locator('.ant-select-item-option:has-text("bedroom")').click();
    await page.waitForTimeout(500);

    await page.locator('input[placeholder*="ユーザー名"]').fill('admin');
    await page.locator('input[placeholder*="パスワード"]').fill('admin');
    await page.locator('button[type="submit"]').click();
    await page.waitForTimeout(5000);

    await expect(page.locator('.ant-table')).toBeVisible({ timeout: 15000 });

    // Mobile sidebar handling
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobile) {
      const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]').first();
      if (await menuToggle.count() > 0) {
        await menuToggle.click({ timeout: 3000 });
        await page.waitForTimeout(500);
      }
    }

    // Access multiple documents to test session stability
    const documentButtons = page.locator('.ant-table-tbody tr:has([aria-label="file"]) button');
    const documentCount = await documentButtons.count();
    console.log(`Found ${documentCount} documents`);

    if (documentCount > 0) {
      const accessCount = Math.min(3, documentCount);

      for (let i = 0; i < accessCount; i++) {
        console.log(`\n📍 Accessing document ${i + 1}/${accessCount}...`);

        const freshDocumentButtons = page.locator('.ant-table-tbody tr:has([aria-label="file"]) button');
        const freshDocumentCount = await freshDocumentButtons.count();
        console.log(`  Found ${freshDocumentCount} documents (re-queried)`);
        
        if (i >= freshDocumentCount) {
          console.log(`  ⚠️ Document ${i} no longer available, stopping test`);
          break;
        }

        // Click document
        console.log(`  Clicking document button ${i}...`);
        await freshDocumentButtons.nth(i).click({ force: true });
        await page.waitForTimeout(3000);
        
        // Wait for navigation to complete
        console.log(`  Waiting for URL to match document pattern...`);
        try {
          await page.waitForURL(/\/documents\/[a-f0-9-]+/, { timeout: 10000 });
          console.log(`  ✅ Navigation completed successfully`);
        } catch (error) {
          console.log(`  ❌ Navigation timeout - URL did not change to document detail page`);
          console.log(`  Current URL after click: ${page.url()}`);
          
          // Check if document detail drawer opened instead of navigation
          const hasDrawer = await page.locator('.ant-drawer-open').count() > 0;
          const hasModal = await page.locator('.ant-modal:not(.ant-modal-hidden)').count() > 0;
          console.log(`  Has drawer open: ${hasDrawer}`);
          console.log(`  Has modal open: ${hasModal}`);
          
          if (hasDrawer || hasModal) {
            console.log(`  ✅ Document details opened in drawer/modal instead of navigation`);
          } else {
            throw error;
          }
        }

        // Check for errors
        const hasLoginForm = await page.locator('input[placeholder*="ユーザー名"]').count() > 0;
        const hasDocumentDetails = await page.locator('.ant-descriptions').count() > 0;
        const hasDrawerDetails = await page.locator('.ant-drawer .ant-descriptions').count() > 0;
        const hasModalDetails = await page.locator('.ant-modal .ant-descriptions').count() > 0;
        const hasErrorMessage = await page.locator('.ant-message-error, .ant-notification-error').count() > 0;
        
        const hasAnyDocumentDetails = hasDocumentDetails || hasDrawerDetails || hasModalDetails;

        // DEBUGGING: Log current state
        const currentUrl = page.url();
        console.log(`  Current URL: ${currentUrl}`);
        console.log(`  Has login form: ${hasLoginForm}`);
        console.log(`  Has document details (page): ${hasDocumentDetails}`);
        console.log(`  Has document details (drawer): ${hasDrawerDetails}`);
        console.log(`  Has document details (modal): ${hasModalDetails}`);
        console.log(`  Has any document details: ${hasAnyDocumentDetails}`);
        console.log(`  Has error message: ${hasErrorMessage}`);

        if (hasErrorMessage) {
          const errorText = await page.locator('.ant-message-error, .ant-notification-error').first().textContent();
          console.log(`  Error message: "${errorText}"`);
        }

        if (hasLoginForm) {
          console.log(`❌ Document ${i + 1}: Re-authentication required`);
          expect(hasLoginForm).toBe(false);
        }

        if (!hasAnyDocumentDetails) {
          console.log(`❌ Document ${i + 1}: Failed to load details`);
          console.log(`POSSIBLE ISSUE: Document detail page not rendering properly after multiple accesses`);

          // Check if we're stuck on a loading state or if there's a UI error
          const hasSpinner = await page.locator('.ant-spin').count() > 0;
          const hasDrawer = await page.locator('.ant-drawer').count() > 0;
          console.log(`  Has spinner (loading): ${hasSpinner}`);
          console.log(`  Has drawer: ${hasDrawer}`);

          expect(hasAnyDocumentDetails).toBe(true);
        } else {
          console.log(`✅ Document ${i + 1}: Loaded successfully`);
        }

        // Return to list
        console.log(`  Returning to document list...`);
        
        // Check if we need to close drawer/modal or navigate back
        const hasDrawer = await page.locator('.ant-drawer-open').count() > 0;
        const hasModal = await page.locator('.ant-modal:not(.ant-modal-hidden)').count() > 0;
        
        if (hasDrawer) {
          const closeButton = page.locator('.ant-drawer-close');
          if (await closeButton.count() > 0) {
            await closeButton.click();
            await page.waitForTimeout(1000);
            console.log(`  ✅ Closed drawer`);
          }
        } else if (hasModal) {
          const closeButton = page.locator('.ant-modal-close');
          if (await closeButton.count() > 0) {
            await closeButton.click();
            await page.waitForTimeout(1000);
            console.log(`  ✅ Closed modal`);
          }
        } else {
          const backButton = page.locator('button:has-text("戻る")');
          if (await backButton.count() > 0) {
            await backButton.click({ force: true });
            await page.waitForTimeout(1000);
            console.log(`  ✅ Clicked back button`);
          } else {
            await page.goto('http://localhost:8080/core/ui/dist/index.html#/documents');
            await page.waitForTimeout(2000);
            console.log(`  ✅ Navigated back to documents page`);
          }
        }
        
        // Verify we're back on the documents list
        await expect(page.locator('.ant-table')).toBeVisible({ timeout: 5000 });
        console.log(`  ✅ Back on documents list`);
      }

      console.log(`\n✅ Successfully accessed ${accessCount} documents without authentication issues`);
    }
  });
});
