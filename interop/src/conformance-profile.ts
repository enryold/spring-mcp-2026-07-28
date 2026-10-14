import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { buildFixtureJar, startFixture } from './processes.js';
import { startReferenceServer } from './reference.js';

const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const INTEROP = fileURLToPath(new URL('../', import.meta.url));
const CONFORMANCE = fileURLToPath(
  new URL('../node_modules/@modelcontextprotocol/conformance/dist/index.js', import.meta.url),
);
const REVISION = '2026-07-28';
const JAVA_ARTIFACT = path.join(ROOT, 'samples/api-server/build/libs/api-server-0.1.0-SNAPSHOT.jar');
const SCENARIO_TIMEOUT_MS = 15_000;
const FULL_RUN_TIMEOUT_MS = 180_000;

type Check = {
  id: string;
  name?: string;
  status: string;
  description?: string;
  errorMessage?: string;
  [key: string]: unknown;
};

type ScenarioEntry = {
  scenario: string;
  fixture?: string;
  reason?: string;
};

type ScopedEntry = ScenarioEntry & {
  applicableChecks: string[];
  outOfProfileChecks: string[];
};

type Applicability = {
  protocolVersion: string;
  profile: string;
  runner: { package: string; version: string; repositoryCommit: string; requirementsSha256: string };
  server: {
    applicableRequired: ScenarioEntry[];
    scopedRequired: ScopedEntry[];
    unsupportedRequired: ScenarioEntry[];
    notScored: { applicable: ScenarioEntry[]; unsupported: ScenarioEntry[] };
  };
};

type CommandResult = {
  exitCode: number | null;
  signal: NodeJS.Signals | null;
  timedOut: boolean;
  stdout: string;
  stderr: string;
};

type ScenarioResult = {
  scenario: string;
  classification: 'applicable' | 'scoped' | 'not_scored';
  command: Omit<CommandResult, 'stdout' | 'stderr'>;
  checksFile: string | null;
  checks: Check[];
  profileFailures: string[];
  outOfProfileFailures: string[];
  affectsFrozenScore: boolean;
  toolingSkips?: Check[];
  referenceLane?: string;
};

type ProfileScenario = (ScenarioEntry & { classification: 'applicable' | 'not_scored' })
  | (ScopedEntry & { classification: 'scoped' });

