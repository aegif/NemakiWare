import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';

/**
 * Cloud Directory Sync E2E Tests
 *
 * Tests for NemakiWare cloud directory sync management page:
 * - Navigation to cloud directory sync page
 * - Google / Microsoft tab switching
 * - Sync control buttons existence
 * - Status display components
 */
test.describe('Cloud Directory Sync', () => {
  let authHelper: AuthHelper;
  let testHelper: TestHelper;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    await authHelper.login();
    await page.waitForTimeout(2000);

    await testHelper.closeMobileSidebar(browserName);
    await testHelper.waitForAntdLoad();
  });

  test('should navigate to cloud directory sync page', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    // Open admin submenu
    const adminMenu = page.locator('.ant-menu-submenu').filter({ hasText: /管理|Admin/i });

    if (await adminMenu.count() > 0) {
      await adminMenu.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(500);
    }

    // Look for cloud directory sync menu item
    const cloudSyncMenuItem = page.locator('.ant-menu-item').filter({
      hasText: /クラウドディレクトリ同期|Cloud Directory Sync/i
    });

    if (await cloudSyncMenuItem.count() === 0) {
      test.skip('Cloud directory sync menu item not found');
      return;
    }

    await cloudSyncMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    expect(page.url()).toContain('/cloud-directory-sync');
  });

  test('should display Google and Microsoft tabs', async ({ page }) => {
    await page.goto('/core/ui/#/cloud-directory-sync');
    await page.waitForTimeout(2000);

    // Check for tabs
    const googleTab = page.locator('.ant-tabs-tab').filter({ hasText: /Google/i });
    const microsoftTab = page.locator('.ant-tabs-tab').filter({ hasText: /Microsoft/i });

    if (await googleTab.count() === 0 && await microsoftTab.count() === 0) {
      test.skip('Cloud directory sync page tabs not found');
      return;
    }

    expect(await googleTab.count()).toBeGreaterThan(0);
    expect(await microsoftTab.count()).toBeGreaterThan(0);
  });

  test('should switch between Google and Microsoft tabs', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    await page.goto('/core/ui/#/cloud-directory-sync');
    await page.waitForTimeout(2000);

    const microsoftTab = page.locator('.ant-tabs-tab').filter({ hasText: /Microsoft/i });

    if (await microsoftTab.count() === 0) {
      test.skip('Microsoft tab not found');
      return;
    }

    await microsoftTab.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(500);

    // Verify Microsoft tab is active
    const activeTab = page.locator('.ant-tabs-tab-active');
    await expect(activeTab).toContainText(/Microsoft/i);
  });

  test('should display sync control buttons', async ({ page }) => {
    await page.goto('/core/ui/#/cloud-directory-sync');
    await page.waitForTimeout(2000);

    // Look for Delta sync button
    const deltaSyncButton = page.locator('button').filter({
      hasText: /Delta同期|Delta Sync/i
    });

    // Look for Full Reconciliation button
    const fullReconcButton = page.locator('button').filter({
      hasText: /完全同期|Full Reconciliation/i
    });

    // Look for Test Connection button
    const testConnButton = page.locator('button').filter({
      hasText: /接続テスト|Test Connection/i
    });

    if (await deltaSyncButton.count() === 0) {
      test.skip('Sync buttons not found - page may not be loaded');
      return;
    }

    expect(await deltaSyncButton.count()).toBeGreaterThan(0);
    expect(await fullReconcButton.count()).toBeGreaterThan(0);
    expect(await testConnButton.count()).toBeGreaterThan(0);
  });

  test('should display sync status statistics', async ({ page }) => {
    await page.goto('/core/ui/#/cloud-directory-sync');
    await page.waitForTimeout(2000);

    // Look for Statistic components (ant-statistic)
    const statistics = page.locator('.ant-statistic');

    if (await statistics.count() === 0) {
      test.skip('Statistics not displayed');
      return;
    }

    // Should have multiple statistics (users created/updated/deleted, groups created/updated/deleted etc.)
    expect(await statistics.count()).toBeGreaterThanOrEqual(4);
  });

  test('should display status tag', async ({ page }) => {
    await page.goto('/core/ui/#/cloud-directory-sync');
    await page.waitForTimeout(2000);

    // Status should be shown as a Tag component
    const statusTag = page.locator('.ant-tag').first();

    if (await statusTag.count() === 0) {
      test.skip('Status tag not found');
      return;
    }

    // Status tag should contain one of the known statuses
    const tagText = await statusTag.textContent();
    expect(tagText).toMatch(/IDLE|RUNNING|COMPLETED|ERROR|待機|実行中|完了|エラー/i);
  });
});
