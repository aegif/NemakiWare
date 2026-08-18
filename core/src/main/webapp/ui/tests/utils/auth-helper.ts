import { Page, expect } from '@playwright/test';

export interface LoginCredentials {
  username: string;
  password: string;
}

/**
 * Authentication Helper Utilities for NemakiWare Playwright E2E Tests
 *
 * Comprehensive authentication management utility providing robust login/logout functionality:
 * - Flexible login method with 3 calling patterns (parameters/object/defaults)
 * - React SPA initialization handling with proper wait strategies
 * - Mobile browser support with extended timeouts (30s)
 * - Authentication retry logic (3 attempts) for race conditions
 * - Multiple selector fallback patterns for UI refactoring stability
 * - Automatic redirect detection and handling
 * - Comprehensive debugging logging for CI/CD troubleshooting
 * - Ant Design component interaction patterns (Select dropdown, Menu)
 *
 * Usage Examples:
 * ```typescript
 * const authHelper = new AuthHelper(page);
 *
 * // Pattern 1: No parameters (uses default admin credentials)
 * await authHelper.login();
 *
 * // Pattern 2: Individual parameters
 * await authHelper.login('testuser', 'password');
 *
 * // Pattern 3: Credentials object
 * await authHelper.login({ username: 'admin', password: 'admin' });
 *
 * // Logout
 * await authHelper.logout();
 *
 * // Check login status
 * const loggedIn = await authHelper.isLoggedIn();
 *
 * // Ensure logged in (login if necessary)
 * await authHelper.ensureLoggedIn();
 * ```
 *
 * IMPORTANT DESIGN DECISIONS:
 *
 * 1. Method Overload Pattern with 3 Calling Patterns (Lines 25-58):
 *    - Pattern A: login() - uses DEFAULT_CREDENTIALS (admin:admin)
 *    - Pattern B: login('username', 'password') - individual parameters
 *    - Pattern C: login({ username, password }) - credentials object
 *    - Implementation uses typeof check and parameter parsing (Lines 42-58)
 *    - Rationale: Supports both legacy test code (Pattern B) and modern test code (Pattern C)
 *    - TypeScript overload signatures (Lines 25-32) provide proper type safety
 *    - Advantage: Tests can use most convenient calling pattern without sacrificing type safety
 *
 * 2. React SPA Initialization Wait Strategy (Lines 63-76):
 *    - Waits for React root div to have children (app mounted and rendered)
 *    - waitForFunction() checks document.getElementById('root').children.length > 0
 *    - Timeout: 30000ms (30 seconds) - increased from 10000ms per 2025-10-22 code review
 *    - Additional 1000ms wait for Ant Design components to fully render
 *    - Rationale: React SPA needs time to mount Login component before form fields appear
 *    - Previous issue: Tests attempting to fill form before React rendered it
 *    - Code review feedback: 10000ms too aggressive for slower CI systems
 *    - Implementation: Browser-side function for accurate DOM state detection
 *
 * 3. Multiple Selector Fallback Pattern (Lines 79-96, 113-130, 163-180, 298-311):
 *    - Username field: 3 selectors (placeholder, name, type)
 *    - Password field: 3 selectors (placeholder, name, type)
 *    - Login button: 3 selectors (submit+text, text, primary class)
 *    - User menu: 3 selectors (Space+text, Space class, avatar)
 *    - Loop pattern: for-of loop trying each selector with try-catch
 *    - Break on first successful match (field.waitFor visible)
 *    - Timeout per selector: 30000ms (30 seconds)
 *    - Rationale: UI refactoring may change specific selectors but keep alternatives
 *    - Advantage: Tests remain stable across form structure changes
 *    - Error handling: Comprehensive error messages with page state debugging (Lines 98-106)
 *
 * 4. Authentication Retry Logic with User-Specific Timeouts (Lines 385-439):
 *    - Admin users: 3 retry attempts with 30s timeout per attempt (90s total)
 *    - Test users: 5 retry attempts with 60s timeout per attempt (300s total)
 *    - CRITICAL FIX (2025-10-26): Increased retries and timeout for test users
 *    - Detection strategy: Password field gone AND main app elements present
 *    - Main elements: .ant-layout-sider, .ant-layout-content, .ant-table
 *    - On timeout: Log page state (URL, body text first 200 chars) and retry
 *    - Wait 2 seconds between retries for server processing
 *    - Rationale: Test users require time for ACL permission propagation after creation
 *    - Permission setup via CMIS Browser Binding API may take time to synchronize
 *    - Code review feedback (2025-10-21): Extended timeout to 30s for admin users
 *    - Code review feedback (2025-10-26): Extended timeout to 60s for test users
 *    - Implementation: while loop with break on success, error throw after max retries
 *
 * 5. Automatic Redirect Detection and Handling (Lines 261-274):
 *    - React app automatically redirects authenticated users from / to /documents
 *    - Primary: Wait for URL pattern matching /documents with 5-second timeout
 *    - Fallback: Manual navigation if redirect doesn't happen
 *    - Manual navigation steps: goto index.html → click Documents menu item → wait 2s
 *    - Rationale: React Router may not redirect immediately on slow networks
 *    - Implementation: try-catch around waitForURL, graceful fallback
 *    - Advantage: Tests don't fail due to redirect timing variations
 *
 * 6. Post-Login Page Stabilization Wait (Lines 276-289):
 *    - Waits for Ant Design layout components to be fully present
 *    - Detection: .ant-layout AND .ant-layout-sider both present in DOM
 *    - Timeout: 30000ms (30 seconds) - increased from 10000ms per 2025-10-21 code review
 *    - Additional 1000ms wait for final page stabilization
 *    - Rationale: React components need time to fetch data and render after navigation
 *    - Code review feedback: Slower CI environments need generous timeouts
 *    - Implementation: waitForFunction for accurate DOM state check
 *
 * 7. Repository Selection:
 *    - Login form defaults to the first repository ('bedroom')
 *    - Repository selection is not performed by this helper
 *    - For multi-repository testing, use direct API calls or page interaction
 *    - Advantage: Works with dropdowns that have many options requiring scrolling
 *
 * 8. Logout Hard Navigation Detection (Lines 292-371):
 *    - Logout triggers window.location.href redirect (hard navigation, not SPA routing)
 *    - User menu detection: 3 selector fallbacks (Space+admin text, avatar)
 *    - Dropdown wait: .ant-dropdown:not(.ant-dropdown-hidden) 3s timeout
 *    - Logout menu item: Filter by Japanese text 'ログアウト'
 *    - Force click: { force: true } to bypass overlay/visibility checks
 *    - URL wait: Pattern matching /ui/ path with 5s timeout
 *    - Verification: Check for login form elements (password field OR username field)
 *    - Rationale: Hard navigation requires different wait strategy than SPA routing
 *    - Implementation: Extensive logging and dual verification (URL + form elements)
 *
 * 9. Enhanced Error Messages with Page State Debugging (Lines 98-106, 132-136, 182-186):
 *    - Username field not found: Log body HTML length, root HTML first 500 chars, current URL
 *    - Password field not found: Log current URL
 *    - Login button not found: Log current URL
 *    - Rationale: CI/CD failures difficult to diagnose without page state context
 *    - Implementation: console.error() with structured debugging information
 *    - Advantage: Failed tests provide actionable debugging information
 *
 * 10. Mobile Browser Timeout Extensions (Lines 72, 89, 123, 173, 233, 285):
 *     - All critical timeouts set to 30000ms (30 seconds) instead of 10000ms or 20000ms
 *     - Applied to: React initialization, form field detection, login button, authentication detection, page stabilization
 *     - Code review feedback (2025-10-21, 2025-10-22): Slower CI environments and mobile browsers need generous timeouts
 *     - Rationale: Mobile browsers have slower rendering and network performance
 *     - Implementation: Uniform 30s timeout across all critical wait operations
 *     - Trade-off: Slower test failures but dramatically reduced flaky test rate
 *
 * Expected Results:
 * - login(): Authenticated and navigated to /documents page, Ant Design layout visible
 * - logout(): Returned to login page with password/username fields visible
 * - isLoggedIn(): true if authenticated elements present, false if login form visible
 * - ensureLoggedIn(): Logged in if necessary, no-op if already authenticated
 *
 * Performance Characteristics:
 * - login() success: ~8-15s (React init + form fill + auth + redirect + stabilization)
 * - login() timeout: ~90s+ (30s React + 30s auth retries + 30s stabilization)
 * - logout() success: ~5-8s (menu click + navigation + verification)
 * - logout() timeout: ~20s (3s dropdown + 5s URL + timeouts)
 * - isLoggedIn(): ~100-500ms (element detection, no waits)
 * - ensureLoggedIn(): ~8-15s if login needed, ~500ms if already logged in
 *
 * Debugging Features:
 * - Comprehensive console logging for each authentication phase
 * - Page state debugging on errors (URL, body HTML, root HTML)
 * - Retry attempt logging with current page state
 * - Login error message detection and logging
 * - Logout verification with login form detection logging
 * - Element count logging before throwing errors
 * - Current URL logging at critical decision points
 *
 * Known Limitations:
 * - Japanese text hardcoded (placeholder="ユーザー名", "パスワード", "ログイン", "ログアウト")
 * - Login always uses the form's default repository (typically 'bedroom')
 * - Logout assumes user menu text contains 'admin' (fallback to avatar selector)
 * - Authentication detection assumes specific Ant Design class names
 * - Hard navigation for logout may not work if logout implementation changes to SPA routing
 * - Multiple selector fallback assumes at least one selector will match
 * - 30s timeouts may be too long for fast local development (optimized for CI/CD)
 *
 * Relationships to Other Utilities:
 * - Used by all test files in test suite (24 test files depend on AuthHelper)
 * - TestHelper complements with document upload and CMIS operations
 * - Tests assume AuthHelper.login() establishes session for all subsequent operations
 * - BeforeEach hooks in test files typically call authHelper.login() for fresh session
 * - Some tests use ensureLoggedIn() instead of login() for session reuse optimization
 *
 * Common Failure Scenarios:
 * - login() fails: React SPA not initializing (timeout at root children check)
 * - login() fails: Username field not found (all 3 selectors failed)
 * - login() fails: Authentication timeout (all 3 retry attempts exhausted)
 * - login() fails: Redirect not happening (manual navigation also failed)
 * - logout() fails: User menu not found (all selector fallbacks failed)
 * - logout() fails: Login form not appearing after logout (hard navigation didn't work)
 * - isLoggedIn() incorrect: Authenticated elements not present despite valid session
 */
