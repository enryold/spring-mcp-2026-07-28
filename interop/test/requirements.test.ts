import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const ROOT = new URL('../../', import.meta.url);

test('profile requirements remain source-backed and cover P01 through P18', async () => {
  const matrix = JSON.parse(await readFile(new URL('interop/fixtures/requirements.json', ROOT), 'utf8'));
  const applicability = JSON.parse(await readFile(
    new URL('interop/fixtures/conformance-applicability.json', ROOT), 'utf8'));
  const conformancePackage = JSON.parse(await readFile(
    new URL('interop/node_modules/@modelcontextprotocol/conformance/package.json', ROOT), 'utf8'));
  assert.equal(conformancePackage.version, applicability.runner.version,
    'official check inventory must match the installed pinned conformance package');
  const caseFiles = ['wire-cases.json', 'catalog-cases.json', 'streaming-cases.json', 'interaction-cases.json'];
  const wireCases = (await Promise.all(caseFiles.map(async file => JSON.parse(await readFile(
    new URL(`interop/fixtures/${file}`, ROOT), 'utf8')) as Array<{ id: string; requirements: string[] }>))).flat();
  const wireById = new Map(wireCases.map(wireCase => [wireCase.id, wireCase]));
  assert.equal(wireById.size, wireCases.length, 'wire case ids must be unique');
  const schema = JSON.parse(await readFile(new URL('spec/upstream/schema.json', ROOT), 'utf8'));
  const serverMethods: string[] = schema.$defs.ClientRequest.anyOf.map((entry: { $ref: string }) =>
    schema.$defs[entry.$ref.split('/').at(-1)!].properties.method.const);
  const concreteCases = wireCases as Array<{ id: string; requirements: string[];
    request: { mcpMethod?: string } }>;
  for (const method of serverMethods) {
    assert.ok(concreteCases.some(wireCase => wireCase.request.mcpMethod === method),
      `frozen server request ${method} has no raw availability/behavior case`);
  }
  const junitTests = await collectJUnitTests();
  const officialChecks = collectApplicableOfficialChecks(applicability);
  const ids = matrix.requirements.map((requirement: { id: string }) => requirement.id);
  assert.equal(new Set(ids).size, ids.length, 'requirement ids must be unique');
  const requirementIds = new Set(ids);
  assert.deepEqual(
    [...new Set(matrix.requirements.map((requirement: { profileId: string }) => requirement.profileId))].sort(),
    Array.from({ length: 18 }, (_, index) => `P${String(index + 1).padStart(2, '0')}`),
  );

  for (const requirement of matrix.requirements as Array<{
    id: string;
    profileId: string;
    strength: string;
    source: { path: string; lines?: string };
    assertion: string;
    inventoryOnly: string[];
    applicability?: { status: string; reason: string };
    evidence: {
      wireCases: string[];
      jvmTests: string[];
      officialChecks: string[];
      openGaps?: string[];
    };
  }>) {
    assert.match(requirement.id, /^P(?:0[1-9]|1[0-8])\.\d{2}$/);
    assert.equal(requirement.id.slice(0, 3), requirement.profileId);
    assert.ok(['MUST', 'SHOULD', 'PROFILE_MUST'].includes(requirement.strength));
    assert.ok(requirement.assertion.length > 0, `${requirement.id} assertion`);
    assert.ok(requirement.inventoryOnly.length > 0, `${requirement.id} inventory`);
    assert.equal(new Set(requirement.inventoryOnly).size, requirement.inventoryOnly.length,
      `${requirement.id} duplicate inventory entries`);
    const source = await readFile(new URL(requirement.source.path, ROOT), 'utf8');
    if (requirement.source.lines !== undefined) {
      for (const range of requirement.source.lines.split(',').map(value => value.trim())) {
        const match = /^(\d+)-(\d+)$/.exec(range);
        assert.ok(match, `${requirement.id} line range`);
        assert.ok(Number(match[1]) >= 1 && Number(match[2]) <= source.split('\n').length,
          `${requirement.id} source line range exists`);
      }
    }

    const evidence = requirement.evidence;
    assert.ok(evidence && Array.isArray(evidence.wireCases)
      && Array.isArray(evidence.jvmTests) && Array.isArray(evidence.officialChecks),
    `${requirement.id} has typed executable evidence`);
    for (const [kind, values] of Object.entries({
      wireCases: evidence.wireCases,
      jvmTests: evidence.jvmTests,
      officialChecks: evidence.officialChecks,
    })) {
      assert.equal(new Set(values).size, values.length, `${requirement.id} duplicate ${kind}`);
    }
    for (const wireId of evidence.wireCases) {
      const wireCase = wireById.get(wireId);
      assert.ok(wireCase, `${requirement.id} unknown wire case ${wireId}`);
      assert.ok(wireCase.requirements.includes(requirement.id),
        `${wireId} must reciprocally tag ${requirement.id}`);
    }
    for (const jvmTest of evidence.jvmTests) {
      assert.ok(junitTests.has(jvmTest), `${requirement.id} unknown JVM test ${jvmTest}`);
    }
    for (const checkId of evidence.officialChecks) {
      assert.ok(officialChecks.has(checkId), `${requirement.id} unknown official check ${checkId}`);
    }

    const conditionalNotTriggered = requirement.applicability?.status === 'conditional_not_triggered';
    if (conditionalNotTriggered) {
      assert.ok(requirement.applicability?.reason, `${requirement.id} conditional reason`);
    } else if (requirement.strength === 'MUST' || requirement.strength === 'PROFILE_MUST') {
      assert.ok(evidence.wireCases.length + evidence.jvmTests.length + evidence.officialChecks.length > 0,
        `${requirement.id} has no executable evidence`);
      assert.deepEqual(evidence.openGaps ?? [], [], `${requirement.id} has unresolved evidence gaps`);
    }
  }

  for (const wireCase of wireCases) {
    assert.ok(wireCase.requirements.length > 0, `${wireCase.id} has no granular requirement tag`);
    assert.equal(new Set(wireCase.requirements).size, wireCase.requirements.length,
      `${wireCase.id} duplicate requirement tags`);
    for (const requirementId of wireCase.requirements) {
      assert.match(requirementId, /^P(?:0[1-9]|1[0-8])\.\d{2}$/,
        `${wireCase.id} uses coarse requirement tag ${requirementId}`);
      assert.ok(requirementIds.has(requirementId), `${wireCase.id} unknown requirement ${requirementId}`);
      const requirement = matrix.requirements.find((candidate: { id: string }) => candidate.id === requirementId);
      assert.ok(requirement.evidence.wireCases.includes(wireCase.id),
        `${wireCase.id} is not reciprocal evidence for ${requirementId}`);
    }
  }
});

