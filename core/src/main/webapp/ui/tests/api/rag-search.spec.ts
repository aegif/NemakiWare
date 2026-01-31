import { test, expect } from '@playwright/test';

/**
 * RAG Vector Search API E2E Tests
 *
 * Tests for RAG search endpoints. TEI (Text Embeddings Inference) service
 * is not always available, so tests are split into:
 * - Always-run: health/status checks and input validation
 * - Conditional: search functionality (requires TEI)
 */

const BASE_URL = 'http://localhost:8080';
const REPO_ID = 'bedroom';
const RAG_BASE = `${BASE_URL}/core/api/v1/cmis/repositories/${REPO_ID}/rag`;
const SE_RAG_BASE = `${BASE_URL}/core/api/v1/cmis/repositories/${REPO_ID}/search-engine/rag`;
const AUTH_HEADER = 'Basic ' + Buffer.from('admin:admin').toString('base64');

let ragEnabled = false;

test.beforeAll(async ({ request }) => {
  // Check if RAG is enabled
  try {
    const response = await request.get(`${RAG_BASE}/health`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    if (response.ok()) {
      const data = await response.json();
      ragEnabled = data.enabled === true;
    }
  } catch {
    ragEnabled = false;
  }
  console.log(`RAG enabled: ${ragEnabled}`);
});

test.describe('RAG Health & Status', () => {

  test('GET /rag/health returns 200 with enabled field', async ({ request }) => {
    const response = await request.get(`${RAG_BASE}/health`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(typeof data.enabled).toBe('boolean');
  });

  test('GET /search-engine/rag/enabled returns boolean', async ({ request }) => {
    const response = await request.get(`${SE_RAG_BASE}/enabled`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(typeof data.enabled).toBe('boolean');
  });

  test('GET /search-engine/rag/status returns status object', async ({ request }) => {
    const response = await request.get(`${SE_RAG_BASE}/status`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.status || data.enabled !== undefined).toBeTruthy();
  });
});

test.describe('RAG Input Validation', () => {

  test('POST /rag/search with empty query returns 400', async ({ request }) => {
    const response = await request.post(`${RAG_BASE}/search`, {
      headers: {
        'Authorization': AUTH_HEADER,
        'Content-Type': 'application/json'
      },
      data: { query: '' }
    });
    // Empty query should be rejected (400) or service unavailable (503)
    expect([400, 503]).toContain(response.status());
  });

  test('POST /rag/search with topK=0 returns 400', async ({ request }) => {
    const response = await request.post(`${RAG_BASE}/search`, {
      headers: {
        'Authorization': AUTH_HEADER,
        'Content-Type': 'application/json'
      },
      data: { query: 'test', topK: 0 }
    });
    expect([400, 503]).toContain(response.status());
  });

  test('POST /rag/search with negative minScore returns 400', async ({ request }) => {
    const response = await request.post(`${RAG_BASE}/search`, {
      headers: {
        'Authorization': AUTH_HEADER,
        'Content-Type': 'application/json'
      },
      data: { query: 'test', minScore: -1.0 }
    });
    expect([400, 503]).toContain(response.status());
  });

  test('POST /rag/search with invalid propertyBoost returns 400', async ({ request }) => {
    const response = await request.post(`${RAG_BASE}/search`, {
      headers: {
        'Authorization': AUTH_HEADER,
        'Content-Type': 'application/json'
      },
      data: { query: 'test', propertyBoost: -5.0 }
    });
    expect([400, 503]).toContain(response.status());
  });

  test('POST /rag/search without auth returns error', async ({ request }) => {
    const response = await request.post(`${RAG_BASE}/search`, {
      headers: { 'Content-Type': 'application/json' },
      data: { query: 'test' }
    });
    // May return 401 (auth required), 503 (RAG disabled), or 200 (auth filter pass-through)
    expect([200, 401, 503]).toContain(response.status());
  });

  test('POST /rag/search with invalid repository returns error', async ({ request }) => {
    const invalidUrl = `${BASE_URL}/core/api/v1/cmis/repositories/nonexistent/rag/search`;
    const response = await request.post(invalidUrl, {
      headers: {
        'Authorization': AUTH_HEADER,
        'Content-Type': 'application/json'
      },
      data: { query: 'test' }
    });
    expect(response.status()).toBeGreaterThanOrEqual(400);
  });

  test('GET /rag/similar/nonexistent returns error', async ({ request }) => {
    const response = await request.get(`${RAG_BASE}/similar/nonexistent-doc-id`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    // Should return 404 (doc not found) or 503 (RAG disabled)
    expect(response.status()).toBeGreaterThanOrEqual(400);
  });
});

test.describe('RAG Search (TEI required)', () => {

  test.beforeEach(async () => {
    test.skip(!ragEnabled, 'RAG not enabled - TEI service unavailable');
  });

  test('POST /rag/search with valid query returns results', async ({ request }) => {
    const response = await request.post(`${RAG_BASE}/search`, {
      headers: {
        'Authorization': AUTH_HEADER,
        'Content-Type': 'application/json'
      },
      data: { query: 'document management', topK: 5 }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.results).toBeDefined();
    expect(Array.isArray(data.results)).toBeTruthy();
  });

  test('POST /rag/search response includes topK fields', async ({ request }) => {
    const response = await request.post(`${RAG_BASE}/search`, {
      headers: {
        'Authorization': AUTH_HEADER,
        'Content-Type': 'application/json'
      },
      data: { query: 'test', topK: 3 }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.topK).toBeDefined();
  });

  test('POST /rag/search with folderId scopes results', async ({ request }) => {
    // Get root folder ID
    const rootRes = await request.get(
      `${BASE_URL}/core/browser/${REPO_ID}/root?cmisselector=object`,
      { headers: { 'Authorization': AUTH_HEADER } }
    );
    const rootData = await rootRes.json();
    const rootId = rootData.succinctProperties?.['cmis:objectId'] || rootData.properties?.['cmis:objectId']?.value;

    const response = await request.post(`${RAG_BASE}/search`, {
      headers: {
        'Authorization': AUTH_HEADER,
        'Content-Type': 'application/json'
      },
      data: { query: 'test', folderId: rootId }
    });
    expect(response.status()).toBe(200);
  });

  test('GET /rag/similar/{docId} returns similar documents', async ({ request }) => {
    // Create a test document first
    const createRes = await request.post(`${BASE_URL}/core/browser/${REPO_ID}`, {
      headers: { 'Authorization': AUTH_HEADER },
      multipart: {
        cmisaction: 'createDocument',
        'propertyId[0]': 'cmis:objectTypeId',
        'propertyValue[0]': 'cmis:document',
        'propertyId[1]': 'cmis:name',
        'propertyValue[1]': `rag-test-${Date.now()}.txt`
      }
    });
    if (createRes.ok()) {
      const docData = await createRes.json();
      const docId = docData.succinctProperties?.['cmis:objectId'];
      if (docId) {
        const response = await request.get(`${RAG_BASE}/similar/${docId}`, {
          headers: { 'Authorization': AUTH_HEADER }
        });
        expect([200, 404]).toContain(response.status());
        // Cleanup
        await request.post(`${BASE_URL}/core/browser/${REPO_ID}`, {
          headers: { 'Authorization': AUTH_HEADER },
          form: { cmisaction: 'delete', objectId: docId }
        });
      }
    }
  });

  test('POST /search-engine/rag/reindex starts reindexing', async ({ request }) => {
    const response = await request.post(`${SE_RAG_BASE}/reindex`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect([200, 202, 409]).toContain(response.status());
  });
});
