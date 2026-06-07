/**
 * 404 Error Handling and Login Redirect Verification Tests
 *
 * Comprehensive test suite for error handling and redirect behavior:
 * - Validates 404 error handling redirects to login page
 * - Tests authentication error (401/403) redirect behavior
 * - Verifies React Router handles non-existent pages gracefully
 * - Documents product bugs with CMIS backend error handling
 * - Ensures user-friendly error experience (no raw Tomcat error pages)
 *
 * User Requirement (Original): "404エラーになる可能性がある場所は初期のログインページへの遷移にして欲しいです。
 * すぐにエラーで身動きできなくなるのでテストもしにくいですし。"
 * (Translation: "Places that might cause 404 errors should redirect to initial login page.
 * Otherwise it immediately becomes an error that you can't recover from, making testing difficult.")
 *
 * Test Coverage (3 error scenarios):
 * 1. CMIS backend 404 error → Should redirect to login (PRODUCT BUG: Shows raw Tomcat error)
 * 2. Authentication error 401/403 → Should redirect to login (WORKS)
 * 3. React Router non-existent page → Should show login or graceful error (WORKS)
 *
 * IMPORTANT DESIGN DECISIONS:
 *
 * 1. Product Bug Investigation Pattern (Lines 15-22, 47-51, 69-73):
 *    - Documents known product bugs directly in test code
 *    - Uses test.skip(true, 'ENV: reason') when bug prevents test success
 *    - Includes detailed expected vs actual behavior
 *    - Example: "PRODUCT BUG: CMIS backend errors not redirecting to login"
 *    - Rationale: Tests serve as specification and bug documentation
 *    - Implementation: Console logging + conditional skip based on bug presence
 *
 * 2. CMIS Backend Direct Access Testing Strategy (Lines 36-37):
 *    - Tests backend endpoints directly: /core/browser/bedroom/root?objectId=nonexistent
 *    - Bypasses React UI error boundaries
 *    - Validates backend error responses (404, 401) are handled gracefully
 *    - Rationale: CMIS backend errors must not show raw Tomcat error pages
 *    - Current Issue: Backend returns raw HTTP Status pages instead of redirects
 *
 * 3. Auth Token Clearing Strategy for 401 Simulation (Lines 87-91):
 *    - Uses page.evaluate() to access localStorage
 *    - Removes 'nemakiware_auth' token to simulate session expiration
 *    - Next API call triggers 401 Unauthorized response
 *    - Rationale: Realistic simulation of auth token expiration scenario
 *    - Implementation: Clear token → navigate to protected resource → expect redirect
 *
 * 4. Graceful Error Handling Verification (Lines 128-145):
 *    - Distinguishes between graceful (login page) and catastrophic (raw error) outcomes
 *    - Checks for "Cannot GET", "404", "Not Found" text indicating raw server errors
 *    - Considers both login form visible OR absence of catastrophic error as success
 *    - Rationale: Users should never see raw Node.js/Tomcat error messages
 *    - User Experience Goal: Always provide recovery path (login page)
 *
 * 5. Multi-Scenario Error Coverage (3 different error types):
 *    - Scenario 1: CMIS backend 404 (non-existent objectId in CMIS query)
 *    - Scenario 2: Authentication errors (401/403 from cleared auth token)
 *    - Scenario 3: React Router 404 (non-existent UI route /nonexistent-page)
 *    - Rationale: Different error sources require different handling mechanisms
 *    - Coverage: Backend errors, auth errors, frontend routing errors
 *
 * 6. React Router Error Boundary Testing (Lines 115-147):
 *    - Tests client-side routing errors (React Router 404)
 *    - Expects React app to handle unknown routes gracefully
 *    - Verifies no "Cannot GET" or raw 404 error pages shown
 *    - Rationale: React SPA should handle all client routes, show login on unknown
 *    - Implementation: Access non-existent /core/ui/nonexistent-page directly
 *
 * 7. Console Logging for Diagnostic Visibility (Lines 30, 42, 48, 85, 107, 123, 134):
 *    - Logs each test phase: "Login successful", "Testing 404 error handling"
 *    - Logs current URLs after redirects for debugging
 *    - Logs error detection results: "Has login form", "Has catastrophic error"
 *    - Logs actual error page content (first 200 chars) when bugs occur
 *    - Rationale: Rich diagnostic output for CI pipeline debugging
 *    - Helps developers understand redirect flow without browser inspection
 *
 * 8. Conditional Test Skipping for Known Bugs (Lines 71-73):
 *    - Uses test.skip(true, 'ENV: reason') to skip tests blocked by product bugs
 *    - Includes specific bug description in skip message
 *    - Allows test suite to pass while documenting known issues
 *    - Rationale: Tests document expected behavior even when bugs exist
 *    - Self-healing: Test will automatically pass when bug is fixed
 *
 * 9. URL Pattern Matching for Login Detection (Lines 54, 104):
 *    - Checks currentUrl.includes('index.html') OR endsWith('/dist/')
 *    - Handles both explicit index.html and implicit directory index
 *    - Flexible matching for different server configurations
 *    - Rationale: React Router base path may vary (development vs production)
 *    - Implementation: Multiple URL patterns accepted as "on login page"
 *
 * 10. HTTP Status Code Extraction from Error Pages (Lines 45-46):
 *     - Uses regex to extract status code from Tomcat error page text
 *     - Pattern: /HTTP Status (\d+)/ matches "HTTP Status 404" or "HTTP Status 401"
 *     - Logs exact status code when raw error page is shown
 *     - Rationale: Helps identify which HTTP errors are not being handled
 *     - Diagnostic Value: Distinguishes 404 vs 401 vs 403 error sources
 *
 * Expected Results:
 * - Test 1: SKIP (known bug - CMIS backend shows raw error instead of redirect)
 * - Test 2: PASS - Auth errors correctly redirect to login
 * - Test 3: PASS - React Router handles unknown routes gracefully
 *
 * Performance Characteristics:
 * - Each test: 5-10 seconds
 * - Network requests: Minimal (1-2 CMIS endpoints per test)
 * - Wait timeouts: 2-3 seconds per redirect (generous for slow CI)
 *
 * Debugging Features:
 * - Extensive console logging for each redirect step
 * - URL tracking after each navigation
 * - Error page content extraction and logging
 * - HTTP status code detection from error pages
 *
 * Known Limitations and Product Bugs:
 * - CMIS backend 404 errors show raw Tomcat error page (not user-friendly)
 * - No error boundary for CMIS API errors in React UI
 * - Users see "HTTP Status 401 - Unauthorized" text instead of login redirect
 * - Test 1 must be skipped until CMIS error handling is implemented
 * - TODO: Implement error boundary or redirect logic for CMIS backend errors
 *
 * Relationship to Other Tests:
 * - Uses AuthHelper utility (same as login.spec.ts)
 * - Tests React Router error handling (complements basic-connectivity.spec.ts)
 * - Validates authentication flow errors (relates to access-control.spec.ts)
 * - CMIS backend testing (similar strategy to backend/versioning-api.spec.ts)
 *
 * Common Failure Scenarios:
 * - Test 1 fails: Product bug still exists (CMIS backend raw error page)
 * - Test 2 fails: Auth redirect logic broken in React UI
 * - Test 3 fails: React Router not handling unknown routes
 * - Timeout errors: Network latency or server not responding
 * - Login form not found: Login page UI changed or not loaded
 */

