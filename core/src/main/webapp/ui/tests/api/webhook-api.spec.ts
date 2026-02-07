/**
 * Webhook API E2E Tests
 *
 * Verifies the Webhook REST API functionality:
 * - Test webhook endpoint (ping external URLs)
 * - Delivery logs retrieval
 * - Webhook configuration management
 * - Retry functionality
 *
 * Note: NemakiWare REST API returns:
 * - status: "success" or "failure" (string, not boolean)
 * - error: [...] array for error details (not errMsg)
 *
 * External webhook tests may fail due to SSRF protection blocking certain hosts.
 */

import { test, expect } from '@playwright/test';
import { generateTestId } from '../utils/test-helper';

const REPOSITORY_ID = 'bedroom';
const BASE_URL = 'http://localhost:8080/core';
const AUTH_HEADER = 'Basic ' + Buffer.from('admin:admin').toString('base64');

// Test URLs for webhook testing
// Note: SSRF protection may block some external URLs depending on DNS resolution
const HTTPBIN_POST_URL = 'https://httpbin.org/post';
const HTTPBIN_STATUS_200 = 'https://httpbin.org/status/200';
const HTTPBIN_STATUS_500 = 'https://httpbin.org/status/500';
const INVALID_URL = 'https://invalid.nonexistent.domain.test/webhook';

/**
 * Helper: Make REST API request
 */
async function restGet(request: any, path: string, authHeader: string = AUTH_HEADER) {
  return request.get(`${BASE_URL}/rest/repo/${REPOSITORY_ID}${path}`, {
    headers: { 'Authorization': authHeader },
  });
}

async function restPost(request: any, path: string, body: any, authHeader: string = AUTH_HEADER) {
  return request.post(`${BASE_URL}/rest/repo/${REPOSITORY_ID}${path}`, {
    headers: {
      'Authorization': authHeader,
      'Content-Type': 'application/json',
    },
    data: body,
  });
}

/**
 * Helper: Create a document via CMIS Browser Binding
 */
async function createTestDocument(request: any, name: string): Promise<string> {
  // Get root folder ID
  const rootRes = await request.get(
    `${BASE_URL}/browser/${REPOSITORY_ID}/root?cmisselector=object`,
    { headers: { 'Authorization': AUTH_HEADER } }
  );
  expect(rootRes.ok()).toBeTruthy();
  const rootData = await rootRes.json();
  const rootId = rootData.succinctProperties?.['cmis:objectId'] || rootData.properties?.['cmis:objectId']?.value;

  // Create document
  const createRes = await request.post(
    `${BASE_URL}/browser/${REPOSITORY_ID}?objectId=${rootId}`,
    {
      headers: { 'Authorization': AUTH_HEADER, 'Content-Type': 'application/x-www-form-urlencoded' },
      form: {
        cmisaction: 'createDocument',
        folderId: rootId,
        'propertyId[0]': 'cmis:objectTypeId',
        'propertyValue[0]': 'cmis:document',
        'propertyId[1]': 'cmis:name',
        'propertyValue[1]': name,
      },
    }
  );
  expect(createRes.ok()).toBeTruthy();
  const data = await createRes.json();
  return data.succinctProperties?.['cmis:objectId'] || data.properties?.['cmis:objectId']?.value;
}

/**
 * Helper: Delete object via CMIS
 */
async function deleteObject(request: any, objectId: string) {
  const res = await request.post(
    `${BASE_URL}/browser/${REPOSITORY_ID}?objectId=${objectId}`,
    {
      headers: { 'Authorization': AUTH_HEADER, 'Content-Type': 'application/x-www-form-urlencoded' },
      form: { cmisaction: 'delete', allVersions: 'true' },
    }
  );
  return res;
}

