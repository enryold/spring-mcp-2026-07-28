import { randomBytes } from 'node:crypto';
import {
  inputRequired, McpServer, MissingRequiredClientCapabilityError, ProtocolError,
  type CallToolResult, type InputRequest, type Server, type ServerContext,
} from '@modelcontextprotocol/server';
import * as z from 'zod/v4';

export const TASKS_EXTENSION_ID = 'io.modelcontextprotocol/tasks';
export interface InteractionIdentity { subject: string; tenantId: string }
type JsonObject = Record<string, unknown>;
type Status = 'working' | 'input_required' | 'completed' | 'failed' | 'cancelled';
type Complete = JsonObject & { resultType: 'complete'; content: Array<{ type: 'text'; text: string }>; isError?: boolean };
interface Task {
  taskId: string; owner: string; status: Status; createdAt: string; lastUpdatedAt: string;
  expiresAt: number; ttlMs: number; pollIntervalMs: number; pending: Record<string, InputRequest>;
  accepted: JsonObject; result?: JsonObject; error?: JsonObject; timer?: NodeJS.Timeout;
  controller: AbortController;
  resume?: (responses: JsonObject) => JsonObject;
}

/** Bounded, identity-scoped state for the pinned reference diagnostic. */
export class InteractionFixtureRuntime {
  readonly #tasks = new Map<string, Task>();
  readonly #states = new Map<string, { owner: string; kind: string; expiresAt: number; payload: JsonObject }>();
  readonly #max: number;
  readonly #ttl: number;
  readonly #poll: number;

  constructor(options: { maxTasks?: number; ttlMs?: number; pollIntervalMs?: number } = {}) {
    this.#max = options.maxTasks ?? 256;
    this.#ttl = options.ttlMs ?? 3_600_000;
    this.#poll = options.pollIntervalMs ?? 100;
  }

