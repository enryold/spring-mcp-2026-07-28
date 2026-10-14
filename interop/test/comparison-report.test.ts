import assert from 'node:assert/strict';
import test from 'node:test';
import { assessComparison, summarizeComparisons } from '../src/comparison-report.ts';
import type { Observation } from '../src/comparator.ts';
import type { WireCase } from '../src/wire.ts';

const wireCase: WireCase = {
  id: 'comparison-example', requirements: ['P01.03'],
  request: { mcpMethod: 'tools/list', params: {} }, assert: { status: 400, errorCode: -32600 },
};
const baseline: Observation = {
  status: 400, headers: { 'content-type': 'application/json' },
  body: { jsonrpc: '2.0', id: 1, error: { code: -32600, message: 'Invalid request' } },
};
const divergence = { case: wireCase.id, sourceClause: 'pinned-source:1-2',
  referenceObservation: 'Different diagnostic prose.', javaDecision: 'Preserve code and status.',
  test: 'fixture', removalCondition: 'Aligned diagnostics.' };

test('comparison report counts structural equality as identical', () => {
  const result = assessComparison(wireCase, baseline, structuredClone(baseline), [], []);
  assert.equal(result.comparison, 'identical');
  assert.deepEqual(result.strictDifferences, []);
  assert.equal(summarizeComparisons([result]).identical, 1);
});

test('documented exclusions preserve strict differences in the report', () => {
  const changed = { ...baseline, body: { ...baseline.body as object,
    error: { code: -32600, message: 'Validator-specific prose' } } };
  const result = assessComparison({ ...wireCase, ignorePaths: ['$body.error.message'] }, baseline, changed, [], [divergence]);
  assert.equal(result.comparison, 'documented_fields');
  assert.equal(result.strictDifferences.length, 1);
  assert.deepEqual(result.remainingDifferences, []);
});

test('split assertions never claim behavioral equality', () => {
  const result = assessComparison({ ...wireCase, skipDifferential: true }, baseline,
    { ...baseline, status: 200 }, [], [divergence]);
  assert.equal(result.comparison, 'documented_behavior');
  assert.ok(result.strictDifferences.length > 0);
});

test('undocumented exclusions and assertion failures fail the report', () => {
  assert.equal(assessComparison({ ...wireCase, ignorePaths: ['$body.id'] }, baseline,
    baseline, [], []).comparison, 'failed');
  assert.equal(assessComparison(wireCase, baseline, baseline,
    ['both endpoints returned an invalid response'], []).comparison, 'failed');
});

test('side effect differences cannot be hidden by a diagnostic exclusion', () => {
  const result = assessComparison({ ...wireCase, ignorePaths: ['$body.error.message'] },
    { ...baseline, state: { count: 1 } }, { ...baseline, state: { count: 2 } }, [], [divergence]);
  assert.equal(result.comparison, 'failed');
  assert.match(result.remainingDifferences.join('\n'), /state/);
});
