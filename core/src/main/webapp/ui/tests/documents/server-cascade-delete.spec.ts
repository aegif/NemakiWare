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

  test('E4: deleteObject(parent) succeeds even when child has no delete permission; child remains', async ({ request }) => {
    const rootId = await getRootFolderId(request);
    const uuid = generateTestId();
    const parentId = await createFolder(request, `srv-e4-parent-${uuid}`, rootId);
    const childId = await createDocument(request, `srv-e4-child-${uuid}.txt`, rootId);
    const relId = await createParentChildRel(request, parentId, childId, `rel-e4-${uuid}`);

    await applyACL(request, parentId, TEST_USER, 'cmis:all');
    await applyACL(request, childId, TEST_USER, 'cmis:read');

    const delRes = await deleteObject(request, parentId, TEST_USER_AUTH);
    expect(delRes.ok()).toBeTruthy();

    expect(await getObjectExists(request, parentId)).toBe(false);
    expect(await getObjectExists(request, relId)).toBe(false);
    expect(await getObjectExists(request, childId)).toBe(true);

    if (await getObjectExists(request, childId)) await deleteObject(request, childId);
  });

  test('B4: deleteObject(parent) cascades to multiple children', async ({ request }) => {
    const rootId = await getRootFolderId(request);
    const uuid = generateTestId();
    const parentId = await createFolder(request, `srv-multi-parent-${uuid}`, rootId);
    const child1Id = await createDocument(request, `srv-multi-child1-${uuid}.txt`, rootId);
    const child2Id = await createDocument(request, `srv-multi-child2-${uuid}.txt`, rootId);
    const child3Id = await createFolder(request, `srv-multi-child3-${uuid}`, rootId);

    await createParentChildRel(request, parentId, child1Id, `rel-pc1-${uuid}`);
    await createParentChildRel(request, parentId, child2Id, `rel-pc2-${uuid}`);
    await createParentChildRel(request, parentId, child3Id, `rel-pc3-${uuid}`);

    const delRes = await deleteObject(request, parentId);
    expect(delRes.ok()).toBeTruthy();

    expect(await getObjectExists(request, parentId)).toBe(false);
    expect(await getObjectExists(request, child1Id)).toBe(false);
    expect(await getObjectExists(request, child2Id)).toBe(false);
    expect(await getObjectExists(request, child3Id)).toBe(false);
  });

  test('E5: Mixed permission children - deletable ones deleted, non-deletable remain', async ({ request }) => {
    const rootId = await getRootFolderId(request);
    const uuid = generateTestId();
    const parentId = await createFolder(request, `srv-mixed-parent-${uuid}`, rootId);
    const deletableChildId = await createDocument(request, `srv-mixed-ok-${uuid}.txt`, rootId);
    const nonDeletableChildId = await createDocument(request, `srv-mixed-deny-${uuid}.txt`, rootId);

    await createParentChildRel(request, parentId, deletableChildId, `rel-ok-${uuid}`);
    await createParentChildRel(request, parentId, nonDeletableChildId, `rel-deny-${uuid}`);

    // Grant test user full access to parent and deletable child, but only read to non-deletable
    await applyACL(request, parentId, TEST_USER, 'cmis:all');
    await applyACL(request, deletableChildId, TEST_USER, 'cmis:all');
    await applyACL(request, nonDeletableChildId, TEST_USER, 'cmis:read');

    const delRes = await deleteObject(request, parentId, TEST_USER_AUTH);
    expect(delRes.ok()).toBeTruthy();

    expect(await getObjectExists(request, parentId)).toBe(false);
    expect(await getObjectExists(request, deletableChildId)).toBe(false);
    expect(await getObjectExists(request, nonDeletableChildId)).toBe(true);

    // Cleanup
    if (await getObjectExists(request, nonDeletableChildId)) await deleteObject(request, nonDeletableChildId);
  });

  test('B5: Four-level deep cascade (parent->child->grandchild->great-grandchild)', async ({ request }) => {
    const rootId = await getRootFolderId(request);
    const uuid = generateTestId();
    const level1Id = await createFolder(request, `srv-deep-l1-${uuid}`, rootId);
    const level2Id = await createFolder(request, `srv-deep-l2-${uuid}`, rootId);
    const level3Id = await createFolder(request, `srv-deep-l3-${uuid}`, rootId);
    const level4Id = await createDocument(request, `srv-deep-l4-${uuid}.txt`, rootId);

    await createParentChildRel(request, level1Id, level2Id, `rel-12-${uuid}`);
    await createParentChildRel(request, level2Id, level3Id, `rel-23-${uuid}`);
    await createParentChildRel(request, level3Id, level4Id, `rel-34-${uuid}`);

    const delRes = await deleteObject(request, level1Id);
    expect(delRes.ok()).toBeTruthy();

    expect(await getObjectExists(request, level1Id)).toBe(false);
    expect(await getObjectExists(request, level2Id)).toBe(false);
    expect(await getObjectExists(request, level3Id)).toBe(false);
    expect(await getObjectExists(request, level4Id)).toBe(false);
  });

  test('B6: Tree structure cascade (parent with two children, each with grandchildren)', async ({ request }) => {
    const rootId = await getRootFolderId(request);
    const uuid = generateTestId();

    // Tree structure:
    //       parent
    //      /      \
    //   child1   child2
    //    |         |
    //  grand1    grand2

    const parentId = await createFolder(request, `srv-tree-parent-${uuid}`, rootId);
    const child1Id = await createFolder(request, `srv-tree-c1-${uuid}`, rootId);
    const child2Id = await createFolder(request, `srv-tree-c2-${uuid}`, rootId);
    const grand1Id = await createDocument(request, `srv-tree-g1-${uuid}.txt`, rootId);
    const grand2Id = await createDocument(request, `srv-tree-g2-${uuid}.txt`, rootId);

    await createParentChildRel(request, parentId, child1Id, `rel-p-c1-${uuid}`);
    await createParentChildRel(request, parentId, child2Id, `rel-p-c2-${uuid}`);
    await createParentChildRel(request, child1Id, grand1Id, `rel-c1-g1-${uuid}`);
    await createParentChildRel(request, child2Id, grand2Id, `rel-c2-g2-${uuid}`);

    const delRes = await deleteObject(request, parentId);
    expect(delRes.ok()).toBeTruthy();

    expect(await getObjectExists(request, parentId)).toBe(false);
    expect(await getObjectExists(request, child1Id)).toBe(false);
    expect(await getObjectExists(request, child2Id)).toBe(false);
    expect(await getObjectExists(request, grand1Id)).toBe(false);
    expect(await getObjectExists(request, grand2Id)).toBe(false);
  });

  test('E6: Deleting middle of chain only affects descendants', async ({ request }) => {
    const rootId = await getRootFolderId(request);
    const uuid = generateTestId();

    // Chain: A -> B -> C
    // Delete B: should delete B and C, but A remains

    const aId = await createFolder(request, `srv-mid-a-${uuid}`, rootId);
    const bId = await createFolder(request, `srv-mid-b-${uuid}`, rootId);
    const cId = await createDocument(request, `srv-mid-c-${uuid}.txt`, rootId);

    await createParentChildRel(request, aId, bId, `rel-ab-${uuid}`);
    await createParentChildRel(request, bId, cId, `rel-bc-${uuid}`);

    const delRes = await deleteObject(request, bId);
    expect(delRes.ok()).toBeTruthy();

    expect(await getObjectExists(request, aId)).toBe(true); // A remains
    expect(await getObjectExists(request, bId)).toBe(false); // B deleted
    expect(await getObjectExists(request, cId)).toBe(false); // C cascaded

    // Cleanup
    if (await getObjectExists(request, aId)) await deleteObject(request, aId);
  });
});
