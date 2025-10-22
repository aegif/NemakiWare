import { Page, expect } from '@playwright/test';

export interface LoginCredentials {
  username: string;
  password: string;
  repository?: string;
}

/**
 * Authentication helper utilities for NemakiWare UI tests
 */
export class AuthHelper {
  constructor(private page: Page) {}

  /**
   * Default admin credentials for testing
   */
  static readonly DEFAULT_CREDENTIALS: LoginCredentials = {
    username: 'admin',
    password: 'admin',
    repository: 'bedroom',
  };

  /**
   * Perform login with specified credentials (method overload - individual parameters)
   */
  async login(username: string, password: string, repository?: string): Promise<void>;

  /**
   * Perform login with specified credentials (method overload - credentials object)
   */
  async login(credentials?: LoginCredentials): Promise<void>;

  /**
   * Perform login with specified credentials (implementation)
   * Supports both calling patterns:
   * - login('username', 'password', 'repository')
   * - login({ username: 'user', password: 'pass', repository: 'repo' })
   * - login() - uses default admin credentials
   */
  async login(usernameOrCredentials?: string | LoginCredentials, password?: string, repository?: string): Promise<void> {
    // Parse parameters to determine credentials
    let credentials: LoginCredentials;

    if (typeof usernameOrCredentials === 'string') {
      // Called with individual parameters: login('username', 'password', 'repository')
      credentials = {
        username: usernameOrCredentials,
        password: password!,
        repository: repository || 'bedroom',
      };
    } else if (usernameOrCredentials === undefined) {
      // Called with no parameters: login() - use defaults
      credentials = AuthHelper.DEFAULT_CREDENTIALS;
    } else {
      // Called with credentials object: login({ username, password, repository })
      credentials = usernameOrCredentials;
    }

    // Navigate to login page
    await this.page.goto('/core/ui/dist/index.html', { waitUntil: 'networkidle' });

    // CRITICAL FIX (2025-10-22): Wait for React SPA to initialize before looking for form fields
    // The React app needs time to mount and render the Login component
    // Timeout increased to 30000ms per code review - 10000ms was too aggressive for slower systems
    await this.page.waitForFunction(
      () => {
        // Check if React root div has children (app is rendered)
        const root = document.getElementById('root');
        return root && root.children.length > 0;
      },
      { timeout: 30000 }
    );

    // Additional wait for Ant Design components to fully render
    await this.page.waitForTimeout(1000);

    // Wait for login form to be visible - try multiple selectors
    const usernameFieldSelectors = [
      'input[placeholder="ユーザー名"]',
      'input[name="username"]',
      'input[type="text"]',
    ];

    let usernameField;
    for (const selector of usernameFieldSelectors) {
      const field = this.page.locator(selector).first();
      try {
        await field.waitFor({ state: 'visible', timeout: 10000 });
        usernameField = field;
        break;
      } catch (e) {
        // Try next selector
        continue;
      }
    }

    if (!usernameField) {
      // Enhanced error message with page state debugging
      const bodyHtml = await this.page.locator('body').innerHTML();
      const rootHtml = await this.page.locator('#root').innerHTML().catch(() => 'No #root element');
      console.error('AuthHelper: Username field not found');
      console.error('AuthHelper: Current URL:', this.page.url());
      console.error('AuthHelper: Body HTML length:', bodyHtml?.length);
      console.error('AuthHelper: Root HTML:', rootHtml?.substring(0, 500));
      throw new Error('Username field not found - Login page may not have loaded properly');
    }

    // Fill username
    await usernameField.fill(credentials.username);

    // Fill password - try multiple selectors
    const passwordFieldSelectors = [
      'input[placeholder="パスワード"]',
      'input[name="password"]',
      'input[type="password"]',
    ];

    let passwordField;
    for (const selector of passwordFieldSelectors) {
      const field = this.page.locator(selector).first();
      try {
        await field.waitFor({ state: 'visible', timeout: 10000 });
        passwordField = field;
        break;
      } catch (e) {
        // Try next selector
        continue;
      }
    }

    if (!passwordField) {
      console.error('AuthHelper: Password field not found');
      console.error('AuthHelper: Current URL:', this.page.url());
      throw new Error('Password field not found - Login page may not have loaded properly');
    }

    await passwordField.fill(credentials.password);

    // Select repository if dropdown exists
    if (credentials.repository) {
      const repositorySelect = this.page.locator('.ant-select').first();
      if (await repositorySelect.count() > 0) {
        await repositorySelect.click();

        // Wait for dropdown to be fully opened
        await this.page.waitForSelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)', { timeout: 5000 });

        // Find the option and scroll it into view before clicking
        const option = this.page.locator('.ant-select-dropdown .ant-select-item-option').filter({ hasText: credentials.repository }).first();

        // Wait for the option to be present
        await option.waitFor({ state: 'attached', timeout: 3000 });

        // Scroll into view and click
        await option.scrollIntoViewIfNeeded();
        await this.page.waitForTimeout(300); // Brief wait after scrolling
        await option.click();
      }
    }

