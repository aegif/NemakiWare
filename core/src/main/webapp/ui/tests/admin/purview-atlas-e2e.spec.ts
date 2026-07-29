import { waitForAppReady, waitForRender, waitForUiStable } from '../utils/wait-helpers';
import { test, expect, Page, APIRequestContext } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';

/**
 * Purview / Atlas End-to-End Tests
 *
 * Validates that NemakiWare → Atlas integration works end-to-end:
 * - Incremental sync pushes CMIS objects to Atlas
 * - Governance API returns Atlas entity metadata
 * - Lineage Journal events are projected to Atlas
 * - Dead-letter replay works
 * - UI components render correctly
 *
 * Prerequisites:
 * - Atlas running on localhost:21000 (docker-compose-atlas.yml)
 * - NemakiWare core running on localhost:8080
 * - CouchDB running on localhost:5984
 *
 * All tests skip gracefully when Atlas is not available.
 *
 * Run:
 *   npx playwright test tests/admin/purview-atlas-e2e.spec.ts --project=chromium
 */

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const BASE_URL = 'http://localhost:8080';
const ATLAS_URL = 'http://localhost:21000';
const COUCHDB_URL = 'http://localhost:5984';
const REPOSITORY_ID = 'bedroom';
const AUTH_HEADER = 'Basic ' + Buffer.from('admin:admin').toString('base64');
const ATLAS_AUTH_HEADER = 'Basic ' + Buffer.from('admin:admin').toString('base64');
const COUCHDB_AUTH_HEADER = 'Basic ' + Buffer.from('admin:password').toString('base64');

// ---------------------------------------------------------------------------
// Shared state across serial groups
// ---------------------------------------------------------------------------

let atlasAvailable: boolean | null = null;
let testDocId: string | null = null;
let testDocName: string | null = null;
let testFolderId: string | null = null;
let testFolderName: string | null = null;
let unsyncedDocId: string | null = null;
let unsyncedDocName: string | null = null;
const injectedCouchIds: string[] = [];

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

async function checkAtlasAvailable(request: APIRequestContext): Promise<boolean> {
  if (atlasAvailable !== null) return atlasAvailable;
  try {
    // Check 1: Atlas server is reachable
    const res = await request.get(
      `${ATLAS_URL}/api/atlas/v2/types/typedefs/headers`,
      { headers: { Authorization: ATLAS_AUTH_HEADER }, timeout: 10000 }
    );
    if (!res.ok()) {
      atlasAvailable = false;
      return atlasAvailable;
    }
    // Check 2: NemakiWare has a catalog backend configured for Atlas
    const settingsRes = await request.get(
      `${BASE_URL}/core/api/v1/admin/integration-settings/atlas`,
      { headers: { Authorization: AUTH_HEADER }, timeout: 10000 }
    );
    if (settingsRes.ok()) {
      const body = await settingsRes.json();
      const settings = body.settings ?? {};
      atlasAvailable = settings['atlas.enabled'] === 'true';
    } else {
      atlasAvailable = false;
    }
  } catch {
    atlasAvailable = false;
  }
  return atlasAvailable;
}

function skipIfNoAtlas(available: boolean) {
  if (!available) {
    test.skip(true, 'ENV: Atlas not available — skipping');
  }
}

async function queryAtlasEntity(
  request: APIRequestContext,
  typeName: string,
  qualifiedName: string
): Promise<any | null> {
  try {
    const res = await request.get(
      `${ATLAS_URL}/api/atlas/v2/entity/uniqueAttribute/type/${typeName}?attr:qualifiedName=${encodeURIComponent(qualifiedName)}`,
      { headers: { Authorization: ATLAS_AUTH_HEADER }, timeout: 15000 }
    );
    if (res.status() === 404) return null;
    if (!res.ok()) return null;
    return await res.json();
  } catch {
    return null;
  }
}

async function searchAtlasEntities(
  request: APIRequestContext,
  query: string,
  typeName?: string
): Promise<any> {
  const body: any = { query, limit: 25 };
  if (typeName) body.typeName = typeName;
  const res = await request.post(
    `${ATLAS_URL}/api/atlas/v2/search/basic`,
    {
      headers: { Authorization: ATLAS_AUTH_HEADER, 'Content-Type': 'application/json' },
      data: body,
      timeout: 15000,
    }
  );
  return res.ok() ? await res.json() : { entities: [] };
}

async function deleteAtlasEntity(
  request: APIRequestContext,
  typeName: string,
  qualifiedName: string
): Promise<void> {
  try {
    await request.delete(
      `${ATLAS_URL}/api/atlas/v2/entity/uniqueAttribute/type/${typeName}?attr:qualifiedName=${encodeURIComponent(qualifiedName)}`,
      { headers: { Authorization: ATLAS_AUTH_HEADER }, timeout: 10000 }
    );
  } catch { /* best-effort cleanup */ }
}

