import { test, expect } from '@playwright/test';
import { AuthHelper } from './utils/auth-helper';

/**
 * Debug Authentication Test
 *
 * Simple test to isolate authentication issue
 */

test.describe('Debug Authentication', () => {
  test('should login successfully', async ({ page }) => {
    const authHelper = new AuthHelper(page);

    console.log('DEBUG: Starting login test');

    try {
      await authHelper.login();
      console.log('DEBUG: Login completed without error');

      // Verify we're logged in
      const isLoggedIn = await authHelper.isLoggedIn();
      console.log('DEBUG: Is logged in:', isLoggedIn);

      expect(isLoggedIn).toBe(true);

      // Take screenshot for verification
      await page.screenshot({ path: 'test-results/debug-login-success.png', fullPage: true });
      console.log('DEBUG: Screenshot saved');

    } catch (error) {
      console.error('DEBUG: Login failed with error:', error);

      // Take screenshot of failure state
      await page.screenshot({ path: 'test-results/debug-login-failure.png', fullPage: true });

      // Log current URL and page content
      console.log('DEBUG: Current URL:', page.url());
      const bodyText = await page.locator('body').textContent();
      console.log('DEBUG: Body text (first 500 chars):', bodyText?.substring(0, 500));

      throw error;
    }
  });
});
