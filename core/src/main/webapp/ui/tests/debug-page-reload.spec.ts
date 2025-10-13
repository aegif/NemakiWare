import { test } from '@playwright/test';
import { AuthHelper } from './utils/auth-helper';

test('debug page reload after navigation', async ({ page }) => {
  test.setTimeout(120000);
  const authHelper = new AuthHelper(page);

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

  console.log('Before reload:');
  console.log('  URL:', page.url());
  const rowsBefore = await page.locator('.ant-table-tbody tr').count();
  console.log('  Table rows:', rowsBefore);

  await page.reload();
  await page.waitForTimeout(3000);

  console.log('\nAfter reload:');
  console.log('  URL:', page.url());
  const rowsAfter = await page.locator('.ant-table-tbody tr').count();
  console.log('  Table rows:', rowsAfter);

  // Check if we're still on user management page
  const userMgmtHeading = page.locator('h1, h2, .ant-page-header-heading-title').filter({ hasText: /ユーザー|User/ });
  const headingCount = await userMgmtHeading.count();
  console.log('  User management heading count:', headingCount);

  if (headingCount > 0) {
    const headingText = await userMgmtHeading.first().textContent();
    console.log('  Heading text:', headingText);
  }

  // Check current page elements
  const tableExists = await page.locator('.ant-table').count();
  console.log('  Table exists:', tableExists > 0);

  const createButtonExists = await page.locator('button').filter({ hasText: /新規ユーザー/ }).count();
  console.log('  Create button exists:', createButtonExists > 0);
});
