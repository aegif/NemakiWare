import { test, expect } from '@playwright/test';

/**
 * LDAP Directory Sync API E2E Tests
 *
 * Tests for LDAP synchronization endpoints.
 * Uses beforeAll to detect whether LDAP is actually connected,
 * enabling deeper validation when OpenLDAP is available.
 *
 * Endpoints: /core/rest/repo/{repositoryId}/sync/
 */

const BASE_URL = 'http://localhost:8080';
const REPO_ID = 'bedroom';
const SYNC_BASE = `${BASE_URL}/core/rest/repo/${REPO_ID}/sync`;
const AUTH_HEADER = 'Basic ' + Buffer.from('admin:admin').toString('base64');

let ldapConnected = false;

test.beforeAll(async ({ request }) => {
  try {
    const response = await request.get(`${SYNC_BASE}/test-connection`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    if (response.ok()) {
      const data = await response.json();
      ldapConnected = data.connectionSuccess === true;
    }
  } catch {
    ldapConnected = false;
  }
  console.log(`LDAP connected: ${ldapConnected}`);
});

test.describe('LDAP Sync - Endpoint Availability', () => {

  test('GET /sync/status returns JSON status', async ({ request }) => {
    const response = await request.get(`${SYNC_BASE}/status`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.status).toBeDefined();
  });

  test('GET /sync/config returns sync configuration', async ({ request }) => {
    const response = await request.get(`${SYNC_BASE}/config`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.config).toBeDefined();
    expect(typeof data.config.enabled).toBe('boolean');
    expect(data.config.ldapUrl).toBeDefined();
    expect(data.config.ldapBaseDn).toBeDefined();
  });

  test('GET /sync/test-connection returns connection result', async ({ request }) => {
    const response = await request.get(`${SYNC_BASE}/test-connection`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.status).toBeDefined();
    // connectionSuccess should be boolean
    expect(typeof data.connectionSuccess).toBe('boolean');
  });

  test('GET /sync/preview returns preview data', async ({ request }) => {
    const response = await request.get(`${SYNC_BASE}/preview`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.status).toBeDefined();
  });

  test('POST /sync/trigger with dryRun=true executes safely', async ({ request }) => {
    const response = await request.post(`${SYNC_BASE}/trigger?dryRun=true`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.status).toBeDefined();
  });

  test('GET /sync/status without auth returns 401 or 200', async ({ request }) => {
    const response = await request.get(`${SYNC_BASE}/status`);
    expect([200, 401]).toContain(response.status());
  });

  test('GET /sync/status with invalid repository returns error', async ({ request }) => {
    const invalidUrl = `${BASE_URL}/core/rest/repo/nonexistent-repo/sync/status`;
    const response = await request.get(invalidUrl, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBeGreaterThanOrEqual(400);
  });
});

test.describe('LDAP Sync - Connection Validation (LDAP required)', () => {

  test.beforeEach(async () => {
    test.skip(!ldapConnected, 'LDAP not connected - skipping connection tests');
  });

  test('test-connection reports success when LDAP is available', async ({ request }) => {
    const response = await request.get(`${SYNC_BASE}/test-connection`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.connectionSuccess).toBe(true);
    expect(data.message).toContain('Successfully');
  });

  test('config shows enabled=true and correct LDAP URL', async ({ request }) => {
    const response = await request.get(`${SYNC_BASE}/config`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    const data = await response.json();
    expect(data.config.enabled).toBe(true);
    expect(data.config.ldapUrl).toContain('ldap://');
    expect(data.config.userSearchBase).toBe('ou=users');
    expect(data.config.groupSearchBase).toBe('ou=groups');
    expect(data.config.userIdAttribute).toBe('uid');
    expect(data.config.groupIdAttribute).toBe('cn');
  });
});

test.describe('LDAP Sync - Preview & Dry Run (LDAP required)', () => {

  test.beforeEach(async () => {
    test.skip(!ldapConnected, 'LDAP not connected - skipping sync tests');
  });

  test('preview detects LDAP users to sync', async ({ request }) => {
    const response = await request.get(`${SYNC_BASE}/preview`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.syncResult).toBeDefined();
    expect(data.syncResult.dryRun).toBe(true);
    expect(data.syncResult.status).toBe('SUCCESS');
    // OpenLDAP has 8 test users
    expect(data.syncResult.usersAdded + data.syncResult.usersUpdated + data.syncResult.usersSkipped).toBeGreaterThanOrEqual(1);
  });

  test('preview detects LDAP groups to sync', async ({ request }) => {
    const response = await request.get(`${SYNC_BASE}/preview`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    const data = await response.json();
    expect(data.syncResult.groupsCreated + data.syncResult.groupsUpdated + data.syncResult.groupsSkipped).toBeGreaterThanOrEqual(1);
  });

  test('preview has no errors', async ({ request }) => {
    const response = await request.get(`${SYNC_BASE}/preview`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    const data = await response.json();
    expect(data.syncResult.errors).toBeDefined();
    expect(data.syncResult.errors.length).toBe(0);
  });

  test('dry run trigger returns SUCCESS with syncResult', async ({ request }) => {
    const response = await request.post(`${SYNC_BASE}/trigger?dryRun=true`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.syncResult).toBeDefined();
    expect(data.syncResult.dryRun).toBe(true);
    expect(data.syncResult.status).toBe('SUCCESS');
    expect(data.syncResult.repositoryId).toBe(REPO_ID);
  });
});

test.describe.serial('LDAP Sync - Full Sync Execution (LDAP required)', () => {

  test.beforeEach(async () => {
    test.skip(!ldapConnected, 'LDAP not connected - skipping full sync tests');
  });

  test('POST /sync/trigger (actual sync) creates users and groups', async ({ request }) => {
    const response = await request.post(`${SYNC_BASE}/trigger`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.syncResult).toBeDefined();
    expect(data.syncResult.dryRun).toBe(false);
    expect(data.syncResult.status).toBe('SUCCESS');
    // Should have synced users and groups
    const totalUsers = data.syncResult.usersAdded + data.syncResult.usersUpdated + data.syncResult.usersSkipped;
    expect(totalUsers).toBeGreaterThanOrEqual(1);
    console.log(`Sync result: ${data.syncResult.usersAdded} users added, ${data.syncResult.groupsCreated} groups created`);
  });

  test('status shows last sync result after sync execution', async ({ request }) => {
    const response = await request.get(`${SYNC_BASE}/status`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.lastSyncResult).toBeDefined();
    expect(data.lastSyncResult.status).toBe('SUCCESS');
    expect(data.lastSyncResult.dryRun).toBe(false);
  });

  test('synced LDAP users exist in NemakiWare user list', async ({ request }) => {
    // Check that LDAP users were created in NemakiWare
    const response = await request.get(`${BASE_URL}/core/rest/repo/${REPO_ID}/user/list`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    const userIds = Array.isArray(data)
      ? data.map((u: any) => u.userId || u.id)
      : (data.users || []).map((u: any) => u.userId || u.id);

    // At least some LDAP users should exist (e.g., yamada, suzuki from seed data)
    const ldapUsers = ['yamada', 'suzuki', 'tanaka', 'sato', 'watanabe', 'ldapuser1', 'ldapuser2', 'ldapadmin'];
    const foundUsers = ldapUsers.filter(u => userIds.includes(u));
    expect(foundUsers.length).toBeGreaterThanOrEqual(1);
    console.log(`Found LDAP users in NemakiWare: ${foundUsers.join(', ')}`);
  });

  test('synced LDAP groups exist in NemakiWare group list', async ({ request }) => {
    const response = await request.get(`${BASE_URL}/core/rest/repo/${REPO_ID}/group/list`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    const groupIds = Array.isArray(data)
      ? data.map((g: any) => g.groupId || g.id)
      : (data.groups || []).map((g: any) => g.groupId || g.id);

    // LDAP groups should exist with ldap_ prefix
    const expectedGroups = ['ldap_engineering', 'ldap_sales', 'ldap_managers', 'ldap_all-staff', 'ldap_project-alpha'];
    const foundGroups = expectedGroups.filter(g => groupIds.includes(g));
    expect(foundGroups.length).toBeGreaterThanOrEqual(1);
    console.log(`Found LDAP groups in NemakiWare: ${foundGroups.join(', ')}`);
  });

  test('second sync is idempotent (no new additions)', async ({ request }) => {
    const response = await request.post(`${SYNC_BASE}/trigger`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.syncResult.status).toBe('SUCCESS');
    // Second run should show updates/skips instead of new additions
    expect(data.syncResult.usersAdded).toBe(0);
    expect(data.syncResult.groupsCreated).toBe(0);
    console.log(`Second sync: ${data.syncResult.usersUpdated} users updated, ${data.syncResult.usersSkipped} skipped`);
  });
});
