package io.github.enryold.mcp.sample;

import io.github.enryold.mcp.McpContext;
import io.github.enryold.mcp.McpEvents;
import io.github.enryold.mcp.McpNotifications;
import io.github.enryold.mcp.McpTool;
import io.github.enryold.mcp.ToolResult;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Deterministic progress, logging and subscription fixtures for the conformance profile. */
@Configuration(proxyBeanMethods = false)
@Profile("conformance")
class ConformanceStreamingConfiguration {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Bean(destroyMethod = "close")
    McpEvents conformanceEvents() {
        return new McpEvents(Set.of(
                McpEvents.Kind.TOOLS_LIST_CHANGED,
                McpEvents.Kind.PROMPTS_LIST_CHANGED,
                McpEvents.Kind.RESOURCES_LIST_CHANGED,
                McpEvents.Kind.RESOURCE_UPDATED), 64);
    }

    @Bean
    McpTool conformanceProgressTool() {
        return new McpTool(descriptor("test_tool_with_progress", "Emit deterministic progress and requested logs."),
                (arguments, context) -> {
                    McpNotifications notifications = McpNotifications.forRequest(context);
                    notifications.log("info", "conformance", JSON.valueToTree("Progress tool started."));
                    if (context.requestMetadata().has("progressToken")) notifications.progress(0, 100d, null);
                    pause(context);
                    if (context.requestMetadata().has("progressToken")) notifications.progress(50, 100d, null);
                    pause(context);
                    if (context.requestMetadata().has("progressToken")) notifications.progress(100, 100d, null);
                    return ToolResult.success(text("Progress tool completed."));
                }, null, true);
    }

    @Bean
    McpTool conformanceLoggingTool() {
        return new McpTool(descriptor("test_logging_tool", "Emit a log only when the request selects a log level."),
                (arguments, context) -> {
                    McpNotifications.forRequest(context)
                            .log("info", "conformance", JSON.valueToTree("Requested diagnostic log."));
                    return ToolResult.success(text("Logging diagnostic completed."));
                }, null, true);
    }

    @Bean
    McpTool conformanceStreamingElicitationTool() {
        ObjectNode required = JSON.createObjectNode();
        required.set("elicitation", JSON.createObjectNode());
        return new McpTool(descriptor("test_streaming_elicitation", "Return an elicitation request over SSE."),
                (arguments, context) -> ToolResult.inputRequired(elicitationRequests()), required, true);
    }

    @Bean
    McpTool conformanceTriggerToolChange(McpEvents events) {
        return new McpTool(descriptor("test_trigger_tool_change", "Publish one identity-scoped tool-list change."),
                (arguments, context) -> {
                    events.toolsChanged(context);
                    return ToolResult.success(text("Tool change published."));
                });
    }

    @Bean
    McpTool conformanceTriggerPromptChange(McpEvents events) {
        return new McpTool(descriptor("test_trigger_prompt_change", "Publish one identity-scoped prompt-list change."),
                (arguments, context) -> {
                    events.promptsChanged(context);
                    return ToolResult.success(text("Prompt change published."));
                });
    }

    @Bean
    McpTool conformanceTriggerResourceChange(McpEvents events) {
        return new McpTool(descriptor("test_trigger_resource_change", "Publish one identity-scoped resource-list change."),
                (arguments, context) -> {
                    events.resourcesChanged(context);
                    return ToolResult.success(text("Resource change published."));
                });
    }

    @Bean
    McpTool conformanceTriggerResourceUpdate(McpEvents events) {
        return new McpTool(descriptor("test_trigger_resource_update", "Publish one identity-scoped resource update."),
                (arguments, context) -> {
                    events.resourceUpdated(context, "test://static-text");
                    return ToolResult.success(text("Resource update published."));
                });
    }

    private static void pause(McpContext context) throws InterruptedException {
        if (context.isCancelled()) throw new InterruptedException("request cancelled");
        Thread.sleep(50);
    }

    private static ObjectNode descriptor(String name, String description) {
        ObjectNode descriptor = JSON.createObjectNode().put("name", name).put("description", description);
        ObjectNode inputSchema = JSON.createObjectNode().put("type", "object").put("additionalProperties", false);
        inputSchema.set("properties", JSON.createObjectNode());
        descriptor.set("inputSchema", inputSchema);
        return descriptor;
    }

    private static ObjectNode elicitationRequests() {
        ObjectNode request = JSON.createObjectNode().put("method", "elicitation/create");
        ObjectNode params = request.putObject("params")
                .put("mode", "form")
                .put("message", "Provide a value for the streaming diagnostic.");
        ObjectNode schema = params.putObject("requestedSchema").put("type", "object");
        schema.putObject("properties").putObject("value").put("type", "string");
        schema.putArray("required").add("value");
        ObjectNode requests = JSON.createObjectNode();
        requests.set("elicitation", request);
        return requests;
    }

    private static ArrayNode text(String value) {
        return JSON.createArrayNode().add(JSON.createObjectNode().put("type", "text").put("text", value));
    }
}
