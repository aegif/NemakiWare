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
// CouchLineageJournalStore watches this database, fixed, regardless of repository. Injecting a
// lineage event into the repository DB (which is what these groups used to do) puts it somewhere
// the projection loop never looks, so the whole Group 4/5/7/8 path was asserting on nothing.
const LINEAGE_DB = 'nemaki_lineage';

/**
 * Injected lineage events go to a repository id of their own, never `bedroom`.
 *
 * The projector is cursor-based: it reads events whose sequenceNumber is above
 * `projection_cursor:{target}:{repositoryId}` and then advances that cursor. Production numbering
 * comes from `append()`, which CAS-increments `lineage_seq:{repositoryId}` — bedroom's counter is
 * at 1. Injecting a fixture straight into the journal with a made-up number (this file used a
 * fixed 99999) would push bedroom's cursor to 99999 the first time it projected, and every real
 * event after that would sit unprojected until the counter reached 100000. Deleting the fixture
 * afterwards does not move the cursor back.
 *
 * `projectEventsOrdered` collects repository ids from the cursor store and from the non-terminal
 * events themselves, not from a configured list, so a synthetic id is projected normally — and
 * its cursor is ours to throw away. Sequence numbers below are monotonic from 1 within it.
 */
const LINEAGE_REPO = `atlas-e2e-${Math.random().toString(36).slice(2, 10)}`;
let lineageSeq = 0;
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
/** Atlas Process qualifiedNames created by injected events, removed by the file-level cleanup. */
const projectedProcessKeys: string[] = [];

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
    // 404 is the answer to "is it there?"; 401/500/anything else is a broken probe and
    // returning null for those turns an infrastructure failure into "not synced yet".
    if (res.status() === 404) return null;
    if (!res.ok()) {
      throw new Error(
        `atlas ${typeName}/${qualifiedName} -> HTTP ${res.status()}: ${(await res.text()).substring(0, 200)}`
      );
    }
    return await res.json();
  } catch (e) {
    if (e instanceof Error && e.message.startsWith('atlas ')) throw e;
    throw new Error(`atlas ${typeName}/${qualifiedName} unreachable: ${e}`);
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
  // 300s, not 60s: a pass took 2m15s here while an archive reconciliation held the repository.
  // Waiting is the honest way to handle that; catching the timeout and retrying would let a
  // background scheduler's work stand in for a broken admin API.
  const res = await request.post(
    `${BASE_URL}/core/api/v1/admin/purview/incremental-sync/${repoId}`,
    { headers: { Authorization: AUTH_HEADER, 'X-Requested-With': 'XMLHttpRequest' }, timeout: 300000 }
  );
  if (!res.ok()) {
    // 401/403/404/500 used to become null and be retried, so a suite could go green with the
    // endpoint completely broken.
    throw new Error(
      `incremental-sync -> HTTP ${res.status()}: ${(await res.text()).substring(0, 200)}`
    );
  }
  return await res.json();
}

/**
 * The tombstone delete resolution keeps for a deleted object, read straight from the state DB.
 * PurviewStateStoreImpl stores one CouchDB document per field, id = "system_config_" + the key
 * with dots replaced by underscores (PurviewTombstoneStateServiceImpl.buildKeyPrefix).
 */
async function readTombstoneField(
  request: APIRequestContext,
  repoId: string,
  objectId: string,
  field: string
): Promise<string | null> {
  const docId = `system_config_purview_tombstone_state_${repoId}_${objectId}_${field}`;
  const res = await request.get(`${COUCHDB_URL}/nemaki_purview_state/${docId}`, {
    headers: { Authorization: COUCHDB_AUTH_HEADER },
  });
  if (!res.ok()) return null;
  const body = await res.json();
  return body?.value ?? null;
}

