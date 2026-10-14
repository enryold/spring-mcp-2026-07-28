import { readFile } from 'node:fs/promises';
import type { Observation } from './comparator.ts';
import { PROTOCOL_VERSION } from './reference.ts';

export interface WireCase {
  id: string;
  requirements: string[];
  profile?: 'sample' | 'conformance' | 'deadline' | 'tasks';
  clientCapabilities?: Record<string, unknown>;
  token?: string;
  before?: WireStep[];
  parallelBefore?: Array<{ token?: string; request: WireCase['request'] }>;
  request: {
    token?: string;
    meta?: Record<string, unknown>;
    httpMethod?: string;
    mcpMethod?: string;
    bodyMethod?: string;
    mcpName?: string;
    protocolVersion?: string;
    bodyProtocolVersion?: string;
    contentType?: string;
    accept?: string | null;
    origin?: string;
    query?: Record<string, string>;
    headers?: Record<string, string>;
    clientCapabilities?: Record<string, unknown>;
    clientInfo?: Record<string, unknown>;
    id?: string | number | null;
    params?: Record<string, unknown>;
    body?: unknown;
    bodyRaw?: string;
    bodyBase64?: string;
    bodyDepth?: number;
    firstSseMessage?: boolean;
  };
  steps?: WireStep[];
  parallelRequests?: WireCase['request'][];
  afterAck?: WireCase['request'] | WireCase['request'][];
  stateTokens?: string[];
  ignorePaths?: string[];
  skipDifferential?: boolean;
  assert: {
    status: number;
    errorCode?: number;
    resultType?: string;
    cacheScope?: string;
    ttlMs?: number;
    isError?: boolean;
    isErrorPresence?: 'present' | 'absent';
    toolNames?: string[];
    contentTypes?: string[];
    structuredContent?: unknown;
    structuredPath?: Record<string, unknown>;
    resultPath?: Record<string, unknown>;
    resultAbsentPaths?: string[];
    errorDataPath?: Record<string, unknown>;
    contentIncludes?: string;
    contentExcludes?: string;
    stateCounts?: Record<string, Record<string, number>>;
    stateItemValues?: Record<string, Record<string, string>>;
    headerValues?: Record<string, string>;
    bodyPresence?: 'null' | 'object';
    idPresence?: 'absent' | 'null';
    idValue?: string | number | null;
    bodyPath?: Record<string, unknown>;
    resultPresentPaths?: string[];
    stringResultPaths?: string[];
    contentType?: string;
    messageMethods?: string[];
    forbiddenMethods?: string[];
    progressToken?: string | number;
    progressValues?: number[];
    logLevels?: string[];
    terminalId?: string | number;
    subscriptionId?: string | number;
    isolatedProgressTokens?: string[];
    forbiddenIndependentRequests?: boolean;
    resourceUris?: string[];
  };
  referenceAssert?: Partial<WireCase['assert']>;
  referenceOmitAssertions?: Array<keyof WireCase['assert']>;
}

export interface WireStep {
  token?: string;
  request: WireCase['request'];
  assert?: WireCase['assert'];
  save?: Record<string, string>;
  until?: { resultPath: string; equals: unknown };
  maxPolls?: number;
  intervalMs?: number;
}

export async function loadWireCases(): Promise<WireCase[]> {
  const groups = await Promise.all(['wire', 'catalog', 'streaming', 'interaction'].map(async group =>
    JSON.parse(await readFile(new URL(`../fixtures/${group}-cases.json`, import.meta.url), 'utf8')) as WireCase[]));
  return groups.flat().map(wireCase => ({ ...wireCase,
    request: wireCase.request ?? wireCase.steps?.at(-1)?.request ?? wireCase.parallelRequests?.[0]!,
    assert: wireCase.assert ?? wireCase.steps?.at(-1)?.assert ?? { status: 200 },
  }));
}

