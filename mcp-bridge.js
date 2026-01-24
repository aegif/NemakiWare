#!/usr/bin/env node
/**
 * NemakiWare MCP Bridge
 *
 * Stdio-to-HTTP bridge for Claude Desktop.
 * Converts stdin/stdout MCP messages to HTTP requests.
 */

const http = require('http');
const readline = require('readline');
const fs = require('fs');

// Debug log file
const DEBUG = process.env.MCP_DEBUG === 'true';
const LOG_FILE = '/tmp/mcp-bridge.log';

function log(message) {
  if (DEBUG) {
    const timestamp = new Date().toISOString();
    fs.appendFileSync(LOG_FILE, `${timestamp} ${message}\n`);
  }
}

log('=== MCP Bridge Started ===');

const MCP_URL = process.env.MCP_URL || 'http://localhost:8080/core/mcp/message';
const AUTH_HEADER = process.env.MCP_AUTH || 'Basic YWRtaW46YWRtaW4=';

log(`MCP_URL: ${MCP_URL}`);
log(`AUTH_HEADER: ${AUTH_HEADER ? 'set' : 'not set'}`);

// Parse URL
const url = new URL(MCP_URL);

const rl = readline.createInterface({
  input: process.stdin,
  output: process.stdout,
  terminal: false
});

function sendRequest(jsonRpcMessage) {
  return new Promise((resolve, reject) => {
    const postData = JSON.stringify(jsonRpcMessage);
    log(`Sending request: ${postData}`);

    const options = {
      hostname: url.hostname,
      port: url.port || 80,
      path: url.pathname,
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(postData),
        'Authorization': AUTH_HEADER
      }
    };

    const req = http.request(options, (res) => {
      let data = '';
      res.on('data', (chunk) => data += chunk);
      res.on('end', () => {
        log(`Response: ${data}`);
        try {
          resolve(JSON.parse(data));
        } catch (e) {
          log(`Parse error: ${e.message}`);
          reject(new Error(`Invalid JSON response: ${data}`));
        }
      });
    });

    req.on('error', (e) => {
      log(`Request error: ${e.message}`);
      reject(e);
    });

    req.write(postData);
    req.end();
  });
}

function writeResponse(response) {
  const output = JSON.stringify(response) + '\n';
  log(`Writing response: ${output.trim()}`);
  process.stdout.write(output);
}

function writeError(id, code, message) {
  writeResponse({
    jsonrpc: '2.0',
    id: id,
    error: { code, message }
  });
}

async function processLine(line) {
  log(`Received line: ${line}`);
  if (!line.trim()) return;

  let request;
  try {
    request = JSON.parse(line);
  } catch (e) {
    log(`JSON parse error: ${e.message}`);
    writeError(null, -32700, 'Parse error');
    return;
  }

  // Check if this is a notification (no id field)
  // Notifications don't expect responses according to JSON-RPC spec
  const isNotification = !('id' in request);

  if (isNotification) {
    log(`Notification received (no response expected): ${request.method}`);
    // For notifications, we still send to server but don't write response to stdout
    try {
      await sendRequest(request);
      log('Notification sent to server (no response written)');
    } catch (e) {
      log(`Notification send error (ignored): ${e.message}`);
    }
    return;
  }

  try {
    const response = await sendRequest(request);
    writeResponse(response);
  } catch (e) {
    log(`Error: ${e.message}`);
    writeError(request.id, -32603, e.message);
  }
}

rl.on('line', processLine);

rl.on('close', () => {
  log('stdin closed');
  setTimeout(() => process.exit(0), 100);
});

// Handle signals gracefully
process.on('SIGTERM', () => {
  log('SIGTERM received');
  process.exit(0);
});
process.on('SIGINT', () => {
  log('SIGINT received');
  process.exit(0);
});

process.on('uncaughtException', (e) => {
  log(`Uncaught exception: ${e.message}`);
  process.exit(1);
});

// Keep process alive
process.stdin.resume();
log('Bridge ready, waiting for input...');
