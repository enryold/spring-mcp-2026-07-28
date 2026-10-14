package io.github.enryold.mcp;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.regex.RegularExpressionFactory;
import java.io.InputStream;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Secure MRTR continuation handling and the {@code io.modelcontextprotocol/tasks} extension. */
public final class McpInteractions implements McpModule {
    public static final String TASKS_EXTENSION = "io.modelcontextprotocol/tasks";
    private static final SecureRandom RANDOM = new SecureRandom();
    static final int MAX_ACCEPTED_RESPONSE_BYTES = 256 * 1_024;
    static final int MAX_TASK_INPUT_KEYS = 256;
    private static final int MAX_SCHEMA_PATTERN_LENGTH = 4_096;
    private static final RegularExpressionFactory SCHEMA_REGEX_FACTORY = expression -> {
        if (expression.length() > MAX_SCHEMA_PATTERN_LENGTH) {
            throw new IllegalArgumentException("schema regular expression exceeds 4096 characters");
        }
        try {
            com.google.re2j.Pattern pattern = com.google.re2j.Pattern.compile(expression);
            return value -> pattern.matcher(value).find();
        } catch (com.google.re2j.PatternSyntaxException error) {
            throw new IllegalArgumentException("schema regular expression uses unsupported RE2 syntax", error);
        }
    };
    private static final Set<String> ELICIT_SCHEMA_KEYS = Set.of(
            "$schema", "type", "properties", "required", "title", "description", "default", "minimum",
            "maximum", "minLength", "maxLength", "format", "enum", "enumNames", "items", "minItems",
            "maxItems", "oneOf", "anyOf", "const");
    private static final ProtocolSchemas SCHEMAS = new ProtocolSchemas();

    private final McpInteractionStore store;
    private final ExecutorService executor;
    private final Clock clock;
    private final Duration continuationTtl;
    private final Duration taskTtl;
    private final long pollIntervalMs;

    public McpInteractions(McpInteractionStore store, ExecutorService executor) {
        this(store, executor, Clock.systemUTC(), Duration.ofMinutes(5), Duration.ofHours(1), Duration.ofMillis(100));
    }

    public McpInteractions(McpInteractionStore store, ExecutorService executor, Clock clock,
            Duration continuationTtl, Duration taskTtl, Duration pollInterval) {
        this.store = Objects.requireNonNull(store, "store");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.continuationTtl = positive(continuationTtl, "continuationTtl");
        this.taskTtl = positive(taskTtl, "taskTtl");
        this.pollIntervalMs = positive(pollInterval, "pollInterval").toMillis();
        if (pollIntervalMs < 1) throw new IllegalArgumentException("pollInterval must be at least one millisecond");
    }

    @Override
    public ObjectNode capabilities() {
        ObjectNode capabilities = object();
        capabilities.putObject("extensions").putObject(TASKS_EXTENSION);
        return capabilities;
    }

    @Override
    public Collection<McpMethod> methods() {
        return List.of(
                new McpMethod("tasks/get", null, null, this::getTask),
                new McpMethod("tasks/update", null, null, this::updateTask),
                new McpMethod("tasks/cancel", null, null, this::cancelTask));
    }

    public ToolResult execute(McpTool tool, ObjectNode validatedArguments, McpContext context) throws Exception {
        return execute(tool, validatedArguments, context, ToolResult::toObjectNode);
    }

    /** The encoder must apply the same complete-result validation used by synchronous dispatch. */
    public ToolResult execute(McpTool tool, ObjectNode validatedArguments, McpContext context,
            Function<ToolResult, ObjectNode> completionEncoder) throws Exception {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(validatedArguments, "validatedArguments");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(completionEncoder, "completionEncoder");
        context.authorize("tools/call", tool.name());
        context = validateInitialResponses("tools/call", context);

        String taskSupport = taskSupport(tool);
        boolean tasks = supports(context, TASKS_EXTENSION);
        if ("required".equals(taskSupport) && !tasks) throw missingTasks(context);

        ObjectNode request = context.requestParams();
        String token = text(request.get("requestState"));
        if (token != null) {
            return resumeTool(token, tool, validatedArguments, context, completionEncoder, taskSupport, tasks);
        }
        return finishToolResult(invokeTool(tool, validatedArguments, context), tool, validatedArguments,
                context, completionEncoder, taskSupport, tasks);
    }