async function main(): Promise<void> {
  const [applicabilityBytes, baselineBytes, manifestBytes] = await Promise.all([
    readFile(path.join(ROOT, 'interop/fixtures/conformance-applicability.json')),
    readFile(path.join(ROOT, 'spec/baseline.json')),
    readFile(path.join(ROOT, 'interop/package.json')),
  ]);
  const applicability = JSON.parse(applicabilityBytes.toString('utf8')) as Applicability;
  const baseline = JSON.parse(baselineBytes.toString('utf8')) as {
    protocolVersion: string;
    specification: { commit: string; schemaSha256: string };
    conformance: { requirementsSha256: string };
  };
  const manifest = JSON.parse(manifestBytes.toString('utf8')) as {
    version: string;
    devDependencies: Record<string, string>;
  };
  if (applicability.protocolVersion !== REVISION) {
    throw new Error(`Applicability map targets ${applicability.protocolVersion}, expected ${REVISION}`);
  }
  if (baseline.protocolVersion !== REVISION) {
    throw new Error(`Normative baseline targets ${baseline.protocolVersion}, expected ${REVISION}`);
  }
  const conformanceVersion = manifest.devDependencies['@modelcontextprotocol/conformance'];
  if (conformanceVersion !== applicability.runner.version) {
    throw new Error(`Installed conformance manifest pin ${conformanceVersion} does not match applicability ${applicability.runner.version}`);
  }

  const filter = parseFilter(process.argv.slice(2));
  const scenarios = selectScenarios(applicability, filter);
  const stamp = new Date().toISOString().replaceAll(':', '-').replaceAll('.', '-');
  const artifactRoot = path.join(INTEROP, 'artifacts', 'conformance', stamp);
  await mkdir(artifactRoot, { recursive: true });

  let profileFailed = false;
  let runnerFailed = false;
  let fixture: Awaited<ReturnType<typeof startFixture>> | undefined;
  let reference: Awaited<ReturnType<typeof startReferenceServer>> | undefined;
  let referenceTasks: Awaited<ReturnType<typeof startReferenceServer>> | undefined;
  await buildFixtureJar();
  const [javaSourceSha256, typescriptSourceSha256, javaArtifactSha256] = await Promise.all([
    sourceFingerprint([
      'build.gradle', 'settings.gradle', 'gradle.properties', 'settings-gradle.lockfile',
      'mcp-2026-core/src', 'mcp-2026-spring-boot/src', 'mcp-2026-spring-boot-starter/src',
      'samples/api-server/src',
    ]),
    sourceFingerprint(['interop/src', 'interop/fixtures', 'interop/package.json', 'interop/package-lock.json']),
    sha256File(JAVA_ARTIFACT),
  ]);
  const report: Record<string, unknown> = {
    generatedAt: new Date().toISOString(),
    protocolVersion: REVISION,
    profile: applicability.profile,
    applicability: 'interop/fixtures/conformance-applicability.json',
    runner: {
      ...applicability.runner,
      packageVersionFromManifest: conformanceVersion,
    },
    evidence: {
      baseline: {
        path: 'spec/baseline.json',
        sha256: sha256(baselineBytes),
        specificationCommit: baseline.specification.commit,
        schemaSha256: baseline.specification.schemaSha256,
        requirementsSha256: baseline.conformance.requirementsSha256,
      },
      toolchain: {
        interopPackageVersion: manifest.version,
        serverSdk: manifest.devDependencies['@modelcontextprotocol/server'],
        clientSdk: manifest.devDependencies['@modelcontextprotocol/client'],
        nodeAdapter: manifest.devDependencies['@modelcontextprotocol/node'],
        conformance: conformanceVersion,
      },
      fingerprints: {
        algorithm: 'sha256 over sorted relative path, byte length, and file bytes',
        javaSourceTreeSha256: javaSourceSha256,
        javaArtifact: path.relative(ROOT, JAVA_ARTIFACT),
        javaArtifactSha256,
        typescriptSourceTreeSha256: typescriptSourceSha256,
      },
    },
    filter: filter === null ? null : [...filter],
    unsupportedRequired: applicability.server.unsupportedRequired,
    unsupportedNotScored: applicability.server.notScored.unsupported,
    implementations: {},
  };

  try {
    fixture = await startFixture(`conformance-${Date.now()}`, [
      '--spring.profiles.active=conformance',
      '--enryold.mcp.allowed-origins[0]=http://127.0.0.1:${server.port}',
    ]);
    reference = await startReferenceServer({ fixtureUrl: fixture.url, conformance: true });
    if (scenarios.some(entry => entry.scenario.startsWith('tasks-'))) referenceTasks = await startReferenceServer({
      fixtureUrl: fixture.url, conformance: true, tasksDiagnostic: true });
    for (const target of [
      { name: 'java', url: `${fixture.url}/mcp` },
      { name: 'typescript', url: reference.url },
    ]) {
      const targetRoot = path.join(artifactRoot, target.name);
      const profileRoot = path.join(targetRoot, 'profile');
      const results: ScenarioResult[] = [];
      for (const scenario of scenarios) {
        const tasksLane = target.name === 'typescript' && scenario.scenario.startsWith('tasks-');
        const result = await runScenario(tasksLane ? referenceTasks!.url : target.url, profileRoot, scenario);
        result.referenceLane = tasksLane ? 'official SDK transport/low-level Server with pinned SEP-2663 diagnostic handlers'
          : target.name === 'typescript' ? 'native official SDK' : 'Java';
        results.push(result);
        if (result.profileFailures.length > 0) profileFailed = true;
        const mark = result.toolingSkips?.length && result.profileFailures.length === 0 ? 'TOOLING_SKIP' : scenario.classification === 'not_scored'
          ? result.profileFailures.length === 0 ? 'OBSERVED_PASS' : 'OBSERVED_FAIL'
          : result.profileFailures.length === 0 ? 'PASS' : 'FAIL';
        process.stdout.write(`${target.name} ${scenario.scenario}: ${mark}\n`);
      }

      const fullRevision = filter === null
        ? await runFullRevision(target.url, path.join(targetRoot, 'full-revision'), target.name === 'typescript'
          ? applicability.server.notScored.applicable.filter(entry => entry.reason === 'extension').map(entry => entry.scenario) : [])
        : { skipped: true, reason: '--filter requested a targeted profile run' };
      if ('executionFailures' in fullRevision && fullRevision.executionFailures.length > 0) {
        runnerFailed = true;
      }
      if ('unexpectedFailures' in fullRevision && fullRevision.unexpectedFailures.length > 0) profileFailed = true;
      (report.implementations as Record<string, unknown>)[target.name] = {
        url: target.url,
        sourceTreeSha256: target.name === 'java' ? javaSourceSha256 : typescriptSourceSha256,
        profileScenarios: results,
        fullRevision,
      };
    }
  } finally {
    await Promise.allSettled([reference?.close(), referenceTasks?.close(), fixture?.close()]);
  }

  const reportPath = path.join(artifactRoot, 'report.json');
  await writeFile(reportPath, `${JSON.stringify(report, null, 2)}\n`);
  process.stdout.write(`Conformance report: ${path.relative(ROOT, reportPath)}\n`);
  if (profileFailed || runnerFailed) process.exitCode = 1;
}

