import { test } from '@playwright/test';
import { AuthHelper } from './utils/auth-helper';

test('debug user creation error messages', async ({ page }) => {
  test.setTimeout(120000);
  const authHelper = new AuthHelper(page);
  const testUsername = `testuser${Date.now()}`;

  await authHelper.login();
  await page.waitForTimeout(2000);

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

  const createButton = page.locator('button').filter({
    hasText: /新規ユーザー|新規作成|ユーザー追加|追加/
  });

  if (await createButton.count() > 0) {
    await createButton.first().click();
    await page.waitForTimeout(1000);

    const modal = page.locator('.ant-modal:not(.ant-modal-hidden), .ant-drawer:not(.ant-drawer-hidden)');
    await modal.waitFor({ state: 'visible', timeout: 5000 });

    // Fill fields
    console.log('Filling user ID:', testUsername);
    await modal.locator('input#id').fill(testUsername);

    console.log('Filling name (display name):', `${testUsername}_display`);
    await modal.locator('input#name').fill(`${testUsername}_display`);

    console.log('Filling firstName: Test');
    await modal.locator('input#firstName').fill('Test');

    console.log('Filling lastName: User');
    await modal.locator('input#lastName').fill('User');

    console.log('Filling email');
    await modal.locator('input#email').fill(`${testUsername}@example.com`);

    console.log('Filling password');
    await modal.locator('input#password').fill('TestPass123!');

    // Submit
    const submitButton = modal.locator('button[type="submit"], button.ant-btn-primary');
    console.log('Clicking submit button');
    await submitButton.first().click();

    // Wait a bit for form validation/submission
    await page.waitForTimeout(3000);

    // Check for error messages
    console.log('\n=== Checking for error messages ===');

    const formErrors = await modal.locator('.ant-form-item-explain-error').allTextContents();
    if (formErrors.length > 0) {
      console.log('Form validation errors:');
      formErrors.forEach((err, i) => console.log(`  ${i + 1}. ${err}`));
    } else {
      console.log('No form validation errors found');
    }

    const messageErrors = await page.locator('.ant-message-error').allTextContents();
    if (messageErrors.length > 0) {
      console.log('Message errors:');
      messageErrors.forEach((err, i) => console.log(`  ${i + 1}. ${err}`));
    } else {
      console.log('No message errors found');
    }

    const notificationErrors = await page.locator('.ant-notification-error').allTextContents();
    if (notificationErrors.length > 0) {
      console.log('Notification errors:');
      notificationErrors.forEach((err, i) => console.log(`  ${i + 1}. ${err}`));
    } else {
      console.log('No notification errors found');
    }

    // Check modal state
    const modalVisible = await modal.isVisible();
    console.log('\nModal still visible:', modalVisible);

    // Check all text in modal
    const modalText = await modal.textContent();
    console.log('\n=== Modal content (first 500 chars) ===');
    console.log(modalText?.substring(0, 500));

    // Take screenshot
    await page.screenshot({ path: 'user-creation-error-debug.png', fullPage: true });
    console.log('\nScreenshot saved: user-creation-error-debug.png');
  }
});
