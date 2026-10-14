import assert from 'node:assert/strict';
import test from 'node:test';
import { compareObservations, type Observation } from '../src/comparator.ts';
import { validateResponseShape } from '../src/response-oracle.ts';
import type { WireCase } from '../src/wire.ts';

test('equal responses missing resultType fail the independent official-schema oracle', () => {
  const wireCase = caseFor('oracle-missing-result-type', 'tools/list');
  const observed = observation({
    jsonrpc: '2.0', id: 1, result: { cacheScope: 'private', ttlMs: 0, tools: [] },
  });

  assert.deepEqual(compareObservations(observed, structuredClone(observed)), []);
  assert.match(validateResponseShape(wireCase, observed, 'java').join('\n'), /resultType/);
  assert.match(validateResponseShape(wireCase, observed, 'reference').join('\n'), /resultType/);
});

test('equal responses with an invalid content block fail the concrete call result schema', () => {
  const wireCase = caseFor('oracle-invalid-content', 'tools/call');
  const observed = observation({
    jsonrpc: '2.0', id: 1, result: { resultType: 'complete', content: [{ type: 'text' }] },
  });

  assert.deepEqual(compareObservations(observed, structuredClone(observed)), []);
  const failures = validateResponseShape(wireCase, observed, 'java');
  assert.ok(failures.length > 0);
  assert.match(failures.join('\n'), /content|text/);
});

test('null error ids fail Java and only the exact documented SDK case is exempt', () => {
  const observed = { ...observation({
    jsonrpc: '2.0', id: null, error: { code: -32_700, message: 'Parse error' },
  }), status: 400 };
  const documented = caseFor('wire-malformed-json', 'tools/list');
  const unrelated = caseFor('oracle-null-error-id', 'tools/list');

  assert.match(validateResponseShape(documented, observed, 'java').join('\n'), /\/id/);
  assert.deepEqual(validateResponseShape(documented, observed, 'reference'), []);
  assert.match(validateResponseShape(unrelated, observed, 'reference').join('\n'), /\/id/);
});

test('specialized protocol errors require their normative data shape', () => {
  const observed = { ...observation({
    jsonrpc: '2.0', id: 1, error: { code: -32_021, message: 'Capability required' },
  }), status: 400 };
  assert.match(validateResponseShape(caseFor('oracle-capability-data', 'tools/call'), observed, 'java').join('\n'), /data/);
});

test('terminal result and error members are mutually exclusive even when the schema leaves additions open', () => {
  const observed = observation({
    jsonrpc: '2.0',
    id: 1,
    result: { resultType: 'complete', content: [{ type: 'text', text: 'invalid coexistence' }] },
    error: { code: -32_603, message: 'also invalid' },
  });
  assert.deepEqual(compareObservations(observed, structuredClone(observed)), []);
  assert.match(validateResponseShape(caseFor('oracle-terminal-xor', 'tools/call'), observed, 'java').join('\n'), /exactly one/);
});

function caseFor(id: string, method: string): WireCase {
  return {
    id,
    requirements: ['P01.03'],
    request: { mcpMethod: method, id: 1, params: {} },
    assert: { status: 200 },
  };
}

test('SSE availability probes validate the concrete first acknowledgment and its request binding', () => {
  const wireCase = caseFor('subscriptions-probe', 'subscriptions/listen');
  wireCase.request.firstSseMessage = true;
  const observed: Observation = { status: 200, headers: { 'content-type': 'text/event-stream' },
    body: { jsonrpc: '2.0', method: 'notifications/subscriptions/acknowledged',
      params: { notifications: {}, _meta: { 'io.modelcontextprotocol/subscriptionId': 1 } } } };
  assert.deepEqual(validateResponseShape(wireCase, observed, 'reference'), []);
  (observed.body as any).params._meta['io.modelcontextprotocol/subscriptionId'] = 2;
  assert.match(validateResponseShape(wireCase, observed, 'reference').join('\n'), /subscriptionId/);
  (observed.body as any).method = 'notifications/progress';
  assert.match(validateResponseShape(wireCase, observed, 'reference').join('\n'), /constant/);
});

