import { test, expect } from '@playwright/test';

const BASE = 'http://localhost:8080/core/api';
const AUTH = { Authorization: 'Basic ' + Buffer.from('admin:admin').toString('base64') };
const JSON_HEADERS = { ...AUTH, 'Content-Type': 'application/json' };

test.describe('External Ingest API', () => {

  // ── Connector CRUD ──────────────────────────────────────────────

  test.describe('Connector Management', () => {
    const connectorId = 'test-connector-' + Date.now();

    test('POST /connectors — create connector', async ({ request }) => {
      const res = await request.post(`${BASE}/v1/admin/connectors`, {
        headers: JSON_HEADERS,
        data: {
          connectorId,
          displayName: 'Test Connector',
          sourceArchetype: 'FILE_SHARE',
          sourceSystem: 'box',
          authType: 'oauth2',
          enabled: true,
        },
      });
      expect(res.status()).toBe(201);
      const body = await res.json();
      expect(body.status).toBe('success');
      expect(body.connectorId).toBe(connectorId);
    });

    test('GET /connectors — list connectors', async ({ request }) => {
      const res = await request.get(`${BASE}/v1/admin/connectors`, { headers: AUTH });
      expect(res.ok()).toBeTruthy();
      const body = await res.json();
      expect(Array.isArray(body)).toBeTruthy();
      expect(body.some((c: { connectorId: string }) => c.connectorId === connectorId)).toBeTruthy();
    });

    test('GET /connectors — secrets are masked', async ({ request }) => {
      // First update with a secret
      await request.put(`${BASE}/v1/admin/connectors/${connectorId}`, {
        headers: JSON_HEADERS,
        data: {
          connectorId,
          sourceArchetype: 'FILE_SHARE',
          sourceSystem: 'box',
          credentialRef: 'my-secret-token',
          webhookSecret: 'my-webhook-secret',
          enabled: true,
        },
      });
      // Then read back
      const res = await request.get(`${BASE}/v1/admin/connectors/${connectorId}`, { headers: AUTH });
      expect(res.ok()).toBeTruthy();
      const body = await res.json();
      expect(body.credentialRef).toBe('[configured]');
      expect(body.webhookSecret).toBe('[configured]');
    });

    test('PUT /connectors — [configured] placeholder preserves real secret', async ({ request }) => {
      // Step 1: Set a known webhookSecret
      await request.put(`${BASE}/v1/admin/connectors/${connectorId}`, {
        headers: JSON_HEADERS,
        data: {
          connectorId,
          sourceArchetype: 'CHAT_CONTEXT',
          sourceSystem: 'slack',
          webhookSecret: 'test-secret-12345',
          displayName: 'Secret Test',
          enabled: true,
        },
      });

      // Step 2: Send update with [configured] placeholder — should preserve the secret
      await request.put(`${BASE}/v1/admin/connectors/${connectorId}`, {
        headers: JSON_HEADERS,
        data: {
          connectorId,
          sourceArchetype: 'CHAT_CONTEXT',
          sourceSystem: 'slack',
          webhookSecret: '[configured]',
          displayName: 'Updated Name',
          enabled: true,
        },
      });

      // Step 3: Verify the real secret is still functional by sending a webhook
      // with an INVALID signature — should be rejected (proves secret is still set)
      const webhookRes = await request.post(`${BASE}/v1/ingest-webhook/${connectorId}`, {
        headers: {
          'Content-Type': 'application/json',
          'X-Webhook-Signature': 'intentionally-wrong-signature',
        },
        data: { type: 'test' },
      });
      // 401 = signature verification failed = secret is still active and working
      expect(webhookRes.status()).toBe(401);
    });

    test('DELETE /connectors — cleanup', async ({ request }) => {
      const res = await request.delete(`${BASE}/v1/admin/connectors/${connectorId}`, { headers: AUTH });
      expect(res.ok()).toBeTruthy();
    });
  });

  // ── Profile CRUD ────────────────────────────────────────────────

  test.describe('Import Profile Management', () => {
    const profileId = 'test-profile-' + Date.now();
    const connectorId = 'test-conn-for-profile-' + Date.now();

    test.beforeAll(async ({ request }) => {
      // Create a connector for profile validation
      await request.post(`${BASE}/v1/admin/connectors`, {
        headers: JSON_HEADERS,
        data: {
          connectorId,
          sourceArchetype: 'MESSAGE_CONTEXT',
          sourceSystem: 'imap',
          enabled: true,
        },
      });
    });

    test('POST /import-profiles — create profile', async ({ request }) => {
      const res = await request.post(`${BASE}/v1/admin/import-profiles`, {
        headers: JSON_HEADERS,
        data: {
          profileId,
          repositoryId: 'bedroom',
          targetFolderPath: '/test-import',
          defaultObjectTypeId: 'cmis:document',
          dedupePolicy: 'create_new_version',
          versioningPolicy: 'major',
          enabled: true,
        },
      });
      expect(res.status()).toBe(201);
      const body = await res.json();
      expect(body.status).toBe('success');
    });

    test('GET /import-profiles — list profiles', async ({ request }) => {
      const res = await request.get(`${BASE}/v1/admin/import-profiles?repositoryId=bedroom`, { headers: AUTH });
      expect(res.ok()).toBeTruthy();
      const body = await res.json();
      expect(Array.isArray(body)).toBeTruthy();
      expect(body.some((p: { profileId: string }) => p.profileId === profileId)).toBeTruthy();
    });

    test('GET /import-profiles/{id} — get with warnings', async ({ request }) => {
      const res = await request.get(`${BASE}/v1/admin/import-profiles/${profileId}`, { headers: AUTH });
      expect(res.ok()).toBeTruthy();
      const body = await res.json();
      expect(body.profile).toBeDefined();
      expect(body.profile.profileId).toBe(profileId);
    });

    test('PUT /import-profiles — scheduler validation rejects missing defaultConnectorId', async ({ request }) => {
      const res = await request.put(`${BASE}/v1/admin/import-profiles/${profileId}`, {
        headers: JSON_HEADERS,
        data: {
          profileId,
          repositoryId: 'bedroom',
          targetFolderPath: '/test-import',
          schedulerEnabled: true,
          // No defaultConnectorId
          enabled: true,
        },
      });
      expect(res.status()).toBe(400);
    });

    test('PUT /import-profiles — scheduler validation accepts valid connector', async ({ request }) => {
      const res = await request.put(`${BASE}/v1/admin/import-profiles/${profileId}`, {
        headers: JSON_HEADERS,
        data: {
          profileId,
          repositoryId: 'bedroom',
          targetFolderPath: '/test-import',
          schedulerEnabled: true,
          defaultConnectorId: connectorId,
          enabled: true,
        },
      });
      expect(res.ok()).toBeTruthy();
    });

    test('DELETE — cleanup', async ({ request }) => {
      await request.delete(`${BASE}/v1/admin/import-profiles/${profileId}`, { headers: AUTH });
      await request.delete(`${BASE}/v1/admin/connectors/${connectorId}`, { headers: AUTH });
    });
  });

  // ── Scheduler Status ────────────────────────────────────────────

  test.describe('Scheduler', () => {
    test('GET /ingest-scheduler/status', async ({ request }) => {
      const res = await request.get(`${BASE}/v1/admin/ingest-scheduler/status`, { headers: AUTH });
      expect(res.ok()).toBeTruthy();
      const body = await res.json();
      expect(body.scheduledProfiles).toBeDefined();
      expect(body.count).toBeDefined();
      expect(body.idleProfiles).toBeDefined();
    });
  });

  // ── Job History / DLQ ───────────────────────────────────────────

  test.describe('Job History & DLQ', () => {
    test('GET /ingest/jobs — list jobs', async ({ request }) => {
      const res = await request.get(`${BASE}/v1/admin/ingest/jobs`, { headers: AUTH });
      expect(res.ok()).toBeTruthy();
      const body = await res.json();
      expect(Array.isArray(body)).toBeTruthy();
    });

    test('GET /ingest/dlq — list DLQ entries', async ({ request }) => {
      const res = await request.get(`${BASE}/v1/admin/ingest/dlq`, { headers: AUTH });
      expect(res.ok()).toBeTruthy();
      const body = await res.json();
      expect(body.count).toBeDefined();
      expect(Array.isArray(body.entries)).toBeTruthy();
    });
  });

  // ── Ingest Endpoint ─────────────────────────────────────────────

  test.describe('Ingest Endpoint', () => {
    test('POST /repo/{repoId}/ingest — unauthenticated request is rejected', async ({ request }) => {
      const res = await request.post(`${BASE}/v1/repo/bedroom/ingest`, {
        headers: { 'Content-Type': 'application/json' },
        data: { sourceObjectId: 'test' },
      });
      // Without valid CMIS auth, rejected by auth filter or controller
      expect(res.ok()).toBeFalsy();
    });
  });

  // ── Webhook Endpoint ────────────────────────────────────────────

  test.describe('Webhook Endpoint', () => {
    test('POST /ingest-webhook/{connectorId} — unknown connector', async ({ request }) => {
      const res = await request.post(`${BASE}/v1/ingest-webhook/nonexistent`, {
        headers: { 'Content-Type': 'application/json' },
        data: { type: 'test' },
      });
      // Returns 401 (auth filter) or 404 (connector not found) depending on filter chain
      expect([401, 404]).toContain(res.status());
    });
  });
});
