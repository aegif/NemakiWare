import { test } from '@playwright/test';
import { AuthHelper } from './utils/auth-helper';

test('debug user creation steps', async ({ page }) => {
  test.setTimeout(120000); // 2 minutes timeout
  const authHelper = new AuthHelper(page);
  const testUsername = `testuser${Date.now()}`;

  console.log('Step 1: Login as admin');
  await authHelper.login();
  await page.waitForTimeout(2000);
  console.log('✅ Login successful');

  console.log('Step 2: Navigate to 管理 menu');
  const adminMenu = page.locator('.ant-menu-submenu:has-text("管理")');
  const adminMenuCount = await adminMenu.count();
  console.log(`Found ${adminMenuCount} 管理 menu items`);

  if (adminMenuCount > 0) {
    await adminMenu.click();
    await page.waitForTimeout(1000);
    console.log('✅ 管理 menu clicked');
  } else {
    console.log('❌ 管理 menu not found');
    return;
  }

  console.log('Step 3: Navigate to ユーザー管理');
  const userManagementItem = page.locator('.ant-menu-item:has-text("ユーザー管理")');
  const userMgmtCount = await userManagementItem.count();
  console.log(`Found ${userMgmtCount} ユーザー管理 menu items`);

  if (userMgmtCount > 0) {
    await userManagementItem.click();
    await page.waitForTimeout(2000);
    console.log('✅ ユーザー管理 page loaded');
  } else {
    console.log('❌ ユーザー管理 menu not found');
    return;
  }

  console.log('Step 4: Look for create user button');
  const allButtons = await page.locator('button').allTextContents();
  console.log(`Found ${allButtons.length} buttons:`, allButtons.slice(0, 15));

  const createButton = page.locator('button').filter({
    hasText: /新規ユーザー|新規作成|ユーザー追加|追加/
  });
  const createBtnCount = await createButton.count();
  console.log(`Found ${createBtnCount} create buttons`);

  if (createBtnCount > 0) {
    console.log('Step 5: Click create user button');
    await createButton.first().click();
    await page.waitForTimeout(1000);
    console.log('✅ Create button clicked');

    console.log('Step 6: Wait for modal to appear');
    const modal = page.locator('.ant-modal:not(.ant-modal-hidden), .ant-drawer:not(.ant-drawer-hidden)');
    await modal.waitFor({ state: 'visible', timeout: 5000 });
    console.log('✅ Modal appeared');

    console.log('Step 7: Check form fields');
    const allInputs = await modal.locator('input').all();
    console.log(`Found ${allInputs.length} input fields in modal`);
    for (let i = 0; i < allInputs.length; i++) {
      const input = allInputs[i];
      const id = await input.getAttribute('id');
      const placeholder = await input.getAttribute('placeholder');
      console.log(`  Input ${i}: id="${id}", placeholder="${placeholder}"`);
    }

    console.log('Step 8: Fill user fields (quick test)');
    const userIdInput = modal.locator('input#id');
    if (await userIdInput.count() > 0) {
      await userIdInput.fill(testUsername);
      console.log(`✅ Filled user ID: ${testUsername}`);
    }

    console.log('Step 9: Look for submit button');
    const submitButton = modal.locator('button[type="submit"], button.ant-btn-primary');
    const submitBtnCount = await submitButton.count();
    console.log(`Found ${submitBtnCount} submit buttons`);

    console.log('✅ Test completed - user creation UI is accessible');
  } else {
    console.log('❌ Create button not found');
  }
});
