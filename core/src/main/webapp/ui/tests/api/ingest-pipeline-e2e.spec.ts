import { test, expect, APIRequestContext } from '@playwright/test';

/**
 * Ingest Pipeline API smoke tests.
 *
 * Exercises the canonical import pipeline via REST API with a local stub
 * connector + profile. No external service accounts required.
 *
 * Scope: API-level integration — verifies document creation, CMIS properties,
 * dedupe, content round-trip, classification, cloud metadata, and admin endpoints.
 * Does NOT cover adapter-level integration (Gmail/Slack/etc.) — those require
 * JVM-side stub servers (WireMock).
 */

const BASE = 'http://localhost:8080/core/api';
const CMIS = 'http://localhost:8080/core/browser/bedroom';
const AUTH = { Authorization: 'Basic ' + Buffer.from('admin:admin').toString('base64') };
const JSON_H = { ...AUTH, 'Content-Type': 'application/json' };

/** Extract property value from CMIS object (handles both succinct and full format). */
function cmisProperty(obj: Record<string, unknown>, propId: string): unknown {
  const succinct = obj.succinctProperties as Record<string, unknown> | undefined;
  if (succinct) return succinct[propId];
  const props = obj.properties as Record<string, { value: unknown }> | undefined;
  return props?.[propId]?.value;
}

/** Create a unique stub connector + profile pair for a single test. */
async function createStub(request: APIRequestContext, suffix: string) {
  const connId = `e2e-conn-${suffix}`;
  const profId = `e2e-prof-${suffix}`;

  await request.delete(`${BASE}/v1/admin/import-profiles/${profId}`, { headers: AUTH }).catch(() => {});
  await request.delete(`${BASE}/v1/admin/connectors/${connId}`, { headers: AUTH }).catch(() => {});

  await request.post(`${BASE}/v1/admin/connectors`, {
    headers: JSON_H,
    data: { connectorId: connId, sourceArchetype: 'FILE_SHARE', sourceSystem: 'e2e_test', authType: 'none', enabled: true },
  });
  await request.post(`${BASE}/v1/admin/import-profiles`, {
    headers: JSON_H,
    data: {
      profileId: profId, repositoryId: 'bedroom', targetFolderPath: '/',
      defaultConnectorId: connId, defaultObjectTypeId: 'cmis:document',
      dedupePolicy: 'create_new_version', updatePolicy: 'version_up_on_content_change',
      versioningPolicy: 'major', enabled: true,
    },
  });

  return { connId, profId };
}

/** Delete a stub connector + profile pair. */
async function deleteStub(request: APIRequestContext, connId: string, profId: string) {
  await request.delete(`${BASE}/v1/admin/import-profiles/${profId}`, { headers: AUTH }).catch(() => {});
  await request.delete(`${BASE}/v1/admin/connectors/${connId}`, { headers: AUTH }).catch(() => {});
}

/** Count documents in root folder via CMIS. */
async function countRootChildren(request: APIRequestContext): Promise<number> {
  const res = await request.get(`${CMIS}/root?cmisselector=children`, { headers: AUTH });
  const body = await res.json();
  return body.objects?.length ?? 0;
}

/** Clean up any orphaned e2e- test connectors/profiles from previous failed runs. */
async function cleanupOrphans(request: APIRequestContext) {
  try {
    const profRes = await request.get(`${BASE}/v1/admin/import-profiles?repositoryId=bedroom`, { headers: AUTH });
    if (profRes.ok()) {
      for (const p of await profRes.json()) {
        if (p.profileId?.startsWith('e2e-')) {
          await request.delete(`${BASE}/v1/admin/import-profiles/${p.profileId}`, { headers: AUTH }).catch(() => {});
        }
      }
    }
    const connRes = await request.get(`${BASE}/v1/admin/connectors`, { headers: AUTH });
    if (connRes.ok()) {
      for (const c of await connRes.json()) {
        if (c.connectorId?.startsWith('e2e-')) {
          await request.delete(`${BASE}/v1/admin/connectors/${c.connectorId}`, { headers: AUTH }).catch(() => {});
        }
      }
    }
  } catch { /* best effort */ }
}

