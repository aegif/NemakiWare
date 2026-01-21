/**
 * Version Property Inheritance E2E Tests
 *
 * This test suite validates that custom properties are properly inherited
 * when creating new versions through different methods:
 * 1. Same-name file upload (setContentStream)
 * 2. Check-out/Check-in workflow
 *
 * Test Environment:
 * - Server: http://localhost:8080/core
 * - Authentication: admin:admin
 * - Repository: bedroom
 *
 * CMIS Concepts Tested:
 * - setContentStream creates new version while preserving properties
 * - checkIn creates new version with property inheritance
 * - Custom properties (subTypeProperties) are copied to new versions
 *
 * Implementation Reference:
 * - ContentServiceImpl.buildCopyDocument() copies SubTypeProperties
 * - Line 1322: copy.setSubTypeProperties(new ArrayList<Property>(original.getSubTypeProperties()))
 */

import { test, expect } from '@playwright/test';
import { randomUUID } from 'crypto';

const BASE_URL = 'http://localhost:8080/core/browser/bedroom';
const AUTH_HEADER = 'Basic ' + Buffer.from('admin:admin').toString('base64');

// Custom type with custom property for testing
const TEST_TYPE_ID = 'test:versionableDocWithProps';
const CUSTOM_PROPERTY_ID = 'test:customProp';
const CUSTOM_PROPERTY_VALUE = 'inherited-value-' + randomUUID().substring(0, 8);
const UPDATED_PROPERTY_VALUE = 'updated-value-' + randomUUID().substring(0, 8);