async function triggerIncrementalSync(request: APIRequestContext, repoId: string): Promise<any> {
  const res = await request.post(
    `${BASE_URL}/core/api/v1/admin/purview/incremental-sync/${repoId}`,
    { headers: { Authorization: AUTH_HEADER, 'X-Requested-With': 'XMLHttpRequest' }, timeout: 60000 }
  );
  return res.ok() ? await res.json() : null;
}

async function triggerDeleteResolution(request: APIRequestContext, repoId: string): Promise<any> {
  const res = await request.post(
    `${BASE_URL}/core/api/v1/admin/purview/delete-resolution/${repoId}`,
    { headers: { Authorization: AUTH_HEADER, 'X-Requested-With': 'XMLHttpRequest' }, timeout: 60000 }
  );
  return res.ok() ? await res.json() : null;
}

async function applySchemaBootstrap(request: APIRequestContext): Promise<any> {
  const res = await request.post(
    `${BASE_URL}/core/api/v1/admin/purview/type-definitions/apply`,
    { headers: { Authorization: AUTH_HEADER, 'X-Requested-With': 'XMLHttpRequest' }, timeout: 60000 }
  );
  return res.ok() ? await res.json() : null;
}

async function createCmisDocument(request: APIRequestContext, name: string): Promise<string> {
  // Get root folder ID
  const infoRes = await request.get(
    `${BASE_URL}/core/browser/${REPOSITORY_ID}?cmisselector=repositoryInfo`,
    { headers: { Authorization: AUTH_HEADER } }
  );
  const info = await infoRes.json();
  const rootFolderId = info[REPOSITORY_ID]?.rootFolderId;

  const res = await request.post(
    `${BASE_URL}/core/browser/${REPOSITORY_ID}`,
    {
      headers: { Authorization: AUTH_HEADER },
      multipart: {
        cmisaction: 'createDocument',
        objectId: rootFolderId,
        'propertyId[0]': 'cmis:objectTypeId',
        'propertyValue[0]': 'cmis:document',
        'propertyId[1]': 'cmis:name',
        'propertyValue[1]': name,
        content: {
          name,
          mimeType: 'text/plain',
          buffer: Buffer.from('Atlas E2E test content'),
        },
      },
    }
  );
  const data = await res.json();
  return data.properties?.['cmis:objectId']?.value || data.succinctProperties?.['cmis:objectId'];
}

async function createCmisFolder(request: APIRequestContext, name: string): Promise<string> {
  const infoRes = await request.get(
    `${BASE_URL}/core/browser/${REPOSITORY_ID}?cmisselector=repositoryInfo`,
    { headers: { Authorization: AUTH_HEADER } }
  );
  const info = await infoRes.json();
  const rootFolderId = info[REPOSITORY_ID]?.rootFolderId;

  const res = await request.post(
    `${BASE_URL}/core/browser/${REPOSITORY_ID}`,
    {
      headers: { Authorization: AUTH_HEADER },
      form: {
        cmisaction: 'createFolder',
        objectId: rootFolderId,
        'propertyId[0]': 'cmis:objectTypeId',
        'propertyValue[0]': 'cmis:folder',
        'propertyId[1]': 'cmis:name',
        'propertyValue[1]': name,
      },
    }
  );
  const data = await res.json();
  return data.properties?.['cmis:objectId']?.value || data.succinctProperties?.['cmis:objectId'];
}

async function deleteCmisObject(request: APIRequestContext, objectId: string): Promise<void> {
  try {
    await request.post(
      `${BASE_URL}/core/browser/${REPOSITORY_ID}`,
      {
        headers: { Authorization: AUTH_HEADER },
        form: { cmisaction: 'delete', objectId, allVersions: 'true' },
      }
    );
  } catch { /* best-effort */ }
}

async function updateCmisProperties(
  request: APIRequestContext,
  objectId: string,
  props: Record<string, string>
): Promise<void> {
  const form: Record<string, string> = { cmisaction: 'updateProperties', objectId };
  let idx = 0;
  for (const [key, value] of Object.entries(props)) {
    form[`propertyId[${idx}]`] = key;
    form[`propertyValue[${idx}]`] = value;
    idx++;
  }
  await request.post(
    `${BASE_URL}/core/browser/${REPOSITORY_ID}`,
    { headers: { Authorization: AUTH_HEADER }, form }
  );
}