export async function runWireCase(targetUrl: string, fixtureUrl: string, resetKey: string, wireCase: WireCase): Promise<Observation> {
  await resetFixture(fixtureUrl, resetKey);
  const variables: Record<string, unknown> = {};
  const trace: unknown[] = [];
  const polls: unknown[] = [];
  const resolved = (request: WireCase['request']) => resolveVariables({ ...request,
    clientCapabilities: request.clientCapabilities ?? wireCase.clientCapabilities }, variables) as WireCase['request'];
  const exchange = async (step: WireStep): Promise<Observation> => {
    const request = resolved(step.request);
    const token = step.token ?? request.token ?? wireCase.token;
    trace.push(describeWireRequest(request, token));
    let observed: Observation | undefined;
    for (let attempt = 0; attempt < (step.until ? step.maxPolls ?? 20 : 1); attempt++) {
      const response = await fetch(requestUrl(targetUrl, request), requestInit(request, token));
      observed = await observeResponse(response, request, wireCase.afterAck ? async () => {
        for (const next of Array.isArray(wireCase.afterAck) ? wireCase.afterAck : [wireCase.afterAck!]) {
          const trigger = resolved(next);
          trace.push(describeWireRequest(trigger, trigger.token ?? wireCase.token));
          const reply = await fetch(requestUrl(targetUrl, trigger), requestInit(trigger, trigger.token ?? wireCase.token));
          afterAckObservations.push(await observeResponse(reply, trigger));
        }
      } : undefined, wireCase.assert.messageMethods?.length);
      if (!step.until || Object.is(valueAt((observed.body as any)?.result, step.until.resultPath), step.until.equals)) break;
      polls.push({ request: describeWireRequest(request, token), observation: observed });
      if (observed.status !== 200) break;
      await new Promise(resolve => setTimeout(resolve, step.intervalMs ?? 100));
    }
    if (!observed) throw new Error(`${wireCase.id}: step made no requests`);
    for (const [name, path] of Object.entries(step.save ?? {})) {
      const value = valueAt(observed.body, path);
      if (typeof value !== 'string' || value.length === 0) throw new Error(`${wireCase.id}: invalid saved ${name}`);
      variables[name] = value;
    }
    return observed;
  };
  const afterAckObservations: Observation[] = [];
  const beforeObservations: Observation[] = [];
  for (const before of wireCase.before ?? []) {
    beforeObservations.push(await exchange(before));
  }
  const parallelObservations = await Promise.all((wireCase.parallelBefore ?? []).map(async before => {
    return exchange(before);
  }));
  const steps: Observation[] = [];
  for (const step of wireCase.steps ?? []) steps.push(await exchange(step));
  const parallel = await Promise.all((wireCase.parallelRequests ?? []).map(request => exchange({ request })));
  const observed = steps.at(-1) ?? parallel[0] ?? await exchange({ request: wireCase.request });
  const state: Record<string, unknown> = {};
  for (const token of wireCase.stateTokens ?? []) {
    const stateResponse = await fetch(new URL('/api/test/state', fixtureUrl), {
      headers: { Authorization: `Bearer ${fixtureApiToken(token)}` },
      signal: AbortSignal.timeout(3_000),
    });
    if (!stateResponse.ok) throw new Error(`${wireCase.id}: fixture state failed with ${stateResponse.status}`);
    state[token] = await stateResponse.json();
  }
  return {
    ...observed,
    ...(Object.keys(state).length ? { state } : {}),
    ...(beforeObservations.length ? { before: beforeObservations } : {}),
    ...(parallelObservations.length ? { parallelBefore: parallelObservations } : {}),
    ...(steps.length ? { steps } : {}), ...(parallel.length ? { parallel } : {}),
    ...(afterAckObservations.length ? { afterAck: afterAckObservations } : {}),
    trace, ...(polls.length ? { polls } : {}),
  };
}

async function observeResponse(response: Response, request: WireCase['request'], afterAck?: () => Promise<void>, messageCount?: number): Promise<Observation> {
  if (!request.firstSseMessage && response.headers.get('content-type')?.startsWith('text/event-stream')) {
    const messages = await readSseMessages(response, afterAck, messageCount);
    const terminal = messages.filter(message => isRecord(message) && ('result' in message || 'error' in message));
    return { status: response.status, headers: selectedHeaders(response.headers),
      body: terminal.at(-1) ?? messages.at(-1), messages };
  }
  const text = request.firstSseMessage && response.headers.get('content-type')?.startsWith('text/event-stream')
    ? await readFirstSseMessage(response) : await response.text();
  let body: unknown = text || null;
  if (text) {
    try { body = JSON.parse(text); } catch { /* Raw bodies remain text for wire assertions. */ }
  }
  return { status: response.status, headers: selectedHeaders(response.headers), body };
}

