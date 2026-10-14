import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { pathToFileURL } from 'node:url';
import { McpServer, createMcpHandler, type AuthInfo, type CallToolResult } from '@modelcontextprotocol/server';
import { hostHeaderValidation, originValidation, toNodeHandler } from '@modelcontextprotocol/node';
import * as z from 'zod/v4';
import { installConformanceTools } from './conformance-tools.js';
import { installCatalogFixtures } from './catalog-fixtures.js';
import { installStreamingFixtures, StreamingFixtureEvents } from './streaming-fixtures.js';
import { installInteractionFixtures, installTaskV2DiagnosticAdapter, InteractionFixtureRuntime } from './interaction-fixtures.js';

export const PROTOCOL_VERSION = '2026-07-28';
export const SERVER_INFO = { name: 'spring-mcp-sample', version: '0.1.0' } as const;

type Identity = AuthInfo & { subject: string; tenantId: string; fixtureToken: string };

const IDENTITIES: Record<string, Identity> = {
  'alice-read': { token: 'alice-read', fixtureToken: 'api-alice-read', clientId: 'fixture-client', scopes: ['read'], subject: 'alice', tenantId: 'red' },
  'alice-write': { token: 'alice-write', fixtureToken: 'api-alice-write', clientId: 'fixture-client', scopes: ['read', 'write'], subject: 'alice', tenantId: 'red' },
  'bob-write': { token: 'bob-write', fixtureToken: 'api-bob-write', clientId: 'fixture-client', scopes: ['read', 'write'], subject: 'bob', tenantId: 'blue' },
  'alice-rate-limited': { token: 'alice-rate-limited', fixtureToken: 'api-alice-read', clientId: 'fixture-client', scopes: ['read'], subject: 'rate-limited', tenantId: 'red' },
};
const CONFORMANCE_IDENTITY: Identity = {
  token: 'conformance', fixtureToken: 'api-alice-write', clientId: 'official-conformance', scopes: ['read', 'write'],
  subject: 'conformance', tenantId: 'red',
};

const itemSchema = z.object({
  id: z.string(),
  tenantId: z.string(),
  value: z.string(),
  createdAt: z.string(),
}).strict();

export interface ReferenceOptions {
  fixtureUrl: string;
  port?: number;
  host?: string;
  conformance?: boolean;
  tasksDiagnostic?: boolean;
}