function selectScenarios(applicability: Applicability, filter: Set<string> | null) {
  const all = [
    ...applicability.server.applicableRequired.map(entry => ({ ...entry, classification: 'applicable' as const })),
    ...applicability.server.scopedRequired.map(entry => ({ ...entry, classification: 'scoped' as const })),
    ...applicability.server.notScored.applicable.map(entry => ({ ...entry, classification: 'not_scored' as const })),
  ];
  if (filter === null) return all;
  const known = new Set(all.map(entry => entry.scenario));
  const unknown = [...filter].filter(name => !known.has(name));
  if (unknown.length > 0) throw new Error(`Unknown profile scenario(s): ${unknown.join(', ')}`);
  return all.filter(entry => filter.has(entry.scenario));
}

function parseFilter(args: string[]): Set<string> | null {
  if (args.length === 0) return null;
  if (args.length !== 2 || args[0] !== '--filter' || args[1].trim() === '') {
    throw new Error('Usage: conformance-profile.ts [--filter scenario-a,scenario-b]');
  }
  const filter = new Set(args[1].split(',').map(value => value.trim()).filter(Boolean));
  if (filter.size === 0) throw new Error('--filter must name at least one scenario');
  return filter;
}

async function runScenario(
  serverUrl: string,
  outputRoot: string,
  entry: ProfileScenario,
): Promise<ScenarioResult> {
  const outputDir = path.join(outputRoot, entry.scenario);
  await mkdir(outputDir, { recursive: true });
  const command = await runCommand(CONFORMANCE, [
    'server', '--url', serverUrl,
    '--scenario', entry.scenario,
    '--spec-version', REVISION,
    ...(entry.reason === 'extension' ? ['--force'] : []),
    '--timeout', String(SCENARIO_TIMEOUT_MS),
    '--output-dir', outputDir,
  ], SCENARIO_TIMEOUT_MS + 10_000);
  await writeLogs(outputDir, command);
  const checksFiles = await findChecks(outputDir);
  const checks = checksFiles.length === 0
    ? []
    : JSON.parse(await readFile(checksFiles[0], 'utf8')) as Check[];
  const classified = classifyScenarioOutcome(entry, checks, command);

  return {
    scenario: entry.scenario,
    classification: entry.classification,
    command: { exitCode: command.exitCode, signal: command.signal, timedOut: command.timedOut },
    checksFile: checksFiles[0] ? path.relative(ROOT, checksFiles[0]) : null,
    checks,
    ...classified,
    toolingSkips: checks.filter(check => isPinnedToolingSkip(entry, check)),
    affectsFrozenScore: entry.classification !== 'not_scored',
  };
}