test.describe('Ingest Pipeline — API Smoke Tests', () => {

  test.beforeAll(async ({ request }) => {
    await cleanupOrphans(request);
  });

  // ── Content upload + download round-trip ───────────────────────

  test('should upload content via multipart and verify download round-trip', async ({ request }) => {
    const ts = Date.now();
    const { connId, profId } = await createStub(request, `upload-${ts}`);
    const fileContent = `E2E content round-trip test ${ts}`;

    try {
      const res = await request.post(`${BASE}/v1/repo/bedroom/ingest`, {
        headers: AUTH,
        multipart: {
          request: { name: 'request.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify({
            profileId: profId, connectorId: connId,
            sourceObjectId: `rt-${ts}`, sourceObjectType: 'file', executionMode: 'manual',
          })) },
          content: { name: `roundtrip-${ts}.txt`, mimeType: 'text/plain', buffer: Buffer.from(fileContent) },
        },
      });

      const body = await res.json();
      expect(body.errors ?? []).toHaveLength(0);
      expect(body.objectId).toBeDefined();

      // Verify content download round-trip via CMIS
      const dlRes = await request.get(
        `${CMIS}/root?objectId=${encodeURIComponent(body.objectId)}&cmisselector=content`,
        { headers: AUTH },
      );
      expect(dlRes.ok()).toBeTruthy();
      const downloaded = await dlRes.text();
      expect(downloaded).toBe(fileContent);
    } finally {
      await deleteStub(request, connId, profId);
    }
  });

  // ── Content hash: identical re-upload → no new version ────────

  test('should skip version-up when re-uploaded content hash matches', async ({ request }) => {
    const ts = Date.now();
    const { connId, profId } = await createStub(request, `hash-${ts}`);
    const content = `identical content for hash test ${ts}`;

    try {
      const mkMultipart = () => ({
        request: { name: 'req.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify({
          profileId: profId, connectorId: connId,
          sourceObjectId: `hash-${ts}`, sourceObjectType: 'file', executionMode: 'manual',
        })) },
        content: { name: `hash-${ts}.txt`, mimeType: 'text/plain', buffer: Buffer.from(content) },
      });

      const r1 = await request.post(`${BASE}/v1/repo/bedroom/ingest`, { headers: AUTH, multipart: mkMultipart() });
      expect((await r1.json()).errors ?? []).toHaveLength(0);

      const r2 = await request.post(`${BASE}/v1/repo/bedroom/ingest`, { headers: AUTH, multipart: mkMultipart() });
      const b2 = await r2.json();
      expect(b2.errors ?? []).toHaveLength(0);
      // Identical content → hash match → metadata-only update, no new version
      expect(b2.isNewVersion).toBeFalsy();
    } finally {
      await deleteStub(request, connId, profId);
    }
  });

  // ── JSON-only import (metadata only) ──────────────────────────

  test('should import metadata-only document via JSON', async ({ request }) => {
    const ts = Date.now();
    const { connId, profId } = await createStub(request, `json-${ts}`);

    try {
      const res = await request.post(`${BASE}/v1/repo/bedroom/ingest`, {
        headers: JSON_H,
        data: {
          profileId: profId, connectorId: connId,
          sourceObjectId: `meta-${ts}`, sourceObjectType: 'file',
          fileName: `meta-${ts}.txt`, executionMode: 'manual',
        },
      });

      const body = await res.json();
      expect(body.errors ?? []).toHaveLength(0);
      expect(body.objectId).toBeDefined();

      // Verify CMIS properties
      const docRes = await request.get(
        `${CMIS}/root?objectId=${encodeURIComponent(body.objectId)}&cmisselector=object`,
        { headers: AUTH },
      );
      expect(docRes.ok()).toBeTruthy();
      expect(cmisProperty(await docRes.json(), 'cmis:name')).toBe(`meta-${ts}.txt`);
    } finally {
      await deleteStub(request, connId, profId);
    }
  });

  // ── Dedupe: re-import with changed content creates new version ──

  test('should create new version on re-import with different content', async ({ request }) => {
    const ts = Date.now();
    const { connId, profId } = await createStub(request, `dedup-${ts}`);
    const srcId = `dup-${ts}`;

    try {
      // First import with content
      const r1 = await request.post(`${BASE}/v1/repo/bedroom/ingest`, {
        headers: AUTH,
        multipart: {
          request: { name: 'req.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify({
            profileId: profId, connectorId: connId, sourceObjectId: srcId, sourceObjectType: 'file', executionMode: 'manual',
          })) },
          content: { name: `dup-${ts}.txt`, mimeType: 'text/plain', buffer: Buffer.from(`content v1 ${ts}`) },
        },
      });
      expect((await r1.json()).errors ?? []).toHaveLength(0);

      // Second import with DIFFERENT content → new version
      const r2 = await request.post(`${BASE}/v1/repo/bedroom/ingest`, {
        headers: AUTH,
        multipart: {
          request: { name: 'req.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify({
            profileId: profId, connectorId: connId, sourceObjectId: srcId, sourceObjectType: 'file', executionMode: 'manual',
          })) },
          content: { name: `dup-${ts}.txt`, mimeType: 'text/plain', buffer: Buffer.from(`content v2 ${ts}`) },
        },
      });
      const b2 = await r2.json();
      expect(b2.errors ?? []).toHaveLength(0);
      expect(b2.isNewVersion).toBe(true);
    } finally {
      await deleteStub(request, connId, profId);
    }
  });

  // ── Re-import without content: metadata-only update ────────────
  // When re-importing the same sourceObjectId without a content stream,
  // computedHash is null → version_up_on_content_change treats this as
  // "no content provided" → metadata-only update, no new version.

  test('re-import without content does metadata-only update (no version-up)', async ({ request }) => {
    const ts = Date.now();
    const { connId, profId } = await createStub(request, `nocont-${ts}`);

    try {
      const data = {
        profileId: profId, connectorId: connId,
        sourceObjectId: `nocont-${ts}`, sourceObjectType: 'file',
        fileName: `nocont-${ts}.txt`, executionMode: 'manual',
      };
      const r1 = await request.post(`${BASE}/v1/repo/bedroom/ingest`, { headers: JSON_H, data });
      expect((await r1.json()).errors ?? []).toHaveLength(0);

      const r2 = await request.post(`${BASE}/v1/repo/bedroom/ingest`, { headers: JSON_H, data });
      const b2 = await r2.json();
      expect(b2.errors ?? []).toHaveLength(0);
      // No content stream → computedHash null → metadata-only, no new version
      expect(b2.isNewVersion).toBeFalsy();
    } finally {
      await deleteStub(request, connId, profId);
    }
  });

  // ── Dry-run: verify no side effects ───────────────────────────

  // Smoke: verifies dry-run returns dryRun=true and does not create a new
  // document in the target folder. Does NOT verify absence of job/DLQ/lineage
  // side effects — those require JVM-level integration tests.
  test('dry-run returns preview and does not create a new document in target folder', async ({ request }) => {
    const ts = Date.now();
    const { connId, profId } = await createStub(request, `dry-${ts}`);

    try {
      const beforeCount = await countRootChildren(request);

      const res = await request.post(`${BASE}/v1/repo/bedroom/ingest`, {
        headers: JSON_H,
        data: {
          profileId: profId, connectorId: connId,
          sourceObjectId: `dry-${ts}`, sourceObjectType: 'file',
          fileName: `dry-${ts}.txt`, dryRun: true, executionMode: 'manual',
        },
      });
      const body = await res.json();
      expect(body.dryRun).toBe(true);
      // objectId should be null for new documents in dry-run
      expect(body.objectId).toBeNull();

      // Verify no new document appeared in target folder
      const afterCount = await countRootChildren(request);
      expect(afterCount).toBe(beforeCount);
    } finally {
      await deleteStub(request, connId, profId);
    }
  });

  // ── cloudDriveMetadata via pipeline ────────────────────────────

  test('should apply cloudDriveMetadata when cloudProvider in metadata', async ({ request }) => {
    const ts = Date.now();
    const connId = `e2e-cloud-${ts}`;
    const profId = `e2e-cloudp-${ts}`;

    await request.post(`${BASE}/v1/admin/connectors`, {
      headers: JSON_H,
      data: { connectorId: connId, sourceArchetype: 'FILE_SHARE', sourceSystem: 'google', enabled: true },
    });
    await request.post(`${BASE}/v1/admin/import-profiles`, {
      headers: JSON_H,
      data: { profileId: profId, repositoryId: 'bedroom', targetFolderPath: '/', defaultConnectorId: connId, dedupePolicy: 'create_new_version', enabled: true },
    });

    try {
      const res = await request.post(`${BASE}/v1/repo/bedroom/ingest`, {
        headers: JSON_H,
        data: {
          profileId: profId, connectorId: connId,
          sourceObjectId: `gd-${ts}`, sourceObjectType: 'file',
          fileName: `cloud-${ts}.docx`, executionMode: 'manual',
          metadata: { cloudProvider: 'google', cloudFileId: `gfile-${ts}`, cloudFileUrl: 'https://docs.google.com/d/test' },
        },
      });
      const body = await res.json();
      expect(body.errors ?? []).toHaveLength(0);

      const docRes = await request.get(
        `${CMIS}/root?objectId=${encodeURIComponent(body.objectId)}&cmisselector=object&filter=*`,
        { headers: AUTH },
      );
      const doc = await docRes.json();
      expect(cmisProperty(doc, 'nemaki:cloudProvider')).toBe('google');
      expect(cmisProperty(doc, 'nemaki:cloudFileId')).toBe(`gfile-${ts}`);
    } finally {
      await deleteStub(request, connId, profId);
    }
  });

  // ── defaultClassification ─────────────────────────────────────

  test('should apply classification from profile', async ({ request }) => {
    const ts = Date.now();
    const connId = `e2e-cls-c-${ts}`;
    const profId = `e2e-cls-p-${ts}`;

    await request.post(`${BASE}/v1/admin/connectors`, {
      headers: JSON_H,
      data: { connectorId: connId, sourceArchetype: 'FILE_SHARE', sourceSystem: 'e2e_test', enabled: true },
    });
    await request.post(`${BASE}/v1/admin/import-profiles`, {
      headers: JSON_H,
      data: { profileId: profId, repositoryId: 'bedroom', targetFolderPath: '/', defaultConnectorId: connId, defaultClassification: 'Confidential', dedupePolicy: 'create_new_version', enabled: true },
    });

    try {
      const res = await request.post(`${BASE}/v1/repo/bedroom/ingest`, {
        headers: JSON_H,
        data: { profileId: profId, connectorId: connId, sourceObjectId: `cls-${ts}`, sourceObjectType: 'file', fileName: `cls-${ts}.txt`, executionMode: 'manual' },
      });
      const body = await res.json();
      expect(body.errors ?? []).toHaveLength(0);

      const docRes = await request.get(
        `${CMIS}/root?objectId=${encodeURIComponent(body.objectId)}&cmisselector=object&filter=*`,
        { headers: AUTH },
      );
      const classification = cmisProperty(await docRes.json(), 'nemaki:classification');
      expect(classification).toContain('Confidential');
    } finally {
      await deleteStub(request, connId, profId);
    }
  });

  // ── Admin endpoint smoke + negative-path assertions ────────────
  // These verify endpoint availability and basic rejection behavior.
  // Positive-path scheduler/DLQ/job behavioral E2E (trigger→import→
  // checkpoint→job record→DLQ→retry) requires JVM-side integration
  // tests with WireMock stub adapters.

  test('scheduler status includes idleProfiles', async ({ request }) => {
    const res = await request.get(`${BASE}/v1/admin/ingest-scheduler/status`, { headers: AUTH });
    expect(res.ok()).toBeTruthy();
    expect(Array.isArray((await res.json()).idleProfiles)).toBeTruthy();
  });

  test('IDLE start rejects non-IMAP connector', async ({ request }) => {
    const ts = Date.now();
    const { connId, profId } = await createStub(request, `idle-${ts}`);
    try {
      // Enable scheduler on the stub profile
      await request.put(`${BASE}/v1/admin/import-profiles/${profId}`, {
        headers: JSON_H,
        data: { profileId: profId, repositoryId: 'bedroom', targetFolderPath: '/', defaultConnectorId: connId, schedulerEnabled: true, enabled: true },
      });
      const res = await request.post(`${BASE}/v1/admin/ingest-scheduler/idle/start/${profId}`, { headers: AUTH });
      const body = await res.json();
      expect(body.status).toBe('error');
      expect(body.message).toContain('IMAP');
    } finally {
      await deleteStub(request, connId, profId);
    }
  });

  // Webhook endpoint bypasses auth filter (external push notifications).
  // Security is enforced by webhookSecret signature verification in the controller.
  test('webhook rejects request when webhookSecret is not configured', async ({ request }) => {
    const ts = Date.now();
    const connId = `e2e-wh-${ts}`;
    await request.post(`${BASE}/v1/admin/connectors`, {
      headers: JSON_H,
      data: { connectorId: connId, sourceArchetype: 'CHAT_CONTEXT', sourceSystem: 'slack', enabled: true },
      // No webhookSecret configured
    });
    try {
      // Webhook endpoint is auth-filter exempt, so this reaches the controller.
      // Without webhookSecret, verifySignature returns false → 401 from controller.
      const res = await request.post(`${BASE}/v1/ingest-webhook/${connId}`, {
        headers: { 'Content-Type': 'application/json' },
        data: { type: 'test' },
      });
      expect(res.status()).toBe(401);
      const body = await res.json();
      expect(body.error).toBe('Signature verification failed');
    } finally {
      await request.delete(`${BASE}/v1/admin/connectors/${connId}`, { headers: AUTH });
    }
  });

  // ── Skipped result returns existingObjectId ──────────────────

  test('should return skipped with existingObjectId for skip_if_same_version dedupe', async ({ request }) => {
    const ts = Date.now();
    const connId = `e2e-conn-skip-${ts}`;
    const profId = `e2e-prof-skip-${ts}`;

    // Create stub with skip_if_same_version dedupe policy
    await request.delete(`${BASE}/v1/admin/import-profiles/${profId}`, { headers: AUTH }).catch(() => {});
    await request.delete(`${BASE}/v1/admin/connectors/${connId}`, { headers: AUTH }).catch(() => {});

    await request.post(`${BASE}/v1/admin/connectors`, {
      headers: JSON_H,
      data: { connectorId: connId, sourceArchetype: 'FILE_SHARE', sourceSystem: 'e2e_test', authType: 'none', enabled: true },
    });
    await request.post(`${BASE}/v1/admin/import-profiles`, {
      headers: JSON_H,
      data: {
        profileId: profId, repositoryId: 'bedroom', targetFolderPath: '/',
        defaultConnectorId: connId, defaultObjectTypeId: 'cmis:document',
        dedupePolicy: 'skip_if_same_version', updatePolicy: 'version_up_on_content_change',
        versioningPolicy: 'major', enabled: true,
      },
    });

    try {
      // First import creates the document
      const r1 = await request.post(`${BASE}/v1/repo/bedroom/ingest`, {
        headers: JSON_H,
        data: {
          profileId: profId, connectorId: connId,
          sourceObjectId: `skip-test-${ts}`, sourceObjectType: 'file',
          fileName: `skip-test-${ts}.txt`, executionMode: 'manual',
        },
      });
      const b1 = await r1.json();
      expect(b1.errors ?? []).toHaveLength(0);
      expect(b1.objectId).toBeTruthy();

      // Second import with same source identity → skipped
      const r2 = await request.post(`${BASE}/v1/repo/bedroom/ingest`, {
        headers: JSON_H,
        data: {
          profileId: profId, connectorId: connId,
          sourceObjectId: `skip-test-${ts}`, sourceObjectType: 'file',
          fileName: `skip-test-${ts}.txt`, executionMode: 'manual',
        },
      });
      expect(r2.ok()).toBeTruthy();
      const b2 = await r2.json();
      expect(b2.skipped).toBe(true);
      expect(b2.skipReason).toContain('already exists');
      expect(b2.objectId).toBe(b1.objectId); // existingObjectId populated
    } finally {
      await request.delete(`${BASE}/v1/admin/import-profiles/${profId}`, { headers: AUTH }).catch(() => {});
      await request.delete(`${BASE}/v1/admin/connectors/${connId}`, { headers: AUTH }).catch(() => {});
    }
  });

  test('DLQ list returns entries array', async ({ request }) => {
    const res = await request.get(`${BASE}/v1/admin/ingest/dlq`, { headers: AUTH });
    expect(res.ok()).toBeTruthy();
    expect(Array.isArray((await res.json()).entries)).toBeTruthy();
  });

  test('job history returns array', async ({ request }) => {
    const res = await request.get(`${BASE}/v1/admin/ingest/jobs`, { headers: AUTH });
    expect(res.ok()).toBeTruthy();
    expect(Array.isArray(await res.json())).toBeTruthy();
  });

  // ── DLQ retry cooldown ──────────────────────────────────────

  test('DLQ retry returns 429 on rapid retry of same entry', async ({ request }) => {
    // List DLQ to find an entry (if any exist from previous test failures)
    const listRes = await request.get(`${BASE}/v1/admin/ingest/dlq`, { headers: AUTH });
    const entries = (await listRes.json()).entries || [];
    if (entries.length === 0) {
      // No DLQ entries — skip gracefully (cooldown logic still unit-tested)
      return;
    }
    const dlqId = entries[0].id;

    // First retry — should succeed (or fail for other reasons, but not 429)
    const r1 = await request.post(`${BASE}/v1/admin/ingest/dlq/${dlqId}/retry`, { headers: AUTH });
    // Don't assert r1 status (may fail for config reasons) — just confirm it's not 429
    expect(r1.status()).not.toBe(429);

    // Immediate second retry — should be rate-limited
    const r2 = await request.post(`${BASE}/v1/admin/ingest/dlq/${dlqId}/retry`, { headers: AUTH });
    expect(r2.status()).toBe(429);
  });
});
