import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { waitForUiStable } from '../utils/wait-helpers';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:8080';
const AUTH_HEADER = 'Basic ' + Buffer.from('admin:admin').toString('base64');

test.describe('MCP Settings', () => {

  // ── API Tests ──

  test('should read MCP settings via API', async ({ request }) => {
    const res = await request.get(`${BASE_URL}/core/api/v1/admin/integration-settings/mcp`, {
      headers: { 'Authorization': AUTH_HEADER, 'X-Requested-With': 'XMLHttpRequest' },
    });
    expect(res.status()).toBe(200);
    const body = await res.json();
    expect(body.settings).toHaveProperty('mcp.tools.list.public');
    expect(['true', 'false']).toContain(body.settings['mcp.tools.list.public']);
  });

  test('should toggle tools/list visibility via API', async ({ request }) => {
    // Set to false
    const off = await request.put(`${BASE_URL}/core/api/v1/admin/integration-settings/mcp`, {
      headers: { 'Authorization': AUTH_HEADER, 'X-Requested-With': 'XMLHttpRequest', 'Content-Type': 'application/json' },
      data: { 'mcp.tools.list.public': 'false' },
    });
    expect(off.status()).toBe(200);

    // Anonymous tools/list should fail
    const denied = await request.post(`${BASE_URL}/core/mcp/message`, {
      headers: { 'Content-Type': 'application/json' },
      data: { jsonrpc: '2.0', method: 'tools/list', id: 1 },
    });
    const deniedBody = await denied.json();
    expect(deniedBody.error).toBeDefined();
    expect(deniedBody.error.message).toContain('Authentication required');

    // Authenticated tools/list should succeed
    const authed = await request.post(`${BASE_URL}/core/mcp/message`, {
      headers: { 'Content-Type': 'application/json', 'Authorization': AUTH_HEADER },
      data: { jsonrpc: '2.0', method: 'tools/list', id: 2 },
    });
    const authedBody = await authed.json();
    expect(authedBody.result.tools.length).toBeGreaterThan(0);

    // Restore to true
    const on = await request.put(`${BASE_URL}/core/api/v1/admin/integration-settings/mcp`, {
      headers: { 'Authorization': AUTH_HEADER, 'X-Requested-With': 'XMLHttpRequest', 'Content-Type': 'application/json' },
      data: { 'mcp.tools.list.public': 'true' },
    });
    expect(on.status()).toBe(200);

    // Anonymous tools/list should work again
    const restored = await request.post(`${BASE_URL}/core/mcp/message`, {
      headers: { 'Content-Type': 'application/json' },
      data: { jsonrpc: '2.0', method: 'tools/list', id: 3 },
    });
    const restoredBody = await restored.json();
    expect(restoredBody.result.tools.length).toBeGreaterThan(0);
  });

  // ── UI Test ──

  test('should show MCP settings tab in admin UI', async ({ page }) => {
    const auth = new AuthHelper(page);
    await auth.login();

    // Navigate to Integration Settings
    await page.goto(`${BASE_URL}/core/ui/#/integration`);
    await waitForUiStable(page);

    // Find and click MCP tab
    const mcpTab = page.getByRole('tab', { name: /MCP/i });
    await expect(mcpTab).toBeVisible({ timeout: 10000 });
    await mcpTab.click();
    await waitForUiStable(page);

    // Verify switch is visible
    const toggle = page.locator('.ant-switch');
    await expect(toggle).toBeVisible({ timeout: 5000 });

    // Verify endpoint reference is visible
    await expect(page.locator('text=/\\/core\\/mcp\\/message/')).toBeVisible();
  });
});
