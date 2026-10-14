export interface Observation {
  status: number;
  headers: Record<string, string>;
  body: unknown;
  state?: unknown;
  before?: readonly Observation[];
  parallelBefore?: readonly Observation[];
  messages?: readonly unknown[];
  steps?: readonly Observation[];
  parallel?: readonly Observation[];
  afterAck?: readonly Observation[];
  trace?: readonly unknown[];
  polls?: readonly unknown[];
}

export function compareObservations(expected: Observation, actual: Observation, ignorePaths: string[] = []): string[] {
  const differences: string[] = [];
  compare('$status', expected.status, actual.status, differences);
  compare('$headers', lowerCaseKeys(expected.headers), lowerCaseKeys(actual.headers), differences);
  const expectedBody = structuredClone(expected.body);
  const actualBody = structuredClone(actual.body);
  const expectedState = structuredClone(expected.state);
  const actualState = structuredClone(actual.state);
  for (const path of ignorePaths) {
    removePath(path, expectedBody, expectedState);
    removePath(path, actualBody, actualState);
  }
  compare('$body', expectedBody, actualBody, differences);
  compare('$state', expectedState, actualState, differences);
  for (const kind of ['before', 'parallelBefore', 'messages', 'steps', 'parallel', 'afterAck'] as const) {
    const left = structuredClone(expected[kind]);
    const right = structuredClone(actual[kind]);
    for (const path of ignorePaths.filter(path => path.startsWith(`$${kind}.`))) {
      removePath(path.replace(`$${kind}`, '$body'), left, undefined);
      removePath(path.replace(`$${kind}`, '$body'), right, undefined);
    }
    compare(`$${kind}`, left, right, differences);
  }
  return differences;
}

function removePath(path: string, body: unknown, state: unknown): void {
  const root = path.startsWith('$state') ? state : body;
  const parts = path.replace(/^\$(?:body|state)\.?/, '').split('.').filter(Boolean);
  let current = root;
  for (let index = 0; index < parts.length - 1; index++) {
    const key = parts[index];
    if (Array.isArray(current) && /^\d+$/.test(key)) current = current[Number(key)];
    else if (isRecord(current)) current = current[key];
    else return;
  }
  const last = parts.at(-1);
  if (last === undefined) return;
  if (Array.isArray(current) && /^\d+$/.test(last)) delete current[Number(last)];
  else if (isRecord(current)) delete current[last];
}

function compare(path: string, expected: unknown, actual: unknown, differences: string[]): void {
  if (Object.is(expected, actual)) return;
  if (Array.isArray(expected) && Array.isArray(actual)) {
    if (expected.length !== actual.length) differences.push(`${path}.length: ${expected.length} != ${actual.length}`);
    for (let index = 0; index < Math.min(expected.length, actual.length); index++) {
      compare(`${path}[${index}]`, expected[index], actual[index], differences);
    }
    return;
  }
  if (isRecord(expected) && isRecord(actual)) {
    for (const key of new Set([...Object.keys(expected), ...Object.keys(actual)])) {
      if (!(key in expected)) differences.push(`${path}.${key}: unexpected field`);
      else if (!(key in actual)) differences.push(`${path}.${key}: missing field`);
      else compare(`${path}.${key}`, expected[key], actual[key], differences);
    }
    return;
  }
  differences.push(`${path}: ${display(expected)} != ${display(actual)}`);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function lowerCaseKeys(headers: Record<string, string>): Record<string, string> {
  return Object.fromEntries(Object.entries(headers).map(([name, value]) => [name.toLowerCase(), value]));
}

function display(value: unknown): string {
  const serialized = JSON.stringify(value);
  return serialized === undefined ? String(value) : serialized;
}
