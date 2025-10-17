import { test } from '@playwright/test';
import { AuthHelper } from './utils/auth-helper';

test.describe('Debug Access Control beforeAll', () => {
  const testUsername = `testuser${Date.now()}`;
  const testUserPassword = 'TestPass123!';

  test.beforeAll(async ({ browser }) => {
    test.setTimeout(300000); // 5 minutes
    const context = await browser.newContext();
    const page = await context.newPage();
    const setupAuthHelper = new AuthHelper(page);

    try {
      const startTime = Date.now();
      console.log(`[${new Date().toISOString()}] Starting beforeAll setup`);

      // Login as admin
      console.log(`[${new Date().toISOString()}] Step 1: Login as admin`);
      await setupAuthHelper.login();
      console.log(`[${new Date().toISOString()}] ✅ Login completed (${Date.now() - startTime}ms)`);

      await page.waitForTimeout(2000);
      console.log(`[${new Date().toISOString()}] ✅ Waited 2s after login (${Date.now() - startTime}ms)`);

      // Navigate to user management
      console.log(`[${new Date().toISOString()}] Step 2: Navigate to ユーザー管理`);
      const adminMenu = page.locator('.ant-menu-submenu:has-text("管理")');
      if (await adminMenu.count() > 0) {
        await adminMenu.click();
        console.log(`[${new Date().toISOString()}] ✅ Clicked 管理 menu (${Date.now() - startTime}ms)`);
        await page.waitForTimeout(1000);
      }

      const userManagementItem = page.locator('.ant-menu-item:has-text("ユーザー管理")');
      if (await userManagementItem.count() > 0) {
        await userManagementItem.click();
        console.log(`[${new Date().toISOString()}] ✅ Clicked ユーザー管理 (${Date.now() - startTime}ms)`);
        await page.waitForTimeout(2000);
      }

      // Create test user
      console.log(`[${new Date().toISOString()}] Step 3: Create test user`);
      const createButton = page.locator('button').filter({
        hasText: /新規ユーザー|新規作成|ユーザー追加|追加/
      });

      if (await createButton.count() > 0) {
        await createButton.first().click();
        console.log(`[${new Date().toISOString()}] ✅ Clicked create button (${Date.now() - startTime}ms)`);
        await page.waitForTimeout(1000);

        const modal = page.locator('.ant-modal:not(.ant-modal-hidden), .ant-drawer:not(.ant-drawer-hidden)');
        await modal.waitFor({ state: 'visible', timeout: 5000 });
        console.log(`[${new Date().toISOString()}] ✅ Modal opened (${Date.now() - startTime}ms)`);

        // Fill fields
        await modal.locator('input#id').fill(testUsername);
        await modal.locator('input#firstName').fill('Test');
        await modal.locator('input#lastName').fill('User');
        await modal.locator('input#email').fill(`${testUsername}@example.com`);
        await modal.locator('input#password').fill(testUserPassword);
        console.log(`[${new Date().toISOString()}] ✅ Filled all fields (${Date.now() - startTime}ms)`);

        // Submit
        const submitButton = modal.locator('button[type="submit"], button.ant-btn-primary');
        await submitButton.first().click();
        console.log(`[${new Date().toISOString()}] ✅ Clicked submit (${Date.now() - startTime}ms)`);

        // Wait for success message
        try {
          await page.waitForSelector('.ant-message-success, .ant-notification-success', { timeout: 5000 });
          console.log(`[${new Date().toISOString()}] ✅ Success message appeared (${Date.now() - startTime}ms)`);
        } catch (e) {
          console.log(`[${new Date().toISOString()}] ⚠️ No success message (${Date.now() - startTime}ms)`);
        }

        // Wait for modal to close
        try {
          await modal.waitFor({ state: 'hidden', timeout: 5000 });
          console.log(`[${new Date().toISOString()}] ✅ Modal closed (${Date.now() - startTime}ms)`);
        } catch (e) {
          console.log(`[${new Date().toISOString()}] ⚠️ Modal did not close (${Date.now() - startTime}ms)`);
        }

        // Wait for DB write
        console.log(`[${new Date().toISOString()}] Step 4: Wait for DB write`);
        await page.waitForTimeout(2000);
        console.log(`[${new Date().toISOString()}] ✅ Waited 2s for DB write (${Date.now() - startTime}ms)`);

        // Verify in table
        console.log(`[${new Date().toISOString()}] Step 5: Verify user in table`);
        await page.reload();
        console.log(`[${new Date().toISOString()}] ✅ Page reloaded (${Date.now() - startTime}ms)`);
        await page.waitForTimeout(2000);

        const userTableRow = page.locator(`tr:has-text("${testUsername}")`);
        const userRowCount = await userTableRow.count();
        console.log(`[${new Date().toISOString()}] ✅ User table check: ${userRowCount} rows (${Date.now() - startTime}ms)`);
      }

      console.log(`[${new Date().toISOString()}] 🎉 beforeAll completed in ${Date.now() - startTime}ms`);
    } catch (error) {
      console.log(`[${new Date().toISOString()}] ❌ beforeAll error:`, error);
    } finally {
      await context.close();
    }
  });

  test('dummy test to trigger beforeAll', async () => {
    // This test just exists to trigger the beforeAll hook
    console.log('Dummy test executed');
  });
});