export function assertWireCase(wireCase: WireCase, observed: Observation, target: 'java' | 'reference' = 'java'): string[] {
  const failures: string[] = [];
  const body = isRecord(observed.body) ? observed.body as Record<string, any> : {};
  const result = body?.result;
  const expected = target === 'reference' ? { ...wireCase.assert, ...wireCase.referenceAssert } : { ...wireCase.assert };
  if (target === 'reference') {
    for (const assertion of wireCase.referenceOmitAssertions ?? []) delete expected[assertion];
  }
  check(failures, 'status', expected.status, observed.status);
  if (result?.resultType === 'task' && wireCase.ignorePaths?.some(path => path.endsWith('.result.content'))) {
    if (target === 'reference') check(failures, 'SDK task creation content extension', [], result.content);
    else if ('content' in result) failures.push('Java task creation unexpectedly includes content');
  }
  if (expected.contentType) check(failures, 'content-type', expected.contentType, observed.headers['content-type']);
  for (const path of expected.resultPresentPaths ?? []) {
    if (valueAt(result, path) === undefined) failures.push(`result.${path}: expected present`);
  }
  for (const path of expected.stringResultPaths ?? []) {
    if (typeof valueAt(result, path) !== 'string' || valueAt(result, path) === '') failures.push(`result.${path}: expected nonempty string`);
  }
  const messages = (observed.messages ?? []) as Array<Record<string, any>>;
  const notifications = messages.filter(message => typeof message.method === 'string');
  if (expected.messageMethods) check(failures, 'message methods', expected.messageMethods, notifications.map(message => message.method));
  for (const method of expected.forbiddenMethods ?? []) if (notifications.some(message => message.method === method)) failures.push(`forbidden notification ${method}`);
  const progress = notifications.filter(message => message.method === 'notifications/progress');
  if (expected.progressToken !== undefined) for (const notification of progress) check(failures, 'progressToken', expected.progressToken, notification.params?.progressToken);
  if (expected.progressValues) check(failures, 'progress values', expected.progressValues, progress.map(message => message.params?.progress));
  if (expected.logLevels) check(failures, 'log levels', expected.logLevels, notifications.filter(message => message.method === 'notifications/message').map(message => message.params?.level));
  if (expected.terminalId !== undefined) {
    check(failures, 'terminal id', expected.terminalId, body.id);
    check(failures, 'terminal count', 1, messages.filter(message => 'result' in message || 'error' in message).length);
    check(failures, 'terminal is last', body, messages.at(-1));
  }
  if (expected.subscriptionId !== undefined) for (const message of notifications) check(failures, 'subscriptionId', expected.subscriptionId, message.params?._meta?.['io.modelcontextprotocol/subscriptionId']);
  if (expected.resourceUris) check(failures, 'resource URIs', expected.resourceUris,
    notifications.filter(message => message.method === 'notifications/resources/updated').map(message => message.params?.uri));
  if (expected.forbiddenIndependentRequests && messages.some(message => typeof message.method === 'string' && 'id' in message)) failures.push('independent server request in SSE stream');
  if (expected.isolatedProgressTokens) {
    check(failures, 'parallel count', expected.isolatedProgressTokens.length, observed.parallel?.length);
    for (const [index, token] of expected.isolatedProgressTokens.entries()) {
      const stream = observed.parallel?.[index];
      if (stream) failures.push(...assertWireCase({ ...wireCase, parallelRequests: undefined,
        assert: { status: 200, contentType: 'text/event-stream', progressToken: token, progressValues: [0, 50, 100],
          terminalId: wireCase.parallelRequests?.[index]?.id as string } }, stream, target).map(value => `parallel[${index}]: ${value}`));
    }
  }
  for (const kind of ['before', 'steps'] as const) for (const [index, step] of (wireCase[kind] ?? []).entries()) {
    if (step.assert && observed[kind]?.[index]) failures.push(...assertWireCase({ ...wireCase, before: undefined, steps: undefined,
      parallelRequests: undefined, assert: step.assert, referenceAssert: undefined, referenceOmitAssertions: undefined }, observed[kind]![index], target)
      .map(value => `${kind}[${index}]: ${value}`));
  }
  for (const [index, trigger] of (observed.afterAck ?? []).entries()) if (trigger.status !== 200) failures.push(`afterAck[${index}]: trigger failed with ${trigger.status}`);
  const tasks = (observed.steps ?? []).map(step => (step.body as any)?.result).filter(result => typeof result?.taskId === 'string');
  for (const [index, task] of tasks.entries()) {
    check(failures, `task[${index}] stable id`, tasks[0].taskId, task.taskId);
    check(failures, `task[${index}] creation time`, tasks[0].createdAt, task.createdAt);
    if (Date.parse(task.lastUpdatedAt) < Date.parse(task.createdAt)) failures.push(`task[${index}]: update precedes creation`);
    if (index && Date.parse(task.lastUpdatedAt) < Date.parse(tasks[index - 1].lastUpdatedAt)) failures.push(`task[${index}]: update clock regressed`);
  }
  if (expected.bodyPresence === 'null' && observed.body !== null) failures.push('body: expected null');
  if (expected.bodyPresence === 'object' && !isRecord(observed.body)) failures.push('body: expected object');
  if (expected.idPresence === 'absent' && 'id' in body) failures.push('id: expected absent');
  if (expected.idPresence === 'null' && (!('id' in body) || body.id !== null)) failures.push('id: expected explicit null');
  if (expected.idValue !== undefined) check(failures, 'id', expected.idValue, body.id);
  if (expected.errorCode !== undefined) check(failures, 'error.code', expected.errorCode, body?.error?.code);
  if (expected.resultType !== undefined) check(failures, 'result.resultType', expected.resultType, result?.resultType);
  if (expected.cacheScope !== undefined) check(failures, 'result.cacheScope', expected.cacheScope, result?.cacheScope);
  if (expected.ttlMs !== undefined) check(failures, 'result.ttlMs', expected.ttlMs, result?.ttlMs);
  if (expected.isError !== undefined) check(failures, 'result.isError', expected.isError, result?.isError);
  if (expected.isErrorPresence === 'present' && !('isError' in (result ?? {}))) failures.push('result.isError: expected present');
  if (expected.isErrorPresence === 'absent' && 'isError' in (result ?? {})) failures.push('result.isError: expected absent');
  if (expected.toolNames) check(failures, 'result.tools names', expected.toolNames, result?.tools?.map((tool: any) => tool.name));
  if (expected.contentTypes) check(failures, 'result.content types', expected.contentTypes,
    result?.content?.map((block: any) => block.type));
  if (expected.structuredContent !== undefined) check(failures, 'result.structuredContent', expected.structuredContent, result?.structuredContent);
  for (const [name, value] of Object.entries(expected.structuredPath ?? {})) {
    check(failures, `result.structuredContent.${name}`, value, result?.structuredContent?.[name]);
  }
  for (const [path, value] of Object.entries(expected.resultPath ?? {})) {
    check(failures, `result.${path}`, value, valueAt(result, path));
  }
  for (const path of expected.resultAbsentPaths ?? []) {
    if (valueAt(result, path) !== undefined) failures.push(`result.${path}: expected absent`);
  }
  for (const [path, value] of Object.entries(expected.errorDataPath ?? {})) {
    check(failures, `error.data.${path}`, value, valueAt(body?.error?.data, path));
  }
  for (const [path, value] of Object.entries(expected.bodyPath ?? {})) {
    check(failures, `body.${path}`, value, valueAt(body, path));
  }
  const content = JSON.stringify(result?.content ?? '');
  if (expected.contentIncludes && !content.includes(expected.contentIncludes)) failures.push(`content does not include ${expected.contentIncludes}`);
  if (expected.contentExcludes && content.includes(expected.contentExcludes)) failures.push(`content includes forbidden ${expected.contentExcludes}`);
  for (const [token, counts] of Object.entries(expected.stateCounts ?? {})) {
    for (const [operation, value] of Object.entries(counts)) {
      check(failures, `state.${token}.counts.${operation}`, value,
        (observed.state as Record<string, any>)?.[token]?.counts?.[operation]);
    }
  }
  for (const [token, items] of Object.entries(expected.stateItemValues ?? {})) {
    for (const [id, value] of Object.entries(items)) {
      const item = (observed.state as Record<string, any>)?.[token]?.items?.find((candidate: any) => candidate.id === id);
      check(failures, `state.${token}.items.${id}.value`, value, item?.value);
    }
  }
  for (const [name, value] of Object.entries(expected.headerValues ?? {})) {
    check(failures, `headers.${name.toLowerCase()}`, value, observed.headers[name.toLowerCase()]);
  }
  return failures;
}

