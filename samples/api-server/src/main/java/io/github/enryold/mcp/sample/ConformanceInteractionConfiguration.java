package io.github.enryold.mcp.sample;

import io.github.enryold.mcp.McpContext;
import io.github.enryold.mcp.McpInteractionStore;
import io.github.enryold.mcp.McpInteractions;
import io.github.enryold.mcp.McpProtocolException;
import io.github.enryold.mcp.McpTool;
import io.github.enryold.mcp.ToolResult;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Official MRTR and Tasks fixtures, isolated from the ordinary sample profile. */
@Configuration(proxyBeanMethods = false)
@Profile("conformance")
class ConformanceInteractionConfiguration {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Bean(destroyMethod = "shutdownNow")
    ExecutorService conformanceInteractionExecutor() {
        return new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64),
                Thread.ofVirtual().name("mcp-task-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    McpInteractions conformanceInteractions(ExecutorService conformanceInteractionExecutor) {
        return new McpInteractions(new McpInteractionStore(256, 256), conformanceInteractionExecutor);
    }

    @Bean McpTool inputRequiredElicitation() {
        return tool("test_input_required_result_elicitation", "forbidden", (arguments, context) -> {
            ObjectNode response = response(context, "user_name");
            return response == null ? ToolResult.inputRequired(requests("user_name", elicit("What is your name?", "name")))
                    : text("Hello, " + response.path("content").path("name").asString() + "!");
        });
    }

    @Bean McpTool inputRequiredSampling() {
        return tool("test_input_required_result_sampling", "forbidden", (arguments, context) -> {
            ObjectNode response = response(context, "sample_request");
            return response == null ? ToolResult.inputRequired(requests("sample_request",
                    sampling("What is the capital of France?", 100)))
                    : text("Sampling result: " + response.path("content").path("text").asString("no response"));
        });
    }

    @Bean McpTool inputRequiredRoots() {
        return tool("test_input_required_result_list_roots", "forbidden", (arguments, context) -> {
            ObjectNode response = response(context, "roots_request");
            return response == null ? ToolResult.inputRequired(requests("roots_request", roots()))
                    : text("Found " + response.path("roots").size() + " root(s)");
        });
    }

    @Bean McpTool inputRequiredState() {
        return tool("test_input_required_result_request_state", "forbidden", (arguments, context) -> {
            if (responses(context).has("confirm")) {
                return text(context.requestParams().path("requestState").asString().equals("state-ok")
                        ? "state-ok: requestState validated" : "state-invalid");
            }
            return ToolResult.inputRequired(requests("confirm", elicit("Please confirm", "ok", "boolean")), "state-ok");
        });
    }

    @Bean McpTool inputRequiredMultiple() {
        return tool("test_input_required_result_multiple_inputs", "forbidden", (arguments, context) -> {
            ObjectNode responses = responses(context);
            if (responses.has("user_name") && responses.has("greeting") && responses.has("client_roots")) {
                String name = responses.path("user_name").path("content").path("name").asString();
                String greeting = responses.path("greeting").path("content").path("text").asString("Hello there!");
                int roots = responses.path("client_roots").path("roots").size();
                return text("Name: " + name + "; Greeting: " + greeting + "; Roots: " + roots);
            }
            ObjectNode pending = JSON.createObjectNode();
            if (!responses.has("user_name")) pending.set("user_name", elicit("What is your name?", "name"));
            if (!responses.has("greeting")) pending.set("greeting", sampling("Generate a greeting", 50));
            if (!responses.has("client_roots")) pending.set("client_roots", roots());
            return ToolResult.inputRequired(pending, "multiple-inputs");
        });
    }

