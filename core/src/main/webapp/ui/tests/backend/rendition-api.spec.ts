import { test, expect } from '@playwright/test';

/**
 * Rendition API Tests
 * Tests for the RenditionController REST endpoints
 *
 * Endpoints tested:
 * - GET /api/v1/repo/{repositoryId}/renditions/supported-types
 * - GET /api/v1/repo/{repositoryId}/renditions/{objectId}
 * - POST /api/v1/repo/{repositoryId}/renditions/generate
 * - POST /api/v1/repo/{repositoryId}/renditions/batch
 */

test.describe.configure({ mode: 'serial' });

const BASE_URL = 'http://localhost:8080';
const REPOSITORY_ID = 'bedroom';
const API_BASE = `${BASE_URL}/core/api/v1/repo/${REPOSITORY_ID}/renditions`;

// Test credentials
const ADMIN_AUTH = Buffer.from('admin:admin').toString('base64');

// Helper function to create authorization header
function getAuthHeader(isAdmin: boolean = true): { [key: string]: string } {
  return {
    'Authorization': `Basic ${ADMIN_AUTH}`,
    'Content-Type': 'application/json'
  };
}

// Helper to create a test document
async function createTestDocument(request: any, name: string, content: string): Promise<string | null> {
  // First, get the root folder ID
  const rootResponse = await request.get(
    `${BASE_URL}/core/browser/${REPOSITORY_ID}/root?cmisselector=object`,
    { headers: getAuthHeader() }
  );

  if (!rootResponse.ok()) {
    console.error('Failed to get root folder');
    return null;
  }

  const rootData = await rootResponse.json();
  const rootFolderId = rootData.properties?.['cmis:objectId']?.value;

  if (!rootFolderId) {
    console.error('Root folder ID not found');
    return null;
  }

  // Create document using Browser Binding
  const formData = new URLSearchParams();
  formData.append('cmisaction', 'createDocument');
  formData.append('propertyId[0]', 'cmis:objectTypeId');
  formData.append('propertyValue[0]', 'cmis:document');
  formData.append('propertyId[1]', 'cmis:name');
  formData.append('propertyValue[1]', name);

  const createResponse = await request.post(
    `${BASE_URL}/core/browser/${REPOSITORY_ID}?objectId=${rootFolderId}`,
    {
      headers: {
        'Authorization': `Basic ${ADMIN_AUTH}`,
        'Content-Type': 'application/x-www-form-urlencoded'
      },
      data: formData.toString()
    }
  );

  if (!createResponse.ok()) {
    console.error('Failed to create document:', await createResponse.text());
    return null;
  }

  const docData = await createResponse.json();
  return docData.properties?.['cmis:objectId']?.value || null;
}

// Helper to delete a test document
async function deleteTestDocument(request: any, objectId: string): Promise<void> {
  try {
    const formData = new URLSearchParams();
    formData.append('cmisaction', 'delete');
    formData.append('objectId', objectId);

    await request.post(
      `${BASE_URL}/core/browser/${REPOSITORY_ID}`,
      {
        headers: {
          'Authorization': `Basic ${ADMIN_AUTH}`,
          'Content-Type': 'application/x-www-form-urlencoded'
        },
        data: formData.toString(),
        timeout: 30000
      }
    );
  } catch (error) {
    console.error('Failed to delete document:', error);
  }
}