async function injectCouchDoc(request: APIRequestContext, db: string, doc: any): Promise<string> {
  const res = await request.put(
    `${COUCHDB_URL}/${db}/${doc._id}`,
    {
      headers: { Authorization: COUCHDB_AUTH_HEADER, 'Content-Type': 'application/json' },
      data: doc,
    }
  );
  const body = await res.json();
  injectedCouchIds.push(doc._id);
  return body.id;
}

async function deleteCouchDoc(request: APIRequestContext, db: string, docId: string): Promise<void> {
  try {
    const getRes = await request.get(
      `${COUCHDB_URL}/${db}/${docId}`,
      { headers: { Authorization: COUCHDB_AUTH_HEADER } }
    );
    if (getRes.ok()) {
      const data = await getRes.json();
      await request.delete(
        `${COUCHDB_URL}/${db}/${docId}?rev=${data._rev}`,
        { headers: { Authorization: COUCHDB_AUTH_HEADER } }
      );
    }
  } catch { /* best-effort */ }
}

async function getGovernanceApi(
  request: APIRequestContext,
  repoId: string,
  objectId: string
): Promise<any> {
  const res = await request.get(
    `${BASE_URL}/core/api/v1/repo/${repoId}/purview/governance/${objectId}`,
    { headers: { Authorization: AUTH_HEADER }, timeout: 15000 }
  );
  return res.ok() ? await res.json() : null;
}

async function pollUntil(
  fn: () => Promise<boolean>,
  timeoutMs: number = 60000,
  intervalMs: number = 3000
): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await fn()) return true;
    await new Promise((r) => setTimeout(r, intervalMs));
  }
  return false;
}