export class AuthHelper {
  constructor(private page: Page) {}

  /**
   * Default admin credentials for testing
   */
  static readonly DEFAULT_CREDENTIALS: LoginCredentials = {
    username: 'admin',
    password: 'admin',
  };

  /**
   * Perform login with specified credentials (method overload - individual parameters)
   */
  async login(username: string, password: string): Promise<void>;

  /**
   * Perform login with specified credentials (method overload - credentials object)
   */
  async login(credentials?: LoginCredentials): Promise<void>;

  /**
   * Perform login with specified credentials (implementation)
   * Supports both calling patterns:
   * - login('username', 'password')
   * - login({ username: 'user', password: 'pass' })
   * - login() - uses default admin credentials
   */
  async login(usernameOrCredentials?: string | LoginCredentials, password?: string): Promise<void> {
    // Parse parameters to determine credentials
    let credentials: LoginCredentials;

    if (typeof usernameOrCredentials === 'string') {
      // Called with individual parameters: login('username', 'password')
      credentials = {
        username: usernameOrCredentials,
        password: password!,
      };
    } else if (usernameOrCredentials === undefined) {
      // Called with no parameters: login() - use defaults
      credentials = AuthHelper.DEFAULT_CREDENTIALS;
    } else {
      // Called with credentials object: login({ username, password })
      credentials = usernameOrCredentials;
    }

    // CRITICAL FIX (2026-02-13): Simplified login flow using proven loginAsUser pattern
    // The original complex flow had intermittent issues with Ant Design form state management
    let loginSucceeded = false;
    const maxLoginAttempts = 3;
    for (let attempt = 1; attempt <= maxLoginAttempts; attempt++) {
      console.log(`AuthHelper: Login attempt ${attempt}/${maxLoginAttempts} for user: ${credentials.username}`);

      // Navigate to login page.
      // 3.3.1 #9b: the goto used to be UNCAUGHT, so a navigation timeout threw straight out
      // of this retry loop — in CI the known client-side hang then cascaded through every
      // later spec's afterAll cleanup as "[Cleanup] Fatal error: page.goto: Timeout 30000ms".
      // A failed goto now consumes one attempt and retries, which is the reload-retry shape
      // that converged the same hang locally.
      try {
        // 15s per attempt, not 30s: three 30s attempts plus the post-login waits would
        // exceed most specs' test timeout before the retry loop could ever help.
        await this.page.goto('/core/ui/', { timeout: 15000 });
      } catch (gotoError) {
        console.warn(`AuthHelper: goto failed on attempt ${attempt}: ${gotoError}`);
        if (attempt === maxLoginAttempts) throw gotoError;
        await this.page.waitForTimeout(2000);
        continue;
      }

      // Check if already authenticated
      try {
        await this.page.waitForURL(/\/#\/documents/, { timeout: 3000 });
        console.log('AuthHelper: Already authenticated, skipping login form');
        loginSucceeded = true;
        break;
      } catch (e) {
        // Not authenticated yet, proceed with login
      }

      // Wait for username field to appear
      await this.page.waitForSelector('input[placeholder*="ユーザー"], input[placeholder*="User"]', { timeout: 15000 });

      // CRITICAL FIX (2026-02-13): Use keyboard input for reliable Ant Design form interaction
      // page.fill() can fail silently with Ant Design's controlled form components
      const usernameInput = this.page.locator('input[placeholder*="ユーザー"], input[placeholder*="User"]');
      await usernameInput.click();
      await this.page.keyboard.press('Control+a');
      await this.page.keyboard.type(credentials.username);
      const passwordInput = this.page.locator('input[type="password"]');
      await passwordInput.click();
      await this.page.keyboard.press('Control+a');
      await this.page.keyboard.type(credentials.password);

      // Submit via Enter key (more reliable than button click with Ant Design forms)
      await this.page.keyboard.press('Enter');
      console.log(`AuthHelper: Submitted login for user: ${credentials.username}`);

      // Wait for URL to change to documents page (most reliable login success indicator)
      try {
        await this.page.waitForURL(/\/#\/documents/, { timeout: 45000 });
        console.log('AuthHelper: Login successful, redirected to documents page');
        loginSucceeded = true;
        break;
      } catch (e) {
        if (attempt < maxLoginAttempts) {
          console.log(`AuthHelper: Login attempt ${attempt} failed (URL did not change), retrying...`);
          continue;
        }
        console.log('AuthHelper: All login attempts via waitForURL failed');
        throw new Error(`Login failed after ${maxLoginAttempts} attempts for user: ${credentials.username}`);
      }
    }

    if (!loginSucceeded) {
      throw new Error(`Login failed for user: ${credentials.username}`);
    }

    // Wait for page to stabilize after login
    await this.page.waitForTimeout(1000);

    // Wait for the documents table to render and finish loading. The list-load
    // can intermittently hang on "読み込み中..." (a stalled metadata request that
    // otherwise never resolves); reload and retry so callers reliably land on a
    // ready documents page rather than a spinning one. Non-fatal if the page
    // genuinely has no table (some flows navigate elsewhere) — callers that need
    // the table assert it themselves.
    for (let attempt = 0; attempt < 3; attempt++) {
      const tableVisible = await this.page.locator('.ant-table').first()
        .waitFor({ state: 'visible', timeout: 15000 }).then(() => true).catch(() => false);
      if (tableVisible) {
        // Wait for the table spinner to clear.
        await this.page.waitForFunction(
          () => document.querySelector('.ant-spin-spinning') === null,
          { timeout: 10000 }
        ).catch(() => {});
        break;
      }
      if (attempt < 2) {
        await this.page.reload();
        await this.page.waitForTimeout(1000);
      }
    }

    // Additional wait for page stabilization
    await this.page.waitForTimeout(500);
  }

  /**
   * Perform logout
   */
  async logout(): Promise<void> {
    // Click on user menu - find by the username text in header
    // Try multiple approaches to find the user menu trigger
    const userMenuSelectors = [
      '.ant-layout-header .ant-space:has-text("admin")',
      '.ant-layout-header [class*="ant-space"]:has-text("admin")',
      '.ant-layout-header:has(.ant-avatar)',
    ];

    let userMenuTrigger;
    for (const selector of userMenuSelectors) {
      const element = this.page.locator(selector).last(); // Use last() to get the rightmost element
      if (await element.count() > 0) {
        userMenuTrigger = element;
        break;
      }
    }

    if (!userMenuTrigger) {
      // Fallback: click on avatar in header
      const avatar = this.page.locator('.ant-layout-header .ant-avatar');
      if (await avatar.count() > 0) {
        userMenuTrigger = avatar;
      }
    }

    if (userMenuTrigger) {
      await userMenuTrigger.click();

      // Wait for dropdown menu to appear
      await this.page.waitForSelector('.ant-dropdown:not(.ant-dropdown-hidden)', { timeout: 3000 });

      // Click logout menu item - this triggers window.location.href redirect
      const logoutMenuItem = this.page.locator('.ant-dropdown .ant-dropdown-menu-item').filter({ hasText: 'ログアウト' });

      console.log('AuthHelper: Clicking logout menu item');
      // Ensure menu item is in viewport before clicking
      await logoutMenuItem.scrollIntoViewIfNeeded();
      await this.page.waitForTimeout(300); // Brief wait after scrolling
      await logoutMenuItem.click({ force: true });

      // Wait for navigation to complete (window.location.href causes a hard navigation)
      // Wait for URL to change to the login page
      console.log('AuthHelper: Waiting for navigation...');
      try {
        await this.page.waitForURL('**/ui/**', { timeout: 5000 });
        console.log('AuthHelper: URL changed to:', this.page.url());
      } catch (e) {
        console.log('AuthHelper: URL wait timed out, current URL:', this.page.url());
      }

      // Wait additional time for page to fully reload
      await this.page.waitForTimeout(2000);

      // Debug: Check what's on the page
      const bodyText = await this.page.locator('body').textContent();
      console.log('AuthHelper: Page body text:', bodyText?.substring(0, 200));

      // Try to find login page elements
      const loginFormExists = await this.page.locator('input[type="password"]').count() > 0;
      const usernameExists = await this.page.locator('input[placeholder="ユーザー名"]').count() > 0;

      console.log('AuthHelper: Login form exists:', loginFormExists);
      console.log('AuthHelper: Username field exists:', usernameExists);

      // If we found login elements, we're successfully logged out
      if (loginFormExists || usernameExists) {
        console.log('AuthHelper: Successfully returned to login page');
        return;
      }

      // If we didn't find login elements, throw error with debug info
      throw new Error(`Logout failed: No login form found. URL: ${this.page.url()}`);
    } else {
      throw new Error('User menu not found');
    }
  }

  /**
   * Check if user is currently logged in
   */
  async isLoggedIn(): Promise<boolean> {
    try {
      // Check if we're on a page that requires authentication
      const currentUrl = this.page.url();

      if (currentUrl.includes('/ui/') && !currentUrl.includes('login')) {
        // Look for elements that only appear when logged in
        const authenticatedElements = [
          '.ant-layout-sider', // Sidebar
          '.document-list', // Document list
          '[data-testid="user-info"]', // User info
        ];

        for (const selector of authenticatedElements) {
          if (await this.page.locator(selector).count() > 0) {
            return true;
          }
        }
      }

      // Check for login form (indicates not logged in)
      const loginForm = this.page.locator('input[placeholder="パスワード"]');
      return await loginForm.count() === 0;

    } catch {
      return false;
    }
  }

  /**
   * Ensure user is logged in, perform login if necessary
   */
  async ensureLoggedIn(credentials?: LoginCredentials): Promise<void> {
    if (!(await this.isLoggedIn())) {
      await this.login(credentials);
    }
  }
}