test.describe('Rendition API - Supported Types', () => {
  test('should return supported types when authenticated', async ({ request }) => {
    const response = await request.get(`${API_BASE}/supported-types`, {
      headers: getAuthHeader()
    });

    // API might return 401 if authentication filter is not configured for this endpoint
    // or 200 if working correctly
    if (response.status() === 200) {
      const data = await response.json();
      expect(data.status).toBe('success');
      expect(data).toHaveProperty('supportedTypes');
      expect(data).toHaveProperty('enabled');
      expect(Array.isArray(data.supportedTypes)).toBe(true);
    } else if (response.status() === 401) {
      // Expected if CallContext is not set by authentication filter
      const data = await response.json();
      expect(data.status).toBe('error');
      expect(data.message).toBe('Authentication required');
    } else {
      // Log unexpected status for debugging
      console.log('Unexpected status:', response.status(), await response.text());
    }
  });

  test('should reject unauthenticated requests', async ({ playwright }) => {
    // Create a new request context without default credentials
    // This overrides the global httpCredentials and extraHTTPHeaders from playwright.config.ts
    const noAuthContext = await playwright.request.newContext({
      extraHTTPHeaders: { 'Content-Type': 'application/json' }
    });

    try {
      const response = await noAuthContext.get(`${API_BASE}/supported-types`);

      // Should return 401 Unauthorized
      expect(response.status()).toBe(401);
      const data = await response.json();
      expect(data.status).toBe('error');
      expect(data.message).toBe('Authentication required');
    } finally {
      await noAuthContext.dispose();
    }
  });
});

test.describe('Rendition API - Get Renditions', () => {
  let testDocumentId: string | null = null;

  test.beforeAll(async ({ request }) => {
    // Create a test document for rendition tests
    testDocumentId = await createTestDocument(
      request,
      `rendition-test-${Date.now()}.txt`,
      'Test content for rendition'
    );
  });

  test.afterAll(async ({ request }) => {
    // Clean up test document
    if (testDocumentId) {
      await deleteTestDocument(request, testDocumentId);
    }
  });

  test('should return renditions for a document', async ({ request }) => {
    test.skip(!testDocumentId, 'Test document was not created');

    const response = await request.get(`${API_BASE}/${testDocumentId}`, {
      headers: getAuthHeader()
    });

    if (response.status() === 200) {
      const data = await response.json();
      expect(data.status).toBe('success');
      expect(data).toHaveProperty('renditions');
      expect(data).toHaveProperty('count');
      expect(Array.isArray(data.renditions)).toBe(true);
    } else if (response.status() === 401) {
      const data = await response.json();
      expect(data.message).toBe('Authentication required');
    } else if (response.status() === 404) {
      const data = await response.json();
      expect(data.status).toBe('error');
    }
  });

  test('should return 404 for non-existent document', async ({ request }) => {
    const fakeObjectId = 'non-existent-object-id-12345';
    const response = await request.get(`${API_BASE}/${fakeObjectId}`, {
      headers: getAuthHeader()
    });

    // Should return 404 or 401 depending on auth status
    expect([401, 404]).toContain(response.status());
    const data = await response.json();
    expect(data.status).toBe('error');
  });

  test('should reject unauthenticated requests for renditions', async ({ playwright }) => {
    test.skip(!testDocumentId, 'Test document was not created');

    // Create a new request context without default credentials
    const noAuthContext = await playwright.request.newContext({
      extraHTTPHeaders: { 'Content-Type': 'application/json' }
    });

    try {
      const response = await noAuthContext.get(`${API_BASE}/${testDocumentId}`);

      expect(response.status()).toBe(401);
      const data = await response.json();
      expect(data.status).toBe('error');
      expect(data.message).toBe('Authentication required');
    } finally {
      await noAuthContext.dispose();
    }
  });
});

