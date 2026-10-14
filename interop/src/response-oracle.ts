import { readFileSync } from 'node:fs';
import { Ajv2020, type ErrorObject, type ValidateFunction } from 'ajv/dist/2020.js';
import type { Observation } from './comparator.ts';
import type { WireCase } from './wire.ts';

type OracleTarget = 'java' | 'reference';

const SCHEMA_ID = 'https://spring-mcp-interop.invalid/2026-07-28/schema.json';
const TIMESTAMP_PATTERN = '^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})$';
const schema = JSON.parse(readFileSync(
  new URL('../../spec/upstream/schema.json', import.meta.url), 'utf8')) as Record<string, unknown>;
// The frozen schema uses format names as annotations, including `byte`, and
// intentionally uses constructs that Ajv's optional strict linter rejects.
const ajv = new Ajv2020({ allErrors: true, strict: false, allowUnionTypes: true, validateFormats: false });
ajv.addSchema({ ...schema, $id: SCHEMA_ID });

const validators = new Map<string, ValidateFunction>();
for (const definition of [
  'JSONRPCErrorResponse',
  'HeaderMismatchError',
  'MissingRequiredClientCapabilityError',
  'UnsupportedProtocolVersionError',
  'SubscriptionsAcknowledgedNotification',
  'ProgressNotification',
  'LoggingMessageNotification',
  'ToolListChangedNotification',
  'PromptListChangedNotification',
  'ResourceListChangedNotification',
  'ResourceUpdatedNotification',
]) {
  validators.set(definition, ajv.compile({ $ref: `${SCHEMA_ID}#/$defs/${definition}` }));
}
validators.set('DiscoverResultResponse', completeResponse('DiscoverResultResponse', 'DiscoverResult'));
validators.set('ListToolsResultResponse', completeResponse('ListToolsResultResponse', 'ListToolsResult'));
validators.set('ListResourcesResultResponse', completeResponse('ListResourcesResultResponse', 'ListResourcesResult'));
validators.set('ListResourceTemplatesResultResponse',
  completeResponse('ListResourceTemplatesResultResponse', 'ListResourceTemplatesResult'));
validators.set('ReadResourceCompleteResultResponse', completeResponse('ReadResourceResultResponse', 'ReadResourceResult'));
validators.set('ListPromptsResultResponse', completeResponse('ListPromptsResultResponse', 'ListPromptsResult'));
validators.set('GetPromptCompleteResultResponse', completeResponse('GetPromptResultResponse', 'GetPromptResult'));
validators.set('CompleteResultResponse', completeResponse('CompleteResultResponse', 'CompleteResult'));
validators.set('SubscriptionsListenResultResponse',
  completeResponse('SubscriptionsListenResultResponse', 'SubscriptionsListenResult'));
validators.set('CallToolCompleteResultResponse', completeResponse('CallToolResultResponse', 'CallToolResult'));
validators.set('CallToolInputRequiredResultResponse', inputRequiredResponse('CallToolResultResponse'));
validators.set('ReadResourceInputRequiredResultResponse', inputRequiredResponse('ReadResourceResultResponse'));
validators.set('GetPromptInputRequiredResultResponse', inputRequiredResponse('GetPromptResultResponse'));
validators.set('CallToolKnownResultResponse', ajv.compile({
  allOf: [
    { $ref: `${SCHEMA_ID}#/$defs/CallToolResultResponse` },
    { properties: { result: { properties: { resultType: { enum: ['complete', 'input_required', 'task'] } },
      required: ['resultType'] } }, required: ['result'] },
  ],
}));
validators.set('TaskCreateResultResponse', taskCreateResponse());
validators.set('TaskGetResultResponse', taskGetResponse());
validators.set('TaskAckResultResponse', taskAckResponse());

/**
 * Independent protocol oracle. It validates the actual packet against the frozen
 * official 2026 schema before differential equality or case assertions can pass.
 */
