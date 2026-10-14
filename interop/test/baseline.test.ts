import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

test('normative inputs match their pinned digests', async () => {
  const root = new URL('../../', import.meta.url);
  const baseline = JSON.parse(await readFile(new URL('spec/baseline.json', root), 'utf8'));
  for (const [path, expected] of [
    [baseline.specification.schema, baseline.specification.schemaSha256],
    [baseline.conformance.requirements, baseline.conformance.requirementsSha256],
    ...baseline.specification.documents.map((document: { path: string; sha256: string }) =>
      [document.path, document.sha256]),
  ]) {
    const bytes = await readFile(new URL(path, root));
    assert.equal(createHash('sha256').update(bytes).digest('hex'), expected, path);
  }
  const manifest = JSON.parse(await readFile(new URL('interop/package.json', root), 'utf8'));
  assert.equal(manifest.devDependencies['@modelcontextprotocol/server'], baseline.reference.server);
  assert.equal(manifest.devDependencies['@modelcontextprotocol/conformance'], baseline.conformance.packageVersion);
});
