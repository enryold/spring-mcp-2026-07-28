import assert from 'node:assert/strict';
import test from 'node:test';
import { McpServer, createMcpHandler } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';

test('pinned TypeScript SDK answers a 2026 discovery and tool call', async () => {
  const handler = createMcpHandler(() => {
    const server = new McpServer({ name: 'toolchain-check', version: '0.1.0' });
    server.registerTool('echo', { inputSchema: z.object({ text: z.string() }) },
      async ({ text }) => ({ content: [{ type: 'text', text }] }));
    return server;
  }, { legacy: 'reject' });

  async function request(method: string, params: Record<string, unknown> = {}) {
    return handler.fetch(new Request('http://127.0.0.1/mcp', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json, text/event-stream',
        'MCP-Protocol-Version': '2026-07-28',
        'Mcp-Method': method,
        ...(params.name ? { 'Mcp-Name': String(params.name) } : {}),
      },
      body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params: {
        ...params,
        _meta: {
          'io.modelcontextprotocol/protocolVersion': '2026-07-28',
          'io.modelcontextprotocol/clientCapabilities': {},
        },
      } }),
    }));
  }

  try {
    const discovery = await request('server/discover');
    assert.equal(discovery.status, 200);
    const discovered = await discovery.json();
    assert.ok(discovered.result.supportedVersions.includes('2026-07-28'));
    assert.equal(discovered.result.resultType, 'complete');

    const call = await request('tools/call', { name: 'echo', arguments: { text: 'Caffè ☕' } });
    assert.equal(call.status, 200);
    const called = await call.json();
    assert.equal(called.result.resultType, 'complete');
    assert.deepEqual(called.result.content, [{ type: 'text', text: 'Caffè ☕' }]);
  } finally {
    await handler.close();
  }
});
