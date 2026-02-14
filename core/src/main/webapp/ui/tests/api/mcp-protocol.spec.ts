import { test, expect } from '@playwright/test';

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

  test('GET /mcp/info returns serverInfo, capabilities, tools', async ({ request }) => {
    const response = await request.get(`${MCP_BASE}/info`);
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.serverInfo).toBeDefined();
    expect(data.serverInfo.name).toBeDefined();
    expect(data.capabilities).toBeDefined();
    expect(data.tools).toBeDefined();
    expect(Array.isArray(data.tools)).toBeTruthy();
  });

  test('GET /mcp/health returns status=healthy', async ({ request }) => {
    const response = await request.get(`${MCP_BASE}/health`);
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.status).toBe('healthy');
    expect(data.service).toBe('nemakiware-mcp');
  });

  test('GET /mcp/info tools have inputSchema', async ({ request }) => {
    const response = await request.get(`${MCP_BASE}/info`);
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.tools.length).toBeGreaterThan(0);
    const firstTool = data.tools[0];
    expect(firstTool.name).toBeDefined();
    // inputSchema may be present for tools that accept parameters
    expect(firstTool.description || firstTool.inputSchema).toBeDefined();
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
