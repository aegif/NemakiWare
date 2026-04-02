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

  test('Connector CRUD via API', async ({ request }) => {
    const headers = { Authorization: 'Basic ' + Buffer.from('admin:admin').toString('base64'), 'Content-Type': 'application/json' };

    // Cleanup
    await request.delete(`${API_BASE}/connectors/e2e-test-conn`, { headers }).catch(() => {});

    // Create
    const createRes = await request.post(`${API_BASE}/connectors`, {
      headers,
      data: { connectorId: 'e2e-test-conn', displayName: 'E2E Test', sourceArchetype: 'FILE_SHARE', sourceSystem: 'google_drive', enabled: true },
    });
    expect(createRes.ok()).toBe(true);
    const createBody = await createRes.json();
    expect(createBody.connectorId).toBe('e2e-test-conn');

    // List
    const listRes = await request.get(`${API_BASE}/connectors`, { headers });
    const connectors = await listRes.json();
    expect(connectors.some((c: { connectorId: string }) => c.connectorId === 'e2e-test-conn')).toBe(true);

    // Get
    const getRes = await request.get(`${API_BASE}/connectors/e2e-test-conn`, { headers });
    expect(getRes.ok()).toBe(true);
    const connector = await getRes.json();
    expect(connector.sourceSystem).toBe('google_drive');

    // Update
    const updateRes = await request.put(`${API_BASE}/connectors/e2e-test-conn`, {
      headers,
      data: { connectorId: 'e2e-test-conn', displayName: 'Updated', sourceArchetype: 'FILE_SHARE', sourceSystem: 'google_drive', enabled: false },
    });
    expect(updateRes.ok()).toBe(true);

    // Delete
    const deleteRes = await request.delete(`${API_BASE}/connectors/e2e-test-conn`, { headers });
    expect(deleteRes.ok()).toBe(true);
  });

  test('Import Profile CRUD via API', async ({ request }) => {
    const headers = { Authorization: 'Basic ' + Buffer.from('admin:admin').toString('base64'), 'Content-Type': 'application/json' };

    // Cleanup
    await request.delete(`${API_BASE}/import-profiles/e2e-test-profile`, { headers }).catch(() => {});

    // Create
    const createRes = await request.post(`${API_BASE}/import-profiles`, {
      headers,
      data: { profileId: 'e2e-test-profile', displayName: 'E2E Profile', repositoryId: 'bedroom', targetFolderId: 'ROOT', enabled: true },
    });
    expect(createRes.ok()).toBe(true);
    const createBody = await createRes.json();
    expect(createBody.profileId).toBe('e2e-test-profile');

    // List
    const listRes = await request.get(`${API_BASE}/import-profiles?repositoryId=bedroom`, { headers });
    const profiles = await listRes.json();
    expect(profiles.some((p: { profileId: string }) => p.profileId === 'e2e-test-profile')).toBe(true);

    // Get
    const getRes = await request.get(`${API_BASE}/import-profiles/e2e-test-profile`, { headers });
    expect(getRes.ok()).toBe(true);

    // Delete
    const deleteRes = await request.delete(`${API_BASE}/import-profiles/e2e-test-profile`, { headers });
    expect(deleteRes.ok()).toBe(true);
  });
});
