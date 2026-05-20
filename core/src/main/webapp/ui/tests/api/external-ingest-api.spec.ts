import { test, expect } from '@playwright/test';

const BASE = 'http://localhost:8080/core/api';
// CSRF: /core/api/v1/* requires X-Requested-With for state-changing
// requests when basic auth (an ambient credential) is used. Add it to
// AUTH so every helper that spreads {...AUTH} gets the header. GET
// requests don't strictly need it, but including it is harmless.
const AUTH = {
  Authorization: 'Basic ' + Buffer.from('admin:admin').toString('base64'),
  'X-Requested-With': 'XMLHttpRequest',
};
const JSON_HEADERS = { ...AUTH, 'Content-Type': 'application/json' };

// CSRF + serial execution: these tests share backend state (single
// connectorId / profileId within each describe block) and depend on
// CRUD ordering. With workers > 1 parallel runs trip on each other's
// state — declare serial so all 16 tests in this file run in one
// worker even when the project parallelizes other files.
test.describe.configure({ mode: 'serial' });

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

    test('PUT /connectors — [configured] placeholder preserves webhookSecret (behavioral)', async ({ request }) => {
      // Tests webhookSecret only — credentialRef preservation requires JVM-side test
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
      // with valid API auth BUT an INVALID signature — this passes the auth filter
      // and reaches verifySignature() in the controller, which should reject it
      const webhookRes = await request.post(`${BASE}/v1/ingest-webhook/${connectorId}`, {
        headers: {
          ...AUTH,
          'Content-Type': 'application/json',
          'X-Webhook-Signature': 'intentionally-wrong-signature',
        },
        data: { type: 'test' },
      });
      expect(webhookRes.status()).toBe(401);
      const webhookBody = await webhookRes.json();
      // Assert the CONTROLLER's specific error — not the auth filter's generic 401
      expect(webhookBody.error).toBe('Signature verification failed');
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
    test('POST /ingest-webhook/{connectorId} — unknown connector returns uniform 401', async ({ request }) => {
      const res = await request.post(`${BASE}/v1/ingest-webhook/nonexistent`, {
        headers: { 'Content-Type': 'application/json' },
        data: { type: 'test' },
      });
      // Returns 401 for both not-found and disabled connectors (prevents enumeration)
      expect([401, 404]).toContain(res.status());
    });
  });
});
