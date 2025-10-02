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
    await this.page.goto('/core/ui/dist/');

    // Wait for login form to be visible
    await this.page.waitForSelector('input[placeholder="ユーザー名"]', {
      timeout: 10000,
    });

    // Fill username
    const usernameField = this.page.locator('input[placeholder="ユーザー名"]');
    await usernameField.fill(credentials.username);

    // Fill password
    const passwordField = this.page.locator('input[placeholder="パスワード"]');
    await passwordField.fill(credentials.password);

    // Select repository if dropdown exists
    if (credentials.repository) {
      const repositorySelect = this.page.locator('.ant-select');
      if (await repositorySelect.count() > 0) {
        await repositorySelect.click();
        await this.page.getByText(credentials.repository).click();
      }
    }

    // Click login button
    const loginButton = this.page.getByRole('button', { name: 'ログイン' });
    await loginButton.click();

    // Wait for successful login by checking for authenticated elements
    // Rather than URL pattern which can match login page too
    await this.page.waitForFunction(
      () => {
        // Check if login form is gone (password field not visible)
        const passwordField = document.querySelector('input[placeholder="パスワード"]');
        if (passwordField && passwordField.offsetParent !== null) {
          return false; // Still on login page
        }

        // Check for main application elements
        const mainElements = [
          '.ant-layout-sider', // Sidebar
          '.ant-layout-content', // Main content
          '.document-list', // Document management
          '[data-testid="main-app"]' // Main app container
        ];

        return mainElements.some(selector => {
          const element = document.querySelector(selector);
          return element && element.offsetParent !== null;
        });
      },
      { timeout: 15000 }
    );

    // Additional verification: ensure we're not on login page anymore
    await expect(this.page.locator('input[placeholder="パスワード"]')).not.toBeVisible();
  }

  /**
   * Perform logout
   */
  async logout(): Promise<void> {
    // Look for logout button or user menu
    const logoutButton = this.page.getByRole('button', { name: /logout|ログアウト/i });

    if (await logoutButton.count() > 0) {
      await logoutButton.click();
    } else {
      // Try clicking user menu first
      const userMenu = this.page.locator('.ant-dropdown-trigger, .user-menu');
      if (await userMenu.count() > 0) {
        await userMenu.click();
        await this.page.getByRole('menuitem', { name: /logout|ログアウト/i }).click();
      }
    }

    // Wait for redirect to login page
    await this.page.waitForURL('**/ui/dist/', { timeout: 5000 });

    // Verify we're back at login page
    await expect(this.page.locator('input[placeholder="パスワード"]')).toBeVisible();
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