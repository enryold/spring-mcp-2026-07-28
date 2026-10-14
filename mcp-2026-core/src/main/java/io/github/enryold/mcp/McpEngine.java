package io.github.enryold.mcp;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.regex.RegularExpressionFactory;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class McpEngine {
    public static final String PROTOCOL_VERSION = "2026-07-28";

    private static final int DEFAULT_MAX_JSON_DEPTH = 64;
    private static final int DEFAULT_MAX_REQUEST_BYTES = 1_048_576;
    private static final int MAX_SCHEMA_DEPTH = 64;
    private static final int MAX_SCHEMA_NODES = 4_096;
    private static final int MAX_SCHEMA_PATTERN_LENGTH = 4_096;
    private static final Pattern HTTP_TOKEN = Pattern.compile("[!#$%&'*+\\-.^_`|~0-9A-Za-z]+");
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
    private static final Set<String> PRIMITIVE_HEADER_TYPES = Set.of("string", "integer", "boolean");
    private static final List<String> REFERENCE_KEYWORDS = List.of("$ref", "$dynamicRef", "$recursiveRef");
    private static final Set<String> SINGLE_SCHEMA_KEYWORDS = Set.of(
            "additionalProperties", "contains", "contentSchema", "else", "if", "items", "not",
            "propertyNames", "then", "unevaluatedItems", "unevaluatedProperties");
    private static final Set<String> ARRAY_SCHEMA_KEYWORDS = Set.of("allOf", "anyOf", "oneOf", "prefixItems");
    private static final Set<String> MAP_SCHEMA_KEYWORDS = Set.of(
            "$defs", "definitions", "dependentSchemas", "patternProperties", "properties");
    private static final List<String> NON_REACHABLE_SCHEMA_KEYS = List.of(
            "items", "prefixItems", "contains", "additionalProperties", "unevaluatedProperties",
            "unevaluatedItems", "propertyNames", "patternProperties", "dependentSchemas", "oneOf",
            "anyOf", "allOf", "not", "if", "then", "else", "$defs", "definitions");
    private final ObjectMapper json;
    private final int maxRequestBytes;
    private final ObjectNode serverInfo;
    private final McpAuthorization authorization;
    private final Map<String, RegisteredTool> tools;
    private final SchemaRegistry schemas;
    private final Schema requestMetaSchema;
    private final Schema implementationSchema;
    private final Schema toolDescriptorSchema;
    private final Schema clientCapabilitiesSchema;
    private final Schema callToolResultSchema;
    private final Schema jsonMetaSchema;
    private final Schema callToolRequestSchema;
    private final Schema inputRequiredResultSchema;
    private final Map<String, RegisteredMethod> methods;
    private final ObjectNode capabilities;
    private final McpInteractions interactions;

    public McpEngine(JsonNode serverInfo, Collection<McpTool> tools, McpAuthorization authorization) {
        this(serverInfo, tools, authorization, DEFAULT_MAX_JSON_DEPTH, DEFAULT_MAX_REQUEST_BYTES);
    }

    public McpEngine(JsonNode serverInfo, Collection<McpTool> tools, McpAuthorization authorization,
            Collection<McpModule> modules) {
        this(serverInfo, tools, authorization, DEFAULT_MAX_JSON_DEPTH, DEFAULT_MAX_REQUEST_BYTES, modules);
    }

    public McpEngine(
            JsonNode serverInfo,
            Collection<McpTool> tools,
            McpAuthorization authorization,
            int maxJsonDepth) {
        this(serverInfo, tools, authorization, maxJsonDepth, DEFAULT_MAX_REQUEST_BYTES);
    }

    public McpEngine(
            JsonNode serverInfo,
            Collection<McpTool> tools,
            McpAuthorization authorization,
            int maxJsonDepth,
            int maxRequestBytes) {
        this(serverInfo, tools, authorization, maxJsonDepth, maxRequestBytes, List.of());
    }

    public McpEngine(JsonNode serverInfo, Collection<McpTool> tools, McpAuthorization authorization,
            int maxJsonDepth, int maxRequestBytes, Collection<McpModule> modules) {
        if (maxJsonDepth < 2) {
            throw new IllegalArgumentException("maxJsonDepth must be at least 2");
        }
        if (maxRequestBytes < 1) {
            throw new IllegalArgumentException("maxRequestBytes must be positive");
        }
        this.maxRequestBytes = maxRequestBytes;
        this.json = JsonMapper.builder(JsonFactory.builder()
                        .streamReadConstraints(StreamReadConstraints.builder()
                                .maxNestingDepth(maxJsonDepth)
                                .maxDocumentLength(maxRequestBytes)
                                .maxStringLength(maxRequestBytes)
                                .maxNameLength(maxRequestBytes)
                                .maxNumberLength(maxRequestBytes)
                                .maxTokenCount(maxRequestBytes)
                                .build())
                        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .build())
                .build();
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.schemas = SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_2020_12,
                builder -> builder
                        .schemaLoader(loader -> loader.fetchRemoteResources(false))
                        .schemaRegistryConfig(SchemaRegistryConfig.builder()
                                .failFast(true)
                                .preloadSchema(true)
                                .regularExpressionFactory(SCHEMA_REGEX_FACTORY)
                                .typeLoose(false)
                                .build()));
        ObjectNode protocolSchema;
        try {
            protocolSchema = readProtocolSchema();
            this.requestMetaSchema = protocolDefinition(protocolSchema, "RequestMetaObject");
            this.implementationSchema = protocolDefinition(protocolSchema, "Implementation");
            this.toolDescriptorSchema = protocolDefinition(protocolSchema, "Tool");
            this.clientCapabilitiesSchema = protocolDefinition(protocolSchema, "ClientCapabilities");
            this.callToolResultSchema = protocolDefinition(protocolSchema, "CallToolResult");
            this.callToolRequestSchema = protocolDefinition(protocolSchema, "CallToolRequestParams");
            this.inputRequiredResultSchema = protocolDefinition(protocolSchema, "InputRequiredResult");
            this.jsonMetaSchema = schemas.getSchema(
                    SchemaLocation.of(SpecificationVersion.DRAFT_2020_12.getDialectId()));
        } catch (Exception error) {
            throw new IllegalStateException("invalid bundled protocol schema", error);
        }
        this.serverInfo = validateServerInfo(serverInfo);
        this.tools = register(Objects.requireNonNull(tools, "tools"));
        this.capabilities = json.createObjectNode().set("tools", json.createObjectNode());
        this.capabilities.putObject("logging");
        this.methods = registerModules(Objects.requireNonNull(modules, "modules"), protocolSchema);
        validateCapabilityMethods();
        this.interactions = modules.stream().filter(McpInteractions.class::isInstance)
                .map(McpInteractions.class::cast).findFirst().orElse(null);
    }

    public McpRequest readRequest(byte[] utf8Json) {
        Objects.requireNonNull(utf8Json, "utf8Json");
        if (utf8Json.length > maxRequestBytes) {
            throw protocol(413, -32000, "Request body too large", null, null);
        }

        JsonNode value;
        try {
            String document = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8Json))
                    .toString();
            if (!document.isEmpty() && document.charAt(0) == '\ufeff') {
                document = document.substring(1);
            }
            try (JsonParser parser = json.createParser(document)) {
                value = json.readTree(parser);
                if (value == null) {
                    throw new IllegalArgumentException("empty JSON document");
                }
                if (parser.nextToken() != null) {
                    throw new IllegalArgumentException("trailing JSON value");
                }
            }
        } catch (Exception error) {
            throw protocol(400, -32700, "Parse error", null, null);
        }
        if (!(value instanceof ObjectNode object)) {
            throw protocol(400, -32600, "Invalid Request", null, null);
        }

        if (!object.has("id")) {
            if ("2.0".equals(textValue(object.get("jsonrpc")))
                    && textValue(object.get("method")) != null
                    && (!object.has("params") || object.get("params") instanceof ObjectNode)) {
                throw protocol(404, -32601, "Method not found", null, null);
            }
            throw protocol(400, -32600, "Invalid Request", null, null);
        }
        JsonNode candidateId = validId(object.get("id")) ? object.get("id") : null;
        if (!validId(object.get("id"))
                || !"2.0".equals(textValue(object.get("jsonrpc")))
                || textValue(object.get("method")) == null) {
            throw protocol(400, -32600, "Invalid Request", null, candidateId);
        }
        if (!(object.get("params") instanceof ObjectNode params)) {
            throw protocol(400, -32602, "Invalid params", null, candidateId);
        }
        if (!(params.get("_meta") instanceof ObjectNode metadata)
                || !requestMetaSchema.validate(metadata).isEmpty()) {
            throw protocol(400, -32602, "Invalid _meta envelope for protocol revision 2026-07-28", null, candidateId);
        }
        return new McpRequest(candidateId, object.path("method").asText(), params, metadata);
    }

    public ObjectNode dispatch(McpRequest request, McpContext context) {
        return dispatch(request, context, authorization);
    }

    public ObjectNode dispatch(
            McpRequest request,
            McpContext context,
            McpAuthorization requestAuthorization) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(requestAuthorization, "requestAuthorization");
        validateVersion(request);
        context = context.withRequest(request).withAuthorization(requestAuthorization);

        if (!request.method().equals("server/discover")
                && !request.method().equals("tools/list")
                && !request.method().equals("tools/call")
                && !methods.containsKey(request.method())) {
            throw protocol(404, -32601, "Method not found", null, request.id());
        }

        List<ObjectNode> buffered = new ArrayList<>();
        long[] bufferedBytes = { 0 };
        RegisteredTool candidate = "tools/call".equals(request.method()) ? tools.get(request.params().path("name").asString("")) : null;
        boolean mayCreateTask = candidate != null && interactions != null
                && !"forbidden".equals(candidate.descriptor().path("execution").path("taskSupport").asString("forbidden"))
                && request.metadata().path("io.modelcontextprotocol/clientCapabilities").path("extensions")
                    .has(McpInteractions.TASKS_EXTENSION);
        McpContext original = context;
        context = mayCreateTask ? context.bufferingNotifications(notification -> {
            synchronized (buffered) {
                long bytes = notification.toString().getBytes(StandardCharsets.UTF_8).length;
                if (buffered.size() >= 256 || bufferedBytes[0] + bytes > maxRequestBytes) {
                    throw new IllegalStateException("initial task notification buffer exceeded");
                }
                bufferedBytes[0] += bytes;
                buffered.add(notification);
            }
        }) : context;

        String toolName = textValue(request.params().get("name"));
        if (toolName == null) toolName = textValue(request.params().get("uri"));
        if (toolName == null) toolName = textValue(request.params().get("taskId"));
        authorize(requestAuthorization, context, request.method(), toolName, request.id());
        checkCancelled(context, request.id());

        ObjectNode response = switch (request.method()) {
            case "server/discover" -> response(request.id(), discover());
            case "tools/list" -> response(request.id(), listTools(request, context, requestAuthorization));
            case "tools/call" -> response(request.id(), callTool(request, context));
            default -> response(request.id(), callMethod(request, context));
        };
        if (!"task".equals(response.at("/result/resultType").asString(""))) {
            synchronized (buffered) {
                for (ObjectNode notification : buffered) original.emitNotification(notification);
            }
        }
        return response;
    }

    public boolean streams(McpRequest request) {
        if ("subscriptions/listen".equals(request.method())) return methods.containsKey(request.method());
        RegisteredTool tool = "tools/call".equals(request.method()) ? tools.get(request.params().path("name").asString()) : null;
        return request.metadata().has("progressToken")
                || request.metadata().has("io.modelcontextprotocol/logLevel")
                || tool != null && tool.tool().streaming();
    }

    public boolean supports(String method) {
        return Set.of("server/discover", "tools/list", "tools/call").contains(method) || methods.containsKey(method);
    }

    public ObjectNode descriptor(String name) {
        RegisteredTool tool = tools.get(name);
        return tool == null ? null : tool.descriptor().deepCopy();
    }

    private ObjectNode discover() {
        ObjectNode result = completeResult();
        ArrayNode versions = result.putArray("supportedVersions");
        versions.add(PROTOCOL_VERSION);
        result.set("capabilities", capabilities.deepCopy());
        return cacheable(result);
    }

    private ObjectNode listTools(
            McpRequest request,
            McpContext context,
            McpAuthorization requestAuthorization) {
        ObjectNode params = request.params();
        if (params.has("cursor")) {
            throw protocol(200, -32602, "Invalid cursor", null, request.id());
        }
        ObjectNode result = cacheable(completeResult());
        ArrayNode visible = result.putArray("tools");
        tools.values().stream()
                .sorted(Comparator.comparing(tool -> tool.tool().name()))
                .filter(tool -> visible(requestAuthorization, context, tool.tool().name(), request.id()))
                .forEach(tool -> visible.add(tool.descriptor().deepCopy()));
        return result;
    }

    private ObjectNode callTool(McpRequest request, McpContext context) {
        ObjectNode params = request.params();
        String name = textValue(params.get("name"));
        if (name == null || !callToolRequestSchema.validate(params).isEmpty()) {
            throw protocol(200, -32602, "Invalid params for tools/call", null, request.id());
        }
        rejectUnregisteredRetry(params, request.id());
        RegisteredTool registered = tools.get(name);
        if (registered == null) {
            throw protocol(200, -32602, "Tool " + name + " not found", null, request.id());
        }
        requireClientCapabilities(registered, request);

        ObjectNode arguments = params.get("arguments") instanceof ObjectNode supplied
                ? supplied.deepCopy()
                : json.createObjectNode();
        List<com.networknt.schema.Error> inputErrors = registered.inputSchema().validate(arguments);
        if (!inputErrors.isEmpty()) {
            String location = inputErrors.getFirst().getInstanceLocation().toString();
            String detail = location.isEmpty() ? inputErrors.getFirst().getKeyword() : location + ": " + inputErrors.getFirst().getKeyword();
            return toolResult(ToolResult.error(textContent(
                    "Input validation error: Invalid arguments for tool " + name + ": " + detail)), registered, request.id());
        }

        checkCancelled(context, request.id());
        ToolResult result;
        try {
            result = interactions == null
                    ? Objects.requireNonNull(registered.tool().handler().execute(arguments.deepCopy(), context), "tool result")
                    : interactions.execute(registered.tool(), arguments.deepCopy(), context,
                            completion -> toolResult(completion, registered, request.id()));
        } catch (McpProtocolException error) {
            throw protocol(error.httpStatus(), error.code(), error.getMessage(), error.data(), request.id());
        } catch (McpRateLimitException error) {
            throw rateLimit(error, request.id());
        } catch (McpAccessException error) {
            throw access(error, request.id());
        } catch (Exception error) {
            throw internal(request.id());
        }
        if (!"task".equals(result.resultType())) checkCancelled(context, request.id());
        return toolResult(result, registered, request.id());
    }

    private Map<String, RegisteredMethod> registerModules(Collection<McpModule> modules, ObjectNode protocolSchema) {
        var registered = new HashMap<String, RegisteredMethod>();
        Schema capabilitySchema = protocolDefinition(protocolSchema, "ServerCapabilities");
        for (McpModule module : modules) {
            ObjectNode declared = Objects.requireNonNull(module.capabilities(), "module capabilities");
            if (!capabilitySchema.validate(declared).isEmpty()) throw new IllegalArgumentException("invalid module capabilities");
            mergeCapabilities(capabilities, declared);
            for (McpMethod method : module.methods()) {
                if (Set.of("server/discover", "tools/list", "tools/call", "initialize", "ping", "logging/setLevel",
                        "resources/subscribe", "resources/unsubscribe", "tasks/list", "tasks/result").contains(method.name()))
                    throw new IllegalArgumentException("reserved protocol method: " + method.name());
                if (method.name().startsWith("tasks/") && !Set.of("tasks/get", "tasks/update", "tasks/cancel").contains(method.name()))
                    throw new IllegalArgumentException("unsupported Tasks method: " + method.name());
                if ((method.requestSchema() == null || method.resultSchema() == null)
                        && !Set.of("tasks/get", "tasks/update", "tasks/cancel").contains(method.name()))
                    throw new IllegalArgumentException("registered methods require pinned schemas");
                var entry = new RegisteredMethod(method,
                        method.requestSchema() == null ? null : protocolDefinition(protocolSchema, method.requestSchema()),
                        method.resultSchema() == null ? null : protocolDefinition(protocolSchema, method.resultSchema()));
                if (registered.putIfAbsent(method.name(), entry) != null)
                    throw new IllegalArgumentException("duplicate method: " + method.name());
            }
        }
        return Map.copyOf(registered);
    }

    private static void mergeCapabilities(ObjectNode target, ObjectNode source) {
        for (var entry : source.properties()) {
            JsonNode previous = target.get(entry.getKey());
            if (previous instanceof ObjectNode object && entry.getValue() instanceof ObjectNode nested)
                mergeCapabilities(object, nested);
            else if (previous != null && !previous.equals(entry.getValue()))
                throw new IllegalArgumentException("conflicting capability: " + entry.getKey());
            else target.set(entry.getKey(), entry.getValue().deepCopy());
        }
    }

    private void validateCapabilityMethods() {
        for (var entry : Map.of(
                "resources", List.of("resources/list", "resources/templates/list", "resources/read"),
                "prompts", List.of("prompts/list", "prompts/get"),
                "completions", List.of("completion/complete"),
                "extensions/io.modelcontextprotocol~1tasks", List.of("tasks/get", "tasks/update", "tasks/cancel")).entrySet()) {
            boolean declared = !capabilities.at("/" + entry.getKey()).isMissingNode();
            boolean any = entry.getValue().stream().anyMatch(methods::containsKey);
            boolean all = entry.getValue().stream().allMatch(methods::containsKey);
            if (declared && !all || !declared && any) {
                throw new IllegalArgumentException("capability and handlers must match: " + entry.getKey());
            }
        }
        if ((capabilities.path("tools").path("listChanged").asBoolean(false)
                || capabilities.path("prompts").path("listChanged").asBoolean(false)
                || capabilities.path("resources").path("listChanged").asBoolean(false)
                || capabilities.path("resources").path("subscribe").asBoolean(false))
                && !methods.containsKey("subscriptions/listen")) {
            throw new IllegalArgumentException("change capabilities require subscriptions/listen");
        }
    }

    private ObjectNode callMethod(McpRequest request, McpContext context) {
        if (Set.of("prompts/get", "resources/read").contains(request.method())) rejectUnregisteredRetry(request.params(), request.id());
        RegisteredMethod registered = methods.get(request.method());
        if (registered.requestSchema() != null) {
            ObjectNode wire = json.createObjectNode().put("jsonrpc", "2.0").put("method", request.method());
            wire.set("id", request.id());
            wire.set("params", request.params());
            if (!registered.requestSchema().validate(wire).isEmpty())
                throw protocol(200, -32602, "Invalid params for " + request.method(), null, request.id());
        }
        try {
            ObjectNode result;
            if (interactions != null && Set.of("prompts/get", "resources/read").contains(request.method())) {
                ObjectNode salient = request.params();
                salient.remove(List.of("_meta", "inputResponses", "requestState"));
                String name = textValue(request.params().get("name"));
                if (name == null) name = textValue(request.params().get("uri"));
                result = interactions.execute(request.method(), name, salient, context,
                        resumed -> registered.method().handler().execute(resumed.requestParams(), resumed));
            } else result = registered.method().handler().execute(request.params(), context);
            result = Objects.requireNonNull(result, "method result").deepCopy();
            checkCancelled(context, request.id());
            if (!result.has("resultType")) result.put("resultType", "complete");
            result.withObject("_meta").set("io.modelcontextprotocol/serverInfo", serverInfo.deepCopy());
            String type = result.path("resultType").asString();
            if ("input_required".equals(type)) {
                if (interactions == null || !Set.of("prompts/get", "resources/read").contains(request.method())
                        || !inputRequiredResultSchema.validate(result).isEmpty()
                        || !result.has("inputRequests") && !result.has("requestState")) throw internal(request.id());
            } else if (!"complete".equals(type) || registered.resultSchema() != null
                    && !registered.resultSchema().validate(result).isEmpty()) throw internal(request.id());
            return result;
        } catch (McpProtocolException error) {
            throw protocol(error.httpStatus(), error.code(), error.getMessage(), error.data(), request.id());
        } catch (McpRateLimitException error) {
            throw rateLimit(error, request.id());
        } catch (McpAccessException error) {
            throw access(error, request.id());
        } catch (Exception error) {
            throw internal(request.id());
        }
    }

    private void rejectUnregisteredRetry(ObjectNode params, JsonNode id) {
        if (interactions == null && (params.has("requestState") || params.has("inputResponses"))) {
            throw protocol(200, -32602, "Multi-round requests require registered interactions", null, id);
        }
    }

    private ObjectNode toolResult(ToolResult result, RegisteredTool tool, JsonNode id) {
        if ("complete".equals(result.resultType()) && tool.outputSchema() != null && !result.isError()) {
            if (!result.hasStructuredContent() || !tool.outputSchema().validate(result.structuredContent()).isEmpty()) {
                return toolResult(ToolResult.error(textContent(
                        "Output validation error: Invalid structured content for tool " + tool.tool().name())), tool, id);
            }
        }

        ObjectNode wire = result.toObjectNode();
        wire.withObject("_meta").set("io.modelcontextprotocol/serverInfo", serverInfo.deepCopy());
        if ("input_required".equals(result.resultType())) {
            if (interactions == null || !inputRequiredResultSchema.validate(wire).isEmpty()
                    || !wire.has("inputRequests") && !wire.has("requestState")) throw internal(id);
        } else if ("task".equals(result.resultType())) {
            if (interactions == null || !wire.path("taskId").isString() || !wire.path("status").isString()
                    || !wire.path("createdAt").isString() || !wire.path("lastUpdatedAt").isString()) throw internal(id);
        } else if (!"complete".equals(result.resultType()) || !callToolResultSchema.validate(wire).isEmpty()) {
            throw internal(id);
        }
        return wire;
    }

    private void validateVersion(McpRequest request) {
        String requested = request.metadata().path("io.modelcontextprotocol/protocolVersion").asText();
        if (PROTOCOL_VERSION.equals(requested)) {
            return;
        }
        ObjectNode data = json.createObjectNode().put("requested", requested);
        data.putArray("supported").add(PROTOCOL_VERSION);
        throw protocol(400, -32022, "Unsupported protocol version: " + requested, data, request.id());
    }

    private Map<String, RegisteredTool> register(Collection<McpTool> candidates) {
        Map<String, RegisteredTool> registered = new HashMap<>();
        for (McpTool tool : candidates) {
            Objects.requireNonNull(tool, "tool");
            ObjectNode descriptor = validateDescriptor(tool.descriptor());
            String name = descriptor.path("name").asText();
            validateHeaderDeclarations(descriptor.path("inputSchema"));
            Schema input = compileSchema(descriptor.path("inputSchema"), name + " inputSchema");
            Schema output = descriptor.get("outputSchema") == null
                    ? null
                    : compileSchema(descriptor.path("outputSchema"), name + " outputSchema");
            ObjectNode requiredCapabilities = tool.requiredClientCapabilities();
            if (requiredCapabilities != null && !clientCapabilitiesSchema.validate(requiredCapabilities).isEmpty()) {
                throw new IllegalArgumentException(name + " requiredClientCapabilities is invalid");
            }
            if (registered.putIfAbsent(
                    name, new RegisteredTool(tool, descriptor, input, output, requiredCapabilities)) != null) {
                throw new IllegalArgumentException("duplicate tool name: " + name);
            }
        }
        return Map.copyOf(registered);
    }

    private ObjectNode validateDescriptor(ObjectNode descriptor) {
        String name = textValue(descriptor.get("name"));
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool descriptor name must not be blank");
        }
        if (!(descriptor.get("inputSchema") instanceof ObjectNode input)
                || !"object".equals(textValue(input.get("type")))) {
            throw new IllegalArgumentException(name + " inputSchema must have root type object");
        }
        if (descriptor.has("outputSchema") && !(descriptor.get("outputSchema") instanceof ObjectNode)) {
            throw new IllegalArgumentException(name + " outputSchema must be an object");
        }
        if (!toolDescriptorSchema.validate(descriptor).isEmpty()) {
            throw new IllegalArgumentException(name + " tool descriptor is invalid");
        }
        return descriptor.deepCopy();
    }

    private Schema compileSchema(JsonNode schemaNode, String description) {
        inspectSchemaDocument(schemaNode, description, 0, new int[] {0});
        inspectSchemaKeywords(schemaNode, description);
        try {
            if (!jsonMetaSchema.validate(schemaNode).isEmpty()) {
                throw new IllegalArgumentException("invalid " + description);
            }
            Schema schema = schemas.getSchema(schemaNode);
            schema.initializeValidators();
            return schema;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("invalid " + description, error);
        }
    }

    private void inspectSchemaDocument(JsonNode node, String description, int depth, int[] count) {
        if (depth > MAX_SCHEMA_DEPTH || ++count[0] > MAX_SCHEMA_NODES) {
            throw new IllegalArgumentException(description + " exceeds schema limits");
        }
        if (node instanceof ObjectNode object) {
            for (Map.Entry<String, JsonNode> entry : object.properties()) {
                inspectSchemaDocument(entry.getValue(), description, depth + 1, count);
            }
        } else if (node instanceof ArrayNode array) {
            for (JsonNode child : array) {
                inspectSchemaDocument(child, description, depth + 1, count);
            }
        }
    }

    private void inspectSchemaKeywords(JsonNode node, String description) {
        if (!(node instanceof ObjectNode object)) {
            return;
        }
        JsonNode dialect = object.get("$schema");
        if (dialect != null && !"https://json-schema.org/draft/2020-12/schema".equals(textValue(dialect))) {
            throw new IllegalArgumentException(description + " must use JSON Schema 2020-12");
        }
        for (String keyword : REFERENCE_KEYWORDS) {
            JsonNode reference = object.get(keyword);
            if (reference != null && (textValue(reference) == null || !reference.asText().startsWith("#"))) {
                throw new IllegalArgumentException(description + " contains a remote or relative " + keyword);
            }
        }
        for (String keyword : SINGLE_SCHEMA_KEYWORDS) {
            JsonNode child = object.get(keyword);
            if (child != null) {
                inspectSchemaKeywords(child, description);
            }
        }
        for (String keyword : ARRAY_SCHEMA_KEYWORDS) {
            if (object.get(keyword) instanceof ArrayNode children) {
                children.forEach(child -> inspectSchemaKeywords(child, description));
            }
        }
        for (String keyword : MAP_SCHEMA_KEYWORDS) {
            if (object.get(keyword) instanceof ObjectNode children) {
                children.properties().forEach(entry -> inspectSchemaKeywords(entry.getValue(), description));
            }
        }
    }

    private void validateHeaderDeclarations(JsonNode inputSchema) {
        Map<String, String> headers = new HashMap<>();
        String problem = scanHeaderDeclarations(inputSchema, new ArrayList<>(), true, headers);
        if (problem != null) {
            throw new IllegalArgumentException("invalid x-mcp-header declaration: " + problem);
        }
    }

    private String scanHeaderDeclarations(
            JsonNode node,
            List<String> path,
            boolean reachable,
            Map<String, String> headers) {
        if (!(node instanceof ObjectNode object)) {
            return null;
        }
        if (object.has("x-mcp-header")) {
            String header = textValue(object.get("x-mcp-header"));
            if (!reachable || path.isEmpty()) {
                return "annotation is not on a properties-only path";
            }
            if (header == null || !HTTP_TOKEN.matcher(header).matches()) {
                return "header name must be an RFC 9110 token";
            }
            if (!PRIMITIVE_HEADER_TYPES.contains(textValue(object.get("type")))) {
                return "annotated property must have primitive type string, integer, or boolean";
            }
            String previous = headers.putIfAbsent(header.toLowerCase(Locale.ROOT), header);
            if (previous != null) {
                return "header names must be case-insensitively unique: " + previous + " and " + header;
            }
        }
        if (object.get("properties") instanceof ObjectNode properties) {
            for (Map.Entry<String, JsonNode> property : properties.properties()) {
                List<String> childPath = new ArrayList<>(path);
                childPath.add(property.getKey());
                String problem = scanHeaderDeclarations(property.getValue(), childPath, reachable, headers);
                if (problem != null) {
                    return problem;
                }
            }
        }
        for (String key : NON_REACHABLE_SCHEMA_KEYS) {
            JsonNode value = object.get(key);
            if (value == null || value.isValueNode()) {
                continue;
            }
            Iterable<JsonNode> branches = value instanceof ArrayNode array
                    ? array
                    : (key.equals("patternProperties") || key.equals("dependentSchemas") || key.equals("$defs") || key.equals("definitions"))
                            && value instanceof ObjectNode map
                    ? map.properties().stream().map(Map.Entry::getValue).toList()
                    : List.of(value);
            for (JsonNode branch : branches) {
                String problem = scanHeaderDeclarations(branch, path, false, headers);
                if (problem != null) {
                    return problem;
                }
            }
        }
        return null;
    }

    private ObjectNode response(JsonNode id, ObjectNode result) {
        return json.createObjectNode().put("jsonrpc", "2.0").set("id", id).set("result", result);
    }

    private ObjectNode completeResult() {
        ObjectNode result = json.createObjectNode().put("resultType", "complete");
        result.putObject("_meta").set("io.modelcontextprotocol/serverInfo", serverInfo.deepCopy());
        return result;
    }

    private static ObjectNode cacheable(ObjectNode result) {
        return result.put("ttlMs", 0).put("cacheScope", "private");
    }

    private ArrayNode textContent(String text) {
        return json.createArrayNode().add(json.createObjectNode().put("type", "text").put("text", text));
    }

    private void authorize(
            McpAuthorization requestAuthorization,
            McpContext context,
            String method,
            String toolName,
            JsonNode id) {
        try {
            requestAuthorization.authorize(context, method, toolName);
        } catch (McpRateLimitException error) {
            throw rateLimit(error, id);
        } catch (McpAccessException error) {
            throw access(error, id);
        } catch (RuntimeException error) {
            throw internal(id);
        }
    }

    private boolean visible(
            McpAuthorization requestAuthorization,
            McpContext context,
            String name,
            JsonNode id) {
        try {
            return requestAuthorization.visible(context, "tools/call", name);
        } catch (McpRateLimitException error) {
            throw rateLimit(error, id);
        } catch (McpAccessException error) {
            throw access(error, id);
        } catch (RuntimeException error) {
            throw internal(id);
        }
    }

    private static void checkCancelled(McpContext context, JsonNode id) {
        if (context.isCancelled()) {
            throw protocol(408, -32000, "Request cancelled or deadline exceeded", null, id);
        }
    }

    private static McpProtocolException access(McpAccessException error, JsonNode id) {
        String message = error.getMessage() == null || error.getMessage().isBlank() ? "Forbidden" : error.getMessage();
        return protocol(403, -32001, message, null, id);
    }

    private McpProtocolException rateLimit(McpRateLimitException error, JsonNode id) {
        ObjectNode data = json.createObjectNode().put("retryAfterSeconds", error.retryAfterSeconds());
        return protocol(429, -32000, "Rate limit exceeded", data, id);
    }

    private ObjectNode validateServerInfo(JsonNode value) {
        if (!(value instanceof ObjectNode object) || !implementationSchema.validate(object).isEmpty()) {
            throw new IllegalArgumentException("serverInfo is not a valid MCP Implementation");
        }
        return object.deepCopy();
    }

    private static boolean validId(JsonNode id) {
        if (id == null) {
            return false;
        }
        if (id.isTextual() || id.isIntegralNumber()) {
            return true;
        }
        if (!id.isNumber() || id.isFloatingPointNumber() && !Double.isFinite(id.asDouble())) {
            return false;
        }
        try {
            return id.decimalValue().stripTrailingZeros().scale() <= 0;
        } catch (ArithmeticException error) {
            return false;
        }
    }

    private static String textValue(JsonNode value) {
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private McpProtocolException internal(JsonNode id) {
        ObjectNode data = json.createObjectNode().put("correlationId", UUID.randomUUID().toString());
        return protocol(200, -32603, "Internal error", data, id);
    }

    private static McpProtocolException protocol(
            int httpStatus,
            int code,
            String message,
            JsonNode data,
            JsonNode id) {
        return new McpProtocolException(httpStatus, code, message, data, id);
    }


    private ObjectNode readProtocolSchema() throws Exception {
        try (InputStream input = McpEngine.class.getResourceAsStream("schema-2026-07-28.json")) {
            if (input == null) {
                throw new IllegalStateException("missing bundled protocol schema");
            }
            return (ObjectNode) JsonMapper.shared().readTree(input);
        }
    }

    private Schema protocolDefinition(ObjectNode protocolSchema, String definition) {
        if (!protocolSchema.path("$defs").has(definition)) throw new IllegalArgumentException("unknown MCP schema: " + definition);
        ObjectNode wrapper = protocolSchema.deepCopy().put("$ref", "#/$defs/" + definition);
        Schema schema = schemas.getSchema(wrapper);
        schema.initializeValidators();
        return schema;
    }

    private void requireClientCapabilities(RegisteredTool tool, McpRequest request) {
        ObjectNode required = tool.requiredClientCapabilities();
        if (required == null) {
            return;
        }
        JsonNode actual = request.metadata().path("io.modelcontextprotocol/clientCapabilities");
        if (!containsCapabilities(actual, required)) {
            ObjectNode data = json.createObjectNode().set("requiredCapabilities", required.deepCopy());
            throw protocol(400, -32021, "Missing required client capability", data, request.id());
        }
    }

    private static boolean containsCapabilities(JsonNode actual, JsonNode required) {
        if (required instanceof ObjectNode requiredObject) {
            if (!(actual instanceof ObjectNode actualObject)) {
                return false;
            }
            for (Map.Entry<String, JsonNode> entry : requiredObject.properties()) {
                if (!actualObject.has(entry.getKey())
                        || !containsCapabilities(actualObject.get(entry.getKey()), entry.getValue())) {
                    return false;
                }
            }
            return true;
        }
        return required.equals(actual);
    }

    private record RegisteredTool(
            McpTool tool,
            ObjectNode descriptor,
            Schema inputSchema,
            Schema outputSchema,
            ObjectNode requiredClientCapabilities) {}

    private record RegisteredMethod(McpMethod method, Schema requestSchema, Schema resultSchema) {}
}
