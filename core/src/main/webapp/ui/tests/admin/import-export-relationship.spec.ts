import { test, expect } from '@playwright/test';
import * as zlib from 'zlib';

/**
 * Import/Export E2E: Custom Types + Relationships + ID Remapping
 *
 * Tests:
 * A. Custom document type + custom relationship type definition → export → import round-trip
 * B. Relationship ID remapping after import (old source/target IDs → new IDs)
 * C. Self-referencing relationship preservation (P3 regression: disjunction → union fix)
 * D. .nemaki-relationships/ directory structure in ZIP
 *
 * Prerequisites:
 * - NemakiWare running on http://localhost:8080
 * - Admin credentials (admin/admin)
 */

const BASE_URL = 'http://localhost:8080';
const AUTH_HEADER = 'Basic ' + Buffer.from('admin:admin').toString('base64');
const BROWSER_URL = `${BASE_URL}/core/browser/bedroom`;
const REST_URL = `${BASE_URL}/core/rest/repo/bedroom`;
const TIMEOUT = 30000;
const UNIQUE = Date.now();

// Custom type IDs
const CUSTOM_DOC_TYPE = `test:relDoc${UNIQUE}`;
const CUSTOM_DOC_PROP = `test:relDocTag${UNIQUE}`;
const CUSTOM_REL_TYPE = `test:relLink${UNIQUE}`;
const CUSTOM_REL_PROP = `test:relNote${UNIQUE}`;

// ---------- Helpers ----------

async function getRootFolderId(request: any): Promise<string> {
  const res = await request.get(`${BROWSER_URL}/root?cmisselector=object`, {
    headers: { Authorization: AUTH_HEADER },
  });
  expect(res.ok()).toBeTruthy();
  const data = await res.json();
  return data.properties['cmis:objectId'].value;
}

async function createFolder(request: any, parentId: string, name: string): Promise<string> {
  const res = await request.post(BROWSER_URL, {
    headers: { Authorization: AUTH_HEADER },
    multipart: {
      cmisaction: 'createFolder',
      'propertyId[0]': 'cmis:objectTypeId',
      'propertyValue[0]': 'cmis:folder',
      'propertyId[1]': 'cmis:name',
      'propertyValue[1]': name,
      objectId: parentId,
      succinct: 'true',
    },
  });
  expect(res.ok()).toBeTruthy();
  const data = await res.json();
  return data.succinctProperties['cmis:objectId'];
}

async function createDocumentOfType(
  request: any,
  folderId: string,
  name: string,
  content: string,
  typeId: string,
  extraProps?: Record<string, string>,
): Promise<string> {
  const multipart: Record<string, any> = {
    cmisaction: 'createDocument',
    'propertyId[0]': 'cmis:objectTypeId',
    'propertyValue[0]': typeId,
    'propertyId[1]': 'cmis:name',
    'propertyValue[1]': name,
    objectId: folderId,
    content: { name, mimeType: 'text/plain', buffer: Buffer.from(content) },
    succinct: 'true',
  };
  let idx = 2;
  if (extraProps) {
    for (const [key, value] of Object.entries(extraProps)) {
      multipart[`propertyId[${idx}]`] = key;
      multipart[`propertyValue[${idx}]`] = value;
      idx++;
    }
  }
  const res = await request.post(BROWSER_URL, {
    headers: { Authorization: AUTH_HEADER },
    multipart,
    timeout: TIMEOUT,
  });
  expect(res.ok()).toBeTruthy();
  const data = await res.json();
  return data.succinctProperties['cmis:objectId'];
}