export function describeWireRequest(request: WireCase['request'], token?: string) {
  const init = requestInit(request, token);
  const headers = Object.fromEntries(Object.entries(init.headers as Record<string, string>)
    .map(([name, value]) => [name, /authorization|cookie|reset-key/i.test(name) ? '[redacted]' : value]));
  const query = Object.fromEntries(Object.entries(request.query ?? {})
    .map(([name, value]) => [name, /token|secret|key/i.test(name) ? '[redacted]' : value]));
  let body: unknown = init.body;
  if (typeof init.body === 'string') {
    try { body = JSON.parse(init.body); } catch { /* Preserve malformed fixture bytes as text. */ }
  } else if (init.body instanceof Uint8Array) body = { base64: Buffer.from(init.body).toString('base64') };
  return { method: init.method, path: '/mcp', query, headers, body,
    responseObservation: request.firstSseMessage ? 'first SSE message, then close stream' : 'complete JSON response' };
}

export async function readFirstSseMessage(response: Response): Promise<string> {
  const reader = response.body?.getReader();
  if (!reader) throw new Error('SSE response has no body');
  const decoder = new TextDecoder('utf-8', { fatal: true });
  let buffer = '';
  let bytes = 0;
  try {
    while (true) {
      const next = await reader.read();
      if (next.done) throw new Error('SSE stream ended without a data message');
      bytes += next.value.byteLength;
      if (bytes > 65_536) throw new Error('SSE first message exceeds 64 KiB');
      buffer += decoder.decode(next.value, { stream: true });
      let boundary: RegExpExecArray | null;
      while ((boundary = /\r?\n\r?\n/.exec(buffer)) !== null) {
        const event = buffer.slice(0, boundary.index);
        buffer = buffer.slice(boundary.index + boundary[0].length);
        const data = event.split(/\r?\n/).filter(line => line.startsWith('data:'))
          .map(line => line.slice(5).replace(/^ /, ''));
        if (data.length > 0) return data.join('\n');
      }
    }
  } finally {
    await reader.cancel();
  }
}

