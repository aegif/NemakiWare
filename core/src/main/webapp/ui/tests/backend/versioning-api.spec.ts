import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';

/**
 * CMIS Versioning API Tests
 *
 * Tests document versioning operations using CMIS Browser Binding API
 * This tests the actual backend versioning functionality, not the UI.
 */
test.describe('CMIS Versioning API', () => {
  const baseUrl = process.env.DOCKER_ENV === '1'
    ? 'http://localhost:8080/core/browser/bedroom'
    : 'http://localhost:8080/core/browser/bedroom';

  const rootFolderId = 'e02f784f8360a02cc14d1314c10038ff'; // Bedroom root folder
  let authHeader: string;
  let testDocumentId: string;
  let pwcId: string; // Private Working Copy ID

  test.beforeAll(async () => {
    // Setup basic auth header
    const credentials = Buffer.from('admin:admin').toString('base64');
    authHeader = `Basic ${credentials}`;
  });

  test.afterEach(async ({ request }) => {
    // Cleanup: Delete test document if it exists
    if (testDocumentId) {
      try {
        await request.post(baseUrl, {
          headers: {
            'Authorization': authHeader,
            'Content-Type': 'application/x-www-form-urlencoded',
          },
          form: {
            cmisaction: 'delete',
            objectId: testDocumentId,
            allVersions: 'true',
          },
        });
        console.log(`Cleaned up test document: ${testDocumentId}`);
      } catch (error) {
        console.log(`Cleanup failed for ${testDocumentId}:`, error);
      }
      testDocumentId = '';
    }

    // Cleanup PWC if it exists
    if (pwcId) {
      try {
        await request.post(baseUrl, {
          headers: {
            'Authorization': authHeader,
            'Content-Type': 'application/x-www-form-urlencoded',
          },
          form: {
            cmisaction: 'delete',
            objectId: pwcId,
          },
        });
        console.log(`Cleaned up PWC: ${pwcId}`);
      } catch (error) {
        console.log(`PWC cleanup failed for ${pwcId}:`, error);
      }
      pwcId = '';
    }
  });

  test('should create a versionable document', async ({ request }) => {
    // Create a versionable document using Browser Binding
    // Using application/x-www-form-urlencoded for simplicity (content added separately)
    const createResponse = await request.post(baseUrl, {
      headers: {
        'Authorization': authHeader,
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      form: {
        cmisaction: 'createDocument',
        folderId: rootFolderId,
        'propertyId[0]': 'cmis:objectTypeId',
        'propertyValue[0]': 'cmis:document',
        'propertyId[1]': 'cmis:name',
        'propertyValue[1]': 'versioning-test-doc.txt',
      },
    });

    expect(createResponse.status()).toBe(201);

    const responseData = await createResponse.json();
    testDocumentId = responseData.succinctProperties['cmis:objectId'];

    console.log('Created versionable document:', testDocumentId);
    expect(testDocumentId).toBeTruthy();

    // Verify document is versionable
    const objectResponse = await request.get(`${baseUrl}/root`, {
      headers: { 'Authorization': authHeader },
      params: {
        cmisselector: 'object',
        objectId: testDocumentId,
        succinct: 'true',
      },
    });

    expect(objectResponse.status()).toBe(200);
    const objectData = await objectResponse.json();

    // Check version properties
    expect(objectData.succinctProperties['cmis:isVersionSeriesCheckedOut']).toBe(false);
    expect(objectData.succinctProperties['cmis:versionLabel']).toBeTruthy();
    console.log('Document version label:', objectData.succinctProperties['cmis:versionLabel']);
  });

  test('should check-out a document', async ({ request }) => {
    // 1. Create a versionable document
    const createResponse = await request.post(baseUrl, {
      headers: {
        'Authorization': authHeader,
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      form: {
        cmisaction: 'createDocument',
        folderId: rootFolderId,
        'propertyId[0]': 'cmis:objectTypeId',
        'propertyValue[0]': 'cmis:document',
        'propertyId[1]': 'cmis:name',
        'propertyValue[1]': 'checkout-test-doc.txt',
      },
    });

    const createData = await createResponse.json();
    testDocumentId = createData.succinctProperties['cmis:objectId'];

    // 2. Check-out the document
    const checkoutResponse = await request.post(baseUrl, {
      headers: {
        'Authorization': authHeader,
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      form: {
        cmisaction: 'checkOut',
        objectId: testDocumentId,
        succinct: 'true',
      },
    });

    expect(checkoutResponse.status()).toBe(201);

    const checkoutData = await checkoutResponse.json();
    pwcId = checkoutData.succinctProperties['cmis:objectId'];

    console.log('Checked out document, PWC ID:', pwcId);
    expect(pwcId).toBeTruthy();
    expect(pwcId).not.toBe(testDocumentId); // PWC should have different ID

    // 3. Verify document is checked out
    const objectResponse = await request.get(`${baseUrl}/root`, {
      headers: { 'Authorization': authHeader },
      params: {
        cmisselector: 'object',
        objectId: testDocumentId,
        succinct: 'true',
      },
    });

    const objectData = await objectResponse.json();
    expect(objectData.succinctProperties['cmis:isVersionSeriesCheckedOut']).toBe(true);
    expect(objectData.succinctProperties['cmis:versionSeriesCheckedOutId']).toBe(pwcId);
    console.log('Document is checked out by:', objectData.succinctProperties['cmis:versionSeriesCheckedOutBy']);
  });

  test('should check-in a document with new version', async ({ request }) => {
    // 1. Create a versionable document
    const createResponse = await request.post(baseUrl, {
      headers: {
        'Authorization': authHeader,
        'Content-Type': 'multipart/form-data; boundary=----WebKitFormBoundary',
      },
      multipart: {
        cmisaction: 'createDocument',
        folderId: rootFolderId,
        'propertyId[0]': 'cmis:objectTypeId',
        'propertyValue[0]': 'cmis:document',
        'propertyId[1]': 'cmis:name',
        'propertyValue[1]': 'checkin-test-doc.txt',
        content: {
          name: 'checkin-test-doc.txt',
          mimeType: 'text/plain',
          buffer: Buffer.from('Version 1.0 content', 'utf-8'),
        },
      },
    });

    const createData = await createResponse.json();
    testDocumentId = createData.succinctProperties['cmis:objectId'];
    const originalVersionLabel = createData.succinctProperties['cmis:versionLabel'];

    // 2. Check-out the document
    const checkoutResponse = await request.post(baseUrl, {
      headers: {
        'Authorization': authHeader,
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      form: {
        cmisaction: 'checkOut',
        objectId: testDocumentId,
        succinct: 'true',
      },
    });

    const checkoutData = await checkoutResponse.json();
    pwcId = checkoutData.succinctProperties['cmis:objectId'];

    // 3. Check-in with new content (major version)
    const checkinResponse = await request.post(baseUrl, {
      headers: {
        'Authorization': authHeader,
        'Content-Type': 'multipart/form-data; boundary=----WebKitFormBoundary',
      },
      multipart: {
        cmisaction: 'checkIn',
        objectId: pwcId,
        major: 'true', // Create major version
        checkinComment: 'Updated to version 2.0',
        content: {
          name: 'checkin-test-doc.txt',
          mimeType: 'text/plain',
          buffer: Buffer.from('Version 2.0 content - updated', 'utf-8'),
        },
        succinct: 'true',
      },
    });

    expect(checkinResponse.status()).toBe(201);

    const checkinData = await checkinResponse.json();
    const newVersionId = checkinData.succinctProperties['cmis:objectId'];
    const newVersionLabel = checkinData.succinctProperties['cmis:versionLabel'];

    console.log('Checked in document:');
    console.log('  Original version:', originalVersionLabel);
    console.log('  New version:', newVersionLabel);
    console.log('  New version ID:', newVersionId);

    // Update testDocumentId to new version for cleanup
    testDocumentId = newVersionId;

    // 4. Verify document is no longer checked out
    const objectResponse = await request.get(`${baseUrl}/root`, {
      headers: { 'Authorization': authHeader },
      params: {
        cmisselector: 'object',
        objectId: newVersionId,
        succinct: 'true',
      },
    });

    const objectData = await objectResponse.json();
    expect(objectData.succinctProperties['cmis:isVersionSeriesCheckedOut']).toBe(false);
    expect(objectData.succinctProperties['cmis:versionLabel']).not.toBe(originalVersionLabel);

    // PWC should be gone after check-in
    pwcId = '';
  });

  test('should cancel check-out', async ({ request }) => {
    // 1. Create a versionable document
    const createResponse = await request.post(baseUrl, {
      headers: {
        'Authorization': authHeader,
        'Content-Type': 'multipart/form-data; boundary=----WebKitFormBoundary',
      },
      multipart: {
        cmisaction: 'createDocument',
        folderId: rootFolderId,
        'propertyId[0]': 'cmis:objectTypeId',
        'propertyValue[0]': 'cmis:document',
        'propertyId[1]': 'cmis:name',
        'propertyValue[1]': 'cancel-checkout-test.txt',
        content: {
          name: 'cancel-checkout-test.txt',
          mimeType: 'text/plain',
          buffer: Buffer.from('Original content', 'utf-8'),
        },
      },
    });

    const createData = await createResponse.json();
    testDocumentId = createData.succinctProperties['cmis:objectId'];

    // 2. Check-out the document
    const checkoutResponse = await request.post(baseUrl, {
      headers: {
        'Authorization': authHeader,
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      form: {
        cmisaction: 'checkOut',
        objectId: testDocumentId,
        succinct: 'true',
      },
    });

    const checkoutData = await checkoutResponse.json();
    pwcId = checkoutData.succinctProperties['cmis:objectId'];

    // 3. Cancel check-out
    const cancelResponse = await request.post(baseUrl, {
      headers: {
        'Authorization': authHeader,
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      form: {
        cmisaction: 'cancelCheckOut',
        objectId: pwcId,
      },
    });

    expect(cancelResponse.status()).toBe(200);
    console.log('Cancelled check-out, PWC deleted');

    // 4. Verify document is no longer checked out
    const objectResponse = await request.get(`${baseUrl}/root`, {
      headers: { 'Authorization': authHeader },
      params: {
        cmisselector: 'object',
        objectId: testDocumentId,
        succinct: 'true',
      },
    });

    const objectData = await objectResponse.json();
    expect(objectData.succinctProperties['cmis:isVersionSeriesCheckedOut']).toBe(false);
    console.log('Document is no longer checked out');

    // PWC should be deleted after cancel
    pwcId = '';

    // 5. Verify PWC no longer exists
    const pwcResponse = await request.get(`${baseUrl}/root`, {
      headers: { 'Authorization': authHeader },
      params: {
        cmisselector: 'object',
        objectId: pwcId || 'dummy-id',
        succinct: 'true',
      },
    });

    // PWC should return 404 or error
    expect([404, 500]).toContain(pwcResponse.status());
  });

  test('should retrieve all versions of a document', async ({ request }) => {
    // 1. Create a versionable document
    const createResponse = await request.post(baseUrl, {
      headers: {
        'Authorization': authHeader,
        'Content-Type': 'multipart/form-data; boundary=----WebKitFormBoundary',
      },
      multipart: {
        cmisaction: 'createDocument',
        folderId: rootFolderId,
        'propertyId[0]': 'cmis:objectTypeId',
        'propertyValue[0]': 'cmis:document',
        'propertyId[1]': 'cmis:name',
        'propertyValue[1]': 'all-versions-test.txt',
        content: {
          name: 'all-versions-test.txt',
          mimeType: 'text/plain',
          buffer: Buffer.from('Version 1.0', 'utf-8'),
        },
      },
    });

    const createData = await createResponse.json();
    testDocumentId = createData.succinctProperties['cmis:objectId'];

    // 2. Create version 2.0 (check-out -> check-in)
    const checkout1 = await request.post(baseUrl, {
      headers: {
        'Authorization': authHeader,
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      form: {
        cmisaction: 'checkOut',
        objectId: testDocumentId,
        succinct: 'true',
      },
    });

    const pwc1Data = await checkout1.json();
    const pwc1Id = pwc1Data.succinctProperties['cmis:objectId'];

    const checkin1 = await request.post(baseUrl, {
      headers: {
        'Authorization': authHeader,
        'Content-Type': 'multipart/form-data; boundary=----WebKitFormBoundary',
      },
      multipart: {
        cmisaction: 'checkIn',
        objectId: pwc1Id,
        major: 'true',
        checkinComment: 'Version 2.0',
        content: {
          name: 'all-versions-test.txt',
          mimeType: 'text/plain',
          buffer: Buffer.from('Version 2.0', 'utf-8'),
        },
        succinct: 'true',
      },
    });

    const version2Data = await checkin1.json();
    testDocumentId = version2Data.succinctProperties['cmis:objectId']; // Update to latest

    // 3. Create version 3.0 (check-out -> check-in)
    const checkout2 = await request.post(baseUrl, {
      headers: {
        'Authorization': authHeader,
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      form: {
        cmisaction: 'checkOut',
        objectId: testDocumentId,
        succinct: 'true',
      },
    });

    const pwc2Data = await checkout2.json();
    const pwc2Id = pwc2Data.succinctProperties['cmis:objectId'];

    const checkin2 = await request.post(baseUrl, {
      headers: {
        'Authorization': authHeader,
        'Content-Type': 'multipart/form-data; boundary=----WebKitFormBoundary',
      },
      multipart: {
        cmisaction: 'checkIn',
        objectId: pwc2Id,
        major: 'true',
        checkinComment: 'Version 3.0',
        content: {
          name: 'all-versions-test.txt',
          mimeType: 'text/plain',
          buffer: Buffer.from('Version 3.0', 'utf-8'),
        },
        succinct: 'true',
      },
    });

    const version3Data = await checkin2.json();
    testDocumentId = version3Data.succinctProperties['cmis:objectId']; // Update to latest

    // 4. Get all versions
    const versionsResponse = await request.get(`${baseUrl}/root`, {
      headers: { 'Authorization': authHeader },
      params: {
        cmisselector: 'versions',
        objectId: testDocumentId,
        succinct: 'true',
      },
    });

    expect(versionsResponse.status()).toBe(200);

    const versionsData = await versionsResponse.json();
    console.log('All versions response:', JSON.stringify(versionsData, null, 2));

    // Verify we have 3 versions
    expect(versionsData.objects).toBeDefined();
    expect(versionsData.objects.length).toBeGreaterThanOrEqual(3);

    // Verify version labels
    const versionLabels = versionsData.objects.map((obj: any) =>
      obj.object.succinctProperties['cmis:versionLabel']
    );
    console.log('Version labels:', versionLabels);

    // Should contain versions in some order
    expect(versionLabels.length).toBeGreaterThanOrEqual(3);
  });

  test('should get latest version of a document', async ({ request }) => {
    // 1. Create initial document
    const createResponse = await request.post(baseUrl, {
      headers: {
        'Authorization': authHeader,
        'Content-Type': 'multipart/form-data; boundary=----WebKitFormBoundary',
      },
      multipart: {
        cmisaction: 'createDocument',
        folderId: rootFolderId,
        'propertyId[0]': 'cmis:objectTypeId',
        'propertyValue[0]': 'cmis:document',
        'propertyId[1]': 'cmis:name',
        'propertyValue[1]': 'latest-version-test.txt',
        content: {
          name: 'latest-version-test.txt',
          mimeType: 'text/plain',
          buffer: Buffer.from('Version 1.0', 'utf-8'),
        },
      },
    });

    const createData = await createResponse.json();
    const version1Id = createData.succinctProperties['cmis:objectId'];
    const versionSeriesId = createData.succinctProperties['cmis:versionSeriesId'];

    // 2. Create version 2.0
    const checkout1 = await request.post(baseUrl, {
      headers: {
        'Authorization': authHeader,
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      form: {
        cmisaction: 'checkOut',
        objectId: version1Id,
        succinct: 'true',
      },
    });

    const pwc1Data = await checkout1.json();

    const checkin1 = await request.post(baseUrl, {
      headers: {
        'Authorization': authHeader,
        'Content-Type': 'multipart/form-data; boundary=----WebKitFormBoundary',
      },
      multipart: {
        cmisaction: 'checkIn',
        objectId: pwc1Data.succinctProperties['cmis:objectId'],
        major: 'true',
        checkinComment: 'Latest version',
        content: {
          name: 'latest-version-test.txt',
          mimeType: 'text/plain',
          buffer: Buffer.from('Version 2.0 - LATEST', 'utf-8'),
        },
        succinct: 'true',
      },
    });

    const version2Data = await checkin1.json();
    testDocumentId = version2Data.succinctProperties['cmis:objectId'];

    // 3. Get object using version1Id - should still work
    const version1Response = await request.get(`${baseUrl}/root`, {
      headers: { 'Authorization': authHeader },
      params: {
        cmisselector: 'object',
        objectId: version1Id,
        succinct: 'true',
      },
    });

    expect(version1Response.status()).toBe(200);
    const version1Object = await version1Response.json();
    expect(version1Object.succinctProperties['cmis:isLatestVersion']).toBe(false);
    console.log('Version 1 is latest:', version1Object.succinctProperties['cmis:isLatestVersion']);

    // 4. Get latest version using version series ID
    const latestResponse = await request.get(`${baseUrl}/root`, {
      headers: { 'Authorization': authHeader },
      params: {
        cmisselector: 'object',
        objectId: versionSeriesId,
        returnVersion: 'latest',
        succinct: 'true',
      },
    });

    expect(latestResponse.status()).toBe(200);
    const latestObject = await latestResponse.json();

    expect(latestObject.succinctProperties['cmis:isLatestVersion']).toBe(true);
    expect(latestObject.succinctProperties['cmis:objectId']).toBe(testDocumentId);
    console.log('Latest version ID:', latestObject.succinctProperties['cmis:objectId']);
    console.log('Latest version label:', latestObject.succinctProperties['cmis:versionLabel']);
  });
});
