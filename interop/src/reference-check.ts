import { mkdir, rm, writeFile } from 'node:fs/promises';
import { buildFixtureJar, startFixture } from './processes.ts';
import { startReferenceServer } from './reference.ts';
import { validateResponseShape } from './response-oracle.ts';
import { assertWireCase, describeWireRequest, loadWireCases, runWireCase } from './wire.ts';

const RESET_KEY = 'interop-reference-reset';
const FAILURE_ARTIFACT = new URL('../artifacts/reference-failures.json', import.meta.url);

await buildFixtureJar();
let fixture: Awaited<ReturnType<typeof startFixture>> | undefined;
let reference: Awaited<ReturnType<typeof startReferenceServer>> | undefined;
let referenceConformance: Awaited<ReturnType<typeof startReferenceServer>> | undefined;
let referenceTasks: Awaited<ReturnType<typeof startReferenceServer>> | undefined;
const failures: unknown[] = [];
try {
  fixture = await startFixture(RESET_KEY);
  reference = await startReferenceServer({ fixtureUrl: fixture.url });
  const cases = await loadWireCases();
  if (cases.length === 0) throw new Error('Reference check selected zero cases');
  if (cases.some(wireCase => wireCase.profile === 'conformance')) {
    referenceConformance = await startReferenceServer({ fixtureUrl: fixture.url, conformance: true });
  }
  if (cases.some(wireCase => wireCase.profile === 'tasks')) referenceTasks = await startReferenceServer({ fixtureUrl: fixture.url, conformance: true, tasksDiagnostic: true });
  for (const wireCase of cases) {
    try {
      const target = wireCase.profile === 'tasks' ? referenceTasks : wireCase.profile === 'conformance' ? referenceConformance : reference;
      if (!target) throw new Error(`${wireCase.id}: required reference lane was not started`);
      const observed = await runWireCase(target.url, fixture.url, RESET_KEY, wireCase);
      const shapeFailures = validateResponseShape(wireCase, observed, 'reference');
      const assertions = assertWireCase(wireCase, observed, 'reference');
      if (shapeFailures.length || assertions.length) failures.push({ case: wireCase.id, requirements: wireCase.requirements,
        request: sanitizedRequest(wireCase), shapeFailures, assertions, observed });
      else process.stdout.write(`reference PASS ${wireCase.id}\n`);
    } catch (error) {
      failures.push({ case: wireCase.id, requirements: wireCase.requirements, request: sanitizedRequest(wireCase),
        error: error instanceof Error ? error.message : String(error) });
    }
  }
} finally {
  await Promise.allSettled([reference?.close(), referenceConformance?.close(), referenceTasks?.close(), fixture?.close()]);
}

if (failures.length) {
  await mkdir(new URL('../artifacts/', import.meta.url), { recursive: true });
  await writeFile(FAILURE_ARTIFACT, JSON.stringify({
    baseline: { protocol: '2026-07-28', specificationCommit: '5ea996028df322ce97d1c906731cf8c05a6e268e',
      schemaSha256: 'ef70b61f99b6d2e5e3b46863822eab08dff6a45bedc7a08914e0e5b133f40203',
      requirementsSha256: 'ae2f4f6210fd729e2e318edd5bbfa31a43cee0bc608e48052fa26dbf1d939b57',
      serverSdk: '2.3.1', nodeAdapter: '2.1.1' },
    failures,
  }, null, 2));
  throw new Error(`${failures.length} reference case(s) failed; see ${FAILURE_ARTIFACT.pathname}`);
}
await rm(FAILURE_ARTIFACT, { force: true });

function sanitizedRequest(wireCase: import('./wire.ts').WireCase): unknown {
  return describeWireRequest(wireCase.request, wireCase.token);
}
