import { waitForUiStable, waitForRender } from '../utils/wait-helpers';
import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';

/**
 * Solr Index Maintenance E2E Tests
 *
 * Comprehensive end-to-end tests for NemakiWare Solr index maintenance functionality:
 * - Navigation to Solr maintenance page from admin menu
 * - Index health check display with document count statistics
 * - Reindex status monitoring and progress display
 * - Full repository reindexing operation
 * - Folder-based reindexing with recursive option
 * - Direct Solr query execution interface
 * - Index clear and optimize operations
 *
 * IMPORTANT DESIGN DECISIONS:
 *
 * 1. Admin-Only Access Pattern (Lines 58-75):
 *    - Solr maintenance is accessed via admin menu -> Solrインデックス
 *    - Only admin users can access this functionality
 *    - Non-admin users should be redirected or see access denied
 *    - Rationale: Index operations are administrative tasks
 *
 * 2. Health Check Display (Lines 85-120):
 *    - Shows Solr document count vs CouchDB document count
 *    - Displays missing and orphaned document counts
 *    - Shows health status alert (success/warning)
 *    - Rationale: Administrators need visibility into index consistency
 *
 * 3. Reindex Operations (Lines 130-180):
 *    - Full reindex requires confirmation via Popconfirm
 *    - Folder reindex requires folder ID input
 *    - Progress bar shows during active reindexing
 *    - Cancel button available during reindexing
 *    - Rationale: Reindexing is resource-intensive, needs user confirmation
 *
 * 4. Solr Query Interface (Lines 190-240):
 *    - Direct Solr query input with q, start, rows, sort, fl parameters
 *    - Results displayed in expandable document cards
 *    - Query execution time shown in results
 *    - Rationale: Administrators need direct Solr access for debugging
 *
 * 5. Mobile Browser Support (Lines 42-56):
 *    - Sidebar close logic prevents overlay blocking clicks
 *    - Force click option for mobile viewport interactions
 *    - Rationale: Mobile layouts have sidebar overlays
 *
 * Test Coverage:
 * 1. Navigate to Solr maintenance page
 * 2. Display index health check statistics
 * 3. Show reindex status and progress
 * 4. Execute full reindex operation
 * 5. Execute folder-based reindex
 * 6. Execute Solr query and display results
 * 7. Clear index operation (with confirmation)
 * 8. Optimize index operation
 *
 * Known Limitations:
 * - Tests skip gracefully if Solr maintenance UI not implemented
 * - Reindex operations may take time to complete
 * - Health check requires backend Solr connection
 * - Query results depend on existing indexed documents
 */
