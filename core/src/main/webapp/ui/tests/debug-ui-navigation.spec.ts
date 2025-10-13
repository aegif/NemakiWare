import { test } from '@playwright/test';
import { AuthHelper } from './utils/auth-helper';

test('debug UI navigation for user list refresh', async ({ page }) => {
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

  console.log('Initial state:');
  console.log('  URL:', page.url());
  const rowsBefore = await page.locator('.ant-table-tbody tr').count();
  console.log('  Table rows:', rowsBefore);

  // Method 1: UI navigation (Documents -> User Management)
  console.log('\nMethod 1: UI Navigation');
  const documentsMenu = page.locator('.ant-menu-item:has-text("ドキュメント")');
  if (await documentsMenu.count() > 0) {
    console.log('  Navigating to Documents...');
    await documentsMenu.click();
    await page.waitForTimeout(1000);
    console.log('  Documents URL:', page.url());
  }

  const adminMenuBack = page.locator('.ant-menu-submenu:has-text("管理")');
  if (await adminMenuBack.count() > 0) {
    console.log('  Opening Admin menu...');
    await adminMenuBack.click();
    await page.waitForTimeout(500);
  }

  const userMgmtBack = page.locator('.ant-menu-item:has-text("ユーザー管理")');
  if (await userMgmtBack.count() > 0) {
    console.log('  Navigating back to User Management...');
    await userMgmtBack.click();
    await page.waitForTimeout(2000);
  }

  console.log('After UI navigation:');
  console.log('  URL:', page.url());
  const rowsAfterNav = await page.locator('.ant-table-tbody tr').count();
  console.log('  Table rows:', rowsAfterNav);

  // Check current page elements
  const tableExists = await page.locator('.ant-table').count();
  console.log('  Table exists:', tableExists > 0);

  const createButtonExists = await page.locator('button').filter({ hasText: /新規ユーザー/ }).count();
  console.log('  Create button exists:', createButtonExists > 0);

  const headingExists = await page.locator('h1, h2, .ant-page-header-heading-title').count();
  console.log('  Heading exists:', headingExists > 0);

  // Compare with page.reload() for reference
  console.log('\nMethod 2: page.reload() for comparison');
  await page.reload();
  await page.waitForTimeout(3000);

  console.log('After page.reload():');
  console.log('  URL:', page.url());
  const rowsAfterReload = await page.locator('.ant-table-tbody tr').count();
  console.log('  Table rows:', rowsAfterReload);

  const tableExistsReload = await page.locator('.ant-table').count();
  console.log('  Table exists:', tableExistsReload > 0);

  const createButtonExistsReload = await page.locator('button').filter({ hasText: /新規ユーザー/ }).count();
  console.log('  Create button exists:', createButtonExistsReload > 0);
});