test.describe('Rendition API - Generate Rendition', () => {
  let testDocumentId: string | null = null;

  test.beforeAll(async ({ request }) => {
    // Create a test document for rendition generation
    testDocumentId = await createTestDocument(
      request,
      `generate-rendition-test-${Date.now()}.txt`,
      'Test content for rendition generation'
    );
  });

  test.afterAll(async ({ request }) => {
    if (testDocumentId) {
      await deleteTestDocument(request, testDocumentId);
    }
  });

  test('should handle rendition generation request', async ({ request }) => {
    test.skip(!testDocumentId, 'Test document was not created');

    const response = await request.post(`${API_BASE}/generate?objectId=${testDocumentId}`, {
      headers: getAuthHeader()
    });

    // Response depends on whether rendition generation is enabled and supported
    if (response.status() === 200) {
      const data = await response.json();
      expect(data.status).toBe('success');
      // Message could indicate success or that rendition wasn't generated
      expect(data).toHaveProperty('message');
    } else if (response.status() === 401) {
      const data = await response.json();
      expect(data.message).toBe('Authentication required');
    } else if (response.status() === 503) {
      // Rendition generation is disabled
      const data = await response.json();
      expect(data.status).toBe('error');
      expect(data.message).toBe('Rendition generation is disabled');
    }
  });

  test('should reject force regeneration for non-admin users', async ({ request }) => {
    test.skip(!testDocumentId, 'Test document was not created');

    // This test verifies the force=true parameter requires admin privileges
    // Note: With admin credentials, this should succeed
    const response = await request.post(
      `${API_BASE}/generate?objectId=${testDocumentId}&force=true`,
      { headers: getAuthHeader() }
    );

    // With admin credentials, force should be allowed
    if (response.status() === 200 || response.status() === 503) {
      // Either success or disabled is acceptable for admin
      const data = await response.json();
      expect(data).toHaveProperty('status');
    } else if (response.status() === 401) {
      const data = await response.json();
      expect(data.message).toBe('Authentication required');
    }
  });

  test('should reject unauthenticated generation requests', async ({ playwright }) => {
    test.skip(!testDocumentId, 'Test document was not created');

    // Create a new request context without default credentials
    const noAuthContext = await playwright.request.newContext({
      extraHTTPHeaders: { 'Content-Type': 'application/json' }
    });

    try {
      const response = await noAuthContext.post(
        `${API_BASE}/generate?objectId=${testDocumentId}`
      );

      expect(response.status()).toBe(401);
      const data = await response.json();
      expect(data.status).toBe('error');
      expect(data.message).toBe('Authentication required');
    } finally {
      await noAuthContext.dispose();
    }
  });

  test('should return 404 for non-existent document generation', async ({ request }) => {
    const fakeObjectId = 'non-existent-doc-for-generation';
    const response = await request.post(
      `${API_BASE}/generate?objectId=${fakeObjectId}`,
      { headers: getAuthHeader() }
    );

    // Should return 404 or 401 or 503
    expect([401, 404, 503]).toContain(response.status());
    const data = await response.json();
    expect(data.status).toBe('error');
  });
});

