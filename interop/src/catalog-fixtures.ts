import { McpServer, ResourceTemplate, acceptedContent, completable, inputRequired } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import type { InteractionFixtureRuntime, InteractionIdentity } from './interaction-fixtures.js';

const PNG_1X1 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl3fWQAAAAASUVORK5CYII=';

/** Exact resource, prompt, and completion diagnostics used by official server conformance. */
export function installCatalogFixtures(server: McpServer, identity: InteractionIdentity, interactions: InteractionFixtureRuntime): void {
  server.registerResource('static-binary', 'test://static-binary', {
    title: 'Static Binary Resource',
    description: 'A static binary resource (image) for testing',
    mimeType: 'image/png',
  }, async () => ({ contents: [{
    uri: 'test://static-binary', mimeType: 'image/png', blob: PNG_1X1,
  }] }));

  server.registerResource('static-text', 'test://static-text', {
    title: 'Static Text Resource',
    description: 'A static text resource for testing',
    mimeType: 'text/plain',
  }, async () => ({ contents: [{
    uri: 'test://static-text', mimeType: 'text/plain',
    text: 'This is the content of the static text resource.',
  }] }));

  server.registerResource('template', new ResourceTemplate('test://template/{id}/data', {
    list: undefined,
    complete: { id: value => ['123', '456'].filter(candidate => candidate.startsWith(value)) },
  }), {
    title: 'Resource Template',
    description: 'A resource template with parameter substitution',
    mimeType: 'application/json',
  }, async (uri, variables) => ({ contents: [{
    uri: uri.toString(),
    mimeType: 'application/json',
    text: JSON.stringify({ id: variables.id, templateTest: true, data: `Data for ID: ${variables.id}` }),
  }] }));

  server.registerPrompt('test_input_required_result_prompt', {
    title: 'Input Required Result Prompt', description: 'A prompt that requests user context',
  }, async context => {
    const supplied = acceptedContent<{ context?: unknown }>(context.mcpReq.inputResponses, 'user_context');
    const promptContext = supplied !== undefined && typeof supplied.context === 'string'
      && Object.keys(supplied).length === 1 ? supplied.context : undefined;
    if (promptContext !== undefined) interactions.verifyState(identity, context.mcpReq.requestState(), 'prompt-context');
    return promptContext === undefined
      ? inputRequired({ inputRequests: { user_context: {
        method: 'elicitation/create',
        params: {
          message: 'What context should the prompt use?',
          requestedSchema: {
            type: 'object', properties: { context: { type: 'string' } }, required: ['context'],
          },
        },
      } }, requestState: interactions.mintState(identity, 'prompt-context') })
      : { messages: [{ role: 'user', content: { type: 'text', text: `Prompt context: ${promptContext}` } }] };
  });

  server.registerPrompt('test_prompt_with_arguments', {
    title: 'Prompt With Arguments',
    description: 'A prompt with required arguments',
    argsSchema: z.object({
      arg1: completable(z.string().describe('First test argument'), value =>
        ['testValue1', 'testValue2'].filter(candidate => candidate.startsWith(value))),
      arg2: z.string().describe('Second test argument'),
    }).strict(),
  }, async ({ arg1, arg2 }) => ({ messages: [{
    role: 'user',
    content: { type: 'text', text: `Prompt with arguments: arg1='${arg1}', arg2='${arg2}'` },
  }] }));

  server.registerPrompt('test_prompt_with_embedded_resource', {
    title: 'Prompt With Embedded Resource',
    description: 'A prompt that includes an embedded resource',
    argsSchema: z.object({ resourceUri: z.string().describe('URI of the resource to embed') }).strict(),
  }, async ({ resourceUri }) => ({ messages: [
    { role: 'user', content: { type: 'resource', resource: {
      uri: resourceUri, mimeType: 'text/plain', text: 'Embedded resource content for testing.',
    } } },
    { role: 'user', content: { type: 'text', text: 'Please process the embedded resource above.' } },
  ] }));

  server.registerPrompt('test_prompt_with_image', {
    title: 'Prompt With Image', description: 'A prompt that includes image content',
  }, async () => ({ messages: [
    { role: 'user', content: { type: 'image', data: PNG_1X1, mimeType: 'image/png' } },
    { role: 'user', content: { type: 'text', text: 'Please analyze the image above.' } },
  ] }));

  server.registerPrompt('test_simple_prompt', {
    title: 'Simple Test Prompt', description: 'A simple prompt without arguments',
  }, async () => ({ messages: [{
    role: 'user', content: { type: 'text', text: 'This is a simple prompt for testing.' },
  }] }));
}
