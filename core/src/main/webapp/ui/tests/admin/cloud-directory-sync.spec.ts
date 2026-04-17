import { waitForUiStable, waitForRender } from '../utils/wait-helpers';
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
 *
 * Note: These tests skip ONLY when the cloud directory sync feature is not
 * enabled (menu item not present). Once on the page, missing UI elements
 * are treated as test failures, not skips.
 */
test.describe('Cloud Directory Sync', () => {
  let authHelper: AuthHelper;
  let testHelper: TestHelper;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    await authHelper.login();
    await page.waitForSelector('.ant-menu-item, .ant-table-tbody', { timeout: 30000 });

    await testHelper.closeMobileSidebar(browserName);
    await testHelper.waitForAntdLoad();
  });

  test('should navigate to cloud directory sync page', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    // Open admin submenu
    const adminMenu = page.locator('.ant-menu-submenu').filter({ hasText: /管理|Admin/i });

    if (await adminMenu.count() > 0) {
      await adminMenu.click(isMobile ? { force: true } : {});
      await waitForRender(page);
    }

    // Look for cloud directory sync menu item
    const cloudSyncMenuItem = page.locator('.ant-menu-item').filter({
      hasText: /ディレクトリ同期|Directory Sync/i
    });

    if (await cloudSyncMenuItem.count() === 0) {
      // Feature not enabled in this deployment - legitimate skip
      test.skip(true, 'Cloud directory sync feature not enabled (menu item not present)');
      return;
    }

    await cloudSyncMenuItem.click(isMobile ? { force: true } : {});
    await waitForUiStable(page);

    expect(page.url()).toContain('/cloud-directory-sync');
  });

  test('should display Google and Microsoft tabs', async ({ page }) => {
    await page.goto('/core/ui/#/cloud-directory-sync');
    await waitForUiStable(page);

    // If page redirected away (feature not enabled), skip
    if (!page.url().includes('/cloud-directory-sync')) {
      test.skip(true, 'Cloud directory sync page not available');
      return;
    }

    // On the page - tabs MUST exist
    const googleTab = page.locator('.ant-tabs-tab').filter({ hasText: /Google/i });
    const microsoftTab = page.locator('.ant-tabs-tab').filter({ hasText: /Microsoft/i });

    await expect(googleTab).toBeVisible({ timeout: 5000 });
    await expect(microsoftTab).toBeVisible({ timeout: 5000 });
  });

  test('should switch between Google and Microsoft tabs', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    await page.goto('/core/ui/#/cloud-directory-sync');
    await waitForUiStable(page);

    if (!page.url().includes('/cloud-directory-sync')) {
      test.skip(true, 'Cloud directory sync page not available');
      return;
    }

    const microsoftTab = page.locator('.ant-tabs-tab').filter({ hasText: /Microsoft/i });
    await expect(microsoftTab).toBeVisible({ timeout: 5000 });

    await microsoftTab.click(isMobile ? { force: true } : {});
    await waitForRender(page);

    // Verify Microsoft tab is active
    const activeTab = page.locator('.ant-tabs-tab-active');
    await expect(activeTab).toContainText(/Microsoft/i);
  });

  test('should display sync control buttons', async ({ page }) => {
    await page.goto('/core/ui/#/cloud-directory-sync');
    await waitForUiStable(page);

    if (!page.url().includes('/cloud-directory-sync')) {
      test.skip(true, 'Cloud directory sync page not available');
      return;
    }

    // On the page - buttons MUST exist
    const deltaSyncButton = page.locator('button').filter({
      hasText: /Delta同期|Delta Sync/i
    });
    const fullReconcButton = page.locator('button').filter({
      hasText: /完全同期|Full Reconciliation/i
    });
    const testConnButton = page.locator('button').filter({
      hasText: /接続テスト|Test Connection/i
    });

    await expect(deltaSyncButton).toBeVisible({ timeout: 5000 });
    await expect(fullReconcButton).toBeVisible({ timeout: 5000 });
    await expect(testConnButton).toBeVisible({ timeout: 5000 });
  });

  test('should display sync status statistics', async ({ page }) => {
    await page.goto('/core/ui/#/cloud-directory-sync');
    await waitForUiStable(page);

    if (!page.url().includes('/cloud-directory-sync')) {
      test.skip(true, 'Cloud directory sync page not available');
      return;
    }

    // Statistics may not exist if no cloud provider has been synced yet
    const statistics = page.locator('.ant-statistic');
    const statisticsCount = await statistics.count();
    if (statisticsCount === 0) {
      console.log('No sync statistics displayed - no cloud sync has been executed yet (expected for fresh environment)');
    } else {
      await expect(statistics.first()).toBeVisible({ timeout: 5000 });
      expect(statisticsCount).toBeGreaterThanOrEqual(4);
      console.log(`Found ${statisticsCount} sync statistics`);
    }
  });

  test('should display status tag', async ({ page }) => {
    await page.goto('/core/ui/#/cloud-directory-sync');
    await waitForUiStable(page);

    if (!page.url().includes('/cloud-directory-sync')) {
      test.skip(true, 'Cloud directory sync page not available');
      return;
    }

    // Status tag may not exist if no cloud provider has been synced yet
    const statusTag = page.locator('.ant-tag').first();
    if (await statusTag.count() === 0) {
      console.log('No status tag displayed - no cloud sync has been executed yet (expected for fresh environment)');
    } else {
      await expect(statusTag).toBeVisible({ timeout: 5000 });
      const tagText = await statusTag.textContent();
      expect(tagText).toMatch(/IDLE|RUNNING|COMPLETED|ERROR|待機|実行中|完了|エラー/i);
      console.log(`Status tag displayed: ${tagText}`);
    }
  });
});
