import {
  CLIENT_CAPABILITIES_META_KEY,
  inputRequired,
  McpServer,
  type CallToolResult,
} from '@modelcontextprotocol/server';
import * as z from 'zod/v4';

const PNG_1X1 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl3fWQAAAAASUVORK5CYII=';
const EMPTY_WAV = 'UklGRiQAAABXQVZFZm10IBAAAAABAAEARKwAAIhYAQACABAAZGF0YQAAAAA=';

const JSON_SCHEMA_2020_12 = {
  $schema: 'https://json-schema.org/draft/2020-12/schema',
  type: 'object',
  $defs: {
    address: {
      $anchor: 'addressDef',
      type: 'object',
      properties: {
        street: { type: 'string' },
        city: { type: 'string' },
      },
    },
  },
  properties: {
    name: { type: 'string' },
    address: { $ref: '#/$defs/address' },
    contactMethod: { type: 'string', enum: ['phone', 'email'] },
    phone: { type: 'string' },
    email: { type: 'string' },
  },
  allOf: [{ anyOf: [{ required: ['phone'] }, { required: ['email'] }] }],
  if: { properties: { contactMethod: { const: 'phone' } }, required: ['contactMethod'] },
  then: { required: ['phone'] },
  else: { required: ['email'] },
  additionalProperties: false,
} as const;

const CUSTOM_HEADER_SCHEMA = {
  $schema: 'https://json-schema.org/draft/2020-12/schema',
  type: 'object',
  properties: {
    value: { type: 'string', 'x-mcp-header': 'Conformance-Value' },
  },
  required: ['value'],
  additionalProperties: false,
} as const;

const BOUNDED_PATTERN_SCHEMA = {
  $schema: 'https://json-schema.org/draft/2020-12/schema',
  type: 'object',
  properties: {
    value: { type: 'string', pattern: '^(a+)+$' },
  },
  required: ['value'],
  additionalProperties: false,
} as const;

export function installConformanceTools(server: McpServer): void {
  const emptyInput = z.object({}).strict();

  server.registerTool('test_structured_content', {
    description: 'Preserve any structured JSON value, including explicit null.',
    inputSchema: exactObjectSchema({ type: 'object', properties: { value: {} }, required: ['value'],
      additionalProperties: false }, value => Object.keys(value).length === 1 && 'value' in value
        ? undefined : 'Exactly one value property is required'),
  }, async (arguments_): Promise<CallToolResult> => ({
    content: [{ type: 'text', text: 'Structured value.' }],
    structuredContent: (arguments_ as Record<string, unknown>).value as CallToolResult['structuredContent'],
  }));

  server.registerTool('test_embedded_binary', {
    description: 'Return a binary embedded resource block.', inputSchema: emptyInput,
  }, async (): Promise<CallToolResult> => ({
    content: [{ type: 'resource', resource: { uri: 'test://embedded-binary',
      mimeType: 'application/octet-stream', blob: 'aGVsbG8=' } }],
  }));

  server.registerTool('test_simple_text', {
    description: 'Return the official conformance text fixture.',
    inputSchema: emptyInput,
  }, async (): Promise<CallToolResult> => ({
    content: [{ type: 'text', text: 'This is a simple text response for testing.' }],
  }));

  server.registerTool('test_image_content', {
    description: 'Return a minimal PNG image block.',
    inputSchema: emptyInput,
  }, async (): Promise<CallToolResult> => ({
    content: [{ type: 'image', data: PNG_1X1, mimeType: 'image/png' }],
  }));

  server.registerTool('test_audio_content', {
    description: 'Return a minimal WAV audio block.',
    inputSchema: emptyInput,
  }, async (): Promise<CallToolResult> => ({
    content: [{ type: 'audio', data: EMPTY_WAV, mimeType: 'audio/wav' }],
  }));

  server.registerTool('test_embedded_resource', {
    description: 'Return the official embedded resource fixture.',
    inputSchema: emptyInput,
  }, async (): Promise<CallToolResult> => ({
    content: [{
      type: 'resource',
      resource: {
        uri: 'test://embedded-resource',
        mimeType: 'text/plain',
        text: 'This is an embedded resource content.',
      },
    }],
  }));

  server.registerTool('test_resource_link', {
    description: 'Return the official resource-link content fixture.',
    inputSchema: emptyInput,
  }, async (): Promise<CallToolResult> => ({
    content: [{ type: 'resource_link', uri: 'test://resource-link', name: 'fixture' }],
  }));

  server.registerTool('test_multiple_content_types', {
    description: 'Return text, image, and embedded resource blocks together.',
    inputSchema: emptyInput,
  }, async (): Promise<CallToolResult> => ({
    content: [
      { type: 'text', text: 'Multiple content types test:' },
      { type: 'image', data: PNG_1X1, mimeType: 'image/png' },
      {
        type: 'resource',
        resource: {
          uri: 'test://mixed-content-resource',
          mimeType: 'application/json',
          text: '{"test":"data","value":123}',
        },
      },
    ],
  }));

  server.registerTool('test_error_handling', {
    description: 'Return an intentional tool execution error result.',
    inputSchema: emptyInput,
  }, async (): Promise<CallToolResult> => ({
    isError: true,
    content: [{ type: 'text', text: 'This tool intentionally returns an error for testing' }],
  }));

  server.registerTool('test_missing_capability', {
    description: 'Require the sampling capability for the stateless capability diagnostic.',
    inputSchema: emptyInput,
  }, async () => inputRequired({
    inputRequests: {
      sampling: inputRequired.createMessage({
        messages: [{ role: 'user', content: { type: 'text', text: 'Capability probe.' } }],
        maxTokens: 1,
      }),
    },
  }));

  server.registerTool('test_required_capability', {
    description: 'Complete only when the request declares the sampling capability.',
    inputSchema: emptyInput,
  }, async (_arguments, context) => {
    const envelope = context.mcpReq.envelope as Record<string, unknown> | undefined;
    const capabilities = envelope?.[CLIENT_CAPABILITIES_META_KEY];
    const sampling = isObject(capabilities) ? capabilities.sampling : undefined;
    if (isObject(sampling)) {
      return { content: [{ type: 'text' as const, text: 'Sampling capability declared.' }] };
    }
    return inputRequired({
      inputRequests: {
        sampling: inputRequired.createMessage({
          messages: [{ role: 'user', content: { type: 'text', text: 'Capability probe.' } }],
          maxTokens: 1,
        }),
      },
    });
  });

  server.registerTool('json_schema_2020_12_tool', {
    description: 'Tool with JSON Schema 2020-12 features',
    inputSchema: exactObjectSchema(JSON_SCHEMA_2020_12, validateJsonSchemaFixture),
  }, async (): Promise<CallToolResult> => ({
    content: [{ type: 'text', text: 'ok' }],
  }));

  server.registerTool('test_bounded_pattern', {
    description: 'Exercise bounded JSON Schema pattern validation.',
    inputSchema: exactObjectSchema(BOUNDED_PATTERN_SCHEMA, validateBoundedPatternFixture),
  }, async (): Promise<CallToolResult> => ({
    content: [{ type: 'text', text: 'Pattern accepted.' }],
  }));

  server.registerTool('test_custom_header', {
    description: 'Mirror a string argument into an MCP parameter header.',
    inputSchema: exactObjectSchema(CUSTOM_HEADER_SCHEMA, validateCustomHeaderFixture),
  }, async (): Promise<CallToolResult> => ({
    content: [{ type: 'text', text: 'Custom header accepted.' }],
  }));

  server.registerTool('test_output_violation', {
    description: 'Deliberately violate the declared output schema.',
    inputSchema: emptyInput,
    outputSchema: z.object({ value: z.string() }).strict(),
  }, async (): Promise<CallToolResult> => ({
    content: [{ type: 'text', text: 'invalid output' }],
    structuredContent: {},
  }));
}