async function triggerFullSync(request: APIRequestContext, repoId: string): Promise<any> {
  const res = await request.post(
    `${BASE_URL}/core/api/v1/admin/purview/full-sync/${repoId}`,
    { headers: { Authorization: AUTH_HEADER, 'X-Requested-With': 'XMLHttpRequest' }, timeout: 300000 }
  );
  if (!res.ok()) {
    throw new Error(`full-sync -> HTTP ${res.status()}: ${(await res.text()).substring(0, 200)}`);
  }
  return await res.json();
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

/**
 * This server requires a change token on updateProperties — without one it answers
 *
 *   409 {"exception":"updateConflict","message":"Change token is required to update"}
 *
 * and the object is left untouched. The old version of this helper sent no token and
 * ignored the response, so the update silently did nothing and the caller then waited
 * out its full 60-second Atlas poll for a value that was never written. Read the token
 * first, and fail loudly on a rejected update rather than as a timeout somewhere else.
 */
async function updateCmisProperties(
  request: APIRequestContext,
  objectId: string,
  props: Record<string, string>
): Promise<void> {
  // `/browser/{repo}` serves repository-level selectors only (repositoryInfo, typeChildren…);
  // an object GET there answers 405. Object selectors live under `/browser/{repo}/root`.
  const objRes = await request.get(
    `${BASE_URL}/core/browser/${REPOSITORY_ID}/root?cmisselector=object&objectId=${encodeURIComponent(objectId)}`,
    { headers: { Authorization: AUTH_HEADER } }
  );
  if (!objRes.ok()) {
    throw new Error(`updateCmisProperties: cannot read ${objectId} (HTTP ${objRes.status()})`);
  }
  const changeToken = (await objRes.json())?.properties?.['cmis:changeToken']?.value;

  const form: Record<string, string> = { cmisaction: 'updateProperties', objectId };
  if (changeToken) {
    form.changeToken = String(changeToken);
  }
  let idx = 0;
  for (const [key, value] of Object.entries(props)) {
    form[`propertyId[${idx}]`] = key;
    form[`propertyValue[${idx}]`] = value;
    idx++;
  }
  const res = await request.post(
    `${BASE_URL}/core/browser/${REPOSITORY_ID}`,
    { headers: { Authorization: AUTH_HEADER }, form }
  );
  if (!res.ok()) {
    throw new Error(
      `updateCmisProperties: HTTP ${res.status()} for ${objectId}: ${(await res.text()).substring(0, 200)}`
    );
  }
}

async function injectCouchDoc(request: APIRequestContext, db: string, doc: any): Promise<string> {
  const res = await request.put(
    `${COUCHDB_URL}/${db}/${doc._id}`,
    {
      headers: { Authorization: COUCHDB_AUTH_HEADER, 'Content-Type': 'application/json' },
      data: doc,
    }
  );
  if (!res.ok()) {
    // CouchDB answers 4xx with a JSON error body, which the old code returned as if it were a
    // successful write — the test then waited for an event that had never been stored.
    throw new Error(`injectCouchDoc: ${db}/${doc._id} -> HTTP ${res.status()} ${(await res.text()).substring(0, 200)}`);
  }
  const body = await res.json();
  if (!body?.id) {
    throw new Error(`injectCouchDoc: ${db}/${doc._id} returned no id: ${JSON.stringify(body).substring(0, 200)}`);
  }
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
      const delRes = await request.delete(
        `${COUCHDB_URL}/${db}/${docId}?rev=${data._rev}`,
        { headers: { Authorization: COUCHDB_AUTH_HEADER } }
      );
      // A 409 here means the document changed under us and is still in the journal; leaving it
      // silently would let the next run inherit it.
      if (!delRes.ok()) {
        console.warn(`deleteCouchDoc: ${db}/${docId} -> HTTP ${delRes.status()} (left behind)`);
      }
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

/**
 * Incremental sync walks the change log from a stored checkpoint, so one pass is not a
 * guarantee: a change made moments earlier can still be behind the cursor when the pass runs,
 * and nothing re-reads it afterwards. The old shape here — trigger once, then poll Atlas for
 * 60 seconds — could therefore only ever observe what that single pass happened to carry.
 *
 * Deliberately incremental-only. An earlier version escalated to a full sync half way through,
 * which made this a test of "either sync works" and would have gone green even if incremental
 * sync dropped the change permanently. Group 2 takes its baseline with one full sync in
 * beforeAll; from then on only the incremental path is exercised, and every pass must report
 * COMPLETED with no failures — a FAILED or partially-failed pass is a defect, not something to
 * poll through. REJECTED is the one benign outcome: another pass holds the repository lock.
 */
async function syncUntil(
  request: APIRequestContext,
  check: () => Promise<boolean>,
  timeoutMs: number = 180000,
  intervalMs: number = 5000
): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const result = await triggerIncrementalSync(request, REPOSITORY_ID);
    if (result.status !== 'REJECTED') {
      expect(result.status, `incremental sync reported ${result.status}: ${result.errorSummary}`)
        .toBe('COMPLETED');
      expect(result.failedCount, `incremental sync failed on ${result.failedCount} object(s): ${result.errorSummary}`)
        .toBe(0);
    }
    if (await check()) return true;
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
  if (!res.ok()) {
    throw new Error(`configureSettings(${group}) -> HTTP ${res.status()}: ${(await res.text()).substring(0, 200)}`);
  }
  return await res.json();
}

function randomSuffix(): string {
  return Math.random().toString(36).substring(2, 10);
}

function trackProcess(qualifiedName: string): string {
  projectedProcessKeys.push(qualifiedName);
  return qualifiedName;
}

function makeLineageEvent(
  processType: string,
  eventKey: string,
  inputs: string[],
  outputs: string[],
  repositoryId: string = LINEAGE_REPO
): any {
  const eventId = crypto.randomUUID();
  return {
    _id: `lineage_event:${eventId}`,
    type: 'lineage_event',
    schemaVersion: 1,
    eventId,
    eventKey,
    sequenceNumber: ++lineageSeq,
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

    // Atlas, not Purview. Purview and Atlas are two separate catalog backends with
    // separate settings and separate probes: `/purview/test-connection` reads
    // purview.enabled and needs an Azure tenant/client/secret, so against a local
    // Atlas it answers "Purview integration is currently disabled" no matter how
    // healthy Atlas is. Everything else in this file gates on atlas.enabled
    // (checkAtlasAvailable), so this is the probe that matches the gate.
    const res = await request.post(
      `${BASE_URL}/core/api/v1/admin/integration-settings/atlas/test-connection`,
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
  // The folder is NOT torn down here. Group 3 asks the governance API about it
  // (`test.skip(!testFolderId, 'ENV: Depends on synced folder from Group 2')`), and this
  // hook used to delete both the CMIS object and its Atlas entity the moment Group 2
  // finished — so 3.1 always got a null response and, the file being serial, took the
  // remaining 16 tests with it. Group 3's own afterAll owns the folder now.
  //
  // One full sync as a baseline, so the checkpoint starts at "now" and the tests below are
  // exercising the incremental path from a known point rather than an arbitrary backlog.
  test.beforeAll(async ({ request }) => {
    if (!(await checkAtlasAvailable(request))) return;
    const result = await triggerFullSync(request, REPOSITORY_ID);
    expect(result, 'full sync baseline did not run').not.toBeNull();
    expect(result.status, `full sync baseline: ${result.errorSummary}`).toBe('COMPLETED');
  });

  // `unsyncedDocId` is not touched either: 3.4 is what creates it, long after this runs.
  test.afterAll(async ({ request }) => {
    if (testDocId) {
      await deleteCmisObject(request, testDocId);
      await deleteAtlasEntity(request, 'nemaki_document', `nemaki://${REPOSITORY_ID}/objects/${testDocId}`);
    }
  });

  test('2.1 Document creation → sync', async ({ request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);

    testDocName = `atlas-e2e-doc-${randomSuffix()}`;
    testDocId = await createCmisDocument(request, testDocName);
    expect(testDocId).toBeTruthy();
    console.log(`Created doc: ${testDocName} (${testDocId})`);

    const qn = `nemaki://${REPOSITORY_ID}/objects/${testDocId}`;
    const found = await syncUntil(request, async () => {
      const entity = await queryAtlasEntity(request, 'nemaki_document', qn);
      return entity != null;
    });

    expect(found).toBe(true);
    console.log('Document synced to Atlas');
  });

  test('2.2 Property update → re-sync', async ({ request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);
    test.skip(!testDocId, 'ENV: Depends on 2.1');

    const newName = `atlas-e2e-doc-renamed-${randomSuffix()}`;
    await updateCmisProperties(request, testDocId!, { 'cmis:name': newName });

    const qn = `nemaki://${REPOSITORY_ID}/objects/${testDocId}`;
    const found = await syncUntil(request, async () => {
      const entity = await queryAtlasEntity(request, 'nemaki_document', qn);
      if (!entity) return false;
      const attrs = entity.entity?.attributes || {};
      return attrs.name === newName;
    });

    expect(found).toBe(true);
    console.log('Property update synced to Atlas');
  });

  test('2.3 Folder creation → sync', async ({ request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);

    testFolderName = `atlas-e2e-folder-${randomSuffix()}`;
    testFolderId = await createCmisFolder(request, testFolderName);
    expect(testFolderId).toBeTruthy();

    const qn = `nemaki://${REPOSITORY_ID}/objects/${testFolderId}`;
    const found = await syncUntil(request, async () => {
      const entity = await queryAtlasEntity(request, 'nemaki_folder', qn);
      return entity != null;
    });

    expect(found).toBe(true);
    console.log('Folder synced to Atlas');
  });

  /**
   * A CMIS delete in NemakiWare is not a purge — the object moves to the archive, from which
   * it can be restored. Delete resolution knows that: when the tombstone is a document and an
   * archive record exists for it, it marks the tombstone ARCHIVED and deliberately leaves the
   * catalog entity in place (PurviewDeleteResolutionServiceImpl.resolveTombstone). The Atlas
   * schema models this on purpose — it ships `nemaki_archive` and `nemaki_document_has_archive`
   * types, and 4.2 covers the archive event itself.
   *
   * This test used to assert that the entity disappeared, which is the one thing the product
   * deliberately does not do, so it failed on every run and — because the file is serial — took
   * the remaining 18 tests with it. Verified against the running stack: after delete +
   * incremental-sync + delete-resolution (both COMPLETED, failedCount 0), the entity is still
   * there with `status: ACTIVE`, and a second delete-resolution finds nothing left to do.
   *
   * What is worth asserting is the contract that actually holds, and it is not vacuous: if
   * delete resolution ever started purging catalog entries for archived documents, the
   * retention check fails; if it broke outright, the job-result check fails.
   */
  test('2.4 Delete → Delete Resolution keeps the archived document in the catalog', async ({ request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);
    test.skip(!testDocId, 'ENV: Depends on 2.1');

    const qn = `nemaki://${REPOSITORY_ID}/objects/${testDocId}`;
    const deletedDocId = testDocId!;

    // 1. the delete itself must succeed
    const deleteRes = await request.post(`${BASE_URL}/core/browser/${REPOSITORY_ID}`, {
      headers: { Authorization: AUTH_HEADER },
      form: { cmisaction: 'delete', objectId: deletedDocId, allVersions: 'true' },
    });
    expect(deleteRes.ok(), `CMIS delete failed: ${deleteRes.status()}`).toBe(true);
    testDocId = null; // prevent double-delete in afterAll

    // 2. and the object must really be gone — a 404, not any old non-2xx
    const objRes = await request.get(
      `${BASE_URL}/core/browser/${REPOSITORY_ID}/root?cmisselector=object&objectId=${encodeURIComponent(deletedDocId)}`,
      { headers: { Authorization: AUTH_HEADER } }
    );
    expect(objRes.status()).toBe(404);

    // 3. the sync pass that notices the delete has to actually run
    const synced = await syncUntil(request, async () =>
      (await readTombstoneField(request, REPOSITORY_ID, deletedDocId, 'status')) !== null);
    expect(synced, 'incremental sync never recorded a tombstone for the deleted document').toBe(true);

    // 4. resolution only picks up tombstones whose dueAt has passed (a few seconds by
    //    default). Calling it immediately gives processedCount 0 on a fast machine, which
    //    would then have to be tolerated — and a tolerated zero is no assertion at all.
    const dueAt = await readTombstoneField(request, REPOSITORY_ID, deletedDocId, 'dueAt');
    expect(dueAt, 'tombstone has no dueAt').not.toBeNull();
    const waitMs = Date.parse(dueAt!) - Date.now();
    if (waitMs > 0) {
      await new Promise((r) => setTimeout(r, waitMs + 1000));
    }

    const resolution = await triggerDeleteResolution(request, REPOSITORY_ID);
    expect(resolution).not.toBeNull();
    expect(resolution.status).toBe('COMPLETED');
    expect(resolution.failedCount).toBe(0);
    expect(resolution.processedCount, 'delete resolution processed nothing at all').toBeGreaterThan(0);

    // 5. ARCHIVED is the outcome that says "resolved, and deliberately not purged"
    expect(await readTombstoneField(request, REPOSITORY_ID, deletedDocId, 'status')).toBe('ARCHIVED');

    // 6. …which is why the catalog entry is still there
    const entity = await queryAtlasEntity(request, 'nemaki_document', qn);
    expect(entity).not.toBeNull();
    expect(entity.entity?.status).toBe('ACTIVE');
    console.log(`Document ${deletedDocId} archived; catalog entry retained as designed`);
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

    // Wait for the rename to actually reach the catalog — otherwise this passes on the
    // pre-rename entity, which is exactly what "qualifiedName is stable" would look like.
    //
    // folderPath, not name: nemaki_folder extends Referenceable, which has no `name` attribute
    // (nemaki_document extends DataSet, which does — hence 2.2 can assert on it). The payload
    // factory does send name/description/owner/createTime/modifiedTime for folders, and Atlas
    // drops attributes the type does not declare, so a folder entity's name is permanently
    // null. Whether folders should carry a name in the catalog is a schema decision; asserting
    // on it here would just be asserting on a known gap.
    const renamed = await syncUntil(request, async () => {
      const entity = await queryAtlasEntity(request, 'nemaki_folder', qn);
      return typeof entity?.entity?.attributes?.folderPath === 'string'
        && entity.entity.attributes.folderPath.includes(newFolderName);
    });
    expect(renamed, 'the rename never reached Atlas under the unchanged qualifiedName').toBe(true);

    // Same qualifiedName as before: it is objectId-based, not name-based.
    const after = await queryAtlasEntity(request, 'nemaki_folder', qn);
    expect(after.entity.attributes.qualifiedName).toBe(qn);
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
    // Group 2 created it, Group 3 was the last to read it.
    if (testFolderId) {
      await deleteCmisObject(request, testFolderId);
      await deleteAtlasEntity(request, 'nemaki_folder', `nemaki://${REPOSITORY_ID}/objects/${testFolderId}`);
      testFolderId = null;
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
      await deleteCouchDoc(request, LINEAGE_DB, docId);
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

    await injectCouchDoc(request, LINEAGE_DB, event);
    group4Events.push(event._id);

    // Wait for projection to pick up the event
    const atlasQn = trackProcess(`nemakiware:${LINEAGE_REPO}:import_uploaded:${eventKey}`);
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

    await injectCouchDoc(request, LINEAGE_DB, event);
    group4Events.push(event._id);

    const atlasQn = trackProcess(`nemakiware:${LINEAGE_REPO}:archive_local:${eventKey}`);
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

    await injectCouchDoc(request, LINEAGE_DB, event);
    group4Events.push(event._id);

    const atlasQn = trackProcess(`nemakiware:${LINEAGE_REPO}:export_selected_objects:${eventKey}`);
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
      await deleteCouchDoc(request, LINEAGE_DB, docId);
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

    await injectCouchDoc(request, LINEAGE_DB, event);
    group5Events.push(event._id);

    const atlasQn = trackProcess(`nemakiware:${LINEAGE_REPO}:cloud_sync_upload:${eventKey}`);
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

    await injectCouchDoc(request, LINEAGE_DB, event);
    group5Events.push(event._id);

    const atlasQn = trackProcess(`nemakiware:${LINEAGE_REPO}:cloud_sync_download:${eventKey}`);
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
  let replayEventId: string | null = null;
  let replayEventKey: string | null = null;
  let replayEventCouchId: string | null = null;

  test.afterAll(async ({ request }) => {
    if (replayEventCouchId) {
      await deleteCouchDoc(request, LINEAGE_DB, replayEventCouchId);
    }
  });

  /**
   * A PROJECTING fixture, not a FAILED one.
   *
   * LineageProjectionLoop processes PENDING *and* FAILED, on a 10-second poll, so a FAILED
   * fixture can be claimed and published before the assertion reads it — the test would pass or
   * fail on timing. PROJECTING is the one non-terminal state the loop leaves alone (it treats it
   * as claimed by another node and stops), until reapStaleProjecting reclaims it much later.
   * That gives a stable starting point for the thing actually under test: the replay API.
   *
   * Switching `atlas.enabled` off to manufacture a failure does not work either — the loop asks
   * sink.isAvailable() first and skips the whole target without touching any event, so the event
   * stays PENDING. It also risked leaving that persisted setting off, which is what makes the
   * entire file skip.
   */
  test('7.1 A claimed event is replayable', async ({ request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);

    const suffix = randomSuffix();
    const eventKey = `test-replay-${suffix}`;
    const event = makeLineageEvent(
      'IMPORT_UPLOADED',
      eventKey,
      [`nemaki://${LINEAGE_REPO}/objects/test-input-${suffix}`],
      [`nemaki://${LINEAGE_REPO}/objects/test-output-${suffix}`]
    );
    event.publishStatusByTarget = { atlas: 'PROJECTING' };
    event.claimedAtByTarget = { atlas: new Date().toISOString() };
    replayEventId = event.eventId;
    replayEventKey = eventKey;
    replayEventCouchId = event._id;

    await injectCouchDoc(request, LINEAGE_DB, event);

    const evtRes = await request.get(
      `${BASE_URL}/core/api/v1/admin/lineage-journal/events/${replayEventId}`,
      { headers: { Authorization: AUTH_HEADER } }
    );
    expect(evtRes.ok(), `event lookup -> HTTP ${evtRes.status()}`).toBe(true);
    expect((await evtRes.json())?.publishStatusByTarget?.atlas).toBe('PROJECTING');
  });

  test('7.2 Replay re-drives the event through to Atlas', async ({ request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);
    test.skip(!replayEventId, 'ENV: Depends on 7.1');

    const replayRes = await request.post(
      `${BASE_URL}/core/api/v1/admin/lineage-journal/events/${replayEventId}/replay?target=atlas`,
      { headers: { Authorization: AUTH_HEADER, 'X-Requested-With': 'XMLHttpRequest' } }
    );
    expect(replayRes.ok(), `replay -> HTTP ${replayRes.status()}`).toBe(true);

    const published = await pollUntil(async () => {
      const evtRes = await request.get(
        `${BASE_URL}/core/api/v1/admin/lineage-journal/events/${replayEventId}`,
        { headers: { Authorization: AUTH_HEADER } }
      );
      if (!evtRes.ok()) return false;
      return (await evtRes.json())?.publishStatusByTarget?.atlas === 'PUBLISHED';
    }, 120000, 5000);

    // PUBLISHED, not "PENDING is fine too": accepting a non-terminal status means the test
    // passes with the replay path dead, which is the one thing it exists to check.
    expect(published, 'replayed event never reached PUBLISHED within 120s').toBe(true);

    // …and the projection must have reached Atlas, not just moved a status field. Same contract
    // AtlasLineageSink.buildAtlasPayload writes and Group 4 asserts.
    const process = await queryAtlasEntity(
      request, 'Process', `nemakiware:${LINEAGE_REPO}:import_uploaded:${replayEventKey}`);
    expect(process, 'the event is PUBLISHED but no Atlas Process entity exists for it')
      .not.toBeNull();
    console.log('Replayed event is PUBLISHED and present in Atlas');
  });

  test('7.3 Replay-all dead letters', async ({ request }) => {
    // Not run here, and not because it is unimportant.
    //
    // /dead-letters/replay-all is global: it re-drives every unreplayed dead letter in
    // nemaki_lineage. This environment holds 93 of them, all from bedroom and none from this
    // file, and calling it would re-inject those events and mark the dead letters replayed. The
    // synthetic repository this file uses isolates the journal stream, not an API that ignores
    // repository scope. `replayed > 0` would also be satisfied by those 93 regardless of
    // anything this file did.
    //
    // It belongs in a CouchDB integration test with a throwaway journal, where the before/after
    // set is known. Targeted replay is covered by 7.2.
    test.skip(true, 'ENV: replay-all is global and would re-drive unrelated dead letters');
  });
});

test.describe('Group 8: Multi-target', () => {
  /**
   * Scope: this covers the PROJECTOR, not target selection. The fixture still sets
   * `{atlas: PENDING}` itself, so LineageEventBuilder.targets() and the emitter's choice of
   * targets are not exercised here and would have to break somewhere else to be caught — that
   * belongs with a test that emits through a real business operation. What this does establish
   * is that the projector drives the configured target to a terminal state and does not invent
   * statuses for targets that were never requested.
   */
  test('8.1 The projector publishes the configured target and no other', async ({ request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);

    const configured = await request.get(
      `${BASE_URL}/core/api/v1/admin/integration-settings/lineage`,
      { headers: { Authorization: AUTH_HEADER } }
    );
    expect(configured.ok()).toBe(true);
    const targets = (await configured.json()).settings['lineage.targets'];
    expect(targets, 'this group assumes the atlas-only configuration').toBe('atlas');

    const suffix = randomSuffix();
    const eventKey = `test-multitarget-${suffix}`;
    const event = makeLineageEvent(
      'IMPORT_UPLOADED',
      eventKey,
      [`nemaki://${LINEAGE_REPO}/objects/test-input-${suffix}`],
      [`nemaki://${LINEAGE_REPO}/objects/test-output-${suffix}`]
    );
    await injectCouchDoc(request, LINEAGE_DB, event);

    try {
      // Atlas must actually publish it…
      const published = await pollUntil(async () => {
        const evtRes = await request.get(
          `${BASE_URL}/core/api/v1/admin/lineage-journal/events/${event.eventId}`,
          { headers: { Authorization: AUTH_HEADER } }
        );
        if (!evtRes.ok()) return false;
        return (await evtRes.json())?.publishStatusByTarget?.atlas === 'PUBLISHED';
      }, 120000, 5000);
      expect(published, 'the single configured target never published the event').toBe(true);

      // …and no other target may have been given a status at all.
      const evtRes = await request.get(
        `${BASE_URL}/core/api/v1/admin/lineage-journal/events/${event.eventId}`,
        { headers: { Authorization: AUTH_HEADER } }
      );
      const statuses = Object.keys((await evtRes.json()).publishStatusByTarget || {});
      expect(statuses.sort()).toEqual(['atlas']);
      console.log(`Targets that projected: ${statuses.join(', ')}`);
    } finally {
      await deleteCouchDoc(request, LINEAGE_DB, event._id);
    }
  });

  /**
   * The cursor contract, which the fixed sequence numbering used to make untestable: two events
   * appended in order must BOTH project. If the cursor jumped past the first, the second would
   * still publish and the bug would be invisible — so assert on both.
   */
  test('8.2 Two consecutive events both project', async ({ request }) => {
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);

    const a = randomSuffix();
    const b = randomSuffix();
    const first = makeLineageEvent('IMPORT_UPLOADED', `test-seq-a-${a}`,
      [`nemaki://${LINEAGE_REPO}/objects/in-${a}`], [`nemaki://${LINEAGE_REPO}/objects/out-${a}`]);
    const second = makeLineageEvent('IMPORT_UPLOADED', `test-seq-b-${b}`,
      [`nemaki://${LINEAGE_REPO}/objects/in-${b}`], [`nemaki://${LINEAGE_REPO}/objects/out-${b}`]);
    expect(second.sequenceNumber).toBe(first.sequenceNumber + 1);

    await injectCouchDoc(request, LINEAGE_DB, first);
    await injectCouchDoc(request, LINEAGE_DB, second);

    try {
      for (const event of [first, second]) {
        const published = await pollUntil(async () => {
          const evtRes = await request.get(
            `${BASE_URL}/core/api/v1/admin/lineage-journal/events/${event.eventId}`,
            { headers: { Authorization: AUTH_HEADER } }
          );
          if (!evtRes.ok()) return false;
          return (await evtRes.json())?.publishStatusByTarget?.atlas === 'PUBLISHED';
        }, 120000, 5000);
        expect(published, `event seq ${event.sequenceNumber} never projected`).toBe(true);
      }
      console.log(`Sequence ${first.sequenceNumber} and ${second.sequenceNumber} both projected`);
    } finally {
      await deleteCouchDoc(request, LINEAGE_DB, first._id);
      await deleteCouchDoc(request, LINEAGE_DB, second._id);
    }
  });

});

/**
 * One teardown for everything shared, at file level.
 *
 * Per-group afterAll hooks only run for groups that were reached, and a group that fails part
 * way leaves whatever it had already created. That is not theoretical: when 2.5 failed, the
 * folder it had created was still in CMIS and in Atlas afterwards, because its removal was
 * Group 3's job and Group 3 never ran.
 *
 * Everything injected lived under a synthetic repository id, so that stream's cursor and
 * sequence counter are ours to remove; bedroom's own stream is untouched by construction.
 */
test.describe('cleanup', () => {
  test.afterAll(async ({ request }) => {
    if (!atlasAvailable) return;

    for (const id of [testDocId, unsyncedDocId]) {
      if (id) {
        await deleteCmisObject(request, id);
        await deleteAtlasEntity(request, 'nemaki_document', `nemaki://${REPOSITORY_ID}/objects/${id}`);
      }
    }
    if (testFolderId) {
      await deleteCmisObject(request, testFolderId);
      await deleteAtlasEntity(request, 'nemaki_folder', `nemaki://${REPOSITORY_ID}/objects/${testFolderId}`);
    }
    testDocId = null;
    testFolderId = null;
    unsyncedDocId = null;

    // The Process entities the projector created for the injected events.
    for (const eventKey of projectedProcessKeys) {
      await deleteAtlasEntity(request, 'Process', eventKey);
    }

    for (const docId of [`projection_cursor:atlas:${LINEAGE_REPO}`, `lineage_seq:${LINEAGE_REPO}`]) {
      await deleteCouchDoc(request, LINEAGE_DB, docId);
    }
    for (const docId of injectedCouchIds) {
      await deleteCouchDoc(request, LINEAGE_DB, docId);
    }
  });

  test('cleanup placeholder', async ({ request }) => {
    // Playwright only runs a describe's afterAll if the describe contains a test.
    const available = await checkAtlasAvailable(request);
    skipIfNoAtlas(available);
    expect(true).toBe(true);
  });
});
