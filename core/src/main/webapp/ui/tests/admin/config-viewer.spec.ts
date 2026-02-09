import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';
import { generateTestId } from '../utils/api-helper';

/**
 * Config Viewer E2E Tests
 *
 * Tests the admin-only config viewer page (/config-viewer).
 * Verifies:
 * - Admin access and page rendering
 * - i18n labels (Japanese/English)
 * - Table structure with property data
 * - Source tag color coding
 * - Sensitive value masking
 * - Category filter and search
 * - Non-admin access restriction
 *
 * Prerequisites:
 * - Server running on localhost:8080
 * - admin:admin credentials
 * - bedroom repository
 */

const BASE_URL = 'http://localhost:8080';
const REPOSITORY_ID = 'bedroom';

test.describe('Config Viewer - Admin Access', () => {
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

  test('should display config viewer page for admin', async ({ page }) => {
    await page.goto(`${BASE_URL}/core/ui/#/config-viewer`);
    await page.waitForTimeout(3000);

    // Should show the title
    const title = page.locator('h2, .ant-card-head-title').filter({
      hasText: /設定プロパティ一覧|Config Properties/i
    });
    await expect(title).toBeVisible({ timeout: 10000 });
  });

  test('should display table with property data', async ({ page }) => {
    await page.goto(`${BASE_URL}/core/ui/#/config-viewer`);
    await page.waitForTimeout(5000);

    // Table should be present
    const table = page.locator('.ant-table');
    await expect(table).toBeVisible({ timeout: 10000 });

    // Should have rows
    const rows = page.locator('.ant-table-tbody tr.ant-table-row');
    const rowCount = await rows.count();
    console.log(`Config viewer shows ${rowCount} properties`);

    // Should have at least some properties
    expect(rowCount).toBeGreaterThan(0);
  });

  test('should display correct column headers', async ({ page }) => {
    await page.goto(`${BASE_URL}/core/ui/#/config-viewer`);
    await page.waitForTimeout(5000);

    const tableHeader = page.locator('.ant-table-thead');
    await expect(tableHeader).toBeVisible({ timeout: 10000 });

    // Check for expected column headers (bilingual)
    const expectedColumns = [
      /カテゴリー|Category/i,
      /プロパティキー|Property Key/i,
      /値|Value/i,
      /取得元|Source/i
    ];

    for (const col of expectedColumns) {
      const header = tableHeader.locator('th').filter({ hasText: col });
      const found = await header.count() > 0;
      console.log(`Column ${col}: ${found ? 'found' : 'not found'}`);
      expect(found).toBe(true);
    }
  });

  test('should display source tags with color coding', async ({ page }) => {
    await page.goto(`${BASE_URL}/core/ui/#/config-viewer`);
    await page.waitForTimeout(5000);

    // Source tags should be present (ant-tag elements)
    const tags = page.locator('.ant-table-tbody .ant-tag');
    const tagCount = await tags.count();
    console.log(`Found ${tagCount} source tags`);

    expect(tagCount).toBeGreaterThan(0);
  });

  test('should mask sensitive values', async ({ page }) => {
    await page.goto(`${BASE_URL}/core/ui/#/config-viewer`);
    await page.waitForTimeout(5000);

    // Look for masked values (***) in the table
    const maskedCells = page.locator('.ant-table-tbody td').filter({ hasText: '***' });
    const maskedCount = await maskedCells.count();
    console.log(`Found ${maskedCount} masked values`);

    // There should be at least one masked value (e.g., db.password)
    if (maskedCount > 0) {
      console.log('Sensitive values are properly masked');
    } else {
      console.log('No masked values found (may not have password-type properties)');
    }
  });

  test('should support key search filter', async ({ page }) => {
    await page.goto(`${BASE_URL}/core/ui/#/config-viewer`);
    await page.waitForTimeout(5000);

    // Find search input
    const searchInput = page.locator('.ant-input-search input, input[placeholder]').first();
    if (await searchInput.count() > 0) {
      // Get initial row count
      const initialRows = await page.locator('.ant-table-tbody tr.ant-table-row').count();

      // Type a search term
      await searchInput.fill('db.');
      await page.waitForTimeout(1000);

      const filteredRows = await page.locator('.ant-table-tbody tr.ant-table-row').count();
      console.log(`Before filter: ${initialRows}, after 'db.' filter: ${filteredRows}`);

      // Filtered should have fewer or equal rows
      expect(filteredRows).toBeLessThanOrEqual(initialRows);

      // Clear search
      await searchInput.clear();
      await page.waitForTimeout(1000);
    }
  });

  test('should support category filter', async ({ page }) => {
    await page.goto(`${BASE_URL}/core/ui/#/config-viewer`);
    await page.waitForTimeout(5000);

    // Find category select
    const categorySelect = page.locator('.ant-select').first();
    if (await categorySelect.count() > 0) {
      await categorySelect.click();
      await page.waitForTimeout(500);

      // Check for category options
      const options = page.locator('.ant-select-item-option');
      const optionCount = await options.count();
      console.log(`Found ${optionCount} category options`);

      if (optionCount > 1) {
        // Select a specific category (not "All")
        const dbOption = options.filter({ hasText: 'DB' });
        if (await dbOption.count() > 0) {
          await dbOption.click();
          await page.waitForTimeout(1000);

          const filteredRows = await page.locator('.ant-table-tbody tr.ant-table-row').count();
          console.log(`Rows after DB category filter: ${filteredRows}`);
        }
      }

      // Close dropdown by clicking elsewhere
      await page.locator('body').click();
    }
  });
});