  mintState(identity: InteractionIdentity, kind: string, payload: JsonObject = {}): string {
    this.#purge();
    if (this.#states.size >= this.#max) throw new ProtocolError(-32000, 'Continuation capacity reached');
    const state = token();
    this.#states.set(state, { owner: owner(identity), kind, expiresAt: Date.now() + 300_000,
      payload: structuredClone(payload) });
    return state;
  }

  verifyState(identity: InteractionIdentity, state: unknown, kind: string): JsonObject {
    this.#purge();
    const stored = typeof state === 'string' ? this.#states.get(state) : undefined;
    if (stored === undefined || stored.owner !== owner(identity) || stored.kind !== kind) {
      throw new ProtocolError(-32602, 'Invalid or expired requestState');
    }
    return structuredClone(stored.payload);
  }

  /** SDK requestState.verify hook; rejects tampering before tool error wrapping. */
  verifyOpaqueState(identity: InteractionIdentity, state: string): string {
    this.#purge();
    const stored = this.#states.get(state);
    if (stored === undefined || stored.owner !== owner(identity)) {
      throw new ProtocolError(-32602, 'Invalid or expired requestState');
    }
    return state;
  }

  create(identity: InteractionIdentity, initial: (signal: AbortSignal) => Promise<JsonObject> | JsonObject): JsonObject {
    this.#purge();
    if (this.#tasks.size >= this.#max) throw new ProtocolError(-32000, 'Task capacity reached');
    const now = new Date().toISOString();
    const task: Task = {
      taskId: token(), owner: owner(identity), status: 'working', createdAt: now, lastUpdatedAt: now,
      expiresAt: Date.now() + this.#ttl, ttlMs: this.#ttl, pollIntervalMs: this.#poll,
      pending: {}, accepted: {}, controller: new AbortController(),
    };
    this.#tasks.set(task.taskId, task);
    queueMicrotask(async () => {
      try {
        const value = await initial(task.controller.signal);
        if (task.status === 'cancelled') return;
        if (value.resultType === 'input_required') {
          task.pending = copyRequests(value.inputRequests);
          task.resume = typeof value.resume === 'function'
            ? value.resume as (responses: JsonObject) => JsonObject : undefined;
          task.status = 'input_required';
        } else {
          task.result = value;
          task.status = 'completed';
        }
      } catch (error) {
        if (task.status !== 'cancelled') {
          const protocol = error instanceof ProtocolError ? error : undefined;
          task.error = { code: protocol?.code ?? -32603, message: protocol?.message ?? 'Internal error' };
          task.status = 'failed';
        }
      } finally {
        task.lastUpdatedAt = new Date().toISOString();
      }
    });
    return { resultType: 'task', ...taskInfo(task) };
  }

  delayed(identity: InteractionIdentity, milliseconds: number, text: string): JsonObject {
    return this.create(identity, signal => new Promise(resolve => {
      const timer = setTimeout(() => resolve(complete(text)), milliseconds);
      signal.addEventListener('abort', () => {
        clearTimeout(timer);
        resolve(complete(text));
      }, { once: true });
    }));
  }

  get(identity: InteractionIdentity, taskId: string): JsonObject {
    const task = this.#owned(identity, taskId);
    return {
      resultType: 'complete', ...taskInfo(task),
      ...(task.status === 'input_required' ? { inputRequests: structuredClone(task.pending) } : {}),
      ...(task.status === 'completed' ? { result: structuredClone(task.result) } : {}),
      ...(task.status === 'failed' ? { error: structuredClone(task.error) } : {}),
    };
  }

  update(identity: InteractionIdentity, taskId: string, responses: JsonObject): JsonObject {
    const task = this.#owned(identity, taskId);
    if (task.status !== 'input_required') return { resultType: 'complete' };
    for (const [key, response] of Object.entries(responses)) {
      const request = task.pending[key];
      if (request !== undefined && validResponse(request, response)) {
        task.accepted[key] = structuredClone(response);
        delete task.pending[key];
      }
    }
    if (Object.keys(task.pending).length === 0) {
      task.result = task.resume?.(structuredClone(task.accepted)) ?? complete('Task input accepted');
      task.status = 'completed';
    }
    task.lastUpdatedAt = new Date().toISOString();
    return { resultType: 'complete' };
  }

  cancel(identity: InteractionIdentity, taskId: string): JsonObject {
    const task = this.#owned(identity, taskId);
    if (!terminal(task.status)) {
      if (task.timer !== undefined) clearTimeout(task.timer);
      task.controller.abort();
      task.status = 'cancelled';
      task.lastUpdatedAt = new Date().toISOString();
    }
    return { resultType: 'complete' };
  }

  #owned(identity: InteractionIdentity, taskId: string): Task {
    this.#purge();
    const task = this.#tasks.get(taskId);
    if (task === undefined || task.owner !== owner(identity)) throw new ProtocolError(-32602, 'Unknown taskId');
    return task;
  }
  #purge(): void {
    const now = Date.now();
    for (const [id, task] of this.#tasks) if (task.expiresAt <= now) this.#tasks.delete(id);
    for (const [id, state] of this.#states) if (state.expiresAt <= now) this.#states.delete(id);
  }
}