    @Bean McpTool inputRequiredMultiRound() {
        return tool("test_input_required_result_multi_round", "forbidden", (arguments, context) -> {
            ObjectNode responses = responses(context);
            String state = context.requestParams().path("requestState").asString("");
            if (state.startsWith("round-2:") && responses.has("step2")) {
                String color = responses.path("step2").path("content").path("color").asString();
                return text("Multi-round complete for " + state.substring("round-2:".length())
                        + " who likes " + color);
            }
            if ("round-1".equals(state) && responses.has("step1")) {
                String name = responses.path("step1").path("content").path("name").asString("friend");
                return ToolResult.inputRequired(requests("step2", elicit("Step 2: What is your favorite color?", "color")),
                        "round-2:" + name);
            }
            return ToolResult.inputRequired(requests("step1", elicit("Step 1: What is your name?", "name")), "round-1");
        });
    }

    @Bean McpTool inputRequiredTamper() {
        return tool("test_input_required_result_tampered_state", "forbidden", (arguments, context) ->
                completeOrInput(context, "confirm", elicit("Please confirm", "ok", "boolean"),
                        "integrity-ok: state verified"));
    }

    @Bean McpTool inputRequiredCapabilities() {
        return tool("test_input_required_result_capabilities", "forbidden", (arguments, context) -> {
            ObjectNode responses = responses(context);
            if (!responses.isEmpty()) return text("capabilities-ok: received "
                    + String.join(",", responses.propertyNames()));
            JsonNode capabilities = context.requestMetadata().path("io.modelcontextprotocol/clientCapabilities");
            ObjectNode pending = JSON.createObjectNode();
            if (capabilities.path("elicitation").isObject()) {
                pending.set("elicit_input", elicit("Elicitation input", "value"));
            }
            if (capabilities.path("sampling").isObject()) {
                pending.set("sample_input", sampling("Sample request", 50));
            }
            return pending.isEmpty() ? text("No supported capabilities declared")
                    : ToolResult.inputRequired(pending, "capabilities");
        });
    }