    // Click login button - try multiple selectors
    const loginButtonSelectors = [
      'button[type="submit"]:has-text("ログイン")',
      'button:has-text("ログイン")',
      'button.ant-btn-primary',
    ];

    let loginButton;
    for (const selector of loginButtonSelectors) {
      const button = this.page.locator(selector).first();
      try {
        await button.waitFor({ state: 'visible', timeout: 10000 });
        loginButton = button;
        break;
      } catch (e) {
        // Try next selector
        continue;
      }
    }

    if (!loginButton) {
      console.error('AuthHelper: Login button not found');
      console.error('AuthHelper: Current URL:', this.page.url());
      throw new Error('Login button not found - Login page may not have loaded properly');
    }

    await loginButton.click();
    console.log(`AuthHelper: Clicked login button for user: ${credentials.username}`);

    // Wait a moment for the login request to process
    await this.page.waitForTimeout(1000);
    console.log('AuthHelper: Waiting for authentication...');

    // Check for login error messages
    const errorMessage = await this.page.locator('body').textContent();
    if (errorMessage?.includes('ログインに失敗しました')) {
      console.log('AuthHelper: Login failed message detected on page');
      throw new Error('Login failed - incorrect credentials or user not found');
    }

    // Wait for successful login by checking for authenticated elements
    // Increased timeout for mobile browsers and non-admin users
    // CRITICAL FIX (2025-10-21): Extended timeout to 30s to prevent flaky test failures
    // Add retry logic for authentication race conditions
    let authRetries = 0;
    const maxAuthRetries = 3;

    while (authRetries < maxAuthRetries) {
      try {
        console.log(`AuthHelper: Waiting for authenticated page elements (attempt ${authRetries + 1}/${maxAuthRetries})...`);
        await this.page.waitForFunction(
          () => {
            // Check if login form is gone (password field not visible)
            const passwordFields = document.querySelectorAll('input[type="password"]');
            const passwordVisible = Array.from(passwordFields).some(field => field.offsetParent !== null);
            if (passwordVisible) {
              return false; // Still on login page
            }

            // Check for main application elements
            const mainElements = [
              '.ant-layout-sider', // Sidebar
              '.ant-layout-content', // Main content
              '.ant-table', // Document table
            ];

            return mainElements.some(selector => {
              const element = document.querySelector(selector);
              return element && element.offsetParent !== null;
            });
          },
          { timeout: 30000 }  // Increased from 20000ms to 30000ms per code review feedback
        );
        // Success - break retry loop
        break;
      } catch (error) {
        authRetries++;

        // Debug: Log current page state if timeout occurs
        console.log('AuthHelper: Login timeout on attempt', authRetries);
        console.log('AuthHelper: Current URL:', this.page.url());
        const bodyText = await this.page.locator('body').textContent();
        console.log('AuthHelper: Body text (first 200 chars):', bodyText?.substring(0, 200));

        // If this was the last retry, throw the error
        if (authRetries >= maxAuthRetries) {
          console.error('AuthHelper: All authentication retries exhausted');
          throw error;
        }

        // Wait before retrying
        console.log('AuthHelper: Waiting 2 seconds before retry...');
        await this.page.waitForTimeout(2000);
      }
    }

    // Additional verification: ensure we're not on login page anymore
    await expect(passwordField).not.toBeVisible({ timeout: 5000 });

    // CRITICAL FIX: Wait for automatic redirect to documents page after successful login
    // The React app automatically redirects authenticated users from / to /documents
    try {
      await this.page.waitForURL('**/documents', { timeout: 5000 });
    } catch (e) {
      // If redirect didn't happen automatically, navigate manually
      await this.page.goto('/core/ui/dist/index.html', { waitUntil: 'networkidle' });
      // Click documents menu item to navigate
      const documentsMenuItem = this.page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
      if (await documentsMenuItem.count() > 0) {
        await documentsMenuItem.click();
        await this.page.waitForTimeout(2000);
      }
    }

    // Wait for documents page to fully load with Ant Design components
    // CRITICAL FIX (2025-10-21): Extended timeout to 30s for slow CI environments
    await this.page.waitForFunction(
      () => {
        // Check for key elements that indicate successful navigation to documents
        const hasLayout = document.querySelector('.ant-layout') !== null;
        const hasSider = document.querySelector('.ant-layout-sider') !== null;
        return hasLayout && hasSider;
      },
      { timeout: 30000 }  // Increased from 10000ms to 30000ms per code review feedback
    );

    // Additional wait for page stabilization
    await this.page.waitForTimeout(1000);
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
      await logoutMenuItem.click();

      // Wait for navigation to complete (window.location.href causes a hard navigation)
      // Wait for URL to change to the login page
      console.log('AuthHelper: Waiting for navigation...');
      try {
        await this.page.waitForURL('**/ui/dist/**', { timeout: 5000 });
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

      if (currentUrl.includes('/ui/dist/') && !currentUrl.includes('login')) {
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