test('schema oracle checks preparatory and parallel outputs independently', () => {
  const wireCase = caseFor('parallel-oracle', 'tools/list');
  const request = { mcpMethod: 'tools/list', id: 1, params: {} };
  wireCase.before = [{ request }];
  wireCase.parallelBefore = [{ request }];
  const valid = observation({ jsonrpc: '2.0', id: 1,
    result: { resultType: 'complete', tools: [], ttlMs: 0, cacheScope: 'private' } });
  const invalid = observation({ jsonrpc: '2.0', id: 1, result: { tools: [] } });
  const failures = validateResponseShape(wireCase, { ...valid, before: [invalid], parallelBefore: [invalid] }, 'java');
  assert.match(failures.join('\n'), /before\[0\].*resultType/);
  assert.match(failures.join('\n'), /parallelBefore\[0\].*resultType/);
});

test('catalog responses use their concrete resource prompt and completion schemas', () => {
  const resources = observation({ jsonrpc: '2.0', id: 1,
    result: { resultType: 'complete', resources: [{ name: 'missing-uri' }] } });
  assert.match(validateResponseShape(caseFor('oracle-resources', 'resources/list'), resources, 'java').join('\n'),
    /resources|uri/);

  const prompt = observation({ jsonrpc: '2.0', id: 1,
    result: { resultType: 'complete', messages: [{ role: 'user', content: { type: 'text' } }] } });
  assert.match(validateResponseShape(caseFor('oracle-prompt', 'prompts/get'), prompt, 'java').join('\n'),
    /messages|content|text/);

  const completion = observation({ jsonrpc: '2.0', id: 1,
    result: { resultType: 'complete', completion: { values: [42] } } });
  assert.match(validateResponseShape(caseFor('oracle-completion', 'completion/complete'), completion, 'java').join('\n'),
    /completion|values|string/);
});

test('finite SSE messages are validated and correlated to the source request', () => {
  const wireCase = caseFor('oracle-progress', 'tools/call');
  wireCase.request.meta = { progressToken: 'expected-token' };
  const terminal = { jsonrpc: '2.0', id: 1,
    result: { resultType: 'complete', content: [{ type: 'text', text: 'done' }] } };
  const observed: Observation = { status: 200, headers: { 'content-type': 'text/event-stream' }, body: terminal,
    messages: [
      { jsonrpc: '2.0', method: 'notifications/progress',
        params: { progressToken: 'wrong-token', progress: 1 } },
      terminal,
    ] };
  assert.match(validateResponseShape(wireCase, observed, 'java').join('\n'), /progressToken.*source request/);
  (observed.messages as any[])[0] = { jsonrpc: '2.0', id: 'independent', method: 'sampling/createMessage', params: {} };
  assert.match(validateResponseShape(wireCase, observed, 'java').join('\n'), /no notification validator/);
});

test('subscription messages require first acknowledgment, request identity, and exact filters', () => {
  const wireCase = caseFor('oracle-subscription', 'subscriptions/listen');
  wireCase.request.id = 'subscription-1';
  wireCase.request.params = { notifications: { resourceSubscriptions: ['test://allowed'] } };
  const update = { jsonrpc: '2.0', method: 'notifications/resources/updated', params: {
    uri: 'test://other', _meta: { 'io.modelcontextprotocol/subscriptionId': 'wrong' },
  } };
  const ack = { jsonrpc: '2.0', method: 'notifications/subscriptions/acknowledged', params: {
    notifications: { resourceSubscriptions: ['test://allowed'] },
    _meta: { 'io.modelcontextprotocol/subscriptionId': 'subscription-1' },
  } };
  const observed: Observation = { status: 200, headers: { 'content-type': 'text/event-stream' },
    body: ack, messages: [update, ack] };
  const failures = validateResponseShape(wireCase, observed, 'java').join('\n');
  assert.match(failures, /acknowledgment must be the first/);
  assert.match(failures, /subscriptionId does not match/);
  assert.match(failures, /resource update URI was not requested/);
});

test('step and parallel observations are checked against their own source requests', () => {
  const wireCase = caseFor('oracle-correlated', 'tools/call');
  const resourceRequest = { mcpMethod: 'resources/list', id: 'resource-step', params: {} };
  const promptRequest = { mcpMethod: 'prompts/list', id: 'prompt-parallel', params: {} };
  wireCase.steps = [{ request: resourceRequest }];
  wireCase.parallelRequests = [promptRequest];
  const top = observation({ jsonrpc: '2.0', id: 1,
    result: { resultType: 'complete', content: [{ type: 'text', text: 'top' }] } });
  const wrongForBoth = observation({ jsonrpc: '2.0', id: 'resource-step',
    result: { resultType: 'complete', content: [{ type: 'text', text: 'wrong' }] } });
  const failures = validateResponseShape(wireCase,
    { ...top, steps: [wrongForBoth], parallel: [wrongForBoth] }, 'java').join('\n');
  assert.match(failures, /steps\[0\].*ListResourcesResultResponse/);
  assert.match(failures, /parallel\[0\].*(source request id|ListPromptsResultResponse)/);
});