    @Bean McpTool greet() {
        return tool("greet", "forbidden", (arguments, context) -> text("Hello, " + arguments.path("name").asString() + "!"),
                object("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"],\"additionalProperties\":false}"));
    }

    @Bean McpTool slowCompute() {
        return tool("slow_compute", "optional", (arguments, context) -> ToolResult.deferred(executionContext -> {
            Thread.sleep(arguments.path("seconds").asLong() * 1_000L);
            return text("Computed " + arguments.path("label").asString("result"));
        }), object("{\"type\":\"object\",\"properties\":{\"seconds\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":60},\"label\":{\"type\":\"string\"}},\"required\":[\"seconds\"],\"additionalProperties\":false}"));
    }

    @Bean McpTool failingJob() {
        return tool("failing_job", "required", (arguments, context) -> ToolResult.deferred(executionContext -> {
            Thread.sleep(50);
            return ToolResult.error(content("intentional tool failure"));
        }));
    }

    @Bean McpTool protocolErrorJob() {
        return tool("protocol_error_job", "optional", (arguments, context) -> ToolResult.deferred(executionContext -> {
            throw new McpProtocolException(200, -32603, "Intentional protocol failure", null,
                    executionContext.requestId());
        }));
    }

    @Bean McpTool taskNotificationSuppression() {
        McpTool fixture = tool("test_task_notification_suppression", "required", (arguments, context) -> {
            var notifications = io.github.enryold.mcp.McpNotifications.forRequest(context);
            notifications.progress(1, null, null);
            notifications.log("info", "conformance", JSON.stringNode("initial task diagnostic"));
            return ToolResult.deferred(execution -> text("task complete"));
        });
        return new McpTool(fixture.descriptor(), fixture.handler(), null, true);
    }

    @Bean McpTool confirmDelete() {
        return tool("confirm_delete", "optional", (arguments, context) -> {
            if (responses(context).has("confirm")) return ToolResult.deferred(executionContext -> text("confirmed"));
            return ToolResult.deferred(executionContext -> ToolResult.inputRequired(
                    requests("confirm", elicit("Confirm deletion", "confirm", "boolean")), "confirm-delete"));
        }, object("{\"type\":\"object\",\"properties\":{\"filename\":{\"type\":\"string\"}},\"required\":[\"filename\"],\"additionalProperties\":false}"));
    }

    @Bean McpTool multiInput() {
        return tool("multi_input", "optional", (arguments, context) -> {
            ObjectNode responses = responses(context);
            if (responses.has("first") && responses.has("second")) {
                return ToolResult.deferred(executionContext -> text("both-complete"));
            }
            return ToolResult.deferred(executionContext -> ToolResult.inputRequired(JSON.createObjectNode()
                    .set("first", elicit("First input", "name"))
                    .set("second", elicit("Second input", "name")), "multi-input"));
        });
    }

    @Bean McpTool mrtrThenTask() {
        return tool("test_tool_with_task", "required", (arguments, context) -> {
            ObjectNode responses = responses(context);
            if (!responses.has("user_name")) {
                return ToolResult.inputRequired(requests("user_name", elicit("What is your name?", "name")), "task-name");
            }
            String name = responses.path("user_name").path("content").path("name").asString("friend");
            return ToolResult.deferred(executionContext -> text("Hello, " + name + " from task"));
        });
    }

    private static ToolResult completeOrInput(McpContext context, String key, ObjectNode request, String completed) {
        return responses(context).has(key) ? text(completed) : ToolResult.inputRequired(requests(key, request), completed);
    }

    private static McpTool tool(String name, String taskSupport, McpTool.Handler handler) {
        return tool(name, taskSupport, handler, object("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"));
    }

    private static McpTool tool(String name, String taskSupport, McpTool.Handler handler, ObjectNode inputSchema) {
        ObjectNode descriptor = JSON.createObjectNode().put("name", name).put("description", name);
        descriptor.set("inputSchema", inputSchema);
        descriptor.putObject("execution").put("taskSupport", taskSupport);
        return new McpTool(descriptor, handler);
    }

    private static ObjectNode responses(McpContext context) {
        JsonNode value = context.requestParams().get("inputResponses");
        return value instanceof ObjectNode object ? object : JSON.createObjectNode();
    }

    private static ObjectNode requests(String key, ObjectNode request) {
        return JSON.createObjectNode().set(key, request);
    }

    private static ObjectNode elicit(String message, String property) {
        return elicit(message, property, "string");
    }

    private static ObjectNode elicit(String message, String property, String type) {
        ObjectNode request = JSON.createObjectNode().put("method", "elicitation/create");
        ObjectNode params = request.putObject("params").put("message", message);
        ObjectNode schema = params.putObject("requestedSchema").put("type", "object");
        schema.putObject("properties").putObject(property).put("type", type);
        schema.putArray("required").add(property);
        return request;
    }

    private static ObjectNode sampling(String prompt, int maxTokens) {
        ObjectNode request = JSON.createObjectNode().put("method", "sampling/createMessage");
        ObjectNode params = request.putObject("params").put("maxTokens", maxTokens);
        params.putArray("messages").add(JSON.createObjectNode().put("role", "user")
                .set("content", JSON.createObjectNode().put("type", "text").put("text", prompt)));
        return request;
    }

    private static ObjectNode roots() {
        return JSON.createObjectNode().put("method", "roots/list").set("params", JSON.createObjectNode());
    }

    private static ObjectNode response(McpContext context, String key) {
        JsonNode value = responses(context).get(key);
        return value instanceof ObjectNode object ? object : null;
    }

    private static ToolResult text(String value) {
        return ToolResult.success(content(value));
    }

    private static ArrayNode content(String value) {
        return JSON.createArrayNode().add(JSON.createObjectNode().put("type", "text").put("text", value));
    }

    private static ObjectNode object(String value) {
        try {
            return (ObjectNode) JSON.readTree(value);
        } catch (Exception error) {
            throw new IllegalArgumentException(error);
        }
    }
}