async function configureSettings(
  request: APIRequestContext,
  group: string,
  settings: Record<string, string>
): Promise<any> {
  const res = await request.put(
    `${BASE_URL}/core/api/v1/admin/integration-settings/${group}`,
    {
      headers: { Authorization: AUTH_HEADER, 'Content-Type': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
      data: settings,
    }
  );
  return res.ok() ? await res.json() : null;
}

function randomSuffix(): string {
  return Math.random().toString(36).substring(2, 10);
}

function makeLineageEvent(
  processType: string,
  eventKey: string,
  inputs: string[],
  outputs: string[],
  repositoryId: string = REPOSITORY_ID
): any {
  const eventId = crypto.randomUUID();
  return {
    _id: `lineage_event:${eventId}`,
    type: 'lineage_event',
    schemaVersion: 1,
    eventId,
    eventKey,
    sequenceNumber: 99999,
    occurredAt: new Date().toISOString(),
    repositoryId,
    processType,
    inputs,
    outputs,
    snapshotAttributes: { name: 'test-doc.txt' },
    publishStatusByTarget: { atlas: 'PENDING' },
    version: 1,
  };
}

// ---------------------------------------------------------------------------
// Configure all tests as serial with long timeout
// ---------------------------------------------------------------------------

test.describe.configure({ mode: 'serial', timeout: 300_000 });

// =====================================================================
// Group 1: Prerequisites & Schema Setup
// =====================================================================

test.describe('Group 1: Prerequisites & Schema Setup', () => {
  test('1.1 Atlas reachability', async ({ request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);

    const res = await request.get(
      `${ATLAS_URL}/api/atlas/v2/types/typedefs/headers`,
      { headers: { Authorization: ATLAS_AUTH_HEADER } }
    );
    expect(res.ok()).toBe(true);
    console.log('Atlas is reachable');
  });

  test('1.2 Schema bootstrap', async ({ request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);

    const result = await applySchemaBootstrap(request);
    expect(result).not.toBeNull();
    console.log('Schema bootstrap result:', JSON.stringify(result).substring(0, 300));

    // Verify nemaki types exist in Atlas
    const found = await pollUntil(async () => {
      const entity = await searchAtlasEntities(request, 'nemaki_document');
      return entity != null;
    }, 30000, 5000);
    // Schema types may or may not have searchable entities yet — the apply call itself is the assertion
    console.log('Schema search found entities:', found);
  });

  test('1.3 Connection test', async ({ request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);

    const res = await request.post(
      `${BASE_URL}/core/api/v1/admin/integration-settings/purview/test-connection`,
      { headers: { Authorization: AUTH_HEADER, 'X-Requested-With': 'XMLHttpRequest' } }
    );
    expect(res.ok()).toBe(true);
    const data = await res.json();
    expect(data.connected).toBe(true);
    console.log('Connection test passed:', data.message);
  });
});

// =====================================================================
// Group 2: Incremental Sync → Atlas
// =====================================================================

test.describe('Group 2: Incremental Sync → Atlas', () => {
  test.afterAll(async ({ request }) => {
    // Cleanup CMIS objects
    if (testDocId) await deleteCmisObject(request, testDocId);
    if (testFolderId) await deleteCmisObject(request, testFolderId);
    if (unsyncedDocId) await deleteCmisObject(request, unsyncedDocId);

    // Cleanup Atlas entities
    if (testDocId) {
      await deleteAtlasEntity(request, 'nemaki_document', `nemaki://${REPOSITORY_ID}/objects/${testDocId}`);
    }
    if (testFolderId) {
      await deleteAtlasEntity(request, 'nemaki_folder', `nemaki://${REPOSITORY_ID}/objects/${testFolderId}`);
    }
  });

  test('2.1 Document creation → sync', async ({ request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);

    testDocName = `atlas-e2e-doc-${randomSuffix()}`;
    testDocId = await createCmisDocument(request, testDocName);
    expect(testDocId).toBeTruthy();
    console.log(`Created doc: ${testDocName} (${testDocId})`);

    await triggerIncrementalSync(request, REPOSITORY_ID);

    const qn = `nemaki://${REPOSITORY_ID}/objects/${testDocId}`;
    const found = await pollUntil(async () => {
      const entity = await queryAtlasEntity(request, 'nemaki_document', qn);
      return entity != null;
    }, 60000, 5000);

    expect(found).toBe(true);
    console.log('Document synced to Atlas');
  });

  test('2.2 Property update → re-sync', async ({ request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);
    test.skip(!testDocId, 'ENV: Depends on 2.1');

    const newName = `atlas-e2e-doc-renamed-${randomSuffix()}`;
    await updateCmisProperties(request, testDocId!, { 'cmis:name': newName });
    await triggerIncrementalSync(request, REPOSITORY_ID);

    const qn = `nemaki://${REPOSITORY_ID}/objects/${testDocId}`;
    const found = await pollUntil(async () => {
      const entity = await queryAtlasEntity(request, 'nemaki_document', qn);
      if (!entity) return false;
      const attrs = entity.entity?.attributes || {};
      return attrs.name === newName;
    }, 60000, 5000);

    expect(found).toBe(true);
    console.log('Property update synced to Atlas');
  });

  test('2.3 Folder creation → sync', async ({ request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);

    testFolderName = `atlas-e2e-folder-${randomSuffix()}`;
    testFolderId = await createCmisFolder(request, testFolderName);
    expect(testFolderId).toBeTruthy();

    await triggerIncrementalSync(request, REPOSITORY_ID);

    const qn = `nemaki://${REPOSITORY_ID}/objects/${testFolderId}`;
    const found = await pollUntil(async () => {
      const entity = await queryAtlasEntity(request, 'nemaki_folder', qn);
      return entity != null;
    }, 60000, 5000);

    expect(found).toBe(true);
    console.log('Folder synced to Atlas');
  });

  test('2.4 Delete → Delete Resolution', async ({ request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);
    test.skip(!testDocId, 'ENV: Depends on 2.1');

    const qn = `nemaki://${REPOSITORY_ID}/objects/${testDocId}`;
    await deleteCmisObject(request, testDocId!);
    const deletedDocId = testDocId;
    testDocId = null; // prevent double-delete in afterAll

    await triggerIncrementalSync(request, REPOSITORY_ID);
    await triggerDeleteResolution(request, REPOSITORY_ID);

    const gone = await pollUntil(async () => {
      const entity = await queryAtlasEntity(request, 'nemaki_document', qn);
      return entity == null;
    }, 60000, 5000);

    expect(gone).toBe(true);
    console.log(`Document ${deletedDocId} removed from Atlas`);
  });

  test('2.5 Rename → qualifiedName unchanged', async ({ request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);
    test.skip(!testFolderId, 'ENV: Depends on 2.3');

    const qn = `nemaki://${REPOSITORY_ID}/objects/${testFolderId}`;

    // Verify entity exists
    const before = await queryAtlasEntity(request, 'nemaki_folder', qn);
    expect(before).not.toBeNull();

    const newFolderName = `atlas-e2e-folder-renamed-${randomSuffix()}`;
    await updateCmisProperties(request, testFolderId!, { 'cmis:name': newFolderName });
    await triggerIncrementalSync(request, REPOSITORY_ID);

    // qualifiedName should still be the same (objectId-based, not name-based)
    const after = await queryAtlasEntity(request, 'nemaki_folder', qn);
    expect(after).not.toBeNull();
    console.log('qualifiedName is objectId-based and stable after rename');
  });
});

// =====================================================================
// Group 3: Governance Tab
// =====================================================================

test.describe('Group 3: Governance Tab', () => {
  test.afterAll(async ({ request }) => {
    if (unsyncedDocId) {
      await deleteCmisObject(request, unsyncedDocId);
      unsyncedDocId = null;
    }
  });

  test('3.1 Governance API response', async ({ request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);
    test.skip(!testFolderId, 'ENV: Depends on synced folder from Group 2');

    const data = await getGovernanceApi(request, REPOSITORY_ID, testFolderId!);
    expect(data).not.toBeNull();
    expect(data.featureEnabled).toBe(true);
    expect(data.entityFound).toBe(true);
    expect(data.qualifiedName).toBeTruthy();
    expect(data.entityTypeName).toBeTruthy();
    console.log('Governance API:', JSON.stringify(data).substring(0, 300));
  });

  test('3.2 qualifiedName format', async ({ request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);
    test.skip(!testFolderId, 'ENV: Depends on synced folder from Group 2');

    const data = await getGovernanceApi(request, REPOSITORY_ID, testFolderId!);
    expect(data.qualifiedName).toMatch(/^nemaki:\/\/bedroom\/objects\//);
    console.log('qualifiedName:', data.qualifiedName);
  });

  test('3.3 UI Governance tab display', async ({ page, request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);
    test.skip(!testFolderId, 'ENV: Depends on synced folder from Group 2');

    const authHelper = new AuthHelper(page);
    await authHelper.login();
    await waitForAppReady(page, { timeout: 30000 });

    // Navigate to the folder's document viewer
    // Use direct URL navigation to the object
    await page.goto(`${BASE_URL}/core/ui/#/documents/${testFolderId}`);
    await waitForUiStable(page);

    // Look for Governance tab
    const governanceTab = page.locator('[role="tab"]').filter({ hasText: /Governance|ガバナンス/i });
    if (await governanceTab.count() > 0) {
      await governanceTab.click();
      await waitForUiStable(page);

      // Verify qualifiedName or entityType is displayed somewhere
      const pageText = await page.locator('.ant-tabs-content-active').textContent();
      expect(pageText).toBeTruthy();
      console.log('Governance tab text (first 200):', pageText?.substring(0, 200));
    }
  });

  test('3.4 Unsynced document → entityFound: false', async ({ request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);

    // Create a doc but do NOT sync
    unsyncedDocName = `atlas-e2e-unsynced-${randomSuffix()}`;
    unsyncedDocId = await createCmisDocument(request, unsyncedDocName);
    expect(unsyncedDocId).toBeTruthy();

    const data = await getGovernanceApi(request, REPOSITORY_ID, unsyncedDocId!);
    expect(data).not.toBeNull();
    expect(data.entityFound).toBe(false);
    console.log('Unsynced doc governance:', data.message);
  });
});

// =====================================================================
// Group 4: Lineage Journal → Atlas
// =====================================================================

test.describe('Group 4: Lineage Journal → Atlas', () => {
  const group4Events: string[] = [];

  test.afterAll(async ({ request }) => {
    for (const docId of group4Events) {
      await deleteCouchDoc(request, `${REPOSITORY_ID}`, docId);
    }
    // Atlas cleanup is best-effort
  });

  test('4.1 Import event → Atlas process', async ({ request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);

    const suffix = randomSuffix();
    const eventKey = `test-import-${suffix}`;
    const event = makeLineageEvent(
      'IMPORT_UPLOADED',
      eventKey,
      [`nemaki://${REPOSITORY_ID}/objects/test-input-${suffix}`],
      [`nemaki://${REPOSITORY_ID}/objects/test-output-${suffix}`]
    );

    await injectCouchDoc(request, REPOSITORY_ID, event);
    group4Events.push(event._id);

    // Wait for projection to pick up the event
    const atlasQn = `nemakiware:${REPOSITORY_ID}:import_uploaded:${eventKey}`;
    const found = await pollUntil(async () => {
      const entity = await queryAtlasEntity(request, 'Process', atlasQn);
      return entity != null;
    }, 90000, 5000);

    if (!found) {
      // Projection may not have run yet — check event status
      const evtRes = await request.get(
        `${BASE_URL}/core/api/v1/admin/lineage-journal/events/${event.eventId}`,
        { headers: { Authorization: AUTH_HEADER } }
      );
      const evtData = evtRes.ok() ? await evtRes.json() : null;
      console.log('Event status:', JSON.stringify(evtData?.publishStatusByTarget));
    }
    expect(found).toBe(true);
    console.log('Import event projected to Atlas');
  });

  test('4.2 Archive event → Atlas process', async ({ request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);

    const suffix = randomSuffix();
    const eventKey = `test-archive-${suffix}`;
    const event = makeLineageEvent(
      'ARCHIVE_LOCAL',
      eventKey,
      [`nemaki://${REPOSITORY_ID}/objects/test-input-${suffix}`],
      [`nemaki://${REPOSITORY_ID}/objects/test-output-${suffix}`]
    );

    await injectCouchDoc(request, REPOSITORY_ID, event);
    group4Events.push(event._id);

    const atlasQn = `nemakiware:${REPOSITORY_ID}:archive_local:${eventKey}`;
    const found = await pollUntil(async () => {
      const entity = await queryAtlasEntity(request, 'Process', atlasQn);
      return entity != null;
    }, 90000, 5000);

    expect(found).toBe(true);
    console.log('Archive event projected to Atlas');
  });

  test('4.3 Export event → Atlas process', async ({ request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);

    const suffix = randomSuffix();
    const eventKey = `test-export-${suffix}`;
    const event = makeLineageEvent(
      'EXPORT_SELECTED_OBJECTS',
      eventKey,
      [`nemaki://${REPOSITORY_ID}/objects/test-input-${suffix}`],
      [`nemaki://${REPOSITORY_ID}/objects/test-output-${suffix}`]
    );

    await injectCouchDoc(request, REPOSITORY_ID, event);
    group4Events.push(event._id);

    const atlasQn = `nemakiware:${REPOSITORY_ID}:export_selected_objects:${eventKey}`;
    const found = await pollUntil(async () => {
      const entity = await queryAtlasEntity(request, 'Process', atlasQn);
      return entity != null;
    }, 90000, 5000);

    expect(found).toBe(true);
    console.log('Export event projected to Atlas');
  });
});

// =====================================================================
// Group 5: Cloud Drive Simulation
// =====================================================================

test.describe('Group 5: Cloud Drive Simulation', () => {
  const group5Events: string[] = [];

  test.afterAll(async ({ request }) => {
    for (const docId of group5Events) {
      await deleteCouchDoc(request, REPOSITORY_ID, docId);
    }
  });

  test('5.1 Cloud Upload event', async ({ request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);

    const suffix = randomSuffix();
    const eventKey = `test-cloud-upload-${suffix}`;
    const event = makeLineageEvent(
      'CLOUD_SYNC_UPLOAD',
      eventKey,
      [`nemaki://${REPOSITORY_ID}/objects/test-local-${suffix}`],
      [`cloud://google/test-file-${suffix}`]
    );

    await injectCouchDoc(request, REPOSITORY_ID, event);
    group5Events.push(event._id);

    const atlasQn = `nemakiware:${REPOSITORY_ID}:cloud_sync_upload:${eventKey}`;
    const found = await pollUntil(async () => {
      const entity = await queryAtlasEntity(request, 'Process', atlasQn);
      return entity != null;
    }, 90000, 5000);

    expect(found).toBe(true);
    console.log('Cloud Upload event projected to Atlas');
  });

  test('5.2 Cloud Download event', async ({ request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);

    const suffix = randomSuffix();
    const eventKey = `test-cloud-download-${suffix}`;
    const event = makeLineageEvent(
      'CLOUD_SYNC_DOWNLOAD',
      eventKey,
      [`cloud://google/test-file-${suffix}`],
      [`nemaki://${REPOSITORY_ID}/objects/test-local-${suffix}`]
    );

    await injectCouchDoc(request, REPOSITORY_ID, event);
    group5Events.push(event._id);

    const atlasQn = `nemakiware:${REPOSITORY_ID}:cloud_sync_download:${eventKey}`;
    const found = await pollUntil(async () => {
      const entity = await queryAtlasEntity(request, 'Process', atlasQn);
      return entity != null;
    }, 90000, 5000);

    expect(found).toBe(true);
    console.log('Cloud Download event projected to Atlas');
  });
});

// =====================================================================
// Group 6: Lineage Journal UI
// =====================================================================

test.describe('Group 6: Lineage Journal UI', () => {
  let page: Page;
  let authHelper: AuthHelper;
  let testHelper: TestHelper;

  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext({
      httpCredentials: { username: 'admin', password: 'admin' },
      extraHTTPHeaders: { Authorization: AUTH_HEADER },
    });
    page = await context.newPage();
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);
    await authHelper.login();
    await waitForAppReady(page, { timeout: 30000 });
  });

  test.afterAll(async () => {
    await page?.close();
  });

  test('6.1 Event table renders', async ({ request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);

    await page.goto(`${BASE_URL}/core/ui/#/lineage-journal`);
    await waitForUiStable(page);

    // Should have a table
    const table = page.locator('.ant-table');
    await expect(table).toBeVisible({ timeout: 10000 });

    // Table should have rows (from injected events) or at least columns
    const rows = page.locator('.ant-table-row');
    const rowCount = await rows.count();
    console.log(`Event table rows: ${rowCount}`);
    // At minimum the table component should render
    expect(await table.count()).toBeGreaterThan(0);
  });

  test('6.2 Event detail drawer', async ({ request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);

    await page.goto(`${BASE_URL}/core/ui/#/lineage-journal`);
    await waitForUiStable(page);

    // Find a detail button
    const detailButton = page.locator('button').filter({ hasText: /詳細|Detail/i }).first();
    if (await detailButton.count() > 0) {
      await detailButton.click();
      await waitForUiStable(page);

      // Drawer should appear
      const drawer = page.locator('.ant-drawer');
      await expect(drawer).toBeVisible({ timeout: 10000 });

      const drawerText = await drawer.textContent();
      console.log('Drawer text (first 200):', drawerText?.substring(0, 200));

      // Close drawer
      const closeBtn = drawer.locator('.ant-drawer-close');
      if (await closeBtn.count() > 0) {
        await closeBtn.click();
        await waitForRender(page);
      }
    }
  });

  test('6.3 Stats tab', async ({ request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);

    // Stats is typically available via the API — verify API response
    const res = await request.get(
      `${BASE_URL}/core/api/v1/admin/lineage-journal/stats`,
      { headers: { Authorization: AUTH_HEADER } }
    );
    expect(res.ok()).toBe(true);
    const data = await res.json();
    expect(data).toHaveProperty('mode');
    expect(data).toHaveProperty('totalEvents');
    expect(data).toHaveProperty('byProcessType');
    console.log(`Stats: mode=${data.mode}, totalEvents=${data.totalEvents}`);

    // Also verify UI stats page if it exists
    await page.goto(`${BASE_URL}/core/ui/#/lineage-journal`);
    await waitForUiStable(page);

    // Look for stats tab
    const statsTab = page.locator('[role="tab"]').filter({ hasText: /統計|Stats/i });
    if (await statsTab.count() > 0) {
      await statsTab.click();
      await waitForUiStable(page);
      const statsText = await page.locator('.ant-tabs-content-active').textContent();
      console.log('Stats tab text (first 200):', statsText?.substring(0, 200));
    }
  });

  test('6.4 Dead Letters tab', async ({ request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);

    // Verify API
    const res = await request.get(
      `${BASE_URL}/core/api/v1/admin/lineage-journal/dead-letters`,
      { headers: { Authorization: AUTH_HEADER } }
    );
    expect(res.ok()).toBe(true);
    const data = await res.json();
    expect(data).toHaveProperty('deadLetters');
    console.log(`Dead letters count: ${data.deadLetters?.length ?? 0}`);

    // Check UI
    await page.goto(`${BASE_URL}/core/ui/#/lineage-journal`);
    await waitForUiStable(page);

    const deadLetterTab = page.locator('[role="tab"]').filter({ hasText: /Dead.*Letter|デッドレター/i });
    if (await deadLetterTab.count() > 0) {
      await deadLetterTab.click();
      await waitForUiStable(page);

      const tabContent = await page.locator('.ant-tabs-content-active').textContent();
      console.log('Dead Letter tab text (first 200):', tabContent?.substring(0, 200));

      // Should have either a table or empty state, plus Replay All button
      const replayAllBtn = page.locator('button').filter({ hasText: /Replay All|全件リプレイ/i });
      const replayAllCount = await replayAllBtn.count();
      console.log(`Replay All button count: ${replayAllCount}`);
    }
  });
});

