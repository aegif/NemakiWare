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
   * Perform login with specified credentials
   */
  async login(credentials: LoginCredentials = AuthHelper.DEFAULT_CREDENTIALS): Promise<void> {
    // Navigate to login page
    await this.page.goto('/core/ui/dist/index.html');

    // Wait for login form to be visible - try multiple selectors
    const usernameFieldSelectors = [
      'input[placeholder="ユーザー名"]',
      'input[name="username"]',
      'input[type="text"]',
    ];

    let usernameField;
    for (const selector of usernameFieldSelectors) {
      const field = this.page.locator(selector).first();
      if (await field.count() > 0) {
        await field.waitFor({ state: 'visible', timeout: 10000 });
        usernameField = field;
        break;
      }
    }

    if (!usernameField) {
      throw new Error('Username field not found');
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
      if (await field.count() > 0) {
        passwordField = field;
        break;
      }
    }

    if (!passwordField) {
      throw new Error('Password field not found');
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
      if (await button.count() > 0) {
        loginButton = button;
        break;
      }
    }

    if (!loginButton) {
      throw new Error('Login button not found');
    }

    await loginButton.click();

    // Wait for successful login by checking for authenticated elements
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
      { timeout: 15000 }
    );

    // Additional verification: ensure we're not on login page anymore
    await expect(passwordField).not.toBeVisible({ timeout: 5000 });
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
      await logoutMenuItem.click();

      // Wait for navigation event (window.location.href will trigger navigation)
      await this.page.waitForTimeout(3000); // Wait for redirect to complete

      // Wait for login page to fully load
      try {
        await this.page.waitForLoadState('load', { timeout: 10000 });
      } catch {
        // Ignore load state errors, focus on element visibility
      }

      // Verify we're back at login page by checking for login form elements
      const passwordField = this.page.locator('input[type="password"]');
      const usernameField = this.page.locator('input[type="text"]').first();

      // Wait for either password or username field to be visible
      await expect(passwordField.or(usernameField)).toBeVisible({ timeout: 10000 });
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