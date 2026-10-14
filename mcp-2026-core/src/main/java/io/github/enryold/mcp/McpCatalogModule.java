package io.github.enryold.mcp;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Resources, resource templates, prompts, and completion backed by explicit registrations. */
public final class McpCatalogModule implements McpModule {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final CatalogSchemas SCHEMAS = new CatalogSchemas();

    private final List<McpResource> resources;
    private final Map<String, McpResource> resourcesByUri;
    private final List<McpResourceTemplate> templates;
    private final List<McpPrompt> prompts;
    private final Map<String, McpPrompt> promptsByName;
    private final Map<String, McpCompletion> completions;
    private final List<McpMethod> methods;

    public McpCatalogModule(
            Collection<McpResource> resources,
            Collection<McpResourceTemplate> templates,
            Collection<McpPrompt> prompts,
            Collection<McpCompletion> completions) {
        this.resources = sorted(resources, McpResource::uri);
        this.templates = sorted(templates, McpResourceTemplate::uriTemplate);
        this.prompts = sorted(prompts, McpPrompt::name);
        this.resourcesByUri = unique(this.resources, McpResource::uri, "resource URI");
        this.promptsByName = unique(this.prompts, McpPrompt::name, "prompt name");

        for (McpResource resource : this.resources) {
            SCHEMAS.registration("Resource", resource.descriptor());
        }
        Set<String> templateUris = this.templates.stream().map(McpResourceTemplate::uriTemplate).collect(Collectors.toSet());
        if (templateUris.size() != this.templates.size()) {
            throw new IllegalArgumentException("duplicate resource template URI");
        }
        for (McpResourceTemplate template : this.templates) {
            SCHEMAS.registration("ResourceTemplate", template.descriptor());
        }
        for (McpPrompt prompt : this.prompts) {
            SCHEMAS.registration("Prompt", prompt.descriptor());
            requireUniquePromptArguments(prompt);
        }

        Map<String, McpCompletion> indexedCompletions = new LinkedHashMap<>();
        for (McpCompletion completion : Objects.requireNonNull(completions, "completions")) {
            ObjectNode reference = completion.reference();
            String identity = referenceIdentity(reference);
            String definition = "ref/prompt".equals(reference.path("type").asText())
                    ? "PromptReference"
                    : "ResourceTemplateReference";
            SCHEMAS.registration(definition, reference);
            requireCompletionTarget(reference, completion.argumentName(), templateUris);
            String key = completionKey(reference, completion.argumentName());
            if (indexedCompletions.putIfAbsent(key, completion) != null) {
                throw new IllegalArgumentException("duplicate completion for " + identity + " argument " + completion.argumentName());
            }
        }
        this.completions = Map.copyOf(indexedCompletions);
        this.methods = createMethods();
    }

    @Override
    public ObjectNode capabilities() {
        ObjectNode capabilities = JSON.createObjectNode();
        if (!resources.isEmpty() || !templates.isEmpty()) capabilities.putObject("resources");
        if (!prompts.isEmpty()) capabilities.putObject("prompts");
        if (!completions.isEmpty()) capabilities.putObject("completions");
        return capabilities;
    }

    @Override
    public Collection<McpMethod> methods() {
        return methods;
    }

    private List<McpMethod> createMethods() {
        List<McpMethod> registrations = new ArrayList<>();
        if (!resources.isEmpty() || !templates.isEmpty()) {
            registrations.add(new McpMethod("resources/list", "ListResourcesRequest", "ListResourcesResult", this::listResources));
            registrations.add(new McpMethod("resources/templates/list", "ListResourceTemplatesRequest",
                    "ListResourceTemplatesResult", this::listTemplates));
            registrations.add(new McpMethod("resources/read", "ReadResourceRequest", "ReadResourceResult", this::readResource));
        }
        if (!prompts.isEmpty()) {
            registrations.add(new McpMethod("prompts/list", "ListPromptsRequest", "ListPromptsResult", this::listPrompts));
            registrations.add(new McpMethod("prompts/get", "GetPromptRequest", "GetPromptResult", this::getPrompt));
        }
        if (!completions.isEmpty()) {
            registrations.add(new McpMethod("completion/complete", "CompleteRequest", "CompleteResult", this::complete));
        }
        return List.copyOf(registrations);
    }

    private ObjectNode listResources(ObjectNode params, McpContext context) {
        rejectCursor(params);
        ObjectNode result = cacheable();
        ArrayNode listed = result.putArray("resources");
        resources.stream().filter(resource -> context.visible("resources/read", resource.uri()))
                .forEach(resource -> listed.add(resource.descriptor()));
        return SCHEMAS.result("ListResourcesResult", result);
    }

