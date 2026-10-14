import assert from 'node:assert/strict';
import test from 'node:test';
import { compareObservations, type Observation } from '../src/comparator.ts';

const baseline: Observation = {
  status: 200,
  headers: { 'content-type': 'application/json', 'cache-control': 'private, max-age=0' },
  body: { jsonrpc: '2.0', id: 1, result: { tools: [{ name: 'a' }, { name: 'b' }] } },
  state: { counts: { create: 1 } },
};

test('comparator accepts structural object equality independent of key order', () => {
  const reordered: Observation = {
    ...baseline,
    body: { id: 1, result: { tools: [{ name: 'a' }, { name: 'b' }] }, jsonrpc: '2.0' },
  };
  assert.deepEqual(compareObservations(baseline, reordered), []);
});

test('comparator ignores only an explicitly named path', () => {
  const changed = structuredClone(baseline);
  (changed.body as any).result.tools[0].name = 'validator-specific';
  assert.deepEqual(compareObservations(baseline, changed, ['$body.result.tools.0.name']), []);
  (changed.body as any).result.tools[1].name = 'still-material';
  assert.notDeepEqual(compareObservations(baseline, changed, ['$body.result.tools.0.name']), []);
});

for (const [label, changed] of [
  ['wrong status', { ...baseline, status: 400 }],
  ['missing field', { ...baseline, body: { jsonrpc: '2.0', id: 1 } }],
  ['changed error', { ...baseline, body: { jsonrpc: '2.0', id: 1, error: { code: -32603 } } }],
  ['array order', { ...baseline, body: { jsonrpc: '2.0', id: 1, result: { tools: [{ name: 'b' }, { name: 'a' }] } } }],
  ['duplicated effect', { ...baseline, state: { counts: { create: 2 } } }],
  ['lost preparatory response', { ...baseline, before: [baseline] }],
  ['lost parallel response', { ...baseline, parallelBefore: [baseline] }],
] as const) {
  test(`comparator catches ${label}`, () => {
    assert.notDeepEqual(compareObservations(baseline, changed), []);
  });
}
