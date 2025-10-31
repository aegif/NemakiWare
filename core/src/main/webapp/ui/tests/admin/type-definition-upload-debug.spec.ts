import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';

/**
 * DEBUG: Type Management Page Button Investigation
 *
 * Purpose: Capture screenshots and logs to understand why "ファイルからインポート" button is not found
 */

let authHelper: AuthHelper;
let testHelper: TestHelper;

test.describe('Type Management Page Debug', () => {
  test('should investigate button presence on Type Management page', async ({ page }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    // Login
    await authHelper.login();
    await page.waitForTimeout(2000);
    await testHelper.waitForAntdLoad();

    // Navigate to Type Management
    const adminMenu = page.locator('.ant-menu-submenu:has-text("管理")');
    if (await adminMenu.count() > 0) {
      await adminMenu.click();
      await page.waitForTimeout(1000);
    }

    const typeManagementItem = page.locator('.ant-menu-item:has-text("タイプ管理")');
    if (await typeManagementItem.count() > 0) {
      console.log('✅ Type Management menu item found, clicking...');
      await typeManagementItem.click();
      await page.waitForTimeout(2000);
    } else {
      console.log('❌ Type Management menu item NOT found');
    }

    // Wait for table
    await page.waitForSelector('.ant-table', { timeout: 15000 });
    console.log('✅ Table loaded');

    // Take screenshot before button check
    await page.screenshot({ path: '/tmp/type-management-page.png', fullPage: true });
    console.log('📸 Screenshot saved to /tmp/type-management-page.png');

    // Check for all buttons on page
    const allButtons = await page.locator('button').all();
    console.log(`🔍 Total buttons on page: ${allButtons.length}`);

    for (let i = 0; i < Math.min(allButtons.length, 20); i++) {
      const buttonText = await allButtons[i].textContent();
      const ariaLabel = await allButtons[i].getAttribute('aria-label');
      console.log(`  Button ${i}: text="${buttonText}", aria-label="${ariaLabel}"`);
    }

    // Check specifically for import button
    const importButton = page.locator('button:has-text("ファイルからインポート")');
    const importButtonCount = await importButton.count();
    console.log(`🔍 Import button count: ${importButtonCount}`);

    if (importButtonCount > 0) {
      console.log('✅ Import button FOUND');
      const buttonText = await importButton.textContent();
      console.log(`  Button text: "${buttonText}"`);
    } else {
      console.log('❌ Import button NOT FOUND');

      // Try alternative selectors
      const importButtonAlt1 = page.locator('button').filter({ hasText: 'ファイル' });
      const alt1Count = await importButtonAlt1.count();
      console.log(`  Alternative 1 (hasText "ファイル"): ${alt1Count} buttons`);

      const importButtonAlt2 = page.locator('button').filter({ hasText: 'インポート' });
      const alt2Count = await importButtonAlt2.count();
      console.log(`  Alternative 2 (hasText "インポート"): ${alt2Count} buttons`);

      // Get page HTML for analysis
      const pageContent = await page.content();
      const hasButtonText = pageContent.includes('ファイルからインポート');
      console.log(`  Page HTML contains "ファイルからインポート": ${hasButtonText}`);
    }

    // Check React component rendering
    const reactRoot = page.locator('#root');
    const hasReactContent = await reactRoot.count() > 0;
    console.log(`🔍 React root element present: ${hasReactContent}`);

    // Wait a bit more and check again
    await page.waitForTimeout(5000);
    const importButtonCountAfterWait = await importButton.count();
    console.log(`🔍 Import button count after 5s wait: ${importButtonCountAfterWait}`);

    // Final screenshot
    await page.screenshot({ path: '/tmp/type-management-page-after-wait.png', fullPage: true });
    console.log('📸 Screenshot saved to /tmp/type-management-page-after-wait.png');
  });
});