/** Installs the pinned SDK's native 2026 MRTR fixtures. */
export function installInteractionFixtures(
  server: McpServer, identity: InteractionIdentity, runtime: InteractionFixtureRuntime,
): void {
  const empty = z.object({}).strict();
  server.registerTool('test_input_required_result_elicitation', {
    description: 'MRTR elicitation fixture', inputSchema: empty,
  }, async (_args, ctx) => {
    const name = object(object(ctx.mcpReq.inputResponses?.user_name)?.content)?.name;
    if (typeof name === 'string') {
      runtime.verifyState(identity, ctx.mcpReq.requestState(), 'elicitation');
      return complete(`Hello, ${name}!`);
    }
    return inputRequired({ inputRequests: { user_name: elicit('What is your name?', 'name') },
      requestState: runtime.mintState(identity, 'elicitation') });
  });
  server.registerTool('test_input_required_result_sampling', {
    description: 'MRTR sampling fixture', inputSchema: empty,
  }, async (_args, ctx) => {
    if (ctx.mcpReq.inputResponses?.sample_request !== undefined) {
      runtime.verifyState(identity, ctx.mcpReq.requestState(), 'sampling');
      const response = object(ctx.mcpReq.inputResponses.sample_request);
      return complete(`Sampling result: ${String(object(response?.content)?.text ?? 'no response')}`);
    }
    return inputRequired({ inputRequests: { sample_request: sampling('What is the capital of France?') },
      requestState: runtime.mintState(identity, 'sampling') });
  });
  server.registerTool('test_input_required_result_list_roots', {
    description: 'MRTR roots fixture', inputSchema: empty,
  }, async (_args, ctx) => {
    if (ctx.mcpReq.inputResponses?.roots_request !== undefined) {
      runtime.verifyState(identity, ctx.mcpReq.requestState(), 'roots');
      const response = object(ctx.mcpReq.inputResponses.roots_request);
      return complete(`Found ${Array.isArray(response?.roots) ? response.roots.length : 0} root(s)`);
    }
    return inputRequired({ inputRequests: { roots_request: roots() },
      requestState: runtime.mintState(identity, 'roots') });
  });
  server.registerTool('test_input_required_result_request_state', {
    description: 'MRTR state fixture', inputSchema: empty,
  }, async (_args, ctx) => {
    if (ctx.mcpReq.inputResponses?.confirm !== undefined) {
      runtime.verifyState(identity, ctx.mcpReq.requestState(), 'request-state');
      return complete('state-ok: requestState validated');
    }
    return inputRequired({ inputRequests: { confirm: elicit('Please confirm', 'ok', 'boolean') },
      requestState: runtime.mintState(identity, 'request-state') });
  });
  server.registerTool('test_input_required_result_multiple_inputs', {
    description: 'MRTR parallel inputs fixture', inputSchema: empty,
  }, async (_args, ctx) => {
    const r = ctx.mcpReq.inputResponses ?? {};
    if (r.user_name && r.greeting && r.client_roots) {
      runtime.verifyState(identity, ctx.mcpReq.requestState(), 'multiple');
      const name = String(object(object(r.user_name)?.content)?.name ?? '');
      const greeting = String(object(r.greeting)?.content !== undefined
        ? object(object(r.greeting)?.content)?.text ?? 'Hello there!' : 'Hello there!');
      const rootsValue = object(r.client_roots)?.roots;
      const rootCount = Array.isArray(rootsValue) ? rootsValue.length : 0;
      return complete(`Name: ${name}; Greeting: ${greeting}; Roots: ${rootCount}`);
    }
    return inputRequired({ inputRequests: {
        user_name: elicit('What is your name?', 'name'), greeting: sampling('Generate a greeting', 50), client_roots: roots(),
      }, requestState: runtime.mintState(identity, 'multiple') });
  });
  server.registerTool('test_input_required_result_multi_round', {
    description: 'MRTR multiple round fixture', inputSchema: empty,
  }, async (_args, ctx) => {
    const r = ctx.mcpReq.inputResponses ?? {};
    if (r.step2 !== undefined) {
      const state = runtime.verifyState(identity, ctx.mcpReq.requestState(), 'multi-round-2');
      const color = String(object(object(r.step2)?.content)?.color ?? '');
      return complete(`Multi-round complete for ${String(state.name ?? 'friend')} who likes ${color}`);
    }
    if (r.step1 !== undefined) {
      runtime.verifyState(identity, ctx.mcpReq.requestState(), 'multi-round-1');
      const name = String(object(object(r.step1)?.content)?.name ?? 'friend');
      return inputRequired({ inputRequests: { step2: elicit('Step 2: What is your favorite color?', 'color') },
        requestState: runtime.mintState(identity, 'multi-round-2', { name }) });
    }
    return inputRequired({ inputRequests: { step1: elicit('Step 1: What is your name?', 'name') },
      requestState: runtime.mintState(identity, 'multi-round-1') });
  });
  server.registerTool('test_input_required_result_tampered_state', {
    description: 'MRTR state integrity fixture', inputSchema: empty,
  }, async (_args, ctx) => {
    if (ctx.mcpReq.inputResponses?.confirm !== undefined) {
      runtime.verifyState(identity, ctx.mcpReq.requestState(), 'tamper');
      return complete('integrity-ok: state verified');
    }
    return inputRequired({ inputRequests: { confirm: elicit('Please confirm', 'ok', 'boolean') },
      requestState: runtime.mintState(identity, 'tamper') });
  });
  server.registerTool('test_input_required_result_capabilities', {
    description: 'MRTR capabilities fixture', inputSchema: empty,
  }, async (_args, ctx) => {
    if (Object.keys(ctx.mcpReq.inputResponses ?? {}).length > 0) {
      runtime.verifyState(identity, ctx.mcpReq.requestState(), 'capabilities');
      return complete(`capabilities-ok: received ${Object.keys(ctx.mcpReq.inputResponses ?? {}).join(',')}`);
    }
    const caps = clientCapabilities(ctx);
    const requests: Record<string, InputRequest> = {};
    if (object(caps.elicitation) !== undefined) requests.elicit_input = elicit('Elicitation input', 'value');
    if (object(caps.sampling) !== undefined) requests.sample_input = sampling('Sample request', 50);
    return Object.keys(requests).length === 0 ? complete('No supported capabilities declared')
      : inputRequired({ inputRequests: requests, requestState: runtime.mintState(identity, 'capabilities') });
  });
}