export function validateResponseShape(
  wireCase: WireCase,
  observed: Observation,
  target: OracleTarget,
): string[] {
  const failures = validateSingleResponse(wireCase, observed, target);
  for (const kind of ['before', 'parallelBefore'] as const) {
    const requests = wireCase[kind] ?? [];
    const responses = observed[kind] ?? [];
    if (requests.length !== responses.length) failures.push(`${kind}: missing observed responses`);
    for (const [index, response] of responses.entries()) {
      const request = requests[index]?.request;
      if (request) failures.push(...validateSingleResponse({ ...wireCase, request }, response, target)
        .map(value => `${kind}[${index}]: ${value}`));
    }
  }
  validateCorrelatedResponses(failures, wireCase, observed, target, 'steps',
    (wireCase.steps ?? []).map(step => step.request));
  validateCorrelatedResponses(failures, wireCase, observed, target, 'parallel', wireCase.parallelRequests ?? []);
  const afterAck = wireCase.afterAck === undefined ? []
    : Array.isArray(wireCase.afterAck) ? wireCase.afterAck : [wireCase.afterAck];
  validateCorrelatedResponses(failures, wireCase, observed, target, 'afterAck', afterAck);
  return failures;
}

function validateSingleResponse(wireCase: WireCase, observed: Observation, target: OracleTarget): string[] {
  const failures = validateStreamMessages(wireCase, observed, target);
  if (observed.body === null && bodyMayBeAbsent(wireCase, observed)) return failures;
  if (!isRecord(observed.body)) return failures.concat(
    `response body is not a JSON object: ${display(observed.body)}`);

  if (wireCase.request.firstSseMessage && observed.headers['content-type'] === 'text/event-stream') {
    const validate = validators.get('SubscriptionsAcknowledgedNotification')!;
    const legacyFailures = validate(observed.body) ? []
      : (validate.errors ?? []).map(error => `SubscriptionsAcknowledgedNotification${error.instancePath} ${error.message}`);
    const params = isRecord(observed.body.params) ? observed.body.params : {};
    const meta = isRecord(params._meta) ? params._meta : {};
    if (meta['io.modelcontextprotocol/subscriptionId'] !== wireCase.request.id) {
      legacyFailures.push('First SSE acknowledgment must carry the exact requested subscriptionId');
    }
    return failures.concat(legacyFailures);
  }

  // A subscription stream is deliberately closed after the expected notification;
  // its body is the final notification rather than a JSON-RPC terminal response.
  if (requestMethod(wireCase) === 'subscriptions/listen' && observed.messages?.length) return failures;

  const hasResult = Object.hasOwn(observed.body, 'result');
  const hasError = Object.hasOwn(observed.body, 'error');
  const terminalFailures = hasResult === hasError
    ? ['response must contain exactly one terminal member: result or error']
    : [];
  const expectedId = wireCase.request.id;
  if (expectedId !== undefined && expectedId !== null && observed.body.id !== expectedId
      && (hasResult || Object.hasOwn(observed.body, 'id'))
      && !isDocumentedReferenceIdValue(wireCase, observed, target)) {
    terminalFailures.push(`response id must match source request id ${display(expectedId)}`);
  }
  const definition = responseDefinition(wireCase, observed.body);
  const validate = validators.get(definition);
  if (!validate) return failures.concat(`no response validator for ${definition}`);
  const semanticFailures = definition.startsWith('Task') ? taskTimestampFailures(observed.body) : [];
  if (validate(observed.body)) return failures.concat(terminalFailures, semanticFailures);

  return failures.concat(terminalFailures, semanticFailures, (validate.errors ?? [])
    .filter(error => !isDocumentedReferenceException(wireCase, observed, target, error))
    .map(error => `${definition}${error.instancePath || '/'} ${error.message ?? error.keyword}`));
}

function taskTimestampFailures(body: Record<string, unknown>): string[] {
  const result = isRecord(body.result) ? body.result : undefined;
  if (!result) return [];
  const created = validTimestamp(result.createdAt);
  const updated = validTimestamp(result.lastUpdatedAt);
  const failures: string[] = [];
  if (typeof result.createdAt === 'string' && created === undefined) failures.push('task createdAt is not a valid timestamp');
  if (typeof result.lastUpdatedAt === 'string' && updated === undefined) failures.push('task lastUpdatedAt is not a valid timestamp');
  if (created !== undefined && updated !== undefined && updated < created) {
    failures.push('task lastUpdatedAt precedes createdAt');
  }
  return failures;
}