function requestInit(request: WireCase['request'], token?: string): RequestInit {
  const method = request.httpMethod ?? 'POST';
  const headers: Record<string, string> = {
    ...(request.accept === null ? {} : { Accept: request.accept ?? 'application/json, text/event-stream' }),
    'Content-Type': request.contentType ?? 'application/json',
    ...request.headers,
  };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (request.protocolVersion !== null) headers['MCP-Protocol-Version'] = request.protocolVersion ?? PROTOCOL_VERSION;
  if (request.mcpMethod) headers['Mcp-Method'] = request.mcpMethod;
  if (request.mcpName) headers['Mcp-Name'] = request.mcpName;
  if (request.origin) headers.Origin = request.origin;
  let body: BodyInit | undefined;
  if (method !== 'GET' && method !== 'HEAD') {
    if (request.bodyBase64 !== undefined) body = new Uint8Array(Buffer.from(request.bodyBase64, 'base64'));
    else if (request.bodyRaw !== undefined) body = request.bodyRaw;
    else if (request.body !== undefined) body = JSON.stringify(request.body);
    else {
      const params = structuredClone(request.params ?? {});
      const args = params.arguments as Record<string, unknown> | undefined;
      if (args && typeof args.textRepeat === 'number') {
        args.text = 'x'.repeat(args.textRepeat);
        delete args.textRepeat;
      }
      params._meta = {
        ...(isRecord(params._meta) ? params._meta : {}), ...request.meta,
        'io.modelcontextprotocol/protocolVersion': request.bodyProtocolVersion ?? request.protocolVersion ?? PROTOCOL_VERSION,
        'io.modelcontextprotocol/clientCapabilities': request.clientCapabilities ?? {},
        ...(request.clientInfo === undefined ? {} : { 'io.modelcontextprotocol/clientInfo': request.clientInfo }),
      };
      if (request.bodyDepth !== undefined) {
        let nested: Record<string, unknown> = { leaf: true };
        for (let depth = 0; depth < request.bodyDepth; depth++) nested = { nested };
        params.arguments = { ...(params.arguments as Record<string, unknown> | undefined), nested };
      }
      body = JSON.stringify({ jsonrpc: '2.0', id: request.id === undefined ? 1 : request.id,
        method: request.bodyMethod ?? request.mcpMethod, params });
    }
  }
  return { method, headers, body, signal: AbortSignal.timeout(5_000) };
}

function resolveVariables(value: unknown, variables: Record<string, unknown>): unknown {
  if (typeof value === 'string' && /^\$\{\w+\}$/.test(value)) {
    const name = value.slice(2, -1);
    if (!(name in variables)) throw new Error(`Missing scenario variable ${name}`);
    return variables[name];
  }
  if (Array.isArray(value)) return value.map(entry => resolveVariables(entry, variables));
  if (isRecord(value)) return Object.fromEntries(Object.entries(value).map(([key, entry]) => [key, resolveVariables(entry, variables)]));
  return value;
}