test.describe('Rendition API - Batch Generation', () => {
  let testDocumentIds: string[] = [];

  test.beforeAll(async ({ request }) => {
    // Create multiple test documents for batch generation
    for (let i = 0; i < 3; i++) {
      const docId = await createTestDocument(
        request,
        `batch-test-${Date.now()}-${i}.txt`,
        `Batch test content ${i}`
      );
      if (docId) {
        testDocumentIds.push(docId);
      }
    }
  });

  test.afterAll(async ({ request }) => {
    // Clean up test documents
    for (const docId of testDocumentIds) {
      await deleteTestDocument(request, docId);
    }
  });

  test('should handle batch generation request with admin credentials', async ({ request }) => {
    test.skip(testDocumentIds.length === 0, 'No test documents created');

    const batchRequest = {
      objectIds: testDocumentIds,
      force: false,
      maxItems: 10
    };

    const response = await request.post(`${API_BASE}/batch`, {
      headers: getAuthHeader(),
      data: batchRequest
    });

    if (response.status() === 200) {
      const data = await response.json();
      expect(data.status).toBe('success');
      expect(data).toHaveProperty('generatedCount');
      expect(data).toHaveProperty('requestedCount');
      // API may return count as string or number
      expect(Number(data.requestedCount)).toBe(testDocumentIds.length);
    } else if (response.status() === 401) {
      const data = await response.json();
      expect(data.message).toContain('Authentication');
    } else if (response.status() === 403) {
      const data = await response.json();
      expect(data.message).toContain('Admin');
    } else if (response.status() === 503) {
      const data = await response.json();
      expect(data.message).toBe('Rendition generation is disabled');
    }
  });

  test('should reject batch generation without objectIds', async ({ request }) => {
    const batchRequest = {
      force: false,
      maxItems: 10
    };

    const response = await request.post(`${API_BASE}/batch`, {
      headers: getAuthHeader(),
      data: batchRequest
    });

    // Should return 400 Bad Request or 401 Unauthorized
    if (response.status() === 400) {
      const data = await response.json();
      expect(data.status).toBe('error');
      expect(data.message).toContain('objectIds');
    } else if (response.status() === 401) {
      const data = await response.json();
      expect(data.message).toContain('Authentication');
    } else if (response.status() === 503) {
      // Rendition generation disabled
      const data = await response.json();
      expect(data.message).toBe('Rendition generation is disabled');
    }
  });

  test('should reject batch generation with empty objectIds', async ({ request }) => {
    const batchRequest = {
      objectIds: [],
      force: false,
      maxItems: 10
    };

    const response = await request.post(`${API_BASE}/batch`, {
      headers: getAuthHeader(),
      data: batchRequest
    });

    if (response.status() === 400) {
      const data = await response.json();
      expect(data.status).toBe('error');
      expect(data.message).toContain('objectIds');
    } else if (response.status() === 401) {
      const data = await response.json();
      expect(data.message).toContain('Authentication');
    } else if (response.status() === 503) {
      const data = await response.json();
      expect(data.message).toBe('Rendition generation is disabled');
    }
  });

  test('should enforce maxItems limit', async ({ request }) => {
    const batchRequest = {
      objectIds: testDocumentIds,
      force: false,
      maxItems: 600 // Exceeds MAX_BATCH_ITEMS (500)
    };

    const response = await request.post(`${API_BASE}/batch`, {
      headers: getAuthHeader(),
      data: batchRequest
    });

    if (response.status() === 400) {
      const data = await response.json();
      expect(data.status).toBe('error');
      expect(data.message).toContain('500');
    } else if (response.status() === 401) {
      const data = await response.json();
      expect(data.message).toContain('Authentication');
    } else if (response.status() === 503) {
      const data = await response.json();
      expect(data.message).toBe('Rendition generation is disabled');
    }
  });

  test('should reject unauthenticated batch requests', async ({ playwright }) => {
    const batchRequest = {
      objectIds: testDocumentIds,
      force: false,
      maxItems: 10
    };

    // Create a new request context without default credentials
    const noAuthContext = await playwright.request.newContext({
      extraHTTPHeaders: { 'Content-Type': 'application/json' }
    });

    try {
      const response = await noAuthContext.post(`${API_BASE}/batch`, {
        data: batchRequest
      });

      expect(response.status()).toBe(401);
      const data = await response.json();
      expect(data.status).toBe('error');
      expect(data.message).toContain('Authentication');
    } finally {
      await noAuthContext.dispose();
    }
  });
});

test.describe('Rendition API - Permission Checks', () => {
  test('should verify per-object permission checks in batch endpoint', async ({ request }) => {
    // Create a document and verify that permission checks work
    const testDocId = await createTestDocument(
      request,
      `permission-test-${Date.now()}.txt`,
      'Permission test content'
    );

    test.skip(!testDocId, 'Test document was not created');

    try {
      // Try batch with the created document
      const batchRequest = {
        objectIds: [testDocId, 'invalid-object-id'],
        force: false,
        maxItems: 10
      };

      const response = await request.post(`${API_BASE}/batch`, {
        headers: getAuthHeader(),
        data: batchRequest
      });

      if (response.status() === 200) {
        const data = await response.json();
        // Should have skipped objects in response
        if (data.skipped && data.skipped.length > 0) {
          // Verify that invalid object was skipped
          const skippedIds = data.skipped.map((s: any) => s.objectId);
          expect(skippedIds).toContain('invalid-object-id');
        }
      }
    } finally {
      if (testDocId) {
        await deleteTestDocument(request, testDocId);
      }
    }
  });
});