type ObjectValidator = (value: Record<string, unknown>) => string | undefined;

function exactObjectSchema(schema: Record<string, unknown>, validateObject: ObjectValidator) {
  return {
    '~standard': {
      version: 1 as const,
      vendor: 'spring-mcp-conformance',
      validate(value: unknown) {
        if (isObject(value)) {
          const issue = validateObject(value);
          return issue === undefined ? { value } : { issues: [{ message: issue }] };
        }
        return { issues: [{ message: 'Expected an object' }] };
      },
      jsonSchema: {
        input: () => structuredClone(schema),
        output: () => structuredClone(schema),
      },
    },
  };
}

function validateJsonSchemaFixture(value: Record<string, unknown>): string | undefined {
  const allowed = new Set(['name', 'address', 'contactMethod', 'phone', 'email']);
  if (Object.keys(value).some(key => !allowed.has(key))) return 'Unexpected property';
  for (const key of ['name', 'phone', 'email'] as const) {
    if (key in value && typeof value[key] !== 'string') return `${key} must be a string`;
  }
  if ('contactMethod' in value && value.contactMethod !== 'phone' && value.contactMethod !== 'email') {
    return 'contactMethod must be phone or email';
  }
  if ('address' in value) {
    if (!isObject(value.address)) return 'address must be an object';
    for (const key of ['street', 'city'] as const) {
      if (key in value.address && typeof value.address[key] !== 'string') {
        return `address.${key} must be a string`;
      }
    }
  }
  if (typeof value.phone !== 'string' && typeof value.email !== 'string') {
    return 'phone or email is required';
  }
  if (value.contactMethod === 'phone' && typeof value.phone !== 'string') {
    return 'phone is required when contactMethod is phone';
  }
  if (value.contactMethod !== 'phone' && typeof value.email !== 'string') {
    return 'email is required unless contactMethod is phone';
  }
  return undefined;
}

function validateCustomHeaderFixture(value: Record<string, unknown>): string | undefined {
  if (Object.keys(value).some(key => key !== 'value')) return 'Unexpected property';
  return typeof value.value === 'string' ? undefined : 'value must be a string';
}

function validateBoundedPatternFixture(value: Record<string, unknown>): string | undefined {
  if (Object.keys(value).some(key => key !== 'value')) return 'Unexpected property';
  if (typeof value.value !== 'string') return 'value must be a string';
  return /^a+$/.test(value.value) ? undefined : 'value must match ^(a+)+$';
}

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
