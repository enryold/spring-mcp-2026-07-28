package io.github.enryold.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class McpCatalogModuleTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void listsOnlyVisibleRegistrationsInDeterministicPrivateCatalogs() throws Exception {
        var module = new McpCatalogModule(
                List.of(resource("z-resource", "test://z"), resource("a-resource", "test://a")),
                List.of(template("test://tenant/{id}")),
                List.of(prompt("z-prompt", false), prompt("a-prompt", false)),
                List.of());
        McpAuthorization hideZ = new McpAuthorization() {
            @Override public void authorize(McpContext context, String method, String name) {}
            @Override public boolean visible(McpContext context, String name) { return !name.contains("z"); }
        };
        McpContext context = context("red").withAuthorization(hideZ);

        ObjectNode resources = execute(module, "resources/list", object("{}"), context);
        ObjectNode templates = execute(module, "resources/templates/list", object("{}"), context);
        ObjectNode prompts = execute(module, "prompts/list", object("{}"), context);

        assertThat(resources.at("/resources/0/name").asText()).isEqualTo("a-resource");
        assertThat(resources.path("resources")).hasSize(1);
        assertThat(templates.at("/resourceTemplates/0/uriTemplate").asText()).isEqualTo("test://tenant/{id}");
        assertThat(prompts.at("/prompts/0/name").asText()).isEqualTo("a-prompt");
        for (ObjectNode result : List.of(resources, templates, prompts)) {
            assertThat(result.path("resultType").asText()).isEqualTo("complete");
            assertThat(result.path("cacheScope").asText()).isEqualTo("private");
            assertThat(result.path("ttlMs").intValue()).isZero();
        }
        assertThat(module.capabilities().has("resources")).isTrue();
        assertThat(module.capabilities().has("prompts")).isTrue();
        assertThat(module.capabilities().has("completions")).isFalse();
    }

    @Test
    void resolvesResourcesPerTenantAndReturnsTheNormativeMissingResourceError() throws Exception {
        var module = new McpCatalogModule(List.of(new McpResource(object("""
                {"uri":"test://tenant/current","name":"tenant"}
                """), (uri, context) -> {
                    ObjectNode result = object("""
                            {"contents":[{"uri":"test://tenant/current","text":"placeholder"}]}
                            """);
                    result.withArray("contents").get(0).asObject().put("text", context.tenant());
                    return result;
                })),
                List.of(template("test://tenant/{id}")), List.of(), List.of());
        AtomicInteger authorizations = new AtomicInteger();
        McpAuthorization policy = new McpAuthorization() {
            @Override public void authorize(McpContext context, String method, String name) {
                assertThat(method).isEqualTo("resources/read");
                assertThat(name).isEqualTo("test://tenant/current");
                authorizations.incrementAndGet();
            }
            @Override public boolean visible(McpContext context, String name) { return true; }
        };

        ObjectNode red = execute(module, "resources/read", object("{\"uri\":\"test://tenant/current\"}"),
                context("red").withAuthorization(policy));
        ObjectNode blue = execute(module, "resources/read", object("{\"uri\":\"test://tenant/current\"}"),
                context("blue").withAuthorization(policy));

        assertThat(red.at("/contents/0/text").asText()).isEqualTo("red");
        assertThat(blue.at("/contents/0/text").asText()).isEqualTo("blue");
        assertThat(authorizations).hasValue(2);
        assertThatThrownBy(() -> execute(module, "resources/read", object("{\"uri\":\"test://missing\"}"), context("red")))
                .isInstanceOfSatisfying(McpProtocolException.class, error -> {
                    assertThat(error.code()).isEqualTo(-32602);
                    assertThat(error.data().path("uri").asText()).isEqualTo("test://missing");
                });
    }

    @Test
    void validatesRequiredPromptStringsAndCompletionResults() throws Exception {
        McpPrompt required = prompt("required", true);
        McpCompletion completion = new McpCompletion(
                object("{\"type\":\"ref/prompt\",\"name\":\"required\"}"), "value",
                (value, previous, context) -> object("""
                        {"values":["value-a","value-b"],"total":2,"hasMore":false}
                        """));
        var module = new McpCatalogModule(List.of(), List.of(), List.of(required), List.of(completion));

        assertThatThrownBy(() -> execute(module, "prompts/get",
                object("{\"name\":\"required\",\"arguments\":{}}"), context("red")))
                .isInstanceOfSatisfying(McpProtocolException.class,
                        error -> assertThat(error.code()).isEqualTo(-32602));

        ObjectNode prompt = execute(module, "prompts/get",
                object("{\"name\":\"required\",\"arguments\":{\"value\":\"ok\"}}"), context("red"));
        ObjectNode completed = execute(module, "completion/complete", object("""
                {"ref":{"type":"ref/prompt","name":"required"},"argument":{"name":"value","value":"v"}}
                """), context("red"));

        assertThat(prompt.at("/messages/0/content/text").asText()).isEqualTo("ok");
        assertThat(completed.at("/completion/values/0").asText()).isEqualTo("value-a");
        assertThat(completed.path("resultType").asText()).isEqualTo("complete");
    }

    @Test
    void distinguishesCollidingNamesByMethodForCatalogsAndCompletionTargets() throws Exception {
        McpPrompt prompt = prompt("collision", true);
        McpCompletion completion = new McpCompletion(
                object("{\"type\":\"ref/prompt\",\"name\":\"collision\"}"), "value",
                (value, previous, context) -> object("{\"values\":[\"value\"],\"total\":1,\"hasMore\":false}"));
        var module = new McpCatalogModule(List.of(), List.of(), List.of(prompt), List.of(completion));
        McpContext promptVisible = context("red").withAuthorization(visibleOnly("prompts/get"));
        McpContext toolVisible = context("red").withAuthorization(visibleOnly("tools/call"));
        ObjectNode request = object("""
                {"ref":{"type":"ref/prompt","name":"collision"},
                 "argument":{"name":"value","value":"v"}}
                """);

        assertThat(execute(module, "prompts/list", object("{}"), promptVisible).path("prompts")).hasSize(1);
        assertThat(promptVisible.visible("tools/call", "collision")).isFalse();
        assertThat(execute(module, "completion/complete", request, promptVisible)
                .at("/completion/values/0").asText()).isEqualTo("value");

        assertThat(execute(module, "prompts/list", object("{}"), toolVisible).path("prompts")).isEmpty();
        assertThat(toolVisible.visible("tools/call", "collision")).isTrue();
        assertThatThrownBy(() -> execute(module, "completion/complete", request, toolVisible))
                .isInstanceOf(McpAccessException.class);
    }

    @Test
    void engineSeparatesToolAndPromptVisibilityForTheSameName() {
        McpTool tool = new McpTool(object("""
                {"name":"collision","inputSchema":{"type":"object"}}
                """), (arguments, context) -> ToolResult.success(JSON.createArrayNode()));
        var catalog = new McpCatalogModule(List.of(), List.of(), List.of(prompt("collision", false)), List.of());
        var engine = new McpEngine(object("{\"name\":\"test\",\"version\":\"1\"}"),
                List.of(tool), McpAuthorization.permitAll(), List.of(catalog));

        ObjectNode promptPolicyPrompts = dispatch(engine, "prompts/list", visibleOnly("prompts/get"));
        ObjectNode promptPolicyTools = dispatch(engine, "tools/list", visibleOnly("prompts/get"));
        ObjectNode toolPolicyPrompts = dispatch(engine, "prompts/list", visibleOnly("tools/call"));
        ObjectNode toolPolicyTools = dispatch(engine, "tools/list", visibleOnly("tools/call"));

        assertThat(promptPolicyPrompts.at("/result/prompts")).hasSize(1);
        assertThat(promptPolicyTools.at("/result/tools")).isEmpty();
        assertThat(toolPolicyPrompts.at("/result/prompts")).isEmpty();
        assertThat(toolPolicyTools.at("/result/tools")).hasSize(1);
    }

    @Test
    void rejectsInvalidDescriptorsDuplicatesAndHandlerOutputs() {
        assertThatThrownBy(() -> new McpCatalogModule(
                List.of(new McpResource(object("{\"name\":\"missing-uri\"}"), (uri, context) -> object("{}"))),
                List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Resource");
        assertThatThrownBy(() -> new McpCatalogModule(
                List.of(resource("one", "test://same"), resource("two", "test://same")),
                List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate");

        var invalidOutput = new McpCatalogModule(List.of(new McpResource(
                object("{\"name\":\"bad\",\"uri\":\"test://bad\"}"),
                (uri, context) -> object("{\"contents\":[{\"uri\":\"test://bad\"}]}"))),
                List.of(), List.of(), List.of());
        assertThatThrownBy(() -> execute(invalidOutput, "resources/read",
                object("{\"uri\":\"test://bad\"}"), context("red")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ReadResourceResult");
    }

    @Test
    void engineValidatesCatalogWireResultsAndPreservesErrorIds() {
        var module = new McpCatalogModule(List.of(resource("text", "test://text")), List.of(), List.of(), List.of());
        var engine = new McpEngine(object("{\"name\":\"test\",\"version\":\"1\"}"),
                List.of(), McpAuthorization.permitAll(), List.of(module));

        ObjectNode discover = engine.dispatch(engine.readRequest(wire("discover", "server/discover", "")), context("red"));
        ObjectNode read = engine.dispatch(engine.readRequest(wire("read", "resources/read", ",\"uri\":\"test://text\"")),
                context("red"));
        McpProtocolException missing = null;
        try {
            engine.dispatch(engine.readRequest(wire("missing", "resources/read", ",\"uri\":\"test://missing\"")),
                    context("red"));
        } catch (McpProtocolException error) {
            missing = error;
        }

        assertThat(discover.at("/result/capabilities/resources").isObject()).isTrue();
        assertThat(read.at("/result/_meta/io.modelcontextprotocol~1serverInfo/name").asText()).isEqualTo("test");
        assertThat(missing).isNotNull();
        assertThat(missing.id().asText()).isEqualTo("missing");
        assertThat(missing.data().path("uri").asText()).isEqualTo("test://missing");
    }

    @Test
    void promptCanCompleteAnEngineManagedInputRequiredRoundTrip() {
        var executor = Executors.newSingleThreadExecutor();
        try {
            McpPrompt prompt = new McpPrompt(object("""
                    {"name":"test_input_required_result_prompt"}
                    """), (arguments, context) -> {
                var response = context.requestParams().at("/inputResponses/user_context");
                if ("accept".equals(response.path("action").asText())) {
                    ObjectNode result = object("""
                            {"messages":[{"role":"user","content":{"type":"text","text":"placeholder"}}]}
                            """);
                    result.at("/messages/0/content").asObject().put(
                            "text", "Prompt context: " + response.at("/content/context").asText());
                    return result;
                }
                return object("""
                        {"resultType":"input_required","inputRequests":{"user_context":{
                          "method":"elicitation/create","params":{
                            "message":"What context should the prompt use?",
                            "requestedSchema":{"type":"object","properties":{"context":{"type":"string"}},
                                               "required":["context"]}}}}}
                        """);
            });
            var catalog = new McpCatalogModule(List.of(), List.of(), List.of(prompt), List.of());
            var interactions = new McpInteractions(new McpInteractionStore(8, 8), executor);
            var engine = new McpEngine(object("{\"name\":\"test\",\"version\":\"1\"}"),
                    List.of(), McpAuthorization.permitAll(), List.of(catalog, interactions));

            ObjectNode first = engine.dispatch(engine.readRequest(promptWire("first", null)), context("red"));
            String requestState = first.at("/result/requestState").asText();
            ObjectNode second = engine.dispatch(engine.readRequest(promptWire("second", requestState)), context("red"));

            assertThat(first.at("/result/resultType").asText()).isEqualTo("input_required");
            assertThat(requestState).isNotBlank();
            assertThat(second.at("/result/resultType").asText()).isEqualTo("complete");
            assertThat(second.at("/result/messages/0/content/text").asText())
                    .isEqualTo("Prompt context: test context");
        } finally {
            executor.shutdownNow();
        }
    }

    private static McpResource resource(String name, String uri) {
        return new McpResource(object("{\"name\":\"" + name + "\",\"uri\":\"" + uri + "\"}"),
                (requested, context) -> object("{\"contents\":[{\"uri\":\"" + uri + "\",\"text\":\"ok\"}]}"));
    }

    private static McpResourceTemplate template(String uriTemplate) {
        return new McpResourceTemplate(
                object("{\"name\":\"template\",\"uriTemplate\":\"" + uriTemplate + "\"}"),
                uri -> uri.startsWith("test://tenant/"),
                (uri, context) -> object("{\"contents\":[{\"uri\":\"" + uri + "\",\"text\":\"ok\"}]}"));
    }

    private static McpPrompt prompt(String name, boolean required) {
        String descriptor = required
                ? "{\"name\":\"" + name + "\",\"arguments\":[{\"name\":\"value\",\"required\":true}]}"
                : "{\"name\":\"" + name + "\"}";
        return new McpPrompt(object(descriptor), (arguments, context) -> {
            ObjectNode result = object("""
                    {"messages":[{"role":"user","content":{"type":"text","text":"placeholder"}}]}
                    """);
            result.withArray("messages").get(0).path("content").asObject()
                    .put("text", arguments.path("value").asText("ok"));
            return result;
        });
    }

    private static ObjectNode execute(McpCatalogModule module, String name, ObjectNode params, McpContext context)
            throws Exception {
        McpMethod method = module.methods().stream().filter(candidate -> candidate.name().equals(name)).findFirst().orElseThrow();
        return method.handler().execute(params, context);
    }

    private static McpContext context(String tenant) {
        return new McpContext("subject", tenant, Set.of("read"), Map.of(),
                Instant.now().plus(1, ChronoUnit.MINUTES), () -> false);
    }

    private static McpAuthorization visibleOnly(String allowedMethod) {
        return new McpAuthorization() {
            @Override public void authorize(McpContext context, String method, String name) {}
            @Override public boolean visible(McpContext context, String name) { return false; }
            @Override public boolean visible(McpContext context, String method, String name) {
                return allowedMethod.equals(method);
            }
        };
    }

    private static ObjectNode dispatch(McpEngine engine, String method, McpAuthorization authorization) {
        return engine.dispatch(engine.readRequest(wire(method, method, "")), context("red"), authorization);
    }

    private static ObjectNode object(String json) {
        try {
            return (ObjectNode) JSON.readTree(json);
        } catch (Exception invalid) {
            throw new IllegalArgumentException(invalid);
        }
    }

    private static byte[] wire(String id, String method, String fields) {
        return ("{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"method\":\"" + method
                + "\",\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                + "\"io.modelcontextprotocol/clientCapabilities\":{}}" + fields + "}}").getBytes();
    }

    private static byte[] promptWire(String id, String requestState) {
        ObjectNode metadata = object("""
                {"io.modelcontextprotocol/protocolVersion":"2026-07-28",
                 "io.modelcontextprotocol/clientCapabilities":{"elicitation":{}}}
                """);
        ObjectNode params = JSON.createObjectNode()
                .put("name", "test_input_required_result_prompt")
                .set("_meta", metadata);
        if (requestState != null) {
            params.put("requestState", requestState).putObject("inputResponses")
                    .putObject("user_context").put("action", "accept")
                    .putObject("content").put("context", "test context");
        }
        ObjectNode request = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", id)
                .put("method", "prompts/get").set("params", params);
        return request.toString().getBytes(StandardCharsets.UTF_8);
    }
}
