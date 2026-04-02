import { test, expect } from '@playwright/test';

const BASE_URL = 'http://localhost:8080';
const API_BASE = `${BASE_URL}/core/api/v1/admin`;

test.describe('Connector & Profile Management UI', () => {
  test.beforeEach(async ({ page }) => {
    // Login
    await page.goto(`${BASE_URL}/core/ui/`);
    await page.fill('input[type="text"], input[name="username"], #username', 'admin');
    await page.fill('input[type="password"], input[name="password"], #password', 'admin');
    await page.click('button[type="submit"]');
    await page.waitForTimeout(3000);
  });

  test('should display Connectors tab in Integration Settings', async ({ page }) => {
    await page.goto(`${BASE_URL}/core/ui/#/integration-settings`);
    await page.waitForTimeout(3000);

    const tabs = page.locator('[role="tab"]');
    const connectorTab = tabs.filter({ hasText: /Connector|コネクタ/i });
    await expect(connectorTab).toBeVisible({ timeout: 10000 });
  });

  test('should display Import Profiles tab in Integration Settings', async ({ page }) => {
    await page.goto(`${BASE_URL}/core/ui/#/integration-settings`);
    await page.waitForTimeout(3000);

    const tabs = page.locator('[role="tab"]');
    const profileTab = tabs.filter({ hasText: /Import Profile|インポートプロファイル/i });
    await expect(profileTab).toBeVisible({ timeout: 10000 });
  });

  test('Connector CRUD via UI', async ({ page, request }) => {
    // Cleanup: delete test connector if exists
    try {
      await request.delete(`${API_BASE}/connectors/e2e-test-conn`, {
        headers: { Authorization: 'Basic ' + Buffer.from('admin:admin').toString('base64') },
      });
    } catch { /* ignore */ }

    await page.goto(`${BASE_URL}/core/ui/#/integration-settings`);
    await page.waitForTimeout(3000);

    // Navigate to Connectors tab
    const connectorTab = page.locator('[role="tab"]').filter({ hasText: /Connector|コネクタ/i });
    await connectorTab.click();
    await page.waitForTimeout(2000);

    // Click Create button
    const createBtn = page.locator('button').filter({ hasText: /New|新規|Create|作成/i });
    await expect(createBtn).toBeVisible({ timeout: 5000 });
    await createBtn.click();
    await page.waitForTimeout(1000);

    // Fill form in modal
    await page.fill('[id="connectorId"], [name="connectorId"]', 'e2e-test-conn');
    await page.fill('[id="displayName"], [name="displayName"]', 'E2E Test Connector');
    // Select sourceArchetype
    const archetypeSelect = page.locator('.ant-select').filter({ hasText: /Archetype|類型/i }).first();
    if (await archetypeSelect.isVisible()) {
      await archetypeSelect.click();
      await page.locator('.ant-select-item').filter({ hasText: /FILE_SHARE/i }).click();
    }
    await page.fill('[id="sourceSystem"], [name="sourceSystem"]', 'google_drive');

    // Submit
    const submitBtn = page.locator('.ant-modal button[type="submit"], .ant-modal button').filter({ hasText: /OK|Save|保存|作成/i });
    await submitBtn.click();
    await page.waitForTimeout(2000);

    // Verify connector appears in table
    const table = page.locator('.ant-table');
    await expect(table.locator('text=e2e-test-conn')).toBeVisible({ timeout: 5000 });

    // Cleanup
    await request.delete(`${API_BASE}/connectors/e2e-test-conn`, {
      headers: { Authorization: 'Basic ' + Buffer.from('admin:admin').toString('base64') },
    });
  });

  test('Import Profile CRUD via UI', async ({ page, request }) => {
    // Cleanup
    try {
      await request.delete(`${API_BASE}/import-profiles/e2e-test-profile`, {
        headers: { Authorization: 'Basic ' + Buffer.from('admin:admin').toString('base64') },
      });
    } catch { /* ignore */ }

    await page.goto(`${BASE_URL}/core/ui/#/integration-settings`);
    await page.waitForTimeout(3000);

    // Navigate to Import Profiles tab
    const profileTab = page.locator('[role="tab"]').filter({ hasText: /Import Profile|インポートプロファイル/i });
    await profileTab.click();
    await page.waitForTimeout(2000);

    // Click Create button
    const createBtn = page.locator('button').filter({ hasText: /New|新規|Create|作成/i });
    await expect(createBtn).toBeVisible({ timeout: 5000 });
    await createBtn.click();
    await page.waitForTimeout(1000);

    // Fill form
    await page.fill('[id="profileId"], [name="profileId"]', 'e2e-test-profile');
    await page.fill('[id="displayName"], [name="displayName"]', 'E2E Test Profile');
    await page.fill('[id="targetFolderId"], [name="targetFolderId"]', 'ROOT_FOLDER_ID');

    // Submit
    const submitBtn = page.locator('.ant-modal button[type="submit"], .ant-modal button').filter({ hasText: /OK|Save|保存|作成/i });
    await submitBtn.click();
    await page.waitForTimeout(2000);

    // Verify profile appears in table
    const table = page.locator('.ant-table');
    await expect(table.locator('text=e2e-test-profile')).toBeVisible({ timeout: 5000 });

    // Cleanup
    await request.delete(`${API_BASE}/import-profiles/e2e-test-profile`, {
      headers: { Authorization: 'Basic ' + Buffer.from('admin:admin').toString('base64') },
    });
  });
});
