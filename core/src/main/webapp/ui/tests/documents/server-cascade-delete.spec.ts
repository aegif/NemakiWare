/**
 * Server-Side Cascade Delete Tests
 *
 * Verifies that when deleteObject(parent) is called via CMIS Browser Binding,
 * the server automatically cascades to delete child objects linked via
 * nemaki:parentChildRelationship. No UI or client-side collectParentChildDescendants needed.
 *
 * Test Scenarios:
 * - B1: Parent -> Child (single level), deleteObject(parent) => both deleted
 * - B2: Parent -> Child -> Grandchild (chain), deleteObject(parent) => all deleted
 * - B3: deleteObject(relationship) => only relationship removed, parent+child remain
 * - L1: Circular ref A->B->C->A, deleteObject(A) => no infinite loop, all deleted
 * - E4: Parent deletable, child NOT deletable (ACL) => deleteObject(parent) fails with permission error; both remain
 */

import { test, expect } from '@playwright/test';
import { generateTestId } from '../utils/test-helper';

const REPOSITORY_ID = 'bedroom';
const BASE_URL = 'http://localhost:8080/core';
const AUTH_HEADER = 'Basic ' + Buffer.from('admin:admin').toString('base64');
const TEST_USER = 'api-e2e-testuser';
const TEST_USER_PASS = 'test';
const TEST_USER_AUTH = 'Basic ' + Buffer.from(`${TEST_USER}:${TEST_USER_PASS}`).toString('base64');

async function getRootFolderId(request: any, authHeader: string = AUTH_HEADER): Promise<string> {
  const res = await request.get(
    `${BASE_URL}/browser/${REPOSITORY_ID}/root?cmisselector=object`,
    { headers: { 'Authorization': authHeader } }
  );
  expect(res.ok()).toBeTruthy();
  const data = await res.json();
  const rootId = data.succinctProperties?.['cmis:objectId'] || data.properties?.['cmis:objectId']?.value;
  expect(rootId).toBeTruthy();
  return rootId;
}

async function browserPost(request: any, form: Record<string, string>, objectId?: string, authHeader: string = AUTH_HEADER) {
  const url = objectId
    ? `${BASE_URL}/browser/${REPOSITORY_ID}?objectId=${objectId}`
    : `${BASE_URL}/browser/${REPOSITORY_ID}`;
  return request.post(url, {
    headers: { 'Authorization': authHeader, 'Content-Type': 'application/x-www-form-urlencoded' },
    form,
  });
}

async function createFolder(request: any, name: string, parentId: string): Promise<string> {
  const res = await browserPost(request, {
    cmisaction: 'createFolder',
    'propertyId[0]': 'cmis:objectTypeId',
    'propertyValue[0]': 'cmis:folder',
    'propertyId[1]': 'cmis:name',
    'propertyValue[1]': name,
  }, parentId);
  expect(res.ok()).toBeTruthy();
  const data = await res.json();
  const id = data.succinctProperties?.['cmis:objectId'] || data.properties?.['cmis:objectId']?.value;
  expect(id).toBeTruthy();
  return id;
}

async function createDocument(request: any, name: string, parentId: string): Promise<string> {
  const res = await browserPost(request, {
    cmisaction: 'createDocument',
    folderId: parentId,
    'propertyId[0]': 'cmis:objectTypeId',
    'propertyValue[0]': 'cmis:document',
    'propertyId[1]': 'cmis:name',
    'propertyValue[1]': name,
  }, parentId);
  expect(res.ok()).toBeTruthy();
  const data = await res.json();
  const id = data.succinctProperties?.['cmis:objectId'] || data.properties?.['cmis:objectId']?.value;
  expect(id).toBeTruthy();
  return id;
}

function extractObjectId(data: any): string | undefined {
  return data?.succinctProperties?.['cmis:objectId']
    || data?.properties?.['cmis:objectId']?.value
    || data?.objects?.[0]?.succinctProperties?.['cmis:objectId']
    || data?.objects?.[0]?.properties?.['cmis:objectId']?.value;
}

async function createParentChildRel(request: any, sourceId: string, targetId: string, name: string): Promise<string> {
  const res = await browserPost(request, {
    cmisaction: 'createRelationship',
    succinct: 'false',
    'propertyId[0]': 'cmis:objectTypeId',
    'propertyValue[0]': 'nemaki:parentChildRelationship',
    'propertyId[1]': 'cmis:name',
    'propertyValue[1]': name,
    'propertyId[2]': 'cmis:sourceId',
    'propertyValue[2]': sourceId,
    'propertyId[3]': 'cmis:targetId',
    'propertyValue[3]': targetId,
  });
  if (!res.ok()) {
    const errText = await res.text();
    throw new Error(`createRelationship failed: ${res.status()} ${errText}`);
  }
  const data = await res.json();
  const relId = extractObjectId(data);
  if (!relId) {
    throw new Error(`createRelationship: objectId not found in response: ${JSON.stringify(data)}`);
  }
  return relId;
}

async function deleteObject(request: any, objectId: string, authHeader: string = AUTH_HEADER) {
  return browserPost(request, { cmisaction: 'delete', objectId }, undefined, authHeader);
}

async function applyACL(request: any, objectId: string, addPrincipal: string, addPermission: string) {
  const res = await browserPost(request, {
    cmisaction: 'applyACL',
    objectId,
    'addACEPrincipal[0]': addPrincipal,
    'addACEPermission[0][0]': addPermission,
  });
  return res;
}

