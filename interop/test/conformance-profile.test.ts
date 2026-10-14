import assert from 'node:assert/strict';
import test from 'node:test';
import { classifyScenarioOutcome } from '../src/conformance-profile.ts';

const scoped = {
  scenario: 'synthetic-scoped',
  classification: 'scoped' as const,
  applicableChecks: ['applicable-check'],
  outOfProfileChecks: ['deferred-check'],
};

const completed = { exitCode: 1, signal: null, timedOut: false };
const success = { id: 'applicable-check', status: 'SUCCESS' };

test('scoped conformance treats an unknown failing check as a profile failure', () => {
  const result = classifyScenarioOutcome(scoped, [
    success,
    { id: 'new-unknown-check', status: 'FAILURE', errorMessage: 'new contract failed' },
  ], completed);

  assert.deepEqual(result.outOfProfileFailures, []);
  assert.match(result.profileFailures.join('\n'), /new-unknown-check: unexpected scoped check failed/);
});

test('scoped conformance keeps only an explicitly deferred failure out of profile', () => {
  const result = classifyScenarioOutcome(scoped, [
    success,
    { id: 'deferred-check', status: 'FAILURE', errorMessage: 'deferred contract failed' },
  ], completed);

  assert.deepEqual(result.profileFailures, []);
  assert.deepEqual(result.outOfProfileFailures, ['deferred-check: deferred contract failed']);
});

test('scoped conformance treats an applicable failing check as a profile failure', () => {
  const result = classifyScenarioOutcome(scoped, [
    { id: 'applicable-check', status: 'FAILURE', errorMessage: 'applicable contract failed' },
  ], completed);

  assert.deepEqual(result.outOfProfileFailures, []);
  assert.match(result.profileFailures.join('\n'), /applicable-check: FAILURE applicable contract failed/);
});

test('scoped conformance fails when the runner ends on a signal after successful checks', () => {
  const result = classifyScenarioOutcome(scoped, [success], {
    exitCode: null,
    signal: 'SIGTERM',
    timedOut: false,
  });

  assert.match(result.profileFailures.join('\n'), /conformance command ended on signal SIGTERM/);
});

test('scoped conformance always treats wire schema failure as in profile', () => {
  const result = classifyScenarioOutcome(scoped, [
    success,
    { id: 'wire-schema-valid', status: 'FAILURE', errorMessage: 'invalid response' },
  ], completed);

  assert.deepEqual(result.outOfProfileFailures, []);
  assert.match(result.profileFailures.join('\n'), /wire-schema-valid: invalid response/);
});

test('applicable conformance fails when every emitted check is skipped', () => {
  const result = classifyScenarioOutcome({
    scenario: 'synthetic-required',
    classification: 'applicable',
  }, [
    { id: 'required-check-a', status: 'SKIPPED', description: 'fixture was not exercised' },
    { id: 'required-check-b', status: 'SKIPPED' },
  ], {
    exitCode: 0,
    signal: null,
    timedOut: false,
  });

  assert.match(result.profileFailures.join('\n'), /required-check-a: SKIPPED fixture was not exercised/);
  assert.match(result.profileFailures.join('\n'), /required-check-b: SKIPPED/);
});

test('warnings remain visible without failing applicable or scoped checks', () => {
  const command = { exitCode: 0, signal: null, timedOut: false };

  assert.deepEqual(classifyScenarioOutcome({
    scenario: 'synthetic-required',
    classification: 'applicable',
  }, [{ id: 'advisory-check', status: 'WARNING', description: 'SHOULD guidance' }], command).profileFailures, []);
  assert.deepEqual(classifyScenarioOutcome(scoped, [
    { id: 'applicable-check', status: 'WARNING', description: 'SHOULD guidance' },
  ], command).profileFailures, []);
});

test('only the exact pinned optional Tasks notification harness skip is classified as tooling', () => {
  const entry = { scenario: 'tasks-status-notifications', classification: 'not_scored' as const };
  const check = { id: 'tasks-status-notifications', status: 'SKIPPED',
    description: 'Status notifications conformance, pending subscriptions/listen rewrite.',
    errorMessage: 'Skipped: SEP-2663 delivers notifications/tasks via the SEP-2575 subscriptions/listen stream, not via the tools/call POST SSE response that the prior harness observed. The harness needs a rewrite against the new channel; tracked as a follow-up.' };
  const command = { exitCode: 0, signal: null, timedOut: false };
  assert.deepEqual(classifyScenarioOutcome(entry, [check], command).profileFailures, []);
  assert.ok(classifyScenarioOutcome({ ...entry, scenario: 'other' }, [check], command).profileFailures.length);
  assert.ok(classifyScenarioOutcome(entry, [{ ...check, id: 'unknown' }], command).profileFailures.length);
  assert.ok(classifyScenarioOutcome({ ...entry, classification: 'applicable' }, [check], command).profileFailures.length);
});