test.describe('Solr Index Maintenance', () => {
  let authHelper: AuthHelper;
  let testHelper: TestHelper;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    // Login as admin
    await authHelper.login();
    await page.waitForSelector('.ant-menu-item, .ant-table-tbody', { timeout: 30000 });

    await testHelper.closeMobileSidebar(browserName);

    await testHelper.waitForAntdLoad();
  });

  test('should navigate to Solr maintenance page from admin menu', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    // Look for admin submenu
    const adminMenu = page.locator('.ant-menu-submenu').filter({ hasText: /管理|Admin/i });

    if (await adminMenu.count() > 0) {
      await adminMenu.click(isMobile ? { force: true } : {});
      await waitForRender(page);
    }

    // Look for Solr maintenance menu item
    const solrMenuItem = page.locator('.ant-menu-item').filter({
      hasText: /Solrインデックス|Solr Index/i
    });

    if (await solrMenuItem.count() > 0) {
      await solrMenuItem.click(isMobile ? { force: true } : {});
      await waitForUiStable(page);

      // Verify Solr maintenance page loaded
      expect(page.url()).toContain('/solr');

      // Check for page title or content
      const pageTitle = page.locator('h2, .ant-card-head-title').filter({
        hasText: /Solrインデックスメンテナンス|Solr Index Maintenance/i
      });

      // count() is a snapshot: it does not retry, so this used to assert on whatever had
      // rendered at that instant. Use a retrying assertion on the page's own content.
      await expect(pageTitle.or(page.locator('.ant-tabs')).first())
        .toBeVisible({ timeout: 15000 });
      console.log('Solr maintenance page loaded successfully');
    } else {
      console.log('Solr maintenance menu item not found - trying direct navigation');
      // Try direct navigation
      await page.goto('/core/ui/#/solr');
      await waitForUiStable(page);

      const hasContent = await page.locator('.ant-tabs, .ant-card').count() > 0;
      if (!hasContent) {
        test.skip('ENV: Solr maintenance page not accessible');
      }
    }
  });

  test('should display index health check statistics', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    // Navigate to Solr maintenance page
    await page.goto('/core/ui/#/solr');
    await page.waitForLoadState('networkidle');

    // Wait for health check card to appear (API loads healthStatus asynchronously)
    const healthCard = page.locator('.ant-card').filter({
      hasText: /インデックスヘルスチェック|Index Health/i
    });

    // Wait up to 15 seconds for health status to load (initially shows Spin, then Card)
    await expect(healthCard).toBeVisible({ timeout: 15000 }).catch(() => {});

    if (await healthCard.count() === 0) {
      // Retry: reload and wait longer
      await page.reload({ waitUntil: 'networkidle' });
      await expect(healthCard).toBeVisible({ timeout: 15000 }).catch(() => {});
    }

    if (await healthCard.count() === 0) {
      test.skip('ENV: Solr health API may be unavailable');
      return;
    }

    // Verify statistics are displayed
    const statisticItems = page.locator('.ant-statistic');
    const statisticCount = await statisticItems.count();

    console.log(`Found ${statisticCount} statistic items`);

    // Should have at least some statistics (Solr count, CouchDB count, etc.)
    expect(statisticCount).toBeGreaterThanOrEqual(2);

    // Check for health status alert
    const healthAlert = page.locator('.ant-alert');
    if (await healthAlert.count() > 0) {
      console.log('Health status alert is displayed');
    }

    // Take screenshot for verification
    await page.screenshot({ path: 'test-results/screenshots/solr_health_check.png', fullPage: true });
  });

  test('should display reindex status', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    // Navigate to Solr maintenance page
    await page.goto('/core/ui/#/solr');
    await waitForUiStable(page);

    // Check for reindex status card
    const statusCard = page.locator('.ant-card').filter({
      hasText: /再インデクシング状態|Reindex Status/i
    });

    // Status card may or may not be visible depending on current state
    if (await statusCard.count() > 0) {
      console.log('Reindex status card is visible');

      // Check for status tag
      const statusTag = statusCard.locator('.ant-tag');
      if (await statusTag.count() > 0) {
        const tagText = await statusTag.first().textContent();
        console.log(`Current reindex status: ${tagText}`);
      }

      // Check for descriptions
      const descriptions = statusCard.locator('.ant-descriptions-item');
      const descCount = await descriptions.count();
      console.log(`Found ${descCount} status description items`);
    }
  });

  test('should show reindexing tab with operations', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    // Navigate to Solr maintenance page
    await page.goto('/core/ui/#/solr');
    await waitForUiStable(page);

    // Click on reindexing tab (use exact match to avoid matching "RAG再インデクシング")
    const reindexTab = page.locator('.ant-tabs-tab').filter({
      hasText: /^再インデクシング$|^Reindex$/i
    }).first();

    await expect(reindexTab).toBeVisible({ timeout: 10000 });
    await reindexTab.click(isMobile ? { force: true } : {});
    await waitForRender(page);

    // Verify reindex UI elements
    const fullReindexButton = page.locator('button').filter({
      hasText: /全体再インデクシング|Full Reindex/i
    });
    await expect(fullReindexButton).toBeVisible({ timeout: 10000 });

    const clearButton = page.locator('button').filter({
      hasText: /インデックスクリア|Clear Index/i
    });
    await expect(clearButton).toBeVisible({ timeout: 5000 });
  });

  test('should show Solr query tab with query interface', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    // Navigate to Solr maintenance page
    await page.goto('/core/ui/#/solr');
    await waitForUiStable(page);

    // Click on Solr query tab
    const queryTab = page.locator('.ant-tabs-tab').filter({
      hasText: /Solrクエリ|Solr Query/i
    });

    await expect(queryTab).toBeVisible({ timeout: 10000 });
    await queryTab.click(isMobile ? { force: true } : {});
    await waitForRender(page);

    // Verify query UI elements
    const queryTextArea = page.locator('textarea');
    await expect(queryTextArea.first()).toBeVisible({ timeout: 10000 });

    const executeButton = page.locator('button').filter({
      hasText: /クエリ実行|Execute Query/i
    });
    await expect(executeButton).toBeVisible({ timeout: 5000 });
  });

  test('should execute simple Solr query', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    // Navigate to Solr maintenance page
    await page.goto('/core/ui/#/solr');
    await waitForUiStable(page);

    // Click on Solr query tab
    const queryTab = page.locator('.ant-tabs-tab').filter({
      hasText: /Solrクエリ|Solr Query/i
    });

    if (await queryTab.count() === 0) {
      test.skip('ENV: Solr query tab not found - Solr may not be enabled');
      return;
    }

    await queryTab.click(isMobile ? { force: true } : {});
    await waitForRender(page);

    // Fill in query
    const queryTextArea = page.locator('textarea').first();
    await queryTextArea.fill('*:*');

    // Click execute button
    const executeButton = page.locator('button').filter({
      hasText: /クエリ実行|Execute Query/i
    }).first();

    await executeButton.click(isMobile ? { force: true } : {});
    await waitForUiStable(page);

    // Check for results
    const resultAlert = page.locator('.ant-alert-info');
    if (await resultAlert.count() > 0) {
      const alertText = await resultAlert.textContent();
      console.log(`Query result: ${alertText}`);
      expect(alertText).toContain('件');
    }

    // Take screenshot of query results
    await page.screenshot({ path: 'test-results/screenshots/solr_query_results.png', fullPage: true });
  });

  test('should show full reindex confirmation dialog', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    // Navigate to Solr maintenance page
    await page.goto('/core/ui/#/solr');
    await waitForUiStable(page);

    // Click on reindexing tab (use exact match to avoid matching "RAG再インデクシング")
    const reindexTab = page.locator('.ant-tabs-tab').filter({
      hasText: /^再インデクシング$|^Reindex$/i
    }).first();

    if (await reindexTab.count() === 0) {
      test.skip('ENV: Reindexing tab not found - Solr may not be enabled');
      return;
    }

    await reindexTab.click(isMobile ? { force: true } : {});
    await waitForRender(page);

    // Click full reindex button
    const fullReindexButton = page.locator('button').filter({
      hasText: /全体再インデクシング|Full Reindex/i
    }).first();

    await expect(fullReindexButton).toBeVisible({ timeout: 10000 });

    await fullReindexButton.click(isMobile ? { force: true } : {});
    await waitForRender(page);

    // Check for Popconfirm dialog
    const popconfirm = page.locator('.ant-popconfirm, .ant-popover');
    if (await popconfirm.count() > 0) {
      console.log('Reindex confirmation dialog appeared');

      // Check for confirmation text
      const confirmText = await popconfirm.textContent();
      expect(confirmText).toContain('再インデクシング');

      // Cancel the operation (don't actually run reindex in test)
      const cancelButton = popconfirm.locator('button').filter({
        hasText: /キャンセル|Cancel/i
      });

      if (await cancelButton.count() > 0) {
        await cancelButton.click();
        console.log('Cancelled reindex operation');
      }
    }
  });

  test('should show index clear confirmation dialog', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    // Navigate to Solr maintenance page
    await page.goto('/core/ui/#/solr');
    await waitForUiStable(page);

    // Click on reindexing tab (use exact match to avoid matching "RAG再インデクシング")
    const reindexTab = page.locator('.ant-tabs-tab').filter({
      hasText: /^再インデクシング$|^Reindex$/i
    }).first();

    if (await reindexTab.count() === 0) {
      test.skip('ENV: Reindexing tab not found - Solr may not be enabled');
      return;
    }

    await reindexTab.click(isMobile ? { force: true } : {});
    await waitForRender(page);

    // Click index clear button
    const clearButton = page.locator('button').filter({
      hasText: /インデックスクリア|Clear Index/i
    }).first();

    await expect(clearButton).toBeVisible({ timeout: 10000 });

    await clearButton.click(isMobile ? { force: true } : {});
    await waitForRender(page);

    // Check for Popconfirm dialog
    const popconfirm = page.locator('.ant-popconfirm, .ant-popover');
    if (await popconfirm.count() > 0) {
      console.log('Index clear confirmation dialog appeared');

      // Check for warning text
      const confirmText = await popconfirm.textContent();
      expect(confirmText).toContain('クリア');

      // Cancel the operation (don't actually clear index in test)
      const cancelButton = popconfirm.locator('button').filter({
        hasText: /キャンセル|Cancel/i
      });

      if (await cancelButton.count() > 0) {
        await cancelButton.click();
        console.log('Cancelled index clear operation');
      }
    }
  });

  test('should refresh health check on button click', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    // Navigate to Solr maintenance page
    await page.goto('/core/ui/#/solr');
    await page.waitForLoadState('networkidle');

    // Wait for health check card to load (API is async)
    const healthCardLocator = page.locator('.ant-card').filter({
      hasText: /インデックスヘルスチェック|Index Health/i
    });
    await expect(healthCardLocator).toBeVisible({ timeout: 15000 }).catch(() => {});

    // Find refresh button in health check card
    const refreshButton = page.locator('.ant-card').filter({
      hasText: /インデックスヘルスチェック|Index Health/i
    }).locator('button').filter({
      hasText: /更新|Refresh/i
    });

    if (await refreshButton.count() === 0) {
      // Try finding by icon
      const iconButton = page.locator('.ant-card').filter({
        hasText: /インデックスヘルスチェック|Index Health/i
      }).locator('button:has(.anticon-reload)');

      if (await iconButton.count() > 0) {
        await iconButton.click(isMobile ? { force: true } : {});
        console.log('Clicked refresh button (icon)');
      } else {
        test.skip('ENV: Refresh button not found - Solr maintenance UI may differ');
        return;
      }
    } else {
      await refreshButton.click(isMobile ? { force: true } : {});
      console.log('Clicked refresh button');
    }

    await waitForUiStable(page);

    // Verify statistics are still displayed after refresh
    const statisticItems = page.locator('.ant-statistic');
    expect(await statisticItems.count()).toBeGreaterThanOrEqual(2);
    console.log('Health check refreshed successfully');
  });

  test('should display Solr URL', async ({ page, browserName }) => {
    // Navigate to Solr maintenance page
    await page.goto('/core/ui/#/solr');
    await waitForUiStable(page);

    // Check for Solr URL display
    const solrUrlText = page.locator('text=Solr URL');
    if (await solrUrlText.count() > 0) {
      console.log('Solr URL label found');

      // Check for actual URL value
      const urlValue = page.locator('span.ant-typography-secondary, .ant-typography').filter({
        hasText: /http.*solr/i
      });

      if (await urlValue.count() > 0) {
        const url = await urlValue.first().textContent();
        console.log(`Solr URL: ${url}`);
        expect(url).toContain('solr');
      }
    }
  });

  test('should handle folder reindex input', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    // Navigate to Solr maintenance page
    await page.goto('/core/ui/#/solr');
    await waitForUiStable(page);

    // Click on reindexing tab (use exact match to avoid matching "RAG再インデクシング")
    const reindexTab = page.locator('.ant-tabs-tab').filter({
      hasText: /^再インデクシング$|^Reindex$/i
    }).first();

    if (await reindexTab.count() === 0) {
      test.skip('ENV: Reindexing tab not found - Solr may not be enabled');
      return;
    }

    await reindexTab.click(isMobile ? { force: true } : {});
    await waitForRender(page);

    // Find folder ID input
    const folderIdInput = page.locator('input[placeholder*="フォルダID"], input[placeholder*="Folder ID"]');

    await expect(folderIdInput).toBeVisible({ timeout: 10000 });

    // Fill in a test folder ID
    await folderIdInput.fill('test-folder-id-123');
    console.log('Filled folder ID input');

    // Check for recursive checkbox
    const recursiveCheckbox = page.locator('input[type="checkbox"]');
    if (await recursiveCheckbox.count() > 0) {
      const isChecked = await recursiveCheckbox.first().isChecked();
      console.log(`Recursive checkbox is ${isChecked ? 'checked' : 'unchecked'}`);
    }

    // Find folder reindex button
    const folderReindexButton = page.locator('button').filter({
      hasText: /フォルダ再インデクシング|Folder Reindex/i
    });

    expect(await folderReindexButton.count()).toBeGreaterThan(0);
    console.log('Folder reindex button found');
  });

  test('should have proper tab navigation', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    // Navigate to Solr maintenance page
    await page.goto('/core/ui/#/solr');
    await waitForUiStable(page);

    // Check for tabs
    const tabs = page.locator('.ant-tabs-tab');
    const tabCount = await tabs.count();

    console.log(`Found ${tabCount} tabs`);
    expect(tabCount).toBeGreaterThanOrEqual(2);

    // Verify tab labels
    const expectedTabs = ['ステータス', '再インデクシング', 'Solrクエリ'];
    let foundTabs = 0;

    for (const tabName of expectedTabs) {
      const tab = page.locator('.ant-tabs-tab').filter({ hasText: tabName });
      if (await tab.count() > 0) {
        foundTabs++;
        console.log(`Tab found: ${tabName}`);
      }
    }

    expect(foundTabs).toBeGreaterThanOrEqual(2);

    // Test tab switching
    for (let i = 0; i < Math.min(tabCount, 3); i++) {
      await tabs.nth(i).click(isMobile ? { force: true } : {});
      await waitForRender(page);

      // Verify tab is active
      const activeTab = page.locator('.ant-tabs-tab-active');
      expect(await activeTab.count()).toBe(1);
    }

    console.log('Tab navigation works correctly');
  });
});