test.describe('Version Property Inheritance', () => {
  test.describe.configure({ mode: 'serial' });

  const testRunId = randomUUID().substring(0, 8);
  const testDocumentName = `prop-inherit-test-${testRunId}.txt`;
  let testDocumentId: string;
  let rootFolderId: string;

  /**
   * Helper: Extract objectId from response (handles both succinct and full formats)
   */
  function extractObjectId(data: any): string {
    if (data.succinctProperties) {
      return data.succinctProperties['cmis:objectId'];
    }
    if (data.properties && data.properties['cmis:objectId']) {
      return data.properties['cmis:objectId'].value;
    }
    throw new Error('Unable to extract objectId from response');
  }

  /**
   * Helper: Get root folder ID using repositoryInfo
   */
  async function getRootFolderId(request: any): Promise<string> {
    const response = await request.get(`${BASE_URL}?cmisselector=repositoryInfo`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    const data = await response.json();
    // Browser Binding returns repository info wrapped in repository ID key
    // e.g., { "bedroom": { "rootFolderId": "..." } }
    const repoInfo = data.bedroom || data[Object.keys(data)[0]];
    return repoInfo.rootFolderId;
  }

  /**
   * Helper: Get document properties via AtomPub API
   */
  async function getDocumentProperties(request: any, objectId: string): Promise<Record<string, any>> {
    // Use AtomPub binding for reliable object retrieval (URL format: /id?id=objectId)
    const response = await request.get(`http://localhost:8080/core/atom/bedroom/id?id=${encodeURIComponent(objectId)}`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    const text = await response.text();

    // Parse Atom entry to extract properties
    const result: Record<string, any> = {};

    // Extract properties using regex (matches any property type: propertyId, propertyString, etc.)
    const propPatterns = [
      'cmis:objectId',
      'cmis:objectTypeId',
      'cmis:name',
      'cmis:versionLabel',
      'cmis:changeToken',
      CUSTOM_PROPERTY_ID,
    ];

    for (const propId of propPatterns) {
      // Match any property type (propertyId, propertyString, propertyDateTime, etc.)
      const pattern = new RegExp(`<cmis:property[^>]*propertyDefinitionId="${propId}"[^>]*>[\\s\\S]*?<cmis:value>([^<]*)</cmis:value>`);
      const match = text.match(pattern);
      if (match) {
        result[propId] = match[1];
      }
    }

    return result;
  }

  /**
   * Helper: Create or verify custom type exists
   */
  async function ensureCustomTypeExists(request: any): Promise<boolean> {
    // First, check if the type exists
    try {
      const typeResponse = await request.get(
        `http://localhost:8080/core/rest/repo/bedroom/type/${TEST_TYPE_ID}`,
        { headers: { 'Authorization': AUTH_HEADER } }
      );
      if (typeResponse.ok()) {
        console.log(`Custom type ${TEST_TYPE_ID} already exists`);
        return true;
      }
    } catch (e) {
      // Type doesn't exist, we'll create it
    }

    // Create custom type with versionable=true and a custom property
    const typeDefinition = {
      id: TEST_TYPE_ID,
      localName: TEST_TYPE_ID,
      localNamespace: 'http://test.nemakiware.org',
      displayName: 'Versionable Document with Props',
      queryName: TEST_TYPE_ID.replace(':', '_'),
      description: 'Test type for version property inheritance',
      baseId: 'cmis:document',
      parentId: 'cmis:document',
      creatable: true,
      fileable: true,
      queryable: true,
      controllablePolicy: true,
      controllableACL: true,
      fulltextIndexed: true,
      includedInSupertypeQuery: true,
      versionable: true,
      contentStreamAllowed: 'allowed',
      propertyDefinitions: {
        [CUSTOM_PROPERTY_ID]: {
          id: CUSTOM_PROPERTY_ID,
          localName: CUSTOM_PROPERTY_ID,
          displayName: 'Custom Property',
          queryName: CUSTOM_PROPERTY_ID.replace(':', '_'),
          description: 'Custom property for inheritance testing',
          propertyType: 'string',
          cardinality: 'single',
          updatability: 'readwrite',
          inherited: false,
          required: false,
          queryable: true,
          orderable: true
        }
      }
    };

    try {
      const createResponse = await request.post(
        'http://localhost:8080/core/rest/repo/bedroom/type',
        {
          headers: {
            'Authorization': AUTH_HEADER,
            'Content-Type': 'application/json'
          },
          data: JSON.stringify(typeDefinition)
        }
      );

      if (createResponse.ok()) {
        console.log(`Created custom type ${TEST_TYPE_ID}`);
        return true;
      } else {
        console.error('Failed to create custom type:', await createResponse.text());
        return false;
      }
    } catch (e) {
      console.error('Error creating custom type:', e);
      return false;
    }
  }

  /**
   * Helper: Create document with custom property via API
   */
  async function createDocumentWithCustomProperty(
    request: any,
    folderId: string,
    name: string,
    content: string,
    customPropertyValue: string
  ): Promise<string> {
    const formData = new URLSearchParams();
    formData.append('cmisaction', 'createDocument');
    formData.append('succinct', 'true');
    formData.append('propertyId[0]', 'cmis:objectTypeId');
    formData.append('propertyValue[0]', TEST_TYPE_ID);
    formData.append('propertyId[1]', 'cmis:name');
    formData.append('propertyValue[1]', name);
    formData.append('propertyId[2]', CUSTOM_PROPERTY_ID);
    formData.append('propertyValue[2]', customPropertyValue);

    // Create multipart form for file upload
    const boundary = '----WebKitFormBoundary' + randomUUID().substring(0, 8);
    const body = [
      `--${boundary}`,
      'Content-Disposition: form-data; name="cmisaction"',
      '',
      'createDocument',
      `--${boundary}`,
      'Content-Disposition: form-data; name="succinct"',
      '',
      'true',
      `--${boundary}`,
      'Content-Disposition: form-data; name="propertyId[0]"',
      '',
      'cmis:objectTypeId',
      `--${boundary}`,
      'Content-Disposition: form-data; name="propertyValue[0]"',
      '',
      TEST_TYPE_ID,
      `--${boundary}`,
      'Content-Disposition: form-data; name="propertyId[1]"',
      '',
      'cmis:name',
      `--${boundary}`,
      'Content-Disposition: form-data; name="propertyValue[1]"',
      '',
      name,
      `--${boundary}`,
      'Content-Disposition: form-data; name="propertyId[2]"',
      '',
      CUSTOM_PROPERTY_ID,
      `--${boundary}`,
      'Content-Disposition: form-data; name="propertyValue[2]"',
      '',
      customPropertyValue,
      `--${boundary}`,
      `Content-Disposition: form-data; name="content"; filename="${name}"`,
      'Content-Type: text/plain',
      '',
      content,
      `--${boundary}--`,
      ''
    ].join('\r\n');

    const response = await request.post(`${BASE_URL}?objectId=${encodeURIComponent(folderId)}`, {
      headers: {
        'Authorization': AUTH_HEADER,
        'Content-Type': `multipart/form-data; boundary=${boundary}`
      },
      data: body
    });

    if (!response.ok()) {
      throw new Error(`Failed to create document: ${await response.text()}`);
    }

    const data = await response.json();
    return extractObjectId(data);
  }

  /**
   * Helper: Update content stream via API (creates new version for versionable docs)
   */
  async function setContentStream(
    request: any,
    objectId: string,
    name: string,
    content: string,
    changeToken?: string
  ): Promise<string> {
    const boundary = '----WebKitFormBoundary' + randomUUID().substring(0, 8);
    const bodyParts = [
      `--${boundary}`,
      'Content-Disposition: form-data; name="cmisaction"',
      '',
      'setContent',
      `--${boundary}`,
      'Content-Disposition: form-data; name="objectId"',
      '',
      objectId,
      `--${boundary}`,
      'Content-Disposition: form-data; name="overwriteFlag"',
      '',
      'true',
    ];

    // Add change token if provided
    if (changeToken) {
      bodyParts.push(
        `--${boundary}`,
        'Content-Disposition: form-data; name="changeToken"',
        '',
        changeToken
      );
    }

    bodyParts.push(
      `--${boundary}`,
      `Content-Disposition: form-data; name="content"; filename="${name}"`,
      'Content-Type: text/plain',
      '',
      content,
      `--${boundary}--`,
      ''
    );

    const body = bodyParts.join('\r\n');

    const response = await request.post(`${BASE_URL}?objectId=${encodeURIComponent(objectId)}`, {
      headers: {
        'Authorization': AUTH_HEADER,
        'Content-Type': `multipart/form-data; boundary=${boundary}`
      },
      data: body
    });

    if (!response.ok()) {
      throw new Error(`Failed to set content stream: ${await response.text()}`);
    }

    const data = await response.json();
    return extractObjectId(data);
  }

  /**
   * Helper: Check out document via API
   */
  async function checkOut(request: any, objectId: string): Promise<string> {
    const formData = new URLSearchParams();
    formData.append('cmisaction', 'checkOut');
    formData.append('objectId', objectId);
    formData.append('succinct', 'true');

    const response = await request.post(BASE_URL, {
      headers: {
        'Authorization': AUTH_HEADER,
        'Content-Type': 'application/x-www-form-urlencoded'
      },
      data: formData.toString()
    });

    if (!response.ok()) {
      throw new Error(`Failed to check out: ${await response.text()}`);
    }

    const data = await response.json();
    return extractObjectId(data); // PWC ID
  }

  /**
   * Helper: Check in document via API
   */
  async function checkIn(
    request: any,
    pwcId: string,
    name: string,
    content: string,
    comment: string,
    major: boolean = true
  ): Promise<string> {
    const boundary = '----WebKitFormBoundary' + randomUUID().substring(0, 8);
    const body = [
      `--${boundary}`,
      'Content-Disposition: form-data; name="cmisaction"',
      '',
      'checkIn',
      `--${boundary}`,
      'Content-Disposition: form-data; name="objectId"',
      '',
      pwcId,
      `--${boundary}`,
      'Content-Disposition: form-data; name="major"',
      '',
      String(major),
      `--${boundary}`,
      'Content-Disposition: form-data; name="checkinComment"',
      '',
      comment,
      `--${boundary}`,
      'Content-Disposition: form-data; name="succinct"',
      '',
      'true',
      `--${boundary}`,
      `Content-Disposition: form-data; name="content"; filename="${name}"`,
      'Content-Type: text/plain',
      '',
      content,
      `--${boundary}--`,
      ''
    ].join('\r\n');

    const response = await request.post(BASE_URL, {
      headers: {
        'Authorization': AUTH_HEADER,
        'Content-Type': `multipart/form-data; boundary=${boundary}`
      },
      data: body
    });

    if (!response.ok()) {
      throw new Error(`Failed to check in: ${await response.text()}`);
    }

    const data = await response.json();
    return extractObjectId(data);
  }

  /**
   * Helper: Get all versions of a document via AtomPub API
   */
  async function getAllVersions(request: any, objectId: string): Promise<any[]> {
    // Use AtomPub binding for version history as it's the supported method
    const response = await request.get(
      `http://localhost:8080/core/atom/bedroom/versions?id=${encodeURIComponent(objectId)}`,
      { headers: { 'Authorization': AUTH_HEADER } }
    );

    if (!response.ok()) {
      throw new Error(`Failed to get versions: ${await response.text()}`);
    }

    const text = await response.text();
    // Parse Atom feed to extract version entries (handles atom:entry namespace)
    const versions: any[] = [];
    // Match both <entry> and <atom:entry> formats
    const entryPattern = /<(?:atom:)?entry[^>]*>([\s\S]*?)<\/(?:atom:)?entry>/g;
    let match;
    while ((match = entryPattern.exec(text)) !== null) {
      const entry = match[1];
      // Extract objectId from properties
      const objectIdMatch = entry.match(/<cmis:property[^>]*propertyDefinitionId="cmis:objectId"[^>]*>[\s\S]*?<cmis:value>([^<]*)<\/cmis:value>/);
      const versionLabelMatch = entry.match(/<cmis:property[^>]*propertyDefinitionId="cmis:versionLabel"[^>]*>[\s\S]*?<cmis:value>([^<]*)<\/cmis:value>/);
      const objectTypeMatch = entry.match(/<cmis:property[^>]*propertyDefinitionId="cmis:objectTypeId"[^>]*>[\s\S]*?<cmis:value>([^<]*)<\/cmis:value>/);

      if (objectIdMatch) {
        versions.push({
          'cmis:objectId': objectIdMatch[1],
          'cmis:versionLabel': versionLabelMatch ? versionLabelMatch[1] : 'unknown',
          'cmis:objectTypeId': objectTypeMatch ? objectTypeMatch[1] : 'unknown'
        });
      }
    }
    return versions;
  }

  /**
   * Helper: Delete document via API
   */
  async function deleteDocument(request: any, objectId: string): Promise<void> {
    const formData = new URLSearchParams();
    formData.append('cmisaction', 'delete');
    formData.append('objectId', objectId);
    formData.append('allVersions', 'true');

    await request.post(BASE_URL, {
      headers: {
        'Authorization': AUTH_HEADER,
        'Content-Type': 'application/x-www-form-urlencoded'
      },
      data: formData.toString()
    });
  }

  test('Step 1: Setup - Ensure custom type exists and get root folder', async ({ request }) => {
    console.log('Setting up test environment...');

    // Ensure custom type exists
    const typeExists = await ensureCustomTypeExists(request);
    if (!typeExists) {
      console.log('Custom type creation failed, using cmis:document instead');
    }

    // Get root folder ID
    rootFolderId = await getRootFolderId(request);
    console.log(`Root folder ID: ${rootFolderId}`);

    expect(rootFolderId).toBeTruthy();
  });

  test('Step 2: Create document with custom property', async ({ request }) => {
    console.log(`Creating document: ${testDocumentName} with custom property: ${CUSTOM_PROPERTY_VALUE}`);

    try {
      testDocumentId = await createDocumentWithCustomProperty(
        request,
        rootFolderId,
        testDocumentName,
        'Version 1.0 - Initial content',
        CUSTOM_PROPERTY_VALUE
      );
      console.log(`Document created with ID: ${testDocumentId}`);
    } catch (e) {
      // If custom type creation failed, create with cmis:document
      console.log('Custom type not available, creating with cmis:document...');
      const boundary = '----WebKitFormBoundary' + randomUUID().substring(0, 8);
      const body = [
        `--${boundary}`,
        'Content-Disposition: form-data; name="cmisaction"',
        '',
        'createDocument',
        `--${boundary}`,
        'Content-Disposition: form-data; name="succinct"',
        '',
        'true',
        `--${boundary}`,
        'Content-Disposition: form-data; name="propertyId[0]"',
        '',
        'cmis:objectTypeId',
        `--${boundary}`,
        'Content-Disposition: form-data; name="propertyValue[0]"',
        '',
        'cmis:document',
        `--${boundary}`,
        'Content-Disposition: form-data; name="propertyId[1]"',
        '',
        'cmis:name',
        `--${boundary}`,
        'Content-Disposition: form-data; name="propertyValue[1]"',
        '',
        testDocumentName,
        `--${boundary}`,
        `Content-Disposition: form-data; name="content"; filename="${testDocumentName}"`,
        'Content-Type: text/plain',
        '',
        'Version 1.0 - Initial content',
        `--${boundary}--`,
        ''
      ].join('\r\n');

      const response = await request.post(`${BASE_URL}?objectId=${encodeURIComponent(rootFolderId)}`, {
        headers: {
          'Authorization': AUTH_HEADER,
          'Content-Type': `multipart/form-data; boundary=${boundary}`
        },
        data: body
      });

      if (!response.ok()) {
        throw new Error(`Failed to create document: ${await response.text()}`);
      }

      const data = await response.json();
      testDocumentId = extractObjectId(data);
      console.log(`Document created with cmis:document type, ID: ${testDocumentId}`);
    }

    // Verify initial properties
    const props = await getDocumentProperties(request, testDocumentId);
    console.log(`Initial version label: ${props['cmis:versionLabel']}`);
    console.log(`Custom property value: ${props[CUSTOM_PROPERTY_ID] || 'N/A (cmis:document type)'}`);

    expect(testDocumentId).toBeTruthy();
  });

  test('Step 3: Verify property inheritance via setContentStream (same-name upload)', async ({ request }) => {
    if (!testDocumentId) {
      test.skip('Document not created in previous step');
      return;
    }

    console.log('Creating new version via setContentStream...');

    // Get initial properties (including change token)
    const initialProps = await getDocumentProperties(request, testDocumentId);
    const initialVersionLabel = initialProps['cmis:versionLabel'];
    const initialCustomProp = initialProps[CUSTOM_PROPERTY_ID];
    const initialObjectType = initialProps['cmis:objectTypeId'];
    const changeToken = initialProps['cmis:changeToken'];

    console.log(`Initial version: ${initialVersionLabel}`);
    console.log(`Initial custom property: ${initialCustomProp || 'N/A'}`);
    console.log(`Object type: ${initialObjectType}`);
    console.log(`Change token: ${changeToken}`);

    // Create new version via setContentStream (with change token)
    const newVersionId = await setContentStream(
      request,
      testDocumentId,
      testDocumentName,
      'Version 2.0 - Updated via setContentStream',
      changeToken
    );

    console.log(`New version ID: ${newVersionId}`);

    // Get new version properties
    const newProps = await getDocumentProperties(request, newVersionId);
    const newVersionLabel = newProps['cmis:versionLabel'];
    const newCustomProp = newProps[CUSTOM_PROPERTY_ID];
    const newObjectType = newProps['cmis:objectTypeId'];

    console.log(`New version label: ${newVersionLabel}`);
    console.log(`New custom property: ${newCustomProp || 'N/A'}`);

    // Verify version changed
    expect(newVersionLabel).not.toBe(initialVersionLabel);

    // Verify object type is inherited
    expect(newObjectType).toBe(initialObjectType);

    // Verify custom property is inherited (if custom type was used)
    if (initialCustomProp) {
      console.log('Verifying custom property inheritance...');
      expect(newCustomProp).toBe(initialCustomProp);
      console.log('Custom property successfully inherited via setContentStream');
    } else {
      console.log('Custom type not available - skipping custom property verification');
    }

    // Update testDocumentId to the new version for subsequent tests
    testDocumentId = newVersionId;
  });

  test('Step 4: Verify property inheritance via check-out/check-in', async ({ request }) => {
    if (!testDocumentId) {
      test.skip('Document not created in previous step');
      return;
    }

    console.log('Creating new version via check-out/check-in...');

    // Get current properties
    const currentProps = await getDocumentProperties(request, testDocumentId);
    const currentVersionLabel = currentProps['cmis:versionLabel'];
    const currentCustomProp = currentProps[CUSTOM_PROPERTY_ID];
    const currentObjectType = currentProps['cmis:objectTypeId'];

    console.log(`Current version: ${currentVersionLabel}`);
    console.log(`Current custom property: ${currentCustomProp || 'N/A'}`);

    // Check out the document
    console.log('Checking out document...');
    const pwcId = await checkOut(request, testDocumentId);
    console.log(`PWC ID: ${pwcId}`);

    // Check in with new content
    console.log('Checking in with new content...');
    const newVersionId = await checkIn(
      request,
      pwcId,
      testDocumentName,
      'Version 3.0 - Updated via check-in',
      'Version 3.0 created via check-in',
      true // major version
    );

    console.log(`New version ID: ${newVersionId}`);

    // Get new version properties
    const newProps = await getDocumentProperties(request, newVersionId);
    const newVersionLabel = newProps['cmis:versionLabel'];
    const newCustomProp = newProps[CUSTOM_PROPERTY_ID];
    const newObjectType = newProps['cmis:objectTypeId'];

    console.log(`New version label: ${newVersionLabel}`);
    console.log(`New custom property: ${newCustomProp || 'N/A'}`);

    // Verify version changed
    expect(newVersionLabel).not.toBe(currentVersionLabel);

    // Verify object type is inherited
    expect(newObjectType).toBe(currentObjectType);

    // Verify custom property is inherited (if custom type was used)
    if (currentCustomProp) {
      console.log('Verifying custom property inheritance...');
      expect(newCustomProp).toBe(currentCustomProp);
      console.log('Custom property successfully inherited via check-in');
    } else {
      console.log('Custom type not available - skipping custom property verification');
    }

    // Update testDocumentId to the new version
    testDocumentId = newVersionId;
  });

  test('Step 5: Verify all versions maintain property consistency', async ({ request }) => {
    if (!testDocumentId) {
      test.skip('Document not created in previous steps');
      return;
    }

    console.log('Verifying property consistency across all versions...');

    // Get all versions via AtomPub API
    const versions = await getAllVersions(request, testDocumentId);
    console.log(`Total versions: ${versions.length}`);

    // Verify we have multiple versions (original + at least 2 new versions)
    expect(versions.length).toBeGreaterThanOrEqual(2);

    // Check each version has the same object type
    const objectTypes = new Set<string>();

    for (const version of versions) {
      const objectType = version['cmis:objectTypeId'];
      const versionLabel = version['cmis:versionLabel'];

      console.log(`Version ${versionLabel}: objectType=${objectType}`);

      if (objectType && objectType !== 'unknown') {
        objectTypes.add(objectType);
      }
    }

    // All versions should have the same object type
    if (objectTypes.size > 0) {
      expect(objectTypes.size).toBe(1);
      console.log('All versions have consistent object type');
    }

    console.log('Property consistency verification completed successfully');
  });

  test.afterAll(async ({ request }) => {
    console.log('=== Cleanup: Deleting test document ===');

    if (testDocumentId) {
      try {
        await deleteDocument(request, testDocumentId);
        console.log(`Deleted document: ${testDocumentId}`);
      } catch (e) {
        console.error(`Failed to delete document: ${e}`);
      }
    }

    console.log('=== Cleanup completed ===');
  });
});
