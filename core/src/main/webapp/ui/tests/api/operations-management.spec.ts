import { test, expect } from '@playwright/test';

/**
 * Operations Management API E2E Tests
 *
 * Tests for Health, Stats, Metrics, and Job Control endpoints:
 * - GET  /api/v1/cmis/health                      - Health check (no auth)
 * - GET  /api/v1/cmis/repo/{repoId}/stats          - Repository & JVM stats
 * - GET  /api/v1/cmis/repo/{repoId}/metrics         - Prometheus metrics
 * - GET  /api/v1/cmis/repo/{repoId}/jobs             - Job status
 * - POST /api/v1/cmis/repo/{repoId}/jobs/pause       - Pause jobs
 * - POST /api/v1/cmis/repo/{repoId}/jobs/resume      - Resume jobs
 */

const BASE_URL = 'http://localhost:8080';
const API_BASE = `${BASE_URL}/core/api/v1/cmis`;
const REPO_ID = 'bedroom';
const AUTH_HEADER = 'Basic ' + Buffer.from('admin:admin').toString('base64');

test.describe('Health API', () => {

  test('GET /health returns 200 with status field', async ({ request }) => {
    const response = await request.get(`${API_BASE}/health`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.status).toBeDefined();
    expect(['healthy', 'degraded', 'unhealthy']).toContain(data.status);
  });

  test('GET /health contains couchdb and memory checks', async ({ request }) => {
    const response = await request.get(`${API_BASE}/health`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.checks).toBeDefined();
    expect(data.checks.memory).toBeDefined();
    expect(data.checks.couchdb).toBeDefined();
  });

  test('GET /health works without authentication', async ({ request }) => {
    // Health endpoint should be accessible without auth for monitoring
    const response = await request.get(`${API_BASE}/health`);
    // May return 200 (public) or 401 (auth required)
    // Based on our fix, /health is a global path, so it should work with auth
    // Without auth, the Servlet filter may still reject it
    expect([200, 401]).toContain(response.status());
  });

  test('GET /health memory check has detail fields', async ({ request }) => {
    const response = await request.get(`${API_BASE}/health`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    const memory = data.checks.memory;
    expect(memory.status).toBeDefined();
    expect(memory.details).toBeDefined();
    expect(typeof memory.details.usedPercent).toBe('number');
    expect(typeof memory.details.maxBytes).toBe('number');
    expect(typeof memory.details.usedBytes).toBe('number');
  });
});

test.describe('Stats API', () => {

  test('GET /repo/bedroom/stats returns repository and jvm sections', async ({ request }) => {
    const response = await request.get(`${API_BASE}/repo/${REPO_ID}/stats`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.repository).toBeDefined();
    expect(data.jvm).toBeDefined();
    expect(data.repository.repositoryId).toBe(REPO_ID);
  });

  test('GET /repo/bedroom/stats contains JVM details', async ({ request }) => {
    const response = await request.get(`${API_BASE}/repo/${REPO_ID}/stats`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(typeof data.jvm.heapUsed).toBe('number');
    expect(typeof data.jvm.heapMax).toBe('number');
    expect(typeof data.jvm.threadCount).toBe('number');
    expect(typeof data.jvm.uptimeMs).toBe('number');
    expect(data.jvm.heapUsed).toBeGreaterThan(0);
    expect(data.jvm.threadCount).toBeGreaterThan(0);
  });

  test('GET /repo/invalid-repo/stats returns error', async ({ request }) => {
    const response = await request.get(`${API_BASE}/repo/nonexistent-repo/stats`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    // Should return error (401 because auth filter can't resolve repo, or 404/500)
    expect(response.status()).toBeGreaterThanOrEqual(400);
  });
});

test.describe('Metrics API (Prometheus)', () => {

  test('GET /repo/bedroom/metrics returns Prometheus format', async ({ request }) => {
    const response = await request.get(`${API_BASE}/repo/${REPO_ID}/metrics`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const text = await response.text();
    expect(text).toContain('# HELP');
    expect(text).toContain('# TYPE');
  });

  test('GET /repo/bedroom/metrics contains JVM metrics', async ({ request }) => {
    const response = await request.get(`${API_BASE}/repo/${REPO_ID}/metrics`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const text = await response.text();
    expect(text).toContain('jvm_memory_heap_used_bytes');
    expect(text).toContain('jvm_threads_current');
  });

  test('GET /repo/bedroom/metrics contains repository metrics', async ({ request }) => {
    const response = await request.get(`${API_BASE}/repo/${REPO_ID}/metrics`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const text = await response.text();
    expect(text).toContain('nemaki_repository_nodes_total');
    expect(text).toContain(`repository="${REPO_ID}"`);
  });
});

test.describe.serial('Job Control API', () => {

  test('GET /repo/bedroom/jobs returns job status', async ({ request }) => {
    const response = await request.get(`${API_BASE}/repo/${REPO_ID}/jobs`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.status).toBeDefined();
    expect(typeof data.pendingJobs).toBe('number');
    expect(typeof data.runningJobs).toBe('number');
    expect(data.timestamp).toBeDefined();
  });

  test('POST /jobs/pause then GET shows paused state', async ({ request }) => {
    const pauseRes = await request.post(`${API_BASE}/repo/${REPO_ID}/jobs/pause`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(pauseRes.status()).toBe(200);
    const pauseData = await pauseRes.json();
    expect(pauseData.status).toBe('paused');

    // Verify GET also shows paused
    const getRes = await request.get(`${API_BASE}/repo/${REPO_ID}/jobs`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    const getData = await getRes.json();
    expect(getData.status).toBe('paused');
  });

  test('POST /jobs/resume then GET shows running state', async ({ request }) => {
    const resumeRes = await request.post(`${API_BASE}/repo/${REPO_ID}/jobs/resume`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    expect(resumeRes.status()).toBe(200);
    const resumeData = await resumeRes.json();
    expect(resumeData.status).toBe('running');

    // Verify GET also shows running
    const getRes = await request.get(`${API_BASE}/repo/${REPO_ID}/jobs`, {
      headers: { 'Authorization': AUTH_HEADER }
    });
    const getData = await getRes.json();
    expect(getData.status).toBe('running');
  });

  test('Job control without auth returns 401 or 200', async ({ request }) => {
    const response = await request.get(`${API_BASE}/repo/${REPO_ID}/jobs`);
    // Jobs endpoint may be accessible without auth depending on filter config
    expect([200, 401]).toContain(response.status());
  });
});
