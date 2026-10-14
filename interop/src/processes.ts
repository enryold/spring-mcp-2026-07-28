import { spawn, type ChildProcessByStdio } from 'node:child_process';
import { createServer } from 'node:net';
import type { Readable } from 'node:stream';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const JAR = fileURLToPath(new URL('../../samples/api-server/build/libs/api-server-0.1.0-SNAPSHOT.jar', import.meta.url));

export async function buildFixtureJar(): Promise<void> {
  await run(fileURLToPath(new URL('../../gradlew', import.meta.url)), [':api-server:bootJar'], ROOT);
}

export async function startFixture(resetKey: string, extraArgs: string[] = []) {
  const port = await freePort();
  const process = spawn('java', [
    '-jar', JAR,
    `--server.port=${port}`,
    `--fixture.reset-key=${resetKey}`,
    `--fixture.base-url=http://127.0.0.1:${port}`,
    '--enryold.mcp.enabled=true',
    '--enryold.mcp.server-name=spring-mcp-sample',
    '--enryold.mcp.server-version=0.1.0',
    '--enryold.mcp.max-request-bytes=65536',
    '--enryold.mcp.max-json-depth=64',
    '--spring.main.banner-mode=off',
    ...extraArgs,
  ], { cwd: ROOT, stdio: ['ignore', 'pipe', 'pipe'] });
  const output = capture(process);
  const url = `http://127.0.0.1:${port}`;
  try {
    await ready(`${url}/api/echo?text=ready`, { Authorization: 'Bearer api-alice-read' }, process, output);
  } catch (error) {
    await stop(process);
    throw error;
  }
  return { port, url, close: () => stop(process) };
}

async function run(command: string, args: string[], cwd: string): Promise<void> {
  const process = spawn(command, args, { cwd, stdio: 'inherit' });
  const code = await new Promise<number | null>((resolve, reject) => {
    process.once('error', reject);
    process.once('exit', resolve);
  });
  if (code !== 0) throw new Error(`${command} exited with ${code}`);
}

async function freePort(): Promise<number> {
  const server = createServer();
  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const address = server.address();
  if (address === null || typeof address === 'string') throw new Error('Unable to reserve a TCP port');
  await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
  return address.port;
}

type FixtureProcess = ChildProcessByStdio<null, Readable, Readable>;

function capture(process: FixtureProcess): () => string {
  let output = '';
  const append = (chunk: Buffer) => { output = (output + chunk.toString('utf8')).slice(-8_192); };
  process.stdout.on('data', append);
  process.stderr.on('data', append);
  return () => output;
}

async function ready(url: string, headers: Record<string, string>, process: FixtureProcess,
    output: () => string): Promise<void> {
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    if (process.exitCode !== null || process.signalCode !== null) {
      throw new Error(`Fixture exited during startup (${process.exitCode ?? process.signalCode})\n${output()}`);
    }
    try {
      const response = await fetch(url, { headers, signal: AbortSignal.timeout(500) });
      if (response.ok) return;
    } catch { /* Readiness GETs are safe to repeat while the process starts. */ }
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  throw new Error(`Fixture readiness timed out\n${output()}`);
}

async function stop(process: FixtureProcess): Promise<void> {
  if (process.exitCode !== null || process.signalCode !== null) return;
  process.kill('SIGTERM');
  const exited = new Promise<void>(resolve => process.once('exit', () => resolve()));
  const timedOut = await Promise.race([
    exited.then(() => false),
    new Promise<boolean>(resolve => setTimeout(() => resolve(true), 5_000)),
  ]);
  if (timedOut && process.exitCode === null) {
    process.kill('SIGKILL');
    await exited;
  }
}