test('Tasks v2 creation and status-specific payloads use the pinned SEP shape', () => {
  const createdCase = caseFor('oracle-task-create', 'tools/call');
  const validCreated = observation({ jsonrpc: '2.0', id: 1, result: {
    resultType: 'task', taskId: 'opaque', status: 'working',
    createdAt: '2026-10-14T10:40:11.011Z', lastUpdatedAt: '2026-10-14T10:40:11.011Z',
    ttlMs: 3_600_000, pollIntervalMs: 100,
  } });
  assert.deepEqual(validateResponseShape(createdCase, validCreated, 'java'), []);

  const invalidCreated = structuredClone(validCreated);
  (invalidCreated.body as any).result.status = 'completed';
  (invalidCreated.body as any).result.result = { resultType: 'complete' };
  (invalidCreated.body as any).result.createdAt = '2026-02-30T10:40:11Z';
  const creationFailures = validateResponseShape(createdCase, invalidCreated, 'java').join('\n');
  assert.match(creationFailures, /status.*constant/);
  assert.match(creationFailures, /must NOT be valid/);
  assert.match(creationFailures, /createdAt is not a valid timestamp/);

  const getCase = caseFor('oracle-task-get', 'tasks/get');
  const invalidCompleted = observation({ jsonrpc: '2.0', id: 1, result: {
    resultType: 'complete', taskId: 'opaque', status: 'completed',
    createdAt: '2026-10-14T10:40:11Z', lastUpdatedAt: '2026-10-14T10:40:12Z', ttlMs: null,
    result: { resultType: 'complete' }, error: { code: -32603, message: 'must not coexist' },
  } });
  assert.match(validateResponseShape(getCase, invalidCompleted, 'java').join('\n'), /oneOf|must NOT be valid/);

  const ackCase = caseFor('oracle-task-ack', 'tasks/cancel');
  const invalidAck = observation({ jsonrpc: '2.0', id: 1,
    result: { resultType: 'complete', status: 'cancelled' } });
  assert.match(validateResponseShape(ackCase, invalidAck, 'java').join('\n'), /additional properties/);
});

test('task creation honors open Result extensions while rejecting legacy and status payload fields', () => {
  const observed = observation({ jsonrpc: '2.0', id: 1, result: {
    resultType: 'task', taskId: 'opaque', status: 'working', content: [],
    createdAt: '2026-10-14T10:40:11Z', lastUpdatedAt: '2026-10-14T10:40:11Z', ttlMs: 60_000,
  } });
  const wireCase = caseFor('oracle-task-extension-field', 'tools/call');
  assert.deepEqual(validateResponseShape(wireCase, observed, 'java'), []);
  (observed.body as any).result.task = { taskId: 'legacy-wrapper' };
  assert.match(validateResponseShape(wireCase, observed, 'java').join('\n'), /must NOT be valid/);
  delete (observed.body as any).result.task;
  (observed.body as any).result.inputRequests = {};
  assert.match(validateResponseShape(wireCase, observed, 'java').join('\n'), /must NOT be valid/);
});

function observation(body: unknown): Observation {
  return { status: 200, headers: { 'content-type': 'application/json' }, body };
}
test('authentication may reject before parsing an id but cannot return a mismatched id', () => {
  const wireCase: WireCase = { id: 'unauthorized', requirements: ['P10.01'],
    request: { mcpMethod: 'tools/list', id: 'private' }, assert: { status: 401 } };
  const observed: Observation = { status: 401, headers: {},
    body: { jsonrpc: '2.0', error: { code: -32001, message: 'Unauthorized' } } };
  assert.deepEqual(validateResponseShape(wireCase, observed, 'java'), []);
  assert.ok(validateResponseShape(wireCase, { ...observed, body: { ...observed.body as object, id: 'other' } }, 'java').length);
});
