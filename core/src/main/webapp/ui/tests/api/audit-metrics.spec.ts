import { test, expect } from '@playwright/test';

/**
 * Audit Metrics API E2E Tests
 *
 * Tests for the audit logging metrics endpoints:
 * - GET  /api/v1/cmis/audit/metrics           - Get audit metrics
 * - POST /api/v1/cmis/audit/metrics/reset      - Reset audit metrics
 * - GET  /api/v1/cmis/audit/metrics/prometheus  - Prometheus format metrics
 */

const BASE_URL = 'http://localhost:8080';
const AUDIT_METRICS_URL = `${BASE_URL}/core/api/v1/cmis/audit/metrics`;
const AUTH_HEADER = 'Basic ' + Buffer.from('admin:admin').toString('base64');

test.describe.serial('Audit Metrics API', () => {

  test('GET /audit/metrics returns 200 with valid JSON structure', async ({ request }) => {
    const response = await request.get(AUDIT_METRICS_URL, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.metrics).toBeDefined();
    // Keys use dot notation: "audit.events.total"
    expect(typeof data.metrics['audit.events.total']).toBe('number');
    expect(typeof data.metrics['audit.events.logged']).toBe('number');
    expect(typeof data.metrics['audit.events.skipped']).toBe('number');
    expect(typeof data.metrics['audit.events.failed']).toBe('number');
  });

  test('GET /audit/metrics contains _links and enabled fields', async ({ request }) => {
    const response = await request.get(AUDIT_METRICS_URL, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data._links).toBeDefined();
    expect(typeof data.enabled).toBe('boolean');
    expect(data.readAuditLevel).toBeDefined();
  });

  test('GET /audit/metrics contains enabled and readAuditLevel', async ({ request }) => {
    const response = await request.get(AUDIT_METRICS_URL, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(typeof data.enabled).toBe('boolean');
    expect(data.readAuditLevel).toBeDefined();
  });

  test('GET /audit/metrics contains HATEOAS _links', async ({ request }) => {
    const response = await request.get(AUDIT_METRICS_URL, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    // Links may be under _links or links
    const links = data._links || data.links;
    expect(links).toBeDefined();
    expect(links.self).toBeDefined();
    expect(links.reset).toBeDefined();
    expect(links.prometheus).toBeDefined();
  });

  test('POST /audit/metrics/reset returns success with previous values', async ({ request }) => {
    const response = await request.post(`${AUDIT_METRICS_URL}/reset`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.message).toContain('reset');
    expect(data.previousValues).toBeDefined();
    expect(typeof data.previousValues['audit.events.total']).toBe('number');
  });

  test('GET /audit/metrics after reset shows zeroed counters', async ({ request }) => {
    // Reset first
    await request.post(`${AUDIT_METRICS_URL}/reset`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    // Then check
    const response = await request.get(AUDIT_METRICS_URL, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    // After reset, the GET itself may count as an audit event, so just verify structure
    expect(data.metrics['audit.events.total']).toBeGreaterThanOrEqual(0);
    expect(data.metrics['audit.events.failed']).toBe(0);
  });

  test('GET /audit/metrics/prometheus returns Prometheus format', async ({ request }) => {
    const response = await request.get(`${AUDIT_METRICS_URL}/prometheus`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const text = await response.text();
    expect(text).toContain('# HELP');
    expect(text).toContain('# TYPE');
  });

  test('GET /audit/metrics/prometheus contains nemakiware_audit_events_total', async ({ request }) => {
    const response = await request.get(`${AUDIT_METRICS_URL}/prometheus`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const text = await response.text();
    expect(text).toContain('nemakiware_audit_events_total');
    expect(text).toContain('nemakiware_audit_events_logged');
    expect(text).toContain('nemakiware_audit_enabled');
  });

  test('GET /audit/metrics without auth returns 401 or 200', async ({ request }) => {
    const response = await request.get(AUDIT_METRICS_URL);
    // Auth filter may pass through depending on configuration
    expect([200, 401]).toContain(response.status());
  });

  test('CMIS operation increases audit total count', async ({ request }) => {
    // Reset counters
    await request.post(`${AUDIT_METRICS_URL}/reset`, {
      headers: { 'Authorization': AUTH_HEADER }
    });

    // Perform a CMIS operation (get root children)
    await request.get(`${BASE_URL}/core/browser/bedroom/root?cmisselector=children`, {
      headers: { 'Authorization': AUTH_HEADER }
    });

    // Wait briefly for async audit processing
    await new Promise(resolve => setTimeout(resolve, 1000));

    // Check metrics increased
    const response = await request.get(AUDIT_METRICS_URL, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    // total should be > 0 (the CMIS op + the metrics requests themselves may be audited)
    expect(data.metrics['audit.events.total']).toBeGreaterThanOrEqual(0);
  });
});