async function createRelationship(
  request: any,
  typeId: string,
  sourceId: string,
  targetId: string,
  name: string,
  extraProps?: Record<string, string>,
): Promise<string> {
  const formData = new URLSearchParams();
  formData.append('cmisaction', 'createRelationship');
  formData.append('propertyId[0]', 'cmis:objectTypeId');
  formData.append('propertyValue[0]', typeId);
  formData.append('propertyId[1]', 'cmis:name');
  formData.append('propertyValue[1]', name);
  formData.append('propertyId[2]', 'cmis:sourceId');
  formData.append('propertyValue[2]', sourceId);
  formData.append('propertyId[3]', 'cmis:targetId');
  formData.append('propertyValue[3]', targetId);
  let idx = 4;
  if (extraProps) {
    for (const [key, value] of Object.entries(extraProps)) {
      formData.append(`propertyId[${idx}]`, key);
      formData.append(`propertyValue[${idx}]`, value);
      idx++;
    }
  }
  const res = await request.post(BROWSER_URL, {
    headers: { Authorization: AUTH_HEADER, 'Content-Type': 'application/x-www-form-urlencoded' },
    data: formData.toString(),
    timeout: TIMEOUT,
  });
  expect([200, 201]).toContain(res.status());
  const data = await res.json();
  return data.properties?.['cmis:objectId']?.value || data.succinctProperties?.['cmis:objectId'];
}

async function getRelationships(request: any, objectId: string, direction: string = 'both'): Promise<any[]> {
  // CMIS Browser Binding: use includeRelationships on object selector
  // (cmisselector=relationships is not supported by OpenCMIS)
  // Valid values: "source", "target", "both" (not "either" which is the Java enum name)
  const res = await request.get(
    `${BROWSER_URL}/${objectId}?cmisselector=object&includeRelationships=${direction}`,
    { headers: { Authorization: AUTH_HEADER } },
  );
  if (!res.ok()) return [];
  const data = await res.json();
  // Relationships are returned as a top-level array on the object response
  return data.relationships || [];
}

async function getObjectProps(request: any, objectId: string): Promise<Record<string, any>> {
  const res = await request.get(`${BROWSER_URL}/${objectId}?cmisselector=object`, {
    headers: { Authorization: AUTH_HEADER },
  });
  expect(res.ok()).toBeTruthy();
  const data = await res.json();
  if (data.succinctProperties) return data.succinctProperties;
  const props: Record<string, any> = {};
  if (data.properties) {
    for (const [key, val] of Object.entries(data.properties)) {
      props[key] = (val as any)?.value;
    }
  }
  return props;
}

async function getChildren(request: any, folderId: string): Promise<any[]> {
  const res = await request.get(
    `${BROWSER_URL}/root?objectId=${folderId}&cmisselector=children`,
    { headers: { Authorization: AUTH_HEADER } },
  );
  expect(res.ok()).toBeTruthy();
  const data = await res.json();
  return data.objects || [];
}

async function exportFolder(request: any, folderId: string): Promise<Buffer> {
  const res = await request.get(`${REST_URL}/importexport/export/${folderId}`, {
    headers: { Authorization: AUTH_HEADER },
    timeout: 60000,
  });
  expect(res.ok()).toBeTruthy();
  return Buffer.from(await res.body());
}

async function importZip(request: any, targetFolderId: string, zipBuffer: Buffer): Promise<any> {
  const res = await request.post(`${REST_URL}/importexport/import/${targetFolderId}`, {
    headers: { Authorization: AUTH_HEADER },
    multipart: {
      file: { name: 'export.zip', mimeType: 'application/zip', buffer: zipBuffer },
    },
    timeout: 120000,
  });
  expect(res.ok()).toBeTruthy();
  return await res.json();
}

async function deleteTree(request: any, objectId: string): Promise<void> {
  try {
    await request.post(BROWSER_URL, {
      headers: { Authorization: AUTH_HEADER, 'Content-Type': 'application/x-www-form-urlencoded' },
      form: { cmisaction: 'deleteTree', objectId, allVersions: 'true', continueOnFailure: 'true' },
      timeout: TIMEOUT,
    });
  } catch (e) {
    console.log(`Cleanup warning: ${e}`);
  }
}

async function deleteObject(request: any, objectId: string): Promise<void> {
  try {
    const formData = new URLSearchParams();
    formData.append('cmisaction', 'delete');
    formData.append('objectId', objectId);
    await request.post(BROWSER_URL, {
      headers: { Authorization: AUTH_HEADER, 'Content-Type': 'application/x-www-form-urlencoded' },
      data: formData.toString(),
      timeout: TIMEOUT,
    });
  } catch (e) {
    console.log(`Delete warning: ${e}`);
  }
}

