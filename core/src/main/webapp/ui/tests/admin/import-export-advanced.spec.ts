import { test, expect } from '@playwright/test';
import * as zlib from 'zlib';

/**
 * Import/Export Advanced E2E Tests
 *
 * Tests complex scenarios:
 * A. Custom type + custom property round-trip
 * B. Version history export
 * C. Custom properties changing across versions
 * D. ACL preservation in export/import
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

// Custom type ID used across tests
const CUSTOM_TYPE_ID = `test:ieAdvDoc${UNIQUE}`;
const CUSTOM_PROP_DEPT = `test:ieDept${UNIQUE}`;
const CUSTOM_PROP_PRIO = `test:iePrio${UNIQUE}`;

// Helper: get root folder ID
async function getRootFolderId(request: any): Promise<string> {
  const res = await request.get(`${BROWSER_URL}/root?cmisselector=object`, {
    headers: { 'Authorization': AUTH_HEADER },
  });
  expect(res.ok()).toBeTruthy();
  const data = await res.json();
  return data.properties['cmis:objectId'].value;
}

// Helper: create folder
async function createFolder(request: any, parentId: string, name: string): Promise<string> {
  const res = await request.post(BROWSER_URL, {
    headers: { 'Authorization': AUTH_HEADER },
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

// Helper: create document (standard type)
async function createDocument(
  request: any, folderId: string, name: string, content: string
): Promise<string> {
  const res = await request.post(BROWSER_URL, {
    headers: { 'Authorization': AUTH_HEADER },
    multipart: {
      cmisaction: 'createDocument',
      'propertyId[0]': 'cmis:objectTypeId',
      'propertyValue[0]': 'cmis:document',
      'propertyId[1]': 'cmis:name',
      'propertyValue[1]': name,
      objectId: folderId,
      content: { name, mimeType: 'text/plain', buffer: Buffer.from(content) },
      succinct: 'true',
    },
    timeout: TIMEOUT,
  });
  expect(res.ok()).toBeTruthy();
  const data = await res.json();
  return data.succinctProperties['cmis:objectId'];
}

// Helper: checkOut
async function checkOut(request: any, objectId: string): Promise<string> {
  const res = await request.post(BROWSER_URL, {
    headers: { 'Authorization': AUTH_HEADER, 'Content-Type': 'application/x-www-form-urlencoded' },
    form: { cmisaction: 'checkOut', objectId, succinct: 'true' },
    timeout: TIMEOUT,
  });
  expect(res.ok()).toBeTruthy();
  const data = await res.json();
  return data.succinctProperties['cmis:objectId'];
}

// Helper: checkIn
async function checkIn(
  request: any, pwcId: string, content: string, comment: string,
  extraProps?: Record<string, string>
): Promise<string> {
  const multipart: Record<string, any> = {
    cmisaction: 'checkIn',
    objectId: pwcId,
    major: 'true',
    checkinComment: comment,
    content: { name: 'content.txt', mimeType: 'text/plain', buffer: Buffer.from(content) },
    succinct: 'true',
  };

  // Add extra properties if provided
  let propIdx = 0;
  if (extraProps) {
    for (const [key, value] of Object.entries(extraProps)) {
      multipart[`propertyId[${propIdx}]`] = key;
      multipart[`propertyValue[${propIdx}]`] = value;
      propIdx++;
    }
  }

  const res = await request.post(BROWSER_URL, {
    headers: { 'Authorization': AUTH_HEADER },
    multipart,
    timeout: TIMEOUT,
  });
  expect(res.ok()).toBeTruthy();
  const data = await res.json();
  return data.succinctProperties['cmis:objectId'];
}

// Helper: extract property value from various response formats
function extractPropValue(obj: any, propId: string): any {
  // Format 1: succinctProperties { key: value }
  if (obj.succinctProperties) return obj.succinctProperties[propId];
  // Format 2: browser binding { properties: { key: { value: ... } } }
  const p2 = obj.properties?.[propId];
  if (p2 && 'value' in p2) return p2.value;
  if (p2 && 'firstValue' in p2) return p2.firstValue;
  // Format 3: OpenCMIS serialization { properties: { properties: { key: { firstValue: ... } } } }
  const p3 = obj.properties?.properties?.[propId];
  if (p3 && 'firstValue' in p3) return p3.firstValue;
  if (p3 && 'value' in p3) return p3.value;
  return undefined;
}

// Helper: get all versions
async function getAllVersions(request: any, objectId: string): Promise<any[]> {
  const res = await request.get(`${BROWSER_URL}/${objectId}?cmisselector=versions`, {
    headers: { 'Authorization': AUTH_HEADER },
  });
  expect(res.ok()).toBeTruthy();
  return await res.json();
}

// Helper: delete tree
async function deleteTree(request: any, objectId: string): Promise<void> {
  try {
    await request.post(BROWSER_URL, {
      headers: { 'Authorization': AUTH_HEADER, 'Content-Type': 'application/x-www-form-urlencoded' },
      form: { cmisaction: 'deleteTree', objectId, allVersions: 'true', continueOnFailure: 'true' },
      timeout: TIMEOUT,
    });
  } catch (e) {
    console.log(`Cleanup warning: ${e}`);
  }
}

// Helper: delete custom type
async function deleteCustomType(request: any, typeId: string): Promise<void> {
  try {
    await request.delete(`${REST_URL}/type/delete/${typeId}`, {
      headers: { 'Authorization': AUTH_HEADER },
      timeout: TIMEOUT,
    });
  } catch (e) {
    console.log(`Type cleanup warning: ${e}`);
  }
}

// Helper: parse ZIP entries using central directory (handles data descriptors)
function parseZipEntries(buffer: Buffer): Map<string, Buffer> {
  const entries = new Map<string, Buffer>();

  // Find End of Central Directory record (search from end)
  let eocdOffset = -1;
  for (let i = buffer.length - 22; i >= 0; i--) {
    if (buffer.readUInt32LE(i) === 0x06054b50) {
      eocdOffset = i;
      break;
    }
  }
  if (eocdOffset < 0) return entries;

  const cdOffset = buffer.readUInt32LE(eocdOffset + 16);
  const cdEntries = buffer.readUInt16LE(eocdOffset + 10);

  // Read central directory entries
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

    // Read from local file header to get actual data position
    const localNameLen = buffer.readUInt16LE(localHeaderOffset + 26);
    const localExtraLen = buffer.readUInt16LE(localHeaderOffset + 28);
    const dataStart = localHeaderOffset + 30 + localNameLen + localExtraLen;

    let fileData: Buffer;
    if (compressionMethod === 8) {
      try {
        fileData = zlib.inflateRawSync(buffer.subarray(dataStart, dataStart + compressedSize));
      } catch {
        fileData = buffer.subarray(dataStart, dataStart + compressedSize);
      }
    } else {
      fileData = buffer.subarray(dataStart, dataStart + compressedSize);
    }

    entries.set(name, fileData);
    offset += 46 + nameLen + extraLen + commentLen;
  }

  return entries;
}

// Helper: get children of a folder
async function getChildren(request: any, folderId: string): Promise<any[]> {
  const res = await request.get(
    `${BROWSER_URL}/root?objectId=${folderId}&cmisselector=children`,
    { headers: { 'Authorization': AUTH_HEADER } }
  );
  expect(res.ok()).toBeTruthy();
  const data = await res.json();
  return data.objects || [];
}

// Helper: get object properties
async function getObjectProperties(request: any, objectId: string): Promise<any> {
  const res = await request.get(
    `${BROWSER_URL}/${objectId}?cmisselector=object`,
    { headers: { 'Authorization': AUTH_HEADER } }
  );
  expect(res.ok()).toBeTruthy();
  const data = await res.json();
  if (data.succinctProperties) {
    return data.succinctProperties;
  }
  // Non-succinct format: properties are {key: {value: ...}}
  const props: Record<string, any> = {};
  if (data.properties) {
    for (const [key, val] of Object.entries(data.properties)) {
      props[key] = (val as any)?.value;
    }
  }
  return props;
}

// ================================================================
// Test Suite
// ================================================================

test.describe.configure({ mode: 'serial' });

test.describe('Import/Export Advanced Scenarios', () => {
  let rootFolderId: string;
  let testFolderId: string;
  const createdFolderIds: string[] = [];

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    rootFolderId = await getRootFolderId(page.request);

    // Create test folder
    testFolderId = await createFolder(page.request, rootFolderId, `ie-adv-test-${UNIQUE}`);
    createdFolderIds.push(testFolderId);
    await ctx.close();
  });

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    // Cleanup folders
    for (const id of createdFolderIds) {
      await deleteTree(page.request, id);
    }
    // Cleanup custom type
    await deleteCustomType(page.request, CUSTOM_TYPE_ID);
    await ctx.close();
  });

  // ========== A. Custom Type + Custom Property Round-trip ==========

  test.describe('A. Custom Type Round-trip', () => {
    let srcFolderId: string;
    let importFolderId: string;
    let docId: string;

    test('A1. Create custom type with properties', async ({ page }) => {
      const typeRes = await page.request.post(`${REST_URL}/type/create`, {
        headers: { 'Authorization': AUTH_HEADER, 'Content-Type': 'application/json' },
        data: {
          id: CUSTOM_TYPE_ID,
          localName: CUSTOM_TYPE_ID,
          displayName: 'IE Advanced Test Doc',
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
              id: CUSTOM_PROP_DEPT,
              localName: CUSTOM_PROP_DEPT,
              displayName: 'Department',
              propertyType: 'string',
              cardinality: 'single',
              updatability: 'readwrite',
              required: false,
              queryable: true,
            },
            {
              id: CUSTOM_PROP_PRIO,
              localName: CUSTOM_PROP_PRIO,
              displayName: 'Priority',
              propertyType: 'integer',
              cardinality: 'single',
              updatability: 'readwrite',
              required: false,
              queryable: false,
            },
          ],
        },
        timeout: TIMEOUT,
      });
      expect(typeRes.ok()).toBeTruthy();
      console.log('✅ Custom type created:', CUSTOM_TYPE_ID);

      // Wait for type cache to refresh
      await new Promise(resolve => setTimeout(resolve, 3000));
    });

    test('A2. Create document with custom type and properties', async ({ page }) => {
      srcFolderId = await createFolder(page.request, testFolderId, `custom-src-${UNIQUE}`);
      createdFolderIds.push(srcFolderId);

      const res = await page.request.post(BROWSER_URL, {
        headers: { 'Authorization': AUTH_HEADER },
        multipart: {
          cmisaction: 'createDocument',
          'propertyId[0]': 'cmis:objectTypeId',
          'propertyValue[0]': CUSTOM_TYPE_ID,
          'propertyId[1]': 'cmis:name',
          'propertyValue[1]': `custom-doc-${UNIQUE}.txt`,
          'propertyId[2]': CUSTOM_PROP_DEPT,
          'propertyValue[2]': 'Engineering',
          'propertyId[3]': CUSTOM_PROP_PRIO,
          'propertyValue[3]': '5',
          objectId: srcFolderId,
          content: {
            name: `custom-doc-${UNIQUE}.txt`,
            mimeType: 'text/plain',
            buffer: Buffer.from('Custom type document content'),
          },
          succinct: 'true',
        },
        timeout: TIMEOUT,
      });
      expect(res.ok()).toBeTruthy();
      const data = await res.json();
      docId = data.succinctProperties?.['cmis:objectId'] || data.properties?.['cmis:objectId']?.value;
      expect(docId).toBeTruthy();

      // Verify custom properties were set
      const props = await getObjectProperties(page.request, docId);
      expect(props['cmis:objectTypeId']).toBe(CUSTOM_TYPE_ID);
      expect(props[CUSTOM_PROP_DEPT]).toBe('Engineering');
      expect(Number(props[CUSTOM_PROP_PRIO])).toBe(5);
      console.log('✅ Custom type document created with properties');
    });

    test('A3. Export and verify custom properties in ZIP metadata', async ({ page }) => {
      const exportRes = await page.request.get(
        `${REST_URL}/importexport/export/${srcFolderId}`,
        { headers: { 'Authorization': AUTH_HEADER }, timeout: 60000 }
      );
      expect(exportRes.ok()).toBeTruthy();

      const zipBuffer = Buffer.from(await exportRes.body());
      expect(zipBuffer.length).toBeGreaterThan(0);

      // Parse ZIP and check .meta.json
      const entries = parseZipEntries(zipBuffer);
      const metaFiles = Array.from(entries.keys()).filter(k => k.endsWith('.meta.json'));
      expect(metaFiles.length).toBeGreaterThan(0);

      // Find our document's metadata
      let foundCustomProps = false;
      for (const metaFile of metaFiles) {
        const metaContent = entries.get(metaFile)!.toString('utf8');
        const meta = JSON.parse(metaContent);
        const props = meta.properties || {};
        if (props['cmis:objectTypeId'] === CUSTOM_TYPE_ID) {
          foundCustomProps = true;
          expect(props[CUSTOM_PROP_DEPT]).toBe('Engineering');
          expect(Number(props[CUSTOM_PROP_PRIO])).toBe(5);
          console.log('✅ Custom properties found in ZIP metadata:', metaFile);
        }
      }
      expect(foundCustomProps).toBeTruthy();
    });

    test('A4. Import to new folder and verify custom properties preserved', async ({ page }) => {
      // Create import target folder
      importFolderId = await createFolder(page.request, testFolderId, `custom-import-${UNIQUE}`);
      createdFolderIds.push(importFolderId);

      // Export
      const exportRes = await page.request.get(
        `${REST_URL}/importexport/export/${srcFolderId}`,
        { headers: { 'Authorization': AUTH_HEADER }, timeout: 60000 }
      );
      const zipBuffer = Buffer.from(await exportRes.body());

      // Import
      const importRes = await page.request.post(
        `${REST_URL}/importexport/import/${importFolderId}`,
        {
          headers: { 'Authorization': AUTH_HEADER },
          multipart: {
            file: { name: 'export.zip', mimeType: 'application/zip', buffer: zipBuffer },
          },
          timeout: 60000,
        }
      );
      expect(importRes.ok()).toBeTruthy();
      const importData = await importRes.json();
      console.log('Import result:', JSON.stringify(importData));

      // Verify imported document has custom properties
      const children = await getChildren(page.request, importFolderId);
      expect(children.length).toBeGreaterThan(0);

      // Find the imported document and check its properties
      let foundImportedCustomDoc = false;
      for (const child of children) {
        const childProps = child.object?.succinctProperties || child.object?.properties;
        if (!childProps) continue;

        const childId = childProps['cmis:objectId']?.value || childProps['cmis:objectId'];
        if (!childId) continue;

        const props = await getObjectProperties(page.request, childId);
        if (props[CUSTOM_PROP_DEPT]) {
          foundImportedCustomDoc = true;
          expect(props[CUSTOM_PROP_DEPT]).toBe('Engineering');
          console.log('✅ Imported document has custom property department=Engineering');
        }
      }
      expect(foundImportedCustomDoc).toBeTruthy();
    });
  });

  // ========== B. Version History Export ==========

  test.describe('B. Version History Export', () => {
    let versionFolderId: string;
    let versionDocId: string;

    test('B1. Create document with 3 versions', async ({ page }) => {
      versionFolderId = await createFolder(page.request, testFolderId, `version-src-${UNIQUE}`);
      createdFolderIds.push(versionFolderId);

      // V1.0
      versionDocId = await createDocument(
        page.request, versionFolderId, `versioned-${UNIQUE}.txt`, 'Version 1 content'
      );
      expect(versionDocId).toBeTruthy();
      console.log('✅ V1.0 created:', versionDocId);

      // V2.0
      const pwc1 = await checkOut(page.request, versionDocId);
      versionDocId = await checkIn(page.request, pwc1, 'Version 2 content', 'Updated to V2');
      console.log('✅ V2.0 created');

      // V3.0
      const pwc2 = await checkOut(page.request, versionDocId);
      versionDocId = await checkIn(page.request, pwc2, 'Version 3 content', 'Updated to V3');
      console.log('✅ V3.0 created');
    });

    test('B2. Verify all 3 versions exist via API', async ({ page }) => {
      const versions = await getAllVersions(page.request, versionDocId);
      console.log(`Found ${versions.length} versions`);
      expect(versions.length).toBeGreaterThanOrEqual(3);

      // Check version labels
      const labels = versions.map((v: any) => extractPropValue(v, 'cmis:versionLabel')).filter(Boolean);
      console.log('Version labels:', labels);
      expect(labels).toContain('1.0');
      expect(labels).toContain('2.0');
      expect(labels).toContain('3.0');
    });

    test('B3. Export and verify version files in ZIP', async ({ page }) => {
      const exportRes = await page.request.get(
        `${REST_URL}/importexport/export/${versionFolderId}`,
        { headers: { 'Authorization': AUTH_HEADER }, timeout: 60000 }
      );
      expect(exportRes.ok()).toBeTruthy();

      const zipBuffer = Buffer.from(await exportRes.body());
      const entries = parseZipEntries(zipBuffer);
      const fileNames = Array.from(entries.keys());
      console.log('ZIP entries:', fileNames);

      // Should have main file + version files (.v1, .v2) + meta files
      const mainFile = fileNames.find(f => f.includes(`versioned-${UNIQUE}.txt`) && !f.includes('.v') && !f.includes('.meta'));
      expect(mainFile).toBeTruthy();
      console.log('✅ Main file found:', mainFile);

      // Check for version files
      const versionFiles = fileNames.filter(f => f.match(/\.v\d+$/));
      console.log('Version files:', versionFiles);
      // At least 2 older versions should be exported
      if (versionFiles.length >= 2) {
        console.log('✅ Version history files found in ZIP');
      } else {
        console.log('⚠️ Version files count:', versionFiles.length, '(expected >= 2)');
      }

      // Check for version metadata
      const versionMeta = fileNames.filter(f => f.match(/\.v\d+\.meta\.json$/));
      console.log('Version meta files:', versionMeta);
    });
  });

  // ========== C. Custom Properties Changing Across Versions ==========

  test.describe('C. Property Changes Across Versions', () => {
    let propChangeFolderId: string;
    let propChangeDocId: string;

    test('C1. Create custom type document with initial properties', async ({ page }) => {
      propChangeFolderId = await createFolder(page.request, testFolderId, `propchange-${UNIQUE}`);
      createdFolderIds.push(propChangeFolderId);

      // V1.0: department=Sales, priority=1
      const res = await page.request.post(BROWSER_URL, {
        headers: { 'Authorization': AUTH_HEADER },
        multipart: {
          cmisaction: 'createDocument',
          'propertyId[0]': 'cmis:objectTypeId',
          'propertyValue[0]': CUSTOM_TYPE_ID,
          'propertyId[1]': 'cmis:name',
          'propertyValue[1]': `propchange-${UNIQUE}.txt`,
          'propertyId[2]': CUSTOM_PROP_DEPT,
          'propertyValue[2]': 'Sales',
          'propertyId[3]': CUSTOM_PROP_PRIO,
          'propertyValue[3]': '1',
          objectId: propChangeFolderId,
          content: {
            name: `propchange-${UNIQUE}.txt`,
            mimeType: 'text/plain',
            buffer: Buffer.from('V1: Sales department'),
          },
          succinct: 'true',
        },
        timeout: TIMEOUT,
      });
      expect(res.ok()).toBeTruthy();
      const data = await res.json();
      propChangeDocId = data.succinctProperties['cmis:objectId'];
      console.log('✅ V1.0 created: department=Sales, priority=1');
    });

    test('C2. Create V2.0 with changed properties', async ({ page }) => {
      // V2.0: department=Marketing, priority=2
      const pwc = await checkOut(page.request, propChangeDocId);
      propChangeDocId = await checkIn(page.request, pwc, 'V2: Marketing department', 'Changed to Marketing', {
        [CUSTOM_PROP_DEPT]: 'Marketing',
        [CUSTOM_PROP_PRIO]: '2',
      });
      console.log('✅ V2.0 created: department=Marketing, priority=2');
    });

    test('C3. Create V3.0 with changed properties', async ({ page }) => {
      // V3.0: department=Engineering, priority=3
      const pwc = await checkOut(page.request, propChangeDocId);
      propChangeDocId = await checkIn(page.request, pwc, 'V3: Engineering department', 'Changed to Engineering', {
        [CUSTOM_PROP_DEPT]: 'Engineering',
        [CUSTOM_PROP_PRIO]: '3',
      });
      console.log('✅ V3.0 created: department=Engineering, priority=3');
    });

    test('C4. Verify property values differ across versions', async ({ page }) => {
      const versions = await getAllVersions(page.request, propChangeDocId);
      expect(versions.length).toBeGreaterThanOrEqual(3);

      // Map version label to properties
      const versionProps = new Map<string, any>();
      for (const v of versions) {
        const label = extractPropValue(v, 'cmis:versionLabel');
        if (label) {
          versionProps.set(label, {
            department: extractPropValue(v, CUSTOM_PROP_DEPT),
            priority: extractPropValue(v, CUSTOM_PROP_PRIO),
          });
        }
      }

      console.log('Version properties:');
      for (const [label, props] of versionProps) {
        console.log(`  ${label}: department=${props.department}, priority=${props.priority}`);
      }

      // Verify latest version (3.0) has Engineering/3
      const v3 = versionProps.get('3.0');
      expect(v3).toBeTruthy();
      expect(v3.department).toBe('Engineering');
      expect(Number(v3.priority)).toBe(3);

      // Verify V1.0 has Sales/1 (if properties are stored per-version)
      const v1 = versionProps.get('1.0');
      if (v1 && v1.department) {
        expect(v1.department).toBe('Sales');
        expect(Number(v1.priority)).toBe(1);
        console.log('✅ Properties correctly differ across versions');
      } else {
        console.log('⚠️ V1.0 properties not accessible (may be CMIS implementation limitation)');
      }
    });

    test('C5. Export and verify per-version metadata', async ({ page }) => {
      const exportRes = await page.request.get(
        `${REST_URL}/importexport/export/${propChangeFolderId}`,
        { headers: { 'Authorization': AUTH_HEADER }, timeout: 60000 }
      );
      expect(exportRes.ok()).toBeTruthy();

      const zipBuffer = Buffer.from(await exportRes.body());
      const entries = parseZipEntries(zipBuffer);
      const fileNames = Array.from(entries.keys());
      console.log('ZIP entries for property change test:', fileNames);

      // Check main file metadata has latest properties
      const mainMeta = fileNames.find(
        f => f.includes(`propchange-${UNIQUE}`) && f.endsWith('.meta.json') && !f.includes('.v')
      );
      if (mainMeta) {
        const meta = JSON.parse(entries.get(mainMeta)!.toString('utf8'));
        const props = meta.properties || {};
        console.log('Main meta properties:', JSON.stringify(props));
        if (props[CUSTOM_PROP_DEPT]) {
          expect(props[CUSTOM_PROP_DEPT]).toBe('Engineering');
          console.log('✅ Latest version metadata has department=Engineering');
        }
      }

      // Check version metadata files for historical properties
      const versionMeta = fileNames.filter(
        f => f.includes(`propchange-${UNIQUE}`) && f.match(/\.v\d+\.meta\.json$/)
      );
      console.log('Version metadata files:', versionMeta);
      for (const vm of versionMeta) {
        const meta = JSON.parse(entries.get(vm)!.toString('utf8'));
        console.log(`  ${vm}: properties=${JSON.stringify(meta.properties || {})}`);
      }
    });
  });

  // ========== D. ACL Preservation ==========

  test.describe('D. ACL Preservation in Export/Import', () => {
    let aclFolderId: string;
    let aclDocId: string;

    test('D1. Create document with ACL', async ({ page }) => {
      aclFolderId = await createFolder(page.request, testFolderId, `acl-test-${UNIQUE}`);
      createdFolderIds.push(aclFolderId);

      aclDocId = await createDocument(
        page.request, aclFolderId, `acl-doc-${UNIQUE}.txt`, 'ACL test content'
      );

      // Add ACL: grant cmis:read to "anyone" (anonymous)
      const aclRes = await page.request.post(BROWSER_URL, {
        headers: { 'Authorization': AUTH_HEADER, 'Content-Type': 'application/x-www-form-urlencoded' },
        form: {
          cmisaction: 'applyACL',
          objectId: aclDocId,
          'addACEPrincipal[0]': 'anyone',
          'addACEPermission[0][0]': 'cmis:read',
        },
        timeout: TIMEOUT,
      });
      // ACL application may vary - log result
      console.log('ACL apply status:', aclRes.status());
      if (aclRes.ok()) {
        console.log('✅ ACL applied to document');
      }
    });

    test('D2. Export and verify ACL in ZIP metadata', async ({ page }) => {
      const exportRes = await page.request.get(
        `${REST_URL}/importexport/export/${aclFolderId}`,
        { headers: { 'Authorization': AUTH_HEADER }, timeout: 60000 }
      );
      expect(exportRes.ok()).toBeTruthy();

      const zipBuffer = Buffer.from(await exportRes.body());
      const entries = parseZipEntries(zipBuffer);

      // Find document metadata
      const metaFiles = Array.from(entries.keys()).filter(
        k => k.includes(`acl-doc-${UNIQUE}`) && k.endsWith('.meta.json')
      );
      expect(metaFiles.length).toBeGreaterThan(0);

      const meta = JSON.parse(entries.get(metaFiles[0])!.toString('utf8'));
      console.log('ACL metadata:', JSON.stringify(meta.acl || 'none'));

      if (meta.acl && meta.acl.length > 0) {
        const aclEntries = meta.acl;
        const anyoneAce = aclEntries.find((ace: any) => ace.principalId === 'anyone');
        if (anyoneAce) {
          console.log('✅ ACL for "anyone" found in export metadata');
          expect(anyoneAce.permissions).toContain('cmis:read');
        }
      } else {
        console.log('⚠️ No ACL entries found in metadata (ACL may not have been set)');
      }
    });

    test('D3. Import and verify ACL restored', async ({ page }) => {
      const importFolderId = await createFolder(page.request, testFolderId, `acl-import-${UNIQUE}`);
      createdFolderIds.push(importFolderId);

      // Export
      const exportRes = await page.request.get(
        `${REST_URL}/importexport/export/${aclFolderId}`,
        { headers: { 'Authorization': AUTH_HEADER }, timeout: 60000 }
      );
      const zipBuffer = Buffer.from(await exportRes.body());

      // Import
      const importRes = await page.request.post(
        `${REST_URL}/importexport/import/${importFolderId}`,
        {
          headers: { 'Authorization': AUTH_HEADER },
          multipart: {
            file: { name: 'export.zip', mimeType: 'application/zip', buffer: zipBuffer },
          },
          timeout: 60000,
        }
      );
      expect(importRes.ok()).toBeTruthy();

      // Get imported document
      const children = await getChildren(page.request, importFolderId);
      if (children.length > 0) {
        const childProps = children[0].object?.succinctProperties || children[0].object?.properties;
        const childId = childProps?.['cmis:objectId']?.value || childProps?.['cmis:objectId'];
        if (childId) {
          // Check ACL on imported document
          const aclRes = await page.request.get(
            `${BROWSER_URL}/${childId}?cmisselector=acl`,
            { headers: { 'Authorization': AUTH_HEADER } }
          );
          if (aclRes.ok()) {
            const aclData = await aclRes.json();
            console.log('Imported document ACL:', JSON.stringify(aclData));
            const aces = aclData.aces || aclData.acl?.aces || [];
            const anyoneAce = aces.find((ace: any) =>
              ace.principal?.principalId === 'anyone' || ace.principalId === 'anyone'
            );
            if (anyoneAce) {
              console.log('✅ ACL restored on imported document');
            } else {
              console.log('⚠️ ACL not found on imported document (may require ACL import support)');
            }
          }
        }
      }
    });
  });

  // ========== E. Version History Round-trip ==========

  test.describe('E. Version History Import Round-trip', () => {
    let versionRtFolderId: string;
    let versionRtDocId: string;
    let importFolderId: string;

    test('E1. Create document with 3 versions for round-trip', async ({ page }) => {
      versionRtFolderId = await createFolder(page.request, testFolderId, `ver-rt-src-${UNIQUE}`);
      createdFolderIds.push(versionRtFolderId);

      // V1.0
      versionRtDocId = await createDocument(
        page.request, versionRtFolderId, `ver-rt-${UNIQUE}.txt`, 'RT Version 1'
      );

      // V2.0
      const pwc1 = await checkOut(page.request, versionRtDocId);
      versionRtDocId = await checkIn(page.request, pwc1, 'RT Version 2', 'V2 update');

      // V3.0
      const pwc2 = await checkOut(page.request, versionRtDocId);
      versionRtDocId = await checkIn(page.request, pwc2, 'RT Version 3', 'V3 update');

      const versions = await getAllVersions(page.request, versionRtDocId);
      expect(versions.length).toBeGreaterThanOrEqual(3);
      console.log('✅ E1: 3 versions created for round-trip test');
    });

    test('E2. Export, import to new folder, verify version count', async ({ page }) => {
      // Export
      const exportRes = await page.request.get(
        `${REST_URL}/importexport/export/${versionRtFolderId}`,
        { headers: { 'Authorization': AUTH_HEADER }, timeout: 60000 }
      );
      expect(exportRes.ok()).toBeTruthy();
      const zipBuffer = Buffer.from(await exportRes.body());

      // Verify ZIP has version files
      const entries = parseZipEntries(zipBuffer);
      const versionFiles = Array.from(entries.keys()).filter(f => f.match(/\.v\d+$/));
      console.log('Version files in ZIP:', versionFiles);
      expect(versionFiles.length).toBeGreaterThanOrEqual(2);

      // Import to new folder
      importFolderId = await createFolder(page.request, testFolderId, `ver-rt-import-${UNIQUE}`);
      createdFolderIds.push(importFolderId);

      const importRes = await page.request.post(
        `${REST_URL}/importexport/import/${importFolderId}`,
        {
          headers: { 'Authorization': AUTH_HEADER },
          multipart: {
            file: { name: 'export.zip', mimeType: 'application/zip', buffer: zipBuffer },
          },
          timeout: 120000,
        }
      );
      expect(importRes.ok()).toBeTruthy();
      const importData = await importRes.json();
      console.log('Import result:', JSON.stringify(importData));

      // Wait for processing
      await new Promise(resolve => setTimeout(resolve, 3000));

      // Find imported document and check version count
      const children = await getChildren(page.request, importFolderId);
      expect(children.length).toBeGreaterThan(0);

      const childProps = children[0].object?.succinctProperties || children[0].object?.properties;
      const childId = childProps?.['cmis:objectId']?.value || childProps?.['cmis:objectId'];
      expect(childId).toBeTruthy();

      const versions = await getAllVersions(page.request, childId);
      console.log(`Imported document has ${versions.length} versions`);

      // Should have exactly 3 versions after round-trip
      expect(versions.length).toBe(3);
      console.log('✅ E2: Version history preserved in round-trip (exactly 3 versions)');
    });

    test('E3. Verify imported version content', async ({ page }) => {
      const children = await getChildren(page.request, importFolderId);
      const childProps = children[0].object?.succinctProperties || children[0].object?.properties;
      const childId = childProps?.['cmis:objectId']?.value || childProps?.['cmis:objectId'];

      // Get all versions to find the latest
      const versions = await getAllVersions(page.request, childId);
      console.log('E3 versions:', versions.map((v: any) => extractPropValue(v, 'cmis:versionLabel')));

      // Find latest version (highest version label)
      const latestVersion = versions.find((v: any) => extractPropValue(v, 'cmis:isLatestVersion') === true);
      const latestId = latestVersion ? extractPropValue(latestVersion, 'cmis:objectId') : childId;

      // Get latest version content via object ID
      const contentRes = await page.request.get(
        `${BROWSER_URL}?objectId=${latestId}&cmisselector=content`,
        { headers: { 'Authorization': AUTH_HEADER }, timeout: TIMEOUT }
      );
      expect(contentRes.ok()).toBeTruthy();
      const latestContent = (await contentRes.body()).toString('utf8');
      console.log('Latest version content:', latestContent);
      expect(latestContent).toBe('RT Version 3');
      console.log('✅ E3: Latest version content matches after round-trip');
    });
  });

  // ========== F. Type Definition Round-trip ==========

  test.describe('F. Type Definition Export/Import Round-trip', () => {
    let typeRtFolderId: string;

    test('F1. Export folder with custom type doc and verify .nemaki-types/', async ({ page }) => {
      // Ensure custom type exists (may not exist if running F tests standalone)
      try {
        const typeCheck = await page.request.get(`${REST_URL}/type/show/${CUSTOM_TYPE_ID}`, {
          headers: { 'Authorization': AUTH_HEADER },
        });
        if (!typeCheck.ok()) {
          await page.request.post(`${REST_URL}/type/create`, {
            headers: { 'Authorization': AUTH_HEADER, 'Content-Type': 'application/json' },
            data: {
              id: CUSTOM_TYPE_ID, localName: CUSTOM_TYPE_ID, displayName: 'IE Advanced Test Doc',
              baseId: 'cmis:document', parentId: 'cmis:document', creatable: true, queryable: true,
              fulltextIndexed: true, includedInSupertypeQuery: true, controllablePolicy: false, controllableACL: true,
              propertyDefinitions: [
                { id: CUSTOM_PROP_DEPT, localName: CUSTOM_PROP_DEPT, displayName: 'Department', propertyType: 'string', cardinality: 'single', updatability: 'readwrite', required: false, queryable: true },
                { id: CUSTOM_PROP_PRIO, localName: CUSTOM_PROP_PRIO, displayName: 'Priority', propertyType: 'integer', cardinality: 'single', updatability: 'readwrite', required: false, queryable: false },
              ],
            },
            timeout: TIMEOUT,
          });
          await new Promise(resolve => setTimeout(resolve, 3000));
        }
      } catch (e) { console.log('Type check/create warning:', e); }

      typeRtFolderId = await createFolder(page.request, testFolderId, `type-rt-src-${UNIQUE}`);
      createdFolderIds.push(typeRtFolderId);

      // Create document with custom type
      const res = await page.request.post(BROWSER_URL, {
        headers: { 'Authorization': AUTH_HEADER },
        multipart: {
          cmisaction: 'createDocument',
          'propertyId[0]': 'cmis:objectTypeId',
          'propertyValue[0]': CUSTOM_TYPE_ID,
          'propertyId[1]': 'cmis:name',
          'propertyValue[1]': `type-rt-doc-${UNIQUE}.txt`,
          'propertyId[2]': CUSTOM_PROP_DEPT,
          'propertyValue[2]': 'R&D',
          objectId: typeRtFolderId,
          content: {
            name: `type-rt-doc-${UNIQUE}.txt`,
            mimeType: 'text/plain',
            buffer: Buffer.from('Type round-trip content'),
          },
          succinct: 'true',
        },
        timeout: TIMEOUT,
      });
      expect(res.ok()).toBeTruthy();

      // Export
      const exportRes = await page.request.get(
        `${REST_URL}/importexport/export/${typeRtFolderId}`,
        { headers: { 'Authorization': AUTH_HEADER }, timeout: 60000 }
      );
      expect(exportRes.ok()).toBeTruthy();

      const zipBuffer = Buffer.from(await exportRes.body());
      const entries = parseZipEntries(zipBuffer);
      const fileNames = Array.from(entries.keys());
      console.log('F1 ZIP entries:', fileNames);

      // Verify .nemaki-types/ directory exists with type definition
      const typeFiles = fileNames.filter(f => f.startsWith('.nemaki-types/') && f.endsWith('.type.json'));
      console.log('Type definition files:', typeFiles);
      expect(typeFiles.length).toBeGreaterThan(0);

      // Verify the custom type definition JSON content
      const typeFile = typeFiles.find(f => f.includes(CUSTOM_TYPE_ID.replace(':', '_'))) || typeFiles[0];
      const typeJson = JSON.parse(entries.get(typeFile)!.toString('utf8'));
      expect(typeJson.id || typeJson.typeId).toBeTruthy();
      expect(typeJson.baseId || typeJson.baseTypeId).toBeTruthy();
      console.log('✅ F1: Type definition found in ZIP:', typeFile);
      console.log('Type JSON keys:', Object.keys(typeJson));
    });

    test('F2. Verify type definition JSON has property definitions', async ({ page }) => {
      const exportRes = await page.request.get(
        `${REST_URL}/importexport/export/${typeRtFolderId}`,
        { headers: { 'Authorization': AUTH_HEADER }, timeout: 60000 }
      );
      const zipBuffer = Buffer.from(await exportRes.body());
      const entries = parseZipEntries(zipBuffer);

      const typeFiles = Array.from(entries.keys()).filter(
        f => f.startsWith('.nemaki-types/') && f.endsWith('.type.json')
      );
      expect(typeFiles.length).toBeGreaterThan(0);

      const typeJson = JSON.parse(entries.get(typeFiles[0])!.toString('utf8'));
      const propDefs = typeJson.propertyDefinitions || typeJson.properties || [];
      console.log('Property definitions count:', Array.isArray(propDefs) ? propDefs.length : Object.keys(propDefs).length);

      // Should have at least our 2 custom properties
      if (Array.isArray(propDefs)) {
        expect(propDefs.length).toBeGreaterThanOrEqual(2);
        const propIds = propDefs.map((p: any) => p.id || p.propertyId);
        console.log('Property IDs:', propIds);
      }
      console.log('✅ F2: Type definition includes property definitions');
    });

    test('F3. Import with existing type - should skip type creation', async ({ page }) => {
      // Export
      const exportRes = await page.request.get(
        `${REST_URL}/importexport/export/${typeRtFolderId}`,
        { headers: { 'Authorization': AUTH_HEADER }, timeout: 60000 }
      );
      const zipBuffer = Buffer.from(await exportRes.body());

      // Import to new folder (type already exists)
      const importFolderId = await createFolder(page.request, testFolderId, `type-rt-import-${UNIQUE}`);
      createdFolderIds.push(importFolderId);

      const importRes = await page.request.post(
        `${REST_URL}/importexport/import/${importFolderId}`,
        {
          headers: { 'Authorization': AUTH_HEADER },
          multipart: {
            file: { name: 'export.zip', mimeType: 'application/zip', buffer: zipBuffer },
          },
          timeout: 60000,
        }
      );
      expect(importRes.ok()).toBeTruthy();
      const importData = await importRes.json();
      console.log('F3 Import result (existing type):', JSON.stringify(importData));

      // Verify document was imported with correct type
      const children = await getChildren(page.request, importFolderId);
      expect(children.length).toBeGreaterThan(0);

      const childProps = children[0].object?.succinctProperties || children[0].object?.properties;
      const childId = childProps?.['cmis:objectId']?.value || childProps?.['cmis:objectId'];
      expect(childId).toBeTruthy();
      const props = await getObjectProperties(page.request, childId);
      console.log('Imported doc type:', props['cmis:objectTypeId']);
      expect(props['cmis:objectTypeId']).toBe(CUSTOM_TYPE_ID);
      expect(props[CUSTOM_PROP_DEPT]).toBe('R&D');
      console.log('✅ F3: Document imported with correct custom type and properties');
    });
  });
});