    private ObjectNode listTemplates(ObjectNode params, McpContext context) {
        rejectCursor(params);
        ObjectNode result = cacheable();
        ArrayNode listed = result.putArray("resourceTemplates");
        templates.stream().filter(template -> context.visible("resources/read", template.uriTemplate()))
                .forEach(template -> listed.add(template.descriptor()));
        return SCHEMAS.result("ListResourceTemplatesResult", result);
    }

    private ObjectNode listPrompts(ObjectNode params, McpContext context) {
        rejectCursor(params);
        ObjectNode result = cacheable();
        ArrayNode listed = result.putArray("prompts");
        prompts.stream().filter(prompt -> context.visible("prompts/get", prompt.name()))
                .forEach(prompt -> listed.add(prompt.descriptor()));
        return SCHEMAS.result("ListPromptsResult", result);
    }

    private ObjectNode readResource(ObjectNode params, McpContext context) throws Exception {
        String uri = params.path("uri").asText();
        McpResource direct = resourcesByUri.get(uri);
        McpResource.Reader reader = direct == null ? null : direct.reader();
        if (reader == null) {
            reader = templates.stream().filter(template -> template.matches(uri))
                    .map(McpResourceTemplate::reader).findFirst().orElse(null);
        }
        if (reader == null) {
            ObjectNode data = JSON.createObjectNode().put("uri", uri);
            throw invalidParams("Resource not found: " + uri, data);
        }
        context.authorize("resources/read", uri);
        return completeResult("ReadResourceResult", reader.read(uri, context), true);
    }

    private ObjectNode getPrompt(ObjectNode params, McpContext context) throws Exception {
        String name = params.path("name").asText();
        McpPrompt prompt = promptsByName.get(name);
        if (prompt == null) {
            throw invalidParams("Prompt not found: " + name, JSON.createObjectNode().put("name", name));
        }
        context.authorize("prompts/get", name);
        ObjectNode arguments = params.get("arguments") instanceof ObjectNode object
                ? object.deepCopy()
                : JSON.createObjectNode();
        validatePromptArguments(prompt, arguments);
        return completeResult("GetPromptResult", prompt.handler().get(arguments, context), false);
    }

    private ObjectNode complete(ObjectNode params, McpContext context) throws Exception {
        ObjectNode reference = (ObjectNode) params.path("ref");
        ObjectNode argument = (ObjectNode) params.path("argument");
        String argumentName = argument.path("name").asText();
        McpCompletion completion = completions.get(completionKey(reference, argumentName));
        String identity = referenceIdentity(reference);
        if (completion == null) {
            throw invalidParams("Completion not found for " + identity + " argument " + argumentName, null);
        }
        context.authorize("completion/complete", identity);
        if (!context.visible(referenceMethod(reference), identity)) throw new McpAccessException("Forbidden");
        ObjectNode previous = params.at("/context/arguments") instanceof ObjectNode object
                ? object.deepCopy()
                : JSON.createObjectNode();
        ObjectNode result = JSON.createObjectNode();
        result.set("completion", Objects.requireNonNull(
                completion.handler().complete(argument.path("value").asText(), previous, context),
                "completion handler result"));
        result.put("resultType", "complete");
        return SCHEMAS.result("CompleteResult", result);
    }

    private ObjectNode completeResult(String definition, ObjectNode supplied, boolean cacheable) {
        ObjectNode result = Objects.requireNonNull(supplied, "handler result").deepCopy();
        if ("input_required".equals(result.path("resultType").asText())) return result;
        result.put("resultType", "complete");
        if (cacheable) {
            result.put("ttlMs", 0);
            result.put("cacheScope", "private");
        }
        return SCHEMAS.result(definition, result);
    }

    private void validatePromptArguments(McpPrompt prompt, ObjectNode arguments) {
        Map<String, Boolean> declared = new LinkedHashMap<>();
        if (prompt.descriptor().path("arguments") instanceof ArrayNode descriptors) {
            for (JsonNode descriptor : descriptors) {
                declared.put(descriptor.path("name").asText(), descriptor.path("required").asBoolean(false));
            }
        }
        for (Map.Entry<String, JsonNode> supplied : arguments.properties()) {
            if (!declared.containsKey(supplied.getKey()) || !supplied.getValue().isTextual()) {
                throw invalidParams("Invalid arguments for prompt " + prompt.name(), null);
            }
        }
        for (Map.Entry<String, Boolean> argument : declared.entrySet()) {
            if (argument.getValue() && !arguments.has(argument.getKey())) {
                throw invalidParams("Missing required argument " + argument.getKey() + " for prompt " + prompt.name(), null);
            }
        }
    }

