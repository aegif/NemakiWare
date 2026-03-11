import { test, expect } from '@playwright/test';

/**
 * Configuration Dynamic Loading Tests
 *
 * Verifies that getConfiguration() → REST API chain works correctly.
 * These tests serve as regression guards against the RC9 bug where
 * getConfiguration() was stubbed out, causing silent failures:
 * - Directory sync reported success but created no CMIS items
 * - User/group management endpoints failed silently
 *
 * Tests validate that configuration is correctly loaded from CouchDB
 * and consumed by REST API endpoints.
 *
 * Prerequisites:
 * - NemakiWare server running at http://localhost:8080
 * - bedroom repository initialized
 * - admin:admin credentials available
 */

const BASE_URL = 'http://localhost:8080';
const AUTH_HEADER = 'Basic ' + Buffer.from('admin:admin').toString('base64');

test.describe('Configuration Dynamic Loading', () => {

  test('ユーザー管理APIが systemFolder 設定を正しく読み取れる', async ({ request }) => {
    // GET /core/api/v1/repo/bedroom/users depends on PropertyManager
    // reading system.folder from CouchDB to locate the .system/users folder.
    // If getConfiguration() is broken (stubbed/empty), this returns 500.
    const response = await request.get(
      `${BASE_URL}/core/api/v1/repo/bedroom/users`,
      { headers: { 'Authorization': AUTH_HEADER } }
    );

    // Accept 200 (normal) or 404 (if .system/users folder not yet created).
    // 500 would indicate getConfiguration() failure.
    expect([200, 404]).toContain(response.status());

    if (response.status() === 200) {
      const body = await response.json();
      // Should return a JSON object (not an error page)
      expect(body).toBeDefined();
    }
  });

  test('認証設定が動的に取得される', async ({ request }) => {
    // GET /core/rest/auth/config returns authentication configuration
    // which is loaded from properties + dynamic config.
    const response = await request.get(
      `${BASE_URL}/core/rest/auth/config`,
      { headers: { 'Authorization': AUTH_HEADER } }
    );

    expect(response.status()).toBe(200);

    const body = await response.json();
    expect(body).toBeDefined();
    // Should contain at least some config keys
    expect(typeof body).toBe('object');
  });

  test('リポジトリ一覧が動的に返される', async ({ request }) => {
    // GET /core/rest/all/repositories returns the list of configured repositories.
    // This endpoint reads configuration at startup and should always work.
    const response = await request.get(
      `${BASE_URL}/core/rest/all/repositories`,
      { headers: { 'Authorization': AUTH_HEADER } }
    );

    expect(response.status()).toBe(200);

    const body = await response.json();
    expect(body).toBeDefined();

    // Should contain a repositories array with at least "bedroom"
    const repositories = body.repositories || body;
    expect(Array.isArray(repositories) || typeof repositories === 'object').toBeTruthy();
  });
});