export function classifyScenarioOutcome(
  entry: ProfileScenario,
  checks: Check[],
  command: Pick<CommandResult, 'exitCode' | 'signal' | 'timedOut'>,
): Pick<ScenarioResult, 'profileFailures' | 'outOfProfileFailures'> {
  const failures = checks.filter(check => check.status === 'FAILURE');
  const skipped = checks.filter(check => check.status === 'SKIPPED');
  const profileFailures: string[] = [];
  const outOfProfileFailures: string[] = [];

  if (entry.classification === 'applicable') {
    if (checks.length === 0) profileFailures.push('scenario emitted no checks');
    profileFailures.push(...failures.map(check => `${check.id}: ${check.errorMessage ?? check.description ?? 'failed'}`));
    profileFailures.push(...skipped
      .map(check => `${check.id}: SKIPPED ${check.errorMessage ?? check.description ?? ''}`.trim()));
    if (command.signal === null && (command.exitCode ?? 1) !== 0 && failures.length === 0) {
      profileFailures.push(`conformance command exited ${command.exitCode ?? 'without status'}`);
    }
  } else if (entry.classification === 'scoped') {
    for (const checkId of entry.applicableChecks) {
      const matching = checks.filter(check => check.id === checkId);
      if (matching.length === 0) {
        profileFailures.push(`${checkId}: expected applicable check was not emitted`);
      } else {
        profileFailures.push(...matching
          .filter(check => check.status === 'FAILURE' || check.status === 'SKIPPED')
          .map(check => `${check.id}: ${check.status} ${check.errorMessage ?? check.description ?? ''}`.trim()));
      }
    }
    profileFailures.push(...failures
      .filter(check => check.id === 'wire-schema-valid')
      .map(check => `${check.id}: ${check.errorMessage ?? check.description ?? 'failed'}`));
    outOfProfileFailures.push(...failures
      .filter(check => entry.outOfProfileChecks.includes(check.id))
      .map(check => `${check.id}: ${check.errorMessage ?? check.description ?? 'failed'}`));
    profileFailures.push(...failures
      .filter(check => !entry.applicableChecks.includes(check.id)
        && !entry.outOfProfileChecks.includes(check.id)
        && check.id !== 'wire-schema-valid')
      .map(check => `${check.id}: unexpected scoped check failed: ${check.errorMessage ?? check.description ?? 'failed'}`));
    if (command.signal === null && (command.exitCode ?? 1) !== 0 && failures.length === 0) {
      profileFailures.push(`conformance command exited ${command.exitCode ?? 'without status'}`);
    }
  } else {
    if (checks.length === 0) profileFailures.push('scenario emitted no checks');
    profileFailures.push(...failures
      .map(check => `${check.id}: ${check.errorMessage ?? check.description ?? 'failed'}`));
    profileFailures.push(...skipped.filter(check => !isPinnedToolingSkip(entry, check))
      .map(check => `${check.id}: SKIPPED ${check.errorMessage ?? check.description ?? ''}`.trim()));
    if (command.signal === null && (command.exitCode ?? 1) !== 0 && failures.length === 0) {
      profileFailures.push(`conformance command exited ${command.exitCode ?? 'without status'}`);
    }
  }

  if (command.signal !== null) {
    profileFailures.push(`conformance command ended on signal ${command.signal}`);
  }
  if (command.timedOut) profileFailures.push('conformance command exceeded its process deadline');
  return { profileFailures, outOfProfileFailures };
}

function isPinnedToolingSkip(entry: ProfileScenario, check: Check): boolean {
  // Frozen conformance commit's notifications.ts deliberately never exercises this optional scenario.
  return entry.classification === 'not_scored' && entry.scenario === 'tasks-status-notifications'
    && check.id === 'tasks-status-notifications' && check.status === 'SKIPPED'
    && check.description === 'Status notifications conformance, pending subscriptions/listen rewrite.'
    && check.errorMessage === 'Skipped: SEP-2663 delivers notifications/tasks via the SEP-2575 subscriptions/listen stream, not via the tools/call POST SSE response that the prior harness observed. The harness needs a rewrite against the new channel; tracked as a follow-up.';
}