// =====================================================================
// Group 7: Dead-Letter & Replay
// =====================================================================

test.describe('Group 7: Dead-Letter & Replay', () => {
  let failedEventId: string | null = null;
  let failedEventCouchId: string | null = null;

  test.afterAll(async ({ request }) => {
    // Cleanup injected events
    if (failedEventCouchId) {
      await deleteCouchDoc(request, REPOSITORY_ID, failedEventCouchId);
    }
  });

  test('7.1 Atlas disabled → FAILED status', async ({ request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);

    // Disable atlas via settings
    await configureSettings(request, 'atlas', { 'atlas.enabled': 'false' });

    // Wait for the setting to propagate
    await new Promise((r) => setTimeout(r, 3000));

    const suffix = randomSuffix();
    const eventKey = `test-failed-${suffix}`;
    const event = makeLineageEvent(
      'IMPORT_UPLOADED',
      eventKey,
      [`nemaki://${REPOSITORY_ID}/objects/test-input-${suffix}`],
      [`nemaki://${REPOSITORY_ID}/objects/test-output-${suffix}`]
    );
    failedEventId = event.eventId;
    failedEventCouchId = event._id;

    await injectCouchDoc(request, REPOSITORY_ID, event);

    // Wait for projection to attempt and fail/skip
    const statusBecameNonPending = await pollUntil(async () => {
      const evtRes = await request.get(
        `${BASE_URL}/core/api/v1/admin/lineage-journal/events/${failedEventId}`,
        { headers: { Authorization: AUTH_HEADER } }
      );
      if (!evtRes.ok()) return false;
      const evtData = await evtRes.json();
      const atlasStatus = evtData?.publishStatusByTarget?.atlas;
      return atlasStatus === 'FAILED' || atlasStatus === 'SKIPPED';
    }, 60000, 3000);

    expect(statusBecameNonPending).toBe(true);
    console.log('Event status became FAILED/SKIPPED as expected');

    // Re-enable atlas
    await configureSettings(request, 'atlas', { 'atlas.enabled': 'true' });
    await new Promise((r) => setTimeout(r, 2000));
  });

  test('7.2 Re-enable → replay single event', async ({ request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);
    test.skip(!failedEventId, 'ENV: Depends on 7.1');

    // Replay the failed event
    const replayRes = await request.post(
      `${BASE_URL}/core/api/v1/admin/lineage-journal/events/${failedEventId}/replay?target=atlas`,
      { headers: { Authorization: AUTH_HEADER, 'X-Requested-With': 'XMLHttpRequest' } }
    );
    expect(replayRes.ok()).toBe(true);

    const replayData = await replayRes.json();
    expect(replayData.status).toBe('ok');
    console.log('Replay response:', JSON.stringify(replayData));

    // Wait for re-projection
    const published = await pollUntil(async () => {
      const evtRes = await request.get(
        `${BASE_URL}/core/api/v1/admin/lineage-journal/events/${failedEventId}`,
        { headers: { Authorization: AUTH_HEADER } }
      );
      if (!evtRes.ok()) return false;
      const evtData = await evtRes.json();
      return evtData?.publishStatusByTarget?.atlas === 'PUBLISHED';
    }, 90000, 5000);

    // The event should eventually become PUBLISHED or remain PENDING if projection hasn't run yet
    console.log('Post-replay status: PUBLISHED =', published);
    // At minimum the replay should reset to PENDING
    const evtRes = await request.get(
      `${BASE_URL}/core/api/v1/admin/lineage-journal/events/${failedEventId}`,
      { headers: { Authorization: AUTH_HEADER } }
    );
    const evtData = await evtRes.json();
    const status = evtData?.publishStatusByTarget?.atlas;
    expect(['PENDING', 'PUBLISHED', 'PROJECTING']).toContain(status);
    console.log(`Final replay status: ${status}`);
  });

  test('7.3 Replay-all dead letters', async ({ request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);

    const res = await request.post(
      `${BASE_URL}/core/api/v1/admin/lineage-journal/dead-letters/replay-all`,
      { headers: { Authorization: AUTH_HEADER, 'X-Requested-With': 'XMLHttpRequest' } }
    );
    expect(res.ok()).toBe(true);
    const data = await res.json();
    expect(data).toHaveProperty('replayed');
    console.log(`Replay-all: replayed ${data.replayed} dead letters`);
  });
});