function validTimestamp(value: unknown): number | undefined {
  if (typeof value !== 'string') return undefined;
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.exec(value);
  if (!match) return undefined;
  const [, yearText, monthText, dayText, hourText, minuteText, secondText] = match;
  const year = Number(yearText), month = Number(monthText), day = Number(dayText);
  if (month < 1 || month > 12 || day < 1 || day > new Date(Date.UTC(year, month, 0)).getUTCDate()
      || Number(hourText) > 23 || Number(minuteText) > 59 || Number(secondText) > 59) return undefined;
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}

function validateCorrelatedResponses(
  failures: string[], wireCase: WireCase, observed: Observation, target: OracleTarget,
  kind: 'steps' | 'parallel' | 'afterAck', requests: WireCase['request'][],
): void {
  const responses = observed[kind] ?? [];
  if (requests.length !== responses.length) failures.push(`${kind}: missing observed responses`);
  for (const [index, response] of responses.entries()) {
    const request = requests[index];
    if (request) failures.push(...validateSingleResponse({ ...wireCase, request }, response, target)
      .map(value => `${kind}[${index}]: ${value}`));
  }
}

function validateStreamMessages(wireCase: WireCase, observed: Observation, target: OracleTarget): string[] {
  if (!observed.messages?.length) return [];
  const failures: string[] = [];
  const subscription = requestMethod(wireCase) === 'subscriptions/listen';
  let previousProgress: number | undefined;
  let terminalCount = 0;
  for (const [index, message] of observed.messages.entries()) {
    if (!isRecord(message)) {
      failures.push(`messages[${index}]: message is not a JSON object`);
      continue;
    }
    if ('result' in message || 'error' in message) {
      terminalCount++;
      const nested = { ...observed, body: message, messages: undefined };
      failures.push(...validateSingleResponse(wireCase, nested, target).map(value => `messages[${index}]: ${value}`));
      continue;
    }
    const method = typeof message.method === 'string' ? message.method : undefined;
    if ('id' in message) failures.push(`messages[${index}]: server notification must not be an independent request`);
    const definition = notificationDefinition(method);
    const validate = definition === undefined ? undefined : validators.get(definition);
    if (!validate) {
      failures.push(`messages[${index}]: no notification validator for ${display(method)}`);
      continue;
    }
    if (!validate(message)) failures.push(...(validate.errors ?? [])
      .map(error => `messages[${index}]: ${definition}${error.instancePath || '/'} ${error.message ?? error.keyword}`));
    const params = isRecord(message.params) ? message.params : {};
    if (method === 'notifications/progress') {
      const expected = wireCase.request.meta?.progressToken;
      if (expected !== undefined && params.progressToken !== expected) {
        failures.push(`messages[${index}]: progressToken does not match source request`);
      }
      if (typeof params.progress === 'number') {
        if (previousProgress !== undefined && params.progress <= previousProgress) {
          failures.push(`messages[${index}]: progress must increase monotonically`);
        }
        previousProgress = params.progress;
      }
    }
    if (method === 'notifications/message'
        && typeof wireCase.request.meta?.['io.modelcontextprotocol/logLevel'] !== 'string') {
      failures.push(`messages[${index}]: log notification was not requested by the source request`);
    }
    if (subscription) {
      const metadata = isRecord(params._meta) ? params._meta : {};
      if (metadata['io.modelcontextprotocol/subscriptionId'] !== wireCase.request.id) {
        failures.push(`messages[${index}]: subscriptionId does not match source request`);
      }
    }
  }
  if (!subscription) {
    if (terminalCount !== 1) failures.push('finite SSE response must contain exactly one terminal response');
    const last = observed.messages.at(-1);
    if (!isRecord(last) || !('result' in last || 'error' in last)) {
      failures.push('finite SSE terminal response must be the final message');
    }
  } else {
    const methods = observed.messages.map(message => isRecord(message) ? message.method : undefined);
    if (methods[0] !== 'notifications/subscriptions/acknowledged') {
      failures.push('subscription acknowledgment must be the first message');
    }
    if (methods.filter(method => method === 'notifications/subscriptions/acknowledged').length !== 1) {
      failures.push('subscription stream must contain exactly one acknowledgment');
    }
    failures.push(...subscriptionFilterFailures(wireCase, observed.messages));
  }
  return failures;
}