export async function startReferenceServer(options: ReferenceOptions) {
  const conformance = options.conformance ?? process.env.CONFORMANCE === 'true';
  const events = new StreamingFixtureEvents();
  const interactions = new InteractionFixtureRuntime();
  const createHandler = (owner: Identity, streaming: boolean) => createMcpHandler(({ authInfo }) => {
    const identity = authInfo as Identity;
    const server = new McpServer(SERVER_INFO, {
      supportedProtocolVersions: [PROTOCOL_VERSION],
      capabilities: { tools: {}, logging: {}, ...(options.tasksDiagnostic ? { extensions: { 'io.modelcontextprotocol/tasks': {} } } : {}) },
      requestState: conformance ? { verify: state => interactions.verifyOpaqueState(identity, state) } : undefined,
      cacheHints: {
        'server/discover': { ttlMs: 0, cacheScope: 'private' },
        'tools/list': { ttlMs: 0, cacheScope: 'private' },
        'prompts/list': { ttlMs: 0, cacheScope: 'private' },
        'resources/list': { ttlMs: 0, cacheScope: 'private' },
        'resources/templates/list': { ttlMs: 0, cacheScope: 'private' },
        'resources/read': { ttlMs: 0, cacheScope: 'private' },
      },
      maxToolInputElements: 64,
    });

    if (identity.scopes.includes('read')) {
      server.registerTool('fixture.echo', {
        description: 'Echo UTF-8 text through the Spring fixture',
        inputSchema: z.object({ text: z.string() }).strict(),
        outputSchema: z.object({ text: z.string() }).strict(),
        annotations: { readOnlyHint: true, idempotentHint: true },
      }, async ({ text }) => result(await fixture(options.fixtureUrl, identity.fixtureToken, 'GET', `/api/echo?text=${encodeURIComponent(text)}`)));

      server.registerTool('fixture.error', {
        description: 'Return deterministic expected or sanitized unexpected fixture failures',
        inputSchema: z.object({ kind: z.enum(['expected', 'unexpected']) }).strict(),
        outputSchema: z.object({ kind: z.string(), message: z.string() }).strict(),
        annotations: { readOnlyHint: true, idempotentHint: true },
      }, async ({ kind }) => result(await fixture(options.fixtureUrl, identity.fixtureToken, 'POST', '/api/error', { kind })));

      if (identity.scopes.includes('write')) {
        server.registerTool('fixture.item.create', {
          description: 'Create one item in the authenticated tenant',
          inputSchema: z.object({ id: z.string().min(1), value: z.string() }).strict(),
          outputSchema: itemSchema,
          annotations: { destructiveHint: false, idempotentHint: false },
        }, async (input) => result(await fixture(options.fixtureUrl, identity.fixtureToken, 'POST', '/api/items', input)));

        server.registerTool('fixture.item.createSlow', {
          description: 'Commit one item, then delay the authenticated response',
          inputSchema: z.object({
            id: z.string().min(1),
            value: z.string(),
            delayMs: z.number().int().min(0).max(2_000),
          }).strict(),
          outputSchema: itemSchema,
          annotations: { destructiveHint: false, idempotentHint: false },
        }, async (input) => result(await fixture(options.fixtureUrl, identity.fixtureToken, 'POST', '/api/items/slow', input)));
      }

      server.registerTool('fixture.item.read', {
        description: 'Read one item from the authenticated tenant',
        inputSchema: z.object({ id: z.string().min(1) }).strict(),
        outputSchema: itemSchema,
        annotations: { readOnlyHint: true, idempotentHint: true },
      }, async ({ id }) => result(await fixture(options.fixtureUrl, identity.fixtureToken, 'GET', `/api/items/${encodeURIComponent(id)}`)));

      server.registerTool('fixture.slow', {
        description: 'Delay a deterministic fixture response',
        inputSchema: z.object({ delayMs: z.number().int().min(0).max(2_000) }).strict(),
        outputSchema: z.object({ delayMs: z.number().int() }).strict(),
        annotations: { readOnlyHint: true, idempotentHint: true },
      }, async ({ delayMs }) => result(await fixture(options.fixtureUrl, identity.fixtureToken, 'POST', '/api/slow', { delayMs })));
    }

    if (conformance) {
      installConformanceTools(server);
      installCatalogFixtures(server, identity, interactions);
      installStreamingFixtures(server, identity, events, interactions);
      installInteractionFixtures(server, identity, interactions);
    }
    if (options.tasksDiagnostic) installTaskV2DiagnosticAdapter(server.server, identity, interactions);
    server.server.registerCapabilities(conformance
      ? { tools: { listChanged: true }, prompts: { listChanged: true }, resources: { listChanged: true, subscribe: true }, logging: {} }
      : { tools: { listChanged: undefined } });
    return server;
  }, {
    legacy: 'reject',
    responseMode: streaming ? 'sse' : 'json',
    bus: events.bus(owner),
    maxSubscriptions: 64,
    maxRequestBodySize: 65_536,
  });

  const handlers = new Map<string, { handler: ReturnType<typeof createHandler>; node: ReturnType<typeof toNodeHandler> }>();
  const nodeHandler = (identity: Identity, streaming: boolean) => {
    const key = `${identity.tenantId}\0${identity.subject}\0${streaming}`;
    let entry = handlers.get(key);
    if (entry === undefined) {
      const handler = createHandler(identity, streaming);
      entry = { handler, node: toNodeHandler({ fetch: async (request, requestOptions) => {
        const response = await handler.fetch(request, requestOptions);
        // Host policy keeps authenticated streams private, like the Java fixture.
        if (response.headers.get('content-type')?.startsWith('text/event-stream')) {
          response.headers.set('Cache-Control', 'private, no-store, no-transform');
        }
        return response;
      } }, { maxRequestBodySize: 65_536 }) };
      handlers.set(key, entry);
    }
    return entry.node;
  };
  const validateHost = hostHeaderValidation(['localhost', '127.0.0.1', '[::1]']);
  const validateOrigin = originValidation(conformance ? ['localhost', '127.0.0.1', '[::1]'] : []);
  const httpServer = createServer(async (request, response) => {
    if (request.url === '/health' && request.method === 'GET') {
      response.writeHead(204).end();
      return;
    }
    if (request.url !== '/mcp') {
      response.writeHead(404).end();
      return;
    }
    response.setHeader('Cache-Control', 'private, no-store');
    if (!validateHost(request, response) || !validateOrigin(request, response)) return;
    const identity = authenticate(request.headers.authorization)
      ?? (conformance && request.headers.authorization === undefined ? CONFORMANCE_IDENTITY : undefined);
    if (!identity) {
      unauthorized(response);
      return;
    }
    if (identity.subject === 'rate-limited') {
      rateLimited(response, await requestId(request));
      return;
    }
    if (request.headers['mcp-method'] === 'tools/call' && request.headers['mcp-name'] === 'fixture.item.create'
        && !identity.scopes.includes('write')) {
      forbidden(response, await requestId(request));
      return;
    }
    if (request.headers['mcp-method'] === 'tools/call' && request.headers['mcp-name'] === 'fixture.item.createSlow'
        && !identity.scopes.includes('write')) {
      forbidden(response, await requestId(request));
      return;
    }
    (request as IncomingMessage & { auth: AuthInfo }).auth = identity;
    const streaming = conformance && new Set(['test_tool_with_progress', 'test_logging_tool', 'test_streaming_elicitation', 'test_task_notification_suppression'])
      .has(String(request.headers['mcp-name'] ?? ''));
    await nodeHandler(identity, streaming)(request, response);
  });

  await new Promise<void>((resolve, reject) => {
    httpServer.once('error', reject);
    httpServer.listen(options.port ?? 0, options.host ?? '127.0.0.1', resolve);
  });
  const address = httpServer.address();
  if (address === null || typeof address === 'string') throw new Error('Reference server did not bind a TCP port');
  return {
    port: address.port,
    url: `http://${options.host ?? '127.0.0.1'}:${address.port}/mcp`,
    close: async () => {
      await Promise.all([...handlers.values()].map(entry => entry.handler.close()));
      await new Promise<void>((resolve, reject) => httpServer.close(error => error ? reject(error) : resolve()));
    },
  };
}