    private void requireCompletionTarget(ObjectNode reference, String argumentName, Set<String> templateUris) {
        if ("ref/prompt".equals(reference.path("type").asText())) {
            McpPrompt prompt = promptsByName.get(reference.path("name").asText());
            if (prompt == null || !hasPromptArgument(prompt, argumentName)) {
                throw new IllegalArgumentException("completion must reference a registered prompt argument");
            }
        } else if (!templateUris.contains(reference.path("uri").asText())) {
            throw new IllegalArgumentException("completion must reference a registered resource template");
        }
    }

    private static boolean hasPromptArgument(McpPrompt prompt, String argumentName) {
        if (!(prompt.descriptor().path("arguments") instanceof ArrayNode arguments)) return false;
        for (JsonNode argument : arguments) {
            if (argumentName.equals(argument.path("name").asText())) return true;
        }
        return false;
    }

    private static void requireUniquePromptArguments(McpPrompt prompt) {
        if (!(prompt.descriptor().path("arguments") instanceof ArrayNode arguments)) return;
        Set<String> names = arguments.valueStream().map(argument -> argument.path("name").asText()).collect(Collectors.toSet());
        if (names.size() != arguments.size()) {
            throw new IllegalArgumentException("duplicate argument in prompt " + prompt.name());
        }
    }

    private static void rejectCursor(ObjectNode params) {
        if (params.has("cursor")) throw invalidParams("Invalid cursor", null);
    }

    private static ObjectNode cacheable() {
        return JSON.createObjectNode().put("resultType", "complete").put("ttlMs", 0).put("cacheScope", "private");
    }

    private static String completionKey(ObjectNode reference, String argumentName) {
        return reference.path("type").asText() + '\n' + referenceIdentity(reference) + '\n' + argumentName;
    }

    private static String referenceIdentity(ObjectNode reference) {
        return "ref/prompt".equals(reference.path("type").asText())
                ? reference.path("name").asText()
                : reference.path("uri").asText();
    }

    private static String referenceMethod(ObjectNode reference) {
        return "ref/prompt".equals(reference.path("type").asText()) ? "prompts/get" : "resources/read";
    }

    private static McpProtocolException invalidParams(String message, JsonNode data) {
        return new McpProtocolException(200, -32602, message, data, null);
    }

    private static <T> List<T> sorted(Collection<T> registrations, java.util.function.Function<T, String> key) {
        ArrayList<T> sorted = new ArrayList<>(Objects.requireNonNull(registrations, "registrations"));
        if (sorted.stream().anyMatch(Objects::isNull)) throw new NullPointerException("registration");
        sorted.sort(Comparator.comparing(key));
        return List.copyOf(sorted);
    }

    private static <T> Map<String, T> unique(List<T> registrations, java.util.function.Function<T, String> key,
            String label) {
        Map<String, T> indexed = new LinkedHashMap<>();
        for (T registration : registrations) {
            if (indexed.putIfAbsent(key.apply(registration), registration) != null) {
                throw new IllegalArgumentException("duplicate " + label + ": " + key.apply(registration));
            }
        }
        return Map.copyOf(indexed);
    }

    private static final class CatalogSchemas {
        private final Map<String, Schema> definitions = new LinkedHashMap<>();

        private CatalogSchemas() {
            try (InputStream input = McpCatalogModule.class.getResourceAsStream("schema-2026-07-28.json")) {
                if (input == null) throw new IllegalStateException("missing bundled protocol schema");
                ObjectNode protocol = (ObjectNode) JsonMapper.shared().readTree(input);
                SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
                        SpecificationVersion.DRAFT_2020_12,
                        builder -> builder.schemaLoader(loader -> loader.fetchRemoteResources(false))
                                .schemaRegistryConfig(SchemaRegistryConfig.builder().failFast(true)
                                        .preloadSchema(true).typeLoose(false).build()));
                for (String definition : List.of(
                        "Resource", "ResourceTemplate", "Prompt", "PromptReference", "ResourceTemplateReference",
                        "ListResourcesResult", "ListResourceTemplatesResult", "ReadResourceResult",
                        "ListPromptsResult", "GetPromptResult", "CompleteResult")) {
                    ObjectNode wrapper = protocol.deepCopy().put("$ref", "#/$defs/" + definition);
                    Schema schema = registry.getSchema(wrapper);
                    schema.initializeValidators();
                    definitions.put(definition, schema);
                }
            } catch (Exception error) {
                throw new IllegalStateException("invalid bundled protocol schema", error);
            }
        }

        private void registration(String definition, ObjectNode value) {
            var errors = definitions.get(definition).validate(value);
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException(definition + " registration is invalid: " + errors.getFirst().getMessage());
            }
        }

        private ObjectNode result(String definition, ObjectNode value) {
            var errors = definitions.get(definition).validate(value);
            if (!errors.isEmpty()) {
                throw new IllegalStateException(definition + " handler result is invalid: " + errors.getFirst().getMessage());
            }
            return value;
        }
    }
}