export async function readSseMessages(response: Response, afterAck?: () => Promise<void>, messageCount?: number): Promise<unknown[]> {
  const reader = response.body?.getReader();
  if (!reader) throw new Error('SSE response has no body');
  const decoder = new TextDecoder('utf-8', { fatal: true });
  const messages: unknown[] = [];
  let buffer = '';
  let bytes = 0;
  try {
    while (true) {
      const pending = reader.read();
      let timer: ReturnType<typeof setTimeout> | undefined;
      const next = afterAck && messageCount !== undefined && messages.length >= messageCount
        ? await Promise.race([pending, new Promise<null>(resolve => { timer = setTimeout(() => resolve(null), 300); })])
        : await pending;
      if (timer) clearTimeout(timer);
      if (next === null) return messages;
      if (next.done) {
        decoder.decode();
        if (buffer.trim()) throw new Error('SSE stream ended with an incomplete event');
        if (!messages.length) throw new Error('SSE stream ended without a data message');
        return messages;
      }
      bytes += next.value.byteLength;
      if (bytes > 1_048_576) throw new Error('SSE stream exceeds 1 MiB');
      buffer += decoder.decode(next.value, { stream: true });
      let boundary: RegExpExecArray | null;
      while ((boundary = /\r?\n\r?\n/.exec(buffer)) !== null) {
        const event = buffer.slice(0, boundary.index);
        buffer = buffer.slice(boundary.index + boundary[0].length);
        const data = event.split(/\r?\n/).filter(line => line.startsWith('data:')).map(line => line.slice(5).replace(/^ /, ''));
        if (!data.length) continue;
        const message: unknown = JSON.parse(data.join('\n'));
        messages.push(message);
        if (isRecord(message) && message.method === 'notifications/subscriptions/acknowledged' && afterAck) await afterAck();
      }
    }
  } finally { await reader.cancel(); }
}

function requestUrl(targetUrl: string, request: WireCase['request']): string {
  const url = new URL(targetUrl);
  for (const [name, value] of Object.entries(request.query ?? {})) url.searchParams.set(name, value);
  return url.toString();
}

async function resetFixture(fixtureUrl: string, resetKey: string): Promise<void> {
  const response = await fetch(new URL('/api/test/reset', fixtureUrl), {
    method: 'POST',
    headers: {
      Authorization: 'Bearer api-alice-write',
      'Content-Type': 'application/json',
      'X-Fixture-Reset-Key': resetKey,
    },
    body: '{}',
    signal: AbortSignal.timeout(3_000),
  });
  if (response.status !== 204) throw new Error(`Fixture reset failed with ${response.status}`);
}

function fixtureApiToken(mcpToken: string): string {
  const tokens: Record<string, string> = {
    'alice-read': 'api-alice-read',
    'alice-write': 'api-alice-write',
    'bob-write': 'api-bob-write',
  };
  const token = tokens[mcpToken];
  if (!token) throw new Error(`No fixture API credential for ${mcpToken}`);
  return token;
}

function selectedHeaders(headers: Headers): Record<string, string> {
  const selected: Record<string, string> = {};
  for (const name of ['content-type', 'cache-control', 'www-authenticate', 'retry-after', 'mcp-protocol-version']) {
    const value = headers.get(name);
    if (value !== null) selected[name] = name === 'content-type' ? value.split(';', 1)[0].trim().toLowerCase() : value;
  }
  return selected;
}

function check(failures: string[], path: string, expected: unknown, actual: unknown): void {
  if (!isDeepStrictEqual(expected, actual)) failures.push(`${path}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
}

function valueAt(value: unknown, path: string): unknown {
  return path.split('.').reduce<unknown>((current, part) => isRecord(current) ? current[part] : undefined, value);
}

function isDeepStrictEqual(left: unknown, right: unknown): boolean {
  if (Object.is(left, right)) return true;
  if (Array.isArray(left) && Array.isArray(right)) return left.length === right.length && left.every((value, index) => isDeepStrictEqual(value, right[index]));
  if (isRecord(left) && isRecord(right)) {
    const keys = Object.keys(left);
    return keys.length === Object.keys(right).length && keys.every(key => key in right && isDeepStrictEqual(left[key], right[key]));
  }
  return false;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