/**
 * Dedicated Tasks v2 diagnostic adapter. It uses the official low-level SDK
 * Server and replaces tools/list + tools/call, so install it on its own SDK
 * server instance. The pinned SDK labels its bundled task vocabulary as 2025
 * compatibility types with no runtime, and high-level tool config has no
 * execution/taskSupport field.
 */
export function installTaskV2DiagnosticAdapter(
  server: Server, identity: InteractionIdentity, runtime: InteractionFixtureRuntime,
): void {
  server.registerCapabilities({ extensions: { [TASKS_EXTENSION_ID]: {} } });
  const result = z.record(z.string(), z.unknown());
  server.setRequestHandler('tools/list', { params: z.object({}).passthrough(), result }, () => ({
    resultType: 'complete', tools: TASK_TOOL_DESCRIPTORS,
  }));
  server.setRequestHandler('tools/call', {
    params: z.object({ name: z.string(), arguments: z.record(z.string(), z.unknown()).optional() }).passthrough(),
    result,
  }, (params, ctx) => callTaskTool(runtime, identity, params.name, params.arguments ?? {}, ctx));
  const id = z.object({ taskId: z.string().min(1) }).strict();
  // The SDK lifts MRTR fields into mcpReq before invoking a low-level handler.
  const update = z.object({ taskId: z.string().min(1),
    inputResponses: z.record(z.string(), z.unknown()).optional() }).strict();
  server.setRequestHandler('tasks/get', { params: id, result }, (params, ctx) => {
    requireTasks(ctx);
    const value = runtime.get(identity, params.taskId);
    for (const request of Object.values(object(value.inputRequests) ?? {})) {
      const method = object(request)?.method;
      const capability = method === 'elicitation/create' ? 'elicitation'
        : method === 'sampling/createMessage' ? 'sampling' : 'roots';
      if (object(clientCapabilities(ctx)[capability]) === undefined) throw new MissingRequiredClientCapabilityError({
        requiredCapabilities: { [capability]: {} },
      });
    }
    return value;
  });
  server.setRequestHandler('tasks/update', { params: update, result }, (params, ctx) => {
    requireTasks(ctx); return runtime.update(identity, params.taskId, ctx.mcpReq.inputResponses ?? {});
  });
  server.setRequestHandler('tasks/cancel', { params: id, result }, (params, ctx) => {
    requireTasks(ctx); return runtime.cancel(identity, params.taskId);
  });
}