function subscriptionFilterFailures(wireCase: WireCase, messages: readonly unknown[]): string[] {
  const requestFilter = isRecord(wireCase.request.params?.notifications)
    ? wireCase.request.params.notifications : {};
  const failures: string[] = [];
  for (const [index, message] of messages.entries()) {
    if (!isRecord(message) || typeof message.method !== 'string') continue;
    if (message.method === 'notifications/subscriptions/acknowledged') {
      const params = isRecord(message.params) ? message.params : {};
      const acknowledged = isRecord(params.notifications) ? params.notifications : {};
      for (const name of ['toolsListChanged', 'promptsListChanged', 'resourcesListChanged']) {
        if (acknowledged[name] === true && requestFilter[name] !== true) {
          failures.push(`messages[${index}]: acknowledgment includes unrequested ${name}`);
        }
      }
      if (Array.isArray(acknowledged.resourceSubscriptions)) {
        const requested = Array.isArray(requestFilter.resourceSubscriptions)
          ? requestFilter.resourceSubscriptions : [];
        if (acknowledged.resourceSubscriptions.some(uri => !requested.includes(uri))) {
          failures.push(`messages[${index}]: acknowledgment includes an unrequested resource subscription`);
        }
      }
    }
    const required = ({
      'notifications/tools/list_changed': 'toolsListChanged',
      'notifications/prompts/list_changed': 'promptsListChanged',
      'notifications/resources/list_changed': 'resourcesListChanged',
    } as Record<string, string>)[message.method];
    if (required && requestFilter[required] !== true) {
      failures.push(`messages[${index}]: notification was not requested by the subscription filter`);
    }
    if (message.method === 'notifications/resources/updated') {
      const requested = requestFilter.resourceSubscriptions;
      const params = isRecord(message.params) ? message.params : {};
      if (!Array.isArray(requested) || !requested.includes(params.uri)) {
        failures.push(`messages[${index}]: resource update URI was not requested by the subscription filter`);
      }
    }
  }
  return failures;
}

function notificationDefinition(method: string | undefined): string | undefined {
  return ({
    'notifications/progress': 'ProgressNotification',
    'notifications/message': 'LoggingMessageNotification',
    'notifications/tools/list_changed': 'ToolListChangedNotification',
    'notifications/prompts/list_changed': 'PromptListChangedNotification',
    'notifications/resources/list_changed': 'ResourceListChangedNotification',
    'notifications/resources/updated': 'ResourceUpdatedNotification',
    'notifications/subscriptions/acknowledged': 'SubscriptionsAcknowledgedNotification',
  } as Record<string, string>)[method ?? ''];
}

function responseDefinition(wireCase: WireCase, body: Record<string, unknown>): string {
  if ('error' in body) {
    const code = isRecord(body.error) ? body.error.code : undefined;
    if (code === -32_020) return 'HeaderMismatchError';
    if (code === -32_021) return 'MissingRequiredClientCapabilityError';
    if (code === -32_022) return 'UnsupportedProtocolVersionError';
    return 'JSONRPCErrorResponse';
  }
  const method = requestMethod(wireCase);
  if (method === 'server/discover') return 'DiscoverResultResponse';
  if (method === 'tools/list') return 'ListToolsResultResponse';
  if (method === 'tools/call') {
    const resultType = isRecord(body.result) ? body.result.resultType : undefined;
    if (resultType === 'complete') return 'CallToolCompleteResultResponse';
    if (resultType === 'input_required') return 'CallToolInputRequiredResultResponse';
    if (resultType === 'task') return 'TaskCreateResultResponse';
    return 'CallToolKnownResultResponse';
  }
  if (method === 'resources/list') return 'ListResourcesResultResponse';
  if (method === 'resources/templates/list') return 'ListResourceTemplatesResultResponse';
  if (method === 'resources/read') return resultType(body) === 'input_required'
    ? 'ReadResourceInputRequiredResultResponse' : 'ReadResourceCompleteResultResponse';
  if (method === 'prompts/list') return 'ListPromptsResultResponse';
  if (method === 'prompts/get') return resultType(body) === 'input_required'
    ? 'GetPromptInputRequiredResultResponse' : 'GetPromptCompleteResultResponse';
  if (method === 'completion/complete') return 'CompleteResultResponse';
  if (method === 'subscriptions/listen') return 'SubscriptionsListenResultResponse';
  if (method === 'tasks/get') return 'TaskGetResultResponse';
  if (method === 'tasks/update' || method === 'tasks/cancel') return 'TaskAckResultResponse';
  return 'JSONRPCErrorResponse';
}