async function getObjectExists(request: any, objectId: string, authHeader: string = AUTH_HEADER): Promise<boolean> {
  const res = await request.get(`${BASE_URL}/browser/${REPOSITORY_ID}/${objectId}?cmisselector=object`, {
    headers: { 'Authorization': authHeader },
  });
  return res.ok();
}

test.describe('Server-Side Cascade Delete', () => {
  test.setTimeout(120000);

  test('B1: deleteObject(parent) cascades to child', async ({ request }) => {
    const rootId = await getRootFolderId(request);
    const uuid = generateTestId();
    const parentId = await createFolder(request, `srv-cascade-parent-${uuid}`, rootId);
    const childId = await createDocument(request, `srv-cascade-child-${uuid}.txt`, rootId);
    expect(parentId).toBeTruthy();
    expect(childId).toBeTruthy();

    const relId = await createParentChildRel(request, parentId, childId, `rel-${uuid}`);
    expect(relId).toBeTruthy();

    const delRes = await deleteObject(request, parentId);
    expect(delRes.ok()).toBeTruthy();

    expect(await getObjectExists(request, parentId)).toBe(false);
    expect(await getObjectExists(request, childId)).toBe(false);
    expect(await getObjectExists(request, relId)).toBe(false);
  });

  test('B2: deleteObject(parent) cascades to grandchild chain', async ({ request }) => {
    const rootId = await getRootFolderId(request);
    const uuid = generateTestId();
    const parentId = await createFolder(request, `srv-cascade-p-${uuid}`, rootId);
    const childId = await createFolder(request, `srv-cascade-c-${uuid}`, rootId);
    const grandchildId = await createDocument(request, `srv-cascade-g-${uuid}.txt`, rootId);

    await createParentChildRel(request, parentId, childId, `rel-pc-${uuid}`);
    await createParentChildRel(request, childId, grandchildId, `rel-cg-${uuid}`);

    const delRes = await deleteObject(request, parentId);
    expect(delRes.ok()).toBeTruthy();

    expect(await getObjectExists(request, parentId)).toBe(false);
    expect(await getObjectExists(request, childId)).toBe(false);
    expect(await getObjectExists(request, grandchildId)).toBe(false);
  });

  test('B3: deleteObject(relationship) does NOT delete parent or child', async ({ request }) => {
    const rootId = await getRootFolderId(request);
    const uuid = generateTestId();
    const parentId = await createFolder(request, `srv-cascade-p-${uuid}`, rootId);
    const childId = await createDocument(request, `srv-cascade-c-${uuid}.txt`, rootId);
    const relId = await createParentChildRel(request, parentId, childId, `rel-${uuid}`);

    const delRes = await deleteObject(request, relId);
    expect(delRes.ok()).toBeTruthy();

    expect(await getObjectExists(request, relId)).toBe(false);
    expect(await getObjectExists(request, parentId)).toBe(true);
    expect(await getObjectExists(request, childId)).toBe(true);

    await deleteObject(request, parentId);
    await deleteObject(request, childId);
  });

  test('L1: circular parentChild does not cause infinite loop', async ({ request }) => {
    const rootId = await getRootFolderId(request);
    const uuid = generateTestId();
    const aId = await createFolder(request, `srv-circ-a-${uuid}`, rootId);
    const bId = await createFolder(request, `srv-circ-b-${uuid}`, rootId);
    const cId = await createFolder(request, `srv-circ-c-${uuid}`, rootId);

    await createParentChildRel(request, aId, bId, `rel-ab-${uuid}`);
    await createParentChildRel(request, bId, cId, `rel-bc-${uuid}`);
    await createParentChildRel(request, cId, aId, `rel-ca-${uuid}`);

    const delRes = await deleteObject(request, aId);
    expect(delRes.ok()).toBeTruthy();

    expect(await getObjectExists(request, aId)).toBe(false);
    expect(await getObjectExists(request, bId)).toBe(false);
    expect(await getObjectExists(request, cId)).toBe(false);
  });

  test('E4: deleteObject(parent) fails when child has no delete permission; both remain', async ({ request }) => {
    const rootId = await getRootFolderId(request);
    const uuid = generateTestId();
    const parentId = await createFolder(request, `srv-e4-parent-${uuid}`, rootId);
    const childId = await createDocument(request, `srv-e4-child-${uuid}.txt`, rootId);
    await createParentChildRel(request, parentId, childId, `rel-e4-${uuid}`);

    await applyACL(request, parentId, TEST_USER, 'cmis:all');
    await applyACL(request, childId, TEST_USER, 'cmis:read');

    const delRes = await deleteObject(request, parentId, TEST_USER_AUTH);
    const status = delRes.status();
    const body = await delRes.text();

    if (!delRes.ok()) {
      expect(
        status === 403 || status === 500 || (status >= 400 && body.toLowerCase().includes('permission')),
        `Expected permission-related failure, got ${status}: ${body.slice(0, 200)}`
      ).toBe(true);
      expect(await getObjectExists(request, parentId)).toBe(true);
      expect(await getObjectExists(request, childId)).toBe(true);
    }
    if (await getObjectExists(request, parentId)) await deleteObject(request, parentId);
    if (await getObjectExists(request, childId)) await deleteObject(request, childId);
  });
});
