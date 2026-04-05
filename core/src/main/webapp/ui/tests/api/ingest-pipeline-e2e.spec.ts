import { test, expect, APIRequestContext } from '@playwright/test';

/**
 * End-to-end tests for the canonical External Ingestion pipeline.
 *
 * These tests exercise the full import flow via the REST API without requiring
 * external service accounts. A test connector + profile are created as stubs,
 * and documents are ingested via the /ingest endpoint.
 */

const BASE = 'http://localhost:8080/core/api';
const CMIS = 'http://localhost:8080/core/browser/bedroom';
const AUTH = { Authorization: 'Basic ' + Buffer.from('admin:admin').toString('base64') };
const JSON_H = { ...AUTH, 'Content-Type': 'application/json' };

const CONN_ID = 'e2e-stub-connector';
const PROF_ID = 'e2e-stub-profile';

/** Extract property value from CMIS object (handles both succinct and full format). */
function cmisProperty(obj: Record<string, unknown>, propId: string): unknown {
  const succinct = obj.succinctProperties as Record<string, unknown> | undefined;
  if (succinct) return succinct[propId];
  const props = obj.properties as Record<string, { value: unknown }> | undefined;
  return props?.[propId]?.value;
}

async function ensureStubSetup(request: APIRequestContext) {
  // Idempotent setup: create connector + profile if not present
  const connCheck = await request.get(`${BASE}/v1/admin/connectors/${CONN_ID}`, { headers: AUTH });
  if (connCheck.status() === 404) {
    await request.post(`${BASE}/v1/admin/connectors`, {
      headers: JSON_H,
      data: { connectorId: CONN_ID, displayName: 'E2E Stub', sourceArchetype: 'FILE_SHARE', sourceSystem: 'e2e_test', authType: 'none', enabled: true },
    });
  }

  const profCheck = await request.get(`${BASE}/v1/admin/import-profiles/${PROF_ID}`, { headers: AUTH });
  if (profCheck.status() === 404) {
    await request.post(`${BASE}/v1/admin/import-profiles`, {
      headers: JSON_H,
      data: {
        profileId: PROF_ID, repositoryId: 'bedroom', targetFolderPath: '/',
        defaultConnectorId: CONN_ID, defaultObjectTypeId: 'cmis:document',
        dedupePolicy: 'create_new_version', updatePolicy: 'version_up_on_content_change',
        versioningPolicy: 'major', enabled: true,
      },
    });
  }
}

