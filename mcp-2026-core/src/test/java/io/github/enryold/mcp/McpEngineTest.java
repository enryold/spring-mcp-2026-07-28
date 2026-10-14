package io.github.enryold.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class McpEngineTest {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final ObjectNode SERVER_INFO = object("""
            {"name":"spring-mcp-sample","version":"0.1.0"}
            """);

    @Test
    void parsesAndDefensivelyCopiesAValidRequest() {
        var engine = engine(List.of(echoTool()), McpAuthorization.permitAll());

        McpRequest request = engine.readRequest(wire(7, "tools/list", ""));
        request.params().put("cursor", "changed");
        request.metadata().put("extension.example/value", "changed");

        assertThat(request.id().intValue()).isEqualTo(7);
        assertThat(request.method()).isEqualTo("tools/list");
        assertThat(request.params().has("cursor")).isFalse();
        assertThat(request.metadata().path("extension.example/value").asText()).isEqualTo("kept");
    }

    @Test
    void rejectsMalformedBatchAndInvalidEnvelopeWithoutInventingAnId() {
        var engine = engine(List.of(), McpAuthorization.permitAll());

        assertProtocolError(engine, "{".getBytes(), -32700, 400, false);
        assertProtocolError(engine, new byte[0], -32700, 400, false);
        assertProtocolError(engine, "  \n\t".getBytes(), -32700, 400, false);
        assertProtocolError(engine, "[]".getBytes(), -32600, 400, false);
        assertProtocolError(engine, "{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"tools/list\"}".getBytes(), -32600, 400, false);
    }

    @Test
    void acceptsOnlyStrictUtf8WithoutExecutingRequestsDecodedFromOtherUnicodeEncodings() {
        var calls = new AtomicInteger();
        var tool = tool("""
                {"name":"encoding","inputSchema":{"type":"object"}}
                """, (arguments, context) -> {
            calls.incrementAndGet();
            return ToolResult.success(text("ok"));
        });
        var engine = engine(List.of(tool), McpAuthorization.permitAll());
        String document = request("byte-id", "tools/call", ",\"name\":\"encoding\"");

        byte[] utf8Bom = prefix(new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf},
                document.getBytes(StandardCharsets.UTF_8));
        ObjectNode valid = engine.dispatch(engine.readRequest(utf8Bom), context());
        assertThat(valid.path("id").asText()).isEqualTo("byte-id");
        assertThat(calls).hasValue(1);

        List<byte[]> rejected = List.of(
                prefix(new byte[] {(byte) 0xff, (byte) 0xfe}, document.getBytes(StandardCharsets.UTF_16LE)),
                prefix(new byte[] {(byte) 0xfe, (byte) 0xff}, document.getBytes(StandardCharsets.UTF_16BE)),
                prefix(new byte[] {(byte) 0xff, (byte) 0xfe, 0, 0}, document.getBytes(Charset.forName("UTF-32LE"))),
                prefix(new byte[] {0, 0, (byte) 0xfe, (byte) 0xff}, document.getBytes(Charset.forName("UTF-32BE"))),
                document.getBytes(StandardCharsets.UTF_16LE),
                prefix(new byte[] {(byte) 0xc3, 0x28}, document.getBytes(StandardCharsets.UTF_8)));
        for (byte[] bytes : rejected) {
            var error = assertThrowsProtocol(() -> engine.dispatch(engine.readRequest(bytes), context()));
            assertThat(error.httpStatus()).isEqualTo(400);
            assertThat(error.code()).isEqualTo(-32700);
            assertThat(error.id()).isNull();
            assertThat(error.toResponse().has("id")).isFalse();
        }
        assertThat(calls).hasValue(1);
    }

    @Test
    void acceptsMathematicallyIntegralNumericIdsAndRejectsFractionalIds() {
        var engine = engine(List.of(), McpAuthorization.permitAll());

        for (String literal : List.of("1.0", "1e0")) {
            byte[] body = request(1, "tools/list", "")
                    .replace("\"id\":1", "\"id\":" + literal)
                    .getBytes();
            JsonNode parsedId = JSON.readTree(body).path("id");
            McpRequest request = engine.readRequest(body);
            ObjectNode response = engine.dispatch(request, context());

            assertThat(request.id()).isEqualTo(parsedId);
            assertThat(response.path("id")).isEqualTo(parsedId);
        }

        byte[] fractional = request(1, "tools/list", "")
                .replace("\"id\":1", "\"id\":1.5")
                .getBytes();
        assertProtocolError(engine, fractional, -32600, 400, false);
    }

    @Test
    void validatesThePerRequestEnvelopeAndVersion() {
        var engine = engine(List.of(), McpAuthorization.permitAll());

        var missingMeta = assertThrowsProtocol(() -> engine.readRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"method\":\"tools/list\",\"params\":{}}".getBytes()));
        assertThat(missingMeta.code()).isEqualTo(-32602);
        assertThat(missingMeta.httpStatus()).isEqualTo(400);
        assertThat(missingMeta.id().asText()).isEqualTo("a");

        byte[] unsupported = request(9, "tools/list", "")
                .replace("2026-07-28", "2099-01-01")
                .getBytes();
        McpRequest unsupportedRequest = engine.readRequest(unsupported);
        var error = assertThrowsProtocol(() -> engine.dispatch(unsupportedRequest, context()));
        assertThat(error.code()).isEqualTo(-32022);
        assertThat(error.httpStatus()).isEqualTo(400);
        assertThat(error.data()).isEqualTo(object("""
                {"requested":"2099-01-01","supported":["2026-07-28"]}
                """));
    }

    @Test
    void validatesKnownRequestMetadataTypesWhilePreservingExtensions() {
        var engine = engine(List.of(), McpAuthorization.permitAll());
        String invalidCapabilities = request(1, "tools/list", "")
                .replace("\"io.modelcontextprotocol/clientCapabilities\":{}",
                        "\"io.modelcontextprotocol/clientCapabilities\":{\"sampling\":false}");
        String invalidClientInfo = request(2, "tools/list", "")
                .replace("\"extension.example/value\":\"kept\"",
                        "\"io.modelcontextprotocol/clientInfo\":{\"name\":\"client\"}");

        assertThat(assertThrowsProtocol(() -> engine.readRequest(invalidCapabilities.getBytes())).code())
                .isEqualTo(-32602);
        assertThat(assertThrowsProtocol(() -> engine.readRequest(invalidClientInfo.getBytes())).code())
                .isEqualTo(-32602);

        String valid = request(3, "tools/list", "")
                .replace("\"extension.example/value\":\"kept\"",
                        "\"io.modelcontextprotocol/clientInfo\":{\"name\":\"client\",\"version\":\"1\"},\"extension.example/value\":{\"nested\":true}");
        McpRequest parsed = engine.readRequest(valid.getBytes());
        assertThat(parsed.metadata().at("/extension.example~1value/nested").asBoolean()).isTrue();
    }

    @Test
    void rejectsTrailingJsonValues() {
        var engine = engine(List.of(), McpAuthorization.permitAll());

        var error = assertThrowsProtocol(() -> engine.readRequest(
                (request(1, "tools/list", "") + " {}").getBytes()));

        assertThat(error.code()).isEqualTo(-32700);
        assertThat(error.httpStatus()).isEqualTo(400);
        assertThat(error.id()).isNull();
    }

    @Test
    void usesAConfigurableRequestByteLimit() {
        String large = request(1, "tools/list", ",\"padding\":\"" + "x".repeat(70_000) + "\"");

        assertThat(engine(List.of(), McpAuthorization.permitAll()).readRequest(large.getBytes()).id().intValue())
                .isEqualTo(1);

        var limited = new McpEngine(SERVER_INFO, List.of(), McpAuthorization.permitAll(), 64, 1_024);
        var error = assertThrowsProtocol(() -> limited.readRequest(large.getBytes()));
        assertThat(error.httpStatus()).isEqualTo(413);
        assertThat(error.code()).isEqualTo(-32000);
    }

    @Test
    void rejectsClientToServerNotificationsWithoutSynthesizingANullId() {
        var engine = engine(List.of(), McpAuthorization.permitAll());

        var error = assertThrowsProtocol(() -> engine.readRequest("""
                {"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":1}}
                """.getBytes()));

        assertThat(error.code()).isEqualTo(-32601);
        assertThat(error.toResponse().has("id")).isFalse();

        var malformed = assertThrowsProtocol(() -> engine.readRequest("{}".getBytes()));
        assertThat(malformed.code()).isEqualTo(-32600);
        assertThat(malformed.httpStatus()).isEqualTo(400);
    }

    @Test
    void appliesConfiguredJsonDepthLimit() {
        var engine = new McpEngine(SERVER_INFO, List.of(), McpAuthorization.permitAll(), 5);

        var error = assertThrowsProtocol(() -> engine.readRequest("{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":{}}}}}}".getBytes()));

        assertThat(error.code()).isEqualTo(-32700);
        assertThat(error.httpStatus()).isEqualTo(400);
    }

    @Test
    void validatesSchemaLocationsWhileRejectingActualRemoteReferencesAtStartup() {
        McpTool duplicate = echoTool();
        assertThatThrownBy(() -> engine(List.of(duplicate, duplicate), McpAuthorization.permitAll()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");

        assertThatThrownBy(() -> engine(List.of(tool("""
                {"name":"bad","inputSchema":{"type":"string"}}
                """, (arguments, context) -> ToolResult.success(text("never")))), McpAuthorization.permitAll()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputSchema");

        assertThatThrownBy(() -> engine(List.of(tool("""
                {"name":"bad-ref","inputSchema":{"type":"object","properties":{"x":{"$ref":"https://evil.invalid/schema"}}}}
                """, (arguments, context) -> ToolResult.success(text("never")))), McpAuthorization.permitAll()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remote");

        assertThatThrownBy(() -> engine(List.of(tool("""
                {"name":"bad-dynamic-ref","inputSchema":{"type":"object","properties":{"x":{"$dynamicRef":"https://evil.invalid/schema"}}}}
                """, (arguments, context) -> ToolResult.success(text("never")))), McpAuthorization.permitAll()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remote");

        assertThatThrownBy(() -> engine(List.of(tool("""
                {"name":"bad-relative-ref","inputSchema":{"type":"object","properties":{"x":{"$ref":"child"}}}}
                """, (arguments, context) -> ToolResult.success(text("never")))), McpAuthorization.permitAll()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remote");

        var calls = new AtomicInteger();
        var identifier = tool("""
                {"name":"http-id","inputSchema":{"$id":"https://schemas.example/tool","type":"object",
                  "$defs":{"label":{"type":"string"}},"properties":{"label":{"$ref":"#/$defs/label"}},"required":["label"]}}
                """, (arguments, context) -> {
                    calls.incrementAndGet();
                    return ToolResult.success(text("ok"));
                });
        var literalKeywords = tool("""
                {"name":"literal-keywords","inputSchema":{"type":"object","properties":{
                  "enumValue":{"enum":[{"$ref":"https://instance.example/enum"}]},
                  "constValue":{"const":{"$ref":"https://instance.example/const"}},
                  "defaultValue":{"default":{"$ref":"https://instance.example/default"}}
                }}}
                """, (arguments, context) -> ToolResult.success(text("ok")));
        var schemaEngine = engine(List.of(identifier, literalKeywords), McpAuthorization.permitAll());
        ObjectNode invalid = schemaEngine.dispatch(schemaEngine.readRequest(wire(
                1, "tools/call", ",\"name\":\"http-id\",\"arguments\":{\"label\":4}")), context());
        ObjectNode valid = schemaEngine.dispatch(schemaEngine.readRequest(wire(
                2, "tools/call", ",\"name\":\"http-id\",\"arguments\":{\"label\":\"ok\"}")), context());
        assertThat(invalid.at("/result/isError").asBoolean()).isTrue();
        assertThat(valid.at("/result/isError").isMissingNode()).isTrue();
        assertThat(calls).hasValue(1);

        assertThatThrownBy(() -> engine(List.of(tool("""
                {"name":"bad-header","inputSchema":{"type":"object","oneOf":[{"type":"object","properties":{"x":{"type":"string","x-mcp-header":"Trace Id"}}}]}}
                """, (arguments, context) -> ToolResult.success(text("never")))), McpAuthorization.permitAll()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("x-mcp-header");

        assertThatThrownBy(() -> engine(List.of(tool("""
                {"name":"duplicate-header","inputSchema":{"type":"object","properties":{"x":{"type":"string","x-mcp-header":"Trace-Id"},"y":{"type":"integer","x-mcp-header":"trace-id"}}}}
                """, (arguments, context) -> ToolResult.success(text("never")))), McpAuthorization.permitAll()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");

        ObjectNode deepSchema = JSON.createObjectNode().put("type", "object");
        ObjectNode cursor = deepSchema;
        for (int depth = 0; depth < 70; depth++) {
            ObjectNode child = JSON.createObjectNode();
            cursor.set("not", child);
            cursor = child;
        }
        ObjectNode deepDescriptor = JSON.createObjectNode().put("name", "too-deep").set("inputSchema", deepSchema);
        assertThatThrownBy(() -> engine(List.of(new McpTool(
                deepDescriptor, (arguments, context) -> ToolResult.success(text("never")))), McpAuthorization.permitAll()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema limits");

        assertThatThrownBy(() -> engine(List.of(tool("""
                {"name":"bad-schema-type","inputSchema":{"type":"object","properties":{"x":{"type":"bogus"}}}}
                """, (arguments, context) -> ToolResult.success(text("never")))), McpAuthorization.permitAll()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputSchema");

        assertThatThrownBy(() -> engine(List.of(tool("""
                {"name":"bad-schema-keyword","inputSchema":{"type":"object","properties":{"x":{"type":"number","minimum":"abc"}}}}
                """, (arguments, context) -> ToolResult.success(text("never")))), McpAuthorization.permitAll()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputSchema");
    }

    @Test
    void discoversAndListsVisibleToolsInDeterministicOrder() {
        McpAuthorization authorization = new McpAuthorization() {
            @Override
            public void authorize(McpContext context, String method, String toolName) {}

            @Override
            public boolean visible(McpContext context, String toolName) {
                return !toolName.equals("hidden");
            }
        };
        var engine = engine(List.of(namedTool("zeta"), namedTool("hidden"), namedTool("alpha")), authorization);

        ObjectNode discover = engine.dispatch(engine.readRequest(wire(1, "server/discover", "")), context());
        ObjectNode list = engine.dispatch(engine.readRequest(wire(2, "tools/list", "")), context());

        JsonNode discoverResult = discover.path("result");
        assertThat(discoverResult.path("resultType").asText()).isEqualTo("complete");
        assertThat(discoverResult.path("supportedVersions").get(0).asText()).isEqualTo("2026-07-28");
        assertThat(discoverResult.path("capabilities").path("tools").isObject()).isTrue();
        assertPrivateUncachedAndStamped(discoverResult);

        assertThat(list.at("/result/tools/0/name").asText()).isEqualTo("alpha");
        assertThat(list.at("/result/tools/1/name").asText()).isEqualTo("zeta");
        assertPrivateUncachedAndStamped(list.path("result"));
        assertConformsToPinnedDefinition(discover, "DiscoverResultResponse");
        assertConformsToPinnedDefinition(list, "ListToolsResultResponse");
    }

    @Test
    void dispatchesOneAuthorizedToolCallAndPreservesStructuredNull() {
        var calls = new AtomicInteger();
        McpTool tool = tool("""
                {"name":"fixture.echo","description":"Echo","inputSchema":{"type":"object","properties":{"value":{"type":"string"}},"required":["value"],"additionalProperties":false},"outputSchema":{"type":["object","null"]}}
                """, (arguments, context) -> {
                    calls.incrementAndGet();
                    return ToolResult.success(text(arguments.path("value").asText()), JSON.nullNode());
                });
        var engine = engine(List.of(tool), McpAuthorization.permitAll());

        ObjectNode response = engine.dispatch(engine.readRequest(wire(
                "call-1", "tools/call", ",\"name\":\"fixture.echo\",\"arguments\":{\"value\":\"héllo\"}")), context());

        assertThat(calls).hasValue(1);
        assertThat(response.at("/result/content/0/text").asText()).isEqualTo("héllo");
        assertThat(response.at("/result/structuredContent").isNull()).isTrue();
        assertThat(response.at("/result/resultType").asText()).isEqualTo("complete");
        assertThat(response.path("result").has("isError")).isFalse();
        assertConformsToPinnedDefinition(response, "CallToolResultResponse");
    }

    @Test
    void enforcesToolClientCapabilityPrerequisitesWithoutExposingMetadataToTheHandler() {
        var calls = new AtomicInteger();
        McpTool tool = new McpTool(object("""
                {"name":"sampling-dependent","inputSchema":{"type":"object"}}
                """), (arguments, context) -> {
                    calls.incrementAndGet();
                    return ToolResult.success(text("ok"));
                }, object("{\"sampling\":{}}"));
        var engine = engine(List.of(tool), McpAuthorization.permitAll());

        var missing = assertThrowsProtocol(() -> engine.dispatch(
                engine.readRequest(wire(1, "tools/call", ",\"name\":\"sampling-dependent\"")), context()));
        assertThat(missing.httpStatus()).isEqualTo(400);
        assertThat(missing.code()).isEqualTo(-32021);
        assertThat(missing.data()).isEqualTo(object("""
                {"requiredCapabilities":{"sampling":{}}}
                """));

        String capableWire = request(2, "tools/call", ",\"name\":\"sampling-dependent\"")
                .replace("\"io.modelcontextprotocol/clientCapabilities\":{}",
                        "\"io.modelcontextprotocol/clientCapabilities\":{\"sampling\":{}}");
        ObjectNode success = engine.dispatch(engine.readRequest(capableWire.getBytes()), context());
        assertThat(success.at("/result/content/0/text").asText()).isEqualTo("ok");
        assertThat(calls).hasValue(1);

        assertThatThrownBy(() -> engine(List.of(new McpTool(
                object("{\"name\":\"bad-capability\",\"inputSchema\":{\"type\":\"object\"}}"),
                (arguments, context) -> ToolResult.success(text("never")),
                object("{\"sampling\":false}"))), McpAuthorization.permitAll()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requiredClientCapabilities");
    }

    @Test
    void distinguishesAbsentStructuredContentFromExplicitNull() {
        var engine = engine(List.of(
                namedTool("absent"),
                tool("""
                        {"name":"explicit-null","inputSchema":{"type":"object"}}
                        """, (arguments, context) -> ToolResult.success(text("ok"), JSON.nullNode()))), McpAuthorization.permitAll());

        ObjectNode absent = engine.dispatch(engine.readRequest(wire(1, "tools/call", ",\"name\":\"absent\"")), context());
        ObjectNode explicitNull = engine.dispatch(engine.readRequest(wire(2, "tools/call", ",\"name\":\"explicit-null\"")), context());

        assertThat(absent.path("result").has("structuredContent")).isFalse();
        assertThat(explicitNull.path("result").has("structuredContent")).isTrue();
        assertThat(explicitNull.at("/result/structuredContent").isNull()).isTrue();
    }

    @Test
    void returnsAProtocolErrorForUnknownToolAndAToolErrorForSchemaInvalidArguments() {
        var calls = new AtomicInteger();
        McpTool tool = tool("""
                {"name":"fixture.echo","inputSchema":{"type":"object","properties":{"value":{"type":"string"}},"required":["value"]}}
                """, (arguments, context) -> {
                    calls.incrementAndGet();
                    return ToolResult.success(text("ok"));
                });
        var engine = engine(List.of(tool), McpAuthorization.permitAll());

        var unknown = assertThrowsProtocol(() -> engine.dispatch(
                engine.readRequest(wire(1, "tools/call", ",\"name\":\"missing\"")), context()));
        ObjectNode invalid = engine.dispatch(
                engine.readRequest(wire(2, "tools/call", ",\"name\":\"fixture.echo\",\"arguments\":{\"value\":4}")), context());

        assertThat(unknown.code()).isEqualTo(-32602);
        assertThat(unknown.httpStatus()).isEqualTo(200);
        assertThat(invalid.at("/result/isError").asBoolean()).isTrue();
        assertThat(invalid.at("/result/content/0/text").asText()).startsWith("Input validation error:");
        assertThat(calls).hasValue(0);
    }

    @Test
    void validatesNestedComposedSchemasWithoutCoercion() {
        var calls = new AtomicInteger();
        var engine = engine(List.of(tool("""
                {
                  "name":"nested",
                  "inputSchema":{
                    "type":"object",
                    "$defs":{"payload":{"type":"object","properties":{"count":{"type":"integer","minimum":-2,"maximum":2},"note":{"type":["string","null"]}},"required":["count"],"additionalProperties":false}},
                    "properties":{"payload":{"allOf":[{"$ref":"#/$defs/payload"}]}},
                    "required":["payload"],
                    "additionalProperties":false
                  }
                }
                """, (arguments, context) -> {
                    calls.incrementAndGet();
                    return ToolResult.success(text("ok"));
                })), McpAuthorization.permitAll());

        ObjectNode valid = engine.dispatch(engine.readRequest(wire(
                1, "tools/call", ",\"name\":\"nested\",\"arguments\":{\"payload\":{\"count\":2,\"note\":null}}")), context());
        ObjectNode fractional = engine.dispatch(engine.readRequest(wire(
                2, "tools/call", ",\"name\":\"nested\",\"arguments\":{\"payload\":{\"count\":1.5}}")), context());
        ObjectNode outOfRange = engine.dispatch(engine.readRequest(wire(
                3, "tools/call", ",\"name\":\"nested\",\"arguments\":{\"payload\":{\"count\":3}}")), context());

        assertThat(valid.at("/result/isError").isMissingNode()).isTrue();
        assertThat(fractional.at("/result/isError").asBoolean()).isTrue();
        assertThat(outOfRange.at("/result/isError").asBoolean()).isTrue();
        assertThat(calls).hasValue(1);
    }

    @Test
    void validatesSchemaPatternsWithBoundedRe2Matching() {
        var calls = new AtomicInteger();
        var engine = engine(List.of(
                tool("""
                        {"name":"bounded","inputSchema":{"type":"object","properties":{"value":{"type":"string","pattern":"(a+)+$"}},"required":["value"]}}
                        """, (arguments, context) -> {
                    calls.incrementAndGet();
                    return ToolResult.success(text("ok"));
                }),
                tool("""
                        {"name":"unicode","inputSchema":{"type":"object","properties":{"value":{"type":"string","pattern":"^[\\\\p{L}\\\\p{N}_-]+$"}},"required":["value"]}}
                        """, (arguments, context) -> {
                    calls.incrementAndGet();
                    return ToolResult.success(text("ok"));
                })), McpAuthorization.permitAll());

        assertTimeout(Duration.ofSeconds(5), () -> {
            ObjectNode adversarial = engine.dispatch(engine.readRequest(wire(
                    1, "tools/call", ",\"name\":\"bounded\",\"arguments\":{\"value\":\"" + "a".repeat(30_000) + "!\"}")), context());
            ObjectNode next = engine.dispatch(engine.readRequest(wire(
                    2, "tools/call", ",\"name\":\"bounded\",\"arguments\":{\"value\":\"aaaa\"}")), context());
            ObjectNode unicode = engine.dispatch(engine.readRequest(wire(
                    3, "tools/call", ",\"name\":\"unicode\",\"arguments\":{\"value\":\"Caffè_東京9\"}")), context());

            assertThat(adversarial.at("/result/isError").asBoolean()).isTrue();
            assertThat(next.at("/result/isError").isMissingNode()).isTrue();
            assertThat(unicode.at("/result/isError").isMissingNode()).isTrue();
        });
        assertThat(calls).hasValue(2);
    }

    @Test
    void rejectsUnsupportedOrOversizedSchemaPatternsAtStartup() {
        assertThatThrownBy(() -> engine(List.of(tool("""
                {"name":"lookahead","inputSchema":{"type":"object","properties":{"value":{"type":"string","pattern":"(?=a)a"}}}}
                """, (arguments, context) -> ToolResult.success(text("never")))), McpAuthorization.permitAll()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputSchema");

        assertThatThrownBy(() -> engine(List.of(tool("""
                {"name":"backreference","inputSchema":{"type":"object","patternProperties":{"(a)\\\\1":{"type":"string"}}}}
                """, (arguments, context) -> ToolResult.success(text("never")))), McpAuthorization.permitAll()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputSchema");

        ObjectNode oversizedSchema = object("{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}}}");
        ((ObjectNode) oversizedSchema.at("/properties/value")).put("pattern", "a".repeat(4_097));
        ObjectNode descriptor = JSON.createObjectNode().put("name", "oversized").set("inputSchema", oversizedSchema);
        assertThatThrownBy(() -> engine(List.of(new McpTool(
                descriptor, (arguments, context) -> ToolResult.success(text("never")))), McpAuthorization.permitAll()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputSchema");
    }

    @Test
    void expectedAndOutputValidationFailuresAreResultsButUnexpectedFailuresAreProtocolErrors() {
        var engine = engine(List.of(
                tool("""
                        {"name":"domain","inputSchema":{"type":"object"}}
                        """, (arguments, context) -> ToolResult.error(text("denied"))),
                tool("""
                        {"name":"domain-with-output","inputSchema":{"type":"object"},"outputSchema":{"type":"object","required":["value"]}}
                        """, (arguments, context) -> ToolResult.error(text("denied"))),
                tool("""
                        {"name":"boom","inputSchema":{"type":"object"}}
                        """, (arguments, context) -> { throw new IllegalStateException("secret-value"); }),
                tool("""
                        {"name":"bad-output","inputSchema":{"type":"object"},"outputSchema":{"type":"object","required":["value"]}}
                        """, (arguments, context) -> ToolResult.success(
                                text("private-output"), object("{}"), object("{\"private\":\"metadata\"}")))),
                McpAuthorization.permitAll());

        ObjectNode domain = engine.dispatch(engine.readRequest(wire(1, "tools/call", ",\"name\":\"domain\"")), context());
        assertThat(domain.at("/result/isError").asBoolean()).isTrue();
        ObjectNode domainWithOutput = engine.dispatch(
                engine.readRequest(wire(1, "tools/call", ",\"name\":\"domain-with-output\"")), context());
        assertThat(domainWithOutput.at("/result/isError").asBoolean()).isTrue();

        var error = assertThrowsProtocol(() -> engine.dispatch(
                engine.readRequest(wire(2, "tools/call", ",\"name\":\"boom\"")), context()));
        assertThat(error.code()).isEqualTo(-32603);
        assertThat(error.httpStatus()).isEqualTo(200);
        assertThat(error.getMessage()).doesNotContain("secret-value");
        assertThat(error.data().path("correlationId").asText()).isNotBlank();

        ObjectNode invalidOutput = engine.dispatch(
                engine.readRequest(wire(3, "tools/call", ",\"name\":\"bad-output\"")), context());
        assertThat(invalidOutput.at("/result/isError").asBoolean()).isTrue();
        assertThat(invalidOutput.at("/result/content/0/text").asText())
                .isEqualTo("Output validation error: Invalid structured content for tool bad-output");
        assertThat(invalidOutput.at("/result/structuredContent").isMissingNode()).isTrue();
        assertThat(invalidOutput.at("/result/_meta/private").isMissingNode()).isTrue();
    }

    @Test
    void validatesAllSupportedContentBlocks() {
        ArrayNode content = array("""
                [
                  {"type":"text","text":"x"},
                  {"type":"image","data":"AA==","mimeType":"image/png"},
                  {"type":"audio","data":"AA==","mimeType":"audio/wav"},
                  {"type":"resource","resource":{"uri":"https://example.test/a","text":"a"}},
                  {"type":"resource_link","uri":"https://example.test/a","name":"a"}
                ]
                """);
        var engine = engine(List.of(tool("""
                {"name":"content","inputSchema":{"type":"object"}}
                """, (arguments, context) -> ToolResult.success(content))), McpAuthorization.permitAll());

        ObjectNode response = engine.dispatch(engine.readRequest(wire(1, "tools/call", ",\"name\":\"content\"")), context());

        assertThat(response.at("/result/content")).hasSize(5);
        assertConformsToPinnedDefinition(response, "CallToolResultResponse");
    }

    @Test
    void followsThePinnedAnyOfSemanticsForEmbeddedResources() {
        ArrayNode content = array("""
                [{"type":"resource","resource":{"uri":"https://example.test/a","text":"a","blob":"YQ=="}}]
                """);
        var engine = engine(List.of(tool("""
                {"name":"resource","inputSchema":{"type":"object"}}
                """, (arguments, context) -> ToolResult.success(content))), McpAuthorization.permitAll());

        ObjectNode response = engine.dispatch(
                engine.readRequest(wire(1, "tools/call", ",\"name\":\"resource\"")), context());

        assertThat(response.at("/result/content/0/resource/text").asText()).isEqualTo("a");
        assertThat(response.at("/result/content/0/resource/blob").asText()).isEqualTo("YQ==");
        assertConformsToPinnedDefinition(response, "CallToolResultResponse");
    }

    @Test
    void enforcesAuthorizationAndDeadlineOnEveryDispatch() {
        var authorizations = new AtomicInteger();
        McpAuthorization denied = new McpAuthorization() {
            @Override
            public void authorize(McpContext context, String method, String toolName) {
                authorizations.incrementAndGet();
                throw new McpAccessException("Forbidden");
            }

            @Override
            public boolean visible(McpContext context, String toolName) {
                return false;
            }
        };
        var deniedEngine = engine(List.of(echoTool()), denied);
        var deniedError = assertThrowsProtocol(() -> deniedEngine.dispatch(
                deniedEngine.readRequest(wire(1, "server/discover", "")), context()));
        assertThat(deniedError.code()).isEqualTo(-32001);
        assertThat(deniedError.httpStatus()).isEqualTo(403);
        assertThat(authorizations).hasValue(1);

        var engine = engine(List.of(echoTool()), McpAuthorization.permitAll());
        var expired = new McpContext("alice", "red", Set.of("read"), Map.of("credential", "never-print"),
                Instant.EPOCH, () -> false);
        var timeout = assertThrowsProtocol(() -> engine.dispatch(
                engine.readRequest(wire(2, "tools/list", "")), expired));
        assertThat(timeout.httpStatus()).isEqualTo(408);
        assertThat(expired.toString()).doesNotContain("never-print");
    }

    @Test
    void explicitRequestAuthorizationCannotBeWeakenedByTheEnginePolicy() {
        var engine = engine(List.of(namedTool("visible"), namedTool("hidden")), McpAuthorization.permitAll());
        McpAuthorization requestPolicy = new McpAuthorization() {
            @Override
            public void authorize(McpContext context, String method, String toolName) {
                if ("server/discover".equals(method)) {
                    throw new McpAccessException("Forbidden");
                }
            }

            @Override
            public boolean visible(McpContext context, String toolName) {
                return "visible".equals(toolName);
            }
        };

        var denied = assertThrowsProtocol(() -> engine.dispatch(
                engine.readRequest(wire(1, "server/discover", "")), context(), requestPolicy));
        ObjectNode list = engine.dispatch(
                engine.readRequest(wire(2, "tools/list", "")), context(), requestPolicy);

        assertThat(denied.httpStatus()).isEqualTo(403);
        assertThat(list.at("/result/tools")).extracting(node -> node.path("name").asText())
                .containsExactly("visible");
    }

    @Test
    void mapsAuthorizationRateLimitsToAProtocolErrorWithRetryMetadata() {
        McpAuthorization limited = new McpAuthorization() {
            @Override
            public void authorize(McpContext context, String method, String toolName) {
                throw new McpRateLimitException(17);
            }

            @Override
            public boolean visible(McpContext context, String toolName) {
                return true;
            }
        };
        var engine = engine(List.of(), limited);

        var error = assertThrowsProtocol(() -> engine.dispatch(
                engine.readRequest(wire("limited", "server/discover", "")), context()));

        assertThat(error.httpStatus()).isEqualTo(429);
        assertThat(error.code()).isEqualTo(-32000);
        assertThat(error.getMessage()).isEqualTo("Rate limit exceeded");
        assertThat(error.data()).isEqualTo(object("{\"retryAfterSeconds\":17}"));
        assertThat(error.id().asText()).isEqualTo("limited");
        assertThatThrownBy(() -> new McpRateLimitException(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpRateLimitException(86_401)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exposesDefensiveDescriptorSnapshotsForTransportHeaderValidation() {
        var engine = engine(List.of(echoTool()), McpAuthorization.permitAll());

        ObjectNode descriptor = engine.descriptor("fixture.echo");
        descriptor.put("name", "mutated");

        assertThat(engine.descriptor("fixture.echo").path("name").asText()).isEqualTo("fixture.echo");
        assertThat(engine.descriptor("missing")).isNull();
    }

    @Test
    void returnedResponsesCannotMutateTheRegistryOrServerIdentity() {
        var engine = engine(List.of(echoTool()), McpAuthorization.permitAll());
        ObjectNode first = engine.dispatch(engine.readRequest(wire(1, "tools/list", "")), context());
        ((ObjectNode) first.at("/result/tools/0")).put("name", "mutated");
        ((ObjectNode) first.at("/result/_meta/io.modelcontextprotocol~1serverInfo")).put("name", "mutated");

        ObjectNode second = engine.dispatch(engine.readRequest(wire(2, "tools/list", "")), context());

        assertThat(second.at("/result/tools/0/name").asText()).isEqualTo("fixture.echo");
        assertThat(second.at("/result/_meta/io.modelcontextprotocol~1serverInfo/name").asText())
                .isEqualTo("spring-mcp-sample");
    }

    private static McpEngine engine(List<McpTool> tools, McpAuthorization authorization) {
        return new McpEngine(SERVER_INFO, tools, authorization);
    }

    private static McpTool echoTool() {
        return namedTool("fixture.echo");
    }

    private static McpTool namedTool(String name) {
        return tool("""
                {"name":"%s","inputSchema":{"type":"object"}}
                """.formatted(name), (arguments, context) -> ToolResult.success(text("ok")));
    }

    private static McpTool tool(String descriptor, McpTool.Handler handler) {
        return new McpTool(object(descriptor), handler);
    }

    private static McpContext context() {
        return new McpContext("alice", "red", Set.of("read"), Map.of(), Instant.MAX, () -> false);
    }

    private static String request(Object id, String method, String extraParams) {
        String jsonId = id instanceof Number ? id.toString() : "\"" + id + "\"";
        return """
                {"jsonrpc":"2.0","id":%s,"method":"%s","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{},"extension.example/value":"kept"}%s}}
                """.formatted(jsonId, method, extraParams);
    }

    private static byte[] wire(Object id, String method, String extraParams) {
        return request(id, method, extraParams).getBytes();
    }

    private static byte[] prefix(byte[] prefix, byte[] value) {
        byte[] combined = new byte[prefix.length + value.length];
        System.arraycopy(prefix, 0, combined, 0, prefix.length);
        System.arraycopy(value, 0, combined, prefix.length, value.length);
        return combined;
    }

    private static ArrayNode text(String value) {
        ArrayNode content = JSON.createArrayNode();
        content.add(JSON.createObjectNode().put("type", "text").put("text", value));
        return content;
    }

    private static ObjectNode object(String json) {
        try {
            return (ObjectNode) JSON.readTree(json);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static ArrayNode array(String json) {
        try {
            return (ArrayNode) JSON.readTree(json);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static McpProtocolException assertThrowsProtocol(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        try {
            call.call();
            throw new AssertionError("Expected McpProtocolException");
        } catch (McpProtocolException error) {
            return error;
        } catch (Throwable error) {
            throw new AssertionError(error);
        }
    }

    private static void assertProtocolError(McpEngine engine, byte[] input, int code, int status, boolean hasId) {
        var error = assertThrowsProtocol(() -> engine.readRequest(input));
        assertThat(error.code()).isEqualTo(code);
        assertThat(error.httpStatus()).isEqualTo(status);
        assertThat(error.toResponse().has("id")).isEqualTo(hasId);
        assertConformsToPinnedDefinition(error.toResponse(), "JSONRPCErrorResponse");
    }

    private static void assertPrivateUncachedAndStamped(JsonNode result) {
        assertThat(result.path("cacheScope").asText()).isEqualTo("private");
        assertThat(result.path("ttlMs").intValue()).isZero();
        assertThat(result.at("/_meta/io.modelcontextprotocol~1serverInfo")).isEqualTo(SERVER_INFO);
    }

    private static void assertConformsToPinnedDefinition(JsonNode value, String definition) {
        try {
            Path schemaPath = Path.of("..", "spec", "upstream", "schema.json");
            ObjectNode schema = (ObjectNode) JSON.readTree(schemaPath);
            schema.put("$ref", "#/$defs/" + definition);
            Schema validator = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(schema);
            assertThat(validator.validate(value)).isEmpty();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
