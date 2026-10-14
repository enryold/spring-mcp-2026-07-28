import assert from 'node:assert/strict';
import { Client, StreamableHTTPClientTransport } from '@modelcontextprotocol/client';
import { buildFixtureJar, startFixture } from './processes.ts';
import { PROTOCOL_VERSION, SERVER_INFO, startReferenceServer } from './reference.ts';

const RESET_KEY = 'interop-client-reset';

await buildFixtureJar();
let java: Awaited<ReturnType<typeof startFixture>> | undefined;
let referenceFixture: Awaited<ReturnType<typeof startFixture>> | undefined;
let reference: Awaited<ReturnType<typeof startReferenceServer>> | undefined;
try {
  java = await startFixture(RESET_KEY);
  referenceFixture = await startFixture(RESET_KEY);
  reference = await startReferenceServer({ fixtureUrl: referenceFixture.url });

  const referenceObserved = await exercise(reference.url);
  const javaObserved = await exercise(`${java.url}/mcp`);
  assert.deepEqual(javaObserved, referenceObserved);
  process.stdout.write(`client e2e PASS ${javaObserved.tools.length} tools and Unicode echo\n`);
} finally {
  await Promise.allSettled([reference?.close(), java?.close(), referenceFixture?.close()]);
}

async function exercise(url: string) {
  const client = new Client({ name: 'spring-mcp-interop-client', version: '0.1.0' }, {
    versionNegotiation: { mode: { pin: PROTOCOL_VERSION } },
  });
  const transport = new StreamableHTTPClientTransport(new URL(url), {
    authProvider: { token: async () => 'alice-write' },
  });
  try {
    await client.connect(transport);
    assert.equal(client.getNegotiatedProtocolVersion(), PROTOCOL_VERSION);
    assert.deepEqual(client.getServerVersion(), SERVER_INFO);
    assert.deepEqual(client.getDiscoverResult()?.capabilities, { tools: {}, logging: {} });

    const listed = await client.listTools();
    const tools = listed.tools.map(tool => tool.name);
    assert.deepEqual(tools, [
      'fixture.echo',
      'fixture.error',
      'fixture.item.create',
      'fixture.item.createSlow',
      'fixture.item.read',
      'fixture.slow',
    ]);
    const echoed = await client.callTool({ name: 'fixture.echo', arguments: { text: 'Caffè ☕ & MCP' } });
    assert.deepEqual(echoed.structuredContent, { text: 'Caffè ☕ & MCP' });
    assert.equal(echoed.isError, undefined);
    return { tools, content: echoed.content, structuredContent: echoed.structuredContent };
  } finally {
    await client.close();
  }
}