test.describe.serial('Ingest Pipeline E2E', () => {

  // ── Basic import ──────────────────────────────────────────────

  test('should import a file via JSON and create a CMIS document', async ({ request }) => {
    await ensureStubSetup(request);
    const ts = Date.now();
    const srcId = 'e2e-json-' + ts;

    const res = await request.post(`${BASE}/v1/repo/bedroom/ingest`, {
      headers: JSON_H,
      data: {
        profileId: PROF_ID, connectorId: CONN_ID,
        sourceObjectId: srcId, sourceObjectType: 'file',
        fileName: 'e2e-test-' + ts + '.txt', executionMode: 'manual',
      },
    });

    const body = await res.json();
    expect(body.errors ?? [], `Ingest failed: ${JSON.stringify(body.errors)}`).toHaveLength(0);
    expect(body.objectId).toBeDefined();

    // Verify via CMIS
    const docRes = await request.get(
      `${CMIS}/root?objectId=${encodeURIComponent(body.objectId)}&cmisselector=object`,
      { headers: AUTH },
    );
    expect(docRes.ok()).toBeTruthy();
    const doc = await docRes.json();
    expect(cmisProperty(doc, 'cmis:name')).toBe('e2e-test-' + ts + '.txt');
  });

  // ── Dedupe: re-import creates new version ─────────────────────

  test('should create new version on re-import of same sourceObjectId', async ({ request }) => {
    await ensureStubSetup(request);
    const ts = Date.now();
    const srcId = 'e2e-dedupe-' + ts;
    const fn = 'dup-' + ts + '.txt';

    // First import
    const r1 = await request.post(`${BASE}/v1/repo/bedroom/ingest`, {
      headers: JSON_H,
      data: { profileId: PROF_ID, connectorId: CONN_ID, sourceObjectId: srcId, sourceObjectType: 'file', fileName: fn, executionMode: 'manual' },
    });
    expect(r1.ok()).toBeTruthy();

    // Second import — same sourceObjectId
    const r2 = await request.post(`${BASE}/v1/repo/bedroom/ingest`, {
      headers: JSON_H,
      data: { profileId: PROF_ID, connectorId: CONN_ID, sourceObjectId: srcId, sourceObjectType: 'file', fileName: fn, executionMode: 'manual' },
    });
    expect(r2.ok()).toBeTruthy();
    const b2 = await r2.json();
    expect(b2.isNewVersion).toBe(true);
  });

  // ── Dry-run ───────────────────────────────────────────────────

  test('should return dry-run result without creating document', async ({ request }) => {
    await ensureStubSetup(request);
    const res = await request.post(`${BASE}/v1/repo/bedroom/ingest`, {
      headers: JSON_H,
      data: { profileId: PROF_ID, connectorId: CONN_ID, sourceObjectId: 'e2e-dry-' + Date.now(), sourceObjectType: 'file', fileName: 'dry.txt', dryRun: true, executionMode: 'manual' },
    });
    expect(res.ok()).toBeTruthy();
    const body = await res.json();
    expect(body.dryRun).toBe(true);
  });

  // ── cloudDriveMetadata via pipeline ────────────────────────────

  test('should apply cloudDriveMetadata when cloudProvider in metadata', async ({ request }) => {
    await ensureStubSetup(request);
    const cloudConnId = 'e2e-cloud-' + Date.now();

    await request.post(`${BASE}/v1/admin/connectors`, {
      headers: JSON_H,
      data: { connectorId: cloudConnId, sourceArchetype: 'FILE_SHARE', sourceSystem: 'google', enabled: true },
    });

    const res = await request.post(`${BASE}/v1/repo/bedroom/ingest`, {
      headers: JSON_H,
      data: {
        profileId: PROF_ID, connectorId: cloudConnId,
        sourceObjectId: 'cloud-' + Date.now(), sourceObjectType: 'file',
        fileName: 'cloud-' + Date.now() + '.docx', executionMode: 'manual',
        metadata: { cloudProvider: 'google', cloudFileId: 'gfile123', cloudFileUrl: 'https://docs.google.com/d/test' },
      },
    });
    expect(res.ok()).toBeTruthy();
    const body = await res.json();

    // Verify cloudDriveMetadata via CMIS properties
    const docRes = await request.get(
      `${CMIS}/root?objectId=${encodeURIComponent(body.objectId)}&cmisselector=object&filter=*`,
      { headers: AUTH },
    );
    const docJson = await docRes.json();
    const props = { 'nemaki:cloudProvider': cmisProperty(docJson, 'nemaki:cloudProvider'), 'nemaki:cloudFileId': cmisProperty(docJson, 'nemaki:cloudFileId') };
    expect(props['nemaki:cloudProvider']).toBe('google');
    expect(props['nemaki:cloudFileId']).toBe('gfile123');

    await request.delete(`${BASE}/v1/admin/connectors/${cloudConnId}`, { headers: AUTH });
  });

  // ── defaultClassification ─────────────────────────────────────

  test('should apply classification from profile', async ({ request }) => {
    await ensureStubSetup(request);
    const cpId = 'e2e-class-' + Date.now();

    await request.post(`${BASE}/v1/admin/import-profiles`, {
      headers: JSON_H,
      data: {
        profileId: cpId, repositoryId: 'bedroom', targetFolderPath: '/',
        defaultConnectorId: CONN_ID, defaultClassification: 'Confidential',
        dedupePolicy: 'create_new_version', enabled: true,
      },
    });

    const res = await request.post(`${BASE}/v1/repo/bedroom/ingest`, {
      headers: JSON_H,
      data: { profileId: cpId, connectorId: CONN_ID, sourceObjectId: 'class-' + Date.now(), sourceObjectType: 'file', fileName: 'classified-' + Date.now() + '.txt', executionMode: 'manual' },
    });
    expect(res.ok()).toBeTruthy();
    const body = await res.json();

    const docRes = await request.get(
      `${CMIS}/root?objectId=${encodeURIComponent(body.objectId)}&cmisselector=object&filter=*`,
      { headers: AUTH },
    );
    const classification = cmisProperty(await docRes.json(), 'nemaki:classification');
    expect(classification).toContain('Confidential');

    await request.delete(`${BASE}/v1/admin/import-profiles/${cpId}`, { headers: AUTH });
  });

  // ── Scheduler / IDLE / Webhook / DLQ ──────────────────────────

  test('scheduler status includes idleProfiles', async ({ request }) => {
    const res = await request.get(`${BASE}/v1/admin/ingest-scheduler/status`, { headers: AUTH });
    expect(res.ok()).toBeTruthy();
    const body = await res.json();
    expect(Array.isArray(body.idleProfiles)).toBeTruthy();
  });

  test('IDLE start rejects non-IMAP connector', async ({ request }) => {
    await ensureStubSetup(request);
    const ip = 'e2e-idle-' + Date.now();
    await request.post(`${BASE}/v1/admin/import-profiles`, {
      headers: JSON_H,
      data: { profileId: ip, repositoryId: 'bedroom', targetFolderPath: '/', defaultConnectorId: CONN_ID, schedulerEnabled: true, enabled: true },
    });

    const res = await request.post(`${BASE}/v1/admin/ingest-scheduler/idle/start/${ip}`, { headers: AUTH });
    const body = await res.json();
    expect(body.status).toBe('error');
    expect(body.message).toContain('IMAP');

    await request.delete(`${BASE}/v1/admin/import-profiles/${ip}`, { headers: AUTH });
  });

  test('webhook rejects request when secret not configured', async ({ request }) => {
    await ensureStubSetup(request);
    const res = await request.post(`${BASE}/v1/ingest-webhook/${CONN_ID}`, {
      headers: { 'Content-Type': 'application/json' },
      data: { type: 'test' },
    });
    expect(res.ok()).toBeFalsy();
  });

  test('DLQ list returns entries array', async ({ request }) => {
    const res = await request.get(`${BASE}/v1/admin/ingest/dlq`, { headers: AUTH });
    expect(res.ok()).toBeTruthy();
    const body = await res.json();
    expect(Array.isArray(body.entries)).toBeTruthy();
  });

  test('job history returns array', async ({ request }) => {
    const res = await request.get(`${BASE}/v1/admin/ingest/jobs`, { headers: AUTH });
    expect(res.ok()).toBeTruthy();
    expect(Array.isArray(await res.json())).toBeTruthy();
  });

  // ── Cleanup ───────────────────────────────────────────────────

  test('cleanup stub connector and profile', async ({ request }) => {
    await request.delete(`${BASE}/v1/admin/import-profiles/${PROF_ID}`, { headers: AUTH });
    await request.delete(`${BASE}/v1/admin/connectors/${CONN_ID}`, { headers: AUTH });
  });
});