export const TASK_TOOL_DESCRIPTORS = [
  descriptor('test_task_notification_suppression', 'required', { type: 'object', properties: {}, additionalProperties: false }),
  descriptor('greet', 'forbidden', { type: 'object', properties: { name: { type: 'string' } }, required: ['name'], additionalProperties: false }),
  descriptor('slow_compute', 'optional', { type: 'object', properties: { seconds: { type: 'integer', minimum: 0, maximum: 60 }, label: { type: 'string' } }, required: ['seconds'], additionalProperties: false }),
  descriptor('failing_job', 'required'), descriptor('protocol_error_job', 'optional'),
  descriptor('confirm_delete', 'optional', { type: 'object', properties: { filename: { type: 'string' } }, required: ['filename'], additionalProperties: false }),
  descriptor('multi_input', 'optional'), descriptor('test_tool_with_task', 'required'),
] as const;

async function callTaskTool(runtime: InteractionFixtureRuntime, identity: InteractionIdentity, name: string,
  args: JsonObject, ctx: ServerContext): Promise<JsonObject> {
  if (name === 'greet') return complete(`Hello, ${String(args.name)}!`);
  if (name === 'slow_compute') {
    const ms = Number(args.seconds) * 1_000;
    if (!supportsTasks(ctx)) {
      await new Promise(resolve => setTimeout(resolve, ms));
      return complete(`Computed ${String(args.label ?? 'result')}`);
    }
    return runtime.delayed(identity, ms, `Computed ${String(args.label ?? 'result')}`);
  }
  if (name === 'failing_job') {
    requireTasks(ctx);
    return runtime.create(identity, () => ({ ...complete('intentional tool failure'), isError: true }));
  }
  if (name === 'test_task_notification_suppression') {
    requireTasks(ctx);
    return runtime.create(identity, () => complete('task complete'));
  }
  if (name === 'protocol_error_job') {
    if (!supportsTasks(ctx)) throw new ProtocolError(-32603, 'Intentional protocol failure');
    return runtime.create(identity, () => { throw new ProtocolError(-32603, 'Intentional protocol failure'); });
  }
  if (name === 'confirm_delete') {
    const response = object(ctx.mcpReq.inputResponses?.confirm);
    if (!supportsTasks(ctx)) {
      return response === undefined
        ? { resultType: 'input_required', inputRequests: { confirm: elicit('Confirm deletion', 'confirm', 'boolean') } }
        : complete('confirmed');
    }
    return runtime.create(identity, () => ({
      resultType: 'input_required', inputRequests: { confirm: elicit('Confirm deletion', 'confirm', 'boolean') },
      resume: () => complete('confirmed'),
    }));
  }
  if (name === 'multi_input') {
    const responses = ctx.mcpReq.inputResponses ?? {};
    if (!supportsTasks(ctx)) {
      return responses.first !== undefined && responses.second !== undefined
        ? complete('both-complete')
        : { resultType: 'input_required', inputRequests: {
          first: elicit('First input', 'name'), second: elicit('Second input', 'name'),
        } };
    }
    return runtime.create(identity, () => ({
      resultType: 'input_required', inputRequests: { first: elicit('First input', 'name'), second: elicit('Second input', 'name') },
      resume: () => complete('both-complete'),
    }));
  }
  if (name === 'test_tool_with_task') {
    requireTasks(ctx);
    const response = object(ctx.mcpReq.inputResponses?.user_name);
    const gathered = object(response?.content)?.name;
    if (typeof gathered !== 'string') return {
      resultType: 'input_required', inputRequests: { user_name: elicit('What is your name?', 'name') },
      requestState: runtime.mintState(identity, 'task-composition'),
    };
    runtime.verifyState(identity, ctx.mcpReq.requestState(), 'task-composition');
    return runtime.create(identity, () => complete(`Hello, ${gathered} from task`));
  }
  throw new ProtocolError(-32602, `Unknown tool: ${name}`);
}

