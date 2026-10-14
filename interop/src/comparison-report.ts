import { compareObservations, type Observation } from './comparator.ts';
import type { WireCase } from './wire.ts';

export type Comparison = 'identical' | 'documented_fields' | 'documented_behavior' | 'failed';
export type Divergence = {
  case: string;
  sourceClause: string;
  referenceObservation: string;
  javaDecision: string;
  test: string;
  removalCondition: string;
};

export function assessComparison(
  wireCase: WireCase, reference: Observation, java: Observation,
  assertionFailures: string[], divergences: Divergence[],
) {
  const documented = divergences.filter(entry => entry.case.split(',').includes(wireCase.id));
  const failures = [...assertionFailures];
  if ((wireCase.ignorePaths?.length || wireCase.skipDifferential) && documented.length === 0) {
    failures.push('Comparison exclusions have no source-backed divergence entry');
  }
  const strictDifferences = compareObservations(reference, java);
  const remainingDifferences = wireCase.skipDifferential ? []
    : compareObservations(reference, java, wireCase.ignorePaths);
  const comparison: Comparison = failures.length || remainingDifferences.length ? 'failed'
    : strictDifferences.length === 0 ? 'identical'
    : wireCase.skipDifferential ? 'documented_behavior' : 'documented_fields';
  return {
    case: wireCase.id, requirements: wireCase.requirements, comparison,
    strictDifferences, remainingDifferences, failures, documented,
    rules: { ignoredPaths: wireCase.ignorePaths ?? [], splitAssertions: wireCase.skipDifferential ?? false },
    reference, java,
  };
}

export function summarizeComparisons(results: Array<{ comparison: Comparison }>) {
  return {
    total: results.length,
    identical: results.filter(result => result.comparison === 'identical').length,
    documentedFields: results.filter(result => result.comparison === 'documented_fields').length,
    documentedBehavior: results.filter(result => result.comparison === 'documented_behavior').length,
    failed: results.filter(result => result.comparison === 'failed').length,
  };
}

export function renderComparisonMarkdown(report: {
  generatedAt: string;
  summary: ReturnType<typeof summarizeComparisons>;
  methodCoverage: Array<{ method: string; support: string; cases: string[] }>;
  cases: Array<{ case: string; comparison: Comparison; strictDifferences: string[];
    remainingDifferences: string[]; failures: string[] }>;
}): string {
  const { summary } = report;
  return [
    '# Java / official TypeScript — MCP 2026-07-28 E2E', '', report.generatedAt, '',
    `Cases: **${summary.total}**. Structurally identical: **${summary.identical}**. `
      + `Documented field differences: **${summary.documentedFields}**. `
      + `Documented behavior differences: **${summary.documentedBehavior}**. Failed: **${summary.failed}**.`, '',
    'Equality covers HTTP status, selected protocol/security headers, structural JSON and backend state. '
      + 'The identical count applies before per-case exclusions; it does not mean byte-for-byte HTTP equality. '
      + 'Full effective requests, both responses, state, schema assertions and source-backed decisions are in [e2e-report.json](e2e-report.json).', '',
    '## Frozen core server request inventory', '',
    'Unsupported-method rejection in the default sample does not prove support; positive module cases exercise resources, prompts, completion, SSE, MRTR and subscriptions. '
      + 'Tasks v2 uses a separate TypeScript low-level official SDK diagnostic adapter because pinned SDK 2.3.1 has no native high-level Tasks v2 runtime. '
      + 'The separate unfiltered conformance report retains native SDK limitations. '
      + 'Client-only scenarios and stdio are outside this HTTP server comparison.', '',
    '| Method | Support | Raw cases |', '| --- | --- | --- |',
    ...report.methodCoverage.map(entry => `| ${entry.method} | ${entry.support} | ${entry.cases.join(', ')} |`), '',
    '## Every raw case', '', '| Case | Comparison | Strict differences |', '| --- | --- | --- |',
    ...report.cases.map(entry => `| ${entry.case} | ${entry.comparison} | ${entry.strictDifferences.length} |`), '',
    '## Observed differences and failures', '',
    ...report.cases.filter(entry => entry.comparison !== 'identical').flatMap(entry => [
      `### ${entry.case} — ${entry.comparison}`, '',
      ...[...entry.strictDifferences, ...entry.failures].map(value => `- ${value.replaceAll('\n', ' ').replaceAll('`', "'")}`), '',
    ]),
  ].join('\n') + '\n';
}
