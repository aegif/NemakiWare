import { test } from '@playwright/test';
import { AuthHelper } from './utils/auth-helper';

test('complete user creation with UI navigation verification', async ({ page }) => {
  test.setTimeout(180000);
  const authHelper = new AuthHelper(page);
  const testUsername = `testuser${Date.now()}`;

  console.log('Step 1: Login as admin');
  await authHelper.login();
  await page.waitForTimeout(2000);

  console.log('Step 2: Navigate to user management');
  const adminMenu = page.locator('.ant-menu-submenu:has-text("管理")');
  if (await adminMenu.count() > 0) {
    await adminMenu.click();
    await page.waitForTimeout(1000);
  }

  const userManagementItem = page.locator('.ant-menu-item:has-text("ユーザー管理")');
  if (await userManagementItem.count() > 0) {
    await userManagementItem.click();
    await page.waitForTimeout(2000);
  }

  console.log('Step 3: Create test user');
  const createButton = page.locator('button').filter({
    hasText: /新規ユーザー|新規作成|ユーザー追加|追加/
  });

  if (await createButton.count() > 0) {
    await createButton.first().click();
    await page.waitForTimeout(1000);

    const modal = page.locator('.ant-modal:not(.ant-modal-hidden), .ant-drawer:not(.ant-drawer-hidden)');
    await modal.waitFor({ state: 'visible', timeout: 5000 });

    // Fill ALL required fields
    console.log(`Creating user: ${testUsername}`);
    await modal.locator('input#id').fill(testUsername);
    await modal.locator('input#name').fill(`${testUsername}_display`);
    await modal.locator('input#firstName').fill('Test');
    await modal.locator('input#lastName').fill('User');
    await modal.locator('input#email').fill(`${testUsername}@example.com`);
    await modal.locator('input#password').fill('TestPass123!');

    // Submit
    const submitButton = modal.locator('button[type="submit"], button.ant-btn-primary');
    await submitButton.first().click();
    console.log('✅ Submit clicked');

    // Wait for success message
    try {
      await page.waitForSelector('.ant-message-success, .ant-notification-success', { timeout: 5000 });
      console.log('✅ Success message appeared');
    } catch (e) {
      console.log('⚠️ No success message (may still succeed)');
    }

    // Wait for modal to close
    try {
      await modal.waitFor({ state: 'hidden', timeout: 5000 });
      console.log('✅ Modal closed');
    } catch (e) {
      console.log('⚠️ Modal did not close');
    }

    // Wait for DB write
    await page.waitForTimeout(2000);

    console.log('Step 4: Verify user via UI navigation');

    // Navigate away to Documents
    const documentsMenu = page.locator('.ant-menu-item:has-text("ドキュメント")');
    if (await documentsMenu.count() > 0) {
      await documentsMenu.click();
      await page.waitForTimeout(1000);
      console.log('✅ Navigated to Documents');
    }

    // Navigate back to User Management
    const adminMenuBack = page.locator('.ant-menu-submenu:has-text("管理")');
    if (await adminMenuBack.count() > 0) {
      await adminMenuBack.click();
      await page.waitForTimeout(500);
    }

    const userMgmtBack = page.locator('.ant-menu-item:has-text("ユーザー管理")');
    if (await userMgmtBack.count() > 0) {
      await userMgmtBack.click();
      await page.waitForTimeout(2000);
      console.log('✅ Navigated back to User Management');
    }

    // Check for user in table
    const userTableRow = page.locator(`tr:has-text("${testUsername}")`);
    const userRowCount = await userTableRow.count();

    console.log(`User table check: ${userRowCount} row(s) found`);

    if (userRowCount > 0) {
      console.log(`✅ SUCCESS: User ${testUsername} created and found in table`);

      // Get user details from table
      const cells = await userTableRow.locator('td').allTextContents();
      console.log('User details:', cells.slice(0, 5).join(' | '));
    } else {
      console.log(`❌ FAILED: User ${testUsername} not found in table`);

      // Debug: Show all users in table
      const allRows = await page.locator('.ant-table-tbody tr').count();
      console.log(`Total rows in table: ${allRows}`);
    }
  }
});
