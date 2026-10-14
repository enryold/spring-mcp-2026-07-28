import assert from 'node:assert/strict';
import test from 'node:test';
import { describeWireRequest, readFirstSseMessage, readSseMessages, assertWireCase, type WireCase } from '../src/wire.ts';

test('effective request evidence preserves JSON metadata and redacts credentials', () => {
  const request = describeWireRequest({ mcpMethod: 'tools/list', id: 'wire-id',
    query: { access_token: 'private-token' }, params: {} }, 'private-token');
  assert.ok(!JSON.stringify(request).includes('private-token'));
  assert.equal((request.body as any).params._meta['io.modelcontextprotocol/protocolVersion'], '2026-07-28');
  assert.equal((request.body as any).id, 'wire-id');
});

test('SSE probe handles comments and split UTF-8 frames and closes the stream', async () => {
  const bytes = new TextEncoder().encode(': heartbeat\r\n\r\nevent: message\r\ndata: {"text":"città"}\r\n\r\n');
  const split = bytes.indexOf(0xc3) + 1;
  let cancelled = false;
  const stream = new ReadableStream<Uint8Array>({
    start(controller) { controller.enqueue(bytes.slice(0, split)); controller.enqueue(bytes.slice(split)); },
    cancel() { cancelled = true; },
  });
  assert.equal(await readFirstSseMessage(new Response(stream)), '{"text":"città"}');
  assert.equal(cancelled, true);
});

test('SSE probe fails closed when no message arrives or its bounded size is exceeded', async () => {
  await assert.rejects(readFirstSseMessage(new Response(': comment\n\n')), /without a data message/);
  await assert.rejects(readFirstSseMessage(new Response('x'.repeat(65_537))), /exceeds/);
});

test('finite SSE preserves notifications and the exact terminal message', async () => {
  const response = new Response(': keepalive\n\ndata: {"jsonrpc":"2.0","method":"notifications/progress","params":{"progressToken":"p","progress":0}}\n\ndata: {"jsonrpc":"2.0","id":"done","result":{"resultType":"complete","content":[]}}\n\n');
  const messages = await readSseMessages(response);
  assert.equal(messages.length, 2);
  assert.equal((messages[1] as any).id, 'done');
  await assert.rejects(readSseMessages(new Response('data: invalid\n\n')), /JSON/);
});

test('stream assertions reject cross-request progress and wrong terminal ids', () => {
  const wireCase: WireCase = { id: 'stream', requirements: ['P17.01'],
    request: { mcpMethod: 'tools/call', id: 'expected' }, assert: { status: 200,
      contentType: 'text/event-stream', progressToken: 'expected-token', progressValues: [0], terminalId: 'expected' } };
  const failures = assertWireCase(wireCase, { status: 200, headers: { 'content-type': 'text/event-stream' },
    body: { jsonrpc: '2.0', id: 'wrong', result: { resultType: 'complete', content: [] } },
    messages: [{ jsonrpc: '2.0', method: 'notifications/progress', params: { progressToken: 'other', progress: 0 } }] });
  assert.ok(failures.some(value => value.includes('progressToken')));
  assert.ok(failures.some(value => value.includes('terminal')));
});
