import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { waitForUiStable, waitForRender } from '../utils/wait-helpers';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:8080';
const PW_USER = process.env.PW_BASIC_USER || 'admin';
const PW_PASS = process.env.PW_BASIC_PASS || 'admin';
const AUTH_HEADER = 'Basic ' + Buffer.from(`${PW_USER}:${PW_PASS}`).toString('base64');

const MCP_SETTINGS_URL = `${BASE_URL}/core/api/v1/admin/integration-settings/mcp`;
const MCP_MESSAGE_URL = `${BASE_URL}/core/mcp/message`;
const MCP_INFO_URL = `${BASE_URL}/core/mcp/info`;
const MCP_HEALTH_URL = `${BASE_URL}/core/mcp/health`;

/** Helper to set tools.list.public via API */
async function setToolsListPublic(request: any, value: string) {
  return request.put(MCP_SETTINGS_URL, {
    headers: { 'Authorization': AUTH_HEADER, 'X-Requested-With': 'XMLHttpRequest', 'Content-Type': 'application/json' },
    data: { 'mcp.tools.list.public': value },
  });
}

test.describe('MCP Settings', () => {

  // Restore default after each test
  test.afterEach(async ({ request }) => {
    await setToolsListPublic(request, 'true');
  });

  // ── API: Read settings ──

  test('should read MCP settings via admin API', async ({ request }) => {
    const res = await request.get(MCP_SETTINGS_URL, {
      headers: { 'Authorization': AUTH_HEADER, 'X-Requested-With': 'XMLHttpRequest' },
    });
    expect(res.status()).toBe(200);
    const body = await res.json();
    expect(body.settings['mcp.tools.list.public']).toBe('true');
  });

  // ── API: Toggle off → deny anonymous ──

  test('should deny anonymous tools/list when set to false', async ({ request }) => {
    await setToolsListPublic(request, 'false');

    // Use native fetch for truly anonymous request (no Playwright session cookies)
    const res = await fetch(MCP_MESSAGE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ jsonrpc: '2.0', method: 'tools/list', id: 1 }),
    });
    const body = await res.json();
    expect(body.error).toBeDefined();
    expect(body.error.message).toContain('Authentication required');
  });

  test('should allow authenticated tools/list when set to false', async ({ request }) => {
    await setToolsListPublic(request, 'false');

    const res = await request.post(MCP_MESSAGE_URL, {
      headers: { 'Content-Type': 'application/json', 'Authorization': AUTH_HEADER },
      data: { jsonrpc: '2.0', method: 'tools/list', id: 1 },
    });
    const body = await res.json();
    expect(body.result).toBeDefined();
    expect(body.result.tools.length).toBeGreaterThan(0);
  });

  // ── API: Toggle on → restore anonymous ──

  test('should restore anonymous access after toggle on', async ({ request }) => {
    await setToolsListPublic(request, 'false');
    await setToolsListPublic(request, 'true');

    const res = await request.post(MCP_MESSAGE_URL, {
      headers: { 'Content-Type': 'application/json' },
      data: { jsonrpc: '2.0', method: 'tools/list', id: 1 },
    });
    const body = await res.json();
    expect(body.result.tools.length).toBeGreaterThan(0);
  });

  // ── API: /mcp/info security ──

  test('/mcp/info should not expose tools', async ({ request }) => {
    const res = await request.get(MCP_INFO_URL);
    expect(res.status()).toBe(200);
    const body = await res.json();
    expect(body.serverInfo).toBeDefined();
    expect(body.serverInfo.name).toBe('nemakiware-mcp');
    expect(body.capabilities).toBeDefined();
    expect(body.tools).toBeUndefined();
  });

  // ── API: /mcp/health ──

  test('/mcp/health should be anonymously accessible', async ({ request }) => {
    const res = await request.get(MCP_HEALTH_URL);
    expect(res.status()).toBe(200);
  });

  // ── API: CSRF on settings ──

  test('should reject settings update without CSRF header (Basic auth only)', async ({ request }) => {
    const res = await request.put(MCP_SETTINGS_URL, {
      headers: { 'Authorization': AUTH_HEADER, 'Content-Type': 'application/json' },
      data: { 'mcp.tools.list.public': 'false' },
    });
    expect(res.status()).toBe(403);
  });

  // ── UI Tests ──

  test('should show MCP tab with switch and endpoints', async ({ page }) => {
    const auth = new AuthHelper(page);
    await auth.login();

    await page.goto(`${BASE_URL}/core/ui/#/integration-settings`);
    await waitForUiStable(page);

    const mcpTab = page.getByRole('tab', { name: /MCP/i });
    await expect(mcpTab).toBeVisible({ timeout: 10000 });
    await mcpTab.click();
    await waitForUiStable(page);

    // Switch toggle should be visible
    await expect(page.getByRole('switch')).toBeVisible({ timeout: 5000 });

    // Endpoint references should be visible
    await expect(page.locator('text=/\\/core\\/mcp\\/message/')).toBeVisible();
  });

  test('should toggle switch and save via UI', async ({ page, request }) => {
    test.slow(); // UI state sync may need extra time
    // Ensure clean state via API before page load
    await setToolsListPublic(request, 'true');
    // Brief wait for CouchDB write to propagate
    await page.waitForTimeout(1000);

    const auth = new AuthHelper(page);
    await auth.login();

    await page.goto(`${BASE_URL}/core/ui/#/integration-settings`);
    await waitForUiStable(page);

    const mcpTab = page.getByRole('tab', { name: /MCP/i });
    await mcpTab.click();
    await waitForUiStable(page);

    // Toggle off (switch should be ON initially)
    const toggle = page.getByRole('switch');
    await expect(toggle).toBeChecked({ timeout: 5000 });
    await toggle.click();
    // Ant Design Switch needs a moment for React re-render
    await page.waitForTimeout(500);
    await expect(toggle).not.toBeChecked({ timeout: 5000 });

    // Save button should now be enabled
    const saveButton = page.getByRole('button', { name: /保.*存|Save/i });
    await expect(saveButton).toBeEnabled({ timeout: 10000 });
    await saveButton.click();
    await waitForUiStable(page);

    // Verify via API
    const res = await request.get(MCP_SETTINGS_URL, {
      headers: { 'Authorization': AUTH_HEADER, 'X-Requested-With': 'XMLHttpRequest' },
    });
    const body = await res.json();
    expect(body.settings['mcp.tools.list.public']).toBe('false');
  });
});