function collectApplicableOfficialChecks(applicability: {
  server: {
    applicableRequired: Array<{ applicableChecks?: string[] }>;
    scopedRequired: Array<{ applicableChecks: string[] }>;
    notScored: { applicable: Array<{ applicableChecks?: string[] }> };
  };
}): Set<string> {
  return new Set([
    ...applicability.server.applicableRequired.flatMap(entry => entry.applicableChecks ?? []),
    ...applicability.server.scopedRequired.flatMap(entry => entry.applicableChecks),
    ...applicability.server.notScored.applicable.flatMap(entry => entry.applicableChecks ?? []),
  ]);
}

async function collectJUnitTests(): Promise<Set<string>> {
  const roots = [
    'mcp-2026-core/src/test/java',
    'mcp-2026-spring-boot/src/test/java',
    'samples/api-server/src/test/java',
  ];
  const tests = new Set<string>();
  for (const relativeRoot of roots) {
    const directory = fileURLToPath(new URL(relativeRoot, ROOT));
    const entries = await readdir(directory, { recursive: true, withFileTypes: true });
    for (const entry of entries) {
      if (!entry.isFile() || !entry.name.endsWith('.java')) continue;
      const source = await readFile(path.join(entry.parentPath, entry.name), 'utf8');
      const packageName = /^package\s+([\w.]+);/m.exec(source)?.[1];
      const className = /\bclass\s+(\w+)/.exec(source)?.[1];
      if (!packageName || !className) continue;
      for (const match of source.matchAll(
        /@Test(?:\s+@\w+(?:\([^)]*\))?)*\s+(?:public\s+)?void\s+(\w+)\s*\(/g,
      )) {
        tests.add(`${packageName}.${className}#${match[1]}`);
      }
    }
  }
  return tests;
}

