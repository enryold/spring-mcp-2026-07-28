package io.github.enryold.mcp.sample;

import io.github.enryold.mcp.McpTool;
import io.github.enryold.mcp.ToolResult;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Official conformance fixtures, isolated from the ordinary sample profile. */
@Configuration(proxyBeanMethods = false)
@Profile("conformance")
class ConformanceMcpConfiguration {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PNG_1X1 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl3fWQAAAAASUVORK5CYII=";
    private static final String EMPTY_WAV =
            "UklGRiQAAABXQVZFZm10IBAAAAABAAEARKwAAIhYAQACABAAZGF0YQAAAAA=";

    @Bean
    McpTool conformanceSimpleTextTool() {
        return tool("test_simple_text", "Return the official conformance text fixture.", emptySchema(),
                (arguments, context) -> ToolResult.success(text("This is a simple text response for testing.")));
    }

    @Bean
    McpTool conformanceStructuredContentTool() {
        ObjectNode schema = object("""
                {"type":"object","properties":{"value":{}},"required":["value"],"additionalProperties":false}
                """);
        return tool("test_structured_content", "Preserve any structured JSON value, including explicit null.", schema,
                (arguments, context) -> ToolResult.success(text("Structured value."), arguments.get("value")));
    }

    @Bean
    McpTool conformanceEmbeddedBinaryTool() {
        return tool("test_embedded_binary", "Return a binary embedded resource block.", emptySchema(),
                (arguments, context) -> {
                    ObjectNode content = JSON.createObjectNode().put("type", "resource");
                    content.putObject("resource").put("uri", "test://embedded-binary")
                            .put("mimeType", "application/octet-stream").put("blob", "aGVsbG8=");
                    return ToolResult.success(JSON.createArrayNode().add(content));
                });
    }

    @Bean
    McpTool conformanceImageTool() {
        return tool("test_image_content", "Return a minimal PNG image block.", emptySchema(),
                (arguments, context) -> ToolResult.success(JSON.createArrayNode().add(JSON.createObjectNode()
                        .put("type", "image").put("data", PNG_1X1).put("mimeType", "image/png"))));
    }

    @Bean
    McpTool conformanceAudioTool() {
        return tool("test_audio_content", "Return a minimal WAV audio block.", emptySchema(),
                (arguments, context) -> ToolResult.success(JSON.createArrayNode().add(JSON.createObjectNode()
                        .put("type", "audio").put("data", EMPTY_WAV).put("mimeType", "audio/wav"))));
    }

    @Bean
    McpTool conformanceEmbeddedResourceTool() {
        return tool("test_embedded_resource", "Return the official embedded resource fixture.", emptySchema(),
                (arguments, context) -> ToolResult.success(resource(
                        "test://embedded-resource", "text/plain", "This is an embedded resource content.")));
    }

    @Bean
    McpTool conformanceResourceLinkTool() {
        return tool("test_resource_link", "Return the official resource-link content fixture.", emptySchema(),
                (arguments, context) -> ToolResult.success(JSON.createArrayNode().add(JSON.createObjectNode()
                        .put("type", "resource_link")
                        .put("uri", "test://resource-link")
                        .put("name", "fixture"))));
    }

    @Bean
    McpTool conformanceMixedContentTool() {
        return tool("test_multiple_content_types", "Return text, image, and embedded resource blocks together.",
                emptySchema(), (arguments, context) -> {
                    ArrayNode content = text("Multiple content types test:");
                    content.add(JSON.createObjectNode()
                            .put("type", "image").put("data", PNG_1X1).put("mimeType", "image/png"));
                    content.add(resourceBlock("test://mixed-content-resource", "application/json",
                            "{\"test\":\"data\",\"value\":123}"));
                    return ToolResult.success(content);
                });
    }

    @Bean
    McpTool conformanceErrorTool() {
        return tool("test_error_handling", "Return an intentional tool execution error result.", emptySchema(),
                (arguments, context) -> ToolResult.error(
                        text("This tool intentionally returns an error for testing")));
    }

    @Bean
    McpTool conformanceMissingCapabilityTool() {
        return tool("test_missing_capability", "Require sampling for the stateless capability diagnostic.",
                emptySchema(),
                (arguments, context) -> ToolResult.success(text("Sampling capability declared.")),
                object("{\"sampling\":{}}"));
    }

    @Bean
    McpTool conformanceRequiredCapabilityTool() {
        return tool("test_required_capability", "Complete only when the request declares sampling.",
                emptySchema(),
                (arguments, context) -> ToolResult.success(text("Sampling capability declared.")),
                object("{\"sampling\":{}}"));
    }