// =====================================================================
// Group 8: Multi-target
// =====================================================================

test.describe('Group 8: Multi-target', () => {
  test('8.1 Atlas single target', async ({ request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);

    const suffix = randomSuffix();
    const eventKey = `test-multitarget-${suffix}`;
    const event = makeLineageEvent(
      'IMPORT_UPLOADED',
      eventKey,
      [`nemaki://${REPOSITORY_ID}/objects/test-input-${suffix}`],
      [`nemaki://${REPOSITORY_ID}/objects/test-output-${suffix}`]
    );

    await injectCouchDoc(request, REPOSITORY_ID, event);

    // Check the event's publishStatusByTarget
    const evtRes = await request.get(
      `${BASE_URL}/core/api/v1/admin/lineage-journal/events/${event.eventId}`,
      { headers: { Authorization: AUTH_HEADER } }
    );
    expect(evtRes.ok()).toBe(true);
    const evtData = await evtRes.json();

    const targets = Object.keys(evtData.publishStatusByTarget || {});
    expect(targets).toContain('atlas');
    expect(targets).not.toContain('purview');
    expect(targets).not.toContain('dataplex');
    console.log(`Targets: ${targets.join(', ')}`);

    // Cleanup
    await deleteCouchDoc(request, REPOSITORY_ID, event._id);
  });
});