test('conformance applicability exactly partitions the frozen source inventory', async () => {
  const applicability = JSON.parse(await readFile(
    new URL('interop/fixtures/conformance-applicability.json', ROOT), 'utf8'));
  const yaml = await readFile(new URL('spec/upstream/requirements.yaml', ROOT), 'utf8');
  const source = parseRequirements(yaml);

  const requiredServer = [
    ...applicability.server.applicableRequired.map((entry: { scenario: string }) => entry.scenario),
    ...applicability.server.scopedRequired.map((entry: { scenario: string }) => entry.scenario),
    ...applicability.server.unsupportedRequired.map((entry: { scenario: string }) => entry.scenario),
  ];
  assert.equal(new Set(requiredServer).size, requiredServer.length, 'server partition has duplicates');
  assert.equal(applicability.server.officialRequiredCount, source.server.length);
  assert.deepEqual([...requiredServer].sort(), [...source.server].sort());

  assert.equal(applicability.client.officialRequiredCount, source.client.length);
  assert.deepEqual([...applicability.client.officialRequired].sort(), [...source.client].sort());

  const mappedNotScored = [
    ...applicability.server.notScored.applicable.map(
      (entry: { scenario: string; reason: string }) => `${entry.scenario}|server|${entry.reason}`),
    ...applicability.server.notScored.unsupported.map(
      (entry: { scenario: string; reason: string }) => `${entry.scenario}|server|${entry.reason}`),
    ...applicability.client.notScored.map(
      (entry: { scenario: string; reason: string }) => `${entry.scenario}|client|${entry.reason}`),
  ].sort();
  assert.deepEqual(mappedNotScored, source.notScored
    .map(entry => `${entry.scenario}|${entry.leg}|${entry.reason}`).sort());

  for (const scoped of applicability.server.scopedRequired as Array<{
    scenario: string;
    applicableChecks: string[];
    outOfProfileChecks: string[];
  }>) {
    assert.ok(scoped.applicableChecks.length > 0, `${scoped.scenario} applicable checks`);
    assert.equal(new Set(scoped.applicableChecks).size, scoped.applicableChecks.length);
    assert.equal(new Set(scoped.outOfProfileChecks).size, scoped.outOfProfileChecks.length);
    assert.deepEqual(
      scoped.applicableChecks.filter(check => scoped.outOfProfileChecks.includes(check)),
      [],
      `${scoped.scenario} check classifications overlap`,
    );
  }
});

function parseRequirements(yaml: string): {
  server: string[];
  client: string[];
  notScored: Array<{ scenario: string; leg: string; reason: string }>;
} {
  const server: string[] = [];
  const client: string[] = [];
  const notScored: Array<{ scenario: string; leg: string; reason: string }> = [];
  let section: 'server' | 'client' | 'not_scored' | null = null;
  let current: Partial<{ scenario: string; leg: string; reason: string }> | null = null;

  for (const line of yaml.split('\n')) {
    const heading = /^(server|client|not_scored):$/.exec(line);
    if (heading) {
      if (current?.scenario && current.leg && current.reason) {
        notScored.push({ scenario: current.scenario, leg: current.leg, reason: current.reason });
      }
      current = null;
      section = heading[1] as 'server' | 'client' | 'not_scored';
      continue;
    }
    if (section === 'server' || section === 'client') {
      const item = /^  - (\S+)$/.exec(line)?.[1];
      if (item) (section === 'server' ? server : client).push(item);
      continue;
    }
    if (section === 'not_scored') {
      const scenario = /^  - scenario: (\S+)$/.exec(line)?.[1];
      if (scenario) {
        if (current?.scenario && current.leg && current.reason) {
          notScored.push({ scenario: current.scenario, leg: current.leg, reason: current.reason });
        }
        current = { scenario };
        continue;
      }
      const field = /^    (leg|reason): (\S+)$/.exec(line);
      if (field && current) current[field[1] as 'leg' | 'reason'] = field[2];
    }
  }
  if (current?.scenario && current.leg && current.reason) {
    notScored.push({ scenario: current.scenario, leg: current.leg, reason: current.reason });
  }
  return { server, client, notScored };
}