function requireTasks(ctx: ServerContext): void {
  if (!supportsTasks(ctx)) throw new MissingRequiredClientCapabilityError({
    requiredCapabilities: { extensions: { [TASKS_EXTENSION_ID]: {} } },
  });
}
function supportsTasks(ctx: ServerContext): boolean {
  const extensions = object(clientCapabilities(ctx).extensions);
  return object(extensions?.[TASKS_EXTENSION_ID]) !== undefined;
}
function clientCapabilities(ctx: ServerContext): JsonObject {
  const envelope = object(ctx.mcpReq.envelope) ?? {};
  return object(envelope['io.modelcontextprotocol/clientCapabilities']) ?? {};
}
function descriptor(name: string, taskSupport: 'forbidden' | 'optional' | 'required',
  inputSchema: JsonObject = { type: 'object', properties: {}, additionalProperties: false }): JsonObject {
  return { name, description: name, inputSchema, execution: { taskSupport } };
}
function taskInfo(task: Task): JsonObject {
  return { taskId: task.taskId, status: task.status, createdAt: task.createdAt, lastUpdatedAt: task.lastUpdatedAt,
    ttlMs: task.ttlMs, pollIntervalMs: task.pollIntervalMs,
    ...(task.status === 'failed' ? { statusMessage: task.error?.message ?? 'Task failed' } : {}) };
}
function elicit(message: string, property: string, type: 'string' | 'boolean' = 'string'): InputRequest {
  return { method: 'elicitation/create', params: { message, requestedSchema: {
    type: 'object', properties: { [property]: { type } }, required: [property],
  } } } as InputRequest;
}
function sampling(text: string, maxTokens = 100): InputRequest {
  return { method: 'sampling/createMessage', params: {
    messages: [{ role: 'user', content: { type: 'text', text } }], maxTokens,
  } } as InputRequest;
}
function roots(): InputRequest { return { method: 'roots/list', params: {} } as InputRequest; }
function validResponse(request: InputRequest, response: unknown): boolean {
  const value = object(response);
  if (value === undefined) return false;
  if (request.method === 'roots/list') return Array.isArray(value.roots);
  if (request.method === 'sampling/createMessage') return typeof value.role === 'string' && value.content !== undefined;
  if (request.method !== 'elicitation/create') return false;
  if (value.action === 'decline' || value.action === 'cancel') return true;
  if (value.action !== 'accept') return false;
  const params = object(request.params);
  const schema = object(params?.requestedSchema);
  const properties = object(schema?.properties);
  const content = object(value.content);
  if (properties === undefined || content === undefined) return false;
  const required = Array.isArray(schema?.required) ? schema.required : [];
  return required.every(key => typeof key === 'string' && key in content)
    && Object.entries(content).every(([key, item]) => {
      const expected = object(properties[key])?.type;
      return expected === undefined ? true
        : expected === 'integer' ? Number.isInteger(item)
        : expected === 'boolean' ? typeof item === 'boolean'
          : expected === 'string' ? typeof item === 'string' : false;
    });
}
function copyRequests(value: unknown): Record<string, InputRequest> {
  const requests = object(value);
  if (requests === undefined || Object.keys(requests).length === 0) throw new ProtocolError(-32602, 'Task input_required needs inputRequests');
  return structuredClone(requests) as Record<string, InputRequest>;
}
function complete(text: string): Complete {
  return { resultType: 'complete', content: [{ type: 'text', text }],
    _meta: { 'io.modelcontextprotocol/serverInfo': { name: 'spring-mcp-sample', version: '0.1.0' } } };
}
function object(value: unknown): JsonObject | undefined {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as JsonObject : undefined;
}
function owner(identity: InteractionIdentity): string { return `${identity.tenantId}\0${identity.subject}`; }
function token(): string { return randomBytes(32).toString('base64url'); }
function terminal(status: Status): boolean { return ['completed', 'failed', 'cancelled'].includes(status); }

const _completeTypeObservation: CallToolResult = complete('type observation');
void _completeTypeObservation;