async function runFullRevision(serverUrl: string, outputDir: string, nativeSdkExtensions: string[]) {
  await mkdir(outputDir, { recursive: true });
  const command = await runCommand(CONFORMANCE, [
    'server', '--url', serverUrl,
    '--requirements', REVISION,
    '--timeout', '5000',
    '--output-dir', outputDir,
  ], FULL_RUN_TIMEOUT_MS);
  await writeLogs(outputDir, command);
  const files = await findChecks(outputDir);
  const scenarios = [];
  for (const file of files) {
    const checks = JSON.parse(await readFile(file, 'utf8')) as Check[];
    scenarios.push({
      checksFile: path.relative(ROOT, file),
      failures: checks.filter(check => check.status === 'FAILURE'),
      warnings: checks.filter(check => check.status === 'WARNING'),
      successes: checks.filter(check => check.status === 'SUCCESS').length,
      skipped: checks.filter(check => check.status === 'SKIPPED'),
      nativeSdkExtensionUnavailable: nativeSdkExtensions.some(name => path.basename(path.dirname(file)).startsWith(`server-${name}-`)),
    });
  }
  const executionFailures: string[] = [];
  if (command.timedOut) executionFailures.push('full revision command exceeded its process deadline');
  if (command.signal !== null) executionFailures.push(`full revision command ended on signal ${command.signal}`);
  if (files.length === 0) executionFailures.push('full revision command emitted no scenario reports');
  return {
    command: { exitCode: command.exitCode, signal: command.signal, timedOut: command.timedOut },
    scenarioReports: scenarios,
    executionFailures,
    unexpectedFailures: scenarios.filter(scenario => !scenario.nativeSdkExtensionUnavailable)
      .flatMap(scenario => scenario.failures.map(check => ({ checksFile: scenario.checksFile, ...check }))),
    nativeSdkLimitation: nativeSdkExtensions.length ? 'Pinned high-level SDK has no Tasks v2 runtime; full native observations remain here. Tasks profile scenarios run separately through the documented low-level SDK diagnostic lane.' : null,
  };
}

async function sourceFingerprint(entries: string[]): Promise<string> {
  const files: string[] = [];
  for (const entry of entries) {
    const absolute = path.join(ROOT, entry);
    try {
      const children = await readdir(absolute, { recursive: true, withFileTypes: true });
      files.push(...children.filter(child => child.isFile()).map(child => path.join(child.parentPath, child.name)));
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === 'ENOTDIR') files.push(absolute);
      else if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error;
    }
  }
  const digest = createHash('sha256');
  for (const file of [...new Set(files)].sort()) {
    const bytes = await readFile(file);
    const relative = path.relative(ROOT, file);
    digest.update(`${relative}\0${bytes.length}\0`);
    digest.update(bytes);
    digest.update('\0');
  }
  return digest.digest('hex');
}

async function sha256File(file: string): Promise<string> {
  return sha256(await readFile(file));
}

function sha256(bytes: Uint8Array): string {
  return createHash('sha256').update(bytes).digest('hex');
}

async function runCommand(command: string, args: string[], timeoutMs: number): Promise<CommandResult> {
  return await new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd: ROOT, stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '';
    let stderr = '';
    let timedOut = false;
    child.stdout.on('data', chunk => { stdout += chunk.toString('utf8'); });
    child.stderr.on('data', chunk => { stderr += chunk.toString('utf8'); });
    child.once('error', reject);
    const timeout = setTimeout(() => {
      timedOut = true;
      child.kill('SIGTERM');
      setTimeout(() => child.kill('SIGKILL'), 5_000).unref();
    }, timeoutMs);
    child.once('close', (exitCode, signal) => {
      clearTimeout(timeout);
      resolve({ exitCode, signal, timedOut, stdout, stderr });
    });
  });
}

async function writeLogs(outputDir: string, result: CommandResult): Promise<void> {
  await Promise.all([
    writeFile(path.join(outputDir, 'stdout.txt'), result.stdout),
    writeFile(path.join(outputDir, 'stderr.txt'), result.stderr),
  ]);
}

async function findChecks(directory: string): Promise<string[]> {
  const entries = await readdir(directory, { recursive: true, withFileTypes: true });
  return entries
    .filter(entry => entry.isFile() && entry.name === 'checks.json')
    .map(entry => path.join(entry.parentPath, entry.name))
    .sort();
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await main();
}