function resultType(body: Record<string, unknown>): unknown {
  return isRecord(body.result) ? body.result.resultType : undefined;
}

function requestMethod(wireCase: WireCase): string | undefined {
  if (isRecord(wireCase.request.body) && typeof wireCase.request.body.method === 'string') {
    return wireCase.request.body.method;
  }
  if (wireCase.request.bodyRaw !== undefined) {
    try {
      const body = JSON.parse(wireCase.request.bodyRaw) as unknown;
      if (isRecord(body) && typeof body.method === 'string') return body.method;
    } catch {
      // The response will be an error and does not need method dispatch.
    }
  }
  return wireCase.request.bodyMethod ?? wireCase.request.mcpMethod;
}

function bodyMayBeAbsent(wireCase: WireCase, observed: Observation): boolean {
  return wireCase.id === 'wire-notification-rejected-no-execution' && observed.status === 202
    || wireCase.id === 'identity-query-token-rejected' && observed.status === 404
    || requestMethod(wireCase) === 'subscriptions/listen' && Boolean(observed.messages?.length);
}

type ReferenceIdException = {
  case: string;
  pointer: '/id';
  status: number;
  errorCode: number;
  value: null | number;
};

// Pinned SDK 2.3.1 exceptions, each also recorded in divergences.json. They
// authorize only the exact /id value on the exact case/status/error code.
const REFERENCE_ID_EXCEPTIONS: readonly ReferenceIdException[] = [
  { case: 'wire-malformed-json', pointer: '/id', status: 400, errorCode: -32_700, value: null },
  { case: 'wire-empty-body', pointer: '/id', status: 400, errorCode: -32_700, value: null },
  { case: 'wire-whitespace-body', pointer: '/id', status: 400, errorCode: -32_700, value: null },
  { case: 'wire-utf16le-bom-rejected', pointer: '/id', status: 400, errorCode: -32_700, value: null },
  { case: 'wire-utf16be-bom-rejected', pointer: '/id', status: 400, errorCode: -32_700, value: null },
  { case: 'wire-batch-rejected', pointer: '/id', status: 400, errorCode: -32_600, value: null },
  { case: 'wire-boolean-id-rejected', pointer: '/id', status: 400, errorCode: -32_600, value: null },
  { case: 'wire-missing-method', pointer: '/id', status: 400, errorCode: -32_600, value: null },
  { case: 'wire-id-null', pointer: '/id', status: 400, errorCode: -32_600, value: null },
  { case: 'wire-response-direction-rejected', pointer: '/id', status: 400, errorCode: -32_600, value: null },
  { case: 'http-wrong-content-type', pointer: '/id', status: 415, errorCode: -32_000, value: null },
  { case: 'http-get-rejected', pointer: '/id', status: 405, errorCode: -32_000, value: null },
  { case: 'security-origin-rejected', pointer: '/id', status: 403, errorCode: -32_000, value: null },
  { case: 'limits-body-size', pointer: '/id', status: 413, errorCode: -32_000, value: null },
  { case: 'wire-id-fraction-rejected', pointer: '/id', status: 400, errorCode: -32_600, value: 1.5 },
];

function isDocumentedReferenceException(
  wireCase: WireCase,
  observed: Observation,
  target: OracleTarget,
  error: ErrorObject,
): boolean {
  if (target !== 'reference') return false;
  if (error.keyword === 'type' && error.instancePath === '/id') {
    return isDocumentedReferenceIdValue(wireCase, observed, target);
  }
  return false;
}

function isDocumentedReferenceIdValue(
  wireCase: WireCase,
  observed: Observation,
  target: OracleTarget,
): boolean {
  if (target !== 'reference') return false;
  const body = observed.body;
  if (!isRecord(body) || !isRecord(body.error) || typeof body.error.code !== 'number') return false;
  return REFERENCE_ID_EXCEPTIONS.some(exception => exception.case === wireCase.id
    && observed.status === exception.status
    && body.error.code === exception.errorCode
    && Object.is(body.id, exception.value));
}

function isRecord(value: unknown): value is Record<string, any> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function display(value: unknown): string {
  const serialized = JSON.stringify(value);
  return serialized === undefined ? String(value) : serialized;
}