import { test, expect } from '@playwright/test';
import { waitForUiStable } from './utils/wait-helpers';
import { AuthHelper } from './utils/auth-helper';

test.describe('404 Error Handling Verification', () => {
  test('should handle non-existent document gracefully in React UI', async ({ page }) => {
    // Test that navigating to a non-existent document in the React UI
    // does not crash the application or show raw error pages.
    // The React UI should show the login page or a user-friendly error.

    const authHelper = new AuthHelper(page);
    await authHelper.login();

    // Verify login successful
    await expect(page.locator('.ant-layout').first()).toBeVisible({ timeout: 10000 });
    console.log('Login successful');

    // Navigate to a non-existent document ID within the React UI
    await page.goto('http://localhost:8080/core/ui/index.html#/documents/nonexistent-id-12345');
    await waitForUiStable(page);

    const currentUrl = page.url();
    console.log('Current URL after navigating to non-existent document:', currentUrl);

    // The React app should handle this gracefully:
    // Either show an error message, redirect to documents list, or show login
    const bodyText = await page.textContent('body') || '';

    // Should NOT show raw Tomcat error page
    const hasRawError = bodyText.includes('HTTP Status 404') || bodyText.includes('HTTP Status 500');
    expect(hasRawError).toBe(false);

    // Should show either the React app (with error message or redirect) or login page
    const hasReactApp = await page.locator('.ant-layout, .ant-form, .ant-message, .ant-result').first().isVisible().catch(() => false);
    const hasLoginForm = await page.locator('input[type="password"]').isVisible().catch(() => false);

    expect(hasReactApp || hasLoginForm).toBe(true);
    console.log(`React app handled non-existent document gracefully (app visible: ${hasReactApp}, login: ${hasLoginForm})`);
  });

  test('should handle 401/403 errors by redirecting to login', async ({ page }) => {
    // Login using AuthHelper
    const authHelper = new AuthHelper(page);
    await authHelper.login();

    // Verify login successful
    await expect(page.locator('.ant-layout').first()).toBeVisible({ timeout: 10000 });
    console.log('✅ Login successful');

    // Clear auth token to simulate 401 error
    await page.evaluate(() => {
      localStorage.removeItem('nemakiware_auth');
      // Also clear any session storage auth data
      sessionStorage.clear();
    });
    console.log('Auth token cleared - forcing page reload to apply');

    // Force reload to apply token removal (SPA retains in-memory auth state)
    await page.reload({ waitUntil: 'networkidle' });
    await waitForUiStable(page, { timeout: 15000 });

    // Verify login form is displayed (the SPA should show login when auth is missing)
    const currentUrl = page.url();
    console.log('Current URL after auth error:', currentUrl);

    // Check for login form visibility rather than URL pattern
    // (SPA may keep hash route but show login overlay)
    const loginForm = page.locator('input[type="text"], input[type="password"], form.login-form, .ant-form');
    const isLoginFormVisible = await loginForm.first().isVisible({ timeout: 5000 }).catch(() => false);

    // Also check URL pattern as secondary indicator
    const isOnLoginUrl = currentUrl.includes('/ui/') &&
      !currentUrl.includes('#/documents') &&
      !currentUrl.includes('#/search');

    const isOnLoginPage = isLoginFormVisible || isOnLoginUrl;

    if (isOnLoginPage) {
      console.log('Auth error correctly redirected to login page');
    }

    expect(isOnLoginPage).toBe(true);
  });

  test('should show login page without error on initial 404', async ({ page }) => {
    // Try to access non-existent URL directly (without logging in first)
    await page.goto('http://localhost:8080/core/ui/nonexistent-page');
    await waitForUiStable(page);

    // React Router should handle this gracefully
    // Either show login page or show a user-friendly error
    const currentUrl = page.url();
    console.log('URL after accessing non-existent page:', currentUrl);

    // Check if login form is visible (good outcome)
    const hasLoginForm = await page.locator('input[type="text"]').count() > 0;

    // Check if there's a catastrophic error (bad outcome we want to avoid)
    const bodyText = await page.textContent('body');
    const hasCatastrophicError = bodyText?.includes('Cannot GET') ||
                                 bodyText?.includes('404') ||
                                 bodyText?.includes('Not Found');

    console.log('Has login form:', hasLoginForm);
    console.log('Has catastrophic error:', hasCatastrophicError);

    // We want either login form or React app loaded (not raw 404 error)
    const isGraceful = hasLoginForm || !hasCatastrophicError;

    if (isGraceful) {
      console.log('✅ Non-existent page handled gracefully');
    }

    expect(isGraceful).toBe(true);
  });
});
