import { test, expect } from '@playwright/test';
import { AuthHelper } from './utils/auth-helper';

test.describe('Verify page.reload() fixes across different pages', () => {
  test('verify UI navigation works on /users page', async ({ page }) => {
    const authHelper = new AuthHelper(page);
    await authHelper.login();
    await page.waitForTimeout(2000);

    // Navigate to user management
    const adminMenu = page.locator('.ant-menu-submenu:has-text("管理")');
    if (await adminMenu.count() > 0) {
      await adminMenu.click();
      await page.waitForTimeout(1000);
    }
    await page.locator('.ant-menu-item:has-text("ユーザー管理")').click();
    await page.waitForTimeout(2000);

    console.log('Initial URL:', page.url());
    const rowsBeforeNav = await page.locator('.ant-table-tbody tr').count();
    console.log('Initial rows:', rowsBeforeNav);
    expect(rowsBeforeNav).toBeGreaterThan(0);

    // UI Navigation (should preserve UI)
    const documentsMenu = page.locator('.ant-menu-item:has-text("ドキュメント")');
    await documentsMenu.click();
    await page.waitForTimeout(1000);

    await adminMenu.click();
    await page.waitForTimeout(500);
    await page.locator('.ant-menu-item:has-text("ユーザー管理")').click();
    await page.waitForTimeout(2000);

    console.log('After UI navigation URL:', page.url());
    const rowsAfterNav = await page.locator('.ant-table-tbody tr').count();
    console.log('After UI navigation rows:', rowsAfterNav);

    expect(rowsAfterNav).toBeGreaterThan(0);
    expect(await page.locator('.ant-table').count()).toBeGreaterThan(0);
    console.log('✅ UI navigation preserves table on /users');
  });

  test('verify UI navigation works on /groups page', async ({ page }) => {
    const authHelper = new AuthHelper(page);
    await authHelper.login();
    await page.waitForTimeout(2000);

    // Navigate to group management
    const adminMenu = page.locator('.ant-menu-submenu:has-text("管理")');
    if (await adminMenu.count() > 0) {
      await adminMenu.click();
      await page.waitForTimeout(1000);
    }
    await page.locator('.ant-menu-item:has-text("グループ管理")').click();
    await page.waitForTimeout(2000);

    const rowsBeforeNav = await page.locator('.ant-table-tbody tr').count();
    console.log('Initial rows on /groups:', rowsBeforeNav);

    // UI Navigation
    const documentsMenu = page.locator('.ant-menu-item:has-text("ドキュメント")');
    await documentsMenu.click();
    await page.waitForTimeout(1000);

    await adminMenu.click();
    await page.waitForTimeout(500);
    await page.locator('.ant-menu-item:has-text("グループ管理")').click();
    await page.waitForTimeout(2000);

    const rowsAfterNav = await page.locator('.ant-table-tbody tr').count();
    console.log('After UI navigation rows:', rowsAfterNav);

    expect(rowsAfterNav).toBeGreaterThan(0);
    expect(await page.locator('.ant-table').count()).toBeGreaterThan(0);
    console.log('✅ UI navigation preserves table on /groups');
  });

  test('verify UI navigation works on /documents page', async ({ page }) => {
    const authHelper = new AuthHelper(page);
    await authHelper.login();
    await page.waitForTimeout(2000);

    // Already on /documents after login
    console.log('Initial URL:', page.url());
    const rowsBeforeNav = await page.locator('.ant-table-tbody tr').count();
    console.log('Initial rows on /documents:', rowsBeforeNav);

    // UI Navigation away and back
    const adminMenu = page.locator('.ant-menu-submenu:has-text("管理")');
    if (await adminMenu.count() > 0) {
      await adminMenu.click();
      await page.waitForTimeout(1000);
    }

    const userMgmtItem = page.locator('.ant-menu-item:has-text("ユーザー管理")');
    if (await userMgmtItem.count() > 0) {
      await userMgmtItem.click();
      await page.waitForTimeout(1000);
    }

    const documentsMenuItem = page.locator('.ant-menu-item:has-text("ドキュメント")');
    await documentsMenuItem.click();
    await page.waitForTimeout(2000);

    console.log('After UI navigation URL:', page.url());
    const rowsAfterNav = await page.locator('.ant-table-tbody tr').count();
    console.log('After UI navigation rows:', rowsAfterNav);

    expect(rowsAfterNav).toBeGreaterThan(0);
    expect(await page.locator('.ant-table').count()).toBeGreaterThan(0);
    console.log('✅ UI navigation preserves table on /documents');
  });
});