function completeResponse(responseDefinition: string, resultDefinition: string): ValidateFunction {
  return ajv.compile({
    allOf: [
      { $ref: `${SCHEMA_ID}#/$defs/${responseDefinition}` },
      { properties: { result: { allOf: [
        { $ref: `${SCHEMA_ID}#/$defs/${resultDefinition}` },
        { properties: { resultType: { const: 'complete' } }, required: ['resultType'] },
      ] } }, required: ['result'] },
    ],
  });
}

function inputRequiredResponse(responseDefinition: string): ValidateFunction {
  return ajv.compile({
    allOf: [
      { $ref: `${SCHEMA_ID}#/$defs/${responseDefinition}` },
      { properties: { result: { allOf: [
        { $ref: `${SCHEMA_ID}#/$defs/InputRequiredResult` },
        { properties: { resultType: { const: 'input_required' } }, required: ['resultType'],
          anyOf: [{ required: ['inputRequests'] }, { required: ['requestState'] }] },
      ] } }, required: ['result'] },
    ],
  });
}

function taskProperties(resultTypeValue: 'task' | 'complete', status: string): Record<string, unknown> {
  return {
    resultType: { const: resultTypeValue },
    _meta: { $ref: `${SCHEMA_ID}#/$defs/ResultMetaObject` },
    taskId: { type: 'string', minLength: 1 },
    status: { const: status },
    statusMessage: { type: 'string' },
    createdAt: { type: 'string', pattern: TIMESTAMP_PATTERN },
    lastUpdatedAt: { type: 'string', pattern: TIMESTAMP_PATTERN },
    ttlMs: { anyOf: [{ type: 'integer', minimum: 0 }, { type: 'null' }] },
    pollIntervalMs: { type: 'integer', minimum: 0 },
  };
}

function taskRequired(): string[] {
  return ['resultType', 'taskId', 'status', 'createdAt', 'lastUpdatedAt', 'ttlMs'];
}

function taskCreateResponse(): ValidateFunction {
  return taskResponse({
    type: 'object',
    properties: taskProperties('task', 'working'), required: taskRequired(),
    ...withoutTaskFields('inputRequests', 'requestState', 'result', 'error', 'task'),
  });
}

function taskGetResponse(): ValidateFunction {
  const variant = (status: string, payload: Record<string, unknown> = {}, required: string[] = []) => ({
    type: 'object',
    properties: { ...taskProperties('complete', status), ...payload },
    required: [...taskRequired(), ...required],
  });
  return taskResponse({ oneOf: [
    { ...variant('working'), ...withoutTaskFields('inputRequests', 'requestState', 'result', 'error', 'task') },
    { ...variant('input_required', {
      inputRequests: { allOf: [{ $ref: `${SCHEMA_ID}#/$defs/InputRequests` }, { minProperties: 1 }] },
    }, ['inputRequests']), ...withoutTaskFields('requestState', 'result', 'error', 'task') },
    { ...variant('completed', { result: { allOf: [
      { $ref: `${SCHEMA_ID}#/$defs/Result` },
      { properties: { resultType: { const: 'complete' } }, required: ['resultType'] },
    ] } }, ['result']), ...withoutTaskFields('inputRequests', 'requestState', 'error', 'task') },
    { ...variant('failed', { error: { $ref: `${SCHEMA_ID}#/$defs/Error` } }, ['error']),
      ...withoutTaskFields('inputRequests', 'requestState', 'result', 'task') },
    { ...variant('cancelled'), ...withoutTaskFields('inputRequests', 'requestState', 'result', 'error', 'task') },
  ] });
}

function withoutTaskFields(...names: string[]): Record<string, unknown> {
  return { allOf: names.map(name => ({ not: { required: [name] } })) };
}

function taskAckResponse(): ValidateFunction {
  return taskResponse({
    type: 'object', additionalProperties: false,
    properties: {
      resultType: { const: 'complete' },
      _meta: { $ref: `${SCHEMA_ID}#/$defs/ResultMetaObject` },
    },
    required: ['resultType'],
  });
}

function taskResponse(result: Record<string, unknown>): ValidateFunction {
  return ajv.compile({
    allOf: [
      { $ref: `${SCHEMA_ID}#/$defs/JSONRPCResultResponse` },
      { properties: { result }, required: ['result'] },
    ],
  });
}