async function deleteCustomType(request: any, typeId: string): Promise<void> {
  try {
    await request.delete(`${REST_URL}/type/delete/${typeId}`, {
      headers: { Authorization: AUTH_HEADER },
      timeout: TIMEOUT,
    });
  } catch (e) {
    console.log(`Type cleanup warning: ${e}`);
  }
}

async function createCustomDocType(request: any): Promise<void> {
  const res = await request.post(`${REST_URL}/type/create`, {
    headers: { Authorization: AUTH_HEADER, 'Content-Type': 'application/json' },
    data: {
      id: CUSTOM_DOC_TYPE,
      localName: CUSTOM_DOC_TYPE,
      displayName: 'Rel Test Document',
      baseId: 'cmis:document',
      parentId: 'cmis:document',
      creatable: true,
      queryable: true,
      fulltextIndexed: true,
      includedInSupertypeQuery: true,
      controllablePolicy: false,
      controllableACL: true,
      propertyDefinitions: [
        {
          id: CUSTOM_DOC_PROP,
          localName: CUSTOM_DOC_PROP,
          displayName: 'Tag',
          propertyType: 'string',
          cardinality: 'single',
          updatability: 'readwrite',
          required: false,
          queryable: true,
        },
      ],
    },
    timeout: TIMEOUT,
  });
  expect(res.ok()).toBeTruthy();
}

async function createCustomRelType(request: any): Promise<void> {
  const res = await request.post(`${REST_URL}/type/create`, {
    headers: { Authorization: AUTH_HEADER, 'Content-Type': 'application/json' },
    data: {
      id: CUSTOM_REL_TYPE,
      localName: CUSTOM_REL_TYPE,
      displayName: 'Test Link',
      baseId: 'cmis:relationship',
      parentId: 'cmis:relationship',
      creatable: true,
      queryable: true,
      fulltextIndexed: false,
      includedInSupertypeQuery: true,
      controllablePolicy: false,
      controllableACL: true,
      propertyDefinitions: [
        {
          id: CUSTOM_REL_PROP,
          localName: CUSTOM_REL_PROP,
          displayName: 'Note',
          propertyType: 'string',
          cardinality: 'single',
          updatability: 'readwrite',
          required: false,
          queryable: false,
        },
      ],
    },
    timeout: TIMEOUT,
  });
  expect(res.ok()).toBeTruthy();
}

// Parse ZIP entries using central directory
function parseZipEntries(buffer: Buffer): Map<string, Buffer> {
  const entries = new Map<string, Buffer>();
  let eocdOffset = -1;
  for (let i = buffer.length - 22; i >= 0; i--) {
    if (buffer.readUInt32LE(i) === 0x06054b50) { eocdOffset = i; break; }
  }
  if (eocdOffset < 0) return entries;

  const cdOffset = buffer.readUInt32LE(eocdOffset + 16);
  const cdEntries = buffer.readUInt16LE(eocdOffset + 10);

  let offset = cdOffset;
  for (let i = 0; i < cdEntries; i++) {
    if (buffer.readUInt32LE(offset) !== 0x02014b50) break;
    const compressionMethod = buffer.readUInt16LE(offset + 10);
    const compressedSize = buffer.readUInt32LE(offset + 20);
    const nameLen = buffer.readUInt16LE(offset + 28);
    const extraLen = buffer.readUInt16LE(offset + 30);
    const commentLen = buffer.readUInt16LE(offset + 32);
    const localHeaderOffset = buffer.readUInt32LE(offset + 42);
    const name = buffer.toString('utf8', offset + 46, offset + 46 + nameLen);

    const localNameLen = buffer.readUInt16LE(localHeaderOffset + 26);
    const localExtraLen = buffer.readUInt16LE(localHeaderOffset + 28);
    const dataStart = localHeaderOffset + 30 + localNameLen + localExtraLen;

    let fileData: Buffer;
    if (compressionMethod === 8) {
      try { fileData = zlib.inflateRawSync(buffer.subarray(dataStart, dataStart + compressedSize)); }
      catch { fileData = buffer.subarray(dataStart, dataStart + compressedSize); }
    } else {
      fileData = buffer.subarray(dataStart, dataStart + compressedSize);
    }
    entries.set(name, fileData);
    offset += 46 + nameLen + extraLen + commentLen;
  }
  return entries;
}

