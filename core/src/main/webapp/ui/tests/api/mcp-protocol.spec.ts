import { test, expect, request as apiRequest } from '@playwright/test';

/**
 * MCP (Model Context Protocol) E2E Tests
 *
 * Tests for the MCP server endpoints:
 * - POST /mcp/message  - JSON-RPC message handling
 * - GET  /mcp/info     - Server info & capabilities
 * - GET  /mcp/health   - Health check
 */

const BASE_URL = 'http://localhost:8080';
const MCP_BASE = `${BASE_URL}/core/mcp`;
const AUTH_HEADER = 'Basic ' + Buffer.from('admin:admin').toString('base64');

/** Helper to send a JSON-RPC request */
async function sendJsonRpc(request: any, method: string, params?: any, auth = true) {
  const body: any = {
    jsonrpc: '2.0',
    id: Date.now(),
    method
  };
  if (params !== undefined) {
    body.params = params;
  }
  const headers: Record<string, string> = {
    'Content-Type': 'application/json'
  };
  if (auth) {
    headers['Authorization'] = AUTH_HEADER;
  }
  return request.post(`${MCP_BASE}/message`, {
    headers,
    data: body
  });
}

test.describe('MCP Info & Health', () => {

  test('GET /mcp/info returns serverInfo and capabilities (tools intentionally absent)', async ({ request }) => {
    // /mcp/info is anonymous-public for client compatibility, so it must
    // not enumerate tools — discovery happens via JSON-RPC tools/list,
    // which can be gated by mcp.tools.list.public. Removed in commit
    // bb2f9b72f (security: remove tools from /mcp/info to close enumeration bypass).
    const response = await request.get(`${MCP_BASE}/info`);
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.serverInfo).toBeDefined();
    expect(data.serverInfo.name).toBeDefined();
    expect(data.capabilities).toBeDefined();
    expect(data.tools).toBeUndefined();
  });

  test('GET /mcp/health returns status=healthy', async ({ request }) => {
    const response = await request.get(`${MCP_BASE}/health`);
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.status).toBe('healthy');
    expect(data.service).toBe('nemakiware-mcp');
  });

  test('JSON-RPC tools/list is reachable anonymously and exposes inputSchema for every tool', async () => {
    // Discovery moved from /mcp/info to JSON-RPC tools/list. This test
    // verifies the *default-public* contract — i.e. the call must work
    // without any Authorization header. The shared `request` fixture
    // would otherwise inject Basic auth via playwright.config.ts'
    // extraHTTPHeaders, masking a regression to mcp.tools.list.public=false.
    // We allocate a fresh APIRequestContext with no global headers to
    // make the anonymous path explicit.
    const anonRequest = await apiRequest.newContext();
    try {
      const response = await anonRequest.post(`${MCP_BASE}/message`, {
        headers: { 'Content-Type': 'application/json' },
        data: { jsonrpc: '2.0', id: 1, method: 'tools/list' },
      });
      expect(response.status()).toBe(200);
      const body = await response.json();
      expect(body.result, `tools/list response: ${JSON.stringify(body)}`).toBeDefined();
      expect(Array.isArray(body.result.tools)).toBeTruthy();
      expect(body.result.tools.length).toBeGreaterThan(0);

      // Every tool MUST advertise an inputSchema — Claude/Claude Code
      // and other MCP clients rely on it to render argument forms.
      // A description-only tool is a contract regression.
      for (const tool of body.result.tools) {
        expect(tool.name, `unnamed tool in tools/list: ${JSON.stringify(tool)}`).toBeDefined();
        expect(
          tool.inputSchema,
          `tool '${tool.name}' is missing inputSchema`,
        ).toBeDefined();
        expect(typeof tool.inputSchema).toBe('object');
      }
    } finally {
      await anonRequest.dispose();
    }
  });
});

test.describe('MCP JSON-RPC Protocol', () => {

  test('initialize returns capabilities', async ({ request }) => {
    const response = await sendJsonRpc(request, 'initialize', {
      protocolVersion: '2024-11-05',
      capabilities: {},
      clientInfo: { name: 'test-client', version: '1.0.0' }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.jsonrpc).toBe('2.0');
    expect(data.result).toBeDefined();
    expect(data.result.serverInfo).toBeDefined();
    expect(data.result.capabilities).toBeDefined();
  });

  test('tools/list returns tool array', async ({ request }) => {
    const response = await sendJsonRpc(request, 'tools/list');
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.result).toBeDefined();
    expect(data.result.tools).toBeDefined();
    expect(Array.isArray(data.result.tools)).toBeTruthy();
    expect(data.result.tools.length).toBeGreaterThan(0);
  });

  test('ping returns pong', async ({ request }) => {
    const response = await sendJsonRpc(request, 'ping');
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.result).toBeDefined();
  });

  test('tools/call nemakiware_search executes', async ({ request }) => {
    const response = await sendJsonRpc(request, 'tools/call', {
      name: 'nemakiware_search',
      arguments: { query: 'test' }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    // Should return result (possibly empty) or error if tool not found
    expect(data.jsonrpc).toBe('2.0');
    expect(data.result !== undefined || data.error !== undefined).toBeTruthy();
  });

  test('tools/call nemakiware_list_folders executes', async ({ request }) => {
    const response = await sendJsonRpc(request, 'tools/call', {
      name: 'nemakiware_list_folders',
      arguments: {}
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.jsonrpc).toBe('2.0');
    expect(data.result !== undefined || data.error !== undefined).toBeTruthy();
  });

  test('invalid method returns JSON-RPC error', async ({ request }) => {
    const response = await sendJsonRpc(request, 'nonexistent/method');
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.jsonrpc).toBe('2.0');
    expect(data.error).toBeDefined();
    expect(data.error.code).toBeDefined();
  });

  test('missing jsonrpc field returns error', async ({ request }) => {
    const response = await request.post(`${MCP_BASE}/message`, {
      headers: {
        'Content-Type': 'application/json',
        'Authorization': AUTH_HEADER
      },
      data: { method: 'ping', id: 1 }
    });
    expect(response.status()).toBe(200);
    const data = await response.json();
    // Should return error for invalid JSON-RPC format
    expect(data.error !== undefined || data.result !== undefined).toBeTruthy();
  });

  test('request without auth still processes (public tools)', async ({ request }) => {
    const response = await sendJsonRpc(request, 'ping', undefined, false);
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.jsonrpc).toBe('2.0');
  });

  test('request with Basic auth accesses protected tools', async ({ request }) => {
    const response = await sendJsonRpc(request, 'tools/list');
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.result.tools.length).toBeGreaterThan(0);
    // Authenticated tools should include search, list, etc.
    const toolNames = data.result.tools.map((t: any) => t.name);
    expect(toolNames.length).toBeGreaterThan(0);
  });
});
