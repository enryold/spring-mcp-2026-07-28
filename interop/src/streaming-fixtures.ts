import {
  InMemoryServerEventBus,
  inputRequired,
  type McpServer,
  type ServerEventBus,
} from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import type { InteractionFixtureRuntime } from './interaction-fixtures.js';

export interface StreamingIdentity {
  subject: string;
  tenantId: string;
}

/** Keeps the SDK subscription bus scoped to the authenticated subject and tenant. */
export class StreamingFixtureEvents {
  readonly #buses = new Map<string, InMemoryServerEventBus>();

  bus(identity: StreamingIdentity): ServerEventBus {
    const key = `${identity.tenantId}\0${identity.subject}`;
    let bus = this.#buses.get(key);
    if (bus === undefined) {
      bus = new InMemoryServerEventBus();
      this.#buses.set(key, bus);
    }
    return bus;
  }

  toolsChanged(identity: StreamingIdentity): void {
    this.bus(identity).publish({ kind: 'tools_list_changed' });
  }

  promptsChanged(identity: StreamingIdentity): void {
    this.bus(identity).publish({ kind: 'prompts_list_changed' });
  }

  resourcesChanged(identity: StreamingIdentity): void {
    this.bus(identity).publish({ kind: 'resources_list_changed' });
  }

  resourceUpdated(identity: StreamingIdentity, uri: string): void {
    this.bus(identity).publish({ kind: 'resource_updated', uri });
  }
}

export function installStreamingFixtures(
  server: McpServer,
  identity: StreamingIdentity,
  events: StreamingFixtureEvents,
  interactions: InteractionFixtureRuntime,
): void {
  server.registerTool('test_tool_with_progress', {
    description: 'Emit deterministic progress and requested logs.',
    inputSchema: z.object({}).strict(),
  }, async (_arguments, context) => {
    await context.mcpReq.log('info', 'Progress tool started.', 'conformance');
    const token = context.mcpReq._meta?.progressToken;
    if (token !== undefined) await progress(context.mcpReq.notify, token, 0);
    await delay(50, context.mcpReq.signal);
    if (token !== undefined) await progress(context.mcpReq.notify, token, 50);
    await delay(50, context.mcpReq.signal);
    if (token !== undefined) await progress(context.mcpReq.notify, token, 100);
    return { content: [{ type: 'text' as const, text: 'Progress tool completed.' }] };
  });

  server.registerTool('test_logging_tool', {
    description: 'Emit a log only when the request selects a log level.',
    inputSchema: z.object({}).strict(),
  }, async (_arguments, context) => {
    await context.mcpReq.log('info', 'Requested diagnostic log.', 'conformance');
    return { content: [{ type: 'text' as const, text: 'Logging diagnostic completed.' }] };
  });

  server.registerTool('test_streaming_elicitation', {
    description: 'Return an elicitation request over SSE.',
    inputSchema: z.object({}).strict(),
  }, async () => inputRequired({
    inputRequests: {
      elicitation: { method: 'elicitation/create', params: { mode: 'form',
        message: 'Provide a value for the streaming diagnostic.',
        requestedSchema: { type: 'object', properties: { value: { type: 'string' } }, required: ['value'] },
      } },
    },
    requestState: interactions.mintState(identity, 'streaming-elicitation'),
  }));

  server.registerTool('test_trigger_tool_change', {
    description: 'Publish one identity-scoped tool-list change.',
    inputSchema: z.object({}).strict(),
  }, async () => {
    events.toolsChanged(identity);
    return { content: [{ type: 'text' as const, text: 'Tool change published.' }] };
  });

  server.registerTool('test_trigger_prompt_change', {
    description: 'Publish one identity-scoped prompt-list change.',
    inputSchema: z.object({}).strict(),
  }, async () => {
    events.promptsChanged(identity);
    return { content: [{ type: 'text' as const, text: 'Prompt change published.' }] };
  });

  server.registerTool('test_trigger_resource_change', {
    description: 'Publish one identity-scoped resource-list change.',
    inputSchema: z.object({}).strict(),
  }, async () => {
    events.resourcesChanged(identity);
    return { content: [{ type: 'text' as const, text: 'Resource change published.' }] };
  });

  server.registerTool('test_trigger_resource_update', {
    description: 'Publish one identity-scoped resource update.',
    inputSchema: z.object({}).strict(),
  }, async () => {
    events.resourceUpdated(identity, 'test://static-text');
    return { content: [{ type: 'text' as const, text: 'Resource update published.' }] };
  });
}

async function progress(
  notify: (notification: { method: string; params?: Record<string, unknown> }) => Promise<void>,
  progressToken: string | number,
  value: number,
): Promise<void> {
  await notify({
    method: 'notifications/progress',
    params: { progressToken, progress: value, total: 100 },
  });
}

async function delay(milliseconds: number, signal: AbortSignal): Promise<void> {
  await new Promise<void>((resolve, reject) => {
    const timer = setTimeout(resolve, milliseconds);
    signal.addEventListener('abort', () => {
      clearTimeout(timer);
      reject(signal.reason);
    }, { once: true });
  });
}
