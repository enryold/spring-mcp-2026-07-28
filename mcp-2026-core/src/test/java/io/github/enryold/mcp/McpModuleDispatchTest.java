package io.github.enryold.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class McpModuleDispatchTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void loggingIsDeclaredByTheEngineAndDoesNotRequireSubscriptions() {
        var emitted = new java.util.ArrayList<ObjectNode>();
        var tool = new McpTool(JSON.createObjectNode().put("name", "log")
                .set("inputSchema", JSON.createObjectNode().put("type", "object")), (arguments, context) -> {
            McpNotifications.forRequest(context).log("info", "test", JSON.stringNode("message"));
            return ToolResult.success(JSON.createArrayNode());
        });
        var engine = new McpEngine(identity(), List.of(tool), McpAuthorization.permitAll());
        assertThat(engine.dispatch(request(engine, "server/discover", "{}"), context())
                .at("/result/capabilities/logging").isObject()).isTrue();
        var base = request(engine, "tools/call", "{\"name\":\"log\",\"arguments\":{}}");
        var metadata = base.metadata().put("io.modelcontextprotocol/logLevel", "info");
        var params = base.params().set("_meta", metadata);
        var request = new McpRequest(base.id(), base.method(), params, metadata);
        engine.dispatch(request, context().withRequest(request, emitted::add));
        assertThat(emitted).hasSize(1);
        assertThat(emitted.getFirst().path("method").asString()).isEqualTo("notifications/message");
    }

    @Test
    void declaredCapabilitiesRequireTheirHandlersAndHandlersRequireDeclarations() {
        try (var events = new McpEvents(Set.of(McpEvents.Kind.PROMPTS_LIST_CHANGED), 1)) {
            assertThatThrownBy(() -> new McpEngine(identity(), List.of(), McpAuthorization.permitAll(), List.of(events)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("prompts");
        }
        McpModule missingDeclaration = new McpModule() {
            public ObjectNode capabilities() { return JSON.createObjectNode(); }
            public Collection<McpMethod> methods() { return List.of(new McpMethod("completion/complete", "CompleteRequest", "CompleteResult", (params, context) -> JSON.createObjectNode())); }
        };
        assertThatThrownBy(() -> new McpEngine(identity(), List.of(), McpAuthorization.permitAll(), List.of(missingDeclaration)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("completions");
    }

    @Test
    void taskPromotionDiscardsInitialProgressAndLogsWhileSynchronousCallsKeepThem() {
        var emitted = new java.util.ArrayList<ObjectNode>();
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var interactions = new McpInteractions(new McpInteractionStore(4, 4), executor);
            var descriptor = (ObjectNode) JSON.readTree("""
                    {"name":"optional","inputSchema":{"type":"object"},"execution":{"taskSupport":"optional"}}
                    """);
            var tool = new McpTool(descriptor, (arguments, context) -> {
                var notifications = McpNotifications.forRequest(context);
                notifications.progress(1, null, null);
                notifications.progress(2, null, null);
                notifications.log("info", "test", JSON.stringNode("safe"));
                return arguments.path("background").asBoolean(false)
                        ? ToolResult.deferred(execution -> ToolResult.success(JSON.createArrayNode()))
                        : ToolResult.success(JSON.createArrayNode());
            });
            var engine = new McpEngine(identity(), List.of(tool), McpAuthorization.permitAll(), List.of(interactions));
            var base = request(engine, "tools/call", "{\"name\":\"optional\",\"arguments\":{\"background\":true}}");
            var metadata = base.metadata().put("progressToken", "task-progress").put("io.modelcontextprotocol/logLevel", "info");
            metadata.withObject("io.modelcontextprotocol/clientCapabilities").putObject("extensions").putObject(McpInteractions.TASKS_EXTENSION);
            var request = new McpRequest(base.id(), base.method(), base.params().set("_meta", metadata), metadata);
            assertThat(engine.dispatch(request, context().withRequest(request, emitted::add)).at("/result/resultType").asString()).isEqualTo("task");
            assertThat(emitted).isEmpty();
            var params = request.params();
            params.withObject("arguments").put("background", false);
            var synchronous = new McpRequest(JSON.stringNode("sync"), request.method(), params, metadata);
            assertThat(engine.dispatch(synchronous, context().withRequest(synchronous, emitted::add)).at("/result/resultType").asString()).isEqualTo("complete");
            assertThat(emitted).extracting(value -> value.path("method").asString())
                    .containsExactly("notifications/progress", "notifications/progress", "notifications/message");
        }
    }

    @Test
    void moduleCatalogUsesInvocationPolicyEvenWhenEnginePolicyAllowsEverything() {
        McpModule module = module(new McpMethod("resources/list", "ListResourcesRequest", "ListResourcesResult",
                (params, context) -> {
                    var result = JSON.createObjectNode().put("resultType", "complete").put("ttlMs", 0).put("cacheScope", "private");
                    var list = result.putArray("resources");
                    if (context.visible("test://private")) list.add(JSON.createObjectNode().put("uri", "test://private").put("name", "private"));
                    return result;
                }));
        var engine = new McpEngine(identity(), List.of(), McpAuthorization.permitAll(), List.of(module));
        var policy = new McpAuthorization() {
            public void authorize(McpContext context, String method, String name) {}
            public boolean visible(McpContext context, String name) { return false; }
        };
        var result = engine.dispatch(request(engine, "resources/list", "{}"), context(), policy);
        assertThat(result.path("result").path("resources").size()).isZero();
        assertThat(result.path("result").path("_meta").path("io.modelcontextprotocol/serverInfo").path("name").asString()).isEqualTo("test");
        assertThat(engine.dispatch(request(engine, "server/discover", "{}"), context()).path("result").path("capabilities").has("resources")).isTrue();
    }

    @Test
    void moduleInputsAndOutputsUseIndependentPinnedDefinitions() {
        var calls = new AtomicInteger();
        var engine = new McpEngine(identity(), List.of(), McpAuthorization.permitAll(), List.of(module(
                new McpMethod("resources/read", "ReadResourceRequest", "ReadResourceResult", (params, context) -> {
                    calls.incrementAndGet();
                    return JSON.createObjectNode().put("resultType", "complete").put("private-output", "must-not-escape");
                }))));
        assertThatThrownBy(() -> engine.dispatch(request(engine, "resources/read", "{\"uri\":false}"), context()))
                .isInstanceOfSatisfying(McpProtocolException.class, error -> assertThat(error.code()).isEqualTo(-32602));
        assertThat(calls).hasValue(0);
        assertThatThrownBy(() -> engine.dispatch(request(engine, "resources/read", "{\"uri\":\"test://private\"}"), context()))
                .isInstanceOfSatisfying(McpProtocolException.class, error -> {
                    assertThat(error.code()).isEqualTo(-32603);
                    assertThat(error.toResponse().toString()).doesNotContain("must-not-escape");
                });
        assertThat(calls).hasValue(1);
    }

    @Test
    void moduleProtocolErrorsKeepTheCurrentReadableRequestId() {
        var engine = new McpEngine(identity(), List.of(), McpAuthorization.permitAll(), List.of(module(
                new McpMethod("resources/read", "ReadResourceRequest", "ReadResourceResult", (params, context) -> {
                    throw new McpProtocolException(200, -32602, "Resource not found",
                            JSON.createObjectNode().put("uri", params.path("uri").asString()), null);
                }))));
        assertThatThrownBy(() -> engine.dispatch(request(engine, "resources/read", "{\"uri\":\"test://missing\"}"), context()))
                .isInstanceOfSatisfying(McpProtocolException.class, error -> assertThat(error.id().asString()).isEqualTo("module-id"));
    }

    @Test
    void modulesCannotReplaceCoreMethodsOrRegisterUnknownSchemaNames() {
        assertThatThrownBy(() -> new McpEngine(identity(), List.of(), McpAuthorization.permitAll(), List.of(module(
                new McpMethod("tools/list", "ListToolsRequest", "ListToolsResult", (params, context) -> JSON.createObjectNode())))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpEngine(identity(), List.of(), McpAuthorization.permitAll(), List.of(module(
                new McpMethod("resources/read", "ImaginaryRequest", "ReadResourceResult", (params, context) -> JSON.createObjectNode())))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpEngine(identity(), List.of(), McpAuthorization.permitAll(), List.of(module(
                new McpMethod("tasks/evil", null, null, (params, context) -> JSON.createObjectNode())))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unregisteredInteractionsNeverExposeRawToolOrResourceContinuationState() {
        var descriptor = JSON.createObjectNode().put("name", "state")
                .set("inputSchema", JSON.createObjectNode().put("type", "object"));
        var tool = new McpTool(descriptor, (arguments, context) -> ToolResult.inputRequired("private-handler-state"));
        var engine = new McpEngine(identity(), List.of(tool), McpAuthorization.permitAll());
        assertThatThrownBy(() -> engine.dispatch(request(engine, "tools/call", "{\"name\":\"state\"}"), context()))
                .isInstanceOfSatisfying(McpProtocolException.class, error -> {
                    assertThat(error.code()).isEqualTo(-32603);
                    assertThat(error.toResponse().toString()).doesNotContain("private-handler-state");
                });
        var resourceEngine = new McpEngine(identity(), List.of(), McpAuthorization.permitAll(), List.of(module(
                new McpMethod("resources/read", "ReadResourceRequest", "ReadResourceResult", (params, context) ->
                        JSON.createObjectNode().put("resultType", "input_required").put("requestState", "private-handler-state")))));
        assertThatThrownBy(() -> resourceEngine.dispatch(request(resourceEngine, "resources/read", "{\"uri\":\"test://state\"}"), context()))
                .isInstanceOfSatisfying(McpProtocolException.class, error -> {
                    assertThat(error.code()).isEqualTo(-32603);
                    assertThat(error.toResponse().toString()).doesNotContain("private-handler-state");
                });
    }

    private static McpModule module(McpMethod method) {
        return new McpModule() {
            public ObjectNode capabilities() { return JSON.createObjectNode().set("resources", JSON.createObjectNode()); }
            public Collection<McpMethod> methods() {
                var methods = new java.util.ArrayList<McpMethod>();
                methods.add(method);
                if (!method.name().equals("resources/list")) methods.add(new McpMethod("resources/list", "ListResourcesRequest", "ListResourcesResult", (params, context) -> JSON.createObjectNode()));
                if (!method.name().equals("resources/read")) methods.add(new McpMethod("resources/read", "ReadResourceRequest", "ReadResourceResult", (params, context) -> JSON.createObjectNode()));
                if (!method.name().equals("resources/templates/list")) methods.add(new McpMethod("resources/templates/list", "ListResourceTemplatesRequest", "ListResourceTemplatesResult", (params, context) -> JSON.createObjectNode()));
                return methods;
            }
        };
    }
    private static ObjectNode identity() { return JSON.createObjectNode().put("name", "test").put("version", "1"); }
    private static McpContext context() { return new McpContext("alice", "red", Set.of("read"), Map.of(), Instant.now().plusSeconds(30), () -> false); }
    private static McpRequest request(McpEngine engine, String method, String params) {
        var arguments = (ObjectNode) JSON.readTree(params);
        arguments.set("_meta", JSON.createObjectNode().put("io.modelcontextprotocol/protocolVersion", "2026-07-28")
                .set("io.modelcontextprotocol/clientCapabilities", JSON.createObjectNode()));
        return engine.readRequest(JSON.writeValueAsBytes(JSON.createObjectNode().put("jsonrpc", "2.0").put("id", "module-id")
                .put("method", method).set("params", arguments)));
    }
}