test.describe('Webhook API Tests', () => {

  test.describe('Webhook Test Endpoint', () => {

    test('W1: Test webhook API returns proper structure', async ({ request }) => {
      // Test with invalid URL to verify API structure (external URLs may be blocked by SSRF protection)
      const res = await restPost(request, '/webhook/test', {
        url: INVALID_URL,
      });

      expect(res.ok()).toBeTruthy();
      const data = await res.json();
      // API should return success status (API call succeeded)
      expect(data.status).toBe('success');
      // Webhook delivery itself should have success field
      expect(typeof data.success).toBe('boolean');
      // Status code should be present
      expect(typeof data.statusCode).toBe('number');
      // Response time should be present
      expect(typeof data.responseTime).toBe('number');
    });

    test('W2: Test webhook with invalid URL returns webhook failure', async ({ request }) => {
      const res = await restPost(request, '/webhook/test', {
        url: INVALID_URL,
      });

      expect(res.ok()).toBeTruthy();
      const data = await res.json();
      expect(data.status).toBe('success'); // API call succeeded
      expect(data.success).toBe(false); // Webhook delivery failed (unresolvable host)
    });

    test('W3: Test webhook with external URL (may fail due to SSRF protection)', async ({ request }) => {
      // This test documents the SSRF protection behavior
      // External URLs may be blocked depending on DNS resolution
      const res = await restPost(request, '/webhook/test', {
        url: HTTPBIN_STATUS_200,
      });

      expect(res.ok()).toBeTruthy();
      const data = await res.json();
      expect(data.status).toBe('success'); // API call succeeded

      // The webhook may succeed or fail depending on SSRF protection
      // Just verify the response structure is correct
      expect(typeof data.success).toBe('boolean');
      expect(typeof data.statusCode).toBe('number');
    });

    test('W4: Test webhook without URL returns validation error', async ({ request }) => {
      const res = await restPost(request, '/webhook/test', {});

      expect(res.ok()).toBeTruthy();
      const data = await res.json();
      expect(data.status).toBe('failure');
      expect(Array.isArray(data.error)).toBeTruthy();
    });

    test('W5: Test webhook with secret generates proper response', async ({ request }) => {
      const testSecret = 'test-secret-key-12345';
      const res = await restPost(request, '/webhook/test', {
        url: INVALID_URL, // Use invalid URL to avoid SSRF issues
        secret: testSecret,
      });

      expect(res.ok()).toBeTruthy();
      const data = await res.json();
      expect(data.status).toBe('success'); // API call structure is correct
      // The actual webhook will fail but the API processed the request correctly
    });
  });

  test.describe('Webhook Delivery Logs', () => {

    test('W6: Get delivery logs requires admin authentication', async ({ request }) => {
      // Non-admin user should be denied (covered in W15)
      // Admin user should succeed (covered in W7)
      // This test verifies the endpoint exists and works for admin
      const res = await restGet(request, '/webhook/deliveries?limit=1');
      expect(res.ok()).toBeTruthy();
      const data = await res.json();
      expect(data.status).toBe('success');
    });

    test('W7: Get delivery logs with admin returns list', async ({ request }) => {
      const res = await restGet(request, '/webhook/deliveries?limit=10');

      expect(res.ok()).toBeTruthy();
      const data = await res.json();
      expect(data.status).toBe('success');
      expect(Array.isArray(data.deliveries)).toBeTruthy();
    });

    test('W8: Get delivery logs respects limit parameter', async ({ request }) => {
      const res = await restGet(request, '/webhook/deliveries?limit=5');

      expect(res.ok()).toBeTruthy();
      const data = await res.json();
      expect(data.status).toBe('success');
      expect(data.deliveries.length).toBeLessThanOrEqual(5);
    });

    test('W9: Get delivery logs can filter by objectId', async ({ request }) => {
      const testObjectId = 'nonexistent-object-id';
      const res = await restGet(request, `/webhook/deliveries?objectId=${testObjectId}&limit=10`);

      expect(res.ok()).toBeTruthy();
      const data = await res.json();
      expect(data.status).toBe('success');
      expect(Array.isArray(data.deliveries)).toBeTruthy();
    });
  });

  test.describe('Webhook Configuration', () => {

    test('W10: Get webhook config requires authentication', async ({ request }) => {
      const testObjectId = 'test-object-id';
      const noAuthRes = await request.get(
        `${BASE_URL}/rest/repo/${REPOSITORY_ID}/webhook/config/${testObjectId}`,
        { headers: {} }
      );
      const noAuthData = await noAuthRes.json().catch(() => ({}));
      expect(noAuthRes.status() === 401 || noAuthData.status === 'failure').toBeTruthy();
    });

    test('W11: Get webhook config for non-existent object returns error', async ({ request }) => {
      const res = await restGet(request, '/webhook/config/nonexistent-object-12345');

      expect(res.ok()).toBeTruthy();
      const data = await res.json();
      expect(data.status).toBe('failure');
      expect(Array.isArray(data.error)).toBeTruthy();
    });

    test('W12: Get webhook config for existing object returns config list', async ({ request }) => {
      const uuid = generateTestId();
      const docId = await createTestDocument(request, `webhook-test-${uuid}.txt`);

      try {
        const res = await restGet(request, `/webhook/config/${docId}`);

        expect(res.ok()).toBeTruthy();
        const data = await res.json();
        expect(data.status).toBe('success');
        expect(data.objectId).toBe(docId);
        expect(Array.isArray(data.webhookConfigs)).toBeTruthy();
      } finally {
        await deleteObject(request, docId);
      }
    });
  });

  test.describe('Webhook Retry', () => {

    test('W13: Retry non-existent delivery returns error', async ({ request }) => {
      const res = await restPost(request, '/webhook/deliveries/nonexistent-delivery-id/retry', {});

      expect(res.ok()).toBeTruthy();
      const data = await res.json();
      expect(data.status).toBe('failure');
    });

    test('W14: Retry requires authentication', async ({ request }) => {
      const noAuthRes = await request.post(
        `${BASE_URL}/rest/repo/${REPOSITORY_ID}/webhook/deliveries/test-id/retry`,
        { headers: { 'Content-Type': 'application/json' }, data: {} }
      );
      const noAuthData = await noAuthRes.json().catch(() => ({}));
      expect(noAuthRes.status() === 401 || noAuthData.status === 'failure').toBeTruthy();
    });
  });

  test.describe('Webhook Security', () => {

    test('W15: Non-admin user cannot access delivery logs', async ({ request }) => {
      // Test user auth (api-e2e-testuser created by global setup)
      const testUserAuth = 'Basic ' + Buffer.from('api-e2e-testuser:test').toString('base64');

      const res = await request.get(
        `${BASE_URL}/rest/repo/${REPOSITORY_ID}/webhook/deliveries`,
        { headers: { 'Authorization': testUserAuth } }
      );

      // Should be denied - status will be 'failure'
      const data = await res.json().catch(() => ({}));
      expect(res.status() === 403 || data.status === 'failure').toBeTruthy();
    });

    test('W16: Non-admin user cannot test webhooks', async ({ request }) => {
      const testUserAuth = 'Basic ' + Buffer.from('api-e2e-testuser:test').toString('base64');

      const res = await request.post(
        `${BASE_URL}/rest/repo/${REPOSITORY_ID}/webhook/test`,
        {
          headers: { 'Authorization': testUserAuth, 'Content-Type': 'application/json' },
          data: { url: HTTPBIN_STATUS_200 },
        }
      );

      const data = await res.json().catch(() => ({}));
      expect(res.status() === 403 || data.status === 'failure').toBeTruthy();
    });

    test('W17: Non-admin user cannot access webhook config', async ({ request }) => {
      const testUserAuth = 'Basic ' + Buffer.from('api-e2e-testuser:test').toString('base64');
      const uuid = generateTestId();
      const docId = await createTestDocument(request, `webhook-sec-test-${uuid}.txt`);

      try {
        const res = await request.get(
          `${BASE_URL}/rest/repo/${REPOSITORY_ID}/webhook/config/${docId}`,
          { headers: { 'Authorization': testUserAuth } }
        );

        const data = await res.json().catch(() => ({}));
        expect(res.status() === 403 || data.status === 'failure').toBeTruthy();
      } finally {
        await deleteObject(request, docId);
      }
    });
  });
});