    @Bean
    McpTool conformanceJsonSchemaTool() {
        ObjectNode schema = object("""
                {
                  "$schema":"https://json-schema.org/draft/2020-12/schema",
                  "type":"object",
                  "$defs":{"address":{"$anchor":"addressDef","type":"object","properties":{
                    "street":{"type":"string"},"city":{"type":"string"}
                  }}},
                  "properties":{
                    "name":{"type":"string"},
                    "address":{"$ref":"#/$defs/address"},
                    "contactMethod":{"type":"string","enum":["phone","email"]},
                    "phone":{"type":"string"},
                    "email":{"type":"string"}
                  },
                  "allOf":[{"anyOf":[{"required":["phone"]},{"required":["email"]}]}],
                  "if":{"properties":{"contactMethod":{"const":"phone"}},"required":["contactMethod"]},
                  "then":{"required":["phone"]},
                  "else":{"required":["email"]},
                  "additionalProperties":false
                }
                """);
        return tool("json_schema_2020_12_tool", "Tool with JSON Schema 2020-12 features", schema,
                (arguments, context) -> ToolResult.success(text("ok")));
    }

    @Bean
    McpTool conformanceBoundedPatternTool() {
        ObjectNode schema = object("""
                {
                  "$schema":"https://json-schema.org/draft/2020-12/schema",
                  "type":"object",
                  "properties":{"value":{"type":"string","pattern":"^(a+)+$"}},
                  "required":["value"],
                  "additionalProperties":false
                }
                """);
        return tool("test_bounded_pattern", "Exercise bounded JSON Schema pattern validation.", schema,
                (arguments, context) -> ToolResult.success(text("Pattern accepted.")));
    }

    @Bean
    McpTool conformanceCustomHeaderTool() {
        ObjectNode schema = object("""
                {
                  "$schema":"https://json-schema.org/draft/2020-12/schema",
                  "type":"object",
                  "properties":{"value":{"type":"string","x-mcp-header":"Conformance-Value"}},
                  "required":["value"],
                  "additionalProperties":false
                }
                """);
        return tool("test_custom_header", "Mirror a string argument into an MCP parameter header.", schema,
                (arguments, context) -> ToolResult.success(text("Custom header accepted.")));
    }

    @Bean
    McpTool conformanceOutputViolationTool() {
        ObjectNode outputSchema = object("""
                {
                  "type":"object",
                  "properties":{"value":{"type":"string"}},
                  "required":["value"],
                  "additionalProperties":false
                }
                """);
        return tool("test_output_violation", "Deliberately violate the declared output schema.",
                emptySchema(), outputSchema,
                (arguments, context) -> ToolResult.success(text("invalid output"), JSON.createObjectNode()));
    }

    private static McpTool tool(String name, String description, ObjectNode inputSchema, McpTool.Handler handler) {
        return tool(name, description, inputSchema, handler, null);
    }

    private static McpTool tool(
            String name,
            String description,
            ObjectNode inputSchema,
            McpTool.Handler handler,
            ObjectNode requiredClientCapabilities) {
        return tool(name, description, inputSchema, null, handler, requiredClientCapabilities);
    }

    private static McpTool tool(
            String name,
            String description,
            ObjectNode inputSchema,
            ObjectNode outputSchema,
            McpTool.Handler handler) {
        return tool(name, description, inputSchema, outputSchema, handler, null);
    }

    private static McpTool tool(
            String name,
            String description,
            ObjectNode inputSchema,
            ObjectNode outputSchema,
            McpTool.Handler handler,
            ObjectNode requiredClientCapabilities) {
        ObjectNode descriptor = JSON.createObjectNode()
                .put("name", name)
                .put("description", description);
        descriptor.set("inputSchema", inputSchema);
        if (outputSchema != null) descriptor.set("outputSchema", outputSchema);
        return new McpTool(descriptor, handler, requiredClientCapabilities);
    }

    private static ObjectNode emptySchema() {
        return object("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}");
    }

    private static ArrayNode text(String value) {
        return JSON.createArrayNode().add(JSON.createObjectNode().put("type", "text").put("text", value));
    }

    private static ArrayNode resource(String uri, String mimeType, String value) {
        return JSON.createArrayNode().add(resourceBlock(uri, mimeType, value));
    }

    private static ObjectNode resourceBlock(String uri, String mimeType, String value) {
        ObjectNode block = JSON.createObjectNode().put("type", "resource");
        block.putObject("resource").put("uri", uri).put("mimeType", mimeType).put("text", value);
        return block;
    }

    private static ObjectNode object(String json) {
        try {
            JsonNode value = JSON.readTree(json);
            if (value instanceof ObjectNode object) return object;
            throw new IllegalArgumentException("Expected JSON object");
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid conformance fixture JSON", invalid);
        }
    }
}
