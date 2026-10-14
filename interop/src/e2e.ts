import { createHash } from 'node:crypto';
import { mkdir, readdir, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { assessComparison, renderComparisonMarkdown, summarizeComparisons, type Divergence } from './comparison-report.ts';
import { buildFixtureJar, startFixture } from './processes.ts';
import { startReferenceServer } from './reference.ts';
import { validateResponseShape } from './response-oracle.ts';
import { assertWireCase, describeWireRequest, loadWireCases, runWireCase, type WireCase } from './wire.ts';

const RESET_KEY = 'interop-e2e-reset';
const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const FAILURE_ARTIFACT = new URL('../artifacts/e2e-failures.json', import.meta.url);
const REPORT_ARTIFACT = new URL('../artifacts/e2e-report.json', import.meta.url);
const cases = select(await loadWireCases(), process.argv.slice(2));
if (cases.length === 0) throw new Error('E2E selected zero cases');
const divergences: Divergence[] = JSON.parse(await readFile(new URL('../fixtures/divergences.json', import.meta.url), 'utf8'));
const schema = JSON.parse(await readFile(new URL('../../spec/upstream/schema.json', import.meta.url), 'utf8'));
const methodCoverage = schema.$defs.ClientRequest.anyOf.map((entry: { $ref: string }) => {
  const method: string = schema.$defs[entry.$ref.split('/').at(-1)!].properties.method.const;
  return { method, support: ['server/discover', 'tools/list', 'tools/call'].includes(method)
    ? 'supported by core' : 'supported with explicitly registered capability module',
    cases: cases.filter(wireCase => [wireCase.request, ...(wireCase.steps ?? []).map(step => step.request),
      ...(wireCase.parallelRequests ?? [])].some(request => request.mcpMethod === method)).map(wireCase => wireCase.id) };
});
for (const method of ['tasks/get', 'tasks/update', 'tasks/cancel']) methodCoverage.push({ method,
  support: 'pinned Tasks extension; TypeScript low-level SDK diagnostic adapter',
  cases: cases.filter(wireCase => wireCase.steps?.some(step => step.request.mcpMethod === method)).map(wireCase => wireCase.id) });

await buildFixtureJar();
let java: Awaited<ReturnType<typeof startFixture>> | undefined;
let javaConformance: Awaited<ReturnType<typeof startFixture>> | undefined;
let javaDeadline: Awaited<ReturnType<typeof startFixture>> | undefined;
let referenceFixture: Awaited<ReturnType<typeof startFixture>> | undefined;
let reference: Awaited<ReturnType<typeof startReferenceServer>> | undefined;
let referenceConformance: Awaited<ReturnType<typeof startReferenceServer>> | undefined;
let referenceTasks: Awaited<ReturnType<typeof startReferenceServer>> | undefined;
type CaseReport = {
  case: string;
  comparison: 'identical' | 'documented_fields' | 'documented_behavior' | 'failed';
  strictDifferences: string[];
  remainingDifferences: string[];
  failures: string[];
  [key: string]: unknown;
};
const results: CaseReport[] = [];
try {
  java = await startFixture(RESET_KEY);
  referenceFixture = await startFixture(RESET_KEY);
  reference = await startReferenceServer({ fixtureUrl: referenceFixture.url });
  if (cases.some(wireCase => wireCase.profile === 'conformance' || wireCase.profile === 'tasks')) {
    javaConformance = await startFixture(RESET_KEY, ['--spring.profiles.active=conformance']);
    referenceConformance = await startReferenceServer({ fixtureUrl: referenceFixture.url, conformance: true });
  }
  if (cases.some(wireCase => wireCase.profile === 'tasks')) referenceTasks = await startReferenceServer({ fixtureUrl: referenceFixture.url, conformance: true, tasksDiagnostic: true });
  if (cases.some(wireCase => wireCase.profile === 'deadline')) {
    javaDeadline = await startFixture(RESET_KEY, ['--enryold.mcp.request-timeout=100ms']);
  }
  for (const wireCase of cases) {
    try {
      const referenceTarget = wireCase.profile === 'tasks' ? referenceTasks : wireCase.profile === 'conformance' ? referenceConformance : reference;
      const javaTarget = wireCase.profile === 'conformance' || wireCase.profile === 'tasks' ? javaConformance
        : wireCase.profile === 'deadline' ? javaDeadline : java;
      if (!referenceTarget || !javaTarget) throw new Error(`${wireCase.id}: required process lane was not started`);
      const referenceObserved = await runWireCase(referenceTarget.url, referenceFixture.url, RESET_KEY, wireCase);
      const javaObserved = await runWireCase(`${javaTarget.url}/mcp`, javaTarget.url, RESET_KEY, wireCase);
      const referenceShapeFailures = validateResponseShape(wireCase, referenceObserved, 'reference');
      const javaShapeFailures = validateResponseShape(wireCase, javaObserved, 'java');
      const referenceAssertions = assertWireCase(wireCase, referenceObserved, 'reference');
      const javaAssertions = assertWireCase(wireCase, javaObserved);
      const assessment = assessComparison(wireCase, referenceObserved, javaObserved, [
        ...referenceShapeFailures.map(value => `reference schema: ${value}`),
        ...javaShapeFailures.map(value => `java schema: ${value}`),
        ...referenceAssertions.map(value => `reference assertion: ${value}`),
        ...javaAssertions.map(value => `java assertion: ${value}`),
      ], divergences);
      results.push({ ...assessment, request: sanitizedRequest(wireCase),
        referenceStrictSchemaFailures: validateResponseShape(wireCase, referenceObserved, 'java') });
      process.stdout.write(`e2e ${assessment.comparison === 'failed' ? 'FAIL' : 'PASS'} ${wireCase.id} (${assessment.comparison})\n`);
    } catch (error) {
      results.push({ case: wireCase.id, requirements: wireCase.requirements, request: sanitizedRequest(wireCase),
        comparison: 'failed', strictDifferences: [], remainingDifferences: [],
        failures: [error instanceof Error ? error.message : String(error)] });
    }
  }
} finally {
  await Promise.allSettled([reference?.close(), referenceConformance?.close(), referenceTasks?.close(), java?.close(), javaConformance?.close(),
    javaDeadline?.close(), referenceFixture?.close()]);
}

const report = {
    generatedAt: new Date().toISOString(),
    selection: process.argv.slice(2),
    baseline: { protocol: '2026-07-28', javaSourceSha256: await sourceDigest(),
      typescriptSourceSha256: await sourceDigest(['interop/src', 'interop/fixtures', 'interop/package.json', 'interop/package-lock.json']),
      javaArtifactSha256: createHash('sha256').update(await readFile(join(ROOT, 'samples/api-server/build/libs/api-server-0.1.0-SNAPSHOT.jar'))).digest('hex'),
      specificationCommit: '5ea996028df322ce97d1c906731cf8c05a6e268e',
      schemaSha256: 'ef70b61f99b6d2e5e3b46863822eab08dff6a45bedc7a08914e0e5b133f40203',
      requirementsSha256: 'ae2f4f6210fd729e2e318edd5bbfa31a43cee0bc608e48052fa26dbf1d939b57',
      serverSdk: '2.3.1', nodeAdapter: '2.1.1' },
    referenceLanes: { native: 'official @modelcontextprotocol/server 2.3.1',
      tasks: 'official SDK transport/low-level Server with SEP-2663 diagnostic handlers; no native high-level Tasks v2 runtime' },
    summary: summarizeComparisons(results), methodCoverage, cases: results,
};
await mkdir(new URL('../artifacts/', import.meta.url), { recursive: true });
await writeFile(REPORT_ARTIFACT, JSON.stringify(report, null, 2) + '\n');
await writeFile(new URL('../artifacts/e2e-report.md', import.meta.url), renderComparisonMarkdown(report));
process.stdout.write(`E2E comparison: ${JSON.stringify(report.summary)}\nReport: ${REPORT_ARTIFACT.pathname}\n`);
const failures = results.filter(result => result.comparison === 'failed');
if (failures.length) {
  await writeFile(FAILURE_ARTIFACT, JSON.stringify({ baseline: report.baseline, failures }, null, 2));
  throw new Error(`${failures.length} differential case(s) failed; see ${FAILURE_ARTIFACT.pathname}`);
}
await rm(FAILURE_ARTIFACT, { force: true });

function select(cases: WireCase[], args: string[]): WireCase[] {
  const caseIndex = args.indexOf('--case');
  if (caseIndex >= 0) {
    const ids = new Set((args[caseIndex + 1] ?? '').split(',').filter(Boolean));
    return cases.filter(wireCase => ids.has(wireCase.id));
  }
  const groupIndex = args.indexOf('--group');
  if (groupIndex < 0) return cases;
  const prefixes: Record<string, string[]> = {
    discovery: ['discover', 'catalog'], call: ['call', 'error', 'schema', 'unknown'],
    http: ['wire', 'meta', 'http', 'security'], identity: ['identity'], limits: ['limits'], deadline: ['deadline'],
    conformance: ['call-content', 'schema-nested', 'schema-output', 'meta-required', 'http-custom'],
    expanded: ['resource', 'prompt', 'completion', 'mrtr', 'task', 'stream', 'subscription'],
  };
  const selected = (args[groupIndex + 1] ?? '').split(',').flatMap(group => prefixes[group] ?? []);
  return cases.filter(wireCase => selected.some(prefix => wireCase.id.startsWith(prefix)));
}

function sanitizedRequest(wireCase: WireCase): unknown {
  const describe = (request: WireCase['request'], token?: string) => describeWireRequest({ ...request,
    clientCapabilities: request.clientCapabilities ?? wireCase.clientCapabilities }, token ?? request.token ?? wireCase.token);
  return { template: describe(wireCase.request),
    before: (wireCase.before ?? []).map(entry => describe(entry.request, entry.token)),
    parallelBefore: (wireCase.parallelBefore ?? []).map(entry => describe(entry.request, entry.token)),
    steps: (wireCase.steps ?? []).map(entry => describe(entry.request, entry.token)),
    parallel: (wireCase.parallelRequests ?? []).map(request => describe(request)),
    resolvedRequests: 'Each endpoint observation.trace retains the actual substituted inputs and credentials are redacted.' };
}

async function sourceDigest(inputs = [
  'build.gradle', 'gradle.properties', 'settings.gradle', 'settings-gradle.lockfile', 'gradle',
  'mcp-2026-core', 'mcp-2026-spring-boot', 'mcp-2026-spring-boot-starter', 'samples/api-server',
]): Promise<string> {
  const hash = createHash('sha256');
  const files: string[] = [];
  for (const input of inputs) await collect(join(ROOT, input), files);
  for (const file of files.sort()) {
    hash.update(relative(ROOT, file));
    hash.update('\0');
    hash.update(await readFile(file));
    hash.update('\0');
  }
  return hash.digest('hex');
}

async function collect(path: string, files: string[]): Promise<void> {
  const details = await stat(path);
  if (details.isFile()) {
    files.push(path);
    return;
  }
  for (const entry of await readdir(path, { withFileTypes: true })) {
    if (entry.name === 'build' || entry.name === '.gradle') continue;
    await collect(join(path, entry.name), files);
  }
}