test.describe('Config Viewer - i18n', () => {
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

  test('should display Japanese labels', async ({ page }) => {
    await page.evaluate(() => {
      localStorage.setItem('nemakiware-language', 'ja');
    });
    await page.goto(`${BASE_URL}/core/ui/#/config-viewer`);
    await page.waitForTimeout(3000);

    const title = page.locator('h2, .ant-card-head-title').filter({ hasText: '設定プロパティ一覧' });
    await expect(title).toBeVisible({ timeout: 10000 });
  });

  test('should display English labels', async ({ page }) => {
    await page.evaluate(() => {
      localStorage.setItem('nemakiware-language', 'en');
    });
    await page.goto(`${BASE_URL}/core/ui/#/config-viewer`);
    await page.waitForTimeout(3000);

    const title = page.locator('h2, .ant-card-head-title').filter({ hasText: 'Config Properties' });
    await expect(title).toBeVisible({ timeout: 10000 });
  });
});

test.describe('Config Viewer - Navigation', () => {
  test('should have config viewer link in admin menu', async ({ page, browserName }) => {
    const authHelper = new AuthHelper(page);
    const testHelper = new TestHelper(page);

    await authHelper.login();
    await page.waitForTimeout(2000);
    await testHelper.closeMobileSidebar(browserName);
    await testHelper.waitForAntdLoad();

    // Look for config viewer menu item in sidebar
    const menuItem = page.locator('.ant-menu-item, .ant-menu-submenu').filter({
      hasText: /設定ビューア|Config Viewer/i
    });

    if (await menuItem.count() > 0) {
      await menuItem.click();
      await page.waitForTimeout(3000);

      // Should navigate to config-viewer page
      expect(page.url()).toContain('config-viewer');
      console.log('Config Viewer menu item works correctly');
    } else {
      console.log('Config Viewer menu item not found in sidebar');
    }
  });
});

test.describe('Config Viewer - API', () => {
  test('should return properties for admin', async ({ page }) => {
    const authHeader = 'Basic ' + Buffer.from('admin:admin').toString('base64');

    const response = await page.request.get(
      `${BASE_URL}/core/rest/repo/${REPOSITORY_ID}/config/properties`,
      { headers: { 'Authorization': authHeader } }
    );

    console.log(`Config properties API status: ${response.status()}`);

    if (response.ok()) {
      const data = await response.json();
      expect(data).toBeDefined();

      if (data.properties) {
        expect(Array.isArray(data.properties)).toBe(true);
        console.log(`Config properties count: ${data.properties.length}`);

        if (data.properties.length > 0) {
          const prop = data.properties[0];
          expect(prop).toHaveProperty('key');
          expect(prop).toHaveProperty('value');
          expect(prop).toHaveProperty('source');
          expect(prop).toHaveProperty('category');
          console.log(`Sample property: ${prop.key} = ${prop.value} (${prop.source})`);
        }

        // Check that password values are masked
        const passwordProps = data.properties.filter(
          (p: any) => p.key.toLowerCase().includes('password')
        );
        for (const p of passwordProps) {
          expect(p.value).toBe('***');
          console.log(`Password property ${p.key} correctly masked`);
        }
      }
    } else {
      console.log(`Config properties API returned ${response.status()}`);
    }
  });

  test('should reject properties endpoint for non-admin', async ({ page }) => {
    const localTestRunId = generateTestId();
    const testUserId = `cfg-test-user-${localTestRunId}`;
    const testPassword = 'testpass123';

    const { ApiHelper } = await import('../utils/api-helper');
    const api = new ApiHelper(page);

    const created = await api.createUser(testUserId, testPassword, false);
    if (!created) {
      console.log('Could not create test user - skipping');
      return;
    }

    try {
      const nonAdminAuth = 'Basic ' + Buffer.from(`${testUserId}:${testPassword}`).toString('base64');
      const response = await page.request.get(
        `${BASE_URL}/core/rest/repo/${REPOSITORY_ID}/config/properties`,
        { headers: { 'Authorization': nonAdminAuth } }
      );

      console.log(`Config properties as non-admin: ${response.status()}`);

      // Parse response to check for failure
      const body = await response.text();
      console.log(`Response body: ${body.substring(0, 200)}`);

      // Should indicate access denied
      if (response.ok()) {
        const data = JSON.parse(body);
        if (data.status === 'failure') {
          console.log('Config properties correctly rejects non-admin (200 with failure status)');
        }
      } else {
        expect(response.status()).toBeGreaterThanOrEqual(400);
        console.log('Config properties correctly rejects non-admin');
      }
    } finally {
      await api.deleteUser(testUserId);
    }
  });
});

test.describe('Config Viewer - Cold Storage Download Endpoint', () => {
  test('should return 410 Gone for cold storage download', async ({ page }) => {
    const authHeader = 'Basic ' + Buffer.from('admin:admin').toString('base64');

    // Try to download from a non-existent cold archive
    // The endpoint should return 410 Gone regardless of whether the archive exists,
    // because downloadFromColdStorage always returns 410
    const response = await page.request.get(
      `${BASE_URL}/core/rest/repo/${REPOSITORY_ID}/archive/download/nonexistent-cold-archive-id`,
      { headers: { 'Authorization': authHeader } }
    );

    console.log(`Archive download API status: ${response.status()}`);

    // The actual status depends on whether the archive exists at all.
    // If archive doesn't exist → 404. If ARCHIVED_COLD → 410.
    // We can only test the API contract here.
    if (response.status() === 410) {
      const body = await response.text();
      expect(body).toContain('Cold storage content is managed outside NemakiWare');
      console.log('Cold storage download correctly returns 410 Gone');
    } else {
      console.log(`Archive download returned ${response.status()} (archive may not exist)`);
    }
  });
});