async function fixture(baseUrl: string, token: string, method: string, path: string, body?: unknown) {
  const response = await fetch(new URL(path, baseUrl), {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(3_000),
  });
  const value = await response.json() as Record<string, unknown>;
  return { ok: response.ok, value };
}

function result(response: { ok: boolean; value: Record<string, unknown> }): CallToolResult {
  const text = JSON.stringify(response.value);
  return response.ok
    ? { content: [{ type: 'text', text }], structuredContent: response.value }
    : { content: [{ type: 'text', text }], isError: true };
}

function authenticate(authorization: string | undefined): Identity | undefined {
  if (!authorization?.startsWith('Bearer ')) return undefined;
  return IDENTITIES[authorization.slice('Bearer '.length)];
}

function unauthorized(response: ServerResponse): void {
  response.writeHead(401, {
    'Content-Type': 'application/json',
    'Cache-Control': 'private, no-store',
    'WWW-Authenticate': 'Bearer',
  });
  response.end(JSON.stringify({ jsonrpc: '2.0', error: { code: -32_001, message: 'Unauthorized' } }));
}

async function requestId(request: IncomingMessage): Promise<string | number | undefined> {
  let body = '';
  for await (const chunk of request) {
    body += typeof chunk === 'string' ? chunk : chunk.toString('utf8');
    if (Buffer.byteLength(body) > 65_536) return undefined;
  }
  try {
    const id = (JSON.parse(body) as { id?: unknown }).id;
    return typeof id === 'string' || typeof id === 'number' ? id : undefined;
  } catch {
    return undefined;
  }
}

function forbidden(response: ServerResponse, id: string | number | undefined): void {
  response.writeHead(403, {
    'Content-Type': 'application/json',
    'WWW-Authenticate': 'Bearer error="insufficient_scope"',
  });
  response.end(JSON.stringify({ jsonrpc: '2.0', error: { code: -32_001, message: 'Forbidden' }, ...(id === undefined ? {} : { id }) }));
}

function rateLimited(response: ServerResponse, id: string | number | undefined): void {
  response.writeHead(429, { 'Content-Type': 'application/json', 'Retry-After': '5' });
  response.end(JSON.stringify({
    jsonrpc: '2.0',
    error: { code: -32_000, message: 'Rate limit exceeded', data: { retryAfterSeconds: 5 } },
    ...(id === undefined ? {} : { id }),
  }));
}

async function main(): Promise<void> {
  const fixtureUrl = process.env.REF_FIXTURE_URL;
  if (!fixtureUrl) throw new Error('REF_FIXTURE_URL is required');
  const running = await startReferenceServer({ fixtureUrl, port: Number(process.env.PORT ?? 18081),
    conformance: process.env.CONFORMANCE === 'true' });
  process.stdout.write(`reference-ready ${running.url}\n`);
  const stop = async () => {
    await running.close();
    process.exit(0);
  };
  process.once('SIGINT', stop);
  process.once('SIGTERM', stop);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await main();
}