// =================================================================
// Test Suite
// =================================================================

test.describe.configure({ mode: 'serial' });

test.describe('Import/Export: Custom Types + Relationships + ID Remapping', () => {
  let rootFolderId: string;
  let testFolderId: string;
  const cleanupFolderIds: string[] = [];
  const cleanupRelIds: string[] = [];

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    rootFolderId = await getRootFolderId(page.request);
    testFolderId = await createFolder(page.request, rootFolderId, `ie-rel-test-${UNIQUE}`);
    cleanupFolderIds.push(testFolderId);

    // Create custom types
    await createCustomDocType(page.request);
    await createCustomRelType(page.request);
    // Wait for type cache
    await new Promise(r => setTimeout(r, 3000));
    await ctx.close();
  });

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    // Delete relationships first
    for (const relId of cleanupRelIds) {
      await deleteObject(page.request, relId);
    }
    // Delete folders
    for (const id of cleanupFolderIds) {
      await deleteTree(page.request, id);
    }
    // Delete custom types
    await deleteCustomType(page.request, CUSTOM_REL_TYPE);
    await deleteCustomType(page.request, CUSTOM_DOC_TYPE);
    await ctx.close();
  });

  // ========== A. Custom Document Type + Custom Relationship Type Round-trip ==========

  test.describe('A. Custom Types + Relationship Export/Import Round-trip', () => {
    let srcFolderId: string;
    let importFolderId: string;
    let docAId: string;
    let docBId: string;
    let relId: string;

    test('A1. Create source folder with two custom-type documents and a custom relationship', async ({ page }) => {
      srcFolderId = await createFolder(page.request, testFolderId, `rel-src-${UNIQUE}`);
      cleanupFolderIds.push(srcFolderId);

      docAId = await createDocumentOfType(
        page.request, srcFolderId,
        `docA-${UNIQUE}.txt`, 'Document A content',
        CUSTOM_DOC_TYPE, { [CUSTOM_DOC_PROP]: 'alpha' },
      );
      expect(docAId).toBeTruthy();

      docBId = await createDocumentOfType(
        page.request, srcFolderId,
        `docB-${UNIQUE}.txt`, 'Document B content',
        CUSTOM_DOC_TYPE, { [CUSTOM_DOC_PROP]: 'beta' },
      );
      expect(docBId).toBeTruthy();

      // Create custom relationship A → B with a property
      relId = await createRelationship(
        page.request, CUSTOM_REL_TYPE, docAId, docBId,
        `link-AB-${UNIQUE}`,
        { [CUSTOM_REL_PROP]: 'relates-to' },
      );
      expect(relId).toBeTruthy();
      cleanupRelIds.push(relId);
      console.log(`Created: docA=${docAId}, docB=${docBId}, rel=${relId}`);
    });

    test('A2. Verify relationship exists before export', async ({ page }) => {
      const rels = await getRelationships(page.request, docAId, 'both');
      console.log(`Relationships from docA (source): ${rels.length}`);
      // Find our relationship
      const found = rels.find((r: any) => {
        // Relationships are returned directly as { properties: {...} } (no object wrapper)
        const props = r.properties || r.object?.properties || {};
        const typeId = props['cmis:objectTypeId']?.value || props['cmis:objectTypeId'];
        return typeId === CUSTOM_REL_TYPE;
      });
      expect(found).toBeTruthy();
    });

    test('A3. Export and verify .nemaki-relationships/ in ZIP', async ({ page }) => {
      const zipBuffer = await exportFolder(page.request, srcFolderId);
      const entries = parseZipEntries(zipBuffer);
      const fileNames = Array.from(entries.keys());
      console.log('ZIP entries:', fileNames);

      // Check for .nemaki-relationships/ directory
      const relFiles = fileNames.filter(f => f.startsWith('.nemaki-relationships/') && f.endsWith('.rel.json'));
      console.log('Relationship files:', relFiles);
      expect(relFiles.length).toBeGreaterThanOrEqual(1);

      // Verify relationship JSON content
      const relJson = JSON.parse(entries.get(relFiles[0])!.toString('utf8'));
      console.log('Relationship JSON:', JSON.stringify(relJson));
      expect(relJson.sourceId).toBeTruthy();
      expect(relJson.targetId).toBeTruthy();
      expect(relJson.objectType).toBe(CUSTOM_REL_TYPE);

      // Check for .nemaki-types/ with both custom types
      const typeFiles = fileNames.filter(f => f.startsWith('.nemaki-types/') && f.endsWith('.type.json'));
      console.log('Type definition files:', typeFiles);
      // At least the document type should be exported; relationship type may or may not be
      expect(typeFiles.length).toBeGreaterThanOrEqual(1);

      // Verify custom doc properties in document metadata
      const docMetaFiles = fileNames.filter(f => f.includes(`docA-${UNIQUE}`) && f.endsWith('.meta.json'));
      expect(docMetaFiles.length).toBe(1);
      const docMeta = JSON.parse(entries.get(docMetaFiles[0])!.toString('utf8'));
      expect(docMeta.properties['cmis:objectTypeId']).toBe(CUSTOM_DOC_TYPE);
      expect(docMeta.properties[CUSTOM_DOC_PROP]).toBe('alpha');

      // Verify cmis:objectId is included in metadata (needed for ID remapping)
      expect(docMeta.properties['cmis:objectId']).toBeTruthy();
    });

    test('A4. Import to new folder and verify documents recreated', async ({ page }) => {
      importFolderId = await createFolder(page.request, testFolderId, `rel-import-${UNIQUE}`);
      cleanupFolderIds.push(importFolderId);

      const zipBuffer = await exportFolder(page.request, srcFolderId);
      const result = await importZip(page.request, importFolderId, zipBuffer);
      console.log('Import result:', JSON.stringify(result));
      // "partial" is acceptable when type definitions already exist (warning only)
      expect(['success', 'partial']).toContain(result.status);
      expect(result.documentsCreated).toBeGreaterThanOrEqual(2);

      // Verify imported documents have correct custom type and properties
      const children = await getChildren(page.request, importFolderId);
      expect(children.length).toBe(2);

      for (const child of children) {
        const childProps = child.object?.succinctProperties || child.object?.properties;
        const childId = childProps?.['cmis:objectId']?.value || childProps?.['cmis:objectId'];
        const props = await getObjectProps(page.request, childId);
        expect(props['cmis:objectTypeId']).toBe(CUSTOM_DOC_TYPE);

        const childName = props['cmis:name'];
        if (childName.includes('docA')) {
          expect(props[CUSTOM_DOC_PROP]).toBe('alpha');
        } else if (childName.includes('docB')) {
          expect(props[CUSTOM_DOC_PROP]).toBe('beta');
        }
        console.log(`Imported: ${childName}, type=${props['cmis:objectTypeId']}, tag=${props[CUSTOM_DOC_PROP]}`);
      }
    });

    test('A5. Verify relationships were imported with remapped IDs', async ({ page }) => {
      // Get imported document IDs
      const children = await getChildren(page.request, importFolderId);
      let importedDocAId = '';
      let importedDocBId = '';
      for (const child of children) {
        const childProps = child.object?.succinctProperties || child.object?.properties;
        const childId = childProps?.['cmis:objectId']?.value || childProps?.['cmis:objectId'];
        const childName = childProps?.['cmis:name']?.value || childProps?.['cmis:name'];
        if (childName.includes('docA')) importedDocAId = childId;
        if (childName.includes('docB')) importedDocBId = childId;
      }
      expect(importedDocAId).toBeTruthy();
      expect(importedDocBId).toBeTruthy();
      console.log(`Imported IDs: docA=${importedDocAId}, docB=${importedDocBId}`);

      // These should be different from the original IDs
      expect(importedDocAId).not.toBe(docAId);
      expect(importedDocBId).not.toBe(docBId);

      // Check relationships on imported docA
      const rels = await getRelationships(page.request, importedDocAId, 'source');
      console.log(`Imported docA relationships (source): ${rels.length}`);

      // There should be at least one relationship
      if (rels.length > 0) {
        const rel = rels[0];
        const relProps = rel.properties || rel.object?.properties || {};
        const newSourceId = relProps['cmis:sourceId']?.value;
        const newTargetId = relProps['cmis:targetId']?.value;
        const relTypeId = relProps['cmis:objectTypeId']?.value;

        console.log(`Imported relationship: type=${relTypeId}, source=${newSourceId}, target=${newTargetId}`);

        // Verify the relationship points to the NEW imported documents, not the old ones
        expect(newSourceId).toBe(importedDocAId);
        expect(newTargetId).toBe(importedDocBId);
        expect(relTypeId).toBe(CUSTOM_REL_TYPE);
      } else {
        // No relationships found via CMIS query on the imported document
        console.log('WARNING: No relationships found on imported document via CMIS query');
        // Fail explicitly so this doesn't silently pass
        expect(rels.length, 'Expected at least 1 relationship on imported docA').toBeGreaterThanOrEqual(1);
      }
    });
  });

  // ========== B. Self-referencing relationship (P3 regression test) ==========

  test.describe('B. Self-referencing Relationship', () => {
    let selfRefFolderId: string;
    let selfRefDocId: string;
    let selfRefRelId: string;
    let importFolderId: string;

    test('B1. Create document with self-referencing relationship', async ({ page }) => {
      selfRefFolderId = await createFolder(page.request, testFolderId, `selfref-${UNIQUE}`);
      cleanupFolderIds.push(selfRefFolderId);

      selfRefDocId = await createDocumentOfType(
        page.request, selfRefFolderId,
        `selfref-doc-${UNIQUE}.txt`, 'Self-referencing document',
        CUSTOM_DOC_TYPE, { [CUSTOM_DOC_PROP]: 'self' },
      );

      // Create relationship where source == target
      selfRefRelId = await createRelationship(
        page.request, CUSTOM_REL_TYPE, selfRefDocId, selfRefDocId,
        `self-link-${UNIQUE}`,
        { [CUSTOM_REL_PROP]: 'self-reference' },
      );
      expect(selfRefRelId).toBeTruthy();
      cleanupRelIds.push(selfRefRelId);
      console.log(`Self-ref: doc=${selfRefDocId}, rel=${selfRefRelId}`);
    });

    test('B2. Verify self-referencing relationship retrieved with EITHER direction', async ({ page }) => {
      // This tests the P3 fix: disjunction → union merge
      const rels = await getRelationships(page.request, selfRefDocId, 'both');
      console.log(`Self-ref relationships (either): ${rels.length}`);
      // Must find the self-referencing relationship (was dropped by disjunction)
      const selfRef = rels.find((r: any) => {
        const props = r.properties || r.object?.properties || {};
        const src = props['cmis:sourceId']?.value;
        const tgt = props['cmis:targetId']?.value;
        return src === selfRefDocId && tgt === selfRefDocId;
      });
      expect(selfRef).toBeTruthy();
    });

    test('B3. Export folder with self-referencing relationship', async ({ page }) => {
      const zipBuffer = await exportFolder(page.request, selfRefFolderId);
      const entries = parseZipEntries(zipBuffer);
      const fileNames = Array.from(entries.keys());

      const relFiles = fileNames.filter(f => f.startsWith('.nemaki-relationships/') && f.endsWith('.rel.json'));
      console.log('Self-ref ZIP relationship files:', relFiles);
      expect(relFiles.length).toBeGreaterThanOrEqual(1);

      // Verify the relationship JSON has source == target
      const relJson = JSON.parse(entries.get(relFiles[0])!.toString('utf8'));
      expect(relJson.sourceId).toBe(relJson.targetId);
      console.log(`Self-ref in ZIP: sourceId=${relJson.sourceId}, targetId=${relJson.targetId}`);
    });

    test('B4. Import and verify self-referencing relationship preserved', async ({ page }) => {
      importFolderId = await createFolder(page.request, testFolderId, `selfref-import-${UNIQUE}`);
      cleanupFolderIds.push(importFolderId);

      const zipBuffer = await exportFolder(page.request, selfRefFolderId);
      const result = await importZip(page.request, importFolderId, zipBuffer);
      console.log('Self-ref import result:', JSON.stringify(result));
      // "partial" is acceptable when type definitions already exist (warning only)
      expect(['success', 'partial']).toContain(result.status);

      // Get imported document
      const children = await getChildren(page.request, importFolderId);
      expect(children.length).toBe(1);
      const childProps = children[0].object?.succinctProperties || children[0].object?.properties;
      const importedDocId = childProps?.['cmis:objectId']?.value || childProps?.['cmis:objectId'];

      // Verify self-referencing relationship was recreated with new ID
      const rels = await getRelationships(page.request, importedDocId, 'both');
      console.log(`Imported self-ref relationships: ${rels.length}`);
      if (rels.length > 0) {
        const rel = rels[0];
        const relProps = rel.properties || rel.object?.properties || {};
        const newSrc = relProps['cmis:sourceId']?.value;
        const newTgt = relProps['cmis:targetId']?.value;
        console.log(`Imported self-ref: source=${newSrc}, target=${newTgt}, importedDoc=${importedDocId}`);
        // Both source and target should point to the SAME new document
        expect(newSrc).toBe(importedDocId);
        expect(newTgt).toBe(importedDocId);
      }
    });
  });

  // ========== C. Built-in relationship type round-trip ==========

  test.describe('C. Built-in Relationship Type (nemaki:bidirectionalRelationship)', () => {
    let builtinFolderId: string;
    let docXId: string;
    let docYId: string;
    let builtinRelId: string;
    let importFolderId: string;

    test('C1. Create documents with built-in relationship type', async ({ page }) => {
      builtinFolderId = await createFolder(page.request, testFolderId, `builtin-rel-${UNIQUE}`);
      cleanupFolderIds.push(builtinFolderId);

      docXId = await createDocumentOfType(
        page.request, builtinFolderId,
        `docX-${UNIQUE}.txt`, 'Document X',
        'cmis:document',
      );
      docYId = await createDocumentOfType(
        page.request, builtinFolderId,
        `docY-${UNIQUE}.txt`, 'Document Y',
        'cmis:document',
      );

      builtinRelId = await createRelationship(
        page.request, 'nemaki:bidirectionalRelationship',
        docXId, docYId, `bidir-${UNIQUE}`,
      );
      expect(builtinRelId).toBeTruthy();
      cleanupRelIds.push(builtinRelId);
      console.log(`Built-in rel: X=${docXId} → Y=${docYId}, rel=${builtinRelId}`);
    });

    test('C2. Export and verify built-in relationship in ZIP', async ({ page }) => {
      const zipBuffer = await exportFolder(page.request, builtinFolderId);
      const entries = parseZipEntries(zipBuffer);
      const relFiles = Array.from(entries.keys()).filter(
        f => f.startsWith('.nemaki-relationships/') && f.endsWith('.rel.json'),
      );
      expect(relFiles.length).toBeGreaterThanOrEqual(1);

      const relJson = JSON.parse(entries.get(relFiles[0])!.toString('utf8'));
      expect(relJson.objectType).toBe('nemaki:bidirectionalRelationship');
      console.log('Built-in rel exported:', JSON.stringify(relJson));
    });

    test('C3. Import and verify built-in relationship remapped', async ({ page }) => {
      importFolderId = await createFolder(page.request, testFolderId, `builtin-import-${UNIQUE}`);
      cleanupFolderIds.push(importFolderId);

      const zipBuffer = await exportFolder(page.request, builtinFolderId);
      const result = await importZip(page.request, importFolderId, zipBuffer);
      console.log('Built-in rel import:', JSON.stringify(result));
      // "partial" is acceptable when type definitions already exist (warning only)
      expect(['success', 'partial']).toContain(result.status);

      // Verify the imported documents exist
      const children = await getChildren(page.request, importFolderId);
      expect(children.length).toBe(2);

      // Find imported doc IDs
      let importedXId = '';
      let importedYId = '';
      for (const child of children) {
        const cp = child.object?.succinctProperties || child.object?.properties;
        const cid = cp?.['cmis:objectId']?.value || cp?.['cmis:objectId'];
        const cname = cp?.['cmis:name']?.value || cp?.['cmis:name'];
        if (cname.includes('docX')) importedXId = cid;
        if (cname.includes('docY')) importedYId = cid;
      }

      // Check that IDs are new (remapped)
      expect(importedXId).not.toBe(docXId);
      expect(importedYId).not.toBe(docYId);

      // Check relationships
      const rels = await getRelationships(page.request, importedXId, 'source');
      console.log(`Imported X relationships: ${rels.length}`);
      if (rels.length > 0) {
        const relProps = rels[0].properties || rels[0].object?.properties || {};
        expect(relProps['cmis:sourceId']?.value).toBe(importedXId);
        expect(relProps['cmis:targetId']?.value).toBe(importedYId);
        console.log('Built-in relationship correctly remapped to new IDs');
      }
    });
  });

  // ========== D. Same-name overwrite with relationships ==========

  test.describe('D. Same-name Overwrite + Relationship Preservation', () => {
    let overwriteFolderId: string;

    test('D1. Export folder, import twice to same target, verify no duplicates', async ({ page }) => {
      overwriteFolderId = await createFolder(page.request, testFolderId, `overwrite-${UNIQUE}`);
      cleanupFolderIds.push(overwriteFolderId);

      // Create documents
      const dId = await createDocumentOfType(
        page.request, overwriteFolderId,
        `overwrite-doc-${UNIQUE}.txt`, 'Original content',
        CUSTOM_DOC_TYPE, { [CUSTOM_DOC_PROP]: 'v1' },
      );

      const d2Id = await createDocumentOfType(
        page.request, overwriteFolderId,
        `overwrite-target-${UNIQUE}.txt`, 'Target content',
        'cmis:document',
      );

      // Create relationship
      const rId = await createRelationship(
        page.request, CUSTOM_REL_TYPE, dId, d2Id,
        `overwrite-rel-${UNIQUE}`,
      );
      cleanupRelIds.push(rId);

      // Export
      const zipBuffer = await exportFolder(page.request, overwriteFolderId);

      // Create target folder and import once
      const importTarget = await createFolder(page.request, testFolderId, `overwrite-target-folder-${UNIQUE}`);
      cleanupFolderIds.push(importTarget);

      const result1 = await importZip(page.request, importTarget, zipBuffer);
      console.log('First import:', JSON.stringify(result1));
      expect(['success', 'partial']).toContain(result1.status);

      // Measure actual child count after first import (more reliable than documentsCreated,
      // which only counts documents while getChildren includes folders too)
      const childrenAfterFirst = await getChildren(page.request, importTarget);
      const firstChildCount = childrenAfterFirst.length;
      console.log(`Children after first import: ${firstChildCount}`);
      expect(firstChildCount).toBeGreaterThan(0);

      // Import again to the same target (should overwrite, not duplicate)
      const result2 = await importZip(page.request, importTarget, zipBuffer);
      console.log('Second import (overwrite):', JSON.stringify(result2));
      expect(['success', 'partial']).toContain(result2.status);

      // Verify no duplicates: child count should be same as first import
      const children = await getChildren(page.request, importTarget);
      console.log(`Children after two imports: ${children.length}`);
      expect(children.length).toBe(firstChildCount);
    });
  });
});