    /** Wraps prompts/get or resources/read handlers with the same validated continuation contract. */
    public ObjectNode execute(String method, String name, ObjectNode salientParams, McpContext context,
            ObjectInvocation invocation) throws Exception {
        if (!"prompts/get".equals(method) && !"resources/read".equals(method)) {
            throw new IllegalArgumentException("MRTR is supported only for prompts/get and resources/read");
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(salientParams, "salientParams");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(invocation, "invocation");
        context.authorize(method, name);
        context = validateInitialResponses(method, context);

        String token = text(context.requestParams().get("requestState"));
        ObjectNode result;
        if (token == null) {
            result = Objects.requireNonNull(invocation.execute(context), "interaction result").deepCopy();
        } else {
            Continuation continuation = continuation(token, Binding.of(method, name, salientParams, context), context);
            synchronized (continuation) {
                if (continuation.terminal != null) {
                    ObjectNode terminal = (ObjectNode) continuation.terminal;
                    validateCachedObjectResult(terminal, context);
                    return terminal.deepCopy();
                }
                ObjectNode ready = acceptResponses(continuation, context);
                if (ready == null) {
                    validateRequests(continuation.pendingRequests, context, false);
                    return pendingObjectResult(continuation);
                }
                try {
                    McpContext resumed = resumedContext(context, method, name, salientParams,
                            ready, continuation.handlerState);
                    result = Objects.requireNonNull(invocation.execute(resumed), "interaction result").deepCopy();
                    result = protectObjectResult(result, Binding.of(method, name, salientParams, context), context);
                    continuation.terminal = result.deepCopy();
                    return result;
                } catch (Exception error) {
                    continuation.failure = failure(error, context);
                    throw continuation.failure;
                }
            }
        }
        return protectObjectResult(result, Binding.of(method, name, salientParams, context), context);
    }

    private ToolResult invokeTool(McpTool tool, ObjectNode arguments, McpContext context) throws Exception {
        return Objects.requireNonNull(tool.handler().execute(arguments.deepCopy(), context), "tool result");
    }

    private ToolResult resumeTool(String token, McpTool tool, ObjectNode arguments, McpContext context,
            Function<ToolResult, ObjectNode> completionEncoder, String taskSupport, boolean tasks) throws Exception {
        Continuation continuation = continuation(token, Binding.of("tools/call", tool.name(), arguments, context), context);
        synchronized (continuation) {
            if (continuation.failure != null) throw continuation.failure;
            if (continuation.terminal != null) {
                ToolResult terminal = (ToolResult) continuation.terminal;
                validateCachedToolResult(terminal, context, tasks);
                return terminal;
            }
            ObjectNode ready = acceptResponses(continuation, context);
            if (ready == null) {
                validateRequests(continuation.pendingRequests, context, false);
                return pendingToolResult(continuation);
            }
            try {
                McpContext resumed = resumedContext(context, "tools/call", tool.name(), arguments,
                        ready, continuation.handlerState);
                ToolResult result = finishToolResult(invokeTool(tool, arguments, resumed), tool, arguments,
                        resumed, completionEncoder, taskSupport, tasks);
                continuation.terminal = result;
                return result;
            } catch (Exception error) {
                continuation.failure = failure(error, context);
                throw continuation.failure;
            }
        }
    }

    private ToolResult finishToolResult(ToolResult result, McpTool tool, ObjectNode arguments, McpContext context,
            Function<ToolResult, ObjectNode> completionEncoder, String taskSupport, boolean tasks) throws Exception {
        if ("input_required".equals(result.resultType())) {
            return protectToolResult(result, Binding.of("tools/call", tool.name(), arguments, context), context);
        }
        if (!result.isDeferred()) return result;
        if (!tasks || "forbidden".equals(taskSupport)) {
            ToolResult completed = result.runDeferred(context);
            return finishToolResult(completed, tool, arguments, context, completionEncoder, taskSupport, false);
        }
        return createTask(tool, arguments, context, result, completionEncoder);
    }

    private void validateCachedToolResult(ToolResult result, McpContext context, boolean tasks) {
        if ("task".equals(result.resultType()) && !tasks) throw missingTasks(context);
        if ("input_required".equals(result.resultType())) {
            validateRequests(result.inputRequests(), context, false);
        }
    }

    private void validateCachedObjectResult(ObjectNode result, McpContext context) {
        if ("input_required".equals(text(result.get("resultType")))) {
            validateRequests(asObject(result.get("inputRequests")), context, false);
        }
    }

    private ToolResult protectToolResult(ToolResult result, Binding binding, McpContext context) {
        ObjectNode requests = validateRequests(result.inputRequests(), context, false);
        Continuation continuation = new Continuation(token(), binding, expires(continuationTtl), requests,
                result.requestState());
        putContinuation(continuation, context);
        return requests == null ? ToolResult.inputRequired(continuation.token)
                : ToolResult.inputRequired(requests, continuation.token);
    }

    private ObjectNode protectObjectResult(ObjectNode result, Binding binding, McpContext context) {
        if (!"input_required".equals(text(result.get("resultType")))) return result.deepCopy();
        ObjectNode requests = validateRequests(asObject(result.get("inputRequests")), context, false);
        String handlerState = text(result.get("requestState"));
        if (requests == null && handlerState == null) throw invalid(context, "input_required needs inputRequests or requestState");
        Continuation continuation = new Continuation(token(), binding, expires(continuationTtl), requests, handlerState);
        putContinuation(continuation, context);
        ObjectNode protectedResult = object().put("resultType", "input_required").put("requestState", continuation.token);
        if (requests != null) protectedResult.set("inputRequests", requests.deepCopy());
        if (result.get("_meta") instanceof ObjectNode metadata) protectedResult.set("_meta", metadata.deepCopy());
        return protectedResult;
    }

    private ToolResult createTask(McpTool tool, ObjectNode arguments, McpContext context, ToolResult deferred,
            Function<ToolResult, ObjectNode> completionEncoder) {
        Instant now = clock.instant();
        TaskRecord task = new TaskRecord(token(), Binding.of("tools/call", tool.name(), arguments, context),
                now, expires(taskTtl), taskTtl.toMillis(), pollIntervalMs, tool, arguments, completionEncoder);
        TaskExecution execution = execution(task, () -> runTask(task, context, deferred));
        task.execution = execution;
        try {
            store.putTask(task, now);
        } catch (IllegalStateException error) {
            execution.cancel();
            throw busy(context, "task capacity reached");
        }
        ObjectNode created = taskFields(task, false);
        try {
            executor.execute(execution);
        } catch (RejectedExecutionException error) {
            execution.cancel();
            store.removeTask(task.taskId, task);
            throw busy(context, "task executor capacity reached");
        }
        return ToolResult.task(created);
    }

    private void runTask(TaskRecord task, McpContext context, ToolResult first) {
        try {
            McpContext executionContext = taskContext(task, context);
            ToolResult result = first.isDeferred() ? first.runDeferred(executionContext) : first;
            settleTask(task, result, executionContext);
        } catch (Exception error) {
            failTask(task, error, context);
        }
    }

    private void resumeTask(TaskRecord task, McpContext context) {
        try {
            ObjectNode responses;
            String handlerState;
            synchronized (task) {
                if (!"working".equals(task.status)) return;
                responses = task.acceptedResponses.deepCopy();
                handlerState = task.handlerState;
            }
            McpContext resumed = taskContext(task, resumedContext(context, task.binding.method, task.binding.name,
                    task.arguments, responses, handlerState));
            ToolResult result = invokeTool(task.tool, task.arguments, resumed);
            if (result.isDeferred()) result = result.runDeferred(resumed);
            settleTask(task, result, resumed);
        } catch (Exception error) {
            failTask(task, error, context);
        }
    }

    private McpContext taskContext(TaskRecord task, McpContext context) {
        return context.withDetachedLifecycle(task.expiresAt, () -> {
            synchronized (task) {
                return task.expired || "cancelled".equals(task.status);
            }
        });
    }

    private void settleTask(TaskRecord task, ToolResult result, McpContext context) {
        synchronized (task) {
            if (task.expired || "cancelled".equals(task.status)) return;
            if ("input_required".equals(result.resultType())) {
                ObjectNode requests = validateRequests(result.inputRequests(), context, true);
                if (requests == null || requests.isEmpty()) {
                    failTaskLocked(task, invalid(context, "task input_required needs inputRequests"));
                    return;
                }
                Set<String> newKeys = new java.util.HashSet<>();
                for (String key : requests.propertyNames()) {
                    if (task.usedInputKeys.contains(key) || !newKeys.add(key)) {
                        failTaskLocked(task, invalid(context, "task inputRequest keys must not be reused"));
                        return;
                    }
                }
                if ((long) task.usedInputKeys.size() + newKeys.size() > MAX_TASK_INPUT_KEYS) {
                    failTaskLocked(task, invalid(context, "task inputRequest key budget exceeded"));
                    return;
                }
                task.usedInputKeys.addAll(newKeys);
                task.pendingRequests = requests;
                task.handlerState = result.requestState();
                task.status = "input_required";
                task.lastUpdatedAt = clock.instant();
                return;
            }
            if (!"complete".equals(result.resultType())) {
                failTaskLocked(task, invalid(context, "task execution returned an invalid result type"));
                return;
            }
            task.result = Objects.requireNonNull(task.completionEncoder.apply(result), "encoded task result").deepCopy();
            task.status = "completed";
            task.lastUpdatedAt = clock.instant();
            clearTaskInteractionState(task);
        }
    }

    private void failTask(TaskRecord task, Exception error, McpContext context) {
        synchronized (task) {
            if (!task.expired && !"cancelled".equals(task.status)) failTaskLocked(task, failure(error, context));
        }
    }

    private void failTaskLocked(TaskRecord task, McpProtocolException error) {
        JsonNode body = error.toResponse().path("error");
        task.error = body instanceof ObjectNode object ? object.deepCopy()
                : object().put("code", -32603).put("message", "Internal error");
        task.status = "failed";
        task.statusMessage = error.getMessage();
        task.lastUpdatedAt = clock.instant();
        clearTaskInteractionState(task);
    }

    private ObjectNode getTask(ObjectNode params, McpContext context) {
        requireTaskCapability(context);
        requireKeys(params, context, Set.of("taskId", "_meta"), Set.of("taskId"));
        TaskRecord task = ownedTask(text(params.get("taskId")), context);
        synchronized (task) {
            if ("input_required".equals(task.status)) validateRequests(task.pendingRequests, context, true);
            return taskFields(task, true).put("resultType", "complete");
        }
    }

    private ObjectNode updateTask(ObjectNode params, McpContext context) {
        requireTaskCapability(context);
        requireKeys(params, context, Set.of("taskId", "inputResponses", "_meta"), Set.of("taskId", "inputResponses"));
        ObjectNode responses = asObject(params.get("inputResponses"));
        if (responses == null) throw invalid(context, "tasks/update inputResponses must be an object");
        TaskRecord task = ownedTask(text(params.get("taskId")), context);
        TaskExecution next = null;
        synchronized (task) {
            if ("input_required".equals(task.status)) {
                ObjectNode accepted = task.acceptedResponses.deepCopy();
                ObjectNode pending = task.pendingRequests.deepCopy();
                for (Map.Entry<String, JsonNode> entry : responses.properties()) {
                    JsonNode request = pending.get(entry.getKey());
                    if (request != null && validResponse(request, entry.getValue())) {
                        accepted.set(entry.getKey(), entry.getValue().deepCopy());
                        pending.remove(entry.getKey());
                    }
                }
                requireResponseBudget(accepted, context);
                task.acceptedResponses.removeAll();
                task.acceptedResponses.setAll(accepted);
                task.pendingRequests = pending;
                task.lastUpdatedAt = clock.instant();
                if (task.pendingRequests.isEmpty()) {
                    task.status = "working";
                    next = execution(task, () -> resumeTask(task, context));
                    task.execution = next;
                }
            }
        }
        if (next != null) {
            try {
                executor.execute(next);
            } catch (RejectedExecutionException error) {
                next.cancel();
                failTask(task, error, context);
            }
        }
        return object().put("resultType", "complete");
    }

    private ObjectNode cancelTask(ObjectNode params, McpContext context) {
        requireTaskCapability(context);
        requireKeys(params, context, Set.of("taskId", "_meta"), Set.of("taskId"));
        TaskRecord task = ownedTask(text(params.get("taskId")), context);
        TaskExecution execution;
        synchronized (task) {
            if (!terminal(task.status)) {
                task.status = "cancelled";
                task.lastUpdatedAt = clock.instant();
                clearTaskInteractionState(task);
            }
            execution = task.execution;
        }
        if (execution != null) execution.cancel();
        return object().put("resultType", "complete");
    }

    private TaskRecord ownedTask(String taskId, McpContext context) {
        if (taskId == null || taskId.isBlank()) throw invalid(context, "taskId is required");
        TaskRecord task = store.task(taskId, clock.instant());
        if (task == null || !task.binding.owner(context)) throw invalid(context, "Unknown taskId");
        context.authorize(task.binding.method, task.binding.name);
        return task;
    }

    private ObjectNode taskFields(TaskRecord task, boolean detailed) {
        ObjectNode value = object()
                .put("taskId", task.taskId)
                .put("status", task.status)
                .put("createdAt", task.createdAt.toString())
                .put("lastUpdatedAt", task.lastUpdatedAt.toString())
                .put("ttlMs", task.ttlMs)
                .put("pollIntervalMs", task.pollIntervalMs);
        if (task.statusMessage != null) value.put("statusMessage", task.statusMessage);
        if (detailed && "input_required".equals(task.status)) value.set("inputRequests", task.pendingRequests.deepCopy());
        if (detailed && "completed".equals(task.status)) value.set("result", task.result.deepCopy());
        if (detailed && "failed".equals(task.status)) value.set("error", task.error.deepCopy());
        return value;
    }

    private Continuation continuation(String token, Binding binding, McpContext context) {
        Continuation continuation = store.continuation(token, clock.instant());
        if (continuation == null || !continuation.binding.matches(binding)) {
            throw invalid(context, "Invalid or expired requestState");
        }
        return continuation;
    }

    /** Returns all accepted responses when the round is ready, otherwise null. */
    private ObjectNode acceptResponses(Continuation continuation, McpContext context) {
        if (continuation.failure != null) throw continuation.failure;
        ObjectNode supplied = asObject(context.requestParams().get("inputResponses"));
        if (supplied != null && continuation.pendingRequests != null) {
            ObjectNode accepted = continuation.acceptedResponses.deepCopy();
            ObjectNode pending = continuation.pendingRequests.deepCopy();
            for (Map.Entry<String, JsonNode> entry : supplied.properties()) {
                JsonNode request = pending.get(entry.getKey());
                if (request != null && validResponse(request, entry.getValue())) {
                    accepted.set(entry.getKey(), entry.getValue().deepCopy());
                    pending.remove(entry.getKey());
                }
            }
            requireResponseBudget(accepted, context);
            continuation.acceptedResponses.removeAll();
            continuation.acceptedResponses.setAll(accepted);
            continuation.pendingRequests = pending;
        }
        return continuation.pendingRequests == null || continuation.pendingRequests.isEmpty()
                ? continuation.acceptedResponses.deepCopy() : null;
    }

    private static void requireResponseBudget(ObjectNode responses, McpContext context) {
        if (responses.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > MAX_ACCEPTED_RESPONSE_BYTES) {
            throw invalid(context, "accepted inputResponses byte budget exceeded");
        }
    }

    private TaskExecution execution(TaskRecord task, Runnable operation) {
        return new TaskExecution(task, operation);
    }

    private static void clearTaskInteractionState(TaskRecord task) {
        task.acceptedResponses.removeAll();
        task.pendingRequests = object();
        task.usedInputKeys.clear();
        task.handlerState = null;
    }

    private ToolResult pendingToolResult(Continuation continuation) {
        return ToolResult.inputRequired(continuation.pendingRequests, continuation.token);
    }

    private ObjectNode pendingObjectResult(Continuation continuation) {
        return object().put("resultType", "input_required").put("requestState", continuation.token)
                .set("inputRequests", continuation.pendingRequests.deepCopy());
    }

    private ObjectNode validateRequests(ObjectNode requests, McpContext context, boolean task) {
        if (requests == null) return null;
        if (!SCHEMAS.inputRequests.validate(requests).isEmpty()) {
            throw invalid(context, "Invalid inputRequests");
        }
        for (JsonNode request : requests) {
            String method = text(request.get("method"));
            if (!supportsRequest(context, request)) throw missingCapability(context, method);
        }
        if (!task && requests.isEmpty()) return null;
        return requests.deepCopy();
    }

    private boolean validResponse(JsonNode request, JsonNode response) {
        Schema schema = switch (text(request.get("method"))) {
            case "elicitation/create" -> null;
            case "sampling/createMessage" -> SCHEMAS.createMessageResult;
            case "roots/list" -> SCHEMAS.listRootsResult;
            default -> null;
        };
        if ("elicitation/create".equals(text(request.get("method")))) {
            return validElicitationResponse(request, response);
        }
        return schema != null && schema.validate(response).isEmpty();
    }

    private boolean validElicitationResponse(JsonNode request, JsonNode response) {
        if (!SCHEMAS.elicitResult.validate(response).isEmpty()) return false;
        if (!"accept".equals(text(response.get("action")))) return true;
        JsonNode requestedSchema = request.path("params").get("requestedSchema");
        if (requestedSchema == null) return true;
        JsonNode content = response.get("content");
        if (!(content instanceof ObjectNode) || !safeElicitationSchema(requestedSchema, 0)) return false;
        try {
            Schema schema = SCHEMAS.registry.getSchema(requestedSchema);
            schema.initializeValidators();
            return schema.validate(content).isEmpty();
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static boolean safeElicitationSchema(JsonNode node, int depth) {
        if (depth > 6) return false;
        if (node instanceof ObjectNode object) {
            if (object.size() > 256) return false;
            for (Map.Entry<String, JsonNode> entry : object.properties()) {
                if (!ELICIT_SCHEMA_KEYS.contains(entry.getKey())) return false;
                if ("properties".equals(entry.getKey())) {
                    if (depth != 0 || !(entry.getValue() instanceof ObjectNode properties)
                            || properties.size() > 256) return false;
                    for (JsonNode property : properties) {
                        if (!safeElicitationSchema(property, depth + 1)) return false;
                    }
                } else if (!safeElicitationSchema(entry.getValue(), depth + 1)) {
                    return false;
                }
            }
        } else if (node instanceof tools.jackson.databind.node.ArrayNode array) {
            if (array.size() > 256) return false;
            for (JsonNode child : array) if (!safeElicitationSchema(child, depth + 1)) return false;
        } else if (node.isTextual() && node.asText().length() > 4_096) {
            return false;
        }
        return true;
    }

    private static boolean supportsRequest(McpContext context, JsonNode request) {
        JsonNode capabilities = context.requestMetadata().path("io.modelcontextprotocol/clientCapabilities");
        String method = text(request.get("method"));
        JsonNode params = request.path("params");
        if ("roots/list".equals(method)) return capabilities.path("roots").isObject();
        if ("sampling/createMessage".equals(method)) {
            JsonNode sampling = capabilities.path("sampling");
            if (!sampling.isObject()) return false;
            if ((params.has("tools") || params.has("toolChoice")) && !sampling.path("tools").isObject()) return false;
            String include = text(params.get("includeContext"));
            return (include == null || "none".equals(include)) || sampling.path("context").isObject();
        }
        if ("elicitation/create".equals(method)) {
            JsonNode elicitation = capabilities.path("elicitation");
            if (!elicitation.isObject()) return false;
            String mode = text(params.get("mode"));
            if ("url".equals(mode)) return elicitation.path("url").isObject();
            // Empty elicitation capability is the frozen revision's legacy form declaration.
            return elicitation.isEmpty() || elicitation.path("form").isObject();
        }
        return false;
    }

    private McpContext resumedContext(McpContext context, String method, String name, ObjectNode salient,
            ObjectNode responses, String handlerState) {
        ObjectNode params = context.requestParams();
        params.set("inputResponses", responses.deepCopy());
        if (handlerState == null) params.remove("requestState");
        else params.put("requestState", handlerState);
        if ("tools/call".equals(method)) {
            params.put("name", name).set("arguments", salient.deepCopy());
        }
        return context.withRequestPreservingProgress(
                new McpRequest(context.requestId(), method, params, context.requestMetadata()));
    }

    private void requireTaskCapability(McpContext context) {
        if (!supports(context, TASKS_EXTENSION)) throw missingTasks(context);
    }

    private static boolean supports(McpContext context, String capability) {
        JsonNode capabilities = context.requestMetadata().path("io.modelcontextprotocol/clientCapabilities");
        return capability.contains("/")
                ? capabilities.path("extensions").path(capability).isObject()
                : capabilities.path(capability).isObject();
    }

    private static String taskSupport(McpTool tool) {
        String support = text(tool.descriptor().path("execution").get("taskSupport"));
        return support == null ? "forbidden" : support;
    }

    private static void requireKeys(ObjectNode params, McpContext context, Set<String> allowed, Set<String> required) {
        for (String name : params.propertyNames()) {
            if (!allowed.contains(name)) throw invalid(context, "Unexpected parameter: " + name);
        }
        for (String name : required) {
            if (!params.has(name)) throw invalid(context, "Missing parameter: " + name);
        }
    }

    private static McpContext validateInitialResponses(String method, McpContext context) {
        ObjectNode params = context.requestParams();
        ObjectNode responses = asObject(params.get("inputResponses"));
        if (responses != null && text(params.get("requestState")) == null) {
            if (!responses.isEmpty()) throw invalid(context, "inputResponses require a valid requestState");
            params.remove("inputResponses");
            return context.withRequestPreservingProgress(
                    new McpRequest(context.requestId(), method, params, context.requestMetadata()));
        }
        return context;
    }

    private void putContinuation(Continuation continuation, McpContext context) {
        try {
            store.putContinuation(continuation, clock.instant());
        } catch (IllegalStateException error) {
            throw busy(context, "continuation capacity reached");
        }
    }

    private Instant expires(Duration ttl) {
        return clock.instant().plus(ttl);
    }

    private static boolean terminal(String status) {
        return "completed".equals(status) || "cancelled".equals(status) || "failed".equals(status);
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static McpProtocolException missingTasks(McpContext context) {
        ObjectNode required = object();
        required.putObject("requiredCapabilities").putObject("extensions").putObject(TASKS_EXTENSION);
        return new McpProtocolException(400, -32021, "Missing required client capability", required, context.requestId());
    }

    private static McpProtocolException missingCapability(McpContext context, String capability) {
        ObjectNode required = object();
        ObjectNode capabilities = required.putObject("requiredCapabilities");
        if ("elicitation/create".equals(capability)) capabilities.putObject("elicitation");
        else if ("sampling/createMessage".equals(capability)) capabilities.putObject("sampling");
        else if ("roots/list".equals(capability)) capabilities.putObject("roots");
        else if (capability != null && capability.contains("/")) capabilities.putObject("extensions").putObject(capability);
        else if (capability != null) capabilities.putObject(capability);
        return new McpProtocolException(400, -32021, "Missing required client capability", required, context.requestId());
    }

    private static McpProtocolException invalid(McpContext context, String message) {
        return new McpProtocolException(200, -32602, message, null, context.requestId());
    }

    private static McpProtocolException busy(McpContext context, String message) {
        return new McpProtocolException(503, -32000, message, null, context.requestId());
    }

    private static McpProtocolException failure(Exception error, McpContext context) {
        if (error instanceof McpProtocolException protocol) return protocol;
        if (error instanceof McpAccessException) {
            return new McpProtocolException(403, -32001, "Forbidden", null, context.requestId());
        }
        return new McpProtocolException(200, -32603, "Internal error",
                object().put("correlationId", token()), context.requestId());
    }

    private static ObjectNode asObject(JsonNode value) {
        return value instanceof ObjectNode object ? object : null;
    }

    private static String text(JsonNode value) {
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    @FunctionalInterface
    public interface ObjectInvocation {
        ObjectNode execute(McpContext context) throws Exception;
    }

    static final class Continuation {
        final String token;
        final Binding binding;
        final Instant expiresAt;
        final ObjectNode acceptedResponses = object();
        ObjectNode pendingRequests;
        final String handlerState;
        Object terminal;
        McpProtocolException failure;

        Continuation(String token, Binding binding, Instant expiresAt, ObjectNode requests, String handlerState) {
            this.token = token;
            this.binding = binding;
            this.expiresAt = expiresAt;
            this.pendingRequests = requests == null ? null : requests.deepCopy();
            this.handlerState = handlerState;
        }
    }

    static final class TaskRecord {
        final String taskId;
        final Binding binding;
        final Instant createdAt;
        final Instant expiresAt;
        final long ttlMs;
        final long pollIntervalMs;
        final McpTool tool;
        final ObjectNode arguments;
        final Function<ToolResult, ObjectNode> completionEncoder;
        final ObjectNode acceptedResponses = object();
        final Set<String> usedInputKeys = new java.util.HashSet<>();
        final AtomicInteger liveExecutions = new AtomicInteger();
        Instant lastUpdatedAt;
        String status = "working";
        String statusMessage;
        String handlerState;
        ObjectNode pendingRequests = object();
        ObjectNode result;
        ObjectNode error;
        volatile boolean expired;
        volatile TaskExecution execution;

        TaskRecord(String taskId, Binding binding, Instant createdAt, Instant expiresAt, long ttlMs,
                long pollIntervalMs, McpTool tool, ObjectNode arguments,
                Function<ToolResult, ObjectNode> completionEncoder) {
            this.taskId = taskId;
            this.binding = binding;
            this.createdAt = createdAt;
            this.lastUpdatedAt = createdAt;
            this.expiresAt = expiresAt;
            this.ttlMs = ttlMs;
            this.pollIntervalMs = pollIntervalMs;
            this.tool = tool;
            this.arguments = arguments.deepCopy();
            this.completionEncoder = completionEncoder;
        }

        void expireExecution() {
            TaskExecution active = execution;
            if (active != null) active.cancel();
        }
    }

    final class TaskExecution implements Runnable {
        private final TaskRecord task;
        private final FutureTask<Void> future;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean exited = new AtomicBoolean();

        TaskExecution(TaskRecord task, Runnable operation) {
            this.task = task;
            this.future = new FutureTask<>(() -> {
                operation.run();
                return null;
            });
            task.liveExecutions.incrementAndGet();
        }

        @Override
        public void run() {
            started.set(true);
            try {
                future.run();
            } finally {
                exit();
            }
        }

        void cancel() {
            future.cancel(true);
            if (!started.get()) exit();
        }

        private void exit() {
            if (!exited.compareAndSet(false, true)) return;
            task.liveExecutions.decrementAndGet();
            store.executionExited(task);
        }
    }

    private record Binding(String method, String name, ObjectNode salient, String subject, String tenant) {
        static Binding of(String method, String name, ObjectNode salient, McpContext context) {
            return new Binding(method, name, salient.deepCopy(), context.subject(), context.tenant());
        }

        boolean owner(McpContext context) {
            return subject.equals(context.subject()) && tenant.equals(context.tenant());
        }

        boolean matches(Binding other) {
            return method.equals(other.method) && name.equals(other.name) && salient.equals(other.salient)
                    && subject.equals(other.subject) && tenant.equals(other.tenant);
        }
    }

    private static final class ProtocolSchemas {
        final SchemaRegistry registry;
        final Schema inputRequests;
        final Schema elicitResult;
        final Schema createMessageResult;
        final Schema listRootsResult;

        ProtocolSchemas() {
            try (InputStream input = McpInteractions.class.getResourceAsStream("schema-2026-07-28.json")) {
                if (input == null) throw new IllegalStateException("missing bundled protocol schema");
                ObjectNode protocol = (ObjectNode) JsonMapper.shared().readTree(input);
                registry = SchemaRegistry.withDefaultDialect(
                        SpecificationVersion.DRAFT_2020_12,
                        builder -> builder.schemaLoader(loader -> loader.fetchRemoteResources(false))
                                .schemaRegistryConfig(SchemaRegistryConfig.builder().failFast(true).preloadSchema(true)
                                        .regularExpressionFactory(SCHEMA_REGEX_FACTORY).typeLoose(false).build()));
                inputRequests = definition(registry, protocol, "InputRequests");
                elicitResult = definition(registry, protocol, "ElicitResult");
                createMessageResult = definition(registry, protocol, "CreateMessageResult");
                listRootsResult = definition(registry, protocol, "ListRootsResult");
            } catch (Exception error) {
                throw new ExceptionInInitializerError(error);
            }
        }

        private static Schema definition(SchemaRegistry registry, ObjectNode protocol, String name) {
            ObjectNode wrapper = protocol.deepCopy().put("$ref", "#/$defs/" + name);
            Schema schema = registry.getSchema(wrapper);
            schema.initializeValidators();
            return schema;
        }
    }
}
